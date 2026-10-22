package dev.tessera.compiler.model;

import dev.tessera.compiler.SourcePos;
import java.util.List;
import java.util.Optional;

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
 */
public record Element(
    String name,
    List<Attribute> attributes,
    List<Node> children,
    boolean selfClosing,
    boolean hasEndTag,
    SourcePos pos)
    implements Node {

  public Element {
    attributes = List.copyOf(attributes);
    children = List.copyOf(children);
  }

  public Optional<Attribute> attribute(String name) {
    return attributes.stream().filter(a -> a.name().equals(name)).findFirst();
  }

  /** {@code t:} attributes in source order. */
  public List<Attribute> tesseraAttributes() {
    return attributes.stream().filter(Attribute::isTessera).toList();
  }
}
