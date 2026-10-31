package dev.tessera.compiler.model;

/**
 * Where an expression's value lands in the HTML output. Decided by the parser
 * ({@code dev.tessera.compiler.html.Contexts}), never by the template author. Each context maps
 * to exactly one runtime escaper; contexts without an escaper are build errors.
 */
public enum OutputContext {
  /**
   * Element text. The escaper also breaks {@code {{} with an empty comment, so client-side
   * frameworks that compile the DOM as a template do not interpolate values (#248).
   */
  TEXT("text"),
  /**
   * Text in {@code <title>} or {@code <textarea>} (RCDATA, #248). Escaped like TEXT, but
   * {@code {{} is not broken: a comment would show as text there, so the generator adds
   * {@code v-pre} to a computed {@code <title>} or {@code <textarea>} (#549). The parser never
   * picks it: the verifier names this escaper for TEXT where a browser reads RCDATA (#378).
   */
  RCDATA("rcdata"),
  /**
   * Quoted value of a plain-text attribute: an inert-text attribute ({@code inert-attributes.txt}),
   * a named {@code <meta>}'s {@code content}, {@code data-*} or a custom element's prop. Generated
   * attributes are always double-quoted.
   */
  ATTRIBUTE("attr"),
  /**
   * An attribute whose grammar is exactly one URL: href, src, action, formaction, cite, poster, ...
   * (scheme-checked).
   */
  URL("url"),
  /**
   * An attribute that holds URLs but is not exactly one URL (#539): a list ({@code ping},
   * {@code srcset}, {@code imagesrcset}) or the {@code values to from by} of an SVG
   * {@code <animate>} or {@code <set>}, whose meaning depends on its {@code attributeName}. The
   * URL escaper checks one URL, so the value may only combine string literals, numbers and
   * {@code TrustedUrl}s; a String or other plain value is a build error. Written attribute-escaped.
   */
  TRUSTED_URL(null),
  /**
   * URL that loads code or a document: {@code <script src>}, {@code <script href>},
   * {@code <script xlink:href>}, {@code <iframe src>},
   * {@code <object data>}, {@code <embed src>}, {@code <base href>},
   * {@code <link href>}. Only a literal-path link expression or a {@code TrustedUrl}.
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
   * Value of a Vue/Alpine bind attribute ({@code v-bind:x}, {@code :x}, {@code x-bind:x}) whose
   * target {@code x} is an ordinary attribute (#246; see {@code Contexts.isJsonBinding}). The
   * framework evaluates the value as a JavaScript expression, so it is serialized as JSON exactly
   * as in {@code <script>} (a string is always a string literal) and then attribute-escaped.
   */
  SCRIPT_ATTRIBUTE("jsonAttr"),
  /**
   * {@code <style>} element or {@code style} attribute. Expressions in a {@code <style>} are a
   * build error; a {@code style} attribute takes only literals, numbers and {@code CssValue}s
   * (#182) and is attribute-escaped.
   */
  STYLE(null),
  /**
   * A standard attribute that is not an inert-text attribute ({@code inert-attributes.txt}) and
   * has no more specific context, such as {@code sandbox}, {@code rel}, {@code target}, an SVG
   * {@code transform} or {@code x}. The value may only combine string literals and numbers (#565);
   * a String, other plain value or safe type is a build error. Written attribute-escaped.
   */
  TYPED_ATTRIBUTE(null),
  /**
   * {@code on*} event-handler attribute, or a client-side framework directive whose value runs as
   * JavaScript ({@code v-*}, {@code x-*}, {@code hx-on*}, ...; see
   * {@code Contexts.isFrameworkDirective}): expressions are a build error.
   */
  EVENT_HANDLER(null),
  /**
   * Anything else we cannot escape safely ({@code srcdoc}, non-JS {@code <script type>}), an
   * attribute that decides how others are read (a {@code <script>}'s {@code type}, a
   * {@code <meta>}'s {@code http-equiv}, an animation's {@code attributeName}, {@code hx-boost},
   * {@code hx-ws}, {@code hx-sse}, tree-builder inputs), and names that are not plain identifiers
   * or not known (#330): expressions are a build error.
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
}
