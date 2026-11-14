package dev.chainguard.thwart.compiler.codegen.attr;

import dev.chainguard.thwart.compiler.JavaStrings;
import dev.chainguard.thwart.compiler.codegen.AttributeProcessor;
import dev.chainguard.thwart.compiler.codegen.CodegenContext;
import dev.chainguard.thwart.compiler.codegen.ElementPlan;
import dev.chainguard.thwart.compiler.model.Attribute;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code t:attr} and {@code t:errorclass} (#766): Thymeleaf attributes Thwart dropped, each a build
 * error that names its recipe. Not a safety check: without it the generic processor still rejects
 * both names (they are off the computed-attribute allow-list), only with a less useful message.
 */
public final class RemovedProcessor implements AttributeProcessor {

  static final String ATTR = "t:attr is not supported; write one t:<name> per attribute:"
      + " t:attr=\"title=a, href=b\" becomes t:title=\"a\" t:href=\"b\"";

  /** Literal classes, quoted or not: {@code err}, {@code 'has-error is-invalid'}. */
  private static final Pattern CLASSES = Pattern.compile("'?([A-Za-z0-9_-]+( [A-Za-z0-9_-]+)*)'?");

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    ctx.diagnostics().error(attr.pos(), attr.localName().equals("attr") ? ATTR : errorClass(attr, plan));
  }

  /**
   * The t:errorclass recipe. {@code fields.hasErrors} takes what {@code t:field} takes ({@code *{age}},
   * a {@code Field} parameter), so the element's own t:field value is the path; literal classes are kept.
   */
  private static String errorClass(Attribute attr, ElementPlan plan) {
    String field = plan.element().attribute(Attribute.PREFIX + "field").map(Attribute::value)
        .map(JavaStrings::stripJava).filter(f -> !f.isEmpty() && f.indexOf('"') < 0).orElse("'<path>'");
    Matcher classes = CLASSES.matcher(attr.value() == null ? "" : JavaStrings.stripJava(attr.value()));
    return "t:errorclass is not supported; write t:classappend=\"fields.hasErrors(" + field + ") ? '"
        + (classes.matches() ? classes.group(1) : "<classes>")
        + "'\" (if the element has a t:classappend already, join both conditions into that one)";
  }
}
