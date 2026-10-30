package dev.tessera.compiler.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.verify.Skeleton.Escaper;
import dev.tessera.compiler.verify.Skeleton.FragmentId;
import dev.tessera.compiler.verify.Skeleton.Node;
import dev.tessera.compiler.verify.Skeleton.Seq;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The verifier's rule table and control flow (#367) on hand-built skeletons, so each rule is
 * reached exactly, whatever the profile would say first. End-to-end cases are in
 * {@link ContextVerifierTest}.
 */
class ContextVerifierRulesTest {
  private int column = 1;

  // ---- holes ----

  @Test
  void textIsAcceptedWhereEveryValueIsCharacters() {
    accept(st("<p>"), text(), st("</p>"));
    accept(st("<table><tr><td>"), text(), st("</td></tr></table>"));
    accept(st("<table><caption>"), text());
    accept(st("<template>"), text());
    accept(st("<svg><text>"), text()); // foreign content appends every character as is
    accept(st("<iframe>"), text()); // raw text a browser never runs
    accept(st("<p><noscript>"), text()); // raw text with scripting on, text in the body without
    reject(Problem.TEXT_OUTSIDE_BODY, st("<noscript>"), text()); // in the head without scripting
    accept(st("<select><option>"), text());
    accept(st("<pre>"), text(), st("\nx</pre>"));
    // A foster-parented element is a parse error (#406, #410).
    reject(Problem.PARSE_ERROR, st("<table><h3>"), text());
  }

  @Test
  void textInsideMarkupIsRejected() {
    reject(Problem.TEXT_NOT_IN_TEXT, st("<p><"), text());
    reject(Problem.TEXT_NOT_IN_TEXT, st("<p></"), text());
    reject(Problem.TEXT_NOT_IN_TEXT, st("<p>&"), text());
    reject(Problem.TEXT_NOT_IN_TEXT, st("<p><!-- "), text());
    reject(Problem.TEXT_NOT_IN_TEXT, st("<p "), text());
    reject(Problem.TEXT_NOT_IN_TEXT, st("<title><"), text()); // '</' + the value could end the title
    reject(Problem.TEXT_NOT_IN_TEXT, st("<plaintext>"), text());
    reject(Problem.TEXT_NOT_IN_TEXT, st("<svg><![CDATA["), text());
  }

  @Test
  void textThatABrowserRunsIsRejected() {
    reject(Problem.TEXT_IN_SCRIPT, st("<script>"), text());
    reject(Problem.TEXT_IN_SCRIPT, st("<style>"), text());
    reject(Problem.TEXT_IN_SCRIPT, st("<svg><script>"), text());
    reject(Problem.TEXT_IN_SCRIPT, st("<svg><style>"), text());
    reject(Problem.TEXT_IN_SCRIPT, st("<script><!--<script>"), text());
  }

  @Test
  void textTheTreeBuilderMovesIsRejected() {
    reject(Problem.TEXT_IN_TABLE, st("<table>"), text());
    reject(Problem.TEXT_IN_TABLE, st("<table><tr><td>a</td></tr>"), text());
    reject(Problem.TEXT_IN_TABLE, st("<table><tr><td>a</td>"), text());
    reject(Problem.TEXT_IN_TABLE, st("<table><colgroup>"), text());
    reject(Problem.TEXT_IN_TABLE, st("<table><tr><td>a<td>b</td>"), text()); // probe T14
    reject(Problem.TEXT_OUTSIDE_BODY, st("<!DOCTYPE html><html><head>"), text());
    reject(Problem.TEXT_OUTSIDE_BODY, st("<!DOCTYPE html><html><head></head>"), text());
    reject(Problem.TEXT_OUTSIDE_BODY, st("<!DOCTYPE html><html><body></body>"), text());
    // Probe T22: misnested formatting elements, reopened before the text, are a parse error (#410).
    onlyParseErrors(st("<p><b>x</p>"), text());
  }

  @Test
  void rcdataEscapingOnlyInRcdata() {
    accept(st("<title>"), hole(Escaper.RCDATA, null), st("</title>"));
    accept(st("<textarea>"), hole(Escaper.RCDATA, null), st("</textarea>"));
    reject(Problem.RCDATA_NOT_IN_RCDATA, st("<svg><title>"), hole(Escaper.RCDATA, null)); // #306 A7
    reject(Problem.RCDATA_NOT_IN_RCDATA, st("<p>"), hole(Escaper.RCDATA, null));
  }

