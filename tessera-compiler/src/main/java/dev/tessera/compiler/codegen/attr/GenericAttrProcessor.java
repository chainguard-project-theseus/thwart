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
import dev.tessera.compiler.links.LinkCompiler;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.runtime.CssValue;
import dev.tessera.runtime.Escapers;
import dev.tessera.runtime.TrustedUrl;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Any other {@code t:<name>} (t:href, t:src, t:value, t:id, t:class, t:title, t:data-x, ...): sets attribute {@code name} from the expression, escaped for {@code Contexts.attribute(element, name)}. Boolean attributes ({@code checked, selected, disabled, readonly, required, multiple, hidden, ...}) take a condition. EVENT_HANDLER (including framework directives such as {@code v-on:click}) and FORBIDDEN contexts are build errors. A Vue/Alpine binding with an ordinary target ({@code :prop}, SCRIPT_ATTRIBUTE) takes only a JSON-serializable value, written as JSON. STYLE takes only {@link #style safe style values}. REFRESH takes only {@link #refresh a delay and a same-origin link}. RESOURCE_URL accepts only a literal-path link expression or a {@code TrustedUrl}; {@link #TRUSTED_URL_LISTS ping, srcset and imagesrcset} only a {@code TrustedUrl}; a {@code <base href>} (#350), an htmx request URL ({@code hx-get}, ...), the {@code action}/{@code formaction} of a form htmx submits ({@link CodegenContext#htmxSubmits}), and a {@code formaction} that may receive the CSRF token ({@link CodegenContext#mayPostCsrfToken}), only a same-origin link; there a computed {@code formmethod} is a build error. A name that is not a standard HTML, SVG, MathML or ARIA attribute, {@code data-*}, a plain prop on a custom element or a framework name Contexts classifies is a build error ({@link Contexts#isUnknownName}, #330).
 */
public final class GenericAttrProcessor implements AttributeProcessor {

  /** HTML boolean attributes (Thymeleaf's list): rendered as {@code name="name"} or omitted. */
  private static final Set<String> BOOLEAN_ATTRIBUTES =
      Backports.setOf(
          "async", "autofocus", "autoplay", "checked", "controls", "declare", "default", "defer",
          "disabled", "formnovalidate", "hidden", "inert", "ismap", "itemscope", "loop", "multiple",
          "muted", "nomodule", "novalidate", "nowrap", "open", "playsinline", "pubdate", "readonly",
          "required", "reversed", "scoped", "seamless", "selected");

  /**
   * URL attributes that hold a list of URLs, which Tessera does not split and check one by one, so
   * a computed value must be a {@code TrustedUrl}: {@code ping} is a whitespace-separated list, and
   * {@code srcset} and {@code imagesrcset} are comma-separated image candidates.
   */
  private static final Set<String> TRUSTED_URL_LISTS = Backports.setOf("ping", "srcset", "imagesrcset");

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
    if (Contexts.isUnknownName(plan.element(), name)) {
      ctx.diagnostics().error(pos, label + " is not allowed: " + unknownName(name));
      return;
    }
    OutputContext context = Contexts.attribute(plan.element(), name);
    String hint;
    switch (context) {
      case SCRIPT_ATTRIBUTE:
        jsonBinding(ctx, plan, name, value, pos, label);
        return;
      case EVENT_HANDLER:
        hint =
            isBind(lower)
                ? "'" + name + "' is a Vue or Alpine binding whose value is run as JavaScript; an expression"
                    + " (written as JSON) is only allowed in :x, v-bind:x or x-bind:x whose target x is an"
                    + " ordinary attribute, with no modifiers, not on* / style / a URL or URL part / innerHTML / is, and not"
                    + " on <script>, <style> or <meta>; otherwise write it as a static attribute"
                : Contexts.isFrameworkDirective(plan.element(), name)
                ? "'" + name + "' is a Vue, Alpine, htmx, _hyperscript, AngularJS or Knockout directive whose value is"
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
                : lower.equals("attributename")
                ? "the attributeName of an SVG animation cannot be computed; it decides whether values, to,"
                    + " from and by are CSS or a URL, so write a static attributeName attribute"
                : Contexts.isHtmxBoost(name)
                ? "'" + name + "' cannot be computed; it decides whether htmx submits the forms inside with AJAX"
                    + " and swaps the response into the page, so write " + name + "=\"true\" or " + name
                    + "=\"false\" statically"
                : Contexts.isLegacyHtmxConnect(name)
                ? "htmx 1.x '" + name + "' holds connect:<url>, a URL htmx connects to and swaps into the page as"
                    + " HTML, so it cannot be computed; write it as a static attribute, or use the sse/ws"
                    + " extension's sse-connect/ws-connect with a same-origin link such as @{/events}"
                : lower.equals("srcdoc")
                ? "srcdoc is a whole HTML document and cannot contain expressions; use src with a URL"
                : "attribute name '" + name + "' is not allowed; names must match [A-Za-z_][A-Za-z0-9_.:-]*";
        break;
      default:
        hint = null;
    }
    if (hint != null) {
      boolean computedType = context == OutputContext.FORBIDDEN
          && (lower.equals("type") || lower.equals("http-equiv") || lower.equals("attributename"));
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
    if (lower.equals("href") && element.equalsIgnoreCase("base")) {
      // #350: every relative URL on the page, a token-bearing form's action included, resolves against it.
      htmxRequestUrl(ctx, plan, name, value, pos, label, CsrfInjector.BASE_MOVES_URLS, "", "a <base href>");
      return;
    }
    if (context == OutputContext.RESOURCE_URL && Contexts.isHtmxRequestUrl(name)) {
      htmxRequestUrl(ctx, plan, name, value, pos, label,
          "htmx requests this URL and swaps the response into the page as HTML", "", HTMX_URL);
      return;
    }
    if (context == OutputContext.URL
        && (lower.equals("formaction") || (lower.equals("action") && element.equalsIgnoreCase("form")))
        && ctx.htmxSubmits(plan.element())) {
      htmxRequestUrl(ctx, plan, name, value, pos, label,
          "inside hx-boost or an htmx form, htmx submits to this URL and swaps the response into the page as HTML",
          ", or put hx-boost=\"false\" on the form to submit it natively", HTMX_URL);
      return;
    }
    if (lower.equals("formaction") && ctx.mayPostCsrfToken(plan.element())) {
      // #314: the token goes wherever the button submits the form.
      htmxRequestUrl(ctx, plan, name, value, pos, label, CsrfInjector.IN_TOKEN_FORM,
          ", or move the button into its own form", "a formaction that may receive the CSRF token");
      return;
    }
    if (lower.equals("formmethod") && ctx.mayPostCsrfToken(plan.element())) {
      ctx.diagnostics().error(pos, label + " is not allowed: " + CsrfInjector.GET_TOKEN);
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
    if (TRUSTED_URL_LISTS.contains(lower) && !isDeclared(c.type(), TrustedUrl.class.getName())) {
      ctx.diagnostics()
          .error(pos, label + " on <" + element + "> is a list of URLs that Tessera does not check one by one:"
              + " use a TrustedUrl value or a static attribute, not a " + c.type());
      return;
    }
    plan.setAttribute(name, c.code(), pos);
  }

  /** Why {@code name}, which is not {@link Contexts#isKnownName known} (#330), cannot be computed. */
  static String unknownName(String name) {
    String data = name.toLowerCase(Locale.ROOT);
    return "'" + name + "' is not a known attribute; a computed attribute must be a standard HTML, SVG,"
        + " MathML or ARIA attribute, a data-* attribute, or a plain [a-z][a-z0-9]* prop on a custom"
        + " element. If it is data for your script, use data-" + data + "; if a framework reads it,"
        + " write it as a static attribute";
  }

  private static final Pattern BIND = Pattern.compile("[vx]-bind([:.].*)?");

  /** Whether {@code lower} is a Vue/Alpine binding ({@code :x}, {@code v-bind...}, {@code x-bind...}). */
  private static boolean isBind(String lower) {
    return lower.startsWith(":") || BIND.matcher(lower).matches();
  }

  /**
   * A Vue/Alpine binding such as {@code :prop} (#246; {@link Contexts#isJsonBinding}): the value
   * must be JSON-serializable ({@link ScriptJson}) and is written as JSON, then attribute-escaped
   * ({@code Escapers.jsonAttr}), so the framework evaluates it to the same value and never runs it.
   */
  private static void jsonBinding(CodegenContext ctx, ElementPlan plan, String name, Expr value, SourcePos pos, String label) {
    CompiledExpr c = ctx.expressions().compileOutput(value, plan.scope());
    if (c == null) {
      return;
    }
    String code = ScriptJson.attributeValue(ctx, c, pos, label);
    if (code != null) {
      plan.setAttribute(name, code, pos);
    }
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
    if (!LinkCompiler.isSameOriginPath(path)) {
      ctx.diagnostics()
          .error(link.pos(), label + " is not allowed: a refresh URL must be a same-origin path starting with a"
              + " single '/', not @{" + path + "}");
      return;
    }
    CompiledExpr c = ctx.expressions().compileOutput(link, plan.scope());
    if (c != null) {
      plan.setAttribute(name, JavaStrings.literal(prefix.group(1) + ";url=") + " + " + sameOrigin(c), pos);
    }
  }

  /**
   * An htmx request URL such as {@code hx-get} (#249): htmx fetches it and swaps the response
   * into the page as HTML, and htmx 1.x also sends cross-origin requests, so only a link whose
   * literal path is same-origin ({@link LinkCompiler#isSameOriginPath}) compiles. Path variables and
   * parameters are percent-encoded by the link; the value is written with the resource-URL escaper.
   * Also the {@code action}/{@code formaction} of a boosted or htmx form (#258), written with the URL
   * escaper, and a {@code formaction} that may receive the CSRF token (#314). {@code why} starts the
   * error for a non-link value and {@code orElse} ends it; {@code what} names the URL in the error
   * for a link that is not same-origin.
   */
  private static void htmxRequestUrl(
      CodegenContext ctx, ElementPlan plan, String name, Expr value, SourcePos pos, String label, String why,
      String orElse, String what) {
    if (!(value instanceof Expr.Link)) {
      ctx.diagnostics()
          .error(pos, label + " is not allowed: " + why + "; use a same-origin link such as"
              + " @{/items/{id}(id=${item.id})}" + orElse);
      return;
    }
    Expr.Link link = (Expr.Link) value;
    if (!LinkCompiler.isSameOriginPath(link.path())) {
      ctx.diagnostics()
          .error(link.pos(), label + " is not allowed: " + what + " must be a same-origin path starting"
              + " with a single '/', not @{" + link.path() + "}");
      return;
    }
    CompiledExpr c = ctx.expressions().compileOutput(link, plan.scope());
    if (c != null) {
      plan.setAttribute(name, sameOrigin(c), pos);
    }
  }

  /**
   * The code of a link whose literal path {@link LinkCompiler#isSameOriginPath is same-origin},
   * re-checked at render time (#358): the context path or a custom {@code Links} could still make it
   * protocol-relative.
   */
  private static String sameOrigin(CompiledExpr link) {
    return Escapers.class.getName() + ".sameOriginLink(" + link.code() + ")";
  }

  private static final String HTMX_URL = "an htmx request URL";

  /**
   * Boxed and big number types whose text is only digits, sign, '.', 'E', NaN or Infinity.
   * BigDecimal and BigInteger are not final, so {@link #styleCode} checks their text at render time.
   */
  private static final Set<String> NUMBER_TYPES =
      Backports.setOf(
          "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.lang.Float",
          "java.lang.Double", "java.math.BigDecimal", "java.math.BigInteger");

  /**
   * A {@code style} attribute (#182). CSS cannot be escaped, so the value is built only from
   * pieces that cannot change the declaration structure: literals (written by the template
   * author), numbers and {@link CssValue}s, joined by {@code +}, {@code |...|}, {@code c ? a : b} and
   * {@code a ?: b}. Any other piece, such as a String expression, is a build error. The
   * result is then attribute-escaped like any attribute. A {@code BigDecimal} or {@code BigInteger}
   * piece goes through {@link Escapers#styleNumber}, which fails the render if a subclass's
   * {@code toString()} is not a number.
   */
  private static void style(CodegenContext ctx, ElementPlan plan, String name, Expr value, SourcePos pos, String label) {
    if (!styleSafe(ctx, value, plan, label)) {
      return;
    }
    CompiledExpr c = ctx.expressions().compileOutput(value, plan.scope());
    if (c != null) {
      plan.setAttribute(name, styleCode(ctx, value, plan), pos);
    }
  }

  /**
   * The code of a style value that {@link #styleSafe} accepted: what the expression compiler
   * writes, except that each {@code BigDecimal} or {@code BigInteger} piece is wrapped in
   * {@link Escapers#styleNumber}. A {@code +} with a String operand is concatenation; any other
   * {@code +} is arithmetic on primitives and boxes, so it has no such piece.
   * As in the compiler's {@code ?:}, both operands of an elvis are evaluated, so a hostile fallback
   * fails the render even when the value is not null.
   */
  private static String styleCode(CodegenContext ctx, Expr e, ElementPlan plan) {
    String whole = code(ctx, e, plan);
    if (e instanceof Expr.Literal) {
      return whole;
    }
    if (e instanceof Expr.Interpolation) {
      StringBuilder code = new StringBuilder("(\"\"");
      for (Expr part : ((Expr.Interpolation) e).parts()) {
        code.append(" + ").append(styleCode(ctx, part, plan));
      }
      return code.append(')').toString();
    }
    if (e instanceof Expr.Binary && ((Expr.Binary) e).op() == Expr.BinaryOp.ADD) {
      Expr.Binary b = (Expr.Binary) e;
      return isDeclared(type(ctx, e, plan), "java.lang.String")
          ? "(" + styleCode(ctx, b.left(), plan) + " + " + styleCode(ctx, b.right(), plan) + ")"
          : whole;
    }
    if (e instanceof Expr.Conditional) {
      Expr.Conditional c = (Expr.Conditional) e;
      String then = styleCode(ctx, c.then(), plan);
      String otherwise = c.otherwise() == null ? "null" : styleCode(ctx, c.otherwise(), plan);
      if (then.equals(code(ctx, c.then(), plan))
          && (c.otherwise() == null || otherwise.equals(code(ctx, c.otherwise(), plan)))) {
        return whole;
      }
      return "(" + ctx.expressions().compileCondition(c.condition(), plan.scope()).code()
          + " ? (java.lang.Object) " + then + " : (java.lang.Object) " + otherwise + ")";
    }
    if (e instanceof Expr.Elvis) {
      Expr.Elvis el = (Expr.Elvis) e;
      String value = styleCode(ctx, el.value(), plan);
      String fallback = styleCode(ctx, el.fallback(), plan);
      if (value.equals(code(ctx, el.value(), plan)) && fallback.equals(code(ctx, el.fallback(), plan))) {
        return whole;
      }
      TypeMirror t = type(ctx, el.value(), plan);
      if (t.getKind().isPrimitive()) {
        return value; // never null
      }
      if (t.getKind() == TypeKind.NULL) {
        return fallback;
      }
      return "dev.tessera.runtime.Values.<java.lang.Object>elvis((java.lang.Object) " + value
          + ", (java.lang.Object) " + fallback + ")";
    }
    TypeMirror t = type(ctx, e, plan);
    return isDeclared(t, "java.math.BigDecimal") || isDeclared(t, "java.math.BigInteger")
        ? Escapers.class.getName() + ".styleNumber(" + whole + ")"
        : whole;
  }

  private static String code(CodegenContext ctx, Expr e, ElementPlan plan) {
    return ctx.expressions().compile(e, plan.scope()).code();
  }

  private static TypeMirror type(CodegenContext ctx, Expr e, ElementPlan plan) {
    return ctx.expressions().compile(e, plan.scope()).type();
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
