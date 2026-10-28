package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.codegen.Emitted;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.Node;
import dev.tessera.compiler.verify.Skeleton;
import dev.tessera.runtime.Escapers;
import dev.tessera.runtime.TrustedUrl;
import java.util.ArrayList;
import java.util.List;
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
 * cannot lose its token. The value is converted to text there, once, so the check and the start tag
 * see the same text.
 *
 * <p>The action must be same-origin by shape ({@link Escapers#isSameOriginUrl}): absent, empty, or
 * relative without a scheme and not protocol-relative, so the session's token is never posted to
 * another site (#285). A static {@code action} is checked at build time; a computed one
 * ({@code t:action}, {@code t:attr="action=..."}, links included) is evaluated once before the
 * start tag and converted to text, like a computed method, and the token is written only if that
 * text passes. An absolute URL to the application's own host gets no token: write it as
 * {@code @{/...}}.
 *
 * <p>A submit button can send the form elsewhere (#314). Where it may submit a form that gets the
 * token ({@link CodegenContext#mayPostCsrfToken}), a computed {@code formaction} must be a
 * same-origin link ({@code GenericAttrProcessor}), a static one must be same-origin by shape, and
 * {@code formmethod} must be a static {@code post} or {@code dialog}: {@link #checkSubmitter}.
 *
 * <p>A form that writes its own token input (#225) keeps it and gets no second one: a direct child
 * {@code <input type="hidden">} whose literal {@code name} equals the token's parameter name at
 * render time ({@link #ownTokenNames}). Only an input that is always written, in the same place
 * as the automatic one, counts, so a form never loses its token unless the template visibly
 * writes one.
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
    Emitted token = tokenInput(ctx, ownTokenNames(plan));
    plan.afterStartTag(
        condition == null ? token : Emitted.opt(Skeleton.OptKind.CSRF_TOKEN, null, "if (" + condition + ") ", token, ""));
    return true;
  }

  /**
   * The literal {@code name}s of the form's own token inputs: direct children
   * {@code <input type="hidden" name="...">} with a static {@code type} ({@code hidden}, ASCII
   * case-insensitive) and a static, non-empty {@code name}, written whenever the form's start tag
   * is. So the input has no {@code t:} attribute except {@code t:value} and {@code t:id} (nothing
   * that can drop it or change its type or name), no {@code disabled} (not submitted) and no
   * {@code form} (submits another form), and the form's body is not replaced ({@code t:text},
   * {@code t:remove="body"}, ...). The automatic input is left out when one of these names equals
   * the token's parameter name.
   */
  static List<String> ownTokenNames(ElementPlan plan) {
    List<String> names = new ArrayList<>();
    if (plan.body() != null) {
      return names;
    }
    for (Node n : plan.element().children()) {
      if (!(n instanceof Element) || !((Element) n).name().equalsIgnoreCase("input")) {
        continue;
      }
      String type = null;
      String name = null;
      boolean always = true;
      for (Attribute a : ((Element) n).attributes()) {
        String an = a.name().toLowerCase(Locale.ROOT);
        if (a.isTessera()) {
          always &= an.equals(Attribute.PREFIX + "value") || an.equals(Attribute.PREFIX + "id");
        } else if (an.equals("type")) {
          type = type == null ? a.value() : type;
        } else if (an.equals("name")) {
          name = name == null ? a.value() : name;
        } else if (an.equals("disabled") || an.equals("form")) {
          always = false;
        }
      }
      if (always && type != null && type.toLowerCase(Locale.ROOT).equals("hidden") && name != null
          && !name.isEmpty()) {
        names.add(name);
      }
    }
    return names;
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

  /**
   * Checks the static {@code href} of a {@code <base>} (#350): every relative URL on the page
   * resolves against it, so a cross-origin base would send a form with a relative action, CSRF token
   * included, to another site. It must be same-origin by shape, like a static {@code action}. This
   * is checked on every {@code <base>}, whether or not the template has a form: a layout's
   * {@code <base>} applies to forms that fragments from other templates insert. A computed one must
   * be a same-origin link ({@code GenericAttrProcessor}). Every {@code href} is checked, since a
   * browser uses the first and the plan keeps the last.
   */
  public static void checkBase(CodegenContext ctx, Element element) {
    for (Attribute a : element.attributes()) {
      if (!a.isTessera() && a.name().equalsIgnoreCase("href") && !isSameOriginStatic(a.value())) {
        ctx.diagnostics()
            .error(a.pos(), a.name() + "=\"" + a.value() + "\" on <base> is not allowed: " + BASE_MOVES_URLS
                + ", and this URL is not same-origin; use a same-origin path such as /app/, or remove <base>"
                + " and write absolute URLs where another origin is meant");
      }
    }
  }

  /** Starts the reason for the {@code <base href>} errors. */
  static final String BASE_MOVES_URLS =
      "every relative URL on the page resolves against <base href>, so a POST form with a relative action would"
          + " send the CSRF token there";

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
   * start tag write that local; returns its name. A value other than {@code null} (the attribute is
   * omitted) or a {@link TrustedUrl} is converted to its text there, once, so the token's condition
   * and the start tag see the same {@code String} even if its {@code toString()} changes between
   * calls.
   */
  private static String hoist(CodegenContext ctx, ElementPlan plan, ElementPlan.DynamicAttr d, String name) {
    String v = ctx.freshName(name);
    plan.wrap(
        "{ java.lang.Object " + v + " = " + d.valueCode() + "; if (" + v + " != null && !(" + v + " instanceof "
            + TrustedUrl.class.getName() + ")) " + v + " = " + Escapers.class.getName() + ".toText(" + v + ");",
        "}");
    plan.setAttribute(d.name(), v, d.pos());
    return v;
  }

  /**
   * The hidden input, written when the request has a token whose parameter name is none of
   * {@code ownNames} (made up: no source position).
   */
  private static Emitted tokenInput(CodegenContext ctx, List<String> ownNames) {
    String t = ctx.freshName("csrf");
    Emitted input =
        Emitted.concat(
            Emitted.text("<input type=\"hidden\" name=\"", null),
            Emitted.code(" "),
            Emitted.hole(Skeleton.Escaper.ATTR, "input", "name", null, t + ".get().parameterName()"),
            Emitted.code(" "),
            Emitted.text("\" value=\"", null),
            Emitted.code(" "),
            Emitted.hole(Skeleton.Escaper.ATTR, "input", "value", null, t + ".get().token()"),
            Emitted.code(" "),
            Emitted.text("\" />", null));
    StringBuilder notOwn = new StringBuilder();
    for (String name : ownNames) {
      notOwn.append(" && !").append(t).append(".get().parameterName().equals(")
          .append(JavaStrings.literal(name)).append(')');
    }
    return Emitted.opt(
        Skeleton.OptKind.CSRF_TOKEN,
        null,
        "{ java.util.Optional<dev.tessera.runtime.Forms.CsrfToken> " + t + " = ctx.forms().csrfToken(); if (" + t
            + ".isPresent()" + notOwn + ") { ",
        input,
        " } }");
  }
}