  @Test
  void theParserStateNamesTheTextEscaper() { // #378
    // RCDATA takes rcdata, not text: the comments that break {{ would show in the title.
    assertEquals(Escaper.RCDATA, textEscaper(st("<title>"), text(), st("</title>")));
    assertEquals(Escaper.RCDATA, textEscaper(st("<textarea>"), text(), st("</textarea>")));
    assertEquals(Escaper.RCDATA, textEscaper(st("<svg><foreignObject><title>"), text()));
    assertEquals(Escaper.RCDATA, textEscaper(st("<math><mi><textarea>"), text()));
    assertEquals(Escaper.RCDATA, textEscaper(st("<svg></svg><textarea>"), text()));
    assertEquals(Escaper.TEXT, textEscaper(st("<p>"), text(), st("</p>")));
    assertEquals(Escaper.TEXT, textEscaper(st("<svg><title>"), text())); // foreign content: data, text
    assertEquals(Escaper.TEXT, textEscaper(st("<svg><textarea>"), text()));
    accept(st("<svg><foreignObject><title>"), hole(Escaper.RCDATA, null));
    accept(st("<svg><title>"), text());
    // Never-run raw text takes any escaper: none writes '<'. Element text there is text.
    accept(st("<xmp>"), text());
    assertEquals(Escaper.TEXT, textEscaper(st("<xmp>"), text()));
    accept(st("<xmp>"), hole(Escaper.RCDATA, null));
    accept(st("<p><noscript><title>"), hole(Escaper.RCDATA, null)); // raw text with scripting on, RCDATA without
    assertEquals(Escaper.RCDATA, textEscaper(st("<p><noscript><title>"), text()));
  }

  @Test
  void aHoleWhosePathsNameDifferentEscapersIsRejected() { // #378, the branch rule
    reject(Problem.TEXT_IN_RCDATA, alt(seq(st("<p>")), seq(st("<title>"))), text());
    reject(Problem.RCDATA_NOT_IN_RCDATA, alt(seq(st("<p>")), seq(st("<title>"))), hole(Escaper.RCDATA, null));
    reject(Problem.JSON_NOT_IN_SCRIPT, alt(seq(st("<script>")), seq(st("<p>"))), json());
    reject(Problem.TEXT_IN_SCRIPT, alt(seq(st("<script>")), seq(st("<p>"))), text());
    Map<String, Seq> f = Map.of("f", seq(text()));
    accept(f, st("<p>"), call("f"), st("</p>"));
    reject(f, Problem.TEXT_IN_RCDATA, st("<p>"), call("f"), st("</p><title>"), call("f"), st("</title>"));
    // Element text takes the escaper of the first state that names one; the other is rejected.
    reject(Problem.RCDATA_NOT_IN_RCDATA, alt(seq(st("<title>")), seq(st("<p>"))), text());
    reject(f, Problem.RCDATA_NOT_IN_RCDATA, st("<title>"), call("f"), st("</title><p>"), call("f"), st("</p>"));
    accept(f, st("<title>"), call("f"), st("</title><textarea>"), call("f"), st("</textarea>"));
  }

  @Test
  void attributeValuesMustBeDoubleQuotedValuesOfTheirAttribute() {
    accept(st("<p title=\""), attr("title"), st("\">x</p>"));
    accept(st("<p TITLE=\""), attr("title"), st("\">x</p>"));
    accept(st("<svg viewBox=\""), attr("viewBox"), st("\"></svg>"));
    for (Escaper e : List.of(Escaper.URL, Escaper.URL_LIST, Escaper.RESOURCE_URL, Escaper.REFRESH, Escaper.JSON_ATTR)) {
      accept(st("<a href=\""), hole(e, "href"), st("\">x</a>"));
    }
    reject(Problem.ATTRIBUTE_NOT_IN_VALUE, st("<p title='"), attr("title"));
    reject(Problem.ATTRIBUTE_NOT_IN_VALUE, st("<p title="), attr("title"));
    reject(Problem.ATTRIBUTE_NOT_IN_VALUE, st("<p>"), attr("title"));
    reject(Problem.ATTRIBUTE_NOT_IN_VALUE, st("<p title=\"a\" "), attr("title"));
    reject(Problem.ATTRIBUTE_WRONG_NAME, st("<p alt=\""), attr("title"));
    reject(Problem.ATTRIBUTE_WRONG_NAME, st("<p title=\"a\" title=\""), attr("title")); // dropped duplicate
    // A browser folds A-Z only: \u017Frc (long s) is not src, \u212Aind (Kelvin sign) is not kind.
    reject(Problem.ATTRIBUTE_WRONG_NAME, st("<img \u017Frc=\""), attr("src"));
    reject(Problem.ATTRIBUTE_WRONG_NAME, st("<track \u212Aind=\""), attr("kind"));
  }

