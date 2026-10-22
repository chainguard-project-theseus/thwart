package dev.tessera.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** One jar carries the registration files of every Boot generation (2.0 through 4). */
class AutoConfigurationImportsTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        // Boot 2.7+.
        "/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
        // Makes @WebMvcTest slices pick up the Tessera view resolver: Boot 2.7/3, then Boot 4.
        "/META-INF/spring/org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc.imports",
        "/META-INF/spring/org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureWebMvc.imports"
      })
  void autoConfigurationIsRegistered(String resource) throws IOException {
    try (InputStream in = getClass().getResourceAsStream(resource)) {
      assertNotNull(in, resource);
      List<String> lines =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .lines()
              .map(String::trim)
              .filter(l -> !l.isEmpty() && !l.startsWith("#"))
              .collect(Collectors.toList());
      assertThat(lines).as(resource).contains(TesseraAutoConfiguration.class.getName());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "org.springframework.boot.autoconfigure.EnableAutoConfiguration",
        "org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc"
      })
  void springFactoriesRegistersForBoot2(String key) throws IOException {
    // Boot 2.0-2.6 read only META-INF/spring.factories.
    Properties factories = new Properties();
    try (InputStream in = getClass().getResourceAsStream("/META-INF/spring.factories")) {
      assertNotNull(in, "spring.factories");
      factories.load(in);
    }
    assertThat(factories.getProperty(key)).isEqualTo(TesseraAutoConfiguration.class.getName());
  }
}
