package dev.chainguard.thwart.compiler.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.chainguard.thwart.compiler.testing.Compilation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Each {@code [[...]]} in a JavaScript {@code <script>} must be the whole value of a declaration
 * {@code const|let|var x = [[...]];} at the top of the script (security-architecture.md §5.4, #540).
 * Before #509, {@code globalThis[[${l}]]([[${s}]])} built, and with {@code l = ["eval"]} it
 * rendered {@code globalThis["eval"]("...")}, which runs {@code s} as code. A value inside a string
 * literal would close the string and run as code (#281), and one inside a comment would never be
 * used, as in Thymeleaf's natural-template {@code /*[[${x}]]*}{@code /} (#315). The lists below
 * keep the cases of #290's, #332's and #509's lexers: every one is now rejected by the one grammar
 * rule, and after the prelude anything static goes.
 */
class ScriptPreludeTest {
  private static final String PARAMS = "<div t:params=\"String s, int n, boolean b, java.util.List<String> l,"
      + " java.util.Map<String, String> m, String x, int a\">\n";

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

  /** Scripts with a {@code [[...]]} inside a string literal; the last {@code [[} is the one at fault. */
  static final List<String> IN_STRING =
      List.of(
          "<script>var a = \"[[x]]\";</script>",
          "<script>var a = \"[[${x}]]\";</script>",
          "<script>var a = '[[x]]';</script>",
          "<script>var a = \"a [[x]] b\";</script>",
          "<script>var a = \"\\\"[[x]]\";</script>",
          "<script>var a = \"it\\'s [[x]]\";</script>",
          "<script>var a = 'a' + \"[[x]]\";</script>",
          "<script>f(\"a\", '[[x]]');</script>",
          "<script type=\"module\">var a = \"[[x]]\";</script>",
          "<script type=\"application/json\">{\"a\": \"[[x]]\"}</script>",
          "<script type=\"application/ld+json\">{\"name\": \"[[x]]\"}</script>",
          // "/*/" opens a block comment and does not close it.
          "<script>/*/ */ var a = \"[[x]]\";</script>",
          "<script>var a = \"[[x]]\"; // after\n</script>",
          // CR ends a line comment just like LF.
          "<script>// c\r var a = \"[[x]]\";</script>",
          // A value is an expression: the string after it is a string.
          "<script>var q = [[a]] + \"[[x]]\".length;</script>");

  /** Scripts with a {@code [[...]]} inside a comment; the last {@code [[} is the one at fault. */
  static final List<String> IN_COMMENT =
      List.of(
          "<script>var a = /*[[${x}]]*/ 'd';</script>",
          "<script>var n = /*[[a]]*/ 0;</script>",
          "<script>/*[[x]]*/</script>",
          "<script>/* \"[[x]]\" */</script>",
          "<script>/*/[[x]]*/</script>",
          "<script>// [[x]]\n</script>",
          "<script>// \"[[x]]\"\n</script>",
          "<script>var a = 1; // note [[x]]\n</script>",
          "<script type=\"module\">/*[[x]]*/</script>",
          "<script type=\"application/json\">/*[[x]]*/</script>");

  /**
   * Scripts with a {@code /} before the last value: a division, a regex, or a {@code [[...]]} inside
   * a regex. Most are #290's cases, which it accepted, rejected for the string or regex, or rejected
   * as unsure.
   */
  static final List<String> SLASH =
      List.of(
          // #290 regex literals with a value in them.
          "<script>var r = /[[x]]/;</script>",
          "<script>var a = [[x]]; var r = /[[x]]/;</script>",
          "<script>var r = /a[[x]]b/;</script>",
          "<script>var r = /[\"[[x]]]/;</script>",
          "<script>if (c) /[[x]]/.test(s);</script>",
          "<script>function f() { return /[[x]]/; }</script>",
          "<script>var r = /[[x]]/; var a = \"[[x]]\";</script>",
          // #290 strings after a regex or division.
          "<script>var r = /x/; var a = \"[[x]]\";</script>",
          "<script>var n = 4 / 2; var a = \"[[x]]\";</script>",
          "<script>function f(s) { return /\"/.test(s) ? \"[[x]]\" : 0; }</script>",
          "<script>var q = [[a]] / \"[[x]]\".length;</script>",
          "<script>if (true) /\"/.test(s); var a = \"[[x]]\";</script>",
          "<script>while (f(s)) /\"/.exec(s); var a = \"[[x]]\";</script>",
          "<script>for (;;) /\"/.test(s); var a = \"[[x]]\";</script>",
          "<script>if ((a)) /\"/.test(s); var a = \"[[x]]\";</script>",
          "<script>function f(s) { return\u2007/\"/.test(s) ? \"[[x]]\" : 0; }</script>",
          "<script>if\u202f(c) /\"/.test(s); var a = \"[[x]]\";</script>",
          "<script>var a = [.../\"/.exec(s) || [], \"[[x]]\"];</script>",
          "<script type=\"module\">for await (const c of s) /\"/.test(c); var y = \"[[x]]\";</script>",
          "<script type=\"module\">export default /\"/; var y = \"[[x]]\";</script>",
          "<script>class A extends /\"/.constructor {}; var y = \"[[x]]\";</script>",
          "<script>var n = o.return / \"[[x]]\".length;</script>",
          "<script>var n = o?.typeof / \"[[x]]\".length;</script>",
          "<script>var z = y /\"/; [[x]]\";</script>",
          "<script>class A { #return = 1; m() { return this.#return /\"/ + [[a]] + \"[[x]]\"; } }</script>",
          "<script>var n = \u2118return /\"/ + [[a]] + \"[[x]]\";</script>",
          "<script>var n = x\u00b7if(1) /\"/ + [[a]] + \"[[x]]\";</script>",
          // #290 "unsure" cases: a '/' it could not classify.
          "<script>var o = {} / \"[[x]]\";</script>",
          "<script>var o = {}/[[x]];</script>",
          "<script>var n = i-- / 2; var b = [[x]];</script>",
          "<script>function f() {}\n/\"/.test(s); var a = [[x]];</script>",
          "<script>for (const k of /\"/.exec(s)) {} var b = [[x]];</script>",
          "<script>for (;;) { break\n/\"/.test(s); var y = \"[[x]]\"; }</script>",
          "<script>var n = (a + b)\n  / 2; var y = [[x]];</script>",
          "<script>[[a]]\n/\"/.test(s); var y = \"[[x]]\";</script>",
          "<script>var n = 1.\nreturn /\"/.test(s) ? \"[[x]]\" : 0;</script>",
          "<script>var n = a\\u{62}return /\"/ + 1 + \"[[x]]\";</script>",
          // #290 accepted these: a '/' it read right, before the last value.
          "<script>var r = /\"/; var b = [[x]];</script>",
          "<script>var r = /[/\"]/; var b = [[x]];</script>",
          "<script>var n = a / 2; var b = [[x]];</script>",
          "<script>var q = [[a]] / [[b]];</script>",
          "<script>var r = a /[[b]]/ 2;</script>",
          "<script>var q = (a) / 2; var s = \"/\"; var b = [[x]];</script>",
          "<script>function f() { return /\"/.test(\"a\"); } var b = [[x]];</script>",
          "<script>var n = (a + b) /\n  2; var b = [[x]];</script>");

  /** Scripts with a backtick (a template literal) before the last value. */
  static final List<String> BACKTICK =
      List.of(
          "<script>var t = `[[x]]`;</script>",
          "<script>var t = `a ${1} [[x]]`;</script>",
          "<script>var a = `${\"[[x]]\"}`;</script>",
          "<script>var r = 1; var t = \"${\" + `[[x]]`;</script>",
          // #290 accepted these: a value in ${...} code, or a template literal before it.
          "<script>var t = `${[[x]]}`;</script>",
          "<script>var t = `a ${`b ${[[x]]}`}`;</script>",
          "<script>var t = `\"`; var b = [[x]];</script>",
          "<script>var t = `a ${\"}\"} b`; var c = [[x]];</script>",
          "<script>var t = `\\${`; var b = [[x]];</script>");

  /** Scripts with an Annex B comment opener before the last value. */
  static final List<String> ANNEX_B =
      List.of(
          "<script>\n--> /*\nvar a = \"[[x]]\";</script>",
          "<script>\n  /* c */ --> /*\nvar a = \"[[x]]\";</script>",
          "<script>var a = b --> \"[[x]]\";</script>",
          "<script>--> [[x]]\n</script>",
          "<script>var b = [[x]];\n--> \"[[x]]\"\n</script>",
          "<script>\n--> \"x\"\nvar b = [[x]];</script>",
          // "<!" and a value "-1" or code "--" would make "<!--" (a literal "<!--" is a markup error).
          "<script>var x = 2 <!-[[a]]\n</script>",
          "<script>var x = 2 <![[a]]\n</script>",
          "<script>var c = a <!b; var d = [[x]];</script>",
          "<script>#!/*\nvar a = \"[[x]]\";</script>",
          "<script>#![[x]]\n</script>",
          "<script>#! \"x\"\nvar b = [[x]];</script>");

  /** Scripts with a backslash line continuation in a string before the last value. */
  static final List<String> CONTINUATION =
      List.of(
          "<script>var a = \"line \\\n[[x]]\";</script>",
          "<script>var a = \"line \\\r\n[[x]]\";</script>",
          "<script>var a = 'line \\\nmore'; var b = [[x]];</script>");

  /** Scripts with U+2028 or U+2029 before the last value, in code, a string or a comment. */
  static final List<String> SEPARATOR =
      List.of(
          "<script>// c\u2028 var a = \"[[x]]\";</script>",
          "<script>// c\u2029 var a = \"[[x]]\";</script>",
          "<script>var a = \"x\u2028 [[x]]\";</script>",
          "<script>var a = \"x\u2028\"; var b = [[x]];</script>",
          "<script>let p = 1, q\u2028var y = [[x]];</script>");

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
          "<script>[[${l}]];</script>",
          // #290 and #332 shapes: code before a value, or a value inside an expression.
          "<script>var a = \"a\" + [[x]] + \"b\";</script>",
          "<script>var a = \"done\"; var b = [[x]];</script>",
          "<script>var a = 'it\\'s'; var b = [[x]];</script>",
          "<script>var a = \"q\\\"q\"; var b = [[x]];</script>",
          "<script>var a = \"a\\\\\"; var b = [[x]];</script>",
          "<script>var s = \"/\" + '`' + '-->'; var b = [[x]];</script>",
          "<script>/* \"c\" / ` */ var b = [[x]];</script>",
          "<script>/*/ \"c\" */ var b = [[x]];</script>",
          "<script>var o = { a: 1 }; var b = [[x]];</script>",
          "<script>if (a) { b(); } // done\nvar c = [[x]];</script>",
          "<script>var q = [[a]] * [[b]] - 1.5e3 % 2;</script>",
          "<script>var a = [[a]], b = [[x]]; f(a / b, `\"`);</script>");

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
          "<script>var a = [[${l}]];\nclass A { in\n[a] = 1 }</script>",
          "<script>var a = [[x]];</script>",
          "<script>// \"c\" / ` -->\n var b = [[x]];</script>",
          "<script type=\"application/json\">{\"a\": [[x]]}</script>",
          "<script>var b = [[x]]; var n = a / 2; var r = /\"/.test(s); var t = `${b}` + '\u2028';</script>",
          "<script>var d = [[x]];\n$(function () { var s = d.split(/[,/]/); });</script>");

  /**
   * The fix-it for values outside the prelude: each moved into a declaration above the first code
   * (the bench shapes), and the name used in its place.
   */
  static final List<String> MOVED_INTO_PRELUDE =
      List.of(
          "<script>\n    var a = [[${s}]];\n    var _t1 = [[${s}]];\n    var _t2 = [[${n}]];\n    var _t3 = [[${m}]];\n"
              + "    var _t4 = [[${s}]];\n    var _t5 = [[${s}]];\n    var _t6 = [[${l}]];\n"
              + "    var parts = _t1.split(\".\");\n    init(_t2, parts);\n    var o = {name: _t3, editable: true};\n"
              + "    var g = \"Hello \" + _t4 + \"!\";\n    numberValidationMessage = _t5;\n    var visits = _t6;\n"
              + "    var re = /^a$/;\n</script>",
          "<script>var _t1 = [[${s}]]; var _t2 = [[${l}]]; var n = (\"a \" + _t1).length; f(_t2);</script>",
          "<script type=\"module\">var _t1 = [[${s}]]; import { start } from \"/app.js\"; start(_t1);</script>",
          "<script>\r\n  // data\r\n  var a = [[${s}]];\r\n  var _t1 = [[${n}]];\r\n  f(a, _t1);\r\n</script>");

  private static Compilation compile(String markup) {
    return Compilation.builder().template("page.html", PARAMS + markup + "\n</div>").compile();
  }

  @ParameterizedTest
  @FieldSource({"REJECTED", "NOT_PRELUDE", "CLOSE_COMMENT", "IN_STRING", "IN_COMMENT", "SLASH", "BACKTICK", "ANNEX_B",
      "CONTINUATION", "SEPARATOR"})
  void valueOutsideThePreludeFailsToBuild(String markup) {
    Compilation c = compile(markup);
    c.assertError(markup.contains("application/") ? "of a JSON <script>" : "only allowed as the whole value of a declaration");
    assertEquals(1, c.errors().size(), c.errors().toString());
    assertNull(c.generatedSource("thwart.generated.PageTemplate"), "nothing may be generated");
  }

  @ParameterizedTest
  @FieldSource({"ALLOWED", "MOVED_INTO_PRELUDE"})
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
    assertNull(c.generatedSource("thwart.generated.PageTemplate"), "nothing may be generated");
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

  @ParameterizedTest
  @ValueSource(strings = {"var", "let", "const"})
  void locationIsNotADeclaredName(String keyword) {
    // In a classic script, var location = (json) calls window.location's setter: with
    // "javascript:alert(1)" the browser runs the value as code.
    Compilation c = compile("<script>" + keyword + " location = [[${s}]];</script>");
    c.assertError("page.html:2:", "only allowed as the whole value", "not a reserved word or location");
    assertNull(c.generatedSource("thwart.generated.PageTemplate"), "nothing may be generated");
    compile("<script type=\"module\">" + keyword + " location = [[${s}]];</script>").assertError("page.html:2:");
    compile("<script>" + keyword + " locationUrl = [[${s}]];</script>").assertSuccess();
  }

  @Test
  void textOnAJavaScriptScriptFailsToBuild() {
    for (String script : new String[] {"<script t:text=\"${s}\"></script>", "<script type=\"module\" t:text=\"${l}\"></script>"}) {
      Compilation c = compile(script);
      c.assertError("page.html:2:", "t:text is not allowed on a JavaScript <script>", "const name = [[...]];", "application/json");
      assertNull(c.generatedSource("thwart.generated.PageTemplate"), "nothing may be generated");
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

  @Test
  void errorPointsAtTheValueAndGivesTheFix() {
    Compilation c = compile("<script>\nvar o = {}\n/ 2; var a = [[x]];</script>");
    c.assertError("page.html:4:16", "const name = [[...]];", "<script type=\"application/json\">", "JSON.parse");
    assertEquals(1, c.errors().size(), c.errors().toString());
    compile("<script>var t = `${[[x]]}`;</script>").assertError("page.html:2:22", "only allowed as the whole value of a declaration");
  }

  @Test
  void quotedAndCommentedValuesPointAtTheExpression() {
    Compilation c =
        Compilation.builder()
            .template("page.html", "<div t:params=\"String x\">\n<script>\n  var a = \"[[x]]\";\n</script></div>")
            .compile();
    c.assertError("page.html:3:14", "only allowed as the whole value of a declaration");
    assertEquals(1, c.errors().size(), c.errors().toString());
    c = Compilation.builder()
            .template("page.html", "<div t:params=\"String x\">\n<script>\n  var a = /*[[x]]*/ 'd';\n</script></div>")
            .compile();
    c.assertError("page.html:3:15", "only allowed as the whole value of a declaration");
    compile("<script>// [[x]]\n</script>").assertError("only allowed as the whole value of a declaration");
  }

  @Test
  void rawInlineInAStringKeepsItsOwnError() {
    compile("<script>var a = \"[(x)]\";</script>").assertError("page.html:2:", "[(...)] is only allowed in element text");
    compile("<script>/*[(x)]*/</script>").assertError("page.html:2:", "[(...)] is only allowed in element text");
  }

  @Test
  void renderOfIntendedFormIsInert() {
    Compilation c =
        Compilation.builder()
            .template("page.html", "<div t:params=\"String x\"><script>var a = [[x]];</script></div>")
            .compile()
            .assertSuccess();
    assertEquals(
        "<div><script>var a = (\"+alert(1)+\");</script></div>", c.render("page", Map.of("x", "+alert(1)+")));
    assertEquals(
        "<div><script>var a = (\"\\u003c\\/script\\u003e\\u003cscript\\u003ealert(1)\\u003c\\/script\\u003e\");</script></div>",
        c.render("page", Map.of("x", "</script><script>alert(1)</script>")));
  }
}
