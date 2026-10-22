package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.testing.Compilation.model;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for t:if/t:unless, t:each, t:with, t:switch/t:case and t:remove. Output is
 * observed through conditions over static text, so these tests do not depend on t:text.
 */
class ControlFlowTest {

  private static String page(String params, String body) {
    return "<html xmlns:t=\"https://tessera.dev\" t:import=\"com.example.*\" t:params=\""
        + params
        + "\">"
        + body
        + "</html>";
  }

  private static Compilation compile(String params, String body, String... java) {
    Compilation.Builder b = Compilation.builder().template("page.html", page(params, body));
    for (int i = 0; i < java.length; i += 2) {
      b.java(java[i], java[i + 1]);
    }
    return b.compile();
  }

  private static String render(Compilation c, Map<String, ?> model) {
    String out = c.assertSuccess().render("page", model);
    assertEquals("<html>", out.substring(0, 6), out);
    return out.substring(6, out.length() - 7);
  }

  // ---- t:if / t:unless ----

  @Test
  void ifFollowsThymeleafTruthiness() {
    Compilation c =
        compile(
            "String s, Integer n, Boolean b, List<String> l",
            "<i t:if=\"s\">s</i><i t:if=\"n\">n</i><i t:if=\"b\">b</i><i t:if=\"l\">l</i>");
    assertEquals("", render(c, model()));
    assertEquals(
        "<i>s</i><i>n</i><i>b</i><i>l</i>",
        render(c, model("s", "yes", "n", 2, "b", true, "l", List.of())));
    assertEquals("", render(c, model("s", "no", "n", 0, "b", false)));
    assertEquals("", render(c, model("s", "FALSE")));
    assertEquals("", render(c, model("s", "off")));
    assertEquals("<i>s</i>", render(c, model("s", "")));
  }

  @Test
  void unlessIsTheNegation() {
    Compilation c = compile("boolean x, String s", "<i t:unless=\"x\">u</i><b t:unless=\"s\">n</b>");
    assertEquals("<i>u</i><b>n</b>", render(c, model("x", false)));
    assertEquals("", render(c, model("x", true, "s", "on")));
  }

  @Test
  void ifUnknownVariableIsAnError() {
    compile("", "<i t:if=\"nope\">x</i>").assertError("page.html:1:", "unknown identifier 'nope'");
  }

  // ---- t:each ----

  private static final String ABC =
      "<b t:if=\"x == 'a'\">A</b><b t:if=\"x == 'b'\">B</b><b t:if=\"x == 'c'\">C</b>";

  @Test
  void eachOverList() {
    Compilation c = compile("List<String> items", "<i t:each=\"x : items\">" + ABC + "</i>");
    assertEquals(
        "<i><b>A</b></i><i><b>B</b></i><i><b>C</b></i>", render(c, model("items", List.of("a", "b", "c"))));
    assertEquals("", render(c, model("items", List.of())));
  }

  // Expected outputs below were checked against Thymeleaf 3.1.5 with the th: equivalents.

  @Test
  void eachRepeatsPrecedingWhitespaceBetweenIterations() {
    Compilation c = compile("List<String> items", "<ul>\n  <li t:each=\"x : items\">" + ABC + "</li>\n</ul>");
    assertEquals(
        "<ul>\n  <li><b>A</b></li>\n  <li><b>B</b></li>\n  <li><b>C</b></li>\n</ul>",
        render(c, model("items", List.of("a", "b", "c"))));
    assertEquals("<ul>\n  <li><b>A</b></li>\n</ul>", render(c, model("items", List.of("a"))));
    assertEquals("<ul>\n  \n</ul>", render(c, model("items", List.of())));
    assertEquals("<ul>\n  \n</ul>", render(c, model()));
  }

