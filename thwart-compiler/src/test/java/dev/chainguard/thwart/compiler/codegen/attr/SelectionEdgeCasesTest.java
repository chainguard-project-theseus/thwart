package dev.chainguard.thwart.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.chainguard.thwart.compiler.testing.Compilation;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Edge cases of {@code ~{::selector}} arguments and generated parameter names. */
class SelectionEdgeCasesTest {
  private static final String NS = "xmlns:t=\"https://thwart.chainguard.dev\"";
  private static final String WRAP =
      "<div " + NS + "><section t:fragment=\"wrap(Fragment body)\">[<div t:replace=\"body\">b</div>]</section></div>";

  private static Compilation.Builder owner() {
    return Compilation.builder()
        .java("com.example.Owner", "package com.example; public record Owner(String name) {}")
        .template("fragments/wrap.html", WRAP);
  }

  private static String page(String body) {
    return "<html " + NS + " t:import=\"com.example.Owner\" t:params=\"Owner owner\"><body>" + body + "</body></html>";
  }

  @Test
  void mutuallyRecursiveSelectionsAreADiagnostic() {
    owner()
        .template(
            "page.html",
            page(
                "<a t:replace=\"~{fragments/wrap :: wrap(~{::b})}\">a</a>"
                    + "<b t:replace=\"~{fragments/wrap :: wrap(~{::a})}\">b</b>"))
        .compile()
        .assertError("page.html:1:", "~{::", "include each other");
  }

  @Test
  void selectionInsideTObjectKeepsTheFormSelection() {
    Compilation c =
        owner()
            .template(
                "page.html",
                page(
                    "<form t:object=\"owner\"><div t:replace=\"~{fragments/wrap :: wrap(~{::span})}\">x</div>"
                        + "<span t:text=\"*{name}\">n</span></form>"))
            .compile()
            .assertSuccess();
    assertEquals(
        "<html><body><form><section>[<span>Jo</span>]</section><span>Jo</span></form></body></html>",
        c.render("page", Map.of("owner", c.newInstance("com.example.Owner", "Jo"))));
  }

  @Test
  void selectionOfTheTObjectElementKeepsTheFormSelection() {
    Compilation c =
        owner()
            .template(
                "page.html",
                page(
                    "<div t:replace=\"~{fragments/wrap :: wrap(~{::form})}\">x</div>"
                        + "<form t:object=\"owner\"><span t:text=\"*{name}\">n</span></form>"))
            .compile()
            .assertSuccess();
    assertEquals(
        "<html><body><section>[<form><span>Jo</span></form>]</section><form><span>Jo</span></form></body></html>",
        c.render("page", Map.of("owner", c.newInstance("com.example.Owner", "Jo"))));
  }

  @Test
  void selectionInsideTheHostsOwnTObjectIsADiagnostic() {
    owner()
        .template(
            "page.html",
            page(
                "<form t:object=\"owner\" t:insert=\"~{fragments/wrap :: wrap(~{::span})}\">"
                    + "<span t:text=\"*{name}\">n</span></form>"))
        .compile()
        .assertError("page.html:1:", "~{::span}", "runs before t:object", "move t:object to an enclosing element");
  }

  @Test
  void paramsNamedLikePackageRootsDoNotShadowGeneratedNames() {
    Compilation c =
        Compilation.builder()
            .template(
                "page.html",
                "<p " + NS + " t:params=\"String java, String dev, String com\">"
                    + "<b t:text=\"java\">j</b><i t:text=\"dev + com\">d</i></p>")
            .compile()
            .assertSuccess();
    assertEquals("<p><b>J</b><i>DC</i></p>", c.render("page", Map.of("java", "J", "dev", "D", "com", "C")));
  }
}
