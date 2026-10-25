package dev.tessera.compiler.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.oracle.ProfileOracleTest.CaseResult;
import dev.tessera.compiler.oracle.ProfileOracleTest.Classified;
import dev.tessera.compiler.oracle.ProfileOracleTest.Compiled;
import dev.tessera.compiler.oracle.ProfileOracleTest.Escape;
import dev.tessera.compiler.oracle.ProfileOracleTest.Family;
import dev.tessera.compiler.oracle.ProfileOracleTest.GateCase;
import dev.tessera.compiler.oracle.ProfileOracleTest.Layer;
import dev.tessera.compiler.oracle.ProfileOracleTest.LemmaMemo;
import dev.tessera.compiler.oracle.ProfileOracleTest.Ratchet;
import dev.tessera.compiler.oracle.ProfileOracleTest.Subject;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.compiler.verify.Skeleton;
import dev.tessera.compiler.verify.SkeletonMatcher;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The oracle gate's own tests (#324): each way it must fail (a skeleton the render isn't in, an
 * escaper whose output isn't inert, an A/B finding on an accepted template, a stale or untracked
 * known-holes line), and that ordinary templates and listed C/D/E holes pass.
 */
class ProfileOracleGateTest {

  private static final String P = "<div t:params=\"String s\">";

  private static GateCase gateCase(String template) {
    return new GateCase(OracleCase.of("test", template, OracleCase.Origin.SEED), ProfileOracleTest.params(template), null);
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
    assertEquals(List.of(), r.findings);
    assertEquals(2 + ProfileOracleTest.HOSTILE.size(), r.renders);
    // The lemma ran: 5 holes x 8 parsers, every value fed once per distinct state.
    assertTrue(memo.verdicts.size() >= 5, memo.verdicts.toString());
    assertTrue(memo.feeds.get() >= 5L * ProfileOracleTest.LEMMA_VALUES.size(), String.valueOf(memo.feeds.get()));
    assertEquals(new Verdict.Clean(), r.verdict());
    assertEquals(2, r.export.size()); // benign and fingerprint, no hostile values asked for
  }

  @Test
  void aListedFamilyDHolePasses() {
    // 306-A18: {{ split across statics and a hole, a client-framework hole (#336) the verifier
    // doesn't cover.
    String template = P + "{{ <t:block t:text=\"s\"></t:block> }}</div>";
    CaseResult r = run(template);
    assertEquals(List.of(), r.failures);
    assertFalse(r.findings.isEmpty());
    assertTrue(r.findings.stream().allMatch(f -> f.finding().kind() == Finding.Kind.CSTI && f.family() == Family.D));
    OracleCase c = r.gateCase.oracleCase();
    Ratchet ratchet = new Ratchet(ProfileOracleTest.holes("t.txt", c.id() + " CSTI * #336 306-A18\n"), "t.txt");
    assertEquals(List.of(), ratchet.check(c, r.findings));
    assertEquals(List.of(), ratchet.stale(null));
    // Unlisted, it is a new hole.
    Ratchet empty = new Ratchet(ProfileOracleTest.holes("t.txt", ""), "t.txt");
    assertTrue(any(empty.check(c, r.findings), "new hole: " + c.id()), empty.check(c, r.findings).toString());
  }

