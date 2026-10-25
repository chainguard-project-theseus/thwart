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

/** The engine API over the shaded parser (#365). */
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
  void names() throws EngineException {
    assertEquals("htmlparser@validator-26.10.12", Engines.CURRENT.name());
    assertEquals("htmlparser@validator-26.10.12", doc(Engines.CURRENT, "<p>").engine());
  }

  /** Customizable <select> parsing keeps <title> RCDATA in a select. */
  @Test
  void selectTitleIsRcdata() throws EngineException {
    for (boolean scripting : new boolean[] {true, false}) {
      String html = "<!DOCTYPE html><select><title><script>";
      ParserState current = doc(Engines.CURRENT, html, scripting);
      assertEquals(TokenizerState.RCDATA, current.tokenizer());
      assertEquals("title", current.endTagExpectation());
      assertEquals("title", current.currentNode().localName());
    }
  }

  /** The state at a hole: the template's text up to the hole, fed in one chunk. */
  @Test
  void stateAtHoleOffsets() throws EngineException {
    Engine e = Engines.CURRENT;
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

  @Test
  void treeState() throws EngineException {
    Engine e = Engines.CURRENT;
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
    Engine e = Engines.CURRENT;
    for (String[] c : cases) {
      assertEquals(InsertionMode.valueOf(c[1]), doc(e, c[0]).mode(), e.name() + " " + c[0]);
    }
    assertEquals(InsertionMode.IN_HEAD, doc(Engines.CURRENT, "<!DOCTYPE html><title>").originalMode());
    assertEquals(InsertionMode.IN_BODY, doc(Engines.CURRENT, "<!DOCTYPE html><select>").mode());
    assertEquals(InsertionMode.IN_CELL, doc(Engines.CURRENT, "<!DOCTYPE html><table><tr><td><select>").mode());
  }

  @Test
  void bothScriptingModes() throws EngineException {
    Engine e = Engines.CURRENT;
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

  @Test
  void fragments() throws EngineException {
    Engine e = Engines.CURRENT;
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

  /**
   * The fragment context is part of the state: after {@code </table>} the parser resets its
   * insertion mode from the context (in caption in a caption, in body in a div), and current
   * upstream ignores {@code <select>} in a select context.
   */
  @Test
  void contextIsPartOfTheSignature() throws EngineException {
    Engine e = Engines.CURRENT;
    Parser div = e.fragment(OpenElement.HTML, "div", false);
    Parser caption = e.fragment(OpenElement.HTML, "caption", false);
    div.feed("<table><tr><td>");
    caption.feed("<table><tr><td>");
    assertEquals(div.state().stack(), caption.state().stack(), e.name());
    assertEquals(div.state().mode(), caption.state().mode());
    assertNotEquals(div.state(), caption.state());
    div.feed("</table>");
    caption.feed("</table>");
    assertEquals(InsertionMode.IN_BODY, div.state().mode());
    assertEquals(InsertionMode.IN_CAPTION, caption.state().mode());
    assertEquals(new OpenElement(OpenElement.HTML, "caption", false), caption.state().fragmentContext());
    assertNull(e.document(false).state().fragmentContext());
    assertNotEquals(e.document(false).state(), e.fragment(OpenElement.HTML, "html", false).state());

    div = e.fragment(OpenElement.HTML, "div", false);
    Parser select = e.fragment(OpenElement.HTML, "select", false);
    assertEquals(div.state().mode(), select.state().mode());
    assertNotEquals(div.state(), select.state());
    div.feed("<select>");
    select.feed("<select>");
    assertEquals(2, div.state().stack().size());
    assertEquals(1, select.state().stack().size(), "a select context ignores <select>");
  }

  /**
   * A buffer that ends right after an unmatched named reference ({@code &x}) loses nothing: fed as
   * {@code "&x<" + "script>"} the engine still sees the script. The engine is fed each chunk as it
   * comes, mid-token too (#408: htmlparser 1.4.16 dropped that character, and chunks were replayed
   * from the last point between tokens for it).
   */
  @Test
  void bufferEndsAfterAnUnmatchedReferenceLoseNothing() throws EngineException {
    String[][] cases = {
      {"<!DOCTYPE html><body>&x<", "script>"},
      {"<!DOCTYPE html><body>&x", "<", "script>"},
      {"<!DOCTYPE html><title>&drop<", "/title><script>"},
      {"<!DOCTYPE html><textarea>&x<", "/textarea><script>"},
      {"<!DOCTYPE html><p title=\"&x\"", "><script>"},
      {"<!DOCTYPE html><p title=&x ", "id=\"", "\"><script>"},
    };
    Engine e = Engines.CURRENT;
    for (String[] chunks : cases) {
      Parser p = e.document(true);
      for (String c : chunks) {
        p.feed(c);
        p.state();
      }
      ParserState s = p.state();
      assertEquals(TokenizerState.SCRIPT_DATA, s.tokenizer(), e.name() + " " + String.join("|", chunks));
      assertEquals(doc(e, String.join("", chunks), true), s);
    }
  }

  @Test
  void forkIsIndependent() throws EngineException {
    Engine e = Engines.CURRENT;
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
    "<!DOCTYPE html><p title=\"a&x\" id=&y class='&z'>&q<b>&drop</b><title>&x</title>&x<script>a</script>",
    // Parse errors (#406), several inside a tag or reference that a split can cut.
    "<!DOCTYPE html><p id=a id=b class=\"x\"y><a href=x><a>&#0;&#x110000;&#128;&notanentity;</p >",
    "<!DOCTYPE html>\r\n<table>x<tr><td><form><form></td><div></table></p><p\r\n  title=\"\r\n\"</br>",
    "<!DOCTYPE html><svg><desc><b></desc></svg><math><mi><p></math><!-- x --!><!x><?pi><x/ y>",
    "<!DOCTYPE html><select><div><option><svg></select><template><td></template><body><head><html>",
  };

  /** The state is the same however the input is split: inside tags, references, CRLF and surrogates. */
  @Test
  void stateDoesNotDependOnSplitPoints() throws EngineException {
    Random random = new Random(365);
    Engine e = Engines.CURRENT;
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

  /**
   * The parse errors and inserted elements a listener hears (#406) are the same however the input
   * is split, in the same order, each once, with the stack as it is then. Offsets agree to within
   * one character: characters the tokenizer flushes at the end of a buffer are reported at the
   * character before the last (vnu's {@code flushChars}), so what text processing reports there,
   * such as reconstructed formatting elements, is one character early.
   */
  @Test
  void eventsDoNotDependOnSplitPoints() throws EngineException {
    Engine e = Engines.CURRENT;
    int errors = 0;
    for (boolean scripting : new boolean[] {true, false}) {
      for (String input : INPUTS) {
        List<String> whole = events(e, scripting, input);
        errors += whole.stream().filter(x -> x.contains(" error ")).count();
        for (int i = 0; i <= input.length(); i++) {
          assertSameEvents(whole, events(e, scripting, input.substring(0, i), input.substring(i)),
              "split at " + i + ": " + input);
        }
        for (int i = 0; i + 1 < input.length(); i++) {
          assertSameEvents(whole, events(e, scripting, input.substring(0, i), input.substring(i, i + 1), input.substring(i + 1)),
              "one character alone at " + i + ": " + input);
        }
      }
    }
    assertTrue(errors > 40, "the inputs have parse errors: " + errors);
  }

  private static void assertSameEvents(List<String> want, List<String> got, String what) {
    assertEquals(want.stream().map(x -> x.substring(x.indexOf(' '))).toList(),
        got.stream().map(x -> x.substring(x.indexOf(' '))).toList(), what);
    for (int k = 0; k < want.size(); k++) {
      int a = Integer.parseInt(want.get(k).substring(0, want.get(k).indexOf(' ')));
      int b = Integer.parseInt(got.get(k).substring(0, got.get(k).indexOf(' ')));
      assertTrue(Math.abs(a - b) <= 1, what + ": " + want.get(k) + " vs " + got.get(k));
    }
  }

  /** What a listener hears while {@code chunks} are fed in turn, at offsets into their concatenation. */
  private static List<String> events(Engine e, boolean scripting, String... chunks) throws EngineException {
    Parser p = e.document(scripting);
    List<String> out = new ArrayList<>();
    int[] base = {0};
    for (String chunk : chunks) {
      p.feed(chunk, new Parser.Listener() {
        @Override
        public void error(String message, int offset) {
          out.add((base[0] + offset) + " error " + message);
        }

        @Override
        public void pushed(Parser.Stack stack, int offset) {
          StringBuilder sb = new StringBuilder();
          sb.append(base[0] + offset).append(" pushed ").append(stack.insertedName()).append(stack.insertedOnStack() ? "" : "/");
          for (int i = 0; i < stack.size(); i++) {
            sb.append(' ').append(stack.localName(i));
          }
          for (int i = 0; i < stack.templateModeCount(); i++) {
            sb.append(' ').append(stack.templateMode(i));
          }
          out.add(sb.toString());
        }
      });
      base[0] += chunk.length();
    }
    return out;
  }

  /** A fork made anywhere, even mid-tag, continues exactly like the parser it was forked from. */
  @Test
  void forkAnywhereContinuesExactly() throws EngineException {
    Engine e = Engines.CURRENT;
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

  /** Pieces for random markup: tags, comments, CDATA, references, CR/LF, NUL and lone surrogates. */
  private static final String[] PIECES = {
    "<", ">", "</", "<!", "<?", "</ ", "/>", "=", "\"", "'", "-", "--", "]", "]]", "]]>", "<!--", "-->", "--!>", "<![CDATA[",
    "<!DOCTYPE html>", "&", "&amp;", "&amp", "&noti", "&notin;", "&#", "&#x", "&#65", "&#x41;", "&#0;", "&#xD800;", "&foo=",
    "\r", "\n", "\r\n", "\t", "\f", " ", "\u0000", "\uD800", "\uDC00", "😀", "a", "b", "script", "<script>",
    "</script", "</script>", "<style>", "</style>", "<title>", "<textarea>", "<svg>", "</svg>", "<math>", "<mi>",
    "<foreignObject>", "<p>", "</p>", "<b>", "</b>", "<table>", "<tr>", "<td>", "<select>", "<option>", "<template>",
    "</template>", "<noscript>", "<iframe>", "<plaintext>", "<a href=\"", "<div class=", "x=&copy=",
  };

  /**
   * vnu is not split-independent on input with parse errors: {@code "<!<x>b<p>"} in one buffer
   * opens an element named {@code b<p}, and split after {@code "<!<"} it reads the text {@code b}
   * and a {@code <p>}, as browsers do. Until #408 the verifier replayed the input since the last
   * point between tokens in one buffer, which hid this. Without the replay the verifier stays sound
   * because it rejects every parse error: wherever a split changes what the listener hears, both
   * sides report a parse error the verifier doesn't allow. Random markup, every 2-way split and
   * random multi-way splits, scripting on and off. Offsets and duplicate IDs (allowed, and not
   * kept across a fork) are left out.
   */
  @Test
  void splitDependenceComesWithParseErrors() throws EngineException {
    Engine e = Engines.CURRENT;
    assertTrue(rejecting(events(e, true, "<!<x>b<p>")) && rejecting(events(e, true, "<!<", "x>b<p>")));
    Random random = new Random(408);
    int differ = 0;
    for (int n = 0; n < 1000; n++) {
      StringBuilder sb = new StringBuilder();
      for (int k = 1 + random.nextInt(20); k > 0; k--) {
        sb.append(PIECES[random.nextInt(PIECES.length)]);
      }
      String input = sb.toString();
      boolean scripting = random.nextBoolean();
      List<String> whole = events(e, scripting, input);
      List<List<String>> splits = new ArrayList<>();
      for (int i = 1; i < input.length(); i++) {
        splits.add(List.of(input.substring(0, i), input.substring(i)));
      }
      for (int trial = 0; trial < 10; trial++) {
        splits.add(randomChunks(input, random));
      }
      for (List<String> chunks : splits) {
        List<String> got = events(e, scripting, chunks.toArray(new String[0]));
        if (!withoutOffsets(whole).equals(withoutOffsets(got))) {
          differ++;
          assertTrue(rejecting(whole) && rejecting(got), "split " + chunks + "\n" + whole + "\n" + got);
        }
      }
    }
    assertTrue(differ > 0, "the inputs reach the split-dependent cases");
  }

  private static List<String> withoutOffsets(List<String> events) {
    List<String> out = new ArrayList<>();
    for (String x : events) {
      if (!x.contains(" error Duplicate ID ")) {
        out.add(x.substring(x.indexOf(' ')));
      }
    }
    return out;
  }

  /** Whether {@code events} hold a parse error the verifier rejects (as {@code Rules.allowedError}). */
  private static boolean rejecting(List<String> events) {
    for (String x : events) {
      String m = x.substring(x.indexOf(' ') + 1);
      if (m.startsWith("error ") && !m.contains("without seeing a doctype first") && !m.startsWith("error Duplicate ID “")) {
        return true;
      }
    }
    return false;
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
   * The engine runs with its own Java asserts as the JVM sets them: under -ea (surefire, javac
   * -J-ea) they are on, and none fires (#408). htmlparser 1.4.16 threw "strBufLen not reset after
   * previous use!" here, after an unmatched named reference followed by an end tag.
   */
  @Test
  void engineAssertsHoldUnderEa() throws EngineException {
    boolean ea = false;
    assert ea = true;
    assertTrue(ea, "surefire runs tests with -ea");
    assertTrue(dev.tessera.compiler.internal.html5.current.impl.Tokenizer.class.desiredAssertionStatus());
    assertTrue(dev.tessera.compiler.internal.html5.current.impl.TreeBuilder.class.desiredAssertionStatus());
    Engine e = Engines.CURRENT;
    for (boolean scripting : new boolean[] {true, false}) {
      ParserState s = doc(e, "<!DOCTYPE html><p>&drop</span>x", scripting);
      assertEquals(TokenizerState.DATA, s.tokenizer(), e.name());
      assertTrue(s.currentNode().isHtml("p"));
      // Template contents in a document, and a fragment in a template.
      doc(e, "<!DOCTYPE html><template><td>&drop</td><template><tr></template></template><p>", scripting);
      Parser p = e.fragment(OpenElement.HTML, "template", scripting);
      p.feed("<tr><td>&x</td></tr><caption>");
      p.state();
    }
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
    Parser unknown = new VnuParser((VnuEngine) Engines.CURRENT, new FailingRaw(null, "SOMETHING_NEW"));
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
    public void feed(char[] chars, int start, int end, RawListener listener) throws Exception {
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
    public int stackLength() {
      return 0;
    }

    @Override
    public String stackNamespace(int i) {
      throw new IndexOutOfBoundsException();
    }

    @Override
    public String stackName(int i) {
      throw new IndexOutOfBoundsException();
    }

    @Override
    public int templateModeCount() {
      return 0;
    }

    @Override
    public String templateMode(int i) {
      throw new IndexOutOfBoundsException();
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