  @Test
  void attributeValuesThatDecideTheParseAreRejected() {
    accept(st("<input type=\""), attr("type"), st("\">"));
    // An <input> in a table mode is a parse error whatever its type, hidden too (#410).
    onlyParseErrors(st("<table><input type=\""), attr("type"), st("\">"));
    onlyParseErrors(st("<table><tr><input type=\""), attr("type"), st("\">"));
    onlyParseErrors(st("<table><input type=\"hidden\">"));
    reject(Problem.ATTRIBUTE_VALUE_CHANGES_PARSE, st("<math><annotation-xml encoding=\""), attr("encoding"));
    reject(Problem.ATTRIBUTE_VALUE_CHANGES_PARSE, st("<math><annotation-xml ENCODING=\""), attr("ENCODING"));
    // encod\u0131ng and encod\u0130ng are not encoding to a browser: their value decides nothing.
    for (String name : List.of("encod\u0131ng", "encod\u0130ng")) {
      accept(st("<math><annotation-xml " + name + "=\""), attr(name), st("\"></annotation-xml></math>"));
    }
  }

  @Test
  void jsonOnlyInTheScriptOfAnHtmlScript() {
    accept(st("<script>var a = "), json(), st(";</script>"));
    accept(st("<script>if (1<"), json(), st(") {}</script>")); // no JSON starts with '/' or '!'
    accept(st("<script>if (1<"), json(), st("/2) {}</script>")); // ... so '<' + value + '/' is no end tag
    reject(Problem.JSON_NOT_IN_SCRIPT, st("<svg><script>var a = "), json());
    reject(Problem.JSON_NOT_IN_SCRIPT, st("<p>"), json());
    // A JavaScript value, (-1.5) for a negative number, follows the same rules.
    accept(st("<script>var a = "), javaScript(), st(";</script>"));
    accept(st("<script>if (1<"), javaScript(), st("/2) {}</script>"));
    reject(Problem.JSON_NOT_IN_SCRIPT, st("<svg><script>var a = "), javaScript());
    reject(Problem.JSON_NOT_IN_SCRIPT, st("<p>"), javaScript());
    reject(Problem.JSON_NOT_IN_SCRIPT, st("<script><!--<script>"), json());
    reject(Problem.JSON_NOT_IN_SCRIPT, st("<script>a<!"), json());
    reject(Problem.JSON_NOT_IN_SCRIPT, st("<style>"), json());
  }

  @Test
  void anyEscaperIsInertInRawTextABrowserNeverRuns() {
    accept(st("<noscript><p title=\""), attr("title"));
    accept(st("<noscript><script>var a = "), json(), st(";</script></noscript>"));
    accept(st("<xmp>"), json());
  }

  @Test
  void rawHtmlOnlyInFlowContent() {
    accept(st("<div>"), raw(), st("</div>"));
    accept(st("<table><tr><td>"), raw());
    accept(st("<svg><foreignObject><div>"), raw());
    reject(Problem.RAW_NOT_FLOW, st("<!DOCTYPE html><html><head>"), raw());
    reject(Problem.RAW_NOT_FLOW, st("<table>"), raw());
    reject(Problem.RAW_NOT_FLOW, st("<svg>"), raw());
    reject(Problem.RAW_NOT_FLOW, st("<math><mi>"), raw());
    reject(Problem.RAW_NOT_FLOW, st("<title>"), raw());
    onlyParseErrors(st("<p><b>x</p>"), raw()); // formatting elements to reopen (#410)
    // Customizable <select> parsing reads it in body mode, where a <p> or <div> in the value closes
    // the select (#329).
    reject(Problem.RAW_CLOSES_ELEMENT, st("<select>"), raw());
  }

  @Test
  void framesetAndTheNoahsArkClause() {
    // Any frameset element: a coarse exclusion. Where it doesn't apply, it is a parse error.
    reject(Problem.FRAMESET, st("<!DOCTYPE html><html><FRAMESET>"));
    reject(Problem.FRAMESET, st("<!DOCTYPE html><html><head></head><frameset></frameset></html>"));
    onlyParseErrors(st("<p>x<frameset>"));
    // Identical formatting elements with a computed attribute: the Noah's Ark clause drops an entry
    // from the list depending on the value, but the element stays open, and in an error-free parse
    // the list changes the tree only through entries that are no longer open (#444).
    accept(st("<b class=\""), attr("class"), st("\"><b class=\""), attr("class"), st("\">x"));
    accept(st("<b class=\""), attr("class"), st("\"><b><b>x"));
    accept(st("<b><b><b class=\""), attr("class"), st("\">x"));
    accept(st("<b class=\""), attr("class"), st("\"><b><b>x</b></b></b>"));
    accept(st("<b class=\""), attr("class"), st("\"><i><b><b><b></b></b></b></i>x"));
    accept(st("<b><b><b><b>x"));
    // Misnested, it is a parse error.
    onlyParseErrors(st("<p><b class=\"x\">1<b class=\"x\">2<b class=\"x\">3<b class=\""), attr("class"),
        st("\">4</b></b></p>z"));
  }

