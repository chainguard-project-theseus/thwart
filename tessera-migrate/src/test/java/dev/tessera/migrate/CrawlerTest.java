package dev.tessera.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrawlerTest {

  @TempDir Path tmp;

  @Test
  void pagesFileSkipsCommentsAndKeepsSpacesInForms() throws Exception {
    Path pages = Files.writeString(tmp.resolve("pages.txt"), """
        # comment

        GET /owners?lastName=
          POST /owners/new firstName=Ann&lastName=O'Neil <b>&address=1 Main St
        """);
    List<Crawler.Request> requests = Crawler.readPages(pages);
    assertEquals(2, requests.size());
    assertEquals(new Crawler.Request("GET /owners?lastName=", "GET", "/owners?lastName=", ""),
        requests.get(0));
    assertEquals("firstName=Ann&lastName=O'Neil <b>&address=1 Main St", requests.get(1).form());
  }

  @Test
  void badLineNamesFileAndLine() throws Exception {
    Path pages = Files.writeString(tmp.resolve("pages.txt"), "GET /\nPUT /x\n");
    IOException e = assertThrows(IOException.class, () -> Crawler.readPages(pages));
    assertTrue(e.getMessage().contains("pages.txt:2:"), e.getMessage());
  }

  @Test
  void formBodyIsReEncodedAndGetsTheCsrfToken() {
    assertEquals("lastName=O%27Neil+%3Cb%3E&phone=%2B996&a=b+c&_csrf=t%2F1",
        Crawler.formBody("lastName=O'Neil <b>&phone=%2B996&a=b+c", "t/1"));
    assertEquals("_csrf=mine", Crawler.formBody("_csrf=mine", "t"), "an explicit _csrf wins");
    assertEquals("a=", Crawler.formBody("a", null));
    assertEquals("p=100%25", Crawler.formBody("p=100%", null), "a raw % is sent as written");
  }

  @Test
  void csrfComesFromTheHiddenInput() {
    assertEquals("abc", Crawler.csrf("<form><input value='abc' type=hidden name=_csrf></form>"));
    assertNull(Crawler.csrf("<form><input name=csrf value=x></form>"));
  }

  @Test
  void redirectsAreFollowedLikeABrowser() throws Exception {
    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", ex -> {
      String path = ex.getRequestURI().getRawPath();
      String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      seen.add(ex.getRequestMethod() + " " + path + (body.isEmpty() ? "" : " " + body));
      String location = switch (path) {
        // Raw UTF-8 bytes and a space on the wire, as Spring sends an unencoded redirect.
        case "/form" -> new String("/a b/привет?q=x y".getBytes(StandardCharsets.UTF_8),
            StandardCharsets.ISO_8859_1);
        case "/a%20b/%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82" -> "next"; // relative to /a%20b/
        case "/keep" -> "/a%20b/next";
        case "/loop" -> "/loop";
        case "/bad" -> "http://[bad/";
        default -> null;
      };
      int status = location != null ? (path.equals("/keep") ? 307 : 302)
          : path.equals("/see-other") ? 303 : 200;
      if (location != null) {
        ex.getResponseHeaders().set("Location", location);
      }
      byte[] page = ("page " + path).getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(status, page.length);
      ex.getResponseBody().write(page);
      ex.close();
    });
    server.start();
    try {
      Crawler crawler = new Crawler("http://127.0.0.1:" + server.getAddress().getPort());

      Crawler.Response r = crawler.send(new Crawler.Request("", "POST", "/form", "a=1"));
      assertEquals(new Crawler.Response(200, "/a%20b/next", "page /a%20b/next"), r);
      assertEquals(List.of("POST /form a=1",
          "GET /a%20b/%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82", "GET /a%20b/next"), seen,
          "302 turns the POST into a GET; illegal characters are percent-encoded as UTF-8");

      seen.clear();
      r = crawler.send(new Crawler.Request("", "POST", "/keep", "b=2"));
      assertEquals(200, r.status());
      assertEquals(List.of("POST /keep b=2", "POST /a%20b/next b=2"), seen,
          "307 keeps the method and body");

      r = crawler.send(new Crawler.Request("", "GET", "/see-other", ""));
      assertEquals(new Crawler.Response(303, "/see-other", "page /see-other"), r,
          "a redirect status without a Location is a page, as in a browser");

      seen.clear();
      r = crawler.send(new Crawler.Request("", "GET", "/loop", ""));
      assertEquals(0, r.status());
      assertTrue(r.body().contains("more than 10 redirects"), r.body());
      assertEquals(11, seen.size(), "the first request and ten redirects");

      r = crawler.send(new Crawler.Request("", "GET", "/bad", ""));
      assertEquals(0, r.status(), "an unusable Location is no response, not a crash");
      assertTrue(r.body().startsWith("<!-- no response: "), r.body());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void encodeUrlEscapesOnlyWhatIsIllegal() {
    assertEquals("/a%20b/%D0%BF?q=%22x%22&r=%25&s=%2F#f",
        Crawler.encodeUrl("/a b/п?q=\"x\"&r=%&s=%2F#f"));
    assertEquals("//host/x%2f%25", Crawler.encodeUrl(" //host/x%2f% "));
  }
}
