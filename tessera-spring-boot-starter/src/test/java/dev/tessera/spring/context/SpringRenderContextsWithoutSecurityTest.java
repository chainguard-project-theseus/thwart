package dev.tessera.spring.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.runtime.RenderContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

/** {@link SpringRenderContexts} in an application without Spring Security on the classpath. */
class SpringRenderContextsWithoutSecurityTest {

  @Test
  void noCsrfTokenAndNoFailureWithoutSpringSecurity() throws Exception {
    StaticWebApplicationContext wac = new StaticWebApplicationContext();
    wac.refresh();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(DispatcherServlet.WEB_APPLICATION_CONTEXT_ATTRIBUTE, wac);
    // Even a real token is ignored: the starter must not touch Security classes when absent.
    request.setAttribute(
        SpringForms.CSRF_ATTRIBUTE, new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "t"));
    try (NoSecurityClassLoader loader = new NoSecurityClassLoader()) {
      Class<?> contexts = loader.loadClass(SpringRenderContexts.class.getName());
      RenderContext ctx =
          (RenderContext)
              contexts
                  .getMethod(
                      "create", HttpServletRequest.class, HttpServletResponse.class, Map.class)
                  .invoke(null, request, new MockHttpServletResponse(), Collections.emptyMap());
      assertEquals(Optional.empty(), ctx.forms().csrfToken());
    }
  }
}
