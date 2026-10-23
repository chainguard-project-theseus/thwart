package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.types.ExpressionCompiler.isDeclared;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.html.Contexts;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.runtime.CssValue;
import dev.tessera.runtime.TrustedUrl;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Any other {@code t:<name>} (t:href, t:src, t:value, t:id, t:class, t:title, t:data-x, ...): sets attribute {@code name} from the expression, escaped for {@code Contexts.attribute(element, name)}. Boolean attributes ({@code checked, selected, disabled, readonly, required, multiple, hidden, ...}) take a condition. EVENT_HANDLER (including framework directives such as {@code v-on:click}) and FORBIDDEN contexts are build errors. STYLE takes only {@link #style safe style values}. REFRESH takes only {@link #refresh a delay and a same-origin link}. RESOURCE_URL accepts only a literal-path link expression or a {@code TrustedUrl}.
 */
public final class GenericAttrProcessor implements AttributeProcessor {

  /** HTML boolean attributes (Thymeleaf's list): rendered as {@code name="name"} or omitted. */
  private static final Set<String> BOOLEAN_ATTRIBUTES =
      Backports.setOf(
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
    OutputContext context = Contexts.attribute(plan.element(), name);
    String hint;
    switch (context) {
      case EVENT_HANDLER:
        hint =
            Contexts.isFrameworkDirective(plan.element(), name)
                ? "'" + name + "' is a Vue, Alpine, htmx, AngularJS or Knockout directive whose value is"
                    + " run as JavaScript, so it cannot contain expressions; write it as a static attribute,"
                    + " or put the value in a data-* attribute or a <script> with [[...]] and read it from there"
                : "event-handler attributes cannot contain expressions; put the value in a data-* attribute and"
                    + " read it from a script";
        break;
      case STYLE:
        style(ctx, plan, name, value, pos, label);
        return;
      case REFRESH:
        refresh(ctx, plan, name, value, pos, label);
        return;
      case FORBIDDEN:
        hint =
            lower.equals("type")
                ? "the type of a <script> cannot be computed; its content type must be known at"
                    + " build time, so write a static type attribute"
                : lower.equals("http-equiv")
                ? "the http-equiv of a <meta> cannot be computed; it decides whether content is"
                    + " a refresh URL, so write a static http-equiv attribute"
                : lower.equals("srcdoc")
                ? "srcdoc is a whole HTML document and cannot contain expressions; use src with a URL"
                : "attribute name '" + name + "' is not allowed; names must match [A-Za-z_][A-Za-z0-9_.:-]*";
        break;
      default:
        hint = null;
    }
    if (hint != null) {
      boolean computedType = context == OutputContext.FORBIDDEN
          && (lower.equals("type") || lower.equals("http-equiv"));
      ctx.diagnostics().error(pos, label + (computedType ? ": " : " is not allowed: ") + hint);
      return;
    }
    if (context == OutputContext.ATTRIBUTE && BOOLEAN_ATTRIBUTES.contains(lower)) {
      CompiledExpr cond = ctx.expressions().compileCondition(value, plan.scope());
      if (cond != null) {
        String code = "((" + cond.code() + ") ? " + JavaStrings.literal(name) + " : null)";
        plan.setAttribute(name, code, pos);
      }
      return;
    }
    CompiledExpr c = ctx.expressions().compileOutput(value, plan.scope());
    if (c == null) {
      return;
    }
    if (context == OutputContext.RESOURCE_URL
        && !(value instanceof Expr.Link)
        && !isDeclared(c.type(), TrustedUrl.class.getName())) {
      ctx.diagnostics()
          .error(pos, label + " on <" + element + "> loads code or a document: use a literal-path link such as"
              + " @{/js/app.js} or a TrustedUrl value, not a " + c.type());
      return;
    }
    plan.setAttribute(name, c.code(), pos);
  }

  private static final Pattern REFRESH_PREFIX =
      Pattern.compile("\\s*([0-9]+)\\s*[;,]\\s*url\\s*=\\s*", Pattern.CASE_INSENSITIVE);

  /**
   * The {@code content} of a {@code <meta http-equiv="refresh">} (#237): only
   * {@code '<seconds>;url=' + @{/path...}}, where the link path is a literal starting with a single
   * {@code /}, so the page can only refresh to the same origin. Path variables and parameters are
   * percent-encoded by the link, and {@code Escapers.refresh} encodes what would end the URL.
   * The output is always {@code <seconds>;url=<url>}.
   */
  private static void refresh(CodegenContext ctx, ElementPlan plan, String name, Expr value, SourcePos pos, String label) {
    Expr.Binary b = value instanceof Expr.Binary ? (Expr.Binary) value : null;
    Matcher prefix = null;
    if (b != null && b.op() == Expr.BinaryOp.ADD && b.left() instanceof Expr.Literal
        && ((Expr.Literal) b.left()).value() instanceof String && b.right() instanceof Expr.Link) {
      prefix = REFRESH_PREFIX.matcher((String) ((Expr.Literal) b.left()).value());
    }
    if (prefix == null || !prefix.matches()) {
      ctx.diagnostics()
          .error(pos, label + " is not allowed: the content of <meta http-equiv=\"refresh\"> navigates the page;"
              + " write a delay and a same-origin link such as '3;url=' + @{/items/{id}(id=${item.id})},"
              + " or redirect from the controller");
      return;
    }
    Expr.Link link = (Expr.Link) b.right();
    String path = link.path();
    if (!path.startsWith("/") || path.startsWith("//") || path.startsWith("/\\")) {
      ctx.diagnostics()
          .error(link.pos(), label + " is not allowed: a refresh URL must be a same-origin path starting with a"
              + " single '/', not @{" + path + "}");
      return;
    }
    CompiledExpr c = ctx.expressions().compileOutput(link, plan.scope());
    if (c != null) {
      plan.setAttribute(name, JavaStrings.literal(prefix.group(1) + ";url=") + " + " + c.code(), pos);
    }
  }

  /** Boxed and big number types whose text is only digits, sign, '.', 'E', NaN or Infinity. */
  private static final Set<String> NUMBER_TYPES =
      Backports.setOf(
          "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.lang.Float",
          "java.lang.Double", "java.math.BigDecimal", "java.math.BigInteger");

  /**
   * A {@code style} attribute (#182). CSS cannot be escaped, so the value is built only from
   * pieces that cannot change the declaration structure: literals (written by the template
   * author), numbers and {@link CssValue}s, joined by {@code +}, {@code |...|}, {@code c ? a : b} and
   * {@code a ?: b}. Any other piece, such as a String expression, is a build error. The
   * result is then attribute-escaped like any attribute.
   */
  private static void style(CodegenContext ctx, ElementPlan plan, String name, Expr value, SourcePos pos, String label) {
    if (!styleSafe(ctx, value, plan, label)) {
      return;
    }
    CompiledExpr c = ctx.expressions().compileOutput(value, plan.scope());
    if (c != null) {
      plan.setAttribute(name, c.code(), pos);
    }
  }

  private static boolean styleSafe(CodegenContext ctx, Expr e, ElementPlan plan, String label) {
    if (e instanceof Expr.Literal) {
      return true;
    }
    if (e instanceof Expr.Interpolation) {
      boolean ok = true;
      for (Expr part : ((Expr.Interpolation) e).parts()) {
        ok &= styleSafe(ctx, part, plan, label);
      }
      return ok;
    }
    if (e instanceof Expr.Binary && ((Expr.Binary) e).op() == Expr.BinaryOp.ADD) {
      Expr.Binary b = (Expr.Binary) e;
      return styleSafe(ctx, b.left(), plan, label) & styleSafe(ctx, b.right(), plan, label);
    }
    if (e instanceof Expr.Conditional) {
      Expr.Conditional c = (Expr.Conditional) e;
      // The condition is not output. A missing else branch ("c ? a") renders nothing.
      return styleSafe(ctx, c.then(), plan, label)
          & (c.otherwise() == null || styleSafe(ctx, c.otherwise(), plan, label));
    }
    if (e instanceof Expr.Elvis) {
      Expr.Elvis el = (Expr.Elvis) e;
      return styleSafe(ctx, el.value(), plan, label) & styleSafe(ctx, el.fallback(), plan, label);
    }
    CompiledExpr c = ctx.expressions().compile(e, plan.scope());
    if (c == null) {
      return false;
    }
    TypeMirror t = c.type();
    if (isDeclared(t, CssValue.class.getName()) || isNumber(t)) {
      return true;
    }
    String type = t.getKind() == TypeKind.NULL ? "null" : t.toString();
    ctx.diagnostics()
        .error(
            e.pos(),
            label + " is not allowed: a style value can only combine string literals, numbers and CssValues"
                + " such as css.color(c), css.length(l) or css.url(u), not a " + type
                + "; use t:class or t:classappend instead");
    return false;
  }

  private static boolean isNumber(TypeMirror t) {
    switch (t.getKind()) {
      case BYTE:
      case SHORT:
      case INT:
      case LONG:
      case FLOAT:
      case DOUBLE:
        return true;
      case DECLARED:
        for (String n : NUMBER_TYPES) {
          if (isDeclared(t, n)) {
            return true;
          }
        }
        return false;
      default:
        return false;
    }
  }
}
