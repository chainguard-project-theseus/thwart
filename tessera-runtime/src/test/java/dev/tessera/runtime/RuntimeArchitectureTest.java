package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.tessera.runtime.internal.Escapers;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Enforces design principles 1 and 4 on the runtime sources, and the Java 8 floor (#154). */
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

  /** Applications on Java 8 load the runtime: every class file must be major version 52. */
  @Test
  void runtimeClassFilesAreJava8() throws IOException {
    List<Path> classes;
    try (Stream<Path> files = Files.walk(Path.of("target/classes"))) {
      classes = files.filter(p -> p.toString().endsWith(".class")).toList();
    }
    assertFalse(classes.isEmpty(), "no class files under target/classes");
    assertEquals(
        List.of(), classes.stream().filter(p -> majorVersion(p) != 52).map(p -> p + ": " + majorVersion(p)).toList());
    // The class actually loaded by this test, not just the files on disk.
    try (InputStream in = Escapers.class.getResourceAsStream("Escapers.class")) {
      assertEquals(52, majorVersion(in));
    }
  }

  private static int majorVersion(Path p) {
    try (InputStream in = Files.newInputStream(p)) {
      return majorVersion(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static int majorVersion(InputStream in) throws IOException {
    DataInputStream data = new DataInputStream(in);
    assertEquals(0xCAFEBABE, data.readInt());
    data.readUnsignedShort(); // minor
    return data.readUnsignedShort();
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
