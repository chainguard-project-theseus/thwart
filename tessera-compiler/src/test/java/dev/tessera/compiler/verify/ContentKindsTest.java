package dev.tessera.compiler.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.compiler.verify.Skeleton.Node;
import dev.tessera.compiler.verify.Skeleton.Seq;
import dev.tessera.compiler.verify.engine.Parser;
import dev.tessera.compiler.verify.engine.ParserState;
import dev.tessera.compiler.verify.engine.Vnu;
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

  /** A partial whose markup closes an element it didn't open is a parse error in its fragment context. */
  @Test
  void strayEndTagsAreParseErrors() {
    Seq stray = seq(st("<span>"), text(), st("</span></div>"));
    assertEquals(List.of(Problem.PARSE_ERROR), problems(check(Map.of(), stray)));
    Seq li = seq(st("<span>x</span><li>"), text(), st("</li>"));
    assertEquals(List.of(), problems(check(Map.of(), li)));
    Seq form = seq(st("<div><form>"), text(), st("</form></div>"));
    assertEquals(List.of(), problems(check(Map.of(), form)));
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
  void templateModeContinuesFromTheSwitchedState() throws Exception {
    Parser p = Vnu.document(true);
    p.feed("<template>");
    ParserState entry = p.state();
    assertNull(ContentKinds.problem(entry));
    assertTrue(ContentKinds.switchesMode(entry));
    Parser text = p.fork();
    text.feed("plain &amp; text");
    assertEquals(entry, text.state());
    Parser flow = p.fork();
    flow.feed("<p>a <b>b</b></p><ul><li>x</li></ul>");
    Parser representative = p.fork();
    representative.feed(ContentKinds.REPRESENTATIVE);
    assertEquals(representative.state(), flow.state());

    // In template mode a <col> starts a column group, where text is table text; after a flow
    // element it is a stray tag. The verifier goes on from the state after a flow element, where
    // it is a parse error: rejected either way (#682).
    reject(Problem.PARSE_ERROR, st("<template>"), raw(), st("<col>"), text());
    accept(st("<template>"), raw(), st("<span>"), text());
  }

  // ---- HTML messages: PHRASING, sanitized at runtime (#677) ----

  @Test
  void htmlMessageIsPhrasingSoItFitsInAParagraphOrHeading() {
    String links = "m=Read <b>this</b> {0}, <a href=\"/x\">here</a>.<p>stripped</p>\n";
    message("<p><span t:utext=\"#{m}\">x</span> [[${v}]]</p>", links).assertSuccess();
    message("<h1 t:utext=\"#{m}\">x</h1>", links).assertSuccess();
    message("<button t:utext=\"#{m}\">x</button>", links).assertSuccess();
    message("<div><p>[(#{m})]</p></div>", links).assertSuccess();
    // Still not where the parser reads no body content.
    message("<table t:utext=\"#{m}\"></table>", links).assertError("page.html:2:", "[verifier:RAW_NOT_FLOW]");
  }

  @Test
  void htmlMessageWithALinkIsRejectedInALink() {
    message("<a href=\"/\"><span t:utext=\"#{m}\">x</span></a>", "m=<a href=\"/y\">y</a>\n")
        .assertError("page.html:2:", "close an open <a> around it", "[verifier:RAW_CLOSES_ELEMENT]");
    // A key with no link in any bundle file is sanitized without links, so it fits in one.
    message("<a href=\"/\"><span t:utext=\"#{m}\">x</span></a>", "m=One<br>two <b>{0}</b>\n").assertSuccess();
  }

  @Test
  void aLinkInAnyLocaleKeepsLinks() {
    Compilation c = Compilation.builder()
        .template("page.html", "<div " + NS + ">\n<a href=\"/\"><span t:utext=\"#{m}\">x</span></a></div>")
        .messages("m=<b>fine</b>\n")
        .localeMessages("de", "m=<A HREF=\"/de\">gut</A>\n")
        .verifierOnly()
        .compile();
    c.assertError("page.html:2:", "[verifier:RAW_CLOSES_ELEMENT]");
  }

  @Test
  void htmlMessageIsSanitizedAtRender() {
    Compilation c = Compilation.builder()
        .template("page.html", "<div " + NS + " t:params=\"String v\">\n<p t:utext=\"#{m(v)}\">x</p></div>")
        .messages("m=It''s <b>{0}</b>\n")
        .compile()
        .assertSuccess();
    assertTrue(c.skeleton("page").body().toString().contains("$raw{HTML_MESSAGE"), c.skeleton("page").body().toString());
    RenderContext ctx = new RenderContext(Locale.ROOT,
        (k, a) -> new MessageFormat("It''s <b>{0}</b>", Locale.ROOT).format(a), new DefaultLinks(""), Forms.NONE);
    assertEquals("<div>\n<p>It&#39;s <b>&lt;i&gt;</b></p></div>", c.render("page", Map.of("v", "<i>"), ctx));
    // Whatever the message source returns at runtime, only the policy's markup is written.
    RenderContext other = new RenderContext(Locale.ROOT,
        (k, a) -> "<p>other</p><a href=\"/x\">link</a><script>alert(1)</script>", new DefaultLinks(""), Forms.NONE);
    assertEquals("<div>\n<p>otherlink</p></div>", c.render("page", Map.of("v", "x"), other));
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

  private static List<Problem> problems(List<Finding> f) {
    return f.stream().map(x -> x.problem).distinct().collect(Collectors.toList());
  }

  private static String messages(List<Finding> f) {
    return f.stream().map(VerifierMessages::message).collect(Collectors.toList()).toString();
  }

  /** Accepted, but for the partial's own balance: the cases here are snippets of a page (#682). */
  private static void accept(Node... nodes) {
    List<Finding> f = check(Map.of(), seq(nodes)).stream()
        .filter(x -> x.problem != Problem.UNBALANCED || !x.detail.startsWith("this partial"))
        .collect(Collectors.toList());
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
