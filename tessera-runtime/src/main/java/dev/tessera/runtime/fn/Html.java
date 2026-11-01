package dev.tessera.runtime.fn;

import dev.tessera.runtime.HtmlPolicy;
import dev.tessera.runtime.SafeHtml;
import dev.tessera.runtime.TemplateFunction;
import dev.tessera.runtime.TemplateFunctions;

/** Built-in {@code html} namespace. Always registered. */
@TemplateFunctions("html")
public final class Html {
  private Html() {}

  /**
   * {@code target}'s text cleaned by {@link SafeHtml#sanitize}, for {@code t:utext}: the
   * replacement for {@code th:utext} on stored rich text. {@code null} renders nothing.
   */
  @TemplateFunction
  public static SafeHtml sanitize(Object target) {
    return SafeHtml.sanitize(target == null ? null : target.toString());
  }

  /**
   * {@code target}'s text cleaned with {@link HtmlPolicy#RICH}: as {@link #sanitize}, plus
   * same-origin images, {@code language-*} classes, prefixed {@code id}, {@code title} and
   * {@code target="_blank"}, for stored editor or Markdown output. {@code null} renders nothing.
   */
  @TemplateFunction
  public static SafeHtml sanitizeRich(Object target) {
    return SafeHtml.sanitize(target == null ? null : target.toString(), HtmlPolicy.RICH);
  }
}
