package dev.tessera.compiler.expr;

import dev.tessera.compiler.SourcePos;

/** {@code Type name} in {@code t:params} or a fragment signature. */
public record ParamDecl(TypeRef type, String name, SourcePos pos) {}
