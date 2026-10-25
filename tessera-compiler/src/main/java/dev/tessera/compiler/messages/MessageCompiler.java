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
import java.util.Set;

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
   * Code of the HTML of {@code #{key(args)}} ({@code t:utext}, {@code [(...)]}; #236), a
   * {@code String} the generator writes as is ({@code codegen.Emitted#htmlMessage}), or
   * {@code null} after reporting an error. The bundle must be checked, and the key's value in
   * every bundle file the build can see must pass {@link MessageMarkup}. Arguments are
   * HTML-escaped before they are formatted into the message, and at runtime a message that is not
   * what one of those checked values produces is escaped ({@code Escapers.htmlMessage}).
   */
  public static String htmlValue(Expr.Message message, String form, Scope scope, ExpressionCompiler expressions) {
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
    List<String> checked = new ArrayList<>();
    for (Map.Entry<String, String> v : bundle.values(key).entrySet()) {
      checked.add(JavaStrings.literal(v.getValue()));
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
    // The runtime prints the message as HTML only if it is what one of the checked values produces.
    return "dev.tessera.runtime.Escapers.htmlMessage(ctx, " + JavaStrings.literal(key) + ", " + args
        + ", new java.lang.String[] {" + String.join(", ", checked) + "})";
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
      Set<String> only = bundle.values(key).keySet();
      env.diagnostics()
          .error(
              message.pos(),
              "unknown message key '" + key + "': it is not in " + bundle.source()
                  + (only.isEmpty()
                      ? ""
                      : "; it is only in " + String.join(", ", only) + ", which other locales do not fall back"
                          + " to (name the default locale in -Atessera.messages, e.g. <base>_en, or add the key"
                          + " to the base file)"));
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
