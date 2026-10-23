package dev.tessera.runtime.fn;

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
}
