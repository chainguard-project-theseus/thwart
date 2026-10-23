package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.runtime.Escapers;
import java.util.Locale;
import java.util.Optional;

/**
 * Called for every {@code <form>} element after its {@code t:} processors: adds a hidden input
 * with {@code ctx.forms().csrfToken()} after the start tag when a token is present and the form's
 * method is {@code post} (case-insensitive).
 *
 * <p>Both a static and a computed method match {@code post} ASCII case-insensitively and untrimmed,
 * as a browser reads the attribute: any other value submits as GET, and a token there would leak
 * into the URL. A static {@code method} is checked at build time. A computed one ({@code t:method},
 * {@code t:attr="method=..."}) is evaluated once before the start tag and written from that value,
 * as in Thymeleaf, where {@code th:method} runs before the CSRF processor, so a computed POST form
 * cannot lose its token.
 */
public final class CsrfInjector {
  private CsrfInjector() {}

  public static void apply(CodegenContext ctx, ElementPlan plan) {
    if (plan.removed() || plan.replacement() != null) {
      return;
    }
    Optional<ElementPlan.Attr> method = plan.attribute("method");
    if (method.isPresent() && method.get() instanceof ElementPlan.DynamicAttr) {
      ElementPlan.DynamicAttr d = (ElementPlan.DynamicAttr) method.get();
      String m = ctx.freshName("method");
      plan.wrap("{ java.lang.Object " + m + " = " + d.valueCode() + ";", "}");
      plan.setAttribute(d.name(), m, d.pos());
      // toLowerCase(ROOT) maps no non-ASCII character to p, o, s or t, so this is an ASCII match.
      plan.afterStartTag(
          "if (" + m + " != null && java.lang.String.valueOf(" + m
              + ").toLowerCase(java.util.Locale.ROOT).equals(\"post\")) " + tokenInput(ctx));
      return;
    }
    boolean post =
        method
            .filter(a -> a instanceof ElementPlan.StaticAttr)
            .map(a -> ((ElementPlan.StaticAttr) a).source().value())
            .filter(v -> v.toLowerCase(Locale.ROOT).equals("post"))
            .isPresent();
    if (post) {
      plan.afterStartTag(tokenInput(ctx));
    }
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
