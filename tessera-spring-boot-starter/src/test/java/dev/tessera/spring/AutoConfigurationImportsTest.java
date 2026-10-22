package dev.tessera.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AutoConfigurationImportsTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
        // Makes @WebMvcTest slices pick up the Tessera view resolver.
        "/META-INF/spring/org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureWebMvc.imports"
      })
  void autoConfigurationIsRegistered(String resource) throws IOException {
    try (InputStream in = getClass().getResourceAsStream(resource)) {
      assertNotNull(in, resource);
      String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertThat(
              s.lines().map(String::trim).filter(l -> !l.isBlank() && !l.startsWith("#")).toList())
          .as(resource)
          .contains(TesseraAutoConfiguration.class.getName());
    }
  }
}
