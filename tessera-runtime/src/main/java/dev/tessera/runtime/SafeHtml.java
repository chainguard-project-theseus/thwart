package dev.tessera.runtime;

import java.util.Objects;

/**
 * HTML that is known to be safe to emit without escaping. The only ways to get one are
 * {@link #escape(String)}, {@link #sanitize(String)} (or with an {@link HtmlPolicy}), rendering a
 * {@link Template} the compiler verified as a {@code FLOW} partial ({@link #of}),
 * {@link #concat(SafeHtml...)} of other {@code SafeHtml}s, or {@link #unchecked(String)}
 * (greppable, lint-bannable). {@code t:utext} accepts nothing else.
 */
public final class SafeHtml {
  public static final SafeHtml EMPTY = new SafeHtml("");

  private final String html;

  private SafeHtml(String html) {
    this.html = Objects.requireNonNull(html, "html");
  }

  /** Wraps markup the caller vouches for. Every call site is a security review point. */
  public static SafeHtml unchecked(String html) {
    return new SafeHtml(html);
  }

  /**
   * HTML-escapes plain text, as {@code t:text} does: {@code {{} is broken with an empty comment
   * so client-side frameworks do not interpolate it (#248).
   */
  public static SafeHtml escape(String text) {
    return new SafeHtml(Escapers.escapeText(text == null ? "" : text));
  }

  /**
   * Cleans untrusted HTML, such as stored rich text, with {@link HtmlPolicy#BASIC}. The input is
   * parsed and the output rebuilt, never copied through:
   *
   * <ul>
   *   <li>Kept, without attributes: {@code p br hr h1}-{@code h6 b i em strong u s sub sup small
   *       mark code pre blockquote ul ol li span div}.
   *   <li>{@code a} keeps only an {@code href} that passes the URL attribute scheme check
   *       ({@code http}, {@code https}, {@code mailto}, {@code tel} or relative) and then gets
   *       {@code rel="nofollow noopener"}.
   *   <li>Removed with their content: {@code script style iframe object embed template svg math
   *       noscript textarea title xmp plaintext noembed noframes select}.
   *   <li>Any other tag is removed and its text kept. Comments, doctypes, CDATA and every other
   *       attribute ({@code on*}, {@code style}, {@code class}, {@code id}, ...) are removed.
   * </ul>
   *
   * <p>Text is re-escaped as by {@link #escape} ({@code {{} broken), and the output is balanced:
   * every element is closed, and placed only where a browser keeps it as written (see
   * {@link HtmlPolicy}). Malformed markup never fails. {@code null} gives {@link #EMPTY}.
   */
  public static SafeHtml sanitize(String html) {
    return sanitize(html, HtmlPolicy.BASIC);
  }

  /**
   * Cleans untrusted HTML with {@code policy} ({@link HtmlPolicy#BASIC}, {@link HtmlPolicy#RICH}
   * or one from {@link HtmlPolicy#builder()}), as {@link #sanitize(String)} does with its fixed
   * policy. {@code null} gives {@link #EMPTY}.
   */
  public static SafeHtml sanitize(String html, HtmlPolicy policy) {
    Objects.requireNonNull(policy, "policy");
    return html == null ? EMPTY : new SafeHtml(HtmlSanitizer.sanitize(html, policy));
  }

  /** Joins safe parts in order; {@code null} parts are skipped. */
  public static SafeHtml concat(SafeHtml... parts) {
    StringBuilder sb = new StringBuilder();
    for (SafeHtml part : parts) {
      if (part != null) {
        sb.append(part.html);
      }
    }
    return new SafeHtml(sb.toString());
  }

  /**
   * Renders a template; its output is escaped by construction. The template must be one the
   * compiler proved a {@code FLOW} partial ({@link Template#verifiedFlowPartial()}), so that its
   * markup can't close, reopen or adopt elements around the place the value is inserted.
   *
   * @throws IllegalArgumentException if it isn't (a page, a partial whose root is an {@code <li>}
   *     or a table row, one with a {@code <form>}, ...), naming the template and the fix
   */
  public static SafeHtml of(Template template, RenderContext ctx) {
    return new SafeHtml(requireFlowPartial(template, "SafeHtml.of").renderToString(ctx));
  }

  /** {@code template}, if it is a verified {@code FLOW} partial; else the error for {@code api}. */
  static Template requireFlowPartial(Template template, String api) {
    Objects.requireNonNull(template, "template");
    if (!template.verifiedFlowPartial()) {
      throw new IllegalArgumentException(
          "Tessera: " + api + " needs a template the compiler verified as a FLOW partial, and template '"
              + template.templateName() + "' (" + template.getClass().getName() + ") is not one: it"
              + " is a page, or its markup could close or reopen elements around the place it is"
              + " inserted. Fix: make it a partial of flow content, by wrapping its root in a <div>"
              + " (list items in their own <ul>, <ol> or <dl>, table rows in their own <table>),"
              + " with no <form> and no formatting element such as <b> inside another of the same"
              + " name; or render it as a page through a view instead of wrapping it (#396,"
              + " docs/guide.md section 4.9)");
    }
    return template;
  }

  public String html() {
    return html;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof SafeHtml && html.equals(((SafeHtml) o).html);
  }

  @Override
  public int hashCode() {
    return html.hashCode();
  }

  @Override
  public String toString() {
    return html;
  }
}
