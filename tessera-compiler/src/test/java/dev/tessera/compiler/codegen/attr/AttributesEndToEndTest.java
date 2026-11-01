package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.TrustedUrl;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** t:attr, generic t:&lt;attr&gt;, boolean attributes and t:classappend through the real processor. */
class AttributesEndToEndTest {

  private static final String HEADER =
      "<div xmlns:t=\"https://tessera.dev\""
          + " t:params=\"String s, String c, TrustedUrl u, boolean b, Boolean nb, Integer n\">";

  private static Compilation compile(String body) {
    return Compilation.builder().template("t.html", HEADER + body + "</div>").compile();
  }

  private static String render(String body, Map<String, ?> model) {
    Compilation c = compile(body).assertSuccess();
    String out = c.render("t", model);
    String prefix = "<div>";
    return out.substring(prefix.length(), out.length() - "</div>".length());
  }

  private static Map<String, Object> model(Object... kv) {
    Map<String, Object> m = new HashMap<>(Map.of("b", false)); // primitive param: needs a value
    m.putAll(Compilation.model(kv));
    return m;
  }

  // ------------------------------------------------------------- URLs

  @Test
  void urlAttributesUseTheUrlEscaper() {
    assertEquals("<a href=\"/owners?id=1&amp;x=2\">x</a>", render("<a t:href=\"s\">x</a>", model("s", "/owners?id=1&x=2")));
    assertEquals("<a href=\"about:invalid#tessera\">x</a>", render("<a t:href=\"s\">x</a>", model("s", "javascript:alert(1)")));
    assertEquals("<img src=\"about:invalid#tessera\">", render("<img t:src=\"s\">", model("s", " JaVaScRiPt:alert(1)")));
    assertEquals(
        "<q cite=\"about:invalid#tessera\"></q>",
        render("<q t:cite=\"s\"></q>", model("s", "data:text/html,<script>")));
    assertEquals(
        "<a href=\"about:invalid#tessera\">x</a>",
        render("<a t:attr=\"href=s\">x</a>", model("s", "vbscript:x")));
  }

  /**
   * The {@code values to from by} of an SVG {@code <animate>} or {@code <set>} are not exactly one
   * URL (#539): {@code values} is a {@code ;}-separated list, and what any of them holds depends on
   * the {@code attributeName}. A String is a build error, wherever it sits in the value.
   */
  @Test
  void animationValuesRejectStrings() {
    for (String t : new String[] {
      "<svg><a><animate attributeName=\"href\" t:values=\"s\"/></a></svg>",
      "<svg><a><animate attributeName=\"href\" t:values=\"|/a;${s}|\"/></a></svg>",
      "<svg><a><animate attributeName=\"href\" t:values=\"'/a;' + s\"/></a></svg>",
      "<svg><a><set attributeName=\"href\" t:attr=\"values=s\"/></a></svg>",
      "<svg><a><ANIMATE attributeName=\"href\" t:VALUES=\"s\"/></a></svg>",
      "<svg><a><set attributeName=\"href\" t:to=\"s\"/></a></svg>",
      "<svg><a><animate attributeName=\"href\" t:from=\"s\"/></a></svg>",
      "<svg><a><animate attributeName=\"href\" t:by=\"s\"/></a></svg>",
      "<svg><a><set attributeName=\"href\" t:to=\"@{/x}\"/></a></svg>",
      "<svg><rect><animate attributeName=\"opacity\" t:values=\"|0;${s}|\"/></rect></svg>",
      "<svg><rect><set attributeName=\"width\" t:to=\"${b} ? s : '1'\"/></rect></svg>",
    }) {
      compile(t).assertError("t.html:1:", "is not allowed", "holds URLs but is not exactly one URL", "TrustedUrl",
          "String", "choose between literals");
    }
  }

