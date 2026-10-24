package dev.tessera.spring;

import java.util.Set;
import java.util.TreeSet;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.web.servlet.ViewResolver;

/**
 * Warns once at startup when the view resolver of another template engine is configured beside
 * Tessera's. That engine renders every view name Tessera doesn't know, with its own injection
 * risks, so Tessera's guarantees cover only Tessera's views until it is removed. Matches the
 * resolver's class (or a superclass) by name, so no engine is needed on the classpath. JSP's
 * {@code InternalResourceViewResolver} is not reported: Boot registers one in every app. There
 * is no switch to silence the warning; removing the engine does.
 */
final class OldEngineWarning implements SmartInitializingSingleton, ApplicationContextAware {

  static final String MESSAGE =
      "Tessera: another template engine is still configured (%s); it renders every view name"
          + " Tessera doesn't know, so Tessera's guarantees (no template injection, contextual"
          + " escaping) cover only Tessera's views until it is removed. See the Tessera guide,"
          + " section 3, step 6.";

  /** Class-name prefixes of other engines' resolvers, Spring 4.3 / Boot 1.5 through 7 / 4. */
  private static final String[] ENGINES = {
    "org.thymeleaf.",
    "org.springframework.web.servlet.view.freemarker.",
    "org.springframework.web.servlet.view.groovy.",
    "org.springframework.web.servlet.view.script.",
    "org.springframework.web.servlet.view.velocity.",
    "org.springframework.boot.autoconfigure.mustache.", // Boot 1.5
    "org.springframework.boot.web.servlet.view.MustacheViewResolver", // Boot 2.0-3.x
    "org.springframework.boot.mustache.", // Boot 4
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
    String message = message(context);
    if (message != null) {
      log.warn(message);
    }
  }

  /**
   * The warning for the resolvers the {@code DispatcherServlet} would find (this context and its
   * ancestors), or {@code null}. Reads bean types only; instantiates nothing.
   */
  static String message(ListableBeanFactory beans) {
    Set<String> found = new TreeSet<>();
    for (String name :
        BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
            beans, ViewResolver.class, false, false)) {
      Class<?> type = beans.getType(name);
      if (type != null && isOtherEngine(type)) {
        found.add(type.getName());
      }
    }
    return found.isEmpty() ? null : String.format(MESSAGE, String.join(", ", found));
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
