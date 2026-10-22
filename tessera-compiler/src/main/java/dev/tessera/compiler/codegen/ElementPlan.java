package dev.tessera.compiler.codegen;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.html.Contexts;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.types.Scope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * How one element will be rendered. {@link TemplateGenerator} creates a plan from the element's
 * static attributes, runs the element's {@code t:} attribute processors in precedence order
 * ({@link AttributeProcessors}) to modify it, then emits:
 *
 * <pre>
 *   wrappers[0].open ... wrappers[n].open
 *     if removed:            nothing
 *     else if replacement:   replacement
 *     else:
 *       [unless tagOmitted]  "&lt;name" + attributes + "&gt;"   (dynamic attrs: see below)
 *                            afterStartTag
 *                            body if set, else children (rendered in {@link #scope()})
 *       [unless tagOmitted]  "&lt;/name&gt;" if the element had an end tag
 *   wrappers[n].close ... wrappers[0].close
 * </pre>
 *
 * A dynamic attribute is emitted as
 * {@code { Object v$ = <valueCode>; if (v$ != null) { out.write(" name=\""); Escapers.<ctx>(out, v$); out.write('"'); } }},
 * so a {@code null} value omits the attribute (Thymeleaf behaviour) and boolean attributes use
 * {@code cond ? "checked" : null}. Its context is {@link Contexts#attribute} for the element and
 * name, and must have an escaper; processors report contexts without one as build errors instead
 * of adding them.
 */
public final class ElementPlan {

  /** Code opened before and closed after the element, e.g. a loop or if. */
  public static final class Wrapper {
    private final String open;
    private final String close;

    public Wrapper(String open, String close) {
      this.open = open;
      this.close = close;
    }

    public String open() {
      return open;
    }

    public String close() {
      return close;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Wrapper)) {
        return false;
      }
      Wrapper that = (Wrapper) o;
      return Objects.equals(open, that.open)
          && Objects.equals(close, that.close);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(open);
      h = 31 * h + Objects.hashCode(close);
      return h;
    }

    @Override
    public String toString() {
      return "Wrapper[open=" + open + ", close=" + close + "]";
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
    public boolean equals(Object o) {
      if (!(o instanceof StaticAttr)) {
        return false;
      }
      StaticAttr that = (StaticAttr) o;
      return Objects.equals(source, that.source);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(source);
    }

    @Override
    public String toString() {
      return "StaticAttr[source=" + source + "]";
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

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof DynamicAttr)) {
        return false;
      }
      DynamicAttr that = (DynamicAttr) o;
      return Objects.equals(name, that.name)
          && Objects.equals(valueCode, that.valueCode)
          && Objects.equals(context, that.context)
          && Objects.equals(pos, that.pos);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(name);
      h = 31 * h + Objects.hashCode(valueCode);
      h = 31 * h + Objects.hashCode(context);
      h = 31 * h + Objects.hashCode(pos);
      return h;
    }

    @Override
    public String toString() {
      return "DynamicAttr[name=" + name + ", valueCode=" + valueCode + ", context=" + context
          + ", pos=" + pos + "]";
    }
  }

  private final Element element;
  private Scope scope;
  private final List<Wrapper> wrappers = new ArrayList<>();
  private final Map<String, Attr> attributes = new LinkedHashMap<>();
  private final List<String> afterStartTag = new ArrayList<>();
  private String body;
  private String replacement;
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

  private static String key(String name) {
    return name.toLowerCase(Locale.ROOT);
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

  /** Adds a wrapper inside all previously added ones. */
  public void wrap(String open, String close) {
    wrappers.add(new Wrapper(open, close));
  }

  public List<Wrapper> wrappers() {
    return Backports.listCopy(wrappers);
  }

  /** Output attributes in order (source order, new attributes appended). */
  public List<Attr> attributes() {
    return Backports.listCopy(attributes.values());
  }

  /** Case-insensitive lookup. */
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

  /** Statements emitted right after the start tag (e.g. the CSRF hidden input). */
  public void afterStartTag(String statements) {
    afterStartTag.add(statements);
  }

  public List<String> afterStartTagStatements() {
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
  public void replaceBody(String statements, OutputContext writtenFor) {
    OutputContext content = Contexts.text(element);
    if (writtenFor != content) {
      throw new IllegalStateException(
          "body written for " + writtenFor + " inside <" + element.name() + "> whose content is " + content);
    }
    this.body = statements;
  }

  /** Body statements, or null to render the children. */
  public String body() {
    return body;
  }

  /** Replaces the whole element, tag included, with {@code statements} (t:replace). */
  public void replaceElement(String statements) {
    this.replacement = statements;
  }

  public String replacement() {
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
