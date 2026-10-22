package dev.tessera.spring.context;

import dev.tessera.runtime.RenderContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;

/**
 * Builds the per-request {@link RenderContext}: locale from the LocaleResolver, messages from
 * the MessageSource, links with the request context path (and {@code response.encodeURL}),
 * forms from the model's {@code BindingResult}s, and the CSRF token request attribute.
 */
public final class SpringRenderContexts {
  private SpringRenderContexts() {}

  public static RenderContext create(
      HttpServletRequest request, HttpServletResponse response, Map<String, ?> model) {
    throw new UnsupportedOperationException("TODO(#8): SpringRenderContexts.create");
  }
}
