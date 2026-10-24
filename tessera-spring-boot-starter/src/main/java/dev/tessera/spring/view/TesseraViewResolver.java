package dev.tessera.spring.view;

import dev.tessera.runtime.TemplateRegistry;
import java.util.Locale;
import java.util.Objects;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;

/**
 * Resolves a view name by exact lookup in the build-time {@link TemplateRegistry}. The name is
 * never parsed, concatenated or normalized: anything that is not exactly a compiled template's
 * name (including {@code redirect:} and {@code forward:} names, which no template file can be
 * called) yields {@code null} so the next resolver runs. Construction logs how many compiled
 * templates the registry holds, and warns when there are none, since then every view silently
 * falls through.
 */
public final class TesseraViewResolver implements ViewResolver, Ordered {

  /**
   * Before Thymeleaf's resolver ({@code LOWEST_PRECEDENCE - 5}) and Boot's {@code
   * BeanNameViewResolver} ({@code LOWEST_PRECEDENCE - 10}).
   */
  public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 20;

  static final String NO_TEMPLATES =
      "No compiled Tessera templates on the classpath: is"
          + " META-INF/services/dev.tessera.runtime.TemplateRegistry packaged?";

  private static final Log LOG = LogFactory.getLog(TesseraViewResolver.class);

  private final TemplateRegistry registry;
  private final RenderContextFactory contexts;

  public TesseraViewResolver(TemplateRegistry registry, RenderContextFactory contexts) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.contexts = Objects.requireNonNull(contexts, "contexts");
    logTemplateCount(registry, LOG);
  }

  static void logTemplateCount(TemplateRegistry registry, Log log) {
    int count = registry.all().size();
    if (count == 0) {
      log.warn(NO_TEMPLATES);
    } else {
      log.info("Found " + count + " compiled Tessera template" + (count == 1 ? "" : "s"));
    }
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
