package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.model.Attribute;

/**
 * {@code t:each="o, stat : items"}: wraps the element in a loop over an Iterable, array or Map (entries). Declares the loop variable and the status variable ({@code LoopStatus}; implicit name {@code oStat} when omitted).
 */
public final class EachProcessor implements AttributeProcessor {

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    AttributeProcessor.notImplemented(ctx, attr, 12); // TODO(#12)
  }
}
