package dev.chainguard.thwart.compiler.verify;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Decides whether a rendered output is in the language of a {@link Skeleton} (#366): statics
 * byte-equal; each hole in its escaper's output language (security-architecture.md §11.2); {@code Opt}, {@code Alt} and
 * {@code Rep} as the regex operators; a {@code Call} as the target's skeleton with its fragment
 * arguments bound; a bound {@code ParamInsert} as its argument or nothing; a {@code RawInsert} as its
 * content if it has one (an HTML message), else as anything, like an unbound {@code ParamInsert}.
 *
 * <p>Works on sets of end offsets, memoized per (sequence, index, offset, bindings), so ambiguity
 * between a hole and the static text after it costs no backtracking. A recursive call that
 * re-enters itself at the same offset with the same bindings matches nothing there.
 */
public final class SkeletonMatcher {

  /** The outcome of a match: success, or the furthest offset reached and what was expected there. */
  public static final class Result {
    private final boolean matches;
    private final int offset;
    private final String expected;

    Result(boolean matches, int offset, String expected) {
      this.matches = matches;
      this.offset = offset;
      this.expected = expected;
    }

    public boolean matches() {
      return matches;
    }

    /** The first offset no path through the skeleton gets past. */
    public int offset() {
      return offset;
    }

    /** The skeleton path and node expected at {@link #offset}. */
    public String expected() {
      return expected;
    }
  }

  /** Each escaper's output language, as the set of offsets a hole starting at {@code from} can end at. */
  static BitSet holeEnds(Skeleton.Escaper escaper, String s, int from) {
    BitSet ends = new BitSet();
    int i = from;
    ends.set(i);
    while (i < s.length()) {
      int next = escaper == Skeleton.Escaper.JSON || escaper == Skeleton.Escaper.JAVASCRIPT ? jsonToken(s, i) : htmlToken(s, i);
      if (next < 0) {
        break;
      }
      i = next;
      ends.set(i);
    }
    return ends;
  }

  private static final String[] ENTITIES = {"&amp;", "&lt;", "&gt;", "&quot;", "&#39;"};

  /**
   * One token of HTML-escaped output ({@code escapeHtml}): a char that is not {@code & < > " '}, NUL
   * or a lone surrogate, or one of the five references. Returns the end, or -1.
   */
  private static int htmlToken(String s, int i) {
    char c = s.charAt(i);
    if (c == '&') {
      for (String e : ENTITIES) {
        if (s.startsWith(e, i)) {
          return i + e.length();
        }
      }
      return -1;
    }
    if (c == '<' || c == '>' || c == '"' || c == '\'' || c == '\0') {
      return -1;
    }
    return surrogate(s, i);
  }

  /** One char of script JSON ({@code writeJson}): not {@code < > & ' ` $}, U+2028/9, a control or a lone surrogate ({@code /} is only written as {@code \/}, but a lone one is inert too). */
  private static int jsonToken(String s, int i) {
    char c = s.charAt(i);
    if ("<>&'`$  ".indexOf(c) >= 0 || c < 0x20 || c == 0x7f) {
      return -1;
    }
    return surrogate(s, i);
  }

  private static int surrogate(String s, int i) {
    char c = s.charAt(i);
    if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
      return i + 2;
    }
    return Character.isSurrogate(c) ? -1 : i + 1;
  }

  /** Fragment parameter bindings: each bound to a skeleton read in the caller's bindings. */
  private static final class Env {
    static final Env EMPTY = new Env(new HashMap<String, Skeleton.Seq>(), null);

    final Map<String, Skeleton.Seq> args;
    final Env caller;

    Env(Map<String, Skeleton.Seq> args, Env caller) {
      this.args = args;
      this.caller = caller;
    }
  }

  private static final class Key {
    final Object node;
    final int index;
    final int offset;
    final Env env;

    Key(Object node, int index, int offset, Env env) {
      this.node = node;
      this.index = index;
      this.offset = offset;
      this.env = env;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Key)) {
        return false;
      }
      Key k = (Key) o;
      return node == k.node && index == k.index && offset == k.offset && env == k.env;
    }

    @Override
    public int hashCode() {
      return Objects.hash(System.identityHashCode(node), index, offset, System.identityHashCode(env));
    }
  }

  private final String s;
  private final Function<Skeleton.FragmentId, Skeleton.Seq> fragments;
  private final Map<Key, BitSet> memo = new HashMap<>();
  private final Set<Key> active = new HashSet<>();
  /** Call bindings, one Env per (call node, caller Env), so memo keys repeat. */
  private final Map<Key, Env> envs = new HashMap<>();
  /** The skeleton path to the node being matched: labels (nodes or strings), innermost first. */
  private static final class Path {
    final Object label;
    final Path parent;

    Path(Object label, Path parent) {
      this.label = label;
      this.parent = parent;
    }
  }

  private Path path = new Path("body", null);
  private int furthest = -1;
  private Path furthestPath;
  private String furthestWhat = "";
  private Skeleton.Node furthestNode;
  private int furthestChars;

  private SkeletonMatcher(String s, Function<Skeleton.FragmentId, Skeleton.Seq> fragments) {
    this.s = s;
    this.fragments = fragments;
  }

  /**
   * Matches {@code output} against {@code body}; {@code fragments} gives the skeleton of each
   * called fragment (null if unknown, which fails the match with an error).
   */
  public static Result match(Skeleton.Seq body, String output, Function<Skeleton.FragmentId, Skeleton.Seq> fragments) {
    SkeletonMatcher m = new SkeletonMatcher(output, fragments);
    BitSet ends = m.seq(body, 0, Env.EMPTY);
    if (ends.get(output.length())) {
      return new Result(true, output.length(), "");
    }
    int at = Math.max(m.furthest, 0);
    String expected = m.furthestNode == null || m.furthestWhat.equals("after")
        ? "end of output, or what follows " + m.furthestNode + " (the output goes on after every path through the skeleton)"
        : m.describe();
    return new Result(false, at, expected);
  }

  /** Where one hole's output is in a render, from {@link #holes}: {@code [start, end)}. */
  public static final class Span {
    private final Skeleton.Hole hole;
    private final int start;
    private final int end;

    Span(Skeleton.Hole hole, int start, int end) {
      this.hole = hole;
      this.start = start;
      this.end = end;
    }

    public Skeleton.Hole hole() {
      return hole;
    }

    public int start() {
      return start;
    }

    public int end() {
      return end;
    }

    @Override
    public String toString() {
      return hole + "[" + start + "," + end + ")";
    }
  }

  /**
   * One derivation of {@code output} through {@code body} (#324): the span of each hole written, in
   * output order, or null if {@code output} isn't in the skeleton's language. Where the language
   * is ambiguous (a hole followed by static text in its escaper's alphabet) any derivation may be
   * returned; zero-width choices (an omitted {@code Opt}, no {@code Rep} iteration, a null
   * fragment) are preferred. Raw inserts aren't listed.
   */
  public static List<Span> holes(Skeleton.Seq body, String output, Function<Skeleton.FragmentId, Skeleton.Seq> fragments) {
    SkeletonMatcher m = new SkeletonMatcher(output, fragments);
    if (!m.seq(body, 0, Env.EMPTY).get(output.length())) {
      return null;
    }
    List<Span> out = new ArrayList<>();
    if (!m.derive(body, 0, 0, output.length(), Env.EMPTY, out)) {
      throw new AssertionError("the output matches but no derivation was found");
    }
    return out;
  }

  /** Memo of {@link #suffix}. */
  private final Map<Key, BitSet> suffixes = new HashMap<>();
  /** (node, offset, end, env) being derived, to cut zero-progress cycles through calls. */
  private final Set<Key> deriving = new HashSet<>();

  /** The offsets matching nodes {@code i..} of {@code seq} from {@code offset} can end at. */
  private BitSet suffix(Skeleton.Seq seq, int i, int offset, Env env) {
    List<Skeleton.Node> nodes = seq.nodes();
    if (i >= nodes.size()) {
      BitSet b = new BitSet();
      b.set(offset);
      return b;
    }
    Key key = new Key(seq, i, offset, env);
    BitSet cached = suffixes.get(key);
    if (cached != null) {
      return cached;
    }
    BitSet out = new BitSet();
    BitSet first = node(nodes.get(i), offset, env);
    for (int j = first.nextSetBit(0); j >= 0; j = first.nextSetBit(j + 1)) {
      out.or(suffix(seq, i + 1, j, env));
    }
    suffixes.put(key, out);
    return out;
  }

  /** Derives nodes {@code i..} of {@code seq} from {@code offset} to {@code end}, appending hole spans. */
  private boolean derive(Skeleton.Seq seq, int i, int offset, int end, Env env, List<Span> out) {
    List<Skeleton.Node> nodes = seq.nodes();
    for (; i < nodes.size(); i++) {
      Skeleton.Node n = nodes.get(i);
      BitSet ends = node(n, offset, env);
      int chosen = -1;
      for (int j = ends.nextSetBit(offset); j >= 0 && j <= end; j = ends.nextSetBit(j + 1)) {
        if (!suffix(seq, i + 1, j, env).get(end)) {
          continue;
        }
        int mark = out.size();
        if (deriveNode(n, offset, j, env, out)) {
          chosen = j;
          break;
        }
        out.subList(mark, out.size()).clear();
      }
      if (chosen < 0) {
        return false;
      }
      offset = chosen;
    }
    return offset == end;
  }

  /** Derives node {@code n} from {@code offset} to {@code end}. */
  private boolean deriveNode(Skeleton.Node n, int offset, int end, Env env, List<Span> out) {
    Key key = new Key(n, offset, end, env);
    if (!deriving.add(key)) {
      return false;
    }
    try {
      if (n instanceof Skeleton.Static || n instanceof Skeleton.RawInsert) {
        return true;
      }
      if (n instanceof Skeleton.Hole h) {
        out.add(new Span(h, offset, end));
        return true;
      }
      if (n instanceof Skeleton.Call c) {
        return derive(fragments.apply(c.target()), 0, offset, end, callee(c, env), out);
      }
      if (n instanceof Skeleton.ParamInsert p) {
        Skeleton.Seq bound = env.args.get(p.param());
        if (bound == null || end == offset) {
          return true;
        }
        return derive(bound, 0, offset, end, env.caller == null ? Env.EMPTY : env.caller, out);
      }
      if (n instanceof Skeleton.Seq q) {
        return derive(q, 0, offset, end, env, out);
      }
      if (n instanceof Skeleton.Opt o) {
        return end == offset || derive(o.body(), 0, offset, end, env, out);
      }
      if (n instanceof Skeleton.Alt a) {
        for (Skeleton.Seq b : a.branches()) {
          if (seq(b, offset, env).get(end)) {
            int mark = out.size();
            if (derive(b, 0, offset, end, env, out)) {
              return true;
            }
            out.subList(mark, out.size()).clear();
          }
        }
        return false;
      }
      if (n instanceof Skeleton.Rep r) {
        return deriveRep(r, offset, end, env, out);
      }
      throw new IllegalArgumentException("unknown node " + n.getClass().getSimpleName());
    } finally {
      deriving.remove(key);
    }
  }

  private boolean deriveRep(Skeleton.Rep r, int offset, int end, Env env, List<Span> out) {
    if (end == offset) {
      return true;
    }
    // Breadth-first over iteration ends: how each was reached (previous end, separator end).
    Map<Integer, int[]> how = new HashMap<>();
    java.util.ArrayDeque<Integer> todo = new java.util.ArrayDeque<>();
    BitSet first = seq(r.body(), offset, env);
    for (int e = first.nextSetBit(0); e >= 0; e = first.nextSetBit(e + 1)) {
      if (how.putIfAbsent(e, new int[] {-1, offset}) == null) {
        todo.add(e);
      }
    }
    while (!todo.isEmpty() && !how.containsKey(end)) {
      int e = todo.poll();
      BitSet afterSep = seq(r.separator(), e, env);
      for (int s = afterSep.nextSetBit(0); s >= 0; s = afterSep.nextSetBit(s + 1)) {
        BitSet next = seq(r.body(), s, env);
        for (int e2 = next.nextSetBit(0); e2 >= 0; e2 = next.nextSetBit(e2 + 1)) {
          if (how.putIfAbsent(e2, new int[] {e, s}) == null) {
            todo.add(e2);
          }
        }
      }
    }
    if (!how.containsKey(end)) {
      return false;
    }
    List<int[]> steps = new ArrayList<>(); // {previous end, separator end, body end}
    for (int e = end; e >= 0 && how.containsKey(e); ) {
      int[] h = how.get(e);
      steps.add(new int[] {h[0], h[1], e});
      if (h[0] < 0) {
        break;
      }
      e = h[0];
    }
    java.util.Collections.reverse(steps);
    for (int[] st : steps) {
      if (st[0] >= 0 && !derive(r.separator(), 0, st[0], st[1], env, out)) {
        return false;
      }
      if (!derive(r.body(), 0, st[1], st[2], env, out)) {
        return false;
      }
    }
    return true;
  }

  /** The bindings a call's target is matched in: one Env per (call node, caller Env). */
  private Env callee(Skeleton.Call c, Env env) {
    Key k = new Key(c, 0, 0, env);
    Env callee = envs.get(k);
    if (callee == null) {
      callee = new Env(c.fragmentArgs(), env);
      envs.put(k, callee);
    }
    return callee;
  }

  /** Records how far a match got: the description is only built on failure. */
  private void reached(int offset, String what, Skeleton.Node node, int chars) {
    if (offset > furthest) {
      furthest = offset;
      furthestPath = path;
      furthestWhat = what;
      furthestNode = node;
      furthestChars = chars;
    }
  }

  private String describe() {
    List<String> labels = new ArrayList<>();
    for (Path p = furthestPath; p != null; p = p.parent) {
      labels.add(label(p.label));
    }
    java.util.Collections.reverse(labels);
    String node = furthestWhat.equals("static")
        ? "static " + furthestNode + (furthestNode.pos() == null ? "" : " @" + furthestNode.pos()) + ", char "
            + furthestChars
        : "hole " + furthestNode + " (its escaper's output alphabet ends here)";
    return String.join(" > ", labels) + " > " + node;
  }

  private static String label(Object o) {
    if (o instanceof Skeleton.Opt) {
      Skeleton.Opt n = (Skeleton.Opt) o;
      return "opt:" + n.kind() + (n.pos() == null ? "" : "@" + n.pos());
    }
    if (o instanceof Skeleton.Rep) {
      Skeleton.Rep n = (Skeleton.Rep) o;
      return "rep:" + n.kind() + (n.pos() == null ? "" : "@" + n.pos());
    }
    if (o instanceof Skeleton.Call) {
      return "call{" + ((Skeleton.Call) o).target() + "}";
    }
    return String.valueOf(o);
  }

  /** The offsets matching all of {@code seq} from {@code offset} can end at. Callers must not modify the result. */
  private BitSet seq(Skeleton.Seq seq, int offset, Env env) {
    Key key = new Key(seq, 0, offset, env);
    BitSet cached = memo.get(key);
    if (cached != null) {
      return cached;
    }
    if (!active.add(key)) {
      return new BitSet(); // left recursion: no match through this path
    }
    BitSet cur = new BitSet();
    cur.set(offset);
    for (Skeleton.Node n : seq.nodes()) {
      BitSet next = new BitSet();
      for (int j = cur.nextSetBit(0); j >= 0; j = cur.nextSetBit(j + 1)) {
        next.or(node(n, j, env));
      }
      cur = next;
      if (cur.isEmpty()) {
        break;
      }
    }
    active.remove(key);
    memo.put(key, cur);
    return cur;
  }

  private BitSet node(Skeleton.Node n, int offset, Env env) {
    if (n instanceof Skeleton.Static st) {
      BitSet b = new BitSet();
      String t = st.text();
      if (s.startsWith(t, offset)) {
        b.set(offset + t.length());
        reached(offset + t.length(), "after", st, 0);
      } else {
        int k = 0;
        while (offset + k < s.length() && k < t.length() && s.charAt(offset + k) == t.charAt(k)) {
          k++;
        }
        reached(offset + k, "static", st, k);
      }
      return b;
    }

    if (n instanceof Skeleton.Hole h) {
      BitSet b = holeEnds(h.escaper(), s, offset);
      reached(b.length() - 1, "hole", h, 0);
      return b;
    }

    if (n instanceof Skeleton.RawInsert r) {
      return anything(offset);
    }

    if (n instanceof Skeleton.Call c) {
      Skeleton.Seq target = fragments.apply(c.target());
      if (target == null) {
        throw new AssertionError("no skeleton for fragment " + c.target());
      }
      Env callee = callee(c, env);
      path = new Path(c, path);
      try {
        return seq(target, offset, callee);
      } finally {
        path = path.parent;
      }
    }

    if (n instanceof Skeleton.ParamInsert p) {
      Skeleton.Seq bound = env.args.get(p.param());
      if (bound == null) {
        return anything(offset);
      }
      BitSet b = new BitSet();
      b.set(offset); // a null Fragment writes nothing
      path = new Path(p, path);
      try {
        b.or(seq(bound, offset, env.caller == null ? Env.EMPTY : env.caller));
      } finally {
        path = path.parent;
      }
      return b;
    }

    if (n instanceof Skeleton.Seq q) {
      return seq(q, offset, env);
    }

    if (n instanceof Skeleton.Opt o) {
      BitSet b = new BitSet();
      b.set(offset);
      path = new Path(o, path);
      try {
        b.or(seq(o.body(), offset, env));
      } finally {
        path = path.parent;
      }
      return b;
    }

    if (n instanceof Skeleton.Alt a) {
      BitSet b = new BitSet();
      for (int i = 0; i < a.branches().size(); i++) {
        path = new Path("alt[" + i + "]", path);
        try {
          b.or(seq(a.branches().get(i), offset, env));
        } finally {
          path = path.parent;
        }
      }
      return b;
    }

    if (n instanceof Skeleton.Rep r) {
      path = new Path(r, path);
      try {
        BitSet out = new BitSet();
        out.set(offset);
        // After a body: the offsets reached; each may be followed by separator + body.
        BitSet afterBody = seq(r.body(), offset, env);
        BitSet todo = (BitSet) afterBody.clone();
        BitSet seen = new BitSet();
        while (!todo.isEmpty()) {
          int e = todo.nextSetBit(0);
          todo.clear(e);
          if (seen.get(e)) {
            continue;
          }
          seen.set(e);
          out.set(e);
          BitSet afterSep = seq(r.separator(), e, env);
          for (int j = afterSep.nextSetBit(0); j >= 0; j = afterSep.nextSetBit(j + 1)) {
            BitSet next = (BitSet) seq(r.body(), j, env).clone();
            next.andNot(seen);
            todo.or(next);
          }
        }
        return out;
      } finally {
        path = path.parent;
      }
    }
    throw new IllegalArgumentException("unknown node " + n.getClass().getSimpleName());
  }

  private BitSet anything(int offset) {
    BitSet b = new BitSet();
    b.set(offset, s.length() + 1);
    return b;
  }
}
