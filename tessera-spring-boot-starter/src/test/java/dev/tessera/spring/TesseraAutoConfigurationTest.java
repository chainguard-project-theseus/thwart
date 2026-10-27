package dev.tessera.spring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.TemplateEntry;
import dev.tessera.runtime.TemplateRegistry;
import dev.tessera.spring.view.RenderContextFactory;
import dev.tessera.spring.view.TesseraViewResolver;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.resource.ResourceUrlEncodingFilter;
import org.thymeleaf.test.FakeThymeleafViewResolver;

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
    return web(null, userConfiguration);
  }

  /** {@code resourceChain} sets the chain's enabled property (Boot 1.5-2.3 and 2.4+ names). */
  private static AnnotationConfigWebApplicationContext web(
      Boolean resourceChain, Class<?>... userConfiguration) {
    AnnotationConfigWebApplicationContext ctx = new AnnotationConfigWebApplicationContext();
    ctx.setServletContext(new MockServletContext());
    if (resourceChain != null) {
      Map<String, Object> properties = new HashMap<>();
      properties.put("spring.resources.chain.enabled", resourceChain.toString());
      properties.put("spring.web.resources.chain.enabled", resourceChain.toString());
      ctx.getEnvironment()
          .getPropertySources()
          .addFirst(new MapPropertySource("resourceChain", properties));
    }
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
      // One startup warning per context, not one per configuration class.
      assertThat(ctx.getBeanNamesForType(OldEngineWarning.class)).hasSize(1);
    }
  }

  @Test
  void warnsOnceWhileThymeleafIsStillConfigured() {
    try (AnnotationConfigWebApplicationContext ctx =
        web(OldEngineWarningTest.Thymeleaf.class, OldEngineWarningTest.Recording.class)) {
      assertThat(ctx.getBeanNamesForType(OldEngineWarning.class))
          .contains("tesseraOldEngineWarning");
      assertThat(ctx.getBean(OldEngineWarningTest.RecordingLog.class).warn)
          .containsExactly(
              String.format(
                  OldEngineWarning.MESSAGE, FakeThymeleafViewResolver.class.getName()));
    }
  }

  @Test
  void noWarningWithoutAnotherEngine() {
    try (AnnotationConfigWebApplicationContext ctx =
        web(OldEngineWarningTest.Jsp.class, OldEngineWarningTest.Recording.class)) {
      assertThat(ctx.getBeanNamesForType(OldEngineWarning.class))
          .contains("tesseraOldEngineWarning");
      assertThat(ctx.getBean(OldEngineWarningTest.RecordingLog.class).warn).isEmpty();
    }
  }

  @Test
  @SuppressWarnings("rawtypes") // FilterRegistrationBean is not generic on Boot 1.5.
  void registersResourceUrlEncodingFilterWhenResourceChainIsEnabled() {
    try (AnnotationConfigWebApplicationContext ctx = web(true)) {
      FilterRegistrationBean registration =
          ctx.getBean("tesseraResourceUrlEncodingFilter", FilterRegistrationBean.class);
      assertThat(registration.getFilter()).isInstanceOf(ResourceUrlEncodingFilter.class);
      // Dispatch types: ResourceChainTest renders both through a real server.
    }
  }

  @Test
  void errorDispatchFromBoot206AsThymeleafsAutoConfiguration() {
    // Boot 1.5 and 2.0.0-2.0.5 declared a plain filter bean (REQUEST only); 2.0.6+ a
    // FilterRegistrationBean for REQUEST and ERROR.
    assertThat(TesseraAutoConfiguration.registersForErrorDispatch("1.5.22.RELEASE")).isFalse();
    assertThat(TesseraAutoConfiguration.registersForErrorDispatch("2.0.0.RELEASE")).isFalse();
    assertThat(TesseraAutoConfiguration.registersForErrorDispatch("2.0.0.M7")).isFalse();
    assertThat(TesseraAutoConfiguration.registersForErrorDispatch("2.0.5.RELEASE")).isFalse();
    assertThat(TesseraAutoConfiguration.registersForErrorDispatch("2.0.6.RELEASE")).isTrue();
    assertThat(TesseraAutoConfiguration.registersForErrorDispatch("2.0.10.RELEASE")).isTrue();
    assertThat(TesseraAutoConfiguration.registersForErrorDispatch("2.1.0.RELEASE")).isTrue();
    assertThat(TesseraAutoConfiguration.registersForErrorDispatch("2.7.18")).isTrue();
    assertThat(TesseraAutoConfiguration.registersForErrorDispatch("3.5.0")).isTrue();
    assertThat(TesseraAutoConfiguration.registersForErrorDispatch("4.0.0-M1")).isTrue();
    assertThat(TesseraAutoConfiguration.registersForErrorDispatch(null)).isTrue();
    assertThat(TesseraAutoConfiguration.registersForErrorDispatch("unknown")).isTrue();
  }

  @Test
  void noResourceUrlEncodingFilterWhenResourceChainIsDisabled() {
    try (AnnotationConfigWebApplicationContext ctx = web(false)) {
      assertThat(ctx.containsBean("tesseraResourceUrlEncodingFilter")).isFalse();
    }
    // Unset, Boot enables the chain only when the webjars locator is on the classpath; it isn't.
    try (AnnotationConfigWebApplicationContext ctx = web()) {
      assertThat(ctx.containsBean("tesseraResourceUrlEncodingFilter")).isFalse();
    }
  }

  @Test
  void resourceUrlEncodingFilterBacksOffForUserFilter() {
    try (AnnotationConfigWebApplicationContext ctx = web(true, UserFilter.class)) {
      assertThat(ctx.containsBean("tesseraResourceUrlEncodingFilter")).isFalse();
      assertThat(ctx.getBeanNamesForType(ResourceUrlEncodingFilter.class)).hasSize(1);
    }
  }

  @Test
  void resourceUrlEncodingFilterBacksOffForThymeleafsRegistration() {
    // Boot 2.0+'s Thymeleaf auto-configuration (kept while migrating), or an app copying it,
    // declares a FilterRegistrationBean named resourceUrlEncodingFilter.
    try (AnnotationConfigWebApplicationContext ctx = web(true, UserFilterRegistration.class)) {
      assertThat(ctx.containsBean("tesseraResourceUrlEncodingFilter")).isFalse();
      assertThat(ctx.getBeanNamesForType(FilterRegistrationBean.class))
          .containsExactly("resourceUrlEncodingFilter");
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

  @Configuration
  static class UserFilter {
    @Bean
    ResourceUrlEncodingFilter myResourceUrlEncodingFilter() {
      return new ResourceUrlEncodingFilter();
    }
  }

  @Configuration
  static class UserFilterRegistration {
    // Raw: FilterRegistrationBean is not generic on Boot 1.5.
    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    FilterRegistrationBean resourceUrlEncodingFilter() {
      return new FilterRegistrationBean(new ResourceUrlEncodingFilter());
    }
  }
}