  @Test
  void theCompilerSortsRejectionsByLayer() {
    GateCase ok = gateCase(P + "<p>[[s]]</p></div>");
    GateCase profile = gateCase(P + "<p><[[s]]></p></div>");
    // 306-A55: JSON in an SVG <script>'s CDATA section, which the profile lets through.
    GateCase verifier = gateCase(P + "<svg><script><![CDATA[ var a = [[s]]; ]]></script></svg></div>");
    List<Compiled> out = ProfileOracleTest.compile(List.of(ok, profile, verifier));
    assertTrue(out.get(0).accepted());
    assertFalse(out.get(1).accepted());
    assertEquals(Layer.PROFILE, out.get(1).layer());
    assertFalse(out.get(2).accepted());
    assertEquals(Layer.VERIFIER, out.get(2).layer());
    assertTrue(out.get(2).errors().get(0).matches("c[0-9a-f]{12}\\.html:1:[0-9]+: .*\\[verifier:JSON_NOT_IN_SCRIPT\\]"),
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

  // ---- A/B findings ----

  @Test
  void anABFindingOnAnAcceptedTemplateFailsTheGate() {
    GateCase g = gateCase(P + "<p>[[s]]</p></div>");
    Compilation c = compile(g);
    ProfileOracleTest.Renderer real = ProfileOracleTest.renderer(c, g.view());
    // Generated code that writes the value unescaped: the value oracle sees new elements.
    Subject broken = new Subject(g, m -> real.render(m).replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\""),
        c.skeleton(g.view()), c::fragmentSkeleton);
    CaseResult r = ProfileOracleTest.check(broken, ProfileOracleTest.RUNTIME, new LemmaMemo(), -1);
    assertTrue(r.findings.stream().anyMatch(f -> f.family() == Family.AB
        && f.finding().kind() == Finding.Kind.STRUCTURE_CHANGED), r.findings.toString());
    Ratchet ratchet = new Ratchet(ProfileOracleTest.holes("t.txt", ""), "t.txt");
    List<String> failures = ratchet.check(g.oracleCase(), r.findings);
    assertTrue(any(failures, "verifier soundness bug (family A/B, can't be listed in t.txt): " + g.oracleCase().id()),
        failures.toString());
  }

  @Test
  void anABKindCantBeListed() {
    for (String kind : List.of("STRUCTURE_CHANGED", "MARKER_IN_NAME", "LIVE_HANDLER", "CONTEXT_MISMATCH")) {
      IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
          () -> ProfileOracleTest.holes("t.txt", "# c\n0123456789ab " + kind + " vnu-26 #367 x\n"));
      assertTrue(e.getMessage().startsWith("t.txt:2: " + kind + " is a family A/B kind"), e.getMessage());
    }
    // A LIVE_SCRIPT line parses, but doesn't cover a LIVE_SCRIPT reached through markup: the gate
    // still fails, and the line is stale.
    OracleCase c = OracleCase.of("t", P + "x</div>", OracleCase.Origin.SEED);
    Ratchet ratchet = new Ratchet(ProfileOracleTest.holes("t.txt", c.id() + " LIVE_SCRIPT * #332 x\n"), "t.txt");
    Finding live = new Finding(Finding.Kind.LIVE_SCRIPT, "vnu-26", "scripting-on /html/body/div/script", "v");
    assertEquals(Family.AB, ProfileOracleTest.family(live, List.of()));
    assertTrue(any(ratchet.check(c, List.of(new Classified(live, Family.AB))), "verifier soundness bug"));
    assertTrue(any(ratchet.stale(null), "known hole " + c.id() + " LIVE_SCRIPT * #332 no longer occurs"));
  }

  @Test
  void findingsAreSortedIntoFamilies() {
    HtmlInvariant.ScriptSlot html = new HtmlInvariant.ScriptSlot("vnu-26", "scripting-on /html/body/div/script",
        List.of("tqm0001"), "var a = \"tqm0001\";", "var a = tqm0001();");
    HtmlInvariant.ScriptSlot svg = new HtmlInvariant.ScriptSlot("vnu-26", "scripting-on /html/body/div/svg[svg]/script[svg]",
        List.of("tqm0001"), "var a = \"tqm0001\";", "var a = tqm0001();");
    Finding inHtml = new Finding(Finding.Kind.LIVE_SCRIPT, "vnu-26", html.where(), "v");
    Finding inSvg = new Finding(Finding.Kind.LIVE_SCRIPT, "vnu-26", svg.where(), "v");
    assertEquals(Family.C, ProfileOracleTest.family(inHtml, List.of(html, svg)));
    assertEquals(Family.AB, ProfileOracleTest.family(inSvg, List.of(html, svg)));
    assertEquals(Family.AB, ProfileOracleTest.family(
        new Finding(Finding.Kind.LIVE_SCRIPT, "vnu-1.4", html.where(), "v"), List.of(html))); // other engine
    Finding rhino = new Finding(Finding.Kind.LIVE_SCRIPT, "rhino", "x", "v");
    assertEquals(Family.C, ProfileOracleTest.scriptFamily(rhino, html));
    assertEquals(Family.AB, ProfileOracleTest.scriptFamily(rhino, svg));
    assertEquals(Family.D, ProfileOracleTest.family(new Finding(Finding.Kind.CSTI, "vnu-26", "/", "v"), List.of()));
    assertEquals(Family.E, ProfileOracleTest.family(new Finding(Finding.Kind.JS_URL, "vnu-26", "/", "v"), List.of()));
    assertEquals(Family.E,
        ProfileOracleTest.family(new Finding(Finding.Kind.CROSS_ORIGIN_RESOURCE, "vnu-26", "/", "v"), List.of()));
    for (Finding.Kind k : List.of(Finding.Kind.STRUCTURE_CHANGED, Finding.Kind.MARKER_IN_NAME, Finding.Kind.LIVE_HANDLER,
        Finding.Kind.CONTEXT_MISMATCH)) {
      assertEquals(Family.AB, ProfileOracleTest.family(new Finding(k, "vnu-26", "/", "v"), List.of()), k.name());
    }
  }

