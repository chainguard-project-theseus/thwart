package dev.tessera.compiler.verify;

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
 * byte-equal; each hole in its escaper's output language (memo §3.1); {@code Opt}, {@code Alt} and
 * {@code Rep} as the regex operators; a {@code Call} as the target's skeleton with its fragment
 * arguments bound; a bound {@code ParamInsert} as its argument or nothing; a {@code RawInsert} or an
 * unbound {@code ParamInsert} as anything.
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
      int next = escaper == Skeleton.Escaper.JSON ? jsonToken(s, i) : htmlToken(s, i, escaper == Skeleton.Escaper.TEXT);
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
   * One token of HTML-escaped output ({@code escapeHtml}; with {@code text} also the empty comment
   * that breaks braces): a char that is not {@code & < > " '}, NUL or a lone surrogate, one of the
   * five references, or {@code <!---->}. Returns the end, or -1.
   */
  private static int htmlToken(String s, int i, boolean text) {
    char c = s.charAt(i);
    if (text && s.startsWith("<!---->", i)) {
      return i + 7;
    }
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
    return n.accept(new Skeleton.Visitor<BitSet>() {
      @Override
      public BitSet visitStatic(Skeleton.Static st) {
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

      @Override
      public BitSet visitHole(Skeleton.Hole h) {
        BitSet b = holeEnds(h.escaper(), s, offset);
        reached(b.length() - 1, "hole", h, 0);
        return b;
      }

      @Override
      public BitSet visitRawInsert(Skeleton.RawInsert r) {
        return anything(offset);
      }

      @Override
      public BitSet visitCall(Skeleton.Call c) {
        Skeleton.Seq target = fragments.apply(c.target());
        if (target == null) {
          throw new AssertionError("no skeleton for fragment " + c.target());
        }
        Key k = new Key(c, 0, 0, env);
        Env callee = envs.get(k);
        if (callee == null) {
          callee = new Env(c.fragmentArgs(), env);
          envs.put(k, callee);
        }
        path = new Path(c, path);
        try {
          return seq(target, offset, callee);
        } finally {
          path = path.parent;
        }
      }

      @Override
      public BitSet visitParamInsert(Skeleton.ParamInsert p) {
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

      @Override
      public BitSet visitSeq(Skeleton.Seq q) {
        return seq(q, offset, env);
      }

      @Override
      public BitSet visitOpt(Skeleton.Opt o) {
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

      @Override
      public BitSet visitAlt(Skeleton.Alt a) {
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

      @Override
      public BitSet visitRep(Skeleton.Rep r) {
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
    });
  }

  private BitSet anything(int offset) {
    BitSet b = new BitSet();
    b.set(offset, s.length() + 1);
    return b;
  }
}
