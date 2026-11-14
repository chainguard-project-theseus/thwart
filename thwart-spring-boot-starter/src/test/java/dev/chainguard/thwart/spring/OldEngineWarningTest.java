package dev.chainguard.thwart.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.commons.logging.impl.NoOpLog;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
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
import com.mitchellbosecke.pebble.test.FakeLegacyPebbleViewResolver;
import io.pebbletemplates.test.FakePebbleViewResolver;
import org.springframework.web.servlet.view.tiles2.test.FakeTiles2ViewResolver;
import org.springframework.web.servlet.view.tiles3.test.FakeTiles3ViewResolver;
import org.thymeleaf.test.FakeThymeleafViewResolver;

/**
 * The startup warning for another template engine's view resolver, through {@code
 * ThwartConfiguration} (Spring MVC without Boot; {@code ThwartAutoConfigurationTest} covers
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
  void warnsOnceForThymeleafBesideThwart() {
    try (AnnotationConfigWebApplicationContext ctx =
        context(null, Plain.class, Thymeleaf.class, Recording.class)) {
      assertEquals(
          Collections.singletonList(String.format(OldEngineWarning.MESSAGE, THYMELEAF)),
          warnings(ctx));
    }
  }

  @Test
  void silentWithOnlyThwart() {
    // ThwartConfiguration's redirect resolver and @EnableWebMvc's mvcViewResolver.
    try (AnnotationConfigWebApplicationContext ctx = context(null, Plain.class, Recording.class)) {
      assertEquals(Collections.emptyList(), warnings(ctx));
    }
  }

  @Test
  void registeredByThwartConfiguration() {
    try (AnnotationConfigWebApplicationContext ctx = context(null, Plain.class)) {
      assertTrue(
          ctx.getBean("thwartOldEngineWarning", SmartInitializingSingleton.class)
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
  void namesAProxiedResolverWithoutTheProxySuffix() {
    ProxyFactory proxy = new ProxyFactory(new FakeThymeleafViewResolver());
    proxy.setProxyTargetClass(true);
    Object resolver = proxy.getProxy();
    assertTrue(resolver.getClass().getName().contains("$$"));
    DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    beans.registerSingleton("thymeleafViewResolver", resolver);
    assertEquals(
        String.format(OldEngineWarning.MESSAGE, THYMELEAF), OldEngineWarning.message(beans));
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
  void matchesTilesAndPebbleResolvers() {
    // Tiles 2 (Spring 4.3) and 3 (4.3-5.3), and Pebble's old (up to 3.1) and new (3.2+) packages.
    // Fakes in sub-packages, as for Thymeleaf: the real classes aren't on Spring 6/7's classpath.
    DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    beans.registerBeanDefinition("tiles2", new RootBeanDefinition(FakeTiles2ViewResolver.class));
    beans.registerBeanDefinition("tiles3", new RootBeanDefinition(FakeTiles3ViewResolver.class));
    beans.registerBeanDefinition(
        "oldPebble", new RootBeanDefinition(FakeLegacyPebbleViewResolver.class));
    beans.registerBeanDefinition("pebble", new RootBeanDefinition(FakePebbleViewResolver.class));
    assertEquals(
        String.format(
            OldEngineWarning.MESSAGE,
            FakeLegacyPebbleViewResolver.class.getName()
                + ", "
                + FakePebbleViewResolver.class.getName()
                + ", "
                + FakeTiles2ViewResolver.class.getName()
                + ", "
                + FakeTiles3ViewResolver.class.getName()),
        OldEngineWarning.message(beans));
  }

  @Test
  void ignoresSpringsGenericResolvers() {
    DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    beans.registerBeanDefinition("url", new RootBeanDefinition(UrlBasedViewResolver.class));
    beans.registerBeanDefinition("beanName", new RootBeanDefinition(BeanNameViewResolver.class));
    beans.registerBeanDefinition(
        "negotiating", new RootBeanDefinition(ContentNegotiatingViewResolver.class));
    // JSP, which Boot registers in every web app, is not another template engine here.
    beans.registerBeanDefinition(
        "defaultViewResolver", new RootBeanDefinition(InternalResourceViewResolver.class));
    assertNull(OldEngineWarning.message(beans));
  }

  @Configuration
  @EnableWebMvc
  @Import(ThwartConfiguration.class)
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
