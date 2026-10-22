package dev.tessera.compiler.messages;

import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.ExpressionCompiler;
import dev.tessera.compiler.types.Scope;

/**
 * Compiles {@code #{key(args)}} to {@code ctx.messages().get("key", args...)} (type String),
 * reporting unknown keys when the bundle is checked.
 */
public final class MessageCompiler {
  private MessageCompiler() {}

  public static CompiledExpr compile(Expr.Message message, Scope scope, ExpressionCompiler expressions) {
    throw new UnsupportedOperationException("TODO(#16): MessageCompiler.compile");
  }
}
