package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;

/**
 * Called for every {@code <form>} element: if its {@code method} is statically {@code post}
 * (case-insensitive), adds a hidden input with {@code ctx.forms().csrfToken()} after the start
 * tag when a token is present.
 */
public final class CsrfInjector {
  private CsrfInjector() {}

  public static void apply(CodegenContext ctx, ElementPlan plan) {
    // TODO(#15): CSRF hidden input for method="post" forms
  }
}
