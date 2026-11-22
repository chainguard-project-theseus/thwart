package dev.chainguard.thwart.spring;

import dev.chainguard.thwart.runtime.TemplateRegistry;
import dev.chainguard.thwart.spring.context.SpringRenderContexts;
import dev.chainguard.thwart.spring.view.CspFilter;
import dev.chainguard.thwart.spring.view.RenderContextFactory;
import dev.chainguard.thwart.spring.view.ThwartViewResolver;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.view.InternalResourceView;
import org.springframework.web.servlet.view.UrlBasedViewResolver;

/**
 * Thwart for Spring MVC without Boot: {@code @Import(ThwartConfiguration.class)} next to
 * {@code @EnableWebMvc} (or {@code <bean class="dev.chainguard.thwart.spring.ThwartConfiguration"/>} in
 * XML). Registers what {@link ThwartAutoConfiguration} does, plus a resolver for {@code
 * redirect:} and {@code forward:} names, which Boot otherwise provides. Needs only {@code
 * spring-webmvc}. Boot apps get all of this from the auto-configuration, which backs off if
 * this class is imported.
 */
@Configuration
public class ThwartConfiguration {

  @Bean
  public TemplateRegistry thwartTemplateRegistry(ApplicationContext context) {
    return TemplateRegistry.load(context.getClassLoader());
  }

  @Bean
  public RenderContextFactory thwartRenderContextFactory() {
    return SpringRenderContexts::create;
  }

  @Bean
  public ThwartViewResolver thwartViewResolver(
      TemplateRegistry registry, RenderContextFactory contexts) {
    return new ThwartViewResolver(registry, contexts);
  }

  /**
   * The default policy's filter. A servlet container registers {@link CspFilter} itself, as a
   * {@code WebApplicationInitializer}; this bean is for a Boot app that imports this class, where
   * Boot registers it for REQUEST dispatches only (the guide's templates.md, Rendering, has the registration for error and
   * async pages too).
   */
  @Bean
  public CspFilter thwartCspFilter() {
    return new CspFilter();
  }

  /**
   * {@code redirect:} and {@code forward:} names only, as {@code UrlBasedViewResolver} handles
   * them, ordered directly after {@link ThwartViewResolver}; everything else falls through.
   */
  @Bean
  public UrlBasedViewResolver thwartRedirectViewResolver() {
    UrlBasedViewResolver resolver = new UrlBasedViewResolver();
    resolver.setViewClass(InternalResourceView.class);
    resolver.setViewNames(
        UrlBasedViewResolver.REDIRECT_URL_PREFIX + "*",
        UrlBasedViewResolver.FORWARD_URL_PREFIX + "*");
    resolver.setOrder(ThwartViewResolver.ORDER + 1);
    return resolver;
  }

  /** Warns at startup while another template engine's view resolver is still configured. */
  @Bean
  public SmartInitializingSingleton thwartOldEngineWarning() {
    return new OldEngineWarning();
  }
}
