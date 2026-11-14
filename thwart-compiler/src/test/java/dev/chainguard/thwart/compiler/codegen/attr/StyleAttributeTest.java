package dev.chainguard.thwart.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.chainguard.thwart.compiler.testing.Compilation;
import dev.chainguard.thwart.runtime.CssValue;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** t:style takes only literals, numbers and CssValues (#182, css.url #238). */
class StyleAttributeTest {

  private static final String HEADER =
      "<div xmlns:t=\"https://thwart.chainguard.dev\""
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
  void backgroundImageFromTheCssNamespace() {
    String body = "<div t:style=\"|background: ${css.url(s)} no-repeat center; background-size: cover|\">x</div>";
    assertEquals(
        "<div style=\"background: url(&quot;/img/avatar.jpg&quot;) no-repeat center; background-size: cover\">x</div>",
        render(body, "s", "/img/avatar.jpg"));
    assertEquals(
        "<div style=\"background: url(&quot;https://cdn.example.com/a.png?w=1&amp;h=2&quot;) no-repeat center;"
            + " background-size: cover\">x</div>",
        render(body, "s", "https://cdn.example.com/a.png?w=1&h=2"));
    // A breakout attempt stays inside the quoted URL.
    assertEquals(
        "<div style=\"background: url(&quot;/a%22%29%20;%20background:url%28evil&quot;) no-repeat center;"
            + " background-size: cover\">x</div>",
        render(body, "s", "/a\") ; background:url(evil"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"javascript:alert(1)", "data:image/png;base64,AAAA", "/a\nb", "vbscript:x", ""})
  void hostileUrlsNeverReachTheAttribute(String hostile) {
    assertEquals(
        "<div style=\"background-image: null\">x</div>",
        render("<div t:style=\"|background-image: ${css.url(s)}|\">x</div>", "s", hostile));
    assertEquals("<div>x</div>", render("<div t:style=\"css.url(s)\">x</div>", "s", hostile));
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
        "<p style=\"display:none\">x</p>", render("<p t:style=\"${b} ? 'display:none' : null\">x</p>", "b", true));
    assertEquals("<p>x</p>", render("<p t:style=\"${b} ? 'display:none' : null\">x</p>", "b", false));
    // No else branch: the attribute is omitted when the condition is false.
    assertEquals("<p style=\"display:none\">x</p>", render("<p t:style=\"${b} ? 'display:none'\">x</p>", "b", true));
    assertEquals("<p>x</p>", render("<p t:style=\"${b} ? 'display:none'\">x</p>", "b", false));
    // The condition may be any boolean; only the branches are output.
    assertEquals("<p style=\"color:red\">x</p>", render("<p t:style=\"${s == 'x'} ? 'color:red'\">x</p>", "s", "x"));
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
        "<p t:style=\"b ? s\">x</p>",
        "<p t:style=\"|a: ${|${s}|}|\">x</p>",
        "<p t:STYLE=\"s\">x</p>",
        "<p t:style=\"s\">x</p>",
        "<p t:style=\"'color:' + s\">x</p>",
      })
  void stringValuesAreBuildErrors(String body) {
    compile(body).assertError("t.html:1:", "is not allowed", "style value", "t:class");
  }

  @Test
  void charIsNotANumber() {
    // A char could be ';' or '}'.
    Compilation.builder()
        .template("t.html", "<div xmlns:t=\"https://thwart.chainguard.dev\" t:params=\"char c, Character k\"><p t:style=\"|a: ${c}|\">x</p><i t:style=\"|a: ${k}|\">y</i></div>")
        .compile()
        .assertError("t.html:1:", "is not allowed", "not a char")
        .assertError("t.html:1:", "is not allowed", "not a java.lang.Character");
  }

  /** #429: colour functions and custom properties from data, in style. */
  @Test
  void colorFunctionsAndCustomProperties() {
    String color = "<p t:style=\"|color: ${css.color(s)}|\">x</p>";
    assertEquals("<p style=\"color: rgb(0 128 255 / .5)\">x</p>", render(color, "s", "RGB(0 128 255/.5)"));
    assertEquals("<p style=\"color: null\">x</p>", render(color, "s", "rgb(0, 0, 0);background:url(//evil)"));
    String var = "<p t:style=\"|border-color: ${css.var(s)}|\">x</p>";
    assertEquals("<p style=\"border-color: var(--brand)\">x</p>", render(var, "s", "--brand"));
    assertEquals("<p style=\"border-color: null\">x</p>", render(var, "s", "--a);background:url(//evil"));
    // Numbers from the model need no css.* function: the literal holds the function.
    assertEquals("<p style=\"color: rgb(7, 0, 0)\">x</p>", render("<p t:style=\"|color: rgb(${n}, 0, 0)|\">x</p>", "n", 7));
    // The guide's recipe for a computed SVG value (#678): a CSS variable set on an HTML parent.
    assertEquals("<div style=\"--progress: 7\"><svg><path d=\"M0 0\"/></svg></div>",
        render("<div t:style=\"|--progress: ${n}|\"><svg><path d=\"M0 0\"/></svg></div>", "n", 7));
  }

  /**
   * #678: SVG presentation attributes and animation values are no longer modelled as CSS: no computed
   * attribute goes inside an svg (UnmodelledMarkupTest), so outside SVG fill is an ordinary typed attribute.
   */
  @Test
  void svgAttributesAreNotStyle() {
    compile("<p t:fill=\"'url(x)'\">x</p>").assertError("t.html:1:", "t:fill is not allowed", "not on Thwart's list");
    compile("<p t:fill=\"s\">x</p>").assertError("t.html:1:", "t:fill is not allowed", "not on Thwart's list");
    for (String body : List.of("<svg><rect t:fill=\"css.color(s)\"/></svg>", "<svg t:cursor=\"'move'\"></svg>",
        "<svg><rect><animate attributeName=\"fill\" t:values=\"'red'\"/></rect></svg>",
        "<svg><a><set t:attributeName=\"'href'\"/></a></svg>")) {
      compile(body).assertError("t.html:1:", "SVG and MathML by rules Thwart doesn't check");
    }
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
        "\u0661\u0662px",
        "１２px",
        "12px\n;x:y",
        "red;",
        "'red'",
        "\"red\"",
        "rgb(1,2,3);x:y",
        "rgb(url(x),2,3)",
        "#ggg",
        "#12345",
        "",
      })
  void hostileColorsNeverReachTheAttribute(String hostile) {
    assertEquals("<p style=\"color: null\">x</p>", render("<p t:style=\"|color: ${css.color(s)}|\">x</p>", "s", hostile));
    assertEquals("<p style=\"width: null\">x</p>", render("<p t:style=\"|width: ${css.length(s)}|\">x</p>", "s", hostile));
  }
}
