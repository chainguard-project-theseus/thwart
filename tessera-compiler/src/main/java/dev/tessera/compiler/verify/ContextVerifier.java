package dev.tessera.compiler.verify;

import dev.tessera.compiler.DiagnosticSink;
import dev.tessera.compiler.SourcePos;
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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The build-time context verifier (#367, docs/security-architecture.md §11): the security boundary
 * for markup contexts. It runs each template's {@link Skeleton} through the spec HTML parser
 * ({@link Engines#CURRENT}), with scripting on and off, and accepts a hole only if its escaper's
 * output is inert in the parser state there in both ({@link Rules}). A template it doesn't
 * accept is a build error at the hole's file, line and column.
 *
 * <p>It is an abstract interpretation with the parsers as the transfer function. A path holds the
 * two parsers (one, when the template has no {@code <noscript>}). Statics are fed as they are. A
 * hole is checked and leaves the state as it was (that is what "inert" means); an attribute hole
 * writes nothing into the value. Paths are merged by their parser states' signatures
 * at every join: an optional part ({@code t:if}, {@code t:case}, a computed attribute) is the
 * union of skipping it and writing it, a loop runs to a fixed point, and a static fragment call is
 * analysed at each call site, memoised per (fragment, entry state, bound fragment arguments), with
 * recursion as a fixed point too.
 *
 * <p>It accepts only error-free parses (#406, security-architecture.md §11.4): every parse error the
 * engine finds on any path, with scripting on and off, is {@link Problem#PARSE_ERROR} at the static
 * that holds it, except the allow-list ({@link Rules#allowedError}); a partial counts them only in
 * its fragment contexts. And it checks
 * the coarse exclusions ({@link Rules#exclusion}) at every element the parser inserts, and at
 * rejects every CDATA section it starts ({@link Problem#CDATA_SECTION}).
 *
 * <p>It fails closed: an engine failure, too many states, a loop or recursion without a fixed
 * point and a node it can't interpret are build errors.
 */
public final class ContextVerifier {
  /** At most this many distinct parser states at a join. */
  static final int MAX_STATES = 24;

  /** A loop or a recursion must reach its fixed point within this many rounds. */
  static final int MAX_ROUNDS = 4;

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
  /** The parsing context each fragment is declared in ({@link Skeleton.TemplateSkeleton#fragmentContext}). */
  private final Map<Skeleton.FragmentId, String> fragmentContexts = new HashMap<>();
  private final Map<String, Memo> memo = new HashMap<>();
  private final Map<String, InProgress> inProgress = new HashMap<>();
  private final List<Skeleton.FragmentId> callStack = new ArrayList<>();
  /** The position of each call on {@link #callStack}, for a recursion's cycle in messages (#393). */
  private final List<SourcePos> callSites = new ArrayList<>();
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
        Skeleton.FragmentId id = new Skeleton.FragmentId(t.viewName(), f.getKey());
        fragments.put(id, f.getValue());
        fragmentContexts.put(id, t.fragmentContext(f.getKey()));
      }
    }
  }

  /**
   * Verifies every template in {@code templates} (by view name, as the compiler recorded them)
   * and reports what it rejects to {@code diagnostics} as errors.
   */
  public static void verify(
      Map<String, Skeleton.TemplateSkeleton> templates, DiagnosticSink diagnostics) {
    ContextVerifier v = new ContextVerifier(templates);
    for (Skeleton.TemplateSkeleton t : templates.values()) {
      for (Finding f : v.check(t)) {
        diagnostics.error(f.at, VerifierMessages.message(f));
      }
    }
    for (Skeleton.FragmentId id : uncalled(templates)) {
      for (Finding f : v.checkUncalled(id)) {
        diagnostics.error(f.at, VerifierMessages.message(f));
      }
    }
  }

  /**
   * The fragments that no template's body reaches through fragment calls, in declaration order
   * (#461). The verifier sees the others in the states their calls write them in; these it checks
   * on their own ({@link #checkUncalled}). A fragment that only such a fragment calls is checked
   * through that call.
   */
  static List<Skeleton.FragmentId> uncalled(Map<String, Skeleton.TemplateSkeleton> templates) {
    Map<Skeleton.FragmentId, Skeleton.Seq> all = new LinkedHashMap<>();
    List<Skeleton.Seq> work = new ArrayList<>();
    for (Skeleton.TemplateSkeleton t : templates.values()) {
      for (Map.Entry<String, Skeleton.Seq> f : t.fragments().entrySet()) {
        all.put(new Skeleton.FragmentId(t.viewName(), f.getKey()), f.getValue());
      }
      work.add(t.body());
    }
    Set<Skeleton.FragmentId> reached = new HashSet<>();
    List<Skeleton.FragmentId> out = new ArrayList<>();
    for (Map.Entry<Skeleton.FragmentId, Skeleton.Seq> f : all.entrySet()) {
      while (!work.isEmpty()) {
        calls(work.remove(work.size() - 1), all, reached, work);
      }
      if (reached.add(f.getKey())) {
        out.add(f.getKey());
        work.add(f.getValue());
      }
    }
    return out;
  }

  /** Adds the fragments {@code n} calls, and their skeletons to {@code work}, to {@code reached}. */
  private static void calls(Skeleton.Node n, Map<Skeleton.FragmentId, Skeleton.Seq> all,
      Set<Skeleton.FragmentId> reached, List<Skeleton.Seq> work) {
    if (n instanceof Skeleton.Seq) {
      for (Skeleton.Node c : ((Skeleton.Seq) n).nodes()) {
        calls(c, all, reached, work);
      }
    } else if (n instanceof Skeleton.Opt) {
      calls(((Skeleton.Opt) n).body(), all, reached, work);
    } else if (n instanceof Skeleton.Rep) {
      calls(((Skeleton.Rep) n).body(), all, reached, work);
    } else if (n instanceof Skeleton.Alt) {
      for (Skeleton.Seq b : ((Skeleton.Alt) n).branches()) {
        calls(b, all, reached, work);
      }
    } else if (n instanceof Skeleton.Call) {
      Skeleton.Call c = (Skeleton.Call) n;
      for (Skeleton.Seq a : c.fragmentArgs().values()) {
        calls(a, all, reached, work);
      }
      if (all.containsKey(c.target()) && reached.add(c.target())) {
        work.add(all.get(c.target()));
      }
    }
  }

  /** Fragment parsing contexts for fragments declared outside HTML content, by declared context. */
  private static final Map<String, OpenElement> DECLARED_CONTEXTS = new HashMap<>();

  static {
    DECLARED_CONTEXTS.put("svg", new OpenElement(OpenElement.SVG, "svg", false));
    DECLARED_CONTEXTS.put("math", new OpenElement(OpenElement.MATHML, "math", false));
    DECLARED_CONTEXTS.put("annotation-xml", new OpenElement(OpenElement.MATHML, "annotation-xml", false));
    DECLARED_CONTEXTS.put("select", new OpenElement(OpenElement.HTML, "select", false));
  }

  /**
   * What the verifier rejects in a fragment no template calls ({@link #uncalled}, #461), checked on
   * its own like a partial: declared in HTML content, in the fragment contexts its root elements
   * imply (as a document only if it is a page); declared in {@code <svg>}, {@code <math>} or
   * {@code <select>} content, in that element. Declared where no fragment fits (raw text or
   * {@code t:remove="tag"} content), it can't be checked: {@link
   * Problem#UNCALLED_FRAGMENT_FITS_NOWHERE}.
   */
  List<Finding> checkUncalled(Skeleton.FragmentId id) {
    Skeleton.TemplateSkeleton t =
        new Skeleton.TemplateSkeleton(id.view(), fragments.get(id), Collections.<String, Skeleton.Seq>emptyMap());
    String declared = fragmentContexts.get(id);
    List<OpenElement> contexts = new ArrayList<>();
    if ("HTML".equals(declared)) {
      List<String> entries = entries(t);
      for (String e : entries.size() == 1 ? entries : entries.subList(1, entries.size())) {
        contexts.add(e == null ? null : new OpenElement(OpenElement.HTML, e, false));
      }
    } else if (DECLARED_CONTEXTS.containsKey(declared)) {
      contexts.add(DECLARED_CONTEXTS.get(declared));
    } else {
      SourcePos at = firstPos(t);
      for (Skeleton.Node n : t.body().nodes()) {
        if (n.pos() != null) {
          at = n.pos();
          break;
        }
      }
      return Collections.singletonList(new Finding(Problem.UNCALLED_FRAGMENT_FITS_NOWHERE, at, null, null, null, null,
          null, Collections.<ParserState>emptyList(), 1, id.toString(), false));
    }
    return check(t, contexts);
  }

  /**
   * The fragments of {@code templates} the verifier accepts on their own, as a response body
   * (#131), named {@code page :: fragment}: checked as {@link #checkUncalled} checks a fragment no
   * template calls. A called fragment was checked only in the states its calls write it in, so it is
   * checked here again; one with a finding, or that fits nowhere, is left out (it gets no registry
   * entry) and is not an error. Call it only when {@link #verify} reported nothing.
   */
  public static Set<String> servableFragments(Map<String, Skeleton.TemplateSkeleton> templates) {
    ContextVerifier v = new ContextVerifier(templates);
    Set<String> out = new LinkedHashSet<>();
    for (Skeleton.TemplateSkeleton t : templates.values()) {
      for (String name : t.fragments().keySet()) {
        Skeleton.FragmentId id = new Skeleton.FragmentId(t.viewName(), name);
        if (v.checkUncalled(id).isEmpty()) {
          out.add(id.toString());
        }
      }
    }
    return out;
  }

  /** What the verifier rejects in {@code template}, deduplicated, in order. */
  List<Finding> check(Skeleton.TemplateSkeleton template) {
    List<OpenElement> contexts = new ArrayList<>();
    for (String e : entries(template)) {
      contexts.add(e == null ? null : new OpenElement(OpenElement.HTML, e, false));
    }
    return check(template, contexts);
  }

  /** What the verifier rejects in {@code template} parsed in each of {@code entries} (null: as a document). */
  private List<Finding> check(Skeleton.TemplateSkeleton template, List<OpenElement> entries) {
    Map<String, Finding> out = new LinkedHashMap<>();
    SourcePos start = firstPos(template);
    // The scripting flag changes only how a <noscript> tag parses: without one, the parsers with
    // scripting off stay in the states of those with it on.
    boolean scriptingOff = mentionsNoscript(template.body(), Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()));
    parserCount = scriptingOff ? 2 : 1;
    for (OpenElement context : entries) {
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
            Skeleton.Node after = new Skeleton.Static(text.substring(end + 3), n.pos());
            firstElementsThen(Collections.singletonList(after), nodes, i, out, depth);
            return;
          }
          if (end >= 0) {
            continue;
          }
        }
        if (Rules.asciiStartsWith(text, k, "<!doctype")) {
          out.add("!doctype");
          return;
        }
        if (k + 1 < text.length() && text.charAt(k) == '<' && Rules.isAsciiAlpha(text.charAt(k + 1))) {
          int e = k + 1;
          while (e < text.length() && !isSpace(text.charAt(e)) && text.charAt(e) != '>' && text.charAt(e) != '/') {
            e++;
          }
          out.add(Rules.asciiLower(text.substring(k + 1, e)));
          return;
        }
        out.add("#text");
        return;
      } else if (n instanceof Skeleton.Seq) {
        firstElementsThen(((Skeleton.Seq) n).nodes(), nodes, i, out, depth);
        return;
      } else if (n instanceof Skeleton.Opt || n instanceof Skeleton.Rep) {
        Skeleton.Seq body = n instanceof Skeleton.Opt ? ((Skeleton.Opt) n).body() : ((Skeleton.Rep) n).body();
        firstElementsThen(body.nodes(), nodes, i, out, depth);
        // ... or skipped.
      } else if (n instanceof Skeleton.Alt) {
        for (Skeleton.Seq b : ((Skeleton.Alt) n).branches()) {
          firstElementsThen(b.nodes(), nodes, i, out, depth);
        }
        return;
      } else if (n instanceof Skeleton.Call && depth < MAX_ROUNDS) {
        Skeleton.Seq target = fragments.get(((Skeleton.Call) n).target());
        if (target != null) {
          firstElementsThen(target.nodes(), nodes, i, out, depth + 1);
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

  /** {@link #firstElements} of {@code head} followed by the nodes after {@code nodes[i]}. */
  private void firstElementsThen(List<Skeleton.Node> head, List<Skeleton.Node> nodes, int i, Set<String> out, int depth) {
    List<Skeleton.Node> rest = new ArrayList<>(head);
    rest.addAll(nodes.subList(i + 1, nodes.size()));
    firstElements(rest, 0, out, depth);
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
        found = Rules.asciiLower(((Skeleton.Static) n).text()).contains("noscript");
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
    for (Skeleton.Node n : seq.nodes()) {
      if (n instanceof Skeleton.Static) {
        String text = ((Skeleton.Static) n).text();
        if (staticPos == null) {
          staticPos = n.pos();
        }
        // Each static keeps its position, so a parse error points at the markup that holds it.
        statics.append(text, n.pos() != null ? n.pos() : staticPos != null ? staticPos : near);
        continue;
      }
      if (!statics.isEmpty()) {
        cur = statics(cur, statics.build(), staticPos);
        statics = new Located.Builder();
        staticPos = null;
      }
      cur = node(n, cur, env);
    }
    if (!statics.isEmpty()) {
      cur = statics(cur, statics.build(), staticPos);
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
   * through {@link #PRESENCE_MATTERS}, through duplicate names, and where the attribute makes a
   * parse error ({@code <font color>} in SVG), where the written attribute is the stricter case
   * (a later duplicate is dropped). An {@code <input>} in a table is a parse error with any
   * {@code type}. So the written path alone stands
   * for both, and a tag with many computed attributes doesn't multiply the states. Otherwise both
   * are kept, as for any optional part.
   */
  private List<Path> attribute(Skeleton.Opt o, List<Path> in, Env env, SourcePos pos) throws EngineException {
    String name = null;
    for (Skeleton.Node b : o.body().nodes()) {
      if (b instanceof Skeleton.Hole && ((Skeleton.Hole) b).attribute() != null) {
        name = Rules.asciiLower(((Skeleton.Hole) b).attribute());
        break;
      }
    }
    List<Path> all = new ArrayList<>();
    for (Path p : in) {
      ParserState[] entry = p.states();
      boolean pendingBoundary = p.tagBoundary;
      List<Path> written = seq(o.body(), Collections.singletonList(p.fork()), env);
      boolean merge = name != null && !PRESENCE_MATTERS.equals(name) && !written.isEmpty();
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
   * The attribute whose presence changes how the rest parses without a parse error either way:
   * {@code encoding} (MathML {@code annotation-xml} is an HTML integration point with it).
   */
  private static final String PRESENCE_MATTERS = "encoding";

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

  private List<Path> statics(List<Path> in, Located located, SourcePos pos) throws EngineException {
    if (pos != null) {
      near = pos;
    }
    pos = near;
    String text = located.text;
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
      int lt = text.lastIndexOf('<');
      if (lt > 0 && text.indexOf('>', lt) < 0) {
        // A tag that goes on past this static: feed the text before it on its own, so the state
        // inside the tag is described by the state before it plus only the tag's own input
        // (VnuParser), and paths that differ only before the tag still merge.
        p.feed(located.sub(0, lt));
        p.defer(located.sub(lt, text.length()));
      } else {
        p.feed(located);
      }
    }
    return in;
  }

  private void hole(Skeleton.Hole h, Path p, SourcePos pos) throws EngineException {
    boundary(p, pos);
    Map<Problem, List<ParserState>> problems = new LinkedHashMap<>();
    for (ParserState s : p.states()) {
      Problem problem = Rules.hole(h, s);
      if (problem != null) {
        problems.computeIfAbsent(problem, k -> new ArrayList<>()).add(s);
      }
    }
    for (Map.Entry<Problem, List<ParserState>> e : problems.entrySet()) {
      add(p, e.getKey(), pos, h.escaper(), null, h.attribute(), e.getValue(), null);
    }
    if (h.escaper() == Skeleton.Escaper.JSON || h.escaper() == Skeleton.Escaper.JAVASCRIPT) {
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
    List<Path> out = new ArrayList<>(in);
    for (Path p : in) {
      boundary(p, pos);
      Map<Problem, List<ParserState>> problems = new LinkedHashMap<>();
      boolean switches = false;
      for (ParserState s : p.states()) {
        Problem problem = ContentKinds.problem(s, source);
        if (problem != null) {
          problems.computeIfAbsent(problem, k -> new ArrayList<>()).add(s);
        } else {
          switches |= ContentKinds.switchesMode(s);
        }
      }
      for (Map.Entry<Problem, List<ParserState>> e : problems.entrySet()) {
        String detail =
            e.getKey() == Problem.RAW_CLOSES_ELEMENT ? ContentKinds.closer(e.getValue().get(0), source) : null;
        add(p, e.getKey(), pos, null, source, null, e.getValue(), detail);
      }
      if (switches) {
        Path written = p.fork();
        written.feed(ContentKinds.REPRESENTATIVE, pos);
        out.add(written);
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

  /**
   * {@link #add}, naming as the cause (#393) the element the first state reads the value in because
   * of earlier markup ({@link #causeIndex}), with where path {@code p} inserted it.
   */
  private void add(Path p, Problem problem, SourcePos pos, Skeleton.Escaper escaper, Skeleton.Source source,
      String attribute, List<ParserState> states, String detail) throws EngineException {
    Finding f = new Finding(problem, pos, null, null, escaper, source, attribute, states, parserCount, detail,
        recursive());
    ParserState s = states.get(0);
    int index = causeIndex(s);
    ParserState[] all = p.states();
    for (int i = 0; i < all.length && index >= 0; i++) {
      if (all[i] == s) {
        OpenElement e = s.stack().get(index);
        SourcePos openedAt = p.openedAt(i, index, e);
        if (openedAt != null) {
          f = f.causedBy(e, openedAt);
        }
        break;
      }
    }
    sink.add(f);
  }

  /**
   * The index on {@code s}'s stack of the element whose start tag put the parser where it reads the
   * next value (#393), or -1: in raw text, the raw-text element; in text in SVG or MathML, the
   * outermost foreign element around it; in text directly in a table part, that part.
   */
  static int causeIndex(ParserState s) {
    List<OpenElement> stack = s.stack();
    int top = stack.size() - 1;
    TokenizerState t = s.tokenizer();
    if (top < 0) {
      return -1;
    }
    if (t == TokenizerState.RCDATA || t == TokenizerState.RAWTEXT || t == TokenizerState.PLAINTEXT
        || t.name().startsWith("SCRIPT_DATA")) {
      return top;
    }
    if (t != TokenizerState.DATA) {
      return -1;
    }
    if (!OpenElement.HTML.equals(stack.get(top).namespace())) {
      int i = top;
      while (i > 0 && !OpenElement.HTML.equals(stack.get(i - 1).namespace())) {
        i--;
      }
      return i;
    }
    return Rules.TABLE_MODES.contains(s.mode()) ? top : -1;
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
    /** The calls by which the analysis first came back to this one ({@link #cycle}), or null. */
    List<Finding.Step> cycle;

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
      if (running.cycle == null) {
        running.cycle = cycle(running.depth, c.target(), pos);
      }
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
          Collections.<ParserState>emptyList(), parserCount, null, true)
          .withCycle(cycle(callStack.lastIndexOf(c.target()), c.target(), pos)));
      return Collections.emptyList();
    }
    int depth = callStack.size();
    running = new InProgress(depth);
    inProgress.put(key, running);
    callStack.add(c.target());
    callSites.add(pos);
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
          Finding f = new Finding(Problem.STRUCTURE_DEPENDS_ON_DATA, pos, null, null, null, null, null,
              Collections.<ParserState>emptyList(), parserCount, null, true);
          found.add(running.cycle == null ? f : f.withCycle(running.cycle));
          break;
        }
        running.approx = exits;
      }
    } finally {
      sink = outer;
      near = outerNear;
      callStack.remove(callStack.size() - 1);
      callSites.remove(callSites.size() - 1);
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

  /**
   * The cycle of a recursion that enters {@code target} again at {@code pos}, where {@code from}
   * is the index of {@code target}'s call on the call stack: each call after it, then this one.
   */
  private List<Finding.Step> cycle(int from, Skeleton.FragmentId target, SourcePos pos) {
    List<Finding.Step> out = new ArrayList<>();
    for (int i = from + 1; i < callStack.size(); i++) {
      out.add(new Finding.Step(callStack.get(i), callSites.get(i)));
    }
    out.add(new Finding.Step(target, pos));
    return out;
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
   * parsing in {@code context}, with scripting on and, if {@code scriptingOff}, off.
   */
  private Path start(OpenElement context, boolean scriptingOff) throws EngineException {
    List<Parser> ps = new ArrayList<>();
    for (boolean on : scriptingOff ? new boolean[] {true, false} : new boolean[] {true}) {
      ps.add(context == null ? Engines.CURRENT.document(on)
          : Engines.CURRENT.fragment(context.namespace(), context.localName(), on));
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
      return positions[part(offset)];
    }

    private int part(int offset) {
      int k = 0;
      while (k + 1 < starts.length && starts[k + 1] <= offset) {
        k++;
      }
      return k;
    }

    /**
     * Where a parse error at {@code offset} is reported. In a part that is text (it doesn't start
     * with {@code <}), at the {@code <} before the offset that starts markup, so a bogus comment in
     * text is reported at its {@code <!}, {@code <?} or {@code </} (#442); else at the part, which
     * is the tag, comment or doctype.
     */
    SourcePos errorAt(int offset) {
      int k = part(offset);
      int from = starts[k];
      if (positions[k] != null && from < text.length() && text.charAt(from) != '<') {
        int lt = markupStart(text, offset, from);
        if (lt >= 0) {
          return positions[k].advance(text.substring(from, lt), lt - from);
        }
      }
      return positions[k];
    }

    /**
     * Where the tag the parser read at {@code offset} starts: at the last {@code <} before it that
     * starts markup, in whichever part holds it; else at the part that holds {@code offset}.
     */
    SourcePos tagAt(int offset) {
      int lt = markupStart(text, offset, 0);
      int k = lt < 0 ? -1 : part(lt);
      if (k < 0 || positions[k] == null) {
        return at(offset);
      }
      return positions[k].advance(text.substring(starts[k], lt), lt - starts[k]);
    }

    /**
     * The markup a parse error at {@code offset} of {@code text} is in, for its message: from the
     * last {@code <} at or before it that starts markup up to the next {@code >} (at most 64
     * chars), or "". {@code before} is the input written just before {@code text}, which holds the
     * tag name when the tag goes on past a computed attribute.
     */
    static String markup(String before, String text, int offset) {
      String s = before + text;
      int at = before.length() + offset;
      int lt = markupStart(s, at, 0);
      int gt = lt < 0 ? -1 : s.indexOf('>', lt);
      if (lt < 0 || gt >= 0 && gt + 1 < at) {
        return "";
      }
      return s.substring(lt, Math.min(gt < 0 ? s.length() : gt + 1, lt + 64));
    }

    /** The last {@code <} in {@code s} at or before {@code offset}, not before {@code from}, that starts markup; or -1. */
    private static int markupStart(String s, int offset, int from) {
      for (int i = Math.min(offset, s.length() - 2); i >= from; i--) {
        char c = s.charAt(i + 1);
        if (s.charAt(i) == '<' && (Rules.isAsciiAlpha(c) || c == '!' || c == '/' || c == '?')) {
          return i;
        }
      }
      return -1;
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

  /** An element the parser inserted, and where its start tag is. */
  private static final class Opened {
    final String namespace;
    final String name;
    final SourcePos at;

    Opened(String namespace, String name, SourcePos at) {
      this.namespace = namespace;
      this.name = name;
      this.at = at;
    }
  }

  /** One path through the template: a parser per scripting mode, in the same input. */
  private static final class Path {
    /** The verifier the parsers report parse errors and excluded shapes to. */
    private final ContextVerifier owner;
    private final Parser[] parsers;
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
    /** The last chars written to the parsers, for the markup a parse error names ({@link Located#markup}). */
    private String tail = "";
    /**
     * Per parser, where each element on its stack of open elements was inserted, by stack index
     * (#393): the cause a message names. Not part of the {@link #key}: merged paths keep the first's.
     */
    private final List<List<Opened>> opened = new ArrayList<>();

    private Path(ContextVerifier owner, Parser[] parsers) {
      this.owner = owner;
      this.parsers = parsers;
      this.reported = new long[parsers.length];
      for (int i = 0; i < parsers.length; i++) {
        opened.add(new ArrayList<Opened>());
      }
    }

    /**
     * Where the start tag of the element at {@code index} on parser {@code parser}'s stack is, if
     * that element is {@code e}; else null.
     */
    SourcePos openedAt(int parser, int index, OpenElement e) {
      List<Opened> o = opened.get(parser);
      Opened x = index >= 0 && index < o.size() ? o.get(index) : null;
      return x != null && x.name.equals(e.localName()) && x.namespace.equals(e.namespace()) ? x.at : null;
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
      p.tagBoundary = tagBoundary;
      p.states = states;
      p.key = key;
      p.tail = tail;
      for (int i = 0; i < parsers.length; i++) {
        p.opened.get(i).addAll(opened.get(i));
      }
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
      final String before = tail;
      String last = text.text.length() >= 64 ? text.text.substring(text.text.length() - 64) : tail + text.text;
      tail = last.substring(Math.max(0, last.length() - 64));
      final Map<String, Reported> issues = new LinkedHashMap<>();
      for (int i = 0; i < parsers.length; i++) {
        final int parser = i;
        parsers[i].feed(text.text, new Parser.Listener() {
          @Override
          public void error(String message, int offset) {
            if (!Rules.allowedError(message)) {
              // With the markup it is in, for the fix-it, and at that markup's '<' (#442).
              note(Problem.PARSE_ERROR, VerifierMessages.parseErrorDetail(message, Located.markup(before, text.text,
                  offset)), offset, text.errorAt(offset));
            }
          }

          @Override
          public void pushed(Parser.Stack stack, int offset) {
            if (stack.insertedOnStack()) {
              List<Opened> o = opened.get(parser);
              int index = stack.size() - 1;
              while (o.size() > index) {
                o.remove(o.size() - 1);
              }
              while (o.size() < index) {
                o.add(null);
              }
              o.add(new Opened(stack.insertedNamespace(), stack.insertedName(), text.tagAt(offset)));
            }
            Rules.Excluded x = Rules.exclusion(stack);
            if (x != null) {
              note(x.problem, x.detail, offset);
            }
          }

          @Override
          public void cdata(OpenElement current, int offset) {
            // Any CDATA section (#678); at the '<' of "<![" (the engine reports the '['), if this chunk holds it.
            note(Problem.CDATA_SECTION, VerifierMessages.element(current), Math.max(0, offset - 2));
          }

          private void note(Problem problem, String detail, int offset) {
            note(problem, detail, offset, text.at(offset));
          }

          private void note(Problem problem, String detail, int offset, SourcePos pos) {
            long bit = 1L << problem.ordinal();
            if ((reported[parser] & bit) != 0) {
              return;
            }
            reported[parser] |= bit;
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
        sb.append(tagBoundary ? 'b' : '-');
        for (long r : reported) {
          sb.append(r == 0 ? "" : Long.toHexString(r)).append(',');
        }
        key = sb.toString();
      }
      return key;
    }
  }
}
