package dev.tessera.spring;

import dev.tessera.runtime.TemplateRegistry;
import dev.tessera.spring.context.SpringRenderContexts;
import dev.tessera.spring.view.RenderContextFactory;
import dev.tessera.spring.view.TesseraViewResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.view.InternalResourceView;
import org.springframework.web.servlet.view.UrlBasedViewResolver;

/**
 * Tessera for Spring MVC without Boot: {@code @Import(TesseraConfiguration.class)} next to
 * {@code @EnableWebMvc} (or {@code <bean class="dev.tessera.spring.TesseraConfiguration"/>} in
 * XML). Registers what {@link TesseraAutoConfiguration} does, plus a resolver for {@code
 * redirect:} and {@code forward:} names, which Boot otherwise provides. Needs only {@code
 * spring-webmvc}; Boot apps get all of this from the auto-configuration and should not import it.
 */
@Configuration
public class TesseraConfiguration {

  @Bean
  public TemplateRegistry tesseraTemplateRegistry(ApplicationContext context) {
    return TemplateRegistry.load(context.getClassLoader());
  }

  @Bean
  public RenderContextFactory tesseraRenderContextFactory() {
    return SpringRenderContexts::create;
  }

  @Bean
  public TesseraViewResolver tesseraViewResolver(
      TemplateRegistry registry, RenderContextFactory contexts) {
    return new TesseraViewResolver(registry, contexts);
  }

  /**
   * {@code redirect:} and {@code forward:} names only, as {@code UrlBasedViewResolver} handles
   * them, ordered directly after {@link TesseraViewResolver}; everything else falls through.
   */
  @Bean
  public UrlBasedViewResolver tesseraRedirectViewResolver() {
    UrlBasedViewResolver resolver = new UrlBasedViewResolver();
    resolver.setViewClass(InternalResourceView.class);
    resolver.setViewNames(
        UrlBasedViewResolver.REDIRECT_URL_PREFIX + "*",
        UrlBasedViewResolver.FORWARD_URL_PREFIX + "*");
    resolver.setOrder(TesseraViewResolver.ORDER + 1);
    return resolver;
  }

  @Bean
  public static BeanPostProcessor tesseraReturnValueHandlerRegistrar(
      ObjectProvider<RenderContextFactory> contexts) {
    return new ReturnValueHandlerRegistrar(contexts);
  }
}
