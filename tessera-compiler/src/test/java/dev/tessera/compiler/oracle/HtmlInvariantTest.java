package dev.tessera.compiler.oracle;

import static dev.tessera.compiler.oracle.Finding.Kind.CROSS_ORIGIN_RESOURCE;
import static dev.tessera.compiler.oracle.Finding.Kind.CSTI;
import static dev.tessera.compiler.oracle.Finding.Kind.JS_URL;
import static dev.tessera.compiler.oracle.Finding.Kind.LIVE_HANDLER;
import static dev.tessera.compiler.oracle.Finding.Kind.LIVE_SCRIPT;
import static dev.tessera.compiler.oracle.Finding.Kind.MARKER_IN_NAME;
import static dev.tessera.compiler.oracle.Finding.Kind.STRUCTURE_CHANGED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;

/** The HTML checks of the oracle (#318) on hand-written renders; no compiler. */
class HtmlInvariantTest {

  private static final String VALUE = "\"><script>tqm0001()</script>";

  private static HtmlInvariant.Result check(String benign, String hostile) {
    return HtmlInvariant.check(benign, hostile, VALUE, false);
  }

  private static Set<Finding.Kind> kinds(HtmlInvariant.Result r) {
    return r.findings().stream().map(Finding::kind).collect(Collectors.toCollection(TreeSet::new));
  }

  private static Set<Finding.Kind> kinds(HtmlInvariant.Result r, String engine) {
    return r.findings().stream().filter(f -> f.engine().equals(engine)).map(Finding::kind)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static void assertKinds(Set<Finding.Kind> want, String benign, String hostile) {
    HtmlInvariant.Result r = check(benign, hostile);
    assertEquals(want, kinds(r), () -> "benign " + benign + "\nhostile " + hostile + "\n" + r.findings());
    for (String engine : List.of(SpecParsers.VNU_14, SpecParsers.VNU_26)) {
      assertEquals(want, kinds(r, engine), () -> engine + ": " + r.findings());
    }
  }

  private static void assertClean(String benign, String hostile) {
    HtmlInvariant.Result r = check(benign, hostile);
    assertEquals(List.of(), r.findings(), () -> "benign " + benign + "\nhostile " + hostile);
    assertEquals("CLEAN", r.verdict().label());
  }

  // ---- STRUCTURE_CHANGED ----

  @Test
  void injectedElementChangesTheStructure() {
    assertKinds(Set.of(STRUCTURE_CHANGED, LIVE_HANDLER),
        "<p>tqm0001</p>", "<p><img src=x onerror=tqm0001()></p>");
  }

  @Test
  void structureFindingNamesTheEngineFlagAndPath() {
    HtmlInvariant.Result r = check("<p>tqm0001</p>", "<p><img src=x onerror=tqm0001()></p>");
    Finding f = r.findings().stream().filter(x -> x.kind() == STRUCTURE_CHANGED).findFirst().orElseThrow();
    assertEquals(SpecParsers.VNU_14, f.engine());
    assertEquals("scripting-on /html/body/p: the hostile tree adds element img", f.where());
    assertEquals(VALUE, f.value());
    assertEquals("FINDINGS", r.verdict().label());
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
      // A static attribute value changes.
      "<p class=a title=tqm0001>x</p>|<p class=b title=x>x</p>",
      // An attribute appears.
      "<p title=tqm0001>x</p>|<p title=\"\" id=x>x</p>",
      // Static text changes.
      "<p>a</p><p>tqm0001</p>|<p>b</p><p>x</p>",
      // An element is dropped: the value ends the comment early.
      "<!-- tqm0001 --><p>x</p>|<!-- --><p>x</p> --><p>x</p>",
      // A value opens a foreign element around static markup.
      "<svg><desc>tqm0001</desc></svg><p>x</p>|<svg><desc></desc></svg><svg><p>x</p></svg>"})
  void otherStructureChanges(String benign, String hostile) {
    assertTrue(kinds(check(benign, hostile)).contains(STRUCTURE_CHANGED), () -> benign + " / " + hostile);
  }

