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
    accept(st("<title>"), text(), st("</title>"));
    accept(st("<textarea>"), text(), st("</textarea>"));
    accept(st("<table><tr><td>"), text(), st("</td></tr></table>"));
    accept(st("<table><caption>"), text());
    accept(st("<template>"), text());
    accept(st("<svg><text>"), text()); // foreign content appends every character as is
    accept(st("<iframe>"), text()); // raw text a browser never runs
    accept(st("<p><noscript>"), text()); // raw text with scripting on, text in the body without
    reject(Problem.TEXT_OUTSIDE_BODY, st("<noscript>"), text()); // in the head without scripting
    accept(st("<select><option>"), text());
    accept(st("<pre>"), text(), st("\nx</pre>"));
    accept(st("<table><h3>"), text()); // a foster-parented element: its text is not table text
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
    reject(Problem.TEXT_RECONSTRUCTS, st("<p><b>x</p>"), text()); // probe T22
  }

  @Test
  void rcdataEscapingOnlyInRcdata() {
    accept(st("<title>"), hole(Escaper.RCDATA, null), st("</title>"));
    reject(Problem.RCDATA_NOT_IN_RCDATA, st("<svg><title>"), hole(Escaper.RCDATA, null)); // #306 A7
    reject(Problem.RCDATA_NOT_IN_RCDATA, st("<p>"), hole(Escaper.RCDATA, null));
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
  }

  @Test
  void attributeValuesThatDecideTheParseAreRejected() {
    accept(st("<input type=\""), attr("type"), st("\">"));
    reject(Problem.ATTRIBUTE_VALUE_CHANGES_PARSE, st("<table><input type=\""), attr("type"));
    reject(Problem.ATTRIBUTE_VALUE_CHANGES_PARSE, st("<table><tr><input type=\""), attr("type"));
    reject(Problem.ATTRIBUTE_VALUE_CHANGES_PARSE, st("<math><annotation-xml encoding=\""), attr("encoding"));
  }

  @Test
  void jsonOnlyInTheScriptOfAnHtmlScript() {
    accept(st("<script>var a = "), json(), st(";</script>"));
    accept(st("<script>if (1<"), json(), st(") {}</script>")); // no JSON starts with '/' or '!'
    accept(st("<script>if (1<"), json(), st("/2) {}</script>")); // ... so '<' + value + '/' is no end tag
    reject(Problem.JSON_NOT_IN_SCRIPT, st("<svg><script>var a = "), json());
    reject(Problem.JSON_NOT_IN_SCRIPT, st("<p>"), json());
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
    reject(Problem.RAW_NOT_FLOW, st("<p><b>x</p>"), raw());
    // Classic <select> parsing only: the message names the engine.
    List<Finding> f = check(seq(st("<select>"), raw()));
    assertEquals(List.of(Problem.RAW_NOT_FLOW), problems(f));
    assertTrue(VerifierMessages.message(f.get(0)).contains("htmlparser-1.4.16 only: browsers differ here"),
        VerifierMessages.message(f.get(0)));
  }

  @Test
  void framesetAndTheNoahsArkClause() {
    reject(Problem.FRAMESET, st("<!DOCTYPE html><html><FRAMESET>"));
    accept(st("<b class=\""), attr("class"), st("\"><b class=\""), attr("class"), st("\">x"));
    reject(Problem.NOAHS_ARK, st("<b class=\""), attr("class"), st("\"><b><b>x"));
    reject(Problem.NOAHS_ARK, st("<b><b><b class=\""), attr("class"), st("\">x"));
    // Pushed and popped inside one static: still seen.
    reject(Problem.NOAHS_ARK, st("<b class=\""), attr("class"), st("\"><i><b><b><b></b></b></b></i>x"));
    accept(st("<b><b><b><b>x")); // static only: the parse doesn't depend on a value
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
    // A computed type is kept as a branch: whether it is there decides how an input parses in a table.
    reject(Problem.ATTRIBUTE_VALUE_CHANGES_PARSE, st("<table><input"),
        new Skeleton.Opt(Skeleton.OptKind.ATTRIBUTE, seq(st(" type=\""), attr("type"), st("\"")), pos()), st(">"));
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
  }

  // ---- fragments ----

  @Test
  void callsAreCheckedInTheCallersState() {
    Map<String, Seq> frags = Map.of("cell", seq(st("<td>"), text(), st("</td>")));
    accept(frags, st("<table><tr>"), call("cell"), st("</tr></table>"));
    List<Finding> f = check(frags, seq(st("<table>"), call("cell"), call("cell")));
    // Reported at the call site in the page; the fragment's hole is named.
    assertTrue(f.isEmpty(), f.toString()); // a td in a table implies tbody and tr
    f = check(frags, seq(st("<div>"), call("cell")));
    assertTrue(f.isEmpty(), f.toString()); // a td in a div is ignored: the text stays text
    f = check(Map.of("x", seq(text())), seq(st("<table>"), call("x")));
    assertEquals(List.of(Problem.TEXT_IN_TABLE), problems(f));
    assertEquals("t.html", f.get(0).at.file());
    assertTrue(VerifierMessages.message(f.get(0)).contains("in fragment t :: x at t.html:1:"), VerifierMessages.message(f.get(0)));
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

  private static void reject(Problem expected, Node... nodes) {
    reject(Map.of(), expected, nodes);
  }

  private static void reject(Map<String, Seq> fragments, Problem expected, Node... nodes) {
    List<Finding> f = check(fragments, seq(nodes));
    assertTrue(problems(f).contains(expected),
        seq(nodes) + " -> " + f.stream().map(VerifierMessages::message).collect(Collectors.toList()));
  }
}
