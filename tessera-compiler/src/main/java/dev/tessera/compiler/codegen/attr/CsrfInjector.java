package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import java.util.Locale;

/**
 * Called for every {@code <form>} element: if its {@code method} is statically {@code post}
 * (case-insensitive), adds a hidden input with {@code ctx.forms().csrfToken()} after the start
 * tag when a token is present.
 */
public final class CsrfInjector {
  private CsrfInjector() {}

  public static void apply(CodegenContext ctx, ElementPlan plan) {
    boolean post =
        plan.attribute("method")
            .filter(
                a ->
                    a instanceof ElementPlan.StaticAttr s
                        && s.source().value() != null
                        && s.source().value().strip().toLowerCase(Locale.ROOT).equals("post"))
            .isPresent();
    if (!post) {
      return;
    }
    String t = ctx.freshName("csrf");
    String esc = "dev.tessera.runtime.Escapers.attr(out, ";
    plan.afterStartTag(
        "{ java.util.Optional<dev.tessera.runtime.Forms.CsrfToken> " + t + " = ctx.forms().csrfToken(); if (" + t
            + ".isPresent()) { out.write(\"<input type=\\\"hidden\\\" name=\\\"\"); " + esc + t
            + ".get().parameterName()); out.write(\"\\\" value=\\\"\"); " + esc + t
            + ".get().token()); out.write(\"\\\" />\"); } }");
  }
}
