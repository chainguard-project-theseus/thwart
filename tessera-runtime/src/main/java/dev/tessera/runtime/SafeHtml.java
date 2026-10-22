package dev.tessera.runtime;

import java.util.Objects;

/**
 * HTML that is known to be safe to emit without escaping. The only ways to get one are
 * {@link #escape(String)}, rendering a {@link Template}, or {@link #unchecked(String)} (greppable,
 * lint-bannable). {@code t:utext} accepts nothing else.
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

  /** HTML-escapes plain text. */
  public static SafeHtml escape(String text) {
    return new SafeHtml(Escapers.escapeHtml(text == null ? "" : text));
  }

  /** Renders a template; its output is escaped by construction. */
  public static SafeHtml of(Template template, RenderContext ctx) {
    return new SafeHtml(template.renderToString(ctx));
  }

  public String html() {
    return html;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof SafeHtml other && html.equals(other.html);
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
