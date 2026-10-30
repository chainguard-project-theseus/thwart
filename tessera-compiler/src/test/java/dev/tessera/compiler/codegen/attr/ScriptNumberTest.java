package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.testing.Compilation.model;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.tessera.compiler.testing.Compilation;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Scriptable;

/**
 * A negative number or an integer in a JavaScript {@code <script>} is written in parentheses, so a
 * member access, call or index after it applies to the number: {@code -1.5.toFixed(2)} is
 * {@code -(1.5.toFixed(2))}, and {@code 1.toFixed(2)} is a syntax error. JSON scripts and values
 * nested in arrays or objects are unchanged.
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

  @Test
  void memberAccessAfterANegativeNumberAppliesToTheNumber() {
    Compilation c = compile("double n", "<script>var s = [[${n}]].toFixed(2);</script>");
    String html = c.render("page", model("n", -1.5));
    assertEquals("<div><script>var s = (-1.5).toFixed(2);</script></div>", html);
    assertEquals("-1.50", run(script(html), "s"));
    assertEquals("2.50", run(script(c.render("page", model("n", 2.5))), "s"));
  }

  @Test
  void callAndIndexAfterANegativeNumberApplyToTheNumber() {
    Compilation c = compile(
        "int n", "<script>var a = [[${n}]].toString(); var b = [[${n}]]['toString'](); var c = [[${n}]] * 2;</script>");
    String js = script(c.render("page", model("n", -7)));
    assertEquals("var a = (-7).toString(); var b = (-7)['toString'](); var c = (-7) * 2;", js);
    assertEquals("-7", run(js, "a"));
    assertEquals("-7", run(js, "b"));
    assertEquals(-14.0, ((Number) run(js, "c")).doubleValue());
  }

  @Test
  void everyNegativeNumberTypeIsParenthesized() {
    Compilation c = compile(
        "Integer i, Long l, Double d, Float f, java.math.BigDecimal bd, java.math.BigInteger bi,"
            + " java.util.Optional<Double> o, short s, byte b",
        "<script>var i = [[${i}]]; var l = [[${l}]]; var d = [[${d}]]; var f = [[${f}]]; var bd = [[${bd}]];"
            + " var bi = [[${bi}]]; var o = [[${o}]]; var s = [[${s}]]; var b = [[${b}]];</script>");
    Map<String, Object> negative = new LinkedHashMap<>();
    negative.put("i", -3);
    negative.put("l", Long.MIN_VALUE);
    negative.put("d", -0.0);
    negative.put("f", -2.5f);
    negative.put("bd", new BigDecimal("-1.10"));
    negative.put("bi", new BigInteger("-123456789012345678901234567890"));
    negative.put("o", Optional.of(-4.0));
    negative.put("s", (short) -5);
    negative.put("b", (byte) -6);
    assertEquals(
        "var i = (-3); var l = (-9223372036854775808); var d = (-0.0); var f = (-2.5); var bd = (-1.10);"
            + " var bi = (-123456789012345678901234567890); var o = (-4.0); var s = (-5); var b = (-6);",
        script(c.render("page", negative)));
    Map<String, Object> other = new LinkedHashMap<>();
    other.put("i", 3);
    other.put("l", Long.MAX_VALUE);
    other.put("d", 0.0);
    other.put("f", Float.NEGATIVE_INFINITY);
    other.put("bd", new BigDecimal("1.10"));
    other.put("bi", BigInteger.ZERO);
    other.put("o", Optional.empty());
    other.put("s", (short) 5);
    other.put("b", (byte) 6);
    assertEquals(
        "var i = (3); var l = (9223372036854775807); var d = 0.0; var f = null; var bd = 1.10;"
            + " var bi = (0); var o = null; var s = (5); var b = (6);",
        script(c.render("page", other)));
  }

  @Test
  void memberAccessAfterAnIntegerParses() {
    Compilation c = compile(
        "int i, long l, java.math.BigInteger bi, java.math.BigDecimal bd",
        "<script>var a = [[${i}]].toFixed(2); var b = [[${l}]].toFixed(2); var c = [[${bi}]].toFixed(2);"
            + " var d = [[${bd}]].toFixed(2);</script>");
    String js = script(c.render("page", model("i", 1, "l", 1L, "bi", BigInteger.ONE, "bd", new BigDecimal("1"))));
    assertEquals(
        "var a = (1).toFixed(2); var b = (1).toFixed(2); var c = (1).toFixed(2); var d = (1).toFixed(2);", js);
    for (String v : new String[] {"a", "b", "c", "d"}) {
      assertEquals("1.00", run(js, v), v);
    }
  }

  @Test
  void anUnparenthesizedIntegerBeforeAMemberAccessIsASyntaxError() {
    // Why the parentheses exist: without them the output does not parse.
    assertThrows(EvaluatorException.class, () -> run("var s = 1.toFixed(2);", "s"));
  }

  @Test
  void aNumberWithADecimalPointOrExponentIsUnchanged() {
    Compilation c = compile(
        "double d, java.math.BigDecimal bd, java.math.BigDecimal e",
        "<script>var a = [[${d}]].toFixed(2); var b = [[${bd}]].toFixed(2); var c = [[${e}]].toFixed(2);</script>");
    String js = script(c.render("page", model("d", 1.5, "bd", new BigDecimal("1.0"), "e", new BigDecimal("1E+3"))));
    assertEquals("var a = 1.5.toFixed(2); var b = 1.0.toFixed(2); var c = 1E+3.toFixed(2);", js);
    assertEquals("1.50", run(js, "a"));
    assertEquals("1.00", run(js, "b"));
    assertEquals("1000.00", run(js, "c"));
  }

  @Test
  void nestedIntegersAreUnchanged() {
    Compilation c = compile(
        "java.util.List<Integer> l, int[] a, java.util.Map<String, Long> m",
        "<script>var l = [[${l}]].length; var a = [[${a}]]; var m = [[${m}]];</script>");
    String js = script(c.render("page", model("l", List.of(1), "a", new int[] {1}, "m", Map.of("x", 7L))));
    assertEquals("var l = [1].length; var a = [1]; var m = {\"x\":7};", js);
    assertEquals(1, ((Number) run(js, "l")).intValue());
  }

  @Test
  void jsonScriptTypesKeepIntegers() {
    // Negative test: (7) is not JSON, so a JSON script keeps the plain number.
    Compilation c = compile(
        "int n",
        "<script type=\"application/json\">[[${n}]]</script><script type=\"application/json\" t:text=\"${n}\"></script>");
    assertEquals(
        "<div><script type=\"application/json\">7</script><script type=\"application/json\">7</script></div>",
        c.render("page", model("n", 7)));
  }

  @Test
  void negativeZeroKeepsItsSign() {
    Compilation c = compile("double n", "<script>var z = [[${n}]]; var s = 1 / z;</script>");
    assertEquals(Double.NEGATIVE_INFINITY, ((Number) run(script(c.render("page", model("n", -0.0))), "s")).doubleValue());
  }

  @Test
  void textAttributeOnAScriptIsParenthesized() {
    Compilation c = compile("long n", "<script t:text=\"${n}\"></script>");
    assertEquals("<div><script>(-42)</script></div>", c.render("page", model("n", -42L)));
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
  void nestedNegativeNumbersAreUnchanged() {
    Compilation c = compile(
        "java.util.List<Integer> l, java.util.Map<String, Double> m, int[] a, java.util.List<java.util.List<Long>> ll",
        "<script>var l = [[${l}]]; var m = [[${m}]]; var a = [[${a}]]; var ll = [[${ll}]];</script>");
    assertEquals(
        "<div><script>var l = [-1,2]; var m = {\"x\":-1.5}; var a = [-3]; var ll = [[-4]];</script></div>",
        c.render(
            "page",
            model("l", List.of(-1, 2), "m", Map.of("x", -1.5), "a", new int[] {-3}, "ll", List.of(List.of(-4L)))));
  }

  @Test
  void jsonScriptTypesKeepJsonOutput() {
    // Negative test: (-1.5) is not JSON, so a JSON script keeps the plain number.
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
