package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.runtime.fn.Css;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
        "red/**/", "/*x*/red", "\\72 ed", "\\0065xpression(1)", "'red'", "\"red\"", "rgb(0,0,0)",
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
}
