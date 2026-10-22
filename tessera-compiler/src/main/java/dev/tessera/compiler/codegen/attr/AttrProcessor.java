package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.expr.Assignment;
import dev.tessera.compiler.model.Attribute;
import java.util.List;

/**
 * {@code t:attr="name=expr, name2=expr"}: sets each attribute like {@code t:<name>} would.
 */
public final class AttrProcessor implements AttributeProcessor {

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    if (attr.value() == null) {
      ctx.diagnostics().error(attr.pos(), attr.name() + " needs a value, e.g. t:attr=\"title=owner.name, data-id=owner.id\"");
      return;
    }
    List<Assignment> assignments = ctx.parser().parseAssignments(attr.value(), attr.valuePos(), ctx.diagnostics());
    if (assignments == null) {
      return;
    }
    for (Assignment a : assignments) {
      if (a.name().startsWith(Attribute.PREFIX)) {
        ctx.diagnostics().error(a.pos(), "t:attr cannot set " + a.name() + "; write the t: attribute directly");
        continue;
      }
      GenericAttrProcessor.set(ctx, plan, a.name(), a.value(), a.pos(), "t:attr " + a.name());
    }
  }
}
