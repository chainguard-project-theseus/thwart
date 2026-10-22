package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
    Map<String, Object> m = new HashMap<>();
    m.put("b", false); // primitive param: needs a value
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  // ------------------------------------------------------------- URLs

  @Test
  void urlAttributesUseTheUrlEscaper() {
    assertEquals("<a href=\"/owners?id=1&amp;x=2\">x</a>", render("<a t:href=\"s\">x</a>", model("s", "/owners?id=1&x=2")));
    assertEquals("<a href=\"about:invalid#tessera\">x</a>", render("<a t:href=\"s\">x</a>", model("s", "javascript:alert(1)")));
    assertEquals("<img src=\"about:invalid#tessera\">", render("<img t:src=\"s\">", model("s", " JaVaScRiPt:alert(1)")));
    assertEquals(
        "<form action=\"about:invalid#tessera\"></form>",
        render("<form t:action=\"s\"></form>", model("s", "data:text/html,<script>")));
    assertEquals(
        "<a href=\"about:invalid#tessera\">x</a>",
        render("<a t:attr=\"href=s\">x</a>", model("s", "vbscript:x")));
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
    assumeFalse(c.errors().stream().anyMatch(e -> e.contains("TODO(#")), () -> "links not implemented: " + c.errors());
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
    // #46: the url= part of a refresh navigates the page (open redirect).
    compile("<meta http-equiv=\"refresh\" t:content=\"s\">")
        .assertError("t:content is not allowed", "refresh", "controller");
    compile("<meta http-equiv=\"Refresh\" t:attr=\"content=s\">").assertError("t:attr content is not allowed");
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
        "<p id=\"a\" data-owner-id=\"7\" data-x=\"a\">x</p>",
        render("<p t:id=\"s\" t:data-owner-id=\"n\" t:data-x=\"s\">x</p>", model("s", "a", "n", 7)));
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
  void classAppendIsEscaped() {
    assertEquals(
        "<p class=\"a &quot; onclick=&quot;x\">x</p>",
        render("<p class=\"a\" t:classappend=\"s\">x</p>", model("s", "\" onclick=\"x")));
  }
}
