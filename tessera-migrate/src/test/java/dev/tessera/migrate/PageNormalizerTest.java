package dev.tessera.migrate;

import static dev.tessera.migrate.PageNormalizer.Verdict.DIFFERS;
import static dev.tessera.migrate.PageNormalizer.Verdict.REORDERED;
import static dev.tessera.migrate.PageNormalizer.Verdict.SAME;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/**
 * Pairs of snippets that must compare the same, reordered, or different. Each normalization rule
 * has a pair it makes equal and a pair it must not make equal. Cases are from the migration
 * benchmark (bench/migration/lib/crawl.py and RESULTS.md "Harness lessons").
 */
class PageNormalizerTest {

  private static void check(PageNormalizer.Verdict want, String a, String b) {
    assertEquals(want, PageNormalizer.compare(a, b), () -> a + "\n  vs\n" + b
        + "\n" + PageNormalizer.lines(a) + "\n" + PageNormalizer.lines(b));
    assertEquals(want, PageNormalizer.compare(b, a), "compare must be symmetric");
  }

  @ParameterizedTest(name = "same: {0}")
  @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
      optional end tags              | <ul><li>a<li>b</ul>                       | <ul><li>a</li><li>b</li></ul>
      implicit table structure       | <table><tr><td>1<td>2</table>            | <table><tbody><tr><td>1</td><td>2</td></tr></tbody></table>
      stray end tag                  | <div>a</div></span></div>               | <div>a</div>
      self-closing void element      | <p>a<br/>b</p>                           | <p>a<br>b</p>
      first duplicate attribute wins | <a href="/x" href="/y">l</a>             | <a href="/x">l</a>
      attribute order                | <a id="i" class="c" href="/x">l</a>      | <a href="/x" class="c" id="i">l</a>
      class token order              | <p class="b  a">x</p>                    | <p class="a b">x</p>
      attribute whitespace           | <p title=" a   b ">x</p>                 | <p title="a b">x</p>
      text whitespace                | <p>\\n  a\\n    b  </p>                 | <p>a b</p>
      whitespace between elements    | <div>\\n  <p>a</p>\\n  <p>b</p>\\n</div>| <div><p>a</p><p>b</p></div>
      boolean attribute value        | <input type="checkbox" checked="checked"> | <input type="checkbox" checked>
      boolean selected               | <option selected="selected">a</option>   | <option selected>a</option>
      empty input value              | <input name="q" value="">                | <input name="q">
      comments dropped               | <p>a<!-- note -->b</p>                   | <p>ab</p>
      braces broken in text (#248)   | <p><!---->{<!---->{x}}</p>               | <p>{{x}}</p>
      character references           | <p>O&#39;Neil &amp; co</p>               | <p>O'Neil &amp; co</p>
      doctype case                   | <!DOCTYPE HTML><p>x                      | <!doctype html><p>x</p>
      csrf hidden input              | <input type="hidden" name="_csrf" value="abc"> | <input type="hidden" name="_csrf" value="xyz">
      csrf meta                      | <meta name="_csrf" content="abc">        | <meta name="_csrf" content="xyz">
      csrf query parameter           | <a href="/logout?_csrf=abc&x=1">o</a>    | <a href="/logout?_csrf=xyz&x=1">o</a>
      jsessionid path parameter      | <a href="/owners;jsessionid=A1B2?x=1">o</a> | <a href="/owners?x=1">o</a>
      jsessionid in form action      | <form action="/f;jsessionid=A1B2"></form> | <form action="/f"></form>
      csp nonce (#335)               | <script nonce="abc">x()</script><style nonce="d">p{}</style> | <script>x()</script><style>p{}</style>
      v-pre on title, textarea (#549) | <title v-pre>t</title><textarea v-pre>x</textarea> | <title>t</title><textarea>x</textarea>
      json array spacing (#183)      | <script>const f = [ "name", "description" ];</script> | <script>const f = ["name","description"];</script>
      empty json array and object    | <script>var t = [ ]; var a = { };</script> | <script>var t = []; var a = {};</script>
      pretty-printed json object     | <script>var o = {\\n  "a": 1,\\n  "b": [ 2 ]\\n};</script> | <script>var o = {"a":1,"b":[2]};</script>
      json script type               | <script type="application/json">{ "a" : 1 }</script> | <script type="application/json">{"a":1}</script>
      apostrophe in a comment        | <script>// don't\\nvar a = [ 1 ];</script> | <script>// don't\\nvar a = [1];</script>
      json string in parentheses (#625) | <script>var pageLanguage = "en";</script> | <script>var pageLanguage = ("en");</script>
      json array in parentheses      | <script>const fields = [ "a", "b" ];</script> | <script>const fields = (["a","b"]);</script>
      json object in parentheses     | <script>var o = { "a": [ 1 ], "b": null };</script> | <script>var o = ( {"a":[1],"b":null} ) ;</script>
      negative number in parentheses | <script>var n = -1.5; f(n);</script>    | <script>var n = (-1.5); f(n);</script>
      module script in parentheses   | <script type="module">const a = true;</script> | <script type="module">const a = (true);</script>
      """)
  void sameAsABrowserSeesIt(String rule, String a, String b) {
    check(SAME, a.replace("\\n", "\n"), b.replace("\\n", "\n"));
  }

  @ParameterizedTest(name = "differs: {0}")
  @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
      element nesting                | <div><p>a</p></div><p>b</p>              | <div><p>a</p><p>b</p></div>
      second duplicate is not kept   | <a href="/x" href="/y">l</a>             | <a href="/y">l</a>
      attribute value                | <a href="/x">l</a>                       | <a href="/y">l</a>
      missing attribute              | <a href="/x" id="i">l</a>                | <a href="/x">l</a>
      class token missing            | <p class="a b">x</p>                     | <p class="a">x</p>
      words joined                   | <p>a b</p>                               | <p>ab</p>
      text changed                   | <p>Owner</p>                             | <p>Owners</p>
      nbsp is not a space            | <p>a&nbsp;b</p>                          | <p>a b</p>
      boolean attribute missing      | <input type="checkbox" checked>          | <input type="checkbox">
      non-empty input value          | <input name="q" value="x">               | <input name="q">
      empty checkbox value           | <input type="checkbox" value="">         | <input type="checkbox">
      empty radio value              | <input type="radio" value="">            | <input type="radio">
      empty option value             | <option value="">a</option>              | <option>a</option>
      other hidden input             | <input type="hidden" name="id" value="1"> | <input type="hidden" name="id" value="2">
      csrf input missing             | <form><input type="hidden" name="_csrf" value="a"></form> | <form></form>
      unescaped markup in text       | <p>O'Neil &lt;b&gt;</p>                  | <p>O'Neil <b></b></p>
      comment text is not kept       | <p>a<!-- b -->c</p>                      | <p>a b c</p>
      script content                 | <script>var a = 1;</script>              | <script>var a = 2;</script>
      whitespace in a json string    | <script>var f = [ "a b" ];</script>      | <script>var f = ["ab"];</script>
      punctuation in a json string   | <script>var f = [ "a, b: c" ];</script>  | <script>var f = ["a,b:c"];</script>
      punctuation in a quoted string | <script>var f = 'a , b';</script>        | <script>var f = 'a,b';</script>
      json value                     | <script>var f = [ "name" ];</script>     | <script>var f = ["names"];</script>
      json quote style               | <script>var f = [ 'a' ];</script>        | <script>var f = ["a"];</script>
      text next to punctuation       | <p>a, b</p>                              | <p>a,b</p>
      style is not json              | <style>p { color: red }</style>          | <style>p{color:red}</style>
      template script is not json    | <script type="text/template"><p>a, b</p></script> | <script type="text/template"><p>a,b</p></script>
      jsessionid is not the path     | <a href="/owners;jsessionid=A1">o</a>    | <a href="/owner">o</a>
      v-pre elsewhere still counts   | <div v-pre>a</div>                        | <div>a</div>
      parenthesized expression       | <script>var n = a + b;</script>           | <script>var n = (a + b);</script>
      parentheses before an operator | <script>var n = 1 + 2;</script>           | <script>var n = (1) + 2;</script>
      parentheses in a comparison    | <script>if (a == 1) f();</script>         | <script>if (a == (1)) f();</script>
      parentheses in a string        | <script>var s = "= 1;";</script>          | <script>var s = "= (1);";</script>
      other value in parentheses     | <script>var a = "x";</script>             | <script>var a = ("y");</script>
      parentheses in a template      | <script type="text/template">x = "a";</script> | <script type="text/template">x = ("a");</script>
      hoisted value changed          | <script>x = "a"; f(x);</script>           | <script>var _t1 = ("b");\\nx = _t1; f(x);</script>
      declaration after code         | <script>f(); x = "a";</script>            | <script>f(); var _t1 = ("a"); x = _t1;</script>
      declared expression            | <script>x = g();</script>                 | <script>var _t1 = (g()); x = _t1;</script>
      name inside a string           | <script>alert("a");</script>              | <script>var _t1 = ("a"); alert("_t1");</script>
      name after a dot               | <script>o.1 = 2;</script>                 | <script>var x = (1); o.x = 2;</script>
      unused declaration still counts | <script>var a = 1; x = "q";</script> | <script>var a = (2); var _t1 = ("q"); x = _t1;</script>
      """)
  void differentPages(String rule, String a, String b) {
    check(DIFFERS, a.replace("\\n", "\n"), b.replace("\\n", "\n"));
  }

  @Test
  void hoistedPreludeValuesAreReordered() {
    // tessera-migrate hoists each inline into var _tN = [[...]]; at the top of the script (#541).
    check(REORDERED, "<script>x = \"a\"; if (y) { f(x, [1, 2]); }</script>",
        "<script>var _t1 = (\"a\"); var _t2 = ([1,2]);\nx = _t1; if (y) { f(x, _t2); }</script>");
    // A declaration moved to the top: the old page used the value where the new one uses the name.
    check(REORDERED, "<script>// user\ninit(); show(\"u@example.com\");</script>",
        "<script>// user\nconst currentUserEmail = (\"u@example.com\");\ninit(); show(currentUserEmail);</script>");
    // Declarations on both pages, one of them hoisted.
    check(REORDERED, "<script>var lang = \"en\";\nvar n = 2; if (lang) go(3);</script>",
        "<script>var lang = (\"en\"); var n = (2); var _t1 = (3);\nif (lang) go(_t1);</script>");
  }

  @Test
  void childrenInAnotherOrderAreReordered() {
    // Validation messages come from a HashSet: their order is not stable between apps.
    check(REORDERED,
        "<div class=\"errors\"><p>must not be empty</p><p>numeric value out of bounds</p></div>",
        "<div class=\"errors\"><p>numeric value out of bounds</p><p>must not be empty</p></div>");
    check(REORDERED, "<ul><li>a<li>b<li>c</ul>", "<ul><li>c<li>a<li>b</ul>");
  }

  @Test
  void childrenMovedToAnotherElementDiffer() {
    check(DIFFERS, "<div><p>a</p></div><div><p>b</p><p>c</p></div>",
        "<div><p>a</p><p>b</p></div><div><p>c</p></div>");
    // Same lines, but one item lost and another duplicated: not a reordering.
    check(DIFFERS, "<ul><li>a<li>b</ul>", "<ul><li>a<li>a</ul>");
  }

  @Test
  void linesAreOneNodePerLineIndentedByDepth() {
    assertEquals(List.of(
            "<!doctype html>",
            "<html lang=\"en\">",
            "  <head>",
            "    <title>",
            "      T",
            "  <body>",
            "    <form action=\"/f\" method=\"post\">",
            "      <input name=\"_csrf\" type=\"hidden\" value=\"MASKED\">",
            "      <input checked type=\"checkbox\" value=\"\">",
            "      a &lt;b&gt; &amp; c"),
        PageNormalizer.lines("<!DOCTYPE html><html lang=en><title>T</title><form method=post action=/f>"
            + "<input type=hidden name=_csrf value=s3cr3t><input type=checkbox value='' checked=checked>"
            + " a &lt;b> &amp;\n c </form>"));
  }
}
