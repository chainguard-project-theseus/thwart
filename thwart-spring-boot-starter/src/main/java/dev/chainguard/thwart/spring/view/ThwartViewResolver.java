package dev.chainguard.thwart.spring.view;

import dev.chainguard.thwart.runtime.internal.TemplateEntry;
import dev.chainguard.thwart.runtime.TemplateRegistry;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;

/**
 * Resolves a view name by one exact lookup in the build-time {@link TemplateRegistry}. The name is
 * never parsed, evaluated or normalized, except that one leading {@code /} is dropped (Thymeleaf
 * and FreeMarker put their prefix in front of the name, so {@code "/auth/register"} finds the same
 * file as {@code "auth/register"}) and that the spaces around a {@code ::} are made single
 * ({@link #lookupName}), so {@code "owners/list::rows"} finds the fragment entry {@code
 * owners/list :: rows}. Anything else that is not exactly a compiled template's or
 * fragment entry's name (including {@code redirect:} and {@code forward:} names, which no template
 * file can be called, and fragment selectors such as {@code page :: #id} or {@code page ::
 * rows(x)}) yields {@code null} so the next resolver runs. Construction logs how many compiled
 * templates the registry holds, and warns when there are none, since then every view silently
 * falls through.
 *
 * <p>Its views send a strict {@code Content-Security-Policy} ({@link ThwartView}) unless the
 * property {@code thwart.csp.enabled} is {@code false}.
 */
public final class ThwartViewResolver implements ViewResolver, Ordered, EnvironmentAware {

  /** The one switch for the default policy header. */
  static final String CSP_ENABLED = "thwart.csp.enabled";

  /**
   * Before Thymeleaf's resolver ({@code LOWEST_PRECEDENCE - 5}) and Boot's {@code
   * BeanNameViewResolver} ({@code LOWEST_PRECEDENCE - 10}).
   */
  public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 20;

  static final String NO_TEMPLATES =
      "No compiled Thwart templates on the classpath: is"
          + " META-INF/services/dev.chainguard.thwart.runtime.TemplateRegistry packaged?";

  private static final Log LOG = LogFactory.getLog(ThwartViewResolver.class);

  private final TemplateRegistry registry;
  private final RenderContextFactory contexts;
  /** One view per registry entry, built on first resolve (a view is stateless). */
  private final ConcurrentMap<TemplateEntry, ThwartView> views = new ConcurrentHashMap<>();
  /** From {@link #CSP_ENABLED}, set before the first resolve; on for a resolver outside Spring. */
  private boolean csp = true;

  public ThwartViewResolver(TemplateRegistry registry, RenderContextFactory contexts) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.contexts = Objects.requireNonNull(contexts, "contexts");
    logTemplateCount(registry, LOG);
  }

  static void logTemplateCount(TemplateRegistry registry, Log log) {
    int count = registry.all().size();
    if (count == 0) {
      log.warn(NO_TEMPLATES);
    } else {
      log.info("Found " + count + " compiled Thwart template" + (count == 1 ? "" : "s"));
    }
  }

  @Override
  public void setEnvironment(Environment environment) {
    csp = environment.getProperty(CSP_ENABLED, Boolean.class, true);
  }

  @Override
  public View resolveViewName(String viewName, Locale locale) {
    if (viewName == null) {
      return null;
    }
    TemplateEntry entry = registry.find(lookupName(viewName)).orElse(null);
    return entry == null
        ? null
        : views.computeIfAbsent(entry, e -> ThwartView.of(e, contexts, csp));
  }

  /**
   * Whether {@link #resolveViewName} finds a template for {@code viewName}, without building a
   * view ({@link ThwartTemplateAvailabilityProvider}).
   */
  boolean resolves(String viewName) {
    return viewName != null && registry.find(lookupName(viewName)).isPresent();
  }

  /**
   * The registry name {@code viewName} is looked up by: {@code viewName} less one leading {@code /}.
   * A name with {@code ::} is cut at the first one into two literal halves, each trimmed as
   * Thymeleaf trims them ({@link String#trim}), the page half less one leading {@code /}, and joined
   * as {@code page :: fragment}, the name of a fragment entry. Nothing is evaluated: a selector,
   * arguments or a second {@code ::} stay in the name, which then matches nothing.
   */
  static String lookupName(String viewName) {
    int sep = viewName.indexOf("::");
    if (sep < 0) {
      return dropSlash(viewName);
    }
    return dropSlash(viewName.substring(0, sep).trim()) + " :: " + viewName.substring(sep + 2).trim();
  }

  private static String dropSlash(String name) {
    return name.startsWith("/") ? name.substring(1) : name;
  }

  @Override
  public int getOrder() {
    return ORDER;
  }
}
