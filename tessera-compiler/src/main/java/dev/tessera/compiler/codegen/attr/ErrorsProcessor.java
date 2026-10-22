package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.model.Attribute;

/**
 * {@code t:errors="name"} (or {@code "*"}): if the field has errors, renders them escaped and joined by {@code <br />} as the body; otherwise removes the element.
 */
public final class ErrorsProcessor implements AttributeProcessor {

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    AttributeProcessor.notImplemented(ctx, attr, 15); // TODO(#15)
  }
}
