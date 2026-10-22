package dev.tessera.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End to end: record against a small in-process app, then compare against variants of it. The
 * app has a session cookie, a login form whose POST must carry the page's CSRF token, and a page
 * behind the login, so a broken cookie jar or a missing token changes the recorded pages.
 */
class ParityTest {

  /** How the app renders: the original, the same pages written differently, or regressions. */
  enum Variant { ORIGINAL, REWRITTEN, REORDERED, BROKEN, NOT_FOUND_IS_200 }

  @TempDir Path tmp;

  private HttpServer server;
  private String base;
  private volatile Variant variant = Variant.ORIGINAL;
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();
  private Path pages;

  private static final class Session {
    final String csrf = UUID.randomUUID().toString();
    String user;
  }

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", this::handle);
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    pages = Files.writeString(tmp.resolve("pages.txt"), """
        # anonymous, then log in (the POST carries the login page's _csrf), then a page behind login
        GET /login
        POST /login username=ann&password=wrong
        POST /login username=ann&password=s3cret
        GET /errors
        GET /missing
        """);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void handle(HttpExchange ex) throws IOException {
    String sid = null;
    String cookie = ex.getRequestHeaders().getFirst("Cookie");
    if (cookie != null) {
      for (String c : cookie.split(";\\s*")) {
        if (c.startsWith("SID=") && sessions.containsKey(c.substring(4))) {
          sid = c.substring(4);
        }
      }
    }
    if (sid == null) {
      sid = UUID.randomUUID().toString();
      sessions.put(sid, new Session());
      ex.getResponseHeaders().add("Set-Cookie", "SID=" + sid + "; Path=/; HttpOnly");
    }
    Session session = sessions.get(sid);
    String path = ex.getRequestURI().getPath();
    String method = ex.getRequestMethod();
    Variant v = variant;

    if (path.equals("/login") && method.equals("POST")) {
      Map<String, String> form = form(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      if (!session.csrf.equals(form.get("_csrf"))) {
        send(ex, 403, "<p>Invalid CSRF token</p>");
      } else if ("s3cret".equals(form.get("password"))) {
        session.user = form.get("username");
        // jsessionid on the redirect, as servlet containers do before they know cookies work.
        redirect(ex, "/home;jsessionid=" + sid);
      } else {
        send(ex, 200, loginPage(session, v, "Bad credentials"));
      }
    } else if (path.equals("/login")) {
      send(ex, 200, loginPage(session, v, null));
    } else if (session.user == null) {
      redirect(ex, "/login");
    } else if (path.startsWith("/home")) {
      send(ex, 200, v == Variant.REWRITTEN
          ? "<!doctype html>\n<html><head><title>Home</title></head>\n<body>\n  <h1 class='title big'>Hello,\n "
              + session.user + "</h1>\n  <p>Today is " + LocalDate.now() + "</p>\n</body></html>\n"
          : "<!DOCTYPE html><title>Home</title><h1 class='big title'>Hello, " + session.user
              + "</h1><p>Today is " + LocalDate.now());
    } else if (path.equals("/errors")) {
      String a = "<li>must not be empty";
      String b = "<li>numeric value out of bounds";
      send(ex, 200, switch (v) {
        case REORDERED -> "<ul class=errors>" + b + a + "</ul>";
        case BROKEN -> "<ul class=errors>" + a + "<li>numeric value out of bound</ul>";
        default -> "<ul class=errors>" + a + b + "</ul>";
      });
    } else {
      send(ex, v == Variant.NOT_FOUND_IS_200 ? 200 : 404, "<h1>Not found</h1>");
    }
  }

  private static String loginPage(Session session, Variant v, String error) {
    String err = error == null ? "" : "<p class=error>" + error + "</p>";
    return v == Variant.REWRITTEN
        ? "<!doctype html><html><body>" + err + "<form method=\"post\" action=\"/login\">\n"
            + "<input name=\"username\" value=\"\" required=\"required\"/>\n"
            + "<input type=\"password\" name=\"password\"/>\n"
            + "<input name=\"_csrf\" type=\"hidden\" value=\"" + session.csrf + "\"/>\n"
            + "</form></body></html>"
        : "<!DOCTYPE html>" + err + "<form action=/login method=post>"
            + "<input name=username required><input name=password type=password>"
            + "<input type=hidden name=_csrf value=" + session.csrf + "></form>";
  }

  private static Map<String, String> form(String body) {
    Map<String, String> form = new HashMap<>();
    for (String pair : body.split("&")) {
      String[] kv = pair.split("=", 2);
      form.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
          kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
    }
    return form;
  }

  private static void redirect(HttpExchange ex, String location) throws IOException {
    ex.getResponseHeaders().add("Location", location);
    ex.sendResponseHeaders(302, -1);
    ex.close();
  }

  private static void send(HttpExchange ex, int status, String html) throws IOException {
    byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "text/html;charset=UTF-8");
    ex.sendResponseHeaders(status, bytes.length);
    ex.getResponseBody().write(bytes);
    ex.close();
  }

