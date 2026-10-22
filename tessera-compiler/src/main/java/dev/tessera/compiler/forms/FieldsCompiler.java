package dev.tessera.compiler.forms;

import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.ExpressionCompiler;
import dev.tessera.compiler.types.Scope;
import java.util.List;

/**
 * The reserved {@code fields} namespace (Thymeleaf's {@code #fields}), valid only inside
 * {@code t:object}: {@code fields.hasErrors('path')} / {@code fields.hasErrors('*')} (boolean)
 * and {@code fields.errors('path')} ({@code List<String>}). Paths must be string literals and
 * are checked against the selection type. Compiles to {@code ctx.forms().hasErrors(objectName, path)}
 * / {@code ctx.forms().errors(objectName, path)}. The ExpressionCompiler routes every
 * {@code fields.*} and {@code #fields.*} call here.
 */
public final class FieldsCompiler {
  private FieldsCompiler() {}

  public static CompiledExpr compile(
      String function, List<Expr> args, SourcePos pos, Scope scope, ExpressionCompiler expressions) {
    expressions.env().diagnostics().error(pos, "fields." + function + " is not implemented yet (TODO(#15))");
    return null;
  }
}