  @Test
  void eachRepeatsPrecedingWhitespaceOverArrays() {
    Compilation c = compile("String[] items", "<ul>\n\t <li t:each=\"x : items\">" + ABC + "</li>\n</ul>");
    assertEquals(
        "<ul>\n\t <li><b>A</b></li>\n\t <li><b>B</b></li>\n\t <li><b>C</b></li>\n</ul>",
        render(c, model("items", new String[] {"a", "b", "c"})));
  }

  @Test
  void eachDoesNotRepeatTextThatIsNotWhitespace() {
    Compilation c = compile("List<String> items", "<ul>foo\n  <li t:each=\"x : items\">.</li>\n</ul>");
    assertEquals("<ul>foo\n  <li>.</li><li>.</li>\n</ul>", render(c, model("items", List.of("a", "b"))));
    c = compile("List<String> items", "<ul><li t:each=\"x : items\">.</li>\n</ul>");
    assertEquals("<ul><li>.</li><li>.</li>\n</ul>", render(c, model("items", List.of("a", "b"))));
    c = compile("List<String> items", "<ul>\n  <!-- c --><li t:each=\"x : items\">.</li>\n</ul>");
    assertEquals("<ul>\n  <!-- c --><li>.</li><li>.</li>\n</ul>", render(c, model("items", List.of("a", "b"))));
  }

  @Test
  void eachRepeatsWhitespaceAfterLastInlinedExpression() {
    Compilation c = compile("String s, List<String> items", "<ul>[[${s}]]\n  <li t:each=\"x : items\">.</li>\n</ul>");
    assertEquals("<ul>S\n  <li>.</li>\n  <li>.</li>\n</ul>", render(c, model("s", "S", "items", List.of("a", "b"))));
  }

  @Test
  void eachRepeatsWhitespaceForSkippedAndRemovedIterations() {
    Compilation c =
        compile("List<String> items", "<ul>\n  <li t:each=\"x : items\" t:if=\"x != 'b'\">" + ABC + "</li>\n</ul>");
    assertEquals(
        "<ul>\n  <li><b>A</b></li>\n  \n  <li><b>C</b></li>\n</ul>",
        render(c, model("items", List.of("a", "b", "c"))));
    c = compile("List<String> items", "<ul>\n  <li t:each=\"x : items\" t:remove=\"all\">.</li>\n</ul>");
    assertEquals("<ul>\n  \n  \n  \n</ul>", render(c, model("items", List.of("a", "b", "c"))));
  }

  @Test
  void eachOnBlockDoesNotRepeatWhitespace() {
    Compilation c = compile("List<String> items", "<ul>\n  <t:block t:each=\"x : items\">" + ABC + "</t:block>\n</ul>");
    assertEquals("<ul>\n  <b>A</b><b>B</b><b>C</b>\n</ul>", render(c, model("items", List.of("a", "b", "c"))));
  }

  @Test
  void eachRenderedStandaloneHasNoPrecedingWhitespace() {
    Compilation c =
        compile(
            "List<String> items",
            "<ul>\n  <li t:fragment=\"row(List<String> xs)\" t:each=\"x : xs\">.</li>\n</ul><div t:insert=\"~{::row(items)}\"></div>");
    assertEquals(
        "<ul>\n  \n</ul><div><li>.</li><li>.</li></div>",
        render(c, model("items", List.of("a", "b"))));
  }

  @Test
  void nestedEachRepeatsEachLevelsWhitespace() {
    Compilation c =
        compile(
            "List<List<String>> rows",
            "<div>\n<p t:each=\"r : rows\">\n  <i t:each=\"x : r\">" + ABC + "</i>\n</p>\n</div>");
    assertEquals(
        "<div>\n<p>\n  <i><b>A</b></i>\n  <i><b>B</b></i>\n</p>\n<p>\n  <i><b>C</b></i>\n</p>\n</div>",
        render(c, model("rows", List.of(List.of("a", "b"), List.of("c")))));
  }