  @Test
  void contextMismatchesTheVerifierProvesInertAreNotFindings() {
    // 306-A60: text escaping in <xmp>, which never runs: over-escaped, inert (seed family OVER).
    CaseResult xmp = run(P + "<xmp>[[s]]</xmp></div>");
    assertEquals(List.of(), xmp.failures);
    assertEquals(List.of(), xmp.findings);
    assertFalse(xmp.inert.isEmpty());
    // An attribute value the classic engine drops with its <span> inside <select>.
    CaseResult dropped = run(P + "<table><tbody><tr><td t:fragment=\"frag(String s)\"><span t:title=\"s\">x</span></td>"
        + "</tr></tbody></table><select><t:block t:replace=\"~{:: frag(s)}\"></t:block></select></div>");
    assertEquals(List.of(), dropped.failures);
    assertTrue(dropped.inert.stream().anyMatch(f -> f.finding().where().startsWith("(nowhere)")
        && f.finding().engine().equals(SpecParsers.VNU_14)), dropped.inert.toString());
    // A text marker that is nowhere is not inert.
    Finding nowhere = new Finding(Finding.Kind.CONTEXT_MISMATCH, "vnu-26",
        "(nowhere): marker tqf0001 is in the render but not in the parsed tree", "v");
    GateCase g = gateCase(P + "<p>[[s]]</p></div>");
    Compilation c = compile(g);
    String render = c.render(g.view(), Compilation.model("s", Fingerprint.value(1)));
    List<SkeletonMatcher.Span> spans = SkeletonMatcher.holes(c.skeleton(g.view()).body(), render, c::fragmentSkeleton);
    assertEquals(Family.AB, ProfileOracleTest.contextFamily(nowhere, render, spans));
    Finding script = new Finding(Finding.Kind.CONTEXT_MISMATCH, "vnu-26",
        "/html/body/div/p text: [SCRIPT] json escaping in HTML element text (<p>)", "v");
    assertEquals(Family.AB, ProfileOracleTest.contextFamily(script, render, spans));
  }

  // ---- known-holes.txt ----

  @Test
  void aStaleLineFails() {
    OracleCase c = OracleCase.of("t", P + "x</div>", OracleCase.Origin.SEED);
    Ratchet ratchet = new Ratchet(ProfileOracleTest.holes("t.txt",
        c.id() + " CSTI * #336 gone\n" + c.id() + " JS_URL chromium #313 the Node job's\n"), "t.txt");
    assertEquals(List.of(), ratchet.check(c, List.of()));
    List<String> stale = ratchet.stale(null);
    assertEquals(1, stale.size(), stale.toString()); // the chromium line is the Node job's to report
    assertTrue(stale.get(0).startsWith("known hole " + c.id() + " CSTI * #336 no longer occurs"), stale.get(0));
    assertTrue(stale.get(0).endsWith("delete its line from t.txt"), stale.get(0));
    // A line for a case a partial run (-Dtessera.oracle.gen.max) didn't run isn't reported.
    assertEquals(List.of(), ratchet.stale(java.util.Set.of()));
  }

  @Test
  void aLineWithoutAnIssueFails() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ProfileOracleTest.holes("t.txt", "0123456789ab CSTI * no issue\n"));
    assertTrue(e.getMessage().startsWith("t.txt:1: expected '<case-id> <KIND> <engine or *> #<issue> <note>'"), e.getMessage());
  }

  @Test
  void theCheckedInListHasOnlyFamilyCDAndELines() {
    KnownHoles holes = ProfileOracleTest.holes("known-holes.txt", ProfileOracleTest.resource(KnownHoles.RESOURCE));
    assertTrue(holes.entries().stream().allMatch(e -> ProfileOracleTest.LISTABLE.contains(e.kind())));
  }
}
