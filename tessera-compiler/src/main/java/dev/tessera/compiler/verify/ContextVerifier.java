package dev.tessera.compiler.verify;

import dev.tessera.compiler.DiagnosticSink;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.verify.engine.EngineException;
import dev.tessera.compiler.verify.engine.InsertionMode;
import dev.tessera.compiler.verify.engine.OpenElement;
import dev.tessera.compiler.verify.engine.Parser;
import dev.tessera.compiler.verify.engine.ParserState;
import dev.tessera.compiler.verify.engine.TokenizerState;
import dev.tessera.compiler.verify.engine.Vnu;
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
 * ({@link Vnu}), with scripting on and off, and accepts a hole only if its escaper's output is
 * inert in the parser state there in both ({@link Rules}). A template it doesn't accept is a build
 * error at the hole's file, line and column.
 *
 * <p>It follows one path through the template, with the parsers as the transfer function. A path
 * holds the two parsers (one, when the template has no {@code <noscript>}). Statics are fed as they
 * are. A hole is checked and leaves the state as it was (that is what "inert" means); an attribute
 * hole writes nothing into the value. Control flow is state-neutral (#682): the body of a {@code
 * t:if}, {@code t:case}, {@code t:each} and every fragment insertion must leave the parsers in the
 * state it found them in ({@link #balanced}), and a partial must end in the state it starts in, so
 * every way through the template is in the same state at each point and one path is exact. A
 * static fragment call is analysed at each call site, memoised per (fragment, entry state, bound
 * fragment arguments); a recursive call in the state its call in progress started in is neutral by
 * induction.
 *
 * <p>It accepts only error-free parses (#406, security-architecture.md §11.4): every parse error the
 * engine finds, with scripting on and off, is {@link Problem#PARSE_ERROR} at the static that holds
 * it, except the allow-list ({@link Rules#allowedError}); a partial counts them, and its balance,
 * only in its fragment contexts. It checks the coarse exclusions ({@link Rules#exclusion}) at every
 * element the parser inserts, and rejects every CDATA section the parser starts ({@link
 * Problem#CDATA_SECTION}).
 *
 * <p>It fails closed: an engine failure, a recursion into ever new states and a node it can't
 * interpret are build errors.
 */
public final class ContextVerifier {
  /** A fragment may be on the call stack at most this many times (each in a new state). */
  static final int MAX_DEPTH = 4;

  /** A fragment that calls itself may be entered in at most this many states while it recurses. */
  static final int MAX_RECURSIVE_ENTRIES = 24;

  /** Fragment parsing contexts a partial's root element implies; anything else: body. */
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
  /** The calls in progress, by memo key: their depth on the call stack. */
  private final Map<String, Integer> inProgress = new HashMap<>();
  private final List<Skeleton.FragmentId> callStack = new ArrayList<>();
  /** The position of each call on {@link #callStack}, for a recursion's cycle in messages (#393). */
  private final List<SourcePos> callSites = new ArrayList<>();
  /** Per fragment that is on the call stack: how many states it was entered in by recursion. */
  private final Map<Skeleton.FragmentId, Integer> recursiveEntries = new HashMap<>();
  private final Map<Skeleton.Seq, Integer> seqIds = new IdentityHashMap<>();
  /** The lowest call-stack depth of a call in progress the current analysis assumed neutral. */
  private int assumedDepth = Integer.MAX_VALUE;
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

  /**
   * Fragment parsing contexts for fragments declared outside HTML content, by declared context.
   * Nothing inside {@code <svg>} or {@code <math>} is an insertion point (#678): the svg and math
   * entries exist only for the {@code <svg t:replace>} case, where the fragment root is itself an
   * {@code <svg>}, so that such a fragment is checked in the content it was compiled in.
   */
  private static final Map<String, OpenElement> DECLARED_CONTEXTS = new HashMap<>();

  static {
    DECLARED_CONTEXTS.put("svg", new OpenElement(OpenElement.SVG, "svg", false));
    DECLARED_CONTEXTS.put("math", new OpenElement(OpenElement.MATHML, "math", false));
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
    return check(t, contexts, "fragment " + id);
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
    return check(template, contexts, "this partial");
  }

  /**
   * What the verifier rejects in {@code template} parsed in each of {@code entries} (null: as a
   * document). Parsed as a fragment, it must end in the state it starts in: {@code what} names it.
   */
  private List<Finding> check(Skeleton.TemplateSkeleton template, List<OpenElement> entries, String what) {
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
        Path entry = start(context, scriptingOff);
        Path exit = seq(template.body(), entry.fork(), Env.EMPTY);
        if (context != null) {
          balanced(entry, exit, start, what, false);
        }
      } catch (EngineException e) {
        found.add(new Finding(Problem.ENGINE_FAILURE, near, null, null, null, null, null,
            Collections.<ParserState>emptyList(), parserCount, e.getMessage(), false));
      }
      for (Finding f : found) {
        if ((f.problem == Problem.PARSE_ERROR || f.problem == Problem.UNBALANCED) && context == null
            && entries.size() > 1) {
          // A partial counts parse errors and balance only in the fragment contexts its root
          // implies (#406, #682): as a document, a <tr> partial is a stray start tag, and the
          // first element of any partial opens <html> and <body>. So a t:if unbalanced only as a
          // document goes on from its entry alone (optional): its written and skipped states converge at
          // the first flow element, which opens <body> in both, and before that they differ only in head
          // or body placement, where <noscript>, <style>, <title> and the other head elements tokenize
          // the same, so every hole meets the same escaper rule (#707, ContextVerifierRulesTest
          // aLeadingIfDoesNotChangeHowHeadElementsInAPartialAreChecked).
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
      } else if (n instanceof Skeleton.Call && depth < MAX_DEPTH) {
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

  /** The exit path of {@code seq} from {@code p}. May consume {@code p}. */
  private Path seq(Skeleton.Seq seq, Path p, Env env) throws EngineException {
    Path cur = p;
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
        statics(cur, statics.build(), staticPos);
        statics = new Located.Builder();
        staticPos = null;
      }
      cur = node(n, cur, env);
    }
    if (!statics.isEmpty()) {
      statics(cur, statics.build(), staticPos);
    }
    return cur;
  }

  private SourcePos at(Skeleton.Node n) {
    if (n.pos() != null) {
      near = n.pos();
    }
    return near;
  }

  private Path node(Skeleton.Node n, Path p, Env env) throws EngineException {
    SourcePos pos = at(n);
    if (n instanceof Skeleton.Hole) {
      hole((Skeleton.Hole) n, p, pos);
      return p;
    }
    if (n instanceof Skeleton.RawInsert) {
      return raw(((Skeleton.RawInsert) n).source(), p, pos);
    }
    if (n instanceof Skeleton.ParamInsert) {
      Binding b = env.bindings.get(((Skeleton.ParamInsert) n).param());
      if (b == null) {
        return raw(Skeleton.Source.FRAGMENT_VALUE, p, pos);
      }
      // The bound fragment, or nothing for a null value.
      return optional(b.seq, p, b.env, pos, "the Fragment argument inserted here", true);
    }
    if (n instanceof Skeleton.Call) {
      return call((Skeleton.Call) n, p, env, pos);
    }
    if (n instanceof Skeleton.Seq) {
      return seq((Skeleton.Seq) n, p, env);
    }
    if (n instanceof Skeleton.Opt
        && (((Skeleton.Opt) n).kind() == Skeleton.OptKind.ATTRIBUTE || ((Skeleton.Opt) n).kind() == Skeleton.OptKind.CSP_NONCE)) {
      return attribute((Skeleton.Opt) n, p, env, pos);
    }
    if (n instanceof Skeleton.Opt) {
      return optional(((Skeleton.Opt) n).body(), p, env, pos, what(((Skeleton.Opt) n).kind()), false);
    }
    if (n instanceof Skeleton.Alt) {
      // One of the fragments, always: each is checked like a call. The path goes on after a
      // balanced one (one that switched template mode, if any: the stricter), else after the first.
      Path out = null;
      boolean outBalanced = false;
      for (Skeleton.Seq b : ((Skeleton.Alt) n).branches()) {
        Path exit = seq(b, p.fork(), env);
        boolean balanced = balanced(p, exit, pos, "the fragment inserted here", true);
        if (out == null || balanced && (!outBalanced || !flowKey(exit).equals(flowKey(p)))) {
          out = exit;
          outBalanced = balanced;
        }
      }
      return out == null ? p : out;
    }
    if (n instanceof Skeleton.Rep) {
      return rep((Skeleton.Rep) n, p, env, pos);
    }
    add(Problem.UNKNOWN_NODE, pos, null, null, null, Collections.<ParserState>emptyList(), n.getClass().getSimpleName());
    return p;
  }

  private static String what(Skeleton.OptKind kind) {
    switch (kind) {
      case CONDITION:
        return "this t:if or t:unless";
      case CASE:
        return "this t:case";
      case ERRORS:
        return "this t:errors";
      default:
        return "the hidden input written here";
    }
  }

  /**
   * State-neutral control flow (#682): {@code body} run from {@code entry} (on a fork) must leave
   * the parsers as it found them ({@link #balanced}), so a body written or not, once or many times,
   * leaves one state and one path stands for all. Returns the body's exit, which parses what follows
   * as the entry does or (template mode) at least as strictly; if it is unbalanced, the entry.
   */
  private Path optional(Skeleton.Seq body, Path entry, Env env, SourcePos pos, String what, boolean insertion)
      throws EngineException {
    Path exit = seq(body, entry.fork(), env);
    return balanced(entry, exit, pos, what, insertion) ? exit : entry;
  }

  /**
   * Whether every parser of {@code exit} parses every continuation as it does in {@code entry}
   * (else it reports {@link Problem#UNBALANCED} at {@code pos}): the same state but for a stale original
   * insertion mode ({@link ContentKinds#flowSignature}); in template mode, also the "in body"
   * state a flow start tag switches to ({@link ContentKinds#switchesMode}, which is stricter); and
   * for a fragment {@code insertion} at the start of a document, the end of the document (a layout
   * on {@code <html>}: after it, anything but whitespace and comments is a parse error).
   */
  private boolean balanced(Path entry, Path exit, SourcePos pos, String what, boolean insertion) throws EngineException {
    ParserState[] before = entry.states();
    ParserState[] after = exit.states();
    Path switched = null;
    for (int i = 0; i < after.length; i++) {
      if (ContentKinds.flowSignature(before[i]).equals(ContentKinds.flowSignature(after[i]))
          || insertion && documentStart(before[i]) && after[i].mode() == InsertionMode.AFTER_AFTER_BODY) {
        continue;
      }
      if (ContentKinds.switchesMode(before[i])) {
        if (switched == null) {
          switched = entry.fork();
          switched.feed(ContentKinds.REPRESENTATIVE, pos);
        }
        if (ContentKinds.flowSignature(switched.states()[i]).equals(ContentKinds.flowSignature(after[i]))) {
          continue;
        }
      }
      add(Problem.UNBALANCED, pos, null, null, null, Collections.singletonList(after[i]),
          VerifierMessages.unbalanced(what, before[i], after[i]));
      return false;
    }
    return true;
  }

  /** A document parser that has seen at most a doctype. */
  private static boolean documentStart(ParserState s) {
    return s.fragmentContext() == null && s.stack().isEmpty()
        && (s.mode() == InsertionMode.INITIAL || s.mode() == InsertionMode.BEFORE_HTML);
  }

  private static String flowKey(Path p) throws EngineException {
    StringBuilder sb = new StringBuilder();
    for (ParserState s : p.states()) {
      sb.append(ContentKinds.flowSignature(s)).append('\u0000');
    }
    return sb.toString();
  }

  /**
   * An attribute written only if its value isn't null: {@code [ name="<hole>"]}. Inside a start
   * tag, writing a whole attribute or not leaves the tokenizer in states that parse every
   * continuation alike (before an attribute name, after a quoted value; after the tag name or a
   * static attribute's name, when the next character is whitespace, '/' or '>', which {@link
   * Path#tagBoundary} checks), or that parse it more strictly when written: after an attribute's
   * name (a boolean attribute such as {@code disabled}), a '=' or a name right after the written
   * value is a parse error, where without it they would give that attribute a value or start the
   * next one. So the two differ only in the element's attribute list. That list matters to the
   * tree builder only through duplicate names and where the attribute makes a parse error, where
   * the written attribute is the stricter case (a later duplicate is dropped), and through MathML's
   * {@code encoding}, which is never computed (#678). An {@code <input>} in a table is a parse
   * error with any {@code type}. So the written path alone stands for both, and a tag with many
   * computed attributes doesn't multiply the states. Otherwise both must be one state ({@link
   * #balanced}).
   */
  private Path attribute(Skeleton.Opt o, Path p, Env env, SourcePos pos) throws EngineException {
    String name = null;
    for (Skeleton.Node b : o.body().nodes()) {
      if (b instanceof Skeleton.Hole && ((Skeleton.Hole) b).attribute() != null) {
        name = Rules.asciiLower(((Skeleton.Hole) b).attribute());
        break;
      }
    }
    ParserState[] entry = p.states();
    boolean pendingBoundary = p.tagBoundary;
    Path written = seq(o.body(), p.fork(), env);
    boolean merge = name != null;
    boolean inName = false;
    for (ParserState s : entry) {
      TokenizerState t = s.tokenizer();
      merge &= t == TokenizerState.BEFORE_ATTRIBUTE_NAME || t == TokenizerState.AFTER_ATTRIBUTE_VALUE_QUOTED
          || t == TokenizerState.TAG_NAME || t == TokenizerState.ATTRIBUTE_NAME
          || t == TokenizerState.AFTER_ATTRIBUTE_NAME;
      inName |= t == TokenizerState.TAG_NAME || t == TokenizerState.ATTRIBUTE_NAME;
    }
    for (ParserState s : written.states()) {
      merge &= s.tokenizer() == TokenizerState.AFTER_ATTRIBUTE_VALUE_QUOTED;
    }
    if (merge) {
      written.tagBoundary |= inName || pendingBoundary;
      written.key = null;
    } else {
      balanced(p, written, pos, "this computed attribute", false);
    }
    return written;
  }

  /**
   * Zero or more {@code body}, separated. One pass of the body and the separator must each be
   * state-neutral ({@link #optional}); then every number of passes leaves the entry state. In
   * template mode the first pass may switch the mode, and the next passes are checked from there.
   * A separator that switches it is rejected (#707): from two passes on, what follows would parse
   * in the separator's state, not the first pass's.
   */
  private Path rep(Skeleton.Rep r, Path p, Env env, SourcePos pos) throws EngineException {
    String what = r.kind() == Skeleton.RepKind.EACH ? "this t:each" : "the messages of this t:errors";
    Path once = optional(r.body(), p, env, pos, what, false);
    Path next = optional(r.separator(), once, env, pos, what, false);
    ParserState[] a = once.states();
    ParserState[] b = next.states();
    for (int i = 0; i < a.length; i++) {
      if (!ContentKinds.flowSignature(a[i]).equals(ContentKinds.flowSignature(b[i]))) {
        add(Problem.UNBALANCED, pos, null, null, null, Collections.singletonList(b[i]),
            VerifierMessages.unbalanced("the separator of " + what, a[i], b[i]));
        return once;
      }
    }
    if (!flowKey(once).equals(flowKey(p))) {
      return optional(r.body(), next, env, pos, what, false);
    }
    return once;
  }

  private void statics(Path p, Located located, SourcePos pos) throws EngineException {
    if (pos != null) {
      near = pos;
    }
    pos = near;
    String text = located.text;
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
      // (VnuParser), and fragment calls in the same state share their analysis.
      p.feed(located.sub(0, lt));
      p.defer(located.sub(lt, text.length()));
    } else {
      p.feed(located);
    }
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
   * parser state, and then the value leaves the parser where it was. In template mode a flow start
   * tag switches the mode: the path goes on from there, which parses what follows at least as
   * strictly as the state before an empty value ({@link #balanced}).
   */
  private Path raw(Skeleton.Source source, Path p, SourcePos pos) throws EngineException {
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
      p.feed(ContentKinds.REPRESENTATIVE, pos);
    }
    return p;
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
    final Path exit;
    final List<Finding> findings;

    Memo(Path exit, List<Finding> findings) {
      this.exit = exit;
      this.findings = findings;
    }
  }

  /**
   * A fragment call, analysed in the caller's state, memoised per (fragment, bound fragment
   * arguments, entry state). The fragment must leave the state as it found it ({@link #balanced}).
   * A recursive call in the state its call in progress was entered in is taken to do so: that call
   * checks it, so by induction on the depth every depth does. A recursion into ever new states
   * (deeper nesting each time) is {@link Problem#STRUCTURE_DEPENDS_ON_DATA}.
   */
  private Path call(Skeleton.Call c, Path p, Env env, SourcePos pos) throws EngineException {
    Skeleton.Seq target = fragments.get(c.target());
    if (target == null) {
      add(Problem.UNKNOWN_NODE, pos, null, null, null, Collections.<ParserState>emptyList(), "fragment " + c.target());
      return p;
    }
    String what = "fragment " + c.target() + ", inserted here,";
    Env callee = env.bind(c.fragmentArgs());
    String key = c.target() + "\n" + callee.key(seqIds) + "\n" + p.key();
    Memo m = memo.get(key);
    if (m != null) {
      emit(m.findings, pos, c.target());
      Path exit = m.exit.fork();
      balanced(p, exit, pos, what, true);
      return exit;
    }
    Integer running = inProgress.get(key);
    if (running != null) {
      assumedDepth = Math.min(assumedDepth, running);
      return p;
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
    if (same >= MAX_DEPTH || entries > MAX_RECURSIVE_ENTRIES) {
      sink.add(new Finding(Problem.STRUCTURE_DEPENDS_ON_DATA, pos, null, null, null, null, null,
          Collections.<ParserState>emptyList(), parserCount, null, true)
          .withCycle(cycle(callStack.lastIndexOf(c.target()), c.target(), pos)));
      return p;
    }
    int depth = callStack.size();
    inProgress.put(key, depth);
    callStack.add(c.target());
    callSites.add(pos);
    int outerAssumed = assumedDepth;
    assumedDepth = Integer.MAX_VALUE;
    List<Finding> outer = sink;
    List<Finding> found = new ArrayList<>();
    sink = found;
    SourcePos outerNear = near;
    Path exit;
    int read;
    try {
      exit = seq(target, p.fork(), callee);
      // Input the fragment deferred is the fragment's: its errors belong to this call.
      exit.flush();
    } finally {
      read = assumedDepth;
      sink = outer;
      near = outerNear;
      callStack.remove(callStack.size() - 1);
      callSites.remove(callSites.size() - 1);
      inProgress.remove(key);
      if (!callStack.contains(c.target())) {
        recursiveEntries.remove(c.target());
      }
    }
    // A result that assumed an outer call in progress is neutral isn't final: don't memoise it.
    assumedDepth = Math.min(outerAssumed, read < depth ? read : Integer.MAX_VALUE);
    if (read >= depth) {
      memo.put(key, new Memo(exit.fork(), found));
    }
    emit(found, pos, c.target());
    balanced(p, exit, pos, what, true);
    return exit;
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
      ps.add(context == null ? Vnu.document(on)
          : Vnu.fragment(context.namespace(), context.localName(), on));
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
     * (#393): the cause a message names. Not part of the {@link #key}: a memoised call's exit keeps
     * the one it was first analysed with.
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
