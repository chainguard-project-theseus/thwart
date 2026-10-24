package dev.tessera.compiler.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.oracle.Finding.Kind;
import dev.tessera.runtime.Escapers;
import java.io.StringReader;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import nu.validator.htmlparser.common.XmlViolationPolicy;
import nu.validator.htmlparser.dom.HtmlDocumentBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/** Unit tests of the script-slot check (#319) on hand-written script text. */
class ScriptInvariantTest {

  private static final String M = "tqm0001";
  private static final String M2 = "tqm0002";

  /** A few of {@code HostileStrings.VECTORS}, payload call rewritten to the marker. */
  private static final List<String> HOSTILE =
      List.of(
          "\"><script>tqm0001()</script>",
          "</script><script>tqm0001()</script>",
          "\" onmouseover=\"tqm0001()",
          "*/tqm0001()/*",
          "/;tqm0001();//",
          "`+tqm0001()+`",
          "${tqm0001()}",
          "\\\";tqm0001();//",
          " tqm0001() ",
          "𐀀\ud800",
          "");

  private static List<Finding> check(String benign, String hostile) {
    return ScriptInvariant.check(benign, hostile, Set.of(M), "/html/body/script", "v");
  }

  private static Set<Kind> kinds(List<Finding> findings) {
    return findings.stream().map(Finding::kind).collect(Collectors.toSet());
  }

  /** The script escaper's rendering of a value: a JSON string. */
  private static String json(String value) {
    StringBuilder sb = new StringBuilder();
    Escapers.appendJson(sb, value, 0);
    return sb.toString();
  }

  private static void assertFinding(List<Finding> findings, Kind... anyOf) {
    assertTrue(
        findings.stream().anyMatch(f -> Set.of(anyOf).contains(f.kind())),
        () -> "expected one of " + List.of(anyOf) + ", got " + findings);
    findings.forEach(f -> assertEquals("rhino", f.engine()));
  }

  // ---- clean ----

  @Test
  void stringSlotIsClean() {
    for (String v : HOSTILE) {
      assertEquals(List.of(), check("var a = \"tqm0001\";", "var a = " + json(v) + ";"), v);
    }
  }

  @Test
  void slotInObjectLiteralIsClean() {
    for (String v : HOSTILE) {
      assertEquals(List.of(), check("var a = {\"k\":\"tqm0001\"};", "var a = {\"k\":" + json(v) + "};"), v);
    }
  }

  @Test
  void slotAsCallArgumentIsClean() {
    for (String v : HOSTILE) {
      assertEquals(List.of(), check("f(\"tqm0001\", 1)", "f(" + json(v) + ", 1)"), v);
    }
  }

  @Test
  void slotInTemplateSubstitutionIsClean() {
    // `${[[s]]}`: the value is a whole literal in expression position inside the substitution.
    assertEquals(List.of(), check("var a = `x${\"tqm0001\"}y`;", "var a = `x${" + json("`+tqm0001()+`") + "}y`;"));
  }

  @Test
  void codeAfterTheSlotMayUseRegexAndComments() {
    String tail = "; var r = /\"/.test(y) /* \" */ ? a / b : `t`; // done";
    assertEquals(List.of(), check("var a = \"tqm0001\"" + tail, "var a = " + json("\";tqm0001();//") + tail));
  }

  @Test
  void commentsAroundTheSlotAreClean() {
    // Includes Annex B HTML-like comments and ASI before a trailing comment at the end of input.
    String benign = "<!-- x\n/* a */ f(\"tqm0001\") // b\n--> c\nf() /* d */";
    assertEquals(List.of(), check(benign, benign.replace("\"tqm0001\"", json("*/tqm0001()/*"))));
  }

  @Test
  void jsonValueIsClean() {
    // A non-String slot (bean or list) renders as JSON: a whole array/object literal.
    assertEquals(
        List.of(),
        check("var a = \"tqm0001\";", "var a = {\"x\":[1,-2.5,true,false,null,\"tqm0001()\"],\"y\":{}};"));
  }

