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
   * {@code {{} is not broken: a comment would show as text there.
   */
  RCDATA("rcdata"),
  /** Quoted value of an ordinary attribute. Generated attributes are always double-quoted. */
  ATTRIBUTE("attr"),
  /** URL-valued attribute: href, src, action, formaction, cite, poster, ... (scheme-checked). */
  URL("url"),
  /**
   * The {@code values} of an SVG {@code <animate>}: a {@code ;}-separated list that SVG applies
   * item by item, so every item is scheme-checked as in URL (#284). {@code <set>} ignores
   * {@code values}, but it is checked the same way.
   */
  URL_LIST("urlList"),
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
  /** Inside a JavaScript {@code <script>} element; values are serialized as JSON. */
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
   * {@code on*} event-handler attribute, or a client-side framework directive whose value runs as
   * JavaScript ({@code v-*}, {@code x-*}, {@code hx-on*}, ...; see
   * {@code Contexts.isFrameworkDirective}): expressions are a build error.
   */
  EVENT_HANDLER(null),
  /**
   * Anything else we cannot escape safely ({@code srcdoc}, non-JS {@code <script type>},
   * attribute names that are not plain identifiers): expressions are a build error.
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
