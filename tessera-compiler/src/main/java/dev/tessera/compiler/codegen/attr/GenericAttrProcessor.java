package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.types.ExpressionCompiler.isDeclared;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.expr.Assignment;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.html.Contexts;
import dev.tessera.compiler.html.HtmlParser;
import dev.tessera.compiler.links.LinkCompiler;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.runtime.CssValue;
import dev.tessera.runtime.Escapers;
import dev.tessera.runtime.TrustedUrl;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Any other {@code t:<name>} ({@code t:href}, {@code t:id}, {@code t:data-x}, ...): sets attribute
 * {@code name} from the expression, escaped for {@link Contexts#attribute}, whose contexts
 * ({@link OutputContext}) say what each attribute accepts. This processor adds: boolean attributes
 * ({@code checked}, {@code disabled}, ...) take a condition; a NEUTRAL name (#584) takes only a
 * framework-neutral value ({@link #neutralSafe}); a {@code <base href>} (#350), the
 * {@code action} of a static POST form and every
 * {@code formaction} (they may receive the CSRF token, {@link CsrfInjector}) take only a
 * same-origin link; the {@code action} of any other form a same-origin link or a {@code TrustedUrl}
 * (client code may submit it and swap the response in, #585); and a computed {@code formmethod},
 * or {@code method} on a {@code <form>}, is a build error.
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
    String lower = HtmlParser.asciiLower(name);
    OutputContext context = Contexts.attribute(plan.element(), name);
    String hint;
    switch (context) {
      case NEUTRAL:
        if (neutralSafe(ctx, value, plan, label, name)) {
          plan.setAttribute(name, neutralCode(ctx, value, plan), pos);
        }
        return;
      case EVENT_HANDLER:
        hint = "event-handler attributes cannot contain expressions; put the value in a data-* attribute (a number,"
            + " a boolean or a same-origin path) or a <script type=\"application/json\"> block and read it from a script";
        break;
      case TYPED_ATTRIBUTE:
        if (!BOOLEAN_ATTRIBUTES.contains(lower)) {
          style(ctx, plan, name, value, pos, label, context);
          return;
        }
        hint = null;
        break;
      case STYLE:
      case TRUSTED_URL:
        style(ctx, plan, name, value, pos, label, context);
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
                : Contexts.isLegacyHtmxConnect(name)
                ? Contexts.LEGACY_HTMX_CONNECT
                : Contexts.isTreeBuilderInput(plan.element(), name)
                ? treeBuilderInput(name, lower)
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
    if ((context == OutputContext.ATTRIBUTE || context == OutputContext.TYPED_ATTRIBUTE)
        && BOOLEAN_ATTRIBUTES.contains(lower)) {
      CompiledExpr cond = ctx.expressions().compileCondition(value, plan.scope());
      if (cond != null) {
        String code = "((" + cond.code() + ") ? " + JavaStrings.literal(name) + " : null)";
        plan.setAttribute(name, code, pos);
      }
      return;
    }
    if (lower.equals("href") && HtmlParser.asciiEqualsIgnoreCase(element, "base")) {
      // #350: every relative URL on the page, a token-bearing form's action included, resolves against it.
      sameOriginUrl(ctx, plan, name, value, pos, label, CsrfInjector.BASE_MOVES_URLS, "", "a <base href>");
      return;
    }
    boolean form = HtmlParser.asciiEqualsIgnoreCase(element, "form");
    if (form && lower.equals("method")) {
      ctx.diagnostics().error(pos, label + " is not allowed: " + CsrfInjector.COMPUTED_METHOD);
      return;
    }
    if (form && lower.equals("action") && CsrfInjector.isStaticPost(plan.element())) {
      // The CSRF token goes wherever the form posts (#285).
      sameOriginUrl(ctx, plan, name, value, pos, label, CsrfInjector.POST_ACTION, CsrfInjector.POST_ACTION_ELSE,
          "the action of a POST form, which gets the CSRF token,");
      return;
    }
    if (form && lower.equals("action")) {
      // #585: client code may submit any form and swap the response into the page as HTML.
      if (!(value instanceof Expr.Link)) {
        CompiledExpr c = ctx.expressions().compileOutput(value, plan.scope());
        if (c == null) {
          return;
        }
        if (isDeclared(c.type(), TrustedUrl.class.getName())) {
          plan.setAttribute(name, c.code(), pos);
          return;
        }
      }
      sameOriginUrl(ctx, plan, name, value, pos, label, FORM_ACTION, FORM_ACTION_ELSE, "the action of a form");
      return;
    }
    if (lower.equals("formaction")) {
      // #314: the token goes wherever the button submits the form, and any form may get it.
      sameOriginUrl(ctx, plan, name, value, pos, label, CsrfInjector.IN_TOKEN_FORM, "",
          "a formaction, which may receive the CSRF token,");
      return;
    }
    if (lower.equals("formmethod")) {
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
    plan.setAttribute(name, c.code(), pos);
  }

  /** The recipes for a value that is not framework-neutral (#584). */
  private static final String DATA_FIX_IT =
      "put text in the element text or a standard inert attribute such as title, send request values as"
          + " hidden inputs (with hx-include), or put data in a <script type=\"application/json\"> block and read it"
          + " from your script";

  /**
   * Whether {@code e} is framework-neutral for NEUTRAL attribute {@code name} (#583 rule 3, #584), by
   * static type and expression shape only: a literal, an expression of a number type (an exact-class
   * check and plain decimal at render time, {@link Escapers#neutralNumber}) or a boolean type, a
   * {@link #neutralLink neutral link}, or a choice ({@code c ? a : b}, {@code a ?: b}) between those.
   * Reports why not at the offending piece. Static values are not checked: the author wrote them.
   */
  private static boolean neutralSafe(CodegenContext ctx, Expr e, ElementPlan plan, String label, String name) {
    if (e instanceof Expr.Literal) {
      return true;
    }
    if (e instanceof Expr.Conditional) {
      Expr.Conditional c = (Expr.Conditional) e;
      boolean ok = ctx.expressions().compileCondition(c.condition(), plan.scope()) != null;
      ok &= neutralSafe(ctx, c.then(), plan, label, name);
      return ok & (c.otherwise() == null || neutralSafe(ctx, c.otherwise(), plan, label, name));
    }
    if (e instanceof Expr.Elvis) {
      Expr.Elvis el = (Expr.Elvis) e;
      return neutralSafe(ctx, el.value(), plan, label, name) & neutralSafe(ctx, el.fallback(), plan, label, name);
    }
    if (e instanceof Expr.Link) {
      return neutralLink(ctx, (Expr.Link) e, plan, label, name);
    }
    CompiledExpr c = ctx.expressions().compile(e, plan.scope());
    if (c == null) {
      return false;
    }
    if (isNumber(c.type()) || isBoolean(c.type())) {
      return true;
    }
    String type = c.type().getKind() == TypeKind.NULL ? "null" : JavaStrings.typeName(c.type());
    ctx.diagnostics()
        .error(e.pos(), label + " is not allowed: '" + name + "' is not a standard HTML attribute, and client code"
            + " may run what it reads there, so a computed value must be framework-neutral: a number, a"
            + " boolean, a same-origin link such as @{/items/{id}(id=${item.id})}, or a choice between those and"
            + " literals, not a " + type + "; " + DATA_FIX_IT);
    return false;
  }

  /** What a neutral link's literal path may hold, with each {@code {var}} as one letter (#583's research). */
  private static final Pattern NEUTRAL_PATH = Pattern.compile("/(?!/)([A-Za-z0-9._~/-]|%[0-9A-Fa-f]{2})*");

  /**
   * Whether {@code link} is a neutral path (#584): its literal path starts with a single {@code /},
   * holds only {@code A-Z a-z 0-9 . _ ~ / -} and {@code %XX}, and every parameter fills a path
   * variable, so there is no query or fragment. {@link Escapers#neutralPath} encodes the built URL
   * the same way (path variables come from data) and re-checks it at render time.
   */
  private static boolean neutralLink(CodegenContext ctx, Expr.Link link, ElementPlan plan, String label, String name) {
    String path = link.path();
    List<String> vars = LinkCompiler.placeholders(path);
    String shape = path;
    for (String v : vars) {
      shape = shape.replace("{" + v + "}", "x");
    }
    if (!LinkCompiler.isSameOriginPath(path) || !NEUTRAL_PATH.matcher(shape).matches()) {
      ctx.diagnostics()
          .error(link.pos(), label + " is not allowed: a link in '" + name + "' must be a same-origin path that starts"
              + " with a single '/' and holds only letters, digits, . _ ~ / - and %XX, not @{" + path + "}");
      return false;
    }
    for (Assignment p : link.params()) {
      if (!vars.contains(p.name())) {
        ctx.diagnostics()
            .error(p.pos(), label + " is not allowed: a link in '" + name + "' cannot have a query; parameter '"
                + p.name() + "' of @{" + path + "} fills no path variable. Send request values as hidden inputs"
                + " (with hx-include), or put them in a <script type=\"application/json\"> block");
        return false;
      }
    }
    return ctx.expressions().compile(link, plan.scope()) != null;
  }

  /**
   * The code of a value {@link #neutralSafe} accepted: each number piece goes through
   * {@link Escapers#neutralNumber} and each link through {@link Escapers#neutralPath}; literals and
   * booleans are written as compiled. Both operands of an elvis are evaluated, as in the compiler's
   * {@code ?:}.
   */
  private static String neutralCode(CodegenContext ctx, Expr e, ElementPlan plan) {
    if (e instanceof Expr.Literal) {
      return code(ctx, e, plan);
    }
    if (e instanceof Expr.Conditional) {
      Expr.Conditional c = (Expr.Conditional) e;
      return "(" + ctx.expressions().compileCondition(c.condition(), plan.scope()).code()
          + " ? (java.lang.Object) " + neutralCode(ctx, c.then(), plan) + " : (java.lang.Object) "
          + (c.otherwise() == null ? "null" : neutralCode(ctx, c.otherwise(), plan)) + ")";
    }
    if (e instanceof Expr.Elvis) {
      Expr.Elvis el = (Expr.Elvis) e;
      return "dev.tessera.runtime.Values.<java.lang.Object>elvis((java.lang.Object) " + neutralCode(ctx, el.value(), plan)
          + ", (java.lang.Object) " + neutralCode(ctx, el.fallback(), plan) + ")";
    }
    String whole = code(ctx, e, plan);
    if (e instanceof Expr.Link) {
      return Escapers.class.getName() + ".neutralPath(" + whole + ")";
    }
    return isNumber(type(ctx, e, plan)) ? Escapers.class.getName() + ".neutralNumber(" + whole + ")" : whole;
  }

  private static boolean isBoolean(TypeMirror t) {
    return t.getKind() == TypeKind.BOOLEAN || isDeclared(t, "java.lang.Boolean");
  }

  /** Why attribute {@code name}, which the HTML tree builder reads ({@link Contexts#isTreeBuilderInput}), cannot be computed. */
  private static String treeBuilderInput(String name, String lower) {
    String computed = "a computed '" + name + "' ";
    if (lower.equals("is")) {
      return computed + "decides which custom element a browser creates; write it as a static value";
    }
    if (lower.startsWith("xmlns")) {
      return computed + "must name the element's namespace, which a browser checks while it parses; write it"
          + " as a static value, or omit it";
    }
    if (lower.startsWith("shadowroot") || lower.equals("for")) {
      return computed + "on a <template> decides whether a browser attaches its content as a shadow root, or"
          + " where it moves it; write it as a static value";
    }
    if (lower.equals("content")) {
      return computed + "on a <meta http-equiv=\"Content-Type\"> sets the encoding a browser reads the page in;"
          + " write it as a static value, or set the encoding in the Content-Type response header";
    }
    return computed + "on a <meta> sets the encoding a browser reads the page in; write it as a static value,"
        + " such as <meta charset=\"utf-8\">";
  }

  private static final Pattern REFRESH_PREFIX =
      Pattern.compile("[\\t\\n\\f\\r ]*([0-9]+)[\\t\\n\\f\\r ]*[;,][\\t\\n\\f\\r ]*url[\\t\\n\\f\\r ]*=[\\t\\n\\f\\r ]*",
          Pattern.CASE_INSENSITIVE);

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
   * A URL that must stay on the page's origin: a {@code <base href>} (#350), the {@code action} of a
   * POST form or a {@code formaction}, which may receive the CSRF token (#285, #314), and a link
   * {@code action} of any other form (#585). Only a link whose literal path is same-origin
   * ({@link LinkCompiler#isSameOriginPath}) compiles, re-checked at render time ({@link #sameOrigin}).
   * {@code why} starts the error for a non-link value and {@code orElse} ends it; {@code what} names
   * the URL in the error for a link that is not same-origin.
   */
  private static void sameOriginUrl(
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

  /** Starts the reason for the computed action error on a form that is not a static POST one (#585). */
  private static final String FORM_ACTION =
      "a form's computed action must stay on the page's origin, since client code may submit the form and swap"
          + " the response into the page as HTML";

  /** Ends it. */
  private static final String FORM_ACTION_ELSE = ", or a TrustedUrl built in Java if another site is meant";

  /**
   * Boxed and big number types whose text is only digits, sign, '.', 'E', NaN or Infinity.
   * BigDecimal and BigInteger are not final, so {@link #styleCode} wraps them in
   * {@link Escapers#styleNumber}, which accepts only the exact JDK class at render time (#540).
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
   *
   * <p>A TYPED_ATTRIBUTE (#539, #565) is checked the same way but takes no safe type: only literals
   * and numbers. A TRUSTED_URL (#539) takes {@code TrustedUrl} as its safe type instead of
   * {@code CssValue}. {@code context} is the attribute's context: one of those or STYLE.
   */
  private static void style(
      CodegenContext ctx, ElementPlan plan, String name, Expr value, SourcePos pos, String label, OutputContext context) {
    if (!styleSafe(ctx, value, plan, label, name, context)) {
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

  private static boolean styleSafe(CodegenContext ctx, Expr e, ElementPlan plan, String label, String name, OutputContext context) {
    if (e instanceof Expr.Literal) {
      return true;
    }
    if (e instanceof Expr.Interpolation) {
      boolean ok = true;
      for (Expr part : ((Expr.Interpolation) e).parts()) {
        ok &= styleSafe(ctx, part, plan, label, name, context);
      }
      return ok;
    }
    if (e instanceof Expr.Binary && ((Expr.Binary) e).op() == Expr.BinaryOp.ADD) {
      Expr.Binary b = (Expr.Binary) e;
      return styleSafe(ctx, b.left(), plan, label, name, context) & styleSafe(ctx, b.right(), plan, label, name, context);
    }
    if (e instanceof Expr.Conditional) {
      Expr.Conditional c = (Expr.Conditional) e;
      // The condition is not output. A missing else branch ("c ? a") renders nothing.
      return styleSafe(ctx, c.then(), plan, label, name, context)
          & (c.otherwise() == null || styleSafe(ctx, c.otherwise(), plan, label, name, context));
    }
    if (e instanceof Expr.Elvis) {
      Expr.Elvis el = (Expr.Elvis) e;
      return styleSafe(ctx, el.value(), plan, label, name, context) & styleSafe(ctx, el.fallback(), plan, label, name, context);
    }
    CompiledExpr c = ctx.expressions().compile(e, plan.scope());
    if (c == null) {
      return false;
    }
    TypeMirror t = c.type();
    Class<?> safe =
        context == OutputContext.STYLE ? CssValue.class : context == OutputContext.TRUSTED_URL ? TrustedUrl.class : null;
    if (isNumber(t) || safe != null && isDeclared(t, safe.getName())) {
      return true;
    }
    String type = t.getKind() == TypeKind.NULL ? "null" : t.toString();
    if (context == OutputContext.TRUSTED_URL) {
      ctx.diagnostics()
          .error(
              e.pos(),
              label + " is not allowed: '" + name + "' on <" + plan.element().name() + "> holds URLs but is not"
                  + " exactly one URL (a list, or an animation value whose meaning depends on attributeName), so a"
                  + " computed value can only combine string literals, numbers and TrustedUrls, not a " + type
                  + "; write a static attribute, choose between literals such as ${c} ? 'a' : 'b', or build the"
                  + " whole value as a TrustedUrl in Java");
      return false;
    }
    if (context == OutputContext.TYPED_ATTRIBUTE) {
      ctx.diagnostics()
          .error(
              e.pos(),
              label + " is not allowed: '" + name + "' is not an inert-text attribute on <" + plan.element().name()
                  + ">, so a computed value can only combine string literals and numbers, not a "
                  + type + "; write a static attribute, choose between literals such as ${c} ? 'a' : 'b', or"
                  + " if a script reads it, put the data in a <script type=\"application/json\"> block");
      return false;
    }
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
