package dev.chainguard.thwart.compiler.model;

import dev.chainguard.thwart.compiler.SourcePos;

/** A node in a parsed template. The tree mirrors the source exactly; nothing is normalized. */
public interface Node {
  SourcePos pos();
}
