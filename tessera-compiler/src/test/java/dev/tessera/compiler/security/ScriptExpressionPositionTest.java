package dev.tessera.compiler.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.tessera.compiler.testing.Compilation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

/**
 * Each {@code [[...]]} in a {@code <script>} must be in expression position (security-architecture.md
 * §5.4): the static code uses its value as an operand, and the value can't change what that code
 * means. Before this rule {@code globalThis[[${l}]]([[${s}]])} built, and with
 * {@code l = ["eval"]} it rendered {@code globalThis["eval"]("...")}, which runs {@code s} as code.
 */
class ScriptExpressionPositionTest {
  private static final String PARAMS =
      "<div t:params=\"String s, int n, boolean b, java.util.List<String> l, java.util.Map<String, String> m\">\n";

  /** Scripts whose last {@code [[...]]} is not in expression position. */
  static final List<String> REJECTED =
      List.of(
          // The value picks a property of the code before it, or is called.
          "<script>globalThis[[${l}]]([[${s}]]);</script>",
          "<script>var f = globalThis\n[[${l}]]([[${s}]])</script>",
          "<script>var f = globalThis /* c */ // d\n  [[${l}]]</script>",
          "<script>var o = {}; o[[${l}]] = [[${m}]];</script>",
          "<script>var a = Math.[[${b}]];</script>",
          "<script>var a = {}.[[${b}]];</script>",
          "<script>var a = o?.[[${l}]];</script>",
          "<script>var a = f()[[${l}]];</script>",
          "<script>var a = x[0][[${l}]];</script>",
          "<script>var a = 1\n[[${l}]].forEach(function () {});</script>",
          "<script>if (a) { } [[${l}]]</script>",
          "<script>var a = this[[${l}]];</script>",
          "<script>var a = null[[${l}]];</script>",
          "<script>var a = o.return [[${l}]];</script>",
          "<script>class A { #x; m() { return this.#[[${n}]]; } }</script>",
          // A contextual keyword is also an identifier.
          "<script>var of = globalThis; of [[${l}]]([[${s}]]);</script>",
          "<script>var await = globalThis; await [[${l}]];</script>",
          "<script>var yield = globalThis; yield [[${l}]];</script>",
          "<script>var of = globalThis; for (var x of of instanceof of [[${l}]]?.([[${s}]])) {}</script>",
          "<script>for (x of a instanceof of [[${l}]]) {}</script>",
          "<script>var of = globalThis; for (var c of class extends of [[${l}]]?.([[${s}]]) {}) {}</script>",
          "<script>var of = globalThis; function* g() { for (var x of yield of [[${l}]]) {} }</script>",
          "<script>async function g() { for (x of await of [[${l}]]) {} }</script>",
          "<script>for (x of new of [[${l}]]) {}</script>",
          "<script>for (x of (a) of [[${l}]]) {}</script>",
          "<script>for (x of o.p of [[${l}]]) {}</script>",
          // Merges with the token before it.
          "<script>var a = x[[${n}]];</script>",
          "<script>var x5 = 1; var a = x[[${n}]];</script>",
          "<script>var a = 1[[${n}]];</script>",
          "<script>var a = 1.[[${n}]];</script>",
          "<script>var a = 0x[[${n}]];</script>",
          "<script>var a = 1e+[[${n}]];</script>",
          "<script>var a = \"\"[[${s}]];</script>",
          "<script>var a = '\\''[[${s}]];</script>",
          "<script>var \\u00[[${n}]] = 0;</script>",
          "<script>function f() { return[[${n}]]; }</script>",
          "<script>var t = typeof[[${b}]];</script>",
          "<script>switch (x) { case[[${n}]]: f(); }</script>",
          "<script>var a = b -[[${n}]];</script>",
          "<script>var a = [[${n}]][[${n}]];</script>",
          "<script>var a = [[${n}]]\n[[${l}]];</script>",
          "<script>var a = b++[[${n}]];</script>",
          "<script>var a = () => [[${m}]];</script>",
          // Merges with the token after it.
          "<script>var a = [[${n}]]e3;</script>",
          "<script>var a = [[${n}]]n;</script>",
          "<script>var a = [[${n}]]x;</script>",
          "<script>var a = [[${n}]].5;</script>",
          "<script>var a = [[${n}]].e3;</script>",
          "<script>var a = [[${n}]].\nfoo();</script>",
          "<script>var a = [[${n}]]. foo;</script>",
          "<script>var a = [[${n}]]..toString();</script>",
          "<script>var a = [[${b}]]\u00e9;</script>",
          // Object keys, labels and class members.
          "<script>var o = {[[${s}]]: 1};</script>",
          "<script>var o = {a: 1, [[${s}]]: [[${n}]]};</script>",
          "<script>var o = {a: 1,\n  [[${s}]]\n  : 2};</script>",
          "<script>var o = {a: 1, [[${s}]]() {}};</script>",
          "<script>class A { *[[${s}]]() {} }</script>",
          "<script>var t = [[${s}]]`x`;</script>",
          "<script>var a = [[${l}]]?.([[${s}]]);</script>",
          "<script>var a = [[${l}]] ?.\n ([[${s}]]);</script>",
          "<script>var a = [[${l}]]?.`x`;</script>",
          "<script>var a = [[${l}]]?. /* c */ `x`;</script>",
          // A word, then a line break: a reserved word is also a class field name, and the line break ends the field.
          "<script>class A { in\n[[${l}]] = handler }</script>",
          "<script>class A { typeof /*\n*/ [[${l}]] = handler }</script>",
          "<script>class A { return // c\n[[${l}]] = handler }</script>",
          "<script>class A { new\r\n[[${l}]] = handler }</script>",
          "<script>class A { static\n[[${l}]] = handler }</script>",
          "<script>class A { get\n[[${l}]]() {} }</script>",
          "<script>function f() { return\n[[${l}]]; }</script>",
          // A whole statement with code after it: a string is a directive.
          "<script>[[${s}]]; with (o) { f(); }</script>",
          "<script>[[${s}]]\nx = 1;</script>",
          "<script>'a'; [[${s}]]; x = 1;</script>",
          "<script>function f() { 'a'; [[${s}]]; return this; }</script>",
          "<script>class A { a = 1; [[${l}]] = f(); }</script>",
          "<script>class A { a; [[${l}]] }</script>",
          "<script>var a = 1;\n[[${l}]].forEach(f);</script>",
          "<script>[[${l}]].forEach(f);</script>",
          "<script>for (var i = 0; [[${b}]]; i++) {}</script>",
          "<script>for (var of of [[${l}]]) {}</script>",
          "<script>for (x of f(function () { return of [[${l}]]; })) {}</script>");

