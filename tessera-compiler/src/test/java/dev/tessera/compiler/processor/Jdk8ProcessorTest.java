package dev.tessera.compiler.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.tessera.runtime.Escapers;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Java 8 build floor (#166): a small Java 8 application (view classes, an enum, a
 * {@code @TemplateFunctions} class, templates) compiled by a real JDK 8 javac with the processor
 * on its processor path, then rendered on a JDK 8 JVM. A second javac run compiles the templates
 * against the first's output, finding the function class through the function index (#118).
 *
 * <p>The processor path also holds the verifier's two spec HTML parsers (#365), which the
 * {@code tessera-compiler} jar shades; {@link #shadedEnginesRunOnJava8} runs them on JDK 8.
 *
 * <p>The JDK comes from {@code -Dtessera.jdk8.home}, which the pom fills from {@code JDK8_HOME}.
 * Without it the test is skipped locally and fails in CI ({@code CI=true}).
 */
class Jdk8ProcessorTest {

  @TempDir Path dir;

  @Test
  void jdk8JavacRunsTheProcessorAndTheAppRendersOnJava8() throws Exception {
    Path jdk = jdk8();
    Path runtime = codeSource(Escapers.class);
    String processorPath = processor() + File.pathSeparator + runtime;

    Path src = dir.resolve("src");
    Path templates = dir.resolve("templates");
    Path out = dir.resolve("classes");
    write(src.resolve("app/Kind.java"), "package app; public enum Kind { DOG, CAT }");
    write(
        src.resolve("app/Pet.java"),
        """
        package app;
        import dev.tessera.runtime.TemplateView;
        @TemplateView
        public final class Pet {
          private final String name;
          private final Kind kind;
          public Pet(String name, Kind kind) { this.name = name; this.kind = kind; }
          public String getName() { return name; }
          public Kind getKind() { return kind; }
        }
        """);
    write(
        src.resolve("app/Owner.java"),
        """
        package app;
        import dev.tessera.runtime.TemplateView;
        import java.util.List;
        @TemplateView
        public final class Owner {
          private final int id;
          private final String name;
          private final List<Pet> pets;
          public Owner(int id, String name, List<Pet> pets) { this.id = id; this.name = name; this.pets = pets; }
          public int getId() { return id; }
          public String getName() { return name; }
          public List<Pet> getPets() { return pets; }
          public boolean isRegular() { return pets.size() > 1; }
        }
        """);
    write(
        src.resolve("app/Shout.java"),
        """
        package app;
        import dev.tessera.runtime.TemplateFunction;
        import dev.tessera.runtime.TemplateFunctions;
        @TemplateFunctions("shout")
        public final class Shout {
          private Shout() {}
          @TemplateFunction
          public static String loud(String s) { return s.toUpperCase(java.util.Locale.ROOT) + "!"; }
        }
        """);
    write(
        src.resolve("app/Main.java"),
        """
        package app;
        import dev.tessera.runtime.RenderContext;
        import dev.tessera.runtime.StringOutput;
        import dev.tessera.runtime.TemplateRegistry;
        import java.util.Arrays;
        import java.util.HashMap;
        import java.util.Map;
        public final class Main {
          public static void main(String[] args) {
            Owner owner = new Owner(7, "Ann <b>", Arrays.asList(new Pet("Rex", Kind.DOG), new Pet("Tom", Kind.CAT)));
            Map<String, Object> model = new HashMap<>();
            model.put("owner", owner);
            StringOutput out = new StringOutput();
            TemplateRegistry.load(Main.class.getClassLoader()).find("owners/details").get()
                .renderer().render(model, RenderContext.defaults(), out);
            System.out.print(out);
          }
        }
        """);
    write(
        templates.resolve("owners/details.html"),
        """
        <div xmlns:t="https://tessera.dev" t:import="app.*" t:params="Owner owner">
        <h1 t:text="${owner.name}">name</h1>
        <a t:href="@{/owners/{id}(id=${owner.id})}">edit</a>
        <ul><li t:each="pet, stat : ${owner.pets}" t:text="|${stat.count}. ${pet.name} (${pet.kind})|">pet</li></ul>
        <p t:if="${owner.regular}" t:text="${shout.loud(owner.name)}">regular</p>
        <script>var id = [[${owner.id}]]; var owner = [[owner]];</script>
        </div>
        """);

    List<String> javac = new ArrayList<>();
    javac.add(jdk.resolve("bin/javac").toString());
    javac.addAll(List.of("-encoding", "UTF-8", "-d", out.toString()));
    javac.addAll(List.of("-classpath", runtime.toString(), "-processorpath", processorPath));
    javac.add("-Atessera.templates=" + templates);
    try (Stream<Path> files = Files.walk(src)) {
      files.filter(p -> p.toString().endsWith(".java")).forEach(p -> javac.add(p.toString()));
    }
    Files.createDirectories(out);
    String compiled = run(javac);
    assertFalse(compiled.contains("warning"), compiled);
    assertTrue(Files.exists(out.resolve("tessera/generated/owners/DetailsTemplate.class")), compiled);

    // A test compile (#118): the same templates, with the function class only on the classpath.
    Path testSrc = dir.resolve("test-src/app/MainTest.java");
    Path testOut = Files.createDirectories(dir.resolve("test-classes"));
    write(testSrc, "package app; class MainTest {}");
    String testCompiled =
        run(List.of(
            jdk.resolve("bin/javac").toString(), "-encoding", "UTF-8", "-d", testOut.toString(),
            "-classpath", join(out, runtime), "-processorpath", processorPath,
            "-Atessera.templates=" + templates, testSrc.toString()));
    assertTrue(Files.exists(testOut.resolve("tessera/generated/owners/DetailsTemplate.class")), testCompiled);

    String rendered = run(List.of(jdk.resolve("bin/java").toString(), "-cp", join(out, runtime), "app.Main"));
    assertEquals(
        """
        <div>
        <h1>Ann &lt;b&gt;</h1>
        <a href="/owners/7">edit</a>
        <ul><li>1. Rex (DOG)</li><li>2. Tom (CAT)</li></ul>
        <p>ANN &lt;B&gt;!</p>
        <script>var id = 7; var owner = {"id":7,"name":"Ann \\u003cb\\u003e","pets":[{"name":"Rex","kind":"DOG"},{"name":"Tom","kind":"CAT"}],"regular":true};</script>
        </div>
        """,
        rendered);
  }

  /**
   * #233: Lombok getters on a {@code @TemplateView} class, with Lombok before and after Tessera on
   * the processor path, for Lombok 1.16 (espd's 1.16.6, which failed even when listed first) and
   * the current 1.18. A missing property is still one error naming the template position.
   */
  @Test
  void lombokGettersAreTemplatePropertiesInEitherProcessorOrder() throws Exception {
    Path jdk = jdk8();
    Path runtime = codeSource(Escapers.class);
    // The processor with the engines its verifier runs (#367).
    String tessera = processor();
    Path lombok16 = Path.of(System.getProperty("tessera.lombok16.jar", ""));
    assertTrue(Files.isRegularFile(lombok16), "no Lombok 1.16 jar at " + lombok16 + " (see the pom)");
    Path src = dir.resolve("demo/PageInfo.java");
    write(
        src,
        """
        package demo;
        import dev.tessera.runtime.TemplateView;
        import lombok.Getter;
        @TemplateView
        @Getter
        public class PageInfo {
          private final String locale;
          private final boolean active;
          public PageInfo(String locale, boolean active) { this.locale = locale; this.active = active; }
        }
        """);
    Path templates = dir.resolve("templates");
    Path page = templates.resolve("page.html");
    for (Path lombok : List.of(lombok16, codeSource(lombok.Getter.class))) {
      for (boolean lombokFirst : List.of(true, false)) {
        String processorPath =
            lombokFirst
                ? join(lombok) + File.pathSeparator + tessera + File.pathSeparator + join(runtime)
                : tessera + File.pathSeparator + join(runtime, lombok);
        Path out = Files.createDirectories(dir.resolve("lombok-" + lombok.getFileName() + "-" + lombokFirst));
        List<String> javac =
            List.of(
                jdk.resolve("bin/javac").toString(), "-encoding", "UTF-8", "-d", out.toString(),
                "-classpath", join(runtime, lombok), "-processorpath", processorPath,
                "-Atessera.templates=" + templates, src.toString());
        String what = lombok.getFileName() + (lombokFirst ? ", Lombok first" : ", Tessera first");

        write(page, "<p xmlns:t=\"https://tessera.dev\" t:params=\"demo.PageInfo page\" t:if=\"${page.active}\" t:text=\"${page.locale}\">x</p>");
        String compiled = run(javac);
        assertFalse(compiled.contains("warning"), what + ": " + compiled);
        assertTrue(Files.exists(out.resolve("tessera/generated/PageTemplate.class")), what + ": " + compiled);

        write(page, "<p xmlns:t=\"https://tessera.dev\" t:params=\"demo.PageInfo page\" t:text=\"${page.missing}\">x</p>");
        String failed = runFailing(javac);
        assertTrue(failed.contains("page.html:1:74: error: 'missing' is not a property of demo.PageInfo"), what + ": " + failed);
        assertTrue(failed.contains("1 error"), what + ": " + failed);
      }
    }
  }

  /**
   * #367: the context verifier runs in a JDK 8 javac. It accepts an ordinary page (the first test
   * renders one) and rejects a value the profile lets through but a browser runs as script: JSON
   * in an SVG {@code <script>}, which is no HTML script. The error names the hole's position.
   */
  @Test
  void theVerifierRunsInAJdk8Javac() throws Exception {
    Path jdk = jdk8();
    Path runtime = codeSource(Escapers.class);
    Path templates = dir.resolve("templates");
    Path src = dir.resolve("src/app/Placeholder.java");
    write(src, "package app; class Placeholder {}");
    Path out = Files.createDirectories(dir.resolve("verifier-classes"));
    List<String> javac =
        List.of(
            jdk.resolve("bin/javac").toString(), "-encoding", "UTF-8", "-d", out.toString(),
            "-classpath", runtime.toString(), "-processorpath", processor() + File.pathSeparator + runtime,
            "-Atessera.templates=" + templates, src.toString());
    write(templates.resolve("ok.html"),
        "<div xmlns:t=\"https://tessera.dev\" t:params=\"String s\"><p t:title=\"${s}\">[[${s}]]</p>"
            + "<script>var s = [[${s}]];</script></div>");
    String compiled = run(javac);
    assertTrue(Files.exists(out.resolve("tessera/generated/OkTemplate.class")), compiled);

    write(templates.resolve("bad.html"),
        "<div xmlns:t=\"https://tessera.dev\" t:params=\"String s\">\n<svg><script>var s = [[${s}]];</script></svg></div>");
    String failed = runFailing(javac);
    assertTrue(failed.contains("bad.html:2:26: error: a browser reads this value (written as JSON for a script) inside SVG <script>"), failed);
    assertTrue(failed.contains("[verifier:JSON_NOT_IN_SCRIPT]"), failed);
  }

  /**
   * #365: the shaded engines are Java 8 bytecode that a JDK 8 javac accepts and a JDK 8 JVM runs,
   * with assertions on (their own asserts are switched off by the engine), through the engine API.
   */
  @Test
  void shadedEnginesRunOnJava8() throws Exception {
    Path jdk = jdk8();
    Path src = dir.resolve("smoke/EngineSmoke.java");
    write(
        src,
        """
        package smoke;
        import dev.tessera.compiler.verify.engine.Engine;
        import dev.tessera.compiler.verify.engine.Engines;
        import dev.tessera.compiler.verify.engine.Parser;
        public final class EngineSmoke {
          public static void main(String[] args) throws Exception {
            for (Engine e : Engines.all()) {
              Parser p = e.document(true);
              p.feed("<!DOCTYPE html><p>&drop</span><a href=\\"x");
              Parser q = p.fork();
              q.feed("\\"><select><title><script>");
              System.out.println(e.name() + " " + p.state().tokenizer() + " " + p.state().attributeName()
                  + " " + q.state().tokenizer() + " " + q.state().currentNode());
            }
          }
        }
        """);
    Path out = Files.createDirectories(dir.resolve("smoke-classes"));
    String classpath = processor();
    run(List.of(jdk.resolve("bin/javac").toString(), "-proc:none", "-d", out.toString(), "-classpath", classpath, src.toString()));
    String output = run(List.of(jdk.resolve("bin/java").toString(), "-ea", "-cp", join(out) + File.pathSeparator + classpath, "smoke.EngineSmoke"));
    assertEquals(
        """
        htmlparser@validator-26.10.12 ATTRIBUTE_VALUE_DOUBLE_QUOTED href RCDATA title
        htmlparser-1.4.16 ATTRIBUTE_VALUE_DOUBLE_QUOTED href SCRIPT_DATA script
        """,
        output);
  }

  /** The processor as the tessera-compiler jar ships it: its classes and the two shaded engines. */
  private static String processor() throws URISyntaxException {
    return join(
        codeSource(TesseraProcessor.class),
        codeSource(dev.tessera.compiler.internal.html5.current.impl.TesseraAccess.class),
        codeSource(dev.tessera.compiler.internal.html5.classic.impl.TesseraAccess.class));
  }

  /** The configured JDK 8; skips the test locally, fails it in CI, when there is none. */
  private static Path jdk8() throws IOException, InterruptedException {
    String home = System.getProperty("tessera.jdk8.home", "");
    if (home.isBlank() || home.startsWith("${")) {
      String why = "no JDK 8 configured: set JDK8_HOME (or -Dtessera.jdk8.home) to a JDK 8 home";
      if ("true".equals(System.getenv("CI"))) {
        fail(why);
      }
      Assumptions.abort(why);
    }
    Path jdk = Path.of(home);
    if (!Files.isExecutable(jdk.resolve("bin/javac"))) {
      fail("tessera.jdk8.home=" + home + " has no bin/javac");
    }
    String version = run(List.of(jdk.resolve("bin/javac").toString(), "-version"));
    assertTrue(version.contains("javac 1.8."), "tessera.jdk8.home is not a JDK 8: " + version);
    return jdk;
  }

  /** Runs {@code command}; returns its combined output, failing the test on a non-zero exit. */
  private static String run(List<String> command) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(p.waitFor(2, TimeUnit.MINUTES), "timed out: " + command);
    assertEquals(0, p.exitValue(), String.join(" ", command) + "\n" + output);
    return output;
  }

  /** Runs {@code command}; returns its combined output, failing the test unless it exits non-zero. */
  private static String runFailing(List<String> command) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(p.waitFor(2, TimeUnit.MINUTES), "timed out: " + command);
    assertTrue(p.exitValue() != 0, "expected a failure: " + String.join(" ", command) + "\n" + output);
    return output;
  }

  private static Path codeSource(Class<?> c) throws URISyntaxException {
    return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI());
  }

  private static String join(Path... paths) {
    return String.join(File.pathSeparator, Stream.of(paths).map(Path::toString).toList());
  }

  private static void write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }
}
