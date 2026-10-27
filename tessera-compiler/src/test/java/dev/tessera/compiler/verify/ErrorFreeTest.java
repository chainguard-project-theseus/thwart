package dev.tessera.compiler.verify;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.oracle.Seeds;
import dev.tessera.compiler.testing.Compilation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The verifier accepts only error-free parses, outside three coarse exclusions (#406,
 * security-architecture.md §11.4), end to end: each error is a build error at the template's file,
 * line and column, with a fix-it for the error classes the bench corpus has. The rule table's own
 * cases are in {@link ContextVerifierRulesTest}.
 */
class ErrorFreeTest {
  private static final String NS = "xmlns:t=\"https://tessera.dev\"";

  private static Compilation verify(String body) {
    return Compilation.builder()
        .template("t.html", "<div " + NS + " t:params=\"String s\">" + body + "</div>")
        .verifierOnly()
        .compile();
  }

  private static Compilation build(String body) {
    return Compilation.builder()
        .template("t.html", "<div " + NS + " t:params=\"String s\">" + body + "</div>")
        .compile();
  }

  // ---- the corpus's error classes, each with its fix-it ----

  private static final String LAYOUT = "<!DOCTYPE html>\n<html " + NS + " t:import=\"dev.tessera.runtime.Fragment\""
      + " t:fragment=\"layout(Fragment content)\"><head><title>t</title></head>\n"
      + "<body><nav>n</nav><main t:replace=\"${content}\">c</main></body></html>";

  @Test
  void aLayoutCallThatPassesTheBodyElementNestsASecondBody() {
    // 80 of the 101 corpus templates: ~{layout :: layout(~{::body})} writes the page's <body>
    // inside the layout's (#409 teaches tessera-migrate the rewrite).
    Compilation.builder()
        .template("layout.html", LAYOUT)
        .template("page.html", "<!DOCTYPE html>\n<html " + NS + " t:params=\"String s\""
            + " t:replace=\"~{layout :: layout(~{::body})}\">\n<body><p t:text=\"s\">x</p></body></html>")
        .compile()
        .assertError("page.html:2:", "in fragment layout :: layout, inserted here, at page.html:3:1",
            "a browser finds a second <body>", "<body t:remove=\"tag\">", "[verifier:PARSE_ERROR]");
    // The fix (guide section 4.8): pass only the body's content.
    Compilation.builder()
        .template("layout.html", LAYOUT)
        .template("page.html", "<!DOCTYPE html>\n<html " + NS + " t:params=\"String s\""
            + " t:replace=\"~{layout :: layout(~{::body})}\">\n<body t:remove=\"tag\"><p t:text=\"s\">x</p></body></html>")
        .compile()
        .assertSuccess();
  }

  @Test
  void aBlockElementInAParagraph() {
    build("\n<p>Intro <div t:text=\"s\">x</div></p>")
        .assertError("t.html:2:", "a <p> can't hold block elements: use a <div> instead of the <p>", "[verifier:PARSE_ERROR]");
    build("\n<div>Intro <div t:text=\"s\">x</div></div>").assertSuccess();
  }

  @Test
  void contentBetweenHeadAndBody() {
    for (String tag : List.of("<script src=\"/a.js\"></script>", "<meta name=\"a\" content=\"b\">", "<link rel=\"icon\" href=\"/i.png\">")) {
      Compilation.builder()
          .template("p.html", "<!DOCTYPE html>\n<html " + NS + " t:params=\"String s\"><head><title>t</title></head>\n"
              + tag + "\n<body><p t:text=\"s\">x</p></body></html>")
          .verifierOnly()
          .compile()
          .assertError("p.html:3:1:", "from between </head> and <body> back into the <head>; write it inside the <head>",
              "[verifier:PARSE_ERROR]");
    }
  }

  @Test
  void contentDirectlyInATable() {
    verify("\n<table><h3>Title</h3><tr><td t:text=\"s\">x</td></tr></table>")
        .assertError("t.html:2:8:", "a browser moves this <h3> out of the table", "put it in a <td> or <th>", "[verifier:PARSE_ERROR]");
    verify("\n<table><a href=\"/x\">x</a><tr><td t:text=\"s\">x</td></tr></table>")
        .assertError("t.html:2:8:", "a browser moves this <a> out of the table", "[verifier:PARSE_ERROR]");
    verify("\n<table><table><tr><td t:text=\"s\">x</td></tr></table></table>")
        .assertError("t.html:2:8:", "closes the open table at a <table> directly inside it", "[verifier:PARSE_ERROR]");
  }

