package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.html.Contexts;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.OutputContext;

/**
 * {@code t:utext="expr"}: replaces the body with raw HTML. The expression must have static type {@code SafeHtml}, or be a bundle message {@code #{key(args)}} whose value passes the build-time markup check (#236); anything else (including String) is a build error that points to {@code SafeHtml}.
 */
public final class UtextProcessor implements AttributeProcessor {

  /** The error for raw HTML written into SVG or MathML content (also used for {@code [(...)]}). */
  public static final String RAW_IN_FOREIGN_CONTENT =
      "raw HTML (t:utext, [(...)]) inside SVG or MathML content is not supported: a browser would close the <svg>"
          + " or <math> at an HTML tag in it; put it inside <foreignObject> or <mtext>";

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    if (!requireHtmlText(ctx, attr, plan)) {
      return;
    }
    if (plan.element().hasForeignContent()) {
      // A browser leaves <svg>/<math> at an HTML tag such as <p>, so the markup after it would be
      // parsed in another namespace than the contexts chosen at build time (#277).
      ctx.diagnostics().error(attr.pos(), RAW_IN_FOREIGN_CONTENT);
      return;
    }
    Expr e = ctx.parse(attr);
    if (e == null) {
      return;
    }
    plan.replaceBody(TextInlining.safeHtmlStatement(ctx, e, attr.name(), plan.scope()), Contexts.text(plan.element()));
  }

  /**
   * Reports and returns false unless the host's content is HTML text, so markup written into the
   * body is never rendered as live script or CSS inside {@code <script>} or {@code <style>}.
   */
  static boolean requireHtmlText(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    OutputContext context = Contexts.text(plan.element());
    if (context != OutputContext.TEXT && context != OutputContext.RCDATA) {
      ctx.diagnostics()
          .error(attr.pos(), attr.name() + " is only allowed on elements whose content is HTML text, not <script> or <style>");
      return false;
    }
    return true;
  }
}