  /**
   * Scripts with a {@code -->} after a {@code [[...]]}: at the start of a line it is an HTML-like
   * comment, which would hide the {@code :} or {@code (} after the value from the check.
   */
  static final List<String> CLOSE_COMMENT =
      List.of(
          "<script>var o = {a: 1, [[${l}]]\n-->\n: f};</script>",
          "<script>var o = {a: 1, [[${s}]]\n-->\n() {}};</script>",
          "<script>var o = {a: 1, [[${l}]] /*\n*/ --> c\n: f};</script>",
          "<script>class A { *[[${s}]]\n-->\n() {} }</script>",
          "<script>var a = [[${s}]]; // --></script>",
          "<script>var a = [[${s}]]; f(\"-->\");</script>");

  /** Common script shapes that keep building. */
  static final List<String> ALLOWED =
      List.of(
          "<script>var a = [[${s}]];</script>",
          "<script>f([[${s}]], [[${n}]]);</script>",
          "<script>var o = {k: [[${s}]]};</script>",
          "<script>var o = {k: [[${s}]], j: [[${n}]]};</script>",
          "<script>var a = [ [[${s}]] ];</script>",
          "<script>var a = [ [[${s}]], [[${n}]] ];</script>",
          "<script>function f() { return [[${s}]]; }</script>",
          "<script>var x = c ? [[${s}]] : [[${n}]];</script>",
          "<script>if ([[${b}]]) { f(); }</script>",
          "<script>var a = 1, b = [[${n}]];\nvar c = [[${s}]];</script>",
          "<script>var start = [[${s}]].split(\".\");</script>",
          "<script>var n = [[${l}]].length;</script>",
          "<script>var v = [[${l}]][0];</script>",
          "<script>var a = - [[${n}]] + +[[${n}]] * ![[${b}]];</script>",
          "<script>var a = x - [[${n}]] >= [[${n}]] && typeof [[${s}]] === 'string';</script>",
          "<script>var a = [...[[${l}]]];</script>",
          "<script>var a = JSON.parse([[${s}]]);</script>",
          "<script>(0, f)([[${s}]]);</script>",
          "<script>for (var k in [[${m}]]) { f(k); }</script>",
          "<script>for (const x of [[${l}]]) { f(x); }</script>",
          "<script>for (const [k, v] of [[${m}]]) { f(k); }</script>",
          "<script>async function g() { for await (const x of [[${l}]]) { f(x); } }</script>",
          "<script>var a = typeof [[${s}]] + void [[${n}]];</script>",
          "<script>switch (x) { case [[${n}]]: f(); }</script>",
          "<script>var o = {a: [[${b}]] ? 1 : 2};</script>",
          "<script>var a = o[ [[${s}]] ];</script>",
          "<script>x += [[${n}]]; y ??= [[${s}]];</script>",
          "<script>if (x) f(); else [[${l}]].forEach(g);</script>",
          "<script>var a = [[${s}]] // tail\n</script>",
          "<script>var a = [[${s}]] /* tail */</script>",
          "<script>var a = [[${n}]] / 2;</script>",
          "<script>var a = [[${n}]]\nvar b = 1;</script>",
          "<script>var a = x +\n  [[${n}]];</script>",
          "<script>var a = [[${l}]]?.[0];</script>",
          "<script>var a = [[${m}]]?.k;</script>",
          "<script>for (x of [[${l}]]) { f(x); }</script>",
          "<script>for (let {a} of [[${l}]]) { f(a); }</script>",
          "<script>var a = 1; // x-->\nvar b = [[${n}]];</script>",
          // A whole statement with no code after it.
          "<script>[[${l}]]</script>",
          "<script>[[${l}]];</script>",
          "<script type=\"module\">var a = [[${s}]];</script>",
          "<script type=\"application/json\">[[${m}]]</script>",
          "<script type=\"application/json\">{\"a\": [[${s}]], \"b\": [[${l}]]}</script>",
          "<script type=\"application/ld+json\">{\"@type\": \"Person\", \"name\": [[${s}]]}</script>");