  private int run(String... args) {
    out.reset();
    err.reset();
    return Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  private String out() {
    return out.toString(StandardCharsets.UTF_8);
  }

  private String err() {
    return err.toString(StandardCharsets.UTF_8);
  }

  private Path record() throws IOException {
    Path dir = tmp.resolve("recorded");
    assertEquals(0, run("record", base, pages.toString(), dir.toString()), err());
    return dir;
  }

  @Test
  void recordSavesStatusFinalUrlAndBodyPerRequest() throws IOException {
    Path dir = record();

    assertEquals("""
        01 200 GET /login
        02 200 POST /login username=ann&password=wrong
        03 200 POST /login username=ann&password=s3cret
        04 200 GET /errors
        05 404 GET /missing
        tessera-migrate record: 5 page(s) saved to %s
        """.formatted(dir), out().replace(System.lineSeparator(), "\n"));
    String index = Files.readString(dir.resolve(Parity.INDEX));
    assertTrue(index.startsWith("# tessera-migrate record " + LocalDate.now() + " " + base + "\n"), index);
    // The login POST sent the CSRF token and the session cookie: it landed on /home, logged in.
    assertTrue(index.contains("03.html\t200\t/home;jsessionid="), index);
    assertTrue(Files.readString(dir.resolve("03.html")).contains("Hello, ann"));
    assertTrue(Files.readString(dir.resolve("02.html")).contains("Bad credentials"));
    assertTrue(index.contains("05.html\t404\t/missing\tGET /missing\n"), index);
  }

  @Test
  void sameAppComparesSame() throws IOException {
    Path dir = record();

    assertEquals(0, run("compare", base, pages.toString(), dir.toString()), out());

    assertEquals("""
        01 same      GET /login
        02 same      POST /login username=ann&password=wrong
        03 same      POST /login username=ann&password=s3cret
        04 same      GET /errors
        05 same      GET /missing
        tessera-migrate compare: 5 page(s): 5 same, 0 reordered, 0 differ
        """, out().replace(System.lineSeparator(), "\n"));
  }

  @Test
  void differentlyWrittenHtmlComparesSame() throws IOException {
    Path dir = record();
    variant = Variant.REWRITTEN;

    assertEquals(0, run("compare", base, pages.toString(), dir.toString()), out());
    assertTrue(out().endsWith("5 same, 0 reordered, 0 differ" + System.lineSeparator()), out());
  }

  @Test
  void reorderedMessagesPassAsReordered() throws IOException {
    Path dir = record();
    variant = Variant.REORDERED;

    assertEquals(0, run("compare", base, pages.toString(), dir.toString()), out());
    assertTrue(out().contains("04 reordered GET /errors"), out());
    assertTrue(out().contains("4 same, 1 reordered, 0 differ"), out());
  }

  @Test
  void changedTextDiffersWithAUnifiedDiff() throws IOException {
    Path dir = record();
    variant = Variant.BROKEN;

    assertEquals(1, run("compare", base, pages.toString(), dir.toString()));
    assertTrue(out().contains("""
        04 differs   GET /errors
        --- recorded 04.html
        +++ now
        @@ -5,4 +5,4 @@
               <li>
                 must not be empty
               <li>
        -        numeric value out of bounds
        +        numeric value out of bound
        """), out());
    assertTrue(out().contains("4 same, 0 reordered, 1 differ"), out());
  }

  @Test
  void statusMismatchDiffersEvenIfThePageIsTheSame() throws IOException {
    Path dir = record();
    variant = Variant.NOT_FOUND_IS_200;

    assertEquals(1, run("compare", base, pages.toString(), dir.toString()));
    assertTrue(out().contains("""
        05 differs   GET /missing
           status: recorded 404, got 200
           page: same
        """), out());
  }

  @Test
  void aRecordedDateComparesAsToday() throws IOException {
    Path dir = record();
    String yesterday = LocalDate.now().minusDays(1).toString();
    Path index = dir.resolve(Parity.INDEX);
    Files.writeString(index, Files.readString(index).replaceFirst("record \\S+", "record " + yesterday));
    Path home = dir.resolve("03.html");
    Files.writeString(home, Files.readString(home).replace(LocalDate.now().toString(), yesterday));

    assertEquals(0, run("compare", base, pages.toString(), dir.toString()), out());
  }

  @Test
  void deadAppIsNoResponse() throws IOException {
    Path dir = record();
    server.stop(0);

    assertEquals(1, run("compare", base, pages.toString(), dir.toString()));
    assertTrue(out().contains("01 differs   GET /login\n   status: recorded 200, got 0 (<!-- no response:"
        .replace("\n", System.lineSeparator())), out());
    assertEquals(1, run("record", base, pages.toString(), tmp.resolve("dead").toString()));
  }

  @Test
  void pagesFileMustMatchTheRecording() throws IOException {
    Path dir = record();
    Files.writeString(pages, "GET /login\n");

    assertEquals(1, run("compare", base, pages.toString(), dir.toString()));
    assertTrue(err().contains("record again"));
  }

  @Test
  void usageErrors() {
    assertEquals(2, run("record", base, pages.toString()));
    assertTrue(err().contains("record needs <base-url> <pages.txt> <dir>"));
    assertEquals(2, run("compare", base, tmp.resolve("nope.txt").toString(), tmp.toString()));
    assertTrue(err().contains("not a file"));
  }
}