  /**
   * What an animation value may still be: literals, numbers, a choice between literals and a
   * TrustedUrl. A colour animation's values are CSS, like the attribute it animates: literals and
   * css values.
   */
  @Test
  void animationValuesTakeLiteralsNumbersAndTrustedUrls() {
    assertEquals(
        "<svg><rect><animate attributeName=\"opacity\" values=\"0;5\"/></rect></svg>",
        render("<svg><rect><animate attributeName=\"opacity\" t:values=\"|0;${n}|\"/></rect></svg>", model("n", 5)));
    assertEquals(
        "<svg><rect><set attributeName=\"width\" to=\"7\"/></rect></svg>",
        render("<svg><rect><set attributeName=\"width\" t:to=\"n\"/></rect></svg>", model("n", 7)));
    assertEquals(
        "<svg><rect><animate attributeName=\"opacity\" from=\"1\"/></rect></svg>",
        render("<svg><rect><animate attributeName=\"opacity\" t:from=\"${b} ? '0' : '1'\"/></rect></svg>", model()));
    assertEquals(
        "<svg><rect><animate attributeName=\"fill\" values=\"red;#00f\"/></rect></svg>",
        render("<svg><rect><animate attributeName=\"fill\" t:values=\"|red;${css.color(c)}|\"/></rect></svg>",
            model("c", "#00f")));
    assertEquals(
        "<svg><a><animate attributeName=\"href\" values=\"javascript:void(0);/a\"/></a></svg>",
        render("<svg><a><animate attributeName=\"href\" t:values=\"u\"/></a></svg>",
            model("u", TrustedUrl.unchecked("javascript:void(0);/a"))));
    assertEquals(
        "<svg><a><set attributeName=\"href\" to=\"/x;jsessionid=1\"/></a></svg>",
        render("<svg><a><set attributeName=\"href\" t:attr=\"to=u\"/></a></svg>",
            model("u", TrustedUrl.unchecked("/x;jsessionid=1"))));
  }

  /** {@code usemap} is a hash-name reference, not a URL (#539): literals only, like other typed attributes. */
  @Test
  void usemapIsNotAUrl() {
    assertEquals("<img usemap=\"#m\">", render("<img t:usemap=\"${b} ? '#n' : '#m'\">", model()));
    compile("<img t:usemap=\"s\">").assertError("t.html:1:", "t:usemap is not allowed", "not an inert-text attribute");
  }

  @Test
  void attributesThatDifferBeyondAsciiCaseAllRender() {
    // A browser lower-cases A-Z only, so these are two attributes each, and both are written.
    String p = "<p data-\u00C9=\"a\" data-\u00E9=\"b\">x</p>";
    assertEquals(p, render(p, model()));
    String track = "<video><track \u212Aind=\"a\" kind=\"b\"></video>";
    assertEquals(track, render(track, model()));
  }

  @Test
  void trustedUrlPasses() {
    assertEquals(
        "<a href=\"javascript:void(0)\">x</a>",
        render("<a t:href=\"u\">x</a>", model("u", TrustedUrl.unchecked("javascript:void(0)"))));
  }

  @Test
  void resourceUrlAcceptsTrustedUrl() {
    assertEquals(
        "<script src=\"https://cdn.example/app.js\"></script>",
        render("<script t:src=\"u\"></script>", model("u", TrustedUrl.unchecked("https://cdn.example/app.js"))));
  }

  @Test
  void resourceUrlAcceptsLiteralLink() {
    Compilation c = Compilation.builder().template("t.html", HEADER + "<script t:src=\"@{/js/app.js}\"></script></div>").compile();
    assertEquals("<div><script src=\"/js/app.js\"></script></div>", c.assertSuccess().render("t", model()));
  }

  @Test
  void resourceUrlRejectsStrings() {
    compile("<script t:src=\"s\"></script>").assertError("t.html:1:", "t:src on <script>", "@{/js/app.js}", "TrustedUrl");
    compile("<iframe t:src=\"'/x'\"></iframe>").assertError("t:src on <iframe>", "literal-path link");
    compile("<link t:href=\"s\">").assertError("t:href on <link>");
    compile("<script t:attr=\"src=s\"></script>").assertError("t:attr src on <script>");
    compile("<object t:data=\"s + 'x'\"></object>").assertError("t:data on <object>");
  }

  /** {@code manifest} is a URL: the scheme check applies. */
  @Test
  void manifestIsAUrl() {
    Compilation c = Compilation.builder()
        .template("m.html", "<html xmlns:t=\"https://tessera.dev\" t:params=\"String s\" t:manifest=\"s\"><body></body></html>")
        .compile()
        .assertSuccess();
    String ok = c.render("m", Compilation.model("s", "/app.appcache"));
    assertTrue(ok.contains("<html manifest=\"/app.appcache\">"), ok);
    String bad = c.render("m", Compilation.model("s", "javascript:alert(1)"));
    assertTrue(bad.contains("<html manifest=\"about:invalid#tessera\">"), bad);
  }

