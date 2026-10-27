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
 * The data-prelude rule (security-architecture.md §5.4, #332). In a {@code <script>}, {@code [[...]]}
 * writes a complete JSON value with its own quotes, so it goes only where a JavaScript expression
 * goes: inside a string literal the value would close the string and run as code (#281), and inside
 * a comment the page would never use it, as in Thymeleaf's natural-template
 * {@code /*[[${x}]]*}{@code /} (#315). To find strings and comments exactly, the code before the last
 * value may use only tokens a lexer reads without context. A {@code /} (division or regex), a
 * backtick (template literal), {@code -->}, {@code #!}, {@code <!} (which a value or code can
 * complete to {@code <!--}), a backslash line continuation and U+2028/U+2029 there are build
 * errors. After the last value anything goes.
 *
 * <p>The lists keep #290's cases. Its regex and "unsure" cases, the template-literal cases (both
 * rejected and allowed) and the allowed cases with a {@code /} before the last value are now
 * rejected at the {@code /} or backtick: the rule no longer decides regex vs division, so it doesn't
 * accept a {@code /} it read right either.
 */
class ScriptQuotedInlineTest {
  private static final String PARAMS = "<div t:params=\"String x, int a, int b\">\n";

  /** Scripts with a {@code [[...]]} inside a string literal; the last {@code [[} is the one at fault. */
  static final List<String> REJECTED =
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
  static final List<String> REJECTED_COMMENT =
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
  static final List<String> REJECTED_SLASH =
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
  static final List<String> REJECTED_BACKTICK =
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
  static final List<String> REJECTED_ANNEX_B =
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
  static final List<String> REJECTED_CONTINUATION =
      List.of(
          "<script>var a = \"line \\\n[[x]]\";</script>",
          "<script>var a = \"line \\\r\n[[x]]\";</script>",
          "<script>var a = 'line \\\nmore'; var b = [[x]];</script>");

  /** Scripts with U+2028 or U+2029 before the last value, in code, a string or a comment. */
  static final List<String> REJECTED_SEPARATOR =
      List.of(
          "<script>// c\u2028 var a = \"[[x]]\";</script>",
          "<script>// c\u2029 var a = \"[[x]]\";</script>",
          "<script>var a = \"x\u2028 [[x]]\";</script>",
          "<script>var a = \"x\u2028\"; var b = [[x]];</script>",
          "<script>let p = 1, q\u2028var y = [[x]];</script>");

  static final List<String> ALLOWED =
      List.of(
          "<script>var a = [[x]];</script>",
          "<script>var a = \"a\" + [[x]] + \"b\";</script>",
          "<script>var a = \"done\"; var b = [[x]];</script>",
          "<script>var a = 'it\\'s'; var b = [[x]];</script>",
          "<script>var a = \"q\\\"q\"; var b = [[x]];</script>",
          "<script>var a = \"a\\\\\"; var b = [[x]];</script>",
          "<script>var s = \"/\" + '`' + '-->'; var b = [[x]];</script>",
          "<script>// \"c\" / ` -->\n var b = [[x]];</script>",
          "<script>/* \"c\" / ` */ var b = [[x]];</script>",
          "<script>/*/ \"c\" */ var b = [[x]];</script>",
          "<script>var o = { a: 1 }; var b = [[x]];</script>",
          "<script>if (a) { b(); } // done\nvar c = [[x]];</script>",
          "<script>var q = [[a]] * [[b]] - 1.5e3 % 2;</script>",
          "<script type=\"application/json\">{\"a\": [[x]]}</script>",
          // After the last value anything goes.
          "<script>var b = [[x]]; var n = a / 2; var r = /\"/.test(s); var t = `${b}` + '\u2028';</script>",
          "<script>var d = [[x]];\n$(function () { var s = d.split(/[,/]/); });</script>",
          "<script>var a = [[a]], b = [[x]]; f(a / b, `\"`);</script>");

  private static Compilation compile(String markup) {
    return Compilation.builder().template("page.html", PARAMS + markup + "\n</div>").compile();
  }

  @ParameterizedTest
  @FieldSource("REJECTED")
  void quotedInlineFailsToBuild(String markup) {
    assertOneErrorAtLastInline(markup, "is inside a quoted JavaScript string");
  }

  @ParameterizedTest
  @FieldSource("REJECTED_COMMENT")
  void inlineInCommentFailsToBuild(String markup) {
    assertOneErrorAtLastInline(markup, "is inside a JavaScript comment");
  }

  @ParameterizedTest
  @FieldSource("REJECTED_SLASH")
  void slashBeforeTheLastInlineFailsToBuild(String markup) {
    assertOneError(markup, "a '/' (a division or a regular expression) before the last [[...]]");
  }

  @ParameterizedTest
  @FieldSource("REJECTED_BACKTICK")
  void backtickBeforeTheLastInlineFailsToBuild(String markup) {
    assertOneError(markup, "a backtick (a template literal) before the last [[...]]");
  }

  @ParameterizedTest
  @FieldSource("REJECTED_ANNEX_B")
  void annexBCommentBeforeTheLastInlineFailsToBuild(String markup) {
    assertOneError(markup, "comment) before the last [[...]]");
  }

  @ParameterizedTest
  @FieldSource("REJECTED_CONTINUATION")
  void lineContinuationBeforeTheLastInlineFailsToBuild(String markup) {
    assertOneError(markup, "a backslash line continuation before the last [[...]]");
  }

  @ParameterizedTest
  @FieldSource("REJECTED_SEPARATOR")
  void separatorBeforeTheLastInlineFailsToBuild(String markup) {
    assertOneError(markup, "SEPARATOR) before the last [[...]]");
  }

  private static void assertOneErrorAtLastInline(String markup, String fragment) {
    int line = 2 + (int) markup.substring(0, markup.lastIndexOf("[[")).chars().filter(ch -> ch == '\n').count();
    assertOneError(markup, "page.html:" + line + ":", fragment);
  }

  private static void assertOneError(String markup, String... fragments) {
    Compilation c = compile(markup);
    c.assertError(fragments);
    assertEquals(1, c.errors().size(), c.errors().toString());
    assertNull(c.generatedSource("tessera.generated.PageTemplate"), "nothing may be generated");
  }

  @Test
  void tokenErrorPointsAtTheTokenAndGivesTheFix() {
    Compilation c = compile("<script>\nvar o = {}\n/ 2; var a = [[x]];</script>");
    c.assertError(
        "page.html:4:1", "move the [[...]] above the code", "<script type=\"application/json\">", "JSON.parse");
    assertEquals(1, c.errors().size(), c.errors().toString());
    compile("<script>var t = `${[[x]]}`;</script>").assertError("page.html:2:17", "backtick");
    compile("<script>\n  -->\nvar b = [[x]];</script>").assertError("page.html:3:3", "'-->'");
    compile("<script>#!\nvar b = [[x]];</script>").assertError("page.html:2:9", "'#!'");
    compile("<script>var a = 'a\\\nb', b = [[x]];</script>").assertError("page.html:2:19", "continuation");
    compile("<script>var a = 1,\u2029b = [[x]];</script>").assertError("page.html:2:", "U+2029");
  }

  @ParameterizedTest
  @FieldSource("ALLOWED")
  void unquotedInlineBuilds(String markup) {
    compile(markup).assertSuccess();
  }

  @Test
  void errorPointsAtTheExpressionAndNamesTheQuote() {
    Compilation c =
        Compilation.builder()
            .template("page.html", "<div t:params=\"String x\">\n<script>\n  var a = \"[[x]]\";\n</script></div>")
            .compile();
    c.assertError("page.html:3:14", "(\"...\")", "remove the quotes, e.g. var a = [[...]]; or \"Hello \" + [[...]]");
    assertEquals(1, c.errors().size(), c.errors().toString());
    compile("<script>var a = '[[x]]';</script>").assertError("('...')");
  }

  @Test
  void commentErrorPointsAtTheExpressionAndNamesTheOpener() {
    Compilation c =
        Compilation.builder()
            .template("page.html", "<div t:params=\"String x\">\n<script>\n  var a = /*[[x]]*/ 'd';\n</script></div>")
            .compile();
    c.assertError("page.html:3:15", "(/* */)", "becomes var a = [[${x}]];");
    assertEquals(1, c.errors().size(), c.errors().toString());
    compile("<script>// [[x]]\n</script>").assertError("(//)");
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
        "<div><script>var a = \"+alert(1)+\";</script></div>", c.render("page", Map.of("x", "+alert(1)+")));
    assertEquals(
        "<div><script>var a = \"\\u003c\\/script\\u003e\\u003cscript\\u003ealert(1)\\u003c\\/script\\u003e\";</script></div>",
        c.render("page", Map.of("x", "</script><script>alert(1)</script>")));
  }
}
