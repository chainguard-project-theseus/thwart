package dev.tessera.compiler.model;

import dev.tessera.compiler.SourcePos;

/** A node in a parsed template. The tree mirrors the source exactly; nothing is normalized. */
public sealed interface Node permits Element, Text, Comment, DocType {
  SourcePos pos();
}
