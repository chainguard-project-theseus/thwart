package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.model.Attribute;

/**
 * {@code t:utext="expr"}: replaces the body with raw HTML. The expression must have static type {@code SafeHtml}; anything else (including String and messages) is a build error that points to {@code SafeHtml}.
 */
public final class UtextProcessor implements AttributeProcessor {

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    AttributeProcessor.notImplemented(ctx, attr, 11); // TODO(#11)
  }
}
