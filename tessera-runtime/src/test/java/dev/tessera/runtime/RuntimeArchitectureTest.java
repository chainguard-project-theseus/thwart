package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Enforces design principles 1 and 4 on the runtime sources. */
class RuntimeArchitectureTest {
  private static final Pattern FORBIDDEN =
      Pattern.compile(
          "java\\.lang\\.reflect|java\\.lang\\.invoke|MethodHandle|ScriptEngine|getDeclared|Class\\.forName"
              + "|setAccessible|javax\\.script|org\\.springframework|ognl|import (?!java\\.|javax\\.annotation\\.|dev\\.tessera\\.)");

  @Test
  void runtimeHasNoReflectionOrThirdPartyImports() throws IOException {
    List<String> offenders;
    try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
      offenders =
          files
              .filter(p -> p.toString().endsWith(".java"))
              .flatMap(RuntimeArchitectureTest::violations)
              .toList();
    }
    assertEquals(List.of(), offenders);
  }

  private static Stream<String> violations(Path p) {
    try {
      List<String> lines = Files.readAllLines(p);
      return IntStream.range(0, lines.size())
          .filter(i -> FORBIDDEN.matcher(lines.get(i)).find())
          .mapToObj(i -> p + ":" + (i + 1) + ": " + lines.get(i).trim());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
