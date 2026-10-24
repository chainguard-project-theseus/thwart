package dev.tessera.compiler.html;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.model.Attribute;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which elements a browser puts in the SVG or MathML namespace (foreign content), following the
 * HTML tree construction rules: {@code <svg>} and {@code <math>} start foreign content; its HTML
 * integration points (SVG {@code foreignObject}, {@code desc}, {@code title}; MathML {@code
 * annotation-xml} with an HTML {@code encoding}) and MathML text integration points ({@code mi},
 * {@code mo}, {@code mn}, {@code ms}, {@code mtext}) hold HTML again; and an HTML start tag such as
 * {@code <p>} or {@code <br>} ends it. The parsers need this for {@code <title>} and {@code
 * <textarea>}, which are RCDATA only as HTML elements (#277).
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

  private ForeignContent() {}

  /** Whether the content of an element in {@code ns} is HTML. */
  static boolean integrationPoint(int ns, String name, List<Attribute> attributes) {
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

  /** Whether start tag {@code name}, met in foreign content, ends it. */
  static boolean breaksOut(String name, List<Attribute> attributes) {
    String lower = name.toLowerCase(Locale.ROOT);
    if (BREAKOUT.contains(lower)) {
      return true;
    }
    if (!lower.equals("font")) {
      return false;
    }
    for (Attribute a : attributes) {
      String attr = (a.isTessera() ? a.localName() : a.name()).toLowerCase(Locale.ROOT);
      if (attr.equals("color") || attr.equals("face") || attr.equals("size")) {
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
