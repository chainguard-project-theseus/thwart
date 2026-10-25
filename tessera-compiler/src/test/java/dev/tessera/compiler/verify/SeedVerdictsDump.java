package dev.tessera.compiler.verify;

import dev.tessera.compiler.oracle.Seeds;
import dev.tessera.compiler.testing.Compilation;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Not a gate: writes the verifier's verdict on every seed as a TSV (name, 0dda2f1 verdict, verifier
 * verdict with its rules, whether the full compile succeeds, the first errors), to re-derive
 * {@code seed-expectations.txt} after a change to the verifier or an engine bump (#364: such a PR
 * lists every change in verdict). Run with {@code -Dtessera.verify.seedDump=<file>}.
 */
@EnabledIfSystemProperty(named = "tessera.verify.seedDump", matches = ".+")
class SeedVerdictsDump {
  private static final Pattern RULE = Pattern.compile("\\[verifier:([A-Z_]+)\\]");

  @Test
  void dump() throws Exception {
    Path out = Path.of(System.getProperty("tessera.verify.seedDump"));
    try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
      for (Seeds.Seed s : Seeds.load()) {
        Compilation v = Compilation.builder().template("t.html", s.template()).verifierOnly().compile();
        Compilation full = Compilation.builder().template("t.html", s.template()).compile();
        Set<String> rules = new TreeSet<>();
        for (String e : v.errors()) {
          Matcher m = RULE.matcher(e);
          if (m.find()) {
            rules.add(m.group(1));
          }
        }
        String verdict = v.success() ? "ACCEPT" : "REJECT " + String.join(",", rules);
        w.println(String.join("\t", s.name(), s.expected().toString(), verdict, full.success() ? "OK" : "PROFILE",
            oneLine(v.errors().isEmpty() ? "" : v.errors().get(0)), oneLine(full.errors().isEmpty() ? "" : full.errors().get(0)),
            oneLine(String.valueOf(v.skeleton("t"))), oneLine(s.template())));
      }
    }
  }

  private static String oneLine(String s) {
    return s.replace('\n', ' ').replace('\t', ' ').replace('\r', ' ');
  }
}
