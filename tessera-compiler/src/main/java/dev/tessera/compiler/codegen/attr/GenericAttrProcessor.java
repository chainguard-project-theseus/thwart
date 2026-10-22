package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.codegen.TemplateGenerator;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.html.Contexts;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.runtime.TrustedUrl;
import java.util.Locale;
import java.util.Set;

/**
 * Any other {@code t:<name>} (t:href, t:src, t:value, t:id, t:class, t:title, t:data-x, ...): sets attribute {@code name} from the expression, escaped for {@code Contexts.attribute(element, name)}. Boolean attributes ({@code checked, selected, disabled, readonly, required, multiple, hidden, ...}) take a condition. EVENT_HANDLER, STYLE and FORBIDDEN contexts are build errors. RESOURCE_URL accepts only a literal-path link expression or a {@code TrustedUrl}.
 */
public final class GenericAttrProcessor implements AttributeProcessor {

  /** HTML boolean attributes (Thymeleaf's list): rendered as {@code name="name"} or omitted. */
  static final Set<String> BOOLEAN_ATTRIBUTES =
      Set.of(
          "async", "autofocus", "autoplay", "checked", "controls", "declare", "default", "defer",
          "disabled", "formnovalidate", "hidden", "inert", "ismap", "itemscope", "loop", "multiple",
          "muted", "nomodule", "novalidate", "nowrap", "open", "playsinline", "pubdate", "readonly",
          "required", "reversed", "scoped", "seamless", "selected");

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    Expr value = ctx.parse(attr);
    if (value != null) {
      set(ctx, plan, attr.localName(), value, attr.pos(), attr.name());
    }
  }

  /**
   * Sets output attribute {@code name} on {@code plan} from {@code value}, or reports why it
   * cannot. Shared with {@code t:attr}; {@code label} names the source construct in errors.
   */
  static void set(CodegenContext ctx, ElementPlan plan, String name, Expr value, SourcePos pos, String label) {
    String element = plan.element().name();
    String lower = name.toLowerCase(Locale.ROOT);
    if (element.equalsIgnoreCase("script") && lower.equals("type")) {
      ctx.diagnostics()
          .error(pos, label + ": the type of a <script> cannot be computed; its content type must be known at"
              + " build time, so write a static type attribute");
      return;
    }
    if (element.equalsIgnoreCase("meta") && lower.equals("http-equiv")) {
      ctx.diagnostics()
          .error(pos, label + ": the http-equiv of a <meta> cannot be computed; it decides whether content is"
              + " a refresh URL, so write a static http-equiv attribute");
      return;
    }
    OutputContext context = Contexts.attribute(plan.element(), name);
    String hint =
        switch (context) {
          case EVENT_HANDLER ->
              "event-handler attributes cannot contain expressions; put the value in a data-* attribute and"
                  + " read it from a script";
          case STYLE -> "style attributes cannot contain expressions; use t:class or t:classappend instead";
          case FORBIDDEN ->
              lower.equals("srcdoc")
                  ? "srcdoc is a whole HTML document and cannot contain expressions; use src with a URL"
                  : lower.equals("content")
                  ? "the content of <meta http-equiv=\"refresh\"> navigates the page and cannot contain"
                      + " expressions; redirect from the controller instead"
                  : "attribute name '" + name + "' is not allowed; names must match [A-Za-z_][A-Za-z0-9_.:-]*";
          default -> null;
        };
    if (hint != null) {
      ctx.diagnostics().error(pos, label + " is not allowed: " + hint);
      return;
    }
    if (context == OutputContext.ATTRIBUTE && BOOLEAN_ATTRIBUTES.contains(lower)) {
      CompiledExpr cond = ctx.expressions().compileCondition(value, plan.scope());
      if (cond != null) {
        String code = "((" + cond.code() + ") ? " + TemplateGenerator.javaString(name) + " : null)";
        plan.setAttribute(name, code, context, pos);
      }
      return;
    }
    CompiledExpr c = ctx.expressions().compile(value, plan.scope());
    if (c == null || !ctx.expressions().checkOutputType(c.type(), value.pos())) {
      return;
    }
    if (context == OutputContext.RESOURCE_URL
        && !(value instanceof Expr.Link)
        && !c.type().toString().equals(TrustedUrl.class.getName())) {
      ctx.diagnostics()
          .error(pos, label + " on <" + element + "> loads code or a document: use a literal-path link such as"
              + " @{/js/app.js} or a TrustedUrl value, not a " + c.type());
      return;
    }
    plan.setAttribute(name, c.code(), context, pos);
  }
}