  /** {@code srcset} and {@code imagesrcset} are lists of image candidates: no String compiles (#415, #539). */
  @Test
  void srcsetAcceptsTrustedUrl() {
    assertEquals(
        "<img srcset=\"/a.png 1x, /b.png 2x\">",
        render("<img t:srcset=\"u\">", model("u", TrustedUrl.unchecked("/a.png 1x, /b.png 2x"))));
    assertEquals(
        "<link rel=\"preload\" as=\"image\" imagesrcset=\"/a.png 100w\">",
        render("<link rel=\"preload\" as=\"image\" t:imagesrcset=\"u\">", model("u", TrustedUrl.unchecked("/a.png 100w"))));
    assertEquals(
        "<picture><source srcset=\"/a.webp\"></picture>",
        render("<picture><source t:attr=\"srcset=u\"></picture>", model("u", TrustedUrl.unchecked("/a.webp"))));
  }

  @Test
  void srcsetRejectsStrings() {
    compile("<img t:srcset=\"s\">").assertError(
        "t.html:1:", "t:srcset is not allowed: 'srcset' on <img> holds URLs but is not exactly one URL", "TrustedUrl",
        "String");
    compile("<img t:srcset=\"'/a.png 1x, ' + s\">").assertError("'srcset' on <img>", "TrustedUrl", "String");
    compile("<img t:srcset=\"@{/a.png}\">").assertError("'srcset' on <img>", "TrustedUrl");
    compile("<source t:attr=\"srcset=s\">").assertError("t:attr srcset is not allowed: 'srcset' on <source>", "TrustedUrl");
    compile("<link t:imagesrcset=\"s\">").assertError("'imagesrcset' on <link>", "TrustedUrl");
    compile("<img t:SrcSet=\"s\">").assertError("'SrcSet' on <img>", "TrustedUrl");
  }

  /** A literal list is the template author's own, so it compiles, as a static attribute does (#539). */
  @Test
  void urlListsAcceptLiteralsAndNumbers() {
    assertEquals("<img srcset=\"/a.png 1x, /b.png 2x\">", render("<img t:srcset=\"'/a.png 1x, /b.png 2x'\">", model()));
    assertEquals("<img srcset=\"/a.png 640w\">", render("<img t:srcset=\"|/a.png ${n}w|\">", model("n", 640)));
    assertEquals(
        "<img srcset=\"/a.png 1x, /b.png 2x\">",
        render("<img t:srcset=\"|${u} 1x, /b.png 2x|\">", model("u", TrustedUrl.unchecked("/a.png"))));
    assertEquals("<a ping=\"/b\">x</a>", render("<a t:ping=\"${b} ? '/a' : '/b'\">x</a>", model()));
  }

  /** {@code ping} is a whitespace-separated list of URLs: no String compiles (#414, #539). */
  @Test
  void pingAcceptsTrustedUrl() {
    assertEquals(
        "<a ping=\"/a https://t.example/b\">x</a>",
        render("<a t:ping=\"u\">x</a>", model("u", TrustedUrl.unchecked("/a https://t.example/b"))));
    assertEquals(
        "<area ping=\"/a\">",
        render("<area t:attr=\"ping=u\">", model("u", TrustedUrl.unchecked("/a"))));
  }

  @Test
  void pingRejectsStrings() {
    compile("<a t:ping=\"s\">x</a>").assertError(
        "t.html:1:", "t:ping is not allowed: 'ping' on <a> holds URLs but is not exactly one URL", "TrustedUrl",
        "String");
    compile("<a t:ping=\"'/a ' + s\">x</a>").assertError("'ping' on <a>", "TrustedUrl", "String");
    compile("<a t:ping=\"@{/track}\">x</a>").assertError("'ping' on <a>", "TrustedUrl");
    compile("<area t:attr=\"ping=s\">").assertError("t:attr ping is not allowed: 'ping' on <area>", "TrustedUrl");
    compile("<a t:PING=\"s\">x</a>").assertError("'PING' on <a>", "TrustedUrl");
    compile("<svg><a t:ping=\"s\"></a></svg>").assertError("'ping' on <a>", "TrustedUrl");
  }