  // ---- error-free parses and the three exclusions (#406) ----

  /**
   * #398 and PR #402's variants: a table (or template, or select) that ends inside SVG or
   * MathML in a table. validator.nu and Firefox reset the insertion mode to "in body" at the foreign
   * element; the standard, Chromium and WebKit walk on down to the cell. Exclusion (a) rejects the
   * shape itself, so it doesn't matter what follows, or whether anything does. PR #402's
   * differential (45,056 variants) and the #406 measurement's (98,260 variants, /tmp/rv402/diff):
   * every one of the 10,837 variants where Chromium and Firefox disagree is rejected, by the parse
   * errors alone; exclusion (a) also rejects 4,318 of those where they agree.
   */
  @Test
  void aResetInsideForeignContentInATableIsRejected() {
    Problem p = Problem.FOREIGN_IN_TABLE;
    String svgCell = "<table><tbody><tr><td><svg><foreignObject><div>";
    // The repro: a computed type then decides where the <input> goes in a browser.
    reject(p, st(svgCell + "<table></table></div></foreignObject></svg></td><input type=\""), attr("type"),
        st("\"></tr></tbody></table>"));
    // Text and attribute holes after it, and no hole at all.
    reject(p, st(svgCell + "<table></table></div></foreignObject></svg></td><td title=\""), attr("title"), st("\">"),
        text(), st("</td></tr></tbody></table>"));
    reject(p, st(svgCell + "<table></table></div></foreignObject></svg>"), text(), st("</td></tr></tbody></table>"));
    reject(p, st(svgCell + "<table></table></div></foreignObject></svg></td><td>x</td></tr></tbody></table>"));
    // A caption instead of a cell; MathML instead of SVG; annotation-xml as the integration point.
    reject(p, st("<table><caption><svg><desc><table></table></desc></svg>"), text());
    reject(p, st("<table><tr><th><math><mi><table><tr><td>x</td></tr></table></mi></math></th></tr></table>"));
    reject(p, st("<table><tr><td><math><annotation-xml encoding=\"text/html\"><table></table>"
        + "</annotation-xml></math></td></tr></table>"));
    reject(p, st("<table><tr><td><svg><title><table></table></title></svg></td></tr></table>"));
    // Deeper: SVG in a cell of a table inside SVG in a cell.
    reject(p, st("<table><tr><td><svg><foreignObject><table><tr><td><svg><foreignObject><table></table>"));
    reject(p, st("<table><tr><td><svg><foreignObject><div><svg><foreignObject><p><table></table>"));
    // A <template> ends there instead; a nested table with rows.
    reject(p, st(svgCell + "<template></template>"), text());
    reject(p, st(svgCell + "<table><tr><td>a</td></tr></table>"), text());
    // A </select> resets too, where browsers parse <select> the classic way.
    reject(p, st(svgCell + "<select><option>a</option></select>"), text());
    // The </table> comes from a fragment; the SVG from another static.
    Map<String, Seq> end = Map.of("end", seq(st("</table>")));
    reject(end, p, st(svgCell + "<table>"), call("end"), text());
    reject(p, st("<table><tr><td><svg>"), st("<foreignObject><table>"), st("</table>"), text());
    // The SVG comes from a fragment called in the cell.
    reject(Map.of("icon", seq(st("<svg viewBox=\"0 0 1 1\"><path d=\"M0 0\"/></svg>"))), p,
        st("<table><tr><td>"), call("icon"), st("</td></tr></table>"));
    // The table opens in one branch.
    reject(p, st(svgCell), opt(st("<table></table>")), text());
    // Coarse on purpose: any SVG or MathML in a table part or <template>, closed or not, with or
    // without a nested table, and in a partial whose fragment context is a table part.
    reject(p, st("<table><tr><td><svg></svg></td></tr></table>"));
    reject(p, st("<table><tr><td><math><mi>x</mi></math></td><td>"), text(), st("</td></tr></table>"));
    reject(p, st("<template><svg></svg></template>"));
    reject(p, st("<td><svg></svg></td>"));
  }

