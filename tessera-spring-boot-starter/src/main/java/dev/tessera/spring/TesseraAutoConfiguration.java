package dev.tessera.spring;

import dev.tessera.runtime.TemplateRegistry;
import dev.tessera.spring.context.SpringRenderContexts;
import dev.tessera.spring.view.RenderContextFactory;
import dev.tessera.spring.view.TesseraViewResolver;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.ConditionalOnEnabledResourceChain;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.resource.ResourceUrlEncodingFilter;

/**
 * Registers the Tessera view resolver (ordered before Thymeleaf's, falling through for unknown
 * names), the {@code Template} return-value handler, and the merged build-time {@code
 * TemplateRegistry}. The registry, render-context factory and resolver back off if the
 * application declares its own bean of that type. With the resource chain enabled it also
 * registers {@link ResourceUrlEncodingFilter}, as Boot's Thymeleaf auto-configuration does.
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
@AutoConfigureAfter(
    name = {
      "org.springframework.boot.autoconfigure.thymeleaf.ThymeleafAutoConfiguration",
      "org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration"
    })
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

  /**
   * Versioned resource links ({@code @{/webjars/...}} with the resource chain enabled), for normal
   * and error pages: the registration Boot's Thymeleaf auto-configuration makes (2.0+), which goes
   * away with Thymeleaf. Ordered after Thymeleaf and backing off for its bean name, so an app that
   * keeps Thymeleaf while migrating, or declares the filter itself, gets one filter. The raw
   * return type is deliberate: {@code FilterRegistrationBean} is not generic on Boot 1.5, where a
   * parameterized signature would make the method's generic return type malformed.
   */
  @Bean
  @ConditionalOnEnabledResourceChain
  @ConditionalOnMissingBean(
      value = ResourceUrlEncodingFilter.class,
      name = "resourceUrlEncodingFilter")
  @SuppressWarnings("rawtypes")
  public FilterRegistrationBean tesseraResourceUrlEncodingFilter() {
    FilterRegistrationBean<ResourceUrlEncodingFilter> registration =
        new FilterRegistrationBean<>(new ResourceUrlEncodingFilter());
    registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
    return registration;
  }

  /** Warns at startup while another template engine's view resolver is still configured. */
  @Bean
  public SmartInitializingSingleton tesseraOldEngineWarning() {
    return new OldEngineWarning();
  }

  @Bean
  public static BeanPostProcessor tesseraReturnValueHandlerRegistrar(
      ObjectProvider<RenderContextFactory> contexts) {
    return new ReturnValueHandlerRegistrar(contexts);
  }
}
