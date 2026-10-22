package dev.tessera.compiler.messages;

import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.ExpressionCompiler;
import dev.tessera.compiler.types.Scope;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles {@code #{key(args)}} to {@code ctx.messages().get("key", args...)} (type String),
 * reporting unknown keys when the bundle is checked.
 */
public final class MessageCompiler {
  private MessageCompiler() {}

  public static CompiledExpr compile(Expr.Message message, Scope scope, ExpressionCompiler expressions) {
    CompilerEnv env = expressions.env();
    MessageBundle bundle = env.messages();
    String key = message.key();
    boolean ok = true;
    if (!bundle.contains(key)) {
      env.diagnostics().error(message.pos(), "unknown message key '" + key + "': it is not in " + bundle.source());
      ok = false;
    } else if (bundle.firstUncheckedUse(message.pos().file())) {
      env.diagnostics()
          .warning(
              message.pos(),
              "message keys in this template are not checked: set -Atessera.messages=<bundle base path>"
                  + " to check #{...} keys at build time");
    }
    // Arguments are formatted into the message text, so they must be outputtable values.
    List<String> args = new ArrayList<>();
    for (Expr a : message.args()) {
      CompiledExpr c = expressions.compileOutput(a, scope);
      if (c == null) {
        ok = false;
        continue;
      }
      args.add(c.code());
    }
    if (!ok) {
      return null;
    }
    // An explicit array: a lone null or array argument must not be taken as the varargs array.
    String code =
        "ctx.messages().get(" + JavaStrings.literal(key) + ", new java.lang.Object[] {"
            + String.join(", ", args) + "})";
    return new CompiledExpr(code, env.elements().getTypeElement("java.lang.String").asType(), message.pos());
  }
}
