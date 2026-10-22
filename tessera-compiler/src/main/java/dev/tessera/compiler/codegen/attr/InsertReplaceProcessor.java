package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.model.Attribute;

/**
 * {@code t:insert} (keep host tag, fragment becomes the body) and {@code t:replace} (fragment replaces the host). Target is a fragment reference ({@code ~{tpl :: name(args)}}, same-file {@code ~{:: name}}) checked against the callee signature, or an expression of type {@code Fragment}. Fragment-typed arguments ({@code ~{::body}}, {@code ~{::name}}) become lambdas over the selected element.
 */
public final class InsertReplaceProcessor implements AttributeProcessor {

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    AttributeProcessor.notImplemented(ctx, attr, 14); // TODO(#14)
  }
}
