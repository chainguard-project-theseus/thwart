package dev.chainguard.thwart.compiler.codegen.attr;

import dev.chainguard.thwart.compiler.codegen.AttributeProcessor;
import dev.chainguard.thwart.compiler.codegen.CodegenContext;
import dev.chainguard.thwart.compiler.codegen.ElementPlan;
import dev.chainguard.thwart.compiler.expr.Expr;
import dev.chainguard.thwart.compiler.model.Attribute;
import dev.chainguard.thwart.compiler.types.Scope;
import dev.chainguard.thwart.compiler.types.Variable;
import java.util.Optional;

/**
 * {@code t:object="param"}: sets the form selection ({@code Scope.Selection}) for {@code *{...}}, {@code t:field}, {@code t:errors}. Must name a variable; its name is the form object name.
 */
public final class ObjectProcessor implements AttributeProcessor {

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    Expr e = ctx.parse(attr);
    if (e == null) {
      return;
    }
    if (!(e instanceof Expr.Var)) {
      ctx.diagnostics()
          .error(attr.valuePos(), "t:object must name a variable (a t:param or local variable), e.g. t:object=\"owner\"");
      return;
    }
    Expr.Var v = (Expr.Var) e;
    Optional<Variable> var = plan.scope().lookup(v.name());
    if (!var.isPresent()) {
      ctx.expressions().compile(e, plan.scope()); // reports the unknown identifier
      return;
    }
    Variable variable = var.get();
    plan.scope(plan.scope().withSelection(new Scope.Selection(v.name(), variable.javaName(), variable.type())));
  }
}
