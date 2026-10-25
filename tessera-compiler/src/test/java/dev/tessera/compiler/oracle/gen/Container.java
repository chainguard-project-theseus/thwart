package dev.tessera.compiler.oracle.gen;

import java.util.List;

/**
 * One value of the container axis (#321): a short chain of elements the hole sits in, tagged with
 * the parse mode of §5.3 of docs/security-architecture.md that it exercises. A generated case nests
 * up to three containers (two {@link #WRAPPERS} around an innermost one) under the root {@code <div
 * t:params>}.
 *
 * <p>The order of the constants is part of the enumeration: reordering or inserting values changes
 * which cases the default enumeration picks (append new values at the end of their group).
 */
public enum Container {
  /** No container: the hole sits directly in the root {@code <div>} (depth 0). */
  NONE("none", Mode.HTML),

  // HTML flow content.
  DIV("div", Mode.HTML, el("div")),
  P("p", Mode.HTML, el("p")),
  A("a", Mode.HTML, el("a")),
  BUTTON("button", Mode.HTML, el("button")),
  LABEL("label", Mode.HTML, el("label")),
  LI("li", Mode.HTML, el("li")),
  FORM("form", Mode.HTML, el("form")),
  TABLE_TD("table>tbody>tr>td", Mode.TABLE, el("table"), el("tbody"), el("tr"), el("td")),
  TABLE_CAPTION("table>caption", Mode.TABLE, el("table"), el("caption")),
  TEMPLATE("template", Mode.TEMPLATE, el("template")),
  SELECT("select", Mode.SELECT, el("select")),
  SELECT_OPTION("select>option", Mode.SELECT, el("select"), el("option")),
  NOSCRIPT("noscript", Mode.NOSCRIPT, el("noscript")),

  // Raw text and RCDATA.
  SCRIPT("script", Mode.RAWTEXT, el("script")),
  STYLE("style", Mode.RAWTEXT, el("style")),
  TITLE("title", Mode.RCDATA, el("title")),
  TEXTAREA("textarea", Mode.RCDATA, el("textarea")),

  // Foreign content and its integration points.
  SVG("svg", Mode.SVG, el("svg")),
  SVG_G("svg>g", Mode.SVG, el("svg"), el("g")),
  SVG_TITLE("svg>title", Mode.INTEGRATION_POINT, el("svg"), el("title")),
  SVG_DESC("svg>desc", Mode.INTEGRATION_POINT, el("svg"), el("desc")),
  SVG_TEXT("svg>text", Mode.SVG, el("svg"), el("text")),
  SVG_A("svg>a", Mode.SVG, el("svg"), el("a")),
  SVG_FOREIGN_OBJECT("svg>foreignObject", Mode.FOREIGN_OBJECT, el("svg"), el("foreignObject")),
  SVG_SCRIPT("svg>script", Mode.SVG_RAW, el("svg"), el("script")),
  SVG_STYLE("svg>style", Mode.SVG_RAW, el("svg"), el("style")),
  MATH("math", Mode.MATHML, el("math")),
  MATH_MI("math>mi", Mode.INTEGRATION_POINT, el("math"), el("mi")),
  MATH_MTEXT("math>mtext", Mode.INTEGRATION_POINT, el("math"), el("mtext")),
  MATH_ANNOTATION_XML(
      "math>annotation-xml",
      Mode.INTEGRATION_POINT,
      el("math"),
      new Element("annotation-xml", " encoding=\"text/html\"")),

  // Foster parenting: text directly in a table or row is moved before the table.
  TABLE("table", Mode.FOSTER, el("table")),
  TABLE_TR("table>tbody>tr", Mode.FOSTER, el("table"), el("tbody"), el("tr")),

  // Legacy raw-text elements: expected REJECTED, or fixed by #289/#298.
  XMP("xmp", Mode.LEGACY, el("xmp")),
  IFRAME("iframe", Mode.LEGACY, el("iframe")),
  NOEMBED("noembed", Mode.LEGACY, el("noembed")),
  NOFRAMES("noframes", Mode.LEGACY, el("noframes")),
  PLAINTEXT("plaintext", Mode.LEGACY, el("plaintext"));

  /**
   * The parse modes of §5.3 that the generator must cover. {@code SVG_RAW} is SVG {@code
   * script}/{@code style}, which browsers parse as foreign elements, not raw text.
   */
  public enum Mode {
    HTML,
    TABLE,
    FOSTER,
    TEMPLATE,
    SELECT,
    NOSCRIPT,
    RAWTEXT,
    RCDATA,
    SVG,
    SVG_RAW,
    MATHML,
    INTEGRATION_POINT,
    FOREIGN_OBJECT,
    LEGACY
  }

  /** One element of a chain: its name and its static attributes (with a leading space, or empty). */
  public record Element(String name, String attributes) {}

  /**
   * The containers used as outer wrappers around the innermost one: the ones that change how their
   * descendants are parsed, plus a plain {@code div}. Kept small so the pairwise enumeration stays
   * within its cap.
   */
  public static final List<Container> WRAPPERS =
      List.of(
          NONE, DIV, TABLE_TD, TABLE_CAPTION, TEMPLATE, SELECT, NOSCRIPT, TITLE, SVG,
          SVG_FOREIGN_OBJECT, SVG_TITLE, MATH, MATH_MTEXT, MATH_ANNOTATION_XML);

  /** The containers a fragment is inserted under (composition {@code t:insert}/{@code t:replace}). */
  public static final List<Container> INSERTION_POINTS =
      List.of(
          DIV, TABLE_TD, TABLE, SELECT, NOSCRIPT, TEMPLATE, SCRIPT, TITLE, TEXTAREA, SVG, SVG_TITLE,
          SVG_FOREIGN_OBJECT, MATH, MATH_MI);

  private final String label;
  private final Mode mode;
  private final List<Element> elements;

  Container(String label, Mode mode, Element... elements) {
    this.label = label;
    this.mode = mode;
    this.elements = List.of(elements);
  }

  private static Element el(String name) {
    return new Element(name, "");
  }

  /** The label used in case names, as written in #321 ({@code svg>title}). */
  public String label() {
    return label;
  }

  /** The parse mode of the innermost element. */
  public Mode mode() {
    return mode;
  }

  /** The elements, outermost first; empty for {@link #NONE}. */
  public List<Element> elements() {
    return elements;
  }
}
