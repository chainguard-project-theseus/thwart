package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.Variable;
import dev.tessera.compiler.verify.Skeleton;
import java.util.Optional;
import javax.lang.model.type.TypeKind;

/**
 * {@code t:switch="expr"} with descendant {@code t:case="value"} / {@code t:case="*"}: first
 * matching case renders, others are removed. {@code t:case} outside a {@code t:switch} is a
 * build error. A case value is compared with the expression compiler's {@code ==} (so an enum
 * switch accepts string-literal cases naming its constants).
 *
 * <p>The switch value and the "matched" flag are passed to the cases as scope variables whose
 * names contain {@code :}, which no template identifier can, so templates cannot reach them.
 */
public final class SwitchProcessor implements AttributeProcessor {
  private static final String VALUE = "t:switch";
  private static final String MATCHED = "t:switch-matched";

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    if (attr.localName().equals("switch")) {
      processSwitch(ctx, attr, plan);
    } else {
      processCase(ctx, attr, plan);
    }
  }

  private static void processSwitch(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    CompiledExpr value = ctx.compile(attr, plan.scope());
    if (value == null) {
      return;
    }
    String v = ctx.freshName("switch");
    String m = ctx.freshName("matched");
    plan.wrap("{ " + WithProcessor.declaration(value, v) + "boolean " + m + " = false; ", "}");
    plan.scope(
        plan.scope()
            .with(new Variable(VALUE, value.type(), v))
            .with(new Variable(MATCHED, ctx.env().types().getPrimitiveType(TypeKind.BOOLEAN), m)));
  }

  private static void processCase(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    Optional<Variable> value = plan.scope().lookup(VALUE);
    Optional<Variable> matched = plan.scope().lookup(MATCHED);
    if (!value.isPresent() || !matched.isPresent() || plan.element().attribute(Attribute.PREFIX + "switch").isPresent()) {
      ctx.diagnostics().error(attr.pos(), "t:case must be inside an element with t:switch (on an enclosing element, not the same one)");
      return;
    }
    String m = matched.get().javaName();
    String cond;
    if (attr.value() != null && JavaStrings.trimJava(attr.value()).equals("*")) {
      cond = "!" + m;
    } else {
      Expr e = ctx.parse(attr);
      if (e == null) {
        return;
      }
      Expr eq = new Expr.Binary(Expr.BinaryOp.EQ, new Expr.Var(VALUE, e.pos()), e, e.pos());
      CompiledExpr c = ctx.expressions().compileCondition(eq, plan.scope());
      if (c == null) {
        return;
      }
      cond = "!" + m + " && (" + c.code() + ")";
    }
    plan.wrapOpt(Skeleton.OptKind.CASE, "if (" + cond + ") { " + m + " = true; ", "}", attr.pos());
  }
}
