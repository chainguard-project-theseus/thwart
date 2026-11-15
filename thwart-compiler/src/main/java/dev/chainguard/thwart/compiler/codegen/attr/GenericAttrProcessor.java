package dev.chainguard.thwart.compiler.codegen.attr;

import static dev.chainguard.thwart.compiler.types.ExpressionCompiler.isDeclared;

import dev.chainguard.thwart.compiler.Backports;
import dev.chainguard.thwart.compiler.JavaStrings;
import dev.chainguard.thwart.compiler.SourcePos;
import dev.chainguard.thwart.compiler.codegen.AttributeProcessor;
import dev.chainguard.thwart.compiler.codegen.CodegenContext;
import dev.chainguard.thwart.compiler.codegen.ElementPlan;
import dev.chainguard.thwart.compiler.expr.Assignment;
import dev.chainguard.thwart.compiler.expr.Expr;
import dev.chainguard.thwart.compiler.html.Contexts;
import dev.chainguard.thwart.compiler.html.HtmlParser;
import dev.chainguard.thwart.compiler.links.LinkCompiler;
import dev.chainguard.thwart.compiler.model.Attribute;
import dev.chainguard.thwart.compiler.model.OutputContext;
import dev.chainguard.thwart.compiler.types.CompiledExpr;
import dev.chainguard.thwart.runtime.CssValue;
import dev.chainguard.thwart.runtime.internal.Escapers;
import dev.chainguard.thwart.runtime.internal.Checks;
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
 * ({@code checked}, {@code disabled}, ...) take a condition; a NEUTRAL name takes only a
 * framework-neutral value ({@link #neutralCode}); a {@code <base href>}, the
 * {@code action} of a static POST form and every {@code formaction} (they may receive the CSRF
 * token, {@link CsrfInjector}) take only a same-origin link or a choice between such links;
 * the {@code action} of any other form also only a same-origin link or a choice of them (client code
 * may submit it and swap the response in); and a computed {@code formmethod}, or
 * {@code method} on a {@code <form>}, is a build error.
 */
public final class GenericAttrProcessor implements AttributeProcessor {

  /**
   * The boolean attributes on the computed allow-list ({@code Contexts}): rendered as
   * {@code name="name"} or omitted.
   */
  private static final Set<String> BOOLEAN_ATTRIBUTES =
      Backports.setOf(
          "async", "autofocus", "checked", "defer", "disabled", "formnovalidate", "hidden", "multiple",
          "novalidate", "open", "readonly", "required", "reversed", "selected");

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    Expr value = ctx.parse(attr);
    if (value != null) {
      set(ctx, plan, attr.localName(), value, attr.pos(), attr.name());
    }
  }

  /**
   * Sets output attribute {@code name} on {@code plan} from {@code value}, or reports why it
   * cannot; {@code label} names the source construct in errors.
   */
  private static void set(CodegenContext ctx, ElementPlan plan, String name, Expr value, SourcePos pos, String label) {
    String element = plan.element().name();
    String lower = HtmlParser.asciiLower(name);
    OutputContext context = Contexts.attribute(plan.element(), name);
    String hint;
    switch (context) {
      case NEUTRAL:
        String neutral = neutralCode(ctx, value, plan, label, name);
        if (neutral != null) {
          plan.setAttribute(name, neutral, pos);
        }
        return;
      case EVENT_HANDLER:
        hint = "event-handler attributes cannot contain expressions; put the value in a data-* attribute (a number,"
            + " a boolean or a same-origin path) or a <script type=\"application/json\"> block and read it from a script";
        break;
      case TYPED_ATTRIBUTE:
      case STYLE:
        style(ctx, plan, name, value, pos, label, context);
        return;
      case REFRESH:
        refresh(ctx, plan, name, value, pos, label);
        return;
      case FORBIDDEN:
        hint = Contexts.forbidden(plan.element(), name);
        break;
      default:
        hint = null;
    }
    if (hint != null) {
      ctx.diagnostics().error(pos, label + " is not allowed: " + hint);
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
    if (lower.equals("href") && HtmlParser.asciiEqualsIgnoreCase(element, "base")) {
      // Every relative URL on the page, a token-bearing form's action included, resolves against it.
      sameOriginUrl(ctx, plan, name, value, pos, label, CsrfInjector.BASE_MOVES_URLS, "", "a <base href>");
      return;
    }
    boolean form = HtmlParser.asciiEqualsIgnoreCase(element, "form");
    if (form && lower.equals("method")) {
      ctx.diagnostics().error(pos, label + " is not allowed: " + CsrfInjector.COMPUTED_METHOD);
      return;
    }
    if (form && lower.equals("action") && CsrfInjector.isStaticPost(plan.element())) {
      // The CSRF token goes wherever the form posts.
      sameOriginUrl(ctx, plan, name, value, pos, label, CsrfInjector.POST_ACTION, CsrfInjector.POST_ACTION_ELSE,
          "the action of a POST form, which gets the CSRF token,");
      return;
    }
    if (form && lower.equals("action")) {
      // Client code may submit any form and swap the response into the page as HTML.
      sameOriginUrl(ctx, plan, name, value, pos, label, FORM_ACTION, FORM_ACTION_ELSE, "the action of a form");
      return;
    }
    if (lower.equals("formaction")) {
      // The token goes wherever the button submits the form, and any form may get it.
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
    if (context == OutputContext.RESOURCE_URL && !(value instanceof Expr.Link)) {
      ctx.diagnostics()
          .error(pos, label + " on <" + element + "> loads code or a document: use a literal-path link such as"
              + " @{/js/app.js}, not a " + c.type());
      return;
    }
    if (context == OutputContext.RESOURCE_URL) {
      String code = resourceLink(ctx, (Expr.Link) value, c, label, element);
      if (code != null) {
        plan.setAttribute(name, code, pos);
      }
      return;
    }
    plan.setAttribute(name, c.code(), pos);
  }

  /**
   * The code of a resource-URL link (#751 F2), or null after reporting why not: it loads code or a
   * document, so it must stay on the page's origin (no scheme, no {@code //}) and take no query
   * parameters; data goes in the path, where it is percent-encoded. A rooted path is re-checked at
   * render time, since the context path or a custom {@code Links} could make it protocol-relative.
   */
  private static String resourceLink(CodegenContext ctx, Expr.Link link, CompiledExpr c, String label,
      String element) {
    String what = label + " on <" + element + "> loads code or a document, so ";
    if (!Checks.isSameOriginUrl(link.path())) {
      ctx.diagnostics().error(link.pos(), what + "it must be a same-origin path such as @{/js/app.js}, not @{"
          + link.path() + "}: write a static attribute to load from another origin");
      return null;
    }
    // A {var} written after the literal's '?' or '#' would put data in the query or fragment.
    String path = link.path();
    int q = path.indexOf('?');
    int h = path.indexOf('#');
    int end = q < 0 ? h : h < 0 ? q : Math.min(q, h);
    if (end >= 0 && path.indexOf('{', end) >= 0) {
      ctx.diagnostics().error(link.pos(), what + "its path variables must come before any '?' or '#', not @{"
          + path + "}: put the value in the path, such as @{/js/{v}/app.js(v=${v})}");
      return null;
    }
    List<String> vars = LinkCompiler.placeholders(path);
    for (Assignment p : link.params()) {
      if (!vars.contains(p.name())) {
        ctx.diagnostics().error(p.pos(), what + "its link takes no query parameter such as '" + p.name()
            + "': put the value in the path, such as @{/js/{v}/app.js(v=${v})}");
        return null;
      }
    }
    return link.path().startsWith("/") ? sameOrigin(c) : c.code();
  }

  /** The recipes for a value that is not framework-neutral. */
  private static final String DATA_FIX_IT =
      "put text in the element text or a standard inert attribute such as title, send request values as"
          + " hidden inputs (with hx-include), or put data in a <script type=\"application/json\"> block and read it"
          + " from your script";

  /**
   * The code of {@code e} for NEUTRAL attribute {@code name} (client frameworks may run any non-standard attribute as code), or null after
   * reporting why it is not framework-neutral, at the offending piece. By static type and expression
   * shape only: a literal, an expression of a number type (an exact-class check and plain decimal at
   * render time, {@link Escapers#neutralNumber}) or a boolean type, a {@link #neutralLink neutral
   * link} ({@link Escapers#neutralPath}), or a choice ({@code c ? a : b}, {@code a ?: b}) between
   * those. Every branch is checked, so all errors are reported. An elvis is evaluated as
   * {@link #elvis} says. Static values are not checked: the author wrote them.
   */
  private static String neutralCode(CodegenContext ctx, Expr e, ElementPlan plan, String label, String name) {
    if (e instanceof Expr.Literal) {
      return code(ctx, e, plan);
    }
    if (e instanceof Expr.Conditional) {
      Expr.Conditional c = (Expr.Conditional) e;
      CompiledExpr cond = ctx.expressions().compileCondition(c.condition(), plan.scope());
      String then = neutralCode(ctx, c.then(), plan, label, name);
      String otherwise = c.otherwise() == null ? "null" : neutralCode(ctx, c.otherwise(), plan, label, name);
      return cond == null || then == null || otherwise == null
          ? null
          : "(" + cond.code() + " ? (java.lang.Object) " + then + " : (java.lang.Object) " + otherwise + ")";
    }
    if (e instanceof Expr.Elvis) {
      Expr.Elvis el = (Expr.Elvis) e;
      String value = neutralCode(ctx, el.value(), plan, label, name);
      String fallback = neutralCode(ctx, el.fallback(), plan, label, name);
      return value == null || fallback == null ? null : elvis(ctx, el, plan, value, fallback);
    }
    if (e instanceof Expr.Link) {
      return neutralLink(ctx, (Expr.Link) e, plan, label, name);
    }
    CompiledExpr c = ctx.expressions().compile(e, plan.scope());
    if (c == null) {
      return null;
    }
    if (isNumber(c.type())) {
      return Escapers.class.getName() + ".neutralNumber(" + c.code() + ")";
    }
    if (isBoolean(c.type())) {
      return c.code();
    }
    String type = c.type().getKind() == TypeKind.NULL ? "null" : JavaStrings.typeName(c.type());
    ctx.diagnostics()
        .error(e.pos(), label + " is not allowed: '" + name + "' is not a standard HTML attribute, and client code"
            + " may run what it reads there, so a computed value must be framework-neutral: a number, a"
            + " boolean, a same-origin link such as @{/items/{id}(id=${item.id})}, or a choice between those and"
            + " literals, not a " + type + "; " + DATA_FIX_IT);
    return null;
  }

  /** What a neutral link's literal path may hold, with each {@code {var}} as one letter: nothing a client framework reads as code. */
  private static final Pattern NEUTRAL_PATH = Pattern.compile("/(?!/)([A-Za-z0-9._~/-]|%[0-9A-Fa-f]{2})*");

  /**
   * The code of {@code link} if it is a neutral path, else null after reporting why: its
   * literal path starts with a single {@code /}, holds only {@code A-Z a-z 0-9 . _ ~ / -} and
   * {@code %XX}, and every parameter fills a path variable, so there is no query or fragment.
   * {@link Escapers#neutralPath} encodes the built URL the same way (path variables come from data)
   * and re-checks it at render time.
   */
  private static String neutralLink(CodegenContext ctx, Expr.Link link, ElementPlan plan, String label, String name) {
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
      return null;
    }
    for (Assignment p : link.params()) {
      if (!vars.contains(p.name())) {
        ctx.diagnostics()
            .error(p.pos(), label + " is not allowed: a link in '" + name + "' cannot have a query; parameter '"
                + p.name() + "' of @{" + path + "} fills no path variable. Send request values as hidden inputs"
                + " (with hx-include), or put them in a <script type=\"application/json\"> block");
        return null;
      }
    }
    CompiledExpr c = ctx.expressions().compile(link, plan.scope());
    return c == null ? null : Escapers.class.getName() + ".neutralPath(" + c.code() + ")";
  }

  private static boolean isBoolean(TypeMirror t) {
    return t.getKind() == TypeKind.BOOLEAN || isDeclared(t, "java.lang.Boolean");
  }

  private static final Pattern REFRESH_PREFIX =
      Pattern.compile("[\\t\\n\\f\\r ]*([0-9]+)[\\t\\n\\f\\r ]*[;,][\\t\\n\\f\\r ]*url[\\t\\n\\f\\r ]*=[\\t\\n\\f\\r ]*",
          Pattern.CASE_INSENSITIVE);

  /**
   * The {@code content} of a {@code <meta http-equiv="refresh">}: only
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
   * A URL that must stay on the page's origin: a {@code <base href>}, the {@code action} of a
   * POST form or a {@code formaction}, which may receive the CSRF token, and a link
   * {@code action} of any other form. Only a link whose literal path is same-origin
   * ({@link LinkCompiler#isSameOriginPath}) compiles, re-checked at render time ({@link #sameOrigin}),
   * or a choice ({@code c ? a : b}, {@code c ? a}) whose every branch is one, as
   * {@link #neutralCode} takes a choice; a missing else branch omits the attribute.
   * {@code why} starts the error for a non-link value and {@code orElse} ends it; {@code what} names
   * the URL in the error for a link that is not same-origin.
   */
  private static void sameOriginUrl(
      CodegenContext ctx, ElementPlan plan, String name, Expr value, SourcePos pos, String label, String why,
      String orElse, String what) {
    String code = sameOriginCode(ctx, plan, value, pos, label, why, orElse, what);
    if (code != null) {
      plan.setAttribute(name, code, pos);
    }
  }

  /**
   * The code of a value {@link #sameOriginUrl} accepts: each link through {@link #sameOrigin}. Null
   * after reporting why not, for a value that is not a link at {@code pos} (a branch at its own).
   */
  private static String sameOriginCode(
      CodegenContext ctx, ElementPlan plan, Expr value, SourcePos pos, String label, String why, String orElse,
      String what) {
    if (value instanceof Expr.Conditional) {
      Expr.Conditional c = (Expr.Conditional) value;
      CompiledExpr cond = ctx.expressions().compileCondition(c.condition(), plan.scope());
      String then = sameOriginCode(ctx, plan, c.then(), c.then().pos(), label, why, orElse, what);
      String otherwise = c.otherwise() == null
          ? "null"
          : sameOriginCode(ctx, plan, c.otherwise(), c.otherwise().pos(), label, why, orElse, what);
      return cond == null || then == null || otherwise == null
          ? null
          : "(" + cond.code() + " ? " + then + " : " + otherwise + ")";
    }
    if (!(value instanceof Expr.Link)) {
      ctx.diagnostics()
          .error(pos, label + " is not allowed: " + why + "; use a same-origin link such as"
              + " @{/items/{id}(id=${item.id})}" + orElse);
      return null;
    }
    Expr.Link link = (Expr.Link) value;
    if (!LinkCompiler.isSameOriginPath(link.path())) {
      ctx.diagnostics()
          .error(link.pos(), label + " is not allowed: " + what + " must be a same-origin path starting"
              + " with a single '/', not @{" + link.path() + "}");
      return null;
    }
    CompiledExpr c = ctx.expressions().compileOutput(link, plan.scope());
    return c == null ? null : sameOrigin(c);
  }

  /**
   * The code of a link whose literal path {@link LinkCompiler#isSameOriginPath is same-origin},
   * re-checked at render time: the context path or a custom {@code Links} could still make it
   * protocol-relative.
   */
  private static String sameOrigin(CompiledExpr link) {
    return Escapers.class.getName() + ".sameOriginLink(" + link.code() + ")";
  }

  /** Starts the reason for the computed action error on a form that is not a static POST one. */
  private static final String FORM_ACTION =
      "a form's computed action must stay on the page's origin, since client code may submit the form and swap"
          + " the response into the page as HTML";

  /** Ends it. */
  private static final String FORM_ACTION_ELSE = ", or a static action if another site is meant";

  /**
   * Boxed and big number types whose text is only digits, sign, '.', 'E', NaN or Infinity.
   * BigDecimal and BigInteger are not final, so {@link #styleCode} wraps them in
   * {@link Escapers#styleNumber}, which accepts only the exact JDK class at render time.
   */
  private static final Set<String> NUMBER_TYPES =
      Backports.setOf(
          "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.lang.Float",
          "java.lang.Double", "java.math.BigDecimal", "java.math.BigInteger");

  /**
   * A {@code style} attribute. CSS cannot be escaped, so the value is built only from
   * pieces that cannot change the declaration structure: literals (written by the template
   * author), numbers and {@link CssValue}s, joined by {@code +}, {@code |...|}, {@code c ? a : b} and
   * {@code a ?: b}. Any other piece, such as a String expression, is a build error. The
   * result is then attribute-escaped like any attribute. A {@code BigDecimal} or {@code BigInteger}
   * piece goes through {@link Escapers#styleNumber}, which fails the render if a subclass's
   * {@code toString()} is not a number.
   *
   * <p>A TYPED_ATTRIBUTE is checked the same way but takes no safe type: only literals
   * and numbers. {@code context} is the attribute's context: TYPED_ATTRIBUTE or STYLE.
   */
  private static void style(
      CodegenContext ctx, ElementPlan plan, String name, Expr value, SourcePos pos, String label, OutputContext context) {
    String code = styleCode(ctx, value, plan, label, name, context);
    // The whole value is view-safe-checked like any other output.
    if (code != null && ctx.expressions().compileOutput(value, plan.scope()) != null) {
      plan.setAttribute(name, code, pos);
    }
  }

  /**
   * The code of {@code el} from the code of its operands. As in the expression compiler, a variable
   * (side-effect free to read) gets a ternary that evaluates the fallback only when it is null
   * (#807); any other value goes through {@code Values.elvis}, which evaluates both operands.
   */
  private static String elvis(CodegenContext ctx, Expr.Elvis el, ElementPlan plan, String value, String fallback) {
    CompiledExpr v = el.value() instanceof Expr.Var ? ctx.expressions().compile(el.value(), plan.scope()) : null;
    if (v != null && !v.type().getKind().isPrimitive()) {
      return "(" + v.code() + " != null ? (java.lang.Object) " + value + " : (java.lang.Object) " + fallback + ")";
    }
    return "dev.chainguard.thwart.runtime.internal.Values.<java.lang.Object>elvis((java.lang.Object) " + value
        + ", (java.lang.Object) " + fallback + ")";
  }

  /**
   * The code of a style value, or null after reporting why it is not allowed, at the offending
   * piece: what the expression compiler writes, except that each {@code BigDecimal} or
   * {@code BigInteger} piece is wrapped in {@link Escapers#styleNumber}. Every piece is checked, so
   * all errors are reported. A {@code +} with a String operand is concatenation; any other
   * {@code +} is arithmetic on primitives and boxes, so it has no such piece. The condition of a
   * choice is not output. An elvis is evaluated as {@link #elvis} says.
   */
  private static String styleCode(
      CodegenContext ctx, Expr e, ElementPlan plan, String label, String name, OutputContext context) {
    if (e instanceof Expr.Literal) {
      return code(ctx, e, plan);
    }
    if (e instanceof Expr.Interpolation) {
      StringBuilder code = new StringBuilder("(\"\"");
      boolean ok = true;
      for (Expr part : ((Expr.Interpolation) e).parts()) {
        String piece = styleCode(ctx, part, plan, label, name, context);
        ok &= piece != null;
        code.append(" + ").append(piece);
      }
      return ok ? code.append(')').toString() : null;
    }
    if (e instanceof Expr.Binary && ((Expr.Binary) e).op() == Expr.BinaryOp.ADD) {
      Expr.Binary b = (Expr.Binary) e;
      String left = styleCode(ctx, b.left(), plan, label, name, context);
      String right = styleCode(ctx, b.right(), plan, label, name, context);
      CompiledExpr whole = left == null || right == null ? null : ctx.expressions().compile(e, plan.scope());
      if (whole == null) {
        return null;
      }
      return isDeclared(whole.type(), "java.lang.String") ? "(" + left + " + " + right + ")" : whole.code();
    }
    if (e instanceof Expr.Conditional) {
      Expr.Conditional c = (Expr.Conditional) e;
      // A missing else branch ("c ? a") renders nothing.
      String then = styleCode(ctx, c.then(), plan, label, name, context);
      String otherwise = c.otherwise() == null ? "null" : styleCode(ctx, c.otherwise(), plan, label, name, context);
      CompiledExpr whole = then == null || otherwise == null ? null : ctx.expressions().compile(e, plan.scope());
      if (whole == null) {
        return null;
      }
      if (then.equals(code(ctx, c.then(), plan))
          && (c.otherwise() == null || otherwise.equals(code(ctx, c.otherwise(), plan)))) {
        return whole.code();
      }
      return "(" + ctx.expressions().compileCondition(c.condition(), plan.scope()).code()
          + " ? (java.lang.Object) " + then + " : (java.lang.Object) " + otherwise + ")";
    }
    if (e instanceof Expr.Elvis) {
      Expr.Elvis el = (Expr.Elvis) e;
      String value = styleCode(ctx, el.value(), plan, label, name, context);
      String fallback = styleCode(ctx, el.fallback(), plan, label, name, context);
      CompiledExpr whole = value == null || fallback == null ? null : ctx.expressions().compile(e, plan.scope());
      if (whole == null) {
        return null;
      }
      if (value.equals(code(ctx, el.value(), plan)) && fallback.equals(code(ctx, el.fallback(), plan))) {
        return whole.code();
      }
      TypeMirror t = ctx.expressions().compile(el.value(), plan.scope()).type();
      if (t.getKind().isPrimitive()) {
        return value; // never null
      }
      if (t.getKind() == TypeKind.NULL) {
        return fallback;
      }
      return elvis(ctx, el, plan, value, fallback);
    }
    CompiledExpr c = ctx.expressions().compile(e, plan.scope());
    if (c == null) {
      return null;
    }
    TypeMirror t = c.type();
    if (isDeclared(t, "java.math.BigDecimal") || isDeclared(t, "java.math.BigInteger")) {
      return Escapers.class.getName() + ".styleNumber(" + c.code() + ")";
    }
    if (isNumber(t) || context == OutputContext.STYLE && isDeclared(t, CssValue.class.getName())) {
      return c.code();
    }
    String type = t.getKind() == TypeKind.NULL ? "null" : JavaStrings.typeName(t);
    if (context == OutputContext.TYPED_ATTRIBUTE) {
      ctx.diagnostics()
          .error(
              e.pos(),
              label + " is not allowed: '" + name + "' is not an inert-text attribute on <" + plan.element().name()
                  + ">, so a computed value can only combine string literals and numbers, not a " + type
                  + "; write a static attribute, choose between literals such as ${c} ? 'a' : 'b', or if a script"
                  + " reads it, put the data in a <script type=\"application/json\"> block");
      return null;
    }
    ctx.diagnostics()
        .error(
            e.pos(),
            label + " is not allowed: a style value can only combine string literals, numbers and CssValues"
                + " such as css.color(c), css.length(l) or css.url(u), not a " + type
                + "; use t:class or t:classappend instead");
    return null;
  }

  private static String code(CodegenContext ctx, Expr e, ElementPlan plan) {
    return ctx.expressions().compile(e, plan.scope()).code();
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
