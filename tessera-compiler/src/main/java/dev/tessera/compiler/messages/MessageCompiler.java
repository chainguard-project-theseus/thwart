package dev.tessera.compiler.messages;

import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.ExpressionCompiler;
import dev.tessera.compiler.types.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Compiles {@code #{key(args)}} to {@code ctx.messages().get("key", args...)} (type String),
 * reporting unknown keys when the bundle is checked.
 */
public final class MessageCompiler {
  private MessageCompiler() {}

  public static CompiledExpr compile(Expr.Message message, Scope scope, ExpressionCompiler expressions) {
    String args = args(message, scope, expressions, true);
    if (args == null) {
      return null;
    }
    // An explicit array: a lone null or array argument must not be taken as the varargs array.
    String code = "ctx.messages().get(" + JavaStrings.literal(message.key()) + ", " + args + ")";
    CompilerEnv env = expressions.env();
    return new CompiledExpr(code, env.elements().getTypeElement("java.lang.String").asType(), message.pos());
  }

  /**
   * A statement writing {@code #{key(args)}} as raw HTML ({@code t:utext}, {@code [(...)]}; #236),
   * or {@code null} after reporting an error. The bundle must be checked, and the key's value in
   * every bundle file the build can see must pass {@link MessageMarkup}. Arguments are
   * HTML-escaped before they are formatted into the message.
   */
  public static String htmlStatement(Expr.Message message, String form, Scope scope, ExpressionCompiler expressions) {
    CompilerEnv env = expressions.env();
    MessageBundle bundle = env.messages();
    String key = message.key();
    if (!bundle.isChecked()) {
      env.diagnostics()
          .error(
              message.pos(),
              form + " with a message #{...} needs -Atessera.messages=<bundle base path>, so the message"
                  + " can be checked as HTML at build time; or use t:text or [[...]] (escaped)");
      return null;
    }
    boolean ok = true;
    for (Map.Entry<String, String> v : bundle.values(key).entrySet()) {
      String problem = MessageMarkup.problem(v.getValue());
      if (problem != null) {
        env.diagnostics()
            .error(
                message.pos(),
                "message '" + key + "' in " + v.getKey() + " cannot be rendered as HTML by " + form + ": "
                    + problem);
        ok = false;
      }
    }
    String args = args(message, scope, expressions, ok);
    if (args == null) {
      return null;
    }
    return "out.write(java.util.Objects.toString(ctx.messages().get(" + JavaStrings.literal(key)
        + ", dev.tessera.runtime.Escapers.htmlMessageArgs(" + args + ")), \"\"));\n";
  }

  /**
   * The {@code new Object[] {...}} of compiled arguments, or {@code null} after reporting an
   * unknown key or a bad argument (or when {@code ok} is false).
   */
  private static String args(Expr.Message message, Scope scope, ExpressionCompiler expressions, boolean ok) {
    CompilerEnv env = expressions.env();
    MessageBundle bundle = env.messages();
    String key = message.key();
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
    return "new java.lang.Object[] {" + String.join(", ", args) + "}";
  }
}
