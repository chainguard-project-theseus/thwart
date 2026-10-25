package dev.tessera.compiler.verify.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The engine API over both shaded parsers (#365). */
class EnginesTest {

  private static ParserState doc(Engine e, String html) throws EngineException {
    return doc(e, html, false);
  }

  private static ParserState doc(Engine e, String html, boolean scripting) throws EngineException {
    Parser p = e.document(scripting);
    p.feed(html);
    return p.state();
  }

  @Test
  void names() {
    assertEquals("htmlparser@validator-26.10.12", Engines.CURRENT.name());
    assertEquals("htmlparser-1.4.16", Engines.CLASSIC.name());
    assertEquals(List.of(Engines.CURRENT, Engines.CLASSIC), Engines.all());
  }

  /** Classic <select> parsing makes the script live; customizable <select> keeps <title> RCDATA. */
  @Test
  void selectTitleScriptSplitsTheEngines() throws EngineException {
    for (boolean scripting : new boolean[] {true, false}) {
      String html = "<!DOCTYPE html><select><title><script>";
      ParserState classic = doc(Engines.CLASSIC, html, scripting);
      assertEquals(TokenizerState.SCRIPT_DATA, classic.tokenizer());
      assertEquals("script", classic.endTagExpectation());
      assertEquals(new OpenElement(OpenElement.HTML, "script", false), classic.currentNode());
      assertEquals(InsertionMode.IN_SELECT, classic.originalMode());

      ParserState current = doc(Engines.CURRENT, html, scripting);
      assertEquals(TokenizerState.RCDATA, current.tokenizer());
      assertEquals("title", current.endTagExpectation());
      assertEquals("title", current.currentNode().localName());
    }
  }

  /** The state at a hole: the template's text up to the hole, fed in one chunk. */
  @Test
  void stateAtHoleOffsets() throws EngineException {
    for (Engine e : Engines.all()) {
      ParserState s = doc(e, "<!DOCTYPE html><p>Hello, ");
      assertEquals(TokenizerState.DATA, s.tokenizer(), e.name());
      assertEquals(InsertionMode.IN_BODY, s.mode());
      assertTrue(s.currentNode().isHtml("p"));
      assertEquals("", s.pendingInput());
      assertNull(s.attributeName());

      s = doc(e, "<!DOCTYPE html><body><a href=\"/x?q=");
      assertEquals(TokenizerState.ATTRIBUTE_VALUE_DOUBLE_QUOTED, s.tokenizer());
      assertEquals("href", s.attributeName());
      assertFalse(s.pendingInput().isEmpty());
      assertEquals(InsertionMode.IN_BODY, s.mode());
      assertTrue(s.currentNode().isHtml("body"), "the <a> isn't emitted yet");

      assertEquals("title", doc(e, "<div TITLE=\"").attributeName());
      assertEquals(TokenizerState.ATTRIBUTE_VALUE_SINGLE_QUOTED, doc(e, "<div title='").tokenizer());
      assertEquals(TokenizerState.ATTRIBUTE_VALUE_UNQUOTED, doc(e, "<div title=x").tokenizer());
      assertEquals("title", doc(e, "<div title=").attributeName());
      assertEquals(TokenizerState.BEFORE_ATTRIBUTE_VALUE, doc(e, "<div title=").tokenizer());
      ParserState name = doc(e, "<div id=\"a\" tit");
      assertEquals(TokenizerState.ATTRIBUTE_NAME, name.tokenizer());
      assertNull(name.attributeName(), "the name isn't complete");
      ParserState duplicate = doc(e, "<div title=\"a\" title=\"");
      assertEquals(TokenizerState.ATTRIBUTE_VALUE_DOUBLE_QUOTED, duplicate.tokenizer());
      assertNull(duplicate.attributeName(), "a duplicate attribute is dropped");

      s = doc(e, "<!DOCTYPE html><textarea>");
      assertEquals(TokenizerState.RCDATA, s.tokenizer());
      assertEquals("textarea", s.endTagExpectation());
      assertTrue(s.dropsNextLineFeed());
      assertEquals(TokenizerState.RAWTEXT, doc(e, "<style>").tokenizer());
      assertEquals(TokenizerState.PLAINTEXT, doc(e, "<plaintext>").tokenizer());
      assertEquals(TokenizerState.SCRIPT_DATA, doc(e, "<script>var a = ").tokenizer());
      assertEquals(TokenizerState.SCRIPT_DATA_DOUBLE_ESCAPED, doc(e, "<script><!--<script>").tokenizer());
      assertEquals(TokenizerState.COMMENT, doc(e, "<p><!-- x").tokenizer());
      assertEquals(TokenizerState.CDATA_SECTION, doc(e, "<svg><![CDATA[x").tokenizer());
      assertEquals(TokenizerState.BOGUS_COMMENT, doc(e, "<p><![CDATA[").tokenizer(), "CDATA is a bogus comment in HTML");

      ParserState ref = doc(e, "<p>a &am");
      assertNotEquals(TokenizerState.DATA, ref.tokenizer());
      assertFalse(ref.pendingInput().isEmpty());
    }
  }

  @Test
  void treeState() throws EngineException {
    for (Engine e : Engines.all()) {
      ParserState s = doc(e, "<svg><foreignObject>");
      assertEquals(new OpenElement(OpenElement.SVG, "foreignObject", true), s.currentNode(), e.name());
      assertTrue(doc(e, "<math><annotation-xml encoding=\"text/html\">").currentNode().htmlIntegrationPoint());
      assertFalse(doc(e, "<math><annotation-xml encoding=\"x\">").currentNode().htmlIntegrationPoint());
      assertEquals(OpenElement.MATHML, doc(e, "<math><mi>").currentNode().namespace());

      assertTrue(doc(e, "<!DOCTYPE html><table>x").pendingTableText());
      assertFalse(doc(e, "<!DOCTYPE html><table> \n").pendingTableText());
      assertTrue(doc(e, "<pre>").dropsNextLineFeed());
      assertFalse(doc(e, "<pre>\n").dropsNextLineFeed());

      s = doc(e, "<!DOCTYPE html><b><i>");
      assertEquals(List.of(2, 3), s.activeFormatting());
      assertFalse(s.reconstructionPending());
      s = doc(e, "<!DOCTYPE html><p><b></p>");
      assertEquals(List.of(ParserState.NOT_ON_STACK), s.activeFormatting());
      assertTrue(s.reconstructionPending());
      assertEquals("b", s.activeFormattingElements().get(0).localName());
      s = doc(e, "<!DOCTYPE html><p><b></p><table><tr><td>");
      assertEquals(List.of(ParserState.NOT_ON_STACK, ParserState.MARKER), s.activeFormatting());
      assertFalse(s.reconstructionPending(), "the marker stops reconstruction");
      // Attributes of formatting entries are part of the state: reconstruction clones them.
      assertNotEquals(doc(e, "<p><b class=x></p>"), doc(e, "<p><b class=y></p>"));

      assertTrue(doc(e, "<form>").formPointer());
      assertTrue(doc(e, "<p>").headPointer());
      assertTrue(doc(e, "<p>").quirks());
      assertFalse(doc(e, "<!DOCTYPE html><p>").quirks());
      assertTrue(doc(e, "<!DOCTYPE html><p>").framesetOk());
      assertFalse(doc(e, "<!DOCTYPE html><p>x").framesetOk());
      assertEquals(List.of(InsertionMode.IN_TEMPLATE, InsertionMode.IN_TEMPLATE), doc(e, "<template><template>").templateModes());
      assertEquals(List.of(InsertionMode.IN_TABLE_BODY), doc(e, "<template><tr>").templateModes());
    }
  }

  /** Each insertion mode an engine has is reached, so the per-engine mode tables are right. */
  @Test
  void insertionModes() throws EngineException {
    String[][] cases = {
      {"", "INITIAL"},
      {"<!DOCTYPE html>", "BEFORE_HTML"},
      {"<!DOCTYPE html><html>", "BEFORE_HEAD"},
      {"<!DOCTYPE html><head>", "IN_HEAD"},
      {"<!DOCTYPE html><head><noscript>", "IN_HEAD_NOSCRIPT"},
      {"<!DOCTYPE html><head></head>", "AFTER_HEAD"},
      {"<!DOCTYPE html><body>", "IN_BODY"},
      {"<!DOCTYPE html><body><p>x", "IN_BODY"},
      {"<!DOCTYPE html><title>", "TEXT"},
      {"<!DOCTYPE html><table>", "IN_TABLE"},
      {"<!DOCTYPE html><table><caption>", "IN_CAPTION"},
      {"<!DOCTYPE html><table><colgroup>", "IN_COLUMN_GROUP"},
      {"<!DOCTYPE html><table><tbody>", "IN_TABLE_BODY"},
      {"<!DOCTYPE html><table><tr>", "IN_ROW"},
      {"<!DOCTYPE html><table><tr><td>", "IN_CELL"},
      {"<!DOCTYPE html><template>", "IN_TEMPLATE"},
      {"<!DOCTYPE html><body></body>", "AFTER_BODY"},
      {"<!DOCTYPE html><frameset>", "IN_FRAMESET"},
      {"<!DOCTYPE html><frameset></frameset>", "AFTER_FRAMESET"},
      {"<!DOCTYPE html><body></body></html>", "AFTER_AFTER_BODY"},
      {"<!DOCTYPE html><frameset></frameset></html>", "AFTER_AFTER_FRAMESET"},
    };
    for (Engine e : Engines.all()) {
      for (String[] c : cases) {
        assertEquals(InsertionMode.valueOf(c[1]), doc(e, c[0]).mode(), e.name() + " " + c[0]);
      }
    }
    assertEquals(InsertionMode.IN_HEAD, doc(Engines.CURRENT, "<!DOCTYPE html><title>").originalMode());
    assertEquals(InsertionMode.IN_SELECT, doc(Engines.CLASSIC, "<!DOCTYPE html><select>").mode());
    assertEquals(InsertionMode.IN_SELECT_IN_TABLE, doc(Engines.CLASSIC, "<!DOCTYPE html><table><tr><td><select>").mode());
    assertEquals(InsertionMode.IN_BODY, doc(Engines.CURRENT, "<!DOCTYPE html><select>").mode());
    assertEquals(InsertionMode.IN_CELL, doc(Engines.CURRENT, "<!DOCTYPE html><table><tr><td><select>").mode());
  }

  @Test
  void bothScriptingModes() throws EngineException {
    for (Engine e : Engines.all()) {
      ParserState on = doc(e, "<!DOCTYPE html><body><noscript>", true);
      ParserState off = doc(e, "<!DOCTYPE html><body><noscript>", false);
      assertTrue(on.scripting());
      assertFalse(off.scripting());
      assertEquals(TokenizerState.RAWTEXT, on.tokenizer(), e.name());
      assertEquals(TokenizerState.DATA, off.tokenizer(), e.name());
      assertNotEquals(on, off);
      assertEquals(InsertionMode.IN_HEAD_NOSCRIPT, doc(e, "<!DOCTYPE html><head><noscript>", false).mode());
      assertEquals(InsertionMode.TEXT, doc(e, "<!DOCTYPE html><head><noscript>", true).mode());
    }
  }

  @Test
  void fragments() throws EngineException {
    for (Engine e : Engines.all()) {
      Parser p = e.fragment(OpenElement.HTML, "tr", false);
      p.feed("<td>");
      assertEquals(InsertionMode.IN_CELL, p.state().mode(), e.name());
      assertEquals(TokenizerState.RCDATA, e.fragment(OpenElement.HTML, "textarea", false).state().tokenizer());
      assertEquals(TokenizerState.RAWTEXT, e.fragment(OpenElement.HTML, "noscript", true).state().tokenizer());
      assertEquals(TokenizerState.DATA, e.fragment(OpenElement.HTML, "noscript", false).state().tokenizer());
      p = e.fragment(OpenElement.SVG, "svg", false);
      p.feed("<circle>");
      assertEquals(new OpenElement(OpenElement.SVG, "circle", false), p.state().currentNode());
      assertThrows(IllegalArgumentException.class, () -> e.fragment("urn:x", "div", false));
    }
    assertEquals(InsertionMode.IN_SELECT, Engines.CLASSIC.fragment(OpenElement.HTML, "select", false).state().mode());
  }

  @Test
  void forkIsIndependent() throws EngineException {
    for (Engine e : Engines.all()) {
      Parser p = e.document(true);
      p.feed("<!DOCTYPE html><div><a href=\"/x");
      ParserState before = p.state();
      Parser q = p.fork();
      q.feed("\">link</a><table>");
      assertEquals(before, p.state(), e.name());
      assertEquals(InsertionMode.IN_TABLE, q.state().mode());
      p.feed("\">");
      assertEquals(TokenizerState.DATA, p.state().tokenizer());
      assertTrue(p.state().currentNode().isHtml("a"));
      assertEquals(before, doc(e, "<!DOCTYPE html><div><a href=\"/x", true), "same input, same state");
    }
  }

  // Inputs for the split and fork properties: each ends between tokens, so its state has no
  // pending input and doesn't depend on where it was split.
  private static final String[] INPUTS = {
    "<!DOCTYPE html><html><head><title>T &amp; x</title></head><body><p class=\"a\">Hi</p></body>",
    "<!DOCTYPE html>\r\n<pre>\r\nline\r\n</pre>\r\n<p>a\rb\r\n",
    "<!doctype html><p title=\"a&amp;b&notin c &#x41;&#65\">&lt;&notit; &copy &#128512;</p>",
    "<!DOCTYPE html><div>😀 x 😁</div>",
    "<!DOCTYPE html><table><tr><td>a</td>b<td>c</table><b><p>x</b>y</p>",
    "<!DOCTYPE html><script>if (a < b) { x = '<!-- <script>'; }</script><p>",
    "<!DOCTYPE html><svg><foreignObject><p>x</p></foreignObject><circle/></svg><math><mi>x</mi>"
        + "<annotation-xml encoding=\"text/html\"><div></div></annotation-xml></math>",
    "<!DOCTYPE html><select><option>a<option selected>b</select><textarea>\n</textarea x>",
    "<!DOCTYPE html><template><tr><td>x</td></tr></template><!-- c -- --><p>",
    "<p><b><i><a href=x>1<p>2</a>3</i>",
    "<!DOCTYPE html><form><input type=hidden name=x value='y'><button>b</button></form><noscript><p>n</p></noscript>",
  };

  /** The state is the same however the input is split: inside tags, references, CRLF and surrogates. */
  @Test
  void stateDoesNotDependOnSplitPoints() throws EngineException {
    Random random = new Random(365);
    for (Engine e : Engines.all()) {
      for (boolean scripting : new boolean[] {true, false}) {
        for (String input : INPUTS) {
          ParserState whole = doc(e, input, scripting);
          assertEquals("", whole.pendingInput(), input);
          // Every single split point, then random multi-way splits.
          for (int i = 0; i <= input.length(); i++) {
            Parser p = e.document(scripting);
            p.feed(input.substring(0, i));
            p.state();
            p.feed(input.substring(i));
            assertEquals(whole, p.state(), e.name() + " split at " + i + ": " + input);
          }
          for (int trial = 0; trial < 20; trial++) {
            Parser p = e.document(scripting);
            for (String chunk : randomChunks(input, random)) {
              p.feed(chunk);
              if (random.nextBoolean()) {
                p.state();
              }
            }
            assertEquals(whole, p.state(), e.name() + " " + input);
          }
        }
      }
    }
  }

  /** A fork made anywhere, even mid-tag, continues exactly like the parser it was forked from. */
  @Test
  void forkAnywhereContinuesExactly() throws EngineException {
    for (Engine e : Engines.all()) {
      for (String input : INPUTS) {
        ParserState whole = doc(e, input, true);
        for (int i = 0; i <= input.length(); i++) {
          Parser p = e.document(true);
          p.feed(input.substring(0, i));
          ParserState at = p.state();
          Parser q = p.fork();
          assertEquals(at, q.state());
          q.feed(input.substring(i));
          assertEquals(whole, q.state(), e.name() + " fork at " + i + ": " + input);
          assertEquals(at, p.state(), "the original is unchanged");
        }
      }
    }
  }

  private static List<String> randomChunks(String s, Random random) {
    List<String> out = new ArrayList<>();
    int i = 0;
    while (i < s.length()) {
      int j = Math.min(s.length(), i + 1 + random.nextInt(8));
      out.add(s.substring(i, j));
      i = j;
    }
    return out;
  }

  /**
   * htmlparser 1.4.16's own assert ("strBufLen not reset after previous use!") fires under -ea
   * after an unmatched named character reference followed by an end tag. The engines run with
   * their asserts off, as shipped, so the parse goes on (and is right: the next line resets the
   * buffer).
   */
  @Test
  void engineAssertsAreOffEvenUnderEa() throws EngineException {
    boolean ea = false;
    assert ea = true;
    assertTrue(ea, "surefire runs tests with -ea");
    for (Engine e : Engines.all()) {
      ParserState s = doc(e, "<!DOCTYPE html><p>&drop</span>x");
      assertEquals(TokenizerState.DATA, s.tokenizer(), e.name());
      assertTrue(s.currentNode().isHtml("p"));
    }
    assertFalse(dev.tessera.compiler.internal.html5.classic.impl.Tokenizer.class.desiredAssertionStatus());
    assertFalse(dev.tessera.compiler.internal.html5.current.impl.TreeBuilder.class.desiredAssertionStatus());
  }

  /** Anything an engine throws is a checked EngineException, and the parser stays broken. */
  @Test
  void engineFailuresAreCheckedAndFinal() throws EngineException {
    for (Throwable t : new Throwable[] {new IllegalStateException("boom"), new AssertionError("boom")}) {
      Parser p = new VnuParser((VnuEngine) Engines.CURRENT, new FailingRaw(t, "DATA"));
      EngineException first = assertThrows(EngineException.class, () -> p.feed("<p>"));
      assertTrue(first.getMessage().startsWith("htmlparser@validator-26.10.12: parsing failed"), first.getMessage());
      assertTrue(first.getCause() == t);
      assertThrows(EngineException.class, p::state);
      assertThrows(EngineException.class, () -> p.feed("x"));
      assertThrows(EngineException.class, p::fork);
    }
    // A state Tessera has no name for fails closed too.
    Parser unknown = new VnuParser((VnuEngine) Engines.CLASSIC, new FailingRaw(null, "SOMETHING_NEW"));
    EngineException e = assertThrows(EngineException.class, unknown::state);
    assertTrue(e.getMessage().contains("unknown tokenizer state"), e.getMessage());
  }

  /** A raw parser that fails on feed, or reports a tokenizer state name. */
  private static final class FailingRaw implements RawParser {
    private final Throwable failure;
    private final String state;

    FailingRaw(Throwable failure, String state) {
      this.failure = failure;
      this.state = state;
    }

    @Override
    public void feed(char[] chars, int start, int end) throws Exception {
      if (failure instanceof Error) {
        throw (Error) failure;
      }
      throw (Exception) failure;
    }

    @Override
    public RawParser fork() {
      return this;
    }

    @Override
    public boolean scripting() {
      return false;
    }

    @Override
    public String tokenizerState() {
      return state;
    }

    @Override
    public String attributeName() {
      return null;
    }

    @Override
    public boolean betweenTokens() {
      return true;
    }

    @Override
    public String endTagExpectation() {
      return null;
    }

    @Override
    public boolean lastCR() {
      return false;
    }

    @Override
    public RawTree tree() {
      RawTree t = new RawTree();
      t.stackNamespaces = new String[0];
      t.stackNames = new String[0];
      t.stackHtmlIntegrationPoints = new boolean[0];
      t.formattingStackIndex = new int[0];
      t.formattingNamespaces = new String[0];
      t.formattingNames = new String[0];
      t.formattingAttributes = new String[0][];
      t.templateModes = new String[0];
      t.mode = "INITIAL";
      t.originalMode = "INITIAL";
      return t;
    }
  }
}
