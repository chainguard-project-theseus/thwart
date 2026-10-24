package dev.tessera.compiler.html;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.model.Attribute;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which elements a browser puts in the SVG or MathML namespace (foreign content), following the
 * HTML tree construction rules: {@code <svg>} and {@code <math>} start foreign content; its HTML
 * integration points (SVG {@code foreignObject}, {@code desc}, {@code title}; MathML {@code
 * annotation-xml} with an HTML {@code encoding}) and MathML text integration points ({@code mi},
 * {@code mo}, {@code mn}, {@code ms}, {@code mtext}) hold HTML again. The parsers need this for
 * {@code <title>} and {@code <textarea>}, which are RCDATA only as HTML elements (#277).
 *
 * <p>Markup whose namespace would depend on rendering is a build error, so the contexts chosen at
 * build time always match the browser's: an HTML start tag that ends foreign content in a browser
 * (such as {@code <p>} inside {@code <svg>}; it may be skipped by {@code t:if}), a computed {@code
 * encoding} on {@code annotation-xml}, and {@code t:remove="tag"} on an element that changes the
 * namespace of its content. So does {@code <svg>} directly inside MathML (or {@code <math>} inside
 * SVG), which a browser does not parse as a new {@code <svg>}: every element named {@code svg} or
 * {@code math} then starts SVG or MathML content, wherever it is rendered. The check is sound
 * because {@code t:remove} takes a literal value and {@code t:attr} literal attribute names.
 */
final class ForeignContent {

  static final int HTML = 0;
  static final int SVG = 1;
  static final int MATHML = 2;

  private static final Set<String> SVG_HTML_INTEGRATION_POINTS = Backports.setOf("foreignobject", "desc", "title");
  private static final Set<String> MATHML_TEXT_INTEGRATION_POINTS = Backports.setOf("mi", "mo", "mn", "ms", "mtext");

  /** HTML start tags that end foreign content (and {@code font} with color, face or size). */
  private static final Set<String> BREAKOUT =
      Backports.setOf(
          "b", "big", "blockquote", "body", "br", "center", "code", "dd", "div", "dl", "dt", "em", "embed", "h1", "h2",
          "h3", "h4", "h5", "h6", "head", "hr", "i", "img", "li", "listing", "menu", "meta", "nobr", "ol", "p", "pre",
          "ruby", "s", "small", "span", "strong", "strike", "sub", "sup", "table", "tt", "u", "ul", "var");

  /** A {@code t:attr} assignment to a font breakout attribute (names in {@code t:attr} are literal). */
  private static final Pattern FONT_ASSIGNMENT = Pattern.compile("(?:^|,)\\s*(?:color|face|size)\\s*=");
  private static final Pattern ENCODING_ASSIGNMENT = Pattern.compile("(?:^|,)\\s*encoding\\s*=");

  private ForeignContent() {}

  /** Whether markup in the content of an element in {@code ns} is parsed by the foreign-content rules. */
  static boolean hasForeignContent(int ns, String name, List<Attribute> attributes) {
    return ns != HTML && !htmlRules(ns, name, attributes, "p");
  }

  /** Whether the content of an element in {@code ns} is HTML. */
  private static boolean integrationPoint(int ns, String name, List<Attribute> attributes) {
    String lower = name.toLowerCase(Locale.ROOT);
    if (ns == SVG) {
      return SVG_HTML_INTEGRATION_POINTS.contains(lower);
    }
    return ns == MATHML
        && (MATHML_TEXT_INTEGRATION_POINTS.contains(lower) || lower.equals("annotation-xml") && htmlEncoding(attributes));
  }

  /** Whether start tag {@code name} inside a {@code parent} element in {@code parentNs} is parsed as HTML. */
  static boolean htmlRules(int parentNs, String parent, List<Attribute> parentAttributes, String name) {
    if (parentNs == HTML) {
      return true;
    }
    String p = parent.toLowerCase(Locale.ROOT);
    String n = name.toLowerCase(Locale.ROOT);
    if (parentNs == MATHML && MATHML_TEXT_INTEGRATION_POINTS.contains(p)) {
      return !n.equals("mglyph") && !n.equals("malignmark");
    }
    if (parentNs == MATHML && p.equals("annotation-xml") && n.equals("svg")) {
      return true;
    }
    return integrationPoint(parentNs, parent, parentAttributes);
  }

  /** Namespace of an element started by the HTML rules. */
  static int htmlNamespace(String name) {
    if (name.equalsIgnoreCase("svg")) {
      return SVG;
    }
    return name.equalsIgnoreCase("math") ? MATHML : HTML;
  }

  /**
   * The build error for an element in {@code ns} inside {@code parent} (null at top level), whose
   * namespace or whose content's namespace would depend on rendering; null if there is none.
   */
  static String problem(
      int parentNs, String parent, List<Attribute> parentAttributes, int ns, String name, List<Attribute> attributes) {
    String lower = name.toLowerCase(Locale.ROOT);
    boolean htmlRules = parent == null || htmlRules(parentNs, parent, parentAttributes, name);
    if (!htmlRules && breaksOut(lower, attributes)) {
      return "malformed markup: <" + name + "> inside SVG or MathML content ends it in a browser: close the <svg>"
          + " or <math> first, or put the HTML inside <foreignObject> or <mtext>";
    }
    if (!htmlRules && htmlNamespace(name) != HTML && htmlNamespace(name) != parentNs) {
      // <svg> directly in MathML content is a MathML element (and <math> in SVG an SVG one), so it
      // would change namespace when inserted elsewhere as a fragment.
      return "malformed markup: <" + name + "> inside " + (parentNs == SVG ? "SVG" : "MathML") + " content is not a"
          + " real <" + name + "> in a browser: put it inside <foreignObject> (SVG) or <annotation-xml> (MathML)";
    }
    if (ns == MATHML && lower.equals("annotation-xml") && computed(attributes, "encoding", ENCODING_ASSIGNMENT)) {
      return "a computed encoding on <annotation-xml> is not supported: it decides whether a browser parses the"
          + " content as HTML; write a fixed encoding";
    }
    boolean differs = contentNamespaceDiffers(parentNs, parent, parentAttributes, ns, name, attributes);
    if (differs && lower.equals("t:block")) {
      return "<t:block> is not supported here: it renders no tag, so a browser would parse its content in another"
          + " namespace (HTML or SVG/MathML); put t:if, t:each or t:with on the child elements instead";
    }
    if (differs && removesTag(attributes)) {
      return "t:remove=\"tag\" is not supported on <" + name + ">: without its tag a browser would parse the content"
          + " in another namespace (HTML or SVG/MathML); remove the whole element or keep its tag";
    }
    return null;
  }

  /**
   * Whether some child would get another namespace inside the element than directly inside its
   * parent, as it would once {@code t:remove="tag"} (or {@code <t:block>}) drops the element's tags. The children tried
   * stand for every kind the tree builder treats differently.
   */
  private static boolean contentNamespaceDiffers(
      int parentNs, String parent, List<Attribute> parentAttributes, int ns, String name, List<Attribute> attributes) {
    for (String child : new String[] {"textarea", "mglyph", "svg"}) {
      int inside = htmlRules(ns, name, attributes, child) ? htmlNamespace(child) : ns;
      int without =
          parent == null || htmlRules(parentNs, parent, parentAttributes, child) ? htmlNamespace(child) : parentNs;
      if (inside != without) {
        return true;
      }
    }
    return false;
  }

  /** Whether start tag {@code lower}, met in foreign content, may end it. */
  private static boolean breaksOut(String lower, List<Attribute> attributes) {
    if (BREAKOUT.contains(lower)) {
      return true;
    }
    return lower.equals("font")
        && (computed(attributes, "color", FONT_ASSIGNMENT)
            || computed(attributes, "face", FONT_ASSIGNMENT)
            || computed(attributes, "size", FONT_ASSIGNMENT)
            || attributes.stream()
                .anyMatch(a -> !a.isTessera() && a.name().toLowerCase(Locale.ROOT).matches("color|face|size")));
  }

  /** Whether attribute {@code name} may be written by {@code t:<name>} or a {@code t:attr} assignment. */
  private static boolean computed(List<Attribute> attributes, String name, Pattern assignment) {
    for (Attribute a : attributes) {
      if (!a.isTessera()) {
        continue;
      }
      if (a.localName().equalsIgnoreCase(name)) {
        return true;
      }
      if (a.localName().equalsIgnoreCase("attr")
          && a.value() != null
          && assignment.matcher(a.value().toLowerCase(Locale.ROOT)).find()) {
        return true;
      }
    }
    return false;
  }

  /** Whether {@code t:remove="tag"} drops the element's tags (its value is always a literal). */
  static boolean removesTag(List<Attribute> attributes) {
    for (Attribute a : attributes) {
      if (a.isTessera() && a.localName().equals("remove") && a.value() != null && Backports.strip(a.value()).equals("tag")) {
        return true;
      }
    }
    return false;
  }

  private static boolean htmlEncoding(List<Attribute> attributes) {
    for (Attribute a : attributes) {
      if (!a.isTessera() && a.name().equalsIgnoreCase("encoding") && a.value() != null) {
        return a.value().equalsIgnoreCase("text/html") || a.value().equalsIgnoreCase("application/xhtml+xml");
      }
    }
    return false;
  }
}
