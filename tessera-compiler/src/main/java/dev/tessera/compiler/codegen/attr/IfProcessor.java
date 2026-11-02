package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.verify.Skeleton;

/**
 * {@code t:if="cond"} / {@code t:unless="cond"}: wraps the element in {@code if} using
 * {@code ExpressionCompiler.compileCondition}. Truthiness follows Thymeleaf
 * ({@code dev.tessera.runtime.internal.Conditions}).
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
