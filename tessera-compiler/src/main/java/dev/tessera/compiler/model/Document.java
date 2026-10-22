package dev.tessera.compiler.model;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.TemplateSource;
import java.util.List;
import java.util.Objects;

/** A parsed template file: its source and top-level nodes. */
public final class Document {
  private final TemplateSource source;
  private final List<Node> children;

  public Document(TemplateSource source, List<Node> children) {
    this.source = source;
    this.children = Backports.listCopy(children);
  }

  public TemplateSource source() {
    return source;
  }

  public List<Node> children() {
    return children;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Document)) {
      return false;
    }
    Document that = (Document) o;
    return Objects.equals(source, that.source)
        && Objects.equals(children, that.children);
  }

  @Override
  public int hashCode() {
    int h = Objects.hashCode(source);
    h = 31 * h + Objects.hashCode(children);
    return h;
  }

  @Override
  public String toString() {
    return "Document[source=" + source + ", children=" + children + "]";
  }

  /** The first element (where {@code t:params} and {@code t:import} live), or null. */
  public Element rootElement() {
    for (Node n : children) {
      if (n instanceof Element) {
        return (Element) n;
      }
    }
    return null;
  }
}
