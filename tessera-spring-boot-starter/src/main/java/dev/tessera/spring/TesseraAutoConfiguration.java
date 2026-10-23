package dev.tessera.spring;

import dev.tessera.runtime.TemplateRegistry;
import dev.tessera.spring.context.SpringRenderContexts;
import dev.tessera.spring.view.RenderContextFactory;
import dev.tessera.spring.view.TesseraViewResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Registers the Tessera view resolver (ordered before Thymeleaf's, falling through for unknown
 * names), the {@code Template} return-value handler, and the merged build-time {@code
 * TemplateRegistry}. The registry, render-context factory and resolver back off if the
 * application declares its own bean of that type.
 *
 * <p>Plain {@code @Configuration} with untyped conditions (not {@code @AutoConfiguration} or
 * {@code type = SERVLET}) so that one class works on Spring Boot 1.5 through 4. Applications
 * without Boot import {@link TesseraConfiguration} instead; if a Boot app imports it too, this
 * class backs off entirely.
 */
@Configuration
@ConditionalOnWebApplication
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnMissingBean(TesseraConfiguration.class)
public class TesseraAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public TemplateRegistry tesseraTemplateRegistry(ApplicationContext context) {
    return TemplateRegistry.load(context.getClassLoader());
  }

  @Bean
  @ConditionalOnMissingBean
  public RenderContextFactory tesseraRenderContextFactory() {
    return SpringRenderContexts::create;
  }

  @Bean
  @ConditionalOnMissingBean
  public TesseraViewResolver tesseraViewResolver(
      TemplateRegistry registry, RenderContextFactory contexts) {
    return new TesseraViewResolver(registry, contexts);
  }

  @Bean
  public static BeanPostProcessor tesseraReturnValueHandlerRegistrar(
      ObjectProvider<RenderContextFactory> contexts) {
    return new ReturnValueHandlerRegistrar(contexts);
  }
}
