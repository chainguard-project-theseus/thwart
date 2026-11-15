package dev.chainguard.thwart.compiler.forms;

import dev.chainguard.thwart.compiler.SourcePos;
import dev.chainguard.thwart.compiler.expr.Expr;
import dev.chainguard.thwart.compiler.types.CompiledExpr;
import dev.chainguard.thwart.compiler.types.ExpressionCompiler;
import dev.chainguard.thwart.compiler.types.Scope;
import java.util.List;

/**
 * The reserved {@code ids} namespace (Thymeleaf's {@code #ids}, thymeleaf's {@code Ids}): ids
 * numbered by the same per-render counter as the checkbox and radio ids {@code t:field} writes,
 * so a label can point at them. {@code ids.seq(id)} counts and returns the next id
 * ({@code roles1}, {@code roles2}, ...: {@code RenderContext.sequenceId}), {@code ids.next(id)}
 * returns it without counting ({@code nextSequenceId}), and {@code ids.prev(id)} the last one
 * returned ({@code previousSequenceId}, which fails the render if there is none). The argument is
 * any expression, converted to text; {@code null} fails the render, as in Thymeleaf. The result is
 * a {@code String}, escaped for wherever it is written. The ExpressionCompiler routes every
 * {@code ids.*} and {@code #ids.*} call here.
 */
public final class IdsCompiler {
  private IdsCompiler() {}

  public static CompiledExpr compile(
      String function, List<Expr> args, SourcePos pos, Scope scope, ExpressionCompiler expressions) {
    String method;
    switch (function) {
      case "seq":
        method = "sequenceId";
        break;
      case "next":
        method = "nextSequenceId";
        break;
      case "prev":
        method = "previousSequenceId";
        break;
      default:
        expressions.env().diagnostics()
            .error(pos, "unknown function ids." + function + ": the ids namespace has seq('name'), next('name') and prev('name')");
        return null;
    }
    if (args.size() != 1) {
      expressions.env().diagnostics()
          .error(pos, "ids." + function + " takes one argument, the id (a field name such as 'roles')");
      return null;
    }
    // String.valueOf below prints the id: check it as text output, so a record is not toString()'d (#802).
    CompiledExpr id = expressions.compileOutput(args.get(0), scope);
    if (id == null) {
      return null;
    }
    String code =
        "ctx."
            + method
            + "(java.lang.String.valueOf(java.util.Objects.requireNonNull("
            + id.code()
            + ", \"ids."
            + function
            + ": the id is null\")))";
    return new CompiledExpr(
        code, expressions.env().elements().getTypeElement("java.lang.String").asType(), pos);
  }
}
