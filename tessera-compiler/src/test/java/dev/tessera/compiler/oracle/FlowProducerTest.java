package dev.tessera.compiler.oracle;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.compiler.verify.ContentKinds;
import dev.tessera.compiler.verify.engine.Engine;
import dev.tessera.compiler.verify.engine.EngineException;
import dev.tessera.compiler.verify.engine.Engines;
import dev.tessera.compiler.verify.engine.InsertionMode;
import dev.tessera.compiler.verify.engine.Parser;
import dev.tessera.compiler.verify.engine.ParserState;
import dev.tessera.runtime.HtmlPolicy;
import dev.tessera.runtime.SafeHtml;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * The producer side of the {@code FLOW} content kind (#329): every producer of HTML known only at
 * render time leaves the parser in the state it found it, at every insertion point where
 * {@link ContentKinds#problem} accepts {@code FLOW}, in the verifier's engine with scripting on and
 * off. That is the premise the verifier relies on when it takes the state after a
 * {@code SafeHtml} or {@code Fragment} value to be the state before it. (In template mode the first
 * flow start tag switches to "in body"; the verifier continues from both, so either is fine.)
 *
 * <p>Producers: {@code html.sanitize} under {@link HtmlPolicy#BASIC}, {@link HtmlPolicy#RICH} and
 * builder policies, for hostile and fuzzed input ({@link SanitizerBalanceTest} checks their tree);
 * {@link SafeHtml#escape} and {@link SafeHtml#concat}; and rendered partial templates, which is
 * what {@code SafeHtml.of} and a {@code Fragment} value write. HTML messages are known at build time
 * and the verifier checks their markup at each insertion point (ContentKindsTest).
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
            .allowClasses("*")
            .allowRemoteImages()
            .allowLinkTarget()
            .build());
    POLICIES.put(
        "partial",
        HtmlPolicy.builder()
            .allowElements("td", "tr", "li", "a", "p", "pre", "img", "b", "caption", "thead")
            .allowAttributes("id", "colspan")
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
      Engine e = Engines.CURRENT;
      for (boolean scripting : new boolean[] {true, false}) {
        Parser p = e.document(scripting);
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
        assertNull(ContentKinds.problem(ContentKinds.Kind.FLOW, s), () -> context + ": " + s);
      }
    }
  }

  @Test
  void sanitizerOutputLeavesTheStateAsItWas() throws EngineException {
    List<String> inputs = new ArrayList<>(List.of(SanitizerBalanceTest.HOSTILE));
    inputs.addAll(fuzz(200));
    for (String input : inputs) {
      for (Map.Entry<String, HtmlPolicy> policy : POLICIES.entrySet()) {
        assertFlow("sanitize " + policy.getKey() + " of " + input, SafeHtml.sanitize(input, policy.getValue()).html());
      }
    }
  }

  @Test
  void escapedTextAndConcatenationLeaveTheStateAsItWas() throws EngineException {
    for (String input : SanitizerBalanceTest.HOSTILE) {
      assertFlow("escape of " + input, SafeHtml.escape(input).html());
    }
    assertFlow("concat", SafeHtml.concat(
        SafeHtml.escape("a <b> & {{c}}"), SafeHtml.sanitize("<br>"), SafeHtml.sanitize("<p>x<ul><li>y"),
        HtmlPolicy.RICH.sanitize("<table><tr><td>z")).html());
  }

  /**
   * Rendered partials of flow content, with hostile values: what {@code SafeHtml.of(template)} and
   * a {@code Fragment} value that renders a template write.
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
      assertNull(ContentKinds.problem(ContentKinds.Kind.FLOW, entry.state()), cases[6][0]);
    }
    for (int i = 0; i < 6; i++) {
      for (Parser entry : entries(cases[i][0])) {
        assertNotNull(ContentKinds.problem(ContentKinds.Kind.FLOW, entry.state()), cases[i][0]);
      }
    }
  }

  private static void assertFlow(String what, String html) throws EngineException {
    for (String context : CONTEXTS) {
      if (DUMP_FILE != null) {
        DUMP.add("{\"context\":" + json(context) + ",\"html\":" + json(html) + "}");
      }
      for (Parser entry : entries(context)) {
        ParserState before = entry.state();
        Parser p = entry.fork();
        p.feed(html);
        ParserState after = p.state();
        if (same(after, before)) {
          continue;
        }
        if (ContentKinds.switchesMode(ContentKinds.Kind.FLOW, before)) {
          Parser rep = entry.fork();
          rep.feed(ContentKinds.REPRESENTATIVE);
          if (same(after, rep.state())) {
            continue;
          }
        }
        fail(what + "\n output: " + html + "\n context: " + context + " (" + before.engine()
            + (before.scripting() ? ", scripting" : ", no scripting") + ")\n before: " + before + "\n after:  " + after);
      }
    }
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
