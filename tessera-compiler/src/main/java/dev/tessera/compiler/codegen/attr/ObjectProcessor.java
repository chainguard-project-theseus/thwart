package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.types.Scope;
import dev.tessera.compiler.types.Variable;
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
    if (!(e instanceof Expr.Var v)) {
      ctx.diagnostics()
          .error(attr.valuePos(), "t:object must name a variable (a t:param or local variable), e.g. t:object=\"owner\"");
      return;
    }
    Optional<Variable> var = plan.scope().lookup(v.name());
    if (var.isEmpty()) {
      ctx.expressions().compile(e, plan.scope()); // reports the unknown identifier
      return;
    }
    Variable variable = var.get();
    plan.scope(plan.scope().withSelection(new Scope.Selection(v.name(), variable.javaName(), variable.type())));
  }
}
