package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EscapersTest {

  private static String run(BiConsumer<Output, Object> escaper, Object value) {
    StringOutput out = new StringOutput();
    escaper.accept(out, value);
    return out.toString();
  }

  private static String text(Object v) {
    return run(Escapers::text, v);
  }

  private static String attr(Object v) {
    return run(Escapers::attr, v);
  }

  private static String url(Object v) {
    return run(Escapers::url, v);
  }

  private static String resourceUrl(Object v) {
    return run(Escapers::resourceUrl, v);
  }

  private static String json(Object v) {
    return run(Escapers::json, v);
  }

  private static String html(SafeHtml v) {
    StringOutput out = new StringOutput();
    Escapers.html(out, v);
    return out.toString();
  }

  /** No raw HTML-significant character survives escaping. */
  private static void assertInertHtml(String escaped) {
    for (char c : new char[] {'<', '>', '"', '\''}) {
      assertEquals(-1, escaped.indexOf(c), () -> "raw " + c + " in " + escaped);
    }
    // Every & starts one of our entities.
    assertFalse(escaped.replaceAll("&(amp|lt|gt|quot|#39);", "").contains("&"), escaped);
  }

  // --- toText ----------------------------------------------------------------------------------

  @Test
  void toTextConvertsValues() {
    assertEquals("", Escapers.toText(null));
    assertEquals("42", Escapers.toText(42));
    assertEquals("a<b", Escapers.toText("a<b"));
    assertEquals("<b>", Escapers.toText(SafeHtml.unchecked("<b>")));
  }

  // --- text / attr -----------------------------------------------------------------------------

  @Test
  void textEscapesHtmlSpecials() {
    assertEquals("Tom &amp; Jerry", text("Tom & Jerry"));
    assertEquals("&lt;b&gt;&quot;x&quot; &#39;y&#39;&lt;/b&gt;", text("<b>\"x\" 'y'</b>"));
    assertEquals("plain text", text("plain text"));
    assertEquals("42", text(42));
    assertEquals("\u00e9\u4e2d\ud83d\ude00", text("\u00e9\u4e2d\ud83d\ude00"));
  }

  @Test
  void nullIsEmpty() {
    assertEquals("", text(null));
    assertEquals("", attr(null));
    assertEquals("", url(null));
    assertEquals("", resourceUrl(null));
    assertEquals("", html(null));
    assertEquals("null", json(null));
  }

  @Test
  void textEscapesSafeHtml() {
    assertEquals("&lt;b&gt;bold&lt;/b&gt;", text(SafeHtml.unchecked("<b>bold</b>")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\"><script>alert(1)</script>",
        "' onmouseover='alert(1)",
        "\" onmouseover=\"alert(1)",
        "</script><script>alert(1)</script>",
        "<!--",
        "-->",
        "]]>",
        "<![CDATA[x]]>",
        "&#106;avascript:alert(1)",
        "&lt;already&gt;",
        "<img src=x onerror=alert(1)>",
      })
  void textAndAttrNeutralizeMarkup(String payload) {
    assertInertHtml(text(payload));
    assertInertHtml(attr(payload));
  }

  @Test
  void attrCannotBreakOutOfQuotes() {
    assertEquals("&quot;&gt;&lt;script&gt;", attr("\"><script>"));
    assertEquals("&#39; onmouseover=", attr("' onmouseover="));
    // A pre-encoded entity is encoded again, so it cannot decode to anything active.
    assertEquals("&amp;#106;avascript:", attr("&#106;avascript:"));
  }

  @Test
  void htmlEscapersReplaceNulAndUnpairedSurrogates() {
    assertEquals("a\uFFFDb", text("a\u0000b"));
    assertEquals("a\uFFFDb", attr("a\ud800b"));
    assertEquals("a\uFFFDb", text("a\udc00b"));
    assertEquals("\uFFFD", text("\ud800"));
    assertEquals("x\uFFFD\uFFFD", text("x\udc00\ud800"));
    assertEquals("\ud83d\ude00", text("\ud83d\ude00"));
  }

  @Test
  void lineSeparatorsPassThroughHtml() {
    assertEquals("a\u2028b\u2029c", text("a\u2028b\u2029c"));
  }

  // --- url -------------------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://example.com/",
        "https://example.com/a?b=c",
        "HTTPS://EXAMPLE.COM",
        "mailto:a@example.com",
        "tel:+15551234",
        "/owners/1",
        "owners/1",
        "../x",
        "//cdn.example.com/x.js",
        "#frag",
        "?q=1",
        "",
        "/a:b",
        "?next=javascript:alert(1)",
        "#javascript:alert(1)",
        " https://example.com",
        "ht\ttps://example.com",
      })
  void urlAllowsSafeUrls(String u) {
    assertNotEquals(Escapers.INVALID_URL, url(u));
    assertEquals(attr(u), url(u));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "javascript:alert(1)",
        "JaVaScRiPt:alert(1)",
        "JAVASCRIPT:alert(1)",
        " javascript:alert(1)",
        "\t\n javascript:alert(1)",
        "java\tscript:alert(1)",
        "java\nscript:alert(1)",
        "java\rscript:alert(1)",
        "\u0000javascript:alert(1)",
        "\u0001\u001fjavascript:alert(1)",
        "vbscript:msgbox(1)",
        "data:text/html,<script>alert(1)</script>",
        "data:text/html;base64,PHNjcmlwdD4=",
        "DATA:image/svg+xml,<svg onload=alert(1)>",
        "file:///etc/passwd",
        "javascript&colon;alert(1):",
        "httpſ:x",
        "https\u0000:x",
        "blob:https://example.com/x",
        "foo:bar",
        ":x",
      })
  void urlRejectsDangerousSchemes(String u) {
    assertEquals(Escapers.INVALID_URL, url(u));
    assertEquals(Escapers.INVALID_URL, resourceUrl(u));
  }

  @Test
  void urlEntityEncodedSchemeStaysInert() {
    // The '#' makes these relative URLs; '&' is encoded so the browser never decodes the entity.
    assertEquals("&amp;#106;avascript:alert(1)", url("&#106;avascript:alert(1)"));
    assertEquals("jav&amp;#x09;ascript:alert(1)", url("jav&#x09;ascript:alert(1)"));
    assertEquals("&amp;#x6A;avascript:alert(1)", url("&#x6A;avascript:alert(1)"));
  }

  @Test
  void urlAttributeEncodesAllowedValues() {
    assertEquals("/search?a=1&amp;b=&quot;x&quot;", url("/search?a=1&b=\"x\""));
    assertEquals("https://x/&#39;onmouseover=", url("https://x/'onmouseover="));
    assertInertHtml(url("/\"><script>alert(1)</script>"));
  }

  @Test
  void urlChecksToTextOfOtherObjects() {
    Object o =
        new Object() {
          @Override
          public String toString() {
            return "javascript:alert(1)";
          }
        };
    assertEquals(Escapers.INVALID_URL, url(o));
    assertEquals(Escapers.INVALID_URL, url(SafeHtml.unchecked("javascript:alert(1)")));
  }

  @Test
  void trustedUrlSkipsSchemeCheckButIsStillAttributeEncoded() {
    assertEquals("data:image/png;base64,AAAA", url(TrustedUrl.unchecked("data:image/png;base64,AAAA")));
    assertEquals("x&quot;&gt;&lt;script&gt;", url(TrustedUrl.unchecked("x\"><script>")));
  }

  // --- resourceUrl -----------------------------------------------------------------------------

  @Test
  void resourceUrlAcceptsTrustedUrlAndLinks() {
    assertEquals("/js/app.js?v=1&amp;x=2", resourceUrl("/js/app.js?v=1&x=2"));
    assertEquals("https://cdn.example.com/a.js", resourceUrl("https://cdn.example.com/a.js"));
    assertEquals("blob:x&quot;", resourceUrl(TrustedUrl.unchecked("blob:x\"")));
  }

  @Test
  void resourceUrlRejectsOtherTypes() {
    assertThrows(IllegalArgumentException.class, () -> resourceUrl(42));
    assertThrows(IllegalArgumentException.class, () -> resourceUrl(SafeHtml.unchecked("/a.js")));
    assertThrows(IllegalArgumentException.class, () -> resourceUrl(new StringBuilder("/a.js")));
  }

  // --- html ------------------------------------------------------------------------------------

  @Test
  void htmlWritesSafeHtmlVerbatim() {
    assertEquals("<b>bold</b>", html(SafeHtml.unchecked("<b>bold</b>")));
    assertEquals("", html(SafeHtml.EMPTY));
    assertEquals("&lt;script&gt;", html(SafeHtml.escape("<script>")));
  }

  // --- json ------------------------------------------------------------------------------------

  enum Color {
    RED,
    GREEN {
      @Override
      public String toString() {
        return "</script>";
      }
    }
  }

  @Test
  void jsonScalars() {
    assertEquals("true", json(true));
    assertEquals("false", json(Boolean.FALSE));
    assertEquals("1", json((byte) 1));
    assertEquals("2", json((short) 2));
    assertEquals("-3", json(-3));
    assertEquals("9007199254740993", json(9007199254740993L));
    assertEquals("1.5", json(1.5));
    assertEquals("2.5", json(2.5f));
    assertEquals("123456789012345678901234567890", json(new BigInteger("123456789012345678901234567890")));
    assertEquals("1.10", json(new BigDecimal("1.10")));
    assertEquals("\"abc\"", json("abc"));
    assertEquals("\"sb\"", json(new StringBuilder("sb")));
    assertEquals("\"x\"", json('x'));
    assertEquals("\"\"", json(""));
  }

  @Test
  void jsonNonFiniteNumbersBecomeNull() {
    assertEquals("null", json(Double.NaN));
    assertEquals("null", json(Double.POSITIVE_INFINITY));
    assertEquals("null", json(Float.NEGATIVE_INFINITY));
    assertEquals("[1.0,null]", json(new double[] {1.0, Double.NaN}));
  }

  @Test
  void jsonEnumUsesName() {
    assertEquals("\"RED\"", json(Color.RED));
    assertEquals("\"GREEN\"", json(Color.GREEN));
    assertEquals("\"MONDAY\"", json(DayOfWeek.MONDAY));
  }

  @Test
  void jsonStringEscapes() {
    assertEquals("\"a\\\"b\\\\c\"", json("a\"b\\c"));
    assertEquals("\"\\n\\r\\t\\b\\f\"", json("\n\r\t\b\f"));
    assertEquals("\"\\u0000\\u001f\\u007f\"", json("\u0000\u001f\u007f"));
    assertEquals("\"\\u003c\\u003e\\u0026\\u0027\"", json("<>&'"));
    assertEquals("\"\\u2028\\u2029\"", json("\u2028\u2029"));
    assertEquals("\"\u00e9\ud83d\ude00\"", json("\u00e9\ud83d\ude00"));
  }

  @Test
  void jsonEscapesUnpairedSurrogates() {
    assertEquals("\"\\ud800\"", json("\ud800"));
    assertEquals("\"a\\udc00b\"", json("a\udc00b"));
    assertEquals("\"\\udc00\\ud800\"", json("\udc00\ud800"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "</script><script>alert(1)</script>",
        "</SCRIPT>",
        "<!--<script>",
        "]]>",
        "\"; alert(1); //",
        "'; alert(1); //",
        "\u2028alert(1)",
        "\\\"",
      })
  void jsonCannotCloseScriptOrString(String payload) {
    String out = json(payload);
    for (char c : new char[] {'<', '>', '&', '\'', '\u2028', '\u2029'}) {
      assertEquals(-1, out.indexOf(c), () -> "raw " + c + " in " + out);
    }
    // Exactly the two delimiting quotes are unescaped.
    String body = out.substring(1, out.length() - 1);
    assertTrue(out.startsWith("\"") && out.endsWith("\""));
    assertFalse(body.replace("\\\\", "").replace("\\\"", "").contains("\""), out);
  }

  @Test
  void jsonMapKeysAreEscapedToo() {
    assertEquals("{\"\\u003c/script\\u003e\":1}", json(Map.of("</script>", 1)));
  }

  @Test
  void jsonNestedStructures() {
    Map<String, Object> inner = new LinkedHashMap<>();
    inner.put("tags", List.of("a", "b"));
    inner.put("none", null);
    inner.put("opt", Optional.of(Color.RED));
    inner.put("empty", Optional.empty());
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", "O'Brien");
    m.put("inner", inner);
    m.put("ids", new int[] {1, 2, 3});
    m.put("names", new String[] {"x", null});
    m.put("grid", new long[][] {{1L}, {2L, 3L}});
    m.put("flags", new boolean[] {true, false});
    m.put("chars", new char[] {'h', '<'});
    m.put("set", Set.of(7));
    m.put("emptyList", List.of());
    m.put("emptyMap", Map.of());
    assertEquals(
        "{\"name\":\"O\\u0027Brien\","
            + "\"inner\":{\"tags\":[\"a\",\"b\"],\"none\":null,\"opt\":\"RED\",\"empty\":null},"
            + "\"ids\":[1,2,3],\"names\":[\"x\",null],\"grid\":[[1],[2,3]],"
            + "\"flags\":[true,false],\"chars\":\"h\\u003c\",\"set\":[7],"
            + "\"emptyList\":[],\"emptyMap\":{}}",
        json(m));
  }

  @Test
  void jsonPrimitiveArrays() {
    assertEquals("[1,2]", json(new byte[] {1, 2}));
    assertEquals("[1,2]", json(new short[] {1, 2}));
    assertEquals("[1,2]", json(new long[] {1, 2}));
    assertEquals("[1.5]", json(new float[] {1.5f}));
    assertEquals("[]", json(new int[0]));
  }

  @Test
  void jsonJavaTime() {
    assertEquals("\"2024-02-29\"", json(LocalDate.of(2024, 2, 29)));
    assertEquals("\"10:15:30\"", json(LocalTime.of(10, 15, 30)));
    assertEquals("\"2024-02-29T10:15\"", json(LocalDateTime.of(2024, 2, 29, 10, 15)));
    assertEquals("\"1970-01-01T00:00:00Z\"", json(Instant.EPOCH));
    assertEquals(
        "\"2024-02-29T10:15+01:00\"",
        json(OffsetDateTime.of(2024, 2, 29, 10, 15, 0, 0, ZoneOffset.ofHours(1))));
    assertEquals(
        "\"2024-02-29T10:15Z[UTC]\"",
        json(ZonedDateTime.of(2024, 2, 29, 10, 15, 0, 0, ZoneId.of("UTC"))));
    assertEquals("\"PT1H\"", json(Duration.ofHours(1)));
    assertEquals("\"P1D\"", json(Period.ofDays(1)));
    assertEquals("\"2024\"", json(Year.of(2024)));
    assertEquals("\"2024-02\"", json(YearMonth.of(2024, 2)));
    assertEquals("\"Europe/Paris\"", json(ZoneId.of("Europe/Paris")));
    assertEquals("[\"2024-02-29\"]", json(List.of(LocalDate.of(2024, 2, 29))));
  }

  record Point(int x, int y) {}

  @Test
  void jsonRejectsUnsupportedTypes() {
    assertThrows(IllegalArgumentException.class, () -> json(new Object()));
    assertThrows(IllegalArgumentException.class, () -> json(new Point(1, 2)));
    assertThrows(IllegalArgumentException.class, () -> json(SafeHtml.unchecked("<b>")));
    assertThrows(IllegalArgumentException.class, () -> json(TrustedUrl.unchecked("/x")));
    assertThrows(IllegalArgumentException.class, () -> json(Object.class));
    assertThrows(IllegalArgumentException.class, () -> json(List.of(new Object())));
    assertThrows(IllegalArgumentException.class, () -> json(Map.of("k", new Point(0, 0))));
  }

  @Test
  void jsonRejectsCustomNumbersWhoseTextIsUnchecked() {
    Number evil =
        new Number() {
          @Override
          public int intValue() {
            return 0;
          }

          @Override
          public long longValue() {
            return 0;
          }

          @Override
          public float floatValue() {
            return 0;
          }

          @Override
          public double doubleValue() {
            return 0;
          }

          @Override
          public String toString() {
            return "0;</script><script>alert(1)//";
          }
        };
    assertThrows(IllegalArgumentException.class, () -> json(evil));
    assertThrows(IllegalArgumentException.class, () -> json(new AtomicInteger(1)));
  }

  @Test
  void jsonRejectsNonStringKeys() {
    assertThrows(IllegalArgumentException.class, () -> json(Map.of(1, "a")));
    Map<String, Object> m = new LinkedHashMap<>();
    m.put(null, "a");
    assertThrows(IllegalArgumentException.class, () -> json(m));
  }

  @Test
  void jsonRejectsCyclesWithoutStackOverflow() {
    List<Object> self = new ArrayList<>();
    self.add(self);
    assertThrows(IllegalArgumentException.class, () -> json(self));
  }

  @Test
  void jsonWritesNothingOnFailure() {
    StringOutput out = new StringOutput();
    assertThrows(
        IllegalArgumentException.class, () -> Escapers.json(out, List.of("ok", new Object())));
    assertEquals("", out.toString());
  }

  // --- safe types and outputs ------------------------------------------------------------------

  @Test
  void safeHtmlEscapeAndUnchecked() {
    assertEquals("&lt;i&gt;&amp;&lt;/i&gt;", SafeHtml.escape("<i>&</i>").html());
    assertEquals("", SafeHtml.escape(null).html());
    assertEquals("<i>x</i>", SafeHtml.unchecked("<i>x</i>").html());
    assertEquals(SafeHtml.unchecked("a"), SafeHtml.unchecked("a"));
    assertEquals(SafeHtml.unchecked("a").hashCode(), SafeHtml.unchecked("a").hashCode());
    assertNotEquals(SafeHtml.unchecked("a"), SafeHtml.unchecked("b"));
    assertThrows(NullPointerException.class, () -> SafeHtml.unchecked(null));
  }

  @Test
  void safeHtmlOfTemplateRendersIt() {
    Template t =
        new Template() {
          @Override
          public String templateName() {
            return "t";
          }

          @Override
          public void render(RenderContext ctx, Output out) {
            out.write("<p>hi</p>");
          }
        };
    assertEquals("<p>hi</p>", SafeHtml.of(t, null).html());
  }

  @Test
  void trustedUrlValueSemantics() {
    assertEquals("/x", TrustedUrl.unchecked("/x").url());
    assertEquals("/x", TrustedUrl.unchecked("/x").toString());
    assertEquals(TrustedUrl.unchecked("/x"), TrustedUrl.unchecked("/x"));
    assertEquals(TrustedUrl.unchecked("/x").hashCode(), TrustedUrl.unchecked("/x").hashCode());
    assertNotEquals(TrustedUrl.unchecked("/x"), TrustedUrl.unchecked("/y"));
    assertThrows(NullPointerException.class, () -> TrustedUrl.unchecked(null));
  }

  @Test
  void escapersWriteThroughWriterOutput() {
    StringWriter w = new StringWriter();
    Escapers.text(new WriterOutput(w), "<a>");
    assertEquals("&lt;a&gt;", w.toString());
  }
}
