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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Golden files in {@code src/test/resources/golden/petclinic}: {@code input/} is spring-petclinic
 * style Thymeleaf, {@code expected/} the converted templates, {@code expected-report.md} the report.
 */
class GoldenFileTest {

  @TempDir Path tmp;

  private static Path golden(String name) throws URISyntaxException {
    return Path.of(GoldenFileTest.class.getResource("/golden/petclinic/" + name).toURI());
  }

  private static int run(String... args) {
    PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    return Main.run(args, sink, sink);
  }

  @Test
  void convertsPetclinicTemplatesAndWritesReport() throws Exception {
    Path out = tmp.resolve("out");
    Path report = tmp.resolve("report.md");

    assertEquals(0, run(golden("input").toString(), out.toString(), "--report", report.toString()));

    assertSameTree(golden("expected"), out);
    assertEquals(Files.readString(golden("expected-report.md")), Files.readString(report));
  }

  @Test
  void runningTwiceChangesNothing() throws Exception {
    Path once = tmp.resolve("once");
    Path twice = tmp.resolve("twice");

    assertEquals(0, run(golden("input").toString(), once.toString(), "--report", tmp.resolve("r1.md").toString()));
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
