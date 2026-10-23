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
 * <p>The JDK comes from {@code -Dtessera.jdk8.home}, which the pom fills from {@code JDK8_HOME}.
 * Without it the test is skipped locally and fails in CI ({@code CI=true}).
 */
class Jdk8ProcessorTest {

  @TempDir Path dir;

  @Test
  void jdk8JavacRunsTheProcessorAndTheAppRendersOnJava8() throws Exception {
    Path jdk = jdk8();
    Path runtime = codeSource(Escapers.class);
    String processorPath = join(codeSource(TesseraProcessor.class), runtime);

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
