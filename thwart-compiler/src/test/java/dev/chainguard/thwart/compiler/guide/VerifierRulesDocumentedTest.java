package dev.chainguard.thwart.compiler.guide;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chainguard.thwart.compiler.verify.Problem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every verifier rule id ({@code [verifier:RULE]}, {@link Problem}) has a row in the guide's rule
 * table ({@code docs/guide/limitations.md}) and in the context model's rule table (section 9), so a
 * build error always has a documented fix (#687). Only the first column of each table counts: an id
 * named in prose elsewhere in either file is not an entry.
 */
class VerifierRulesDocumentedTest {
  private static final Path ROOT = Path.of("..");

  @Test
  void everyRuleIsInTheGuideAndTheContextModel() throws IOException {
    String guide = ruleColumn("docs/guide/limitations.md", "| Rule | What it means | Fix |");
    String model = ruleColumn("docs/context-model.md", "| Id | Rejects | Accepted instead |");
    for (Problem p : Problem.values()) {
      String id = "`" + p.name() + "`";
      assertTrue(guide.contains(id), "docs/guide/limitations.md rule table has no row for " + id);
      assertTrue(model.contains(id), "docs/context-model.md section 9 rule table has no row for " + id);
    }
  }

  /** The first cells of the one table under {@code header} in {@code file}, one per line. */
  private static String ruleColumn(String file, String header) throws IOException {
    List<String> lines = Files.readAllLines(ROOT.resolve(file));
    int start = lines.indexOf(header);
    assertTrue(start >= 0 && start == lines.lastIndexOf(header), file + ": expected one line '" + header + "'");
    StringBuilder ids = new StringBuilder();
    for (int i = start + 2; i < lines.size() && lines.get(i).startsWith("|"); i++) {
      ids.append(lines.get(i).split("\\|")[1]).append('\n');
    }
    return ids.toString();
  }
}
