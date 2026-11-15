package dev.chainguard.thwart.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chainguard.thwart.compiler.testing.Compilation;
import dev.chainguard.thwart.runtime.TemplateModelException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Full pipeline through javac and the real processor. */
class CodegenEndToEndTest {
  private static final String STATIC =
      "<!DOCTYPE html>\n"
          + "<html xmlns:t=\"https://thwart.chainguard.dev\" lang=\"en\">\n"
          + "<head><meta charset=\"utf-8\"><title>A &amp; B</title></head>\n"
          + "<body class=\"x\"  id='y' hidden>\n"
          + "  <p>Hello, \"world\" \u00e9 \\u000a</p><br/><br />\n"
          + "  <!-- comment -->\n"
          + "</body>\n"
          + "</html>\n";

  @Test
  void staticTemplateRendersByteIdenticalMinusXmlns() {
    Compilation c = Compilation.builder().template("static.html", STATIC).compile().assertSuccess();
    assertEquals(STATIC.replace(" xmlns:t=\"https://thwart.chainguard.dev\"", ""), c.render("static", Map.of()));
  }


  @Test
  void templateCommentsAreNotCompiledOrWritten() {
    // Thymeleaf's parser-level comment <!--/* ... */-->: nothing inside is compiled, verified or written.
    String page = "<html xmlns:t=\"https://thwart.chainguard.dev\" t:params=\"String name\">"
        + "<p>a<!--/* [[${name}]] */-->b</p><!--/*\n<div t:text=\"${nope.x}\" t:fragment=\"f()\">[[${bogus}]]</div>\n"
        + "<script>[[${name}]]</script><a href=\"[[${name}]]\" onclick=\"[[${name}]]\"></a>\n*/-->"
        + "<!-- /* kept */ --></html>";
    Compilation c = Compilation.builder().template("page.html", page).compile().assertSuccess();
    assertEquals("<html><p>ab</p><!-- /* kept */ --></html>", c.render("page", Map.of("name", "n")));
  }
  @Test
  void classNamesAndServicesFile() {
    Compilation c =
        Compilation.builder()
            .option("thwart.package", "com.example.views")
            .template("owners/create-or-update.html", "<p>a</p>")
            .template("class/404.html", "<p>b</p>")
            .template("2024/new.html", "<p>c</p>")
            .compile()
            .assertSuccess();
    assertNotNull(c.generatedSource("com.example.views.owners.CreateOrUpdateTemplate"));
    assertNotNull(c.generatedSource("com.example.views._class._404Template"));
    assertNotNull(c.generatedSource("com.example.views._2024.NewTemplate"));
    assertEquals(
        "com.example.views.ThwartRegistry\n",
        c.generatedResource("META-INF/services/dev.chainguard.thwart.runtime.TemplateRegistry"));
    assertEquals("<p>b</p>", c.render("class/404", Map.of()));
  }

  @Test
  void typedParamsAndRegistryAdapter() {
    Compilation c =
        Compilation.builder()
            .java("com.example.User", "package com.example; public record User(String name) {}")
            .template(
                "page.html",
                "<html xmlns:t=\"https://thwart.chainguard.dev\" t:import=\"com.example.*\""
                    + " t:params=\"User user, List<String> tags, int n\">x</html>")
            .compile()
            .assertSuccess();
    String src = c.generatedSource("thwart.generated.PageTemplate");
    assertTrue(src.contains("of(com.example.User user$, java.util.List<java.lang.String> tags$, int n$)"), src);
    Object user = c.newInstance("com.example.User", "Ann");
    assertEquals("<html>x</html>", c.render("page", Map.of("user", user, "tags", List.of(), "n", 1)));
    assertEquals("<html>x</html>", c.render("page", Map.of("n", 1)));
    TemplateModelException e =
        assertThrows(TemplateModelException.class, () -> c.render("page", Map.of("user", "Ann", "n", 1)));
    assertTrue(e.getMessage().contains("'user'") && e.getMessage().contains("com.example.User"), e.getMessage());
    assertTrue(e.getMessage().contains("java.lang.String"), e.getMessage());
  }

