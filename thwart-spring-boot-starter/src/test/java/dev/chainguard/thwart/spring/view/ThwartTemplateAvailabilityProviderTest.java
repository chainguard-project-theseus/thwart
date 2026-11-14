package dev.chainguard.thwart.spring.view;

import static org.assertj.core.api.Assertions.assertThat;

import dev.chainguard.thwart.runtime.internal.TemplateEntry;
import dev.chainguard.thwart.runtime.TemplateRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.template.TemplateAvailabilityProvider;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

/**
 * Boot asks the provider whether {@code error/<status>} and {@code error/4xx}, {@code 5xx} views
 * exist (#628). It answers with the lookup {@link ThwartViewResolver} uses: from the context's
 * Thwart resolvers when Boot passes the application context, else from the compiled templates on
 * the test classpath ({@code src/test/templates}: {@code error.html}, {@code error/404.html},
 * {@code error/5xx.html}).
 */
class ThwartTemplateAvailabilityProviderTest {

  private static final RenderContextFactory CONTEXTS = (request, response, model) -> null;

  private final TemplateAvailabilityProvider provider = new ThwartTemplateAvailabilityProvider();
  private final ClassLoader loader = getClass().getClassLoader();

  @Test
  void bootFindsTheProviderInSpringFactories() {
    // The mechanism Boot's TemplateAvailabilityProviders uses on every generation, 1.5 through 4.
    assertThat(
            SpringFactoriesLoader.loadFactories(TemplateAvailabilityProvider.class, loader).stream()
                .anyMatch(p -> p instanceof ThwartTemplateAvailabilityProvider))
        .isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"error/404", "error/5xx", "error", "/error/404", "smoke/page"})
  void compiledTemplatesAreAvailable(String view) {
    assertThat(available(view, new DefaultResourceLoader(loader))).as(view).isTrue();
    try (GenericApplicationContext ctx = withResolver(TemplateRegistry.load(loader))) {
      assertThat(available(view, ctx)).as(view).isTrue();
    }
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
    assertThat(available(view, new DefaultResourceLoader(loader))).as(view).isFalse();
    try (GenericApplicationContext ctx = withResolver(TemplateRegistry.load(loader))) {
      assertThat(available(view, ctx)).as(view).isFalse();
    }
  }

  @Test
  void nullIsNotAvailable() {
    assertThat(available(null, new DefaultResourceLoader(loader))).isFalse();
  }

  @Test
  void userRegistryWithoutTheTemplateWins() {
    // The class path has error/404, the application's own registry does not: the resolver would
    // not render it, so Boot must fall back to its "error" view.
    List<TemplateEntry> entries = new ArrayList<>();
    for (TemplateEntry e : TemplateRegistry.load(loader).all()) {
      if (!e.name().equals("error/404")) {
        entries.add(e);
      }
    }
    try (GenericApplicationContext ctx = withResolver(TemplateRegistry.of(entries))) {
      assertThat(available("error/404", ctx)).isFalse();
      assertThat(available("error/5xx", ctx)).isTrue();
    }
  }

  @Test
  void userRegistryWithTheTemplateWins() {
    // Only the application's registry has error/418.
    TemplateEntry teapot =
        new TemplateEntry(
            "error/418", Collections.<TemplateEntry.Param>emptyList(), (model, ctx, out) -> {});
    try (GenericApplicationContext ctx =
        withResolver(TemplateRegistry.of(Collections.singletonList(teapot)))) {
      assertThat(available("error/418", ctx)).isTrue();
      assertThat(available("error/404", ctx)).isFalse();
    }
  }

  @Test
  void contextWithoutThwartResolverHasNoTemplates() {
    try (GenericApplicationContext ctx = new GenericApplicationContext()) {
      ctx.refresh();
      assertThat(available("error/404", ctx)).isFalse();
    }
  }

  @Test
  void resolverInParentContextCounts() {
    try (GenericApplicationContext parent = withResolver(TemplateRegistry.load(loader));
        GenericApplicationContext child = new GenericApplicationContext(parent)) {
      child.refresh();
      assertThat(available("error/404", child)).isTrue();
    }
  }

  private static GenericApplicationContext withResolver(TemplateRegistry registry) {
    GenericApplicationContext ctx = new GenericApplicationContext();
    ctx.getBeanFactory()
        .registerSingleton("thwartViewResolver", new ThwartViewResolver(registry, CONTEXTS));
    ctx.refresh();
    return ctx;
  }

  private boolean available(String view, ResourceLoader resourceLoader) {
    return provider.isTemplateAvailable(view, new MockEnvironment(), loader, resourceLoader);
  }
}
