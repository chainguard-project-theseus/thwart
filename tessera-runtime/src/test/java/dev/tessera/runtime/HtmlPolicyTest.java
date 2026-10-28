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
    return HtmlPolicy.RICH.sanitize(html).html();
  }

  private static String basic(String html) {
    return SafeHtml.sanitize(html).html();
  }

  // ---------------------------------------------------------------------------------------------
  // RICH: what editor and Markdown output needs

  @Test
  void richKeepsTablesImagesClassesAndIds() {
    assertEquals(
        "<table><thead><tr><th align=\"left\">Name</th><th colspan=\"2\">Score</th></tr></thead>"
            + "<tbody><tr><td rowspan=\"2\">Ann</td><td>1</td><td>2</td></tr></tbody></table>",
        rich("<table class=data><thead><tr><th align=LEFT>Name</th><th colspan=2>Score</th></tr></thead>"
            + "<tbody><tr><td rowspan=2>Ann</td><td>1</td><td>2</td></tr></tbody></table>"));
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
    assertSame(SafeHtml.EMPTY, HtmlPolicy.RICH.sanitize(null));
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
    assertEquals("", rich("<img src=\"data:image/png;base64,iVBOR\">")); // data: only if the policy allows
    assertEquals("", rich("<img src=\"data:text/html,<script>alert(1)</script>\">"));
    assertEquals("", rich("<img alt=x>"));
    assertEquals("", rich("<img src=vbscript:x>"));
    assertEquals("<img src=\"/x%7B%7Ba}}\">", rich("<img src=\"/x{{a}}\">"));
    // RICH keeps only same-origin paths: remote images leak the reader's IP address and the page
    for (String remote : new String[] {"https://evil.example/t.gif", "http://evil.example/t.gif", "//evil.example/t.gif",
        "/\\evil.example/t.gif", "\\\\evil.example/t.gif", "/\t/evil.example/t.gif", " //evil.example/t.gif", "t.gif",
        "../t.gif", "?x", "#x", "mailto:a@b", "&#x2f;/evil.example/t.gif"}) {
      assertEquals("", rich("<img src=\"" + remote + "\">"), remote);
    }
    assertEquals("<img src=\"/a/b.png?x#y\">", rich("<img src=\"/a/b.png?x#y\">"));
    HtmlPolicy remote = HtmlPolicy.builder().allowElements("img").allowRemoteImages().build();
    assertEquals("<img src=\"https://cdn.example/a.png\">", remote.sanitize("<img src=\"https://cdn.example/a.png\">").html());
    assertEquals("<img src=\"t.gif\">", remote.sanitize("<img src=t.gif>").html());
    assertEquals("", remote.sanitize("<img src=javascript:alert(1)>").html());
    assertEquals("", remote.sanitize("<img src=\"data:image/png;base64,iVBOR=\">").html());
    HtmlPolicy data = HtmlPolicy.builder().allowElements("img").allowDataImages().build();
    assertEquals("<img src=\"data:image/png;base64,iVBOR=\">", data.sanitize("<img src=\"data:image/png;base64,iVBOR=\">").html());
    assertEquals("", data.sanitize("<img src=\"https://cdn.example/a.png\">").html()); // data images aren't remote images
    assertEquals("", data.sanitize("<img src=\"data:image/svg+xml;base64,PHN2Zz4=\">").html());
    assertEquals("<img src=\"DATA:image/PNG;base64,iVBOR\">", data.sanitize("<img src=\"DATA:image/PNG;base64,iVBOR\">").html());
    assertEquals("", data.sanitize("<img src=\"data:image/png,<svg onload=alert(1)>\">").html());
    assertEquals("", data.sanitize("<img src=\"data:image/png;base64,iV BOR\">").html());
  }

  @Test
  void onlyAsciiLettersFoldInNamesAndKeywords() {
    // A browser folds only A-Z. toLowerCase(Locale.ROOT) also maps U+212A (Kelvin) to k and U+0130
    // to i plus a combining dot, so a value a browser reads as something else must not pass as a
    // keyword here.
    assertEquals("k", HtmlPolicy.lower("K"));
    assertEquals("\u212a", HtmlPolicy.lower("\u212a"));
    assertEquals("\u0130", HtmlPolicy.lower("\u0130"));
    // "_blan" and U+212A names a browsing context; it is not _blank, so the target is dropped
    assertEquals("<a href=\"/x\" rel=\"nofollow noopener\">x</a>", rich("<a href=\"/x\" target=\"_blan\u212a\">x</a>"));
    assertEquals("<a href=\"/x\" rel=\"nofollow noopener\">x</a>", rich("<a href=\"/x\" target=\"_blan&#x212a;\">x</a>"));
    // Kelvin is not base64
    HtmlPolicy data = HtmlPolicy.builder().allowElements("img").allowDataImages().build();
    assertEquals("", data.sanitize("<img src=\"data:image/png;base64,iVBOR\u212a\">").html());
    assertEquals("", data.sanitize("<img src=\"data:\u0130mage/png;base64,iVBOR\">").html());
    // names: an element or attribute spelled with Kelvin is a different, unknown name
    assertEquals("x", rich("<bloc\u212aquote>x</bloc\u212aquote>"));
    assertEquals("<p>x</p>", rich("<p \u212alass=\"language-x\">x</p>"));
  }

  @Test
  void classesAreFilteredToTheSafeForm() {
    // RICH keeps only code-highlighting classes: others could restyle the page with its own classes
    assertEquals("<code class=\"language-java\">x</code>", rich("<code class=\"hidden language-java fixed inset-0 lang-x\">x</code>"));
    assertEquals("<p>x</p>", rich("<p class=\"note\">x</p>"));
    HtmlPolicy any = HtmlPolicy.builder().allowElements("p").allowClasses("*").build();
    // AngularJS reads directives from class values ("ng-init: expr"); ':' ';' '{' are never kept
    assertEquals("<p class=\"a b-c d_e\">x</p>", any.sanitize("<p class=\"a ng-init:x; b-c NG-bind {{x}} d_e ng-include\">x</p>").html());
    assertEquals("<p>x</p>", any.sanitize("<p class=\"ng-init: constructor.constructor('alert(1)')()\">x</p>").html());
    // AngularJS normalizes directive names: x-/data- prefixes, '_' delimiters and the camel-case
    // form a class keeps all name ngBind (framework table, context-model.md 6.3)
    assertEquals("<p class=\"ngrok angular x-ngx nga\">x</p>",
        any.sanitize("<p class=\"data-ng-bind x-ng-bind ng_bind data_ng_init X-NG-bind ngBind ng ngrok angular x-ngx nga\">x</p>").html());
    HtmlPolicy some = HtmlPolicy.builder().allowElements("p").allowClasses("note", "lang-*").build();
    assertEquals("<p class=\"note lang-java\">x</p>", some.sanitize("<p class=\"note lang-java hidden fixed inset-0\">x</p>").html());
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
    HtmlPolicy own = HtmlPolicy.builder().allowElements("p", "a").allowAttributes("id").idPrefix("doc-").linkRel().build();
    assertEquals("<p id=\"doc-a\"><a href=\"#doc-a\">x</a></p>", own.sanitize("<p id=a><a href=#a>x</a></p>").html());
    // without ids, fragment links stay as they are
    assertEquals("<a href=\"#a\" rel=\"nofollow noopener\">x</a>", basic("<a href=#a>x</a>"));
  }

  @Test
  void textAttributesNeverHoldMustache() {
    assertEquals("<p>x</p>", rich("<p title=\"{{constructor.constructor('alert(1)')()}}\">x</p>"));
    assertEquals("<p>x</p>", rich("<p title=\"&lbrace;&#123;x}}\">x</p>"));
    assertEquals("<p title=\"{ x }\">x</p>", rich("<p title=\"{ x }\">x</p>"));
  }

  @Test
  void numericAndEnumeratedAttributesAreValidated() {
    assertEquals("<table><tbody><tr><td>x</td></tr></tbody></table>",
        rich("<table><tr><td colspan=\"2;x\" rowspan=-1 align=\"left;color:red\">x</td></tr></table>"));
    assertEquals("<img src=\"/a\">", rich("<img src=/a width=\"100%\" height=\"1e9\">"));
    assertEquals("<ol><li>x</li></ol>", rich("<ol start=\"1x\"><li value=\"9999999999\">x</li></ol>"));
  }

  @Test
  void linkRelAndTarget() {
    assertEquals("<a href=\"/x\" rel=\"nofollow noopener\">x</a>", basic("<a href=/x target=_blank>x</a>"));
    assertEquals("<a href=\"/x\" rel=\"nofollow noopener\">x</a>", rich("<a href=/x target=_top>x</a>"));
    assertEquals("<a href=\"/x\" rel=\"nofollow noopener\">x</a>", rich("<a href=/x target=\"evil\" rel=opener>x</a>"));
    HtmlPolicy own = HtmlPolicy.builder().allowElements("a").linkRel().build();
    assertEquals("<a href=\"/x\">x</a>", own.sanitize("<a href=/x rel=opener>x</a>").html());
    HtmlPolicy blank = HtmlPolicy.builder().allowElements("a").linkRel().allowLinkTarget().build();
    assertEquals("<a href=\"/x\" rel=\"noopener\" target=\"_blank\">x</a>", blank.sanitize("<a href=/x target=_blank>x</a>").html());
    HtmlPolicy ugc = HtmlPolicy.builder().allowElements("a").linkRel("ugc", "noreferrer").build();
    assertEquals("<a href=\"/x\" rel=\"ugc noreferrer\">x</a>", ugc.sanitize("<a href=/x>x</a>").html());
    assertEquals("<a>x</a>", rich("<a href=javascript:alert(1) target=_blank>x</a>"));
  }

  @Test
  void builderRejectsAnythingOutsideTheClosedSets() {
    for (String e : new String[] {"script", "style", "svg", "math", "iframe", "object", "embed", "form", "input",
        "button", "select", "textarea", "template", "noscript", "title", "base", "link", "meta", "IMG", "", "frameset"}) {
      assertThrows(IllegalArgumentException.class, () -> HtmlPolicy.builder().allowElements(e), e);
    }
    for (String a : new String[] {"style", "onclick", "onerror", "href", "src", "srcdoc", "formaction", "name",
        "class", "data-bind", "v-html", "x-data", "hx-get", "ng-click", "is", "target", "rel", "ID"}) {
      assertThrows(IllegalArgumentException.class, () -> HtmlPolicy.builder().allowAttributes(a), a);
    }
    for (String c : new String[] {"ng-*", "ng-init", "NG-x", "ng_*", "data-ng-*", "x-ng-bind", "ngBind", "a:b", "a b", "{{x}}", "", "a;b", "**"}) {
      assertThrows(IllegalArgumentException.class, () -> HtmlPolicy.builder().allowClasses(c), c);
    }
    for (String p : new String[] {"", "-", "x", "1a-", "a b-", "a:b-", "ab", "my_", "a_b", null}) {
      assertThrows(IllegalArgumentException.class, () -> HtmlPolicy.builder().idPrefix(p), String.valueOf(p));
    }
    for (String r : new String[] {"opener", "", "nofollow noopener", "external"}) {
      assertThrows(IllegalArgumentException.class, () -> HtmlPolicy.builder().linkRel(r), r);
    }
    assertThrows(NullPointerException.class, () -> SafeHtml.sanitize("x", null));
  }

  @Test
  void anEmptyPolicyKeepsOnlyText() {
    HtmlPolicy none = HtmlPolicy.builder().build();
    assertEquals("a &amp; b<!---->{<!---->{x}}", none.sanitize("<p>a &amp; <b>b</b>{{x}}</p><script>c</script>").html());
  }

  // ---------------------------------------------------------------------------------------------
  // Placement: elements go only where a browser keeps them as written

  @Test
  void blocksCloseParagraphsAndPhrasing() {
    assertEquals("<p>a</p><p>b</p><div>c</div>d", basic("<p>a<p>b<div>c</div>d</p>"));
    assertEquals("<p>x</p><ul><li>y</li></ul>", basic("<p>x<ul><li>y</li></ul></p>"));
    assertEquals("<b>x</b><div>y</div>", basic("<b>x<div>y</div></b>"));
    assertEquals("<h1>a</h1><h2>b</h2>c", basic("<h1>a<h2>b</h2>c</h1>"));
    assertEquals("<p>x</p><hr>y", basic("<p>x<hr>y</p>"));
    assertEquals("<p>x</p><table><tbody><tr><td>y</td></tr></tbody></table>", rich("<p>x<table><tr><td>y</td></tr></table></p>"));
  }

  @Test
  void linksDoNotNest() {
    assertEquals(
        "<a href=\"/1\" rel=\"nofollow noopener\">a</a><a href=\"/2\" rel=\"nofollow noopener\">b</a>c",
        basic("<a href=/1>a<a href=/2>b</a>c</a>"));
    assertEquals(
        "<a href=\"/1\" rel=\"nofollow noopener\"><b>a</b></a><a href=\"/2\" rel=\"nofollow noopener\">b</a>",
        basic("<a href=/1><b>a<a href=/2>b</a></b></a>"));
    assertEquals("<a href=\"/x\" rel=\"nofollow noopener\"></a><div>block</div>", basic("<a href=/x><div>block</div></a>"));
  }

  @Test
  void listItemsOnlyInLists() {
    assertEquals("<ul><li>a</li><li>b<ul><li>c</li></ul></li></ul>", basic("<ul><li>a<li>b<ul><li>c</ul></ul>"));
    assertEquals("orphan", basic("<li>orphan</li>"));
    assertEquals("<div>x</div>", basic("<div><li>x</li></div>"));
    assertEquals("<ul><li><p>a</p></li><li>b</li></ul>", basic("<ul><li><p>a<li>b</ul>"));
    assertEquals("<ol>xy</ol>", basic("<ol><p>x</p>y</ol>"));
  }

  @Test
  void tablesGetImpliedSectionsAndRowsAndNoStrayContent() {
    assertEquals("<table><tbody><tr><td>a</td><td>b</td></tr><tr><td>c</td></tr></tbody></table>",
        rich("<table><tr><td>a<td>b<tr><td>c</table>"));
    assertEquals("<table><tbody><tr><td>x</td></tr></tbody></table>", rich("<table>text<b>bold</b><tr>more<td>x</td>tail</tr></table>"));
    assertEquals("<table><caption>c</caption><tbody><tr><td>x</td></tr></tbody></table>", rich("<table><caption>c<tr><td>x</table>"));
    assertEquals("<table><thead><tr><td>h</td></tr></thead><tbody><tr><td>b</td></tr></tbody></table>",
        rich("<table><thead><td>h</td><tbody><tr><td>b</td></tbody></table>"));
    assertEquals("<table><tbody><tr><td>x</td></tr></tbody></table>", rich("<table><table><tr><td>x</td></tr></table></table>"));
    assertEquals("<table><tbody><tr><td><ul><li></li></ul></td><td>x</td></tr></tbody></table>",
        rich("<table><tr><td><ul><li><td>x</ul></table>"));
    assertEquals("no tablex", rich("<td>no table</td><tr><td>x</td></tr>"));
    assertEquals("<table><tbody><tr><td>y</td></tr></tbody></table>", rich("<table><tr><br><img src=/i><hr><td>y</td></tr></table>"));
    // </p> in a table, text in a table
    assertEquals("<table><tbody><tr><td>a</td></tr></tbody></table>", rich("<table></p><tr><td>a</td></p></tr></table>"));
    // BASIC has no tables: the tags go, the text stays
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