  @Test
  void eachOverNullIsZeroIterations() {
    Compilation c = compile("List<String> items", "<p>[<i t:each=\"x : items\">.</i>]</p>");
    assertEquals("<p>[]</p>", render(c, model()));
  }

  @Test
  void eachOverSet() {
    Compilation c = compile("Set<String> items", "<i t:each=\"x : items\">" + ABC + "</i>");
    assertEquals("<i><b>B</b></i>", render(c, model("items", Set.of("b"))));
  }

  @Test
  void eachOverArrays() {
    Compilation c =
        compile(
            "String[] items, int[] nums",
            "<i t:each=\"x : items\">" + ABC + "</i><u t:each=\"n : nums\"><b t:if=\"n > 1\">big</b></u>");
    assertEquals(
        "<i><b>C</b></i><i><b>A</b></i><u></u><u><b>big</b></u>",
        render(c, model("items", new String[] {"c", "a"}, "nums", new int[] {1, 5})));
    assertEquals("", render(c, model()));
  }

  @Test
  void eachOverPlainIterable() {
    Compilation c =
        compile(
            "Iterable<String> items",
            "<i t:each=\"x : items\"><b t:if=\"xStat.last and xStat.size == 2\">last</b>" + ABC + "</i>");
    Iterable<String> once = List.of("a", "b")::iterator;
    assertEquals("<i><b>A</b></i><i><b>last</b><b>B</b></i>", render(c, model("items", once)));
  }

  @Test
  void eachOverMapEntries() {
    Map<String, Integer> m = new LinkedHashMap<>();
    m.put("a", 1);
    m.put("b", 2);
    Compilation c =
        compile(
            "Map<String, Integer> m",
            "<i t:each=\"e : m\"><b t:if=\"e.key == 'a' and e.value == 1\">a1</b>"
                + "<b t:if=\"e.key == 'b' and e.value == 2\">b2</b></i>");
    assertEquals("<i><b>a1</b></i><i><b>b2</b></i>", render(c, model("m", m)));
    assertEquals("", render(c, model()));
  }

  private static final String STATUS =
      "<b t:if=\"%1$s.index == %1$s.count - 1\">c</b><b t:if=\"%1$s.size == 3\">3</b>"
          + "<b t:if=\"%1$s.first\">F</b><b t:if=\"%1$s.last\">L</b>"
          + "<b t:if=\"%1$s.even\">E</b><b t:if=\"%1$s.odd\">O</b>"
          + "<b t:if=\"%1$s.index == 1\">1</b>";

  private static final String STATUS_OUT =
      "<i><b>c</b><b>3</b><b>F</b><b>O</b></i>"
          + "<i><b>c</b><b>3</b><b>E</b><b>1</b></i>"
          + "<i><b>c</b><b>3</b><b>L</b><b>O</b></i>";

  @Test
  void explicitStatusVariable() {
    Compilation c =
        compile("List<String> items", "<i t:each=\"x, st : items\">" + String.format(STATUS, "st") + "</i>");
    assertEquals(STATUS_OUT, render(c, model("items", List.of("a", "b", "c"))));
  }

  @Test
  void implicitStatusVariable() {
    Compilation c =
        compile("String[] items", "<i t:each=\"item : items\">" + String.format(STATUS, "itemStat") + "</i>");
    assertEquals(STATUS_OUT, render(c, model("items", new String[] {"a", "b", "c"})));
  }

  @Test
  void loopAndStatusVariablesMustDiffer() {
    compile("List<String> items", "<i t:each=\"x, x : items\">.</i>").assertError("page.html:1:", "different names");
  }

  @Test
  void nestedLoops() {
    Compilation c =
        compile(
            "List<List<Integer>> rows",
            "<p t:each=\"r : rows\"><i t:each=\"x : r\">"
                + "<b t:if=\"rStat.index == 1 and xStat.first\">R1</b><b t:if=\"x == 2\">two</b>"
                + "</i></p>");
    assertEquals(
        "<p><i></i><i><b>two</b></i></p><p><i><b>R1</b></i></p>",
        render(c, model("rows", List.of(List.of(1, 2), List.of(3)))));
  }

