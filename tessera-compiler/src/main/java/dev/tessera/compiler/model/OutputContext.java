package dev.tessera.compiler.model;

/**
 * Where an expression's value lands in the HTML output. Decided by the parser
 * ({@code dev.tessera.compiler.html.Contexts}), never by the template author. Each context maps
 * to exactly one runtime escaper; contexts without an escaper are build errors.
 */
public enum OutputContext {
  /** Element text, including RCDATA ({@code <title>}, {@code <textarea>}). */
  TEXT("text"),
  /** Quoted value of an ordinary attribute. Generated attributes are always double-quoted. */
  ATTRIBUTE("attr"),
  /** URL-valued attribute: href, src, action, formaction, cite, poster, ... (scheme-checked). */
  URL("url"),
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
