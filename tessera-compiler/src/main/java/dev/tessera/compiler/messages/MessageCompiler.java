package dev.tessera.compiler.messages;

import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.codegen.Emitted;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.html.HtmlParser;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.ExpressionCompiler;
import dev.tessera.compiler.types.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Compiles {@code #{key(args)}} to {@code ctx.messages().get("key", args...)} (type String),
 * reporting unknown keys when the bundle is checked.
 */
public final class MessageCompiler {
  private MessageCompiler() {}

  public static CompiledExpr compile(Expr.Message message, Scope scope, ExpressionCompiler expressions) {
    String args = args(message, scope, expressions);
    if (args == null) {
      return null;
    }
    // An explicit array: a lone null or array argument must not be taken as the varargs array.
    // Arguments go through Escapers.messageArgs, which bounds how long a formatted number can be.
    String code = "ctx.messages().get(" + JavaStrings.literal(message.key()) + ", "
        + (message.args().isEmpty() ? args : "dev.tessera.runtime.Escapers.messageArgs(" + args + ")") + ")";
    CompilerEnv env = expressions.env();
    return new CompiledExpr(code, env.elements().getTypeElement("java.lang.String").asType(), message.pos());
  }

  /**
   * What {@code #{key(args)}} writes as HTML ({@code t:utext}, {@code [(...)]}), or
   * {@code null} after reporting an error: the message, looked up with its arguments escaped as
   * text and cleaned at runtime by the fixed message policy ({@code Escapers.htmlMessage}). Links
   * are kept only if the bundle is unchecked or a value of the key has an {@code <a}; otherwise the
   * message is phrasing without links, which can stand inside a link too.
   */
  public static Emitted html(Expr.Message message, Scope scope, ExpressionCompiler expressions) {
    String args = args(message, scope, expressions);
    if (args == null) {
      return null;
    }
    MessageBundle bundle = expressions.env().messages();
    boolean links = !bundle.isChecked();
    for (String value : bundle.values(message.key()).values()) {
      links |= HtmlParser.asciiLower(value).contains("<a");
    }
    String code = "dev.tessera.runtime.Escapers.htmlMessage(ctx, " + JavaStrings.literal(message.key()) + ", "
        + args + ", " + links + ")";
    return Emitted.htmlMessage(message.pos(), code, links);
  }

  /**
   * The {@code new Object[] {...}} of compiled arguments ({@code Messages.NO_ARGS} for none), or {@code null} after reporting an
   * unknown key or a bad argument.
   */
  private static String args(Expr.Message message, Scope scope, ExpressionCompiler expressions) {
    boolean ok = true;
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
    } else if (bundle.isChecked()) {
      Set<String> gaps = bundle.firstFallbackGaps(key);
      if (!gaps.isEmpty()) {
        env.diagnostics()
            .warning(
                message.pos(),
                "message key '" + key + "' is only in the default locale's files, not in the base file or in "
                    + String.join(", ", gaps) + " (or the files they fall back to): Spring Boot's default"
                    + " ResourceBundleMessageSource falls back to the default locale only for a locale with no"
                    + " file of its own, so it does not find the key in those locales"
                    + " (ReloadableResourceBundleMessageSource does); add the key to them or to the base file");
      }
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
    return args.isEmpty() ? "dev.tessera.runtime.Messages.NO_ARGS" : "new java.lang.Object[] {" + String.join(", ", args) + "}";
  }
}
