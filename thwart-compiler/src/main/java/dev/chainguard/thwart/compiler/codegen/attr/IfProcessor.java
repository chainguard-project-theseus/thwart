package dev.chainguard.thwart.compiler.codegen.attr;

import dev.chainguard.thwart.compiler.codegen.AttributeProcessor;
import dev.chainguard.thwart.compiler.codegen.CodegenContext;
import dev.chainguard.thwart.compiler.codegen.ElementPlan;
import dev.chainguard.thwart.compiler.expr.Expr;
import dev.chainguard.thwart.compiler.model.Attribute;
import dev.chainguard.thwart.compiler.types.CompiledExpr;
import dev.chainguard.thwart.compiler.verify.Skeleton;

/**
 * {@code t:if="cond"} / {@code t:unless="cond"}: wraps the element in {@code if} using
 * {@code ExpressionCompiler.compileCondition}. Truthiness follows Thymeleaf
 * ({@code dev.chainguard.thwart.runtime.internal.Conditions}).
 */
public final class IfProcessor implements AttributeProcessor {

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    Expr e = ctx.parse(attr);
    if (e == null) {
      return;
    }
    CompiledExpr cond = ctx.expressions().compileCondition(e, plan.scope());
    if (cond == null) {
      return;
    }
    String code = attr.localName().equals("unless") ? "!(" + cond.code() + ")" : cond.code();
    plan.wrapOpt(Skeleton.OptKind.CONDITION, "if (" + code + ") {", "}", attr.pos());
  }
}
