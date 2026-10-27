package dev.tessera.spring;

import dev.tessera.runtime.TemplateRegistry;
import dev.tessera.spring.context.SpringRenderContexts;
import dev.tessera.spring.view.RenderContextFactory;
import dev.tessera.spring.view.TesseraViewResolver;
import jakarta.servlet.DispatcherType;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringBootVersion;
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
 * registers {@link ResourceUrlEncodingFilter}, as Boot's Thymeleaf auto-configuration did.
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
   * Versioned resource links ({@code @{/webjars/...}} with the resource chain enabled): the
   * registration Boot's Thymeleaf auto-configuration makes, which goes away with Thymeleaf. Boot
   * 2.0.6+ registers it for REQUEST and ERROR dispatches, so error pages get versioned links too;
   * Boot 1.5 and 2.0.0-2.0.5 declared a plain filter bean, which Boot maps for REQUEST only (see
   * {@link #registersForErrorDispatch}). Ordered after Thymeleaf and backing off for its bean
   * name, so an app that keeps Thymeleaf while migrating, or declares the filter itself, gets one
   * filter. The raw return type is deliberate: {@code FilterRegistrationBean} is not generic on
   * Boot 1.5, where a parameterized signature would make the method's generic return type
   * malformed.
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
    if (registersForErrorDispatch(SpringBootVersion.getVersion())) {
      registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
    } else {
      registration.setDispatcherTypes(DispatcherType.REQUEST);
    }
    return registration;
  }

  private static final Pattern BOOT_VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)");

  /**
   * Whether Boot's Thymeleaf auto-configuration of this Boot version maps {@code
   * ResourceUrlEncodingFilter} for ERROR dispatches too: from 2.0.6 on. An unknown version (no
   * jar manifest) gets the current behaviour.
   */
  static boolean registersForErrorDispatch(String bootVersion) {
    Matcher m = bootVersion == null ? null : BOOT_VERSION.matcher(bootVersion);
    if (m == null || !m.find()) {
      return true;
    }
    int major = Integer.parseInt(m.group(1));
    int minor = Integer.parseInt(m.group(2));
    int patch = Integer.parseInt(m.group(3));
    return major > 2 || (major == 2 && (minor > 0 || patch >= 6));
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
