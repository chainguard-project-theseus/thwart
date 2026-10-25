package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.Emitted;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.forms.FormPaths;
import dev.tessera.compiler.html.Contexts;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.types.Scope;
import dev.tessera.compiler.verify.Skeleton;
import java.util.Optional;

/**
 * {@code t:errors="name"} (or {@code "*"}): if the field has errors, renders them escaped and joined by {@code <br />} as the body; otherwise removes the element.
 * Inside a fragment, {@code name} may be a {@code Field} parameter.
 */
public final class ErrorsProcessor implements AttributeProcessor {

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    if (!UtextProcessor.requireHtmlText(ctx, attr, plan)) {
      return;
    }
    FormPaths.FormField field;
    String value = attr.value() == null ? "" : Backports.strip(attr.value());
    if (value.equals(FormPaths.ALL) || value.equals("*{*}")) {
      Optional<Scope.Selection> selection = plan.scope().selection();
      if (!selection.isPresent()) {
        ctx.diagnostics().error(attr.pos(), "t:errors can only be used inside t:object (on this element or an ancestor)");
        return;
      }
      field = FormPaths.literal(selection.get(), FormPaths.ALL, null);
    } else {
      field = FieldProcessor.field(ctx, attr, plan.scope(), true);
      if (field == null) {
        return;
      }
    }
    String errors = ctx.freshName("errors");
    String i = ctx.freshName("i");
    plan.wrapOpt(
        Skeleton.OptKind.ERRORS,
        "{ java.util.List<java.lang.String> "
            + errors
            + " = "
            + field.formsCall("errors")
            + "; if (!"
            + errors
            + ".isEmpty()) {",
        "} }",
        attr.pos());
    OutputContext context = Contexts.text(plan.element()); // TEXT, or RCDATA in <title>/<textarea>
    plan.replaceBody(
        Emitted.rep(
            Skeleton.RepKind.ERRORS,
            attr.pos(),
            "for (int " + i + " = 0; " + i + " < " + errors + ".size(); " + i + "++) { if (" + i + " > 0) { ",
            Emitted.text("<br />", null),
            " } ",
            Emitted.hole(
                Skeleton.Escaper.forMethod(context.escaper()), ctx.hostElement(), null, attr.pos(),
                errors + ".get(" + i + ")"),
            " }"),
        context);
  }
}