  @Test
  void foreignContentOutsideTablesIsAccepted() {
    accept(st("<div><svg viewBox=\"0 0 1 1\"><path d=\"M0 0\"/></svg>"), text(), st("</div>"));
    accept(st("<p><math><mi>x</mi></math>"), text(), st("</p>"));
    // A table inside SVG outside any table; a nested table in a cell without SVG.
    accept(st("<div><svg><foreignObject><table><tr><td>x</td></tr></table></foreignObject></svg>"), text());
    accept(st("<div><svg><foreignObject><table><tr><td>"), text(), st("</td></tr></table></foreignObject></svg></div>"));
    accept(st("<table><tr><td><div><table><tr><td>a</td></tr></table></div>"), text(), st("</td></tr></table>"));
    // SVG closed before the table.
    accept(st("<div><svg></svg><table><tr><td>"), text(), st("</td></tr></table></div>"));
  }

  /** #401: a table part directly in <template>, where vnu and Firefox close an outer table at </table>. */
  @Test
  void tablePartsDirectlyInATemplateAreRejected() {
    Problem p = Problem.TABLE_IN_TEMPLATE;
    reject(p, st("<table><tr><td><template><caption></table>W"));
    reject(p, st("<td><template><caption>c</caption><table></table></template>"));
    reject(p, st("<td><template><caption>c</caption><table></table></template>"), text());
    reject(p, st("<template><tr><td>"), text(), st("</td></tr></template>"));
    reject(p, st("<template><col></template>")); // a void table part
    reject(p, st("<template><tbody>"), text());
    // Flow content in a template, and a whole table in it, are fine.
    accept(st("<template><p>"), text(), st("</p></template>"));
    accept(st("<template><table><tr><td>"), text(), st("</td></tr></table></template>"));
  }

  /**
   * #305 on composed output: classic select parsing (Firefox ESR, parse5) drops what customizable
   * select parsing (Chromium, current Firefox, WebKit, vnu 26) keeps. The profile's own rule
   * (HtmlParser.SELECT_CONTENT) sees one template's source; exclusion (c) sees fragments, t:remove
   * and template contents unwrapped into a select.
   */
  @Test
  void onlyOptionsGroupsAndRulesInASelect() {
    Problem p = Problem.SELECT_CONTENT;
    Map<String, Seq> span = Map.of("f", seq(st("<span>"), text(), st("</span>")));
    reject(span, p, st("<select><option>"), call("f"), st("</option></select>")); // pr304-7's shape
    reject(span, p, st("<select>"), call("f"), st("</select>"));
    reject(p, st("<select><div>"), text());
    reject(p, st("<select><option><b>"), text());
    reject(p, st("<select><img>")); // a void element too
    reject(p, st("<select><title>"), text()); // pr305-8/9: what <template> or t:remove left there
    reject(p, st("<option><span>"), text()); // an <option> partial: its fragment context is <select>
    accept(st("<select><option>a</option><optgroup label=\"g\"><option>"), text(),
        st("</option></optgroup><hr><option selected>b</option></select>"));
    accept(Map.of("o", seq(st("<option>"), text(), st("</option>"))), st("<select>"), call("o"), call("o"), st("</select>"));
  }

  @Test
  void anyParseErrorIsRejectedWherePathsReachIt() {
    Problem p = Problem.PARSE_ERROR;
    reject(p, st("<p><div>x</div></p>"));
    reject(p, st("<div a=\"1\"b=\"2\">x</div>"));
    reject(p, st("<!DOCTYPE html><html><head></head><script></script><body>"));
    // Only in a branch, only in a loop's second round, only at one of a fragment's call sites.
    reject(p, st("<div>"), opt(st("</span>")), text());
    reject(p, st("<div>"), alt(seq(st("<p>a</p>")), seq(st("<p>a</p></p>"))), text());
    reject(p, st("<div>"), new Skeleton.Rep(Skeleton.RepKind.EACH, seq(text()), seq(st("</i>")), pos()));
    Map<String, Seq> cell = Map.of("cell", seq(st("<td>"), text(), st("</td>")));
    accept(cell, st("<table><tr>"), call("cell"), st("</tr></table>"));
    reject(cell, p, st("<div>"), call("cell"));
    // Only with scripting off: <noscript> in the head is markup then.
    reject(p, st("<!DOCTYPE html><html><head><noscript><div>"));
    accept(st("<!DOCTYPE html><html><head><title>t</title></head><body><p>"), text(), st("</p></body></html>"));
    // The allow-list: the missing-doctype messages only.
    accept(st("<html><body><p>"), text());
    accept(st("<p>"), text());
  }

  @Test
  void aPartialCountsParseErrorsOnlyInItsFragmentContext() {
    // As a document, a <tr> is a stray start tag; in <tbody>, its fragment context, it isn't.
    accept(st("<tr><td>"), text(), st("</td></tr><tr><td>b</td></tr>"));
    accept(st("<td>"), text(), st("</td><th>h</th>"));
    accept(st("<li>"), text(), st("</li><li>b</li>"));
    accept(st("<option>"), text(), st("</option><optgroup label=\"g\"></optgroup>"));
    // ... and anything else in <body>.
    reject(Problem.PARSE_ERROR, st("<caption>"), text());
    reject(Problem.PARSE_ERROR, st("<body><p>"), text());
  }

