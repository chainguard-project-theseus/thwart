package dev.tessera.spring.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.spring.testing.SpringTestContext;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
 * Boot's error handling renders the per-status Tessera templates {@code error/404.html} and
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
    Response page = get("/no-such-page");
    assertThat(page.status).isEqualTo(404);
    assertThat(page.body).contains("<h1>404</h1>").contains("Tessera error/404");
  }

  @Test
  void serverErrorRendersErrorSeriesTemplate() throws IOException {
    // No error/500.html: Boot falls back to error/5xx.html.
    Response page = get("/boom");
    assertThat(page.status).isEqualTo(500);
    assertThat(page.body).contains("<h1>500</h1>").contains("Tessera error/5xx");
  }

  @Test
  void statusWithoutTemplateGetsDefaultErrorView() throws IOException {
    // No error/409.html or error/4xx.html: Boot's default "error" view, not a failure.
    Response page = get("/conflict");
    assertThat(page.status).isEqualTo(409);
    assertThat(page.body)
        .contains("<h1>409</h1>")
        .doesNotContain("Tessera error/404")
        .doesNotContain("Tessera error/5xx");
  }

  private Response get(String path) throws IOException {
    URL url = new URL("http://localhost:" + environment.getProperty("local.server.port") + path);
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestProperty("Accept", "text/html");
    try {
      int status = connection.getResponseCode();
      InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream();
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      for (int n; (n = in.read(buffer)) != -1; ) {
        body.write(buffer, 0, n);
      }
      in.close();
      return new Response(status, new String(body.toByteArray(), StandardCharsets.UTF_8));
    } finally {
      connection.disconnect();
    }
  }

  static final class Response {
    final int status;
    final String body;

    Response(int status, String body) {
      this.status = status;
      this.body = body;
    }
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
