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
 * In a {@code <script>}, {@code [[...]]} writes a complete JSON value with its own quotes, so
 * inside a JavaScript string literal the value would close the string and run as code (#281).
 * That is a build error; comments, regex literals and {@code ${...}} code in template literals
 * keep compiling.
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
          "<script>var a = `${\"[[x]]\"}`;</script>",
          "<script>var r = /x/; var a = \"[[x]]\";</script>",
          "<script>var n = 4 / 2; var a = \"[[x]]\";</script>",
          // A regex after a keyword holds a quote that must not open a string.
          "<script>function f(s) { return /\"/.test(s) ? \"[[x]]\" : 0; }</script>",
          // "/*/" opens a block comment and does not close it.
          "<script>/*/ */ var a = \"[[x]]\";</script>",
          "<script>var a = \"[[x]]\"; // after\n</script>",
          // A backslash before a line break continues the string (also before CR LF).
          "<script>var a = \"line \\\n[[x]]\";</script>",
          "<script>var a = \"line \\\r\n[[x]]\";</script>",
          // CR, LS and PS end a line comment just like LF.
          "<script>// c\r var a = \"[[x]]\";</script>",
          "<script>// c\u2028 var a = \"[[x]]\";</script>",
          "<script>// c\u2029 var a = \"[[x]]\";</script>",
          // LS and PS do not end a string literal (ES2019).
          "<script>var a = \"x\u2028 [[x]]\";</script>",
          // An inlined value is an expression: the '/' after it is division, not a regex.
          "<script>var q = [[a]] / \"[[x]]\".length;</script>",
          "<script>var r = /[[x]]/; var a = \"[[x]]\";</script>",
          // After an identifier '/' is division, so "/; [[x]]" is a string.
          "<script>var z = y /\"/; [[x]]\";</script>",
          // Template-literal text: the value is inert there, but arrives with its own quotes.
          "<script>var t = `[[x]]`;</script>",
          "<script>var t = `a ${1} [[x]]`;</script>");

  static final List<String> ALLOWED =
      List.of(
          "<script>var a = [[x]];</script>",
          "<script>var a = \"a\" + [[x]] + \"b\";</script>",
          "<script>var a = \"done\"; var b = [[x]];</script>",
          "<script>var a = 'it\\'s'; var b = [[x]];</script>",
          "<script>var a = \"q\\\"q\"; var b = [[x]];</script>",
          "<script>var r = /\"/; var b = [[x]];</script>",
          "<script>var r = /[\"]/; var b = [[x]];</script>",
          "<script>var r = /[/\"]/; var b = [[x]];</script>",
          "<script>var n = a / 2; var b = [[x]];</script>",
          "<script>var q = 4 / 2; var s = \"q\"; var b = [[x]];</script>",
          "<script>var q = [[a]] / [[b]];</script>",
          "<script>var q = [[a]] / 2; var s = \"/\"; var b = [[x]];</script>",
          "<script>function f() { return /\"/.test(\"a\"); } var b = [[x]];</script>",
          "<script>// \"c\"\n var b = [[x]];</script>",
          "<script>/* \"c\" */ var b = [[x]];</script>",
          "<script>// \"[[x]]\"\n</script>",
          "<script>/* \"[[x]]\" */</script>",
          "<script>/*/ \"c\" */ var b = [[x]];</script>",
          "<script>var t = `\"`; var b = [[x]];</script>",
          "<script>var t = `a ${\"}\"} b`; var c = [[x]];</script>",
          "<script>var t = `a ${ {k: \"v\"}.k } b`; var c = [[x]];</script>",
          "<script>var t = `\\${`; var b = [[x]];</script>",
          "<script>var t = `${[[x]]}`;</script>",
          "<script>var t = `a ${`b ${[[x]]}`}`;</script>",
          "<script>var r = /[[x]]/; var a = [[x]];</script>",
          "<script>/*[[x]]*/</script>",
          "<script>// [[x]]\n</script>",
          "<script>var a = \"x\u2028\"; var b = [[x]];</script>",
          "<script type=\"application/json\">{\"a\": [[x]]}</script>");

  private static Compilation compile(String markup) {
    return Compilation.builder().template("page.html", PARAMS + markup + "\n</div>").compile();
  }

  @ParameterizedTest
  @FieldSource("REJECTED")
  void quotedInlineFailsToBuild(String markup) {
    Compilation c = compile(markup);
    int line = 2 + (int) markup.substring(0, markup.lastIndexOf("[[")).chars().filter(ch -> ch == '\n').count();
    c.assertError("page.html:" + line + ":", "is inside a quoted JavaScript string");
    assertEquals(1, c.errors().size(), c.errors().toString());
    assertNull(c.generatedSource("tessera.generated.PageTemplate"), "nothing may be generated");
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
    c.assertError("page.html:3:14", "(\"...\")", "remove the quotes, e.g. var a = [[...]];");
    assertEquals(1, c.errors().size(), c.errors().toString());
    compile("<script>var a = '[[x]]';</script>").assertError("('...')");
    compile("<script>var a = `[[x]]`;</script>").assertError("(`...`)");
  }

  @Test
  void rawInlineInAStringKeepsItsOwnError() {
    compile("<script>var a = \"[(x)]\";</script>").assertError("page.html:2:", "[(...)] is only allowed in element text");
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
