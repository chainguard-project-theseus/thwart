package dev.tessera.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.commons.logging.impl.NoOpLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.ViewResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.view.BeanNameViewResolver;
import org.springframework.web.servlet.view.ContentNegotiatingViewResolver;
import org.springframework.web.servlet.view.InternalResourceViewResolver;
import org.springframework.web.servlet.view.UrlBasedViewResolver;
import org.springframework.web.servlet.view.freemarker.FreeMarkerViewResolver;
import org.springframework.web.servlet.view.groovy.GroovyMarkupViewResolver;
import org.springframework.web.servlet.view.script.ScriptTemplateViewResolver;
import org.thymeleaf.test.FakeThymeleafViewResolver;

/**
 * The startup warning for another template engine's view resolver, through {@code
 * TesseraConfiguration} (Spring MVC without Boot; {@code TesseraAutoConfigurationTest} covers
 * Boot). Plain JUnit assertions and Spring 4.3 APIs only: it also runs in the Boot-free test
 * modules.
 */
class OldEngineWarningTest {

  private static final String THYMELEAF = FakeThymeleafViewResolver.class.getName();

  private static AnnotationConfigWebApplicationContext context(
      AnnotationConfigWebApplicationContext parent, Class<?>... configuration) {
    AnnotationConfigWebApplicationContext ctx = new AnnotationConfigWebApplicationContext();
    ctx.setServletContext(new MockServletContext());
    ctx.setParent(parent);
    ctx.register(configuration);
    ctx.refresh();
    return ctx;
  }

  private static List<Object> warnings(AnnotationConfigWebApplicationContext ctx) {
    return ctx.getBean(RecordingLog.class).warn;
  }

  @Test
  void warnsOnceForThymeleafBesideTessera() {
    try (AnnotationConfigWebApplicationContext ctx =
        context(null, Plain.class, Thymeleaf.class, Recording.class)) {
      assertEquals(
          Collections.singletonList(String.format(OldEngineWarning.MESSAGE, THYMELEAF)),
          warnings(ctx));
    }
  }

  @Test
  void silentWithOnlyTesseraAndJspResolvers() {
    // TesseraConfiguration's redirect resolver, plus the InternalResourceViewResolver Boot
    // registers in every app and plain-Spring JSP apps declare.
    try (AnnotationConfigWebApplicationContext ctx =
        context(null, Plain.class, Jsp.class, Recording.class)) {
      assertEquals(Collections.emptyList(), warnings(ctx));
    }
  }

  @Test
  void registeredByTesseraConfiguration() {
    try (AnnotationConfigWebApplicationContext ctx = context(null, Plain.class)) {
      assertTrue(
          ctx.getBean("tesseraOldEngineWarning", SmartInitializingSingleton.class)
              instanceof OldEngineWarning);
      assertEquals(1, ctx.getBeansOfType(OldEngineWarning.class).size());
    }
  }

  @Test
  void reportsASubclassUnderItsOwnName() {
    try (AnnotationConfigWebApplicationContext ctx =
        context(null, Plain.class, Subclassed.class, Recording.class)) {
      assertEquals(
          Collections.singletonList(
              String.format(OldEngineWarning.MESSAGE, AppThymeleafResolver.class.getName())),
          warnings(ctx));
    }
  }

  @Test
  void seesTheResolversOfAncestorContexts() {
    // The DispatcherServlet's context uses the root context's resolvers too.
    try (AnnotationConfigWebApplicationContext root = context(null, Thymeleaf.class);
        AnnotationConfigWebApplicationContext servlet =
            context(root, Plain.class, Recording.class)) {
      assertEquals(
          Collections.singletonList(String.format(OldEngineWarning.MESSAGE, THYMELEAF)),
          warnings(servlet));
    }
  }

  @Test
  void matchesSpringsOwnTemplateEngineResolvers() {
    DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    beans.registerBeanDefinition("ftl", new RootBeanDefinition(FreeMarkerViewResolver.class));
    beans.registerBeanDefinition("groovy", new RootBeanDefinition(GroovyMarkupViewResolver.class));
    beans.registerBeanDefinition(
        "script", new RootBeanDefinition(ScriptTemplateViewResolver.class));
    // Listed sorted; none of them is instantiated (FreeMarker and Groovy are not on the
    // classpath).
    assertEquals(
        String.format(
            OldEngineWarning.MESSAGE,
            FreeMarkerViewResolver.class.getName()
                + ", "
                + GroovyMarkupViewResolver.class.getName()
                + ", "
                + ScriptTemplateViewResolver.class.getName()),
        OldEngineWarning.message(beans));
  }

  @Test
  void ignoresSpringsGenericResolvers() {
    DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    beans.registerBeanDefinition("jsp", new RootBeanDefinition(InternalResourceViewResolver.class));
    beans.registerBeanDefinition("url", new RootBeanDefinition(UrlBasedViewResolver.class));
    beans.registerBeanDefinition("beanName", new RootBeanDefinition(BeanNameViewResolver.class));
    beans.registerBeanDefinition(
        "negotiating", new RootBeanDefinition(ContentNegotiatingViewResolver.class));
    assertNull(OldEngineWarning.message(beans));
  }

  @Configuration
  @EnableWebMvc
  @Import(TesseraConfiguration.class)
  static class Plain {}

  @Configuration
  static class Thymeleaf {
    @Bean
    ViewResolver thymeleafViewResolver() {
      return new FakeThymeleafViewResolver();
    }
  }

  static class AppThymeleafResolver extends FakeThymeleafViewResolver {}

  @Configuration
  static class Subclassed {
    @Bean
    ViewResolver thymeleafViewResolver() {
      return new AppThymeleafResolver();
    }
  }

  @Configuration
  static class Jsp {
    @Bean
    ViewResolver defaultViewResolver() {
      InternalResourceViewResolver resolver = new InternalResourceViewResolver();
      resolver.setPrefix("/WEB-INF/jsp/");
      resolver.setSuffix(".jsp");
      return resolver;
    }
  }

  /** A second warning bean whose log the test reads; the default one logs as usual. */
  @Configuration
  static class Recording {
    @Bean
    RecordingLog recordingLog() {
      return new RecordingLog();
    }

    @Bean
    SmartInitializingSingleton recordingOldEngineWarning(RecordingLog log) {
      return new OldEngineWarning(log);
    }
  }

  @SuppressWarnings("serial") // NoOpLog is Serializable; this test double is never serialized
  static final class RecordingLog extends NoOpLog {
    final List<Object> warn = new ArrayList<>();

    @Override
    public void warn(Object message) {
      warn.add(message);
    }
  }
}
