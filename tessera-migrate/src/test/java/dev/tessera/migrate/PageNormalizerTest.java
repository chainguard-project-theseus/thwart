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
      jsessionid is not the path     | <a href="/owners;jsessionid=A1">o</a>    | <a href="/owner">o</a>
      """)
  void differentPages(String rule, String a, String b) {
    check(DIFFERS, a, b);
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
