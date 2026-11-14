package dev.chainguard.thwart.spring.view;

import dev.chainguard.thwart.runtime.TemplateRegistry;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.template.TemplateAvailabilityProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;

/**
 * Tells Boot which view names are Thwart templates, so that Boot's {@code
 * DefaultErrorViewResolver} renders {@code error/404.html}, {@code error/4xx.html}
 * and the like.
 *
 * <p>Boot passes the application context as {@code resourceLoader}. Then a name is available
 * exactly when one of the context's {@link ThwartViewResolver} beans (ancestors included) would
 * resolve it, so the answer comes from the registry those resolvers hold, including an
 * application's own {@code TemplateRegistry} bean. A context without a Thwart resolver has no
 * Thwart templates. Given a {@code resourceLoader} that is not a bean factory (no Boot caller
 * passes one), it looks the name up, as the resolver would, in the default registry: {@link
 * TemplateRegistry#load} over {@code classLoader}. Boot caches the answer per view name unless
 * {@code spring.template.provider.cache} is off (DevTools); the bean path does not rerun the
 * {@code ServiceLoader} scan per call.
 *
 * <p>Registered in {@code META-INF/spring.factories}, which Boot 1.5 through 4 read for this key
 * through {@code SpringFactoriesLoader}. Nothing else refers to this class, so Spring MVC without
 * Boot never loads it.
 */
public final class ThwartTemplateAvailabilityProvider implements TemplateAvailabilityProvider {

  @Override
  public boolean isTemplateAvailable(
      String view, Environment environment, ClassLoader classLoader, ResourceLoader resourceLoader) {
    if (view == null) {
      return false;
    }
    if (resourceLoader instanceof ListableBeanFactory) {
      // No eager init of FactoryBeans or lazy beans just to answer this.
      for (ThwartViewResolver resolver :
          BeanFactoryUtils.beansOfTypeIncludingAncestors(
                  (ListableBeanFactory) resourceLoader, ThwartViewResolver.class, true, false)
              .values()) {
        if (resolver.resolves(view)) {
          return true;
        }
      }
      return false;
    }
    return TemplateRegistry.load(classLoader)
        .find(ThwartViewResolver.lookupName(view))
        .isPresent();
  }
}
