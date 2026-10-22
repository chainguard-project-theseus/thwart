package dev.tessera.spring.view;

import dev.tessera.runtime.RenderContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;

/**
 * Builds the {@link RenderContext} for one request. The auto-configuration defaults to {@code
 * SpringRenderContexts::create}; declare your own bean of this type to replace it.
 */
@FunctionalInterface
public interface RenderContextFactory {
  RenderContext create(
      HttpServletRequest request, HttpServletResponse response, Map<String, ?> model);
}
