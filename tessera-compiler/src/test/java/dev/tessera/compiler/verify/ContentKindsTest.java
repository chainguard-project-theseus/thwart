package dev.tessera.compiler.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.compiler.verify.Skeleton.Node;
import dev.tessera.compiler.verify.Skeleton.Seq;
import dev.tessera.compiler.verify.engine.Engine;
import dev.tessera.compiler.verify.engine.Engines;
import dev.tessera.compiler.verify.engine.Parser;
import dev.tessera.compiler.verify.engine.ParserState;
import dev.tessera.runtime.DefaultLinks;
import dev.tessera.runtime.Forms;
import dev.tessera.runtime.RenderContext;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Content kinds as predicates on the parser state (#329): where the verifier accepts HTML known
 * only at render time ({@code SafeHtml}, {@code Fragment} values), one negative per clause of the
 * {@code FLOW} predicate, and HTML messages, whose markup is known and analysed as it is.
 */
class ContentKindsTest {
  private static final String NS = "xmlns:t=\"https://tessera.dev\"";

  // ---- FLOW: where it holds ----

  @Test
  void flowHoldsInBodyLikeContainers() {
    for (String prefix : new String[] {
        "<!DOCTYPE html><body>", "<div>", "<table><tr><td>", "<table><tr><th>", "<table><caption>", "<ul><li>", "<ol><li><div>",
        "<dl><dd>", "<dl><dt>", "<template>", "<div><template><div>", "<blockquote>", "<section>",
        // Not closed by FLOW content: a list item (its own li is inside its own list) and a form
        // (FLOW content has none).
        "<form>", "<form><div>", "<ul><li><span>",
        // Not the current node, or behind a scope boundary.
        "<h1><span>", "<p><table><tr><td>", "<a href=\"/\"><table><tr><td>", "<p><object>",
        "<svg><foreignObject><div>", "<math><mi><span>", "<p><button><span>x</span></button></p><div>",
        "<b><b>", "<b><i><u>",
    }) {
      accept(st(prefix), raw());
    }
  }

  // ---- FLOW: one negative per clause ----

  @Test
  void flowNeedsDataStateInHtmlContent() {
    reject(Problem.RAW_NOT_FLOW, st("<title>"), raw()); // RCDATA
    reject(Problem.RAW_NOT_FLOW, st("<script>"), raw()); // script data
    reject(Problem.RAW_NOT_FLOW, st("<div title=\""), raw()); // inside a tag
    reject(Problem.RAW_NOT_FLOW, st("<svg>"), raw()); // foreign content
    reject(Problem.RAW_NOT_FLOW, st("<math><mi>"), raw()); // a MathML text integration point
  }

  @Test
  void flowNeedsABodyLikeMode() {
    reject(Problem.RAW_NOT_FLOW, st("<!DOCTYPE html><html><head>"), raw());
    reject(Problem.RAW_NOT_FLOW, st("<!DOCTYPE html><html><body></body>"), raw());
    reject(Problem.RAW_NOT_FLOW, st("<table>"), raw());
    reject(Problem.RAW_NOT_FLOW, st("<table><tbody>"), raw());
    reject(Problem.RAW_NOT_FLOW, st("<table><tr>"), raw());
    reject(Problem.RAW_NOT_FLOW, st("<table><colgroup>"), raw());
  }

  /** Formatting elements waiting to be reopened are reached only by error recovery (#410). */
  @Test
  void formattingElementsToReopenAreAParseError() {
    reject(Problem.PARSE_ERROR, st("<p><b>x</p>"), raw());
  }

  @Test
  void flowContentClosesAnOpenParagraph() {
    rejectCloser("an open <p>", st("<p>"), raw());
    rejectCloser("an open <p>", st("<p><span>"), raw());
    rejectCloser("an open <p>", st("<p><b><i>"), raw());
    rejectCloser("an open <p>", st("<div><p><a href=\"/\"><em>"), raw());
  }

  @Test
  void flowContentClosesACurrentHeading() {
    for (int i = 1; i <= 6; i++) {
      rejectCloser("an open <h" + i + ">", st("<h" + i + ">"), raw());
    }
  }

  @Test
  void flowContentAdoptsAnOpenLink() {
    rejectCloser("an open <a>", st("<a href=\"/\">"), raw());
    rejectCloser("an open <a>", st("<a href=\"/\"><div>"), raw());
  }

  @Test
  void flowContentClosesButtonsNobrRubyAndSelects() {
    rejectCloser("an open <button>", st("<button>"), raw());
    rejectCloser("an open <button>", st("<button><span>"), raw());
    rejectCloser("an open <nobr>", st("<nobr>"), raw());
    rejectCloser("an open <ruby>", st("<ruby>"), raw());
    rejectCloser("an open <select>", st("<div><select><div>"), raw()); // customizable <select> parsing
  }

  /**
   * A fourth identical formatting element in the value drops the oldest from the list of active
   * formatting elements (the Noah's Ark clause), but that element stays open, and in an error-free
   * parse the list changes the tree only through entries that are no longer open (#444).
   */
  @Test
  void flowContentMayDropAFourthIdenticalFormattingEntry() {
    accept(st("<b><b><b>"), raw());
    accept(st("<em><i><em><em>"), raw());
    accept(st("<b><b><table><tr><td><b>"), raw());
  }

  /**
   * A table in the value resets the insertion mode from the stack: the standard walks down to the
   * cell, validator.nu stops at an SVG or MathML element and picks "in body". Exclusion (a)
   * rejects any {@code <svg>} or {@code <math>} in a table part, closed or not (#406, #410).
   */
  @Test
  void flowContentInACellBehindForeignContent() {
    reject(Problem.FOREIGN_IN_TABLE, st("<table><tr><td><svg><foreignObject><div>"), raw());
    reject(Problem.FOREIGN_IN_TABLE, st("<table><tr><th><math><mi><span>"), raw());
    reject(Problem.FOREIGN_IN_TABLE, st("<table><caption><svg><desc><div>"), raw());
    accept(st("<svg><foreignObject><table><tr><td><div>"), raw()); // the cell is above the foreign element
    reject(Problem.FOREIGN_IN_TABLE, st("<table><tr><td><svg></svg><div>"), raw());
    Compilation.builder()
        .template("page.html", "<table " + NS + " t:params=\"String v\"><tr><td><svg><foreignObject>"
            + "<div t:utext=\"${html.sanitizeRich(v)}\">x</div></foreignObject></svg></td></tr></table>")
        .verifierOnly()
        .compile()
        .assertError("page.html:1:", "<svg> inside <td>", "[verifier:FOREIGN_IN_TABLE]");
  }

  /**
   * A partial whose markup closes an element it didn't open, or writes a list item outside its
   * own list, isn't FLOW content: the first is a parse error in the partial's fragment context,
   * and the second closes the open list item of the {@code <ul><li>} probe (#410).
   */
  @Test
  void strayEndTagsAndListItemsOutsideTheirListAreNotFlow() {
    Seq stray = seq(st("<span>"), text(), st("</span></div>"));
    assertEquals(List.of(Problem.PARSE_ERROR), problems(check(Map.of(), stray)));
    assertTrue(whyNot(stray).contains("PARSE_ERROR"), whyNot(stray));
    Seq li = seq(st("<span>x</span><li>"), text(), st("</li>"));
    assertEquals(List.of(), problems(check(Map.of(), li)));
    assertTrue(whyNot(li).contains("after <ul><li>"), whyNot(li));
    Seq form = seq(st("<div><form>"), text(), st("</form></div>"));
    assertEquals(List.of(), problems(check(Map.of(), form)));
    assertTrue(whyNot(form).contains("<form>"), whyNot(form));
    Seq bb = seq(st("<i><em><i>"), text(), st("</i></em></i>"));
    assertTrue(whyNot(bb).contains("<i> inside another <i>"), whyNot(bb));
    assertNull(whyNot(seq(st("<i><em>"), text(), st("</em></i><i>x</i><template><form></form></template>"))));
  }

  @Test
  void eachSourceOfRuntimeHtmlIsChecked() {
    reject(Problem.RAW_CLOSES_ELEMENT, st("<p>"), new Skeleton.RawInsert(Skeleton.Source.FRAGMENT_VALUE, pos()));
    reject(Problem.RAW_NOT_FLOW, st("<table>"), new Skeleton.RawInsert(Skeleton.Source.FRAGMENT_VALUE, pos()));
    // An unbound Fragment parameter is a Fragment value.
    Map<String, Seq> frags = Map.of("f", seq(st("<p>"), new Skeleton.ParamInsert("content", pos()), st("</p>")));
    List<Finding> f = check(frags, seq(new Skeleton.Call(new Skeleton.FragmentId("t", "f"), Map.of(), pos())));
    assertEquals(List.of(Problem.RAW_CLOSES_ELEMENT), problems(f));
  }

  /**
   * In template mode, the first flow start tag switches the mode to "in body": the verifier goes on
   * from both states, so markup after the value is checked as a browser may read it.
   */
  @Test
  void templateModeContinuesFromBothStates() throws Exception {
    Engine e = Engines.CURRENT;
    Parser p = e.document(true);
    p.feed("<template>");
    ParserState entry = p.state();
    assertNull(ContentKinds.problem(ContentKinds.Kind.FLOW, entry));
    assertTrue(ContentKinds.switchesMode(ContentKinds.Kind.FLOW, entry));
    Parser text = p.fork();
    text.feed("plain &amp; text");
    assertEquals(entry, text.state());
    Parser flow = p.fork();
    flow.feed("<p>a <b>b</b></p><ul><li>x</li></ul>");
    Parser representative = p.fork();
    representative.feed(ContentKinds.REPRESENTATIVE);
    assertEquals(representative.state(), flow.state());

    // In template mode a <col> starts a column group, where text is table text; after a flow
    // element it is ignored. The first reading is the one the verifier must not lose.
    reject(Problem.TEXT_IN_TABLE, st("<template>"), raw(), st("<col>"), text());
    accept(st("<template>"), raw(), st("<span>"), text());
  }

  // ---- HTML messages: known markup, analysed as it is ----

  @Test
  void htmlMessageInAParagraphIsAnalysedAsMarkup() {
    // Inline markup fits in a <p>; a <p> in the message closes the <p> it is in.
    message("<p><span t:utext=\"#{m}\">x</span> [[${v}]]</p>", "m=Read <b>this</b> {0}, <a href=\"/x\">here</a>.\n")
        .assertSuccess();
    message("<p><span t:utext=\"#{m}\">x</span></p>", "m=<p>Read this</p>\n")
        .assertError("page.html:2:", "closes an open <p> around it", "[verifier:MESSAGE_CHANGES_STATE]");
    message("<div t:utext=\"#{m}\">x</div>", "m=<p>Read this</p>\n").assertSuccess();
  }

  @Test
  void htmlMessageThatLeavesAnElementOpenIsRejected() {
    message("<div><span t:utext=\"#{m}\">x</span>[[${v}]]</div>", "m=a<p>b\n")
        .assertError("page.html:2:", "leaves <p> open", "[verifier:MESSAGE_CHANGES_STATE]");
    message("<a href=\"/\"><span t:utext=\"#{m}\">x</span></a>", "m=<a href=\"/y\">y</a>\n")
        .assertError("page.html:2:", "[verifier:MESSAGE_CHANGES_STATE]");
  }

  @Test
  void everyLocaleOfAMessageIsChecked() {
    Compilation c = Compilation.builder()
        .template("page.html", "<div " + NS + " t:params=\"String v\">\n<p><span t:utext=\"#{m}\">x</span></p></div>")
        .messages("m=<b>fine</b>\n")
        .localeMessages("de", "m=<p>nicht</p>\n")
        .verifierOnly()
        .compile();
    c.assertError("page.html:2:", "[verifier:MESSAGE_CHANGES_STATE]");
  }

  @Test
  void htmlMessageTextIsAHole() {
    // The argument, and a message that isn't one of those the build saw (written escaped), are
    // text holes: in a table they are table text.
    message("<table><tr><td><span t:utext=\"#{m}\">x</span></td></tr></table>", "m=a {0} <b>b</b>\n").assertSuccess();
    message("<table><tr t:utext=\"#{m}\"></tr></table>", "m=plain\n")
        .assertError("page.html:2:", "[verifier:TEXT_IN_TABLE]");
    message("<div><span t:utext=\"#{m}\">x</span></div>", "m=a &{0}\n")
        .assertError("page.html:2:", "[verifier:TEXT_NOT_IN_TEXT]");
  }

  @Test
  void htmlMessageSkeletonIsItsMarkup() {
    Compilation c = Compilation.builder()
        .template("page.html", "<div " + NS + " t:params=\"String v\">\n<div t:utext=\"#{m(v)}\">x</div></div>")
        .messages("m=It''s <b>{0}</b>\n")
        .compile()
        .assertSuccess();
    String skeleton = c.skeleton("page").body().toString();
    assertTrue(skeleton.contains("$raw{HTML_MESSAGE alt(\"It''s <b>{0}</b>\" | \"It's <b>\" ${TEXT"), skeleton);
    // The harness checks that every render is in the skeleton's language.
    RenderContext ctx = new RenderContext(Locale.ROOT,
        (k, a) -> a.length == 0 ? "It''s <b>{0}</b>" : new MessageFormat("It''s <b>{0}</b>", Locale.ROOT).format(a),
        new DefaultLinks(""), Forms.NONE);
    assertEquals("<div>\n<div>It's <b>&lt;i&gt;</b></div></div>", c.render("page", Map.of("v", "<i>"), ctx));
    // A message the build didn't see is written escaped: the text hole.
    RenderContext other = new RenderContext(Locale.ROOT, (k, a) -> "<p>other</p>", new DefaultLinks(""), Forms.NONE);
    assertEquals("<div>\n<div>&lt;p&gt;other&lt;/p&gt;</div></div>", c.render("page", Map.of("v", "x"), other));
  }

  // ---- helpers ----

  private int column = 1;

  private static Compilation message(String body, String properties) {
    return Compilation.builder()
        .template("page.html", "<div " + NS + " t:params=\"String v\">\n" + body + "</div>")
        .messages(properties)
        .verifierOnly()
        .compile();
  }

  private SourcePos pos() {
    return new SourcePos("t.html", 1, column++);
  }

  private Skeleton.Static st(String text) {
    return new Skeleton.Static(text, pos());
  }

  private Skeleton.Hole text() {
    return new Skeleton.Hole(Skeleton.Escaper.TEXT, null, null, pos());
  }

  private Skeleton.RawInsert raw() {
    return new Skeleton.RawInsert(Skeleton.Source.SAFE_HTML, pos());
  }

  private static Seq seq(Node... nodes) {
    return new Seq(List.of(nodes));
  }

  private static List<Finding> check(Map<String, Seq> fragments, Seq body) {
    Skeleton.TemplateSkeleton t = new Skeleton.TemplateSkeleton("t", body, fragments);
    Map<String, Skeleton.TemplateSkeleton> all = new LinkedHashMap<>();
    all.put("t", t);
    return new ContextVerifier(all).check(t);
  }

  private static String whyNot(Seq body) {
    Skeleton.TemplateSkeleton t = new Skeleton.TemplateSkeleton("t", body, Map.of());
    Map<String, Skeleton.TemplateSkeleton> all = new LinkedHashMap<>();
    all.put("t", t);
    return new ContextVerifier(all).whyNotFlowPartial(t);
  }

  private static List<Problem> problems(List<Finding> f) {
    return f.stream().map(x -> x.problem).distinct().collect(Collectors.toList());
  }

  private static String messages(List<Finding> f) {
    return f.stream().map(VerifierMessages::message).collect(Collectors.toList()).toString();
  }

  private static void accept(Node... nodes) {
    List<Finding> f = check(Map.of(), seq(nodes));
    assertTrue(f.isEmpty(), seq(nodes) + " -> " + messages(f));
  }

  private static void reject(Problem expected, Node... nodes) {
    List<Finding> f = check(Map.of(), seq(nodes));
    assertTrue(problems(f).contains(expected), seq(nodes) + " -> " + messages(f));
  }

  private static void rejectCloser(String closer, Node... nodes) {
    List<Finding> f = check(Map.of(), seq(nodes));
    assertTrue(f.stream().anyMatch(x -> x.problem == Problem.RAW_CLOSES_ELEMENT && closer.equals(x.detail)),
        seq(nodes) + " -> " + messages(f));
  }
}
