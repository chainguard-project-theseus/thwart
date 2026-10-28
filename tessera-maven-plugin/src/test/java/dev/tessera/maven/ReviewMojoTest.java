package dev.tessera.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #428: the review goal lists every {@code @TemplateFunction} and {@code unchecked} call, and can fail on the latter. */
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

  private static final String UNCHECKED = """
      package com.example;
      import static dev.tessera.runtime.SafeHtml.unchecked;
      import dev.tessera.runtime.SafeHtml;
      import dev.tessera.runtime.TrustedUrl;
      import java.util.function.Function;
      public final class Pages {
        static Object pick(int k, String s) {
          switch (k) {
            case 1: return "a" + s;
            case 2: return "b";
            case 9: return "c";
            default: break;
          }
          switch (k) {
            case 100: return "x";
            case 2000000: return "y";
            default: break;
          }
          return SafeHtml.unchecked(s);
        }
        static Object more(String s) {
          long big = 123456789012L;
          double d = big * 2.5;
          SafeHtml a = unchecked(s + d);
          Function<String, SafeHtml> f = SafeHtml::unchecked;
          return TrustedUrl.unchecked(s) + "" + a + f;
        }
      }
      """;

  private static final String CLEAN = """
      package com.example;
      import dev.tessera.runtime.SafeHtml;
      public final class Clean {
        static Object ok(String s) {
          return SafeHtml.concat(SafeHtml.escape(s), SafeHtml.sanitize(s), other.SafeHtml.unchecked(s));
        }
      }
      """;

  /** Not Tessera's: same simple name, never reported. */
  private static final String LOOKALIKE = """
      package other;
      public final class SafeHtml {
        public static dev.tessera.runtime.SafeHtml unchecked(String s) {
          return dev.tessera.runtime.SafeHtml.escape(s);
        }
      }
      """;

  private Path compile(Map<String, String> sources, String... options) throws Exception {
    Path src = base.resolve("src");
    Path classes = base.resolve("target/classes");
    Files.createDirectories(classes);
    List<String> args = new ArrayList<>(List.of("-proc:none", "-d", classes.toString(), "-cp", System.getProperty("java.class.path")));
    args.addAll(List.of(options));
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

  private ReviewMojo mojo(boolean failOnUnchecked) {
    ReviewMojo mojo = new ReviewMojo();
    mojo.outputDirectory = base.resolve("target/classes").toFile();
    mojo.buildDirectory = base.resolve("target").toFile();
    mojo.failOnUnchecked = failOnUnchecked;
    return mojo;
  }

  private String report() throws Exception {
    return Files.readString(base.resolve("target").resolve(ReviewMojo.REPORT), StandardCharsets.UTF_8);
  }

  @Test
  void listsFunctionsAndMarksSafeReturnTypes() throws Exception {
    compile(Map.of("com.example.Fmt", FUNCTIONS));
    mojo(true).execute();
    String report = report();
    assertTrue(report.contains("## @TemplateFunctions (2)\n"), report);
    assertTrue(report.contains("fmt.upper(String, int[], List) -> String  com/example/Fmt.java:11\n"), report);
    assertTrue(report.contains("fmt.bold(String) -> SafeHtml  com/example/Fmt.java:16  RETURNS SafeHtml: output without escaping\n"), report);
    assertFalse(report.contains("notAFunction"), report);
    assertTrue(report.contains("calls (0)\n"), report);
  }

  @Test
  void listsEveryUncheckedCallAtItsLine() throws Exception {
    compile(Map.of("com.example.Pages", UNCHECKED, "com.example.Clean", CLEAN, "other.SafeHtml", LOOKALIKE));
    mojo(false).execute();
    String report = report();
    assertTrue(report.contains("calls (4)\n"
        + "SafeHtml.unchecked  com/example/Pages.java:19\n" // qualified, after a tableswitch and a lookupswitch
        + "SafeHtml.unchecked  com/example/Pages.java:24\n" // static import
        + "SafeHtml.unchecked  com/example/Pages.java:25\n" // method reference
        + "TrustedUrl.unchecked  com/example/Pages.java:26\n"), report);
    assertFalse(report.contains("Clean"), report);
  }

  @Test
  void failOnUncheckedFailsTheBuild() throws Exception {
    compile(Map.of("com.example.Pages", UNCHECKED));
    MojoFailureException e = assertThrows(MojoFailureException.class, () -> mojo(true).execute());
    assertTrue(e.getMessage().contains("4 unchecked call(s)"), e.getMessage());
    assertTrue(e.getMessage().contains("tessera.failOnUnchecked is set"), e.getMessage());
    assertTrue(report().contains("TrustedUrl.unchecked  com/example/Pages.java:26\n")); // still written
  }

  @Test
  void failOnUncheckedPassesCleanCode() throws Exception {
    compile(Map.of("com.example.Clean", CLEAN, "other.SafeHtml", LOOKALIKE, "com.example.Fmt", FUNCTIONS));
    mojo(true).execute();
    assertTrue(report().contains("calls (0)\n"));
  }

  @Test
  void java8BytecodeAndNoDebugInfo() throws Exception {
    compile(Map.of("com.example.Pages", UNCHECKED), "--release", "8", "-Xlint:-options", "-g:none");
    assertThrows(MojoFailureException.class, () -> mojo(true).execute());
    String report = report();
    assertTrue(report.contains("SafeHtml.unchecked  com.example.Pages.pick\n"), report);
    assertTrue(report.contains("TrustedUrl.unchecked  com.example.Pages.more\n"), report);
  }

  @Test
  void noClassesNoFailure() throws Exception {
    mojo(true).execute();
    assertTrue(report().contains("## @TemplateFunctions (0)\n"));
  }

  @Test
  void signatures() {
    assertEquals("(String, int) -> SafeHtml", ReviewMojo.signature("(Ljava/lang/String;I)Ldev/tessera/runtime/SafeHtml;"));
    assertEquals("(long[][], Map.Entry) -> boolean", ReviewMojo.signature("([[JLjava/util/Map$Entry;)Z"));
  }
}
