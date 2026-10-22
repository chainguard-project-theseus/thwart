package dev.tessera.compiler.model;

import dev.tessera.compiler.SourcePos;

/** An HTML comment, emitted verbatim. Expressions inside comments are not processed. */
public record Comment(String raw, SourcePos pos) implements Node {}
