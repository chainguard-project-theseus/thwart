package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
