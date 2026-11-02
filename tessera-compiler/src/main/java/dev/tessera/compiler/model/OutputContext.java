package dev.tessera.compiler.model;

/**
 * Where an expression's value lands in the HTML output. Decided by the parser
 * ({@code dev.tessera.compiler.html.Contexts}), never by the template author. A context maps to
 * at most one runtime escaper for a value in content ({@link #escaper}) and at most one for an
 * attribute value ({@link #attributeEscaper}); an expression where there is none is a build error.
 */
public enum OutputContext {
  /** Element text, the RCDATA of {@code <title>} and {@code <textarea>} included. */
  TEXT("text"),
  /**
   * Quoted value of a plain-text attribute: an inert-text attribute ({@code inert-attributes.txt}),
   * or a named {@code <meta>}'s {@code content}. Generated
   * attributes are always double-quoted.
   */
  ATTRIBUTE("attr"),
  /**
   * An attribute whose grammar is exactly one URL: href, src, action, formaction, cite, poster, ...
   * (scheme-checked).
   */
  URL("url"),
  /**
   * URL that loads code or a document: {@code <script src>}, {@code <script href>},
   * {@code <script xlink:href>}, {@code <iframe src>},
   * {@code <object data>}, {@code <embed src>}, {@code <base href>},
   * {@code <link href>}. Only a literal-path link expression.
   */
  RESOURCE_URL("resourceUrl"),
  /**
   * The {@code content} of a {@code <meta http-equiv="refresh">} (#237). Only
   * {@code '<seconds>;url=' + @{/path...}} with a same-origin literal path is accepted; the
   * escaper encodes the characters that end a refresh URL and re-checks the path.
   */
  REFRESH("refresh"),
  /**
   * Inside a JavaScript or JSON {@code <script>} element; values are serialized as JSON
   * ({@code Escapers.javaScript}, or {@code Escapers.json} in a JSON script).
   */
  SCRIPT("json"),
  /**
   * {@code <style>} element or {@code style} attribute. Expressions in a {@code <style>} are a
   * build error; a {@code style} attribute takes only literals, numbers and {@code CssValue}s
   * (#182) and is attribute-escaped.
   */
  STYLE(null),
  /**
   * A standard attribute that is not an inert-text attribute ({@code inert-attributes.txt}) and
   * has no more specific context, such as {@code sandbox}, {@code rel}, {@code target}, an SVG
   * {@code transform} or {@code x}, or the {@code content} of a {@code <meta>} that is not named.
   * The value may only combine string literals and numbers (#565); a String, other plain value or
   * safe type is a build error. Written attribute-escaped.
   */
  TYPED_ATTRIBUTE(null),
  /**
   * An attribute whose name is not a standard HTML, SVG, MathML or ARIA name ({@code data-*},
   * {@code hx-*}, {@code x-*}, {@code v-*}, a custom element's own attributes, any other name;
   * #583 rule 3, #584). Whatever client code reads it gets only a framework-neutral value: an
   * exact-class number written in plain decimal, a boolean, a same-origin {@code @{/path}} with no
   * query, or a choice between those and literals. A String or any other type is a build error.
   * Written attribute-escaped.
   */
  NEUTRAL(null),
  /**
   * {@code on*} event-handler attribute: expressions are a build error.
   */
  EVENT_HANDLER(null),
  /**
   * Anything else we cannot escape safely ({@code srcdoc}, non-JS {@code <script type>}), an
   * attribute that decides how others are read (a {@code <script>}'s {@code type}, a
   * {@code <meta>}'s {@code http-equiv}, an animation's {@code attributeName},
   * tree-builder inputs), and names that are not plain identifiers:
   * expressions are a build error.
   */
  FORBIDDEN(null);

  private final String escaper;

  OutputContext(String escaper) {
    this.escaper = escaper;
  }

  /**
   * Name of the {@code dev.tessera.runtime.Escapers} method for this context, or {@code null}
   * if an expression here must be rejected at build time.
   */
  public String escaper() {
    return escaper;
  }

  /**
   * Name of the {@code dev.tessera.runtime.Escapers} method for an attribute value in this context,
   * or {@code null} if an expression in the attribute must be rejected at build time. STYLE,
   * TYPED_ATTRIBUTE and NEUTRAL values are restricted at build time and written attribute-escaped.
   */
  public String attributeEscaper() {
    switch (this) {
      case ATTRIBUTE:
      case STYLE:
      case TYPED_ATTRIBUTE:
      case NEUTRAL:
        return ATTRIBUTE.escaper;
      case URL:
      case RESOURCE_URL:
      case REFRESH:
        return escaper;
      default:
        return null;
    }
  }
}
