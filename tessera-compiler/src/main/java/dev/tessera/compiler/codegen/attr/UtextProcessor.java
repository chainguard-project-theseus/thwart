package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.html.Contexts;
import dev.tessera.compiler.html.ParsingContexts;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.OutputContext;

/**
 * {@code t:utext="expr"}: replaces the body with raw HTML. The expression must have static type {@code SafeHtml}, or be a bundle message {@code #{key(args)}} whose value passes the build-time markup check (#236); anything else (including String) is a build error that points to {@code SafeHtml}.
 */
public final class UtextProcessor implements AttributeProcessor {

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    ParsingContexts contexts = ctx.template().parsingContexts();
    boolean rawText = contexts.insideRawText(plan.element()) || ParsingContexts.rawTextElement(plan.element());
    if (!requireHtmlText(ctx, attr, plan)
        || !TextInlining.requireHtmlContent(ctx, contexts.content(plan.element()), rawText, attr.pos(), attr.name())) {
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
    if (context != OutputContext.TEXT) {
      ctx.diagnostics()
          .error(
              attr.pos(),
              attr.name() + " is only allowed on elements whose content is HTML text, not <script> or <style>"
                  + (context == OutputContext.SCRIPT
                      ? ": pass a value to a script as const name = [[...]]; at its top, or in a"
                          + " <script type=\"application/json\"> read with JSON.parse"
                      : ""));
      return false;
    }
    return true;
  }
}
