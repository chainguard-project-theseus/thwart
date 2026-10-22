package dev.tessera.compiler.links;

import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.ExpressionCompiler;
import dev.tessera.compiler.types.Scope;

/**
 * Compiles {@code @{/path/{id}(id=x, q=y)}} to
 * {@code ctx.links().build("/path/{id}", new String[] {"id", "q"}, new Object[] {x, y})}
 * (type String). Reports {@code {var}} placeholders without a matching parameter, duplicate
 * parameter names, and paths with schemes other than http/https.
 */
public final class LinkCompiler {
  private LinkCompiler() {}

  public static CompiledExpr compile(Expr.Link link, Scope scope, ExpressionCompiler expressions) {
    throw new UnsupportedOperationException("TODO(#16): LinkCompiler.compile");
  }
}
