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
  void htmlSinksPreserveEveryBmpCharacterExceptRequiredEscapes() {
    StringBuilder input = new StringBuilder();
    StringBuilder expected = new StringBuilder();
    for (int i = 0; i <= Character.MAX_VALUE; i++) {
      char c = (char) i;
      input.append(c).append(' '); // Isolate surrogates so each must be replaced.
      switch (c) {
        case '&':
          expected.append("&amp;");
          break;
        case '<':
          expected.append("&lt;");
          break;
        case '>':
          expected.append("&gt;");
          break;
        case '"':
          expected.append("&quot;");
          break;
        case '\'':
          expected.append("&#39;");
          break;
        default:
          expected.append(c == 0 || Character.isSurrogate(c) ? '\uFFFD' : c);
      }
      expected.append(' ');
    }
    assertHtmlSinks(input.toString(), expected.toString());
  }

  @Test
  void htmlSinksHandleAdjacentEscapesAndSurrogateBoundaries() {
    assertHtmlSinks("😀", "😀");
    assertHtmlSinks("😀&😀<😀", "😀&amp;😀&lt;😀");
    assertHtmlSinks("\ud800😀\udc00\0\ud800", "�😀���");
    assertHtmlSinks("<>&\"'", "&lt;&gt;&amp;&quot;&#39;");
    assertHtmlSinks("\"><script>alert('x')</script>",
        "&quot;&gt;&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");
  }

  @Test
  void directHtmlEscapingAppendsToExistingStringOutput() {
    StringOutput out = new StringOutput(1);
    out.write("<p>");
    Escapers.text(out, "before<&😀after");
    Escapers.attr(out, "'\0\ud800");
    Escapers.text(out, null);
    out.write("</p>");
    assertEquals("<p>before&lt;&amp;😀after&#39;��</p>", out.toString());
  }

  @Test
  void otherOutputImplementationsStillReceiveOneWritePerValue() {
    StringBuilder text = new StringBuilder();
    AtomicInteger writes = new AtomicInteger();
    Output out = new Output() {
      public void write(String value) {
        writes.incrementAndGet();
        text.append(value);
      }

      public void write(char value) {
        throw new AssertionError("HTML values must remain buffered for other sinks");
      }
    };
    Escapers.text(out, "<>&\"'😀");
    assertEquals("&lt;&gt;&amp;&quot;&#39;😀", text.toString());
    assertEquals(1, writes.get());
  }

  private static void assertHtmlSinks(String value, String expected) {
    for (BiConsumer<Output, Object> escaper : List.<BiConsumer<Output, Object>>of(
        Escapers::text, Escapers::attr, Escapers::url, Escapers::resourceUrl)) {
      StringOutput string = new StringOutput();
      StringWriter writer = new StringWriter();
      StringBuilder fallback = new StringBuilder();
      Output legacy = new Output() {
        public void write(String s) {
          fallback.append(s);
        }
        public void write(char c) {
          fallback.append(c);
        }
      };
      // TrustedUrl bypasses the scheme policy while retaining HTML escaping for URL sinks.
      Object input = TrustedUrl.unchecked(value);
      escaper.accept(string, input);
      escaper.accept(new WriterOutput(writer), input);
      escaper.accept(legacy, input);
      assertEquals(expected, string.toString());
      assertEquals(expected, writer.toString());
      assertEquals(expected, fallback.toString());
    }
    assertEquals(expected, SafeHtml.escape(value).html());
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
  void schemeComparisonIgnoresOnlyBrowserIgnoredControlsAndAsciiCase() {
    for (String scheme : new String[] {"HTTP", "HTTPS", "MAILTO", "TEL"}) {
      for (int i = 0; i <= scheme.length(); i++) {
        String u = "\u0000 " + scheme.substring(0, i) + "\t\r\n" + scheme.substring(i) + ":x";
        assertTrue(Escapers.isAllowedUrl(u), u);
      }
      for (int i = 1; i <= scheme.length(); i++) {
        for (char c : new char[] {' ', '\u0000', '\u001f', '\u007f', '\u00a0', 'ſ'}) {
          String u = scheme.substring(0, i) + c + scheme.substring(i) + ":x";
          assertFalse(Escapers.isAllowedUrl(u), u);
        }
      }
      assertFalse(Escapers.isAllowedUrl(scheme + "x:x"));
      assertFalse(Escapers.isAllowedUrl("x" + scheme + ":x"));
    }
  }

  @Test
  void longRelativeNamesAreAllowedButUnknownSchemesAreRejected() {
    String name = "a".repeat(10_000);
    assertTrue(Escapers.isAllowedUrl(name));
    assertTrue(Escapers.isAllowedUrl(name + "/javascript:x"));
    assertTrue(Escapers.isAllowedUrl(name + "?javascript:x"));
    assertTrue(Escapers.isAllowedUrl(name + "#javascript:x"));
    assertFalse(Escapers.isAllowedUrl(name + ":x"));
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
    assertEquals("\"\\u0060\\u0024{x}\\/\"", json("`${x}/"));
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
        "*/alert(1)/*",
        "/;alert(1);//",
        "`+alert(1)+`",
        "${alert(1)}",
      })
  void jsonCannotCloseScriptOrString(String payload) {
    String out = json(payload);
    assertFalse(out.replace("\\/", "").contains("/"), () -> "raw / in " + out);
    for (char c : new char[] {'<', '>', '&', '\'', '`', '$', '\u2028', '\u2029'}) {
      assertEquals(-1, out.indexOf(c), () -> "raw " + c + " in " + out);
    }
    // Exactly the two delimiting quotes are unescaped.
    String body = out.substring(1, out.length() - 1);
    assertTrue(out.startsWith("\"") && out.endsWith("\""));
    assertFalse(body.replace("\\\\", "").replace("\\\"", "").contains("\""), out);
  }

  @Test
  void jsonMapKeysAreEscapedToo() {
    assertEquals("{\"\\u003c\\/script\\u003e\":1}", json(Map.of("</script>", 1)));
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
    assertEquals("\"Europe\\/Paris\"", json(ZoneId.of("Europe/Paris")));
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
  @Test
  void generatedJsonBufferUsesTheSameEscapingAndDepthLimit() {
    Object value = Map.of("${key}", List.of("</script>*/`", Double.NaN, Optional.of("value")));
    StringBuilder buffer = new StringBuilder("prefix");
    Escapers.appendJson(buffer, value, 0);
    assertEquals("prefix" + json(value), buffer.toString());
    for (int depth : new int[] {-1, Integer.MIN_VALUE, 65, Integer.MAX_VALUE}) {
      StringBuilder rejected = new StringBuilder("prefix");
      assertThrows(IllegalArgumentException.class, () -> Escapers.appendJson(rejected, "value", depth));
      assertEquals("prefix", rejected.toString());
    }
    StringBuilder last = new StringBuilder();
    Escapers.appendJson(last, "value", 64);
    assertEquals("\"value\"", last.toString());
    assertThrows(IllegalArgumentException.class, () -> Escapers.appendJson(new StringBuilder(), List.of("value"), 64));
  }


  @Test
  void htmlMessageArgsEscapeTextButKeepNumbersAndDates() {
    java.util.Date date = new java.util.Date(0);
    Object[] in = {"<script>&\"'", 42, new BigDecimal("1.5"), date, null, SafeHtml.unchecked("<b>"), 'c'};
    Object[] out = Escapers.htmlMessageArgs(in);
    assertEquals("&lt;script&gt;&amp;&quot;&#39;", out[0]);
    assertEquals(42, out[1]);
    assertEquals(new BigDecimal("1.5"), out[2]);
    assertTrue(out[3] == date);
    assertEquals(null, out[4]);
    assertEquals("&lt;b&gt;", out[5]);
    assertEquals("c", out[6]);
    assertEquals("<script>&\"'", in[0], "the input array is not changed");
    assertEquals(
        "Hi &lt;i&gt;x&lt;/i&gt;, 1,234",
        new java.text.MessageFormat("Hi {0}, {1,number}", java.util.Locale.US)
            .format(Escapers.htmlMessageArgs(new Object[] {"<i>x</i>", 1234})));
  }

  private static RenderContext messages(Messages m) {
    return new RenderContext(java.util.Locale.US, m, new DefaultLinks(""), Forms.NONE);
  }

  @Test
  void htmlMessagePrintsOnlyWhatACheckedPatternProduces() {
    String[] checked = {"Hi <b>{0}</b>, {1,number}", "Salut <b>{0}</b>"};
    Messages formatting = (k, a) -> new java.text.MessageFormat("Hi <b>{0}</b>, {1,number}", java.util.Locale.US).format(a);
    assertEquals(
        "Hi <b>&lt;i&gt;</b>, 1,234",
        Escapers.htmlMessage(messages(formatting), "k", new Object[] {"<i>", 1234}, checked));
    // Unformatted (no arguments, as Spring returns it) and a second locale's pattern.
    assertEquals(
        "Salut <b>{0}</b>", Escapers.htmlMessage(messages((k, a) -> "Salut <b>{0}</b>"), "k", new Object[0], checked));
    // Text without '<' cannot hold markup.
    assertEquals("plain &amp; simple", Escapers.htmlMessage(messages((k, a) -> "plain &amp; simple"), "k", new Object[0], checked));
    assertEquals("", Escapers.htmlMessage(messages((k, a) -> null), "k", new Object[0], checked));
  }

  @Test
  void htmlMessageEscapesAMessageNoCheckedPatternProduces() {
    String[] checked = {"Hi <b>{0}</b>"};
    // A message source the build never saw (another bundle, a database, a changed file).
    assertEquals(
        "&lt;script&gt;alert(1)&lt;/script&gt;",
        Escapers.htmlMessage(messages((k, a) -> "<script>alert(1)</script>"), "k", new Object[] {"x"}, checked));
    // A checked pattern with the raw (unescaped) argument is not what the pattern produces.
    assertEquals(
        "Hi &lt;b&gt;&lt;img src=x onerror=alert(1)&gt;&lt;/b&gt;",
        Escapers.htmlMessage(
            messages((k, a) -> "Hi <b><img src=x onerror=alert(1)></b>"), "k", new Object[] {"<img src=x onerror=alert(1)>"}, checked));
    // No checked patterns at all.
    assertEquals("&lt;b&gt;x&lt;/b&gt;", Escapers.htmlMessage(messages((k, a) -> "<b>x</b>"), "k", new Object[0], new String[0]));
    // An argument that does not fit a checked pattern does not throw.
    assertEquals(
        "&lt;b&gt;x&lt;/b&gt;",
        Escapers.htmlMessage(messages((k, a) -> "<b>x</b>"), "k", new Object[] {"text"}, new String[] {"<b>{0,number}</b>"}));
  }
}
