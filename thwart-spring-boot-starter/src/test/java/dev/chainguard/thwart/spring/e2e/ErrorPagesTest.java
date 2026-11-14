package dev.chainguard.thwart.spring.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import dev.chainguard.thwart.spring.testing.SpringTestContext;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Boot's error handling renders the per-status Thwart templates {@code error/404.html} and
 * {@code error/5xx.html} (#628): the starter's {@code TemplateAvailabilityProvider} tells Boot's
 * {@code DefaultErrorViewResolver} they exist. A status with neither an {@code error/<status>}
 * nor an {@code error/<series>xx} template still gets Boot's default {@code error} view (here
 * {@code error.html}). Needs a real server: MockMvc makes no ERROR dispatches.
 */
@ExtendWith(SpringTestContext.class)
@SpringBootTest(
    classes = ErrorPagesTest.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorPagesTest {

  @Autowired Environment environment;

  @Test
  void notFoundRendersErrorStatusTemplate() throws IOException {
    Response page = Response.get(environment, "/no-such-page");
    assertThat(page.status).isEqualTo(404);
    assertThat(page.body).contains("<h1>404</h1>").contains("Thwart error/404");
    assertDefaultPolicy(page);
  }

  @Test
  void serverErrorRendersErrorSeriesTemplate() throws IOException {
    // No error/500.html: Boot falls back to error/5xx.html.
    Response page = Response.get(environment, "/boom");
    assertThat(page.status).isEqualTo(500);
    assertThat(page.body).contains("<h1>500</h1>").contains("Thwart error/5xx");
    assertDefaultPolicy(page);
  }

  @Test
  void statusWithoutTemplateGetsDefaultErrorView() throws IOException {
    // No error/409.html or error/4xx.html: Boot's default "error" view, not a failure.
    Response page = Response.get(environment, "/conflict");
    assertThat(page.status).isEqualTo(409);
    assertThat(page.body)
        .contains("<h1>409</h1>")
        .doesNotContain("Thwart error/404")
        .doesNotContain("Thwart error/5xx");
    assertDefaultPolicy(page);
  }

  /** The ERROR dispatch, after {@code sendError} or an exception, gets the policy (#775). */
  static void assertDefaultPolicy(Response page) {
    assertThat(page.csp).hasSize(1);
    assertThat(page.csp.get(0)).matches("script-src 'nonce-[A-Za-z0-9+/]+={0,2}' .*");
  }

  @Controller
  public static class FailingController {
    @GetMapping("/boom")
    public String boom() {
      throw new IllegalStateException("boom");
    }

    @GetMapping("/conflict")
    public void conflict(HttpServletResponse response) throws IOException {
      response.sendError(409);
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(FailingController.class)
  static class App {}
}
