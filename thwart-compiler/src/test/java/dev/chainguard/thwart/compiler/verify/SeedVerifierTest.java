package dev.chainguard.thwart.compiler.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chainguard.thwart.compiler.oracle.Seeds;
import dev.chainguard.thwart.compiler.testing.Compilation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The oracle seed corpus (#320) through the context verifier alone (#367), against the
 * expectations in {@code src/test/resources/verify/seed-expectations.txt}. Each line is
 *
 * <pre>name family expect [rule...] [# note]</pre>
 *
 * <ul>
 *   <li>family: {@code AB} (a parser differential or composition seed live on 0dda2f1, the
 *       verifier's to reject), {@code C}, {@code D}, {@code E} (script position, client
 *       frameworks, URL policy: not markup contexts), {@code OVER} (over-escaping in raw text, not
 *       a finding), {@code -} (clean or rejected on 0dda2f1).
 *   <li>expect: {@code VERIFIER} with its rule ids (compiled with the profile's diagnostics
 *       suppressed, the verifier reports each rule), {@code ACCEPT} (the verifier accepts the
 *       skeleton the compiler writes today), or {@code PROFILE} (the profile's tokenizer stops
 *       on it, so there is no tree to verify; the build fails).
 * </ul>
 *
 * An {@code AB} seed is never {@code ACCEPT} without a note saying how main fixed it in codegen.
 */
class SeedVerifierTest {

  record Expectation(String name, String family, String expect, List<String> rules, String note) {}

  private static Map<String, Expectation> expectations;

  static synchronized Map<String, Expectation> expectations() throws IOException {
    if (expectations == null) {
      expectations = load();
    }
    return expectations;
  }

  private static Map<String, Expectation> load() throws IOException {
    String text;
    try (InputStream in = SeedVerifierTest.class.getResourceAsStream("/verify/seed-expectations.txt")) {
      assertNotNull(in);
      text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    Map<String, Expectation> out = new LinkedHashMap<>();
    for (String line : text.split("\n")) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      int hash = line.indexOf(" #");
      String note = hash < 0 ? "" : line.substring(hash + 2).strip();
      String[] f = (hash < 0 ? line : line.substring(0, hash)).strip().split("\\s+");
      List<String> rules = new ArrayList<>(List.of(f).subList(3, f.length));
      Expectation e = new Expectation(f[0], f[1], f[2], rules, note);
      assertTrue(Set.of("AB", "C", "D", "E", "OVER", "-").contains(e.family()), line);
      assertTrue(Set.of("VERIFIER", "ACCEPT", "PROFILE").contains(e.expect()), line);
      assertEquals(e.expect().equals("VERIFIER"), !rules.isEmpty(), line);
      assertTrue(out.put(e.name(), e) == null, "duplicate " + e.name());
    }
    return out;
  }

  static Stream<Seeds.Seed> seeds() {
    return Seeds.load().stream();
  }

  @Test
  void everySeedHasAnExpectation() throws IOException {
    Map<String, Expectation> e = expectations();
    Set<String> names = Seeds.load().stream().map(Seeds.Seed::name).collect(Collectors.toCollection(TreeSet::new));
    assertEquals(names, new TreeSet<>(e.keySet()));
    // #367: the verifier rejects the <select> seeds with SELECT_CONTENT; and these three must be rejected by
    // the verifier (the prototype missed pr304-9, the value oracle pr305-4 and 306-A29).
    for (String n : List.of("306-A31", "pr304-7", "pr305-1", "pr305-2", "pr305-3", "pr305-4", "pr305-5", "pr305-6", "pr305-7",
        "pr305-8", "pr305-9")) {
      assertTrue(e.get(n).rules().contains("SELECT_CONTENT"), n);
    }
    // Positive controls (the review probes): ordinary table, form, option and page markup compiles end to end.
    Map<String, Seeds.Seed> byName =
        Seeds.load().stream().collect(Collectors.toMap(Seeds.Seed::name, s -> s, (a, b) -> a));
    for (String n : List.of("T5-form-in-cell", "T6-caption-nested-table", "T8-option", "T10-template-in-table",
        "T11-conditional-rows", "T21-ordinary-page")) {
      assertEquals("ACCEPT", e.get(n).expect(), n);
      Compilation.builder().template("t.html", byName.get(n).template()).compile().assertSuccess();
    }
    for (String n : List.of("pr304-9", "pr305-4", "306-A29")) {
      assertEquals("VERIFIER", e.get(n).expect(), n);
    }
    for (Expectation x : e.values()) {
      if (x.family().equals("AB") && x.expect().equals("ACCEPT")) {
        assertTrue(x.note().startsWith("fixed on main:"), x.name() + ": an A/B seed the verifier accepts needs a note");
      }
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("seeds")
  void seed(Seeds.Seed seed) throws IOException {
    Expectation e = expectations().get(seed.name());
    assertNotNull(e, seed.name());
    switch (e.expect()) {
      case "VERIFIER" -> {
        Compilation c = Compilation.builder().template("t.html", seed.template()).verifierOnly().compile();
        assertFalse(c.success(), seed.name() + ": the verifier alone accepts it");
        for (String rule : e.rules()) {
          assertTrue(c.errors().stream().anyMatch(m -> m.contains("[verifier:" + rule + "]")),
              seed.name() + ": no " + rule + " in " + c.errors());
        }
      }
      default -> {
        boolean accept = e.expect().equals("ACCEPT");
        Compilation c = Compilation.builder().template("t.html", seed.template()).verifierOnly().compile();
        assertTrue(!accept || c.success(), seed.name() + ": " + c.errors());
        Skeleton.TemplateSkeleton t = c.skeleton("t");
        boolean nothingToCheck = c.success() && t != null && !checks(t.body())
            && t.fragments().values().stream().noneMatch(SeedVerifierTest::checks);
        // PROFILE: the build fails. An ACCEPT seed's build matters only when the verifier has nothing to check.
        boolean builds = (!accept || nothingToCheck) && Compilation.builder().template("t.html", seed.template()).compile().success();
        assertTrue(accept || !builds, seed.name() + ": builds");
        // #752: the compiler wrote nothing the verifier checks, because the profile rejected it first, so the
        // verifier's verdict covers nothing. Marked, so the file says which seeds exercise the verifier.
        assertEquals(nothingToCheck && !builds, e.note().contains(HOLE_NOT_EMITTED),
            seed.name() + ": the verifier-only skeleton holds " + (nothingToCheck ? "nothing to check" : "a value") + ": " + t);
      }
    }
  }

  private static final String HOLE_NOT_EMITTED = "hole not emitted";

  /** Whether the verifier checks something in {@code n}: a value, an insertion or control flow, not just markup. */
  private static boolean checks(Skeleton.Node n) {
    if (n instanceof Skeleton.Seq s) {
      return s.nodes().stream().anyMatch(SeedVerifierTest::checks);
    }
    // The CSP nonce a <script> or <style> gets is generated markup, not the template's value.
    return !(n instanceof Skeleton.Static)
        && !(n instanceof Skeleton.Opt o && o.kind() == Skeleton.OptKind.CSP_NONCE);
  }
}
