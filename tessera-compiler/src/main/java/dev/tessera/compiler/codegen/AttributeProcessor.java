package dev.tessera.compiler.codegen;

import dev.tessera.compiler.model.Attribute;

/** Handles one family of {@code t:} attributes by modifying an {@link ElementPlan}. Stateless. */
@FunctionalInterface
public interface AttributeProcessor {

  /**
   * Called once per matching attribute, in {@link AttributeProcessors} precedence order. Use
   * {@code plan.scope()} for expressions; report errors via {@code ctx.diagnostics()} with the
   * attribute's position.
   */
  void process(CodegenContext ctx, Attribute attr, ElementPlan plan);

  /** Shared stub behaviour until a family is implemented. */
  static void notImplemented(CodegenContext ctx, Attribute attr, int issue) {
    ctx.diagnostics()
        .error(attr.pos(), attr.name() + " is not implemented yet (TODO(#" + issue + "))");
  }
}
