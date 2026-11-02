package dev.tessera.compiler.codegen;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.verify.Skeleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The single output path of generated code: Java statements that write template output,
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
 * <p>Static text is kept apart from the code around it until {@link #java} is asked for, so that
 * {@link #concat} and {@link Builder#add} merge adjacent static text, also across nested elements,
 * into one write.
 *
 * <p>Immutable.
 */
public final class Emitted {
  private static final String ESCAPERS = "dev.tessera.runtime.Escapers.";
  /** Longest static text written by one call (and so one class constant). */
  private static final int CHUNK = 8192;

  /** Nothing. */
  public static final Emitted EMPTY = new Emitted(new Parts(), null, Collections.<Skeleton.Node>emptyList());

  /** Static text and Java code, alternating: text, code, text, ..., text (any may be empty). */
  private final List<String> parts;
  /** Names the class constant holding a text; {@code null} writes literals. */
  private final Function<String, String> constant;
  private final List<Skeleton.Node> nodes;
  private String java;

  private Emitted(Parts parts, Function<String, String> constant, List<Skeleton.Node> nodes) {
    this.parts = Collections.unmodifiableList(new ArrayList<>(parts.list));
    this.constant = constant;
    this.nodes = nodes;
  }

  private static Emitted of(String java, Skeleton.Node node) {
    return new Emitted(new Parts().code(java), null, Collections.singletonList(node));
  }

  /** The Java statements. */
  public String java() {
    if (java == null) {
      StringBuilder code = new StringBuilder();
      for (int i = 0; i < parts.size(); i++) {
        if (i % 2 == 1) {
          code.append(parts.get(i));
        } else {
          writeText(code, parts.get(i));
        }
      }
      java = code.toString();
    }
    return java;
  }

  /** {@code out.write(...);} for {@code text}, split at {@link #CHUNK} chars for the constant pool. */
  private void writeText(StringBuilder code, String text) {
    if (text.length() == 1 && text.charAt(0) >= 0x20 && text.charAt(0) < 0x7f) {
      char c = text.charAt(0);
      code.append(c == '\'' || c == '\\' ? "out.write('\\" + c + "');\n" : "out.write('" + c + "');\n");
      return;
    }
    int i = 0;
    while (i < text.length()) {
      int end = Math.min(text.length(), i + CHUNK);
      if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
        end--;
      }
      String chunk = text.substring(i, end);
      code.append("out.write(").append(constant == null ? JavaStrings.literal(chunk) : constant.apply(chunk))
          .append(");\n");
      i = end;
    }
  }

  /** The skeleton of what {@link #java} writes. */
  public List<Skeleton.Node> nodes() {
    return nodes;
  }

  public Skeleton.Seq seq() {
    return new Skeleton.Seq(nodes);
  }

  // ---- what is written ----

  /** Java that writes nothing, e.g. {@code "\n"} or a declaration. */
  public static Emitted code(String java) {
    return new Emitted(new Parts().code(java), null, Collections.<Skeleton.Node>emptyList());
  }

  /** {@code out.write("<text>");} */
  public static Emitted text(String text, SourcePos pos) {
    return new Emitted(
        new Parts().text(text), null, Collections.<Skeleton.Node>singletonList(new Skeleton.Static(text, pos)));
  }

  /** {@code out.write('<c>');} */
  public static Emitted text(char c, SourcePos pos) {
    return text(String.valueOf(c), pos);
  }

  /**
   * {@code Escapers.<escaper>(out, <valueCode>);}, in the value of {@code attribute}, or in content if
   * it is null.
   */
  public static Emitted hole(Skeleton.Escaper escaper, String attribute, SourcePos pos, String valueCode) {
    return of(ESCAPERS + escaper.method() + "(out, " + valueCode + ");", new Skeleton.Hole(escaper, attribute, pos));
  }

  /**
   * One double-quoted attribute, {@code  <attribute>="<value>"}, the value written by
   * {@code escaper}: {@code Escapers.<escaper>(out, <valueCode>);}. The blank code around the
   * escaper call writes nothing; it keeps the generated Java as it was before
   * {@code Emitted}.
   */
  public static Emitted attributeValue(Skeleton.Escaper escaper, String attribute, SourcePos pos, String valueCode) {
    return concat(
        text(" " + attribute + "=\"", pos),
        code(" "),
        hole(escaper, attribute, pos, valueCode),
        code(" "),
        text('"', pos));
  }

  /**
   * A {@code <script>} JSON value serialized by generated helper {@code helper} ({@link #jsonHelperMethod}),
   * which writes with {@code Escapers.appendJson}: {@code helper(<valueCode>, out);}. With
   * {@code javaScript} the helper writes the value in parentheses, as {@code Escapers.javaScript} does.
   */
  public static Emitted jsonHelperHole(String helper, SourcePos pos, String valueCode, boolean javaScript) {
    return of(
        helper + "(" + valueCode + ", out);",
        new Skeleton.Hole(javaScript ? Skeleton.Escaper.JAVASCRIPT : Skeleton.Escaper.JSON, null, pos));
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
   * A bundle message as HTML: {@code Escapers.html(out, <messageCode>);}, where {@code messageCode}
   * ({@code Escapers.htmlMessage(...)}) sanitizes it by the message policy, with or without
   * {@code links}.
   */
  public static Emitted htmlMessage(SourcePos pos, String messageCode, boolean links) {
    Skeleton.Source source = links ? Skeleton.Source.LINKED_HTML_MESSAGE : Skeleton.Source.HTML_MESSAGE;
    return of(ESCAPERS + "html(out, " + messageCode + ");", new Skeleton.RawInsert(source, pos));
  }

  /**
   * A statically resolved fragment call: {@code <method>(<args>..., ctx, out);}, writing
   * {@code target}'s skeleton with {@code fragmentArgs} bound.
   */
  public static Emitted call(
      Skeleton.FragmentId target,
      Map<String, Skeleton.Seq> fragmentArgs,
      SourcePos pos,
      String method,
      List<String> args) {
    List<String> all = new ArrayList<>(args);
    all.add("ctx");
    all.add("out");
    return of(method + "(" + String.join(", ", all) + ");", new Skeleton.Call(target, fragmentArgs, pos));
  }

  /**
   * A {@code Fragment} value: {@code { <type> <local> = <valueCode>; if (<local> != null) {
   * <local>.render(ctx, out); } }}. {@code candidates} is the skeleton of what the value may
   * write.
   */
  public static Emitted fragmentValue(String type, String local, String valueCode, Skeleton.Node candidates) {
    return of(
        "{ " + type + " " + local + " = " + valueCode + "; if (" + local + " != null) { " + local
            + ".render(ctx, out); } }",
        candidates);
  }

  // ---- combinators ----

  /** The parts one after the other. */
  public static Emitted concat(Emitted... parts) {
    Parts all = new Parts();
    List<Skeleton.Node> nodes = new ArrayList<>();
    Function<String, String> constant = null;
    for (Emitted p : parts) {
      all.add(p);
      nodes.addAll(p.nodes);
      constant = constant != null ? constant : p.constant;
    }
    return new Emitted(all, constant, Collections.unmodifiableList(nodes));
  }

  /** {@code this} followed by Java that writes nothing. */
  public Emitted then(String java) {
    return new Emitted(new Parts().add(this).code(java), constant, nodes);
  }

  /** {@code body} inside Java that runs it exactly once ({@code before} and {@code after} write nothing). */
  public static Emitted wrap(String before, Emitted body, String after) {
    return new Emitted(new Parts().code(before + body.java() + after), null, body.nodes);
  }

  /** {@code body} inside a condition: {@code before} opens it, {@code after} closes it. */
  public static Emitted opt(Skeleton.OptKind kind, SourcePos pos, String before, Emitted body, String after) {
    return of(before + body.java() + after, new Skeleton.Opt(kind, body.seq(), pos));
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
        head + separator.java() + middle + body.java() + tail,
        new Skeleton.Rep(kind, body.seq(), separator.seq(), pos));
  }

  /**
   * {@link #parts} under construction: odd in size, text at even indexes. Text only ever moves
   * across code that is blank, so the order of what is written never changes.
   */
  private static final class Parts {
    final List<String> list = new ArrayList<>(Collections.singletonList(""));

    /** Appends static text, merged with the text before it if only blank code is in between. */
    Parts text(String text) {
      if (text.isEmpty()) {
        return this;
      }
      int last = list.size() - 1;
      if (last >= 2 && list.get(last).isEmpty() && blank(list.get(last - 1))) {
        list.remove(last);
        list.remove(last - 1);
        last -= 2;
      }
      list.set(last, list.get(last) + text);
      return this;
    }

    /** Appends Java code, joined to the code before it if no text is in between. */
    Parts code(String java) {
      if (java.isEmpty()) {
        return this;
      }
      int last = list.size() - 1;
      if (last >= 1 && list.get(last).isEmpty()) {
        list.set(last - 1, list.get(last - 1) + java);
      } else {
        list.add(java);
        list.add("");
      }
      return this;
    }

    private static boolean blank(String java) {
      for (int i = 0; i < java.length(); i++) {
        if (!JavaStrings.isJavaWhitespace(java.charAt(i))) {
          return false;
        }
      }
      return true;
    }

    Parts add(Emitted e) {
      for (int i = 0; i < e.parts.size(); i++) {
        if (i % 2 == 1) {
          code(e.parts.get(i));
        } else {
          text(e.parts.get(i));
        }
      }
      return this;
    }
  }

  /**
   * Accumulates a method body: merges adjacent static text, its own and that of the parts added,
   * into writes of class constants, appends statements, and opens and closes
   * {@link ElementPlan.Wrapper}s around the statements between them.
   */
  public static final class Builder {
    private final Function<String, String> constant;
    private final Parts parts = new Parts();
    /** The node list of every open wrapper, innermost last; the first is the body's. */
    private final List<List<Skeleton.Node>> frames = new ArrayList<>();
    private final List<ElementPlan.Wrapper> open = new ArrayList<>();

    /** {@code constant} names the class constant holding a text. */
    public Builder(Function<String, String> constant) {
      this.constant = constant;
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
      parts.text(s);
      current().add(new Skeleton.Static(s, pos));
    }

    public void add(Emitted e) {
      for (int i = 0; i < e.parts.size(); i++) {
        if (i % 2 == 1) {
          stmt(e.parts.get(i));
        } else {
          parts.text(e.parts.get(i));
        }
      }
      current().addAll(e.nodes);
    }

    /** Opens {@code w}: its open code, then the statements until {@link #close}. */
    public void open(ElementPlan.Wrapper w) {
      stmt(w.openEmitted().java());
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
      return new Emitted(parts, constant, Backports.listCopy(current()));
    }

    /** Code, on lines of its own. */
    private void stmt(String s) {
      parts.code(s.isEmpty() || s.endsWith("\n") ? s : s + "\n");
    }
  }
}
