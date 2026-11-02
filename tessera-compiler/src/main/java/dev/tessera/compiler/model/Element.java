package dev.tessera.compiler.model;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.SourcePos;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * An element as written.
 *
 * @param name element name as written (case preserved), e.g. {@code div}, {@code t:block}
 * @param attributes attributes in source order, including {@code t:} attributes
 * @param children child nodes
 * @param selfClosing written as {@code <x/>}
 * @param hasEndTag an explicit end tag was present (false for void and self-closing elements
 *     and for elements closed implicitly)
 * @param pos position of the {@code <}
 * @param foreign an {@code <svg>} or {@code <math>} element or one inside it, by tag (set by the
 *     parser; integration points are not modelled)
 */
public final class Element implements Node {
  private final String name;
  private final List<Attribute> attributes;
  private final List<Node> children;
  private final boolean selfClosing;
  private final boolean hasEndTag;
  private final SourcePos pos;
  private final boolean foreign;

  public Element(
      String name,
      List<Attribute> attributes,
      List<Node> children,
      boolean selfClosing,
      boolean hasEndTag,
      SourcePos pos) {
    this(name, attributes, children, selfClosing, hasEndTag, pos, false);
  }

  public Element(
      String name,
      List<Attribute> attributes,
      List<Node> children,
      boolean selfClosing,
      boolean hasEndTag,
      SourcePos pos,
      boolean foreign) {
    this.name = name;
    this.attributes = Backports.listCopy(attributes);
    this.children = Backports.listCopy(children);
    this.selfClosing = selfClosing;
    this.hasEndTag = hasEndTag;
    this.pos = pos;
    this.foreign = foreign;
  }

  public String name() {
    return name;
  }

  public List<Attribute> attributes() {
    return attributes;
  }

  public List<Node> children() {
    return children;
  }

  public boolean selfClosing() {
    return selfClosing;
  }

  public boolean hasEndTag() {
    return hasEndTag;
  }

  public SourcePos pos() {
    return pos;
  }

  public boolean foreign() {
    return foreign;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Element)) {
      return false;
    }
    Element that = (Element) o;
    return Objects.equals(name, that.name)
        && Objects.equals(attributes, that.attributes)
        && Objects.equals(children, that.children)
        && selfClosing == that.selfClosing
        && hasEndTag == that.hasEndTag
        && Objects.equals(pos, that.pos)
        && foreign == that.foreign;
  }

  @Override
  public int hashCode() {
    int h = Objects.hashCode(name);
    h = 31 * h + Objects.hashCode(attributes);
    h = 31 * h + Objects.hashCode(children);
    h = 31 * h + Boolean.hashCode(selfClosing);
    h = 31 * h + Boolean.hashCode(hasEndTag);
    h = 31 * h + Objects.hashCode(pos);
    h = 31 * h + Boolean.hashCode(foreign);
    return h;
  }

  @Override
  public String toString() {
    return "Element[name=" + name + ", attributes=" + attributes + ", children=" + children
        + ", selfClosing=" + selfClosing + ", hasEndTag=" + hasEndTag + ", pos=" + pos
        + (foreign ? ", foreign=true" : "") + "]";
  }

  public Optional<Attribute> attribute(String name) {
    return attributes.stream().filter(a -> a.name().equals(name)).findFirst();
  }

  /** {@code t:} attributes in source order. */
  public List<Attribute> tesseraAttributes() {
    return attributes.stream().filter(Attribute::isTessera).collect(Collectors.toList());
  }
}