  @Test
  void twoSlots() {
    String benign = "var a = \"tqm0001\", b = [\"tqm0002\"];";
    String hostile = "var a = " + json("</script>tqm0001()") + ", b = [" + json("*/tqm0002()") + "];";
    assertEquals(List.of(), ScriptInvariant.check(benign, hostile, List.of(M, M2), "", "v"));
  }

  // ---- negatives: the value changed the script ----

  @Test
  void breakoutOfAQuotedHole() {
    // #281: "[[s]]" with the value written unescaped into the string.
    List<Finding> f = check("var a = \"tqm0001\";", "var a = \"\"+tqm0001()+\"\";");
    assertFinding(f, Kind.LIVE_SCRIPT);
    assertFinding(f, Kind.JS_NOT_LITERAL);
  }

  @Test
  void hostileThatDoesNotParse() {
    assertEquals(Set.of(Kind.JS_NOT_LITERAL), kinds(check("var a = \"tqm0001\";", "var a = \"\"tqm0001()\"\";")));
  }

  @Test
  void valueThatBecomesTwoTokens() {
    assertEquals(
        Set.of(Kind.JS_NOT_LITERAL), kinds(check("var a = \"tqm0001\";", "var a = \"tqm0001\" + \"x\";")));
  }

  @Test
  void valueThatIsNotJson() {
    assertFinding(check("var a = \"tqm0001\";", "var a = [tqm0001];"), Kind.LIVE_SCRIPT);
    assertFinding(check("var a = \"tqm0001\";", "var a = {get x() { return 1; }};"), Kind.JS_NOT_LITERAL);
    assertFinding(check("var a = \"tqm0001\";", "var a = /tqm0001/;"), Kind.JS_NOT_LITERAL);
  }

  @Test
  void valueThatAddsStatementsAfterTheSlot() {
    // The slot itself is a literal, but the value went on to write code after it.
    List<Finding> f = check("var a = \"tqm0001\";", "var a = \"\";tqm0001();//\";");
    assertFinding(f, Kind.LIVE_SCRIPT);
  }

  @Test
  void valueThatChangesACommentOrWhitespaceAfterTheSlot() {
    assertFinding(check("var a = \"tqm0001\"; // x", "var a = \"x\"; // y"), Kind.JS_NOT_LITERAL);
  }

  @Test
  void valueInTemplateLiteral() {
    // `[[s]]`: the marker sits in template characters, never a literal of its own.
    List<Finding> f = check("var a = `\"tqm0001\"`;", "var a = `${tqm0001()}`;");
    assertFinding(f, Kind.JS_NOT_LITERAL);
    assertFinding(f, Kind.LIVE_SCRIPT);
  }

  @Test
  void valueInBlockCommentTurnedIntoCode() {
    // /*[[s]]*/ escaped as expression: the marker is in a comment; the hostile value ends it.
    List<Finding> f = check("var a = 1; /*\"tqm0001\"*/", "var a = 1; /*\"*/tqm0001()/*\"*/");
    assertFinding(f, Kind.JS_NOT_LITERAL);
    assertFinding(f, Kind.LIVE_SCRIPT);
  }

  @Test
  void valueInLineComment() {
    assertFinding(check("var a = 1; // \"tqm0001\"", "var a = 1; // \"\ntqm0001()\""), Kind.JS_NOT_LITERAL);
  }

  @Test
  void markerAsIdentifierInBenign() {
    // The compiler thought the hole was inside a string and wrote string content without quotes.
    assertFinding(check("var z = tqm0001;", "var z = tqm0001();"), Kind.LIVE_SCRIPT);
  }

  @Test
  void markerInsideALongerString() {
    // A hole inside a string literal is never "one literal", even when escaped right (§5.4).
    assertFinding(check("var a = \"x tqm0001\";", "var a = \"x \\\"tqm0001()\";"), Kind.JS_NOT_LITERAL);
  }

  @Test
  void regexDivisionConfusion() {
    // A33, #290 review: the compiler read `x\n/"/` as a regex; JS reads division then a string, so
    // the "expression position" hole lands after a string and before an unterminated one.
    List<Finding> f =
        check("var r = x\n/\"/.test(y); var z = \"tqm0001\";", "var r = x\n/\"/.test(y); var z = \"\\\";tqm0001();//\";");
    assertFinding(f, Kind.JS_NOT_LITERAL);
  }