  /** A type-use annotation does not change the type: the RESOURCE_URL gate still sees TrustedUrl. */
  @Test
  void resourceUrlAcceptsTypeAnnotatedTrustedUrl() {
    Compilation c = annotatedPage("<script t:src=\"p.url\"></script>").compile().assertSuccess();
    Object page = c.newInstance("com.example.Page", TrustedUrl.unchecked("https://cdn.example/app.js"), "x");
    assertEquals("<div><script src=\"https://cdn.example/app.js\"></script></div>", c.render("t", Map.of("p", page)));
  }

  @Test
  void resourceUrlRejectsTypeAnnotatedString() {
    annotatedPage("<script t:src=\"p.name\"></script>").compile().assertError("t:src on <script>", "TrustedUrl");
  }

  private static Compilation.Builder annotatedPage(String body) {
    return Compilation.builder()
        .java(
            "com.example.Ann",
            "package com.example; @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)"
                + " public @interface Ann {}")
        .java(
            "com.example.Page",
            "package com.example; public record Page(dev.tessera.runtime.@Ann TrustedUrl url, @Ann String name) {}")
        .template("t.html", "<div xmlns:t=\"https://tessera.dev\" t:params=\"com.example.Page p\">" + body + "</div>");
  }

  // ------------------------------------------------------------- forbidden contexts

  @Test
  void eventHandlerAttributesAreBuildErrors() {
    compile("<p t:onclick=\"s\">x</p>").assertError("t.html:1:", "t:onclick is not allowed", "data-*");
    compile("<p t:ONMOUSEOVER=\"s\">x</p>").assertError("not allowed", "event-handler");
    compile("<p t:attr=\"onclick=s\">x</p>").assertError("t:attr onclick is not allowed", "data-*");
    compile("<p t:attr=\"title=s, onload=s\">x</p>").assertError("t:attr onload is not allowed");
  }

  @Test
  void styleAndSrcdocAreBuildErrors() {
    compile("<p t:style=\"s\">x</p>").assertError("t:style is not allowed", "t:class");
    compile("<p t:attr=\"style=s\">x</p>").assertError("t:attr style is not allowed");
    compile("<iframe t:srcdoc=\"s\"></iframe>").assertError("t:srcdoc is not allowed", "use src");
    compile("<iframe t:attr=\"srcdoc=s\"></iframe>").assertError("t:attr srcdoc is not allowed");
  }

  @Test
  void metaRefreshContentIsABuildError() {
    // #46: the url= part of a refresh navigates the page (open redirect). Only a delay and a
    // same-origin link are allowed (#237).
    compile("<meta http-equiv=\"refresh\" t:content=\"s\">")
        .assertError("t:content is not allowed", "refresh", "'3;url=' + @{", "controller");
    compile("<meta http-equiv=\"Refresh\" t:attr=\"content=s\">").assertError("t:attr content is not allowed");
    compile("<meta http-equiv=\"refresh\" t:content=\"'1;url=' + s\">").assertError("t:content is not allowed");
    compile("<meta http-equiv=\"refresh\" t:content=\"@{/x}\">").assertError("t:content is not allowed");
    compile("<meta http-equiv=\"refresh\" t:content=\"s + ';url=' + @{/x}\">").assertError("t:content is not allowed");
    compile("<meta http-equiv=\"refresh\" t:content=\"|1;url=${s}|\">").assertError("t:content is not allowed");
    compile("<meta http-equiv=\"refresh\" t:content=\"'1;url=/x' + @{/y}\">").assertError("t:content is not allowed");
    compile("<meta http-equiv=\"refresh\" t:content=\"'1;url=' + @{/x} + s\">").assertError("t:content is not allowed");
    compile("<meta http-equiv=\"refresh\" t:content=\"'1;url=' + @{${s}}\">").assertError("dynamic link paths");
    // Only a same-origin literal path: no absolute, protocol-relative or page-relative link.
    for (String path : new String[] {"https://evil.example/x", "//evil.example/x", "/\\evil.example/x",
        "javascript:x", "data:text/html,x", "x/y"}) {
      compile("<meta http-equiv=\"refresh\" t:content=\"'1;url=' + @{" + path + "}\">")
          .assertError("t:content is not allowed", "same-origin path");
    }
    compile("<meta http-equiv=\"refresh\" t:attr=\"content='1;url=' + @{//evil.example}\">")
        .assertError("t:attr content is not allowed", "same-origin path");
    compile("<meta t:http-equiv=\"s\" t:content=\"c\">").assertError("t:http-equiv", "<meta>", "static http-equiv");
    compile("<meta t:attr=\"http-equiv=s\" content=\"0;url=/x\">").assertError("t:attr http-equiv", "<meta>");
    // A literal refresh written by the author is fine, and so is other meta content.
    assertEquals(
        "<meta http-equiv=\"refresh\" content=\"5\">",
        render("<meta http-equiv=\"refresh\" content=\"5\">", model()));
    assertEquals(
        "<meta name=\"description\" content=\"a&lt;b\">",
        render("<meta name=\"description\" t:content=\"s\">", model("s", "a<b")));
  }