  @Test
  void nestedLoopsMayReuseTheVariableName() {
    Compilation c =
        compile(
            "List<List<Integer>> rows",
            "<p t:each=\"x : rows\"><i t:each=\"x : x\"><b t:if=\"x == 3\">3</b></i></p>");
    assertEquals("<p><i></i></p><p><i><b>3</b></i></p>", render(c, model("rows", List.of(List.of(1), List.of(3)))));
  }

  @Test
  void eachAndIfOnOneElementIfSeesLoopVariable() {
    Compilation c = compile("List<String> items", "<i t:each=\"x : items\" t:if=\"x != 'b'\">" + ABC + "</i>");
    assertEquals("<i><b>A</b></i><i><b>C</b></i>", render(c, model("items", List.of("a", "b", "c"))));
  }

  @Test
  void loopVariableIsNotVisibleAfterTheElement() {
    compile("List<String> items", "<i t:each=\"x : items\">.</i>\n<b t:if=\"x == 'a'\">y</b>")
        .assertError("page.html:2:", "unknown identifier 'x'");
    compile("List<String> items", "<i t:each=\"x : items\">.</i><b t:if=\"xStat.first\">y</b>")
        .assertError("unknown identifier 'xStat'");
  }

  @Test
  void eachOverNonIterableIsAnError() {
    compile("String s", "<i t:each=\"x : s\">.</i>").assertError("page.html:1:", "cannot iterate");
  }

  @Test
  void eachOverNeverViewSafeElementsIsAnError() {
    compile(
            "Threads threads",
            "<i t:each=\"t : threads\">.</i>",
            "com.example.Threads",
            "package com.example; @dev.tessera.runtime.TemplateView public class Threads extends java.util.ArrayList<Thread> {}")
        .assertError("page.html:1:", "never view-safe");
  }

  @Test
  void eachSyntaxErrorIsReported() {
    compile("List<String> items", "<i t:each=\"items\">.</i>").assertError("page.html:1:", "':'");
  }

  // ---- t:with ----

  @Test
  void withChained() {
    Compilation c = compile("int n", "<p t:with=\"a=n + 1, b=a * 2\"><b t:if=\"a == 2 and b == 4\">ok</b></p>");
    assertEquals("<p><b>ok</b></p>", render(c, model("n", 1)));
  }

  @Test
  void withShadowsAndRestores() {
    Compilation c =
        compile(
            "int n",
            "<p t:with=\"n=n + 10\"><b t:if=\"n == 11\">in</b>"
                + "<i t:with=\"n=n + 1\"><b t:if=\"n == 12\">deep</b></i></p>"
                + "<b t:if=\"n == 1\">out</b>");
    assertEquals("<p><b>in</b><i><b>deep</b></i></p><b>out</b>", render(c, model("n", 1)));
  }

  @Test
  void withSeesLoopVariableAndNullLiteral() {
    Compilation c =
        compile(
            "List<String> items",
            "<i t:each=\"x : items\" t:with=\"y=x, z=null\"><b t:if=\"y == 'a'\">A</b><b t:if=\"z\">Z</b></i>");
    assertEquals("<i><b>A</b></i><i></i>", render(c, model("items", List.of("a", "b"))));
  }

  @Test
  void withVariableIsNotVisibleAfterTheElement() {
    compile("", "<p t:with=\"a=1\">.</p><b t:if=\"a == 1\">y</b>").assertError("unknown identifier 'a'");
  }

  @Test
  void withNameMustBeAnIdentifier() {
    compile("", "<p t:with=\"data-x=1\">.</p>").assertError("page.html:1:", "must be an identifier");
  }

  // ---- t:switch / t:case ----

