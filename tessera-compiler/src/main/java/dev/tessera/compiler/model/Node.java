package dev.tessera.compiler.model;

import dev.tessera.compiler.SourcePos;

/** A node in a parsed template. The tree mirrors the source exactly; nothing is normalized. */
public interface Node {
  SourcePos pos();
}
