package dev.tessera.spring.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.TemplateEntry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;

/**
 * The default policy (#775): the view stamps the request with its nonce, and {@link CspFilter}
 * adds the policy at the first commit point only if no policy header is set by then.
 */
class CspFilterTest {

  static final String POLICY =
      "script-src 'nonce-Ab+/9_-x==' 'strict-dynamic'; object-src 'none'; base-uri 'none'";

  @Test
  void stampedPageGetsThePolicyForItsNonce() throws Exception {
    MockHttpServletResponse response = render(view("Ab+/9_-x==", true));
    assertThat(response.getHeaders(CspFilter.HEADER)).containsExactly(POLICY);
    assertThat(response.getContentAsString()).isEqualTo("x");
  }

  @Test
  void policySetBeforeCommitIsKept() throws Exception {
    MockHttpServletResponse response = render(view("abc", true), "default-src 'self'");
    assertThat(response.getHeaders(CspFilter.HEADER)).containsExactly("default-src 'self'");
  }

  /**
   * An empty page (length 0) under Spring Security's policy writer: this filter must not commit
   * before Spring Security's wrapper does, or the application's policy would be replaced.
   */
  @Test
  void emptyPageKeepsSpringSecurityPolicy() throws Exception {
    TemplateEntry empty =
        new TemplateEntry(
            "empty", Collections.<TemplateEntry.Param>emptyList(), (m, ctx, out) -> {});
    TesseraView view =
        TesseraView.of(empty, (rq, r, m) -> RenderContext.defaults().withCspNonce("abc"), true);
    HeaderWriterFilter security =
        new HeaderWriterFilter(
            Collections.<HeaderWriter>singletonList(
                new ContentSecurityPolicyHeaderWriter("default-src 'self'")));
    MockHttpServletResponse response = new MockHttpServletResponse();
    new CspFilter()
        .doFilter(
            new MockHttpServletRequest(),
            response,
            (rq, rs) -> security.doFilter(rq, rs, (rq2, rs2) -> render(view, rq2, rs2)));
    assertThat(response.getContentLength()).isEqualTo(0);
    assertThat(response.getHeaders(CspFilter.HEADER)).containsExactly("default-src 'self'");
  }

  @Test
  void disabledOrNoNonceOrNoTesseraViewSendsNoPolicy() throws Exception {
    assertThat(render(view("abc", false)).containsHeader(CspFilter.HEADER)).isFalse();
    assertThat(render(view(null, true)).containsHeader(CspFilter.HEADER)).isFalse();
    MockHttpServletResponse response = new MockHttpServletResponse();
    new CspFilter()
        .doFilter(
            new MockHttpServletRequest(),
            response,
            (rq, rs) -> rs.getOutputStream().write('x')); // not a Tessera view
    assertThat(response.containsHeader(CspFilter.HEADER)).isFalse();
  }

  /** A nonce that is not a CSP base64-value would rewrite the policy; it fails the render. */
  @Test
  void nonceThatIsNotBase64IsAnError() {
    for (String bad : Arrays.asList("a' 'unsafe-inline", "a; script-src *", "a b", "a\r\nX: y")) {
      MockHttpServletResponse response = new MockHttpServletResponse();
      assertThatThrownBy(() -> render(view(bad, true), response))
          .isInstanceOf(IllegalStateException.class);
      assertThat(response.containsHeader(CspFilter.HEADER)).isFalse();
      assertThat(response.getContentLength()).isEqualTo(0); // nothing was written
    }
  }

