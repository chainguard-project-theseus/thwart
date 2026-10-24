package dev.tessera.compiler.oracle;

import dev.tessera.compiler.model.OutputContext;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * The context check (#323, design §5.1: "each value inside the node and context the compiler
 * chose"): for each slot of a case rendered with {@link Fingerprint} values, infer from the render
 * which escaper the compiler chose and check that the spec parser puts the value where that escaper
 * is correct. It catches a hole escaped for the wrong context even when no script runs, e.g.
 * {@code <svg><script>// <p>} moving a JSON value into HTML text.
 *
 * <p>Black box, no compiler API. The slots are the fingerprint markers in the render. The escaper
 * is identified twice, and both must agree:
 *
 * <ol>
 *   <li>from the render's bytes after each marker ({@link Fingerprint#classify} as written). This
 *       tells an escaped value from an unescaped one and {@code json} from {@code jsonAttr}, which
 *       look the same once parsed;
 *   <li>from the parsed tree, for every parser and scripting flag: each marker occurrence is
 *       located (element text of an HTML, raw-text, RCDATA or foreign element; an attribute value; a
 *       comment; a name) and the text from the marker on is classified as the parser left it
 *       (decoded or not). This tells TEXT from the entity-encoding family: only TEXT's {@code {{}
 *       becomes a comment node. The parser must keep comments.
 * </ol>
 *
 * A slot used more than once is classified against the union of its occurrences in the render. The
 * finding is {@link Finding.Kind#CONTEXT_MISMATCH} when:
 *
 * <ul>
 *   <li>no escaper writes the fingerprint that way, or the two classifications don't agree
 *       ("unclassified escaper", fail closed);
 *   <li>the location isn't where any of the classified contexts belongs ({@link #belongs}): TEXT in
 *       HTML flow or foreign element text (not SVG {@code script}/{@code style}); RCDATA in HTML
 *       {@code title}/{@code textarea}; ATTRIBUTE, URL, URL_LIST and RESOURCE_URL in an attribute
 *       that is not an event handler, {@code style}, {@code srcdoc} or a script-evaluating framework
 *       attribute; SCRIPT in a JavaScript {@code script} of the HTML or SVG namespace;
 *       SCRIPT_ATTRIBUTE in a {@code :x}/{@code v-bind:x}/{@code x-bind:x} binding;
 *   <li>the marker is in a comment or a name, or is in the render but nowhere in the tree.
 * </ul>
 *
 * Only slots whose value is a plain {@code String} printed as is can be fingerprinted; the gate
 * (#324) renders other slots ({@code SafeHtml}, {@code Fragment}) with their benign values.
 */
public final class ContextCheck {

  static final String HTML = "http://www.w3.org/1999/xhtml";
  static final String SVG = "http://www.w3.org/2000/svg";
  static final String MATHML = "http://www.w3.org/1998/Math/MathML";

  private static final Set<String> RAW_TEXT =
      Set.of("script", "style", "xmp", "iframe", "noembed", "noframes", "plaintext");
  private static final Set<String> RCDATA = Set.of("title", "textarea");

  /** JavaScript MIME type essences (HTML spec) and {@code module}; empty or absent is JS too. */
  private static final Set<String> JS_TYPES =
      Set.of(
          "application/ecmascript", "application/javascript", "application/x-ecmascript",
          "application/x-javascript", "text/ecmascript", "text/javascript", "text/javascript1.0",
          "text/javascript1.1", "text/javascript1.2", "text/javascript1.3", "text/javascript1.4",
          "text/javascript1.5", "text/jscript", "text/livescript", "text/x-ecmascript",
          "text/x-javascript", "module");

  private ContextCheck() {}

  /** Where a marker occurrence is in a parsed tree. */
  enum Place {
    /** Text of an HTML element that is neither raw text nor RCDATA. */
    HTML_TEXT("HTML element text"),
    /** Text of an HTML raw-text element ({@code script}, {@code style}, {@code xmp}, ...). */
    RAW_TEXT("raw-text element text"),
    /** Text of an HTML {@code title} or {@code textarea}. */
    RCDATA_TEXT("RCDATA element text"),
    /** Text of an SVG or MathML element. */
    FOREIGN_TEXT("foreign element text"),
    /** An attribute value. */
    ATTRIBUTE("attribute value");

    final String label;

    Place(String label) {
      this.label = label;
    }
  }

  /**
   * One marker occurrence in element text or an attribute value: the place, the element (local
   * name, namespace URI, node) and attribute it is in, and its node path.
   */
  record Location(Place place, Element node, String element, String namespace, String attribute,
      String path) {

    /** Whether the parser decoded character references here. */
    boolean decoded() {
      return place != Place.RAW_TEXT;
    }
  }

  /**
   * Checks a fingerprint render with every parser, scripting on and off. A finding seen with both
   * flags is reported once without a flag.
   *
   * @throws IllegalStateException if a parser drops comments (TEXT couldn't be told from RCDATA)
   */
  public static List<Finding> check(String render, List<SpecParser> parsers) {
    Map<String, Set<OutputContext>> written = new LinkedHashMap<>();
    Set<String> unclassified = new LinkedHashSet<>();
    Matcher m = Fingerprint.MARKER.matcher(render);
    while (m.find()) {
      Set<OutputContext> c = Fingerprint.classify(render, m.start(), false);
      written.computeIfAbsent(m.group(), k -> EnumSet.noneOf(OutputContext.class)).addAll(c);
      if (c.isEmpty()) {
        unclassified.add(m.group() + " (" + snippet(render, m.start()) + ")");
      }
    }
    List<Finding> findings = new ArrayList<>();
    for (SpecParser parser : parsers) {
      if (written.isEmpty()) {
        break;
      }
      requireComments(parser);
      for (String u : unclassified) {
        findings.add(mismatch(parser.name(), "render: unclassified escaper writes " + u, u.substring(0, 7)));
      }
      Set<Finding> on = new LinkedHashSet<>(new Walk(written, parser.name(), true).run(parser.parse(render, true)));
      Set<Finding> off = new LinkedHashSet<>(new Walk(written, parser.name(), false).run(parser.parse(render, false)));
      for (Finding f : on) {
        findings.add(off.contains(f) ? f : flagged(f, "scripting-on"));
      }
      for (Finding f : off) {
        if (!on.contains(f)) {
          findings.add(flagged(f, "scripting-off"));
        }
      }
    }
    return findings;
  }

  private static void requireComments(SpecParser parser) {
    Document doc = parser.parse("<!DOCTYPE html><p><!--c--></p>", true);
    Node p = doc.getElementsByTagName("p").item(0);
    if (p == null || p.getFirstChild() == null || p.getFirstChild().getNodeType() != Node.COMMENT_NODE) {
      throw new IllegalStateException(parser.name() + " drops comments; the context check needs them");
    }
  }

  private static Finding flagged(Finding f, String flag) {
    return new Finding(f.kind(), f.engine(), flag + " " + f.where(), f.value());
  }

  /** One parsed tree; {@code where} has no scripting flag. */
  private static final class Walk {
    private final Map<String, Set<OutputContext>> written;
    private final String engine;
    private final boolean scripting;
    private final Set<String> seen = new HashSet<>();
    private final List<Finding> findings = new ArrayList<>();

    Walk(Map<String, Set<OutputContext>> written, String engine, boolean scripting) {
      this.written = written;
      this.engine = engine;
      this.scripting = scripting;
    }

    List<Finding> run(Document doc) {
      element(doc.getDocumentElement(), "");
      for (String marker : written.keySet()) {
        if (!seen.contains(marker)) {
          report("(nowhere): marker " + marker + " is in the render but not in the parsed tree", marker);
        }
      }
      return findings;
    }

    private void element(Element el, String parentPath) {
      String ns = el.getNamespaceURI() == null ? HTML : el.getNamespaceURI();
      String name = el.getLocalName() != null ? el.getLocalName() : el.getTagName();
      String path =
          parentPath + "/" + name + (SVG.equals(ns) ? "[svg]" : MATHML.equals(ns) ? "[math]" : "");
      for (String marker : written.keySet()) {
        if (name.contains(marker)) {
          seen.add(marker);
          report(path + ": marker " + marker + " in an element name", marker);
        }
      }

      NamedNodeMap attrs = el.getAttributes();
      for (int i = 0; i < attrs.getLength(); i++) {
        Attr a = (Attr) attrs.item(i);
        String attrName = unmangle(a.getName());
        String attrPath = path + "/@" + attrName;
        for (String marker : written.keySet()) {
          if (attrName.contains(marker)) {
            seen.add(marker);
            report(attrPath + ": marker " + marker + " in an attribute name", marker);
          }
        }
        text(a.getValue(), List.of(),
            new Location(Place.ATTRIBUTE, el, name, ns, attrName, attrPath));
      }

      // Element text: adjacent text nodes and comments, a comment written as <!--data--> (TEXT
      // breaks {{ with one), each child element a boundary.
      Location here = new Location(textPlace(name, ns), el, name, ns, null, path + " text");
      StringBuilder text = new StringBuilder();
      List<int[]> comments = new ArrayList<>();
      for (Node c = el.getFirstChild(); c != null; c = c.getNextSibling()) {
        switch (c.getNodeType()) {
          case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> text.append(c.getNodeValue());
          case Node.COMMENT_NODE -> {
            int start = text.length() + "<!--".length();
            text.append("<!--").append(c.getNodeValue()).append("-->");
            comments.add(new int[] {start, start + c.getNodeValue().length()});
          }
          case Node.ELEMENT_NODE -> {
            text(text.toString(), comments, here);
            text.setLength(0);
            comments = new ArrayList<>();
            element((Element) c, path);
          }
          default -> {}
        }
      }
      text(text.toString(), comments, here);
    }

    private void text(String s, List<int[]> comments, Location loc) {
      for (String marker : written.keySet()) {
        for (int at = s.indexOf(marker); at >= 0; at = s.indexOf(marker, at + 1)) {
          seen.add(marker);
          boolean inComment = false;
          for (int[] r : comments) {
            inComment |= at >= r[0] && at < r[1];
          }
          if (inComment) {
            report(loc.path().replaceFirst(" text$", " comment") + ": marker " + marker + " in a comment", marker);
          } else {
            judge(s, at, marker, loc);
          }
        }
      }
    }

    private void judge(String s, int at, String marker, Location loc) {
      Set<OutputContext> parsed = Fingerprint.classify(s, at, loc.decoded());
      Set<OutputContext> contexts = EnumSet.noneOf(OutputContext.class);
      contexts.addAll(parsed);
      contexts.retainAll(written.get(marker));
      if (contexts.isEmpty()) {
        if (!written.get(marker).isEmpty()) {
          report(loc.path() + ": unclassified escaper in " + loc.place().label + ": written as "
              + names(written.get(marker)) + ", parsed as " + (parsed.isEmpty() ? "none" : names(parsed))
              + " (" + snippet(s, at) + ")", marker);
        }
        return; // an unclassified write is reported once, from the render
      }
      for (OutputContext c : contexts) {
        if (belongs(c, loc)) {
          return;
        }
      }
      report(loc.path() + ": " + names(contexts) + " escaping in " + loc.place().label
          + describe(loc), marker);
    }

    private Place textPlace(String name, String ns) {
      if (!HTML.equals(ns)) {
        return Place.FOREIGN_TEXT;
      }
      if (RAW_TEXT.contains(name) || (scripting && name.equals("noscript"))) {
        return Place.RAW_TEXT;
      }
      return RCDATA.contains(name) ? Place.RCDATA_TEXT : Place.HTML_TEXT;
    }

    private void report(String where, String marker) {
      findings.add(mismatch(engine, where, marker));
    }
  }

  private static final Pattern MANGLED = Pattern.compile("U([0-9A-F]{6})");

  /**
   * Undoes validator.nu's escaping of names a namespace-aware DOM can't hold ({@code :title} is
   * {@code U00003Atitle} under {@code XmlViolationPolicy.ALTER_INFOSET} as name policy).
   */
  static String unmangle(String name) {
    if (name.indexOf('U') < 0) {
      return name;
    }
    Matcher m = MANGLED.matcher(name);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(sb, Matcher.quoteReplacement(Character.toString(Integer.parseInt(m.group(1), 16))));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  /** Whether {@code context}'s escaper is correct at {@code loc}. */
  static boolean belongs(OutputContext context, Location loc) {
    boolean svg = SVG.equals(loc.namespace());
    return switch (context) {
      case TEXT -> loc.place() == Place.HTML_TEXT
          || (loc.place() == Place.FOREIGN_TEXT
              && !(svg && (loc.element().equals("script") || loc.element().equals("style"))));
      case RCDATA -> loc.place() == Place.RCDATA_TEXT;
      case ATTRIBUTE, URL, URL_LIST, RESOURCE_URL ->
          loc.place() == Place.ATTRIBUTE && plainAttribute(loc.attribute());
      case SCRIPT -> loc.element().equals("script")
          && (loc.place() == Place.RAW_TEXT || (loc.place() == Place.FOREIGN_TEXT && svg))
          && isJavaScript(loc.node());
      case SCRIPT_ATTRIBUTE -> loc.place() == Place.ATTRIBUTE && binding(loc.attribute());
      default -> false;
    };
  }

  /**
   * An attribute an entity-escaped string is correct in: not an event handler, {@code style},
   * {@code srcdoc}, or a framework attribute evaluated as script.
   */
  private static boolean plainAttribute(String attribute) {
    String a = attribute.toLowerCase(Locale.ROOT);
    String unprefixed = a.startsWith("data-") ? a.substring("data-".length()) : a;
    return !(a.startsWith("on") || a.equals("style") || a.equals("srcdoc")
        || a.startsWith(":") || a.startsWith("@") || a.startsWith("#") || a.startsWith(".")
        || a.startsWith("v-") || a.startsWith("x-") || a.equals("data-bind")
        || unprefixed.startsWith("hx-on") || unprefixed.startsWith("ng-"));
  }

  /** A Vue/Alpine bind attribute, whose value is evaluated as a JavaScript expression. */
  private static boolean binding(String attribute) {
    String a = attribute.toLowerCase(Locale.ROOT);
    return (a.startsWith(":") || a.startsWith("v-bind:") || a.startsWith("x-bind:"))
        && !a.endsWith(":");
  }

  private static boolean isJavaScript(Element script) {
    if (!script.hasAttribute("type")) {
      return true;
    }
    String type = script.getAttribute("type").strip().toLowerCase(Locale.ROOT);
    return type.isEmpty() || JS_TYPES.contains(type);
  }

  private static String describe(Location loc) {
    String ns = HTML.equals(loc.namespace()) ? "" : SVG.equals(loc.namespace()) ? " svg" : " math";
    return loc.attribute() == null
        ? " (<" + loc.element() + ">" + ns + ")"
        : " (" + loc.attribute() + " on <" + loc.element() + ">" + ns + ")";
  }

  private static String names(Set<OutputContext> contexts) {
    Set<String> escapers = new LinkedHashSet<>();
    for (OutputContext c : contexts) {
      escapers.add(c.escaper());
    }
    return contexts + " " + String.join("/", escapers);
  }

  private static String snippet(String s, int at) {
    return s.substring(at, Math.min(s.length(), at + 40)).replace("\n", "\\n");
  }

  private static Finding mismatch(String engine, String where, String marker) {
    int slot = Integer.parseInt(marker.substring(Fingerprint.PREFIX.length()));
    return new Finding(Finding.Kind.CONTEXT_MISMATCH, engine, where, Fingerprint.value(slot));
  }
}
