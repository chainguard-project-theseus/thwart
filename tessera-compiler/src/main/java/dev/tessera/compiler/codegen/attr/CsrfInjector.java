package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
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
 */
public final class CsrfInjector {
  /** A named character reference the parser did not decode (a browser may; see below). */
  private static final Pattern UNDECODED_REFERENCE = Pattern.compile("&[A-Za-z][A-Za-z0-9]*;?");

  private CsrfInjector() {}

  public static void apply(CodegenContext ctx, ElementPlan plan) {
    if (plan.removed() || plan.replacement() != null) {
      return;
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
        return;
      }
    }
    // TODO(follow-up to #285): a submit button's formaction, a static hx-post on the form and a
    // nested <form> can also send the token to another origin; not checked here (guide section 7).
    Optional<ElementPlan.Attr> action = plan.attribute("action");
    if (action.isPresent() && action.get() instanceof ElementPlan.DynamicAttr) {
      String a = hoist(ctx, plan, (ElementPlan.DynamicAttr) action.get(), "action");
      String sameOrigin = Escapers.class.getName() + ".isSameOriginUrl(" + a + ")";
      condition = condition == null ? sameOrigin : condition + " && " + sameOrigin;
    } else if (action.isPresent()
        && !isSameOriginStatic(((ElementPlan.StaticAttr) action.get()).source().value())) {
      // A static action on another origin is the author's choice; it just gets no token.
      return;
    }
    plan.afterStartTag(condition == null ? tokenInput(ctx) : "if (" + condition + ") " + tokenInput(ctx));
  }

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
