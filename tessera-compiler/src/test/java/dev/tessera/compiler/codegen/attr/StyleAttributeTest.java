package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.CssValue;
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
        "<p style=\"display:none\">x</p>", render("<p t:attr=\"style=${b} ? 'display:none' : null\">x</p>", "b", true));
    assertEquals("<p>x</p>", render("<p t:attr=\"style=${b} ? 'display:none' : null\">x</p>", "b", false));
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
        "<p t:attr=\"style=s\">x</p>",
        "<p t:attr=\"style='color:' + s\">x</p>",
      })
  void stringValuesAreBuildErrors(String body) {
    compile(body).assertError("t.html:1:", "is not allowed", "style value", "t:class");
  }

  @Test
  void charIsNotANumber() {
    // A char could be ';' or '}'.
    Compilation.builder()
        .template("t.html", "<div xmlns:t=\"https://tessera.dev\" t:params=\"char c, Character k\"><p t:style=\"|a: ${c}|\">x</p><i t:style=\"|a: ${k}|\">y</i></div>")
        .compile()
        .assertError("t.html:1:", "is not allowed", "not a char")
        .assertError("t.html:1:", "is not allowed", "not a java.lang.Character");
  }

  // ------------------------------------------------------------- SVG presentation attributes

  /** An SVG attribute that takes a CSS url() is CSS: it takes what t:style takes. */
  @Test
  void svgCssUrlAttributesTakeCssValues() {
    assertEquals(
        "<svg><rect fill=\"#ff8800\" stroke=\"url(&quot;/p.svg#g&quot;)\"/></svg>",
        render("<svg><rect t:fill=\"css.color(s)\" t:stroke=\"css.url('/p.svg#g')\"/></svg>", "s", "#ff8800"));
    assertEquals(
        "<svg><g clip-path=\"url(#c)\" cursor=\"pointer\"/></svg>",
        render("<svg><g t:clip-path=\"'url(#c)'\" t:cursor=\"b ? 'move' : 'pointer'\"/></svg>"));
    assertEquals(
        "<svg><rect fill=\"null\"/></svg>",
        render("<svg><rect t:fill=\"|${css.color(s)}|\"/></svg>", "s", "url(javascript:alert(1))"));
    assertEquals("<svg><rect/></svg>", render("<svg><rect t:mask=\"css.url(s)\"/></svg>", "s", "javascript:alert(1)"));
  }

  /** #429: colour functions and custom properties from data, in style and in SVG presentation attributes. */
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
    assertEquals(
        "<svg><rect fill=\"hsl(120deg, 50%, 50%)\" stroke=\"var(--line)\"/></svg>",
        render("<svg><rect t:fill=\"css.color(s)\" t:stroke=\"css.var('--line')\"/></svg>", "s", "hsl(120deg, 50%, 50%)"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<svg><rect t:fill=\"s\"/></svg>",
        "<svg><rect t:stroke=\"|url(${s})|\"/></svg>",
        "<svg><g t:clip-path=\"'url(' + s + ')'\"/></svg>",
        "<svg><g t:mask=\"s\"/></svg>",
        "<svg><g t:filter=\"s\"/></svg>",
        "<svg><path t:marker-start=\"s\" /></svg>",
        "<svg><path t:marker-mid=\"s\" /></svg>",
        "<svg><path t:marker-end=\"s\" /></svg>",
        "<svg><path t:marker=\"s\" /></svg>",
        "<svg t:cursor=\"s\"></svg>",
        "<svg><RECT t:FILL=\"s\"/></svg>",
        "<svg><rect t:attr=\"fill=s\"/></svg>",
        "<svg><rect><animate t:stroke=\"s\"/></rect></svg>",
      })
  void svgCssUrlAttributesRejectStrings(String body) {
    compile(body).assertError("t.html:1:", "is not allowed", "style value");
  }

  /**
   * Outside SVG, and on an animation's timing fill, the names are not STYLE, but they are not
   * inert-text attributes either (#539): literals and numbers compile, a String does not.
   */
  @Test
  void otherFillAttributesStayOrdinary() {
    assertEquals("<p fill=\"url(x)\">x</p>", render("<p t:fill=\"'url(x)'\">x</p>"));
    compile("<p t:fill=\"s\">x</p>").assertError("t.html:1:", "t:fill is not allowed", "inert-text", "String");
    compile("<svg><rect><animate t:fill=\"s\"/></rect></svg>").assertError("t:fill is not allowed", "inert-text");
    assertEquals(
        "<svg><rect><animateMotion fill=\"freeze\"/><animateTransform fill=\"remove\"/></rect></svg>",
        render("<svg><rect><animateMotion t:fill=\"b ? 'remove' : 'freeze'\"/><animateTransform t:fill=\"'remove'\"/></rect></svg>",
            "b", false));
    assertEquals(
        "<svg><rect fill-opacity=\"0.5\"/></svg>", render("<svg><rect t:fill-opacity=\"d\"/></svg>", "d", 0.5));
  }

  /** An animation of one of those attributes sets CSS: its values take what t:fill takes. */
  @Test
  void animatedSvgCssUrlAttributesTakeCssValues() {
    assertEquals(
        "<svg><rect><animate attributeName=\"fill\" values=\"red;#ff8800\" to=\"url(&quot;/p.svg#g&quot;)\"/></rect></svg>",
        render("<svg><rect><animate attributeName=\"fill\" t:values=\"|red;${css.color(s)}|\""
            + " t:to=\"css.url('/p.svg#g')\"/></rect></svg>", "s", "#ff8800"));
    assertEquals(
        "<svg><rect><set attributeName=\"mask\" to=\"url(#m)\"/></rect></svg>",
        render("<svg><rect><set attributeName=\"mask\" t:to=\"b ? 'none' : 'url(#m)'\"/></rect></svg>"));
    // css.url keeps the URL in one quoted url(): a ';' in it cannot start another list item's url(.
    assertEquals(
        "<svg><rect><animate attributeName=\"fill\" values=\"url(&quot;/a;url%28//evil/x%29&quot;)\"/></rect></svg>",
        render("<svg><rect><animate attributeName=\"fill\" t:values=\"css.url(s)\"/></rect></svg>",
            "s", "/a;url(//evil/x)"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<svg><rect><animate attributeName=\"fill\" t:values=\"s\"/></rect></svg>",
        "<svg><rect><animate attributeName=\"fill\" t:values=\"|red;${s}|\"/></rect></svg>",
        "<svg><rect><animate attributeName=\"fill\" t:to=\"s\"/></rect></svg>",
        "<svg><rect><animate attributeName=\"stroke\" t:from=\"s\"/></rect></svg>",
        "<svg><rect><animate attributeName=\"clip-path\" t:by=\"s\"/></rect></svg>",
        "<svg><rect><set attributeName=\"mask\" t:to=\"s\"/></rect></svg>",
        "<svg><g><set attributeName=\"filter\" t:attr=\"to=s\"/></g></svg>",
        "<svg><path><set attributeName=\"marker-end\" t:to=\"s\"/></path></svg>",
        "<svg><rect><animateColor attributeName=\"fill\" t:values=\"s\"/></rect></svg>",
        "<svg><rect><ANIMATE ATTRIBUTENAME=\" FILL \" t:VALUES=\"s\"/></rect></svg>",
        "<svg><rect><set attributeName=\"cursor\" t:to=\"s\"/></rect></svg>",
        "<svg><path><set attributeName=\"marker\" t:to=\"s\"/></path></svg>",
      })
  void animatedSvgCssUrlAttributesRejectStrings(String body) {
    compile(body).assertError("t.html:1:", "is not allowed", "style value");
  }

  /** A computed attributeName could retarget an href animation's URL-checked values to CSS. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<svg><a><animate t:attributeName=\"s\" values=\"red\"/></a></svg>",
        "<svg><a><set t:attributeName=\"b ? 'href' : 'fill'\" t:to=\"s\"/></a></svg>",
        "<svg><a><set t:attr=\"attributeName='fill'\"/></a></svg>",
        "<svg><a><animateTransform t:attributeName=\"s\"/></a></svg>",
        "<svg><a><animateMotion t:AttributeName=\"s\"/></a></svg>",
        "<svg><a><animateColor t:attributeName=\"s\"/></a></svg>",
      })
  void animationAttributeNameCannotBeComputed(String body) {
    compile(body).assertError("t.html:1:", "attributeName of an SVG animation cannot be computed");
  }

  /** Other animations keep their contexts: an href animation's values are URL-checked strings. */
  @Test
  void otherAnimationsStayUrlChecked() {
    assertEquals(
        "<svg><a><set attributeName=\"href\" to=\"about:invalid#tessera\"/></a></svg>",
        render("<svg><a><set attributeName=\"href\" t:to=\"s\"/></a></svg>", "s", "javascript:alert(1)"));
    assertEquals(
        "<svg><rect><animate attributeName=\"fill-opacity\" values=\"0;1\"/></rect></svg>",
        render("<svg><rect><animate attributeName=\"fill-opacity\" t:values=\"s\"/></rect></svg>", "s", "0;1"));
    assertEquals("<p attributeName=\"fill\">x</p>", render("<p t:attributeName=\"'fill'\">x</p>"));
    compile("<p t:attributeName=\"s\">x</p>").assertError("t:attributeName is not allowed", "inert-text");
  }

  /**
   * A browser folds A-Z only: attr\u0131buteName (dotless i) and attr\u0130buteName (dotted capital I) are
   * unknown attributes, so the animation animates href, which the real attributeName names, and splits its
   * values at ';'. They stay URL-checked: a decoy naming fill must not make them CSS.
   */
  @Test
  void attributeNameFoldsOnlyAscii() {
    String hostile = "/a;javascript:alert(1)//";
    for (String decoy : List.of("attr\u0131buteName=\"fill\"", "attr\u0130buteName=\"fill\"")) {
      for (String names : List.of("attributeName=\"href\" " + decoy, decoy + " attributeName=\"href\"")) {
        assertEquals(
            "<svg><a href=\"/x\"><animate " + names + " values=\"about:invalid#tessera\"/>x</a></svg>",
            render("<svg><a href=\"/x\"><animate " + names + " t:values=\"css.url(s)\"/>x</a></svg>", "s", hostile),
            names);
        assertEquals(
            "<svg><a href=\"/x\"><set " + names + " to=\"about:invalid#tessera\"/>x</a></svg>",
            render("<svg><a href=\"/x\"><set " + names + " t:to=\"s\"/>x</a></svg>", "s", "javascript:alert(1)"),
            names);
      }
    }
    // The real attributeName still makes the values CSS, in any ASCII case; an ASCII-case duplicate is an error.
    assertEquals(
        "<svg><rect><animate ATTRIBUTENAME=\"fill\" values=\"url(&quot;/a;javascript:alert%281%29//&quot;)\"/>"
            + "</rect></svg>",
        render("<svg><rect><animate ATTRIBUTENAME=\"fill\" t:values=\"css.url(s)\"/></rect></svg>", "s", hostile));
    compile("<svg><a><animate attributeName=\"href\" ATTRIBUTENAME=\"fill\" t:values=\"css.url(s)\"/></a></svg>")
        .assertError("t.html:1:", "duplicate attribute 'ATTRIBUTENAME'");
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
