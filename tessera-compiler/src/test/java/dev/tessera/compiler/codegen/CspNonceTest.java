package dev.tessera.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.compiler.testing.TestFragments;
import dev.tessera.runtime.Escapers;
import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.SafeHtml;
import dev.tessera.runtime.StringOutput;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A render's CSP nonce goes on every {@code <script>} and {@code <style>} start tag the compiler
 * writes, and nowhere else (#335): not when the context has none, not on markup inserted at
 * runtime as {@code SafeHtml}, and not from the template itself.
 */
class CspNonceTest {
  private static final String NS = "xmlns:t=\"https://tessera.dev\"";
  private static final String NONCE = "rAnd0m+/Nonce==";
  private static final RenderContext WITH_NONCE = RenderContext.defaults().withCspNonce(NONCE);
  private static final Pattern START_TAG = Pattern.compile("<(script|style)\\b[^>]*>", Pattern.CASE_INSENSITIVE);

  /** Every script and style start tag in {@code html}. */
  private static List<String> startTags(String html) {
    Matcher m = START_TAG.matcher(html);
    List<String> tags = new java.util.ArrayList<>();
    while (m.find()) {
      tags.add(m.group());
    }
    return tags;
  }

  private static void allStamped(String html, int expected) {
    List<String> tags = startTags(html);
    assertEquals(expected, tags.size(), html);
    for (String tag : tags) {
      assertTrue(tag.endsWith(" nonce=\"" + NONCE + "\">"), tag);
    }
    assertEquals(expected, html.split("nonce=", -1).length - 1, html);
  }

  private static final String PAGE =
      "<html " + NS + " t:params=\"String src, List<String> items, boolean on\">\n"
          + "<head><style>body { color: red }</style>\n"
          + "<script src=\"/app.js\" defer></script>\n"
          + "<script type=\"module\">import x from '/x.js';</script>\n"
          + "<script type=\"importmap\">{\"imports\": {}}</script>\n"
          + "<script t:src=\"@{/js/{n}.js(n=${src})}\"></script>\n"
          + "<script t:if=\"${on}\">var on = true;</script>\n"
          + "<script t:each=\"i : ${items}\">var item = [[${i}]];</script>\n"
          + "<SCRIPT>var upper = 1;</SCRIPT>\n"
          + "<script type=\"application/ld+json\">{\"@type\": \"Thing\"}</script>\n"
          + "<link rel=\"stylesheet\" href=\"/a.css\"></head>\n"
          + "<body><div data-action=\"\">x</div></body>\n"
          + "</html>";

  private static Compilation page() {
    return Compilation.builder().template("page.html", PAGE).compile().assertSuccess();
  }

  private static Map<String, ?> pageModel() {
    return Map.of("src", "main", "items", List.of("a", "b"), "on", true);
  }

  @Test
  void everyScriptAndStyleGetsTheNonce() {
    String html = page().render("page", pageModel(), WITH_NONCE);
    // style, 7 HTML scripts (two from t:each), ld+json.
    allStamped(html, 10);
    assertTrue(html.contains("<script src=\"/app.js\" defer nonce=\"" + NONCE + "\"></script>"), html);
    assertTrue(html.contains("<script src=\"/js/main.js\" nonce=\"" + NONCE + "\"></script>"), html);
    assertTrue(html.contains("<link rel=\"stylesheet\" href=\"/a.css\">"), html);
    assertTrue(html.contains("<div data-action=\"\">"), html);
  }

  @Test
  void noNonceRendersTheTemplateAsBefore() {
    String html = page().render("page", pageModel());
    assertFalse(html.contains("nonce"), html);
    assertTrue(html.contains("<head><style>body { color: red }</style>\n<script src=\"/app.js\" defer></script>"), html);
    assertTrue(html.contains("<SCRIPT>var upper = 1;</SCRIPT>"), html);
  }

  @Test
  void fragmentsAreStampedWhereverTheyAreInserted() {
    Compilation c =
        Compilation.builder()
            .template(
                "fragments/scripts.html",
                "<div " + NS + "><script t:fragment=\"boot\">boot();</script>"
                    + "<t:block t:fragment=\"assets(String v)\"><style>p{}</style><script t:src=\"@{/s/{v}.js(v=${v})}\"></script></t:block>"
                    + "<section t:fragment=\"card(Fragment content)\"><div t:replace=\"${content}\">c</div></section></div>")
            .template(
                "page.html",
                "<main " + NS + "><i t:fragment=\"local\"><script>local();</script></i>"
                    + "<div t:replace=\"~{fragments/scripts :: boot}\">x</div>"
                    + "<div t:insert=\"~{fragments/scripts :: boot}\">x</div>"
                    + "<div t:replace=\"~{fragments/scripts :: assets('1')}\">x</div>"
                    + "<div t:replace=\"~{fragments/scripts :: card(~{::p})}\">x</div><p><script>passed();</script></p>"
                    + "<div t:insert=\"~{:: local}\">x</div></main>")
            .compile()
            .assertSuccess();
    String html = c.render("page", Map.of(), WITH_NONCE);
    // local fragment in place, boot twice, assets' style and script, the passed <p>'s script
    // (twice: in place and in the card), the local fragment inserted.
    allStamped(html, 8);
    assertFalse(c.render("page", Map.of()).contains("nonce"));
  }

  /**
   * Negative: a script or style that a browser does not read as an HTML element gets no nonce, so
   * a strict policy blocks it: inside {@code <svg>} or {@code <math>}, where a browser decodes
   * character references in it, or inside an element whose content a browser reads as text.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        // A value in an SVG <script> is a verifier error (#367), so this one is static.
        "<svg><script>var s = 1;</script><style>circle { fill: red }</style></svg>",
        "<svg><foreignObject><script>x();</script></foreignObject></svg>",
        "<math><mi><style>p{}</style></mi></math>",
        "<noscript><script>x();</script></noscript>",
        "<iframe><style>p{}</style></iframe>",
      })
  void scriptsABrowserDoesNotReadAsHtmlAreNotStamped(String body) {
    Compilation c = Compilation.builder()
        .template("page.html", "<div " + NS + " t:params=\"String s\">" + body + "</div>")
        .compile()
        .assertSuccess();
    String html = c.render("page", Map.of("s", "&quot;-x-&quot;"), WITH_NONCE);
    assertFalse(html.contains("nonce"), html);
    assertTrue(html.contains("<script>") || html.contains("<style>"), html);
  }

  /**
   * t:replace swaps out the whole host, so a fragment that replaces an svg, math or raw text
   * element lands in HTML content and its script gets the nonce (#709).
   */
  @ParameterizedTest
  @ValueSource(strings = {"noscript", "svg", "math", "title", "textarea", "iframe", "div"})
  void replacingAnSvgOrRawTextHostStampsTheFragment(String host) {
    String html = Compilation.builder()
        .template(
            "page.html",
            "<div " + NS + "><i t:fragment=\"f\"><script>x();</script></i><" + host + " t:replace=\"~{::f}\">x</"
                + host + "></div>")
        .compile()
        .assertSuccess()
        .render("page", Map.of(), WITH_NONCE);
    allStamped(html, 2);
  }

  /** The same hosts replaced by a {@code Fragment} value: its script is stamped too. */
  @ParameterizedTest
  @ValueSource(strings = {"noscript", "svg", "math", "title", "textarea", "iframe", "div"})
  void replacingAHostWithAFragmentValueStampsIt(String host) {
    String html = Compilation.builder()
        .template(
            "frags.html",
            "<div " + NS + "><section t:fragment=\"card(Fragment content)\"><" + host + " t:replace=\"${content}\">x</"
                + host + "></section></div>")
        .template(
            "page.html",
            "<div " + NS + "><i t:fragment=\"f\"><script>x();</script></i><div t:replace=\"~{frags :: card(~{::f})}\"></div></div>")
        .compile()
        .assertSuccess()
        .render("page", Map.of(), WITH_NONCE);
    // f in place, and f again as the card's content.
    allStamped(html, 2);
  }

  /** Negative: a hostile nonce is attribute-escaped and cannot add attributes or elements. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "\"><script>alert(1)</script>",
        "x\" onload=\"alert(1)",
        "x' src='//evil.example/x.js",
        "a&quot;b",
        "</script><script>PWN()</script>",
      })
  void hostileNonceIsAttributeEscaped(String nonce) {
    String html =
        Compilation.builder()
            .template("page.html", "<div " + NS + "><script>ok();</script><style>p{}</style></div>")
            .compile()
            .assertSuccess()
            .render("page", Map.of(), RenderContext.defaults().withCspNonce(nonce));
    StringOutput out = new StringOutput();
    Escapers.attr(out, nonce);
    String escaped = out.toString();
    assertFalse(escaped.contains("\"") || escaped.contains("<") || escaped.contains("'"), escaped);
    assertEquals(
        "<div><script nonce=\"" + escaped + "\">ok();</script><style nonce=\"" + escaped + "\">p{}</style></div>", html);
  }

  /** Markup inserted at runtime is not the template's: the sanitizer drops scripts, and nothing is stamped. */
  @Test
  void safeHtmlIsNotStamped() {
    Compilation c =
        Compilation.builder()
            .template("page.html", "<div " + NS + " t:params=\"SafeHtml h\"><div t:utext=\"${h}\">x</div>[(${h})]</div>")
            .compile()
            .assertSuccess();
    SafeHtml sanitized =
        SafeHtml.sanitize("<script>a()</script><style>b{}</style><svg><script>c()</script></svg><b>ok</b>");
    assertEquals("<b>ok</b>", sanitized.toString());
    String html = c.render("page", Map.of("h", sanitized), WITH_NONCE);
    assertEquals("<div><div><b>ok</b></div><b>ok</b></div>", html);
    // A SafeHtml a hand-written Template vouches for is the caller's markup: a strict policy blocks its scripts.
    String vouched = c.render("page", Map.of("h", TestFragments.safeHtml("<script>u()</script>")), WITH_NONCE);
    assertEquals("<div><div><script>u()</script></div><script>u()</script></div>", vouched);
  }

  /** Negative: a static nonce in the template is a build error, naming file, line and column. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<script nonce=\"abc\">x();</script>",
        "<script NONCE=\"abc\" src=\"/a.js\"></script>",
        "<style nonce>p{}</style>",
        "<svg><script nonce=\"abc\">x();</script></svg>",
      })
  void nonceInTheTemplateIsRejected(String element) {
    Compilation c =
        Compilation.builder()
            .template("page.html", "<div " + NS + " t:params=\"String n\">\n" + element + "\n</div>")
            .compile();
    c.assertError("page.html:2:", "a nonce on <", "is not supported", "remove the nonce attribute");
    assertNull(c.generatedSource("tessera.generated.PageTemplate"), "nothing may be generated");
  }

  /** Negative: a computed nonce is off the computed-attribute list (#761), on any element. */
  @ParameterizedTest
  @ValueSource(strings = {"<script t:nonce=\"${n}\">x();</script>",
      "<link rel=\"preload\" t:nonce=\"${n}\">"})
  void computedNonceIsRejected(String element) {
    Compilation c =
        Compilation.builder()
            .template("page.html", "<div " + NS + " t:params=\"String n\">\n" + element + "\n</div>")
            .compile();
    c.assertError("page.html:2:", "Tessera stamps the CSP nonce itself; delete this attribute");
    assertNull(c.generatedSource("tessera.generated.PageTemplate"), "nothing may be generated");
  }

  @Test
  void nonceOnOtherElementsIsCopied() {
    String html =
        Compilation.builder()
            .template("page.html", "<div " + NS + "><link rel=\"preload\" as=\"script\" href=\"/a.js\" nonce=\"n\"></div>")
            .compile()
            .assertSuccess()
            .render("page", Map.of(), WITH_NONCE);
    assertEquals("<div><link rel=\"preload\" as=\"script\" href=\"/a.js\" nonce=\"n\"></div>", html);
  }

  @Test
  void emptyNonceIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> RenderContext.defaults().withCspNonce(""));
    assertFalse(RenderContext.defaults().withCspNonce(null).cspNonce().isPresent());
    assertFalse(WITH_NONCE.toString().contains(NONCE), "toString must not reveal the nonce");
  }
}
