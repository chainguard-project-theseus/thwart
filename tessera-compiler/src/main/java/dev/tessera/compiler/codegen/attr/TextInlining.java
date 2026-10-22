package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.model.Text;
import dev.tessera.compiler.types.Scope;

/**
 * Text inlining: {@code [[expr]]} (escaped for the text node's context: TEXT via
 * {@code Escapers.text}, SCRIPT via {@code Escapers.json}) and {@code [(expr)]} (SafeHtml only,
 * TEXT context only). STYLE/FORBIDDEN contexts with inlined expressions are build errors.
 */
public final class TextInlining {
  private TextInlining() {}

  /**
   * Statements that render {@code text}, or {@code null} if it has no inlined expressions (the
   * generator then emits it verbatim).
   */
  public static String render(CodegenContext ctx, Text text, Scope scope) {
    return null; // TODO(#11): [[...]] and [(...)] inlining
  }
}
