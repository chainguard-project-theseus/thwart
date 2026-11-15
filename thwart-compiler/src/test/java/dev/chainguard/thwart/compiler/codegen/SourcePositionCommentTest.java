package dev.chainguard.thwart.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chainguard.thwart.compiler.SourcePos;
import dev.chainguard.thwart.compiler.testing.Compilation;
import dev.chainguard.thwart.runtime.TemplateModelException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The line of a stack trace into generated code names the template position (#805), through the model exception's cause (#814). */
class SourcePositionCommentTest {

  private static final String USER =
      "package com.example; public record User(String name) {}";

  /** The generated source line that {@code e}'s innermost frame in {@code className} points at. */
  private static String failingLine(Compilation c, String className, RuntimeException e) {
    for (StackTraceElement f : e.getStackTrace()) {
      if (f.getClassName().equals(className)) {
        return c.generatedSource(className).split("\n", -1)[f.getLineNumber() - 1];
      }
    }
    throw new AssertionError("no frame in " + className, e);
  }

  @Test
  void exceptionInAValueLeadsToItsTemplateLine() {
    Compilation c =
        Compilation.builder()
            .java("com.example.User", USER)
            .template(
                "owners/show.html",
                "<div t:params=\"com.example.User u\">\n  <p t:text=\"${u.name}\">x</p>\n</div>")
            .compile()
            .assertSuccess();
    NullPointerException e =
        (NullPointerException) assertThrows(TemplateModelException.class, () -> c.render("owners/show", Map.of())).getCause();
    String line = failingLine(c, "thwart.generated.owners.ShowTemplate", e);
    assertTrue(line.endsWith(" // owners/show.html:2:16"), line);
  }

  @Test
  void exceptionInAConditionLeadsToItsTemplateLine() {
    Compilation c =
        Compilation.builder()
            .java("com.example.User", USER)
            .template(
                "cond.html",
                "<div t:params=\"com.example.User u\">\n\n  <p t:if=\"${u.name}\">x</p>\n</div>")
            .compile()
            .assertSuccess();
    NullPointerException e =
        (NullPointerException) assertThrows(TemplateModelException.class, () -> c.render("cond", Map.of())).getCause();
    String line = failingLine(c, "thwart.generated.CondTemplate", e);
    assertTrue(line.contains(" // cond.html:3:6"), line);
  }

  @Test
  void fileNameCannotEndTheComment() {
    // A newline, or a unicode escape javac would read as one, would end the line comment.
    String java = Emitted.at("f();", new SourcePos("a\n*/\\u000ab.html", 1, 2));
    assertEquals("f(); // a__/_u000ab.html:1:2\n", java);
  }
}
