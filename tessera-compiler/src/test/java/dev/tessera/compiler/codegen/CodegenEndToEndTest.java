package dev.tessera.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.TemplateModelException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Full pipeline through javac and the real processor. */
class CodegenEndToEndTest {
  private static final String STATIC =
      "<!DOCTYPE html>\n"
          + "<html xmlns:t=\"https://tessera.dev\" lang=\"en\">\n"
          + "<head><meta charset=\"utf-8\"><title>A &amp; B</title></head>\n"
          + "<body class=\"x\"  id='y' hidden>\n"
          + "  <p>Hello, \"world\" \u00e9 \\u000a</p><br/><br />\n"
          + "  <!-- comment -->\n"
          + "</body>\n"
          + "</html>\n";

  private static Compilation compile(Compilation.Builder b) {
    Compilation c = b.compile();
    return c;
  }

  @Test
  void staticTemplateRendersByteIdenticalMinusXmlns() {
    Compilation c = compile(Compilation.builder().template("static.html", STATIC)).assertSuccess();
    assertEquals(STATIC.replace(" xmlns:t=\"https://tessera.dev\"", ""), c.render("static", Map.of()));
  }

  @Test
  void classNamesAndServicesFile() {
    Compilation c =
        compile(
                Compilation.builder()
                    .option("tessera.package", "com.example.views")
                    .template("owners/create-or-update.html", "<p>a</p>")
                    .template("class/404.html", "<p>b</p>")
                    .template("2024/new.html", "<p>c</p>"))
            .assertSuccess();
    assertNotNull(c.generatedSource("com.example.views.owners.CreateOrUpdateTemplate"));
    assertNotNull(c.generatedSource("com.example.views._class._404Template"));
    assertNotNull(c.generatedSource("com.example.views._2024.NewTemplate"));
    assertEquals(
        "com.example.views.TesseraRegistry\n",
        c.generatedResource("META-INF/services/dev.tessera.runtime.TemplateRegistry"));
    assertEquals("<p>b</p>", c.render("class/404", Map.of()));
  }

  @Test
  void typedParamsAndRegistryAdapter() {
    Compilation c =
        compile(
                Compilation.builder()
                    .java("com.example.User", "package com.example; public record User(String name) {}")
                    .template(
                        "page.html",
                        "<html xmlns:t=\"https://tessera.dev\" t:import=\"com.example.*\""
                            + " t:params=\"User user, List<String> tags, int n\">x</html>"))
            .assertSuccess();
    String src = c.generatedSource("tessera.generated.PageTemplate");
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
  void selfClosingElementWithReplacedBodyGetsAnEndTag() {
    // petclinic's ownersList writes <td th:text="..." />; Thymeleaf renders <td>text</td>.
    Compilation c =
        compile(
                Compilation.builder()
                    .template(
                        "page.html",
                        "<tr xmlns:t=\"https://tessera.dev\" t:params=\"String s\"><td t:text=\"s\" /><td />"
                            + "<td class=\"a\" t:text=\"s\"/></tr>"))
            .assertSuccess();
    assertEquals(
        "<tr><td>&lt;b&gt;</td><td /><td class=\"a\">&lt;b&gt;</td></tr>", c.render("page", Map.of("s", "<b>")));
  }

  @Test
  void misplacedParamsIsAnErrorAndNothingIsGenerated() {
    Compilation c =
        compile(
            Compilation.builder()
                .template("ok.html", "<p>fine</p>")
                .template("bad.html", "<html>\n<div t:params=\"String x\">a</div>\n</html>"));
    c.assertError("bad.html:2:6", "only allowed on the root element");
    assertNull(c.generatedSource("tessera.generated.BadTemplate"));
    assertNull(c.generatedSource("tessera.generated.OkTemplate"));
    assertNull(c.generatedSource("tessera.generated.TesseraRegistry"));
  }

  @Test
  void generatedSourcesContainNoReflection() {
    Compilation c = compile(Compilation.builder().template("static.html", STATIC)).assertSuccess();
    for (String name : List.of("tessera.generated.StaticTemplate", "tessera.generated.TesseraRegistry")) {
      String src = c.generatedSource(name);
      for (String banned : List.of("java.lang.reflect", "java.lang.invoke", "MethodHandle", "Class.forName")) {
        assertFalse(src.contains(banned), name + " contains " + banned);
      }
    }
  }
}