  // ---- control flow ----

  @Test
  void optionalPartsAreAUnion() {
    reject(Problem.TEXT_IN_TABLE, opt(st("<table>")), text());
    accept(st("<div>"), opt(st("<table><tr><td>")), text());
    // Text first in a document: whitespace is dropped there and anything else starts the body.
    reject(Problem.TEXT_OUTSIDE_BODY, opt(st("<div>")), text());
    reject(Problem.TEXT_IN_TABLE, st("<table>"), alt(seq(st("<tr><td>")), seq(st("<caption>"))), st("x"),
        alt(seq(st("</td></tr>")), seq(st("</caption>"))), text());
  }

  @Test
  void manyComputedAttributesInOneTagStayOneState() {
    List<Node> nodes = new ArrayList<>();
    nodes.add(st("<input"));
    for (int i = 0; i < 40; i++) {
      nodes.add(new Skeleton.Opt(Skeleton.OptKind.ATTRIBUTE, seq(st(" a" + i + "=\""), attr("a" + i), st("\"")), pos()));
    }
    nodes.add(st(">"));
    nodes.add(text());
    accept(nodes.toArray(new Node[0]));
    // Neither attribute written: what follows the tag name must still end it.
    reject(Problem.UNKNOWN_NODE, st("<input"),
        new Skeleton.Opt(Skeleton.OptKind.ATTRIBUTE, seq(st(" a=\""), attr("a"), st("\"")), pos()),
        new Skeleton.Opt(Skeleton.OptKind.ATTRIBUTE, seq(st(" b=\""), attr("b"), st("\"")), pos()), st("x>"));
    // An <input> in a table is a parse error with or without its computed type (#410).
    onlyParseErrors(st("<table><input"),
        new Skeleton.Opt(Skeleton.OptKind.ATTRIBUTE, seq(st(" type=\""), attr("type"), st("\"")), pos()), st(">"));
    // A computed encoding is kept as a branch: whether it is there decides how the content parses.
    reject(Problem.ATTRIBUTE_VALUE_CHANGES_PARSE, st("<math><annotation-xml"),
        new Skeleton.Opt(Skeleton.OptKind.ATTRIBUTE, seq(st(" encoding=\""), attr("encoding"), st("\"")), pos()),
        st("><div>"));
  }

  @Test
  void tooManyStatesIsAnError() {
    List<Seq> branches = new ArrayList<>();
    for (int i = 0; i < ContextVerifier.MAX_STATES + 1; i++) {
      StringBuilder sb = new StringBuilder();
      for (int k = 0; k <= i; k++) {
        sb.append("<div>");
      }
      branches.add(seq(st(sb.toString())));
    }
    reject(Problem.TOO_MANY_STATES, st("<p>"), alt(branches.toArray(new Seq[0])), text());
  }

  @Test
  void loopsRunToAFixedPoint() {
    accept(st("<table>"), rep(seq(st("<tr><td>"), text(), st("</td></tr>"))), st("</table>")); // implied tbody
    accept(st("<ul>"), rep(seq(st("<li>"), text())), st("</ul>")); // an open li closes the last
    reject(Problem.LOOP_NO_FIXED_POINT, rep(seq(st("<div>"), text())));
    reject(Problem.TEXT_IN_TABLE, st("<table><tr>"), rep(seq(st("<td>a</td>"), text())));
    // The separator runs before every iteration but the first, also when an iteration ends where it began.
    reject(Problem.TEXT_IN_TABLE, st("<div>"),
        new Skeleton.Rep(Skeleton.RepKind.EACH, seq(text()), seq(st("<table>")), pos()));
  }

  // ---- fragments ----

  @Test
  void callsAreCheckedInTheCallersState() {
    Map<String, Seq> frags = Map.of("cell", seq(st("<td>"), text(), st("</td>")));
    accept(frags, st("<table><tr>"), call("cell"), st("</tr></table>"));
    List<Finding> f = check(frags, seq(st("<table>"), call("cell"), call("cell")));
    // A td directly in a table implies tbody and tr: the text stays in the cell, but a cell without
    // a row is a parse error (#406).
    assertEquals(List.of(Problem.PARSE_ERROR), problems(f));
    f = check(frags, seq(st("<div>"), call("cell")));
    // A td in a div is ignored, so the text stays text, but it is a parse error (#406), at the call.
    assertEquals(List.of(Problem.PARSE_ERROR), problems(f));
    assertTrue(VerifierMessages.message(f.get(0)).contains("in fragment t :: cell, inserted here, at t.html:1:"),
        VerifierMessages.message(f.get(0)));
    f = check(Map.of("x", seq(text())), seq(st("<table>"), call("x")));
    assertEquals(List.of(Problem.TEXT_IN_TABLE), problems(f));
    assertEquals("t.html", f.get(0).at.file());
    assertTrue(VerifierMessages.message(f.get(0)).contains("in fragment t :: x, inserted here, at t.html:1:"), VerifierMessages.message(f.get(0)));
  }

