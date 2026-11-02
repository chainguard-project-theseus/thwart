package dev.tessera.compiler.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.processor.ProcessorOptions;
import java.util.concurrent.atomic.AtomicReference;
import javax.lang.model.element.TypeElement;
import org.junit.jupiter.api.Test;

/** Tests for the harness itself and the processor wiring. */
class CompilationTest {

  @Test
  void compilesJavaWithNoTemplates() {
    Compilation c =
        Compilation.builder()
            .java("com.example.User", "package com.example; public record User(String name) {}")
            .compile()
            .assertSuccess();
    assertNull(c.generatedSource("tessera.generated.TesseraRegistry"));
  }

  @Test
  void loadsCompiledTestClasses() throws Exception {
    Compilation c =
        Compilation.builder()
            .java("com.example.User", "package com.example; public record User(String name) {}")
            .compile()
            .assertSuccess();
    Object u = c.newInstance("com.example.User", "Ann");
    assertEquals("User[name=Ann]", u.toString());
  }

  @Test
  void release8EmitsJava8ClassFilesAndRewritesRecords() {
    Compilation c =
        Compilation.builder()
            .release(8)
            .java("com.example.User", "package com.example; public record User(String name) {}")
            .template("hello.html", "<p t:params=\"com.example.User user\" t:text=\"user.name\">x</p>")
            .compile()
            .assertSuccess();
    assertEquals("<p>Ann</p>", c.render("hello", Compilation.model("user", c.newInstance("com.example.User", "Ann"))));
    for (String cls : new String[] {"tessera.generated.HelloTemplate", "tessera.generated.TesseraRegistry"}) {
      byte[] b = c.classFile(cls);
      assertEquals(52, ((b[6] & 0xff) << 8) | (b[7] & 0xff), cls);
    }
  }

  @Test
  void release8RejectsPostJava8Apis() {
    Compilation.builder()
        .release(8)
        .java("com.example.Bad", "package com.example; class Bad { Object x = java.util.List.of(); }")
        .compile()
        .assertError("cannot find symbol");
  }

  @Test
  void reportsJavaErrors() {
    Compilation.builder()
        .java("com.example.Bad", "package com.example; class Bad { int x = \"s\"; }")
        .compile()
        .assertError("incompatible types");
  }

  @Test
  void requiresTemplatesOption() {
    Compilation.builder().withoutTemplatesOption().compile().assertError("-Atessera.templates");
  }

  @Test
  void discoversOnlyHtmlTemplatesInSubdirectories() {
    Compilation c =
        Compilation.builder()
            .template("notes.txt", "not a template")
            .template("a/b.html", "<p>hi</p>")
            .compile()
            .assertSuccess();
    assertNotNull(c.generatedSource("tessera.generated.a.BTemplate"));
    assertNull(c.generatedSource("tessera.generated.NotesTemplate"));
    assertTrue(c.registry().find("a/b").isPresent());
    assertTrue(c.registry().find("notes").isEmpty());
    assertEquals(1, c.registry().all().size());
  }

  @Test
  void rejectsMissingTemplatesDirectory() {
    Compilation.builder()
        .option(ProcessorOptions.TEMPLATES, "/nonexistent/tessera/templates")
        .withoutTemplatesOption()
        .compile()
        .assertError("templates directory does not exist");
  }

  @Test
  void rejectsInvalidPackageOption() {
    for (String bad : new String[] {"com.my-app.views", "class.views", "com..views", "views."}) {
      Compilation.builder()
          .template("a.html", "<p>hi</p>")
          .option(ProcessorOptions.PACKAGE, bad)
          .compile()
          .assertError("-Atessera.package=" + bad + " is not a Java package name");
    }
  }

  @Test
  void acceptsValidPackageOption() {
    Compilation c =
        Compilation.builder()
            .template("a.html", "<p>hi</p>")
            .option(ProcessorOptions.PACKAGE, "com.example.views")
            .compile()
            .assertSuccess();
    assertNotNull(c.generatedSource("com.example.views.ATemplate"));
  }

  @Test
  void callbackSeesSourceTypes() {
    AtomicReference<TypeElement> found = new AtomicReference<>();
    Compilation.builder()
        .java("com.example.User", "package com.example; public record User(String name) {}")
        .inProcessor(pe -> found.set(pe.getElementUtils().getTypeElement("com.example.User")))
        .compile()
        .assertSuccess();
    assertNotNull(found.get());
    assertTrue(found.get().getRecordComponents().size() == 1);
  }
}
