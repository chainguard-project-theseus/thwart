package dev.tessera.spring.plain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tessera.spring.CspNonce;
import dev.tessera.spring.TesseraConfiguration;
import dev.tessera.spring.e2e.SmokeController;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Spring MVC without Boot: {@code @EnableWebMvc} plus {@code @Import(TesseraConfiguration.class)}
 * and nothing else. Uses only spring-webmvc and spring-test, so it also runs in the test module
 * that has no Boot jars on the classpath.
 */
class PlainSpringMvcTest {

  private AnnotationConfigWebApplicationContext context;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    context = new AnnotationConfigWebApplicationContext();
    context.setServletContext(new MockServletContext());
    context.register(App.class);
    context.refresh();
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  @AfterEach
  void close() {
    context.close();
  }

  private String body(String path) throws Exception {
    return mvc.perform(get(path))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  void viewNameRendersFromGeneratedRegistry() throws Exception {
    String body = body("/page");
    assertTrue(body.contains("<p id=\"hello\">Hello, Ada</p>"), body);
    assertTrue(body.contains("href=\"/owners/7?q=Ada\""), body);
  }

  @Test
  void formErrorsRender() throws Exception {
    String body =
        mvc.perform(post("/signup").param("name", ""))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(body.contains("<span class=\"error\">Name is required</span>"), body);
  }

  @Test
  void redirectResolvesWithoutAnotherViewResolver() throws Exception {
    mvc.perform(post("/signup").param("name", "Ada"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/page"));
  }

  @Test
  void unknownViewNameDoesNotResolve() {
    // No fallback resolver: an unknown name is an error, never a template lookup by pattern.
    Exception e = assertThrows(Exception.class, () -> mvc.perform(get("/unknown")));
    assertTrue(String.valueOf(e.getMessage()).contains("nope"), e.toString());
  }

  /**
   * Every script and style gets a fresh per-request nonce (#335), which the application's own
   * filter, running before the view, reads for its policy header. Servlet 3.0 has no default
   * methods on {@code Filter}, hence the anonymous class.
   */
  @Test
  void cspNonceIsPerRequestAndSharedWithTheApplication() throws Exception {
    Filter policy =
        new Filter() {
          @Override
          public void init(FilterConfig config) {}

          @Override
          public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
              throws IOException, ServletException {
            ((HttpServletResponse) response)
                .setHeader("Content-Security-Policy", "script-src 'nonce-" + CspNonce.get(request) + "'");
            chain.doFilter(request, response);
          }

          @Override
          public void destroy() {}
        };
    MockMvc withPolicy = MockMvcBuilders.webAppContextSetup(context).addFilters(policy).build();
    MvcResult first = withPolicy.perform(get("/csp")).andExpect(status().isOk()).andReturn();
    String nonce = CspPages.assertStamped(first);
    // The application's policy is kept, and the view adds none (#757).
    assertEquals(
        Collections.singletonList("script-src 'nonce-" + nonce + "'"),
        first.getResponse().getHeaders("Content-Security-Policy"));
    String second = CspPages.assertStamped(withPolicy.perform(get("/csp")).andReturn());
    assertNotEquals(nonce, second);
    // Without the application's filter the view sends the strict policy for the page's nonce.
    MvcResult plain = mvc.perform(get("/csp")).andExpect(status().isOk()).andReturn();
    assertEquals(
        "script-src 'nonce-" + CspPages.assertStamped(plain)
            + "' 'strict-dynamic'; object-src 'none'; base-uri 'none'",
        plain.getResponse().getHeader("Content-Security-Policy"));
  }

  /** {@code tessera.csp.enabled=false} from the plain Spring environment turns the policy off. */
  @Test
  void cspPolicyCanBeTurnedOff() throws Exception {
    AnnotationConfigWebApplicationContext off = new AnnotationConfigWebApplicationContext();
    try {
      off.setServletContext(new MockServletContext());
      off.getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "test", Collections.<String, Object>singletonMap("tessera.csp.enabled", "false")));
      off.register(App.class);
      off.refresh();
      MvcResult result =
          MockMvcBuilders.webAppContextSetup(off)
              .build()
              .perform(get("/csp"))
              .andExpect(status().isOk())
              .andReturn();
      CspPages.assertStamped(result);
      assertNull(result.getResponse().getHeader("Content-Security-Policy"));
    } finally {
      off.close();
    }
  }

  @Configuration
  @EnableWebMvc
  @Import({TesseraConfiguration.class, SmokeController.class, UnknownController.class})
  static class App {
    @Bean
    MessageSource messageSource() {
      ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
      messages.setBasename("messages");
      return messages;
    }
  }

  @Controller
  static class UnknownController {
    @GetMapping("/unknown")
    String unknown() {
      return "nope";
    }
  }
}
