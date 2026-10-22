package dev.tessera.compiler.expr;

import dev.tessera.compiler.SourcePos;

/**
 * {@code name=expr} in {@code t:with}, {@code t:attr} or link parameters. Attribute names may
 * contain {@code - : .}; link parameter names are plain identifiers.
 */
public record Assignment(String name, Expr value, SourcePos pos) {}
