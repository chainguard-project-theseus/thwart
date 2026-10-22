package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.html.Contexts;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.OutputContext;

/**
 * {@code t:text="expr"}: replaces the body with the value escaped for the element's text context ({@code Escapers.text}, or {@code Escapers.json} inside {@code <script>}). The value must be an output-safe type.
 */
public final class TextProcessor implements AttributeProcessor {

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    OutputContext context = Contexts.text(plan.element());
    if (context.escaper() == null) {
      ctx.diagnostics().error(attr.pos(), attr.name() + " is not allowed " + TextInlining.where(context));
      return;
    }
    Expr e = ctx.parse(attr);
    if (e == null) {
      return;
    }
    plan.replaceBody(TextInlining.valueStatement(ctx, e, context, plan.scope()), context);
  }
}
