package dev.tessera.compiler.expr;

import dev.tessera.compiler.SourcePos;
import java.util.List;

/**
 * A Java type as written in {@code t:params} or a fragment signature, e.g.
 * {@code Map<String, List<OwnerView>>} or {@code int[]}. Resolved by the ExpressionCompiler.
 *
 * @param name simple or qualified name as written
 * @param typeArgs type arguments (no wildcards in the MVP)
 * @param arrayDims number of {@code []}
 */
public record TypeRef(String name, List<TypeRef> typeArgs, int arrayDims, SourcePos pos) {
  public TypeRef {
    typeArgs = List.copyOf(typeArgs);
  }
}