  @Test
  void noSpaceBetweenAttributes() {
    build("\n<table><tr><th rowspan='2'class='c'>#</th><td t:text=\"s\">x</td></tr></table>")
        .assertError("t.html:2:12:", "write a space between the attributes", "[verifier:PARSE_ERROR]");
  }

  @Test
  void anyOtherErrorQuotesTheParser() {
    build("\n<h1>Title <h2 t:text=\"s\">x</h2></h1>")
        .assertError("t.html:2:", "the HTML parser reports \"Heading cannot be a child of another heading.\"",
            "Tessera accepts only markup that parses without errors", "[verifier:PARSE_ERROR]");
  }

  // ---- where errors are found ----

  @Test
  void anErrorOnlyWithScriptingOffSaysSo() {
    Compilation.builder()
        .template("p.html", "<!DOCTYPE html>\n<html " + NS + " t:params=\"String s\"><head>\n"
            + "<noscript><div>no js</div></noscript></head><body><p t:text=\"s\">x</p></body></html>")
        .verifierOnly()
        .compile()
        .assertError("p.html:3:", "(only with scripting off) [verifier:PARSE_ERROR]");
  }

  @Test
  void anErrorOnlyInABranchALoopRoundOrACallSite() {
    // Only when the t:if holds.
    verify("\n<p>a<t:block t:if=\"s == null\"><ul><li>x</li></ul></t:block></p><span t:text=\"s\">x</span>")
        .assertError("t.html:2:", "[verifier:PARSE_ERROR]");
    // Only in a loop's second round: a <button> the first round left open, then a second one.
    Compilation.builder()
        .template("t.html", "<div " + NS + " t:params=\"java.util.List<String> xs\">\n"
            + "<t:block t:each=\"x : xs\"><button>[[${x}]]</t:block></div>")
        .verifierOnly()
        .compile()
        .assertError("t.html:2:", "[verifier:PARSE_ERROR]");
    // Only at one call site of a static fragment: a cell is fine in a row, not in a <div>.
    String frag = "<table><tr><td t:fragment=\"cell(String v)\" t:text=\"v\">c</td></tr></table>";
    verify(frag + "<table><tr><td t:replace=\"~{:: cell(s)}\"></td></tr></table>").assertSuccess();
    verify(frag + "\n<div><t:block t:replace=\"~{:: cell(s)}\"></t:block></div>")
        .assertError("t.html:2:26:", "in fragment t :: cell, inserted here, at t.html:1:", "Stray start tag", "[verifier:PARSE_ERROR]");
  }

  // ---- positives ----

  @Test
  void ordinaryTemplatesCompile() {
    // A partial without a doctype (the missing-doctype messages are allowed), and a page.
    build("<ul><li t:text=\"s\">x</li></ul><select><option>a</option><optgroup label=\"g\"><option t:text=\"s\">b</option>"
        + "</optgroup><hr><t:block><option>c</option></t:block></select>").assertSuccess();
    Compilation.builder()
        .template("p.html", "<!DOCTYPE html>\n<html " + NS + " t:params=\"String s\"><head><title t:text=\"s\">t</title>"
            + "<script src=\"/a.js\"></script></head><body><p t:text=\"s\">x</p></body></html>")
        .compile()
        .assertSuccess();
    // SVG and MathML outside tables, and a table inside foreignObject outside any table.
    build("<svg viewBox=\"0 0 1 1\"><path d=\"M0 0\"/><foreignObject><table><tr><td t:text=\"s\">x</td></tr></table>"
        + "</foreignObject></svg><p><math><mi>x</mi></math></p>").assertSuccess();
  }

  /** Partials count errors only in their implied fragment context: budgetmaster's <tr> rows. */
  @Test
  void rowPartialsCompile() {
    Compilation.builder()
        .template("rows.html", "<div " + NS + "><table><tbody><tr t:fragment=\"row(String a, String b)\"><td t:text=\"a\">a</td>"
            + "<td><input type=\"text\" t:value=\"b\"></td></tr></tbody></table></div>")
        .template("row.html", "<t:block " + NS + " t:params=\"String a, String b\">\n"
            + "<t:block t:replace=\"~{rows :: row(a, b)}\"></t:block>\n</t:block>")
        .template("tr.html", "<tr " + NS + " t:params=\"String a\"><td t:text=\"a\">a</td></tr>")
        .compile()
        .assertSuccess();
  }

