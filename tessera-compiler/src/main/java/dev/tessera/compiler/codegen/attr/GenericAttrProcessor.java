package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.model.Attribute;

/**
 * Any other {@code t:<name>} (t:href, t:src, t:value, t:id, t:class, t:title, t:data-x, ...): sets attribute {@code name} from the expression, escaped for {@code Contexts.attribute(element, name)}. Boolean attributes ({@code checked, selected, disabled, readonly, required, multiple, hidden, ...}) take a condition. EVENT_HANDLER, STYLE and FORBIDDEN contexts are build errors. RESOURCE_URL accepts only a literal-path link expression or a {@code TrustedUrl}.
 */
public final class GenericAttrProcessor implements AttributeProcessor {

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    AttributeProcessor.notImplemented(ctx, attr, 13); // TODO(#13)
  }
}
