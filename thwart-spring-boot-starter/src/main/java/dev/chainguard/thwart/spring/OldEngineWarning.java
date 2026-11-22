package dev.chainguard.thwart.spring;

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

/**
 * Warns once at startup while a singleton view resolver bean (this context or an ancestor) belongs
 * to another template engine, which renders every view name Thwart doesn't know. Matched by the
 * class or a superclass name, so no engine is needed on the classpath. Resolvers that are not beans
 * themselves (inside {@code ViewResolverRegistry} or a {@code ContentNegotiatingViewResolver}) and
 * JSP are not looked for.
 */
final class OldEngineWarning implements SmartInitializingSingleton, ApplicationContextAware {

  static final String MESSAGE =
      "Thwart: another template engine is still configured (%s); it renders every view name"
          + " Thwart doesn't know, so Thwart's guarantees (no template injection, contextual"
          + " escaping, CSRF token only in same-origin POST forms) cover only Thwart's views"
          + " until it is removed. See the Thwart guide, migrate.md, The tools and the loop, step 6.";

  /** Class-name prefixes of other engines' resolvers, Spring 4.3 / Boot 1.5 through 7 / 4. */
  private static final String[] ENGINES = {
    "org.thymeleaf.",
    "org.springframework.web.servlet.view.freemarker.",
    "org.springframework.web.servlet.view.groovy.",
    "org.springframework.web.servlet.view.script.",
    "org.springframework.web.servlet.view.tiles", // tiles2 and tiles3
    "org.springframework.web.servlet.view.velocity.",
    "org.springframework.boot.autoconfigure.mustache.", // Boot 1.5
    "org.springframework.boot.web.servlet.view.MustacheViewResolver", // Boot 2.0-3.x
    "org.springframework.boot.mustache.", // Boot 4
    "com.mitchellbosecke.pebble.", // Pebble up to 3.1
    "io.pebbletemplates.", // Pebble 3.2+
  };

  private final Log log;
  private ApplicationContext context;

  OldEngineWarning() {
    this(LogFactory.getLog(OldEngineWarning.class));
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
    String message = message(context);
    if (message != null) {
      log.warn(message);
    }
  }

  /** The warning for the view resolver beans of {@code beans}, or {@code null}; creates none. */
  static String message(ListableBeanFactory beans) {
    Set<String> found = new TreeSet<>();
    for (String name :
        BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
            beans, ViewResolver.class, false, false)) {
      Class<?> type = beans.getType(name);
      for (Class<?> c = type; c != null; c = c.getSuperclass()) {
        for (String engine : ENGINES) {
          if (c.getName().startsWith(engine)) {
            found.add(ClassUtils.getUserClass(type).getName());
          }
        }
      }
    }
    return found.isEmpty() ? null : String.format(MESSAGE, String.join(", ", found));
  }
}
