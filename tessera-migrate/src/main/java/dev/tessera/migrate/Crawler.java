package dev.tessera.migrate;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/**
 * Requests a {@code pages.txt} list in order against one running app, the way a browser session
 * would: one cookie jar for the whole run (so a {@code POST /login} logs in), redirects followed,
 * and every POST carrying the {@code _csrf} hidden input of the previous page.
 *
 * <p>{@code pages.txt} has one request per line, {@code GET /path} or {@code POST /path a=1&b=2};
 * blank lines and {@code #} comments are ignored. Form values may be written raw or
 * percent-encoded ({@code +} is a space, as in any form body).
 */
final class Crawler {

  /** One line of {@code pages.txt}; {@code form} is empty for a GET. */
  record Request(String line, String method, String path, String form) {}

  /** What came back: status 0 means no response at all (the body then says why). */
  record Response(int status, String finalUrl, String body) {}

  private final String base;
  private final HttpClient client;
  private String previousBody = "";

  Crawler(String baseUrl) {
    this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .cookieHandler(new CookieManager())
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  /** Parses {@code pages.txt}. */
  static List<Request> readPages(Path file) throws IOException {
    List<Request> requests = new ArrayList<>();
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i).strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] parts = line.split("\\s+", 3);
      String method = parts[0];
      if (!(method.equals("GET") || method.equals("POST")) || parts.length < 2
          || !parts[1].startsWith("/") || (method.equals("GET") && parts.length > 2)) {
        throw new IOException(file + ":" + (i + 1)
            + ": expected `GET /path` or `POST /path a=1&b=2`, got: " + line);
      }
      requests.add(new Request(line, method, parts[1], parts.length > 2 ? parts[2] : ""));
    }
    return requests;
  }

  /** Sends one request and remembers its body for the next POST's CSRF token. */
  Response send(Request request) {
    Response response;
    try {
      HttpRequest.Builder http = HttpRequest.newBuilder(URI.create(base + request.path()))
          .timeout(Duration.ofSeconds(60))
          .header("Accept", "text/html")
          .header("Accept-Language", "en");
      if (request.method().equals("POST")) {
        http.header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formBody(request.form(), csrf(previousBody))));
      } else {
        http.GET();
      }
      HttpResponse<String> r = client.send(http.build(),
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      response = new Response(r.statusCode(), relative(r.uri().toString()), r.body());
    } catch (IOException | IllegalArgumentException e) {
      response = new Response(0, request.path(),
          "<!-- no response: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " -->");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      response = new Response(0, request.path(), "<!-- interrupted -->");
    }
    previousBody = response.body();
    return response;
  }

  private String relative(String url) {
    return url.startsWith(base) ? url.substring(base.length()) : url;
  }

  /** The value of the page's {@code _csrf} hidden input, or null. */
  static String csrf(String html) {
    Element input = Jsoup.parse(html).selectFirst("input[name=_csrf]");
    return input == null ? null : input.attr("value");
  }

  /** Re-encodes a pages.txt form, adding {@code _csrf=token} unless the form sets it. */
  static String formBody(String form, String token) {
    List<String> pairs = new ArrayList<>();
    boolean hasCsrf = false;
    for (String pair : form.isEmpty() ? new String[0] : form.split("&")) {
      int eq = pair.indexOf('=');
      String name = decode(eq < 0 ? pair : pair.substring(0, eq));
      String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
      hasCsrf |= name.equals("_csrf");
      pairs.add(encode(name) + "=" + encode(value));
    }
    if (token != null && !hasCsrf) {
      pairs.add("_csrf=" + encode(token));
    }
    return String.join("&", pairs);
  }

  private static String decode(String s) {
    try {
      return URLDecoder.decode(s, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) { // a raw '%' that starts no escape
      return s;
    }
  }

  private static String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
