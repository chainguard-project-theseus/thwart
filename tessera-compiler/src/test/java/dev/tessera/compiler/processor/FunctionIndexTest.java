package dev.tessera.compiler.processor;

import static dev.tessera.compiler.testing.Compilation.model;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.tessera.compiler.testing.Compilation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

/**
 * #118: templates compiled without the {@code @TemplateFunctions} classes they use (test compile,
 * incremental build) find them through the function index an earlier compilation wrote.
 */
class FunctionIndexTest {
  private static final String FMT =
      """
      package com.example;
      import dev.tessera.runtime.*;
      @TemplateFunctions("fmt")
      public final class Fmt {
        private Fmt() {}
        @TemplateFunction public static String upper(String s) { return s.toUpperCase(); }
      }
      """;

  private static Compilation main() {
    return Compilation.builder().java("com.example.Fmt", FMT).compile().assertSuccess();
  }

  /** A first compilation that also writes {@code META-INF/tessera/functions/<ns>} = {@code content}. */
  private static Compilation forged(String ns, String content, String className, String source) {
    Compilation.Builder b = Compilation.builder().inProcessor(pe -> writeIndex(pe, ns, content));
    if (className != null) {
      b.java(className, source);
    }
    return b.compile().assertSuccess();
  }

  private static void writeIndex(ProcessingEnvironment pe, String ns, String content) {
    try (Writer w =
        pe.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", TesseraProcessor.FUNCTION_INDEX + ns).openWriter()) {
      w.write(content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void writesOneIndexEntryPerNamespace() {
    assertEquals("com.example.Fmt\n", main().generatedResource("META-INF/tessera/functions/fmt"));
  }

  @Test
  void templatesFindFunctionsCompiledEarlier() {
    Compilation c =
        Compilation.builder()
            .classpath(main())
            .template("hi.html", "<p t:params=\"String name\" t:text=\"${fmt.upper(name)}\">x</p>")
            .compile()
            .assertSuccess();
    assertEquals("<p>ANN</p>", c.render("hi", model("name", "ann")));
    // Only classes compiled now are indexed; the earlier output keeps its own entry.
    assertNull(c.generatedResource("META-INF/tessera/functions/fmt"));
  }

  @Test
  void unknownNamespaceStillErrorsWithPosition() {
    Compilation.builder()
        .classpath(main())
        .template("bad.html", "<p t:params=\"String name\"\n   t:text=\"${nope.upper(name)}\">x</p>")
        .compile()
        .assertError("bad.html:2:14", "unknown identifier or function namespace 'nope'");
  }

  @Test
  void indexEntriesForClassesWithoutTheNamespaceAreIgnored() {
    String template = "<p t:text=\"${rt.getRuntime()}\">x</p>";
    // Not a @TemplateFunctions class at all.
    Compilation.builder()
        .classpath(forged("rt", "java.lang.Runtime\n", null, null))
        .template("rt.html", template)
        .compile()
        .assertError("rt.html:1:14", "unknown identifier or function namespace 'rt'");
    // Declares a different namespace.
    Compilation.builder()
        .classpath(forged("rt", "com.example.Fmt", "com.example.Fmt", FMT))
        .template("rt.html", template)
        .compile()
        .assertError("rt.html:1:14", "unknown identifier or function namespace 'rt'");
  }

  @Test
  void indexedFunctionsAreCheckedLikeCompiledOnes() {
    String bad =
        """
        package com.example;
        import dev.tessera.runtime.*;
        @TemplateFunctions("bad")
        public final class Bad {
          private Bad() {}
          @TemplateFunction public static Class<?> type(String s) { return String.class; }
        }
        """;
    Compilation.builder()
        // Without templates the first compilation never checks its function classes.
        .classpath(Compilation.builder().java("com.example.Bad", bad).compile().assertSuccess())
        .template("b.html", "<p t:params=\"String s\" t:text=\"${bad.type(s)}\">x</p>")
        .compile()
        .assertError("com/example/Bad.java:1:1", "@TemplateFunction com.example.Bad.type returns");
  }
}
