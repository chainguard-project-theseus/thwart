package dev.tessera.compiler.verify;

import dev.tessera.compiler.DiagnosticSink;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.verify.engine.Engine;
import dev.tessera.compiler.verify.engine.EngineException;
import dev.tessera.compiler.verify.engine.Engines;
import dev.tessera.compiler.verify.engine.OpenElement;
import dev.tessera.compiler.verify.engine.Parser;
import dev.tessera.compiler.verify.engine.ParserState;
import dev.tessera.compiler.verify.engine.TokenizerState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The build-time context verifier (#367, docs/security-architecture.md §11): the security boundary
 * for markup contexts. It runs each template's {@link Skeleton} through the spec HTML parsers
 * ({@link Engines#all()}), with scripting on and off, and accepts a hole only if its escaper's
 * output is inert in the parser state there in all of them ({@link Rules}). A template it doesn't
 * accept is a build error at the hole's file, line and column.
 *
 * <p>It is an abstract interpretation with the parsers as the transfer function. A path holds the
 * four parsers. Statics are fed as they are. A hole is checked and leaves the state as it was
 * (that is what "inert" means), except that an attribute hole writes {@link Rules#MARK}. Paths
 * are merged by their parser states' signatures at every join: an optional part ({@code t:if},
 * {@code t:case}, a computed attribute) is the union of skipping it and writing it, a loop runs
 * to a fixed point, and a static fragment call is analysed at each call site, memoised per
 * (fragment, entry state, bound fragment arguments), with recursion as a fixed point too.
 *
 * <p>It accepts only error-free parses (#406, security-architecture.md §11.4): every parse error an
 * engine that reports them ({@link Engine#reportsErrors()}) finds on any path, with scripting on
 * and off, is {@link Problem#PARSE_ERROR} at the static that holds it, except the allow-list
 * ({@link Rules#allowedError}); a partial counts them only in its fragment contexts. And it checks
 * the three coarse exclusions ({@link Rules#exclusion}) at every element the parser inserts.
 *
 * <p>It fails closed: an engine failure, too many states, a loop or recursion without a fixed
 * point and a node it can't interpret are build errors.
 */
public final class ContextVerifier {
  /** At most this many distinct parser states at a join. */
  static final int MAX_STATES = 24;

  /** A loop or a recursion must reach its fixed point within this many rounds. */
  static final int MAX_ROUNDS = 4;

  /** Formatting elements (the list of active formatting elements holds these). */
  private static final String[] FORMATTING = {
    "a", "b", "big", "code", "em", "font", "i", "nobr", "s", "small", "strike", "strong", "tt", "u"
  };

  /** Fragment parsing contexts a partial's root element implies (memo §3.4.6); anything else: body. */
  private static final Map<String, String> PARTIAL_CONTEXTS = new HashMap<>();

  static {
    PARTIAL_CONTEXTS.put("tr", "tbody");
    PARTIAL_CONTEXTS.put("td", "tr");
    PARTIAL_CONTEXTS.put("th", "tr");
    PARTIAL_CONTEXTS.put("option", "select");
    PARTIAL_CONTEXTS.put("optgroup", "select");
    PARTIAL_CONTEXTS.put("li", "ul");
  }

  /** The engines a path runs, {@link Engines#all()} outside tests. */
  private final List<Engine> engines;
  private final Map<Skeleton.FragmentId, Skeleton.Seq> fragments = new HashMap<>();
  private final Map<String, Memo> memo = new HashMap<>();
  private final Map<String, InProgress> inProgress = new HashMap<>();
  private final List<Skeleton.FragmentId> callStack = new ArrayList<>();
  /** Per fragment that is on the call stack: how many states it was entered in by recursion. */
  private final Map<Skeleton.FragmentId, Integer> recursiveEntries = new HashMap<>();
  private final Map<Skeleton.Seq, Integer> seqIds = new IdentityHashMap<>();
  /** The lowest call-stack depth whose approximation the current analysis read. */
  private int approxDepth = Integer.MAX_VALUE;
  /** Where findings go: the analysis of the innermost call in progress, or the entry. */
  private List<Finding> sink = new ArrayList<>();
  /** How many parsers a path of the current template holds. */
  private int parserCount;
  /** How many more steps {@link #firstElements} may take for the current template. */
  private int firstElementsBudget;
  /** The last source position seen, for nodes that have none (generated markup). */
  private SourcePos near;
  /** In the first FLOW-partial probe (#396): check the producer promise after every tag. */
  private boolean promiseCheck;
  /** How the template broke the producer promise in that probe, in words, or null. */
  private String breach;

  ContextVerifier(Map<String, Skeleton.TemplateSkeleton> templates) {
    this(templates, Engines.all());
  }

  /** A verifier that runs only {@code engines} (tests: the classic engine switched off). */
  ContextVerifier(Map<String, Skeleton.TemplateSkeleton> templates, List<Engine> engines) {
    this.engines = engines;
    for (Skeleton.TemplateSkeleton t : templates.values()) {
      for (Map.Entry<String, Skeleton.Seq> f : t.fragments().entrySet()) {
        fragments.put(new Skeleton.FragmentId(t.viewName(), f.getKey()), f.getValue());
      }
    }
  }

  /** A fresh analysis (no memo) over the same fragments. */
  private ContextVerifier(ContextVerifier other) {
    engines = other.engines;
    fragments.putAll(other.fragments);
  }

  /**
   * Verifies every template in {@code templates} (by view name, as the compiler recorded them)
   * and reports what it rejects to {@code diagnostics} as errors.
   */
  public static void verify(Map<String, Skeleton.TemplateSkeleton> templates, DiagnosticSink diagnostics) {
    ContextVerifier v = new ContextVerifier(templates);
    for (Skeleton.TemplateSkeleton t : templates.values()) {
      for (Finding f : v.check(t)) {
        diagnostics.error(f.at, VerifierMessages.message(f));
      }
    }
  }

  /**
   * The view names of the templates in {@code templates} that the verifier proves {@code FLOW}
   * partials (#396, {@link #whyNotFlowPartial}). The generated class of each says so
   * ({@code Template.verifiedFlowPartial()}), and only those may be rendered into a
   * {@code SafeHtml} or {@code Fragment} value. Call it only when {@link #verify} reported nothing.
   */
  public static Set<String> flowPartials(Map<String, Skeleton.TemplateSkeleton> templates) {
    ContextVerifier v = new ContextVerifier(templates);
    Set<String> out = new LinkedHashSet<>();
    for (Skeleton.TemplateSkeleton t : templates.values()) {
      if (v.whyNotFlowPartial(t) == null) {
        out.add(t.viewName());
      }
    }
    return out;
  }

  /**
   * Why {@code template}'s output may not be FLOW content, in words, or null if it is: it is a
   * partial (not a page), its root needs no other context than {@code <body>}, and inserted at each
   * of {@link ContentKinds#PARTIAL_PROBES} it has no finding and leaves the parser as it found it
   * in all four parsers, keeping the producer promise after every tag. It fails closed: an engine
   * failure or a probe the predicate doesn't accept is a "no".
   */
  String whyNotFlowPartial(Skeleton.TemplateSkeleton template) {
    List<String> entries = entries(template);
    if (entries.size() == 1) {
      return "it is a page (it starts with a doctype or <html>)";
    }
    if (entries.size() != 2 || !"body".equals(entries.get(1))) {
      return "its root is a list item, table part or option, which needs another element around it";
    }
    boolean first = true;
    for (String context : ContentKinds.PARTIAL_PROBES) {
      String why = new ContextVerifier(this).probe(template, context, first);
      first = false;
      if (why != null) {
        return why;
      }
    }
    return null;
  }

  /** Why {@code template} isn't FLOW content inserted after {@code context} in a {@code <body>}, or null. */
  private String probe(Skeleton.TemplateSkeleton template, String context, boolean checkPromise) {
    boolean scriptingOff = mentionsNoscript(template.body(), Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()));
    parserCount = (scriptingOff ? 2 : 1) * engines.size();
    promiseCheck = checkPromise;
    List<Finding> found = new ArrayList<>();
    sink = found;
    near = firstPos(template);
    String where = context.isEmpty() ? "in a <body>" : "after " + context;
    try {
      Path entry = start("body", scriptingOff);
      entry.feed(context, near);
      for (ParserState s : entry.states()) {
        if (ContentKinds.problem(ContentKinds.Kind.FLOW, s) != null) {
          return "the probe " + where + " is not a FLOW position";
        }
      }
      // As for an HTML message (known): the exit must be the entry, or in template mode the state
      // a flow start tag switches to.
      Set<String> allowed = new HashSet<>();
      allowed.add(flowKey(entry));
      for (ParserState s : entry.states()) {
        if (ContentKinds.switchesMode(ContentKinds.Kind.FLOW, s)) {
          Path written = entry.fork();
          written.feed(ContentKinds.REPRESENTATIVE, near);
          allowed.add(flowKey(written));
          break;
        }
      }
      List<Path> exits = seq(template.body(), Collections.singletonList(entry.fork()), Env.EMPTY);
      if (breach != null) {
        return breach;
      }
      for (Finding f : found) {
        return "inserted " + where + ", the verifier rejects it (" + f.problem + ")";
      }
      ParserState[] before = entry.states();
      for (Path exit : exits) {
        if (!allowed.contains(flowKey(exit))) {
          ParserState[] after = exit.states();
          for (int i = 0; i < after.length; i++) {
            if (!ContentKinds.flowSignature(after[i]).equals(ContentKinds.flowSignature(before[i]))) {
              return "inserted " + where + ", it " + ContentKinds.difference(before[i], after[i]);
            }
          }
        }
      }
    } catch (EngineException e) {
      return "the verifier's parser failed: " + e.getMessage();
    }
    return null;
  }

  private static String flowKey(Path p) throws EngineException {
    StringBuilder sb = new StringBuilder();
    for (ParserState s : p.states()) {
      sb.append(ContentKinds.flowSignature(s)).append('\u0000');
    }
    return sb.toString();
  }

  /** What the verifier rejects in {@code template}, deduplicated, in order. */
  List<Finding> check(Skeleton.TemplateSkeleton template) {
    Map<String, Finding> out = new LinkedHashMap<>();
    SourcePos start = firstPos(template);
    // The scripting flag changes only how a <noscript> tag parses: without one, the parsers with
    // scripting off stay in the states of those with it on.
    boolean scriptingOff = mentionsNoscript(template.body(), Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()));
    parserCount = (scriptingOff ? 2 : 1) * engines.size();
    List<String> entries = entries(template);
    for (String context : entries) {
      List<Finding> found = new ArrayList<>();
      sink = found;
      near = start;
      try {
        List<Path> paths = Collections.singletonList(start(context, scriptingOff));
        seq(template.body(), paths, Env.EMPTY);
      } catch (EngineException e) {
        found.add(new Finding(Problem.ENGINE_FAILURE, near, null, null, null, null, null,
            Collections.<ParserState>emptyList(), parserCount, e.getMessage(), false));
      }
      for (Finding f : found) {
        if (f.problem == Problem.PARSE_ERROR && context == null && entries.size() > 1) {
          // A partial counts parse errors only in the fragment contexts its root implies (#406):
          // as a document, a <tr> partial is a stray start tag.
          continue;
        }
        // Data-dependent structure is one error per place, not one per hole it reaches.
        String key = f.recursive || f.problem == Problem.STRUCTURE_DEPENDS_ON_DATA ? "recursive " + f.at : f.key();
        if (!out.containsKey(key)) {
          out.put(key, f);
        }
      }
    }
    return new ArrayList<>(out.values());
  }

  /**
   * How {@code template} is parsed: as a document (null) and, if it is a partial (no doctype or
   * {@code <html>} root), also as a fragment in each context its possible root elements imply.
   */
  private List<String> entries(Skeleton.TemplateSkeleton template) {
    List<String> out = new ArrayList<>();
    out.add(null);
    Set<String> roots = new LinkedHashSet<>();
    firstElementsBudget = 256;
    firstElements(template.body().nodes(), 0, roots, 0);
    // A page only if every way it can start is a doctype or <html>.
    roots.remove("!doctype");
    roots.remove("html");
    if (roots.isEmpty() && firstElementsBudget >= 0) {
      return out;
    }
    Set<String> contexts = new LinkedHashSet<>();
    if (firstElementsBudget < 0) {
      // Too many ways to start: check every context a root implies.
      contexts.add("body");
      contexts.addAll(PARTIAL_CONTEXTS.values());
    }
    for (String r : roots) {
      String c = PARTIAL_CONTEXTS.get(r);
      contexts.add(c == null ? "body" : c);
    }
    if (contexts.isEmpty()) {
      contexts.add("body");
    }
    out.addAll(contexts);
    return out;
  }

  /**
   * Adds to {@code out} the lower-case names of the elements that can start the output of
   * {@code nodes} from index {@code i} ({@code !doctype} for a doctype, {@code #text} for text).
   */
  private void firstElements(List<Skeleton.Node> nodes, int i, Set<String> out, int depth) {
    if (--firstElementsBudget < 0) {
      return;
    }
    for (; i < nodes.size(); i++) {
      Skeleton.Node n = nodes.get(i);
      if (n instanceof Skeleton.Static) {
        String text = ((Skeleton.Static) n).text();
        int k = 0;
        while (k < text.length() && isSpace(text.charAt(k))) {
          k++;
        }
        if (k == text.length()) {
          continue;
        }
        if (text.startsWith("<!--", k)) {
          int end = text.indexOf("-->", k + 4);
          if (end >= 0 && end + 3 < text.length()) {
            // Skip the comment and look at the rest of this static.
            List<Skeleton.Node> rest = new ArrayList<>();
            rest.add(new Skeleton.Static(text.substring(end + 3), n.pos()));
            rest.addAll(nodes.subList(i + 1, nodes.size()));
            firstElements(rest, 0, out, depth);
            return;
          }
          if (end >= 0) {
            continue;
          }
        }
        if (text.regionMatches(true, k, "<!doctype", 0, 9)) {
          out.add("!doctype");
          return;
        }
        if (k + 1 < text.length() && text.charAt(k) == '<' && Character.isLetter(text.charAt(k + 1))) {
          int e = k + 1;
          while (e < text.length() && !isSpace(text.charAt(e)) && text.charAt(e) != '>' && text.charAt(e) != '/') {
            e++;
          }
          out.add(text.substring(k + 1, e).toLowerCase(Locale.ROOT));
          return;
        }
        out.add("#text");
        return;
      } else if (n instanceof Skeleton.Seq) {
        List<Skeleton.Node> rest = new ArrayList<>(((Skeleton.Seq) n).nodes());
        rest.addAll(nodes.subList(i + 1, nodes.size()));
        firstElements(rest, 0, out, depth);
        return;
      } else if (n instanceof Skeleton.Opt || n instanceof Skeleton.Rep) {
        Skeleton.Seq body = n instanceof Skeleton.Opt ? ((Skeleton.Opt) n).body() : ((Skeleton.Rep) n).body();
        List<Skeleton.Node> rest = new ArrayList<>(body.nodes());
        rest.addAll(nodes.subList(i + 1, nodes.size()));
        firstElements(rest, 0, out, depth);
        // ... or skipped.
      } else if (n instanceof Skeleton.Alt) {
        for (Skeleton.Seq b : ((Skeleton.Alt) n).branches()) {
          List<Skeleton.Node> rest = new ArrayList<>(b.nodes());
          rest.addAll(nodes.subList(i + 1, nodes.size()));
          firstElements(rest, 0, out, depth);
        }
        return;
      } else if (n instanceof Skeleton.Call && depth < MAX_ROUNDS) {
        Skeleton.Seq target = fragments.get(((Skeleton.Call) n).target());
        if (target != null) {
          List<Skeleton.Node> rest = new ArrayList<>(target.nodes());
          rest.addAll(nodes.subList(i + 1, nodes.size()));
          firstElements(rest, 0, out, depth + 1);
          return;
        }
        out.add("#text");
        return;
      } else {
        out.add("#text");
        return;
      }
    }
  }

  /**
   * Whether a static {@code seq} can write, through the fragments it calls and their arguments,
   * contains {@code noscript} in any case.
   */
  private boolean mentionsNoscript(Skeleton.Seq seq, Set<Object> seen) {
    if (!seen.add(seq)) {
      return false;
    }
    for (Skeleton.Node n : seq.nodes()) {
      boolean found;
      if (n instanceof Skeleton.Static) {
        found = ((Skeleton.Static) n).text().toLowerCase(Locale.ROOT).contains("noscript");
      } else if (n instanceof Skeleton.Seq) {
        found = mentionsNoscript((Skeleton.Seq) n, seen);
      } else if (n instanceof Skeleton.Opt) {
        found = mentionsNoscript(((Skeleton.Opt) n).body(), seen);
      } else if (n instanceof Skeleton.Rep) {
        found = mentionsNoscript(((Skeleton.Rep) n).body(), seen) || mentionsNoscript(((Skeleton.Rep) n).separator(), seen);
      } else if (n instanceof Skeleton.Alt) {
        found = false;
        for (Skeleton.Seq b : ((Skeleton.Alt) n).branches()) {
          found |= mentionsNoscript(b, seen);
        }
      } else if (n instanceof Skeleton.Call) {
        Skeleton.Call c = (Skeleton.Call) n;
        Skeleton.Seq target = fragments.get(c.target());
        found = target != null && mentionsNoscript(target, seen);
        for (Skeleton.Seq a : c.fragmentArgs().values()) {
          found |= mentionsNoscript(a, seen);
        }
      } else {
        found = false;
      }
      if (found) {
        return true;
      }
    }
    return false;
  }

  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
  }

  /** The first source position in {@code template}, else its file's start. */
  private static SourcePos firstPos(Skeleton.TemplateSkeleton template) {
    SourcePos p = firstPos(template.body());
    for (Skeleton.Seq f : template.fragments().values()) {
      if (p == null) {
        p = firstPos(f);
      }
    }
    return p != null ? p : new SourcePos(template.viewName() + ".html", 1, 1);
  }

  private static SourcePos firstPos(Skeleton.Seq seq) {
    for (Skeleton.Node n : seq.nodes()) {
      if (n.pos() != null) {
        return new SourcePos(n.pos().file(), 1, 1);
      }
    }
    return null;
  }

  // ---- the analysis ----

  /** The exit paths of {@code seq} from {@code in}. May consume the paths in {@code in}. */
  private List<Path> seq(Skeleton.Seq seq, List<Path> in, Env env) throws EngineException {
    List<Path> cur = in;
    Located.Builder statics = new Located.Builder();
    SourcePos staticPos = null;
    boolean frameset = false;
    for (Skeleton.Node n : seq.nodes()) {
      if (n instanceof Skeleton.Static) {
        String text = ((Skeleton.Static) n).text();
        if (containsIgnoreCase(text, "<frameset")) {
          add(Problem.FRAMESET, at(n), null, null, null, Collections.<ParserState>emptyList(), null);
          frameset = true;
        }
        if (staticPos == null) {
          staticPos = n.pos();
        }
        // Each static keeps its position, so a parse error points at the markup that holds it.
        statics.append(text, n.pos() != null ? n.pos() : staticPos != null ? staticPos : near);
        continue;
      }
      if (!statics.isEmpty()) {
        cur = statics(cur, statics.build(), staticPos, !frameset);
        statics = new Located.Builder();
        staticPos = null;
        frameset = false;
      }
      cur = node(n, cur, env);
    }
    if (!statics.isEmpty()) {
      cur = statics(cur, statics.build(), staticPos, !frameset);
    }
    return cur;
  }

  private SourcePos at(Skeleton.Node n) {
    if (n.pos() != null) {
      near = n.pos();
    }
    return near;
  }

  private List<Path> node(Skeleton.Node n, List<Path> in, Env env) throws EngineException {
    SourcePos pos = at(n);
    if (in.isEmpty()) {
      return in;
    }
    if (n instanceof Skeleton.Hole) {
      Skeleton.Hole h = (Skeleton.Hole) n;
      for (Path p : in) {
        hole(h, p, pos);
      }
      return in;
    }
    if (n instanceof Skeleton.RawInsert && ((Skeleton.RawInsert) n).content() != null) {
      return known((Skeleton.RawInsert) n, in, env, pos);
    }
    if (n instanceof Skeleton.RawInsert) {
      return raw(((Skeleton.RawInsert) n).source(), in, pos);
    }
    if (n instanceof Skeleton.ParamInsert) {
      Binding b = env.bindings.get(((Skeleton.ParamInsert) n).param());
      if (b == null) {
        return raw(Skeleton.Source.FRAGMENT_VALUE, in, pos);
      }
      // The bound fragment, or nothing for a null value.
      List<Path> all = new ArrayList<>(in);
      all.addAll(seq(b.seq, forks(in), b.env));
      return dedupe(all, pos);
    }
    if (n instanceof Skeleton.Call) {
      List<Path> all = new ArrayList<>();
      for (Path p : in) {
        all.addAll(call((Skeleton.Call) n, p, env, pos));
      }
      return dedupe(all, pos);
    }
    if (n instanceof Skeleton.Seq) {
      return seq((Skeleton.Seq) n, in, env);
    }
    if (n instanceof Skeleton.Opt
        && (((Skeleton.Opt) n).kind() == Skeleton.OptKind.ATTRIBUTE || ((Skeleton.Opt) n).kind() == Skeleton.OptKind.CSP_NONCE)) {
      return attribute((Skeleton.Opt) n, in, env, pos);
    }
    if (n instanceof Skeleton.Opt) {
      List<Path> all = new ArrayList<>(in);
      all.addAll(seq(((Skeleton.Opt) n).body(), forks(in), env));
      return dedupe(all, pos);
    }
    if (n instanceof Skeleton.Alt) {
      List<Path> all = new ArrayList<>();
      for (Skeleton.Seq b : ((Skeleton.Alt) n).branches()) {
        all.addAll(seq(b, forks(in), env));
      }
      return dedupe(all, pos);
    }
    if (n instanceof Skeleton.Rep) {
      return rep((Skeleton.Rep) n, in, env, pos);
    }
    add(Problem.UNKNOWN_NODE, pos, null, null, null, Collections.<ParserState>emptyList(), n.getClass().getSimpleName());
    return Collections.emptyList();
  }

  /**
   * An attribute written only if its value isn't null: {@code [ name="<hole>"]}. Inside a start
   * tag, writing a whole attribute or not leaves the tokenizer in states that parse every
   * continuation alike (before an attribute name, after a quoted value; after the tag name, when
   * the next character is whitespace, '/' or '>', which {@link Path#tagBoundary} checks), so the
   * two differ only in the element's attribute list. That list matters to the tree builder only
   * through the names in {@link #PRESENCE_MATTERS} and through duplicate names, where the written
   * attribute is the stricter case (a later duplicate is dropped). So the written path alone stands
   * for both, and a tag with many computed attributes doesn't multiply the states. Otherwise both
   * are kept, as for any optional part.
   */
  private List<Path> attribute(Skeleton.Opt o, List<Path> in, Env env, SourcePos pos) throws EngineException {
    String name = null;
    for (Skeleton.Node b : o.body().nodes()) {
      if (b instanceof Skeleton.Hole && ((Skeleton.Hole) b).attribute() != null) {
        name = ((Skeleton.Hole) b).attribute().toLowerCase(Locale.ROOT);
        break;
      }
    }
    List<Path> all = new ArrayList<>();
    for (Path p : in) {
      ParserState[] entry = p.states();
      boolean pendingBoundary = p.tagBoundary;
      List<Path> written = seq(o.body(), Collections.singletonList(p.fork()), env);
      boolean merge = name != null && !PRESENCE_MATTERS.contains(name) && !written.isEmpty();
      boolean afterTagName = false;
      for (ParserState s : entry) {
        TokenizerState t = s.tokenizer();
        merge &= t == TokenizerState.BEFORE_ATTRIBUTE_NAME || t == TokenizerState.AFTER_ATTRIBUTE_VALUE_QUOTED
            || t == TokenizerState.TAG_NAME;
        afterTagName |= t == TokenizerState.TAG_NAME;
      }
      for (Path w : written) {
        for (ParserState s : w.states()) {
          merge &= s.tokenizer() == TokenizerState.AFTER_ATTRIBUTE_VALUE_QUOTED;
        }
      }
      if (merge) {
        for (Path w : written) {
          w.tagBoundary |= afterTagName || pendingBoundary;
          w.key = null;
        }
      } else {
        all.add(p);
      }
      all.addAll(written);
    }
    return dedupe(all, pos);
  }

  /**
   * Attributes whose presence changes how the rest parses: {@code type} ({@code <input>} in a
   * table), {@code encoding} (MathML {@code annotation-xml}), and {@code color}, {@code face},
   * {@code size} ({@code <font>} breaks out of SVG and MathML with them).
   */
  private static final Set<String> PRESENCE_MATTERS =
      new HashSet<>(java.util.Arrays.asList("type", "encoding", "color", "face", "size"));

  /** Zero or more {@code body}, separated: iterate until no new state appears. */
  private List<Path> rep(Skeleton.Rep r, List<Path> in, Env env, SourcePos pos) throws EngineException {
    List<Path> all = dedupe(in, pos);
    // States after at least one iteration, kept apart from the entry states: an iteration that
    // returns to an entry state is still followed by separator + body.
    Set<String> seen = new HashSet<>();
    List<Path> fresh = all;
    for (int round = 0; ; round++) {
      List<Path> next = forks(fresh);
      if (round > 0) {
        next = seq(r.separator(), next, env);
      }
      next = dedupe(seq(r.body(), next, env), pos);
      fresh = new ArrayList<>();
      for (Path p : next) {
        if (seen.add(p.key())) {
          fresh.add(p);
        }
      }
      if (fresh.isEmpty()) {
        break;
      }
      all.addAll(fresh);
      if (round + 1 >= MAX_ROUNDS) {
        add(Problem.LOOP_NO_FIXED_POINT, pos, null, null, null, Collections.<ParserState>emptyList(), null);
        break;
      }
    }
    return dedupe(all, pos);
  }

  /** Merges paths with the same state; more than {@link #MAX_STATES} is an error. */
  private List<Path> dedupe(List<Path> paths, SourcePos pos) throws EngineException {
    if (paths.size() <= 1) {
      return new ArrayList<>(paths);
    }
    Map<String, Path> byKey = new LinkedHashMap<>();
    for (Path p : paths) {
      if (!byKey.containsKey(p.key())) {
        byKey.put(p.key(), p);
      }
    }
    List<Path> out = new ArrayList<>(byKey.values());
    if (out.size() > MAX_STATES) {
      add(Problem.TOO_MANY_STATES, pos, null, null, null, Collections.<ParserState>emptyList(), null);
      return new ArrayList<>(out.subList(0, MAX_STATES));
    }
    return out;
  }

  private static List<Path> forks(List<Path> in) throws EngineException {
    List<Path> out = new ArrayList<>(in.size());
    for (Path p : in) {
      out.add(p.fork());
    }
    return out;
  }

  /** {@code frameset}: whether to report a {@code <frameset>} (seq already reported one inside a single static). */
  private List<Path> statics(List<Path> in, Located located, SourcePos pos, boolean frameset) throws EngineException {
    if (pos != null) {
      near = pos;
    }
    pos = near;
    String text = located.text;
    if (frameset && containsIgnoreCase(text, "<frameset")) {
      // Split across statics (each one is checked in seq).
      add(Problem.FRAMESET, pos, null, null, null, Collections.<ParserState>emptyList(), null);
    }
    for (Path p : in) {
      if (p.tagBoundary) {
        char c = text.charAt(0);
        if (!isSpace(c) && c != '/' && c != '>') {
          add(Problem.UNKNOWN_NODE, pos, null, null, null, Collections.<ParserState>emptyList(),
              "markup right after a computed attribute that isn't whitespace, '/' or '>'");
        }
        p.tagBoundary = false;
        p.key = null;
      }
      if (!p.tagHole && !p.markedFormatting && !promiseCheck) {
        int lt = text.lastIndexOf('<');
        if (lt > 0 && text.indexOf('>', lt) < 0) {
          // A tag that goes on past this static: feed the text before it on its own, so the
          // parser replays only the tag's own input while it is inside it (VnuParser).
          p.feed(located.sub(0, lt));
          p.defer(located.sub(lt, text.length()));
        } else {
          p.feed(located);
        }
        continue;
      }
      // A formatting element with a computed attribute may be in the list of active formatting
      // elements: feed each tag on its own and check the Noah's Ark clause after each, so no push
      // and pop inside one static goes unseen (also of the element whose start tag holds the
      // hole, which ends in this static).
      int from = 0;
      for (int i = text.indexOf('<'); i >= 0; i = text.indexOf('<', i + 1)) {
        if (i > from) {
          feedAndCheck(p, located.sub(from, i), pos);
          from = i;
        }
      }
      feedAndCheck(p, located.sub(from, text.length()), pos);
    }
    return in;
  }

  private void feedAndCheck(Path p, Located located, SourcePos pos) throws EngineException {
    String piece = located.text;
    ParserState[] before = null;
    if (promiseCheck && breach == null && piece.startsWith("</")) {
      // An end tag on its own, so the state right after it shows whether it closed anything.
      int gt = piece.indexOf('>');
      if (gt > 0 && gt + 1 < piece.length()) {
        feedAndCheck(p, located.sub(0, gt + 1), pos);
        feedAndCheck(p, located.sub(gt + 1, piece.length()), pos);
        return;
      }
      before = p.states();
    }
    p.feed(located);
    boolean between = true;
    boolean marked = false;
    List<ParserState> ark = new ArrayList<>();
    for (ParserState s : p.states()) {
      between &= s.pendingInput().isEmpty();
      marked |= Rules.hasMarkedFormatting(s);
      if (Rules.noahsArk(s)) {
        ark.add(s);
      }
    }
    if (!ark.isEmpty()) {
      add(Problem.NOAHS_ARK, pos, null, null, null, ark, null);
    }
    if (between) {
      p.tagHole = false;
    }
    p.markedFormatting = marked;
    if (promiseCheck && breach == null) {
      breach = ContentKinds.pageTag(piece);
      if (breach == null && before != null) {
        breach = ContentKinds.strayEndTag(piece, before, p.states());
      }
      for (ParserState s : p.states()) {
        if (breach == null) {
          breach = ContentKinds.promiseBreach(s);
        }
      }
    }
  }

  private static boolean hasFormattingStartTag(String text) {
    for (int i = text.indexOf('<'); i >= 0; i = text.indexOf('<', i + 1)) {
      if (formattingStartTag(text, i)) {
        return true;
      }
    }
    return false;
  }

  private static boolean formattingStartTag(String text, int lt) {
    for (String name : FORMATTING) {
      int end = lt + 1 + name.length();
      if (text.regionMatches(true, lt + 1, name, 0, name.length())
          && (end == text.length() || isSpace(text.charAt(end)) || text.charAt(end) == '/' || text.charAt(end) == '>')) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsIgnoreCase(String text, String needle) {
    for (int i = text.indexOf('<'); i >= 0; i = text.indexOf('<', i + 1)) {
      if (text.regionMatches(true, i, needle, 0, needle.length())) {
        return true;
      }
    }
    return false;
  }

  private void hole(Skeleton.Hole h, Path p, SourcePos pos) throws EngineException {
    boundary(p, pos);
    Map<Problem, List<ParserState>> problems = new LinkedHashMap<>();
    boolean formattingTag = false;
    for (ParserState s : p.states()) {
      // The tag being read starts in the input since the parser was last between tokens.
      formattingTag |= h.escaper().inAttribute() && hasFormattingStartTag(s.pendingInput());
      Problem problem = Rules.hole(h, s);
      if (problem != null) {
        if (!problems.containsKey(problem)) {
          problems.put(problem, new ArrayList<ParserState>());
        }
        problems.get(problem).add(s);
      }
    }
    for (Map.Entry<Problem, List<ParserState>> e : problems.entrySet()) {
      add(e.getKey(), pos, h.escaper(), null, h.attribute(), e.getValue(), null);
    }
    if (h.escaper().inAttribute()) {
      p.defer(Rules.MARK, pos);
      p.tagHole |= formattingTag;
    } else if (h.escaper() == Skeleton.Escaper.JSON) {
      for (ParserState s : p.states()) {
        if (s.tokenizer() == TokenizerState.SCRIPT_DATA_LESS_THAN_SIGN) {
          // A JSON value is never empty and never starts with '/' or '!': any first character
          // ends the '<' alike. Elsewhere it is inert, so this is the exit state of every value.
          p.defer("0", pos);
          break;
        }
      }
    }
  }

  /**
   * HTML known only at render time: its {@link ContentKinds kind}'s predicate must hold in every
   * parser state, and then the value leaves the parser where it was (in template mode, also where a
   * flow start tag switches it to).
   */
  private List<Path> raw(Skeleton.Source source, List<Path> in, SourcePos pos) throws EngineException {
    ContentKinds.Kind kind = ContentKinds.Kind.FLOW;
    List<Path> out = new ArrayList<>(in);
    for (Path p : in) {
      boundary(p, pos);
      Map<Problem, List<ParserState>> problems = new LinkedHashMap<>();
      boolean switches = false;
      for (ParserState s : p.states()) {
        Problem problem = ContentKinds.problem(kind, s);
        if (problem != null) {
          if (!problems.containsKey(problem)) {
            problems.put(problem, new ArrayList<ParserState>());
          }
          problems.get(problem).add(s);
        } else {
          switches |= ContentKinds.switchesMode(kind, s);
        }
      }
      for (Map.Entry<Problem, List<ParserState>> e : problems.entrySet()) {
        String detail = e.getKey() == Problem.RAW_CLOSES_ELEMENT ? ContentKinds.closer(e.getValue().get(0)) : null;
        add(e.getKey(), pos, null, source, null, e.getValue(), detail);
      }
      if (switches) {
        Path written = p.fork();
        written.feed(ContentKinds.REPRESENTATIVE, pos);
        out.add(written);
      }
    }
    return out.size() == in.size() ? out : dedupe(out, pos);
  }

  /**
   * HTML known at build time (an HTML message, #329): its markup is analysed as it is, holes and
   * all, and must leave the parser where it found it, as {@link ContentKinds.Kind#FLOW} content
   * does (in template mode, also where a flow start tag switches it to). Markup that closes an
   * element open around it (a {@code <p>} in a {@code <p>}) or leaves one of its own open is
   * {@link Problem#MESSAGE_CHANGES_STATE}.
   */
  private List<Path> known(Skeleton.RawInsert r, List<Path> in, Env env, SourcePos pos) throws EngineException {
    List<Path> out = new ArrayList<>(in);
    for (Path p : in) {
      boundary(p, pos);
      Set<String> allowed = new HashSet<>();
      allowed.add(p.key());
      boolean template = false;
      for (ParserState s : p.states()) {
        template |= ContentKinds.switchesMode(ContentKinds.Kind.FLOW, s);
      }
      if (template) {
        Path written = p.fork();
        written.feed(ContentKinds.REPRESENTATIVE, pos);
        allowed.add(written.key());
      }
      ParserState[] entry = p.states();
      for (Path exit : seq(r.content(), Collections.singletonList(p.fork()), env)) {
        if (allowed.contains(exit.key())) {
          if (!exit.key().equals(p.key())) {
            out.add(exit);
          }
          continue;
        }
        ParserState[] states = exit.states();
        List<ParserState> bad = new ArrayList<>();
        String detail = null;
        for (int i = 0; i < states.length; i++) {
          if (!states[i].equals(entry[i])) {
            bad.add(states[i]);
            if (detail == null) {
              detail = ContentKinds.difference(entry[i], states[i]);
            }
          }
        }
        add(Problem.MESSAGE_CHANGES_STATE, pos, null, r.source(), null, bad, detail);
      }
    }
    return out.size() == in.size() ? out : dedupe(out, pos);
  }

  /** A value right after a computed attribute that stands for its absence too: not interpretable. */
  private void boundary(Path p, SourcePos pos) {
    if (p.tagBoundary) {
      add(Problem.UNKNOWN_NODE, pos, null, null, null, Collections.<ParserState>emptyList(),
          "a value right after a computed attribute");
      p.tagBoundary = false;
      p.key = null;
    }
  }

  private void add(Problem problem, SourcePos pos, Skeleton.Escaper escaper, Skeleton.Source source, String attribute,
      List<ParserState> states, String detail) {
    sink.add(new Finding(problem, pos, null, null, escaper, source, attribute, states, parserCount, detail, recursive()));
  }

  /** Whether a fragment is on the call stack twice: this is reached only through recursion. */
  private boolean recursive() {
    return new HashSet<>(callStack).size() < callStack.size();
  }

  // ---- fragment calls ----

  private static final class Memo {
    final List<Path> exits;
    final List<Finding> findings;

    Memo(List<Path> exits, List<Finding> findings) {
      this.exits = exits;
      this.findings = findings;
    }
  }

  private static final class InProgress {
    final int depth;
    List<Path> approx = Collections.emptyList();
    boolean used;

    InProgress(int depth) {
      this.depth = depth;
    }
  }

  private List<Path> call(Skeleton.Call c, Path p, Env env, SourcePos pos) throws EngineException {
    Skeleton.Seq target = fragments.get(c.target());
    if (target == null) {
      add(Problem.UNKNOWN_NODE, pos, null, null, null, Collections.<ParserState>emptyList(), "fragment " + c.target());
      return Collections.emptyList();
    }
    Env callee = env.bind(c.fragmentArgs());
    String key = c.target() + "\n" + callee.key(seqIds) + "\n" + p.key();
    Memo m = memo.get(key);
    if (m != null) {
      emit(m.findings, pos, c.target());
      return forks(m.exits);
    }
    InProgress running = inProgress.get(key);
    if (running != null) {
      // Recursion into the same state: use the approximation so far, and iterate to a fixed point.
      running.used = true;
      approxDepth = Math.min(approxDepth, running.depth);
      return forks(running.approx);
    }
    int same = 0;
    for (Skeleton.FragmentId f : callStack) {
      if (f.equals(c.target())) {
        same++;
      }
    }
    // A fragment that calls itself in many new states (rich text rebuilt by tag name) is
    // data-dependent structure long before the depth bound: count its entries while it recurses.
    int entries = same == 0 ? 0 : recursiveEntries.containsKey(c.target()) ? recursiveEntries.get(c.target()) + 1 : 1;
    if (same > 0) {
      recursiveEntries.put(c.target(), entries);
    }
    if (same >= MAX_ROUNDS || entries > MAX_STATES) {
      // Recursion into ever new states (deeper nesting each time): the structure depends on data.
      sink.add(new Finding(Problem.STRUCTURE_DEPENDS_ON_DATA, pos, null, null, null, null, null,
          Collections.<ParserState>emptyList(), parserCount, null, true));
      return Collections.emptyList();
    }
    int depth = callStack.size();
    running = new InProgress(depth);
    inProgress.put(key, running);
    callStack.add(c.target());
    int outerApprox = approxDepth;
    int readApprox = Integer.MAX_VALUE;
    List<Finding> outer = sink;
    List<Path> exits;
    List<Finding> found;
    SourcePos outerNear = near;
    try {
      for (int round = 0; ; round++) {
        approxDepth = Integer.MAX_VALUE;
        found = new ArrayList<>();
        sink = found;
        running.used = false;
        exits = seq(target, Collections.singletonList(p.fork()), callee);
        for (Path e : exits) {
          // Input the fragment deferred is the fragment's: its errors belong to this call.
          e.flush();
        }
        exits = dedupe(exits, pos);
        readApprox = Math.min(readApprox, approxDepth);
        if (!running.used || keys(exits).equals(keys(running.approx))) {
          break;
        }
        if (round + 1 >= MAX_ROUNDS) {
          found.add(new Finding(Problem.STRUCTURE_DEPENDS_ON_DATA, pos, null, null, null, null, null,
              Collections.<ParserState>emptyList(), parserCount, null, true));
          break;
        }
        running.approx = exits;
      }
    } finally {
      sink = outer;
      near = outerNear;
      callStack.remove(callStack.size() - 1);
      inProgress.remove(key);
      if (!callStack.contains(c.target())) {
        recursiveEntries.remove(c.target());
      }
    }
    // A result that read an outer call's approximation isn't final: don't memoise it.
    approxDepth = Math.min(outerApprox, readApprox < depth ? readApprox : Integer.MAX_VALUE);
    if (readApprox >= depth) {
      memo.put(key, new Memo(exits, found));
      exits = forks(exits);
    }
    emit(found, pos, c.target());
    return exits;
  }

  private void emit(List<Finding> found, SourcePos callSite, Skeleton.FragmentId target) {
    for (Finding f : found) {
      sink.add(f.via(callSite, target));
    }
  }

  private static Set<String> keys(List<Path> paths) throws EngineException {
    Set<String> out = new HashSet<>();
    for (Path p : paths) {
      out.add(p.key());
    }
    return out;
  }

  // ---- fragment arguments ----

  /** A {@code Fragment} parameter bound at a call site: a skeleton, read in the caller's bindings. */
  private static final class Binding {
    final Skeleton.Seq seq;
    final Env env;

    Binding(Skeleton.Seq seq, Env env) {
      this.seq = seq;
      this.env = env;
    }
  }

  /** The bound {@code Fragment} parameters of the fragment being analysed. */
  private static final class Env {
    static final Env EMPTY = new Env(Collections.<String, Binding>emptyMap());

    final Map<String, Binding> bindings;
    private String key;

    Env(Map<String, Binding> bindings) {
      this.bindings = bindings;
    }

    Env bind(Map<String, Skeleton.Seq> args) {
      if (args.isEmpty()) {
        return EMPTY;
      }
      Map<String, Binding> b = new TreeMap<>();
      for (Map.Entry<String, Skeleton.Seq> a : args.entrySet()) {
        b.put(a.getKey(), new Binding(a.getValue(), this));
      }
      return new Env(b);
    }

    /** Identifies the bindings: each skeleton by identity, in its own bindings. */
    String key(Map<Skeleton.Seq, Integer> ids) {
      if (key == null) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Binding> e : new TreeMap<>(bindings).entrySet()) {
          Integer id = ids.get(e.getValue().seq);
          if (id == null) {
            id = ids.size();
            ids.put(e.getValue().seq, id);
          }
          sb.append(e.getKey()).append('=').append(id).append('{').append(e.getValue().env.key(ids)).append('}');
        }
        key = sb.toString();
      }
      return key;
    }
  }

  // ---- paths ----

  /**
   * A path at the start of a template: document parsing (null {@code context}) or fragment
   * parsing in an HTML {@code context}, in each engine, with scripting on and, if {@code
   * scriptingOff}, off.
   */
  private Path start(String context, boolean scriptingOff) throws EngineException {
    List<Parser> ps = new ArrayList<>();
    for (Engine e : engines) {
      for (boolean on : scriptingOff ? new boolean[] {true, false} : new boolean[] {true}) {
        ps.add(context == null ? e.document(on) : e.fragment(OpenElement.HTML, context, on));
      }
    }
    return new Path(this, ps.toArray(new Parser[0]));
  }

  /**
   * What the parsers of a path reported while they parsed one chunk (#406): parse errors not on
   * the allow-list, and excluded stack shapes, each with the parsers that reported it.
   */
  private void reported(Path p, Map<String, Reported> issues) throws EngineException {
    for (Reported r : issues.values()) {
      List<ParserState> states = new ArrayList<>();
      for (int i : r.parsers) {
        states.add(p.parsers[i].state());
      }
      add(r.problem, r.pos != null ? r.pos : near, null, null, null, states, r.detail);
    }
  }

  /** One parse error or excluded shape at one position. */
  private static final class Reported {
    final Problem problem;
    final String detail;
    final SourcePos pos;
    final List<Integer> parsers = new ArrayList<>();

    Reported(Problem problem, String detail, SourcePos pos) {
      this.problem = problem;
      this.detail = detail;
      this.pos = pos;
    }
  }

  /** Text and the source position of each part of it (the statics it was written from). */
  private static final class Located {
    final String text;
    /** Where each part starts in {@link #text}, ascending, the first at 0. */
    private final int[] starts;
    private final SourcePos[] positions;

    Located(String text, SourcePos pos) {
      this(text, new int[] {0}, new SourcePos[] {pos});
    }

    private Located(String text, int[] starts, SourcePos[] positions) {
      this.text = text;
      this.starts = starts;
      this.positions = positions;
    }

    /** The position of the part that holds {@code offset}. */
    SourcePos at(int offset) {
      int k = 0;
      while (k + 1 < starts.length && starts[k + 1] <= offset) {
        k++;
      }
      return positions[k];
    }

    Located sub(int from, int to) {
      Builder b = new Builder();
      for (int k = 0; k < starts.length; k++) {
        int s = Math.max(from, starts[k]);
        int e = Math.min(to, k + 1 < starts.length ? starts[k + 1] : text.length());
        if (s < e) {
          b.append(text.substring(s, e), positions[k]);
        }
      }
      return b.build();
    }

    static final class Builder {
      private final StringBuilder text = new StringBuilder();
      private final List<Integer> starts = new ArrayList<>();
      private final List<SourcePos> positions = new ArrayList<>();

      boolean isEmpty() {
        return text.length() == 0;
      }

      Builder append(String s, SourcePos pos) {
        if (!s.isEmpty()) {
          starts.add(text.length());
          positions.add(pos);
          text.append(s);
        }
        return this;
      }

      Builder append(Located l) {
        for (int k = 0; k < l.starts.length; k++) {
          append(l.text.substring(l.starts[k], k + 1 < l.starts.length ? l.starts[k + 1] : l.text.length()),
              l.positions[k]);
        }
        return this;
      }

      Located build() {
        if (starts.isEmpty()) {
          return new Located("", null);
        }
        int[] s = new int[starts.size()];
        for (int i = 0; i < s.length; i++) {
          s[i] = starts.get(i);
        }
        return new Located(text.toString(), s, positions.toArray(new SourcePos[0]));
      }
    }
  }

  /** One path through the template: a parser per engine and scripting mode, in the same input. */
  private static final class Path {
    /** The verifier the parsers report parse errors and excluded shapes to. */
    private final ContextVerifier owner;
    private final Parser[] parsers;
    /**
     * An attribute hole was written since the parser was last between tokens, in a tag that may be
     * a formatting element's.
     */
    boolean tagHole;
    /** A formatting element with a computed attribute is in the list of active formatting elements. */
    boolean markedFormatting;
    /**
     * This path stands also for a computed attribute right after the tag name not written: the
     * next character must be whitespace, '/' or '>' ({@link ContextVerifier#attribute}).
     */
    boolean tagBoundary;
    private ParserState[] states;
    String key;
    /** Input written to no parser yet: fed with the next input, before any state is read. */
    private Located.Builder deferred = new Located.Builder();
    /**
     * Per parser, the problems it reported on this path (bits by {@link Problem#ordinal}): each is
     * reported once, at its first occurrence, so the errors that follow from the first one (a
     * stray end tag after a misplaced start tag) don't bury it. Part of the {@link #key}.
     */
    private final long[] reported;

    private Path(ContextVerifier owner, Parser[] parsers) {
      this.owner = owner;
      this.parsers = parsers;
      this.reported = new long[parsers.length];
    }

    int size() {
      return parsers.length;
    }

    Path fork() throws EngineException {
      flush();
      Parser[] copy = new Parser[parsers.length];
      for (int i = 0; i < parsers.length; i++) {
        copy[i] = parsers[i].fork();
      }
      Path p = new Path(owner, copy);
      System.arraycopy(reported, 0, p.reported, 0, reported.length);
      p.tagHole = tagHole;
      p.markedFormatting = markedFormatting;
      p.tagBoundary = tagBoundary;
      p.states = states;
      p.key = key;
      return p;
    }

    void feed(String text, SourcePos pos) throws EngineException {
      defer(text, pos);
      flush();
    }

    void feed(Located text) throws EngineException {
      defer(text);
      flush();
    }

    /** Feeds {@code text} with the next input: one parser feed fewer per attribute hole. */
    void defer(String text, SourcePos pos) {
      if (!text.isEmpty()) {
        deferred.append(text, pos);
        states = null;
        key = null;
      }
    }

    void defer(Located text) {
      if (!text.text.isEmpty()) {
        deferred.append(text);
        states = null;
        key = null;
      }
    }

    void flush() throws EngineException {
      if (deferred.isEmpty()) {
        return;
      }
      final Located text = deferred.build();
      deferred = new Located.Builder();
      final Map<String, Reported> issues = new LinkedHashMap<>();
      for (int i = 0; i < parsers.length; i++) {
        final int parser = i;
        parsers[i].feed(text.text, new Parser.Listener() {
          @Override
          public void error(String message, int offset) {
            if (!Rules.allowedError(message)) {
              note(Problem.PARSE_ERROR, message, offset);
            }
          }

          @Override
          public void pushed(Parser.Stack stack, int offset) {
            Rules.Excluded x = Rules.exclusion(stack);
            if (x != null) {
              note(x.problem, x.detail, offset);
            }
          }

          private void note(Problem problem, String detail, int offset) {
            long bit = 1L << problem.ordinal();
            if ((reported[parser] & bit) != 0) {
              return;
            }
            reported[parser] |= bit;
            SourcePos pos = text.at(offset);
            String k = problem + "\u0000" + detail + "\u0000" + pos;
            Reported r = issues.get(k);
            if (r == null) {
              r = new Reported(problem, detail, pos);
              issues.put(k, r);
            }
            r.parsers.add(parser);
          }
        });
      }
      states = null;
      key = null;
      if (!issues.isEmpty()) {
        owner.reported(this, issues);
      }
    }

    ParserState[] states() throws EngineException {
      flush();
      if (states == null) {
        ParserState[] s = new ParserState[parsers.length];
        for (int i = 0; i < parsers.length; i++) {
          s[i] = parsers[i].state();
        }
        states = s;
      }
      return states;
    }

    /** The combined signature: equal keys parse every continuation alike in all the parsers. */
    String key() throws EngineException {
      if (key == null) {
        StringBuilder sb = new StringBuilder();
        for (ParserState s : states()) {
          sb.append(s.signature()).append('\u0000');
        }
        sb.append(tagHole ? 'h' : '-').append(markedFormatting ? 'm' : '-').append(tagBoundary ? 'b' : '-');
        for (long r : reported) {
          sb.append(r == 0 ? "" : Long.toHexString(r)).append(',');
        }
        key = sb.toString();
      }
      return key;
    }
  }
}