  @Test
  void attributesTheTreeBuilderReadsAreABuildError() {
    // A browser reads these values while it builds the page, before any escaping applies.
    compile("\n<p t:is=\"s\">x</p>").assertError("t.html:2:4", "t:is is not allowed", "custom element");
    compile("\n<p t:attr=\"is=s\">x</p>").assertError("t.html:2:", "t:attr is is not allowed", "custom element");
    compile("\n<svg t:xmlns=\"s\"></svg>").assertError("t.html:2:", "t:xmlns is not allowed", "namespace");
    compile("\n<svg t:xmlns:xlink=\"s\"></svg>").assertError("t.html:2:", "t:xmlns:xlink is not allowed", "namespace");
    compile("\n<template t:shadowrootmode=\"s\"><p>x</p></template>")
        .assertError("t.html:2:", "t:shadowrootmode is not allowed", "shadow root");
    compile("\n<template shadowrootmode=\"open\" t:shadowrootclonable=\"b\"><p>x</p></template>")
        .assertError("t.html:2:", "t:shadowrootclonable is not allowed", "shadow root");
    compile("\n<template t:for=\"s\"><p>x</p></template>").assertError("t.html:2:", "t:for is not allowed", "<template>");
    compile("\n<meta t:charset=\"s\">").assertError("t.html:2:7", "t:charset is not allowed", "encoding");
    compile("\n<meta http-equiv=\"Content-Type\" t:content=\"s\">")
        .assertError("t.html:2:", "t:content is not allowed", "encoding", "Content-Type response header");
    compile("\n<meta t:content=\"s\" http-equiv=\"content-type\">").assertError("t.html:2:", "t:content is not allowed");
    compile("\n<meta http-equiv=\"Content-Type\" t:attr=\"content=s\">").assertError("t.html:2:", "t:attr content is not allowed");
    // The same values written statically, and the same names where no tree builder reads them, compile.
    assertEquals(
        "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\"><meta charset=\"utf-8\">",
        render("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\"><meta charset=\"utf-8\">",
            model()));
    compile("<p is=\"x-p\">x</p><svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"
        + "<template shadowrootmode=\"open\" for=\"x\"><p>x</p></template>").assertSuccess();
    compile("<meta name=\"description\" t:content=\"s\">"
        + "<label t:for=\"s\">x</label>").assertSuccess();
    // #539: shadowrootmode is no inert-text attribute anywhere, and content under another http-equiv takes a safe value.
    compile("<meta http-equiv=\"content-language\" t:content=\"s\">").assertError("t.html:1:", "t:content is not allowed", "inert-text");
    compile("<div t:shadowrootmode=\"s\">x</div>").assertError("t.html:1:", "t:shadowrootmode is not allowed", "inert-text");
  }

