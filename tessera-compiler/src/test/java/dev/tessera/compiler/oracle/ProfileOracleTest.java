package dev.tessera.compiler.oracle;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tessera.compiler.security.HostileStrings;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.compiler.verify.Skeleton;
import dev.tessera.compiler.verify.SkeletonMatcher;
import dev.tessera.compiler.verify.engine.EngineException;
import dev.tessera.compiler.verify.engine.Parser;
import dev.tessera.compiler.verify.engine.ParserState;
import dev.tessera.compiler.verify.engine.Vnu;
import dev.tessera.runtime.internal.Escapers;
import dev.tessera.runtime.internal.Output;
import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.SafeHtml;
import dev.tessera.runtime.internal.StringOutput;
import dev.tessera.runtime.internal.TemplateEntry;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The oracle gate (#324; security-architecture.md §11.3): tests the two assumptions the
 * build-time context verifier (#367) rests on, over every seed (#320).
 *
 * <p>The seeds are compiled in batches with the real processor, verifier included. For every seed
 * the compiler <b>accepts</b>, the gate renders it with the benign markers ({@code tqm0001}) and
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
 * </ol>
 *
 * Values are checked elsewhere: attribute, URL and sanitizer sinks and script values by {@code
 * HostileSweepTest}, escapers by {@code EscapersTest}/{@code EscaperFuzzTest}, and the renders this
 * gate exports by the Node job in real browsers. For every seed the compiler <b>rejects</b>, every
 * error must name file, line and column.
 *
 * <p>Writes {@code target/oracle/report.txt}. With {@code -Dtessera.oracle.export=<file>} it also
 * writes the JSONL the Node job (#322) reads ({@link ExportRecord}, with each render's vnu trees
 * for the engine-drift check, #399); {@code -Dtessera.oracle.export.values=<n>} keeps the first
 * {@code n} hostile values per case there (default {@link #EXPORT_DEFAULT}: the #320 values, then
 * the {@link #BREAKOUT_VALUES}), to fit the browsers' time budget.
 */
class ProfileOracleTest {

  static final String EXPORT = "tessera.oracle.export";
  static final String EXPORT_VALUES = "tessera.oracle.export.values";

  /** Templates per javac run. */
  static final int BATCH = 150;

  /** The #320 values (security-model.md §4), {@code PWN} standing for the slot's marker. */
  static final List<String> SEED_VALUES =
      List.of("PWN()", "\"+PWN()+\"", "\nPWN()//", " onerror=PWN() ", "{{PWN()}}", "javascript:PWN()",
          "\\evil.example", "/evil.example", "//evil.example", "img src=x onerror=PWN()");

  /**
   * Two values that try to break out of their element with {@code </}: the #320 values have none.
   * (The attributes the tree builder reads, such as {@code input type} or {@code annotation-xml
   * encoding}, are build errors since #678, so no value of theirs is exported.)
   */
  static final List<String> BREAKOUT_VALUES =
      List.of("</td></tr></table><img src=x onerror=PWN()>",
          "</textarea></title></style></noscript></script></svg></math><script>PWN()</script>");

  /**
   * The hostile values: the #320 values, the {@link #BREAKOUT_VALUES}, then {@link
   * HostileStrings#VECTORS} with {@code alert(1)} written {@code PWN()}. {@code PWN} becomes each
   * String slot's marker. The export takes the first {@code tessera.oracle.export.values}, so the
   * Node job gets the first two groups ({@link #EXPORT_DEFAULT}).
   */
  static final List<String> HOSTILE;

  /** How many hostile values the Node job's export takes by default: the #320 and break-out values. */
  static final int EXPORT_DEFAULT = SEED_VALUES.size() + BREAKOUT_VALUES.size();

  /** The prototype's stand-ins ({@code Validator.STANDINS}). */
  static final List<String> STANDINS =
      List.of("", "x", " ", "{", "{{a}}", "<script>alert(1)</script>&amp", "\"'`/-->]]>", "\u0000\n\r");

  /** What the lemma feeds at each hole: hostile values, stand-ins, seeded random strings. */
  static final List<String> LEMMA_VALUES;

  static {
    Set<String> hostile = new LinkedHashSet<>(SEED_VALUES);
    hostile.addAll(BREAKOUT_VALUES);
    for (String v : HostileStrings.VECTORS) {
      hostile.add(v.replace("alert(1)", "PWN()"));
    }
    HOSTILE = List.copyOf(hostile);
    Set<String> lemma = new LinkedHashSet<>();
    for (String v : HOSTILE) {
      lemma.add(v.replace("PWN", marker(1)));
    }
    lemma.addAll(STANDINS);
    lemma.add(marker(1) + "<'\"&{{/}`\n");
    Random random = new Random(324);
    for (int i = 0; i < 64; i++) {
      lemma.add(HostileStrings.random(random, 12));
    }
    LEMMA_VALUES = List.copyOf(lemma);
  }

  /** Slot {@code i}'s marker, {@code tqm0001} for slot 1: the benign value, and the Node job's payload call name. */
  static String marker(int i) {
    return String.format(Locale.ROOT, "tqm%04d", i);
  }

  // ---- cases ----

  /** A template parameter: its type and name. */
  record Param(String type, String name) {}

  /** One case and the template's parameters, in declaration order. */
  record GateCase(OracleCase oracleCase, List<Param> params) {
    String view() {
      return "c" + oracleCase.id();
    }
  }

  private static final Pattern PARAMS = Pattern.compile("t:params=\"([^\"]*)\"");

  /** The parameters a template's root declares. */
  static List<Param> params(String template) {
    Matcher m = PARAMS.matcher(template);
    if (!m.find()) {
      return List.of();
    }
    List<Param> out = new ArrayList<>();
    for (String p : m.group(1).split(",")) {
      String[] tn = p.strip().split("\\s+");
      if (tn.length != 2) {
        throw new IllegalArgumentException("can't read parameter '" + p + "' of " + template);
      }
      out.add(new Param(tn[0], tn[1]));
    }
    return out;
  }

  /** The seeds, each id once. */
  static List<GateCase> cases() {
    Map<String, GateCase> out = new LinkedHashMap<>();
    for (Seeds.Seed s : Seeds.load()) {
      OracleCase c = s.toCase();
      out.putIfAbsent(c.id(), new GateCase(c, params(c.template())));
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

  /**
   * A model for {@code params}: String slot {@code i} (1-based, in declaration order) gets {@code
   * value(i)}, a {@code List<String>} two such slots, an {@code int} -1, and a {@code SafeHtml} fixed
   * benign markup.
   */
  static Map<String, Object> model(List<Param> params, java.util.function.IntFunction<String> value) {
    Map<String, Object> m = new HashMap<>();
    int slot = 0;
    for (Param p : params) {
      switch (p.type()) {
        case "String" -> m.put(p.name(), value.apply(++slot));
        case "int", "Integer", "long" -> m.put(p.name(), -1);
        case "SafeHtml" -> m.put(p.name(), SafeHtml.sanitize("<b>safe</b>"));
        case "SafeInlineHtml" -> m.put(p.name(), dev.tessera.runtime.SafeInlineHtml.escape("safe"));
        case "java.util.List<String>" -> m.put(p.name(), List.of(value.apply(++slot), value.apply(++slot)));
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

  /** One parser the lemma runs: scripting on or off, document or {@code <body>} fragment parsing. */
  record LemmaParser(boolean scripting, boolean fragment) {
    Parser start() throws EngineException {
      return fragment
          ? Vnu.fragment(VnuTrees.HTML, "body", scripting)
          : Vnu.document(scripting);
    }

    @Override
    public String toString() {
      return Vnu.NAME + (scripting ? " scripting-on" : " scripting-off") + (fragment ? " fragment(body)" : " document");
    }
  }

  static final List<LemmaParser> LEMMA_PARSERS;

  static {
    List<LemmaParser> ps = new ArrayList<>();
    for (boolean scripting : new boolean[] {true, false}) {
      for (boolean fragment : new boolean[] {false, true}) {
        ps.add(new LemmaParser(scripting, fragment));
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
        String.valueOf(s.fragmentContext()), String.valueOf(s.scripting()),
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

  // ---- one case ----

  /** What the gate found for one case. */
  static final class CaseResult {
    final GateCase gateCase;
    /** Fidelity and lemma failures, render errors. */
    final List<String> failures = new ArrayList<>();
    final List<ExportRecord> export = new ArrayList<>();
    int renders;
    int refused;
    int lemmaPaths;

    CaseResult(GateCase gateCase) {
      this.gateCase = gateCase;
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
   * Renders and checks one case: fidelity of every render and the lemma along every distinct render
   * path. {@code exportValues} hostile values per case go to the export (negative: none).
   */
  static CaseResult check(Subject subject, Escape escape, LemmaMemo memo, int exportValues) {
    GateCase g = subject.gateCase();
    OracleCase oc = g.oracleCase();
    CaseResult r = new CaseResult(g);
    Set<String> paths = new HashSet<>();

    String benign = render(subject, model(g.params(), ProfileOracleTest::marker), escape, memo, paths, r, "benign");
    if (benign == null) {
      return r;
    }
    List<String[]> hostile = new ArrayList<>();
    for (String v : HOSTILE) {
      String shown = v.replace("PWN", marker(1));
      String out = render(subject, model(g.params(), i -> v.replace("PWN", marker(i))), escape, memo,
          paths, r, "value " + show(shown));
      if (out != null) {
        hostile.add(new String[] {shown, out});
      }
    }
    r.lemmaPaths = paths.size();
    if (exportValues >= 0) {
      r.export.add(ExportRecord.of(oc, marker(1), benign));
      for (String[] h : hostile.subList(0, Math.min(exportValues, hostile.size()))) {
        r.export.add(ExportRecord.of(oc, h[0], h[1]));
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
      return out; // still exported; there are no holes to check the lemma at
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
    List<Compiled> compiled = compile(cases);
    long t1 = System.nanoTime();

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
      for (String e : c.errors()) {
        if (!POSITIONED.matcher(e).find()) {
          failures.computeIfAbsent(label(c.gateCase()), k -> new ArrayList<>())
              .add("compiler error without file, line and column: " + e);
        }
      }
    }
    int renders = 0;
    int refused = 0;
    int lemmaPaths = 0;
    for (CaseResult r : results) {
      renders += r.renders;
      refused += r.refused;
      lemmaPaths += r.lemmaPaths;
      if (!r.failures.isEmpty()) {
        failures.computeIfAbsent(label(r.gateCase), k -> new ArrayList<>()).addAll(r.failures);
      }
    }

    List<String> report = new ArrayList<>();
    long profile = compiled.stream().filter(c -> !c.accepted() && c.layer() == Layer.PROFILE).count();
    long verifier = compiled.stream().filter(c -> !c.accepted() && c.layer() == Layer.VERIFIER).count();
    report.add("oracle gate (#324): " + cases.size() + " seeds");
    report.add("  REJECTED " + (profile + verifier) + ": profile " + profile + ", verifier " + verifier);
    report.add("  accepted " + accepted.size());
    report.add("  renders " + renders + " (" + refused + " refused by an escaper), every one in its skeleton unless listed below");
    report.add("  lemma: " + lemmaPaths + " render paths, " + memo.verdicts.size() + " distinct (state, escaper) pairs, "
        + memo.feeds.get() + " escaped values fed, " + LEMMA_VALUES.size() + " values x " + LEMMA_PARSERS.size() + " parsers");
    report.add(String.format(Locale.ROOT, "  time: compile %.1f s, checks %.1f s", (t1 - start) / 1e9, (t2 - t1) / 1e9));
    report.add("  failures: " + failures.values().stream().mapToInt(List::size).sum());
    report.add("");
    report.add("failures:");
    failures.forEach((k, v) -> v.forEach(f -> report.add("  " + k + ": " + f)));
    report.add("");
    report.add("rejected:");
    compiled.stream().filter(c -> !c.accepted())
        .forEach(c -> report.add("  " + c.layer() + " " + label(c.gateCase()) + ": " + c.errors().get(0)));
    Path dir = Files.createDirectories(Path.of("target", "oracle"));
    Files.write(dir.resolve("report.txt"), report, StandardCharsets.UTF_8);
    System.out.println(String.join("\n", report.subList(0, report.indexOf(""))));

    if (exportFile != null) {
      List<String> lines = new ArrayList<>();
      int k = 0;
      for (Compiled c : compiled) {
        OracleCase oc = c.gateCase().oracleCase();
        if (!c.accepted()) {
          lines.add(new ExportRecord(oc.id(), oc.name(), oc.template(), null, null, "REJECTED").toJsonLine());
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

  private static String label(GateCase g) {
    return g.oracleCase().id() + " (" + g.oracleCase().name() + ")";
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
