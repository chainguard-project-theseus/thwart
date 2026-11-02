package dev.tessera.compiler.links;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.compiler.testing.ExprRunner;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LinkCompilerTest {
  private static final String OWNER =
      "package com.example; public record Owner(String id, int num, Object secret) {}";
  private static final String CTX = "dev.tessera.runtime.RenderContext.defaults()";
  private static final String CTX_PATH =
      "new dev.tessera.runtime.RenderContext(java.util.Locale.ROOT, dev.tessera.runtime.Messages.NONE,"
          + " new dev.tessera.runtime.DefaultLinks(\"/app\"), dev.tessera.runtime.Forms.NONE)";

  private static ExprRunner owners(String id, String term) {
    return ExprRunner.create()
        .java("com.example.Owner", OWNER)
        .var("o", "com.example.Owner", "new com.example.Owner(" + id + ", 7, null)")
        .var("term", "java.lang.String", term);
  }

  // ------------------------------------------------------------- positive

  @Test
  void pathVariableAndQueryParamEndToEnd() {
    ExprRunner r = owners("\"a/b?c#d\"", "\"x & y=z\"");
    assertEquals(
        "ctx.links().encode(ctx.links().contextPath() + \"/owners/\""
            + " + dev.tessera.runtime.UrlEncoding.pathVariable(\"id\", o.id()) + \"?q=\""
            + " + dev.tessera.runtime.UrlEncoding.queryValue(term))",
        r.compile("@{/owners/{id}(id=o.id, q=term)}").code());
    assertEquals("/owners/a%2Fb%3Fc%23d?q=x%20%26%20y%3Dz", r.eval("@{/owners/{id}(id=o.id, q=term)}", CTX));
  }

  @Test
  void contextPathIsPrepended() {
    assertEquals("/app/owners/42/edit", owners("\"42\"", "null").eval("@{/owners/{id}/edit(id=o.id)}", CTX_PATH));
  }

  @Test
  void noParamsAndNullQueryValue() {
    ExprRunner r = owners("\"1\"", "null");
    assertEquals("/owners/find", r.eval("@{/owners/find}", CTX));
    // Like Thymeleaf: a null value is "", so the parameter keeps its '='.
    assertEquals("/owners?page=7&q=", r.eval("@{/owners(page=o.num, q=term)}", CTX));
  }

  @Test
  void valuelessParameterRendersBareName() {
    ExprRunner r = owners("\"a&b\"", "null");
    assertEquals(
        "ctx.links().encode(ctx.links().contextPath() + \"/cart?add&itemId=\""
            + " + dev.tessera.runtime.UrlEncoding.queryValue(o.id()))",
        r.compile("@{/cart(add,itemId=o.id)}").code());
    assertEquals("/cart?add&itemId=a%26b", r.eval("@{/cart(add,itemId=o.id)}", CTX));
    assertEquals("/app/accounts/create?form", r.eval("@{/accounts/create(form)}", CTX_PATH));
    assertEquals("/s?q=&form", r.eval("@{/s(q=term, form)}", CTX));
  }

  @Test
  void collectionValuesRepeatTheParameter() {
    ExprRunner r =
        ExprRunner.create()
            .java("com.example.Tags", "package com.example; public record Tags(java.util.List<String> all, java.util.List<String> none) {}")
            .var("t", "com.example.Tags",
                "new com.example.Tags(java.util.Arrays.asList(\"a\", null, \"b&c\"), java.util.List.of())");
    assertEquals("/s?tag=a&tag&tag=b%26c&x=1", r.eval("@{/s(tag=t.all, x=1)}", CTX));
    assertEquals("/s?x=1", r.eval("@{/s(tag=t.none, x=1)}", CTX));
    assertEquals("/s/a,,b%26c", r.eval("@{/s/{tag}(tag=t.all)}", CTX));
    // A collection first decides the '?' at render time: an empty one renders nothing.
    assertEquals("/s?x=1#top", r.eval("@{/s#top(tag=t.none, x=1)}", CTX));
    assertEquals("/s?tag=a&tag&tag=b%26c&x=1#top", r.eval("@{/s#top(tag=t.all, x=1)}", CTX));
    assertEquals("/s", r.eval("@{/s(tag=t.none)}", CTX));
    assertEquals("/s?a=1&tag=a&tag&tag=b%26c", r.eval("@{/s?a=1(tag=t.all)}", CTX));
    assertTrue(r.compile("@{/s(tag=t.all)}").code().contains("UrlEncoding.firstQuery(dev.tessera.runtime"
        + ".UrlEncoding.queryValues(\"tag\", t.all()))"));
  }

  /** Links compile to concatenation: an int is written as is, with no arrays, boxing or parsing. */
  @Test
  void primitivesAreConcatenatedDirectly() {
    ExprRunner r = owners("\"1\"", "null");
    assertEquals("ctx.links().encode(ctx.links().contextPath() + \"/o/\" + (o.num()) + \"?m=\" + (o.num()))",
        r.compile("@{/o/{n}(n=o.num, m=o.num)}").code());
    assertEquals("ctx.links().encode(\"\" + (o.num()) + \"/x\")", r.compile("@{{n}/x(n=o.num)}").code());
    assertEquals("7/x", r.eval("@{{n}/x(n=o.num)}", CTX));
    assertEquals("/app/a/7/b/7?q=7", r.eval("@{/a/{n}/b/{n}(n=o.num, q=o.num)}", CTX_PATH));
  }

  @Test
  void queryGoesBeforeTheFragmentAndAfterAnExistingQuery() {
    ExprRunner r = owners("\"1\"", "\"t\"");
    assertEquals("/app/s?a=1&b=t#top", r.eval("@{/s?a=1#top(b=term)}", CTX_PATH));
    assertEquals("/s?b=t#top", r.eval("@{/s#top(b=term)}", CTX));
    assertEquals("/s?a=1&form#top", r.eval("@{/s?a=1#top(form)}", CTX));
    assertEquals("/app/owners?sort=name#top", r.eval("@{/owners?sort=name#top}", CTX_PATH));
    assertEquals("/app/o/1#p-1", r.eval("@{/o/{id}#p-{id}(id=o.id)}", CTX_PATH));
  }

  @Test
  void onlyContextRelativePathsGetTheContextPath() {
    ExprRunner r = owners("\"1\"", "null");
    assertEquals("edit?x=7", r.eval("@{edit(x=o.num)}", CTX_PATH));
    assertEquals("//cdn.example.com/a.js", r.eval("@{//cdn.example.com/a.js}", CTX_PATH));
    assertEquals("https://example.com/x", r.eval("@{https://example.com/x}", CTX_PATH));
  }

  @Test
  void emptyOrDotSegmentPathVariableFailsTheRender() {
    for (String bad : List.of("\"\"", "\".\"", "\"..\"", "null")) {
      ExprRunner r = owners(bad, "null");
      Throwable t = assertThrows(AssertionError.class, () -> r.eval("@{/{id}/evil.com(id=o.id)}", CTX));
      while (t.getCause() != null) {
        t = t.getCause();
      }
      assertTrue(t instanceof IllegalArgumentException && t.getMessage().contains("'id'"), bad + ": " + t);
    }
  }

  @Test
  void nonAsciiAndDotSegmentsAreEncoded() {
    assertEquals("/o/%C3%A9t%C3%A9%20x", owners("\"été x\"", "null").eval("@{/o/{id}(id=o.id)}", CTX));
  }

  @Test
  void httpAndHttpsAbsoluteLinksAreAllowed() {
    ExprRunner r = owners("\"1\"", "\"a b\"");
    assertEquals("https://example.com/s?q=a%20b", r.eval("@{https://example.com/s(q=term)}", CTX));
    for (String ok : List.of("@{http://x.org/{id}(id=o.id)}", "@{HTTPS://x.org}", "@{//cdn.x.org/a.js}", "@{page.html}", "@{#top}", "@{?a=1}")) {
      assertEquals(List.of(), r.compile(ok).diagnostics(), ok);
    }
  }

  @Test
  void pathVariableAtStartCannotInjectAScheme() {
    // ':' and '/' are percent-encoded in path variables, so the value cannot become a scheme or host.
    assertEquals("javascript%3Aalert%281%29", owners("\"javascript:alert(1)\"", "null").eval("@{{id}(id=o.id)}", CTX));
    assertEquals("%2F%2Fevil.com", owners("\"//evil.com\"", "null").eval("@{{id}(id=o.id)}", CTX));
  }

  // ------------------------------------------------------------- negative

  @Test
  void placeholderWithoutParameterIsAnError() {
    owners("\"1\"", "null").compile("@{/owners/{id}/pets/{petId}(id=o.id)}").assertError("t.html:1:1", "{petId}", "no parameter");
    owners("\"1\"", "null").compile("@{/owners/{id}}").assertError("{id}");
  }

  @Test
  void valuelessPathVariableIsAnError() {
    owners("\"1\"", "null").compile("@{/owners/{id}(id)}")
        .assertError("t.html:1:", "link parameter 'id' fills path variable {id}", "needs a value");
  }

  @Test
  void duplicateParameterIsAnError() {
    owners("\"1\"", "null").compile("@{/owners(q, q=o.id)}").assertError("duplicate link parameter 'q'");
    owners("\"1\"", "null").compile("@{/owners(q=term, q=o.id)}").assertError("duplicate link parameter 'q'");
  }

  @Test
  void nonHttpSchemesAreErrors() {
    ExprRunner r = owners("\"1\"", "null");
    for (String bad :
        List.of(
            "@{javascript:alert}",
            "@{JaVaScRiPt:alert}",
            "@{ javascript:alert}",
            "@{vbscript:x}",
            "@{data:text/html,x}",
            "@{mailto:a@b.c}",
            "@{file:///etc/passwd}",
            "@{ftp://x.org}")) {
      r.compile(bad).assertError("only http and https");
    }
  }

  @Test
  void schemeFoldsOnlyAscii() {
    // equalsIgnoreCase folded U+017F to s, so httpſ passed as https; a browser folds A-Z only.
    ExprRunner r = owners("\"1\"", "null");
    r.compile("@{http\u017F://evil.com/x}").assertError("has scheme 'http\u017F:'", "only http and https");
    assertEquals(List.of(), r.compile("@{HTTPS://example.com/x}").errors());
  }

  @Test
  void pathVariableInSchemeOrHostIsAnError() {
    ExprRunner r = owners("\"1\"", "null");
    r.compile("@{{id}:alert(id=o.id)}").assertError("only http and https");
    r.compile("@{https://{id}/x(id=o.id)}").assertError("host");
    r.compile("@{//{id}.example.com/x(id=o.id)}").assertError("host");
    r.compile("@{/\\{id}/x(id=o.id)}").assertError("host");
    // Browsers resolve https:host/x and https:/host/x like https://host/x.
    r.compile("@{https:{id}/x(id=o.id)}").assertError("host");
    r.compile("@{HTTP:/{id}/x(id=o.id)}").assertError("host");
    assertEquals(List.of(), r.compile("@{https://x.org/{id}(id=o.id)}").diagnostics());
  }

  @Test
  void parameterValueMustBeViewSafe() {
    owners("\"1\"", "null").compile("@{/x(s=o.secret)}").assertError("java.lang.Object");
  }

  @Test
  void parameterValueErrorsAreReported() {
    owners("\"1\"", "null").compile("@{/x(s=o.nope)}").assertError("nope");
  }

  // ------------------------------------------- protocol-relative in disguise (#313)

  private static Compilation page(String markup) {
    return Compilation.builder()
        .template("page.html", "<div t:params=\"String s\">\n" + markup + "\n</div>")
        .compile();
  }

  /**
   * A browser reads {@code \\} as {@code /} (and drops leading control chars and tab/CR/LF), so
   * these paths load from evil.example ({@code new URL(x, "https://app.example/p").origin}).
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<script t:src=\"@{/\\evil.example/a.js}\"></script>",
        "<link t:href=\"@{/\\evil.example/}\">",
        "<link rel=\"stylesheet\" t:href=\"@{\\\\evil.example/a.css}\">",
        "<a t:href=\"@{/\\evil.example/}\">x</a>",
        "<a t:href=\"@{\\/evil.example/}\">x</a>",
        "<img t:src=\"@{/\\evil.example/a.png(v=${s})}\">",
        "<script t:src=\"@{\u0001//evil.example/a.js}\"></script>",
        "<script t:src=\"@{\u0001/\\evil.example/a.js}\"></script>",
        "<script t:src=\"@{/\\\\evil.example/a.js}\"></script>",
        "<a t:href=\"@{/\\}\">x</a>"
      })
  void browserProtocolRelativePathIsAnError(String markup) {
    Compilation c = page(markup);
    c.assertError("page.html:2:", "protocol-relative", "@{//host/...}");
    assertNull(c.generatedSource("tessera.generated.PageTemplate"));
  }

  @Test
  void errorNamesFileLineAndColumn() {
    page("<script t:src=\"@{/\\evil.example/a.js}\"></script>").assertError("page.html:2:16:");
    page("<base t:href=\"@{/\\evil.example/}\">").assertError("page.html:2:15:");
  }

  @Test
  void neutralLinkKeepsItsError() {
    Compilation c = page("<div t:hx-get=\"@{\\/evil.example/x}\"></div>");
    c.assertError("page.html:2:", "a link in 'hx-get' must be a same-origin path that starts with a single '/'");
    assertNull(c.generatedSource("tessera.generated.PageTemplate"));
  }

  /** #350: a {@code <base href>} must be a same-origin link, with its own error. */
  @Test
  void baseHrefKeepsItsError() {
    Compilation c = page("<base t:href=\"@{/\\evil.example/}\">");
    c.assertError("page.html:2:", "a <base href> must be a same-origin path starting with a single '/'");
    assertNull(c.generatedSource("tessera.generated.PageTemplate"));
  }

  @Test
  void singleLeadingBackslashIsAnError() {
    page("<a t:href=\"@{\\x/y}\">x</a>").assertError("page.html:2:", "starts with '\\'", "write '/'");
  }

  @Test
  void ordinaryLinksStillCompile() {
    Compilation c =
        page("<script t:src=\"@{/js/app.js}\"></script><a t:href=\"@{//cdn.example/a}\">x</a>"
            + "<a t:href=\"@{https://cdn.example/a}\">x</a><a t:href=\"@{/x/{p}(p=${s})}\">x</a>"
            + "<a t:href=\"@{/x\\y}\">x</a><a t:href=\"@{/%5Cevil.example/}\">x</a>")
            .assertSuccess();
    assertEquals(
        "<div>\n<script src=\"/js/app.js\"></script><a href=\"//cdn.example/a\">x</a>"
            + "<a href=\"https://cdn.example/a\">x</a><a href=\"/x/a%2Fb\">x</a>"
            + "<a href=\"/x\\y\">x</a><a href=\"/%5Cevil.example/\">x</a>\n</div>",
        c.render("page", Map.of("s", "a/b")));
  }

  /**
   * #756: a resource URL (script, frame, embed, object, link) is a same-origin path with no query
   * parameters, so data cannot pick another origin or reach a script through its query string.
   * Path variables are percent-encoded, so {@code a/b} stays one segment. Static tags are unaffected.
   */
  @Test
  void resourceUrlsAreSameOriginPaths() {
    Compilation c = page("<script t:src=\"@{/js/{v}/app.js(v=${s})}\"></script><link t:href=\"@{css/{v}.css(v=${s})}\">"
        + "<script src=\"https://cdn.example/a.js?v=1\"></script><script t:src=\"@{/js/app.js?v=1}\"></script>")
        .assertSuccess();
    assertEquals(
        "<div>\n<script src=\"/js/a%2Fb/app.js\"></script><link href=\"css/a%2Fb.css\">"
            + "<script src=\"https://cdn.example/a.js?v=1\"></script><script src=\"/js/app.js?v=1\"></script>\n</div>",
        c.render("page", Map.of("s", "a/b")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<script t:src=\"@{//h/x.js}\"></script>",
        "<script t:src=\"@{https://h/x.js}\"></script>",
        "<iframe t:src=\"@{HTTP://h/}\"></iframe>",
        "<link rel=\"stylesheet\" t:href=\"@{//h/a.css}\">",
        "<object t:data=\"@{https://h/{p}(p=${s})}\"></object>",
        "<script t:attr=\"src=@{//h/x.js}\"></script>"
      })
  void resourceUrlFromAnotherOriginIsAnError(String markup) {
    Compilation c = page(markup);
    c.assertError("page.html:2:", "loads code or a document, so it must be a same-origin path",
        "write a static attribute");
    assertNull(c.generatedSource("tessera.generated.PageTemplate"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<script t:src=\"@{/x(cb=${s})}\"></script>",
        "<script t:src=\"@{/x/{p}(p=${s}, cb=${s})}\"></script>",
        "<embed t:src=\"@{/x(cb)}\">",
        "<script t:attr=\"src=@{/x(cb=${s})}\"></script>"
      })
  void resourceUrlQueryParameterIsAnError(String markup) {
    Compilation c = page(markup);
    c.assertError("page.html:2:", "takes no query parameter such as 'cb'", "@{/js/{v}/app.js(v=${v})}");
    assertNull(c.generatedSource("tessera.generated.PageTemplate"));
  }

  @Test
  void sameOriginPathShape() {
    for (String ok : List.of("/", "/x", "/x\\y", "/%5Cevil", "/%2F/evil", "/x//y")) {
      assertTrue(LinkCompiler.isSameOriginPath(ok), ok);
    }
    for (String bad :
        List.of("//evil", "/\\evil", "\\/evil", "\\\\evil", "\\x", "/\t/evil", "/\n\\evil", "\u0001//evil",
            "x", "https://app.example/x", "")) {
      assertFalse(LinkCompiler.isSameOriginPath(bad), bad);
    }
  }

  @Test
  void placeholderScan() {
    assertEquals(List.of("a", "b"), LinkCompiler.placeholders("/x/{a}/y/{b}?z"));
    assertEquals(List.of(), LinkCompiler.placeholders("/x/{a"));
    assertEquals(null, LinkCompiler.scheme("/a:b"));
    assertEquals("http", LinkCompiler.scheme("http://x"));
    assertEquals("javascript", LinkCompiler.scheme("\u0000 java\nscript:x"));
  }
}
