package dev.tessera.spring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.TemplateRegistry;
import dev.tessera.spring.view.RenderContextFactory;
import dev.tessera.spring.view.TesseraViewResolver;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class TesseraAutoConfigurationTest {

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
          // No generated registries on the test classpath: the merged registry is empty.
          assertThat(ctx.getBean(TemplateRegistry.class).all()).isEmpty();
        });
  }

  @Test
  void backsOffForUserBeans() {
    TemplateRegistry registry = TemplateRegistry.of(List.of());
    RenderContextFactory contexts = (request, response, model) -> RenderContext.defaults();
    web.withBean(TemplateRegistry.class, () -> registry)
        .withBean(RenderContextFactory.class, () -> contexts)
        .run(
            ctx -> {
              assertThat(ctx.getBean(TemplateRegistry.class)).isSameAs(registry);
              assertThat(ctx.getBean(RenderContextFactory.class)).isSameAs(contexts);
            });
  }

  @Test
  void inactiveOutsideServletApps() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TesseraAutoConfiguration.class))
        .run(ctx -> assertThat(ctx).doesNotHaveBean(TesseraViewResolver.class));
  }
}
