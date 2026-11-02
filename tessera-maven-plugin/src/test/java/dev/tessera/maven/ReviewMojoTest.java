package dev.tessera.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #428: the review goal lists every {@code @TemplateFunction}; informational only (#676).
 */
class ReviewMojoTest {
  @TempDir Path base;

  private static final String FUNCTIONS = """
      package com.example;
      import dev.tessera.runtime.SafeHtml;
      import dev.tessera.runtime.TemplateFunction;
      import dev.tessera.runtime.TemplateFunctions;
      import java.util.List;
      @TemplateFunctions("fmt")
      public final class Fmt {
        private Fmt() {}
        @TemplateFunction
        public static String upper(String s, int[] n, List<String> l) {
          return s.toUpperCase();
        }
        @TemplateFunction
        @Deprecated(since = "1", forRemoval = false)
        public static SafeHtml bold(String s) {
          return SafeHtml.escape(s);
        }
        public static String notAFunction() {
          return "";
        }
      }
      """;

  private static final String NS = "xmlns:t=\"https://tessera.dev\"";

  /** A real module: a layout taking {@code Fragment} params, a page passing them, a flow partial, a model param. */
  private static final Map<String, String> TEMPLATES = Map.of(
      "fragments/layout.html",
      "<div " + NS + ">\n<div t:fragment=\"layout(Fragment content, Fragment aside)\">\n"
          + "<main t:insert=\"${content}\"></main><aside t:replace=\"${aside}\"></aside></div></div>",
      "fragments/card.html", "<div " + NS + "><p>card</p></div>",
      "page.html",
      "<div " + NS + " t:params=\"com.example.Model model\">\n"
          + "<t:block t:replace=\"~{fragments/layout :: layout(~{::section}, ~{::nav})}\"></t:block>\n"
          + "<section><p t:text=\"${model.name}\">x</p></section><nav><a href=\"/\">home</a></nav></div>");

  /** The module's own Java: a model class the registry casts to, and a controller. */
  private static final String MODEL = """
      package com.example;
      @dev.tessera.runtime.TemplateView public final class Model {
        public String getName() {
          return "n";
        }
      }
      """;

  /** An app class in the same module as the generated code, building an unverified Fragment as it does. */

  private Path compile(Map<String, String> sources, String... options) throws Exception {
    List<String> args = new ArrayList<>(List.of("-proc:none"));
    args.addAll(List.of(options));
    return javac(sources, args);
  }

  /** Compiles {@code sources} and {@code templates} with Tessera's annotation processor, as a module would. */
  private Path compileWithProcessor(Map<String, String> sources, Map<String, String> templates) throws Exception {
    Path root = base.resolve("templates");
    for (Map.Entry<String, String> e : templates.entrySet()) {
      Path p = root.resolve(e.getKey());
      Files.createDirectories(p.getParent());
      Files.writeString(p, e.getValue());
    }
    return javac(sources, List.of("-processor", "dev.tessera.compiler.processor.TesseraProcessor",
        "-Atessera.templates=" + root, "-Atessera.package=com.example.templates",
        "-s", base.resolve("generated").toString()));
  }

  private Path javac(Map<String, String> sources, List<String> options) throws Exception {
    Path src = base.resolve("src");
    Path classes = base.resolve("target/classes");
    Files.createDirectories(classes);
    Files.createDirectories(base.resolve("generated"));
    List<String> args = new ArrayList<>(List.of("-d", classes.toString(), "-cp", System.getProperty("java.class.path")));
    args.addAll(options);
    for (Map.Entry<String, String> e : sources.entrySet()) {
      Path p = src.resolve(e.getKey().replace('.', '/') + ".java");
      Files.createDirectories(p.getParent());
      Files.writeString(p, e.getValue());
      args.add(p.toString());
    }
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    assertEquals(0, javac.run(null, null, null, args.toArray(new String[0])));
    return classes;
  }

  private ReviewMojo mojo() {
    ReviewMojo mojo = new ReviewMojo();
    mojo.outputDirectory = base.resolve("target/classes").toFile();
    mojo.buildDirectory = base.resolve("target").toFile();
    return mojo;
  }

  private String report() throws Exception {
    return Files.readString(base.resolve("target").resolve(ReviewMojo.REPORT), StandardCharsets.UTF_8);
  }

  @Test
  void listsFunctionsAndMarksSafeReturnTypes() throws Exception {
    compile(Map.of("com.example.Fmt", FUNCTIONS));
    mojo().execute();
    String report = report();
    assertTrue(report.contains("## @TemplateFunctions (2)\n"), report);
    assertTrue(report.contains("fmt.upper(String, int[], List) -> String  com/example/Fmt.java:11\n"), report);
    assertTrue(report.contains("fmt.bold(String) -> SafeHtml  com/example/Fmt.java:16  RETURNS SafeHtml: output without escaping\n"), report);
    assertFalse(report.contains("notAFunction"), report);
  }

  /** A module compiled with the real processor: its generated classes register no function. */
  @Test
  void generatedCodeListsNoFunctions() throws Exception {
    compileWithProcessor(Map.of("com.example.Model", MODEL, "com.example.Fmt", FUNCTIONS), TEMPLATES);
    mojo().execute();
    assertTrue(report().contains("## @TemplateFunctions (2)\n"), report());
  }

  @Test
  void java8BytecodeAndNoDebugInfo() throws Exception {
    compile(Map.of("com.example.Fmt", FUNCTIONS.replace("@Deprecated(since = \"1\", forRemoval = false)", "")),
        "--release", "8", "-Xlint:-options", "-g:none");
    mojo().execute();
    assertTrue(report().contains("fmt.upper(String, int[], List) -> String  com.example.Fmt.upper\n"), report());
  }

  @Test
  void noClassesNoFailure() throws Exception {
    mojo().execute();
    assertTrue(report().contains("## @TemplateFunctions (0)\n"));
  }

  @Test
  void signatures() {
    assertEquals("(String, int) -> SafeHtml", ReviewMojo.signature("(Ljava/lang/String;I)Ldev/tessera/runtime/SafeHtml;"));
    assertEquals("(long[][], Map.Entry) -> boolean", ReviewMojo.signature("([[JLjava/util/Map$Entry;)Z"));
  }
}
