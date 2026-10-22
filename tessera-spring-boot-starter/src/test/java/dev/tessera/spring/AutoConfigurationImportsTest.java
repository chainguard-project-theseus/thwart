package dev.tessera.spring;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AutoConfigurationImportsTest {

  @Test
  void autoConfigurationIsRegistered() throws IOException {
    try (InputStream in =
        getClass()
            .getResourceAsStream(
                "/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
      String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(s.contains(TesseraAutoConfiguration.class.getName()));
    }
  }
}
