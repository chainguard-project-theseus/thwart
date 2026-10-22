package dev.tessera.migrate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
  static final class Request {
    private final String line;
    private final String method;
    private final String path;
    private final String form;

    Request(String line, String method, String path, String form) {
      this.line = line;
      this.method = method;
      this.path = path;
      this.form = form;
    }

    String line() {
      return line;
    }

    String method() {
      return method;
    }

    String path() {
      return path;
    }

    String form() {
      return form;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof Request)) {
        return false;
      }
      Request o = (Request) obj;
      return line.equals(o.line)
          && method.equals(o.method)
          && path.equals(o.path)
          && form.equals(o.form);
    }

    @Override
    public int hashCode() {
      return Objects.hash(line, method, path, form);
    }

    @Override
    public String toString() {
      return "Request[line=" + line + ", method=" + method + ", path=" + path + ", form=" + form + "]";
    }
  }

  /** What came back: status 0 means no response at all (the body then says why). */
  static final class Response {
    private final int status;
    private final String finalUrl;
    private final String body;

    Response(int status, String finalUrl, String body) {
      this.status = status;
      this.finalUrl = finalUrl;
      this.body = body;
    }

    int status() {
      return status;
    }

    String finalUrl() {
      return finalUrl;
    }

    String body() {
      return body;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof Response)) {
        return false;
      }
      Response o = (Response) obj;
      return status == o.status
          && finalUrl.equals(o.finalUrl)
          && body.equals(o.body);
    }

    @Override
    public int hashCode() {
      return Objects.hash(status, finalUrl, body);
    }

    @Override
    public String toString() {
      return "Response[status=" + status + ", finalUrl=" + finalUrl + ", body=" + body + "]";
    }
  }

  private static final int CONNECT_TIMEOUT_MS = 10_000;
  private static final int READ_TIMEOUT_MS = 60_000;

  private final String base;
  /** This run's cookie jar, applied by hand: the JVM-wide default CookieHandler stays untouched. */
  private final CookieManager cookies = new CookieManager();
  private String previousBody = "";

  Crawler(String baseUrl) {
    this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  /** Parses {@code pages.txt}. */
  static List<Request> readPages(Path file) throws IOException {
    List<Request> requests = new ArrayList<>();
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    for (int i = 0; i < lines.size(); i++) {
      String line = Compat.strip(lines.get(i));
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
      URI uri = URI.create(base + request.path());
      String method = request.method();
      String body = method.equals("POST") ? formBody(request.form(), csrf(previousBody)) : null;
      int status;
      String page;
      for (int hops = 0; ; hops++) {
        HttpURLConnection http = exchange(uri, method, body);
        status = http.getResponseCode();
        // Without a Location (a page rendered with status 303, say) a browser shows the body.
        String location = http.getHeaderField("Location");
        if (location == null || !isRedirect(status)) {
          page = readBody(http, status);
          break;
        }
        readBody(http, status); // drain, so the connection can be reused
        if (hops == MAX_REDIRECTS) {
          throw new IOException("more than " + MAX_REDIRECTS + " redirects");
        }
        // Header bytes arrive as ISO-8859-1 chars; a browser reads a Location as UTF-8.
        location = new String(location.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        uri = uri.resolve(encodeUrl(location));
        if (status != 307 && status != 308) {
          method = "GET";
          body = null;
        }
      }
      response = new Response(status, relative(uri.toString()), page);
    } catch (IOException | IllegalArgumentException e) {
      response = new Response(0, request.path(),
          "<!-- no response: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " -->");
    }
    previousBody = response.body();
    return response;
  }

  private static final int MAX_REDIRECTS = 10;

  /** Sends one HTTP request (no redirects followed) and stores the cookies it sets. */
  private HttpURLConnection exchange(URI uri, String method, String body) throws IOException {
    String scheme = uri.getScheme();
    if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
      throw new IOException("not an http(s) URL: " + uri);
    }
    HttpURLConnection http = (HttpURLConnection) uri.toURL().openConnection();
    http.setInstanceFollowRedirects(false); // followed in send(), like a browser
    http.setUseCaches(false);
    http.setConnectTimeout(CONNECT_TIMEOUT_MS);
    http.setReadTimeout(READ_TIMEOUT_MS);
    http.setRequestMethod(method);
    http.setRequestProperty("Accept", "text/html");
    http.setRequestProperty("Accept-Language", "en");
    for (Map.Entry<String, List<String>> header
        : cookies.get(uri, Collections.<String, List<String>>emptyMap()).entrySet()) {
      for (String value : header.getValue()) {
        http.addRequestProperty(header.getKey(), value);
      }
    }
    if (body != null) {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      http.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
      http.setDoOutput(true);
      // Streaming mode also stops HttpURLConnection from silently re-sending a POST.
      http.setFixedLengthStreamingMode(bytes.length);
      try (OutputStream out = http.getOutputStream()) {
        out.write(bytes);
      }
    }
    int status = http.getResponseCode();
    if (status < 0) {
      throw new IOException("not an HTTP response from " + uri);
    }
    cookies.put(uri, http.getHeaderFields());
    return http;
  }

  /**
   * The response body as UTF-8 ({@code getErrorStream} for a 4xx/5xx). A body shorter than its
   * {@code Content-Length} is an error, as it was with {@code java.net.http}.
   */
  private static String readBody(HttpURLConnection http, int status) throws IOException {
    InputStream stream = status >= 400 ? http.getErrorStream() : http.getInputStream();
    byte[] bytes;
    if (stream == null) {
      bytes = new byte[0];
    } else {
      try (InputStream in = stream) {
        bytes = Compat.readAllBytes(in);
      }
    }
    long length = http.getContentLengthLong();
    if (length >= 0 && bytes.length < length) {
      throw new IOException("response truncated: got " + bytes.length + " of " + length + " bytes");
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }
  private static boolean isRedirect(int status) {
    return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
  }

  /**
   * Percent-encodes what a browser would in a {@code Location} before resolving it: every
   * character outside the URI's ASCII set (spaces, non-ASCII as UTF-8, {@code "<>\^`{|}}) and a
   * {@code %} that starts no escape. Existing escapes and the URI's delimiters stay as they are.
   */
  static String encodeUrl(String url) {
    StringBuilder out = new StringBuilder();
    byte[] bytes = Compat.strip(url).getBytes(StandardCharsets.UTF_8);
    for (int i = 0; i < bytes.length; i++) {
      int b = bytes[i] & 0xff;
      boolean escape = b == '%' && i + 2 < bytes.length && isHex(bytes[i + 1]) && isHex(bytes[i + 2]);
      if (escape || (b != '%' && b < 0x80 && URI_CHARS.indexOf(b) >= 0)) {
        out.append((char) b);
      } else {
        out.append('%').append(HEX.charAt(b >> 4)).append(HEX.charAt(b & 0xf));
      }
    }
    return out.toString();
  }

  private static final String HEX = "0123456789ABCDEF";
  private static final String URI_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
      + "0123456789-._~:/?#[]@!$&'()*+,;=";

  private static boolean isHex(byte b) {
    return HEX.indexOf(Character.toUpperCase((char) b)) >= 0;
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
      return URLDecoder.decode(s, "UTF-8");
    } catch (IllegalArgumentException e) { // a raw '%' that starts no escape
      return s;
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError(e); // every JDK has UTF-8
    }
  }

  private static String encode(String s) {
    try {
      return URLEncoder.encode(s, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError(e);
    }
  }
}
