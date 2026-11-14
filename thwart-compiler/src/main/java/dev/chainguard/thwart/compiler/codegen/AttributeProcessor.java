package dev.chainguard.thwart.compiler.codegen;

import dev.chainguard.thwart.compiler.model.Attribute;

/** Handles one family of {@code t:} attributes by modifying an {@link ElementPlan}. Stateless. */
@FunctionalInterface
public interface AttributeProcessor {

  /**
   * Called once per matching attribute, in {@link AttributeProcessors} precedence order. Use
   * {@code plan.scope()} for expressions; report errors via {@code ctx.diagnostics()} with the
   * attribute's position.
   */
  void process(CodegenContext ctx, Attribute attr, ElementPlan plan);
}
