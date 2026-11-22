package dev.chainguard.thwart.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chainguard.thwart.runtime.internal.Checks;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class HtmlSanitizerTest {

  private static String clean(String html) {
    return SafeHtml.sanitize(html).html();
  }

  @Test
  void keepsAllowedFormatting() {
    String html =
        "<h2>Title</h2><p>See <b>the</b> <i>agenda</i>, <em>a</em><strong>b</strong><u>c</u><s>d</s>"
            + "H<sub>2</sub>O x<sup>2</sup><small>s</small><mark>m</mark><code>c</code></p>"
            + "<pre>x\n  y</pre><blockquote>q</blockquote><ul><li>1</li></ul><ol><li>2</li></ol>"
            + "<div><span>s</span></div>line<br>break<hr>";
    assertEquals(html, clean(html));
  }

  @Test
  void nullAndPlainText() {
    assertSame(SafeHtml.EMPTY, SafeHtml.sanitize(null));
    assertEquals("", clean(""));
    assertEquals("a &lt; b &amp;&amp; c &gt; d &quot;&#39;", clean("a < b && c > d \"'"));
  }

  @Test
  void linksKeepOnlyCheckedHrefAndGetRel() {
    assertEquals(
        "<a href=\"https://example.com/?a=1&amp;b=2\" rel=\"nofollow noopener\">x</a>",
        clean("<a href=\"https://example.com/?a=1&amp;b=2\" target=_blank class=c rel=opener>x</a>"));
    assertEquals("<a href=\"/agenda/1\" rel=\"nofollow noopener\">a</a>", clean("<A HREF='/agenda/1'>a</A>"));
    assertEquals(
        "<a href=\"mailto:a@b.c\" rel=\"nofollow noopener\">m</a>", clean("<a href=mailto:a@b.c>m</a>"));
    assertEquals("<a>x</a>", clean("<a href=\"javascript:alert(1)\">x</a>"));
    assertEquals("<a>x</a>", clean("<a name=top>x</a>"));
    // the first href wins, as in a browser
    assertEquals("<a>x</a>", clean("<a href=\"javascript:alert(1)\" href=\"/ok\">x</a>"));
  }

  @Test
  void dropsAttributesAndUnknownTagsButKeepsTheirText() {
    assertEquals("<p>hi</p>", clean("<p class=\"x\" id=y style=\"color:red\" onclick=\"alert(1)\">hi</p>"));
    assertEquals("<p>bold</p>", clean("<p><font color=red>bold</font></p>"));
    assertEquals("ab", clean("<form action=/x><input name=a>a<button>b</button></form><img src=x>"));
    assertEquals("<p>t</p>", clean("<html><body><p>t</p></body></html>"));
  }

  @Test
  void dropsDangerousElementsWithTheirContent() {
    assertEquals("ab", clean("a<script>alert(1)</script>b"));
    assertEquals("ab", clean("a<STYLE>p{}</STYLE>b"));
    assertEquals("ab", clean("a<svg><g><svg></svg><text>t</text></g></svg>b"));
    assertEquals("ab", clean("a<math><mi>x</mi></math>b"));
    assertEquals("ab", clean("a<template><p>t</p></template>b"));
    assertEquals("ab", clean("a<object data=x><p>fallback</p></object>b"));
    assertEquals("ab", clean("a<iframe src=x>i</iframe>b"));
    assertEquals("ab", clean("a<textarea><p>t</p></textarea>b"));
    assertEquals("ab", clean("a<noscript><p>n</p></noscript>b"));
    assertEquals("ab", clean("a<embed src=x>b"));
    assertEquals("a", clean("a<script>never closed <p>x</p>"));
    assertEquals("a", clean("a<plaintext><p>x</p>"));
    assertEquals("a", clean("a<svg><p>x</p>"));
    // a raw text element's content is not markup
    assertEquals("ab", clean("a<script>document.write('</scr'+'ipt><p>')</script>b"));
    // A browser folds A-Z only: </\u017Fcript> does not end the script, so the <b> is script text too.
    assertEquals("ab", clean("a<script></\u017Fcript><b>x</b></script>b"));
    assertEquals("ab", clean("a<STYLE></\u017Ftyle><b>x</b></style>b"));
  }

  @Test
  void commentsDoctypesAndCdataAreDropped() {
    assertEquals("ab", clean("a<!-- <p>x</p> -->b"));
    assertEquals("ab", clean("a<!---->b"));
    assertEquals("a<p>x</p>", clean("a<!--><p>x</p>"));
    assertEquals("a<p>x</p>", clean("a<!---><p>x</p>"));
    assertEquals("a<p>x</p>", clean("a<!-- c --!><p>x</p>"));
    assertEquals("a", clean("a<!-- unclosed <p>x</p>"));
    assertEquals("ab", clean("<!DOCTYPE html>a<![CDATA[x]]>b<?php echo 1 ?>"));
  }

  @Test
  void balancesMisnestedAndUnclosedTags() {
    assertEquals("<b><i>x</i></b>y", clean("<b><i>x</b>y</i>"));
    assertEquals("<p>x</p>", clean("<p>x"));
    assertEquals("x", clean("</div></p>x"));
    assertEquals("<div>x</div>", clean("<div>x</div></div></body>"));
    assertEquals("<p>a</p>", clean("<p>a</p/>"));
    assertEquals("a", clean("a</ x>"));
    assertEquals("a", clean("a</>"));
  }

  @Test
  void literalLessThanAndTruncatedTags() {
    assertEquals("1 &lt; 2 &lt;3 &lt;", clean("1 < 2 <3 <"));
    assertEquals("a", clean("a<p class=\"x"));
    assertEquals("a", clean("a<p"));
    assertEquals("a&lt;/", clean("a</"));
  }

  @Test
  void characterReferencesAreDecodedAndReEscaped() {
    assertEquals("&lt;script&gt;", clean("&lt;script&gt;"));
    assertEquals("A A A   — &#39;", clean("&#65; &#x41 &#X041; &nbsp; &mdash; &apos;"));
    assertEquals("caf&eacute; &amp;zz &amp;# &amp;", clean("caf&eacute; &zz &# &"));
    // #592: an HTML 4 name is kept; any other name outside the tables is escaped, since as written
    // the parser reports it (a legacy name without its ';', or an unknown name).
    assertEquals("&notin; &Oacute; a&amp;notit;b a&amp;lt3;b x&amp;ampy; &amp;copyright; &amp;X; &amp;p;",
        clean("&notin; &Oacute; a&notit;b a&lt3;b x&ampy; &copyright; &X; &p;"));
    assertEquals("<p title=\"&eacute;&amp;notit;&amp;X;\">x</p>", SafeHtml.sanitize("<p title=\"&eacute;&notit;&X;\">x</p>", HtmlPolicy.RICH).html());
    // Deliberate (guide templates.md, Output contexts): valid HTML5-only names are escaped too, so they show as written
    // rather than as the character a browser would decode; an HTML 4 name next to them is kept.
    assertEquals("&amp;check; &amp;star; &amp;Rarr; &rarr;", clean("&check; &star; &Rarr; &rarr;"));
    assertEquals("� � � 😀", clean("&#0; &#xD800; &#99999999999; &#x1F600;"));
    assertEquals("a�b", clean("a\0b"));
  }

  /**
   * #592: code points the parser reports as errors in its input (controls, noncharacters, lone
   * surrogates), literal or from a reference, become U+FFFD in text and attribute values; a CR
   * from a reference becomes the LF the parser reads. Ordinary characters are kept.
   */
  @Test
  void forbiddenCodePointsBecomeReplacementCharacters() {
    String r = "\uFFFD";
    assertEquals("a" + r + "b" + r + "c" + r + "d" + r + "e" + r + "f" + r + "g" + r + "h",
        clean("a\u0001b\u007fc\u0085d\uFFFEe\uFDD0f\uD83F\uDFFFg\uD800h"));
    assertEquals("a" + r + "b" + r + "c" + r + "d" + r + "e\nf", clean("a&#1;b&#129;c&#xFFFF;d&#x1FFFE;e&#13;f"));
    HtmlPolicy rich = HtmlPolicy.RICH;
    assertEquals("<p title=\"" + r + r + "\n\">x</p>", SafeHtml.sanitize("<p title=\"\u0001&#x9d;&#13;\">x</p>", rich).html());
    assertEquals("<img src=\"/a" + r + "b\" alt=\"" + r + "\">", SafeHtml.sanitize("<img src=\"/a\u0001b\" alt=\"&#127;\">", rich).html());
    // the URL is checked before the replacement: a control the browser strips still hides no scheme
    assertEquals("<a>x</a>", clean("<a href=\"\u0001javascript:alert(1)\">x</a>"));
    // negative: whitespace, ordinary and supplementary characters are not replaced
    assertEquals("a\tb\nc\fd \u00E9\u20AC\uD83D\uDE00\uFFFD", clean("a\tb\nc\fd \u00E9\u20AC\uD83D\uDE00\uFFFD"));
  }

  /**
   * #592: a numeric reference to U+0080..U+009F is read through the spec's windows-1252 table, as a
   * browser does, so Word pastes keep their punctuation; the five unmapped ones become U+FFFD.
   */
  @Test
  void c1ReferencesUseTheWindows1252Table() {
    assertEquals("don’t — €5 Ÿ", clean("don&#146;t &#151; &#x80;5 &#159;"));
    assertEquals("<p title=\"“q”\">x</p>", SafeHtml.sanitize("<p title=\"&#147;q&#148;\">x</p>", HtmlPolicy.RICH).html());
    // negative: unmapped references and literal C1 controls are not mapped, they become U+FFFD
    assertEquals("�����|��", clean("&#129;&#141;&#143;&#144;&#157;|\u0080\u0092"));
  }

  @Test
  void textKeepsBracesAsText() {
    // #679: braces, also written as character references, are plain text.
    assertEquals("<p>{{ x }}</p>", clean("<p>{{ x }}</p>"));
    assertEquals("{{x}}", clean("&#123;&#x7b;x}}"));
    assertEquals("{{x}}", clean("&lbrace;&lcub;x}}"));
    assertEquals("{{{x}}", clean("&#123&#x7B&#0000123;x}}"));
    assertEquals("&amp;lbrace&amp;lbrace", clean("&lbrace&lbrace"));
  }

  @Test
  void hrefKeepsBraces() {
    // Braces in an attribute value are plain text.
    assertEquals(
        "<a href=\"/x{{constructor}}?q={{a}}\" rel=\"nofollow noopener\">x</a>",
        clean("<a href=\"/x{{constructor}}?q=&#123;&lbrace;a}}\">x</a>"));
  }

  @Test
  void depthIsCapped() {
    String deep = "<span>".repeat(500) + "x" + "</span>".repeat(500);
    String out = clean(deep);
    assertEquals("<span>".repeat(100) + "x" + "</span>".repeat(100), out);
  }

  /**
   * #329: a formatting element inside one of the same name is dropped, text kept. Nested, it would
   * be a second identical entry in the list of active formatting elements, and next to one around
   * the insertion point a third would make a browser drop the outer one (the Noah's Ark clause).
   */
  @Test
  void formattingElementsDoNotNestInThemselves() {
    assertEquals("<b>x</b>", clean("<b>".repeat(500) + "x"));
    // The end tag of the dropped element closes the open one, as the sanitizer does for any end tag.
    assertEquals("<b>a<i>bc</i></b>d", clean("<b>a<i>b<b>c</b>d</i>"));
    assertEquals("<em>a<strong>bc</strong></em>", clean("<em>a<strong>b<em>c</em></strong></em>"));
    assertEquals("<b>a</b><p><b>b</b></p>", clean("<b>a</b><p><b>b</b></p>"));
  }

  @Test
  void rawTextEndTagMatching() {
    assertEquals("b", clean("<script>a</script >b"));
    assertEquals("b", clean("<script>a</SCRIPT\t>b"));
    assertEquals("c", clean("<script>a</scriptx>b</script>c"));
    // the first end tag closes, so the rest is inert text
    assertEquals("alert(1)", clean("<script><script></script>alert(1)</script>"));
    assertEquals("", clean("<style><style/>x"));
    assertEquals("&quot;&gt;", clean("<noscript><p title=\"</noscript><img src=x onerror=alert(1)>\">"));
  }

  @Test
  void hrefEdgeCases() {
    assertEquals(
        "<a href=\"//evil.com\" rel=\"nofollow noopener\">x</a>", clean("<a href=\"//evil.com\">x</a>"));
    assertEquals(
        "<a href=\"http://ok\" rel=\"nofollow noopener\">x</a>", clean("<a href=\"http&#58;//ok\">x</a>"));
    // quotes decoded from the value are re-escaped, so they cannot end the attribute
    assertEquals(
        "<a href=\"/x&quot;onclick=alert(1)\" rel=\"nofollow noopener\">x</a>",
        clean("<a href=\"/x&quot;onclick=alert(1)\">x</a>"));
    assertEquals(
        "<a href=\"/x?a=1&amp;b=&lt;\" rel=\"nofollow noopener\">x</a>", clean("<a href=\"/x?a=1&amp;b=&lt;\">x</a>"));
    assertEquals("<a>x</a>", clean("<a href=`javascript:alert(1)`>x</a>"));
  }

  /** Pathological input is processed in linear time. */
  @Test
  void largeInputIsLinear() {
    int n = 200_000;
    String[] inputs = {
      "<!---->".repeat(n), "<!-- a -".repeat(n), "<".repeat(n), "<b>".repeat(n), "</b>".repeat(n),
      "<svg>".repeat(n), "<svg>" + "</svg>".repeat(n), "<script></script>".repeat(n), "</a".repeat(n),
      "&aaaa".repeat(n), "&#1111111111".repeat(n), "<p " + "a=1 ".repeat(n) + ">", "<p a=\"".repeat(n),
      "<div>".repeat(100) + "</span>".repeat(n), "<a href=/x>".repeat(n),
    };
    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () -> {
          for (String in : inputs) {
            clean(in);
          }
        });
  }

  @Test
  void concatJoinsParts() {
    assertEquals(
        "a&lt;b<br>c",
        SafeHtml.concat(SafeHtml.escape("a<b"), null, SafeHtml.sanitize("<br>"), SafeHtml.escape("c")).html());
    assertEquals("", SafeHtml.concat().html());
  }

  // ---------------------------------------------------------------------------------------------
  // XSS corpus (OWASP XSS filter evasion cheat sheet style)

  static Stream<String> vectors() {
    return Stream.of(
        "<script>alert(1)</script>",
        "<SCRIPT SRC=http://xss.rocks/xss.js></SCRIPT>",
        "<ScRiPt>alert(1)</sCrIpT>",
        "<script/xss src=x></script>",
        "<<script>alert(1);//<</script>",
        "<script>alert(1)",
        "<scr<script>ipt>alert(1)</scr</script>ipt>",
        "<scr\0ipt>alert(1)</scr\0ipt>",
        "<img src=x onerror=alert(1)>",
        "<IMG SRC=\"javascript:alert('XSS');\">",
        "<IMG SRC=JaVaScRiPt:alert('XSS')>",
        "<IMG \"\"\"><SCRIPT>alert(\"XSS\")</SCRIPT>\">",
        "<p onclick=alert(1)>x</p>",
        "<p ONMOUSEOVER=\"alert(1)\">x</p>",
        "<b/onmouseover=alert(1)>x</b>",
        "<p\nonclick=alert(1)>x</p>",
        "<p \"onclick=alert(1)\">x</p>",
        "<p title=\"a\" onclick='alert(1)'>x</p>",
        "<p title='a\" onclick=\"alert(1)'>x</p>",
        "<p title=\"a' onclick='alert(1)\">x</p>",
        "<p title=a\"onclick=alert(1)>x</p>",
        "<a href=\"javascript:alert(1)\">x</a>",
        "<a href=\"JAVASCRIPT:alert(1)\">x</a>",
        "<a href=\" javascript:alert(1)\">x</a>",
        "<a href=\"\0javascript:alert(1)\">x</a>",
        "<a href=\"jav\tascript:alert(1)\">x</a>",
        "<a href=\"jav&#x09;ascript:alert(1)\">x</a>",
        "<a href=\"jav&#x0A;ascript:alert(1)\">x</a>",
        "<a href=\"jav&#x0D;ascript:alert(1)\">x</a>",
        "<a href=\"jav&Tab;ascript:alert(1)\">x</a>",
        "<a href=\"javascript&colon;alert(1)\">x</a>",
        "<a href=\"javascript&#58;alert(1)\">x</a>",
        "<a href=\"javascript&#x3A;alert(1)\">x</a>",
        "<a href=\"&#106;&#97;&#118;&#97;&#115;&#99;&#114;&#105;&#112;&#116;&#58;alert(1)\">x</a>",
        "<a href=\"&#0000106&#0000097&#0000118&#0000097&#0000115&#0000099&#0000114&#0000105&#0000112&#0000116&#0000058alert(1)\">x</a>",
        "<a href=\"&#x6A&#x61&#x76&#x61&#x73&#x63&#x72&#x69&#x70&#x74&#x3A;alert(1)\">x</a>",
        "<a href=\"&#14;  javascript:alert(1)\">x</a>",
        "<a href=javascript:alert(1)>x</a>",
        "<a href='vbscript:msgbox(1)'>x</a>",
        "<a href=\"data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==\">x</a>",
        "<a href=\"DATA:text/html,<script>alert(1)</script>\">x</a>",
        "<a href=\"livescript:alert(1)\">x</a>",
        "<a href=\"javaKscript:alert(1)\">x</a>",
        "<a href=\"/ok\" onclick=\"alert(1)\">x</a>",
        "<a href=\"/ok\" style=\"position:fixed\">x</a>",
        "<a href=\"x\" target=\"_blank\">x</a>",
        "<a href=\"x\"onmouseover=\"alert(1)\">x</a>",
        "<a href=\"\" \"onmouseover=alert(1)\">x</a>",
        "<style>body{background:url(javascript:alert(1))}</style>",
        "<p style=\"background-image:url(javascript:alert(1))\">x</p>",
        "<p style=\"x:expression(alert(1))\">x</p>",
        "<link rel=stylesheet href=javascript:alert(1)>",
        "<svg onload=alert(1)>",
        "<svg/onload=alert(1)>",
        "<svg><script>alert(1)</script></svg>",
        "<svg><style><img src=x onerror=alert(1)></style></svg>",
        "<math><mtext><table><mglyph><style><img src=x onerror=alert(1)>",
        "<math href=\"javascript:alert(1)\">x</math>",
        "<iframe src=\"javascript:alert(1)\"></iframe>",
        "<iframe srcdoc=\"<script>alert(1)</script>\"></iframe>",
        "<object data=\"javascript:alert(1)\"></object>",
        "<embed src=\"javascript:alert(1)\">",
        "<template><script>alert(1)</script></template>",
        "<body onload=alert(1)>",
        "<meta http-equiv=\"refresh\" content=\"0;url=javascript:alert(1)\">",
        "<base href=\"javascript:alert(1)//\">",
        "<form action=javascript:alert(1)><button>x</button></form>",
        "<input autofocus onfocus=alert(1)>",
        "<details open ontoggle=alert(1)>",
        "<video><source onerror=alert(1)>",
        "<!--><script>alert(1)</script>-->",
        "<!--<script>alert(1)</script>-->",
        "<!-- --!><script>alert(1)</script>",
        "<!--[if IE]><script>alert(1)</script><![endif]-->",
        "<![CDATA[<script>alert(1)</script>]]>",
        "<?xml version=\"1.0\"?><script>alert(1)</script>",
        "</p><script>alert(1)</script>",
        "</div></div></body><script>alert(1)</script>",
        "<p>x</p></p><script>alert(1)</script>",
        "<noscript><p title=\"</noscript><img src=x onerror=alert(1)>\"></noscript>",
        "<textarea></textarea><script>alert(1)</script>",
        "<title><script>alert(1)</script></title>",
        "<xmp><script>alert(1)</script></xmp>",
        "<plaintext><script>alert(1)</script>",
        "<select><option><script>alert(1)</script></option></select>",
        "<a href=\"/x\">&lt;script&gt;alert(1)&lt;/script&gt;</a>",
        "&#60;script&#62;alert(1)&#60;/script&#62;",
        "&lt;img src=x onerror=alert(1)&gt;",
        "<p>\"><script>alert(1)</script></p>",
        "<p title=\"\"><script>alert(1)</script>\"></p>",
        "<div id=\"x\"><a href=\"//evil\" ping=\"//evil\">x</a></div>",
        "<a href=\"x\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" xlink:href=\"javascript:alert(1)\">x</a>",
        "<a href=\"'><script>alert(1)</script>\">x</a>",
        "<a href=\"\"><script>alert(1)</script>\">x</a>",
        "<a href=\"x\"\0onclick=alert(1)>x</a>",
        "<p\0onclick=alert(1)>x</p>",
        "<\0script>alert(1)</script>",
        "<img src=`x` onerror=`alert(1)`>",
        "<p title=`a` onclick=`alert(1)`>x</p>",
        "<svg></p><style><a title=\"</style><img src onerror=alert(1)>\">",
        "<math><mtext><table><mglyph><style><img src onerror=alert(1)>",
        "<noscript><p title=\"</noscript><img src=x onerror=alert(1)>\">",
        "<script><script></script>alert(1)</script>",
        "<script><!--<script></script>alert(1)</script>-->",
        "<style><style/><img src=x onerror=alert(1)>",
        "<script>x</scriptx><img src=x onerror=alert(1)>",
        "<p title=\"x",
        "<a href='javascript:alert(1)",
        "<a href=",
        "<a href=\"java\0script:alert(1)\">x</a>",
        "<a href=\"java\nscript:alert(1)\">x</a>",
        "<a href=\"java&NewLine;script:alert(1)\">x</a>",
        "<a href=\"javascript&#x09;:alert(1)\">x</a>",
        "<a href=\"javascript&#58alert(1)\">x</a>",
        "<a href=\"&#x110000;javascript:alert(1)\">x</a>",
        "<a href=\"&#0;javascript:alert(1)\">x</a>",
        "<a href=\"&#xD800;javascript:alert(1)\">x</a>",
        "<a href=\"&#1;javascript:alert(1)\">x</a>",
        "<a href=\"ja&#x0;vascript:alert(1)\">x</a>",
        "<a href=\"  javascript:alert(1)\">x</a>",
        "<a href=\"/x' onclick='alert(1)\">x</a>",
        "<base href=//evil>",
        "<link rel=import href=//evil>",
        "<meta http-equiv=refresh content=\"0;url=//evil\">",
        "<!-- a --->b<script>alert(1)</script>",
        "<!-- a -- ><script>alert(1)</script>-->",
        "<SVG><p>x</SVG><img src=x onerror=alert(1)>",
        "<svg/ ><script>alert(1)</script></svg>",
        "<div\u000bonclick=alert(1)>y</div>",
        "&#x3c;script&#x3e;alert(1)&#x3c;/script&#x3e;");
  }

  /** Output tokens that may appear: allowed tags, a checked link, or text without '<' or '>'. */
  private static final Pattern TOKEN =
      Pattern.compile(
          "</?(?:b|i|em|strong|u|s|sub|sup|small|mark|code|pre|blockquote|p|h[1-6]|ul|ol|li|span|div|a)>"
              + "|<(?:br|hr)>"
              + "|<a href=\"([^\"<>]*)\" rel=\"nofollow noopener\">"
              + "|[^<>]+");

  private static final Pattern TAG = Pattern.compile("<(/?)([a-z0-9]+)");

  @ParameterizedTest
  @MethodSource("vectors")
  void xssVectorsProduceNoExecutableOutput(String vector) {
    String out = clean(vector);
    assertSafe(vector, out);
    // re-sanitizing safe output is a no-op
    assertEquals(out, clean(out), vector);
  }

  @ParameterizedTest
  @MethodSource("vectors")
  void xssVectorsInsideAllowedMarkup(String vector) {
    assertSafe(vector, clean("<p><b>pre " + vector + " post</b></p><a href=\"/x\">" + vector + "</a>"));
  }

  private static void assertSafe(String vector, String out) {
    Matcher m = TOKEN.matcher(out);
    int pos = 0;
    while (pos < out.length()) {
      assertTrue(m.find(pos) && m.start() == pos, () -> "unexpected output " + out + " for " + vector);
      if (m.group(1) != null) {
        String href = m.group(1).replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&amp;", "&");
        assertFalse(href.contains("&"), () -> "undecoded reference in href " + out + " for " + vector);
        assertTrue(Checks.isAllowedUrl(href), () -> "unsafe href " + out + " for " + vector);
      }
      pos = m.end();
    }
    String lower = out.toLowerCase(Locale.ROOT);
    for (String bad : new String[] {"<script", "<svg", "<math", "<style", "<iframe", "<img", "<!--", "javascript:", "vbscript:", "data:"}) {
      assertFalse(lower.contains(bad), () -> "found " + bad + " in " + out + " for " + vector);
    }
    // balanced: every end tag closes the most recent open element and nothing stays open
    Deque<String> stack = new ArrayDeque<>();
    Matcher t = TAG.matcher(out);
    while (t.find()) {
      String name = t.group(2);
      if (name.equals("br") || name.equals("hr")) {
        continue;
      }
      if (t.group(1).isEmpty()) {
        stack.push(name);
      } else {
        assertEquals(name, stack.poll(), () -> "unbalanced " + out + " for " + vector);
      }
    }
    assertTrue(stack.isEmpty(), () -> "unclosed " + out + " for " + vector);
  }
}