  private static Compilation compile(String markup) {
    return Compilation.builder().template("page.html", PARAMS + markup + "\n</div>").compile();
  }

  @ParameterizedTest
  @FieldSource("REJECTED")
  void valueOutsideExpressionPositionFailsToBuild(String markup) {
    Compilation c = compile(markup);
    c.assertError("must be in expression position");
    assertEquals(1, c.errors().size(), c.errors().toString());
    assertNull(c.generatedSource("tessera.generated.PageTemplate"), "nothing may be generated");
  }

  @ParameterizedTest
  @FieldSource("CLOSE_COMMENT")
  void closeCommentAfterAValueFailsToBuild(String markup) {
    Compilation c = compile(markup);
    c.assertError("'-->' after a [[...]] of a <script>");
    assertEquals(1, c.errors().size(), c.errors().toString());
    assertNull(c.generatedSource("tessera.generated.PageTemplate"), "nothing may be generated");
  }

  @Test
  void openCommentAfterAValueFailsToBuild() {
    compile("<script>var o = {a: 1, [[${l}]] <!-- c\n: f};</script>").assertError("'<!--' inside <script>");
  }

  @ParameterizedTest
  @FieldSource("ALLOWED")
  void valueInExpressionPositionBuilds(String markup) {
    compile(markup).assertSuccess();
  }

  @Test
  void evalThroughAPropertyValueNoLongerBuilds() {
    Compilation c =
        Compilation.builder()
            .template(
                "page.html",
                "<div t:params=\"java.util.List<String> l, String s\"><script>globalThis[[${l}]]([[${s}]]);</script></div>")
            .compile();
    c.assertError("page.html:1:", "must be in expression position", "the token before it is 'globalThis'");
    assertEquals(1, c.errors().size(), c.errors().toString());
    assertNull(c.generatedSource("tessera.generated.PageTemplate"), "nothing may be generated");
  }

  @Test
  void errorNamesTheTokenAndPointsAtTheValue() {
    Compilation c = compile("<script>\nvar f = globalThis\n  [[${l}]]([[${s}]]);</script>");
    c.assertError("page.html:4:5", "the token before it is 'globalThis'", "var v = [[...]];");
    compile("<script>var a = [[${n}]][[${n}]];</script>").assertError("page.html:2:", "another [[...]]");
    compile("<script>var a = 1.[[${n}]];</script>").assertError("a number");
    compile("<script>var a = Math.[[${b}]];</script>").assertError("'.'");
    compile("<script>var a = o.return [[${l}]];</script>").assertError("a property name");
    compile("<script>var a = [[${n}]]e3;</script>").assertError("followed directly by 'e'");
    compile("<script>var a = [[${n}]].5;</script>").assertError("read as its decimal point");
    compile("<script>var o = {a: 1, [[${s}]]: 2};</script>").assertError("an object key");
    compile("<script>var o = {[[${s}]]: 1};</script>").assertError("the token before it is '{'");
    compile("<script>[[${s}]]; x = 1;</script>").assertError("a directive such as \"use strict\"");
    compile("<script>for (x of a instanceof of [[${l}]]) {}</script>")
        .assertError("'of' where it is an identifier, not the keyword of a for (...) head");
    compile("<script>var a = [[${l}]]?.([[${s}]]);</script>").assertError("followed by '?.' and '('");
    compile("<script>class A { in\n[[${l}]] = handler }</script>")
        .assertError("page.html:3:3", "a line break is between it and 'in'");
    compile("<script>var o = {a: 1, [[${l}]]\n-->\n: f};</script>").assertError("page.html:3:1", "'-->' after");
  }

  @Test
  void allowedValueRendersAsOneOperand() {
    Compilation c =
        Compilation.builder()
            .template("page.html", "<div t:params=\"java.util.List<String> l\"><script>var a = [[${l}]];</script></div>")
            .compile()
            .assertSuccess();
    assertEquals("<div><script>var a = [\"eval\"];</script></div>", c.render("page", Map.of("l", List.of("eval"))));
  }
}
