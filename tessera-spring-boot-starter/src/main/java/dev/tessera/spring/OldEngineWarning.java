package dev.tessera.spring;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.ViewResolver;
import org.springframework.web.servlet.view.ContentNegotiatingViewResolver;
import org.springframework.web.servlet.view.ViewResolverComposite;

/**
 * Warns once at startup when the view resolver of another template engine is configured beside
 * Tessera's. That engine renders every view name Tessera doesn't know, with its own injection
 * risks, so Tessera's guarantees cover only Tessera's views until it is removed. Matches the
 * resolver's class (or a superclass) by name, so no engine is needed on the classpath, and names
 * it without a CGLIB proxy suffix. Resolvers registered through {@code ViewResolverRegistry} are
 * found inside {@code mvcViewResolver}. JSP's {@code InternalResourceViewResolver} is not
 * reported, since Boot registers one in every app (#316); a JSP application that renders through
 * Tiles is reported by its {@code TilesViewResolver}. There is no switch to silence the warning;
 * removing the engine does.
 */
final class OldEngineWarning implements SmartInitializingSingleton, ApplicationContextAware {

  static final String MESSAGE =
      "Tessera: another template engine is still configured (%s); it renders every view name"
          + " Tessera doesn't know, so Tessera's guarantees (no template injection, contextual"
          + " escaping, CSRF token only in same-origin POST forms) cover only Tessera's views"
          + " until it is removed. See the Tessera guide, section 3, step 6.";

  /** Class-name prefixes of other engines' resolvers, Spring 4.3 / Boot 1.5 through 7 / 4. */
  private static final String[] ENGINES = {
    "org.thymeleaf.",
    "org.springframework.web.servlet.view.freemarker.",
    "org.springframework.web.servlet.view.groovy.",
    "org.springframework.web.servlet.view.script.",
    "org.springframework.web.servlet.view.tiles2.", // Spring 4.3
    "org.springframework.web.servlet.view.tiles3.", // Spring 4.3-5.3
    "org.springframework.web.servlet.view.velocity.",
    "org.springframework.boot.autoconfigure.mustache.", // Boot 1.5
    "org.springframework.boot.web.servlet.view.MustacheViewResolver", // Boot 2.0-3.x
    "org.springframework.boot.mustache.", // Boot 4
    "com.mitchellbosecke.pebble.", // Pebble up to 3.1 (pebble-spring3/4/5)
    "io.pebbletemplates.", // Pebble 3.2+ (pebble-spring5/6/7, both Boot starters)
    // TODO(#316): report JSP's InternalResourceViewResolver unless it is Boot's
    // defaultViewResolver.
  };

  private static final Log LOG = LogFactory.getLog(OldEngineWarning.class);

  private final Log log;
  private ApplicationContext context;

  OldEngineWarning() {
    this(LOG);
  }

  OldEngineWarning(Log log) {
    this.log = log;
  }

  @Override
  public void setApplicationContext(ApplicationContext context) {
    this.context = context;
  }

  @Override
  public void afterSingletonsInstantiated() {
    String message;
    try {
      message = message(context);
    } catch (RuntimeException e) {
      // Only a warning: a resolver that cannot be inspected must not fail the application.
      log.debug("Tessera: could not inspect the view resolvers", e);
      return;
    }
    if (message != null) {
      log.warn(message);
    }
  }

  /**
   * The warning for the singleton view resolvers of this context and its ancestors, as the {@code
   * DispatcherServlet} finds them, or {@code null}. Reads bean types without instantiating
   * anything, except the composites that hold resolvers which are not beans themselves: the
   * {@code mvcViewResolver} of {@code @EnableWebMvc} (engines added through {@code
   * ViewResolverRegistry}, such as {@code registry.freeMarker()}) and {@code
   * ContentNegotiatingViewResolver}.
   */
  static String message(ListableBeanFactory beans) {
    Set<String> found = new TreeSet<>();
    Set<ViewResolver> seen =
        Collections.newSetFromMap(new IdentityHashMap<ViewResolver, Boolean>());
    // Singletons only (the DispatcherServlet also takes prototype and scoped resolvers): fetching
    // one of those here could create it outside its scope.
    for (String name :
        BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
            beans, ViewResolver.class, false, false)) {
      Class<?> type = beans.getType(name);
      if (type == null) {
        continue;
      }
      if (isOtherEngine(type)) {
        found.add(ClassUtils.getUserClass(type).getName());
      } else if (ViewResolverComposite.class.isAssignableFrom(type)
          || ContentNegotiatingViewResolver.class.isAssignableFrom(type)) {
        addDelegates(beans.getBean(name, ViewResolver.class), found, seen);
      }
    }
    return found.isEmpty() ? null : String.format(MESSAGE, String.join(", ", found));
  }

  private static void addDelegates(
      ViewResolver resolver, Set<String> found, Set<ViewResolver> seen) {
    if (!seen.add(resolver)) {
      return;
    }
    List<ViewResolver> delegates;
    if (resolver instanceof ViewResolverComposite) {
      delegates = ((ViewResolverComposite) resolver).getViewResolvers();
    } else if (resolver instanceof ContentNegotiatingViewResolver) {
      try {
        delegates = ((ContentNegotiatingViewResolver) resolver).getViewResolvers();
      } catch (NullPointerException e) {
        // Spring 4.3 wraps a list that is not set yet: no delegates.
        return;
      }
    } else {
      if (isOtherEngine(resolver.getClass())) {
        found.add(ClassUtils.getUserClass(resolver).getName());
      }
      return;
    }
    if (delegates != null) {
      for (ViewResolver delegate : delegates) {
        addDelegates(delegate, found, seen);
      }
    }
  }

  private static boolean isOtherEngine(Class<?> type) {
    for (Class<?> c = type; c != null; c = c.getSuperclass()) {
      for (String engine : ENGINES) {
        if (c.getName().startsWith(engine)) {
          return true;
        }
      }
    }
    return false;
  }
}
