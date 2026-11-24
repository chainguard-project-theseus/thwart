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
      xhtml 1.0 transitional doctype | <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd"><p>x</p> | <!DOCTYPE html><p>x</p>
      xhtml 1.0 strict doctype       | <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd"><p>x</p> | <!DOCTYPE html><p>x</p>
      xhtml 1.1 doctype              | <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd"><p>x</p> | <!DOCTYPE html><p>x</p>
      html 4.01 doctype              | <!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01//EN" "http://www.w3.org/TR/html4/strict.dtd"><p>x</p> | <!DOCTYPE html><p>x</p>
      html 4.01 frameset doctype     | <!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Frameset//EN"><p>x</p> | <!DOCTYPE html><p>x</p>
      custom attribute to data       | <button class="addToCart" productId="1">Add</button> | <button class="addToCart" data-product-id="1">Add</button>
      hyphenated custom to data      | <div item-price="9.5">x</div>             | <div data-item-price="9.5">x</div>
      custom to data on another element | <a productid="1">x</a><b>y</b>        | <a>x</a><b data-product-id="1">y</b>
      meta itemprop to json-ld       | <span itemscope><meta itemprop="seller" content="Default store"><meta itemprop="currency" content="CAD"><b>9</b></span> | <span itemscope><script type="application/ld+json">{"@type":"Offer","seller":"Default store","priceCurrency":"CAD"}</script><b>9</b></span>
      meta itemprop number to json-ld | <meta itemprop="price" content="49.99"><p>x</p> | <script type="application/ld+json">{"price": 49.99}</script><p>x</p>
      meta itemprop url to json-ld   | <meta itemprop="url" content="/p/1"><p>x</p> | <script type="application/ld+json">{"url":"\\/p\\/1"}</script><p>x</p>
      meta itemprop escaped in json-ld | <meta itemprop="name" content="Men's shoes &amp; bags"><p>x</p> | <script type="application/ld+json">{"name":"Men\\u0027s shoes \\u0026 bags"}</script><p>x</p>
      meta itemprop kept beside json-ld | <meta itemprop="seller" content="Shop"><p>x</p> | <meta itemprop="seller" content="Shop"><script type="application/ld+json">{"seller":"Shop"}</script><p>x</p>
      two meta itemprop for two json-ld values | <meta itemprop="priceCurrency" content="CAD"><p>a</p><meta itemprop="priceCurrency" content="CAD"><p>b</p> | <script type="application/ld+json">[{"priceCurrency":"CAD"},{"priceCurrency":"CAD"}]</script><p>a</p><p>b</p>
      meta itemprop in a json-ld array | <meta itemprop="sameAs" content="https://x.org/a"><p>x</p> | <script type="application/ld+json">{"sameAs":["https://x.org/b","https://x.org/a"]}</script><p>x</p>
      hx-on removed                  | <form hx-on::after-request="h()" hx-post="/s"></form> | <form hx-post="/s"></form>
      hx-on colon form removed       | <button hx-on:htmx:before-request="c(event)">D</button> | <button>D</button>
      alpine x-on removed            | <button x-on:click="open = true">O</button> | <button data-action="open">O</button>
      hx-on kept and another removed | <a hx-on:click="a()">a</a><form hx-on::after-request="r()"></form> | <a hx-on:click="a()">a</a><form></form>
      sanitizer rel on a stored link | <div><a href="https://x.org">x</a></div>  | <div><a href="https://x.org" rel="nofollow noopener">x</a></div>
      message rel added to a rel     | <a href="/p" rel="help" target="_blank">p</a> | <a href="/p" rel="help noopener noreferrer" target="_blank">p</a>
      ugc rel on a stored link       | <a href="https://x.org">x</a>             | <a href="https://x.org" rel="nofollow ugc">x</a>
      sanitizer id prefix and link   | <h2 id="intro">I</h2><a href="#intro">i</a> | <h2 id="user-content-intro">I</h2><a href="#user-content-intro">i</a>
      css url quoted                 | <div style="background-image: url(/a/1.png)"></div> | <div style="background-image: url(&quot;/a/1.png&quot;)"></div>
      css url single quotes          | <div style="background:url( '/a/1.png' ) no-repeat"></div> | <div style="background:url(&quot;/a/1.png&quot;) no-repeat"></div>
      dialect xmlns on html          | <html lang="en" xmlns:salat="http://x.de/s"><body>a</body></html> | <html lang="en"><body>a</body></html>
      xhtml xmlns on html            | <html xmlns="http://www.w3.org/1999/xhtml"><body>a</body></html> | <html><body>a</body></html>
      csrf input added to a form     | <form method="post" hx-post="/s"><input name="q"></form> | <form method="post" hx-post="/s"><input type="hidden" name="_csrf" value="t"><input name="q"></form>
      space in a path encoded        | <a href="/c/World of Nature?id=1">w</a>  | <a href="/c/World%20of%20Nature?id=1">w</a>
      space in a query encoded       | <a href="/r?name=UUI Designer&amp;page=0">r</a> | <a href="/r?name=UUI%20Designer&amp;page=0">r</a>
      greater-than in a query encoded | <a href="/s?q=salary>1800">s</a>        | <a href="/s?q=salary%3E1800">s</a>
      quote in a query encoded       | <a href="/s?q=it's">s</a>                 | <a href="/s?q=it%27s">s</a>
      non-ascii path encoded         | <img src="/t/café.png" alt="c">           | <img src="/t/caf%C3%A9.png" alt="c">
      absolute http url encoded      | <a href="https://x.org/a b#c d">x</a>    | <a href="https://x.org/a%20b#c%20d">x</a>
      form action encoded            | <form action="/f/a b"></form>             | <form action="/f/a%20b"></form>
      data-hx- rename encoded        | <form hx-get="/a b"></form>               | <form data-hx-get="/a%20b"></form>
      data-hx- query moved and encoded | <a hx-get="/c/World of Nature?page=2">n</a> | <a data-hx-get="/c/World%20of%20Nature" data-page="2">n</a>
      csrf input kept out of an hx-delete | <form hx-delete="/c/2" method="post"></form> | <form data-hx-delete="/c/2" hx-params="not _csrf" method="post"><input type="hidden" name="_csrf" value="t"></form>
      hx-params not _csrf kept       | <form hx-delete="/c/2" hx-params="not _csrf" method="post" onsubmit="s()"><input type="hidden" name="_csrf" value="t"></form> | <form data-action="s" hx-delete="/c/2" hx-params="not _csrf" method="post"><input type="hidden" name="_csrf" value="t"></form>
      hx-vals moved to data          | <select hx-post="/price" hx-vals='{ "productId": 1}'></select> | <select data-hx-post="/price" data-product-id="1"></select>
      hx-vals negative integer       | <a hx-vals='{"d": -3}'>n</a>             | <a data-d="-3">n</a>
      hx-vals with two keys          | <a hx-get="/c" hx-vals='{"page": 2, "size": 10}'>n</a> | <a data-page="2" data-size="10" hx-get="/c">n</a>
      two hx-vals for two data       | <a hx-vals='{"id": 1}'>a</a><a hx-vals='{"id": 1}'>b</a> | <a data-id="1">a</a><a data-id="1">b</a>
      two data for hx-vals and a custom attribute | <span productid="1">x</span><button hx-vals='{"productId": 1}'>y</button> | <span data-product-id="1">x</span><button data-product-id="1">y</button>
      confirm text in a hidden child | <button hx-confirm="Remove it?" hx-post="/d">x</button> | <button hx-confirm="Remove it?" hx-post="/d">x<span class="q" hidden>Remove it?</span></button>
      localized confirm text hidden  | <button hx-confirm="Entfernen?" hx-post="/d">x</button> | <button hx-confirm="Remove?" hx-post="/d">x<span hidden>Entfernen?</span></button>
      confirm text with data-hx-confirm | <a hx-confirm="Sure?">d</a>        | <a data-hx-confirm="Sure?"><span hidden> Sure? </span>d</a>
      hidden confirm child on both pages | <button hx-confirm="Delete?" productid="1">x<span hidden>Deleting</span></button> | <button data-product-id="1" hx-confirm="Delete?">x<span hidden>Deleting</span></button>
      """)
  void forcedChanges(String rule, String a, String b) {
    String x = a.replace("\\n", "\n");
    String y = b.replace("\\n", "\n");
    assertEquals(FORCED, PageNormalizer.compare(x, y), () -> PageNormalizer.lines(x) + "\n"
        + PageNormalizer.lines(y));
  }

  @ParameterizedTest(name = "not forced: {0}")
  @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
      data-hx- value changed         | <form hx-get="/a b"></form>               | <form data-hx-get="/a%20c"></form>
      slash encoded                  | <a href="/a/b">x</a>                      | <a href="/a%2Fb">x</a>
      encoded letter                 | <a href="/a">x</a>                        | <a href="/%61">x</a>
      space in a query as a plus     | <a href="/s?q=a b">x</a>                  | <a href="/s?q=a+b">x</a>
      quote in a path encoded        | <a href="/it's">x</a>                     | <a href="/it%27s">x</a>
      mailto encoded                 | <a href="mailto:a b@x.org">x</a>          | <a href="mailto:a%20b@x.org">x</a>
      encoded text                   | <p>a b</p>                                | <p>a%20b</p>
      encoded title                  | <a title="a b">x</a>                      | <a title="a%20b">x</a>
      hx-params without a csrf input | <form hx-delete="/c/2" method="post"></form> | <form hx-delete="/c/2" hx-params="not _csrf" method="post"></form>
      hx-params on a get form        | <form hx-delete="/c/2"><input type="hidden" name="_csrf" value="t"></form> | <form hx-delete="/c/2" hx-params="not _csrf"><input type="hidden" name="_csrf" value="t"></form>
      data-hx-params not _csrf lost  | <form hx-delete="/c/2" data-hx-params="not _csrf" method="post"><input type="hidden" name="_csrf" value="t"></form> | <form hx-delete="/c/2" method="post"><input type="hidden" name="_csrf" value="t"></form>
      hx-params not _csrf lost       | <form hx-delete="/c/2" hx-params="not _csrf" method="post"><input type="hidden" name="_csrf" value="t"></form> | <form hx-delete="/c/2" method="post"><input type="hidden" name="_csrf" value="t"></form>
      other hx-params                | <form hx-delete="/c/2" method="post"></form> | <form hx-delete="/c/2" hx-params="none" method="post"><input type="hidden" name="_csrf" value="t"></form>
      hx-params changed              | <form hx-params="q" method="post"></form> | <form hx-params="not _csrf" method="post"><input type="hidden" name="_csrf" value="t"></form>
      hx-vals removed                | <a hx-get="/c" hx-vals='{"page": 2}'>n</a> | <a hx-get="/c">n</a>
      hx-vals value changed          | <a hx-get="/c" hx-vals='{"page": 2}'>n</a> | <a data-page="3" hx-get="/c">n</a>
      hx-vals one key lost           | <a hx-vals='{"page": 2, "size": 10}'>n</a> | <a data-page="2">n</a>
      hx-vals beside an old data     | <a data-id="1" hx-vals='{"id": 1}'>n</a> | <a data-id="1">n</a>
      two hx-vals for one data       | <a hx-vals='{"id": 1}'>a</a><a hx-vals='{"id": 1}'>b</a> | <a data-id="1">a</a><a>b</a>
      hx-vals as script              | <a hx-vals='js:{id: getId()}'>n</a>      | <a data-id="1">n</a>
      nested hx-vals                 | <a hx-vals='{"o": {"id": 1}}'>n</a>      | <a data-id="1">n</a>
      hx-vals with an array          | <a hx-vals='{"ids": [1]}'>n</a>          | <a data-ids="1">n</a>
      hx-vals key with an equals sign | <a hx-vals='{"a=1": 1}'>n</a>         | <a data-a="1=1">n</a>
      hx-vals negative zero to data  | <a hx-vals='{"d": -0}'>n</a>             | <a data-d="-0">n</a>
      hx-vals decimal to data        | <a hx-vals='{"price": 1.0}'>n</a>       | <a data-price="1.0">n</a>
      hx-vals exponent to data       | <a hx-vals='{"n": 1e2}'>n</a>            | <a data-n="1e2">n</a>
      hx-vals long integer to data   | <a hx-vals='{"id": 12345678901234567890}'>n</a> | <a data-id="12345678901234567890">n</a>
      hx-vals string to data         | <a hx-vals='{"q": "a b"}'>n</a>          | <a data-q="a b">n</a>
      hx-vals boolean to data        | <a hx-vals='{"f": true}'>n</a>           | <a data-f="true">n</a>
      one data for hx-vals and a custom attribute | <span productid="1">x</span><button hx-vals='{"productId": 1}'>y</button> | <span>x</span><button data-product-id="1">y</button>
      one data for a custom attribute and hx-vals | <a productid="1" hx-vals='{"productId": 1}'>n</a> | <a data-product-id="1">n</a>
      hidden confirm text differs    | <button hx-confirm="Entfernen?">x</button> | <button hx-confirm="Entfernen?">x<span hidden>Remove?</span></button>
      hidden element without a confirm | <button hx-post="/d">x</button>     | <button hx-post="/d">x<span hidden>Remove?</span></button>
      two hidden children            | <button hx-confirm="A?">x</button>        | <button hx-confirm="A?">x<span hidden>A?</span><span hidden>A?</span></button>
      hidden child with an element   | <button hx-confirm="A?">x</button>        | <button hx-confirm="A?">x<span hidden><b>A?</b></span></button>
      confirm text in a visible child | <button hx-confirm="A?">x</button>       | <button hx-confirm="A?">x<span>A?</span></button>
      confirm text in a hidden sibling | <button hx-confirm="A?">x</button>      | <button hx-confirm="A?">x</button><span hidden>A?</span>
      old hidden confirm child changed | <button hx-confirm="A?">x<span hidden>B</span></button> | <button hx-confirm="A?">x<span hidden>C</span></button>
      question changed beside an old hidden child | <button hx-confirm="Delete?">x<span hidden>Deleting</span></button> | <button hx-confirm="Wipe everything?">x<span hidden>Deleting</span></button>
      old hidden child lost          | <button hx-confirm="Delete?">x<span hidden>Deleting</span></button> | <button hx-confirm="Deleting">x</button>
      hx- attribute replaced         | <a hx-get="/p" href="/p">p</a>            | <a hx-boost="true" href="/p">p</a>
      real link next to a handler    | <a href="/r?mode=" onclick="t()">r</a>    | <a href="/r?mode" data-action="t">r</a>
      text next to a handler         | <p onclick="a()">Name</p>                 | <p data-action="a">Email</p>
      text moved out of a script's parent | <div><p>a</p><script>x()</script></div><p>b</p> | <div><p>a</p><p>b</p></div>
      hx-on added                    | <form hx-post="/s"></form>                | <form hx-post="/s" hx-on::after-request="h()"></form>
      hx-on changed                  | <form hx-on::after-request="h()"></form>  | <form hx-on::after-request="g()"></form>
      hx-on removed with another attribute | <form hx-on::after-request="h()" hx-post="/s"></form> | <form></form>
      other attribute removed        | <form hx-on:submit="h()" hx-post="/s"></form> | <form hx-on:submit="h()"></form>
      hx-on kept and one changed     | <a hx-on:click="a()">a</a><a hx-on:click="b()">b</a> | <a hx-on:click="a()">a</a><a hx-on:click="c()">b</a>
      attribute without a colon removed | <form hx-on="click: h()"></form>     | <form></form>
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
      doctype removed                | <!DOCTYPE html><p>x</p>                   | <p>x</p>
      legacy doctype removed         | <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd"><p>x</p> | <p>x</p>
      html 3.2 doctype replaced      | <!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 3.2 Final//EN"><p>x</p> | <!DOCTYPE html><p>x</p>
      other public id replaced       | <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML Basic 1.1//EN" "http://www.w3.org/TR/xhtml-basic/xhtml-basic11.dtd"><p>x</p> | <!DOCTYPE html><p>x</p>
      legacy doctype and text changed | <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd"><p>x</p> | <!DOCTYPE html><p>y</p>
      custom attribute removed       | <button productId="1">Add</button>        | <button>Add</button>
      custom attribute value changed | <button productId="1">Add</button>        | <button data-product-id="2">Add</button>
      custom to another data name    | <button productId="1">Add</button>        | <button data-item-id="1">Add</button>
      html attribute to data         | <p id="5">x</p>                           | <p data-id="5">x</p>
      title to data                  | <a title="Bag">x</a>                      | <a data-title="Bag">x</a>
      aria attribute to data         | <p aria-label="Bag">x</p>                 | <p data-aria-label="Bag">x</p>
      one of two hooks lost          | <a productid="1">x</a><b productid="1">y</b> | <a data-product-id="1">x</a><b>y</b>
      value only under another name elsewhere | <a productid="1">x</a><b>y</b> | <a>x</a><b data-item-id="1">y</b>
      hook lost beside an old data   | <a productid="1" data-product-id="1">x</a> | <a data-product-id="1">x</a>
      hook lost beside an old data elsewhere | <a data-product-id="1">x</a><b productid="1">y</b> | <a data-product-id="1">x</a><b>y</b>
      custom kept and text changed   | <button productId="1">Add</button>        | <button data-product-id="1">Buy</button>
      meta itemprop removed          | <meta itemprop="seller" content="Default store"><p>x</p> | <p>x</p>
      meta itemprop value lost       | <meta itemprop="seller" content="Default store"><p>x</p> | <script type="application/ld+json">{"seller":"Other store"}</script><p>x</p>
      meta itemprop value in a js script | <meta itemprop="seller" content="Shop"><p>x</p> | <script>var s = "Shop";</script><p>x</p>
      meta itemprop content changed  | <meta itemprop="seller" content="Shop"><p>x</p> | <meta itemprop="seller" content="Store"><script type="application/ld+json">{"seller":"Shop"}</script><p>x</p>
      meta itemprop number in a string only | <meta itemprop="price" content="49"><p>x</p> | <script type="application/ld+json">{"sku":"A49"}</script><p>x</p>
      meta itemprop value under another key | <meta itemprop="price" content="0"><p>x</p> | <script type="application/ld+json">{"ratingCount": 0}</script><p>x</p>
      meta itemprop exponent out of range | <meta itemprop="price" content="1e9999999999"><p>x</p> | <script type="application/ld+json">{"price": 1}</script><p>x</p>
      meta empty itemprop            | <meta itemprop="" content="CAD"><p>x</p>  | <script type="application/ld+json">{"priceCurrency":"CAD"}</script><p>x</p>
      meta itemprop number as a json string | <meta itemprop="price" content="49"><p>x</p> | <script type="application/ld+json">{"price":"49.0"}</script><p>x</p>
      meta itemprop in a bare word   | <meta itemprop="seller" content="Shop"><p>x</p> | <script type="application/ld+json">{"seller": Shop}</script><p>x</p>
      meta itemprop with a signed escape | <meta itemprop="seller" content="A"><p>x</p> | <script type="application/ld+json">{"seller": "\\u+041"}</script><p>x</p>
      meta itemprop number with a plus | <meta itemprop="price" content="49"><p>x</p> | <script type="application/ld+json">{"price": +49}</script><p>x</p>
      meta itemprop number with a dot first | <meta itemprop="price" content="5"><p>x</p> | <script type="application/ld+json">{"price": .5}</script><p>x</p>
      meta itemprop key case differs | <meta itemprop="seller" content="Shop"><p>x</p> | <script type="application/ld+json">{"Seller": "Shop"}</script><p>x</p>
      meta itemprop key ends in it lower case | <meta itemprop="currency" content="CAD"><p>x</p> | <script type="application/ld+json">{"pricecurrency": "CAD"}</script><p>x</p>
      two meta itemprop for one json-ld value | <meta itemprop="priceCurrency" content="CAD"><p>a</p><meta itemprop="priceCurrency" content="CAD"><p>b</p> | <script type="application/ld+json">{"priceCurrency":"CAD"}</script><p>a</p><p>b</p>
      meta itemprop beside an old json-ld value | <script type="application/ld+json">{"seller":"Shop"}</script><meta itemprop="seller" content="Shop"><p>x</p> | <script type="application/ld+json">{"seller":"Shop"}</script><p>x</p>
      other meta moved to json-ld    | <meta name="description" content="Bag"><p>x</p> | <script type="application/ld+json">{"description":"Bag"}</script><p>x</p>
      other rel token added          | <a href="/p">p</a>                        | <a href="/p" rel="external">p</a>
      rel on a non-link              | <link href="/s.css">                      | <link href="/s.css" rel="noopener">
      other id prefix                | <h2 id="intro">I</h2>                     | <h2 id="post-intro">I</h2>
      id prefix on another id        | <h2 id="intro">I</h2>                     | <h2 id="user-content-outro">I</h2>
      href prefix to another id      | <a href="#intro">i</a>                    | <a href="#user-content-outro">i</a>
      css url changed                | <div style="background: url(/a/1.png)"></div> | <div style="background: url(&quot;/a/2.png&quot;)"></div>
      css url encoded differently    | <div style="background: url(/a/b c.png)"></div> | <div style="background: url(&quot;/a/b%20c.png&quot;)"></div>
      css other property changed     | <div style="color: red; background: url(/a.png)"></div> | <div style="color: blue; background: url(&quot;/a.png&quot;)"></div>
      other html attribute with xmlns | <html lang="en" xmlns:s="http://x.de/s"><body>a</body></html> | <html lang="de"><body>a</body></html>
      xml:lang is still compared     | <p xml:lang="en">a</p>                    | <p>a</p>
      other hidden input added       | <form method="post"><input name="q"></form> | <form method="post"><input type="hidden" name="id" value="1"><input name="q"></form>
      visible csrf field added       | <form method="post"></form>               | <form method="post"><input type="text" name="_csrf"></form>
      csrf input added to a get form | <form><input name="q"></form>             | <form><input type="hidden" name="_csrf" value="t"><input name="q"></form>
      csrf input nested deeper       | <form method="post"><div></div></form>    | <form method="post"><div><input type="hidden" name="_csrf" value="t"></div></form>
      csrf input removed from a post form | <form method="post"><input type="hidden" name="_csrf" value="a"><input name="q"></form> | <form method="post"><input name="q"></form>
      csrf input in a non-ascii post form | <form method="poſt"></form>          | <form method="poſt"><input type="hidden" name="_csrf" value="t"></form>
      csrf field non-ascii hidden    | <form method="post"></form>               | <form method="post"><input type="hıdden" name="_csrf" value="t"></form>
      """)
  void notForced(String rule, String a, String b) {
    assertEquals(DIFFERS, PageNormalizer.compare(a, b), () -> PageNormalizer.lines(a) + "\n"
        + PageNormalizer.lines(b));
  }

  /** The URL rule's whitespace and its path and fragment characters; a backtick can't go in the
   * CSV tables above. */
  @Test
  void urlsCompareAsTheBrowserParsesThem() {
    assertEquals(FORCED, PageNormalizer.compare("<a href=\"/a{b}`c\">x</a>",
        "<a href=\"/a%7Bb%7D%60c\">x</a>"));
    assertEquals(FORCED, PageNormalizer.compare("<a href=\"/a#b`c\">x</a>",
        "<a href=\"/a#b%60c\">x</a>"));
    assertEquals(FORCED, PageNormalizer.compare("<a href=\" /a b \">x</a>",
        "<a href=\"/a%20b\">x</a>"));
    // The browser requests /s?q={a}: the query set leaves curly brackets.
    assertEquals(DIFFERS, PageNormalizer.compare("<a href=\"/s?q={a}\">x</a>",
        "<a href=\"/s?q=%7Ba%7D\">x</a>"));
    // The browser drops a tab or newline (/ab) and encodes each space (/a%20%20b).
    assertEquals(DIFFERS, PageNormalizer.compare("<a href=\"/a\tb\">x</a>",
        "<a href=\"/a%20b\">x</a>"));
    assertEquals(DIFFERS, PageNormalizer.compare("<a href=\"/a\nb\">x</a>",
        "<a href=\"/a%20b\">x</a>"));
    assertEquals(DIFFERS, PageNormalizer.compare("<a href=\"/a  b\">x</a>",
        "<a href=\"/a%20b\">x</a>"));
    assertEquals(DIFFERS, PageNormalizer.compare("<a hx-get=\"/a  b?p=1\">x</a>",
        "<a data-hx-get=\"/a%20b\" data-p=\"1\">x</a>"));
    assertEquals(FORCED, PageNormalizer.compare("<a href=\"/a\tb\">x</a>",
        "<a href=\"/ab\">x</a>"));
  }

  /** Thwart's escapers write a parse-error code point as U+FFFD (espd's tooltip, round 3). */
  @Test
  void controlCharacterWrittenAsReplacementIsForced() {
    assertEquals(FORCED, PageNormalizer.compare("<span title=\"Section \u00ef\u0081\u00a1 of\">x</span>",
        "<span title=\"Section \u00ef\ufffd\u00a1 of\">x</span>"));
    assertEquals(FORCED, PageNormalizer.compare("<p>a\u0001b</p>", "<p>a\ufffdb</p>"));
    assertEquals(FORCED, PageNormalizer.compare("<p>a\ufdd0b</p>", "<p>a\ufffdb</p>"));
    // Only a parse-error code point stands for U+FFFD: a letter, a space or a tab still differs.
    assertEquals(DIFFERS, PageNormalizer.compare("<p>ab</p>", "<p>a\ufffdb</p>"));
    assertEquals(DIFFERS, PageNormalizer.compare("<p>axb</p>", "<p>a\ufffdb</p>"));
    assertEquals(DIFFERS, PageNormalizer.compare("<p title=\"a b\">x</p>", "<p title=\"a\ufffdb\">x</p>"));
    assertEquals(DIFFERS, PageNormalizer.compare("<p>a\tb</p>", "<p>a\ufffdb</p>"));
    assertEquals(DIFFERS, PageNormalizer.compare("<p>a\u0081b</p>", "<p>a\ufffdc</p>"));
    assertEquals(SAME, PageNormalizer.compare("<p>a\ufffdb</p>", "<p>a\ufffdb</p>"));
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
