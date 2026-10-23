package dev.tessera.spring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.TemplateEntry;
import dev.tessera.runtime.TemplateRegistry;
import dev.tessera.spring.view.RenderContextFactory;
import dev.tessera.spring.view.TesseraViewResolver;
import java.util.Collections;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/**
 * The auto-configuration's conditions. Contexts are built by hand, not with {@code
 * ApplicationContextRunner} (Boot 2.0+); {@code @ImportAutoConfiguration} still applies {@link
 * TesseraAutoConfiguration} after the user configuration, as auto-configuration is.
 */
class TesseraAutoConfigurationTest {

  static final TemplateRegistry USER_REGISTRY =
      TemplateRegistry.of(Collections.<TemplateEntry>emptyList());
  static final RenderContextFactory USER_CONTEXTS =
      (request, response, model) -> RenderContext.defaults();

  @Configuration
  @ImportAutoConfiguration(TesseraAutoConfiguration.class)
  static class Auto {}

  private static AnnotationConfigWebApplicationContext web(Class<?>... userConfiguration) {
    AnnotationConfigWebApplicationContext ctx = new AnnotationConfigWebApplicationContext();
    ctx.setServletContext(new MockServletContext());
    // As Boot 2.1+ does: a second set of Tessera beans fails instead of silently overriding.
    ctx.setAllowBeanDefinitionOverriding(false);
    if (userConfiguration.length > 0) {
      ctx.register(userConfiguration);
    }
    ctx.register(Auto.class);
    ctx.refresh();
    return ctx;
  }

  @Test
  void registersDefaults() {
    try (AnnotationConfigWebApplicationContext ctx = web()) {
      assertThat(ctx.getBeanNamesForType(TemplateRegistry.class)).hasSize(1);
      assertThat(ctx.getBeanNamesForType(RenderContextFactory.class)).hasSize(1);
      assertThat(ctx.getBeanNamesForType(TesseraViewResolver.class)).hasSize(1);
      // The merged registry holds the templates generated for this test classpath.
      assertThat(
              ctx.getBean(TemplateRegistry.class).all().stream()
                  .map(TemplateEntry::name)
                  .collect(Collectors.toList()))
          .contains("smoke/page");
    }
  }

  @Test
  void backsOffForUserBeans() {
    try (AnnotationConfigWebApplicationContext ctx = web(UserBeans.class)) {
      assertThat(ctx.getBean(TemplateRegistry.class)).isSameAs(USER_REGISTRY);
      assertThat(ctx.getBean(RenderContextFactory.class)).isSameAs(USER_CONTEXTS);
    }
  }

  @Test
  void backsOffWhenTesseraConfigurationIsImported() {
    // A Boot app that also imports the plain-Spring configuration gets one set of beans, not a
    // bean-name clash (refresh would fail) or a second return-value handler.
    try (AnnotationConfigWebApplicationContext ctx = web(TesseraConfiguration.class)) {
      assertThat(ctx.getBeanNamesForType(TesseraViewResolver.class)).hasSize(1);
      assertThat(ctx.getBeanNamesForType(ReturnValueHandlerRegistrar.class)).hasSize(1);
    }
  }

  @Test
  void inactiveOutsideWebApps() {
    try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
      ctx.register(Auto.class);
      ctx.refresh();
      assertThat(ctx.getBeanNamesForType(TesseraViewResolver.class)).isEmpty();
    }
  }

  @Configuration
  static class UserBeans {
    @Bean
    TemplateRegistry userRegistry() {
      return USER_REGISTRY;
    }

    @Bean
    RenderContextFactory userContexts() {
      return USER_CONTEXTS;
    }
  }
}
