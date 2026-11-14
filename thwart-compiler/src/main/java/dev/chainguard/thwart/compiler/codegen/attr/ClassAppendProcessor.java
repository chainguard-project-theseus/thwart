package dev.chainguard.thwart.compiler.codegen.attr;

import dev.chainguard.thwart.compiler.codegen.AttributeProcessor;
import dev.chainguard.thwart.compiler.codegen.CodegenContext;
import dev.chainguard.thwart.compiler.codegen.ElementPlan;
import dev.chainguard.thwart.compiler.expr.Expr;
import dev.chainguard.thwart.compiler.model.Attribute;
import dev.chainguard.thwart.compiler.types.CompiledExpr;
import java.util.Optional;

/**
 * {@code t:classappend="expr"}: appends the value (if non-null and non-empty) to the {@code class} attribute with a single space.
 */
public final class ClassAppendProcessor implements AttributeProcessor {

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    Expr e = ctx.parse(attr);
    CompiledExpr c = e == null ? null : ctx.expressions().compileOutput(e, plan.scope());
    if (c == null) {
      return;
    }
    Optional<ElementPlan.Attr> current = plan.attribute("class");
    String base = current.map(ElementPlan.Attr::valueCode).orElse("null");
    String name = current.map(ElementPlan.Attr::name).orElse("class");
    plan.setAttribute(
        name,
        "dev.chainguard.thwart.runtime.internal.Attributes.appendClass(" + base + ", " + c.code() + ")",
        attr.pos());
  }
}