  @Test
  void fragmentArgumentsAreBoundAtTheCallSite() {
    Map<String, Seq> frags = new LinkedHashMap<>();
    frags.put("layout", seq(st("<!DOCTYPE html><html><head><title>t</title>"), new Skeleton.ParamInsert("head", pos()),
        st("</head><body>"), new Skeleton.ParamInsert("content", pos()), st("</body></html>")));
    frags.put("links", seq(st("<link rel=\"stylesheet\" href=\""), attr("href"), st("\">")));
    // Bound: the head content is checked in the head, the body content in the body.
    accept(frags, new Skeleton.Call(id("layout"), Map.of("head", seq(call("links")), "content", seq(st("<p>"), text())), pos()));
    // null: nothing in the head.
    accept(frags, new Skeleton.Call(id("layout"), Map.of("head", Seq.empty(), "content", seq(text())), pos()));
    // Unbound: any HTML, which isn't allowed in the head.
    reject(frags, Problem.RAW_NOT_FLOW, new Skeleton.Call(id("layout"), Map.of("content", seq(text())), pos()));
    // Bound content that doesn't fit where the layout puts it.
    reject(frags, Problem.TEXT_OUTSIDE_BODY, new Skeleton.Call(id("layout"), Map.of("head", seq(text()), "content", seq(text())), pos()));
  }

  @Test
  void recursionIsAFixedPoint() {
    // Into the same state each time: converges.
    Map<String, Seq> same = Map.of("item", seq(st("<span>"), text(), st("</span>"), opt(call("item"))));
    accept(same, st("<p>"), call("item"));
    // Deeper each time: the structure depends on data.
    Map<String, Seq> deeper = Map.of("tree", seq(st("<ul><li>"), text(), rep(seq(call("tree"))), st("</li></ul>")));
    List<Finding> f = check(deeper, seq(call("tree")));
    assertEquals(List.of(Problem.STRUCTURE_DEPENDS_ON_DATA), problems(f));
    // Rich text rebuilt by a fragment that switches on tag names: text lands in a table only through recursion.
    Map<String, Seq> rich = Map.of("node", seq(alt(
        seq(st("<table>"), rep(seq(call("node"))), st("</table>")),
        seq(st("<tr>"), rep(seq(call("node"))), st("</tr>")),
        seq(text()))));
    f = check(rich, seq(st("<div>"), call("node"), st("</div>")));
    assertTrue(problems(f).contains(Problem.TEXT_IN_TABLE) || problems(f).contains(Problem.STRUCTURE_DEPENDS_ON_DATA), f.toString());
    assertTrue(f.stream().map(VerifierMessages::message).anyMatch(m -> m.contains("html.sanitize")), f.toString());
  }

  @Test
  void anUnknownFragmentFailsClosed() {
    reject(Problem.UNKNOWN_NODE, call("missing"));
  }

  @Test
  void partialsAreAlsoCheckedAsFragments() {
    // As a document a <tr> outside a table is ignored; as an htmx partial it is parsed in <tbody>.
    accept(st("<tr><td>"), text(), st("</td></tr>"));
    reject(Problem.TEXT_IN_TABLE, st("<tr>"), text(), st("<td>a</td></tr>"));
    accept(st("<li>"), text(), st("</li>"));
    accept(st("<option>"), text(), st("</option>"));
    // A page is a document only.
    accept(st("<!DOCTYPE html><html><body><table><tr><td>"), text());
    // ... but a template that may also start otherwise is a partial too.
    reject(Problem.TEXT_IN_TABLE, alt(seq(st("<!DOCTYPE html><html><body>x</body></html>")),
        seq(st("<tr>"), text(), st("<td>a</td></tr>"))));
  }

  @Test
  void aHoleWithoutAPositionIsReportedAtTheNearestOne() {
    List<Finding> f = check(seq(st("<table>"), new Skeleton.Hole(Escaper.TEXT, null, null, null)));
    assertEquals(new SourcePos("t.html", 1, 1), f.get(0).at);
  }

