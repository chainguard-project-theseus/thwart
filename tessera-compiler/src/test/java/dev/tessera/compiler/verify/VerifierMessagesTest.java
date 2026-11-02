package dev.tessera.compiler.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.oracle.Seeds;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.compiler.verify.engine.InsertionMode;
import dev.tessera.compiler.verify.engine.TokenizerState;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Golden test of the context verifier's messages (#368, all text in {@link VerifierMessages}).
 *
 * <ul>
 *   <li>{@code src/test/resources/verify/messages.txt}: one template (or more) per rule id, with
 *       the exact errors the verifier reports for it.
 *   <li>{@code src/test/resources/verify/messages-seeds.txt}: the exact errors for every seed and
 *       probe the verifier alone rejects (#367's lists).
 * </ul>
 *
 * Every message must name the rule, and none may show the parser's internals: tokenizer states,
 * insertion modes or engine class names. After changing a message, regenerate both files with
 * {@code -Dtessera.golden.update=true} and review the diff.
 */
class VerifierMessagesTest {
  private static final boolean UPDATE = Boolean.getBoolean("tessera.golden.update");
  private static final String NS = "xmlns:t=\"https://tessera.dev\"";

  /** Rule ids no template reaches today; {@link #rulesNoTemplateReaches} checks their messages. */
  private static final Set<Problem> UNREACHABLE_FROM_TEMPLATES =
      EnumSet.of(Problem.ATTRIBUTE_WRONG_NAME, Problem.UNKNOWN_NODE, Problem.ENGINE_FAILURE);

  record Case(String rule, String title, Map<String, String> templates, List<String> expected) {}

  @Test
  void everyRuleIdHasAGoldenTemplate() throws IOException {
    List<Case> cases = cases();
    Set<String> covered = cases.stream().map(Case::rule).collect(Collectors.toCollection(TreeSet::new));
    Set<String> want = new TreeSet<>();
    for (Problem p : Problem.values()) {
      if (!UNREACHABLE_FROM_TEMPLATES.contains(p)) {
        want.add(p.name());
      }
    }
    StringBuilder out = new StringBuilder();
    List<String> failures = new ArrayList<>();
    for (Case c : cases) {
      Compilation.Builder b = Compilation.builder().verifierOnly();
      // A "messages.properties" entry is the message bundle (HTML messages, #329).
      c.templates().forEach((name, body) -> {
        if (name.equals("messages.properties")) {
          b.messages(body);
        } else {
          b.template(name, wrap(body));
        }
      });
      List<String> errors = b.compile().errors();
      if (!UPDATE) {
        errors.forEach(VerifierMessagesTest::checkPlain);
      }
      if (errors.stream().noneMatch(e -> e.endsWith("[verifier:" + c.rule() + "]"))) {
        failures.add(c.title() + ": no " + c.rule() + " in " + errors);
      }
      if (!errors.equals(c.expected())) {
        failures.add(c.title() + "\n  expected " + c.expected() + "\n  actual   " + errors);
      }
      out.append("== ").append(c.rule()).append(": ").append(c.title()).append('\n');
      for (Map.Entry<String, String> t : c.templates().entrySet()) {
        out.append("-- ").append(t.getKey()).append('\n').append(t.getValue()).append('\n');
      }
      for (String e : errors) {
        out.append("=> ").append(e).append('\n');
      }
    }
    if (UPDATE) {
      failures.forEach(System.out::println);
      String text = read("/verify/messages.txt");
      Files.writeString(Path.of("src/test/resources/verify/messages.txt"), header(text) + out, StandardCharsets.UTF_8);
      return;
    }
    assertEquals(want, covered);
    assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  @Test
  void everySeedAndProbeTheVerifierRejectsHasAGoldenMessage() throws IOException {
    Map<String, String> templates = new LinkedHashMap<>();
    Map<String, SeedVerifierTest.Expectation> expectations = SeedVerifierTest.expectations();
    for (Seeds.Seed s : Seeds.load()) {
      if (expectations.get(s.name()).expect().equals("VERIFIER")) {
        templates.put(s.name(), s.template());
      }
    }
    for (ProbeVerifierTest.Probe p : ProbeVerifierTest.probes().toList()) {
      if (p.expect().equals("VERIFIER")) {
        templates.put("probe " + p.name(), p.template());
      }
    }
    StringBuilder out = new StringBuilder();
    for (Map.Entry<String, String> t : templates.entrySet()) {
      List<String> errors = Compilation.builder().template("t.html", t.getValue()).verifierOnly().compile().errors();
      assertFalse(errors.isEmpty(), t.getKey());
      for (String e : errors) {
        if (!UPDATE) {
          checkPlain(e);
        }
        out.append(t.getKey()).append('\t').append(e).append('\n');
      }
    }
    String text = read("/verify/messages-seeds.txt");
    if (UPDATE) {
      Files.writeString(Path.of("src/test/resources/verify/messages-seeds.txt"), header(text) + out, StandardCharsets.UTF_8);
      return;
    }
    assertEquals(body(text), out.toString());
  }

  /**
   * The rule ids no template reaches today, on hand-built skeletons: a duplicate or misread
   * attribute name (codegen writes each attribute once, and the profile rejects the markup that
   * could shift a name), a loop that doesn't settle (the profile's balanced elements settle), an
   * unknown fragment, and an engine that throws.
   */
  @Test
  void rulesNoTemplateReaches() {
    SourcePos at = new SourcePos("t.html", 1, 1);
    assertEquals("a browser reads this value of 'title' as the value of 'alt', not of 'title'; check the markup"
        + " before it in the tag [verifier:ATTRIBUTE_WRONG_NAME]",
        only(Problem.ATTRIBUTE_WRONG_NAME, st("<p alt=\""), attr("title")));
    assertEquals("a browser keeps only the first 'title' attribute of a tag and drops this one; write the attribute"
        + " once [verifier:ATTRIBUTE_WRONG_NAME]",
        only(Problem.ATTRIBUTE_WRONG_NAME, st("<p title=\"a\" title=\""), attr("title")));
    assertEquals("Tessera can't check what a browser does here: fragment t :: missing is unknown to the verifier;"
        + " this is a compiler bug, report it with the template [verifier:UNKNOWN_NODE]",
        only(Problem.UNKNOWN_NODE, new Skeleton.Call(new Skeleton.FragmentId("t", "missing"), Map.of(), at)));
    String engine = VerifierMessages.message(new Finding(Problem.ENGINE_FAILURE, at, null, null, null, null, null,
        List.of(), 2, "boom", false));
    assertEquals("Tessera can't check what a browser does here: the HTML parser it checks with failed (boom);"
        + " this is a compiler bug, report it with the template [verifier:ENGINE_FAILURE]", engine);
    checkPlain(engine);
  }

  private static Skeleton.Static st(String s) {
    return new Skeleton.Static(s, new SourcePos("t.html", 1, 1));
  }

  private static Skeleton.Hole attr(String name) {
    return new Skeleton.Hole(Skeleton.Escaper.ATTR, name, new SourcePos("t.html", 1, 2));
  }

  /** The message of the one finding of {@code problem} for a template made of {@code nodes}. */
  private static String only(Problem problem, Skeleton.Node... nodes) {
    Skeleton.TemplateSkeleton t = new Skeleton.TemplateSkeleton("t", new Skeleton.Seq(List.of(nodes)), Map.of());
    List<String> m = new ContextVerifier(Map.of("t", t)).check(t).stream()
        .filter(f -> f.problem == problem).map(VerifierMessages::message).collect(Collectors.toList());
    assertEquals(1, m.size(), m.toString());
    checkPlain(m.get(0));
    return m.get(0);
  }

  /** The check itself rejects what it must keep out of messages. */
  @Test
  void parserInternalsAreCaught() {
    for (String bad : List.of("a browser reads this value in IN_TABLE_BODY [verifier:TEXT_IN_TABLE]",
        "a browser reads this value in SCRIPT_DATA_ESCAPED [verifier:TEXT_IN_SCRIPT]",
        "the tokenizer is in state 38 [verifier:TEXT_NOT_IN_TEXT]",
        "the insertion mode is in table [verifier:TEXT_IN_TABLE]",
        "a browser reads this value as table text [verifier:TEXT_IN_TABLE] (TEXT_IN_TABLE)",
        "a browser reads this value as table text")) {
      assertThrows(AssertionError.class, () -> checkPlain(bad), bad);
    }
    checkPlain("a browser reads this value as table text and moves it in front of the table; put it in a <td> or <th>"
        + " [verifier:TEXT_IN_TABLE]");
  }

  // ---- helpers ----

  private static final Pattern RULE = Pattern.compile(" \\[verifier:([A-Z_]+)\\]$");

  /** Internal names that must never reach a message. */
  private static final Set<String> INTERNAL = internalNames();

  private static Set<String> internalNames() {
    Set<String> names = new TreeSet<>();
    for (TokenizerState s : TokenizerState.values()) {
      names.add(s.name());
    }
    for (InsertionMode m : InsertionMode.values()) {
      names.add(m.name());
    }
    for (Problem p : Problem.values()) {
      names.add(p.name());
    }
    return names;
  }

  /** The message names its rule once, at the end, and no parser internals anywhere. */
  static void checkPlain(String error) {
    Matcher m = RULE.matcher(error);
    assertTrue(m.find(), "no rule id: " + error);
    String text = error.substring(0, m.start());
    for (String word : text.split("[^A-Za-z0-9_]+")) {
      assertFalse(INTERNAL.contains(word), "internal name " + word + " in: " + error);
    }
    for (String jargon : List.of("insertion mode", "tokenizer", "TreeBuilder", "Tokenizer", "nu.validator", "ParserState",
        "OpenElement", "Exception", "state 1", "state 2", "state 3", "state 4", "state 5", "state 6", "state 7",
        "state 8", "state 9")) {
      assertFalse(text.contains(jargon), "'" + jargon + "' in: " + error);
    }
    assertFalse(text.matches(".*\\b[A-Z]{2,}_[A-Z_]+\\b.*"), "enum-like name in: " + error);
  }

  private static List<Case> cases() throws IOException {
    List<Case> out = new ArrayList<>();
    String rule = null;
    String title = null;
    Map<String, String> templates = null;
    String file = null;
    StringBuilder body = null;
    List<String> expected = null;
    for (String line : (body(read("/verify/messages.txt")) + "== END: end\n").split("\n", -1)) {
      if (line.startsWith("== ")) {
        if (file != null) {
          templates.put(file, body.toString());
        }
        if (rule != null) {
          out.add(new Case(rule, title, templates, expected));
        }
        int colon = line.indexOf(": ");
        rule = line.substring(3, colon);
        title = line.substring(colon + 2);
        templates = new LinkedHashMap<>();
        expected = new ArrayList<>();
        file = null;
        body = null;
      } else if (line.startsWith("-- ")) {
        if (file != null) {
          templates.put(file, body.toString());
        }
        file = line.substring(3).strip();
        body = new StringBuilder();
      } else if (line.startsWith("=> ")) {
        if (file != null) {
          templates.put(file, body.toString());
          file = null;
        }
        expected.add(line.substring(3));
      } else if (file != null) {
        body.append(body.length() > 0 ? "\n" : "").append(line);
      }
    }
    return out;
  }

  /** A template without {@code $NS} (the Tessera namespace declaration) gets a root with it and {@code String s}. */
  private static String wrap(String b) {
    return b.contains("$NS") ? b.replace("$NS", NS) : "<div " + NS + " t:params=\"String s\">" + b + "</div>";
  }

  private static String read(String resource) throws IOException {
    try (InputStream in = VerifierMessagesTest.class.getResourceAsStream(resource)) {
      assertNotNull(in, resource);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** The leading comment lines of a golden file. */
  private static String header(String text) {
    StringBuilder sb = new StringBuilder();
    for (String line : text.split("\n")) {
      if (!line.startsWith("#")) {
        break;
      }
      sb.append(line).append('\n');
    }
    return sb.toString();
  }

  private static String body(String text) {
    return text.substring(header(text).length());
  }
}
