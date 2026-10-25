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
 * for markup contexts. It runs each template's {@link Skeleton} through two spec HTML parsers
 * ({@link Engines#all()}), with scripting on and off, and accepts a hole only if its escaper's
 * output is inert in the parser state there in all four ({@link Rules}). A template it doesn't
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

  ContextVerifier(Map<String, Skeleton.TemplateSkeleton> templates) {
    for (Skeleton.TemplateSkeleton t : templates.values()) {
      for (Map.Entry<String, Skeleton.Seq> f : t.fragments().entrySet()) {
        fragments.put(new Skeleton.FragmentId(t.viewName(), f.getKey()), f.getValue());
      }
    }
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

  /** What the verifier rejects in {@code template}, deduplicated, in order. */
  List<Finding> check(Skeleton.TemplateSkeleton template) {
    Map<String, Finding> out = new LinkedHashMap<>();
    SourcePos start = firstPos(template);
    // The scripting flag changes only how a <noscript> tag parses: without one, the parsers with
    // scripting off stay in the states of those with it on.
    boolean scriptingOff = mentionsNoscript(template.body(), Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()));
    parserCount = (scriptingOff ? 2 : 1) * Engines.all().size();
    for (String context : entries(template)) {
      List<Finding> found = new ArrayList<>();
      sink = found;
      near = start;
      try {
        List<Path> paths = Collections.singletonList(Path.start(context, scriptingOff));
        seq(template.body(), paths, Env.EMPTY);
      } catch (EngineException e) {
        found.add(new Finding(Problem.ENGINE_FAILURE, near, null, null, null, null, null,
            Collections.<ParserState>emptyList(), parserCount, e.getMessage(), false));
      }
      for (Finding f : found) {
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
    StringBuilder statics = new StringBuilder();
    SourcePos staticPos = null;
    boolean frameset = false;
    for (Skeleton.Node n : seq.nodes()) {
      if (n instanceof Skeleton.Static) {
        String text = ((Skeleton.Static) n).text();
        if (containsIgnoreCase(text, "<frameset")) {
          add(Problem.FRAMESET, at(n), null, null, null, Collections.<ParserState>emptyList(), null);
          frameset = true;
        }
        statics.append(text);
        if (staticPos == null) {
          staticPos = n.pos();
        }
        continue;
      }
      if (statics.length() > 0) {
        cur = statics(cur, statics.toString(), staticPos, !frameset);
        statics.setLength(0);
        staticPos = null;
        frameset = false;
      }
      cur = node(n, cur, env);
    }
    if (statics.length() > 0) {
      cur = statics(cur, statics.toString(), staticPos, !frameset);
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
  private List<Path> statics(List<Path> in, String text, SourcePos pos, boolean frameset) throws EngineException {
    if (pos != null) {
      near = pos;
    }
    pos = near;
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
      if (!p.tagHole && !p.markedFormatting) {
        int lt = text.lastIndexOf('<');
        if (lt > 0 && text.indexOf('>', lt) < 0) {
          // A tag that goes on past this static: feed the text before it on its own, so the
          // parser replays only the tag's own input while it is inside it (VnuParser).
          p.feed(text.substring(0, lt));
          p.defer(text.substring(lt));
        } else {
          p.feed(text);
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
          feedAndCheck(p, text.substring(from, i), pos);
          from = i;
        }
      }
      feedAndCheck(p, text.substring(from), pos);
    }
    return in;
  }

  private void feedAndCheck(Path p, String piece, SourcePos pos) throws EngineException {
    p.feed(piece);
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
      p.defer(Rules.MARK);
      p.tagHole |= formattingTag;
    } else if (h.escaper() == Skeleton.Escaper.JSON) {
      for (ParserState s : p.states()) {
        if (s.tokenizer() == TokenizerState.SCRIPT_DATA_LESS_THAN_SIGN) {
          // A JSON value is never empty and never starts with '/' or '!': any first character
          // ends the '<' alike. Elsewhere it is inert, so this is the exit state of every value.
          p.defer("0");
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
        written.feed(ContentKinds.REPRESENTATIVE);
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
        written.feed(ContentKinds.REPRESENTATIVE);
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

  /** One path through the template: a parser per engine and scripting mode, in the same input. */
  private static final class Path {
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
    private final StringBuilder deferred = new StringBuilder();

    private Path(Parser[] parsers) {
      this.parsers = parsers;
    }

    /**
     * Document parsing (null {@code context}) or fragment parsing in an HTML {@code context}, in
     * each engine, with scripting on and, if {@code scriptingOff}, off.
     */
    static Path start(String context, boolean scriptingOff) throws EngineException {
      List<Parser> ps = new ArrayList<>();
      for (Engine e : Engines.all()) {
        for (boolean scripting : scriptingOff ? new boolean[] {true, false} : new boolean[] {true}) {
          ps.add(context == null ? e.document(scripting) : e.fragment(OpenElement.HTML, context, scripting));
        }
      }
      return new Path(ps.toArray(new Parser[0]));
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
      Path p = new Path(copy);
      p.tagHole = tagHole;
      p.markedFormatting = markedFormatting;
      p.tagBoundary = tagBoundary;
      p.states = states;
      p.key = key;
      return p;
    }

    void feed(String text) throws EngineException {
      defer(text);
      flush();
    }

    /** Feeds {@code text} with the next input: one parser feed fewer per attribute hole. */
    void defer(String text) {
      if (!text.isEmpty()) {
        deferred.append(text);
        states = null;
        key = null;
      }
    }

    private void flush() throws EngineException {
      if (deferred.length() == 0) {
        return;
      }
      String text = deferred.toString();
      deferred.setLength(0);
      for (Parser parser : parsers) {
        parser.feed(text);
      }
      states = null;
      key = null;
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
        key = sb.toString();
      }
      return key;
    }
  }
}
