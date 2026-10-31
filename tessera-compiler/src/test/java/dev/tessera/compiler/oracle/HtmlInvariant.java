package dev.tessera.compiler.oracle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * The HTML half of the invariant in §5.1 of docs/security-architecture.md (#318), checked on two
 * renders of one case: the <em>benign</em> render, with each slot holding an inert marker ({@code
 * tqm0001}), and the <em>hostile</em> render, with each slot holding a hostile value whose payload
 * call is that slot's marker ({@code tqm0001()}). No compiler here: the gate (#324) renders.
 *
 * <p>Each {@link SpecParser} parses both renders, with scripting on and off, and the hostile tree
 * must keep the benign tree's structure. Text and attribute values that hold a marker in the benign
 * tree may differ; nothing else may. On the hostile tree, a marker must not be in an element or
 * attribute name, an {@code on*} attribute, a {@code javascript:}/{@code vbscript:}/{@code data:}
 * URL, a script the benign tree doesn't have, or {@code {{ ... }}} in text a client framework
 * interpolates; and a resource URL must not leave the origin by shape. Scripts that hold a marker
 * in the benign tree are passed on as {@link ScriptSlot}s for the script check (#319).
 *
 * <p>Tessera breaks a <code>{{</code> in element text with an empty comment next to each brace
 * (#248), so such comments join the text around them when comparing structure.
 *
 * <p>An {@code <iframe srcdoc>} value is a document of its own: both values are parsed and checked
 * the same way, with paths such as {@code /html/body/iframe/@srcdoc/html/body/script}.
 *
 * <p>Not checked here: a value with no payload call has no marker, so it can't show up in a handler,
 * a script or <code>{{ }}</code> (the JS_URL check still catches its script scheme in a URL slot).
 * And a slot the parser drops from both trees can't be told apart from static text: after
 * {@code </frameset>} a parser keeps only the whitespace of text, so a benign marker vanishes and a
 * hostile value with whitespace gives a STRUCTURE_CHANGED that isn't a security finding (#320's
 * A29).
 */
public final class HtmlInvariant {
  private HtmlInvariant() {}

  /** An inert slot marker: {@code tqm} and four digits. */
  public static final Pattern MARKER = Pattern.compile("tqm[0-9]{4}");

  static final String HTML = "http://www.w3.org/1999/xhtml";
  static final String SVG = "http://www.w3.org/2000/svg";
  static final String MATHML = "http://www.w3.org/1998/Math/MathML";
  private static final String XLINK = "http://www.w3.org/1999/xlink";
  private static final String XML = "http://www.w3.org/XML/1998/namespace";

  /** The marker of slot {@code i}. */
  public static String marker(int i) {
    return String.format(Locale.ROOT, "tqm%04d", i);
  }

  /**
   * A script that holds a marker in the benign tree, for the script check (#319).
   *
   * @param engine the parser
   * @param where scripting flag and node path, as in {@link Finding#where()}
   * @param markers the markers in the benign script, in order
   * @param benign the benign script text
   * @param hostile the hostile script text at the same path, or null if the hostile tree has none
   */
  public record ScriptSlot(String engine, String where, List<String> markers, String benign, String hostile) {}

  /** What the checks found, and the script slots left for #319. */
  public record Result(List<Finding> findings, List<ScriptSlot> scriptSlots) {
    public Result {
      findings = List.copyOf(findings);
      scriptSlots = List.copyOf(scriptSlots);
    }

    /** {@code CLEAN} or {@code FINDINGS}. */
    public Verdict verdict() {
      return findings.isEmpty() ? new Verdict.Clean() : new Verdict.Findings(findings);
    }
  }

  /** {@link #check(List, String, String, String, boolean)} with {@link SpecParsers#all()}. */
  public static Result check(String benign, String hostile, String value, boolean contextRelativeLinks) {
    return check(SpecParsers.all(), benign, hostile, value, contextRelativeLinks);
  }

  /**
   * Checks one pair of renders.
   *
   * @param value the hostile value, recorded in each finding
   * @param contextRelativeLinks whether the template wrote a context-relative link ({@code @{/...}}).
   *     A protocol-relative ({@code //x}) or absolute resource URL is a finding when a value made a
   *     same-origin slot cross-origin, or moved the origin (scheme, userinfo, host, port) of a
   *     cross-origin slot, whatever this says; with it, also when a value changed a URL that holds
   *     no marker. A static CDN URL is the template's own. A backslash form ({@code /\x},
   *     {@code http:\\x}) always is a finding
   */
  public static Result check(
      List<SpecParser> parsers, String benign, String hostile, String value, boolean contextRelativeLinks) {
    List<Finding> findings = new ArrayList<>();
    List<ScriptSlot> slots = new ArrayList<>();
    for (SpecParser parser : parsers) {
      for (boolean scripting : new boolean[] {true, false}) {
        new Check(parser, scripting, value, contextRelativeLinks, "", findings, slots)
            .document(parser.parse(benign, scripting), parser.parse(hostile, scripting));
      }
    }
    return new Result(findings, slots);
  }

  // ---- structure ----

  /** A child of an element as compared: a text run, a non-empty comment, or an element. */
  private record Item(short type, String text, Element element) {}

  private static List<Item> items(Node parent) {
    List<Item> out = new ArrayList<>();
    StringBuilder run = null;
    for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
      short t = c.getNodeType();
      if (t == Node.TEXT_NODE || t == Node.CDATA_SECTION_NODE || braceBreak(c)) {
        if (run == null) {
          run = new StringBuilder();
        }
        if (t != Node.COMMENT_NODE) {
          run.append(c.getNodeValue());
        }
        continue;
      }
      if (run != null) {
        out.add(new Item(Node.TEXT_NODE, run.toString(), null));
        run = null;
      }
      if (t == Node.COMMENT_NODE) {
        out.add(new Item(t, c.getNodeValue(), null));
      } else if (t == Node.ELEMENT_NODE) {
        out.add(new Item(t, null, (Element) c));
      }
    }
    if (run != null) {
      out.add(new Item(Node.TEXT_NODE, run.toString(), null));
    }
    return out;
  }

  /**
   * Whether {@code n} is the empty comment Tessera writes next to a brace in element text
   * (#248, #571): it ends text that ends with a brace or starts text that starts with one.
   */
  private static boolean braceBreak(Node n) {
    if (n.getNodeType() != Node.COMMENT_NODE || !n.getNodeValue().isEmpty()) {
      return false;
    }
    Node prev = n.getPreviousSibling();
    Node next = n.getNextSibling();
    return (prev != null && prev.getNodeType() == Node.TEXT_NODE && brace(prev.getNodeValue(), prev.getNodeValue().length() - 1))
        || (next != null && next.getNodeType() == Node.TEXT_NODE && brace(next.getNodeValue(), 0));
  }

  private static boolean brace(String s, int i) {
    return i >= 0 && i < s.length() && (s.charAt(i) == '{' || s.charAt(i) == '}');
  }

  /** The first difference between the children of {@code b} and {@code h}, or null. */
  private static String compare(Node b, Node h, String path) {
    List<Item> bs = items(b);
    List<Item> hs = items(h);
    int i = 0;
    int j = 0;
    while (i < bs.size() || j < hs.size()) {
      Item bi = i < bs.size() ? bs.get(i) : null;
      Item hj = j < hs.size() ? hs.get(j) : null;
      if (bi != null && bi.type() == Node.TEXT_NODE && hasMarker(bi.text())) {
        // A slot's text may hold anything, including nothing (an empty value).
        i++;
        if (hj != null && hj.type() == Node.TEXT_NODE) {
          j++;
        }
        continue;
      }
      String at = path.isEmpty() ? "/" : path;
      if (bi == null) {
        return at + ": the hostile tree adds " + describe(hj);
      }
      if (hj == null) {
        return at + ": the hostile tree drops " + describe(bi);
      }
      if (bi.type() != hj.type()) {
        return at + ": " + describe(bi) + " became " + describe(hj);
      }
      if (bi.type() == Node.ELEMENT_NODE) {
        String diff = compareElements(bi.element(), hj.element(), path + "/" + step(bi.element()));
        if (diff != null) {
          return diff;
        }
      } else if (!bi.text().equals(hj.text()) && !(bi.type() == Node.COMMENT_NODE && hasMarker(bi.text()))) {
        return at + ": " + describe(bi) + " became " + describe(hj);
      }
      i++;
      j++;
    }
    return null;
  }

  private static String compareElements(Element b, Element h, String path) {
    if (!key(b).equals(key(h))) {
      return path + ": element " + key(b) + " became " + key(h);
    }
    Map<String, String> ba = attributes(b);
    Map<String, String> ha = attributes(h);
    if (!ba.keySet().equals(ha.keySet())) {
      return path + ": attributes " + ba.keySet() + " became " + ha.keySet();
    }
    for (Map.Entry<String, String> a : ba.entrySet()) {
      if (!hasMarker(a.getValue()) && !a.getValue().equals(ha.get(a.getKey()))) {
        return path + "/@" + a.getKey() + ": value " + show(a.getValue()) + " became " + show(ha.get(a.getKey()));
      }
    }
    return compare(b, h, path);
  }

  private static String describe(Item item) {
    return switch (item.type()) {
      case Node.ELEMENT_NODE -> "element " + key(item.element());
      case Node.COMMENT_NODE -> "comment " + show(item.text());
      default -> "text " + show(item.text());
    };
  }

  private static String show(String s) {
    return s.length() > 60 ? "\"" + s.substring(0, 60) + "...\"" : "\"" + s + "\"";
  }

  /** Name and namespace: {@code p}, {@code svg:title}, {@code math:mi}, or {@code {ns}name}. */
  private static String key(Element e) {
    String ns = e.getNamespaceURI();
    String name = e.getLocalName();
    if (HTML.equals(ns)) {
      return name;
    }
    if (SVG.equals(ns)) {
      return "svg:" + name;
    }
    if (MATHML.equals(ns)) {
      return "math:" + name;
    }
    return "{" + ns + "}" + name;
  }

  private static Map<String, String> attributes(Element e) {
    Map<String, String> out = new LinkedHashMap<>();
    NamedNodeMap attrs = e.getAttributes();
    for (int i = 0; i < attrs.getLength(); i++) {
      Attr a = (Attr) attrs.item(i);
      out.put(attrName(a), a.getValue());
    }
    return out;
  }

  /** The attribute's name as a browser sees it: {@code xlink:href}, {@code xml:lang}, or the name. */
  private static String attrName(Attr a) {
    String local = a.getLocalName() == null ? a.getName() : a.getLocalName();
    if (XLINK.equals(a.getNamespaceURI())) {
      return "xlink:" + local;
    }
    if (XML.equals(a.getNamespaceURI())) {
      return "xml:" + local;
    }
    return a.getName();
  }

  // ---- paths ----

  /** {@code /html/body/div/svg[svg]/title[svg]}, with {@code [n]} where siblings share a name. */
  static String path(Element e) {
    StringBuilder sb = new StringBuilder();
    for (Node n = e; n instanceof Element el; n = n.getParentNode()) {
      sb.insert(0, "/" + step(el));
    }
    return sb.toString();
  }

  private static String step(Element e) {
    String ns = e.getNamespaceURI();
    String step = e.getLocalName() + (SVG.equals(ns) ? "[svg]" : MATHML.equals(ns) ? "[math]" : HTML.equals(ns) ? "" : "[" + ns + "]");
    int index = 0;
    int same = 0;
    for (Node c = e.getParentNode().getFirstChild(); c != null; c = c.getNextSibling()) {
      if (c instanceof Element s && key(s).equals(key(e))) {
        same++;
        if (s == e) {
          index = same;
        }
      }
    }
    return same > 1 ? step + "[" + index + "]" : step;
  }

  /** Each element of {@code doc} by its path, in document order. */
  private static Map<String, Element> byPath(Document doc) {
    Map<String, Element> out = new LinkedHashMap<>();
    for (Element e : elements(doc)) {
      out.put(path(e), e);
    }
    return out;
  }

  /** {@code text} as a pattern: each marker matches anything, the rest only itself. */
  private static Pattern slotPattern(String text) {
    StringBuilder re = new StringBuilder();
    int last = 0;
    Matcher m = MARKER.matcher(text);
    while (m.find()) {
      re.append(Pattern.quote(text.substring(last, m.start()))).append("[\\s\\S]*");
      last = m.end();
    }
    return Pattern.compile(re.append(Pattern.quote(text.substring(last))).toString());
  }

  /** The value of the attribute of {@code e} named {@code name} (as {@link #attrName}, lower case), or null. */
  private static String benignAttribute(Element e, String name) {
    NamedNodeMap attrs = e.getAttributes();
    for (int i = 0; i < attrs.getLength(); i++) {
      Attr a = (Attr) attrs.item(i);
      if (attrName(a).toLowerCase(Locale.ROOT).equals(name)) {
        return a.getValue();
      }
    }
    return null;
  }

  private static List<Element> elements(Node root) {
    List<Element> out = new ArrayList<>();
    collect(root, out);
    return out;
  }

  private static void collect(Node n, List<Element> out) {
    if (n instanceof Element e) {
      out.add(e);
    }
    for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
      collect(c, out);
    }
  }

  // ---- point checks on the hostile tree ----

  /** Script types a browser runs: the JavaScript MIME type essences, {@code module} and empty. */
  private static final Set<String> JS_TYPES =
      Set.of(
          "", "module", "application/ecmascript", "application/javascript", "application/x-ecmascript",
          "application/x-javascript", "text/ecmascript", "text/javascript", "text/javascript1.0",
          "text/javascript1.1", "text/javascript1.2", "text/javascript1.3", "text/javascript1.4",
          "text/javascript1.5", "text/jscript", "text/livescript", "text/x-ecmascript", "text/x-javascript");

  /** Attributes whose value a browser reads as a URL. */
  private static final Set<String> URL_ATTRIBUTES =
      Set.of("href", "src", "action", "formaction", "xlink:href", "data", "poster", "cite", "background");

  /** HTML elements whose text Tessera doesn't break <code>{{</code> in: RCDATA and raw text. */
  private static final Set<String> NOT_FLOW_TEXT =
      Set.of("title", "textarea", "script", "style", "xmp", "iframe", "noembed", "noframes", "noscript", "plaintext");

  private static final Set<String> HTMX_REQUESTS = Set.of("hx-get", "hx-post", "hx-put", "hx-patch", "hx-delete");

  private static final Set<String> RESOURCE_RELS = Set.of("stylesheet", "preload", "modulepreload", "import");

  /** Whether {@code e} is a {@code <script>} a browser runs (HTML or SVG, JavaScript type). */
  static boolean liveScript(Element e) {
    String ns = e.getNamespaceURI();
    if (!e.getLocalName().equals("script") || !(HTML.equals(ns) || SVG.equals(ns))) {
      return false;
    }
    String type;
    if (e.hasAttribute("type")) {
      type = e.getAttribute("type");
    } else if (!e.getAttribute("language").isEmpty()) {
      type = "text/" + e.getAttribute("language");
    } else {
      type = "";
    }
    return JS_TYPES.contains(type.strip().toLowerCase(Locale.ROOT));
  }

  private static boolean hasMarker(String s) {
    return s != null && MARKER.matcher(s).find();
  }

  private static List<String> markers(String s) {
    List<String> out = new ArrayList<>();
    Matcher m = MARKER.matcher(s);
    while (m.find()) {
      out.add(m.group());
    }
    return out;
  }

  /**
   * The checks of one parser and scripting flag on one pair of documents. {@code prefix} is empty
   * for the page and the path of the {@code srcdoc} attribute for a document inside one.
   */
  private record Check(
      SpecParser parser, boolean scripting, String value, boolean contextRelativeLinks, String prefix,
      List<Finding> out, List<ScriptSlot> slots) {

    void add(Finding.Kind kind, String where) {
      out.add(new Finding(kind, parser.name(), (scripting ? "scripting-on " : "scripting-off ") + prefix + where, value));
    }

    void document(Document b, Document h) {
      String diff = compare(b, h, "");
      if (diff != null) {
        add(Finding.Kind.STRUCTURE_CHANGED, diff);
      }
      Map<String, Element> benignByPath = byPath(b);
      Map<String, Element> hostileByPath = byPath(h);
      Map<String, Pattern> benignSlots = new LinkedHashMap<>();
      for (Map.Entry<String, Element> be : benignByPath.entrySet()) {
        Element s = be.getValue();
        List<String> markers = markers(s.getTextContent());
        if (liveScript(s) && !markers.isEmpty()) {
          benignSlots.put(be.getKey(), slotPattern(s.getTextContent()));
          Element hs = hostileByPath.get(be.getKey());
          slots.add(new ScriptSlot(parser.name(), (scripting ? "scripting-on " : "scripting-off ") + prefix + be.getKey(),
              markers, s.getTextContent(), hs == null ? null : hs.getTextContent()));
        }
      }
      points(h, benignByPath, benignSlots);
    }

    /**
     * An {@code <iframe srcdoc>} is a whole document in an attribute: parse the benign and hostile
     * values with the same parser and check them like the page.
     */
    void srcdoc(Element e, Element be, String path) {
      String benign = be != null && be.hasAttribute("srcdoc") ? be.getAttribute("srcdoc") : "";
      new Check(parser, scripting, value, contextRelativeLinks, prefix + path + "/@srcdoc", out, slots)
          .document(parser.parse(benign, scripting), parser.parse(e.getAttribute("srcdoc"), scripting));
    }

    /**
     * The point checks on the hostile tree. {@code benign} maps each benign element's path to it;
     * {@code benignSlots} maps the path of each benign live script that holds a marker to a pattern
     * of its text, with each marker free and the rest as written.
     */
    void points(Document hostile, Map<String, Element> benign, Map<String, Pattern> benignSlots) {
      for (Element e : elements(hostile)) {
        String path = path(e);
        Element be = benign.get(path);
        if (hasMarker(e.getLocalName())) {
          add(Finding.Kind.MARKER_IN_NAME, path);
        }
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
          Attr a = (Attr) attrs.item(i);
          String name = attrName(a).toLowerCase(Locale.ROOT);
          String at = path + "/@" + name;
          if (hasMarker(name)) {
            add(Finding.Kind.MARKER_IN_NAME, at);
          }
          if (name.startsWith("on") && hasMarker(a.getValue())) {
            add(Finding.Kind.LIVE_HANDLER, at);
          }
          // A value with no payload to rewrite (data:...;base64,...) has no marker; it still counts
          // when the benign value there was a slot without a script scheme.
          String slot = be == null ? null : benignAttribute(be, name);
          if (scriptUrl(e, name, a.getValue())
              && (hasMarker(a.getValue()) || (hasMarker(slot) && !scriptUrl(be, name, slot)))) {
            add(Finding.Kind.JS_URL, at);
          }
        }
        Map<String, String> benignResources = be == null ? Map.of() : resourceUrls(be);
        for (Map.Entry<String, String> r : resourceUrls(e).entrySet()) {
          String url = r.getValue();
          String was = benignResources.get(r.getKey());
          // A same-origin slot (the benign URL holds a marker) that a value makes cross-origin always
          // counts: the value picks the origin. So does a cross-origin slot whose origin a value moved,
          // or whose origin holds the marker. A static absolute URL (a CDN script) is the template's own;
          // a static context-relative link that a value changed counts when the template wrote @{/...}.
          boolean slot = hasMarker(was);
          boolean changed = was == null || !was.equals(url);
          boolean originMoved = slot && UrlShape.crossOrigin(was)
              && (!UrlShape.authority(was).equals(UrlShape.authority(url)) || hasMarker(UrlShape.authority(was)));
          if (UrlShape.backslashNetworkPath(url)
              || (UrlShape.crossOrigin(url)
                  && ((slot && !UrlShape.crossOrigin(was)) || originMoved || (contextRelativeLinks && changed)))) {
            add(Finding.Kind.CROSS_ORIGIN_RESOURCE, path + "/@" + r.getKey());
          }
        }
        if (HTML.equals(e.getNamespaceURI()) && e.getLocalName().equals("iframe") && e.hasAttribute("srcdoc")) {
          srcdoc(e, be, path);
        }
        if (liveScript(e) && hasMarker(e.getTextContent())) {
          Pattern slots = benignSlots.get(path);
          if (slots == null || !slots.matcher(e.getTextContent()).matches()) {
            add(Finding.Kind.LIVE_SCRIPT, path);
          }
        }
        if (flowText(e) && csti(e)) {
          add(Finding.Kind.CSTI, path);
        }
      }
    }
  }

  /** Whether attribute {@code name} of {@code e} is a URL with a script scheme. */
  private static boolean scriptUrl(Element e, String name, String value) {
    if (URL_ATTRIBUTES.contains(name)) {
      return UrlShape.scriptScheme(value);
    }
    String el = e.getLocalName();
    if (SVG.equals(e.getNamespaceURI()) && (el.equals("animate") || el.equals("set"))
        && (name.equals("values") || name.equals("to") || name.equals("from") || name.equals("by"))) {
      String target = e.getAttribute("attributeName").strip();
      if (target.equals("href") || target.equals("xlink:href")) {
        for (String v : value.split(";", -1)) {
          if (UrlShape.scriptScheme(v)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * Every URL {@code e} loads a resource from or sends a request to, keyed by the attribute that
   * holds it.
   */
  private static Map<String, String> resourceUrls(Element e) {
    Map<String, String> out = new LinkedHashMap<>();
    String ns = e.getNamespaceURI();
    String el = e.getLocalName();
    if (SVG.equals(ns)) {
      if (el.equals("script")) {
        put(out, e, "href");
        if (e.hasAttributeNS(XLINK, "href")) {
          out.put("xlink:href", e.getAttributeNS(XLINK, "href"));
        }
      }
      return out;
    }
    if (!HTML.equals(ns)) {
      return out;
    }
    switch (el) {
      case "script", "iframe", "frame", "embed" -> put(out, e, "src");
      case "base" -> put(out, e, "href");
      case "object" -> put(out, e, "data");
      case "link" -> {
        for (String rel : e.getAttribute("rel").toLowerCase(Locale.ROOT).split("[\\t\\n\\f\\r ]+")) {
          if (RESOURCE_RELS.contains(rel)) {
            put(out, e, "href");
          }
        }
      }
      case "meta" -> {
        String url = e.getAttribute("http-equiv").strip().equalsIgnoreCase("refresh") ? refreshUrl(e.getAttribute("content")) : null;
        if (url != null) {
          out.put("content", url);
        }
      }
      default -> { }
    }
    for (String hx : HTMX_REQUESTS) {
      put(out, e, hx);
      put(out, e, "data-" + hx);
    }
    return out;
  }

  private static void put(Map<String, String> out, Element e, String name) {
    if (e.hasAttribute(name)) {
      out.put(name, e.getAttribute(name));
    }
  }

  /** The URL of a {@code meta refresh} content value ({@code 0;url=/x}, {@code 0; URL='/x'}), or null. */
  static String refreshUrl(String content) {
    // HTML "shared declarative refresh steps": a delay, then ';', ',' or whitespace, then the URL.
    int i = 0;
    int n = content.length();
    while (i < n && asciiSpace(content.charAt(i))) {
      i++;
    }
    if (i >= n || !(asciiDigit(content.charAt(i)) || content.charAt(i) == '.')) {
      return null;
    }
    while (i < n && (asciiDigit(content.charAt(i)) || content.charAt(i) == '.')) {
      i++;
    }
    if (i >= n || (content.charAt(i) != ';' && content.charAt(i) != ',' && !asciiSpace(content.charAt(i)))) {
      return null;
    }
    while (i < n && asciiSpace(content.charAt(i))) {
      i++;
    }
    if (i < n && (content.charAt(i) == ';' || content.charAt(i) == ',')) {
      i++;
    }
    while (i < n && asciiSpace(content.charAt(i))) {
      i++;
    }
    int afterUrl = i;
    if (content.regionMatches(true, i, "url", 0, 3)) {
      int k = i + 3;
      while (k < n && content.charAt(k) <= ' ') {
        k++;
      }
      if (k < n && content.charAt(k) == '=') {
        k++;
        while (k < n && content.charAt(k) <= ' ') {
          k++;
        }
        afterUrl = k;
      }
    }
    String url = content.substring(afterUrl);
    if (!url.isEmpty() && (url.charAt(0) == '"' || url.charAt(0) == '\'')) {
      int end = url.indexOf(url.charAt(0), 1);
      url = end < 0 ? url.substring(1) : url.substring(1, end);
    }
    return url.isEmpty() ? null : url;
  }

  private static boolean asciiSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\f' || c == '\r';
  }

  private static boolean asciiDigit(char c) {
    return c >= '0' && c <= '9';
  }

  /** Whether the text directly in {@code e} is text Tessera breaks <code>{{</code> in (#248). */
  private static boolean flowText(Element e) {
    String ns = e.getNamespaceURI();
    if (HTML.equals(ns)) {
      return !NOT_FLOW_TEXT.contains(e.getLocalName());
    }
    return !(SVG.equals(ns) && (e.getLocalName().equals("script") || e.getLocalName().equals("style")));
  }

  /** Whether a run of adjacent text nodes in {@code e} holds {@code {{ ... marker ... }}}. */
  private static boolean csti(Element e) {
    StringBuilder run = new StringBuilder();
    for (Node c = e.getFirstChild(); ; c = c.getNextSibling()) {
      if (c != null && (c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE)) {
        run.append(c.getNodeValue());
        continue;
      }
      if (interpolates(run.toString())) {
        return true;
      }
      run.setLength(0);
      if (c == null) {
        return false;
      }
    }
  }

  /** Whether a marker is between a <code>{{</code> and the first <code>}}</code> after it, as Vue reads it. */
  private static boolean interpolates(String text) {
    for (int open = text.indexOf("{{"); open >= 0; open = text.indexOf("{{", open + 1)) {
      int close = text.indexOf("}}", open + 2);
      if (close >= 0 && hasMarker(text.substring(open + 2, close))) {
        return true;
      }
    }
    return false;
  }
}
