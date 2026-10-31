package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.testing.Compilation.model;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

/**
 * Every value in a JavaScript {@code <script>} is written in parentheses, {@code (json)}, and may
 * only be the value of a declaration at the top of the script. #511 ({@code [[${n}]].toFixed(2)}
 * with a negative number) and #533 (the same with an integer) are rejected at build time; the
 * prelude form uses the value through its name, so a member access applies to the whole number.
 * JSON scripts are unchanged.
 */
class ScriptNumberTest {
  private static Compilation compile(String params, String body) {
    return Compilation.builder()
        .template("page.html", "<div t:params=\"" + params + "\">" + body + "</div>")
        .compile()
        .assertSuccess();
  }

  /** The value of {@code result} after running {@code script} (Rhino, test scope only). */
  private static Object run(String script, String result) {
    Context cx = Context.enter();
    try {
      cx.setLanguageVersion(Context.VERSION_ECMASCRIPT);
      Scriptable scope = cx.initStandardObjects();
      cx.evaluateString(scope, script, "script", 1, null);
      return Context.jsToJava(scope.get(result, scope), Object.class);
    } finally {
      Context.exit();
    }
  }

  private static String script(String html) {
    return html.substring(html.indexOf("<script>") + "<script>".length(), html.indexOf("</script>"));
  }

  /** The original templates of #511 and #533: a member access, call, index or operator right after a value. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<script>var s = [[${n}]].toFixed(2);</script>",
        "<script>var a = [[${n}]].toString(); var b = [[${n}]]['toString'](); var c = [[${n}]] * 2;</script>",
        "<script>var c = [[${n}]] * 2;</script>",
        "<script>var c = 2 ** [[${n}]];</script>"
      })
  void numberNextToCodeFailsToBuild(String body) {
    Compilation.builder()
        .template("page.html", "<div t:params=\"double n\">" + body + "</div>")
        .compile()
        .assertError("page.html:1:", "only allowed as the whole value of a declaration");
  }

  @Test
  void memberAccessThroughTheNameAppliesToTheNumber() {
    Compilation c = compile(
        "double n, int i, java.math.BigInteger bi, java.math.BigDecimal bd",
        "<script>var n = [[${n}]]; var i = [[${i}]]; var bi = [[${bi}]]; var bd = [[${bd}]];\n"
            + "var a = n.toFixed(2), b = i.toFixed(2), c = bi.toFixed(2), d = bd.toFixed(2);</script>");
    String js = script(c.render("page", model("n", -1.5, "i", 1, "bi", BigInteger.ONE, "bd", new BigDecimal("1E+3"))));
    assertEquals("var n = (-1.5); var i = (1); var bi = (1); var bd = (1E+3);", js.substring(0, js.indexOf('\n')));
    assertEquals("-1.50", run(js, "a"));
    assertEquals("1.00", run(js, "b"));
    assertEquals("1.00", run(js, "c"));
    assertEquals("1000.00", run(js, "d"));
  }

  @Test
  void everyValueIsParenthesized() {
    Compilation c = compile(
        "Integer i, Long l, Double d, Float f, java.math.BigDecimal bd, java.math.BigInteger bi,"
            + " java.util.Optional<Double> o, String s, java.util.List<Integer> li, java.util.Map<String, Long> m",
        "<script>var i = [[${i}]]; var l = [[${l}]]; var d = [[${d}]]; var f = [[${f}]]; var bd = [[${bd}]];"
            + " var bi = [[${bi}]]; var o = [[${o}]]; var s = [[${s}]]; var li = [[${li}]]; var m = [[${m}]];</script>");
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("i", -3);
    values.put("l", Long.MIN_VALUE);
    values.put("d", 0.0);
    values.put("f", Float.NEGATIVE_INFINITY);
    values.put("bd", new BigDecimal("-1.10"));
    values.put("bi", BigInteger.ZERO);
    values.put("o", Optional.empty());
    values.put("s", "-1");
    values.put("li", List.of(-1, 2));
    values.put("m", Map.of("x", 7L));
    assertEquals(
        "var i = (-3); var l = (-9223372036854775808); var d = (0.0); var f = (null); var bd = (-1.10);"
            + " var bi = (0); var o = (null); var s = (\"-1\"); var li = ([-1,2]); var m = ({\"x\":7});",
        script(c.render("page", values)));
  }

  @Test
  void negativeZeroKeepsItsSign() {
    Compilation c = compile("double n", "<script>var z = [[${n}]]; var s = 1 / z;</script>");
    assertEquals(Double.NEGATIVE_INFINITY, ((Number) run(script(c.render("page", model("n", -0.0))), "s")).doubleValue());
  }

  @Test
  void valueInAJavaScriptScriptIsParenthesized() {
    Compilation c = compile("long n", "<script>const n = [[${n}]];</script>");
    assertEquals("<div><script>const n = (-42);</script></div>", c.render("page", model("n", -42L)));
  }

  @Test
  void moduleAndJavaScriptTypesAreParenthesized() {
    Compilation c = compile(
        "int n",
        "<script type=\"module\">var a = [[${n}]];</script><script type=\" Text/JavaScript \">var a = [[${n}]];</script>");
    assertEquals(
        "<div><script type=\"module\">var a = (-1);</script>"
            + "<script type=\" Text/JavaScript \">var a = (-1);</script></div>",
        c.render("page", model("n", -1)));
  }

  @Test
  void jsonScriptTypesKeepJsonOutput() {
    // Negative test: (-1.5) is not JSON, so a JSON script keeps the plain value.
    Compilation c = compile(
        "double n, java.util.List<Double> l",
        "<script type=\"application/json\">[[${n}]]</script>"
            + "<script type=\" Application/LD+JSON \">[[${l}]]</script>"
            + "<script type=\"application/json\" t:text=\"${n}\"></script>");
    assertEquals(
        "<div><script type=\"application/json\">-1.5</script>"
            + "<script type=\" Application/LD+JSON \">[-2.0]</script>"
            + "<script type=\"application/json\">-1.5</script></div>",
        c.render("page", model("n", -1.5, "l", List.of(-2.0))));
  }
}
