package dev.chainguard.thwart.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chainguard.thwart.runtime.internal.fn.Css;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CssValueTest {

  @ParameterizedTest
  @ValueSource(strings = {"#abc", "#ABCD", "#a1b2c3", "#a1b2c3d4", "red", "transparent", "currentcolor"})
  void validColors(String s) {
    assertEquals(s, CssValue.color(s).value());
  }

  @Test
  void colorsAreTrimmedAndNamesLowerCased() {
    assertEquals("cornflowerblue", CssValue.color(" CornflowerBlue\n").value());
    assertEquals("#fff", CssValue.color("\t#fff ").toString());
    assertEquals("#fff", Css.color("#fff").value());
  }

  @Test
  void colorNamesFoldOnlyAscii() {
    // toLowerCase mapped U+212A (Kelvin sign) to k; a browser does not read it as khaki.
    assertNull(CssValue.color("\u212Ahaki"));
    assertEquals("khaki", CssValue.color("KHAKI").value());
  }

  @Test
  void onlyHtmlWhitespaceIsIgnored() {
    // trim() also dropped U+000B and other C0 controls, which CSS does not treat as whitespace.
    assertNull(CssValue.color("\u000bred"));
    assertNull(CssValue.url("\u000b/a.png"));
    assertNull(CssValue.url("/a.png\u0001"));
    assertEquals("red", CssValue.color("\t\n\f\r red ").value());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "12px", "-1.5em", ".5", "50%", "2rem", "100vh", "33.3vw", "1.25"})
  void validLengths(String s) {
    assertEquals(s, CssValue.length(s).value());
    assertEquals(s, Css.length(s).value());
  }

  /** Nothing that could end a declaration, call a function, open a string or comment, or escape. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "", " ", "expression(alert(1))", "url(javascript:alert(1))", "red;", "red; color: blue", "red}",
        "red/**/", "/*x*/red", "\\72 ed", "\\0065xpression(1)", "'red'", "\"red\"", "rgb(0,0)",
        "#ggg", "#12", "#12345", "#1234567", "#123456789", "re d", "red;", "ｒed", "#０００",
        "١٢px", "12 px", "12pt", "1e3px", "--1px", "1.px", "12px;", "12px)", "calc(1px)",
        "12345678901px", "inherit", "javascript"
      })
  void invalidValuesAreNull(String s) {
    assertNull(CssValue.color(s), s);
    assertNull(CssValue.length(s), s);
  }

  @Test
  void nullIsNull() {
    assertNull(CssValue.color(null));
    assertNull(CssValue.length(null));
    assertNull(Css.color(null));
    assertNull(Css.length(null));
  }

  @Test
  void valueSemantics() {
    assertEquals(CssValue.color("red"), CssValue.color("RED"));
    assertEquals(CssValue.color("red").hashCode(), CssValue.color("RED").hashCode());
    assertNotEquals(CssValue.color("red"), CssValue.color("blue"));
    assertNotEquals(CssValue.length("1px"), CssValue.color("red"));
  }

  // css.url (#238)

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/img/avatar.jpg", "img/a.png", "../a.png", "?v=1", "#frag", "a.png?w=1&h=2", "/a%20b.png",
        "https://cdn.example.com/u/1/avatar.jpg", "http://example.com/a.png", "HTTPS://example.com/a",
        "//cdn.example.com/a.png", "/a;b=c,d+e@f!$~_.png", "/[x]:y"
      })
  void validUrlsAreQuotedAsIs(String s) {
    assertEquals("url(\"" + s + "\")", CssValue.url(s).value());
    assertEquals("url(\"" + s + "\")", Css.url(s).value());
  }

  @Test
  void urlsAreTrimmedAndEncoded() {
    assertEquals("url(\"/a.png\")", CssValue.url("  /a.png \t").value());
    assertEquals("url(\"/a%20b%22c%27d%5Ce%28f%29g%2Ah.png\")", CssValue.url("/a b\"c'd\\e(f)g*h.png").value());
    assertEquals("url(\"/%3C%3E%60%7B%7D%7C%5E.png\")", CssValue.url("/<>`{}|^.png").value());
    // A stray % is encoded, a %XX escape is kept.
    assertEquals("url(\"/100%25/a%2F%25zz%25\")", CssValue.url("/100%/a%2F%zz%").value());
    // Non-ASCII is percent-encoded as UTF-8, including supplementary characters.
    assertEquals("url(\"/caf%C3%A9/%F0%9F%98%80.png\")", CssValue.url("/café/😀.png").value());
  }

  /** The quoted part never has anything that ends the string or url(), escapes, or comments. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/a\") ; background:url(evil", "/a') ; background:url(evil", "/a); background:url(//evil",
        "/a\\\"); x:y", "/a\\22 ); x:y", "/a\\29 ; x:y", "expression(alert(1))", "/*x*/a.png",
        "/a.png*/x", "/a.png}x{", "/a b", "/a b", "/a）b", "/a\"\"b", "/a\\\\b",
        "/a.png</style><script>"
      })
  void hostileUrlsCannotBreakOut(String s) {
    String v = CssValue.url(s).value();
    assertTrue(v.startsWith("url(\"") && v.endsWith("\")"), v);
    String inner = v.substring(5, v.length() - 2);
    assertTrue(inner.matches("[A-Za-z0-9\\-._~:/?#\\[\\]@!$&+,;=%]*"), inner);
    assertFalse(inner.contains("/*") || inner.contains("*/"), inner);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "javascript:alert(1)", "JaVaScRiPt:alert(1)", " javascript:alert(1)", "java\tscript:alert(1)",
        "java\nscript:alert(1)", "data:image/png;base64,AAAA", "data:image/svg+xml,<svg onload=x>",
        "vbscript:x", "file:///etc/passwd", "mailto:a@b.c", "TEL:123", "ftp://x/a.png",
        "javаscript:x", "javascript :x", "", "   ", "/a\nb", "/a\rb", "/a\tb", "/a\u0000b",
        "/a\u007fb", "/a\u001bb", "/a\fb", "/a\uD800b", "/a\uDC00", "/\uDC00\uD800"
      })
  void invalidUrlsAreNull(String s) {
    assertNull(CssValue.url(s), s);
    assertNull(Css.url(s), s);
  }

  @Test
  void urlNullAndLength() {
    assertNull(CssValue.url(null));
    assertNull(Css.url(null));
    String max = "/" + "a".repeat(CssValue.MAX_URL_LENGTH - 1);
    assertEquals("url(\"" + max + "\")", CssValue.url(max).value());
    assertNull(CssValue.url(max + "a"));
    assertNull(CssValue.url("/" + "\"".repeat(1_000_000)));
  }
  // Colour functions and custom properties (#429)

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "rgb(0, 128, 255)|rgb(0, 128, 255)",
        "rgb(0,128,255)|rgb(0, 128, 255)",
        " RGB( 0 , 128 , 255 ) |rgb(0, 128, 255)",
        "rgba(0, 128, 255, .5)|rgba(0, 128, 255, .5)",
        "rgb(10%, 20.5%, 100%)|rgb(10%, 20.5%, 100%)",
        "hsl(120, 50%, 50%)|hsl(120, 50%, 50%)",
        "hsla(120deg, 50%, 50%, 0.25)|hsla(120deg, 50%, 50%, 0.25)",
        "HSL(-1.5TURN, 0%, 0%)|hsl(-1.5turn, 0%, 0%)",
        "hsl(3.14rad 10% 10%)|hsl(3.14rad 10% 10%)",
        "hsl(100grad  10%   10%)|hsl(100grad 10% 10%)",
        "rgb(0 128 255)|rgb(0 128 255)",
        "rgb(0 128 255 / 50%)|rgb(0 128 255 / 50%)",
        "rgb(0 128 255/.5)|rgb(0 128 255 / .5)",
      })
  void colorFunctionsAreRebuilt(String in, String out) {
    assertEquals(out, CssValue.color(in).value());
    assertEquals(out, Css.color(in).value());
    assertSafeCss(out);
  }

  /**
   * Only numbers with units inside rgb/rgba/hsl/hsla: every injection class, nested functions,
   * keywords, wrong arity, mixed separators and non-ASCII are null.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        // url( and expression(
        "rgb(url(x), 0, 0)", "url(rgb(0,0,0))", "rgb(0, 0, expression(alert(1)))", "expression(rgb(0,0,0))",
        // backslash escapes
        "rgb(\\31 , 0, 0)", "\\72 gb(0, 0, 0)", "rgb(0, 0, 0)\\", "rgb\\(0, 0, 0)",
        // quotes
        "rgb('1', 0, 0)", "rgb(\"1\", 0, 0)", "rgb(0, 0, 0)'", "rgb(0, 0, 0)\"",
        // ; { } and comments
        "rgb(0, 0, 0);x:y", "rgb(0, 0, 0; 0)", "rgb(0, 0, 0)}", "rgb(0, 0, 0){", "rgb({0}, 0, 0)",
        "rgb(0/**/, 0, 0)", "rgb(0 0 0 /*x*/)", "rgb(0 0 0 /**/ 1)", "/**/rgb(0, 0, 0)",
        // < > @
        "rgb(0, 0, 0)</style>", "rgb(<0>, 0, 0)", "rgb(0, 0, 0)>", "@import rgb(0, 0, 0)", "rgb(0@, 0, 0)",
        // nested functions and keywords
        "rgb(var(--x), 0, 0)", "rgb(calc(1), 0, 0)", "rgb(none 0 0)", "rgb(from red r g b)",
        "rgb(attr(x), 0, 0)", "hsl(env(x) 0% 0%)",
        // shape
        "rgb(0, 0)", "rgb(0, 0, 0, 0, 0)", "rgb(0 0 0 0)", "rgb(0, 0 0)", "rgb(0 0 0, 0)", "rgb(0, 0, 0 / 1)",
        "rgb (0, 0, 0)", "rgb(0, 0, 0", "rgb(0, 0, 0))", "rgb((0), 0, 0)", "rgbx(0, 0, 0)", "lab(0 0 0)",
        "color(srgb 0 0 0)", "rgb()", "rgb(,0,0,0)", "rgb(0,,0,0)", "rgb(0 0 0 /)",
        // numbers
        "rgb(1e3, 0, 0)", "rgb(1px, 0, 0)", "rgb(1., 0, 0)", "rgb(+1, 0, 0)", "rgb(12345678901, 0, 0)",
        "rgb(--1, 0, 0)", "rgb(０, 0, 0)", "rgb(٢, 0, 0)",
        // whitespace other than spaces inside
        "rgb(0,\n0, 0)", "rgb(0\t0 0)"
      })
  void hostileColorFunctionsAreNull(String s) {
    assertNull(CssValue.color(s), s);
    assertNull(CssValue.length(s), s);
    assertNull(CssValue.var(s), s);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {"--brand|var(--brand)", " --Brand-2 |var(--Brand-2)", "--a|var(--a)", "---x|var(---x)", "--x-1|var(--x-1)"})
  void customProperties(String in, String out) {
    assertEquals(out, CssValue.var(in).value());
    assertEquals(out, Css.var(in).value());
    assertSafeCss(out);
  }

  /** A custom property name is {@code --} and an identifier, nothing else: every injection class is null. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "", "--", "brand", "-brand", "--1x", "--a b", "--a, red", "--a)", "--a)x", "var(--a)", "--url(x)",
        "--a;x:y", "--a}", "--a{", "--a/**/", "--a/", "--a\\31", "--\\61", "--a'", "--a\"", "--a<", "--a>",
        "--a@", "--a.b", "--a%", "--a_b", "--ａ", "--é", "expression(1)"
      })
  void hostileCustomPropertiesAreNull(String s) {
    assertNull(CssValue.var(s), s);
    assertNull(Css.var(s), s);
  }

  @Test
  void customPropertyNullAndLength() {
    assertNull(CssValue.var(null));
    assertNull(Css.var(null));
    String max = "--" + "a".repeat(256);
    assertEquals("var(" + max + ")", CssValue.var(max).value());
    assertNull(CssValue.var(max + "a"));
  }

  /** What every colour, length and custom property value is made of (the class's invariant). */
  private static void assertSafeCss(String v) {
    assertTrue(v.matches("[A-Za-z0-9#.%(), /-]*"), v);
    for (String bad : new String[] {"/*", "*/", "url(", "expression(", "\\", "'", "\"", ";", "{", "}", "<", ">", "@"}) {
      assertFalse(v.contains(bad), v);
    }
  }
}
