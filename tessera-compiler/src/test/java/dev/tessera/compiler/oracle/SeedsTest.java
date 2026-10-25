package dev.tessera.compiler.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.oracle.Finding.Kind;
import dev.tessera.compiler.oracle.Seeds.Expectation;
import dev.tessera.compiler.oracle.Seeds.Outcome;
import dev.tessera.compiler.oracle.Seeds.Seed;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SeedsTest {

  /**
   * The #306 probes with findings on 0dda2f1 (the reference tables of #320): probe, kinds, an
   * issue or PR that fixes it. A15 renders an {@code <img onerror>}, a handler (the table says
   * script). A58 and B10 are context mismatches whose JSON-escaped value also shows {@code {{}
   * in HTML text. A33 is {@code JS_NOT_LITERAL}, not a live script: the JSON's closing quote opens
   * an unterminated string, so no value makes the script parse (acorn's tokenizer saw the marker
   * before the error), and the Rhino check (#319) reports it so.
   */
  private static final Map<String, String> REFERENCE = new LinkedHashMap<>();

  static {
    String[][] rows = {
      {"A2", "LIVE_SCRIPT", "290"}, {"A3", "LIVE_HANDLER", "301"}, {"A4", "LIVE_SCRIPT", "301"},
      {"A5", "LIVE_SCRIPT", "301"}, {"A6", "LIVE_SCRIPT", "301"}, {"A8", "LIVE_SCRIPT", "298"},
      {"A9", "LIVE_SCRIPT", "298"}, {"A10", "LIVE_SCRIPT", "296"}, {"A11", "LIVE_SCRIPT", "298"},
      {"A12", "LIVE_SCRIPT", "298"}, {"A13", "LIVE_SCRIPT", "297"}, {"A14", "LIVE_SCRIPT", "295"},
      {"A15", "LIVE_HANDLER", "299"}, {"A16", "LIVE_SCRIPT", "300"}, {"A17", "LIVE_HANDLER", "300"},
      {"A24", "LIVE_SCRIPT", "301"}, {"A27", "LIVE_SCRIPT", "301"}, {"A28", "LIVE_SCRIPT", "298"},
      {"A30", "LIVE_SCRIPT", "298"}, {"A31", "LIVE_SCRIPT", "305"}, {"A33", "JS_NOT_LITERAL", "290"},
      {"A51", "LIVE_SCRIPT", "290"}, {"A52", "LIVE_SCRIPT", "290"}, {"B0", "LIVE_HANDLER", "301"},
      {"B1", "CROSS_ORIGIN_RESOURCE", "313"}, {"B2", "CROSS_ORIGIN_RESOURCE", "313"},
      {"B8", "LIVE_SCRIPT", "301"}, {"B9", "LIVE_SCRIPT", "301"},
      // context and CSTI findings
      {"A7", "CSTI", "301"}, {"A48", "CSTI", "301"}, {"A54", "CSTI", "301"}, {"A18", "CSTI", "336"},
      {"A58", "CONTEXT_MISMATCH CSTI", "301"}, {"B10", "CONTEXT_MISMATCH CSTI", "301"},
      // not in the #320 tables: non-executable model breaks the phase-1 checks report
      {"A32", "JS_NOT_LITERAL", "310"}, {"A35", "JS_NOT_LITERAL", "310"},
      {"A59", "CONTEXT_MISMATCH", "309"}, {"A60", "CONTEXT_MISMATCH", "309"},
    };
    for (String[] r : rows) {
      REFERENCE.put("306-" + r[0], r[1] + " #" + r[2]);
    }
  }

  @Test
  void everySeedFileParsesWithUniqueNamesAndTemplates() {
    List<Seed> seeds = Seeds.load();
    assertEquals(seeds.size(), seeds.stream().map(Seed::name).distinct().count());
    assertEquals(seeds.size(), seeds.stream().map(s -> s.toCase().id()).distinct().count());
    for (String file : Seeds.FILES) {
      assertTrue(seeds.stream().anyMatch(s -> s.file().equals(file)), file);
    }
    for (Seed s : seeds) {
      assertTrue(s.fixedBy().stream().allMatch(n -> n > 0), s.name());
      if (s.expected().outcome() == Outcome.FINDINGS) {
        assertFalse(s.fixedBy().isEmpty(), s.name() + " has findings but no fixed-by");
      }
      assertTrue(s.template().contains("t:params="), s.name());
      assertEquals(Seeds.wrap(s.body()), s.template(), s.name());
    }
    assertEquals(seeds.size(), Seeds.cases().size());
  }

  @Test
  void probeFileHasThe306ExperimentWithItsReferenceFindings() {
    List<Seed> probes = Seeds.load().stream().filter(s -> s.file().endsWith("/306-probes.txt")).toList();
    assertEquals(75, probes.size());
    Map<String, Seed> byName = probes.stream().collect(Collectors.toMap(Seed::name, Function.identity()));
    for (int i = 0; i <= 62; i++) {
      assertTrue(byName.containsKey("306-A" + i), "306-A" + i);
    }
    for (int i = 0; i <= 11; i++) {
      assertTrue(byName.containsKey("306-B" + i), "306-B" + i);
    }
    for (Map.Entry<String, String> e : REFERENCE.entrySet()) {
      Seed s = byName.get(e.getKey());
      String[] kindsAndFix = e.getValue().split(" #");
      assertEquals(kindsAndFix[0], s.expected().toString(), s.name());
      assertTrue(s.fixedBy().contains(Integer.parseInt(kindsAndFix[1])), s.name() + " fixed-by " + s.fixedBy());
    }
    // every other probe is clean or rejected on 0dda2f1
    for (Seed s : probes) {
      assertEquals(REFERENCE.containsKey(s.name()), s.expected().outcome() == Outcome.FINDINGS, s.name());
    }
    // recorded on 0dda2f1 with the htmlparser 1.4.16 oracle (vnu-1.4, removed in #408): history
    assertEquals(List.of("vnu-1.4"), byName.get("306-A31").engines());
    // the static-text control: payload in the template, not in a value
    assertEquals(Outcome.CLEAN, byName.get("306-B11").expected().outcome());
    assertEquals(
        "<div t:params=\"String s\"><svg><title><script>var a = [[s]];</script></title></svg></div>",
        byName.get("306-A5").template());
    assertEquals("<!--> <script> -->\n[[s]]\n<!-- </script> -->", byName.get("306-A14").body());
  }

  @Test
  void controlCharactersAreEscaped() {
    Seed vt =
        Seeds.load().stream()
            .filter(s -> s.body().equals("<script\u000b>var a = [[s]];</script>"))
            .findFirst()
            .orElseThrow();
    assertEquals(EnumSet.of(Kind.CSTI), vt.expected().kinds()); // not a script element: the value is text
    assertTrue(vt.fixedBy().contains(338));
    String odd = "a\u000b\r\t é😀{";
    assertEquals("a{U+000B}{U+000D}{U+0009}{U+2028}{U+00E9}{U+1F600}{", Seeds.escape(odd));
    assertEquals(odd, Seeds.unescape("t", 0, Seeds.escape(odd)));
    // text that looks like an escape survives the round trip
    String literal = "{U+0041} {U+12} {u+0041}";
    assertEquals("{U+007B}U+0041} {U+12} {u+0041}", Seeds.escape(literal));
    assertEquals(literal, Seeds.unescape("t", 0, Seeds.escape(literal)));
  }

  @Test
  void parsesHeadersBodiesAndOwnRoots() {
    List<Seed> seeds =
        Seeds.parse(
            "t.txt",
            "# preamble only\n====\n# name: a\n# expect-0dda2f1: LIVE_SCRIPT CSTI\n# fixed-by: #301 #277\n"
                + "# main: REJECTED\n# engines: firefox\n# note: n\n# a comment\n<p>\n[[s]]</p>\n====\n"
                + "# name: b\n# expect-0dda2f1: CLEAN\n<div t:params=\"String x\"><p>[[x]]</p></div>\n");
    assertEquals(2, seeds.size());
    Seed a = seeds.get(0);
    assertEquals("a", a.name());
    assertEquals(3, a.line());
    assertEquals("<p>\n[[s]]</p>", a.body());
    assertEquals("<div t:params=\"String s\"><p>\n[[s]]</p></div>", a.template());
    assertEquals(EnumSet.of(Kind.LIVE_SCRIPT, Kind.CSTI), a.expected().kinds());
    assertEquals(List.of(301, 277), a.fixedBy());
    assertEquals(Outcome.REJECTED, a.onMain().outcome());
    assertEquals(List.of("firefox"), a.engines());
    assertEquals("n", a.note());
    Seed b = seeds.get(1);
    assertEquals("<div t:params=\"String x\"><p>[[x]]</p></div>", b.template());
    assertEquals(List.of("*"), b.engines());
    assertNull(b.onMain());
    assertEquals(OracleCase.idOf(b.template()), b.toCase().id());
  }

  @Test
  void caseWithoutExpectationFails() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> Seeds.parse("t.txt", "# name: ok\n# expect-0dda2f1: CLEAN\n<p>x</p>\n====\n# name: x\n<p>[[s]]</p>\n"));
    assertEquals("t.txt:5: seed x has no '# expect-0dda2f1:'", e.getMessage());
  }

  @Test
  void otherFormatErrorsFail() {
    String[] bad = {
      "# expect-0dda2f1: CLEAN\n<p>x</p>", // no name
      "# name: a\n# expect-0dda2f1: LIVE_JAVA\n# fixed-by: #1\n<p>x</p>", // unknown kind
      "# name: a\n# expect-0dda2f1: LIVE_SCRIPT\n<p>x</p>", // findings without fixed-by
      "# name: a\n# expect-0dda2f1: LIVE_SCRIPT\n# fixed-by: 301\n<p>x</p>", // not #n
      "# name: a\n# expect-0dda2f1: CLEAN\n# fixed-by: #0\n<p>x</p>", // not an issue number
      "# name: a\n# expect-0dda2f1: CLEAN", // no template
      "# name: a\n# expect-0dda2f1: CLEAN\n# expect: CLEAN\n<p>x</p>", // unknown key
      "# name: a\n# name: b\n# expect-0dda2f1: CLEAN\n<p>x</p>", // duplicate key
      "# name: a b\n# expect-0dda2f1: CLEAN\n<p>x</p>", // bad name
      "# name: a\n# expect-0dda2f1: CLEAN REJECTED\n<p>x</p>", // mixed
    };
    for (String text : bad) {
      IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Seeds.parse("t.txt", text), text);
      assertTrue(e.getMessage().matches("t\\.txt:[1-3]: .*"), e.getMessage());
    }
  }

  @Test
  void expectationsMatchVerdicts() {
    Finding liveFirefox = new Finding(Kind.LIVE_SCRIPT, "firefox", "/html", "v");
    Finding live26 = new Finding(Kind.LIVE_SCRIPT, "vnu-26", "/html", "v");
    Finding unparsable = new Finding(Kind.JS_UNPARSABLE, "rhino", "/html", "v");
    Verdict both = new Verdict.Findings(List.of(liveFirefox, live26, new Finding(Kind.STRUCTURE_CHANGED, "vnu-26", "/", "v")));
    Verdict firefoxOnly = new Verdict.Findings(List.of(liveFirefox));
    Verdict rejected = new Verdict.Rejected(List.of("page.html:1:1: error"));
    Expectation live = Expectation.parse("LIVE_SCRIPT");
    assertTrue(live.metBy(both, List.of("*")));
    assertTrue(live.metBy(firefoxOnly, List.of("firefox")));
    assertFalse(live.metBy(firefoxOnly, List.of("firefox", "vnu-26")));
    assertFalse(live.metBy(new Verdict.Clean(), List.of("*")));
    assertFalse(live.metBy(rejected, List.of("*")));
    assertFalse(Expectation.parse("LIVE_SCRIPT CSTI").metBy(both, List.of("*")));
    Expectation clean = Expectation.parse("CLEAN");
    assertTrue(clean.metBy(new Verdict.Clean(), List.of("*")));
    assertTrue(clean.metBy(new Verdict.Findings(List.of(unparsable)), List.of("*")));
    assertFalse(clean.metBy(firefoxOnly, List.of("*")));
    assertFalse(clean.metBy(rejected, List.of("*")));
    assertTrue(Expectation.parse("REJECTED").metBy(rejected, List.of("*")));
    assertFalse(Expectation.parse("REJECTED").metBy(new Verdict.Clean(), List.of("*")));
  }
}
