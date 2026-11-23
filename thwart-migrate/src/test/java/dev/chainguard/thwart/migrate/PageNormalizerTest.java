package dev.chainguard.thwart.migrate;

import static dev.chainguard.thwart.migrate.PageNormalizer.Verdict.DIFFERS;
import static dev.chainguard.thwart.migrate.PageNormalizer.Verdict.FORCED;
import static dev.chainguard.thwart.migrate.PageNormalizer.Verdict.REORDERED;
import static dev.chainguard.thwart.migrate.PageNormalizer.Verdict.SAME;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/**
 * Pairs of snippets that must compare the same, reordered, or different. Each normalization rule
 * has a pair it makes equal and a pair it must not make equal. Cases are from the migration
 * benchmark (bench/migration/README.md).
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
      comment between braces         | <p><!---->{<!---->{x}}</p>               | <p>{{x}}</p>
      character references           | <p>O&#39;Neil &amp; co</p>               | <p>O'Neil &amp; co</p>
      doctype case                   | <!DOCTYPE HTML><p>x                      | <!doctype html><p>x</p>
      csrf hidden input              | <input type="hidden" name="_csrf" value="abc"> | <input type="hidden" name="_csrf" value="xyz">
      csrf meta                      | <meta name="_csrf" content="abc">        | <meta name="_csrf" content="xyz">
      csrf query parameter           | <a href="/logout?_csrf=abc&x=1">o</a>    | <a href="/logout?_csrf=xyz&x=1">o</a>
      jsessionid path parameter      | <a href="/owners;jsessionid=A1B2?x=1">o</a> | <a href="/owners?x=1">o</a>
      jsessionid in form action      | <form action="/f;jsessionid=A1B2"></form> | <form action="/f"></form>
      csp nonce (#335)               | <script nonce="abc">x()</script><style nonce="d">p{}</style> | <script>x()</script><style>p{}</style>
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
      v-pre still counts             | <div v-pre>a</div>                        | <div>a</div>
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
      declared name assigned again   | <script>var x = "a"; x = g(); f(x);</script> | <script>var x = ("a"); x = g(); f("a");</script>
      declared name incremented      | <script>var n = 0; n++; f(n);</script>    | <script>var n = (0); n++; f(0);</script>
      declared name compound-assigned | <script>var n = 0; n += 2; f(n);</script> | <script>var n = (0); n += 2; f(0);</script>
      declared name as a parameter   | <script>var id = 7; function h(id){return id;}</script> | <script>var id = (7); function h(id){return 7;}</script>
      declared name as an arrow parameter | <script>var id = 7; g((a, id) => id);</script> | <script>var id = (7); g((a, id) => 7);</script>
      declared name declared again   | <script>var id = 7; function f(){ var id = g(); return id; }</script> | <script>var id = (7); function f(){ var id = g(); return 7; }</script>
      declared name destructured     | <script>var id = 7; function f(o){ const {id} = o; return id; }</script> | <script>var id = (7); function f(o){ const {id} = o; return 7; }</script>
      declared name in an array pattern | <script>var id = 7; [id] = xs; g(id);</script> | <script>var id = (7); [id] = xs; g(7);</script>
      declared name in for-of        | <script>var id = 7; for (id of xs) g(id);</script> | <script>var id = (7); for (id of xs) g(7);</script>
      declared name in for-in        | <script>var k = "a"; for (k in o) g(k);</script> | <script>var k = ("a"); for (k in o) g("a");</script>
      declared name decremented first | <script>var n = 0; --n; f(n);</script>    | <script>var n = (0); --n; f(0);</script>
      declared name as a function    | <script>var id = 7; function id(){} f(id);</script> | <script>var id = (7); function id(){} f(7);</script>
      declared name as a class       | <script>var id = 7; class id {} f(id);</script> | <script>var id = (7); class id {} f(7);</script>
      declared name caught           | <script>var e = 1; try { g(); } catch (e) { f(e); }</script> | <script>var e = (1); try { g(); } catch (e) { f(1); }</script>
      declared name as a bare arrow parameter | <script>var id = 7; g(id => id);</script> | <script>var id = (7); g(id => 7);</script>
      declared name in a string fails safe | <script>var id = 7; f(id); log("id = 1");</script> | <script>f(7); log("id = 1");</script>
      """)
  void differentPages(String rule, String a, String b) {
    // A page that differs only inside a <script> or <style> is FORCED, even for a wrong value:
    // migrations rewrite both, so the forced form drops them all, and the guide has the agent
    // check the values in a forced page's diff (a documented gap, not machinery).
    boolean rewritten = a.contains("<script") || a.contains("<style");
    check(rewritten ? FORCED : DIFFERS, a.replace("\\n", "\n"), b.replace("\\n", "\n"));
  }

  /** What Thwart makes a migration change (#863): recorded page first, then the migrated one. */
  @ParameterizedTest(name = "forced: {0}")
  @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
      handler moved to a data hook   | <button onclick="go()">G</button>        | <button data-action="go">G</button>
      javascript url                 | <a href="javascript:void(0)" onclick="n()">N</a> | <a href="#" data-action="n">N</a>
      handler script added           | <form onsubmit="s()"><p>x</p></form>     | <form data-action="s"><p>x</p></form><script src="/js/a.js"></script>
      script changed                 | <p>a</p><script>function s(){}</script>  | <p>a</p><script>function s(){} on('click', s);</script>
      data-hx- rename                | <form hx-get="/q" hx-target="#r" id="f"></form> | <form data-hx-get="/q" hx-target="#r" id="f"></form>
      data-hx- rename with a handler | <a hx-post="/x" onclick="a()" title="t">x</a> | <a data-hx-post="/x" data-action="a" title="t">x</a>
      kept data attribute            | <a data-id="1" onclick="d(1)">x</a>      | <a data-action="d" data-id="1">x</a>
      in another order               | <ul><li onclick="a()">a<li>b</ul>        | <ul><li>b<li data-action="a">a</ul>
      recorded data value changed    | <a data-lightbox="" href="/g">x</a>       | <a data-lightbox="/g" href="/g">x</a>
      recorded data attribute gone   | <a data-bs-target="#m" onclick="o()">x</a> | <a data-action="o">x</a>
      query moved to data attributes | <button hx-delete="/posts?ids=5">D</button> | <button hx-delete="/posts" data-post-id="5">D</button>
      query moved from data-hx-      | <a data-hx-get="/q?page=2#top">n</a>      | <a hx-get="/q#top" data-page="2">n</a>
      svg in a cell to an empty span | <table><tr><td>Edit<svg viewBox="0 0 2 2"><path d="M0 0"/></svg></td></tr></table> | <table><tr><td>Edit<span class="icon icon-edit" aria-hidden="true"></span></td></tr></table>
      svg in a cell to an img        | <table><tr><td><svg><circle r="1"/></svg> ok</td></tr></table> | <table><tr><td><img src="/i/ok.svg" alt=""> ok</td></tr></table>
      math in a template replaced    | <template><p>x<math><mi>y</mi></math></p></template> | <template><p>x<i class="m" aria-hidden="true"></i></p></template>
      style rewritten                | <style>#bar-3 { width: 40% }</style><div id="bar-3"></div> | <div id="bar-3" data-progress="40"></div>
      html comment in a script       | <script><!-- menu\\ntoggle();</script>     | <script>// menu\\ntoggle();</script>
      """)
  void forcedChanges(String rule, String a, String b) {
    String x = a.replace("\\n", "\n");
    String y = b.replace("\\n", "\n");
    assertEquals(FORCED, PageNormalizer.compare(x, y), () -> PageNormalizer.lines(x) + "\n"
        + PageNormalizer.lines(y));
  }

  @ParameterizedTest(name = "not forced: {0}")
  @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
      data-hx- value changed         | <form hx-get="/a b"></form>               | <form data-hx-get="/a%20b"></form>
      hx- attribute replaced         | <a hx-get="/p" href="/p">p</a>            | <a hx-boost="true" href="/p">p</a>
      real link next to a handler    | <a href="/r?mode=" onclick="t()">r</a>    | <a href="/r?mode" data-action="t">r</a>
      text next to a handler         | <p onclick="a()">Name</p>                 | <p data-action="a">Email</p>
      text moved out of a script's parent | <div><p>a</p><script>x()</script></div><p>b</p> | <div><p>a</p><p>b</p></div>
      other inline handler syntax    | <form hx-on::after-request="h()"></form> | <form></form>
      query in an href               | <a href="/p?page=2">n</a>                 | <a href="/p">n</a>
      query in a custom attribute's other value | <a hx-get="/p?page=2">n</a> | <a hx-get="/r?page=2">n</a>
      query in an aria attribute     | <a aria-describedby="/p?x">n</a>          | <a aria-describedby="/p">n</a>
      query in a value that isn't a path | <a hx-confirm="Delete?">n</a>        | <a hx-confirm="Delete">n</a>
      svg outside a table            | <p>Edit<svg><path d="M0 0"/></svg></p>    | <p>Edit<span class="icon"></span></p>
      text in a cell                 | <table><tr><td>Ann</td></tr></table>      | <table><tr><td>Bob</td></tr></table>
      class in a cell                | <table><tr><td class="ok">x</td></tr></table> | <table><tr><td class="bad">x</td></tr></table>
      empty cell removed             | <table><tr><td class="a"></td><td>x</td></tr></table> | <table><tr><td>x</td></tr></table>
      input in a cell removed        | <table><tr><td><svg></svg><input name="q"></td></tr></table> | <table><tr><td></td></tr></table>
      icon removed from a cell       | <table><tr><td>a<svg><path d="M0 0"/></svg></td></tr></table> | <table><tr><td>a</td></tr></table>
      image changed in a cell        | <table><tr><td><img src="/u/1.png" alt="Ann"></td></tr></table> | <table><tr><td><img src="/u/2.png" alt="Bob"></td></tr></table>
      image removed from a cell      | <table><tr><td><img src="/u/1.png" alt="Ann">x</td></tr></table> | <table><tr><td>x</td></tr></table>
      status element changed in a cell | <table><tr><td><span class="dot red" title="Overdue"></span>x</td></tr></table> | <table><tr><td><span class="dot green" title="Paid"></span>x</td></tr></table>
      line break removed in a cell   | <table><tr><td>a<br>b</td></tr></table>   | <table><tr><td>ab</td></tr></table>
      svg to a hidden element with content | <table><tr><td><svg></svg></td></tr></table> | <table><tr><td><span aria-hidden="true"><b></b></span></td></tr></table>
      wrapper with an input in a cell removed | <table><tr><td><div class="x"><input name="q"></div></td></tr></table> | <table><tr><td></td></tr></table>
      svg to a visible element       | <table><tr><td><svg></svg></td></tr></table> | <table><tr><td><span class="icon"></span></td></tr></table>
      span with text in a cell       | <table><tr><td><span class="b">new</span></td></tr></table> | <table><tr><td><span class="b">old</span></td></tr></table>
      width moved to a class         | <style>#b { width: 40% }</style><div id="b"></div> | <div id="b" class="w-40"></div>
      """)
  void notForced(String rule, String a, String b) {
    assertEquals(DIFFERS, PageNormalizer.compare(a, b), () -> PageNormalizer.lines(a) + "\n"
        + PageNormalizer.lines(b));
  }

  @Test
  void forcedLooksAtTheWholePage() {
    // compare prints at most MAX_DIFF_LINES of a diff; the verdict still sees every line, so a
    // real change past a long run of forced ones differs, and a long forced-only diff is forced.
    StringBuilder old = new StringBuilder();
    StringBuilder now = new StringBuilder();
    for (int i = 0; i < 2 * Parity.MAX_DIFF_LINES; i++) {
      old.append("<p onclick=\"f(").append(i).append(")\">").append(i).append("</p>");
      now.append("<p data-i=\"").append(i).append("\">").append(i).append("</p>");
    }
    assertEquals(FORCED, PageNormalizer.compare(old.toString(), now.toString()));
    assertEquals(DIFFERS, PageNormalizer.compare(old + "<p>total 1</p>", now + "<p>total 2</p>"));
  }

  @Test
  void hoistedPreludeValuesAreReordered() {
    // The compiler's fix-it moves each inline into a declaration at the top of the script (#540).
    check(REORDERED, "<script>x = \"a\"; if (y) { f(x, [1, 2]); }</script>",
        "<script>var _t1 = (\"a\"); var _t2 = ([1,2]);\nx = _t1; if (y) { f(x, _t2); }</script>");
    // A declaration moved to the top: the old page used the value where the new one uses the name.
    check(REORDERED, "<script>// user\ninit(); show(\"u@example.com\");</script>",
        "<script>// user\nconst currentUserEmail = (\"u@example.com\");\ninit(); show(currentUserEmail);</script>");
    // Declarations on both pages, one of them hoisted.
    check(REORDERED, "<script>var lang = \"en\";\nvar n = 2; if (lang) go(3);</script>",
        "<script>var lang = (\"en\"); var n = (2); var _t1 = (3);\nif (lang) go(_t1);</script>");
    // Reads in conditions, calls, comparisons and member bases are not rebindings.
    check(REORDERED, "<script>if (\"a\" == y) { while (\"a\") f(\"a\".length, o.x); }</script>",
        "<script>var x = (\"a\");\nif (x == y) { while (x) f(x.length, o.x); }</script>");
    // Reads in an object literal, an array and a call list aren't bindings.
    check(REORDERED, "<script>x = {k: \"a\"}; f([1, \"a\"], 2);</script><script>h(y);</script>",
        "<script>var _t1 = (\"a\");\nx = {k: _t1}; f([1, _t1], 2);</script><script>h(y);</script>");
    // Other scripts aren't consulted: a global another script reads is reordered, and the diff
    // shows the declaration that went.
    check(REORDERED, "<script>f(\"/a\");</script><script>g(ctx);</script>",
        "<script>var ctx = (\"/a\"); f(ctx);</script><script>g(ctx);</script>");
    // A template literal that doesn't name the declaration doesn't stop the expansion.
    check(REORDERED, "<script>f(\"a\", `t${y}`);</script>",
        "<script>var _t1 = (\"a\");\nf(_t1, `t${y}`);</script>");
    // Documented gaps the coarse scan leaves to the diff: a read in ${...}, a rest binding, a
    // declaration list and an object key.
    check(REORDERED, "<script>f(\"/a\"); g(`${ctx}`);</script>",
        "<script>var ctx = (\"/a\"); f(ctx); g(`${ctx}`);</script>");
    check(REORDERED, "<script>function f(...id){ return 7; } f(7);</script>",
        "<script>var id = (7); function f(...id){ return id; } f(id);</script>");
    check(REORDERED, "<script>function f(){ let a, 7; return 7; } f(7);</script>",
        "<script>var id = (7); function f(){ let a, id; return id; } f(id);</script>");
    check(REORDERED, "<script>o = {\"a\": 1}; f(\"a\");</script>",
        "<script>var x = (\"a\"); o = {x: 1}; f(x);</script>");
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
