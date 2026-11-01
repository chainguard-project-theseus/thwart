package dev.tessera.spring.view;

import dev.tessera.runtime.TemplateRegistry;
import org.springframework.boot.autoconfigure.template.TemplateAvailabilityProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;

/**
 * Tells Boot which view names are compiled Tessera templates, so that Boot's {@code
 * DefaultErrorViewResolver} renders {@code error/404.html}, {@code error/4xx.html} and the like
 * (#628). A name is available exactly when {@link TesseraViewResolver} would resolve it: the same
 * lookup name ({@code TesseraViewResolver.lookupName}) in the registry the auto-configuration loads
 * ({@link TemplateRegistry#load} over the application's class loader). Boot caches the answer per
 * view name.
 *
 * <p>Registered in {@code META-INF/spring.factories}, which Boot 1.5 through 4 read for this key
 * through {@code SpringFactoriesLoader}. Nothing else refers to this class, so Spring MVC without
 * Boot never loads it.
 */
public final class TesseraTemplateAvailabilityProvider implements TemplateAvailabilityProvider {

  @Override
  public boolean isTemplateAvailable(
      String view, Environment environment, ClassLoader classLoader, ResourceLoader resourceLoader) {
    return view != null
        && TemplateRegistry.load(classLoader)
            .find(TesseraViewResolver.lookupName(view))
            .isPresent();
  }
}
