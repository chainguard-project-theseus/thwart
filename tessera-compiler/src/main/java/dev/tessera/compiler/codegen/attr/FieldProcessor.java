package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.model.Attribute;

/**
 * {@code t:field="name"} / {@code t:field="*{name}"} inside {@code t:object}: checks the property path at build time and emits {@code id}, {@code name} and the bound value ({@code ctx.forms().value(...)}) for input/select/textarea; checkbox and radio get {@code checked}; select options are marked {@code selected}.
 */
public final class FieldProcessor implements AttributeProcessor {

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    AttributeProcessor.notImplemented(ctx, attr, 15); // TODO(#15)
  }
}