  // ---- the exclusions, end to end ----

  /** #401's shapes, in the build: a table part directly in a <template> inside a cell. */
  @Test
  void tablePartsInATemplateInACell() {
    verify("<table><tr><td><template><caption></table>W").assertError("t.html:1:", "[verifier:TABLE_IN_TEMPLATE]");
    verify("<table><tr><td><template><caption>c</caption><table></table></template></td></tr></table>")
        .assertError("t.html:1:", "<caption> directly in <template>", "[verifier:TABLE_IN_TEMPLATE]");
    verify("<table><tr><td><template><caption>c</caption><table></table></template>[[${s}]]</td></tr></table>")
        .assertError("t.html:1:", "[verifier:TABLE_IN_TEMPLATE]");
  }

  /** #328's oracle cases: a <td> or <table> fragment inserted into a <select>. */
  @Test
  void tableFragmentsInASelect() {
    Compilation.builder()
        .template("t.html", "<div " + NS + " t:params=\"String s\"><table><tbody><tr><td t:fragment=\"frag(String s)\">--><span"
            + " t:title=\"s\">x</span></td></tr></tbody></table><select><t:block t:replace=\"~{:: frag(s)}\"></t:block></select></div>")
        .verifierOnly()
        .compile()
        .assertError("t.html:1:", "[verifier:SELECT_CONTENT]");
    Compilation.builder()
        .template("t.html", "<div " + NS + " t:params=\"String s\"><math><annotation-xml encoding=\"text/html\"><svg><foreignObject>"
            + "<table t:fragment=\"frag(String s)\">&lt;<span t:attr=\"x=${s}\">x</span></table></foreignObject></svg>"
            + "</annotation-xml></math><select t:insert=\"~{:: frag(s)}\"></select></div>")
        .verifierOnly()
        .compile()
        .assertError("t.html:1:", "[verifier:SELECT_CONTENT]");
  }

  /**
   * A tag with an attribute hole is fed in two chunks. When the parser replayed the tag's start to
   * complete it (before #408), with CRLF line breaks before the hole, what it reported in the
   * second chunk mapped back before it and was dropped: the <svg> in a cell and the nested <a> were
   * accepted. Nothing reported in a later chunk may be lost.
   */
  @Test
  void crlfLineBreaksBeforeAnAttributeHoleLoseNothing() {
    String crlf = "\r\n\r\n\r\n\r\n";
    build("<table><tr><td><svg class=\"" + crlf + "y\" t:attr=\"width=${s.length()}\"><rect></rect></svg></td></tr></table>")
        .assertError("t.html:1:", "<svg> inside <td>", "[verifier:FOREIGN_IN_TABLE]");
    verify("<a href=\"/1\">x<a title=\"" + crlf + "y\" t:href=\"@{/x(q=${s})}\">z</a></a>")
        .assertError("t.html:1:", "Start tag \u201Ca\u201D seen but an element of the same type was already open",
            "[verifier:PARSE_ERROR]");
    verify("<select><option>a</option><span title=\"" + crlf + "y\" t:text=\"s\">z</span></select>")
        .assertError("t.html:1:", "[verifier:SELECT_CONTENT]");
    // Without the error, the same shapes compile.
    build("<div><svg class=\"" + crlf + "y\" t:attr=\"width=${s.length()}\"><rect></rect></svg></div>").assertSuccess();
  }

