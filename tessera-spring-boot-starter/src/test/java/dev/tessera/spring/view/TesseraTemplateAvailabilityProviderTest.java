package dev.tessera.spring.view;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.template.TemplateAvailabilityProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

/**
 * Boot asks the provider whether {@code error/<status>} and {@code error/4xx}, {@code 5xx} views
 * exist (#628). It answers from the compiled templates on the test classpath ({@code
 * src/test/templates}: {@code error.html}, {@code error/404.html}, {@code error/5xx.html}), with
 * the lookup {@link TesseraViewResolver} uses.
 */
class TesseraTemplateAvailabilityProviderTest {

  private final TemplateAvailabilityProvider provider = new TesseraTemplateAvailabilityProvider();
  private final ClassLoader loader = getClass().getClassLoader();

  @Test
  void bootFindsTheProviderInSpringFactories() {
    // The mechanism Boot's TemplateAvailabilityProviders uses on every generation, 1.5 through 4.
    assertThat(
            SpringFactoriesLoader.loadFactories(TemplateAvailabilityProvider.class, loader).stream()
                .anyMatch(p -> p instanceof TesseraTemplateAvailabilityProvider))
        .isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"error/404", "error/5xx", "error", "/error/404", "smoke/page"})
  void compiledTemplatesAreAvailable(String view) {
    assertThat(available(view)).as(view).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "error/500",
        "error/4xx",
        "error/418",
        "error/404.html",
        "templates/error/404",
        "redirect:error/404",
        "forward:error/404",
        "error/404 :: #main",
        ""
      })
  void anythingElseIsNot(String view) {
    assertThat(available(view)).as(view).isFalse();
  }

  @Test
  void nullIsNotAvailable() {
    assertThat(available(null)).isFalse();
  }

  private boolean available(String view) {
    return provider.isTemplateAvailable(
        view, new MockEnvironment(), loader, new DefaultResourceLoader(loader));
  }
}
