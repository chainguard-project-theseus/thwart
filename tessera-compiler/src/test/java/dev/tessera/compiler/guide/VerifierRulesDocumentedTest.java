package dev.tessera.compiler.guide;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.verify.Problem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Every verifier rule id ({@code [verifier:RULE]}, {@link Problem}) has an entry in the guide's rule
 * table (section 5) and in the context model's rule table (section 9), so a build error always has a
 * documented fix (#687).
 */
class VerifierRulesDocumentedTest {
  private static final Path ROOT = Path.of("..");

  @Test
  void everyRuleIsInTheGuideAndTheContextModel() throws IOException {
    String guide = Files.readString(ROOT.resolve("docs/guide.md"));
    String model = Files.readString(ROOT.resolve("docs/context-model.md"));
    for (Problem p : Problem.values()) {
      String id = "`" + p.name() + "`";
      assertTrue(guide.contains(id), "docs/guide.md section 5 has no entry for " + id);
      assertTrue(model.contains(id), "docs/context-model.md section 9 has no entry for " + id);
    }
  }
}