  // ---- MARKER_IN_NAME ----

  @Test
  void markerInElementName() {
    assertKinds(Set.of(STRUCTURE_CHANGED, MARKER_IN_NAME), "<p>tqm0001</p>", "<p><tqm0001 x=1></p>");
  }

  @Test
  void markerInAttributeName() {
    assertKinds(Set.of(STRUCTURE_CHANGED, MARKER_IN_NAME), "<p title=\"tqm0001\">x</p>", "<p title=\"\" tqm0001=\"\">x</p>");
  }

  // ---- LIVE_HANDLER ----

  @Test
  void markerInEventHandler() {
    HtmlInvariant.Result r = check("<p onclick=\"f('tqm0001')\">x</p>", "<p onclick=\"f('');tqm0001()//')\">x</p>");
    assertEquals(Set.of(LIVE_HANDLER), kinds(r));
    assertEquals(Set.of("scripting-on /html/body/p/@onclick", "scripting-off /html/body/p/@onclick"),
        r.findings().stream().map(Finding::where).collect(Collectors.toSet()));
    assertEquals(4, r.findings().size(), r.findings()::toString);
  }

  @Test
  void markerInSvgEventHandler() {
    assertKinds(Set.of(LIVE_HANDLER), "<svg><g onload=\"tqm0001\"></g></svg>", "<svg><g onload=\"tqm0001()\"></g></svg>");
  }

  // ---- JS_URL ----

