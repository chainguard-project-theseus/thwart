package dev.chainguard.thwart.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.CookieHandler;
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
  void errorBodiesTruncationAndCookieJarsPerCrawler() throws Exception {
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", ex -> {
      String path = ex.getRequestURI().getRawPath();
      // All Cookie headers, to see that the jar sends one, as a browser does.
      List<String> cookie = ex.getRequestHeaders().get("Cookie");
      if (path.equals("/set")) {
        ex.getResponseHeaders().add("Set-Cookie", "SID=s1; Path=/");
        ex.getResponseHeaders().add("Set-Cookie", "LANG=en; Path=/");
      }
      byte[] page = (path.equals("/echo") ? "cookie " + cookie : "page " + path)
          .getBytes(StandardCharsets.UTF_8);
      int status = path.equals("/missing") ? 404 : path.equals("/boom") ? 500 : 200;
      // /cut promises more than it sends, then hangs up.
      ex.sendResponseHeaders(status, path.equals("/cut") ? page.length + 100 : page.length);
      ex.getResponseBody().write(page);
      // Since JDK 25 the server drops a short response's buffered bytes on close: send them first.
      ex.getResponseBody().flush();
      ex.close();
    });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      Crawler a = new Crawler(base);
      assertEquals(new Crawler.Response(404, "/missing", "page /missing"),
          a.send(new Crawler.Request("", "GET", "/missing", "")), "a 4xx page keeps its body");
      assertEquals(new Crawler.Response(500, "/boom", "page /boom"),
          a.send(new Crawler.Request("", "POST", "/boom", "x=1")), "a 5xx page keeps its body");

      Crawler.Response cut = a.send(new Crawler.Request("", "GET", "/cut", ""));
      assertEquals(0, cut.status(), "a truncated response is no response");
      assertTrue(cut.body().contains("truncated"), cut.body());

      a.send(new Crawler.Request("", "GET", "/set", ""));
      assertEquals("cookie [SID=s1; LANG=en]", a.send(new Crawler.Request("", "GET", "/echo", "")).body());
      assertEquals("cookie null",
          new Crawler(base).send(new Crawler.Request("", "GET", "/echo", "")).body(),
          "each crawler has its own cookie jar");
      assertNull(CookieHandler.getDefault(), "the JVM-wide cookie handler is left alone");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void pagesPathsArePercentEncodedLikeABrowser() throws Exception {
    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", ex -> {
      String raw = ex.getRequestURI().getRawPath()
          + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
      seen.add(raw);
      byte[] page = ("page " + raw).getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(200, page.length);
      ex.getResponseBody().write(page);
      ex.close();
    });
    server.start();
    try {
      Crawler crawler = new Crawler("http://127.0.0.1:" + server.getAddress().getPort());
      Path pages = Files.writeString(tmp.resolve("pages.txt"), """
          GET /tags/new<i>
          GET /heiSearch?pattern=Kraków&q="x"
          GET /caf%C3%A9?url=https://h/x.xml%3Fa&b=%2F&c=100%
          POST /tags/new<i> name=a%26b
          """);
      List<String> finalUrls = new ArrayList<>();
      for (Crawler.Request request : Crawler.readPages(pages)) {
        Crawler.Response r = crawler.send(request);
        assertEquals(200, r.status(), r.body());
        finalUrls.add(r.finalUrl());
      }
      List<String> expected = List.of(
          "/tags/new%3Ci%3E",
          "/heiSearch?pattern=Krak%C3%B3w&q=%22x%22",
          "/caf%C3%A9?url=https://h/x.xml%3Fa&b=%2F&c=100%25",
          "/tags/new%3Ci%3E");
      assertEquals(expected, seen, "illegal characters encoded as UTF-8; escapes, ?&=/ kept");
      assertEquals(expected, finalUrls, "the recorded final URL is the encoded one");
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
