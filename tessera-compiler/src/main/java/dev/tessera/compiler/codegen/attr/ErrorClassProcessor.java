package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.forms.FormPaths;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.runtime.Escapers;
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
    String cls = attr.value() == null ? "" : Backports.strip(attr.value());
    if (cls.length() >= 2 && cls.startsWith("'") && cls.endsWith("'")) {
      cls = Backports.strip(cls.substring(1, cls.length() - 1));
    }
    if (!CLASSES.matcher(cls).matches()) {
      ctx.diagnostics()
          .error(attr.pos(), "t:errorclass needs literal class names (letters, digits, '-', '_'), e.g. t:errorclass=\"error\"");
      return;
    }
    Optional<Attribute> field = plan.element().attribute(Attribute.PREFIX + "field");
    if (!field.isPresent()) {
      ctx.diagnostics().error(attr.pos(), "t:errorclass needs t:field on the same element");
      return;
    }
    // t:field reports a missing t:object or a bad path; don't report it twice.
    FormPaths.FormField formField = FieldProcessor.field(ctx, field.get(), plan.scope(), false);
    if (formField == null) {
      return;
    }
    String original = plan.attribute("class").map(ElementPlan.Attr::valueCode).orElse("null");
    String o = ctx.freshName("class");
    String c = ctx.freshName("class");
    plan.wrap(
        "{ java.lang.Object " + o + " = " + original + "; java.lang.String " + c
            + " = " + Escapers.class.getName() + ".toText(" + o + ").trim();",
        "}");
    plan.setAttribute(
        "class",
        formField.formsCall("hasErrors") + " ? (" + c + ".isEmpty() ? "
            + JavaStrings.literal(cls) + " : " + c + " + " + JavaStrings.literal(" " + cls) + ") : "
            + o,
        attr.pos());
  }
}
