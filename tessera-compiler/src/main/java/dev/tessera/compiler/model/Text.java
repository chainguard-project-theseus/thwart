package dev.tessera.compiler.model;

import dev.tessera.compiler.SourcePos;

/**
 * Character data exactly as written (including CDATA sections).
 *
 * @param raw source text, not decoded
 * @param context {@link OutputContext#TEXT}, {@link OutputContext#SCRIPT} or
 *     {@link OutputContext#STYLE} (or FORBIDDEN for non-JS script types); decides how
 *     {@code [[...]]} inlined expressions are escaped
 */
public record Text(String raw, OutputContext context, SourcePos pos) implements Node {}
