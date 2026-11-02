package dev.tessera.compiler.codegen;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.html.Contexts;
import dev.tessera.compiler.html.HtmlParser;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.types.Scope;
import dev.tessera.compiler.verify.Skeleton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How one element will be rendered. {@link TemplateGenerator} creates a plan from the element's
 * static attributes, runs the element's {@code t:} attribute processors in precedence order
 * ({@link AttributeProcessors}) to modify it, then emits:
 *
 * <pre>
 *   wrappers[0].open ... wrappers[n].open   (scopes, conditions, loops; see {@link Wrapper})
 *     if removed:            nothing
 *     else if replacement:   replacement
 *     else:
 *                            beforeStartTag
 *       [unless tagOmitted]  "&lt;name" + attributes + "&gt;"   (dynamic attrs: see below)
 *                            afterStartTag
 *                            body if set, else children (rendered in {@link #scope()})
 *       [unless tagOmitted]  "&lt;/name&gt;" if the element had an end tag
 *   wrappers[n].close ... wrappers[0].close
 * </pre>
 *
 * Everything written goes through {@link Emitted}, which records the skeleton. A dynamic
 * attribute is emitted as
 * {@code { Object v$ = <valueCode>; if (v$ != null) { out.write(" name=\""); Escapers.<ctx>(out, v$); out.write('"'); } }},
 * so a {@code null} value omits the attribute (Thymeleaf behaviour) and boolean attributes use
 * {@code cond ? "checked" : null}. Its context is {@link Contexts#attribute} for the element and
 * name, and must have an escaper; processors report contexts without one as build errors instead
 * of adding them.
 */
public final class ElementPlan {

  /**
   * Code opened before and closed after the element: a scope ({@code { decl; ... }}), a condition
   * or a loop. The kind is the skeleton of the element inside: a scope runs it once, a
   * condition makes it optional, a loop repeats it. Only a loop's open code writes: the
   * separator before every iteration but the first.
   */
  public static final class Wrapper {
    /** What the wrapper does with the element inside. */
    public enum Kind {
      SCOPE,
      OPT,
      REP
    }

    private final Kind kind;
    private final Emitted open;
    private final String close;
    private final Skeleton.OptKind optKind;
    private final Skeleton.RepKind repKind;
    private final SourcePos pos;

    private Wrapper(
        Kind kind, Emitted open, String close, Skeleton.OptKind optKind, Skeleton.RepKind repKind, SourcePos pos) {
      this.kind = kind;
      this.open = open;
      this.close = close;
      this.optKind = optKind;
      this.repKind = repKind;
      this.pos = pos;
    }

    public Kind kind() {
      return kind;
    }

    /** The open code and what it writes (a loop's separator). */
    public Emitted openEmitted() {
      return open;
    }

    public String close() {
      return close;
    }

    /** For {@link Kind#OPT}, else null. */
    public Skeleton.OptKind optKind() {
      return optKind;
    }

    /** For {@link Kind#REP}, else null. */
    public Skeleton.RepKind repKind() {
      return repKind;
    }

    public SourcePos pos() {
      return pos;
    }

    @Override
    public String toString() {
      return "Wrapper[kind=" + kind + ", open=" + open.java() + ", close=" + close + "]";
    }
  }

  /** An attribute in the output start tag. */
  public interface Attr {
    String name();

    /**
     * Java code for this attribute's current value: a String literal, {@code ""} for a valueless
     * static attribute, or the dynamic expression code.
     */
    String valueCode();
  }

  /** A non-{@code t:} attribute copied from the source as written. */
  public static final class StaticAttr implements Attr {
    private final Attribute source;

    public StaticAttr(Attribute source) {
      this.source = source;
    }

    public Attribute source() {
      return source;
    }

    @Override
    public String name() {
      return source.name();
    }

    @Override
    public String valueCode() {
      return JavaStrings.literal(source.value() == null ? "" : source.value());
    }
  }

  /** A computed attribute. {@code valueCode} is a Java expression (any type; null omits). */
  public static final class DynamicAttr implements Attr {
    private final String name;
    private final String valueCode;
    private final OutputContext context;
    private final SourcePos pos;

    public DynamicAttr(String name, String valueCode, OutputContext context, SourcePos pos) {
      this.name = name;
      this.valueCode = valueCode;
      this.context = context;
      this.pos = pos;
    }

    public String name() {
      return name;
    }

    public String valueCode() {
      return valueCode;
    }

    public OutputContext context() {
      return context;
    }

    public SourcePos pos() {
      return pos;
    }
  }

  private final Element element;
  private Scope scope;
  private final List<Wrapper> wrappers = new ArrayList<>();
  private final Map<String, Attr> attributes = new LinkedHashMap<>();
  private final List<Emitted> beforeStartTag = new ArrayList<>();
  private final List<Emitted> afterStartTag = new ArrayList<>();
  private Emitted body;
  private Emitted replacement;
  private boolean removed;
  private boolean tagOmitted;

  public ElementPlan(Element element, Scope scope) {
    this.element = element;
    this.scope = scope;
    for (Attribute a : element.attributes()) {
      if (!a.isTessera() && !a.name().equals("xmlns:t")) {
        attributes.put(key(a.name()), new StaticAttr(a));
      }
    }
  }

  /**
   * Names fold A-Z only, as in a browser and the parser's duplicate check: {@code data-\u00C9} is not
   * {@code data-\u00E9}.
   */
  private static String key(String name) {
    return HtmlParser.asciiLower(name);
  }

  public Element element() {
    return element;
  }

  /** Scope for later processors, attribute values and children. */
  public Scope scope() {
    return scope;
  }

  public void scope(Scope scope) {
    this.scope = scope;
  }

  /**
   * Adds a scope inside all previously added wrappers: {@code open} must start with <code>{</code>
   * and {@code close} be <code>}</code>, and between them the element is written exactly once
   * (declarations only; use {@link #wrapOpt} for a condition and {@link #wrapRep} for a loop).
   *
   * @throws IllegalArgumentException if {@code open} or {@code close} is not a block
   */
  public void wrap(String open, String close) {
    if (!open.startsWith("{") || !close.equals("}")) {
      throw new IllegalArgumentException("a scope wrapper is a block { ... }, not " + open + " ... " + close);
    }
    wrappers.add(new Wrapper(Wrapper.Kind.SCOPE, Emitted.code(open), close, null, null, null));
  }

  /** Adds a condition inside all previously added wrappers: the element is written or not. */
  public void wrapOpt(Skeleton.OptKind kind, String open, String close, SourcePos pos) {
    wrappers.add(new Wrapper(Wrapper.Kind.OPT, Emitted.code(open), close, kind, null, pos));
  }

  /**
   * Adds a loop inside all previously added wrappers: the element is written zero or more times,
   * with what {@code open} writes (the separator, written before every iteration but the first)
   * between two of them.
   */
  public void wrapRep(Skeleton.RepKind kind, Emitted open, String close, SourcePos pos) {
    wrappers.add(new Wrapper(Wrapper.Kind.REP, open, close, null, kind, pos));
  }

  public List<Wrapper> wrappers() {
    return Backports.listCopy(wrappers);
  }

  /** Output attributes in order (source order, new attributes appended). */
  public List<Attr> attributes() {
    return Backports.listCopy(attributes.values());
  }

  /** ASCII-case-insensitive lookup. */
  public Optional<Attr> attribute(String name) {
    return Optional.ofNullable(attributes.get(key(name)));
  }

  /**
   * Sets a computed attribute, replacing a same-named one in place or appending. Its context is
   * {@link Contexts#attribute} for this element and {@code name}.
   */
  public void setAttribute(String name, String valueCode, SourcePos pos) {
    attributes.put(
        key(name), new DynamicAttr(name, valueCode, Contexts.attribute(element, name), pos));
  }

  public void removeAttribute(String name) {
    attributes.remove(key(name));
  }

  /**
   * Statements emitted right before the start tag, inside the wrappers (e.g. the hidden
   * {@code _name} marker of a {@code <select multiple t:field>}).
   */
  public void beforeStartTag(Emitted statements) {
    beforeStartTag.add(statements);
  }

  public List<Emitted> beforeStartTagStatements() {
    return Backports.listCopy(beforeStartTag);
  }

  /** Statements emitted right after the start tag (e.g. the CSRF hidden input). */
  public void afterStartTag(Emitted statements) {
    afterStartTag.add(statements);
  }

  public List<Emitted> afterStartTagStatements() {
    return Backports.listCopy(afterStartTag);
  }

  /**
   * Replaces the children with {@code statements} (t:text, t:utext, t:errors, t:insert).
   * {@code writtenFor} is the context the statements escape for; it must be the element's
   * content context ({@link Contexts#text}), so a processor that forgot to check it fails loudly
   * instead of writing HTML into a {@code <script>} or {@code <style>}.
   *
   * @throws IllegalStateException if {@code writtenFor} is not {@code Contexts.text(element())}
   */
  public void replaceBody(Emitted statements, OutputContext writtenFor) {
    OutputContext content = Contexts.text(element);
    if (writtenFor != content) {
      throw new IllegalStateException(
          "body written for " + writtenFor + " inside <" + element.name() + "> whose content is " + content);
    }
    this.body = statements;
  }

  /** Body statements, or null to render the children. */
  public Emitted body() {
    return body;
  }

  /** Replaces the whole element, tag included, with {@code statements} (t:replace). */
  public void replaceElement(Emitted statements) {
    this.replacement = statements;
  }

  public Emitted replacement() {
    return replacement;
  }

  /** Renders nothing for this element (t:remove="all", parameterized fragment in place). */
  public void remove() {
    this.removed = true;
  }

  public boolean removed() {
    return removed;
  }

  /** Renders the body without the element's own tags (t:block, t:remove="tag"). */
  public void omitTag() {
    this.tagOmitted = true;
  }

  public boolean tagOmitted() {
    return tagOmitted;
  }
}
