package dev.tessera.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Golden files in {@code src/test/resources/golden/<app>}: {@code input/} the templates,
 * {@code expected/} the converted templates (absent: the input, unchanged), and
 * {@code expected-report.md} the report. {@code petclinic} is spring-petclinic style Thymeleaf; the
 * others are reduced copies of converted bench templates: {@code spring-petclinic-boot} and
 * {@code jobsearch} with layout markup that nests a second {@code <body>} (#409), the rest with a
 * recursive rich-text fragment (#369), which the compiler rejects and migrate leaves as it is, with
 * an empty report (#595).
 */
class GoldenFileTest {

  @TempDir Path tmp;

  private static Path golden(String app, String name) throws URISyntaxException {
    return Path.of(GoldenFileTest.class.getResource("/golden/" + app + "/").toURI()).resolve(name);
  }

  private static int run(String... args) {
    PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    return Main.run(args, sink, sink);
  }

  @ParameterizedTest
  @ValueSource(strings = {"petclinic", "purl-server", "fredbet", "luckyblog", "ewp-registry", "spring-petclinic-boot", "jobsearch"})
  void convertsTemplatesAndWritesReport(String app) throws Exception {
    Path out = tmp.resolve("out");
    Path report = tmp.resolve("report.md");

    assertEquals(0, run(golden(app, "input").toString(), out.toString(), "--report", report.toString()));

    Path expected = golden(app, "expected");
    assertSameTree(Files.isDirectory(expected) ? expected : golden(app, "input"), out);
    assertEquals(Files.readString(golden(app, "expected-report.md")), Files.readString(report));
  }

  @ParameterizedTest
  @ValueSource(strings = {"petclinic", "spring-petclinic-boot", "jobsearch"})
  void runningTwiceChangesNothing(String app) throws Exception {
    Path once = tmp.resolve("once");
    Path twice = tmp.resolve("twice");

    assertEquals(0, run(golden(app, "input").toString(), once.toString(), "--report", tmp.resolve("r1.md").toString()));
    assertEquals(0, run(once.toString(), twice.toString(), "--report", tmp.resolve("r2.md").toString()));

    assertSameTree(once, twice);
  }

  private static void assertSameTree(Path expected, Path actual) throws IOException {
    List<Path> expectedFiles = relativeFiles(expected);
    assertEquals(expectedFiles, relativeFiles(actual));
    for (Path rel : expectedFiles) {
      assertEquals(Files.readString(expected.resolve(rel)), Files.readString(actual.resolve(rel)),
          rel.toString());
    }
  }

  private static List<Path> relativeFiles(Path root) throws IOException {
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(Files::isRegularFile).map(root::relativize).sorted().toList();
    }
  }
}
