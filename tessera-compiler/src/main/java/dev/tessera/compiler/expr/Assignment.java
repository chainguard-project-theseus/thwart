package dev.tessera.compiler.expr;

import dev.tessera.compiler.SourcePos;

/** {@code name=expr} in {@code t:with} or {@code t:attr}. Attribute names may contain {@code - : .}. */
public record Assignment(String name, Expr value, SourcePos pos) {}