  @Test
  void metaRefreshAcceptsADelayAndASameOriginLink() {
    // #237: '<seconds>;url=' + @{/path...}, normalized to <seconds>;url=<url>.
    assertEquals(
        "<meta http-equiv=\"refresh\" content=\"1;url=/admin/update?vid=7\">",
        render("<meta http-equiv=\"refresh\" t:content=\"'1;url=' + @{/admin/update(vid=${n})}\">", model("n", 7)));
    assertEquals(
        "<meta http-equiv=\"REFRESH\" content=\"3;url=/blog/7\">",
        render("<meta http-equiv=\"REFRESH\" t:attr=\"content=' 3 , URL = ' + @{/blog/{n}(n=${n})}\">", model("n", 7)));
    // A path variable cannot add a second url=, leave the path or the origin.
    assertEquals(
        "<meta http-equiv=\"refresh\" content=\"0;url=/p/x%3Burl%3D%2F%2Fevil%20%27%22%2C\">",
        render("<meta http-equiv=\"refresh\" t:content=\"'0;url=' + @{/p/{s}(s=${s})}\">",
            model("s", "x;url=//evil '\",")));
    // Query values keep ; , and ' readable for links; in a refresh they are encoded too.
    assertEquals(
        "<meta http-equiv=\"refresh\" content=\"0;url=/p?q=a%3Burl%3D//evil%2C%27%20%22\">",
        render("<meta http-equiv=\"refresh\" t:content=\"'0;url=' + @{/p(q=${s})}\">",
            model("s", "a;url=//evil,' \"")));
  }

  @Test
  void scriptTypeCannotBeComputed() {
    compile("<script t:type=\"s\"></script>").assertError("t:type", "<script>", "static type attribute");
    compile("<script t:attr=\"type=s\"></script>").assertError("t:attr type", "<script>");
    compile("<script type=\"application/json\" t:attr=\"type=s\"></script>").assertError("t:attr type");
    // type on other elements is an ordinary attribute.
    assertEquals("<input type=\"text\">", render("<input t:type=\"s\">", model("s", "text")));
  }

  @Test
  void tPrefixedNamesInAttrAreRejected() {
    compile("<p t:attr=\"t:text=s\">x</p>").assertError("t:attr cannot set t:text");
  }

  @Test
  void unknownVariableIsReported() {
    compile("<p t:title=\"nope\">x</p>").assertError("t.html:1:", "nope");
  }

  // ------------------------------------------------------------- values

  @Test
  void nullOmitsTheAttribute() {
    assertEquals("<p>x</p>", render("<p t:title=\"s\">x</p>", model()));
    assertEquals("<a>x</a>", render("<a t:href=\"s\">x</a>", model()));
    assertEquals("<p>x</p>", render("<p title=\"static\" t:title=\"s\">x</p>", model()));
    assertEquals("<p>x</p>", render("<p t:attr=\"title=s, id=n\">x</p>", model()));
  }

  @Test
  void quotesAndMarkupInValuesAreEscaped() {
    assertEquals(
        "<p title=\"&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;\">x</p>",
        render("<p t:title=\"s\">x</p>", model("s", "\"><script>alert(1)</script>")));
    assertEquals(
        "<p title=\"&#39; onmouseover=&#39;alert(1)\">x</p>",
        render("<p t:title=\"s\">x</p>", model("s", "' onmouseover='alert(1)")).replace("&#x27;", "&#39;"));
    assertEquals(
        "<a href=\"/x?&quot;&gt;\">x</a>", render("<a t:href=\"s\">x</a>", model("s", "/x?\">")));
  }

  @Test
  void attrSetsSeveralAttributes() {
    assertEquals(
        "<input value=\"v\" id=\"i-3\" data-n=\"3\">",
        render("<input t:attr=\"value=s, id=|i-${n}|, data-n=n\">", model("s", "v", "n", 3)));
  }

  @Test
  void dataAttributesAndIds() {
    assertEquals(
        "<p id=\"a\" data-owner-id=\"7\">x</p>",
        render("<p t:id=\"s\" t:data-owner-id=\"n\">x</p>", model("s", "a", "n", 7)));
  }

  @Test
  void dynamicReplacesStaticInPlace() {
    assertEquals(
        "<p id=\"a\" class=\"dyn\" title=\"t\">x</p>",
        render("<p id=\"a\" class=\"static\" t:class=\"c\" title=\"t\">x</p>", model("c", "dyn")));
    assertEquals(
        "<input type=\"text\" value=\"new\">",
        render("<input type=\"text\" value=\"old\" t:value=\"s\">", model("s", "new")));
  }

