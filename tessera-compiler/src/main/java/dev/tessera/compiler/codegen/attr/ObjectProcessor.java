package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.model.Attribute;

/**
 * {@code t:object="param"}: sets the form selection ({@code Scope.Selection}) for {@code *{...}}, {@code t:field}, {@code t:errors}. Must name a variable; its name is the form object name.
 */
public final class ObjectProcessor implements AttributeProcessor {

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    AttributeProcessor.notImplemented(ctx, attr, 15); // TODO(#15)
  }
}
