package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.CssValue;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** t:style takes only literals, numbers and CssValues (#182). */
class StyleAttributeTest {

  private static final String HEADER =
      "<div xmlns:t=\"https://tessera.dev\""
          + " t:params=\"String s, boolean b, Integer n, double d, BigDecimal big, CssValue v\">";

  private static Compilation compile(String body) {
    return Compilation.builder().template("t.html", HEADER + body + "</div>").compile();
  }

  private static String render(String body, Object... kv) {
    Map<String, Object> m = new HashMap<>(Map.of("b", false, "d", 0.0));
    m.putAll(Compilation.model(kv));
    String out = compile(body).assertSuccess().render("t", m);
    return out.substring("<div>".length(), out.length() - "</div>".length());
  }

  @Test
  void colorFromTheCssNamespace() {
    String body = "<p style=\"color: red\" t:style=\"|background-color: ${css.color(s)}|\">x</p>";
    assertEquals("<p style=\"background-color: #ff8800\">x</p>", render(body, "s", "#ff8800"));
    assertEquals("<p style=\"background-color: rebeccapurple\">x</p>", render(body, "s", "RebeccaPurple"));
    assertEquals(
        "<p style=\"background-color: #fff\">x</p>",
        render("<p t:style=\"'background-color: ' + #css.color(s)\">x</p>", "s", "#fff"));
  }

  @Test
  void invalidInputPrintsNullOrOmitsTheAttribute() {
    // The browser drops "color: null" as an invalid declaration.
    assertEquals(
        "<p style=\"color: null\">x</p>", render("<p t:style=\"|color: ${css.color(s)}|\">x</p>", "s", "red;x:y"));
    assertEquals("<p>x</p>", render("<p t:style=\"css.color(s)\">x</p>", "s", "expression(alert(1))"));
  }

  @Test
  void lengthsAndNumbers() {
    assertEquals(
        "<p style=\"width: 12.5%; margin: 1.5em\">x</p>",
        render("<p t:style=\"|width: ${n / 8.0}%; margin: ${css.length(s)}|\">x</p>", "n", 100, "s", "1.5em"));
    assertEquals(
        "<p style=\"opacity: 0.25; z-index: 3\">x</p>",
        render("<p t:style=\"'opacity: ' + d + '; z-index: ' + n\">x</p>", "d", 0.25, "n", 3));
    assertEquals("<p style=\"flex: 2.50\">x</p>", render("<p t:style=\"|flex: ${big}|\">x</p>", "big", new BigDecimal("2.50")));
  }

  @Test
  void cssValueParameter() {
    assertEquals(
        "<p style=\"color: #abc\">x</p>", render("<p t:style=\"|color: ${v}|\">x</p>", "v", CssValue.color("#abc")));
  }

  @Test
  void literalOnlyConditionals() {
    String body = "<p t:style=\"b ? 'opacity:.5' : 'opacity:1'\">x</p>";
    assertEquals("<p style=\"opacity:.5\">x</p>", render(body, "b", true));
    assertEquals("<p style=\"opacity:1\">x</p>", render(body, "b", false));
    assertEquals(
        "<p style=\"color: #000\">x</p>", render("<p t:style=\"|color: ${css.color(s) ?: css.color('#000')}|\">x</p>", "s", "bad"));
    assertEquals(
        "<p style=\"display:none\">x</p>", render("<p t:attr=\"style=${b} ? 'display:none' : null\">x</p>", "b", true));
    assertEquals("<p>x</p>", render("<p t:attr=\"style=${b} ? 'display:none' : null\">x</p>", "b", false));
  }

  @Test
  void literalsAreAttributeEscaped() {
    assertEquals(
        "<p style=\"font-family: &quot;Inter&quot;\">x</p>",
        render("<p t:style=\"'font-family: &quot;Inter&quot;'\">x</p>"));
  }

  // ------------------------------------------------------------- negative: build errors

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<p t:style=\"s\">x</p>",
        "<p t:style=\"|color: ${s}|\">x</p>",
        "<p t:style=\"'color: ' + s\">x</p>",
        "<p t:style=\"b ? s : 'opacity:1'\">x</p>",
        "<p t:style=\"b ? 'opacity:1' : s\">x</p>",
        "<p t:style=\"css.color(s) ?: s\">x</p>",
        "<p t:style=\"#strings.trim(s)\">x</p>",
        "<p t:style=\"#{color}\">x</p>",
        "<p t:style=\"|color: ${b}|\">x</p>",
        "<p t:STYLE=\"s\">x</p>",
        "<p t:attr=\"style=s\">x</p>",
        "<p t:attr=\"style='color:' + s\">x</p>",
      })
  void stringValuesAreBuildErrors(String body) {
    compile(body).assertError("t.html:1:", "is not allowed", "style value", "t:class");
  }

  @Test
  void errorNamesTheOffendingPieceAndItsType() {
    compile("<p t:style=\"|color: ${css.color(s)}; width: ${s}|\">x</p>")
        .assertError("t.html:1:", "t:style is not allowed", "not a java.lang.String");
  }

  @Test
  void styleElementStillRejectsExpressions() {
    compile("<style>p { color: [[${css.color(s)}]]; }</style>").assertError("t.html:1:", "inside <style>");
  }

  // ------------------------------------------------------------- negative: rendered values

  @ParameterizedTest
  @ValueSource(
      strings = {
        "expression(alert(1))",
        "url(javascript:alert(1))",
        "url(\"https://evil\")",
        "red; background-image: url(https://evil)",
        "red} body { display:none",
        "red/**/",
        "/* x */ red",
        "\\72 ed",
        "\\0065xpression(alert(1))",
        "red\\;",
        "#fff;",
        "#ff\u0000f",
        "＃fff",
        "red;",
        "'red'",
        "\"red\"",
        "rgb(1,2,3)",
        "#ggg",
        "#12345",
        "",
      })
  void hostileColorsNeverReachTheAttribute(String hostile) {
    assertEquals("<p style=\"color: null\">x</p>", render("<p t:style=\"|color: ${css.color(s)}|\">x</p>", "s", hostile));
    assertEquals("<p style=\"width: null\">x</p>", render("<p t:style=\"|width: ${css.length(s)}|\">x</p>", "s", hostile));
  }
}
