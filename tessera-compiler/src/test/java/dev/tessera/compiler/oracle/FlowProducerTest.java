package dev.tessera.compiler.oracle;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.compiler.verify.ContentKinds;
import dev.tessera.compiler.verify.Skeleton;
import dev.tessera.compiler.verify.engine.EngineException;
import dev.tessera.compiler.verify.engine.InsertionMode;
import dev.tessera.compiler.verify.engine.OpenElement;
import dev.tessera.compiler.verify.engine.Parser;
import dev.tessera.compiler.verify.engine.ParserState;
import dev.tessera.compiler.verify.engine.Vnu;
import dev.tessera.runtime.DefaultLinks;
import dev.tessera.runtime.Escapers;
import dev.tessera.runtime.Forms;
import dev.tessera.runtime.HtmlPolicy;
import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.SafeHtml;
import dev.tessera.runtime.SafeInlineHtml;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * The producer side of the {@code FLOW} content kind (#329): every producer of HTML known only at
 * render time parses without a parse error and leaves the parser in the state it found it, at every insertion point where
 * {@link ContentKinds#problem} accepts {@code FLOW}, in the verifier's engine with scripting on and
 * off. That is the premise the verifier relies on when it takes the state after a
 * {@code SafeHtml} or {@code Fragment} value to be the state before it. (In template mode the first
 * flow start tag switches to "in body"; the verifier continues from both, so either is fine.)
 *
 * <p>Producers: {@code html.sanitize} under {@link HtmlPolicy#BASIC}, {@link HtmlPolicy#RICH} and
 * builder policies, for hostile and fuzzed input ({@link SanitizerBalanceTest} checks their tree);
 * {@link SafeHtml#escape} and {@link SafeHtml#concat}; and rendered partial templates, which is
 * what {@code SafeHtml.of} and a {@code Fragment} value write. HTML messages are PHRASING, a
 * narrower kind ({@link #htmlMessageOutputLeavesTheStateAsItWas}).
 *
 * <p>With {@code -Dtessera.producer.dump=<file>} it also writes every (context, output) pair as
 * JSON lines, for the parse5 check in the #329 PR.
 */
class FlowProducerTest {

  /** Insertion points that meet {@code FLOW}, as the document parse prefix that reaches them. */
  static final String[] CONTEXTS = {
    "<!DOCTYPE html><body>",
    "<!DOCTYPE html><div>",
    "<!DOCTYPE html><table><tr><td>",
    "<!DOCTYPE html><table><caption>",
    "<!DOCTYPE html><ul><li>",
    "<!DOCTYPE html><dl><dd>",
    "<!DOCTYPE html><template>",
    "<!DOCTYPE html><form>",
    "<!DOCTYPE html><h1><span>",
    "<!DOCTYPE html><p><table><tr><td>",
    "<!DOCTYPE html><svg><foreignObject><div>",
    "<!DOCTYPE html><b><i>",
    "<!DOCTYPE html><a href=\"/x\"><object>",
    "<html><body><div>", // quirks mode: <table> no longer closes a <p>, but none is open
  };

  private static final Map<String, HtmlPolicy> POLICIES = new LinkedHashMap<>();

  static {
    POLICIES.put("BASIC", HtmlPolicy.BASIC);
    POLICIES.put("RICH", HtmlPolicy.RICH);
    POLICIES.put(
        "wide",
        HtmlPolicy.builder()
            .allowElements(HtmlPolicy.ELEMENTS.toArray(new String[0]))
            .allowAttributes(HtmlPolicy.ATTRIBUTES.toArray(new String[0]))
            .allowClasses(SanitizerBalanceTest.EVERY_CLASS)
            .allowLinkTarget()
            .build());
    POLICIES.put(
        "partial",
        HtmlPolicy.builder()
            .allowElements("li", "a", "p", "pre", "img", "b")
            .allowAttributes("id")
            .linkRel()
            .build());
    POLICIES.put(
        "inline",
        HtmlPolicy.builder().allowElements("a", "b", "i", "em", "strong", "code", "span", "br").build());
  }

  /** One running parser per scripting mode at each context, never fed after setup. */
  private static final Map<String, List<Parser>> ENTRIES = new LinkedHashMap<>();

  private static final List<String> DUMP = new ArrayList<>();

  private static List<Parser> entries(String context) throws EngineException {
    List<Parser> ps = ENTRIES.get(context);
    if (ps == null) {
      ps = new ArrayList<>();
      for (boolean scripting : new boolean[] {true, false}) {
        Parser p = Vnu.document(scripting);
        p.feed(context);
        ps.add(p);
      }
      ENTRIES.put(context, ps);
    }
    return ps;
  }

  @Test
  void contextsMeetFlow() throws EngineException {
    for (String context : CONTEXTS) {
      for (Parser p : entries(context)) {
        ParserState s = p.state();
        assertNull(ContentKinds.problem(s), () -> context + ": " + s);
      }
    }
  }

  @Test
  void sanitizerOutputLeavesTheStateAsItWas() throws EngineException {
    List<String> inputs = new ArrayList<>(List.of(SanitizerBalanceTest.HOSTILE));
    inputs.addAll(List.of(SanitizerBalanceTest.FORBIDDEN_CODE_POINTS));
    inputs.addAll(fuzz(200));
    for (String input : inputs) {
      for (Map.Entry<String, HtmlPolicy> policy : POLICIES.entrySet()) {
        assertFlow("sanitize " + policy.getKey() + " of " + input, SafeHtml.sanitize(input, policy.getValue()).html());
      }
    }
  }

  /** Insertion points that meet an HTML message (PHRASING, #677) but not FLOW. */
  static final String[] PHRASING_CONTEXTS = {
    "<!DOCTYPE html><p>", "<!DOCTYPE html><p><span>", "<!DOCTYPE html><h2>", "<!DOCTYPE html><button>",
    "<!DOCTYPE html><nobr>", "<!DOCTYPE html><ruby>",
  };

  /**
   * The producer side of the HTML message kind (#677): the message policy's output, with or without
   * links, leaves the parser as it was at every FLOW insertion point and in an open paragraph,
   * heading, button, nobr or ruby; without links, also in an open link. The verifier accepts each
   * of these ({@link ContentKinds#problem(ParserState, Skeleton.Source)}).
   */
  @Test
  void htmlMessageOutputLeavesTheStateAsItWas() throws EngineException {
    List<String> inputs = new ArrayList<>(List.of(SanitizerBalanceTest.HOSTILE));
    inputs.addAll(List.of(SanitizerBalanceTest.FORBIDDEN_CODE_POINTS));
    inputs.addAll(fuzz(200));
    inputs.add("<a href=\"/a\">a<a href=\"/b\">b</a><b><b>c</b></b></a><p>d<h1>e</h1>");
    List<String> linked = new ArrayList<>(List.of(CONTEXTS));
    linked.addAll(List.of(PHRASING_CONTEXTS));
    List<String> unlinked = new ArrayList<>(linked);
    unlinked.add("<!DOCTYPE html><a href=\"/x\">");
    unlinked.add("<!DOCTYPE html><p><a href=\"/x\"><b>");
    for (Skeleton.Source source : new Skeleton.Source[] {Skeleton.Source.HTML_MESSAGE, Skeleton.Source.LINKED_HTML_MESSAGE}) {
      List<String> contexts = source == Skeleton.Source.HTML_MESSAGE ? unlinked : linked;
      for (String context : contexts) {
        for (Parser p : entries(context)) {
          assertNull(ContentKinds.problem(p.state(), source), () -> source + " in " + context);
        }
      }
      for (String input : inputs) {
        RenderContext ctx = new RenderContext(Locale.ROOT, (k, a) -> input, new DefaultLinks(""), Forms.NONE);
        String html = Escapers.htmlMessage(ctx, "k", new Object[0], source == Skeleton.Source.LINKED_HTML_MESSAGE).html();
        assertSameState(source + " of " + input, html, contexts.toArray(new String[0]));
      }
    }
    // An open link is the one place the linked message is rejected.
    for (Parser p : entries("<!DOCTYPE html><a href=\"/x\">")) {
      assertNotNull(ContentKinds.problem(p.state(), Skeleton.Source.LINKED_HTML_MESSAGE));
    }
  }

  /**
   * The producer side of {@code SafeInlineHtml} (#397), PHRASING without links: a hostile sweep over
   * the whole inline allow-list, every element with every attribute, nested, misnested and mixed
   * with blocks and links, sanitized by the widest inline policy, and escaped text and {@code
   * concat}, leave the parser as it was at every FLOW insertion point and in an open paragraph,
   * heading, link, button, nobr or ruby. The verifier accepts each of these.
   */
  @Test
  void safeInlineHtmlLeavesTheStateAsItWas() throws EngineException {
    HtmlPolicy widest =
        HtmlPolicy.inlineBuilder()
            .allowElements(HtmlPolicy.INLINE_ELEMENTS.toArray(new String[0]))
            .allowAttributes(HtmlPolicy.ATTRIBUTES.toArray(new String[0]))
            .allowClasses(SanitizerBalanceTest.EVERY_CLASS)
            .build();
    List<String> inputs = new ArrayList<>(List.of(SanitizerBalanceTest.HOSTILE));
    inputs.addAll(List.of(SanitizerBalanceTest.FORBIDDEN_CODE_POINTS));
    inputs.addAll(fuzz(200));
    List<String> tags = new ArrayList<>(List.of("<p>", "<div>", "<a href=/x>", "</a>", "<h2>", "<button>", "<li>", "<nobr>",
        "<rt>", "<table>", "<td>", "<select>", "text", "&amp;", "<"));
    for (String name : HtmlPolicy.INLINE_ELEMENTS) {
      tags.add("<" + name + " id=x class=c title='t\"' alt=a src=/i.png width=1 datetime=2020>");
      tags.add("</" + name + ">");
    }
    Random random = new Random(397);
    for (int i = 0; i < 300; i++) {
      StringBuilder sb = new StringBuilder();
      for (int j = 1 + random.nextInt(30); j > 0; j--) {
        sb.append(tags.get(random.nextInt(tags.size())));
      }
      inputs.add(sb.toString());
    }
    List<String> contexts = new ArrayList<>(List.of(CONTEXTS));
    contexts.addAll(List.of(PHRASING_CONTEXTS));
    contexts.add("<!DOCTYPE html><a href=\"/x\">");
    contexts.add("<!DOCTYPE html><h1><a href=\"/x\"><b>");
    for (String context : contexts) {
      for (Parser p : entries(context)) {
        assertNull(ContentKinds.problem(p.state(), Skeleton.Source.SAFE_INLINE_HTML), () -> "SafeInlineHtml in " + context);
        if (List.of(PHRASING_CONTEXTS).contains(context)) {
          assertNotNull(ContentKinds.problem(p.state(), Skeleton.Source.SAFE_HTML), () -> "SafeHtml in " + context);
        }
      }
    }
    // Still rejected where PHRASING can't go: a select (RAW_CLOSES_ELEMENT), a table part, raw text
    // or foreign content (RAW_NOT_FLOW).
    for (String context : new String[] {"<!DOCTYPE html><select>", "<!DOCTYPE html><select><option>",
        "<!DOCTYPE html><select><optgroup>", "<!DOCTYPE html><table>", "<!DOCTYPE html><table><tr>",
        "<!DOCTYPE html><textarea>", "<!DOCTYPE html><svg>", "<!DOCTYPE html><p><math>"}) {
      for (Parser p : entries(context)) {
        assertNotNull(ContentKinds.problem(p.state(), Skeleton.Source.SAFE_INLINE_HTML), () -> "SafeInlineHtml in " + context);
      }
    }
    String[] all = contexts.toArray(new String[0]);
    for (String input : inputs) {
      String html = SafeInlineHtml.sanitize(input, widest).html();
      java.util.regex.Matcher tag = java.util.regex.Pattern.compile("</?([a-z0-9]+)").matcher(html);
      while (tag.find()) {
        assertTrue(HtmlPolicy.INLINE_ELEMENTS.contains(tag.group(1)), () -> "not inline: " + html);
      }
      assertSameState("SafeInlineHtml of " + input, html, all);
      assertSameState("escaped " + input, SafeInlineHtml.concat(SafeInlineHtml.escape(input), null, SafeInlineHtml.sanitize(input, widest)).html(), all);
    }
  }

  @Test
  void escapedTextAndConcatenationLeaveTheStateAsItWas() throws EngineException {
    List<String> inputs = new ArrayList<>(List.of(SanitizerBalanceTest.HOSTILE));
    inputs.addAll(List.of(SanitizerBalanceTest.FORBIDDEN_CODE_POINTS)); // #614: U+FFFD, no parse error
    for (String input : inputs) {
      assertFlow("escape of " + input, SafeHtml.escape(input).html());
    }
    assertFlow("concat", SafeHtml.concat(
        SafeHtml.escape("a <b> & {{c}}"), SafeHtml.sanitize("<br>"), SafeHtml.sanitize("<p>x<ul><li>y"),
        SafeHtml.sanitize("<table><tr><td>z", HtmlPolicy.RICH)).html());
  }

  /**
   * Rendered partials of flow content, with hostile values: what a template and
   * a {@code Fragment} argument that inserts one write.
   */
  @Test
  void renderedPartialsLeaveTheStateAsItWas() throws EngineException {
    String ns = "xmlns:t=\"https://tessera.dev\"";
    Compilation c = Compilation.builder()
        .template("card.html",
            "<div " + ns + " class=\"card\" t:import=\"java.util.List\" t:params=\"String title, List<String> items, String body\">"
                + "<h2 t:text=\"title\">t</h2><p>Hi <b t:text=\"title\">x</b>, <a t:href=\"@{/x(q=${title})}\" t:text=\"title\">l</a>.</p>"
                + "<ul><li t:each=\"i : items\" t:text=\"i\">x</li></ul>"
                + "<table><tr><th>k</th></tr><tr t:each=\"i : items\"><td t:text=\"i\">v</td></tr></table>"
                + "<div t:utext=\"${html.sanitizeRich(body)}\">rich</div>"
                + "<pre t:text=\"body\">p</pre><textarea t:text=\"body\">t</textarea></div>")
        .template("rows.html",
            "<section " + ns + " t:params=\"String a\"><h3 t:text=\"a\">h</h3><div t:replace=\"~{:: note(a)}\">n</div>"
                + "<script>var a = [[${a}]];</script><img t:alt=\"a\" src=\"/i.png\">"
                + "<p t:fragment=\"note(String text)\">[[${text}]] <em t:if=\"${text.length() > 3}\">long</em></p></section>")
        .compile()
        .assertSuccess();
    List<String> values = new ArrayList<>(List.of(SanitizerBalanceTest.HOSTILE).subList(0, 40));
    values.add("");
    for (String v : values) {
      assertFlow("card with " + v, c.render("card", Map.of("title", v, "items", List.of(v, "b"), "body", v)));
      assertFlow("rows with " + v, c.render("rows", Map.of("a", v)));
    }
    // Forbidden code points in text, attribute values and sanitized markup (#614) and in a script
    // value, which writes them as \\uXXXX escapes (#619).
    for (String v : SanitizerBalanceTest.FORBIDDEN_CODE_POINTS) {
      assertFlow("card with " + v, c.render("card", Map.of("title", v, "items", List.of(v, "b"), "body", v)));
      assertFlow("rows with " + v, c.render("rows", Map.of("a", v)));
    }
  }

  /**
   * Each clause of the predicate is needed: outside {@code FLOW}, ordinary FLOW content changes the
   * state. And the producer promise is needed: content that isn't FLOW (a list item at the top, a
   * form) changes the state at insertion points the predicate accepts.
   *
   * <p>A fourth {@code <b>} inside three open ones changes the state too, but the predicate accepts
   * it (#444): the Noah's Ark clause drops the oldest {@code <b>} from the list of active formatting
   * elements, and that element stays open. In an error-free parse the list changes the tree only
   * through entries that are no longer open, so the change can't reach the tree.
   */
  @Test
  void outsideTheKindTheStateChanges() throws EngineException {
    String[][] cases = {
      {"<!DOCTYPE html><p>", "<p>x</p>"}, // closes the open p
      {"<!DOCTYPE html><p><span>", "<div>x</div>"},
      {"<!DOCTYPE html><h2>", "<h3>x</h3>"}, // pops the heading
      {"<!DOCTYPE html><a href=\"/1\">", "<a href=\"/2\">x</a>"}, // adoption agency
      {"<!DOCTYPE html><button>", "<button>x</button>"},
      {"<!DOCTYPE html><table>", "x"}, // table text, held back: not a FLOW mode
      // Accepted by the predicate (#444): only the list changes.
      {"<!DOCTYPE html><b><b><b>", "<b>x</b>"}, // Noah's Ark
      // Not FLOW content, where the predicate holds:
      {"<!DOCTYPE html><ul><li>", "<li>x</li>"},
      {"<!DOCTYPE html><form>", "<form>x</form>"},
    };
    for (String[] c : cases) {
      boolean changed = false;
      for (Parser entry : entries(c[0])) {
        Parser p = entry.fork();
        p.feed(c[1]);
        changed |= !p.state().equals(entry.state());
      }
      assertTrue(changed, () -> c[1] + " in " + c[0] + " left the state as it was");
    }
    for (Parser entry : entries(cases[6][0])) {
      assertNull(ContentKinds.problem(entry.state()), cases[6][0]);
    }
    for (int i = 0; i < 6; i++) {
      for (Parser entry : entries(cases[i][0])) {
        assertNotNull(ContentKinds.problem(entry.state()), cases[i][0]);
      }
    }
  }

  /**
   * Fails unless {@code html} parses without a parse error and leaves the state as it was, at every
   * context, with scripting on and off (#592: the sanitizer's closed content model parses as written).
   */
  static void assertFlow(String what, String html) throws EngineException {
    assertSameState(what, html, CONTEXTS);
  }

  private static void assertSameState(String what, String html, String[] contexts) throws EngineException {
    for (String context : contexts) {
      if (DUMP_FILE != null) {
        DUMP.add("{\"context\":" + json(context) + ",\"html\":" + json(html) + "}");
      }
      for (Parser entry : entries(context)) {
        ParserState before = entry.state();
        Parser p = entry.fork();
        List<String> errors = new ArrayList<>();
        p.feed(html, errorListener(errors));
        if (!errors.isEmpty()) {
          fail(what + "\n output: " + html + "\n context: " + context + " ("
              + (before.scripting() ? "scripting" : "no scripting") + ")\n parse errors: " + errors);
        }
        ParserState after = p.state();
        if (same(after, before)) {
          continue;
        }
        if (ContentKinds.switchesMode(before)) {
          Parser rep = entry.fork();
          rep.feed(ContentKinds.REPRESENTATIVE);
          if (same(after, rep.state())) {
            continue;
          }
        }
        fail(what + "\n output: " + html + "\n context: " + context + " ("
            + (before.scripting() ? "scripting" : "no scripting") + ")\n before: " + before + "\n after:  " + after);
      }
    }
  }

  /** The parse errors the verifier's parser reports for {@code html} after document prefix {@code context}, scripting on and off. */
  static List<String> parseErrors(String context, String html) throws EngineException {
    List<String> errors = new ArrayList<>();
    for (Parser entry : entries(context)) {
      entry.fork().feed(html, errorListener(errors));
    }
    return errors;
  }

  private static Parser.Listener errorListener(List<String> errors) {
    return new Parser.Listener() {
      @Override
      public void error(String message, int offset) {
        // As the verifier's allow-list (Rules.allowedError): "Duplicate ID" is validator.nu's
        // conformance check, not a parse error (stored content may repeat an id).
        if (!message.startsWith("Duplicate ID \u201C")) {
          errors.add(message + " at " + offset);
        }
      }

      @Override
      public void pushed(Parser.Stack stack, int offset) {}

      @Override
      public void cdata(OpenElement current, int offset) {}
    };
  }

  /**
   * Equal states, but for the original insertion mode outside "text" mode: vnu keeps it after a
   * {@code <textarea>} or {@code <script>} ends, and the HTML standard reads it only in "text" mode,
   * which always sets it first. The signature keeps it anyway (more than needed costs a merge).
   */
  private static boolean same(ParserState a, ParserState b) {
    return a.equals(b) || a.mode() == b.mode() && a.mode() != InsertionMode.TEXT
        && a.signature().replaceFirst(" original=\\S+", "").equals(b.signature().replaceFirst(" original=\\S+", ""));
  }

  private static List<String> fuzz(int n) {
    String[] pieces = {
      "<p>", "</p>", "<div>", "</div>", "<b>", "</b>", "<i>", "<a href=/x>", "</a>", "<ul>", "</ul>", "<ol>",
      "<li>", "</li>", "<h1>", "</h1>", "<h3>", "<pre>", "</pre>", "\n", "<blockquote>", "<table>", "</table>",
      "<caption>", "<tbody>", "<tr>", "</tr>", "<td>", "</td>", "<th>", "<br>", "<hr>", "<img src=/i.png>",
      "<span>", "</span>", "<code>", "<form>", "<svg>", "<math>", "<style>", "<noscript>", "<title>", "<select>",
      "<option>", "<template>", "<!--", "-->", "text", " ", "&amp;", "{{", "<", ">", "\"", "</", "<button>",
      "<nobr>", "<dd>", "<textarea>", "<plaintext>", "<xmp>",
    };
    Random random = new Random(329);
    List<String> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      StringBuilder sb = new StringBuilder();
      int len = 1 + random.nextInt(25);
      for (int j = 0; j < len; j++) {
        sb.append(pieces[random.nextInt(pieces.length)]);
      }
      out.add(sb.toString());
    }
    return out;
  }

  private static final String DUMP_FILE = System.getProperty("tessera.producer.dump");

  @AfterAll
  static void dump() throws IOException {
    if (DUMP_FILE != null) {
      try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(DUMP_FILE), StandardCharsets.UTF_8))) {
        DUMP.stream().distinct().forEach(w::println);
      }
    }
  }

  private static String json(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (char ch : s.toCharArray()) {
      if (ch == '"' || ch == '\\') {
        sb.append('\\').append(ch);
      } else if (ch < 0x20) {
        sb.append(String.format("\\u%04x", (int) ch));
      } else {
        sb.append(ch);
      }
    }
    return sb.append('"').toString();
  }
}
