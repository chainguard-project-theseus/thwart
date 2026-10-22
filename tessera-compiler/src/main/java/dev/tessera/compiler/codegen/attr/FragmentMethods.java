package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.CodegenContext;

/**
 * Called once per template: adds {@code public static void fragment_<name>(<params>,
 * RenderContext ctx, Output out)} for every {@code t:fragment} in
 * {@code ctx.template().fragments()}, rendering the fragment element in a scope containing only
 * its declared parameters.
 */
public final class FragmentMethods {
  private FragmentMethods() {}

  public static void generate(CodegenContext ctx) {
    ctx.template()
        .fragments()
        .values()
        .forEach(
            f ->
                ctx.diagnostics()
                    .error(f.element().pos(), "t:fragment is not implemented yet (TODO(#14))"));
  }
}
