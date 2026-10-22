package dev.tessera.spring.view;

import dev.tessera.runtime.TemplateRegistry;
import java.util.Locale;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;

/**
 * Resolves a view name by exact lookup in the build-time {@link TemplateRegistry}. The name is
 * never parsed, concatenated or normalized: anything that is not exactly a compiled template's
 * name (including {@code redirect:} and {@code forward:} names, which no template file can be
 * called) yields {@code null} so the next resolver runs.
 */
public final class TesseraViewResolver implements ViewResolver, Ordered {

  /**
   * Before Thymeleaf's resolver ({@code LOWEST_PRECEDENCE - 5}) and Boot's {@code
   * BeanNameViewResolver} ({@code LOWEST_PRECEDENCE - 10}).
   */
  public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 20;

  private final TemplateRegistry registry;
  private final RenderContextFactory contexts;

  public TesseraViewResolver(TemplateRegistry registry, RenderContextFactory contexts) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.contexts = Objects.requireNonNull(contexts, "contexts");
  }

  @Override
  public View resolveViewName(String viewName, Locale locale) {
    if (viewName == null) {
      return null;
    }
    return registry.find(viewName).map(e -> TesseraView.of(e, contexts)).orElse(null);
  }

  @Override
  public int getOrder() {
    return ORDER;
  }
}
