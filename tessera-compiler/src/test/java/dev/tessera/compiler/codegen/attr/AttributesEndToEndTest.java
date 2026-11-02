package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Generic t:&lt;attr&gt;, boolean attributes and t:classappend through the real processor. */
class AttributesEndToEndTest {

  private static final String HEADER =
      "<div xmlns:t=\"https://tessera.dev\""
          + " t:params=\"String s, String c, boolean b, Boolean nb, Integer n\">";

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
        render("<a t:href=\"s\">x</a>", model("s", "vbscript:x")));
  }

  /** Negative (#668): no type vouches for a URL, so nothing skips the scheme or origin checks. */
  @Test
  void trustedUrlDoesNotExist() {
    Compilation.builder()
        .java("com.example.Raw", "package com.example; public final class Raw {"
            + " static Object raw(String s) { return dev.tessera.runtime.TrustedUrl.unchecked(s); } }")
        .compile()
        .assertError("cannot find symbol", "TrustedUrl");
  }

  /** Negative (#668): nor can a template take one as a parameter. */
  @Test
  void trustedUrlParamIsABuildError() {
    Compilation.builder()
        .template("t.html", "<div xmlns:t=\"https://tessera.dev\" t:params=\"TrustedUrl u\"><a t:href=\"u\">x</a></div>")
        .compile()
        .assertError("t.html:1:", "TrustedUrl");
  }

  /**
   * Negative: names that once were typed attributes or URLs ({@code usemap}, #539; {@code manifest},
   * {@code background}, {@code longdesc}) are off the closed allow-list (#761), literals included.
   */
  @Test
  void namesOffTheListTakeNothing() {
    for (String body : new String[] {"<img t:usemap=\"${b} ? '#n' : '#m'\">", "<img t:usemap=\"s\">",
        "<img t:longdesc=\"s\">", "<body t:background=\"'/a.png'\"></body>", "<a t:xlink:href=\"s\">x</a>"}) {
      compile(body).assertError("t.html:1:", "is not allowed", "is not on Tessera's list of computed attributes");
    }
    Compilation c = Compilation.builder()
        .template("m.html", "<html xmlns:t=\"https://tessera.dev\" t:params=\"String s\" t:manifest=\"s\"><body></body></html>")
        .compile();
    c.assertError("m.html:1:", "t:manifest is not allowed", "is not on Tessera's list");
  }

  @Test
  void attributesThatDifferBeyondAsciiCaseAllRender() {
    // A browser lower-cases A-Z only, so these are two attributes each, and both are written.
    String p = "<p data-\u00C9=\"a\" data-\u00E9=\"b\">x</p>";
    assertEquals(p, render(p, model()));
    String track = "<video><track \u212Aind=\"a\" kind=\"b\"></video>";
    assertEquals(track, render(track, model()));
  }

  /** A same-origin literal-path link; another origin is a static attribute (#756, LinkCompilerTest). */
  @Test
  void resourceUrlAcceptsLiteralLink() {
    Compilation c = Compilation.builder().template("t.html", HEADER + "<script t:src=\"@{/js/app.js}\"></script></div>").compile();
    assertEquals("<div><script src=\"/js/app.js\"></script></div>", c.assertSuccess().render("t", model()));
    assertEquals("<script src=\"/js/1/app.js\"></script>",
        render("<script t:src=\"@{/js/{v}/app.js(v=${n})}\"></script>", model("n", 1)));
  }

  /** Negative: no computed value but a literal-path link loads code or a document (#668: no safe type either). */
  @Test
  void resourceUrlRejectsStrings() {
    compile("<script t:src=\"s\"></script>")
        .assertError("t.html:1:", "t:src on <script> loads code or a document: use a literal-path link such as"
            + " @{/js/app.js}, not a java.lang.String");
    compile("<iframe t:src=\"'/x'\"></iframe>").assertError("t:src on <iframe>", "literal-path link");
    compile("<link t:href=\"s\">").assertError("t:href on <link>");
    compile("<script t:src=\"s\"></script>").assertError("t:src on <script>");
    compile("<object t:data=\"s + 'x'\"></object>").assertError("t:data on <object>");
  }

  /**
   * Negative: {@code srcset}, {@code imagesrcset} and {@code ping} are lists of URLs, which Tessera doesn't
   * check: nothing computed compiles, not even a literal (#678; before, TYPED_ATTRIBUTE, #539, #668).
   */
  @Test
  void urlListsCannotBeComputed() {
    for (String t : new String[] {"<img t:srcset=\"s\">", "<img t:srcset=\"'/a.png 1x'\">", "<img t:srcset=\"@{/a.png}\">",
        "<source t:srcset=\"s\">", "<link t:imagesrcset=\"s\">", "<img t:SrcSet=\"s\">", "<a t:ping=\"'/a'\">x</a>",
        "<area t:ping=\"s\">", "<a t:PING=\"${b} ? '/a' : '/b'\">x</a>"}) {
      compile(t).assertError("t.html:1:", "is not allowed: it holds a list of URLs", "compute a single src or href");
    }
  }

  /** Negative: a type-use annotation does not change the type: the RESOURCE_URL gate still sees a String. */
  @Test
  void resourceUrlRejectsTypeAnnotatedString() {
    annotatedPage("<script t:src=\"p.name\"></script>").compile().assertError("t:src on <script>", "literal-path link");
  }

  private static Compilation.Builder annotatedPage(String body) {
    return Compilation.builder()
        .java(
            "com.example.Ann",
            "package com.example; @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)"
                + " public @interface Ann {}")
        .java(
            "com.example.Page",
            "package com.example; public record Page(@Ann String name) {}")
        .template("t.html", "<div xmlns:t=\"https://tessera.dev\" t:params=\"com.example.Page p\">" + body + "</div>");
  }

  // ------------------------------------------------------------- forbidden contexts

  @Test
  void eventHandlerAttributesAreBuildErrors() {
    compile("<p t:onclick=\"s\">x</p>").assertError("t.html:1:", "t:onclick is not allowed", "data-*");
    compile("<p t:ONMOUSEOVER=\"s\">x</p>").assertError("not allowed", "event-handler");
    compile("<p t:title=\"s\" t:onload=\"s\">x</p>").assertError("t:onload is not allowed");
  }

  /**
   * Negative (#755): static inline script, which a strict nonce CSP blocks, is a build error at the
   * attribute: {@code on*} on any element, and a {@code javascript:} URL in a URL attribute however
   * a browser still reads the scheme (case, character references, leading spaces, tab/CR/LF).
   */
  @Test
  void staticInlineScriptIsBuildError() {
    String col = "t.html:1:" + (HEADER.length() + 4) + ":";
    compile("<a onclick=\"x()\">x</a>").assertError(col, "onclick on <a> is not allowed", "addEventListener", "data-*");
    for (String t : new String[] {"<p ONMOUSEOVER=\"x()\">x</p>", "<svg><circle onload=\"x()\"/></svg>",
        "<img src=\"/a.png\" onerror=x()>"}) {
      compile(t).assertError("t.html:1:", "is not allowed: inline event handlers are not supported");
    }
    compile("<a href=\"javascript:void(0)\">x</a>").assertError(col, "href on <a> is not allowed", "javascript: URLs");
    for (String t : new String[] {"<a href=\"JavaScript:x()\">x</a>", "<a href=\" java&#x09;script:x()\">x</a>",
        "<a href=\"jav&#x61;script:x()\">x</a>", "<form action=\"javascript:x()\"></form>",
        "<iframe src=\"javascript:x()\"></iframe>", "<object data=\"javascript:x()\"></object>",
        "<svg><a href=\"javascript:x()\"><text>x</text></a></svg>",
        // Named references the parser keeps but a browser decodes (: tab LF).
        "<a href=\"javascript&colon;x()\">x</a>", "<a href=\"java&Tab;script:x()\">x</a>",
        "<a href=\"&NewLine;javascript:x()\">x</a>", "<a href=\"java&Tab;script&colon;x()\">x</a>"}) {
      compile(t).assertError("t.html:1:", "is not allowed: javascript: URLs are not supported");
    }
  }

  /** Positive (#755): {@code href="#"}, data-* and values that only mention javascript stay accepted. */
  @Test
  void staticNonScriptStaysAccepted() {
    compile("<a href=\"#\" data-action=\"save\" title=\"javascript:x\">x</a><a href=\"/javascript:x\">y</a>"
        + "<a href=\"java script:x\">z</a><a href=\"javascripts.html\">j</a><input value=\"javascript:x\"><button type=\"button\">b</button>")
        .assertSuccess();
  }

  @Test
  void styleAndSrcdocAreBuildErrors() {
    compile("<p t:style=\"s\">x</p>").assertError("t:style is not allowed", "t:class");
    compile("<iframe t:srcdoc=\"s\"></iframe>").assertError("t:srcdoc is not allowed", "use src");
  }

  @Test
  void metaRefreshContentIsABuildError() {
    // #46: the url= part of a refresh navigates the page (open redirect). Only a delay and a
    // same-origin link are allowed (#237).
    compile("<meta http-equiv=\"refresh\" t:content=\"s\">")
        .assertError("t:content is not allowed", "refresh", "'3;url=' + @{", "controller");
    compile("<meta http-equiv=\"Refresh\" t:content=\"s\">").assertError("t:content is not allowed");
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
    compile("<meta http-equiv=\"refresh\" t:content=\"'1;url=' + @{//evil.example}\">")
        .assertError("t:content is not allowed", "same-origin path");
    compile("<meta t:http-equiv=\"s\" t:content=\"c\">").assertError("t:http-equiv is not allowed: the http-equiv of a <meta>", "static http-equiv");
    compile("<meta t:http-equiv=\"s\" content=\"0;url=/x\">").assertError("t:http-equiv", "<meta>");
    // A literal refresh written by the author is fine; other computed meta content is off the list (#761).
    assertEquals(
        "<meta http-equiv=\"refresh\" content=\"5\">",
        render("<meta http-equiv=\"refresh\" content=\"5\">", model()));
    compile("<meta name=\"description\" t:content=\"s\">").assertError("t:content is not allowed", "is not on Tessera's list");
  }

  @Test
  void attributesTheTreeBuilderReadsAreABuildError() {
    // A browser reads these values while it builds the page, before any escaping applies.
    compile("\n<p t:is=\"s\">x</p>").assertError("t.html:2:4", "t:is is not allowed", "custom element");
    compile("\n<p t:is=\"s\">x</p>").assertError("t.html:2:", "t:is is not allowed", "custom element");
    compile("\n<p t:xmlns=\"s\">x</p>").assertError("t.html:2:", "t:xmlns is not allowed", "namespace");
    compile("\n<p t:xmlns:xlink=\"s\">x</p>").assertError("t.html:2:", "t:xmlns:xlink is not allowed", "namespace");
    compile("\n<template t:shadowrootmode=\"s\"><p>x</p></template>")
        .assertError("t.html:2:", "t:shadowrootmode is not allowed", "shadow root");
    compile("\n<template shadowrootmode=\"open\" t:shadowrootclonable=\"b\"><p>x</p></template>")
        .assertError("t.html:2:", "t:shadowrootclonable is not allowed", "shadow root");
    compile("\n<template t:for=\"s\"><p>x</p></template>").assertError("t.html:2:", "t:for is not allowed", "<template>");
    compile("\n<meta t:charset=\"s\">").assertError("t.html:2:7", "t:charset is not allowed", "encoding");
    compile("\n<meta http-equiv=\"Content-Type\" t:content=\"s\">")
        .assertError("t.html:2:", "t:content is not allowed", "encoding", "Content-Type response header");
    compile("\n<meta t:content=\"s\" http-equiv=\"content-type\">").assertError("t.html:2:", "t:content is not allowed");
    // The same values written statically, and the same names where no tree builder reads them, compile.
    assertEquals(
        "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\"><meta charset=\"utf-8\">",
        render("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\"><meta charset=\"utf-8\">",
            model()));
    compile("<p is=\"x-p\">x</p><svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"
        + "<template shadowrootmode=\"open\" for=\"x\"><p>x</p></template>").assertSuccess();
    compile("<label t:for=\"s\">x</label>").assertSuccess();
    // #761: shadowrootmode elsewhere, and content under another http-equiv, are off the list.
    compile("<meta http-equiv=\"content-language\" t:content=\"'en'\">").assertError("t.html:1:", "t:content is not allowed", "not on");
    compile("<div t:shadowrootmode=\"s\">x</div>").assertError("t.html:1:", "t:shadowrootmode is not allowed", "not on");
  }

  @Test
  void metaRefreshAcceptsADelayAndASameOriginLink() {
    // #237: '<seconds>;url=' + @{/path...}, normalized to <seconds>;url=<url>.
    assertEquals(
        "<meta http-equiv=\"refresh\" content=\"1;url=/admin/update?vid=7\">",
        render("<meta http-equiv=\"refresh\" t:content=\"'1;url=' + @{/admin/update(vid=${n})}\">", model("n", 7)));
    assertEquals(
        "<meta http-equiv=\"REFRESH\" content=\"3;url=/blog/7\">",
        render("<meta http-equiv=\"REFRESH\" t:content=\"' 3 , URL = ' + @{/blog/{n}(n=${n})}\">", model("n", 7)));
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
    compile("<script t:type=\"s\"></script>").assertError("t:type is not allowed: the type of a <script>", "static type attribute");
    compile("<script type=\"application/json\" t:type=\"s\"></script>").assertError("t:type");
    // #678: on an <input> too; on other elements type is off the list (#761), with no per-element exception.
    compile("<input t:type=\"s\">").assertError("t:type is not allowed: the type of an <input>", "static type attribute");
    compile("<ol t:type=\"${b} ? 'a' : 'i'\"></ol>").assertError("t:type is not allowed", "not on");
    compile("<button t:type=\"${b} ? 'submit' : 'button'\">x</button>").assertError("t:type is not allowed", "under t:if");
  }

  @Test
  void attrIsRemoved() {
    compile("\n<p t:attr=\"title=s\">x</p>").assertError("t.html:2:", "t:attr is not supported; write one t:<name> per"
        + " attribute: t:attr=\"title=a, href=b\" becomes t:title=\"a\" t:href=\"b\"");
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
    assertEquals("<p>x</p>", render("<p t:title=\"s\" t:id=\"n\">x</p>", model()));
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
        render("<input t:value=\"s\" t:id=\"|i-${n}|\" t:data-n=\"n\">", model("s", "v", "n", 3)));
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
    String t = "<input type=\"checkbox\" t:checked=\"b\" t:disabled=\"nb\" t:required=\"s\">";
    assertEquals(
        "<input type=\"checkbox\" checked=\"checked\" disabled=\"disabled\" required=\"required\">",
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
