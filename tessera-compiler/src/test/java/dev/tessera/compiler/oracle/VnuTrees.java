package dev.tessera.compiler.oracle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * The engine-drift detector's Java half (#399): the trees the vnu engine ({@code Vnu})
 * builds for a render, written in the normalized form the Node job (#322) compares with parse5's
 * tree and each browser's DOM ({@code tree.mjs}, {@code compact}). The verifier (#367) reasons
 * about this one engine; where it builds a tree a spec engine or browser doesn't, its proof is
 * about the wrong document (#398), and only this comparison sees it. Under the divergence policy
 * (security-architecture.md §11.4) such drift becomes a coarse exclusion in the verifier.
 *
 * <p>The trees come from {@link SpecParsers}, the unmodified upstream jar of the version the
 * verifier shades ({@code tessera-html5} renames the sources, it doesn't patch them).
 *
 * <p><b>Normal form</b> (the same rules in {@code tree.mjs}; change both together):
 *
 * <ul>
 *   <li>A node list is a JSON array. A text node is a JSON string; an element is {@code [name, ns,
 *       attrs, kids]}: the local name, {@code html}, {@code svg}, {@code math} or the namespace URI,
 *       the attributes as {@code [[name, value], ...]} sorted by name (UTF-16 order, as JavaScript's
 *       default sort), and the children.
 *   <li>The document's children only: the doctype and comments are dropped, and text around a
 *       dropped comment is merged. Text is kept exactly, <b>whitespace-only text included</b>: a
 *       spec engine and a browser build the same whitespace nodes, and inter-element whitespace
 *       is where table text and foster parenting differ.
 *   <li>Attribute names as a browser's {@code Attr.name}: {@code xlink:href}, {@code xml:lang}.
 *       {@code xmlns} and {@code xmlns:*} attributes are dropped on every side (a JDK DOM can't hold
 *       them, and they do nothing in HTML). vnu spells a character that can't be in an XML name
 *       {@code U} and six hex digits; that is decoded (an HTML name is lower case, and no foreign
 *       name has a {@code U} followed by six hex digits, so it can't be read wrongly).
 *   <li>An HTML {@code <template>}'s contents are one child, {@code ["#content", "", [], kids]}
 *       (vnu's DOM appends them to the element; a browser keeps them in {@code .content}).
 *   <li>An {@code <iframe srcdoc>} without a {@code sandbox} attribute gets its document, parsed
 *       the same way with the same scripting flag, as a first child {@code ["#srcdoc", "", [],
 *       kids]}. A sandboxed frame's document isn't compared (a browser may not let us read it,
 *       and it may parse it with scripting off).
 * </ul>
 *
 * Scripting is matched, never mixed: the scripting-on trees are compared with parse5 scripting on
 * and the browsers (scripting on), the scripting-off trees with parse5 scripting off.
 */
public final class VnuTrees {
  private VnuTrees() {}

  static final String HTML = "http://www.w3.org/1999/xhtml";
  static final String SVG = "http://www.w3.org/2000/svg";
  static final String MATHML = "http://www.w3.org/1998/Math/MathML";
  private static final String XLINK = "http://www.w3.org/1999/xlink";
  private static final String XML = "http://www.w3.org/XML/1998/namespace";
  private static final String XMLNS = "http://www.w3.org/2000/xmlns/";
  private static final Pattern ESCAPED = Pattern.compile("U([0-9A-F]{6})");

  /**
   * The {@code trees} field of an export record: {@code {"scripting-on": [...], "scripting-off":
   * [...]}}, the tree the vnu engine builds for {@code render} with scripting on and off.
   */
  public static String json(String render) {
    SpecParser p = SpecParsers.named(SpecParsers.VNU_26);
    return "{\"scripting-on\":" + tree(p, render, true) + ",\"scripting-off\":" + tree(p, render, false) + "}";
  }

  /** The normalized tree {@code parser} builds for {@code html}, as JSON. */
  public static String tree(SpecParser parser, String html, boolean scripting) {
    StringBuilder sb = new StringBuilder();
    kids(sb, parser.parse(html, scripting), parser, scripting);
    return sb.toString();
  }

  private static void kids(StringBuilder sb, Node parent, SpecParser parser, boolean scripting) {
    sb.append('[');
    boolean first = true;
    StringBuilder text = null;
    if (parent instanceof Element e && HTML.equals(e.getNamespaceURI()) && e.getLocalName().equals("iframe")
        && e.hasAttribute("srcdoc") && !e.hasAttribute("sandbox")) {
      sb.append("[\"#srcdoc\",\"\",[],");
      kids(sb, parser.parse(e.getAttribute("srcdoc"), scripting), parser, scripting);
      sb.append(']');
      first = false;
    }
    for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
      short t = c.getNodeType();
      if (t == Node.TEXT_NODE || t == Node.CDATA_SECTION_NODE) {
        if (text == null) {
          text = new StringBuilder();
        }
        text.append(c.getNodeValue());
        continue;
      }
      if (t != Node.ELEMENT_NODE) {
        continue; // comments (their neighbouring text merges), doctype, processing instructions
      }
      if (text != null) {
        quote(sb.append(first ? "" : ","), text.toString());
        first = false;
        text = null;
      }
      sb.append(first ? "" : ",");
      first = false;
      element(sb, (Element) c, parser, scripting);
    }
    if (text != null) {
      quote(sb.append(first ? "" : ","), text.toString());
    }
    sb.append(']');
  }

  private static void element(StringBuilder sb, Element e, SpecParser parser, boolean scripting) {
    String ns = e.getNamespaceURI();
    sb.append('[');
    quote(sb, unescape(e.getLocalName())).append(',');
    quote(sb, HTML.equals(ns) ? "html" : SVG.equals(ns) ? "svg" : MATHML.equals(ns) ? "math" : String.valueOf(ns));
    sb.append(",[");
    List<String[]> attrs = new ArrayList<>();
    NamedNodeMap map = e.getAttributes();
    for (int i = 0; i < map.getLength(); i++) {
      Attr a = (Attr) map.item(i);
      String name = attrName(a);
      if (name != null) {
        attrs.add(new String[] {name, a.getValue()});
      }
    }
    attrs.sort((x, y) -> x[0].compareTo(y[0]));
    for (int i = 0; i < attrs.size(); i++) {
      sb.append(i == 0 ? "[" : ",[");
      quote(sb, attrs.get(i)[0]).append(',');
      quote(sb, attrs.get(i)[1]).append(']');
    }
    sb.append("],");
    if (HTML.equals(ns) && e.getLocalName().equals("template")) {
      sb.append("[[\"#content\",\"\",[],");
      kids(sb, e, parser, scripting);
      sb.append("]]");
    } else {
      kids(sb, e, parser, scripting);
    }
    sb.append(']');
  }

  /** The name a browser's {@code Attr.name} gives, or null for an {@code xmlns} attribute. */
  private static String attrName(Attr a) {
    String local = unescape(a.getLocalName() == null ? a.getName() : a.getLocalName());
    String ns = a.getNamespaceURI();
    if (XMLNS.equals(ns) || a.getName().equals("xmlns") || a.getName().startsWith("xmlns:")) {
      return null;
    }
    if (XLINK.equals(ns)) {
      return "xlink:" + local;
    }
    if (XML.equals(ns)) {
      return "xml:" + local;
    }
    return unescape(a.getName());
  }

  /** Undoes vnu's {@code U}+six-hex spelling of characters that can't be in an XML name. */
  static String unescape(String name) {
    if (name.indexOf('U') < 0) {
      return name;
    }
    Matcher m = ESCAPED.matcher(name);
    StringBuilder sb = new StringBuilder();
    int at = 0;
    while (m.find()) {
      sb.append(name, at, m.start()).appendCodePoint(Integer.parseInt(m.group(1), 16));
      at = m.end();
    }
    return sb.append(name, at, name.length()).toString();
  }

  /** {@code s} as a JSON string, every non-ASCII and control character escaped. */
  static StringBuilder quote(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"' || c == '\\') {
        sb.append('\\').append(c);
      } else if (c < 0x20 || c >= 0x7f) {
        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
      } else {
        sb.append(c);
      }
    }
    return sb.append('"');
  }
}
