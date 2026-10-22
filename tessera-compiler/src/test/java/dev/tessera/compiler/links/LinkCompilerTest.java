package dev.tessera.compiler.links;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.ExprRunner;
import java.util.List;
import org.junit.jupiter.api.Test;

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
        "ctx.links().build(\"/owners/{id}\", new java.lang.String[] {\"id\", \"q\"}, new java.lang.Object[] {o.id(), term})",
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
    assertEquals("/owners?page=7&q", r.eval("@{/owners(page=o.num, q=term)}", CTX));
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
  void duplicateParameterIsAnError() {
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

  @Test
  void placeholderScan() {
    assertEquals(List.of("a", "b"), LinkCompiler.placeholders("/x/{a}/y/{b}?z"));
    assertEquals(List.of(), LinkCompiler.placeholders("/x/{a"));
    assertEquals(null, LinkCompiler.scheme("/a:b"));
    assertEquals("http", LinkCompiler.scheme("http://x"));
    assertEquals("javascript", LinkCompiler.scheme("\u0000 java\nscript:x"));
  }
}