  @Test
  void booleanAttributes() {
    String t = "<input type=\"checkbox\" t:checked=\"b\" t:disabled=\"nb\" t:attr=\"required=s\">";
    // t:attr runs before generic t:<attr> (AttributeProcessors.ORDER).
    assertEquals(
        "<input type=\"checkbox\" required=\"required\" checked=\"checked\" disabled=\"disabled\">",
        render(t, model("b", true, "nb", Boolean.TRUE, "s", "yes")));
    assertEquals("<input type=\"checkbox\">", render(t, model("b", false)));
    assertEquals(
        "<option selected=\"selected\">a</option>",
        render("<option t:selected=\"n == 1\">a</option>", model("n", 1)));
    assertEquals("<option>a</option>", render("<option selected t:selected=\"n == 1\">a</option>", model("n", 2)));
  }

  // ------------------------------------------------------------- classappend

  @Test
  void classAppendToStaticClass() {
    assertEquals("<p class=\"a b\">x</p>", render("<p class=\"a\" t:classappend=\"s\">x</p>", model("s", "b")));
    assertEquals("<p class=\"a\">x</p>", render("<p class=\"a\" t:classappend=\"s\">x</p>", model()));
    assertEquals("<p class=\"a\">x</p>", render("<p class=\"a\" t:classappend=\"s\">x</p>", model("s", "")));
    assertEquals(
        "<p class=\"a &amp; b\">x</p>",
        render("<p class=\"a &amp;\" t:classappend=\"s\">x</p>", model("s", "b")));
  }

  @Test
  void classAppendToDynamicClass() {
    String t = "<p t:class=\"c\" t:classappend=\"b ? 'on' : null\">x</p>";
    assertEquals("<p class=\"x on\">x</p>", render(t, model("c", "x", "b", true)));
    assertEquals("<p class=\"x\">x</p>", render(t, model("c", "x", "b", false)));
    assertEquals("<p class=\"on\">x</p>", render(t, model("b", true)));
    assertEquals("<p>x</p>", render(t, model("b", false)));
  }

  @Test
  void classAppendWithoutClass() {
    assertEquals("<p id=\"i\" class=\"b\">x</p>", render("<p id=\"i\" t:classappend=\"s\">x</p>", model("s", "b")));
    assertEquals("<p id=\"i\">x</p>", render("<p id=\"i\" t:classappend=\"s\">x</p>", model()));
  }

  @Test
  void bemClassNamesWithDoubleUnderscore() {
    // #234: __ is only rejected as Thymeleaf preprocessing (__${...}__), not in literals.
    assertEquals(
        "<p class=\"card__title\">x</p>", render("<p class=\"card__title\">x</p>", model()));
    assertEquals(
        "<p class=\"card__item card__item--on\">x</p>",
        render("<p t:class=\"'card__item card__item--' + s\">x</p>", model("s", "on")));
    assertEquals(
        "<p class=\"card card__item--on\">x</p>",
        render("<p class=\"card\" t:classappend=\"b ? 'card__item--on' : null\">x</p>", model("b", true)));
    assertEquals(
        "<p class=\"card__item card__item--on\">x</p>",
        render("<p t:class=\"|card__item card__item--${s}|\">x</p>", model("s", "on")));
    assertEquals("<p>a__b</p>", render("<p t:text=\"'a__b'\">x</p>", model()));
  }

  @Test
  void preprocessingIsStillRejected() {
    String message = "preprocessing (__...__) is not supported: expressions are never built from strings";
    compile("<p t:class=\"__${s}__\">x</p>").assertError("t.html:1:", message);
    compile("<p t:classappend=\"'card__' + __${s}__\">x</p>").assertError("t.html:1:", message);
    compile("<p t:text=\"'a__${s}__'\">x</p>").assertError("t.html:1:", message);
    compile("<p t:class=\"|card__${s}__|\">x</p>").assertError("t.html:1:", message);
  }

  @Test
  void classAppendIsEscaped() {
    assertEquals(
        "<p class=\"a &quot; onclick=&quot;x\">x</p>",
        render("<p class=\"a\" t:classappend=\"s\">x</p>", model("s", "\" onclick=\"x")));
  }
}
