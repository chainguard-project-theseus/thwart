package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Element;
import dev.tessera.runtime.Escapers;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Called for every {@code <form>} element after its {@code t:} processors: adds a hidden input
 * with {@code ctx.forms().csrfToken()} after the start tag when a token is present, the form's
 * method is {@code post} (case-insensitive) and its action posts to the page's origin.
 *
 * <p>Both a static and a computed method match {@code post} ASCII case-insensitively and untrimmed,
 * as a browser reads the attribute: any other value submits as GET, and a token there would leak
 * into the URL. A static {@code method} is checked at build time. A computed one ({@code t:method},
 * {@code t:attr="method=..."}) is evaluated once before the start tag and written from that value,
 * as in Thymeleaf, where {@code th:method} runs before the CSRF processor, so a computed POST form
 * cannot lose its token.
 *
 * <p>The action must be same-origin by shape ({@link Escapers#isSameOriginUrl}): absent, empty, or
 * relative without a scheme and not protocol-relative, so the session's token is never posted to
 * another site (#285). A static {@code action} is checked at build time; a computed one
 * ({@code t:action}, {@code t:attr="action=..."}, links included) is evaluated once before the
 * start tag, like a computed method, and the token is written only if its value passes. An
 * absolute URL to the application's own host gets no token: write it as {@code @{/...}}.
 *
 * <p>A submit button can send the form elsewhere (#314). Where it may submit a form that gets the
 * token ({@link CodegenContext#mayPostCsrfToken}), a computed {@code formaction} must be a
 * same-origin link ({@code GenericAttrProcessor}), a static one must be same-origin by shape, and
 * {@code formmethod} must be a static {@code post} or {@code dialog}: {@link #checkSubmitter}.
 */
public final class CsrfInjector {
  /** A named character reference the parser did not decode (a browser may; see below). */
  private static final Pattern UNDECODED_REFERENCE = Pattern.compile("&[A-Za-z][A-Za-z0-9]*;?");

  private CsrfInjector() {}

  /** Adds the token input to a {@code <form>}; returns whether it may be written. */
  public static boolean apply(CodegenContext ctx, ElementPlan plan) {
    if (plan.removed() || plan.replacement() != null || plan.tagOmitted()) {
      // Without its tag (t:remove="tag") the token input would land in an enclosing form, maybe a GET one.
      return false;
    }
    if (plan.element().selfClosing() && plan.body() == null) {
      // A browser treats <form/> as an open form: the page after it is inside the form, and the start
      // tags of later forms are dropped, so their buttons and token inputs land in it.
      ctx.diagnostics()
          .error(plan.element().pos(), "<form/> is not self-closing in HTML: the browser treats everything after it"
              + " as inside the form; write <form ...></form>");
      return false;
    }
    // The Java condition under which the token is written, or null for always.
    String condition = null;
    Optional<ElementPlan.Attr> method = plan.attribute("method");
    if (method.isPresent() && method.get() instanceof ElementPlan.DynamicAttr) {
      String m = hoist(ctx, plan, (ElementPlan.DynamicAttr) method.get(), "method");
      // toLowerCase(ROOT) maps no non-ASCII character to p, o, s or t, so this is an ASCII match.
      condition =
          m + " != null && java.lang.String.valueOf(" + m + ").toLowerCase(java.util.Locale.ROOT).equals(\"post\")";
    } else {
      boolean post =
          method
              .filter(a -> a instanceof ElementPlan.StaticAttr)
              .map(a -> ((ElementPlan.StaticAttr) a).source().value())
              .filter(v -> v.toLowerCase(Locale.ROOT).equals("post"))
              .isPresent();
      if (!post) {
        return false;
      }
    }
    // TODO(#311): a static hx-post (or another htmx request attribute) on the form or a button in
    // it can send the token to another origin; TODO(#309): so can a <form> nested in another, whose
    // token input lands in the outer form. Neither is checked here (guide section 7).
    Optional<ElementPlan.Attr> action = plan.attribute("action");
    if (action.isPresent() && action.get() instanceof ElementPlan.DynamicAttr) {
      String a = hoist(ctx, plan, (ElementPlan.DynamicAttr) action.get(), "action");
      String sameOrigin = Escapers.class.getName() + ".isSameOriginUrl(" + a + ")";
      condition = condition == null ? sameOrigin : condition + " && " + sameOrigin;
    } else if (action.isPresent()
        && !isSameOriginStatic(((ElementPlan.StaticAttr) action.get()).source().value())) {
      // A static action on another origin is the author's choice; it just gets no token.
      return false;
    }
    plan.afterStartTag(condition == null ? tokenInput(ctx) : "if (" + condition + ") " + tokenInput(ctx));
    return true;
  }

  /** {@code t:attr} setting {@code form}: the first assignment, or one after a comma. */
  private static final Pattern ATTR_SETS_FORM = Pattern.compile("(?i)(^|,)\\s*form\\s*=");

  /**
   * Whether {@code element} has a {@code form} attribute, static or computed ({@code t:form},
   * {@code t:attr="form=..."}): it then submits that form wherever it is.
   */
  public static boolean hasFormAttribute(Element element) {
    for (Attribute a : element.attributes()) {
      String n = a.name().toLowerCase(Locale.ROOT);
      if (n.equals("form") || n.equals(Attribute.PREFIX + "form")
          || (n.equals(Attribute.PREFIX + "attr") && a.value() != null && ATTR_SETS_FORM.matcher(a.value()).find())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Checks the static {@code formaction} and {@code formmethod} of an element that may submit a
   * form with the token (#314): the action must be same-origin by shape, and the method
   * {@code post} or {@code dialog} (any other value, invalid ones included, submits as GET and
   * would put the token in the URL). Computed ones are checked by {@code GenericAttrProcessor}.
   */
  public static void checkSubmitter(CodegenContext ctx, ElementPlan plan) {
    Optional<ElementPlan.Attr> action = plan.attribute("formaction");
    Optional<ElementPlan.Attr> method = plan.attribute("formmethod");
    boolean staticAction = action.isPresent() && action.get() instanceof ElementPlan.StaticAttr;
    boolean staticMethod = method.isPresent() && method.get() instanceof ElementPlan.StaticAttr;
    if ((!staticAction && !staticMethod) || !ctx.mayPostCsrfToken(plan.element())) {
      return;
    }
    if (staticAction) {
      Attribute a = ((ElementPlan.StaticAttr) action.get()).source();
      if (!isSameOriginStatic(a.value())) {
        ctx.diagnostics()
            .error(a.pos(), a.name() + "=\"" + a.value() + "\" is not allowed here: " + IN_TOKEN_FORM
                + ", and this URL is not same-origin; use a same-origin URL such as /items or"
                + " @{/items/{id}(id=${item.id})}, or move the button into its own form");
      }
    }
    if (staticMethod) {
      Attribute m = ((ElementPlan.StaticAttr) method.get()).source();
      String v = m.value() == null ? "" : m.value().toLowerCase(Locale.ROOT);
      if (!v.equals("post") && !v.equals("dialog")) {
        ctx.diagnostics().error(m.pos(), m.name() + "=\"" + m.value() + "\" is not allowed here: " + GET_TOKEN);
      }
    }
  }

  /** Starts the reason for the formaction errors. */
  static final String IN_TOKEN_FORM =
      "this button may submit a POST form that gets the CSRF token, which then goes to the button's formaction";

  /** The reason for the formmethod errors. */
  static final String GET_TOKEN =
      "this button may submit a POST form that gets the CSRF token, and a formmethod other than post submits"
          + " as GET with the token in the URL; remove formmethod, or move the button into its own GET form";

  /**
   * {@link Escapers#isSameOriginUrl} for a static attribute value, which is written as in the source.
   * The parser decodes numeric and a few named character references; a browser decodes every named
   * one ({@code &sol;}, {@code &colon;}, {@code &Tab;}, ...). So each {@code &name;} left in
   * {@code value} may be any character: the value must pass with it removed, as {@code /} and as
   * {@code :}, which covers every way it could make the URL protocol-relative or give it a scheme
   * ({@code isSameOriginUrl} reads {@code \} as {@code /}, so {@code /} also stands for
   * {@code &bsol;}). The pattern also matches a literal {@code &} (from {@code &amp;}) before
   * letters, so a relative action with one before any {@code / ? #} ({@code action="a&amp;b"})
   * gets no token: fail-closed, and {@code /x?a&b} still passes.
   */
  private static boolean isSameOriginStatic(String value) {
    if (value == null) {
      return true;
    }
    for (String c : new String[] {"", "/", ":"}) {
      if (!Escapers.isSameOriginUrl(UNDECODED_REFERENCE.matcher(value).replaceAll(c))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Evaluates a computed attribute once, before the start tag, into a fresh local, and makes the
   * start tag write that local; returns its name.
   */
  private static String hoist(CodegenContext ctx, ElementPlan plan, ElementPlan.DynamicAttr d, String name) {
    String v = ctx.freshName(name);
    plan.wrap("{ java.lang.Object " + v + " = " + d.valueCode() + ";", "}");
    plan.setAttribute(d.name(), v, d.pos());
    return v;
  }

  private static String tokenInput(CodegenContext ctx) {
    String t = ctx.freshName("csrf");
    String esc = Escapers.class.getName() + ".attr(out, ";
    return "{ java.util.Optional<dev.tessera.runtime.Forms.CsrfToken> " + t + " = ctx.forms().csrfToken(); if (" + t
        + ".isPresent()) { out.write(\"<input type=\\\"hidden\\\" name=\\\"\"); " + esc + t
        + ".get().parameterName()); out.write(\"\\\" value=\\\"\"); " + esc + t
        + ".get().token()); out.write(\"\\\" />\"); } }";
  }
}
