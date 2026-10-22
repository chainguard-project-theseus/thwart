package dev.tessera.compiler.model;

import dev.tessera.compiler.SourcePos;

/** A {@code <!DOCTYPE>}, XML declaration or processing instruction, emitted verbatim. */
public record DocType(String raw, SourcePos pos) implements Node {}