  @Test
  void missingAttributeReadFailsNamingIt() {
    Compilation c =
        Compilation.builder()
            .java("com.example.User", "package com.example; public record User(String name) {}")
            .template(
                "p.html",
                "<p xmlns:t=\"https://thwart.chainguard.dev\" t:params=\"com.example.User u, String s\""
                    + " t:text=\"${u.name}\">x</p>")
            .template(
                "q.html",
                "<p xmlns:t=\"https://thwart.chainguard.dev\" t:params=\"com.example.User u\""
                    + " t:text=\"${u?.name}\">x</p>")
            .compile()
            .assertSuccess();
    TemplateModelException e =
        assertThrows(TemplateModelException.class, () -> c.render("p", Map.of("s", "a")));
    assertEquals(
        "template 'p': NullPointerException while rendering; null model attributes: 'u'"
            + " (the cause's stack trace has the template position)",
        e.getMessage());
    assertTrue(e.getCause() instanceof NullPointerException, String.valueOf(e.getCause()));
    // A missing attribute stays null: a template that expects it renders.
    assertEquals("<p></p>", c.render("q", Map.of()));
  }

  @Test
  void npeWithNoNullAttributeIsRethrownAndListedNullsAssertNoCause() {
    Compilation c =
        Compilation.builder()
            .java("com.example.Address", "package com.example; public record Address(String city) {}")
            .java("com.example.Owner", "package com.example; public record Owner(Address address) {}")
            .template(
                "o.html",
                "<div xmlns:t=\"https://thwart.chainguard.dev\" t:params=\"com.example.Owner owner, String message\">"
                    + "<p t:if=\"${message}\" t:text=\"${message}\">m</p>"
                    + "<span t:text=\"${owner.address.city}\">c</span></div>")
            .template(
                "n.html",
                "<p xmlns:t=\"https://thwart.chainguard.dev\" t:params=\"int n\" t:text=\"${n}\">x</p>")
            .template(
                "f.html",
                "<div xmlns:t=\"https://thwart.chainguard.dev\">"
                    + "<p t:fragment=\"card(com.example.Owner owner)\" t:text=\"${owner.address.city}\">c</p></div>")
            .compile()
            .assertSuccess();
    Object owner = c.newInstance("com.example.Owner", (Object) null);
    // Every reference attribute present, the NPE from a nested null: rethrown as is.
    assertThrows(
        NullPointerException.class,
        () -> c.render("o", Map.of("owner", owner, "message", "hi")));
    assertThrows(NullPointerException.class, () -> c.render("f :: card", Map.of("owner", owner)));
    // An optional attribute null, the NPE from elsewhere: the message lists it without blaming it.
    TemplateModelException e =
        assertThrows(TemplateModelException.class, () -> c.render("o", Map.of("owner", owner)));
    assertTrue(e.getMessage().contains("null model attributes: 'message'"), e.getMessage());
    assertTrue(e.getCause() instanceof NullPointerException, String.valueOf(e.getCause()));
    // Fragment entries go through the same adapter.
    assertThrows(TemplateModelException.class, () -> c.render("f :: card", Map.of()));
    // Only a primitive param: no catch is emitted, and the render works.
    assertEquals("<p>3</p>", c.render("n", Map.of("n", 3)));
    assertTrue(
        !c.generatedSource("thwart.generated.ThwartRegistry").contains("throw nulls(\"n\""),
        "primitive-only entry has no NPE catch");
  }

  @Test
  void selfClosingElementWithReplacedBodyGetsAnEndTag() {
    // petclinic's ownersList writes <td th:text="..." />; Thymeleaf renders <td>text</td>.
    Compilation c =
        Compilation.builder()
            .template(
                "page.html",
                "<tr xmlns:t=\"https://thwart.chainguard.dev\" t:params=\"String s\"><td t:text=\"s\" />"
                    + "<td class=\"a\" t:text=\"s\"/></tr>")
            .compile()
            .assertSuccess();
    assertEquals("<tr><td>&lt;b&gt;</td><td class=\"a\">&lt;b&gt;</td></tr>", c.render("page", Map.of("s", "<b>")));
    // An empty <td /> is written as it is: "/>" on a non-void element is a parse error (#406).
    Compilation.builder()
        .template("page.html", "<tr xmlns:t=\"https://thwart.chainguard.dev\" t:params=\"String s\"><td t:text=\"s\" /><td /></tr>")
        .compile()
        .assertError("page.html:1:82: error: a browser ignores the '/' of <td/>",
            "write the end tag: <td ...></td> [verifier:PARSE_ERROR]");
  }

  @Test
  void misplacedParamsIsAnErrorAndNothingIsGenerated() {
    Compilation c =
        Compilation.builder()
            .template("ok.html", "<p>fine</p>")
            .template("bad.html", "<html>\n<div t:params=\"String x\">a</div>\n</html>")
            .compile();
    c.assertError("bad.html:2:6", "only allowed on the root element");
    assertNull(c.generatedSource("thwart.generated.BadTemplate"));
    assertNull(c.generatedSource("thwart.generated.OkTemplate"));
    assertNull(c.generatedSource("thwart.generated.ThwartRegistry"));
  }
}
