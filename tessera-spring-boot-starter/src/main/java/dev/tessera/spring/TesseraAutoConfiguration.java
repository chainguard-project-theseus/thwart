package dev.tessera.spring;

import dev.tessera.runtime.TemplateRegistry;
import dev.tessera.spring.context.SpringRenderContexts;
import dev.tessera.spring.view.RenderContextFactory;
import dev.tessera.spring.view.TemplateReturnValueHandler;
import dev.tessera.spring.view.TesseraViewResolver;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the Tessera view resolver (ordered before Thymeleaf's, falling through for unknown
 * names), the {@code Template} return-value handler, and the merged build-time {@code
 * TemplateRegistry}. The registry, render-context factory and resolver back off if the
 * application declares its own bean of that type.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
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
  public WebMvcConfigurer tesseraWebMvcConfigurer(ObjectProvider<RenderContextFactory> contexts) {
    return new WebMvcConfigurer() {
      @Override
      public void addReturnValueHandlers(List<HandlerMethodReturnValueHandler> handlers) {
        handlers.add(new TemplateReturnValueHandler(contexts.getObject()));
      }
    };
  }
}
