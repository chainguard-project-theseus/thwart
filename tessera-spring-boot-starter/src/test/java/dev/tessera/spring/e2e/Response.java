package dev.tessera.spring.e2e;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.springframework.core.env.Environment;

/** The status and body of a {@code text/html} GET from a {@code RANDOM_PORT} test's server. */
final class Response {
  final int status;
  final String body;

  private Response(int status, String body) {
    this.status = status;
    this.body = body;
  }

  static Response get(Environment environment, String path) throws IOException {
    URL url = new URL("http://localhost:" + environment.getProperty("local.server.port") + path);
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestProperty("Accept", "text/html");
    try {
      int status = connection.getResponseCode();
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      try (InputStream in =
          status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
        byte[] buffer = new byte[4096];
        for (int n; (n = in.read(buffer)) != -1; ) {
          body.write(buffer, 0, n);
        }
      }
      return new Response(status, new String(body.toByteArray(), StandardCharsets.UTF_8));
    } finally {
      connection.disconnect();
    }
  }
}
