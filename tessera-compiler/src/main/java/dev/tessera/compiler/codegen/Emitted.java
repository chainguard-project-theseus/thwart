package dev.tessera.compiler.codegen;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.html.HtmlParser;
import dev.tessera.compiler.verify.Skeleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The single output path of generated code (#366): Java statements that write template output,
 * together with the {@link Skeleton} of what they write. Every {@code out.write}, escaper call and
 * fragment render in generated code is made here, and records its skeleton node in the same call
 * that makes its Java text, so the skeleton the verifier checks cannot differ from the bytes the
 * class writes. {@code EmissionGuardTest} fails if generated-code strings anywhere else in the
 * compiler write output; {@code Compilation} checks every render of the test suite against the
 * skeleton.
 *
 * <p>Java that writes nothing (declarations, conditions, loop heads) is passed in as plain strings
 * by the combinators: {@link #code}, {@link #wrap}, {@link #opt} and {@link #rep}. Choosing the
 * combinator is choosing the skeleton: {@code wrap} for code that runs its body exactly once,
 * {@code opt} for a condition, {@code rep} for a loop.
 *
 * <p>Immutable.
 */
public final class Emitted {
  private static final String ESCAPERS = "dev.tessera.runtime.Escapers.";

  /** Nothing. */
  public static final Emitted EMPTY = new Emitted("", Collections.<Skeleton.Node>emptyList());

  private final String java;
  private final List<Skeleton.Node> nodes;

  private Emitted(String java, List<Skeleton.Node> nodes) {
    this.java = java;
    this.nodes = nodes;
  }

  private static Emitted of(String java, Skeleton.Node node) {
    return new Emitted(java, Collections.singletonList(node));
  }

  /** The Java statements. */
  public String java() {
    return java;
  }

  /** The skeleton of what {@link #java} writes. */
  public List<Skeleton.Node> nodes() {
    return nodes;
  }

  public Skeleton.Seq seq() {
    return new Skeleton.Seq(nodes);
  }

  public boolean isEmpty() {
    return java.isEmpty() && nodes.isEmpty();
  }

  // ---- what is written ----

  /** Java that writes nothing, e.g. {@code "\n"} or a declaration. */
  public static Emitted code(String java) {
    return new Emitted(java, Collections.<Skeleton.Node>emptyList());
  }

  /** {@code out.write("<text>");} */
  public static Emitted text(String text, SourcePos pos) {
    return of("out.write(" + JavaStrings.literal(text) + ");", new Skeleton.Static(text, pos));
  }

  /** {@code out.write('<c>');} */
  public static Emitted text(char c, SourcePos pos) {
    String literal = c == '\'' || c == '\\' ? "'\\" + c + "'" : "'" + c + "'";
    return of("out.write(" + literal + ");", new Skeleton.Static(String.valueOf(c), pos));
  }

  /**
   * {@code Escapers.<escaper>(out, <valueCode>);}. For element text ({@link Skeleton.Escaper#TEXT})
   * the method is a marker until {@link #resolve} puts in the one the verifier names (#378).
   */
  public static Emitted hole(
      Skeleton.Escaper escaper, String element, String attribute, SourcePos pos, String valueCode) {
    Skeleton.Hole hole = new Skeleton.Hole(escaper, lower(element), attribute, pos);
    String method = escaper == Skeleton.Escaper.TEXT ? MARK + hole.id() + MARK : escaper.method();
    return of(ESCAPERS + method + "(out, " + valueCode + ");", hole);
  }

  /** Delimits a text hole's id in place of its method; never in generated code otherwise ({@link JavaStrings}). */
  private static final String MARK = "\0";

  /**
   * {@code java} with the method of every text hole in it set to the escaper in {@code escapers},
   * which the verifier names from the parser state there ({@code ContextVerifier#verify}).
   *
   * @throws IllegalStateException if {@code escapers} has no escaper for one of them
   */
  public static String resolve(String java, Map<Skeleton.Hole, Skeleton.Escaper> escapers) {
    if (java.indexOf(MARK) < 0) {
      return java;
    }
    Map<Integer, Skeleton.Escaper> byId = new HashMap<>();
    for (Map.Entry<Skeleton.Hole, Skeleton.Escaper> e : escapers.entrySet()) {
      byId.put(e.getKey().id(), e.getValue());
    }
    StringBuilder out = new StringBuilder(java.length());
    int i = 0;
    for (int start = java.indexOf(MARK); start >= 0; start = java.indexOf(MARK, i)) {
      int end = java.indexOf(MARK, start + 1);
      Skeleton.Escaper e = byId.get(Integer.valueOf(java.substring(start + 1, end)));
      if (e == null) {
        throw new IllegalStateException("the verifier reached no text hole " + java.substring(start + 1, end));
      }
      out.append(java, i, start).append(e.method());
      i = end + 1;
    }
    return out.append(java, i, java.length()).toString();
  }

  /**
   * A {@code <script>} JSON value serialized by generated helper {@code helper} ({@link #jsonHelperMethod}),
   * which writes with {@code Escapers.appendJson}: {@code helper(<valueCode>, out);}. With
   * {@code javaScript} the helper writes the value in parentheses, as {@code Escapers.javaScript} does.
   */
  public static Emitted jsonHelperHole(String helper, String element, SourcePos pos, String valueCode, boolean javaScript) {
    return of(
        helper + "(" + valueCode + ", out);",
        new Skeleton.Hole(javaScript ? Skeleton.Escaper.JAVASCRIPT : Skeleton.Escaper.JSON, lower(element), null, pos));
  }

  /**
   * The member method behind {@link #jsonHelperHole}: {@code body} appends JSON to the buffer
   * {@code s$} only with {@code Escapers.appendJson} and literal JSON punctuation, then the buffer is
   * written, in parentheses with {@code javaScript}. Returns the whole method.
   */
  public static String jsonHelperMethod(String helper, String body, boolean javaScript) {
    return "private static void " + helper + "(java.lang.Object o$, dev.tessera.runtime.Output out) {\n"
        + "java.lang.StringBuilder s$ = new java.lang.StringBuilder();\n"
        + body + ";\nout.write(" + (javaScript ? "\"(\" + s$ + \")\"" : "s$.toString()") + ");\n}\n";
  }

  /** A {@code SafeHtml} value: {@code Escapers.html(out, <valueCode>);} */
  public static Emitted safeHtml(SourcePos pos, String valueCode) {
    return of(ESCAPERS + "html(out, " + valueCode + ");", new Skeleton.RawInsert(Skeleton.Source.SAFE_HTML, pos));
  }

  /**
   * A bundle message checked as HTML: {@code out.write(<messageCode>);} ({@code Escapers.htmlMessage(...)}),
   * which writes what {@code skeleton} describes ({@code MessageCompiler#htmlSkeleton}: the
   * message's markup, known at build time, #329).
   */
  public static Emitted htmlMessage(String messageCode, Skeleton.Node skeleton) {
    return of("out.write(" + messageCode + ");", skeleton);
  }

  /**
   * A statically resolved fragment call: {@code <method>(<args>..., <ctxCode>, out);}, writing
   * {@code target}'s skeleton with {@code fragmentArgs} bound. {@code ctxCode} is the render
   * context passed on ({@code CodegenContext#insertionContext}).
   */
  public static Emitted call(
      Skeleton.FragmentId target,
      Map<String, Skeleton.Seq> fragmentArgs,
      SourcePos pos,
      String method,
      List<String> args,
      String ctxCode) {
    List<String> all = new ArrayList<>(args);
    all.add(ctxCode);
    all.add("out");
    return of(method + "(" + String.join(", ", all) + ");", new Skeleton.Call(target, fragmentArgs, pos));
  }

  /**
   * A {@code Fragment} value: {@code { <type> <local> = <valueCode>; if (<local> != null) {
   * <local>.render(<ctxCode>, out); } }}. {@code candidates} is the skeleton of what the value may
   * write (#561). {@code ctxCode} is the render context passed on ({@code
   * CodegenContext#insertionContext}).
   */
  public static Emitted fragmentValue(
      String type, String local, String valueCode, Skeleton.Node candidates, String ctxCode) {
    return of(
        "{ " + type + " " + local + " = " + valueCode + "; if (" + local + " != null) { " + local
            + ".render(" + ctxCode + ", out); } }",
        candidates);
  }

  // ---- combinators ----

  /** The parts one after the other. */
  public static Emitted concat(Emitted... parts) {
    StringBuilder java = new StringBuilder();
    List<Skeleton.Node> nodes = new ArrayList<>();
    for (Emitted p : parts) {
      java.append(p.java);
      nodes.addAll(p.nodes);
    }
    return new Emitted(java.toString(), Collections.unmodifiableList(nodes));
  }

  /** {@code this} followed by Java that writes nothing. */
  public Emitted then(String java) {
    return new Emitted(this.java + java, nodes);
  }

  /** {@code body} inside Java that runs it exactly once ({@code before} and {@code after} write nothing). */
  public static Emitted wrap(String before, Emitted body, String after) {
    return new Emitted(before + body.java + after, body.nodes);
  }

  /** {@code body} inside a condition: {@code before} opens it, {@code after} closes it. */
  public static Emitted opt(Skeleton.OptKind kind, SourcePos pos, String before, Emitted body, String after) {
    return of(before + body.java + after, new Skeleton.Opt(kind, body.seq(), pos));
  }

  /**
   * A loop: {@code head} opens it and the start of an iteration, {@code separator} is written
   * before every iteration but the first (inside {@code head}'s condition), {@code middle} closes
   * that condition, {@code body} is the iteration and {@code tail} closes the loop.
   */
  public static Emitted rep(
      Skeleton.RepKind kind,
      SourcePos pos,
      String head,
      Emitted separator,
      String middle,
      Emitted body,
      String tail) {
    return of(
        head + separator.java + middle + body.java + tail,
        new Skeleton.Rep(kind, body.seq(), separator.seq(), pos));
  }

  private static String lower(String element) {
    return element == null ? null : HtmlParser.asciiLower(element);
  }

  /**
   * Accumulates a method body: coalesces adjacent static text into writes of class constants
   * (split at {@code chunk} chars for the constant pool), appends statements, and opens and closes
   * {@link ElementPlan.Wrapper}s around the statements between them.
   */
  public static final class Builder {
    private final Function<String, String> constant;
    private final int chunk;
    private final StringBuilder code = new StringBuilder();
    private final StringBuilder text = new StringBuilder();
    /** The node list of every open wrapper, innermost last; the first is the body's. */
    private final List<List<Skeleton.Node>> frames = new ArrayList<>();
    private final List<ElementPlan.Wrapper> open = new ArrayList<>();

    /** {@code constant} names the class constant holding a text. */
    public Builder(Function<String, String> constant, int chunk) {
      this.constant = constant;
      this.chunk = chunk;
      frames.add(new ArrayList<Skeleton.Node>());
    }

    private List<Skeleton.Node> current() {
      return frames.get(frames.size() - 1);
    }

    /** Static text, written as is. */
    public void text(String s, SourcePos pos) {
      if (s.isEmpty()) {
        return;
      }
      text.append(s);
      current().add(new Skeleton.Static(s, pos));
    }

    public void add(Emitted e) {
      flush();
      stmt(e.java);
      current().addAll(e.nodes);
    }

    /** Opens {@code w}: its open code, then the statements until {@link #close}. */
    public void open(ElementPlan.Wrapper w) {
      flush();
      stmt(w.openEmitted().java);
      if (w.kind() != ElementPlan.Wrapper.Kind.REP && !w.openEmitted().nodes.isEmpty()) {
        throw new IllegalStateException("only a loop writes in its open code: " + w);
      }
      frames.add(new ArrayList<Skeleton.Node>());
      open.add(w);
    }

    /** Closes {@code w}, the innermost open wrapper. */
    public void close(ElementPlan.Wrapper w) {
      if (open.isEmpty() || open.get(open.size() - 1) != w) {
        throw new IllegalStateException("wrappers closed out of order: " + w);
      }
      flush();
      stmt(w.close());
      open.remove(open.size() - 1);
      Skeleton.Seq body = new Skeleton.Seq(frames.remove(frames.size() - 1));
      switch (w.kind()) {
        case SCOPE:
          current().addAll(body.nodes());
          break;
        case OPT:
          current().add(new Skeleton.Opt(w.optKind(), body, w.pos()));
          break;
        case REP:
          current().add(new Skeleton.Rep(w.repKind(), body, w.openEmitted().seq(), w.pos()));
          break;
        default:
          throw new IllegalStateException(String.valueOf(w.kind()));
      }
    }

    public Emitted build() {
      if (!open.isEmpty()) {
        throw new IllegalStateException("wrapper not closed: " + open);
      }
      flush();
      return new Emitted(code.toString(), Backports.listCopy(current()));
    }

    private void stmt(String s) {
      if (!s.isEmpty()) {
        code.append(s);
        if (!s.endsWith("\n")) {
          code.append('\n');
        }
      }
    }

    private void flush() {
      int i = 0;
      while (i < text.length()) {
        int end = Math.min(text.length(), i + chunk);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
          end--;
        }
        code.append("out.write(").append(constant.apply(text.substring(i, end))).append(");\n");
        i = end;
      }
      text.setLength(0);
    }
  }
}
