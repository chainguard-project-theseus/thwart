package dev.tessera.compiler.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.oracle.ProfileOracleTest.CaseResult;
import dev.tessera.compiler.oracle.ProfileOracleTest.Compiled;
import dev.tessera.compiler.oracle.ProfileOracleTest.Escape;
import dev.tessera.compiler.oracle.ProfileOracleTest.GateCase;
import dev.tessera.compiler.oracle.ProfileOracleTest.Layer;
import dev.tessera.compiler.oracle.ProfileOracleTest.LemmaMemo;
import dev.tessera.compiler.oracle.ProfileOracleTest.Subject;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.compiler.verify.Skeleton;
import dev.tessera.compiler.verify.SkeletonMatcher;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The oracle gate's own tests (#324): each way it must fail (a skeleton the render isn't in, an
 * escaper whose output isn't inert, generated code that writes a value unescaped), and that
 * ordinary templates pass.
 */
class ProfileOracleGateTest {

  private static final String P = "<div t:params=\"String s\">";

  private static GateCase gateCase(String template) {
    return new GateCase(OracleCase.of("test", template, OracleCase.Origin.SEED), ProfileOracleTest.params(template));
  }

  private static Compilation compile(GateCase g) {
    return Compilation.builder().template(g.view() + ".html", g.oracleCase().template()).compile().assertSuccess();
  }

  private static CaseResult run(String template) {
    GateCase g = gateCase(template);
    return ProfileOracleTest.check(Subject.of(g, compile(g)), ProfileOracleTest.RUNTIME, new LemmaMemo(), -1);
  }

  private static boolean any(List<String> failures, String prefix) {
    return failures.stream().anyMatch(f -> f.startsWith(prefix));
  }

  // ---- positives ----

  @Test
  void anOrdinaryTemplatePassesEveryCheck() {
    LemmaMemo memo = new LemmaMemo();
    GateCase g = gateCase(P + "<p>[[s]]</p><a t:title=\"s\" t:href=\"@{/x(q=${s})}\">x</a><textarea>[[s]]</textarea>"
        + "<script>var a = [[s]];</script></div>");
    CaseResult r = ProfileOracleTest.check(Subject.of(g, compile(g)), ProfileOracleTest.RUNTIME, memo, 0);
    assertEquals(List.of(), r.failures);
    assertEquals(1 + ProfileOracleTest.HOSTILE.size(), r.renders);
    // The lemma ran: 5 holes x 8 parsers, every value fed once per distinct state.
    assertTrue(memo.verdicts.size() >= 5, memo.verdicts.toString());
    assertTrue(memo.feeds.get() >= 5L * ProfileOracleTest.LEMMA_VALUES.size(), String.valueOf(memo.feeds.get()));
    assertEquals(1, r.export.size()); // benign, no hostile values asked for
  }

  @Test
  void theCompilerSortsRejectionsByLayer() {
    GateCase ok = gateCase(P + "<p>[[s]]</p></div>");
    GateCase profile = gateCase(P + "<p><[[s]]></p></div>");
    // An <svg> in a table cell (exclusion (a)), which the profile lets through.
    GateCase verifier = gateCase(P + "<table><tr><td><svg></svg>[[s]]</td></tr></table></div>");
    List<Compiled> out = ProfileOracleTest.compile(List.of(ok, profile, verifier));
    assertTrue(out.get(0).accepted());
    assertFalse(out.get(1).accepted());
    assertEquals(Layer.PROFILE, out.get(1).layer());
    assertFalse(out.get(2).accepted());
    assertEquals(Layer.VERIFIER, out.get(2).layer());
    assertTrue(out.get(2).errors().get(0).matches("c[0-9a-f]{12}\\.html:1:[0-9]+: .*\\[verifier:FOREIGN_IN_TABLE\\]"),
        out.get(2).errors().toString());
  }

  // ---- skeleton fidelity ----

  @Test
  void aRenderOutsideItsSkeletonFails() {
    GateCase g = gateCase(P + "<p>[[s]]</p></div>");
    Compilation real = compile(g);
    // The same view compiled from a different template: its skeleton isn't what the class writes.
    Compilation other = Compilation.builder().template(g.view() + ".html", P + "<b>[[s]]</b></div>").compile().assertSuccess();
    Subject fake = new Subject(g, ProfileOracleTest.renderer(real, g.view()), other.skeleton(g.view()), other::fragmentSkeleton);
    CaseResult r = ProfileOracleTest.check(fake, ProfileOracleTest.RUNTIME, new LemmaMemo(), -1);
    assertTrue(any(r.failures, "fidelity: the render (benign) is not in the skeleton at offset 6"), r.failures.toString());
  }

  @Test
  void anEscaperThatWritesOutsideItsAlphabetFailsFidelity() {
    GateCase g = gateCase(P + "<p>[[s]]</p></div>");
    Compilation c = compile(g);
    ProfileOracleTest.Renderer real = ProfileOracleTest.renderer(c, g.view());
    // Generated code whose text escaper forgot '<'.
    Subject broken = new Subject(g, m -> real.render(m).replace("&lt;", "<"), c.skeleton(g.view()), c::fragmentSkeleton);
    CaseResult r = ProfileOracleTest.check(broken, ProfileOracleTest.RUNTIME, new LemmaMemo(), -1);
    assertTrue(any(r.failures, "fidelity: the render (value "), r.failures.toString());
  }

