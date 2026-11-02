package dev.tessera.compiler.model;

import dev.tessera.compiler.verify.Skeleton.Escaper;

/**
 * Where an expression's value lands in the HTML output. Decided by the parser
 * ({@code dev.tessera.compiler.html.Contexts}), never by the template author. A context maps to
 * at most one runtime escaper for a value in content ({@link #escaper}) and at most one for an
 * attribute value ({@link #attributeEscaper}); an expression where there is none is a build error.
 */
public enum OutputContext {
  /** Element text, the RCDATA of {@code <title>} and {@code <textarea>} included. */
  TEXT(Escaper.TEXT),
  /**
   * Quoted value of a plain-text attribute: an inert-text name on the computed allow-list
   * ({@code Contexts}). Generated
   * attributes are always double-quoted.
   */
  ATTRIBUTE(Escaper.ATTR),
  /**
   * An attribute whose grammar is exactly one URL: href, src, action, formaction, cite, poster, ...
   * (scheme-checked).
   */
  URL(Escaper.URL),
  /**
   * URL that loads code or a document: {@code <script src>}, {@code <script href>},
   * {@code <script xlink:href>}, {@code <iframe src>},
   * {@code <object data>}, {@code <embed src>}, {@code <base href>},
   * {@code <link href>}. Only a literal-path link expression.
   */
  RESOURCE_URL(Escaper.RESOURCE_URL),
  /**
   * The {@code content} of a {@code <meta http-equiv="refresh">}. Only
   * {@code '<seconds>;url=' + @{/path...}} with a same-origin literal path is accepted; the
   * escaper encodes the characters that end a refresh URL and re-checks the path.
   */
  REFRESH(Escaper.REFRESH),
  /**
   * Inside a JavaScript or JSON {@code <script>} element; values are serialized as JSON
   * ({@code Escapers.javaScript}, or {@code Escapers.json} in a JSON script).
   */
  SCRIPT(Escaper.JSON),
  /**
   * {@code <style>} element or {@code style} attribute. Expressions in a {@code <style>} are a
   * build error; a {@code style} attribute takes only literals, numbers and {@code CssValue}s
   * and is attribute-escaped.
   */
  STYLE(null),
  /**
   * A listed attribute ({@code Contexts}) that takes only literals: {@code target}, a {@code value}
   * off form controls (such as {@code <param>}), and a {@code for} off {@code <label>} and
   * {@code <output>}.
   * The value may only combine string literals and numbers; a String, other plain value or
   * safe type is a build error. Written attribute-escaped.
   */
  TYPED_ATTRIBUTE(null),
  /**
   * A {@code data-*} attribute, or an {@code aria-*} name outside WAI-ARIA 1.2
   * (every other name outside HTML is off the allow-list and FORBIDDEN):
   * client frameworks may run any non-standard attribute as code. Whatever client code reads it gets only a framework-neutral value: an
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
   * Expressions are a build error: the content of a non-JS {@code <script type>} or of a
   * {@code <script>} with a computed type, and the attributes {@code Contexts.forbidden} gives a
   * reason for: {@code srcdoc}, URL lists ({@code ping}, {@code srcset}, {@code imagesrcset}), tree-builder
   * inputs (a {@code <script>}'s or {@code <input>}'s {@code type}, a {@code <meta>}'s
   * {@code http-equiv}, {@code is}, {@code xmlns*}, ...), names that are not plain identifiers, and
   * every attribute of an SVG or MathML element.
   */
  FORBIDDEN(null);

  private final Escaper escaper;

  OutputContext(Escaper escaper) {
    this.escaper = escaper;
  }

  /**
   * The runtime escaper for a value in content in this context, or {@code null} if an expression
   * here must be rejected at build time. SCRIPT's {@code JSON} only admits the value: {@code
   * ScriptJson} picks the JSON or JavaScript escaper itself.
   */
  public Escaper escaper() {
    return escaper;
  }

  /**
   * The runtime escaper for an attribute value in this context,
   * or {@code null} if an expression in the attribute must be rejected at build time. STYLE,
   * TYPED_ATTRIBUTE and NEUTRAL values are restricted at build time and written attribute-escaped.
   */
  public Escaper attributeEscaper() {
    switch (this) {
      case ATTRIBUTE:
      case STYLE:
      case TYPED_ATTRIBUTE:
      case NEUTRAL:
        return Escaper.ATTR;
      case URL:
      case RESOURCE_URL:
      case REFRESH:
        return escaper;
      default:
        return null;
    }
  }
}
