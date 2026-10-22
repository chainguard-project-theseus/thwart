package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.codegen.TemplateGenerator;
import dev.tessera.compiler.forms.FormPaths;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.types.Scope;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code t:errorclass="cls"} on an element with {@code t:field}: appends {@code cls} to {@code class} when the field has errors.
 * The value is literal class text ({@code error} or {@code 'has-error is-invalid'}), not an expression.
 */
public final class ErrorClassProcessor implements AttributeProcessor {
  private static final Pattern CLASSES = Pattern.compile("[A-Za-z0-9_-]+( [A-Za-z0-9_-]+)*");

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    String cls = attr.value() == null ? "" : attr.value().strip();
    if (cls.length() >= 2 && cls.startsWith("'") && cls.endsWith("'")) {
      cls = cls.substring(1, cls.length() - 1).strip();
    }
    if (!CLASSES.matcher(cls).matches()) {
      ctx.diagnostics()
          .error(attr.pos(), "t:errorclass needs literal class names (letters, digits, '-', '_'), e.g. t:errorclass=\"error\"");
      return;
    }
    Optional<Attribute> field = plan.element().attribute(Attribute.PREFIX + "field");
    if (field.isEmpty()) {
      ctx.diagnostics().error(attr.pos(), "t:errorclass needs t:field on the same element");
      return;
    }
    Optional<Scope.Selection> selection = plan.scope().selection();
    // t:field reports a missing t:object or a bad path; don't report it twice.
    String path = selection.isEmpty() ? null : FieldProcessor.path(ctx, field.get(), d -> {});
    if (path == null) {
      return;
    }
    String original =
        plan.attribute("class")
            .map(
                a ->
                    switch (a) {
                      case ElementPlan.StaticAttr s ->
                          TemplateGenerator.javaString(s.source().value() == null ? "" : s.source().value());
                      case ElementPlan.DynamicAttr d -> d.valueCode();
                    })
            .orElse("null");
    String o = ctx.freshName("class");
    String c = ctx.freshName("class");
    plan.wrap(
        "{ java.lang.Object " + o + " = " + original + "; java.lang.String " + c
            + " = dev.tessera.runtime.Escapers.toText(" + o + ").strip();",
        "}");
    plan.setAttribute(
        "class",
        FormPaths.formsCall("hasErrors", selection.get(), path) + " ? (" + c + ".isEmpty() ? "
            + TemplateGenerator.javaString(cls) + " : " + c + " + " + TemplateGenerator.javaString(" " + cls) + ") : "
            + o,
        OutputContext.ATTRIBUTE,
        attr.pos());
  }
}
