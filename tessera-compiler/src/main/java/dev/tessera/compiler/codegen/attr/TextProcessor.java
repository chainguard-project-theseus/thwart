package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.model.Attribute;

/**
 * {@code t:text="expr"}: replaces the body with the value escaped for the element's text context ({@code Escapers.text}, or {@code Escapers.json} inside {@code <script>}). The value must be an output-safe type.
 */
public final class TextProcessor implements AttributeProcessor {

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    AttributeProcessor.notImplemented(ctx, attr, 11); // TODO(#11)
  }
}