  @ParameterizedTest
  @ValueSource(strings = {
      "javascript:tqm0001()",
      "JaVaScRiPt:tqm0001()",
      " javascript:tqm0001()",
      "java&#9;script:tqm0001()",
      "java&#10;script:tqm0001()",
      "vbscript:tqm0001()",
      "data:text/html,&lt;script&gt;tqm0001()&lt;/script&gt;",
      // No payload to rewrite, so no marker: the slot's scheme still changed to a script scheme.
      "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg=="})
  void scriptSchemeInHref(String url) {
    assertKinds(Set.of(JS_URL), "<a href=\"tqm0001\">x</a>", "<a href=\"" + url + "\">x</a>");
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
      "<form action=\"tqm0001\"></form>|<form action=\"javascript:tqm0001()\"></form>",
      "<button formaction=\"tqm0001\">x</button>|<button formaction=\"javascript:tqm0001()\">x</button>",
      "<svg><a xlink:href=\"tqm0001\"><text>x</text></a></svg>|<svg><a xlink:href=\"javascript:tqm0001()\"><text>x</text></a></svg>",
      "<svg><a href=\"tqm0001\"><text>x</text></a></svg>|<svg><a href=\"javascript:tqm0001()\"><text>x</text></a></svg>",
      "<svg><a><set attributeName=\"href\" to=\"tqm0001\"/></a></svg>|<svg><a><set attributeName=\"href\" to=\"javascript:tqm0001()\"/></a></svg>",
      "<svg><a><animate attributeName=\"xlink:href\" values=\"tqm0001\"/></a></svg>|<svg><a><animate attributeName=\"xlink:href\" values=\"/x;javascript:tqm0001()\"/></a></svg>"})
  void scriptSchemeInOtherUrlAttributes(String benign, String hostile) {
    assertKinds(Set.of(JS_URL), benign, hostile);
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
      // A resource slot: the value also picks a non-same-origin URL.
      "<iframe src=\"tqm0001\"></iframe>|<iframe src=\"javascript:tqm0001()\"></iframe>",
      "<object data=\"tqm0001\"></object>|<object data=\"javascript:tqm0001()\"></object>"})
  void scriptSchemeInAResourceSlot(String benign, String hostile) {
    assertKinds(Set.of(JS_URL, CROSS_ORIGIN_RESOURCE), benign, hostile);
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
      // Not a URL attribute.
      "<p title=\"tqm0001\">x</p>|<p title=\"javascript:tqm0001()\">x</p>",
      // An animation of another attribute.
      "<svg><a><set attributeName=\"fill\" to=\"tqm0001\"/></a></svg>|<svg><a><set attributeName=\"fill\" to=\"javascript:tqm0001()\"/></a></svg>",
      // A script scheme, but no marker in it.
      "<a href=\"javascript:void(0)\" title=\"tqm0001\">x</a>|<a href=\"javascript:void(0)\" title=\"tqm0001()\">x</a>",
      // The value stays in the query.
      "<a href=\"/x?q=tqm0001\">x</a>|<a href=\"/x?q=javascript:tqm0001()\">x</a>"})
  void notAScriptUrl(String benign, String hostile) {
    assertClean(benign, hostile);
  }

  // ---- CROSS_ORIGIN_RESOURCE ----

  @ParameterizedTest
  @ValueSource(strings = {
      "<script src=\"/\\evil.example/a.js\"></script>",
      "<script src=\" \\\\evil.example/a.js\"></script>",
      "<base href=\"\\/evil.example/\">",
      "<link rel=\"stylesheet\" href=\"/\\evil.example/a.css\">",
      "<link rel=\"Preload\" as=\"script\" href=\"/\\evil.example/a.js\">",
      "<link rel=\"icon modulepreload\" href=\"/\\evil.example/a.js\">",
      "<iframe src=\"/\\evil.example/\"></iframe>",
      "<embed src=\"/\\evil.example/\">",
      "<object data=\"/\\evil.example/\"></object>",
      "<div hx-get=\"/\\evil.example/x\">x</div>",
      "<div data-hx-post=\"/\\evil.example/x\">x</div>",
      "<meta http-equiv=\"refresh\" content=\"0;url=/\\evil.example/\">",
      "<meta http-equiv=\" Refresh \" content=\"0; URL='/\\evil.example/'\">",
      "<meta http-equiv=\"refresh\" content=\"0 url=/\\evil.example/\">",
      "<meta http-equiv=\"refresh\" content=\"0 /\\evil.example/\">",
      // Every request attribute counts, not only the first one found.
      "<div hx-get=\"/ok\" hx-post=\"/\\evil.example/x\">x</div>",
      "<div hx-get=\"/ok\" data-hx-get=\"/\\evil.example/x\">x</div>",
      "<script src=\"/ok.js\" hx-get=\"/\\evil.example/x\"></script>",
      "<script src=\"http:\\\\evil.example/a.js\"></script>",
      "<svg><script href=\"/\\evil.example/a.js\"></script></svg>",
      "<svg><script xlink:href=\"/\\evil.example/a.js\"></script></svg>"})
  void backslashResourceUrlIsAlwaysAFinding(String html) {
    assertKinds(Set.of(CROSS_ORIGIN_RESOURCE), "<p>tqm0001</p>" + html, "<p>x</p>" + html);
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
      "<script src=\"/tqm0001/a.js\"></script>|<script src=\"//evil.example/a.js\"></script>",
      "<script src=\"tqm0001\"></script>|<script src=\"//evil.example/a.js\"></script>",
      "<script src=\"tqm0001\"></script>|<script src=\"https://evil.example/a.js\"></script>",
      "<base href=\"tqm0001\">|<base href=\"//evil.example/\">",
      "<base href=\"/tqm0001/\">|<base href=\"//evil.example/\">",
      "<embed src=\"tqm0001\">|<embed src=\"//evil.example/x\">",
      "<meta http-equiv=\"refresh\" content=\"0;url=/tqm0001\">|<meta http-equiv=\"refresh\" content=\"0;url=//evil.example/\">"})
  void valueMakesASlotCrossOriginWhateverTheFlag(String benign, String hostile) {
    assertEquals(Set.of(CROSS_ORIGIN_RESOURCE), kinds(HtmlInvariant.check(benign, hostile, VALUE, false)));
    assertEquals(Set.of(CROSS_ORIGIN_RESOURCE), kinds(HtmlInvariant.check(benign, hostile, VALUE, true)));
  }

  @Test
  void valueChangedAStaticUrlOnlyForContextRelativeLinks() {
    // The benign URL holds no marker, but the hostile render changed it.
    String benign = "<p>tqm0001</p><script src=\"/x/a.js\"></script>";
    String hostile = "<p>x</p><script src=\"//evil.example/a.js\"></script>";
    assertFalse(kinds(HtmlInvariant.check(benign, hostile, VALUE, false)).contains(CROSS_ORIGIN_RESOURCE));
    assertTrue(kinds(HtmlInvariant.check(benign, hostile, VALUE, true)).contains(CROSS_ORIGIN_RESOURCE));
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
      // The marker is in the host: the value picks it.
      "<script src=\"https://tqm0001.cdn.example/a.js\"></script>|<script src=\"https://evil.example/a.js#.cdn.example/a.js\"></script>",
      "<script src=\"https://tqm0001/a.js\"></script>|<script src=\"https://evil.example/a.js\"></script>",
      // A path slot under an absolute or protocol-relative origin, moved to another host.
      "<script src=\"https://cdn.example/tqm0001\"></script>|<script src=\"https://evil.example/x\"></script>",
      "<script src=\"//cdn.example/tqm0001\"></script>|<script src=\"//evil.example/x\"></script>",
      "<script src=\"https://cdn.example/tqm0001\"></script>|<script src=\"https://cdn.example@evil.example/x\"></script>",
      "<script src=\"https://cdn.example/tqm0001\"></script>|<script src=\"https:\\\\evil.example/x\"></script>",
      "<base href=\"https://cdn.example/tqm0001/\">|<base href=\"https://evil.example/\">"})
  void valueMovesTheOriginOfACrossOriginSlot(String benign, String hostile) {
    assertEquals(Set.of(CROSS_ORIGIN_RESOURCE), kinds(HtmlInvariant.check(benign, hostile, VALUE, false)));
  }

  @Test
  void crossOriginSlotOnTheSameAuthorityIsClean() {
    // Path-only changes under the template's own origin are out of scope (URL semantics).
    assertClean("<script src=\"https://cdn.example/tqm0001\"></script>",
        "<script src=\"https://CDN.example/../evil.js\"></script>");
    assertClean("<script src=\"//cdn.example/tqm0001\"></script>",
        "<script src=\"//cdn.example/%2F%2Fevil.example.js\"></script>");
  }

  @Test
  void absoluteSlotThatStaysOnItsOriginIsClean() {
    assertClean("<script src=\"https://cdn.example/tqm0001/a.js\"></script>",
        "<script src=\"https://cdn.example/%22%3E/a.js\"></script>");
  }

  // ---- srcdoc ----

  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
      "&lt;script&gt;tqm0001()&lt;/script&gt;|LIVE_SCRIPT",
      "&lt;p&gt;&lt;img src=x onerror=tqm0001()&gt;&lt;/p&gt;|LIVE_HANDLER"})
  void srcdocIsADocument(String srcdoc, Finding.Kind kind) {
    HtmlInvariant.Result r = check("<iframe srcdoc=\"tqm0001\"></iframe>", "<iframe srcdoc=\"" + srcdoc + "\"></iframe>");
    assertEquals(Set.of(STRUCTURE_CHANGED, kind), kinds(r), r.findings()::toString);
    for (String engine : List.of(SpecParsers.VNU_14, SpecParsers.VNU_26)) {
      assertEquals(Set.of(STRUCTURE_CHANGED, kind), kinds(r, engine), r.findings()::toString);
    }
    assertTrue(r.findings().stream().allMatch(f -> f.where().contains(" /html/body/iframe/@srcdoc/html/")),
        r.findings()::toString);
  }

  @Test
  void escapedSrcdocIsClean() {
    assertClean("<iframe srcdoc=\"&lt;p&gt;tqm0001&lt;/p&gt;\"></iframe>",
        "<iframe srcdoc=\"&lt;p&gt;&amp;lt;script&amp;gt;tqm0001()&amp;lt;/script&amp;gt;&lt;/p&gt;\"></iframe>");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "<script src=\"/a.js\"></script>",
      "<script src=\"\\a.js\"></script>",
      "<link rel=\"icon\" href=\"/\\evil.example/a.ico\">",
      "<a href=\"/\\evil.example/\">x</a>",
      "<img src=\"/\\evil.example/a.png\">",
      "<meta http-equiv=\"refresh\" content=\"5\">",
      "<meta name=\"x\" content=\"0;url=/\\evil.example/\">",
      // Static absolute URLs are the template's own.
      "<script src=\"https://cdn.example/x.js\"></script>",
      "<link rel=\"stylesheet\" href=\"//cdn.example/a.css\">"})
  void notACrossOriginResource(String html) {
    assertEquals(Set.of(), kinds(HtmlInvariant.check("<p>tqm0001</p>" + html, "<p>x</p>" + html, VALUE, true)));
  }

  // ---- LIVE_SCRIPT ----

  @Test
  void markerInAScriptTheBenignTreeDoesNotHave() {
    assertKinds(Set.of(STRUCTURE_CHANGED, LIVE_SCRIPT), "<p>tqm0001</p>", "<p></p><script>tqm0001()</script>");
    assertKinds(Set.of(STRUCTURE_CHANGED, LIVE_SCRIPT), "<svg><desc>tqm0001</desc></svg>", "<svg><desc></desc><script>tqm0001()</script></svg>");
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
      // The injected script takes the path of a benign script without a marker...
      "<script>var a=\"tqm0001\"</script><script>init()</script>|<script>var a=\"</script><script>tqm0001()</script><script>init()</script>",
      "<div><script>a()</script><p>tqm0001</p><script>b()</script></div>|<div><script>a()</script><p></p><script>tqm0001()</script><p></p><script>b()</script></div>",
      // ...or of a benign slot whose static text it doesn't keep.
      "<script>var a=\"tqm0001\"</script><script>var b=\"tqm0002\"</script>|<script>var a=\"</script><script>tqm0001()</script><script>var b=\"x\"</script>"})
  void injectedScriptAtABenignScriptPath(String benign, String hostile) {
    assertKinds(Set.of(STRUCTURE_CHANGED, LIVE_SCRIPT), benign, hostile);
  }

  @Test
  void scriptSlotWithoutAHostileScript() {
    HtmlInvariant.Result r = check("<div><script>var a = \"tqm0001\";</script></div>", "<div></div>");
    assertEquals(Set.of(STRUCTURE_CHANGED), kinds(r));
    assertEquals(4, r.scriptSlots().size(), r.scriptSlots()::toString);
    assertTrue(r.scriptSlots().stream().allMatch(s -> s.hostile() == null), r.scriptSlots()::toString);
  }

  @Test
  void injectedInertScriptIsOnlyAStructureChange() {
    assertKinds(Set.of(STRUCTURE_CHANGED), "<p>tqm0001</p>", "<p></p><script type=\"text/plain\">tqm0001()</script>");
  }

  @Test
  void benignScriptSlotsGoToTheScriptCheck() {
    String hostile = "<script>var a = \"\\\"+tqm0001()+\\\"\";</script>";
    HtmlInvariant.Result r = check("<script>var a = \"tqm0001\";</script>", hostile);
    assertEquals(List.of(), r.findings());
    assertEquals(4, r.scriptSlots().size(), r.scriptSlots()::toString);
    HtmlInvariant.ScriptSlot s = r.scriptSlots().get(0);
    assertEquals(SpecParsers.VNU_14, s.engine());
    assertEquals("scripting-on /html/head/script", s.where());
    assertEquals(List.of("tqm0001"), s.markers());
    assertEquals("var a = \"tqm0001\";", s.benign());
    assertEquals("var a = \"\\\"+tqm0001()+\\\"\";", s.hostile());
  }

  @Test
  void inertScriptIsNotAScriptSlot() {
    HtmlInvariant.Result r = check("<script type=\"application/json\">\"tqm0001\"</script>", "<script type=\"application/json\">\"x\"</script>");
    assertEquals(List.of(), r.findings());
    assertEquals(List.of(), r.scriptSlots());
  }

  // ---- CSTI ----

  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
      "<svg><title>tqm0001</title></svg>|<svg><title>{{tqm0001()}}</title></svg>",
      "<p>tqm0001</p>|<p>{{tqm0001()}}</p>",
      "<p>a tqm0001 b</p>|<p>a {{ x }} {{ tqm0001() }} b</p>",
      "<math><mi>tqm0001</mi></math>|<math><mi>{{tqm0001()}}</mi></math>"})
  void interpolatedMarker(String benign, String hostile) {
    assertKinds(Set.of(CSTI), benign, hostile);
  }

  @Test
  void cstiFindingNamesTheForeignPath() {
    HtmlInvariant.Result r = check("<svg><title>tqm0001</title></svg>", "<svg><title>{{tqm0001()}}</title></svg>");
    assertTrue(r.findings().stream().allMatch(f -> f.where().endsWith(" /html/body/svg[svg]/title[svg]")), r.findings()::toString);
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
      // RCDATA, raw text and attribute values are documented limitations.
      "<textarea>tqm0001</textarea>|<textarea>{{tqm0001()}}</textarea>",
      "<title>tqm0001</title>|<title>{{tqm0001()}}</title>",
      "<p title=\"tqm0001\">x</p>|<p title=\"{{tqm0001()}}\">x</p>",
      "<style>p{}/*tqm0001*/</style>|<style>p{}/*{{tqm0001()}}*/</style>",
      // Tessera's break (#248): an empty comment between the braces.
      "<p>tqm0001</p>|<p>{<!---->{tqm0001()}}</p>",
      // The marker is after the first }}.
      "<p>tqm0001</p>|<p>{{ x }} tqm0001() }}</p>"})
  void notInterpolated(String benign, String hostile) {
    assertClean(benign, hostile);
  }

  // ---- engine and scripting splits ----

  @Test
  void classicSelectRunsTheScriptCurrentSpecDoesNot() {
    HtmlInvariant.Result r = check("<select><title>tqm0001</title></select>",
        "<select><title><script>tqm0001()</script></title></select>");
    assertEquals(Set.of(STRUCTURE_CHANGED, LIVE_SCRIPT), kinds(r, SpecParsers.VNU_14), r.findings()::toString);
    assertEquals(Set.of(), kinds(r, SpecParsers.VNU_26), r.findings()::toString);
  }

  @Test
  void noscriptEndsEarlyOnlyWithScriptingOn() {
    HtmlInvariant.Result r = check("<div><noscript><p title=\"tqm0001\"></noscript></div>",
        "<div><noscript><p title=\"</noscript><script>tqm0001()</script>\"></noscript></div>");
    Set<Finding.Kind> on = r.findings().stream().filter(f -> f.where().startsWith("scripting-on "))
        .map(Finding::kind).collect(Collectors.toCollection(TreeSet::new));
    assertEquals(Set.of(STRUCTURE_CHANGED, LIVE_SCRIPT), on, r.findings()::toString);
    assertTrue(r.findings().stream().noneMatch(f -> f.where().startsWith("scripting-off ")), r.findings()::toString);
  }

  // ---- clean ----

  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
      "<p>tqm0001</p>|<p>&quot;&gt;&lt;script&gt;tqm0001()&lt;/script&gt;</p>",
      "<p title=\"tqm0001\">x</p>|<p title=\"&quot; onerror=tqm0001() &quot;\">x</p>",
      "<p title='tqm0001'>x</p>|<p title='&#39; onmouseover=&#39;tqm0001()'>x</p>",
      "<a href=\"/x?q=tqm0001\">x</a>|<a href=\"/x?q=%22%3E%3Cscript%3Etqm0001()\">x</a>",
      // An empty value leaves no text node.
      "<p>tqm0001</p>|<p></p>",
      "<p>a<b>tqm0001</b>c</p>|<p>a<b></b>c</p>",
      "<!-- tqm0001 --><p>x</p>|<!-- - - &gt; --><p>x</p>",
      "<textarea>tqm0001</textarea>|<textarea>&lt;/textarea&gt;&lt;script&gt;tqm0001()&lt;/script&gt;</textarea>",
      // An empty value in a comment slot (Tessera rejects holes in comments; the checker still copes).
      "<!--tqm0001--><p>x</p>|<!----><p>x</p>",
      "<p>a<!--tqm0001-->b</p>|<p>a<!---->b</p>"})
  void escapedValuesAreClean(String benign, String hostile) {
    assertClean(benign, hostile);
  }

  // ---- parsers ----

  @Test
  void bothParsersLoad() {
    assertEquals(List.of(SpecParsers.VNU_14, SpecParsers.VNU_26), SpecParsers.all().stream().map(SpecParser::name).toList());
    assertEquals(SpecParsers.VNU_26, SpecParsers.named("vnu-26").name());
  }

  @Test
  void namesThatAreNotXmlNamesParse() {
    for (SpecParser p : SpecParsers.all()) {
      Document doc = p.parse("<html xmlns=\"http://www.w3.org/1999/xhtml\"><p a\"b=1 tqm0001<x=2>x</p>", true);
      String names = doc.getElementsByTagName("p").item(0).getAttributes().item(0).getNodeName()
          + " " + doc.getElementsByTagName("p").item(0).getAttributes().item(1).getNodeName();
      assertTrue(names.contains("tqm0001"), p.name() + ": " + names);
    }
  }

  @Test
  void commentsAreKept() {
    for (SpecParser p : SpecParsers.all()) {
      Document doc = p.parse("<p><!-- x --></p>", false);
      assertEquals(" x ", doc.getElementsByTagName("p").item(0).getFirstChild().getNodeValue(), p.name());
    }
  }

  // ---- URL shape ----

  @Test
  void urlPreprocessing() {
    assertEquals("javascript:x", UrlShape.preprocess(" \u0001java\tscr\nipt:x\r "));
    assertEquals("javascript", UrlShape.scheme(" JavaScript:x"));
    assertNull(UrlShape.scheme("/a:b"));
    assertTrue(UrlShape.scriptScheme("DATA:text/html,x"));
    assertFalse(UrlShape.scriptScheme("https://x"));
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
      "/\\evil|true|true",
      "\\\\evil|true|true",
      "\\/evil|true|true",
      "//evil|false|true",
      "https://evil|false|true",
      "/a|false|false",
      "\\a|false|false",
      "a/b|false|false",
      "http:\\\\evil|true|true",
      "HTTPS:/\\evil|true|true",
      "javascript:\\\\x|false|true",
      "http://evil|false|true"})
  void urlShapes(String url, boolean backslash, boolean crossOrigin) {
    assertEquals(backslash, UrlShape.backslashNetworkPath(url), url);
    assertEquals(crossOrigin, UrlShape.crossOrigin(url), url);
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
      "0;url=/x|/x",
      "0; URL = '/x' |/x",
      "3,url=\"/x\"|/x",
      "0;/x|/x",
      "0 url=/x|/x",
      "0 /x|/x",
      " 1.5 , url=/x|/x"})
  void refreshUrls(String content, String url) {
    assertEquals(url, HtmlInvariant.refreshUrl(content));
  }

  @Test
  void refreshWithoutUrl() {
    assertNull(HtmlInvariant.refreshUrl("5"));
    assertNull(HtmlInvariant.refreshUrl(";url=/x"));
    assertNull(HtmlInvariant.refreshUrl("0;"));
    assertNull(HtmlInvariant.refreshUrl("0x;url=/x"));
  }
}
