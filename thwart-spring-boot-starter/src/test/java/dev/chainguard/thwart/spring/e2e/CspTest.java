package dev.chainguard.thwart.spring.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.chainguard.thwart.spring.CspNonce;
import dev.chainguard.thwart.spring.plain.CspPages;
import dev.chainguard.thwart.spring.testing.SpringTestContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XContentTypeOptionsHeaderWriter;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The default policy under Spring Security (#775), on every Spring Security line the shared tests
 * run on (4.2 with Boot 1.5 through 7 with Boot 4). The auto-configured filter runs first and adds
 * the policy at commit only for a Thwart page with no policy by then; Spring Security's {@code
 * HeaderWriterFilter} has written its headers by then.
 */
@ExtendWith(SpringTestContext.class)
@SpringBootTest(classes = CspTest.App.class)
class CspTest {

  static final String HEADER = "Content-Security-Policy";

  @Autowired WebApplicationContext context;

  /** Spring Security with no policy configured: exactly one header, for the page's nonce. */
  @Test
  void springSecurityWithoutAPolicyGetsTheDefault() throws Exception {
    MvcResult result = mvc(nosniff()).perform(get("/csp")).andExpect(status().isOk()).andReturn();
    assertDefault(result);
    assertEquals("nosniff", result.getResponse().getHeader("X-Content-Type-Options"));
  }

  /** Spring Security's {@code contentSecurityPolicy(...)}: the application's policy, once. */
  @Test
  void springSecurityPolicyWins() throws Exception {
    HeaderWriterFilter security =
        new HeaderWriterFilter(
            Collections.<HeaderWriter>singletonList(
                new ContentSecurityPolicyHeaderWriter("default-src 'self'")));
    MvcResult result = mvc(security).perform(get("/csp")).andReturn();
    CspPages.assertStamped(result);
    assertEquals(Collections.singletonList("default-src 'self'"), headers(result));
  }

  /** An application filter's policy is kept, with or without Spring Security. */
  @Test
  void applicationFilterPolicyIsKept() throws Exception {
    for (MockMvc mvc : Arrays.asList(mvc(appPolicy()), mvc(appPolicy(), nosniff()))) {
      MvcResult result = mvc.perform(get("/csp")).andReturn();
      String nonce = CspPages.assertStamped(result);
      assertEquals(Collections.singletonList("script-src 'nonce-" + nonce + "'"), headers(result));
    }
  }

  @Test
  void withoutSpringSecurityTheDefault() throws Exception {
    assertDefault(mvc().perform(get("/csp")).andExpect(status().isOk()).andReturn());
  }

  /** A page rendered in an ASYNC dispatch ({@code Callable}) gets the default, once. */
  @Test
  void asyncPageGetsTheDefault() throws Exception {
    for (MockMvc mvc : Arrays.asList(mvc(), mvc(nosniff()))) {
      MvcResult started =
          mvc.perform(get("/csp/async")).andExpect(request().asyncStarted()).andReturn();
      started.getAsyncResult();
      assertDefault(mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn());
    }
  }

  /** No Thwart view, no policy: even a response whose request has a nonce gets none. */
  @Test
  void nonThwartResponsesGetNoPolicy() throws Exception {
    for (MockMvc mvc : Arrays.asList(mvc(), mvc(nosniff()))) {
      for (String path : Arrays.asList("/csp/body", "/css/app.css")) {
        MvcResult result = mvc.perform(get(path)).andExpect(status().isOk()).andReturn();
        assertNull(result.getResponse().getHeader(HEADER), path);
      }
    }
  }

  /**
   * A redirect or an error status sends no page, so no policy; Spring Security's headers still
   * go out. MockMvc makes no ERROR dispatch: {@link ErrorPagesTest} checks the error page itself.
   */
  @Test
  void redirectAndSendErrorGetNoPolicy() throws Exception {
    MockMvc mvc = mvc(nosniff());
    MvcResult redirect =
        mvc.perform(post("/signup").param("name", "Ada"))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    MvcResult error = mvc.perform(get("/csp/error")).andExpect(status().is(409)).andReturn();
    for (MvcResult result : Arrays.asList(redirect, error)) {
      assertNull(result.getResponse().getHeader(HEADER));
      assertEquals("nosniff", result.getResponse().getHeader("X-Content-Type-Options"));
    }
  }

  static void assertDefault(MvcResult result) throws Exception {
    String nonce = CspPages.assertStamped(result);
    assertEquals(
        Collections.singletonList(
            "script-src 'nonce-" + nonce + "' 'strict-dynamic'; object-src 'none'; base-uri 'none'"),
        headers(result));
  }

  static List<String> headers(MvcResult result) {
    return result.getResponse().getHeaders(HEADER);
  }

  /** Spring Security's header filter with a header other than the policy. */
  static HeaderWriterFilter nosniff() {
    return new HeaderWriterFilter(
        Collections.<HeaderWriter>singletonList(new XContentTypeOptionsHeaderWriter()));
  }

  /** Sets the application's own policy before the chain, as an application filter would. */
  static Filter appPolicy() {
    return new OncePerRequestFilter() {
      @Override
      protected void doFilterInternal(
          HttpServletRequest request, HttpServletResponse response, FilterChain chain)
          throws ServletException, IOException {
        response.setHeader(HEADER, "script-src 'nonce-" + CspNonce.get(request) + "'");
        chain.doFilter(request, response);
      }
    };
  }

  /**
   * The auto-configured filter, then {@code filters} in order. Boot orders Spring Security's
   * chain at -100 by default; the filter must come before it.
   */
  MockMvc mvc(Filter... filters) {
    return mvc(context, filters);
  }

  @SuppressWarnings("rawtypes")
  static MockMvc mvc(WebApplicationContext context, Filter... filters) {
    FilterRegistrationBean registration =
        context.getBean("thwartCspFilter", FilterRegistrationBean.class);
    assertTrue(registration.getOrder() < -100, "order " + registration.getOrder());
    Filter[] chain = new Filter[filters.length + 1];
    chain[0] = registration.getFilter();
    System.arraycopy(filters, 0, chain, 1, filters.length);
    return MockMvcBuilders.webAppContextSetup(context).addFilters(chain).build();
  }

  @Controller
  public static class Endpoints {
    @GetMapping("/csp/body")
    @ResponseBody
    public String body(HttpServletRequest request) {
      return "nonce " + CspNonce.get(request);
    }

    @GetMapping("/csp/error")
    public void error(HttpServletResponse response) throws IOException {
      response.sendError(409);
    }

    @GetMapping("/csp/async")
    public Callable<String> async() {
      return () -> "smoke/csp";
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({SmokeController.class, Endpoints.class})
  static class App {}
}