  @Test
  void regexDivisionConfusionWithStringContentEscaping() {
    // The other way round: the compiler thought the hole was in a string and wrote raw content.
    List<Finding> f = check("var r = x\n/\"/.test(y); var z = \"tqm0001\";", "var r = x\n/\"/.test(y); var z = \"\";tqm0001();//\";");
    assertFinding(f, Kind.JS_NOT_LITERAL, Kind.LIVE_SCRIPT);
  }

  @Test
  void classExtendsRegexIsUnparsableInRhino() {
    // A52 (#290 review): Rhino 1.8.1 has no classes, so the script is unchecked (counted by the gate,
    // covered by the Node job's acorn check), never a pass.
    List<Finding> f =
        check("class A extends /\"/.constructor {}; var y = \"tqm0001\";",
            "class A extends /\"/.constructor {}; var y = \"\"+tqm0001()+\"\";");
    assertEquals(Set.of(Kind.JS_UNPARSABLE), kinds(f));
  }

  @Test
  void exportDefaultRegexIsUnparsableInRhino() {
    // A51 (#290 review): Rhino 1.8.1 doesn't parse modules: JS_UNPARSABLE, unchecked, not a pass.
    List<Finding> f =
        check("export default /\"/; var y = \"tqm0001\";", "export default /\"/; var y = \"\"+tqm0001()+\"\";");
    assertEquals(Set.of(Kind.JS_UNPARSABLE), kinds(f));
  }

  @Test
  void benignBrokenAtTheMarkerIsAFinding() {
    // The marker itself broke the benign script (quoted hole written as a JSON string): not merely
    // "Rhino can't parse this".
    assertEquals(Set.of(Kind.JS_NOT_LITERAL), kinds(check("var a = \"\"tqm0001\"\";", "var a = \"\"x\"\";")));
  }

  @Test
  void svgScriptQuotDecoding() {
    // A4: <svg><script>var a = &quot;[[s]]&quot;;</script></svg>. The compiler treated &quot; as
    // inert text and wrote a JSON string; the HTML parser decodes &quot; in SVG script text, so the
    // script the browser runs is var a = ""tqm0001"";
    String render = "<!DOCTYPE html><svg><script>var a = &quot;\"tqm0001\"&quot;;</script></svg>";
    String hostileRender =
        "<!DOCTYPE html><svg><script>var a = &quot;\"&quot;+tqm0001()+&quot;\"&quot;;</script></svg>";
    String benign = svgScriptText(render);
    assertEquals("var a = \"\"tqm0001\"\";", benign);
    assertFinding(check(benign, svgScriptText(hostileRender)), Kind.JS_NOT_LITERAL);
  }

  @ParameterizedTest
  @ValueSource(strings = {"import x from \"y\"; var a = \"tqm0001\";", "for await (const x of y) {} var a = \"tqm0001\";"})
  void unparsableBenignIsUncheckedNotAFailure(String benign) {
    List<Finding> f = check(benign, benign.replace("\"tqm0001\"", json("\"+tqm0001()+\"")));
    assertEquals(Set.of(Kind.JS_UNPARSABLE), kinds(f));
    assertTrue(f.get(0).where().startsWith("/html/body/script: benign script doesn't parse"), f::toString);
  }

  @Test
  void unparsableBenignStillReportsALiveMarkerInTheHostile() {
    // Fail closed: even when the benign script is unchecked, a hostile identifier is reported.
    List<Finding> f = check("var a = 1 +; var b = \"tqm0001\";", "tqm0001();");
    assertEquals(Set.of(Kind.JS_UNPARSABLE, Kind.LIVE_SCRIPT), kinds(f));
  }

  private static String svgScriptText(String html) {
    try {
      Document doc =
          new HtmlDocumentBuilder(XmlViolationPolicy.ALLOW).parse(new InputSource(new StringReader(html)));
      return doc.getElementsByTagNameNS("http://www.w3.org/2000/svg", "script").item(0).getTextContent();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }
}
