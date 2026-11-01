package dev.tessera.compiler.oracle;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tessera.compiler.oracle.gen.Deciding;
import dev.tessera.compiler.oracle.gen.GeneratedCase;
import dev.tessera.compiler.oracle.gen.Generator;
import dev.tessera.compiler.oracle.gen.Shape;
import dev.tessera.compiler.security.HostileStrings;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.compiler.testing.TestFragments;
import dev.tessera.compiler.verify.Skeleton;
import dev.tessera.compiler.verify.SkeletonMatcher;
import dev.tessera.compiler.verify.engine.Engine;
import dev.tessera.compiler.verify.engine.EngineException;
import dev.tessera.compiler.verify.engine.Engines;
import dev.tessera.compiler.verify.engine.Parser;
import dev.tessera.compiler.verify.engine.ParserState;
import dev.tessera.runtime.Escapers;
import dev.tessera.runtime.Fragment;
import dev.tessera.runtime.Output;
import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.SafeHtml;
import dev.tessera.runtime.StringOutput;
import dev.tessera.runtime.TemplateEntry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The oracle gate (#324; security-architecture.md §11.3, §11.7): tests the two assumptions the
 * build-time context verifier (#367) rests on, and keeps the value checks for what the verifier
 * doesn't cover.
 *
 * <p>Cases are every seed (#320) and the generator's default enumeration (#321), compiled in
 * batches with the real processor, verifier included. For every case the compiler <b>accepts</b>,
 * the gate renders it with the benign markers ({@code tqm0001}), the fingerprint values (#323) and
 * every {@linkplain #HOSTILE hostile value}, and checks:
 *
 * <ol>
 *   <li><b>Skeleton fidelity.</b> Every render is in the language of the case's skeleton (#366's
 *       {@link SkeletonMatcher}): the verifier checked what the generated class writes.
 *   <li><b>The inertness lemma.</b> At each hole of each render path, in the engine, scripting on
 *       and off, document and {@code <body>} fragment parsing, the parser state is the same after
 *       the hole's escaper wrote any of the {@linkplain #LEMMA_VALUES lemma values} as before it
 *       ({@link #lemmaKey}). This is the prototype's stand-in check, run at the states the real
 *       renders reach.
 *   <li><b>The value oracle.</b> {@link HtmlInvariant} (#318), {@link ScriptInvariant} (#319) and
 *       {@link ContextCheck} (#323). A family A/B finding ({@link Family#AB}) on an accepted
 *       template is a verifier soundness bug.
 * </ol>
 *
 * Fidelity and lemma failures and A/B findings fail the gate and can't be listed. Family C, D and
 * E findings are ratcheted against {@code known-holes.txt} ({@link #LISTABLE} kinds only; a line
 * with another kind is a format error, a line that matches nothing fails with "delete its line").
 *
 * <p>For every case the compiler <b>rejects</b>, the gate records the layer (the profile, or the
 * verifier: its messages end with {@code [verifier:RULE]}) and requires every error to name file,
 * line and column. A case the verifier alone rejects is compiled again without it ({@link
 * Compilation.Builder#withoutVerifier()}) and value-checked: no A/B finding makes it a
 * false-positive candidate in the report (informational). Every seed {@code
 * seed-expectations.txt} says the verifier or the profile rejects must be rejected.
 *
 * <p>Writes {@code target/oracle/report.txt}. With {@code -Dtessera.oracle.export=<file>} it also
 * writes the JSONL the Node job (#322) reads ({@link ExportRecord}, with each render's vnu trees
 * for the engine-drift check, #399); {@code -Dtessera.oracle.export.values=<n>} keeps the first
 * {@code n} hostile values per case there (default {@link #EXPORT_DEFAULT}: the #320 values, then
 * the {@link #STRUCTURE_VALUES}), to fit the browsers' time budget. {@code -Dtessera.oracle.gen.max} and
 * {@code -Dtessera.oracle.explore} work as in {@link Generator}; with either set, lines for cases
 * that didn't run are not reported stale.
 */
class ProfileOracleTest {

  static final String EXPORT = "tessera.oracle.export";
  static final String EXPORT_VALUES = "tessera.oracle.export.values";

  /** Templates per javac run. */
  static final int BATCH = 150;

  /** Where a finding belongs (security-architecture.md §11.5). */
  enum Family {
    /** Markup context or composition: the verifier's. A finding here is a verifier soundness bug. */
    AB,
    /** Script lexical position (#332). */
    C,
    /** Client frameworks (#333-#336). */
    D,
    /** URL and CSRF policy (#313). */
    E,
    /**
     * Not a finding: a {@code CONTEXT_MISMATCH} where the value is inert by the verifier's rule
     * table. A value, whatever its escaper, in the raw text of an element that never runs ({@code
     * noscript} with scripting on, {@code iframe}, {@code xmp}, {@code noembed}, {@code noframes};
     * Rules.hole's first rule; seed family {@code OVER}), or an attribute value the parser dropped
     * with its start tag (an element the insertion mode ignores, such as {@code <span>} under classic
     * {@code <select>} parsing). Counted, and listed in the report.
     */
    INERT
  }

  /**
   * The kinds a known-holes line may name: families C, D and E, and the Node job's {@link
   * Finding.Kind#ENGINE_DRIFT} (#399; for a Node engine only, see {@link #holes}).
   */
  static final Set<Finding.Kind> LISTABLE =
      Collections.unmodifiableSet(EnumSet.of(
          Finding.Kind.JS_NOT_LITERAL, Finding.Kind.JS_UNPARSABLE, Finding.Kind.CSTI,
          Finding.Kind.CROSS_ORIGIN_RESOURCE, Finding.Kind.JS_URL, Finding.Kind.LIVE_SCRIPT,
          Finding.Kind.ENGINE_DRIFT));

  /** The engines this gate runs; a known-holes line for another engine is the Node job's. */
  static final Set<String> ENGINES = Set.of(SpecParsers.VNU_26, ScriptInvariant.ENGINE, "*");

  /** The Node job's engines (#322), the only ones an {@code ENGINE_DRIFT} line may name. */
  static final Set<String> NODE_ENGINES = Set.of("parse5", "chromium", "firefox", "webkit");

  /** The #320 values (security-model.md §4), {@code PWN} standing for the slot's marker. */
  static final List<String> SEED_VALUES =
      List.of("PWN()", "\"+PWN()+\"", "\nPWN()//", " onerror=PWN() ", "{{PWN()}}", "javascript:PWN()",
          "\\evil.example", "/evil.example", "//evil.example", "img src=x onerror=PWN()");

  /**
   * Values the tree builder reads (#399): {@code input type=hidden} stays in a table where any other
   * input is foster-parented; {@code annotation-xml encoding=text/html} or {@code
   * application/xhtml+xml} makes it an HTML integration point; {@code font} with {@code
   * color}/{@code face}/{@code size} breaks out of foreign content (the attribute's presence decides,
   * these are ordinary values for it). Then two that break out with {@code </}: the #320 values
   * have none, so without these only the fingerprint brought {@code </} to the browsers.
   */
  static final List<String> STRUCTURE_VALUES =
      List.of("hidden", "text/html", "application/xhtml+xml", "red", "Arial", "7",
          "</td></tr></table><img src=x onerror=PWN()>",
          "</textarea></title></style></noscript></script></svg></math><script>PWN()</script>");

  /**
   * The hostile values: the #320 values, the {@link #STRUCTURE_VALUES}, then {@link
   * HostileStrings#VECTORS} with {@code alert(1)} written {@code PWN()}. {@code PWN} becomes each
   * String slot's marker. The export takes the first {@code tessera.oracle.export.values}, so the
   * Node job gets the first two groups ({@link #EXPORT_DEFAULT}).
   */
  static final List<String> HOSTILE;

  /** How many hostile values the Node job's export takes by default: the #320 and structure values. */
  static final int EXPORT_DEFAULT = SEED_VALUES.size() + STRUCTURE_VALUES.size();

  /** The prototype's stand-ins ({@code Validator.STANDINS}, research memo §3). */
  static final List<String> STANDINS =
      List.of("", "x", " ", "{", "{{a}}", "<script>alert(1)</script>&amp", "\"'`/-->]]>", "\u0000\n\r");

  /** What the lemma feeds at each hole: hostile values, stand-ins, a fingerprint, seeded random strings. */
  static final List<String> LEMMA_VALUES;

  static {
    Set<String> hostile = new LinkedHashSet<>(SEED_VALUES);
    hostile.addAll(STRUCTURE_VALUES);
    for (String v : HostileStrings.VECTORS) {
      hostile.add(v.replace("alert(1)", "PWN()"));
    }
    HOSTILE = List.copyOf(hostile);
    Set<String> lemma = new LinkedHashSet<>();
    for (String v : HOSTILE) {
      lemma.add(v.replace("PWN", HtmlInvariant.marker(1)));
    }
    lemma.addAll(STANDINS);
    lemma.add(Fingerprint.value(1));
    Random random = new Random(324);
    for (int i = 0; i < 64; i++) {
      lemma.add(HostileStrings.random(random, 12));
    }
    LEMMA_VALUES = List.copyOf(lemma);
  }

  // ---- cases ----

  /**
   * One case and what the gate needs to fill it.
   *
   * @param params the template's parameters, in declaration order
   * @param expectation the seed's line in {@code seed-expectations.txt} ({@code VERIFIER},
   *     {@code PROFILE} or {@code ACCEPT}), or null for a generated case
   */
  record GateCase(OracleCase oracleCase, List<Shape.Param> params, String expectation) {
    String view() {
      return "c" + oracleCase.id();
    }
  }

  private static final Pattern PARAMS = Pattern.compile("t:params=\"([^\"]*)\"");

  /** The parameters a template's root declares. */
  static List<Shape.Param> params(String template) {
    Matcher m = PARAMS.matcher(template);
    if (!m.find()) {
      return List.of();
    }
    List<Shape.Param> out = new ArrayList<>();
    for (String p : m.group(1).split(",")) {
      String[] tn = p.strip().split("\\s+");
      if (tn.length != 2) {
        throw new IllegalArgumentException("can't read parameter '" + p + "' of " + template);
      }
      out.add(new Shape.Param(tn[0], tn[1]));
    }
    return out;
  }

  /** Each seed's expectation in {@code verify/seed-expectations.txt} (#367): name to VERIFIER, PROFILE or ACCEPT. */
  static Map<String, String> seedExpectations() {
    Map<String, String> out = new HashMap<>();
    for (String line : resource("verify/seed-expectations.txt").split("\n")) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      String[] f = line.strip().split("\\s+");
      out.put(f[0], f[2]);
    }
    return out;
  }

  /**
   * The seeds, then the generator's cases (default enumeration, the {@link Deciding} positions, or
   * explore mode), each id once.
   */
  static List<GateCase> cases() {
    Map<String, String> expect = seedExpectations();
    Map<String, GateCase> out = new LinkedHashMap<>();
    List<String> seedTemplates = new ArrayList<>();
    for (Seeds.Seed s : Seeds.load()) {
      OracleCase c = s.toCase();
      out.putIfAbsent(c.id(), new GateCase(c, params(c.template()), expect.get(s.name())));
      seedTemplates.add(s.template());
    }
    List<GeneratedCase> generated = new ArrayList<>(Generator.defaultCases());
    generated.addAll(Deciding.cases());
    generated.addAll(Generator.exploreFromSystemProperties(seedTemplates));
    for (GeneratedCase g : generated) {
      out.putIfAbsent(g.oracleCase().id(), new GateCase(g.oracleCase(), g.params(), null));
    }
    return List.copyOf(out.values());
  }

  // ---- compiling ----

  /** Which layer rejected a case. */
  enum Layer {
    /** The profile: the parser, the context tables, the type checker. Nothing was verified. */
    PROFILE,
    /** The context verifier ({@code [verifier:RULE]}). */
    VERIFIER
  }

  /** A compiled case: accepted (with its compilation), or rejected (with its errors and layer). */
  record Compiled(GateCase gateCase, Compilation compilation, List<String> errors, Layer layer) {
    boolean accepted() {
      return compilation != null;
    }
  }

  private static final Pattern ERROR_FILE = Pattern.compile("^(c[0-9a-f]{12})\\.html:");

  /**
   * Compiles {@code cases} in batches of {@link #BATCH}. The processor runs the verifier only when
   * no template has a profile error, so a failing batch drops the templates its errors name and
   * compiles the rest again; errors that name no template send the batch one template at a time.
   */
  static List<Compiled> compile(List<GateCase> cases) {
    List<Compiled> out = new ArrayList<>();
    for (int from = 0; from < cases.size(); from += BATCH) {
      out.addAll(compileBatch(cases.subList(from, Math.min(cases.size(), from + BATCH))));
    }
    return out;
  }

  private static List<Compiled> compileBatch(List<GateCase> batch) {
    Map<String, Compiled> done = new HashMap<>();
    List<GateCase> todo = new ArrayList<>(batch);
    while (!todo.isEmpty()) {
      Compilation.Builder b = Compilation.builder();
      for (GateCase g : todo) {
        b.template(g.view() + ".html", g.oracleCase().template());
      }
      Compilation c = b.compile();
      if (c.success()) {
        for (GateCase g : todo) {
          done.put(g.view(), new Compiled(g, c, List.of(), null));
        }
        break;
      }
      Map<String, List<String>> byView = new HashMap<>();
      for (String e : c.errors()) {
        Matcher m = ERROR_FILE.matcher(e);
        if (m.find()) {
          byView.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(e);
        }
      }
      List<GateCase> rest = new ArrayList<>();
      for (GateCase g : todo) {
        List<String> errors = byView.get(g.view());
        if (errors != null) {
          done.put(g.view(), new Compiled(g, null, errors, layer(errors)));
        } else {
          rest.add(g);
        }
      }
      if (rest.size() == todo.size()) {
        // Errors that name no template here: one at a time.
        for (GateCase g : todo) {
          Compilation alone = Compilation.builder().template(g.view() + ".html", g.oracleCase().template()).compile();
          done.put(g.view(), alone.success()
              ? new Compiled(g, alone, List.of(), null)
              : new Compiled(g, null, alone.errors(), layer(alone.errors())));
        }
        break;
      }
      todo = rest;
    }
    List<Compiled> out = new ArrayList<>();
    for (GateCase g : batch) {
      out.add(done.get(g.view()));
    }
    return out;
  }

  private static Layer layer(List<String> errors) {
    return errors.stream().anyMatch(e -> e.contains("[verifier:")) ? Layer.VERIFIER : Layer.PROFILE;
  }

  /** An error that names file, line and column ({@code c….html:3:7: ...}). */
  private static final Pattern POSITIONED = Pattern.compile("^c[0-9a-f]{12}\\.html:[0-9]+:[0-9]+: ");

  // ---- rendering ----

  /** Renders a model; the gate's default renders the compiled view as {@code Compilation.render} does. */
  @FunctionalInterface
  interface Renderer {
    String render(Map<String, Object> model);
  }

  /** Renders {@code view} of {@code c}, without {@code Compilation.render}'s own skeleton assertion. */
  static Renderer renderer(Compilation c, String view) {
    TemplateEntry entry = c.registry().find(view).orElseThrow(() -> new AssertionError("no template " + view));
    return model -> {
      StringOutput out = new StringOutput();
      entry.renderer().render(model, RenderContext.defaults(), out);
      return out.toString();
    };
  }

  /** A fixed {@code Fragment} argument: rendered the same in every render of a case. */
  private static final Fragment FRAGMENT = TestFragments.markup("<b>fragment</b>");

  /**
   * A model for {@code params}: String slot {@code i} (1-based, in declaration order) gets {@code
   * value(i)}, an {@code int} -1, a {@code SafeHtml} and a {@code Fragment} fixed benign markup (their
   * producers are #329's).
   */
  static Map<String, Object> model(List<Shape.Param> params, java.util.function.IntFunction<String> value) {
    Map<String, Object> m = new HashMap<>();
    int slot = 0;
    for (Shape.Param p : params) {
      switch (p.type()) {
        case "String" -> m.put(p.name(), value.apply(++slot));
        case "int", "Integer", "long" -> m.put(p.name(), -1);
        case "SafeHtml" -> m.put(p.name(), SafeHtml.sanitize("<b>safe</b>"));
        case "Fragment" -> m.put(p.name(), FRAGMENT);
        default -> throw new IllegalArgumentException("the gate can't fill a " + p.type() + " parameter");
      }
    }
    return m;
  }

  // ---- the inertness lemma ----

  /** An escaper as the lemma feeds it: the escaped text, or null if the escaper refuses the value. */
  @FunctionalInterface
  interface Escape {
    String apply(Skeleton.Escaper escaper, String value);
  }

  private static final Map<Skeleton.Escaper, Method> ESCAPERS = new EnumMap<>(Skeleton.Escaper.class);

  static {
    for (Skeleton.Escaper e : Skeleton.Escaper.values()) {
      try {
        ESCAPERS.put(e, Escapers.class.getMethod(e.method(), Output.class, Object.class));
      } catch (NoSuchMethodException x) {
        throw new ExceptionInInitializerError(x);
      }
    }
  }

  /** The runtime's escapers ({@code Escapers.<method>}; reflection in test code). */
  static final Escape RUNTIME = (escaper, value) -> {
    StringOutput out = new StringOutput();
    try {
      ESCAPERS.get(escaper).invoke(null, out, value);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof IllegalArgumentException) {
        return null; // refused: nothing reaches the output
      }
      throw new IllegalStateException(e.getCause());
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
    return out.toString();
  };

  /** One parser the lemma runs: an engine, scripting on or off, document or {@code <body>} fragment parsing. */
  record LemmaParser(Engine engine, boolean scripting, boolean fragment) {
    Parser start() throws EngineException {
      return fragment
          ? engine.fragment(HtmlInvariant.HTML, "body", scripting)
          : engine.document(scripting);
    }

    @Override
    public String toString() {
      return engine.name() + (scripting ? " scripting-on" : " scripting-off") + (fragment ? " fragment(body)" : " document");
    }
  }

  static final List<LemmaParser> LEMMA_PARSERS;

  static {
    List<LemmaParser> ps = new ArrayList<>();
    Engine e = Engines.CURRENT;
    for (boolean scripting : new boolean[] {true, false}) {
      for (boolean fragment : new boolean[] {false, true}) {
        ps.add(new LemmaParser(e, scripting, fragment));
      }
    }
    LEMMA_PARSERS = List.copyOf(ps);
  }

  /**
   * The parts of a state the lemma compares: the signature without the input pending inside a
   * token (an attribute hole is inside its start tag: the tokenizer state and attribute name say
   * whether the value left the value), {@code lastCR} and the drop-next-LF flag (they only decide
   * whether a following LF is text, never structure; #390's soundness question 3) and {@code
   * frameset-ok} (not in the signature; the verifier rejects {@code <frameset>}).
   */
  static String lemmaKey(ParserState s) {
    return String.join("|",
        s.engine(), String.valueOf(s.fragmentContext()), String.valueOf(s.scripting()),
        String.valueOf(s.tokenizer()), String.valueOf(s.attributeName()), String.valueOf(s.endTagExpectation()),
        String.valueOf(s.mode()), String.valueOf(s.originalMode()), String.valueOf(s.stack()),
        String.valueOf(s.activeFormatting()), String.valueOf(s.activeFormattingElements()),
        String.valueOf(s.templateModes()),
        String.valueOf(s.formPointer()), String.valueOf(s.headPointer()), String.valueOf(s.quirks()),
        String.valueOf(s.pendingTableText()));
  }

  /** The lemma's memo: (signature before the hole, escaper) to the first value that isn't inert there, or "". */
  static final class LemmaMemo {
    final Map<String, String> verdicts = new ConcurrentHashMap<>();
    final java.util.concurrent.atomic.AtomicLong feeds = new java.util.concurrent.atomic.AtomicLong();
  }

  /**
   * Checks the lemma along one render: at each hole of one derivation of {@code output} through
   * the skeleton, with every {@link #LEMMA_PARSERS parser}. Returns failure messages.
   */
  static List<String> lemma(Skeleton.Seq body, java.util.function.Function<Skeleton.FragmentId, Skeleton.Seq> fragments,
      String output, Escape escape, LemmaMemo memo) {
    List<SkeletonMatcher.Span> spans = SkeletonMatcher.holes(body, output, fragments);
    if (spans == null) {
      return List.of("fidelity: the render is not in its skeleton, so the lemma has no holes to check");
    }
    List<String> failures = new ArrayList<>();
    for (LemmaParser lp : LEMMA_PARSERS) {
      try {
        Parser p = lp.start();
        int at = 0;
        for (SkeletonMatcher.Span s : spans) {
          p.feed(output.substring(at, s.start()));
          at = s.start();
          ParserState before = p.state();
          Skeleton.Escaper escaper = s.hole().escaper();
          String key = before.signature() + "\n" + escaper;
          String bad = memo.verdicts.get(key);
          if (bad == null) {
            bad = firstNotInert(p, before, escaper, escape, memo);
            memo.verdicts.putIfAbsent(key, bad);
          }
          if (!bad.isEmpty()) {
            failures.add("lemma: hole " + s.hole() + " (" + escaper.method() + ") at offset " + s.start()
                + ", " + lp + ": " + bad);
          }
        }
      } catch (EngineException e) {
        failures.add("lemma: " + lp + " failed: " + e.getMessage());
      }
    }
    return failures;
  }

  private static String firstNotInert(Parser p, ParserState before, Skeleton.Escaper escaper, Escape escape, LemmaMemo memo)
      throws EngineException {
    String want = lemmaKey(before);
    for (String v : LEMMA_VALUES) {
      String value = escaper == Skeleton.Escaper.REFRESH ? "0;url=/" + v : v;
      String written = escape.apply(escaper, value);
      if (written == null) {
        continue;
      }
      Parser q = p.fork();
      q.feed(written);
      memo.feeds.incrementAndGet();
      ParserState after = q.state();
      if (!lemmaKey(after).equals(want)) {
        return "value " + show(value) + " written as " + show(written) + " changes the parser state from "
            + before.signature() + " to " + after.signature();
      }
    }
    return "";
  }

  // ---- classifying and ratcheting findings ----

  /** A finding with its family. */
  record Classified(Finding finding, Family family) {}

  /** The family of an {@link HtmlInvariant} or {@link ContextCheck} finding. */
  static Family family(Finding f, List<HtmlInvariant.ScriptSlot> slots) {
    return switch (f.kind()) {
      case JS_NOT_LITERAL, JS_UNPARSABLE -> Family.C;
      case CSTI -> Family.D;
      // Never reported here: the Node job compares the vnu trees with parse5 and the browsers.
      case ENGINE_DRIFT -> Family.AB;
      case JS_URL, CROSS_ORIGIN_RESOURCE -> Family.E;
      // Family C only inside the data of an HTML script that holds a slot in the benign tree too;
      // anywhere else the value made or reached a script through markup.
      case LIVE_SCRIPT -> slots.stream().anyMatch(s -> s.engine().equals(f.engine()) && s.where().equals(f.where())
          && htmlScript(s)) ? Family.C : Family.AB;
      case STRUCTURE_CHANGED, MARKER_IN_NAME, LIVE_HANDLER, CONTEXT_MISMATCH -> Family.AB;
    };
  }

  private static final Pattern NEVER_RUN_RAW_TEXT =
      Pattern.compile("escaping in raw-text element text \\(<(noscript|iframe|xmp|noembed|noframes)>\\)$");
  private static final Pattern NOWHERE =
      Pattern.compile("^\\(nowhere\\): marker (" + Fingerprint.PREFIX + "[0-9]{4}) is in the render but not in the parsed tree$");

  /**
   * The family of a {@link ContextCheck} finding on {@code render} (the fingerprint render), whose
   * holes are at {@code spans}: {@link Family#INERT} for any escaping in never-run raw text, and
   * for a marker the parser dropped when every occurrence of it is in an attribute hole (an
   * attribute value goes nowhere but its element, so the element was ignored); A/B otherwise.
   */
  static Family contextFamily(Finding f, String render, List<SkeletonMatcher.Span> spans) {
    if (f.kind() != Finding.Kind.CONTEXT_MISMATCH) {
      return family(f, List.of());
    }
    String where = f.where().replaceFirst("^scripting-o(n|ff) ", "");
    Matcher raw = NEVER_RUN_RAW_TEXT.matcher(where);
    // Rules.hole's first rule: any escaper is inert in the raw text of an element a browser never
    // runs or renders (no escaper writes '<', and the lemma checks the value stays inside). A
    // <noscript> is raw text only with scripting on.
    if (raw.find() && (!raw.group(1).equals("noscript") || f.where().startsWith("scripting-on "))) {
      return Family.INERT;
    }
    Matcher m = NOWHERE.matcher(where);
    if (m.find() && spans != null) {
      boolean attributes = true;
      for (int at = render.indexOf(m.group(1)); at >= 0 && attributes; at = render.indexOf(m.group(1), at + 1)) {
        boolean inAttribute = false;
        for (SkeletonMatcher.Span s : spans) {
          inAttribute |= s.start() <= at && at < s.end() && s.hole().escaper().inAttribute();
        }
        attributes = inAttribute;
      }
      if (attributes) {
        return Family.INERT;
      }
    }
    return Family.AB;
  }

  /** Whether a script slot is an HTML {@code <script>} (its path's last step has no namespace). */
  static boolean htmlScript(HtmlInvariant.ScriptSlot s) {
    String path = s.where();
    String last = path.substring(path.lastIndexOf('/') + 1);
    return last.startsWith("script") && !last.contains("[");
  }

  /**
   * The family of a {@link ScriptInvariant} finding on {@code slot}: family C only in an HTML
   * {@code <script>}. A script hole anywhere else (SVG {@code <script>}, say) is one the verifier
   * must reject ({@code JSON_NOT_IN_SCRIPT}), so any finding there is A/B, not a listable C hole.
   */
  static Family scriptFamily(Finding f, HtmlInvariant.ScriptSlot slot) {
    if (!htmlScript(slot)) {
      return Family.AB;
    }
    return f.kind() == Finding.Kind.LIVE_SCRIPT ? Family.C : family(f, List.of());
  }

  /**
   * Loads a known-holes file for this gate: {@link KnownHoles#parse}'s format, and only {@link
   * #LISTABLE} kinds.
   *
   * @throws IllegalArgumentException naming the file and line on a format error
   */
  static KnownHoles holes(String file, String text) {
    KnownHoles holes = KnownHoles.parse(file, text);
    String[] lines = text.split("\r?\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] f = line.split("\\s+");
      String kind = f[1];
      if (!LISTABLE.contains(Finding.Kind.valueOf(kind))) {
        throw new IllegalArgumentException(file + ":" + (i + 1) + ": " + kind + " is a family A/B kind: the verifier"
            + " (#367) must reject the template, so it can't be a known hole; only " + LISTABLE + " may be listed");
      }
      if (kind.equals(Finding.Kind.ENGINE_DRIFT.name()) && !NODE_ENGINES.contains(f[2])) {
        throw new IllegalArgumentException(file + ":" + (i + 1) + ": an ENGINE_DRIFT line names the engine that"
            + " drifts from vnu, one of " + new TreeSet<>(NODE_ENGINES) + ", not " + f[2]);
      }
    }
    return holes;
  }

  /** What the ratchet found: failures, and the lines it used. */
  static final class Ratchet {
    final KnownHoles holes;
    final String file;
    final Set<KnownHoles.Entry> used = ConcurrentHashMap.newKeySet();

    Ratchet(KnownHoles holes, String file) {
      this.holes = holes;
      this.file = file;
    }

    /** The failures for one case's findings: A/B findings, and C/D/E findings with no line. */
    List<String> check(OracleCase c, List<Classified> findings) {
      List<String> failures = new ArrayList<>();
      Set<String> reported = new HashSet<>();
      for (Classified cf : findings) {
        Finding f = cf.finding();
        String key = c.id() + " " + f.kind() + " " + f.engine() + " " + cf.family();
        if (cf.family() == Family.AB) {
          if (reported.add(key)) {
            failures.add("verifier soundness bug (family A/B, can't be listed in " + file + "): " + c.id() + " ("
                + c.name() + ") " + f.kind() + " " + f.engine() + " at " + f.where() + "; value: " + show(f.value())
                + "; template: " + show(c.template()));
          }
          continue;
        }
        KnownHoles.Entry e = holes.find(c.id(), f);
        if (e != null) {
          used.add(e);
        } else if (reported.add(key)) {
          failures.add("new hole: " + c.id() + " (" + c.name() + ") " + f.kind() + " " + f.engine() + " at " + f.where()
              + "; value: " + show(f.value()) + "; template: " + show(c.template()) + "; fix it, or if it is an"
              + " already-tracked family " + cf.family() + " hole add `" + c.id() + " " + f.kind() + " " + f.engine()
              + " #<issue>` to " + file);
        }
      }
      return failures;
    }

    /** Lines for this gate's engines that matched nothing, for cases in {@code ran} (all if null). */
    List<String> stale(Set<String> ran) {
      return stale(ran, null);
    }

    /**
     * {@link #stale(Set)}, and {@code ENGINE_DRIFT} lines (the Node job's) whose case isn't in
     * {@code accepted} (unless null): once the verifier rejects the case (the fix for the drift,
     * #398), nothing is exported to drift, so the line must go in the same change, not after the
     * next Node run.
     */
    List<String> stale(Set<String> ran, Set<String> accepted) {
      List<String> failures = new ArrayList<>();
      for (KnownHoles.Entry e : holes.entries()) {
        boolean inRun = ran == null || ran.contains(e.caseId());
        if (!used.contains(e) && ENGINES.contains(e.engine()) && inRun) {
          failures.add("known hole " + e.caseId() + " " + e.kind() + " " + e.engine() + " #" + e.issue()
              + " no longer occurs (fixed, rejected, or the case is gone): delete its line from " + file);
        } else if (e.kind() == Finding.Kind.ENGINE_DRIFT && inRun && accepted != null && !accepted.contains(e.caseId())) {
          failures.add("known hole " + e.caseId() + " ENGINE_DRIFT " + e.engine() + " #" + e.issue()
              + ": the case is rejected now (or gone), so no engine can drift on it: delete its line from " + file);
        }
      }
      return failures;
    }
  }

  // ---- one case ----

  /** What the gate found for one case. */
  static final class CaseResult {
    final GateCase gateCase;
    final List<Classified> findings = new ArrayList<>();
    /** {@link Family#INERT} context mismatches: counted, not findings. */
    final List<Classified> inert = new ArrayList<>();
    /** Fidelity and lemma failures, render errors: never listable. */
    final List<String> failures = new ArrayList<>();
    final List<ExportRecord> export = new ArrayList<>();
    int renders;
    int refused;
    int lemmaPaths;

    CaseResult(GateCase gateCase) {
      this.gateCase = gateCase;
    }

    Verdict verdict() {
      List<Finding> fs = findings.stream().map(Classified::finding).toList();
      return fs.isEmpty() ? new Verdict.Clean() : new Verdict.Findings(fs);
    }
  }

  /** Everything one accepted case needs; tests replace the parts. */
  record Subject(GateCase gateCase, Renderer renderer, Skeleton.TemplateSkeleton skeleton,
      java.util.function.Function<Skeleton.FragmentId, Skeleton.Seq> fragments) {

    static Subject of(GateCase g, Compilation c) {
      return new Subject(g, ProfileOracleTest.renderer(c, g.view()), c.skeleton(g.view()), c::fragmentSkeleton);
    }
  }

  /**
   * Renders and checks one case: fidelity of every render, the lemma along every distinct render
   * path, and the value checks. {@code exportValues} hostile values per case go to the export
   * (negative: none).
   */
  static CaseResult check(Subject subject, Escape escape, LemmaMemo memo, int exportValues) {
    GateCase g = subject.gateCase();
    OracleCase oc = g.oracleCase();
    CaseResult r = new CaseResult(g);
    boolean contextRelative = oc.template().contains("@{/");
    Set<String> paths = new HashSet<>();

    String benign = render(subject, model(g.params(), HtmlInvariant::marker), escape, memo, paths, r, "benign");
    if (benign == null) {
      return r;
    }
    String fingerprint = render(subject, model(g.params(), Fingerprint::value), escape, memo, paths, r, "fingerprint");
    if (fingerprint != null) {
      List<SkeletonMatcher.Span> spans = SkeletonMatcher.holes(subject.skeleton().body(), fingerprint, subject.fragments());
      for (Finding f : ContextCheck.check(fingerprint, SpecParsers.all())) {
        Family family = contextFamily(f, fingerprint, spans);
        (family == Family.INERT ? r.inert : r.findings).add(new Classified(f, family));
      }
    }
    List<String[]> hostile = new ArrayList<>();
    for (String v : HOSTILE) {
      String shown = v.replace("PWN", HtmlInvariant.marker(1));
      String out = render(subject, model(g.params(), i -> v.replace("PWN", HtmlInvariant.marker(i))), escape, memo,
          paths, r, "value " + show(shown));
      if (out == null) {
        continue;
      }
      hostile.add(new String[] {shown, out});
      HtmlInvariant.Result res = HtmlInvariant.check(benign, out, shown, contextRelative);
      for (Finding f : res.findings()) {
        r.findings.add(new Classified(f, family(f, res.scriptSlots())));
      }
      for (HtmlInvariant.ScriptSlot slot : res.scriptSlots()) {
        if (slot.hostile() == null) {
          continue; // the script is gone: the HTML check reports the structure change
        }
        for (Finding f : ScriptInvariant.check(slot.benign(), slot.hostile(), slot.markers(), slot.where(), shown)) {
          r.findings.add(new Classified(f, scriptFamily(f, slot)));
        }
      }
    }
    r.lemmaPaths = paths.size();
    Verdict verdict = r.verdict();
    if (exportValues >= 0) {
      r.export.add(ExportRecord.of(oc, HtmlInvariant.marker(1), benign, verdict));
      if (fingerprint != null) {
        r.export.add(ExportRecord.of(oc, Fingerprint.value(1), fingerprint, verdict));
      }
      for (String[] h : hostile.subList(0, Math.min(exportValues, hostile.size()))) {
        r.export.add(ExportRecord.of(oc, h[0], h[1], verdict));
      }
    }
    return r;
  }

  /**
   * One render: fidelity, then the lemma if this render takes a path through the skeleton not
   * seen yet in this case. Null if the render failed (an escaper refused the value: nothing is
   * written, so that is not a finding; any other exception is a failure).
   */
  private static String render(Subject s, Map<String, Object> model, Escape escape, LemmaMemo memo, Set<String> paths,
      CaseResult r, String what) {
    String out;
    try {
      out = s.renderer().render(model);
    } catch (IllegalArgumentException e) {
      r.refused++;
      return null;
    } catch (RuntimeException e) {
      r.failures.add("render error (" + what + "): " + e);
      return null;
    }
    r.renders++;
    List<SkeletonMatcher.Span> spans = SkeletonMatcher.holes(s.skeleton().body(), out, s.fragments());
    if (spans == null) {
      SkeletonMatcher.Result m = SkeletonMatcher.match(s.skeleton().body(), out, s.fragments());
      r.failures.add("fidelity: the render (" + what + ") is not in the skeleton at offset " + m.offset() + ": "
          + show(out.substring(Math.max(0, m.offset() - 20), Math.min(out.length(), m.offset() + 20)))
          + "; expected: " + m.expected() + "; output: " + show(out));
      return out; // still value-checked; there are no holes to check the lemma at
    }
    // The path: the render with each hole's text taken out, and which holes. By the lemma the parser
    // states at the holes depend only on that.
    StringBuilder path = new StringBuilder();
    int at = 0;
    for (SkeletonMatcher.Span sp : spans) {
      path.append(out, at, sp.start()).append('\u0000').append(System.identityHashCode(sp.hole())).append('\u0000');
      at = sp.end();
    }
    path.append(out, at, out.length());
    if (paths.add(path.toString())) {
      r.failures.addAll(lemma(s.skeleton().body(), s.fragments(), out, escape, memo));
    }
    return out;
  }

  // ---- the gate ----

  @Test
  void gate() throws IOException {
    // Excluded from the release-8 run in the pom; this also holds when -Dtest overrides excludes.
    assumeTrue(System.getProperty("tessera.test.release") == null, "the gate runs in the default execution only");
    long start = System.nanoTime();
    List<GateCase> cases = cases();
    long t0 = System.nanoTime();
    List<Compiled> compiled = compile(cases);
    long t1 = System.nanoTime();

    String holesFile = "src/test/resources/" + KnownHoles.RESOURCE;
    Ratchet ratchet = new Ratchet(holes(holesFile, resource(KnownHoles.RESOURCE)), holesFile);
    LemmaMemo memo = new LemmaMemo();
    String exportFile = System.getProperty(EXPORT);
    int exportValues = exportFile == null ? -1 : Integer.getInteger(EXPORT_VALUES, EXPORT_DEFAULT);

    List<Compiled> accepted = compiled.stream().filter(Compiled::accepted).toList();
    CaseResult[] results = new CaseResult[accepted.size()];
    ForkJoinPool pool = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors()));
    try {
      pool.submit(() -> IntStream.range(0, accepted.size()).parallel().forEach(i -> {
        Compiled c = accepted.get(i);
        try {
          results[i] = check(Subject.of(c.gateCase(), c.compilation()), RUNTIME, memo, exportValues);
        } catch (RuntimeException | AssertionError e) {
          CaseResult r = new CaseResult(c.gateCase());
          r.failures.add("the gate failed on this case: " + e);
          results[i] = r;
        }
      })).get();
    } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
      throw new IllegalStateException(e);
    }
    long t2 = System.nanoTime();

    // Failures, per case in case order.
    Map<String, List<String>> failures = new LinkedHashMap<>();
    for (Compiled c : compiled) {
      GateCase g = c.gateCase();
      List<String> fs = new ArrayList<>();
      if (!c.accepted()) {
        for (String e : c.errors()) {
          if (!POSITIONED.matcher(e).find()) {
            fs.add("compiler error without file, line and column: " + e);
          }
        }
      } else if (g.expectation() != null && !g.expectation().equals("ACCEPT")) {
        fs.add("seed-expectations.txt says " + g.expectation() + " rejects this seed, but it compiled");
      }
      if (!fs.isEmpty()) {
        failures.computeIfAbsent(label(g), k -> new ArrayList<>()).addAll(fs);
      }
    }
    Map<Family, Map<String, Integer>> byFamily = new EnumMap<>(Family.class);
    int renders = 0;
    int refused = 0;
    int lemmaPaths = 0;
    int clean = 0;
    List<String> inert = new ArrayList<>();
    for (CaseResult r : results) {
      for (Classified cf : r.inert) {
        inert.add(label(r.gateCase) + " " + cf.finding().engine() + " " + cf.finding().where() + "; template: "
            + show(r.gateCase.oracleCase().template()));
      }
      renders += r.renders;
      refused += r.refused;
      lemmaPaths += r.lemmaPaths;
      List<String> fs = new ArrayList<>(r.failures);
      fs.addAll(ratchet.check(r.gateCase.oracleCase(), r.findings));
      if (!fs.isEmpty()) {
        failures.computeIfAbsent(label(r.gateCase), k -> new ArrayList<>()).addAll(fs);
      }
      if (r.findings.isEmpty()) {
        clean++;
      }
      Set<String> seen = new HashSet<>();
      List<Classified> all = new ArrayList<>(r.findings);
      all.addAll(r.inert);
      for (Classified cf : all) {
        String k = cf.finding().kind() + " " + cf.finding().engine();
        if (seen.add(cf.family() + k)) {
          byFamily.computeIfAbsent(cf.family(), x -> new TreeMap<>()).merge(k, 1, Integer::sum);
        }
      }
    }
    boolean partialRun = System.getProperty(Generator.MAX_CASES) != null || System.getProperty(Generator.EXPLORE) != null;
    Set<String> ran = new HashSet<>();
    for (Compiled c : compiled) {
      ran.add(c.gateCase().oracleCase().id());
    }
    Set<String> acceptedIds = new HashSet<>();
    for (Compiled c : accepted) {
      acceptedIds.add(c.gateCase().oracleCase().id());
    }
    List<String> stale = ratchet.stale(partialRun ? ran : null, acceptedIds);
    if (!stale.isEmpty()) {
      failures.put("known-holes.txt", stale);
    }

    // The rejected side: verifier rejections the value oracle finds safe.
    List<String> falsePositives = falsePositiveCandidates(compiled);
    long t3 = System.nanoTime();

    List<String> report = report(cases, compiled, clean, renders, refused, lemmaPaths, memo, byFamily, falsePositives,
        inert, failures, new long[] {t0 - start, t1 - t0, t2 - t1, t3 - t2});
    Path dir = Files.createDirectories(Path.of("target", "oracle"));
    Files.write(dir.resolve("report.txt"), report, StandardCharsets.UTF_8);
    System.out.println(String.join("\n", report.subList(0, Math.max(0, report.indexOf("")))));

    if (exportFile != null) {
      List<String> lines = new ArrayList<>();
      int k = 0;
      for (Compiled c : compiled) {
        if (!c.accepted()) {
          lines.add(new ExportRecord(c.gateCase().oracleCase().id(), c.gateCase().oracleCase().name(),
              c.gateCase().oracleCase().template(), null, null, "REJECTED").toJsonLine());
        } else {
          for (ExportRecord e : results[k++].export) {
            lines.add(e.toJsonLine());
          }
        }
      }
      Path out = Path.of(exportFile);
      if (out.toAbsolutePath().getParent() != null) {
        Files.createDirectories(out.toAbsolutePath().getParent());
      }
      Files.write(out, lines, StandardCharsets.UTF_8);
      System.out.println("oracle gate: exported " + lines.size() + " records to " + out.toAbsolutePath());
    }

    if (!failures.isEmpty()) {
      StringBuilder sb = new StringBuilder("oracle gate: " + failures.values().stream().mapToInt(List::size).sum()
          + " failures in " + failures.size() + " cases (target/oracle/report.txt)\n");
      int shown = 0;
      for (Map.Entry<String, List<String>> e : failures.entrySet()) {
        for (String f : e.getValue()) {
          if (shown++ < 40) {
            sb.append(e.getKey()).append(": ").append(f).append('\n');
          }
        }
      }
      fail(sb.toString());
    }
  }

  /**
   * Compiles each case the verifier alone rejected without the verifier, renders it with the hostile
   * values, and lists those with no family A/B finding: the value oracle says they would have been
   * safe. Informational.
   */
  static List<String> falsePositiveCandidates(List<Compiled> compiled) {
    List<Compiled> verifier = compiled.stream().filter(c -> !c.accepted() && c.layer() == Layer.VERIFIER).toList();
    List<String> out = Collections.synchronizedList(new ArrayList<>());
    LemmaMemo memo = new LemmaMemo();
    verifier.parallelStream().forEach(c -> {
      GateCase g = c.gateCase();
      Compilation unverified =
          Compilation.builder().template(g.view() + ".html", g.oracleCase().template()).withoutVerifier().compile();
      if (!unverified.success()) {
        return;
      }
      CaseResult r;
      try {
        r = check(Subject.of(g, unverified), RUNTIME, memo, -1);
      } catch (RuntimeException | AssertionError e) {
        return;
      }
      boolean ab = r.findings.stream().anyMatch(f -> f.family() == Family.AB);
      boolean lemma = r.failures.stream().anyMatch(f -> f.startsWith("lemma:"));
      if (!ab && !lemma) {
        Set<String> rules = new TreeSet<>();
        Matcher m = Pattern.compile("\\[verifier:([A-Z_]+)\\]").matcher(String.join("\n", c.errors()));
        while (m.find()) {
          rules.add(m.group(1));
        }
        out.add(g.oracleCase().id() + " " + rules + " " + g.oracleCase().name() + " :: " + show(g.oracleCase().template()));
      }
    });
    List<String> sorted = new ArrayList<>(out);
    Collections.sort(sorted);
    return sorted;
  }

  private static List<String> report(List<GateCase> cases, List<Compiled> compiled, int clean, int renders, int refused,
      int lemmaPaths, LemmaMemo memo, Map<Family, Map<String, Integer>> byFamily, List<String> falsePositives,
      List<String> inert, Map<String, List<String>> failures, long[] nanos) {
    List<String> r = new ArrayList<>();
    long seeds = cases.stream().filter(c -> c.oracleCase().origin() == OracleCase.Origin.SEED).count();
    long accepted = compiled.stream().filter(Compiled::accepted).count();
    long profile = compiled.stream().filter(c -> !c.accepted() && c.layer() == Layer.PROFILE).count();
    long verifier = compiled.stream().filter(c -> !c.accepted() && c.layer() == Layer.VERIFIER).count();
    r.add("oracle gate (#324): " + cases.size() + " cases (" + seeds + " seeds, " + (cases.size() - seeds) + " generated)");
    r.add("  REJECTED " + (profile + verifier) + ": profile " + profile + ", verifier " + verifier);
    r.add("  accepted " + accepted + ": CLEAN " + clean + ", FINDINGS " + (accepted - clean));
    Set<String> deciding = new HashSet<>();
    for (GeneratedCase g : Deciding.cases()) {
      deciding.add(g.oracleCase().id());
    }
    List<Compiled> dc = compiled.stream().filter(c -> deciding.contains(c.gateCase().oracleCase().id())).toList();
    r.add("  of them the verifier-deciding positions (Deciding, #399): " + dc.size() + " cases, accepted "
        + dc.stream().filter(Compiled::accepted).count() + ", rejected by the verifier "
        + dc.stream().filter(c -> !c.accepted() && c.layer() == Layer.VERIFIER).count() + ", by the profile "
        + dc.stream().filter(c -> !c.accepted() && c.layer() == Layer.PROFILE).count());
    r.add("  renders " + renders + " (" + refused + " refused by an escaper), every one in its skeleton unless listed below");
    r.add("  lemma: " + lemmaPaths + " render paths, " + memo.verdicts.size() + " distinct (state, escaper) pairs, "
        + memo.feeds.get() + " escaped values fed, " + LEMMA_VALUES.size() + " values x " + LEMMA_PARSERS.size() + " parsers");
    for (Family f : Family.values()) {
      Map<String, Integer> kinds = byFamily.getOrDefault(f, Map.of());
      r.add("  family " + f + " findings (cases per kind and engine): " + (kinds.isEmpty() ? "none" : kinds));
    }
    r.add("  false-positive candidates (verifier rejections the value oracle finds safe, informational): "
        + falsePositives.size());
    r.add(String.format(Locale.ROOT, "  time: cases %.1f s, compile %.1f s, checks %.1f s, false positives %.1f s",
        nanos[0] / 1e9, nanos[1] / 1e9, nanos[2] / 1e9, nanos[3] / 1e9));
    r.add("  failures: " + failures.values().stream().mapToInt(List::size).sum());
    r.add("");
    r.add("failures:");
    failures.forEach((k, v) -> v.forEach(f -> r.add("  " + k + ": " + f)));
    r.add("");
    r.add("inert context mismatches (family INERT, counted, not findings):");
    inert.forEach(f -> r.add("  " + f));
    r.add("");
    r.add("false-positive candidates:");
    falsePositives.forEach(f -> r.add("  " + f));
    r.add("");
    r.add("rejected by the verifier:");
    compiled.stream().filter(c -> !c.accepted() && c.layer() == Layer.VERIFIER)
        .forEach(c -> r.add("  " + label(c.gateCase()) + ": " + c.errors().get(0)));
    r.add("");
    r.add("rejected by the profile:");
    compiled.stream().filter(c -> !c.accepted() && c.layer() == Layer.PROFILE)
        .forEach(c -> r.add("  " + label(c.gateCase()) + ": " + (c.errors().isEmpty() ? "(no error)" : c.errors().get(0))));
    return r;
  }

  private static String label(GateCase g) {
    return g.oracleCase().id() + " (" + g.oracleCase().name() + ")";
  }

  static String resource(String name) {
    try (InputStream in = ProfileOracleTest.class.getClassLoader().getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("missing test resource " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@code s} quoted, with controls and non-ASCII as {@code \}{@code uXXXX}, cut at 300 chars. */
  static String show(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < s.length() && i < 300; i++) {
      char c = s.charAt(i);
      if (c == '"' || c == '\\') {
        sb.append('\\').append(c);
      } else if (c < 0x20 || c >= 0x7f) {
        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
      } else {
        sb.append(c);
      }
    }
    return sb.append(s.length() > 300 ? "...\"" : "\"").toString();
  }
}
