package dev.tessera.spring.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.spring.testing.SpringTestContext;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * With the resource chain enabled, links to static resources are versioned on normal pages and,
 * from Boot 2.0.6, on Boot's error page: the starter registers {@code ResourceUrlEncodingFilter}
 * for the dispatches Boot's Thymeleaf auto-configuration of the same version did (REQUEST only on
 * Boot 1.5 and 2.0.0-2.0.5, REQUEST and ERROR later). The error page also shows Boot's
 * {@code Date timestamp}, which the starter passes as a {@code ZonedDateTime}. Needs a real
 * server: MockMvc neither applies filter registrations nor makes ERROR dispatches. Both property
 * generations are set (Boot 1.5-2.3 {@code spring.resources}, 2.4+ {@code spring.web.resources}).
 */
@ExtendWith(SpringTestContext.class)
@SpringBootTest(
    classes = ResourceChainTest.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.resources.chain.strategy.content.enabled=true",
      "spring.resources.chain.strategy.content.paths=/**",
      "spring.web.resources.chain.strategy.content.enabled=true",
      "spring.web.resources.chain.strategy.content.paths=/**"
    })
class ResourceChainTest {

  /** {@code /css/app.css} with its content hash, as the content version strategy writes it. */
  static final String VERSIONED = "href=\"/css/app-[0-9a-f]{32}\\.css\"";

  static final String UNVERSIONED = "href=\"/css/app.css\"";

  /** Boot 1.5 here; the modules test 1.5, 2.0.9, 2.7 and 3.5 (2.0.0-2.0.5 as 1.5). */
  static final boolean REQUEST_ONLY = SpringBootVersion.getVersion().startsWith("1.");

  @Autowired Environment environment;

  @Test
  void pageLinksAreVersioned() throws IOException {
    Response page = Response.get(environment, "/assets");
    assertThat(page.status).isEqualTo(200);
    assertThat(page.body).contains("<h1>Assets</h1>").containsPattern(VERSIONED);
  }

  @Test
  void errorPageLinksAreVersionedFromBoot206() throws IOException {
    Response page = Response.get(environment, "/no-such-page");
    assertThat(page.status).isEqualTo(404);
    assertThat(page.body).contains("<h1>404</h1>");
    if (REQUEST_ONLY) {
      // As Thymeleaf's plain filter bean: not mapped for the ERROR dispatch.
      assertThat(page.body).contains(UNVERSIONED).doesNotMatch("(?s).*" + VERSIONED + ".*");
    } else {
      assertThat(page.body).containsPattern(VERSIONED).doesNotContain(UNVERSIONED);
    }
  }

  @Test
  void errorPageRendersTimestampAsDateToStringDoes() throws IOException {
    Response page = Response.get(environment, "/no-such-page");
    assertThat(page.status).isEqualTo(404);
    assertThat(page.body)
        .containsPattern(
            "<p id=\"timestamp\">[A-Z][a-z]{2} [A-Z][a-z]{2} \\d{2} \\d{2}:\\d{2}:\\d{2} \\S+ \\d{4}</p>");
  }

  @Controller
  public static class AssetsController {
    @GetMapping("/assets")
    public String assets(Model model) {
      model.addAttribute("title", "Assets");
      return "assets/page";
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(AssetsController.class)
  static class App {}
}
