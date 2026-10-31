package dev.tessera.compiler.verify;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.SourcePos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What a generated template class writes, recorded while the generator writes it (#366): the
 * exact static text, the escaped holes, the control flow around them and the fragment insertions.
 * A render of the class is always in the language of its skeleton, which the test suite checks
 * on every render (docs/security-architecture.md §11). The build-time context verifier (#367)
 * feeds the statics to spec HTML parsers and checks each hole in the parser state it lands in.
 *
 * <p>Every node is immutable. Positions are the source construct the bytes come from (the text,
 * comment, element or attribute; the expression of a hole), or null for markup the generator
 * makes up (the CSRF hidden input, the {@code t:field} checkbox marker, the {@code t:errors}
 * separator).
 */
public final class Skeleton {
  private Skeleton() {}

  /** A skeleton node. */
  public interface Node {
    /** Where in the template the node comes from, or null. */
    SourcePos pos();

    <R> R accept(Visitor<R> v);
  }

  /** Dispatch over the node kinds, so callers need no {@code instanceof} chains. */
  public interface Visitor<R> {
    R visitStatic(Static n);

    R visitHole(Hole n);

    R visitRawInsert(RawInsert n);

    R visitCall(Call n);

    R visitParamInsert(ParamInsert n);

    R visitSeq(Seq n);

    R visitOpt(Opt n);

    R visitAlt(Alt n);

    R visitRep(Rep n);
  }

  /** The runtime escaper a hole is written with: exactly one {@code dev.tessera.runtime.Escapers} method. */
  public enum Escaper {
    TEXT("text"),
    RCDATA("rcdata"),
    ATTR("attr"),
    URL("url"),
    URL_LIST("urlList"),
    RESOURCE_URL("resourceUrl"),
    REFRESH("refresh"),
    JSON("json"),
    JAVASCRIPT("javaScript"),
    JSON_ATTR("jsonAttr");

    private final String method;

    Escaper(String method) {
      this.method = method;
    }

    /** The {@code Escapers} method name. */
    public String method() {
      return method;
    }

    /** Whether the hole is inside a double-quoted attribute value. */
    public boolean inAttribute() {
      return this != TEXT && this != RCDATA && this != JSON && this != JAVASCRIPT;
    }

    /** The escaper whose {@code Escapers} method is {@code method}. */
    public static Escaper forMethod(String method) {
      for (Escaper e : values()) {
        if (e.method.equals(method)) {
          return e;
        }
      }
      throw new IllegalArgumentException("no escaper " + method);
    }
  }

  /** Where the HTML of a {@link RawInsert} comes from. */
  public enum Source {
    /** A {@code SafeHtml} value: {@code t:utext}, {@code [(...)]}. */
    SAFE_HTML,
    /** A bundle message checked as HTML at build time: {@code t:utext="#{key}"}. */
    HTML_MESSAGE,
    /** A {@code Fragment} value from the model (not a fragment parameter of the enclosing fragment). */
    FRAGMENT_VALUE
  }

  /** Why an {@link Opt} may or may not be written. */
  public enum OptKind {
    /** {@code t:if}, {@code t:unless}. */
    CONDITION,
    /** {@code t:case} (each case is optional; at most one is written). */
    CASE,
    /** A computed attribute, omitted when its value is null: {@code [ name="<hole>"]}. */
    ATTRIBUTE,
    /** The CSRF hidden input: a token is present. */
    CSRF_TOKEN,
    /** The {@code t:field} checkbox's hidden {@code _name} input, when a computed {@code disabled} is null. */
    CHECKBOX_MARKER,
    /** A {@code t:errors} element, written when the field has errors. */
    ERRORS,
    /**
     * The CSP nonce of a {@code <script>} or {@code <style>} start tag: {@code [ nonce="<hole>"]},
     * written when the render context has a nonce (not where a fragment is inserted in svg, math or
     * raw text, #335).
     */
    CSP_NONCE
  }

  /** What an {@link Rep} repeats. */
  public enum RepKind {
    /** {@code t:each}; the separator is the whitespace before the element (Thymeleaf repeats it). */
    EACH,
    /** The messages of {@code t:errors}, separated by {@code <br />}. */
    ERRORS
  }

  /** Text written exactly as is. */
  public static final class Static implements Node {
    private final String text;
    private final SourcePos pos;

    public Static(String text, SourcePos pos) {
      this.text = Objects.requireNonNull(text);
      this.pos = pos;
    }

    public String text() {
      return text;
    }

    @Override
    public SourcePos pos() {
      return pos;
    }

    @Override
    public <R> R accept(Visitor<R> v) {
      return v.visitStatic(this);
    }

    @Override
    public String toString() {
      return quote(text);
    }
  }

  /**
   * A value written with {@code escaper}. {@code element} is the lower-case name of the element
   * whose content or start tag the value is in, as the generator sees it (the nearest element whose
   * tag is written; null at the top level of a template or fragment). {@code attribute} is the
   * attribute name for an attribute escaper, else null. {@code pos} is the expression. A
   * {@link Escaper#TEXT} hole is element text: the verifier names its escaper ({@code text} or
   * {@code rcdata}) from the parser state, and the hole's {@link #id} finds it in the generated code
   * (#378).
   */
  public static final class Hole implements Node {
    private static final AtomicInteger IDS = new AtomicInteger();
    private final int id = IDS.incrementAndGet();
    private final Escaper escaper;
    private final String element;
    private final String attribute;
    private final SourcePos pos;

    public Hole(Escaper escaper, String element, String attribute, SourcePos pos) {
      this.escaper = Objects.requireNonNull(escaper);
      this.element = element;
      this.attribute = attribute;
      this.pos = pos;
    }

    public Escaper escaper() {
      return escaper;
    }

    /** Unique among the holes made in this JVM. */
    public int id() {
      return id;
    }

    public String element() {
      return element;
    }

    public String attribute() {
      return attribute;
    }

    @Override
    public SourcePos pos() {
      return pos;
    }

    @Override
    public <R> R accept(Visitor<R> v) {
      return v.visitHole(this);
    }

    @Override
    public String toString() {
      return "${" + escaper + (attribute == null ? "" : " " + attribute) + (pos == null ? "" : " @" + pos) + "}";
    }
  }

  /**
   * HTML written as is: known only at render time, or, for an {@link Source#HTML_MESSAGE}, one of
   * the markups in {@link #content} (#329).
   */
  public static final class RawInsert implements Node {
    private final Source source;
    private final Seq content;
    private final SourcePos pos;

    public RawInsert(Source source, SourcePos pos) {
      this(source, pos, null);
    }

    /**
     * HTML whose every possible markup {@code content} describes (statics and text holes), or null
     * if it is known only at render time.
     */
    public RawInsert(Source source, SourcePos pos, Seq content) {
      this.source = Objects.requireNonNull(source);
      this.content = content;
      this.pos = pos;
    }

    public Source source() {
      return source;
    }

    /** What is written, if it is known at build time; else null. */
    public Seq content() {
      return content;
    }

    @Override
    public SourcePos pos() {
      return pos;
    }

    @Override
    public <R> R accept(Visitor<R> v) {
      return v.visitRawInsert(this);
    }

    @Override
    public String toString() {
      return "$raw{" + source + (content == null ? "" : " " + content) + (pos == null ? "" : " @" + pos) + "}";
    }
  }

  /** A fragment: the template's view name and the fragment's name. */
  public static final class FragmentId {
    private final String view;
    private final String name;

    public FragmentId(String view, String name) {
      this.view = Objects.requireNonNull(view);
      this.name = Objects.requireNonNull(name);
    }

    public String view() {
      return view;
    }

    public String name() {
      return name;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof FragmentId)) {
        return false;
      }
      FragmentId that = (FragmentId) o;
      return view.equals(that.view) && name.equals(that.name);
    }

    @Override
    public int hashCode() {
      return 31 * view.hashCode() + name.hashCode();
    }

    @Override
    public String toString() {
      return view + " :: " + name;
    }
  }

  /**
   * A statically resolved fragment call ({@code t:insert}/{@code t:replace} of
   * {@code ~{template :: name(args)}}): writes {@code target}'s skeleton. {@code fragmentArgs}
   * binds the target's {@code Fragment} parameters whose argument is known at build time to the
   * skeleton of that argument (a {@code ~{...}} reference, a {@code ~{::tag}} selection, or a
   * {@code Fragment} parameter of the caller passed on); a {@code Fragment} parameter with no entry
   * is unbound (any HTML). Bound skeletons are read in the caller's bindings.
   */
  public static final class Call implements Node {
    private final FragmentId target;
    private final Map<String, Seq> fragmentArgs;
    private final SourcePos pos;

    public Call(FragmentId target, Map<String, Seq> fragmentArgs, SourcePos pos) {
      this.target = Objects.requireNonNull(target);
      this.fragmentArgs = Backports.mapCopy(fragmentArgs);
      this.pos = pos;
    }

    public FragmentId target() {
      return target;
    }

    public Map<String, Seq> fragmentArgs() {
      return fragmentArgs;
    }

    @Override
    public SourcePos pos() {
      return pos;
    }

    @Override
    public <R> R accept(Visitor<R> v) {
      return v.visitCall(this);
    }

    @Override
    public String toString() {
      return "call{" + target + (fragmentArgs.isEmpty() ? "" : " " + fragmentArgs) + "}";
    }
  }

  /**
   * {@code t:insert}/{@code t:replace} of {@code ${param}}, where {@code param} is a
   * {@code Fragment} parameter of the enclosing fragment: writes the skeleton bound to it at the
   * call site, or nothing if the value is null; any HTML if it is unbound.
   */
  public static final class ParamInsert implements Node {
    private final String param;
    private final SourcePos pos;

    public ParamInsert(String param, SourcePos pos) {
      this.param = Objects.requireNonNull(param);
      this.pos = pos;
    }

    public String param() {
      return param;
    }

    @Override
    public SourcePos pos() {
      return pos;
    }

    @Override
    public <R> R accept(Visitor<R> v) {
      return v.visitParamInsert(this);
    }

    @Override
    public String toString() {
      return "$param{" + param + "}";
    }
  }

  /** Its nodes, one after the other. */
  public static final class Seq implements Node {
    private static final Seq EMPTY = new Seq(Collections.<Node>emptyList());

    private final List<Node> nodes;

    public Seq(List<? extends Node> nodes) {
      this.nodes = Backports.listCopy(nodes);
    }

    public static Seq empty() {
      return EMPTY;
    }

    public List<Node> nodes() {
      return nodes;
    }

    /** The position of the first node, or null. */
    @Override
    public SourcePos pos() {
      return nodes.isEmpty() ? null : nodes.get(0).pos();
    }

    @Override
    public <R> R accept(Visitor<R> v) {
      return v.visitSeq(this);
    }

    @Override
    public String toString() {
      List<String> parts = new ArrayList<>();
      for (Node n : nodes) {
        parts.add(String.valueOf(n));
      }
      return String.join(" ", parts);
    }
  }

  /** {@code body}, or nothing. */
  public static final class Opt implements Node {
    private final OptKind kind;
    private final Seq body;
    private final SourcePos pos;

    public Opt(OptKind kind, Seq body, SourcePos pos) {
      this.kind = Objects.requireNonNull(kind);
      this.body = Objects.requireNonNull(body);
      this.pos = pos;
    }

    public OptKind kind() {
      return kind;
    }

    public Seq body() {
      return body;
    }

    @Override
    public SourcePos pos() {
      return pos;
    }

    @Override
    public <R> R accept(Visitor<R> v) {
      return v.visitOpt(this);
    }

    @Override
    public String toString() {
      return "opt:" + kind + "(" + body + ")";
    }
  }

  /** Exactly one of {@code branches}. */
  public static final class Alt implements Node {
    private final List<Seq> branches;
    private final SourcePos pos;

    public Alt(List<Seq> branches, SourcePos pos) {
      this.branches = Backports.listCopy(branches);
      this.pos = pos;
    }

    public List<Seq> branches() {
      return branches;
    }

    @Override
    public SourcePos pos() {
      return pos;
    }

    @Override
    public <R> R accept(Visitor<R> v) {
      return v.visitAlt(this);
    }

    @Override
    public String toString() {
      List<String> parts = new ArrayList<>();
      for (Seq b : branches) {
        parts.add(String.valueOf(b));
      }
      return "alt(" + String.join(" | ", parts) + ")";
    }
  }

  /** Zero or more {@code body}, with {@code separator} between two of them. */
  public static final class Rep implements Node {
    private final RepKind kind;
    private final Seq body;
    private final Seq separator;
    private final SourcePos pos;

    public Rep(RepKind kind, Seq body, Seq separator, SourcePos pos) {
      this.kind = Objects.requireNonNull(kind);
      this.body = Objects.requireNonNull(body);
      this.separator = Objects.requireNonNull(separator);
      this.pos = pos;
    }

    public RepKind kind() {
      return kind;
    }

    public Seq body() {
      return body;
    }

    public Seq separator() {
      return separator;
    }

    @Override
    public SourcePos pos() {
      return pos;
    }

    @Override
    public <R> R accept(Visitor<R> v) {
      return v.visitRep(this);
    }

    @Override
    public String toString() {
      return "rep:" + kind + "(" + body + (separator.nodes().isEmpty() ? "" : " / " + separator) + ")";
    }
  }

  /**
   * The skeleton of one template: what its {@code render} method writes ({@code body}), and what
   * each of its fragment methods writes ({@code fragments}, by fragment name).
   */
  public static final class TemplateSkeleton {
    private final String viewName;
    private final Seq body;
    private final Map<String, Seq> fragments;
    private final Map<String, String> fragmentContexts;

    public TemplateSkeleton(String viewName, Seq body, Map<String, Seq> fragments) {
      this(viewName, body, fragments, Collections.<String, String>emptyMap());
    }

    /**
     * {@code fragmentContexts}: the parsing context each fragment is declared in, as {@code
     * html.ParsingContexts} names it ({@code HTML}, {@code svg}, {@code math}, {@code
     * annotation-xml}, {@code select}, or one where no fragment fits); HTML if absent (#461).
     */
    public TemplateSkeleton(String viewName, Seq body, Map<String, Seq> fragments, Map<String, String> fragmentContexts) {
      this.viewName = Objects.requireNonNull(viewName);
      this.body = Objects.requireNonNull(body);
      this.fragments = Collections.unmodifiableMap(new LinkedHashMap<>(fragments));
      this.fragmentContexts = Collections.unmodifiableMap(new LinkedHashMap<>(fragmentContexts));
    }

    /** The parsing context fragment {@code name} is declared in (see the constructor). */
    public String fragmentContext(String name) {
      String c = fragmentContexts.get(name);
      return c == null ? "HTML" : c;
    }

    public String viewName() {
      return viewName;
    }

    public Seq body() {
      return body;
    }

    public Map<String, Seq> fragments() {
      return fragments;
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder(viewName).append(": ").append(body);
      for (Map.Entry<String, Seq> f : fragments.entrySet()) {
        sb.append("\n  :: ").append(f.getKey()).append(": ").append(f.getValue());
      }
      return sb.toString();
    }
  }

  private static String quote(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"' || c == '\\') {
        sb.append('\\').append(c);
      } else if (c == '\n') {
        sb.append("\\n");
      } else if (c < 0x20) {
        sb.append(String.format("\\u%04x", (int) c));
      } else {
        sb.append(c);
      }
    }
    return sb.append('"').toString();
  }
}
