package dev.chainguard.thwart.runtime.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chainguard.thwart.runtime.DefaultLinks;
import dev.chainguard.thwart.runtime.Forms;
import dev.chainguard.thwart.runtime.Messages;
import dev.chainguard.thwart.runtime.RenderContext;
import dev.chainguard.thwart.runtime.SafeHtml;
import dev.chainguard.thwart.runtime.SafeInlineHtml;
import dev.chainguard.thwart.runtime.TestSafeHtml;
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
import java.util.Locale;
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

  private static String javaScript(Object v) {
    return run(Escapers::javaScript, v);
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
    assertEquals("<b>", Escapers.toText(TestSafeHtml.of("<b>")));
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

  /**
   * Every HTML sink against {@link #expectedHtml}: each BMP char alone (spaced, so a surrogate must
   * be replaced), between runs copied in bulk and at the ends of the value, and a pair from every
   * plane.
   */
  @Test
  void htmlSinksMatchTheEscapeRuleForEveryChar() {
    StringBuilder input = new StringBuilder();
    StringBuilder expected = new StringBuilder();
    for (int c = 0; c <= 0xFFFF; c++) {
      String s = String.valueOf((char) c);
      String e = expectedHtml(c);
      input.append(s).append(' ');
      expected.append(e).append(' ');
      assertHtmlSinks("ab" + s + "cd" + s, "ab" + e + "cd" + e);
      assertHtmlSinks(s + "x", e + "x");
    }
    assertHtmlSinks(input.toString(), expected.toString());
    for (int plane = 1; plane <= 16; plane++) {
      for (int low : new int[] {0x0000, 0xFFFD, 0xFFFE, 0xFFFF}) {
        int cp = plane << 16 | low;
        assertHtmlSinks("a" + new String(Character.toChars(cp)) + "&", "a" + expectedHtml(cp) + "&amp;");
      }
    }
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
      StringBuilder fallback = new StringBuilder();
      Output legacy = new Output() {
        public void write(String s) {
          fallback.append(s);
        }
        public void write(char c) {
          fallback.append(c);
        }
      };
      // A leading '/' makes the value a scheme-less path, so the URL sinks' scheme check passes it
      // and only their HTML escaping is under test.
      String input = "/" + value;
      String want = "/" + expected;
      escaper.accept(string, input);
      escaper.accept(legacy, input);
      assertEquals(want, string.toString());
      assertEquals(want, fallback.toString());
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

  /**
   * Controls other than ASCII whitespace, noncharacters and lone surrogates, which the parser
   * reports as parse errors, become U+FFFD in text, attribute values and {@link SafeHtml#escape},
   * as in the sanitizer's output (#614).
   */
  @Test
  void htmlEscapersReplaceForbiddenCodePoints() {
    String[] forbidden = {"\u0001", "\u0008", "\u000b", "\u000e", "\u001f", "\u007f", "\u0080", "\u0085", "\u009f",
      "\ufdd0", "\ufdef", "\ufffe", "\uffff", "\ud83f\udffe", "\ud83f\udfff", "\udbff\udfff", "\ud800", "\udc00"};
    for (String f : forbidden) {
      String in = "a" + f + "b";
      assertHtmlSinks(in, "a\uFFFDb");
      assertEquals("a\uFFFDb", Escapers.escapeHtml(in));
    }
    assertHtmlSinks("\u0001<\u007f&\ufffe", "\uFFFD&lt;\uFFFD&amp;\uFFFD");
  }

  /** The positive control for #614: ASCII whitespace and the code points next to the forbidden ranges are kept. */
  @Test
  void htmlEscapersKeepAllowedCodePointsNextToTheForbiddenOnes() {
    String allowed = "\t\n\f\r \u007e\u00a0\u00ff\ufdcf\ufdf0\ufffd\ud83f\udffd\ud840\udc00\udbff\udffd\u2028";
    assertHtmlSinks(allowed, allowed);
    assertEquals(allowed, Escapers.escapeHtml(allowed));
  }

  /** The predicate the escapers and the sanitizer share, against the spec's definitions, over every code point. */
  @Test
  void forbiddenCodePointMatchesTheSpecOverEveryCodePoint() {
    for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
      assertEquals(parseError(cp), Escapers.forbiddenCodePoint(cp), "U+" + Integer.toHexString(cp));
    }
  }

  /**
   * The spec's input stream parse errors, written from its definitions: a control other than
   * ASCII whitespace (C0 except TAB, LF, FF, CR, and U+007F-U+009F), a noncharacter (U+FDD0-U+FDEF
   * and the last two code points of every plane), a surrogate.
   */
  private static boolean parseError(int cp) {
    boolean control = cp <= 0x1F && cp != 0x09 && cp != 0x0A && cp != 0x0C && cp != 0x0D || cp >= 0x7F && cp <= 0x9F;
    boolean noncharacter = cp >= 0xFDD0 && cp <= 0xFDEF || (cp & 0xFFFF) == 0xFFFE || (cp & 0xFFFF) == 0xFFFF;
    return control || noncharacter || cp >= 0xD800 && cp <= 0xDFFF;
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
        assertTrue(Checks.isAllowedUrl(u), u);
      }
      for (int i = 1; i <= scheme.length(); i++) {
        for (char c : new char[] {' ', '\u0000', '\u001f', '\u007f', '\u00a0', 'ſ'}) {
          String u = scheme.substring(0, i) + c + scheme.substring(i) + ":x";
          assertFalse(Checks.isAllowedUrl(u), u);
        }
      }
      assertFalse(Checks.isAllowedUrl(scheme + "x:x"));
      assertFalse(Checks.isAllowedUrl("x" + scheme + ":x"));
    }
  }

  @Test
  void longRelativeNamesAreAllowedButUnknownSchemesAreRejected() {
    String name = "a".repeat(10_000);
    assertTrue(Checks.isAllowedUrl(name));
    assertTrue(Checks.isAllowedUrl(name + "/javascript:x"));
    assertTrue(Checks.isAllowedUrl(name + "?javascript:x"));
    assertTrue(Checks.isAllowedUrl(name + "#javascript:x"));
    assertFalse(Checks.isAllowedUrl(name + ":x"));
  }

  @Test
  void urlDoesNotSplitOnSemicolon() {
    // ';' is legal in a single URL.
    assertEquals("/x;javascript:x", url("/x;javascript:x"));
  }

  @Test
  void sameOriginUrlsAreRelativeWithoutSchemeOrAuthority() {
    for (String u :
        new String[] {
          "", "/x", "x", "?q=1", "#", "./x", "../x", "  /x", "\t/x", "/x;jsessionid=1", "/a:b", "a/b:c", "?a:b",
          "#a:b", "/", "\\x", "/x//y", "\u00a0//evil"
        }) {
      assertTrue(Checks.isSameOriginUrl(u), u);
    }
    assertTrue(Checks.isSameOriginUrl(null));
  }

  /** Negative (#285): a scheme or a protocol-relative start is another origin, however written. */
  @Test
  void crossOriginUrlsAreNotSameOrigin() {
    for (String u :
        new String[] {
          "https://other/", "HTTP://other/", "//other/", "/\\other/", "\\/other/", "\\\\other/", " //other/",
          "\t//evil", "/\t/evil", "/\n\\evil", "\u0000//evil", "\u0001 https://evil/", "mailto:a@b",
          "javascript:x", "java\tscript:x", "a:b", ":x", "about:invalid#thwart",
          // Same-scheme relative in a browser (so not another origin), but scheme-shaped: fails closed.
          "https:other",
          // A browser reads this as a path, but url() writes about:invalid#thwart for it.
          "\\u003c:x"
        }) {
      assertFalse(Checks.isSameOriginUrl(u), u);
    }
  }

  /** #358: a link built for a same-origin slot passes only if it is a path starting with one '/'. */
  @Test
  void sameOriginLinkAcceptsOnlySingleSlashPaths() {
    for (String u : new String[] {"/", "/a", "/app/a", "/%2F/evil", "/%5Cevil", "/app/../a", "/..//evil", "/a?b=//c#//d"}) {
      assertEquals(u, Escapers.sameOriginLink(u), u);
    }
    for (String u :
        new String[] {
          "//a", "/\\a", "\\/a", "\\a", "/\t/a", "/\n\\a", " //a", " /a", "\u0000/a", "https://evil/a", "http:/a",
          "javascript:alert(1)//", "a", "", "?a", "#a", "./a", "../a"
        }) {
      assertThrows(IllegalStateException.class, () -> Escapers.sameOriginLink(u), u);
    }
    assertThrows(IllegalStateException.class, () -> Escapers.sameOriginLink(null));
  }

  /** #584: a neutral path is in the alphabet A-Z a-z 0-9 . _ ~ / - %XX, with no --. */
  @Test
  void neutralPathEncodesIntoTheAlphabet() {
    assertEquals("/items/7", Escapers.neutralPath("/items/7"));
    assertEquals("/a/b-c_d.e~f%2F", Escapers.neutralPath("/a/b-c_d.e~f%2F"));
    assertEquals("/a-%2Db", Escapers.neutralPath("/a--b"));
    assertEquals("/-%2D-%2D-", Escapers.neutralPath("/-----"));
    assertEquals("/a/-%2D-location.port-%2D", Escapers.neutralPath("/a/---location.port--"));
    assertEquals("/a%2Cb%3Fq%3D1%23f%3A%40%2B%27%28%29%3B%26", Escapers.neutralPath("/a,b?q=1#f:@+'();&"));
    assertEquals("/%25zz/%25", Escapers.neutralPath("/%zz/%"));
    assertEquals("/%C3%A9%20x", Escapers.neutralPath("/é x"));
    assertEquals(null, Escapers.neutralPath(null));
    for (String u : new String[] {"//a", "/\\a", "/\t/a", " /a", "a", "", "https://evil/a", "javascript:alert(1)//"}) {
      assertThrows(IllegalStateException.class, () -> Escapers.neutralPath(u), u);
    }
  }

  /** #584: a neutral number is plain decimal, from the JDK's own number classes only. */
  @Test
  void neutralNumberIsPlainDecimal() {
    assertEquals("7", Escapers.neutralNumber(7));
    assertEquals("-7", Escapers.neutralNumber(-7L));
    assertEquals("1", Escapers.neutralNumber((byte) 1));
    assertEquals("1", Escapers.neutralNumber((short) 1));
    assertEquals("123456789012345678901234567890", Escapers.neutralNumber(new BigInteger("123456789012345678901234567890")));
    assertEquals("1000", Escapers.neutralNumber(new BigDecimal("1E+3")));
    assertEquals("0.000012", Escapers.neutralNumber(new BigDecimal("1.2E-5")));
    assertEquals("100000000000000000000", Escapers.neutralNumber(1e20));
    assertEquals("0.5", Escapers.neutralNumber(0.5f));
    assertEquals("0.0", Escapers.neutralNumber(-0.0));
    assertEquals(null, Escapers.neutralNumber(null));
    for (Object bad : new Object[] {Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, "1", 'c', true,
        new java.util.concurrent.atomic.AtomicInteger(1), new BigDecimal("1") { }, new BigInteger("1") { }}) {
      assertThrows(IllegalArgumentException.class, () -> Escapers.neutralNumber(bad), String.valueOf(bad));
    }
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
  void refreshEncodesWhatEndsTheUrl() {
    assertEquals("3;url=/a/b?x=1&amp;y=2", run(Escapers::refresh, "3;url=/a/b?x=1&y=2"));
    assertEquals(
        "0;url=/a%3Burl=//evil%2C%27%22%20%09%0A%5C&lt;",
        run(Escapers::refresh, "0;url=/a;url=//evil,'\" \t\n\\<"));
    // A backslash cannot make //host: it is encoded before the same-origin check.
    assertEquals("0;url=/%5Cevil", run(Escapers::refresh, "0;url=/\\evil"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "", "3", "3;url=", ";url=/x", "x;url=/x", "3 ;url=/x", "-1;url=/x", "3;URL=/x", "3;url=x",
        "3;url=//evil", "3;url=https://evil", "3;url=javascript:alert(1)", "3;url= /x", "3;url=\t//evil"
      })
  void refreshRejectsAnythingButDelayAndSameOriginPath(String value) {
    assertThrows(IllegalArgumentException.class, () -> run(Escapers::refresh, value));
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
    assertEquals(Escapers.INVALID_URL, url(TestSafeHtml.of("javascript:alert(1)")));
  }

  /** Negative (#668): no value skips the scheme check; there is no wrapper type that vouches for a URL. */
  @ParameterizedTest
  @ValueSource(strings = {"javascript:alert(1)", "data:image/png;base64,AAAA", "blob:x", "vbscript:x", "sms:1"})
  void urlReplacesEveryOtherScheme(String value) {
    assertEquals(Escapers.INVALID_URL, url(value));
    assertEquals(Escapers.INVALID_URL, resourceUrl(value));
  }

  // --- resourceUrl -----------------------------------------------------------------------------

  @Test
  void resourceUrlAcceptsLinks() {
    assertEquals("/js/app.js?v=1&amp;x=2", resourceUrl("/js/app.js?v=1&x=2"));
    assertEquals("https://cdn.example.com/a.js", resourceUrl("https://cdn.example.com/a.js"));
  }

  @Test
  void resourceUrlRejectsOtherTypes() {
    assertThrows(IllegalArgumentException.class, () -> resourceUrl(42));
    assertThrows(IllegalArgumentException.class, () -> resourceUrl(TestSafeHtml.of("/a.js")));
    assertThrows(IllegalArgumentException.class, () -> resourceUrl(new StringBuilder("/a.js")));
  }

  // --- html ------------------------------------------------------------------------------------

  @Test
  void htmlWritesSafeHtmlVerbatim() {
    assertEquals("<b>bold</b>", html(TestSafeHtml.of("<b>bold</b>")));
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
  void javaScriptParenthesizesEveryValue() {
    for (Object v : new Object[] {
      -1.5, -0.0, -3, Long.MIN_VALUE, 0, 7, new BigDecimal("-1.10"), new BigInteger("12345678901234567890"),
      Optional.of(-2.0), 1.5, new BigDecimal("1E+3"), Double.NEGATIVE_INFINITY, null, true, "-1", "a<b",
      List.of(-1, 2), new int[] {-1}, Map.of("a", -1), Optional.empty(), Color.GREEN
    }) {
      assertEquals("(" + json(v) + ")", javaScript(v), String.valueOf(v));
    }
    assertEquals("(-1.5)", javaScript(-1.5));
    assertEquals("(7)", javaScript(7));
    assertEquals("([-1,2])", javaScript(List.of(-1, 2)));
    assertEquals("(\"-1\")", javaScript("-1"));
    assertEquals("(null)", javaScript(null));
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
    assertEquals("\"\\u0060\\u0024\\u007bx}\\/\"", json("`${x}/"));
    // '{' as defence in depth, with '`' and '$'
    assertEquals("\"\\u007b\\u007b x }}\"", json("{{ x }}"));
    assertEquals("\"\u00e9\ud83d\ude00\"", json("\u00e9\ud83d\ude00"));
  }

  @Test
  void jsonEscapesUnpairedSurrogates() {
    assertEquals("\"\\ud800\"", json("\ud800"));
    assertEquals("\"a\\udc00b\"", json("a\udc00b"));
    assertEquals("\"\\udc00\\ud800\"", json("\udc00\ud800"));
  }

  /**
   * #619: controls and noncharacters, which the parser reports as errors in a script's text, are
   * written as {@code \\uXXXX} escapes (a supplementary one as its two halves), so the JS value is
   * the same and the page parses without an error.
   */
  @Test
  void jsonEscapesForbiddenCodePoints() {
    assertEquals("\"a\\u0085b\"", json("a\u0085b"));
    assertEquals("\"\\u0080\\u009f\\ufdd0\\ufdef\\ufffe\\uffff\"", json("\u0080\u009f\ufdd0\ufdef\ufffe\uffff"));
    assertEquals("\"a\\ud83f\\udffeb\\udbff\\udfff\"", json("a\ud83f\udffeb\udbff\udfff"));
    assertEquals("(\"\\u0085\\ud83f\\udfff\")", javaScript("\u0085\ud83f\udfff"));
    assertEquals("{\"\\u0085\":[\"\\ufffe\"]}", json(Map.of("\u0085", List.of("\ufffe"))));
    assertEquals("\"\\u0085\"", json('\u0085'));
  }

  /**
   * Over every code point: a forbidden one is escaped half by half (U+0008 keeps JSON's own
   * {@code \b}), and no output has one.
   */
  @Test
  void jsonWritesNoForbiddenCodePointOverEveryCodePoint() {
    for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
      String in = new String(Character.toChars(cp));
      String out = json(in);
      if (parseError(cp) && cp != '\b') {
        StringBuilder expected = new StringBuilder("\"");
        for (char c : in.toCharArray()) {
          expected.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
        }
        assertEquals(expected.append('"').toString(), out, "U+" + Integer.toHexString(cp));
      }
      out.codePoints().forEach(o -> assertFalse(parseError(o), () -> "U+" + Integer.toHexString(o) + " in " + out));
    }
  }

  /** The positive control for #619: code points next to the forbidden ranges are written as is. */
  @Test
  void jsonKeepsAllowedCodePointsNextToTheForbiddenOnes() {
    String allowed = " \u007e\u00a0\u00ff\ufdcf\ufdf0\ufffd\ud83f\udffd\ud840\udc00\udbff\udffd";
    assertEquals("\"" + allowed + "\"", json(allowed));
    assertEquals("(\"" + allowed + "\")", javaScript(allowed));
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
    assertThrows(IllegalArgumentException.class, () -> json(TestSafeHtml.of("<b>")));
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

  /** An application subclass of BigDecimal whose toString() returns its label. */
  static final class Labeled extends BigDecimal {
    private static final long serialVersionUID = 1L;
    private final String label;

    Labeled(String label) {
      super(1);
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  /** An application subclass of BigInteger whose toString() returns its label. */
  static final class LabeledInt extends BigInteger {
    private static final long serialVersionUID = 1L;
    private final String label;

    LabeledInt(String label) {
      super("123456789012345678901234567890");
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  @Test
  void jsonBigNumbersFromTheJdkAreUnchanged() {
    assertEquals("1E+3", json(new BigDecimal("1E+3")));
    assertEquals("0.00", json(new BigDecimal("-0.00")));
    assertEquals("1E-7", json(new BigDecimal("1E-7")));
    assertEquals("1.0E-7", json(BigDecimal.valueOf(1e-7)));
    assertEquals("1E+5", json(new BigDecimal(BigInteger.ONE, -5)));
    assertEquals("0E-10", json(new BigDecimal("0E-10")));
    assertEquals("-1.5E-10", json(new BigDecimal("-1.5E-10")));
    assertEquals("-0.000012345", json(new BigDecimal(BigInteger.valueOf(-12345), 9)));
    assertEquals("1E+2147483647", json(new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE + 1)));
    assertEquals("-123456789012345678901234567890", json(new BigInteger("-123456789012345678901234567890")));
    assertEquals("0", json(BigInteger.ZERO));
    assertEquals("[1E+3,-7]", json(List.of(new BigDecimal("1E+3"), BigInteger.valueOf(-7))));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-0", "7", "-12", "1E+3", "1e+3", "1E-7", "1.0E-7", "0E-10", "0.00", "-1.5E10",
      "123456789012345678901234567890"})
  void jsonBigNumberSubclassIsRejectedEvenWithJsonNumberText(String text) {
    // Only the JDK classes themselves: toString() of a subclass is never called.
    assertThrows(IllegalArgumentException.class, () -> json(new Labeled(text)));
    assertThrows(IllegalArgumentException.class, () -> json(new LabeledInt(text)));
    assertThrows(IllegalArgumentException.class, () -> Escapers.messageArgs(new Object[] {new Labeled(text)}));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0;alert(1)//</script><script>alert(2)</script>", "1-2", "1+2", "1E5E5", "01", "-01",
      "-", "", "1.", ".5", "+1", "1e", "1E+", "--1", "1.5.5", "Infinity", "NaN", "0x10", " 1", "1 ", "1_000",
      "١", "1E+3)", "1/2", "1.e3"})
  void jsonRejectsBigNumberSubclassWhoseTextIsNotAJsonNumber(String text) {
    for (Object hostile : new Object[] {new Labeled(text), new LabeledInt(text)}) {
      StringOutput out = new StringOutput();
      assertThrows(IllegalArgumentException.class, () -> Escapers.json(out, hostile));
      assertThrows(IllegalArgumentException.class, () -> Escapers.json(out, List.of(1, hostile)));
      assertThrows(IllegalArgumentException.class, () -> Escapers.appendJson(new StringBuilder(), hostile, 0));
      assertEquals("", out.toString());
      assertThrows(IllegalArgumentException.class, () -> Escapers.messageArgs(new Object[] {hostile}));
      assertThrows(IllegalArgumentException.class, () -> Escapers.messageTextArgs(new Object[] {hostile}));
    }
  }

  @Test
  void styleNumberChecksBigNumberText() {
    assertEquals("1E+3", Escapers.styleNumber(new BigDecimal("1E+3")));
    assertEquals("-7", Escapers.styleNumber(BigInteger.valueOf(-7)));
    assertThrows(IllegalArgumentException.class, () -> Escapers.styleNumber(new Labeled("2.50")));
    assertEquals(null, Escapers.styleNumber(null));
    assertThrows(IllegalArgumentException.class, () -> Escapers.styleNumber(new Labeled("1;background-image:url(x)")));
    assertThrows(IllegalArgumentException.class, () -> Escapers.styleNumber(new LabeledInt("1-2")));
  }

  @Test
  void messageArgsRejectBigNumberSubclasses() {
    BigDecimal plain = new BigDecimal("1.5");
    BigInteger plainInt = new BigInteger("-123456789012345678901234567890");
    Object[] in = {plain, plainInt, "text", null};
    assertSame(in, Escapers.messageArgs(in));
    Object[] out = Escapers.messageTextArgs(in);
    assertSame(plain, out[0]);
    assertSame(plainInt, out[1]);
    assertThrows(IllegalArgumentException.class, () -> Escapers.messageTextArgs(new Object[] {new Labeled("2.5E+3")}));
    assertThrows(IllegalArgumentException.class, () -> Escapers.messageArgs(new Object[] {new LabeledInt("-7")}));
  }

  @Test
  void messageFormatCopiesTheTextOfABigNumberSubclass() {
    // Why messageArgs rejects subclasses: MessageFormat copies their toString() as digits.
    java.text.MessageFormat f = new java.text.MessageFormat("Now <b>{0}</b>", java.util.Locale.ROOT);
    assertEquals("Now <b><b></b>", f.format(new Object[] {new Labeled("<b>")}));
    assertEquals("Now <b><b></b>", f.format(new Object[] {new LabeledInt("<b>")}));
    assertThrows(IllegalArgumentException.class, () -> f.format(Escapers.messageTextArgs(new Object[] {new Labeled("<b>")})));
    // A JSON number that is not an integer is not a BigInteger.
    assertThrows(IllegalArgumentException.class, () -> Escapers.messageTextArgs(new Object[] {new LabeledInt("1.5")}));
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
  void messageArgsKeepNumbersThatFormatToABoundedLength() {
    Object[] args = {
      new BigDecimal("1234.5"), new BigDecimal("1E+3"), new BigDecimal("-0.000012345"), new BigDecimal("1E+9999"),
      new BigDecimal("1E-10000"), new BigInteger("123456789012345678901234567890"), Double.MAX_VALUE, "text", null
    };
    assertSame(args, Escapers.messageArgs(args));
    assertEquals(
        "1,234.5 1,000 -0 0",
        new java.text.MessageFormat("{0} {1} {2} {4}", java.util.Locale.ROOT).format(Escapers.messageArgs(args)));
    assertEquals(
        13333, new java.text.MessageFormat("{3}", java.util.Locale.ROOT).format(Escapers.messageArgs(args)).length());
  }

  @Test
  void messageArgsKeepZeroWhateverItsScale() {
    Object[] zeros = {new BigDecimal("0E+10001"), new BigDecimal(BigInteger.ZERO, 10002)};
    assertSame(zeros, Escapers.messageArgs(zeros));
    assertEquals("0 0", new java.text.MessageFormat("{0} {1}", java.util.Locale.ROOT).format(zeros));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1E+10000", "1E+999999999", "-1E+999999999", "12345678901234567890E+9990", "1E-10001",
      "1E-10002", "1E-999999999"})
  void messageArgsRejectBigDecimalsMoreThanTenThousandPlacesFromThePoint(String value) {
    Object[] args = {"a", new BigDecimal(value)};
    assertThrows(IllegalArgumentException.class, () -> Escapers.messageArgs(args));
    assertThrows(IllegalArgumentException.class, () -> Escapers.messageTextArgs(args));
    // A subclass whose own precision and scale say 1 is checked as the JDK value of its toString().
    assertThrows(IllegalArgumentException.class, () -> Escapers.messageArgs(new Object[] {new Labeled(value)}));
    assertThrows(IllegalArgumentException.class, () -> Escapers.messageTextArgs(new Object[] {new Labeled(value)}));
  }

  // HTML messages (#677): sanitized by the fixed message policy

  private static String htmlMessage(Messages m, Object[] args, boolean links) {
    RenderContext ctx = new RenderContext(Locale.US, m, new DefaultLinks(""), Forms.NONE);
    return links ? Escapers.linkedHtmlMessage(ctx, "k", args).html() : Escapers.htmlMessage(ctx, "k", args).html();
  }

  private static Messages format(String pattern) {
    return (k, a) -> a.length == 0 ? pattern : new java.text.MessageFormat(pattern, Locale.US).format(a);
  }

  @Test
  void htmlMessageKeepsThePolicysPhrasingMarkup() {
    assertEquals(
        "Hi <b>Ann</b>, 1,234 <i>items</i><br><code>x</code>",
        htmlMessage(format("Hi <b>{0}</b>, {1,number} <i>items</i><br><code>x</code>"), new Object[] {"Ann", 1234}, false));
    assertEquals("Don&#39;t <em>panic</em>", htmlMessage(format("Don''t <em>panic</em>"), new Object[] {"x"}, false));
    assertEquals("It&#39;&#39;s <b>as written</b>", htmlMessage(format("It''s <b>as written</b>"), new Object[0], false));
    assertEquals("Tom &amp; Jerry", htmlMessage(format("Tom &amp; Jerry"), new Object[0], false));
    assertEquals("", htmlMessage((k, a) -> null, new Object[0], true));
    assertEquals("", htmlMessage((k, a) -> null, new Object[] {"x"}, true));
    assertEquals("", htmlMessage((k, a) -> null, new Object[0], false));
    assertEquals("", htmlMessage((k, a) -> null, new Object[] {"x"}, false));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "<script>alert(1)</script>", "<img src=x onerror=alert(1)>", "</b><a href=\"javascript:alert(1)\">x</a>",
      "<!--", "<b onclick=alert(1)>", "&lt;script&gt;", "\"'&<>", "{{constructor.constructor('alert(1)')()}}",
      "<p>", "</span></p></div>"})
  void htmlMessageArgumentsAreOnlyText(String hostile) {
    String text = Escapers.escapeHtml(hostile);
    for (boolean links : new boolean[] {false, true}) {
      String html = htmlMessage(format("Hi <b>{0}</b>"), new Object[] {hostile}, links);
      assertEquals("Hi <b>" + text + "</b>", html, hostile);
      html = htmlMessage(format("Hi {0}"), new Object[] {TestSafeHtml.of(hostile)}, links);
      assertEquals("Hi " + text, html, hostile);
    }
  }

  @Test
  void htmlMessageDropsMarkupOutsideThePolicy() {
    // Whatever the bundle or message source holds: a file changed after the build, a database.
    String[][] cases = {
      {"Hi<script>alert(1)</script>!", "Hi!"},
      {"<b onclick=\"alert(1)\" style=\"x\" class=\"c\">x</b>", "<b>x</b>"},
      {"<img src=x onerror=alert(1)>x", "x"},
      {"<p>First.</p><div>Second.</div><ul><li>Third</li></ul>", "First.Second.Third"},
      {"<h1>Title</h1><table><tr><td>cell</td></tr></table>", "Titlecell"},
      {"<b>open <i>misnested</b> tail", "<b>open <i>misnested</i></b> tail"},
      {"<b><b>nested</b></b>", "<b>nested</b>"},
      {"<svg><script>alert(1)</script></svg>x<style>p{}</style>", "x"},
      {"<!-- c -->x<![CDATA[y]]>", "x"},
      {"<i>{{x}}</i>", "<i>{{x}}</i>"},
      {"<form action=/x><input name=a></form>x", "x"},
    };
    for (String[] c : cases) {
      assertEquals(c[1], htmlMessage(format(c[0]), new Object[0], true), c[0]);
      assertEquals(c[1], htmlMessage((k, a) -> c[0], new Object[] {"arg"}, false), c[0]);
    }
  }

  @Test
  void htmlMessageLinksAreCheckedAndOnlyWithLinks() {
    String link = "See <a href=\"/docs\" target=\"_blank\" class=\"alert-link\" title=\"Docs\">the docs</a>.";
    assertEquals(
        "See <a href=\"/docs\" title=\"Docs\" rel=\"noopener noreferrer\" target=\"_blank\">the docs</a>.",
        htmlMessage(format(link), new Object[0], true));
    assertEquals("See the docs.", htmlMessage(format(link), new Object[0], false));
    assertEquals(
        "<a href=\"https://example.com/x\" rel=\"noopener noreferrer\">x</a>",
        htmlMessage(format("<a href=\"https://example.com/x\" target=\"_top\">x</a>"), new Object[0], true));
    for (String bad : new String[] {"javascript:alert(1)", "java&#115;cript:alert(1)", " javascript:x", "data:text/html,x",
        "vbscript:x"}) {
      String html = htmlMessage(format("<a href=\"" + bad + "\">x</a>"), new Object[0], true);
      assertFalse(html.contains("href"), html);
    }
    // A link in a link is unwrapped: an <a> start tag never meets an open one in the message.
    assertEquals(
        "<a href=\"/a\" rel=\"noopener noreferrer\">a b</a>",
        htmlMessage(format("<a href=\"/a\">a <a href=\"/b\">b</a></a>"), new Object[0], true));
  }

  @Test
  void htmlMessageWithoutArgumentsIsSanitizedOncePerText() {
    AtomicInteger calls = new AtomicInteger();
    Messages m = (k, a) -> {
      calls.incrementAndGet();
      return "<b>cached</b>" + k;
    };
    RenderContext ctx = new RenderContext(Locale.US, m, new DefaultLinks(""), Forms.NONE);
    SafeInlineHtml first = Escapers.htmlMessage(ctx, "k", new Object[0]);
    assertSame(first, Escapers.htmlMessage(ctx, "k", new Object[0]));
    assertEquals(2, calls.get(), "the source is asked every time; only the sanitizing is cached");
    SafeHtml linked = Escapers.linkedHtmlMessage(ctx, "k", new Object[0]);
    assertSame(linked, Escapers.linkedHtmlMessage(ctx, "k", new Object[0]));
    assertEquals("<b>cached</b>k", linked.html());
  }

  @Test
  void htmlMessageArgumentsAreCheckedBeforeFormatting() {
    assertThrows(
        IllegalArgumentException.class,
        () -> htmlMessage(format("{0}"), new Object[] {new BigDecimal("1E+999999999")}, true));
    assertThrows(IllegalArgumentException.class, () -> htmlMessage(format("{0}"), new Object[] {new Labeled("2.5")}, true));
    java.util.Date date = new java.util.Date(86400000L * 365);
    String day = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, Locale.US).format(date);
    assertEquals(
        "1,234 on " + day,
        htmlMessage(format("{0,number} on {1,date,short}"), new Object[] {1234, date}, false));
  }

  // One text escaper: element text, RCDATA and attribute values are escaped alike (#679).

  @Test
  void textIsTheOneHtmlEscaper() {
    // Braces are plain text: client frameworks that compile server DOM are outside the guarantee.
    assertEquals("{{x}} &amp; &lt;", text("{{x}} & <"));
    for (String s : new String[] {"{", "{{", "a{{b}}", "}}{{x}}", "<{{&}}>", "\u0000{\ud800{", "\"'"}) {
      assertEquals(text(s), attr(s), s);
      assertEquals(text(s), SafeHtml.escape(s).html(), s);
    }
    assertEquals("", text(null));
  }

  /** The one escape rule of every HTML sink (#679, #685), written out. */
  private static String expectedHtml(int cp) {
    switch (cp) {
      case '&':
        return "&amp;";
      case '<':
        return "&lt;";
      case '>':
        return "&gt;";
      case '"':
        return "&quot;";
      case '\'':
        return "&#39;";
      default:
        return parseError(cp) ? String.valueOf((char) 0xFFFD) : new String(Character.toChars(cp));
    }
  }

  private enum Shouting {
    LOUD {
      @Override
      public String toString() {
        return "<b>";
      }
    }
  }

  /**
   * The typed overloads (#685): an int, long or boolean is written as its text; a char is escaped
   * rather than widened to the int overload; a boxed value and an enum, whose toString() can be
   * overridden, take the Object overload and are escaped.
   */
  @Test
  void typedTextOverloads() {
    StringOutput out = new StringOutput();
    Escapers.text(out, -42);
    Escapers.text(out, Long.MIN_VALUE);
    Escapers.text(out, true);
    Escapers.text(out, '<');
    Escapers.text(out, (short) 7);
    Escapers.text(out, (Object) Integer.valueOf(1));
    Escapers.text(out, Shouting.LOUD);
    assertEquals("-42" + Long.MIN_VALUE + "true&lt;71&lt;b&gt;", out.toString());
  }
}
