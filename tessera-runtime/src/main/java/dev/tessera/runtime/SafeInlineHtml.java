package dev.tessera.runtime;

import java.util.Objects;

/**
 * Inline-only HTML that is safe to emit without escaping (#397): balanced phrasing markup from
 * {@link HtmlPolicy#INLINE_ELEMENTS}, with no links, block or interactive elements. Unlike a
 * {@link SafeHtml}, it can stand in a {@code <p>}, a heading, a link or a {@code <button>}, because
 * nothing in it closes an element around it. The only ways to get one are {@link #escape(String)},
 * {@link #sanitize(String, HtmlPolicy)} with a policy from {@link HtmlPolicy#inlineBuilder()}, and
 * {@link #concat(SafeInlineHtml...)}. An HTML message without links
 * ({@link Escapers#htmlMessage}) is one too, sanitized by an inline policy.
 */
public final class SafeInlineHtml {
  private final String html;

  private SafeInlineHtml(String html) {
    this.html = Objects.requireNonNull(html, "html");
  }

  /** HTML-escapes plain text, as {@code t:text} does. */
  public static SafeInlineHtml escape(String text) {
    return new SafeInlineHtml(Escapers.escapeHtml(text == null ? "" : text));
  }

  /**
   * Cleans untrusted HTML with an inline {@code policy} (from {@link HtmlPolicy#inlineBuilder()});
   * any other policy throws {@link IllegalArgumentException}. {@code null} gives empty HTML.
   */
  public static SafeInlineHtml sanitize(String html, HtmlPolicy policy) {
    if (!policy.inline) {
      throw new IllegalArgumentException("SafeInlineHtml.sanitize needs a policy from HtmlPolicy.inlineBuilder()");
    }
    return new SafeInlineHtml(html == null ? "" : HtmlSanitizer.sanitize(html, policy));
  }

  /** Joins safe parts in order; {@code null} parts are skipped. */
  public static SafeInlineHtml concat(SafeInlineHtml... parts) {
    StringBuilder sb = new StringBuilder();
    for (SafeInlineHtml part : parts) {
      if (part != null) {
        sb.append(part.html);
      }
    }
    return new SafeInlineHtml(sb.toString());
  }

  public String html() {
    return html;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof SafeInlineHtml && html.equals(((SafeInlineHtml) o).html);
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
