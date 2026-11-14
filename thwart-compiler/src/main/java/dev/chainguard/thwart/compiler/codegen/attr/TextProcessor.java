package dev.chainguard.thwart.compiler.codegen.attr;

import dev.chainguard.thwart.compiler.codegen.AttributeProcessor;
import dev.chainguard.thwart.compiler.codegen.CodegenContext;
import dev.chainguard.thwart.compiler.codegen.ElementPlan;
import dev.chainguard.thwart.compiler.expr.Expr;
import dev.chainguard.thwart.compiler.html.Contexts;
import dev.chainguard.thwart.compiler.model.Attribute;
import dev.chainguard.thwart.compiler.model.OutputContext;

/**
 * {@code t:text="expr"}: replaces the body with the value escaped for the element's text context
 * ({@code Escapers.text}, or {@code Escapers.json} inside a JSON {@code <script>}). The value must
 * be an output-safe type. Not allowed on a JavaScript {@code <script>}, where a value is only
 * allowed in the prelude (security-architecture.md §5.4).
 */
public final class TextProcessor implements AttributeProcessor {

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    OutputContext context = Contexts.text(plan.element());
    if (context.escaper() == null) {
      ctx.diagnostics().error(attr.pos(), attr.name() + " is not allowed " + TextInlining.where(context));
      return;
    }
    if (context == OutputContext.SCRIPT && !ctx.inJsonScript()) {
      ctx.diagnostics()
          .error(
              attr.pos(),
              attr.name() + " is not allowed on a JavaScript <script>: declare the value at the top of the"
                  + " script, const name = [[...]];, or write it into a <script type=\"application/json\">"
                  + " and read it with JSON.parse");
      return;
    }
    Expr e = ctx.parse(attr);
    if (e == null) {
      return;
    }
    plan.replaceBody(TextInlining.valueStatement(ctx, e, context, plan.scope()), context);
  }
}
