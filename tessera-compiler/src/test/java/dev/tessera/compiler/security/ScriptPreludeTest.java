package dev.tessera.compiler.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.tessera.compiler.testing.Compilation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Each {@code [[...]]} in a JavaScript {@code <script>} must be the whole value of a declaration
 * {@code const|let|var x = [[...]];} at the top of the script (security-architecture.md §5.4).
 * Before #509, {@code globalThis[[${l}]]([[${s}]])} built, and with {@code l = ["eval"]} it
 * rendered {@code globalThis["eval"]("...")}, which runs {@code s} as code. The lists below are the
 * #509 lexer's cases: every one is now rejected by the one grammar rule.
 */
class ScriptPreludeTest {
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
          "<script>class A { *[[${s}]]\n-->\n() {} }</script>");

  /** Old expression-position cases that the prelude rule now rejects: the value is not a declaration at the top. */
  static final List<String> NOT_PRELUDE =
      List.of(
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
          "<script>[[${l}]]</script>",
          "<script>[[${l}]];</script>");

  /** Prelude declarations, and JSON scripts (not JavaScript, so not held to the prelude). */
  static final List<String> ALLOWED =
      List.of(
          "<script>var a = [[${s}]];</script>",
          "<script type=\"module\">var a = [[${s}]];</script>",
          "<script type=\"application/json\">[[${m}]]</script>",
          "<script type=\"application/json\">{\"a\": [[${s}]], \"b\": [[${l}]]}</script>",
          "<script type=\"application/ld+json\">{\"@type\": \"Person\", \"name\": [[${s}]]}</script>",
          "<script>const a = [[${s}]];</script>",
          "<script>\n  // data\n  let a = [[${s}]];\n  var b=[[${n}]] ;\n\n  f(a, b); var c = a / 2; `${b}`;</script>",
          "<script>var a = [[${s}]]; // -->\n</script>",
          "<script>var a = [[${s}]]; f(\"-->\");</script>",
          "<script>var a = [[${l}]];\nclass A { in\n[a] = 1 }</script>");

  private static Compilation compile(String markup) {
    return Compilation.builder().template("page.html", PARAMS + markup + "\n</div>").compile();
  }

  @ParameterizedTest
  @FieldSource({"REJECTED", "NOT_PRELUDE", "CLOSE_COMMENT"})
  void valueOutsideThePreludeFailsToBuild(String markup) {
    Compilation c = compile(markup);
    c.assertError("only allowed as the whole value of a declaration");
    assertEquals(1, c.errors().size(), c.errors().toString());
    assertNull(c.generatedSource("tessera.generated.PageTemplate"), "nothing may be generated");
  }

  @ParameterizedTest
  @FieldSource("ALLOWED")
  void preludeDeclarationBuilds(String markup) {
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
    c.assertError("page.html:1:", "only allowed as the whole value of a declaration", "const name = [[...]];", "application/json");
    assertEquals(1, c.errors().size(), c.errors().toString());
    assertNull(c.generatedSource("tessera.generated.PageTemplate"), "nothing may be generated");
  }

  @Test
  void errorPointsAtTheFirstValueOutsideThePrelude() {
    compile("<script>\nvar a = [[${n}]];\nf();\n  var b = [[${l}]];</script>").assertError("page.html:5:13", "only allowed as the whole value of a declaration");
    compile("<script>\nvar f = globalThis\n  [[${l}]]([[${s}]]);</script>").assertError("page.html:4:5");
    // A value in a line comment before the prelude ends it.
    compile("<script>// [[${s}]]\nvar a = [[${n}]];</script>").assertError("page.html:2:14");
    compile("<script>var a = /*[[${s}]]*/ 'd';</script>").assertError("page.html:2:");
    compile("<script>var a = [[${s}]]</script>").assertError("page.html:2:");
    compile("<script>a = [[${s}]];</script>").assertError("page.html:2:");
    compile("<script>var a = [[${s}]], b = 1;</script>").assertError("page.html:2:");
    compile("<script>var a\u2028= [[${s}]];</script>").assertError("page.html:2:");
  }

  @Test
  void keywordNeedsASpaceAfterIt() {
    // An assignment to a global named constx, not a declaration.
    compile("<script>constx = [[${s}]];</script>").assertError("page.html:2:", "only allowed as the whole value");
    compile("<script>varx = [[${s}]];</script>").assertError("page.html:2:", "only allowed as the whole value");
  }

  @ParameterizedTest
  @ValueSource(strings = {"if", "in", "let", "yield", "await", "this", "null", "class"})
  void reservedWordIsNotADeclaredName(String name) {
    compile("<script>var " + name + " = [[${s}]];</script>").assertError("page.html:2:", "only allowed as the whole value");
    compile("<script>var " + name + "x = [[${s}]];</script>").assertSuccess();
  }

  @Test
  void textOnAJavaScriptScriptFailsToBuild() {
    for (String script : new String[] {"<script t:text=\"${s}\"></script>", "<script type=\"module\" t:text=\"${l}\"></script>"}) {
      Compilation c = compile(script);
      c.assertError("page.html:2:", "t:text is not allowed on a JavaScript <script>", "const name = [[...]];", "application/json");
      assertNull(c.generatedSource("tessera.generated.PageTemplate"), "nothing may be generated");
    }
    compile("<script t:utext=\"${s}\"></script>")
        .assertError("page.html:2:", "t:utext is only allowed on elements whose content is HTML text", "const name = [[...]];");
    // A JSON script takes a whole value.
    compile("<script type=\"application/json\" t:text=\"${m}\"></script>").assertSuccess();
  }

  @Test
  void jsonScriptValueIsOutsideStringsAndComments() {
    compile("<script type=\"application/json\">{\"a\": \"x[[${s}]]\"}</script>")
        .assertError("page.html:2:", "inside a quoted string of a JSON <script>");
    compile("<script type=\"application/json\">{\"a\": \"\\\\\\\"\", \"b\": \"[[${s}]]\"}</script>")
        .assertError("page.html:2:", "inside a quoted string of a JSON <script>");
    compile("<script type=\"application/json\">// c\n[[${s}]]</script>").assertError("page.html:2:", "JSON has no comments");
    compile("<script type=\"application/json\">{\"a/b\": [[${s}]]}</script>").assertSuccess();
  }

  @Test
  void allowedValueRendersAsOneOperand() {
    Compilation c =
        Compilation.builder()
            .template("page.html", "<div t:params=\"java.util.List<String> l\"><script>var a = [[${l}]];</script></div>")
            .compile()
            .assertSuccess();
    assertEquals("<div><script>var a = ([\"eval\"]);</script></div>", c.render("page", Map.of("l", List.of("eval"))));
  }
}
