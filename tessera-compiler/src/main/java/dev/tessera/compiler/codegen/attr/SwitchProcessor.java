package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.model.Attribute;

/**
 * {@code t:switch="expr"} with child {@code t:case="value"} / {@code t:case="*"}: first matching case renders, others are removed. {@code t:case} outside a {@code t:switch} is a build error.
 */
public final class SwitchProcessor implements AttributeProcessor {

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    AttributeProcessor.notImplemented(ctx, attr, 12); // TODO(#12)
  }
}
