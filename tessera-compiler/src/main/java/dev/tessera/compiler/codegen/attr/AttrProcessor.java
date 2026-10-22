package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.model.Attribute;

/**
 * {@code t:attr="name=expr, name2=expr"}: sets each attribute like {@code t:<name>} would.
 */
public final class AttrProcessor implements AttributeProcessor {

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    AttributeProcessor.notImplemented(ctx, attr, 13); // TODO(#13)
  }
}
