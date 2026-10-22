package dev.tessera.compiler.model;

import dev.tessera.compiler.TemplateSource;
import java.util.List;

/** A parsed template file: its source and top-level nodes. */
public record Document(TemplateSource source, List<Node> children) {
  public Document {
    children = List.copyOf(children);
  }

  /** The first element (where {@code t:params} and {@code t:import} live), or null. */
  public Element rootElement() {
    for (Node n : children) {
      if (n instanceof Element e) {
        return e;
      }
    }
    return null;
  }
}
