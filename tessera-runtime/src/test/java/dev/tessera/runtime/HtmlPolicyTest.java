package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.runtime.fn.Html;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** {@link HtmlPolicy}: the built-in policies, the builder, and placement (#262). */
class HtmlPolicyTest {

  private static String rich(String html) {
    return SafeHtml.sanitize(html, HtmlPolicy.RICH).html();
  }

  private static String basic(String html) {
    return SafeHtml.sanitize(html).html();
  }

  /** One prefix per first character of a class token: every class token a policy can allow. */
  private static final String[] EVERY_CLASS =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-".chars()
          .mapToObj(c -> (char) c + "*")
          .toArray(String[]::new);

  /** The widest policy the builder can make (#661). */
  private static final HtmlPolicy MAXIMAL =
      HtmlPolicy.builder()
          .allowElements(HtmlPolicy.ELEMENTS.toArray(new String[0]))
          .allowAttributes(HtmlPolicy.ATTRIBUTES.toArray(new String[0]))
          .allowClasses(EVERY_CLASS)
          .allowLinkTarget()
          .linkRel(HtmlPolicy.REL_TOKENS.toArray(new String[0]))
          .build();

  // ---------------------------------------------------------------------------------------------
  // RICH: what editor and Markdown output needs

  @Test
  void richKeepsImagesClassesAndIds() {
    assertEquals(
        "<p><img src=\"/img/a.png\" alt=\"A cat\" title=\"Cat\" width=\"64\" height=\"48\"></p>",
        rich("<p><img src=\"/img/a.png\" alt=\"A cat\" title=Cat width=64 height=48 onerror=alert(1)></p>"));
    assertEquals(
        "<pre class=\"language-java\"><code class=\"language-java\">int x = 1;</code></pre>",
        rich("<pre class=\"language-java\"><code class=\"language-java\">int x = 1;</code></pre>"));
    assertEquals(
        "<h2 id=\"user-content-intro\">Intro</h2><p><a href=\"#user-content-intro\" rel=\"nofollow noopener\">up</a></p>",
        rich("<h2 id=intro>Intro</h2><p><a href=\"#intro\">up</a></p>"));
    assertEquals(
        "<ol start=\"3\" reversed><li value=\"-2\">x</li></ol><p><del>old</del> <ins>new</ins> <kbd>Ctrl</kbd></p>",
        rich("<ol start=3 reversed=reversed><li value=-2>x</li></ol><p><del>old</del> <ins>new</ins> <kbd>Ctrl</kbd></p>"));
    assertEquals(
        "<a href=\"https://example.com/\" rel=\"nofollow noopener\" target=\"_blank\">x</a>",
        rich("<a href=\"https://example.com/\" target=\"_BLANK\">x</a>"));
  }

  @Test
  void richDecodesEntitiesInTextAndAttributes() {
    // #262 (ewp-registry run 3): HtmlUtils.htmlEscape writes &oacute;, which must stay a character
    assertEquals("<p title=\"Krak&oacute;w\">Krak&oacute;w &lt;PL&gt;</p>", rich("<p title=\"Krak&oacute;w\">Krak&oacute;w &lt;PL&gt;</p>"));
    assertEquals("<img src=\"/a?x=1&amp;y=2\" alt=\"&quot;q&quot;\">", rich("<img src=\"/a?x=1&amp;y=2\" alt='\"q\"'>"));
  }

  @Test
  void htmlFunctionsUseTheirPolicies() {
    String in = "<p class=language-x><img src=/a.png>t</p>";
    assertEquals("<p>t</p>", Html.sanitize(in).html());
    assertEquals("<p class=\"language-x\"><img src=\"/a.png\">t</p>", Html.sanitizeRich(in).html());
    assertSame(SafeHtml.EMPTY, Html.sanitizeRich(null));
    assertSame(SafeHtml.EMPTY, SafeHtml.sanitize(null, HtmlPolicy.RICH));
  }

  // ---------------------------------------------------------------------------------------------
  // Negatives: what no policy lets through

  @Test
  void richNeverKeepsHandlersStyleOrUnknownAttributes() {
    assertEquals(
        "<p>x</p>",
        rich("<p style=\"position:fixed\" onclick=alert(1) onmouseover=alert(1) data-bind=x v-html=x x-data=x"
            + " hx-get=/x ng-click=x :href=x @click=x _=\"on click\" srcdoc=x name=x>x</p>"));
    assertEquals("<div>x</div>", rich("<div xmlns:xlink=\"http://www.w3.org/1999/xlink\" xlink:href=\"javascript:alert(1)\">x</div>"));
  }

  @Test
  void richDropsScriptCapableAndForeignElements() {
    assertEquals("ab", rich("a<script>alert(1)</script><svg><image href=x /></svg><math><mi>x</mi></math>b"));
    assertEquals("ab", rich("a<iframe src=/x></iframe><object data=x></object><embed src=x><style>p{}</style>b"));
    assertEquals("ab", rich("a<form action=/x><input name=q><button formaction=/y></button></form>b"));
    assertEquals("ab", rich("a<base href=//evil><link rel=import href=//evil><meta http-equiv=refresh content=0>b"));
  }

  @Test
  void imagesNeedACheckedSource() {
    assertEquals("", rich("<img src=javascript:alert(1)>"));
    assertEquals("", rich("<img src=\"jav&#x09;ascript:alert(1)\">"));
    assertEquals("", rich("<img src=\"data:image/png;base64,iVBOR\">")); // never data: (#661)
    assertEquals("", rich("<img src=\"data:text/html,<script>alert(1)</script>\">"));
    assertEquals("", rich("<img alt=x>"));
    assertEquals("", rich("<img src=vbscript:x>"));
    assertEquals("<img src=\"/x{{a}}\">", rich("<img src=\"/x{{a}}\">"));
    // RICH keeps only same-origin paths: remote images leak the reader's IP address and the page
    for (String remote : new String[] {"https://evil.example/t.gif", "http://evil.example/t.gif", "//evil.example/t.gif",
        "/\\evil.example/t.gif", "\\\\evil.example/t.gif", "/\t/evil.example/t.gif", " //evil.example/t.gif", "t.gif",
        "../t.gif", "?x", "#x", "mailto:a@b", "&#x2f;/evil.example/t.gif"}) {
      assertEquals("", rich("<img src=\"" + remote + "\">"), remote);
    }
    assertEquals("<img src=\"/a/b.png?x#y\">", rich("<img src=\"/a/b.png?x#y\">"));
  }

  @Test
  void noPolicyKeepsRemoteOrDataImages() {
    // #661: a remote image tracks every reader (IP, page, time) and can GET intranet URLs; a data:
    // image is attacker-chosen content with no host the application can block. No policy keeps
    // either, however wide.
    for (HtmlPolicy policy : new HtmlPolicy[] {HtmlPolicy.RICH, MAXIMAL}) {
      for (String src : new String[] {"https://x/a.png", "http://cdn.example/a.png", "//cdn.example/a.png", "a.png",
          "data:image/png;base64,iVBORw0KGgo=", "DATA:image/PNG;base64,iVBOR", "data:image/gif;base64,R0lGOD=="}) {
        assertEquals("<p>x</p>", SafeHtml.sanitize("<p><img src=\"" + src + "\">x</p>", policy).html(), src);
      }
      assertEquals("<img src=\"/a.png\">", SafeHtml.sanitize("<img src=\"/a.png\">", policy).html());
    }
  }

  @Test
  void onlyAsciiLettersFoldInNamesAndKeywords() {
    // A browser folds only A-Z. toLowerCase(Locale.ROOT) also maps U+212A (Kelvin) to k and U+0130
    // to i plus a combining dot, so a value a browser reads as something else must not pass as a
    // keyword here.
    assertEquals("k", Ascii.lower("K"));
    assertEquals("\u212a", Ascii.lower("\u212a"));
    assertEquals("\u0130", Ascii.lower("\u0130"));
    // "_blan" and U+212A names a browsing context; it is not _blank, so the target is dropped
    assertEquals("<a href=\"/x\" rel=\"nofollow noopener\">x</a>", rich("<a href=\"/x\" target=\"_blan\u212a\">x</a>"));
    assertEquals("<a href=\"/x\" rel=\"nofollow noopener\">x</a>", rich("<a href=\"/x\" target=\"_blan&#x212a;\">x</a>"));
    // trim() also dropped U+000B: a browser reads "\u000b_blank" as a window name, not _blank
    assertEquals("<a href=\"/x\" rel=\"nofollow noopener\">x</a>", rich("<a href=\"/x\" target=\"\u000b_blank\">x</a>"));
    assertEquals(
        "<a href=\"/x\" rel=\"nofollow noopener\" target=\"_blank\">x</a>",
        rich("<a href=\"/x\" target=\" _blank\n\">x</a>"));
    // names: an element or attribute spelled with Kelvin is a different, unknown name
    assertEquals("x", rich("<bloc\u212aquote>x</bloc\u212aquote>"));
    assertEquals("<p>x</p>", rich("<p \u212alass=\"language-x\">x</p>"));
  }

  @Test
  void classesAreFilteredToTheSafeForm() {
    // RICH keeps only code-highlighting classes: others could restyle the page with its own classes
    assertEquals("<code class=\"language-java\">x</code>", rich("<code class=\"hidden language-java fixed inset-0 lang-x\">x</code>"));
    assertEquals("<p>x</p>", rich("<p class=\"note\">x</p>"));
    HtmlPolicy any = HtmlPolicy.builder().allowElements("p").allowClasses(EVERY_CLASS).build();
    // Only tokens of [A-Za-z0-9_-] are kept: ':' ';' '{' never are
    assertEquals("<p class=\"a b-c d_e\">x</p>", SafeHtml.sanitize("<p class=\"a ng-init:x; b-c {{x}} d_e\">x</p>", any).html());
    assertEquals("<p>x</p>", SafeHtml.sanitize("<p class=\"ng-init: constructor.constructor('alert(1)')()\">x</p>", any).html());
    // #586: ng-like tokens of that form are kept like any other (AngularJS is not supported, guide §5)
    assertEquals("<p class=\"ng-bind NG-bind ngBind data-ng-init x_ng-if angular\">x</p>",
        SafeHtml.sanitize("<p class=\"ng-bind NG-bind ngBind data-ng-init x_ng-if angular\">x</p>", any).html());
    assertEquals("<p class=\"ng-x\">x</p>", SafeHtml.sanitize("<p class=\"ng-x other\">x</p>",
        HtmlPolicy.builder().allowElements("p").allowClasses("ng-*").build()).html());
    HtmlPolicy some = HtmlPolicy.builder().allowElements("p").allowClasses("note", "lang-*").build();
    assertEquals("<p class=\"note lang-java\">x</p>", SafeHtml.sanitize("<p class=\"note lang-java hidden fixed inset-0\">x</p>", some).html());
    assertEquals("<p>x</p>", basic("<p class=\"note\">x</p>"));
  }

  @Test
  void idsArePrefixedAndChecked() {
    // DOM clobbering: id="location" would otherwise shadow window.location for named access
    assertEquals("<p id=\"user-content-location\">x</p>", rich("<p id=location>x</p>"));
    assertEquals("<p>x</p>", rich("<p id=\"a b\">x</p>"));
    assertEquals("<p>x</p>", rich("<p id=\"x&quot;onclick=alert(1)\">x</p>"));
    assertEquals("<p>x</p>", basic("<p id=location>x</p>"));
    // already prefixed: kept, so sanitizing twice changes nothing
    assertEquals("<p id=\"user-content-a\"><a href=\"#user-content-a\" rel=\"nofollow noopener\">x</a></p>",
        rich("<p id=user-content-a><a href=#user-content-a>x</a></p>"));
    // #661: the prefix is fixed, so whatever the policy a kept id can't take one of the page's own
    // ids, and fragment links follow it
    for (HtmlPolicy policy : new HtmlPolicy[] {MAXIMAL,
        HtmlPolicy.builder().allowElements("p", "a").allowAttributes("id").linkRel().build()}) {
      assertEquals("<p id=\"user-content-x\"><a href=\"#user-content-x\">y</a></p>",
          SafeHtml.sanitize("<p id=x><a href=#x>y</a></p>", policy).html().replaceAll(" rel=\"[^\"]*\"", ""));
    }
    // without ids, fragment links stay as they are
    assertEquals("<a href=\"#a\" rel=\"nofollow noopener\">x</a>", basic("<a href=#a>x</a>"));
  }

  @Test
  void textAttributesKeepBraces() {
    // Braces in an attribute value are plain text.
    assertEquals("<p title=\"{{x}}\">x</p>", rich("<p title=\"&lbrace;&#123;x}}\">x</p>"));
    assertEquals("<p title=\"{ x }\">x</p>", rich("<p title=\"{ x }\">x</p>"));
  }

  @Test
  void numericAndEnumeratedAttributesAreValidated() {
    assertEquals("<img src=\"/a\">", rich("<img src=/a width=\"100%\" height=\"1e9\">"));
    assertEquals("<ol><li>x</li></ol>", rich("<ol start=\"1x\"><li value=\"9999999999\">x</li></ol>"));
  }

  @Test
  void linkRelAndTarget() {
    assertEquals("<a href=\"/x\" rel=\"nofollow noopener\">x</a>", basic("<a href=/x target=_blank>x</a>"));
    assertEquals("<a href=\"/x\" rel=\"nofollow noopener\">x</a>", rich("<a href=/x target=_top>x</a>"));
    assertEquals("<a href=\"/x\" rel=\"nofollow noopener\">x</a>", rich("<a href=/x target=\"evil\" rel=opener>x</a>"));
    HtmlPolicy own = HtmlPolicy.builder().allowElements("a").linkRel().build();
    assertEquals("<a href=\"/x\">x</a>", SafeHtml.sanitize("<a href=/x rel=opener>x</a>", own).html());
    HtmlPolicy blank = HtmlPolicy.builder().allowElements("a").linkRel().allowLinkTarget().build();
    assertEquals("<a href=\"/x\" rel=\"noopener\" target=\"_blank\">x</a>", SafeHtml.sanitize("<a href=/x target=_blank>x</a>", blank).html());
    HtmlPolicy ugc = HtmlPolicy.builder().allowElements("a").linkRel("ugc", "noreferrer").build();
    assertEquals("<a href=\"/x\" rel=\"ugc noreferrer\">x</a>", SafeHtml.sanitize("<a href=/x>x</a>", ugc).html());
    assertEquals("<a>x</a>", rich("<a href=javascript:alert(1) target=_blank>x</a>"));
  }

  @Test
  void builderRejectsAnythingOutsideTheClosedSets() {
    for (String e : new String[] {"script", "style", "svg", "math", "iframe", "object", "embed", "form", "input",
        "button", "select", "textarea", "template", "noscript", "title", "base", "link", "meta", "IMG", "", "frameset",
        // no tables (#592)
        "table", "caption", "thead", "tbody", "tfoot", "tr", "th", "td", "col", "colgroup"}) {
      assertThrows(IllegalArgumentException.class, () -> HtmlPolicy.builder().allowElements(e), e);
    }
    for (String a : new String[] {"style", "onclick", "onerror", "href", "src", "srcdoc", "formaction", "name",
        "class", "data-bind", "v-html", "x-data", "hx-get", "ng-click", "is", "target", "rel", "ID",
        "colspan", "rowspan", "align"}) {
      assertThrows(IllegalArgumentException.class, () -> HtmlPolicy.builder().allowAttributes(a), a);
    }
    for (String c : new String[] {"a:b", "a b", "{{x}}", "", "a;b", "**", "ng-init:x"}) {
      assertThrows(IllegalArgumentException.class, () -> HtmlPolicy.builder().allowClasses(c), c);
    }
    for (String r : new String[] {"opener", "", "nofollow noopener", "external"}) {
      assertThrows(IllegalArgumentException.class, () -> HtmlPolicy.builder().linkRel(r), r);
    }
    assertThrows(NullPointerException.class, () -> SafeHtml.sanitize("x", null));
  }

  @Test
  void anyClassWildcardThrows() {
    // #661: "*" would give user content the page's whole class namespace (CSS overlays such as
    // Bootstrap's position-fixed top-0 start-0 w-100 h-100, class-initialised widgets)
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> HtmlPolicy.builder().allowClasses("*"));
    assertEquals("not an allowed class pattern: *", e.getMessage());
    assertThrows(IllegalArgumentException.class, () -> HtmlPolicy.builder().allowClasses("note", "*"));
  }

  @Test
  void anEmptyPolicyKeepsOnlyText() {
    HtmlPolicy none = HtmlPolicy.builder().build();
    assertEquals("a &amp; b{{x}}", SafeHtml.sanitize("<p>a &amp; <b>b</b>{{x}}</p><script>c</script>", none).html());
  }

  // ---------------------------------------------------------------------------------------------
  // Placement: the closed content model (#592). What doesn't fit is unwrapped, nothing is moved

  @Test
  void blocksAreUnwrappedInParagraphsAndPhrasing() {
    assertEquals("<p>abcd</p>", basic("<p>a<p>b<div>c</div>d</p>"));
    assertEquals("<p>xy</p>", basic("<p>x<ul><li>y</li></ul></p>"));
    assertEquals("<b>xy</b>", basic("<b>x<div>y</div></b>"));
    assertEquals("<h1>abc</h1>", basic("<h1>a<h2>b</h2>c</h1>"));
    assertEquals("<p>xy</p>", basic("<p>x<hr>y</p>"));
    assertEquals("<p>xy</p>", rich("<p>x<table><tr><td>y</td></tr></table></p>"));
    // what fits is kept as written
    assertEquals("<div><p>a<b>b</b></p><blockquote><h2>c</h2><hr><pre>d</pre></blockquote></div>",
        basic("<div><p>a<b>b</b></p><blockquote><h2>c</h2><hr><pre>d</pre></blockquote></div>"));
  }

  @Test
  void linksAndFormattingDoNotNestInThemselves() {
    assertEquals("<a href=\"/1\" rel=\"nofollow noopener\">ab</a>c", basic("<a href=/1>a<a href=/2>b</a>c</a>"));
    assertEquals("<a href=\"/1\" rel=\"nofollow noopener\"><b>ab</b></a>", basic("<a href=/1><b>a<a href=/2>b</a></b></a>"));
    assertEquals("<a href=\"/x\" rel=\"nofollow noopener\">block</a>", basic("<a href=/x><div>block</div></a>"));
    assertEquals("<b><i>x</i></b>y", basic("<b><i><b>x</b>y</i></b>"));
    assertEquals("<b><i>x</i></b><i><b>y</b></i>", basic("<b><i>x</i></b><i><b>y</b></i>"));
  }

  @Test
  void listItemsOnlyInLists() {
    assertEquals("<ul><li>ab<ul><li>c</li></ul></li></ul>", basic("<ul><li>a<li>b<ul><li>c</ul></ul>"));
    assertEquals("orphan", basic("<li>orphan</li>"));
    assertEquals("<div>x</div>", basic("<div><li>x</li></div>"));
    assertEquals("<ul><li><p>ab</p></li></ul>", basic("<ul><li><p>a<li>b</ul>"));
    assertEquals("<ol>xy</ol>", basic("<ol><p>x</p>y</ol>"));
    assertEquals("<ul><li>a</li><li>b</li></ul>", basic("<ul><li>a</li><li>b</li></ul>"));
  }

  @Test
  void tablesAreUnwrapped() {
    assertEquals("abc", rich("<table><tr><td>a<td>b<tr><td>c</table>"));
    assertEquals("text<b>bold</b>morextail", rich("<table>text<b>bold</b><tr>more<td>x</td>tail</tr></table>"));
    assertEquals("NameScoreAnn12",
        rich("<table class=data><thead><tr><th align=LEFT>Name</th><th colspan=2>Score</th></tr></thead>"
            + "<tbody><tr><td rowspan=2>Ann</td><td>1</td><td>2</td></tr></tbody></table>"));
    assertEquals("<ul><li>x</li></ul>", rich("<table><tr><td><ul><li><td>x</ul></table>"));
    assertEquals("ab", basic("<table><tr><td>a</td><td>b</td></tr></table>"));
  }

  @Test
  void preKeepsALeadingNewline() {
    assertEquals("<pre>\n\nx</pre>", basic("<pre>\nx</pre>"));
    assertEquals("<pre>\n\nx</pre>", basic("<pre>&#10;x</pre>"));
    assertEquals("<pre>\n\nx</pre>", basic("<pre>\r\nx</pre>"));
    assertEquals("<pre>x\ny</pre>", basic("<pre>x\r\ny</pre>"));
    assertEquals("<pre><b>\nx</b></pre>", basic("<pre><b>\nx</b></pre>"));
    assertEquals("a\nb\nc", basic("a\r\nb\rc"));
  }

  // ---------------------------------------------------------------------------------------------
  // The XSS corpus under RICH

  static String[] vectors() {
    return HtmlSanitizerTest.vectors().toArray(String[]::new);
  }

  private static final Pattern TAG = Pattern.compile("<(/?)([a-z0-9]+)((?: [a-z]+(?:=\"[^\"]*\")?)*)>|<!---->");
  private static final Pattern ATTR = Pattern.compile(" ([a-z]+)(?:=\"([^\"]*)\")?");

  @ParameterizedTest
  @MethodSource("vectors")
  void xssVectorsUnderRich(String vector) {
    for (String in : new String[] {vector, "<table><tr><td><p><b>" + vector + "</b></p></td></tr></table>"}) {
      String out = rich(in);
      assertEquals(out, rich(out), in); // idempotent
      String lower = out.toLowerCase(Locale.ROOT);
      for (String bad : new String[] {"<script", "<svg", "<math", "<style", "<iframe", "<object", "<embed", "<form",
          "<base", "<link", "<meta", "<input", "<button", "<textarea", "<select", "<template", "<noscript",
          "javascript:", "vbscript:", "data:"}) {
        assertFalse(lower.contains(bad), () -> "found " + bad + " in " + out + " for " + in);
      }
      Matcher m = TAG.matcher(out);
      while (m.find()) {
        if (m.group(2) == null) {
          continue;
        }
        assertTrue(HtmlPolicy.ELEMENTS.contains(m.group(2)), () -> "element " + m.group(2) + " in " + out);
        Matcher a = ATTR.matcher(m.group(3));
        while (a.find()) {
          String name = a.group(1);
          assertTrue(HtmlPolicy.ATTRIBUTES.contains(name) || name.equals("class") || name.equals("href")
              || name.equals("src") || name.equals("rel") || name.equals("target"), () -> "attribute " + name + " in " + out);
          if (name.equals("href") || name.equals("src")) {
            String url = a.group(2).replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&");
            assertTrue(Escapers.isAllowedUrl(url), () -> "unsafe URL in " + out);
          }
        }
      }
      // nothing outside tags but text: no '<' left over
      assertFalse(TAG.matcher(out).replaceAll("").contains("<"), () -> "stray markup in " + out);
    }
  }
}
