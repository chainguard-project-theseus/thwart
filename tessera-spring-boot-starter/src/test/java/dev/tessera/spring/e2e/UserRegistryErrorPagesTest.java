package dev.tessera.spring.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.runtime.TemplateEntry;
import dev.tessera.runtime.TemplateRegistry;
import dev.tessera.spring.testing.SpringTestContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * With the application's own {@code TemplateRegistry} bean, Boot's error pages follow that
 * registry, not the compiled templates on the class path (#628): here the bean leaves out {@code
 * error/404}, so a 404 renders Boot's default {@code error} view ({@code error.html}) instead of a
 * view name no resolver can render.
 */
@ExtendWith(SpringTestContext.class)
@SpringBootTest(
    classes = UserRegistryErrorPagesTest.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserRegistryErrorPagesTest {

  @Autowired Environment environment;

  @Test
  void notFoundFollowsTheUserRegistry() throws IOException {
    URL url =
        new URL("http://localhost:" + environment.getProperty("local.server.port") + "/missing");
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestProperty("Accept", "text/html");
    try {
      assertThat(connection.getResponseCode()).isEqualTo(404);
      InputStream in = connection.getErrorStream();
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      for (int n; (n = in.read(buffer)) != -1; ) {
        body.write(buffer, 0, n);
      }
      in.close();
      assertThat(new String(body.toByteArray(), StandardCharsets.UTF_8))
          .contains("<h1>404</h1>")
          .doesNotContain("Tessera error/404");
    } finally {
      connection.disconnect();
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class App {
    @Bean
    public TemplateRegistry userTemplateRegistry() {
      List<TemplateEntry> entries = new ArrayList<>();
      for (TemplateEntry e : TemplateRegistry.load(App.class.getClassLoader()).all()) {
        if (!e.name().equals("error/404")) {
          entries.add(e);
        }
      }
      return TemplateRegistry.of(entries);
    }
  }
}
