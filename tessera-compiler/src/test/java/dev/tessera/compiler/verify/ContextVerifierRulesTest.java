package dev.tessera.compiler.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.verify.Skeleton.Escaper;
import dev.tessera.compiler.verify.Skeleton.FragmentId;
import dev.tessera.compiler.verify.Skeleton.Node;
import dev.tessera.compiler.verify.Skeleton.Seq;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
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
    reject(Problem.VALUE_IN_FOREIGN, st("<svg><text>"), text()); // #678: no value in SVG or MathML
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
    reject(Problem.CDATA_SECTION, st("<svg><![CDATA["), text());
  }

  @Test
  void textThatABrowserRunsIsRejected() {
    reject(Problem.TEXT_IN_SCRIPT, st("<script>"), text());
    reject(Problem.TEXT_IN_SCRIPT, st("<style>"), text());
    reject(Problem.VALUE_IN_FOREIGN, st("<svg><script>"), text());
    reject(Problem.VALUE_IN_FOREIGN, st("<svg><style>"), text());
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
  void textIsOneEscaperInDataAndRcdata() { // #679: no escaper selection by parser state
    accept(st("<title>"), text(), st("</title>"));
    accept(st("<textarea>"), text(), st("</textarea>"));
    accept(st("<svg><foreignObject><title>"), text());
    reject(Problem.VALUE_IN_FOREIGN, st("<svg><title>"), text()); // #678
    accept(alt(seq(st("<p>"), text(), st("</p>")), seq(st("<title>"), text(), st("</title>"))));
    Map<String, Seq> f = Map.of("f", seq(text()));
    accept(f, st("<p>"), call("f"), st("</p><title>"), call("f"), st("</title>"));
  }

  @Test
  void aHoleWhosePathsNameDifferentEscapersIsRejected() { // #378, the branch rule: by construction since #682
    reject(Problem.UNBALANCED, alt(seq(st("<script>")), seq(st("<p>"))), json());
    reject(Problem.UNBALANCED, alt(seq(st("<script>")), seq(st("<p>"))), text());
  }

  @Test
  void attributeValuesMustBeDoubleQuotedValuesOfTheirAttribute() {
    accept(st("<p title=\""), attr("title"), st("\">x</p>"));
    accept(st("<p TITLE=\""), attr("title"), st("\">x</p>"));
    accept(st("<svg viewBox=\""), attr("viewBox"), st("\"></svg>"));
    for (Escaper e : List.of(Escaper.URL, Escaper.RESOURCE_URL, Escaper.REFRESH)) {
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
  void noJsonEscaperWritesAnAttributeValue() {
    // #599: the attribute escapers are exactly these; none writes JSON (jsonAttr is deleted).
    assertEquals(EnumSet.of(Escaper.ATTR, Escaper.URL, Escaper.RESOURCE_URL, Escaper.REFRESH),
        Arrays.stream(Escaper.values()).filter(Escaper::inAttribute)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(Escaper.class))));
    // A JSON hole in a binding attribute's value is rejected; the attribute escaper there is accepted.
    for (String name : List.of("v-bind:title", "x-bind:title", "data-x")) {
      reject(Problem.JSON_NOT_IN_SCRIPT, st("<p " + name + "=\""), json());
      reject(Problem.JSON_NOT_IN_SCRIPT, st("<p " + name + "=\""), javaScript());
      accept(st("<p " + name + "=\""), attr(name), st("\">x</p>"));
    }
  }

  @Test
  void attributeValuesThatDecideTheParseAreRejected() {
    accept(st("<input type=\""), attr("type"), st("\">"));
    // An <input> in a table mode is a parse error whatever its type, hidden too (#410).
    onlyParseErrors(st("<table><input type=\""), attr("type"), st("\">"));
    onlyParseErrors(st("<table><tr><input type=\""), attr("type"), st("\">"));
    onlyParseErrors(st("<table><input type=\"hidden\">"));
    // #678: no value in MathML, so a computed encoding never decides how annotation-xml content parses.
    reject(Problem.VALUE_IN_FOREIGN, st("<math><annotation-xml encoding=\""), attr("encoding"));
    // An attribute of an HTML element in an HTML integration point is still read with MathML current
    // (ax-pos-5, which the profile now rejects first: everything under <math> is foreign by tag).
    reject(Problem.VALUE_IN_FOREIGN, st("<math><annotation-xml encoding=\"text/html\"><a title=\""), attr("title"));
  }

  @Test
  void jsonOnlyInTheScriptOfAnHtmlScript() {
    accept(st("<script>var a = "), json(), st(";</script>"));
    accept(st("<script>if (1<"), json(), st(") {}</script>")); // no JSON starts with '/' or '!'
    accept(st("<script>if (1<"), json(), st("/2) {}</script>")); // ... so '<' + value + '/' is no end tag
    reject(Problem.VALUE_IN_FOREIGN, st("<svg><script>var a = "), json());
    reject(Problem.JSON_NOT_IN_SCRIPT, st("<p>"), json());
    // A JavaScript value, (-1.5) for a negative number, follows the same rules.
    accept(st("<script>var a = "), javaScript(), st(";</script>"));
    accept(st("<script>if (1<"), javaScript(), st("/2) {}</script>"));
    reject(Problem.VALUE_IN_FOREIGN, st("<svg><script>var a = "), javaScript());
    reject(Problem.JSON_NOT_IN_SCRIPT, st("<p>"), javaScript());
    reject(Problem.JSON_NOT_IN_SCRIPT, st("<script><!--<script>"), json());
    reject(Problem.JSON_NOT_IN_SCRIPT, st("<script>a<!"), json());
    reject(Problem.JSON_NOT_IN_SCRIPT, st("<style>"), json());
  }

  /**
   * No value in the raw text of {@code <noscript>} (scripting on), {@code <xmp>}, {@code <iframe>},
   * {@code <noembed>} or {@code <noframes>}, whatever its escaper (#583, #586): raw text decodes no
   * character reference. The generator reports it first; the verifier is the
   * boundary.
   */
  @Test
  void anyValueInRawTextIsRejected() {
    for (String el : List.of("noscript", "xmp", "iframe", "noembed", "noframes")) {
      reject(Problem.VALUE_IN_RAW_TEXT, st("<p><" + el + ">"), text());
      reject(Problem.VALUE_IN_RAW_TEXT, st("<p><" + el + ">"), json());
      reject(Problem.VALUE_IN_RAW_TEXT, st("<p><" + el + "><i title=\""), attr("title"));
    }
    // Markup with scripting off, raw text with it on: rejected on the scripting-on parse.
    reject(Problem.VALUE_IN_RAW_TEXT, st("<p><noscript><title>"), text());
    reject(Problem.VALUE_IN_RAW_TEXT, st("<noscript><script>var a = "), json(), st(";</script></noscript>"));
    // The CSRF input a <form> inside <noscript> gets: an attribute hole in raw text (#577).
    reject(Problem.VALUE_IN_RAW_TEXT,
        st("<p><noscript><form method=\"post\"><input type=\"hidden\" name=\"_csrf\" value=\""), attr("value"),
        st("\"></form></noscript></p>"));
    // Positive controls: a hole after the element ends, and static raw text.
    accept(st("<p><noscript>x</noscript>"), text(), st("</p>"));
    accept(st("<div><xmp>a {{ b</xmp><i title=\""), attr("title"), st("\">x</i></div>"));
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
    // Raw HTML in an <option> is rejected for both types (#754): inline markup does not make it fit.
    reject(Problem.RAW_CLOSES_ELEMENT, st("<select><option>"), raw());
    reject(Problem.RAW_CLOSES_ELEMENT, st("<select><option>"), rawInline());
  }

  @Test
  void framesetAndTheNoahsArkClause() {
    // Any frameset element is a PARSE_ERROR (Rules.FRAMESET), also where its start tag applies.
    reject(Problem.PARSE_ERROR, st("<!DOCTYPE html><html><FRAMESET>"));
    reject(Problem.PARSE_ERROR, st("<!DOCTYPE html><html><head></head><frameset></frameset></html>"));
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
  void controlFlowIsStateNeutral() {
    // A body that leaves an element open, or closes one, would make what follows parse differently
    // with and without it (#682).
    reject(Problem.UNBALANCED, opt(st("<table>")), text());
    reject(Problem.UNBALANCED, st("<div>"), opt(st("<table><tr><td>")), text());
    reject(Problem.UNBALANCED, st("<div>"), opt(st("</div>")), text());
    reject(Problem.UNBALANCED, st("<table>"), alt(seq(st("<tr><td>")), seq(st("<caption>"))), st("x"),
        alt(seq(st("</td></tr>")), seq(st("</caption>"))), text());
    accept(st("<div>"), opt(st("<p>"), text(), st("</p>")), alt(seq(st("<b>x</b>")), seq(text())), st("</div>"));
    // Ending inside markup: a tag, a comment, raw text.
    reject(Problem.UNBALANCED, st("<div>"), opt(st("<!-- ")), st("x --></div>"));
    reject(Problem.UNBALANCED, st("<div>"), opt(st("<span title=\"")), st("\"></span></div>"));
    // A t:if on <html> at the start of a page changes the document; a layout call there may end it.
    reject(Problem.UNBALANCED, st("<!DOCTYPE html>"), opt(st("<html><body>x</body></html>")));
    // The message names the body and what it leaves open.
    List<Finding> f = check(seq(st("<div>"), opt(st("<section>")), st("</div>")));
    String m = f.stream().filter(x -> x.problem == Problem.UNBALANCED).map(VerifierMessages::message)
        .findFirst().orElse(f.toString());
    assertTrue(m.contains("this t:if or t:unless leaves <section> open"), m);
  }

  @Test
  void aPartialEndsInTheStateItStarts() {
    accept(st("<div>"), text(), st("</div><p>x</p>"));
    // The issue's example (#682): as a fragment with scripting on, it ends in script data.
    List<Finding> f = check(seq(st("<noscript><!-- </noscript><script> --></noscript>")));
    assertEquals(List.of(Problem.UNBALANCED), problems(f));
    assertTrue(VerifierMessages.message(f.get(0)).contains("this partial"), f.toString());
    // ... and as the body of a t:if.
    reject(Problem.UNBALANCED, st("<div>"), opt(st("<noscript><!-- </noscript><script> --></noscript>")),
        st("</div>"));
    // Only in the contexts whose parse errors count: as a document, any partial opens <html> and <body>.
    accept(opt(st("<div>"), text(), st("</div>")));
  }

  @Test
  void aLeadingIfDoesNotChangeHowHeadElementsInAPartialAreChecked() {
    // As a document, a leading t:if leaves the parser in body (written) or before <html> (skipped);
    // the verifier goes on from the entry only. A hole in a head element after it is rejected
    // wherever either state would reject it (#707): these elements tokenize alike in head and body,
    // and the head adds rules of its own (TEXT_OUTSIDE_BODY after a <noscript> with scripting off).
    for (String e : List.of("noscript", "style", "title")) {
      Node[] el = {st("<" + e + ">"), text(), st("</" + e + ">")};
      List<Problem> skipped = problems(check(seq(el)));
      List<Problem> written = problems(check(seq(st("<p>x</p>"), el[0], el[1], el[2])));
      List<Problem> leadingIf = problems(check(seq(opt(st("<p>x</p>")), el[0], el[1], el[2])));
      assertTrue(leadingIf.containsAll(skipped) && leadingIf.containsAll(written),
          e + ": " + leadingIf + " skipped " + skipped + " written " + written);
    }
    accept(opt(st("<p>x</p>")), st("<title>"), text(), st("</title>"));
    reject(Problem.TEXT_IN_SCRIPT, opt(st("<p>x</p>")), st("<style>"), text(), st("</style>"));
    reject(Problem.VALUE_IN_RAW_TEXT, opt(st("<p>x</p>")), st("<noscript>"), text(), st("</noscript>"));
  }

  @Test
  void aTemplateElementMaySwitchToBody() {
    // In template mode, the first flow start tag switches the mode: allowed, and checked from there on.
    accept(st("<template>"), rep(seq(st("<div>"), text(), st("</div>"))), st("</template>"));
    accept(st("<template>"), opt(st("<p>"), text(), st("</p>")), st("<div>x</div></template>"));
    // ... but not by a loop's separator only: from two passes on a browser is in body, where the
    // first pass leaves template mode (#707). Rejected, not modelled.
    reject(Problem.UNBALANCED, st("<template>"),
        new Skeleton.Rep(Skeleton.RepKind.ERRORS, seq(text()), seq(st("<br />")), pos()),
        st("<tr><td>"), text(), st("</td></tr></template>"));
    reject(Problem.UNBALANCED, st("<template>"),
        new Skeleton.Rep(Skeleton.RepKind.ERRORS, seq(text()), seq(st("<br />")), pos()), st("</template>"));
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
    // After a static boolean attribute: written, a '=' or a name right after it would be a parse
    // error, so the written path is the stricter one (#682).
    accept(st("<input disabled"),
        new Skeleton.Opt(Skeleton.OptKind.ATTRIBUTE, seq(st(" value=\""), attr("value"), st("\"")), pos()), st(" />"));
    accept(st("<input disabled "),
        new Skeleton.Opt(Skeleton.OptKind.ATTRIBUTE, seq(st(" value=\""), attr("value"), st("\"")), pos()), st(">"));
    reject(Problem.UNKNOWN_NODE, st("<input disabled"),
        new Skeleton.Opt(Skeleton.OptKind.ATTRIBUTE, seq(st(" value=\""), attr("value"), st("\"")), pos()), st("=x>"));
    // Skipped, these give the boolean attribute a value; only the written path's parse error rejects them.
    reject(Problem.PARSE_ERROR, st("<input disabled "),
        new Skeleton.Opt(Skeleton.OptKind.ATTRIBUTE, seq(st(" value=\""), attr("value"), st("\"")), pos()), st("=x>"));
    reject(Problem.PARSE_ERROR, st("<input disabled"),
        new Skeleton.Opt(Skeleton.OptKind.ATTRIBUTE, seq(st(" value=\""), attr("value"), st("\"")), pos()), st(" =x>"));
    // An <input> in a table is a parse error with or without its computed type (#410).
    onlyParseErrors(st("<table><input"),
        new Skeleton.Opt(Skeleton.OptKind.ATTRIBUTE, seq(st(" type=\""), attr("type"), st("\"")), pos()), st(">"));
  }

  @Test
  void loopBodiesAreStateNeutral() {
    // A row directly in a table: the first pass implies a <tbody>, the next ones don't.
    reject(Problem.UNBALANCED, st("<table>"), rep(seq(st("<tr><td>"), text(), st("</td></tr>"))), st("</table>"));
    List<Finding> f = check(seq(st("<table>"), rep(seq(st("<tr><td>"), text(), st("</td></tr>"))), st("</table>")));
    String m = f.stream().filter(x -> x.problem == Problem.UNBALANCED).map(VerifierMessages::message)
        .findFirst().orElse(f.toString());
    assertTrue(m.contains("this t:each leaves <tbody> open") && m.contains("write the <tbody> around the rows"), m);
    accept(st("<table><tbody>"), rep(seq(st("<tr><td>"), text(), st("</td></tr>"))), st("</tbody></table>"));
    // An open li closes the last one: the first pass differs from the next.
    reject(Problem.UNBALANCED, st("<ul>"), rep(seq(st("<li>"), text())), st("</ul>"));
    accept(st("<ul>"), rep(seq(st("<li>"), text(), st("</li>"))), st("</ul>"));
    reject(Problem.UNBALANCED, rep(seq(st("<div>"), text())));
    reject(Problem.TEXT_IN_TABLE, st("<table><tr>"), rep(seq(st("<td>a</td>"), text())));
    // The separator runs between passes: it is state-neutral too.
    reject(Problem.UNBALANCED, st("<div>"),
        new Skeleton.Rep(Skeleton.RepKind.EACH, seq(text()), seq(st("<table>")), pos()));
    accept(st("<ul>"), new Skeleton.Rep(Skeleton.RepKind.EACH, seq(st("<li>"), text(), st("</li>")), seq(st("\n  ")),
        pos()), st("</ul>"));
  }

  // ---- fragments ----

  @Test
  void callsAreCheckedInTheCallersState() {
    Map<String, Seq> frags = Map.of("cell", seq(st("<td>"), text(), st("</td>")));
    accept(frags, st("<table><tr>"), call("cell"), st("</tr></table>"));
    List<Finding> f = check(frags, seq(st("<table>"), call("cell"), call("cell")));
    // A td directly in a table implies tbody and tr: the text stays in the cell, but a cell without
    // a row is a parse error (#406), and the implied elements stay open (#682).
    assertEquals(List.of(Problem.PARSE_ERROR, Problem.UNBALANCED), problems(f));
    f = snippet(frags, seq(st("<div>"), call("cell")));
    // A td in a div is ignored, so the text stays text, but it is a parse error (#406), at the call.
    assertEquals(List.of(Problem.PARSE_ERROR), problems(f));
    assertTrue(VerifierMessages.message(f.get(0)).contains("in fragment t :: cell, inserted here, at t.html:1:"),
        VerifierMessages.message(f.get(0)));
    f = snippet(Map.of("x", seq(text())), seq(st("<table>"), call("x")));
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
    accept(frags, new Skeleton.Call(id("layout"), Map.of("head", seq(call("links")), "content", seq(st("<p>"), text(), st("</p>"))), pos()));
    // null: nothing in the head.
    accept(frags, new Skeleton.Call(id("layout"), Map.of("head", Seq.empty(), "content", seq(text())), pos()));
    // Unbound: any HTML, which isn't allowed in the head.
    reject(frags, Problem.RAW_NOT_FLOW, new Skeleton.Call(id("layout"), Map.of("content", seq(text())), pos()));
    // Bound content that doesn't fit where the layout puts it.
    reject(frags, Problem.TEXT_OUTSIDE_BODY, new Skeleton.Call(id("layout"), Map.of("head", seq(text()), "content", seq(text())), pos()));
  }

  @Test
  void recursionIsNeutralByInduction() {
    // Into the state its call started in: taken to be neutral, which that call checks.
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
    List<Finding> f = check(seq(st("<table>"), new Skeleton.Hole(Escaper.TEXT, null, null)));
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
    return new Skeleton.Hole(e, attribute, pos());
  }

  private Skeleton.RawInsert raw() {
    return new Skeleton.RawInsert(Skeleton.Source.SAFE_HTML, pos());
  }

  private Skeleton.RawInsert rawInline() {
    return new Skeleton.RawInsert(Skeleton.Source.SAFE_INLINE_HTML, pos());
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

  private static List<Finding> check(Seq body) {
    return check(Map.of(), body);
  }

  private static List<Finding> check(Map<String, Seq> fragments, Seq body) {
    Skeleton.TemplateSkeleton t = new Skeleton.TemplateSkeleton("t", body, fragments);
    Map<String, Skeleton.TemplateSkeleton> all = new LinkedHashMap<>();
    all.put("t", t);
    return new ContextVerifier(all).check(t);
  }

  /**
   * {@link #check}, without the partial's own balance: most cases here are snippets of a page, such
   * as a {@code <table>} left open around a hole ({@link #aPartialEndsInTheStateItStarts} checks it).
   */
  private static List<Finding> snippet(Map<String, Seq> fragments, Seq body) {
    return check(fragments, body).stream()
        .filter(x -> x.problem != Problem.UNBALANCED || !x.detail.startsWith("this partial"))
        .collect(Collectors.toList());
  }

  private static List<Problem> problems(List<Finding> f) {
    return f.stream().map(x -> x.problem).distinct().collect(Collectors.toList());
  }

  private static void accept(Node... nodes) {
    accept(Map.of(), nodes);
  }

  private static void accept(Map<String, Seq> fragments, Node... nodes) {
    List<Finding> f = snippet(fragments, seq(nodes));
    assertTrue(f.isEmpty(), seq(nodes) + " -> " + f.stream().map(VerifierMessages::message).collect(Collectors.toList()));
  }

  /** Rejected only by the parse-error rule and the exclusions (#406): every hole is accepted. */
  private static void onlyParseErrors(Node... nodes) {
    List<Finding> f = snippet(Map.of(), seq(nodes));
    assertTrue(!f.isEmpty() && f.stream().allMatch(x -> PARSER_REPORTED.contains(x.problem)),
        seq(nodes) + " -> " + f.stream().map(VerifierMessages::message).collect(Collectors.toList()));
  }

  private static final Set<Problem> PARSER_REPORTED =
      Set.of(Problem.PARSE_ERROR, Problem.FOREIGN_IN_TABLE, Problem.TABLE_IN_TEMPLATE, Problem.SELECT_CONTENT);

  private static void reject(Problem expected, Node... nodes) {
    reject(Map.of(), expected, nodes);
  }

  private static void reject(Map<String, Seq> fragments, Problem expected, Node... nodes) {
    List<Finding> f = check(fragments, seq(nodes));
    assertTrue(problems(f).contains(expected),
        seq(nodes) + " -> " + f.stream().map(VerifierMessages::message).collect(Collectors.toList()));
  }
}