  // ---- the inertness lemma ----

  /** The runtime escapers, except that attribute escapers leak {@code "}. */
  private static final Escape LEAKS_QUOTE = (e, v) -> {
    String out = ProfileOracleTest.RUNTIME.apply(e, v);
    return out != null && e.inAttribute() ? out.replace("&quot;", "\"") : out;
  };

  @Test
  void aStandInEscaperThatLeaksAQuoteFailsTheLemma() {
    GateCase g = gateCase(P + "<p><span t:title=\"s\">x</span>[[s]]</p></div>");
    CaseResult r = ProfileOracleTest.check(Subject.of(g, compile(g)), LEAKS_QUOTE, new LemmaMemo(), -1);
    List<String> lemma = r.failures.stream().filter(f -> f.startsWith("lemma:")).toList();
    // Every parser sees the attribute end: 2 engines x scripting on/off x document/fragment.
    assertEquals(ProfileOracleTest.LEMMA_PARSERS.size(), lemma.size(), r.failures.toString());
    assertTrue(lemma.get(0).matches("lemma: hole .* \\(attr\\) at offset \\d+, .*: value .* changes the parser state from .*"),
        lemma.get(0));
    assertTrue(lemma.stream().allMatch(f -> f.contains("(attr)")), lemma.toString()); // the text hole is still inert
    // The real escapers pass on the same template.
    assertEquals(List.of(), run(g.oracleCase().template()).failures);
  }

  @Test
  void theLemmaComparesTheStateAcrossTheHole() {
    GateCase g = gateCase(P + "<p>[[s]]</p></div>");
    Compilation c = compile(g);
    Skeleton.TemplateSkeleton t = c.skeleton(g.view());
    String out = c.render(g.view(), Compilation.model("s", "x"));
    assertEquals(List.of(), ProfileOracleTest.lemma(t.body(), c::fragmentSkeleton, out, ProfileOracleTest.RUNTIME, new LemmaMemo()));
    // A text escaper that leaves '<' alone opens a tag in data state.
    Escape leaksLt = (e, v) -> ProfileOracleTest.RUNTIME.apply(e, v).replace("&lt;", "<");
    List<String> failures = ProfileOracleTest.lemma(t.body(), c::fragmentSkeleton, out, leaksLt, new LemmaMemo());
    assertEquals(ProfileOracleTest.LEMMA_PARSERS.size(), failures.size(), failures.toString());
    assertTrue(failures.get(0).contains("(text)"), failures.get(0));
    // An output that isn't in the skeleton has no holes to check: that fails too.
    assertTrue(any(ProfileOracleTest.lemma(t.body(), c::fragmentSkeleton, "<div><b>x</b></div>", ProfileOracleTest.RUNTIME,
        new LemmaMemo()), "fidelity:"));
  }

  @Test
  void holeSpansFollowTheSkeleton() {
    GateCase g = gateCase("<div t:params=\"String s, java.util.List<String> xs\"><p t:each=\"x : ${xs}\">[[x]]</p>"
        + "<a t:if=\"${s != null}\" t:title=\"s\">[[s]]</a></div>");
    Compilation c = compile(g);
    String out = c.render(g.view(), Compilation.model("s", "a&b", "xs", List.of("1", "<2>")));
    List<SkeletonMatcher.Span> spans = SkeletonMatcher.holes(c.skeleton(g.view()).body(), out, c::fragmentSkeleton);
    List<String> texts = spans.stream().map(s -> out.substring(s.start(), s.end())).toList();
    assertEquals(List.of("1", "&lt;2&gt;", "a&amp;b", "a&amp;b"), texts, out);
    assertEquals(List.of(Skeleton.Escaper.TEXT, Skeleton.Escaper.TEXT, Skeleton.Escaper.ATTR, Skeleton.Escaper.TEXT),
        spans.stream().map(s -> s.hole().escaper()).toList());
    assertNull(SkeletonMatcher.holes(c.skeleton(g.view()).body(), out + "x", c::fragmentSkeleton));
  }

  @Test
  void theExportCarriesTheVnuTreesOfEachRender() {
    GateCase g = gateCase(P + "<p>[[s]]</p></div>");
    CaseResult r = ProfileOracleTest.check(Subject.of(g, compile(g)), ProfileOracleTest.RUNTIME, new LemmaMemo(), 1);
    assertEquals(2, r.export.size()); // benign, the first hostile value
    for (ExportRecord e : r.export) {
      assertEquals(VnuTrees.json(e.render()), e.trees());
      assertTrue(e.toJsonLine().endsWith(",\"trees\":" + e.trees() + "}"), e.toJsonLine());
    }
  }

  @Test
  void theStructureValuesComeRightAfterTheSeedValues() {
    // The Node job exports the first EXPORT_DEFAULT values: the #320 values and the ones the tree
    // builder reads, including a '</' break-out.
    List<String> exported = ProfileOracleTest.HOSTILE.subList(0, ProfileOracleTest.EXPORT_DEFAULT);
    assertTrue(exported.containsAll(List.of("hidden", "text/html", "application/xhtml+xml")), exported.toString());
    assertTrue(exported.stream().anyMatch(v -> v.contains("</")), exported.toString());
  }
}
