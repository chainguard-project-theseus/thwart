package dev.tessera.compiler.guide;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every test that docs/security-model.md section 8 (the proof table) names as pinning a context
 * exists as a test class in some module, so the table can't cite a deleted or renamed test (#764).
 */
class ProofTableTest {
  private static final Path ROOT = Path.of("..");
  private static final Pattern TEST_NAME = Pattern.compile("`([A-Z][A-Za-z0-9]*Test)`");

  @Test
  void everyTestInTheProofTableExists() throws IOException {
    Set<String> named = new TreeSet<>();
    boolean inTable = false;
    for (String line : Files.readAllLines(ROOT.resolve("docs/security-model.md"))) {
      if (line.startsWith("## ")) {
        inTable = line.startsWith("## 8. Proof table");
      } else if (inTable) {
        Matcher m = TEST_NAME.matcher(line);
        while (m.find()) {
          named.add(m.group(1));
        }
      }
    }
    assertFalse(named.isEmpty(), "docs/security-model.md has no '## 8. Proof table' naming tests");
    Set<String> tests;
    try (Stream<Path> files = Files.walk(ROOT)) {
      tests = files.filter(p -> p.toString().replace('\\', '/').contains("/src/test/java/"))
          .map(p -> p.getFileName().toString())
          .filter(n -> n.endsWith("Test.java"))
          .map(n -> n.substring(0, n.length() - ".java".length()))
          .collect(Collectors.toSet());
    }
    List<String> missing = named.stream().filter(n -> !tests.contains(n)).collect(Collectors.toList());
    assertTrue(missing.isEmpty(), "docs/security-model.md section 8 names tests that don't exist: " + missing);
  }
}