  @Test
  void messagesNameTheRuleAndNeverAState() {
    List<Finding> f = check(seq(st("<table>"), text()));
    String m = VerifierMessages.message(f.get(0));
    assertTrue(m.contains("[verifier:TEXT_IN_TABLE]") && m.contains("put it in a <td>"), m);
    assertTrue(!m.matches(".*\\b(IN_TABLE|DATA|tok=)\\b.*"), m);
  }

  // ---- helpers ----

  private SourcePos pos() {
    return new SourcePos("t.html", 1, column++);
  }

  private Skeleton.Static st(String s) {
    return new Skeleton.Static(s, pos());
  }

  private Skeleton.Hole text() {
    return hole(Escaper.TEXT, null);
  }

  private Skeleton.Hole json() {
    return hole(Escaper.JSON, null);
  }

  private Skeleton.Hole javaScript() {
    return hole(Escaper.JAVASCRIPT, null);
  }

  private Skeleton.Hole attr(String name) {
    return hole(Escaper.ATTR, name);
  }

  private Skeleton.Hole hole(Escaper e, String attribute) {
    return new Skeleton.Hole(e, null, attribute, pos());
  }

  private Skeleton.RawInsert raw() {
    return new Skeleton.RawInsert(Skeleton.Source.SAFE_HTML, pos());
  }

  private Skeleton.Opt opt(Node... nodes) {
    return new Skeleton.Opt(Skeleton.OptKind.CONDITION, seq(nodes), pos());
  }

  private Skeleton.Alt alt(Seq... branches) {
    return new Skeleton.Alt(List.of(branches), pos());
  }

  private Skeleton.Rep rep(Seq body) {
    return new Skeleton.Rep(Skeleton.RepKind.EACH, body, Seq.empty(), pos());
  }

  private Skeleton.Call call(String name) {
    return new Skeleton.Call(id(name), Map.of(), pos());
  }

  private static FragmentId id(String name) {
    return new FragmentId("t", name);
  }

  private static Seq seq(Node... nodes) {
    return new Seq(List.of(nodes));
  }

  /** The escaper the verifier names for the only element-text hole in {@code nodes}, which it accepts. */
  private static Escaper textEscaper(Node... nodes) {
    Skeleton.TemplateSkeleton t = new Skeleton.TemplateSkeleton("t", seq(nodes), Map.of());
    List<String> errors = new ArrayList<>();
    Map<Skeleton.Hole, Escaper> escapers = ContextVerifier.verify(Map.of("t", t), d -> errors.add(d.toString()));
    assertEquals(List.of(), errors, seq(nodes).toString());
    assertEquals(1, escapers.size(), seq(nodes).toString());
    return escapers.values().iterator().next();
  }

  private static List<Finding> check(Seq body) {
    return check(Map.of(), body);
  }

  private static List<Finding> check(Map<String, Seq> fragments, Seq body) {
    Skeleton.TemplateSkeleton t = new Skeleton.TemplateSkeleton("t", body, fragments);
    Map<String, Skeleton.TemplateSkeleton> all = new LinkedHashMap<>();
    all.put("t", t);
    return new ContextVerifier(all).check(t);
  }

  private static List<Problem> problems(List<Finding> f) {
    return f.stream().map(x -> x.problem).distinct().collect(Collectors.toList());
  }

  private static void accept(Node... nodes) {
    accept(Map.of(), nodes);
  }

  private static void accept(Map<String, Seq> fragments, Node... nodes) {
    List<Finding> f = check(fragments, seq(nodes));
    assertTrue(f.isEmpty(), seq(nodes) + " -> " + f.stream().map(VerifierMessages::message).collect(Collectors.toList()));
  }

  /** Rejected only by the parse-error rule and the exclusions (#406): every hole is accepted. */
  private static void onlyParseErrors(Node... nodes) {
    List<Finding> f = check(Map.of(), seq(nodes));
    assertTrue(!f.isEmpty() && f.stream().allMatch(x -> PARSER_REPORTED.contains(x.problem)),
        seq(nodes) + " -> " + f.stream().map(VerifierMessages::message).collect(Collectors.toList()));
  }

  private static final Set<Problem> PARSER_REPORTED =
      Set.of(Problem.PARSE_ERROR, Problem.FOREIGN_IN_TABLE, Problem.TABLE_IN_TEMPLATE, Problem.SELECT_CONTENT,
          Problem.FRAMESET);

  private static void reject(Problem expected, Node... nodes) {
    reject(Map.of(), expected, nodes);
  }

  private static void reject(Map<String, Seq> fragments, Problem expected, Node... nodes) {
    List<Finding> f = check(fragments, seq(nodes));
    assertTrue(problems(f).contains(expected),
        seq(nodes) + " -> " + f.stream().map(VerifierMessages::message).collect(Collectors.toList()));
  }
}
