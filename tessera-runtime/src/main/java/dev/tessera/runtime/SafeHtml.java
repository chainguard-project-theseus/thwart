package dev.tessera.runtime;

import java.util.Objects;

/**
 * HTML that is known to be safe to emit without escaping. The only ways to get one are
 * {@link #escape(String)}, {@link #sanitize(String)} (or with an {@link HtmlPolicy}), or
 * {@link #concat(SafeHtml...)} of other {@code SafeHtml}s. There is no constructor that skips the
 * check (#664). {@code t:utext} accepts nothing else.
 */
public final class SafeHtml {
  public static final SafeHtml EMPTY = new SafeHtml("");

  private final String html;

  private SafeHtml(String html) {
    this.html = Objects.requireNonNull(html, "html");
  }

  /**
   * HTML-escapes plain text, as {@code t:text} does.
   */
  public static SafeHtml escape(String text) {
    return new SafeHtml(Escapers.escapeHtml(text == null ? "" : text));
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
   * <p>Text is re-escaped as by {@link #escape}, and the output is balanced:
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
