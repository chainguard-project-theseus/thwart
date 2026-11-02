package dev.tessera.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #428, #602: the review goal lists every {@code @TemplateFunction} and every unverified door outside
 * Tessera-generated classes, and fails the build on a door.
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

  private static final String UNCHECKED = """
      package com.example;
      import static dev.tessera.runtime.Fragment.unverifiedForGeneratedCode;
      import dev.tessera.runtime.Fragment;
      import dev.tessera.runtime.TrustedUrl;
      import java.util.function.Function;
      public final class Pages {
        static Object pick(int k, Fragment.Body b) {
          switch (k) {
            case 1: return "a" + b;
            case 2: return "b";
            case 9: return "c";
            default: break;
          }
          switch (k) {
            case 100: return "x";
            case 2000000: return "y";
            default: break;
          }
          return Fragment.unverifiedForGeneratedCode(b);
        }
        static Object more(Fragment.Body b, String s) {
          long big = 123456789012L;
          double d = big * 2.5;
          Fragment a = unverifiedForGeneratedCode(d > 0 ? b : null);
          Function<Fragment.Body, Fragment> f = Fragment::unverifiedForGeneratedCode;
          return TrustedUrl.unchecked(s) + "" + a + f;
        }
      }
      """;

  private static final String CLEAN = """
      package com.example;
      import dev.tessera.runtime.SafeHtml;
      public final class Clean {
        static Object ok(String s) {
          return SafeHtml.concat(SafeHtml.escape(s), SafeHtml.sanitize(s)) + other.Fragment.unverifiedForGeneratedCode(s);
        }
      }
      """;

  /** Not Tessera's: same simple name, never reported. */
  private static final String LOOKALIKE = """
      package other;
      public final class Fragment {
        public static String unverifiedForGeneratedCode(String s) {
          return s;
        }
      }
      """;

  /** A hand-written Template vouching for its own output: SafeHtml.of and Fragment.of accept it. */
  private static final String VOUCHING = """
      package com.example;
      import dev.tessera.runtime.*;
      @TemplateView public final class Widget implements Template {
        public String templateName() {
          return "widget";
        }
        public void render(RenderContext ctx, Output out) {
          out.write("<script>alert(1)</script>");
        }
        @Override
        public boolean verifiedFlowPartial() {
          return true;
        }
      }
      """;

  /** Not a door: declares nothing, returns the default. */
  private static final String HONEST = """
      package com.example;
      import dev.tessera.runtime.*;
      public final class Honest implements Template {
        public String templateName() {
          return "honest";
        }
        public void render(RenderContext ctx, Output out) {
          out.write("<b>hi</b>");
        }
      }
      """;

  private static final String FACTORY = """
      package com.example;
      import dev.tessera.runtime.Fragment;
      public final class Factory {
        static Object make() {
          Fragment a = Fragment.unverifiedForGeneratedCode((ctx, out) -> out.write("<script>"));
          java.util.function.Function<Fragment.Body, Fragment> f = Fragment::unverifiedForGeneratedCode;
          return a + "" + f;
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
  private static final String LAYOUT_CALLER = """
      package com.example;
      import dev.tessera.runtime.*;
      public final class LayoutCaller {
        static Fragment call() {
          return Fragment.unverifiedForGeneratedCode((ctx, out) -> out.write("<script>"));
        }
      }
      """;

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

  /** Runs the goal, which must fail naming {@code n} doors, and returns the report. */
  private String failsWithDoors(int n) throws Exception {
    MojoFailureException e = assertThrows(MojoFailureException.class, () -> mojo().execute());
    assertTrue(e.getMessage().contains(n + " unverified door(s)"), e.getMessage());
    String report = report(); // still written
    assertTrue(report.contains("## Unverified doors (" + n + ")\n"), report);
    return report;
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
    assertTrue(report.contains("## Unverified doors (0)\n"), report);
  }

  @Test
  void everyDoorCallIsReportedAtItsLine() throws Exception {
    compile(Map.of("com.example.Pages", UNCHECKED, "com.example.Clean", CLEAN, "other.Fragment", LOOKALIKE));
    String report = failsWithDoors(4);
    assertTrue(report.contains("doors (4)\n"
        + "Fragment.unverifiedForGeneratedCode  com/example/Pages.java:19\n" // qualified, after a tableswitch and a lookupswitch
        + "Fragment.unverifiedForGeneratedCode  com/example/Pages.java:24\n" // static import
        + "Fragment.unverifiedForGeneratedCode  com/example/Pages.java:25\n" // method reference
        + "TrustedUrl.unchecked  com/example/Pages.java:26\n"), report);
    assertFalse(report.contains("Clean"), report);
  }

  /** Negative (#602): a hand-written Template whose verifiedFlowPartial() vouches for its output. */
  @Test
  void vouchingTemplateFailsTheBuild() throws Exception {
    compile(Map.of("com.example.Widget", VOUCHING, "com.example.Honest", HONEST));
    String report = failsWithDoors(1);
    assertTrue(report.contains("doors (1)\nverifiedFlowPartial() override  com/example/Widget.java:12\n"), report);
  }

  /** Negative (#602): app code building an unverified Fragment, by call or method reference. */
  @Test
  void unverifiedFragmentFactoryFailsTheBuild() throws Exception {
    compile(Map.of("com.example.Factory", FACTORY));
    String report = failsWithDoors(2);
    assertTrue(report.contains("doors (2)\n"
        + "Fragment.unverifiedForGeneratedCode  com/example/Factory.java:5\n"
        + "Fragment.unverifiedForGeneratedCode  com/example/Factory.java:6\n"), report);
  }

  @Test
  void cleanCodePasses() throws Exception {
    compile(Map.of("com.example.Clean", CLEAN, "other.Fragment", LOOKALIKE, "com.example.Fmt", FUNCTIONS,
        "com.example.Honest", HONEST));
    mojo().execute();
    assertTrue(report().contains("## Unverified doors (0)\n"));
  }

  /**
   * Positive control (#602): a module compiled with the real processor reports no doors, though its
   * generated classes call unverifiedForGeneratedCode and declare verifiedFlowPartial(). The
   * layout's fragment, which takes a Fragment, has no public method (#666).
   */
  @Test
  void generatedCodeIsNotADoor() throws Exception {
    Path classes = compileWithProcessor(Map.of("com.example.Model", MODEL), TEMPLATES);
    Map<String, ClassScan> scans = new LinkedHashMap<>();
    for (String c : List.of("PageTemplate", "fragments/LayoutTemplate", "fragments/CardTemplate")) {
      scans.put(c, ClassScan.scan(Files.readAllBytes(classes.resolve("com/example/templates/" + c + ".class"))));
    }
    // The generated code has each kind of door, so the control would catch a false positive.
    List<String> found = new ArrayList<>();
    scans.values().forEach(s -> s.doors.forEach(d -> found.add(d.target)));
    assertTrue(found.contains("Fragment.unverifiedForGeneratedCode"), found.toString());
    assertTrue(found.contains("verifiedFlowPartial() override"), found.toString());
    mojo().execute();
    assertTrue(report().contains("## Unverified doors (0)\n"), report());
  }

  /**
   * Negative (#602): in a module with a registry, an app class calling
   * Fragment.unverifiedForGeneratedCode as generated code does, and a vouching Template that is
   * also a page's parameter type (so the registry names it, in a cast), are still doors.
   */
  @Test
  void appCodeBesideGeneratedCodeIsStillADoor() throws Exception {
    Map<String, String> templates = new LinkedHashMap<>(TEMPLATES);
    templates.put("widget.html", "<div " + NS + " t:params=\"com.example.Widget w\"><p>w</p></div>");
    compileWithProcessor(Map.of("com.example.Model", MODEL, "com.example.LayoutCaller", LAYOUT_CALLER,
        "com.example.Widget", VOUCHING), templates);
    String report = failsWithDoors(2);
    assertTrue(report.contains("Fragment.unverifiedForGeneratedCode  com/example/LayoutCaller.java:5\n"), report);
    assertTrue(report.contains("verifiedFlowPartial() override  com/example/Widget.java:12\n"), report);
  }

  @Test
  void nestedAndSyntheticClassesOfGeneratedOnesAreGenerated() {
    Set<String> generated = Set.of("a/b/XTemplate");
    assertTrue(ReviewMojo.isGenerated("a/b/XTemplate", generated));
    assertTrue(ReviewMojo.isGenerated("a/b/XTemplate$1", generated));
    assertTrue(ReviewMojo.isGenerated("a/b/XTemplate$In$1", generated));
    assertFalse(ReviewMojo.isGenerated("a/b/XTemplateEvil", generated));
    assertFalse(ReviewMojo.isGenerated("a/b/YTemplate$1", generated));
  }

  @Test
  void java8BytecodeAndNoDebugInfo() throws Exception {
    compile(Map.of("com.example.Pages", UNCHECKED), "--release", "8", "-Xlint:-options", "-g:none");
    String report = failsWithDoors(4);
    assertTrue(report.contains("Fragment.unverifiedForGeneratedCode  com.example.Pages.pick\n"), report);
    assertTrue(report.contains("TrustedUrl.unchecked  com.example.Pages.more\n"), report);
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