  /**
   * SVG or MathML in a SafeHtml or Fragment value lands in a cell at run time, where exclusion (a)
   * can't see it: a partial with them isn't a verified FLOW partial (the <td> probe), so
   * SafeHtml.of and Fragment.of refuse it.
   */
  @Test
  void aPartialWithSvgOrMathIsNotFlow() {
    String[][] partials = {
      {"icon", "<span " + NS + " t:params=\"String a\"><svg viewBox=\"0 0 1 1\"><path d=\"M0 0\"></path></svg>"
          + "<b t:text=\"a\">x</b></span>"},
      {"fo", "<div " + NS + " t:params=\"String a\"><svg><foreignObject><table></table></foreignObject></svg>"
          + "<b t:text=\"a\">x</b></div>"},
      {"mi", "<p " + NS + " t:params=\"String a\"><math><mi t:text=\"a\">x</mi></math></p>"},
    };
    Compilation.Builder b = Compilation.builder();
    for (String[] t : partials) {
      b.template("p/" + t[0] + ".html", t[1]);
    }
    Compilation c = b.compile().assertSuccess();
    for (String[] t : partials) {
      Map<String, Skeleton.TemplateSkeleton> all = new LinkedHashMap<>();
      all.put("p/" + t[0], c.skeleton("p/" + t[0]));
      String why = new ContextVerifier(all).whyNotFlowPartial(c.skeleton("p/" + t[0]));
      assertTrue(why != null && why.contains("FOREIGN_IN_TABLE"), t[0] + ": " + why);
    }
  }

  /**
   * The parse5 deviation PR #402 found: parse5 handles the end tag of an SVG integration point
   * with an HTML formatting element open differently from validator.nu and the browsers. The
   * markup is a parse error, so the verifier never accepts it.
   */
  @Test
  void theParse5DeviationIsAParseError() {
    verify("<div><svg><desc><b></desc></svg>X</div>[[${s}]]").assertError("t.html:1:", "[verifier:PARSE_ERROR]");
  }

  /**
   * The negatives of the rules #410 retired, narrowed, folded into an exclusion or kept: each
   * still fails to compile.
   */
  @Test
  void theNegativesOfRetiredRulesStillFail() {
    // TEXT_RECONSTRUCTS: </p> closes the <b> out of order, and the value would go into a copy.
    verify("<p><b>x</p>[[${s}]]").assertError("t.html:1:", "[verifier:PARSE_ERROR]");
    // Kept: NOAHS_ARK. Three identical formatting elements, one with a computed attribute, written
    // in order parse without errors, so only the rule rejects them; misnested, they are a parse
    // error too.
    verify("<b t:class=\"s\"><b><b>x</b></b></b>").assertError("t.html:1:", "[verifier:NOAHS_ARK]");
    verify("<p><b class=\"x\">1<b class=\"x\">2<b class=\"x\">3<b t:class=\"s\">4</b></b></p>z")
        .assertError("t.html:1:", "[verifier:PARSE_ERROR]");
    // ATTRIBUTE_VALUE_CHANGES_PARSE for a computed input type: an <input> directly in a table.
    verify("<table><input t:type=\"s\"></table>").assertError("t.html:1:", "moves this <input> out of the table",
        "[verifier:PARSE_ERROR]");
    // FRAMESET: any frameset element.
    Compilation.builder()
        .template("t.html", "<!DOCTYPE html>\n<html " + NS + " t:params=\"String s\"><frameset><frame t:src=\"s\">"
            + "</frameset></html>")
        .verifierOnly()
        .compile()
        .assertError("t.html:2:", "<frameset> isn't supported", "[verifier:FRAMESET]");
  }

  /**
   * The seeds that only the 1.4.16 engine rejected before #406 (where browsers parse {@code
   * <select>} the classic way) are rejected by exclusion (c), now that the verifier runs one
   * engine (#408).
   */
  @Test
  void theSelectSeedsAreRejectedByExclusionC() {
    Map<String, Seeds.Seed> seeds = Seeds.load().stream().collect(Collectors.toMap(Seeds.Seed::name, x -> x));
    for (String name : List.of("306-A31", "pr304-7", "pr305-1", "pr305-2", "pr305-5", "pr305-6", "pr305-8", "pr305-9")) {
      Compilation c = Compilation.builder().template("t.html", seeds.get(name).template()).verifierOnly().compile();
      Map<String, Skeleton.TemplateSkeleton> all = new LinkedHashMap<>();
      all.put("t", c.skeleton("t"));
      List<Finding> f = new ContextVerifier(all).check(c.skeleton("t"));
      assertTrue(f.stream().anyMatch(x -> x.problem == Problem.SELECT_CONTENT), name + ": " + f.stream()
          .map(VerifierMessages::message).collect(Collectors.toList()));
    }
    // ... and an ordinary select still compiles.
    verify("<select><option t:text=\"s\">a</option><hr><optgroup label=\"g\"><option>b</option></optgroup></select>")
        .assertSuccess();
  }
}
