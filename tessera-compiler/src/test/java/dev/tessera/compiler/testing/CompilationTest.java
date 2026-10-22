package dev.tessera.compiler.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