  /** Each commit point adds the policy before the response commits, not at the end. */
  @Test
  void everyCommitPointAddsThePolicyFirst() throws Exception {
    Map<String, FilterChain> points = new LinkedHashMap<>();
    points.put("content length", (rq, rs) -> {
      rs.setContentLength(2);
      rs.getOutputStream().write('a');
      assertThat(((HttpServletResponse) rs).containsHeader(CspFilter.HEADER)).isFalse();
      rs.getOutputStream().write('b');
    });
    points.put("buffer full", (rq, rs) -> rs.getOutputStream().write(new byte[rs.getBufferSize()]));
    points.put("flushBuffer", (rq, rs) -> rs.flushBuffer());
    points.put("stream flush", (rq, rs) -> rs.getOutputStream().flush());
    points.put("writer flush", (rq, rs) -> rs.getWriter().flush());
    points.put("sendError", (rq, rs) -> ((HttpServletResponse) rs).sendError(404));
    points.put("sendRedirect", (rq, rs) -> ((HttpServletResponse) rs).sendRedirect("/x"));
    for (Map.Entry<String, FilterChain> point : points.entrySet()) {
      MockHttpServletResponse response = new MockHttpServletResponse();
      boolean[] seen = new boolean[1];
      new CspFilter()
          .doFilter(
              new MockHttpServletRequest(),
              response,
              (rq, rs) -> {
                CspFilter.stamp(rq, "Ab+/9_-x==");
                point.getValue().doFilter(rq, rs);
                seen[0] = ((HttpServletResponse) rs).containsHeader(CspFilter.HEADER);
              });
      assertThat(seen[0]).as(point.getKey()).isTrue();
      assertThat(response.getHeaders(CspFilter.HEADER)).as(point.getKey()).containsExactly(POLICY);
    }
  }

  /** Outside Boot the filter registers itself, first, for REQUEST, ASYNC and ERROR dispatches. */
  @Test
  void registersItselfInAServletContainer() throws Exception {
    // A recording proxy, not Mockito: the older Mockito of the Boot 1.5/2.0 runs can't mock here.
    List<String> calls = new ArrayList<>();
    FilterRegistration.Dynamic registration = proxy(FilterRegistration.Dynamic.class, calls, null);
    new CspFilter().onStartup(proxy(ServletContext.class, calls, registration));
    assertThat(calls)
        .containsExactly(
            "addFilter[tesseraCspFilter, CspFilter]",
            "setAsyncSupported[true]",
            "addMappingForUrlPatterns[[REQUEST, ASYNC, ERROR], false, [/*]]");
    // Already registered (a Boot war registers it too): addFilter returns null.
    new CspFilter().onStartup(proxy(ServletContext.class, calls, null));
  }

  private static <T> T proxy(Class<T> type, List<String> calls, Object result) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (p, method, args) -> {
              List<String> shown = new ArrayList<>();
              for (Object arg : args == null ? new Object[0] : args) {
                shown.add(
                    arg instanceof CspFilter
                        ? "CspFilter"
                        : arg instanceof Object[]
                            ? Arrays.toString((Object[]) arg)
                            : String.valueOf(arg));
              }
              calls.add(method.getName() + shown);
              return result;
            }));
  }

  private static TesseraView view(String nonce, boolean csp) {
    TemplateEntry entry =
        new TemplateEntry(
            "page", Collections.<TemplateEntry.Param>emptyList(), (m, ctx, out) -> out.write("x"));
    return TesseraView.of(
        entry, (request, r, m) -> RenderContext.defaults().withCspNonce(nonce), csp);
  }

  private static MockHttpServletResponse render(TesseraView view) throws Exception {
    return render(view, new MockHttpServletResponse());
  }

  private static MockHttpServletResponse render(TesseraView view, String existing)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    if (existing != null) {
      response.setHeader(CspFilter.HEADER, existing);
    }
    return render(view, response);
  }

  private static MockHttpServletResponse render(
      TesseraView view, MockHttpServletResponse response) throws Exception {
    new CspFilter()
        .doFilter(new MockHttpServletRequest(), response, (rq, rs) -> render(view, rq, rs));
    return response;
  }

  private static void render(TesseraView view, ServletRequest request, ServletResponse response)
      throws IOException {
    try {
      view.render(null, (HttpServletRequest) request, (HttpServletResponse) response);
    } catch (RuntimeException | IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
