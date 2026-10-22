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
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class TesseraAutoConfigurationTest {

  static final TemplateRegistry USER_REGISTRY =
      TemplateRegistry.of(Collections.<TemplateEntry>emptyList());
  static final RenderContextFactory USER_CONTEXTS =
      (request, response, model) -> RenderContext.defaults();

  private final WebApplicationContextRunner web =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(TesseraAutoConfiguration.class));

  @Test
  void registersDefaults() {
    web.run(
        ctx -> {
          assertThat(ctx).hasSingleBean(TemplateRegistry.class);
          assertThat(ctx).hasSingleBean(RenderContextFactory.class);
          assertThat(ctx).hasSingleBean(TesseraViewResolver.class);
          // The merged registry holds the templates generated for this test classpath.
          assertThat(
                  ctx.getBean(TemplateRegistry.class).all().stream()
                      .map(TemplateEntry::name)
                      .collect(Collectors.toList()))
              .contains("smoke/page");
        });
  }

  @Test
  void backsOffForUserBeans() {
    web.withUserConfiguration(UserBeans.class)
        .run(
            ctx -> {
              assertThat(ctx.getBean(TemplateRegistry.class)).isSameAs(USER_REGISTRY);
              assertThat(ctx.getBean(RenderContextFactory.class)).isSameAs(USER_CONTEXTS);
            });
  }

  @Test
  void inactiveOutsideWebApps() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TesseraAutoConfiguration.class))
        .run(ctx -> assertThat(ctx).doesNotHaveBean(TesseraViewResolver.class));
  }

  // withUserConfiguration, not withBean: withBean needs Boot 2.2.
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