  private static final String ROLE =
      "<div t:switch=\"role\"><p t:case=\"'admin'\">A</p><p t:case=\"'user'\">U</p>"
          + "<p t:case=\"'admin'\">A2</p><p t:case=\"*\">D</p></div>";

  @Test
  void switchFirstMatchWins() {
    Compilation c = compile("String role", ROLE);
    assertEquals("<div><p>A</p></div>", render(c, model("role", "admin")));
    assertEquals("<div><p>U</p></div>", render(c, model("role", "user")));
    assertEquals("<div><p>D</p></div>", render(c, model("role", "other")));
    assertEquals("<div><p>D</p></div>", render(c, model()));
  }

  @Test
  void switchOverEnumWithStringCases() {
    String role = "package com.example; public enum Role { ADMIN, USER }";
    Compilation c =
        compile(
            "Role role",
            "<div t:switch=\"role\"><p t:case=\"'ADMIN'\">A</p><p t:case=\"'USER'\">U</p></div>",
            "com.example.Role",
            role);
    c.assertSuccess();
    Object admin = c.enumConstant("com.example.Role", "ADMIN");
    assertEquals("<div><p>A</p></div>", render(c, model("role", admin)));
    assertEquals("<div></div>", render(c, model()));

    compile(
            "Role role",
            "<div t:switch=\"role\"><p t:case=\"'NOPE'\">A</p></div>",
            "com.example.Role",
            role)
        .assertError("page.html:1:", "NOPE");
  }

  @Test
  void nestedSwitchesUseTheNearest() {
    Compilation c =
        compile(
            "String a, String b",
            "<div t:switch=\"a\"><p t:case=\"'x'\"><i t:switch=\"b\"><b t:case=\"'y'\">Y</b>"
                + "<b t:case=\"*\">nb</b></i></p><p t:case=\"*\">na</p></div>");
    assertEquals("<div><p><i><b>Y</b></i></p></div>", render(c, model("a", "x", "b", "y")));
    assertEquals("<div><p><i><b>nb</b></i></p></div>", render(c, model("a", "x", "b", "z")));
    assertEquals("<div><p>na</p></div>", render(c, model("a", "q")));
  }

  @Test
  void caseOutsideSwitchIsAnError() {
    compile("String a", "<p t:case=\"'x'\">x</p>").assertError("page.html:1:", "t:case must be inside");
    compile("String a", "<div t:switch=\"a\"></div><p t:case=\"*\">x</p>").assertError("t:case must be inside");
    compile("String a", "<p t:switch=\"a\" t:case=\"'x'\">x</p>").assertError("t:case must be inside");
  }

  // ---- t:remove ----

  @Test
  void removeVariants() {
    Compilation c =
        compile(
            "",
            "<p t:remove=\"all\">a<b>b</b></p>|<p t:remove=\"body\">a<b>b</b></p>|"
                + "<p t:remove=\"tag\">a<b>b</b></p>|<p t:remove=\"none\">a<b>b</b></p>|"
                + "<ul t:remove=\"all-but-first\">\n  <li>1</li>\n  <li>2</li>\n  <li>3</li>\n</ul>");
    assertEquals(
        "|<p></p>|a<b>b</b>|<p>a<b>b</b></p>|<ul>\n  <li>1</li>\n  \n  \n</ul>", render(c, model()));
  }

  @Test
  void removeWithLoopAndCondition() {
    Compilation c =
        compile(
            "List<String> items",
            "<i t:each=\"x : items\" t:remove=\"tag\">" + ABC + "</i><b t:if=\"items\" t:remove=\"all\">gone</b>");
    assertEquals("<b>A</b><b>B</b>", render(c, model("items", Arrays.asList("a", "b"))));
  }

  @Test
  void removeNeedsALiteral() {
    compile("String x", "<p t:remove=\"${x}\">a</p>").assertError("page.html:1:", "t:remove must be one of");
    compile("", "<p t:remove=\"everything\">a</p>").assertError("t:remove must be one of");
  }
}
