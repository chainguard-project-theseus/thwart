package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.types.ExpressionCompiler.isDeclared;

import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.html.HtmlParser;
import dev.tessera.compiler.messages.MessageCompiler;
import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.model.Text;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.Scope;
import dev.tessera.runtime.SafeHtml;
import javax.lang.model.type.TypeKind;

/**
 * Text inlining: {@code [[expr]]} (escaped for the text node's context: TEXT via
 * {@code Escapers.text}, RCDATA via {@code Escapers.rcdata}, SCRIPT via {@code Escapers.json})
 * and {@code [(expr)]} (SafeHtml or a checked bundle message, TEXT and RCDATA only).
 * STYLE/FORBIDDEN contexts with inlined expressions are build errors, and so is an expression
 * inside a static client-side {@code {{ ... }}} interpolation in TEXT or RCDATA (#248).
 *
 * <p>Also holds the output helpers shared with {@code t:text} and {@code t:utext}.
 */
public final class TextInlining {
  private TextInlining() {}

  private static final String ESCAPERS = "dev.tessera.runtime.Escapers";
  private static final String SAFE_HTML = SafeHtml.class.getName();

  /**
   * Statements that render {@code text}, or {@code null} if it has no inlined expressions (the
   * generator then emits it verbatim).
   */
  public static String render(CodegenContext ctx, Text text, Scope scope) {
    String raw = text.raw();
    if (!raw.contains("[[") && !raw.contains("[(")) {
      return null;
    }
    StringBuilder code = new StringBuilder();
    StringBuilder literal = new StringBuilder();
    boolean found = false;
    boolean html = text.context() == OutputContext.TEXT || text.context() == OutputContext.RCDATA;
    boolean inMustache = false;
    int i = 0;
    while (i < raw.length()) {
      boolean escaped = raw.startsWith("[[", i);
      if (escaped || raw.startsWith("[(", i)) {
        int end = findEnd(raw, i + 2, escaped ? "]]" : ")]");
        if (end >= 0) {
          found = true;
          SourcePos pos = text.pos().advance(raw, i + 2);
          inMustache = html && inMustache(inMustache, literal.toString());
          flush(code, literal);
          if (inMustache) {
            ctx.diagnostics()
                .error(
                    pos,
                    (escaped ? "[[...]]" : "[(...)]") + " is inside a client-side {{ ... }} interpolation: Vue or"
                        + " AngularJS would run the value as code, whatever the escaping. Pass the value in a"
                        + " data-* attribute or a <script> with [[...]] and read it from there");
            i = end + 2;
            continue;
          }
          code.append(inline(ctx, text.context(), raw.substring(i + 2, end), pos, escaped, scope));
          i = end + 2;
          continue;
        }
      }
      literal.append(raw.charAt(i));
      i++;
    }
    if (!found) {
      return null;
    }
    flush(code, literal);
    return code.toString();
  }

  /**
   * Whether a client-side {@code {{} interpolation is open after the static text {@code literal},
   * given whether one was open before it. Frameworks read the decoded text, so character
   * references for braces count. As in Vue and AngularJS, the first {@code }}} after {@code {{}
   * closes it.
   */
  static boolean inMustache(boolean open, String literal) {
    String s =
        HtmlParser.decode(
            literal
                .replace("&lbrace;", "{")
                .replace("&lcub;", "{")
                .replace("&rbrace;", "}")
                .replace("&rcub;", "}"));
    int i = 0;
    while (true) {
      int next = s.indexOf(open ? "}}" : "{{", i);
      if (next < 0) {
        return open;
      }
      open = !open;
      i = next + 2;
    }
  }

  /** Index of the first {@code close} at or after {@code from} outside a '...' string, or -1. */
  private static int findEnd(String raw, int from, String close) {
    boolean inString = false;
    for (int i = from; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (inString) {
        if (c == '\\') {
          i++;
        } else if (c == '\'') {
          inString = false;
        }
      } else if (c == '\'') {
        inString = true;
      } else if (raw.startsWith(close, i)) {
        return i;
      }
    }
    return -1;
  }

  private static void flush(StringBuilder code, StringBuilder literal) {
    if (literal.length() != 0) {
      code.append("out.write(").append(JavaStrings.literal(literal.toString())).append(");\n");
      literal.setLength(0);
    }
  }

  private static String inline(
      CodegenContext ctx, OutputContext context, String source, SourcePos pos, boolean escaped, Scope scope) {
    String form = escaped ? "[[...]]" : "[(...)]";
    if (context.escaper() == null) {
      ctx.diagnostics().error(pos, form + " is not allowed " + where(context));
      return "";
    }
    if (!escaped && context != OutputContext.TEXT && context != OutputContext.RCDATA) {
      ctx.diagnostics()
          .error(pos, "[(...)] is only allowed in element text; inside <script> use [[...]], which outputs JSON");
      return "";
    }
    Expr e = ctx.parser().parseExpression(source, pos, ctx.diagnostics());
    if (e == null) {
      return "";
    }
    return escaped ? valueStatement(ctx, e, context, scope) : safeHtmlStatement(ctx, e, form, scope);
  }

  /** Where an expression cannot go, for error messages. */
  static String where(OutputContext context) {
    switch (context) {
      case STYLE:
        return "inside <style>: CSS cannot be escaped safely; compute a class name instead";
      case FORBIDDEN:
        return "inside a <script> whose type is not JavaScript or JSON (or is set by t:type)";
      default:
        return "in " + context + " context: move the expression into element text or a t: attribute";
    }
  }

  /**
   * A statement writing {@code e} escaped for {@code context} (TEXT, RCDATA or SCRIPT), or "" after
   * reporting an error. The value must be view-safe; in SCRIPT it must also be JSON-serializable
   * ({@link ScriptJson}).
   */
  static String valueStatement(CodegenContext ctx, Expr e, OutputContext context, Scope scope) {
    CompiledExpr c = ctx.expressions().compileOutput(e, scope);
    if (c == null) {
      return "";
    }
    if (context == OutputContext.SCRIPT) {
      String json = ScriptJson.statement(ctx, c);
      return json == null ? "" : json;
    }
    if (isDeclared(c.type(), "java.util.Optional")) {
      String o = ctx.freshName("opt");
      return "{ java.util.Optional<?> " + o + " = " + c.code() + "; " + ESCAPERS + "." + context.escaper()
          + "(out, " + o + " == null ? null : " + o + ".orElse(null)); }\n";
    }
    return ESCAPERS + "." + context.escaper() + "(out, " + c.code() + ");\n";
  }

  /**
   * A statement writing {@code e} as raw HTML, or "" after reporting an error. {@code e} must
   * have static type {@code SafeHtml} or be a bundle message {@code #{...}} that passes the
   * build-time markup check; {@code form} names the construct in messages.
   */
  static String safeHtmlStatement(CodegenContext ctx, Expr e, String form, Scope scope) {
    if (e instanceof Expr.Message) {
      // A bundle message is developer-authored markup, checked at build time (#236).
      String statement = MessageCompiler.htmlStatement((Expr.Message) e, form, scope, ctx.expressions());
      return statement == null ? "" : statement;
    }
    CompiledExpr c = ctx.expressions().compile(e, scope);
    if (c == null) {
      return "";
    }
    if (!isDeclared(c.type(), SAFE_HTML)) {
      String type = c.type().getKind() == TypeKind.NULL ? "null" : c.type().toString();
      ctx.diagnostics()
          .error(
              c.pos(),
              form + " needs a value of type " + SAFE_HTML + ", but the expression has type " + type
                  + ": use t:text or [[...]] (escaped), or build a SafeHtml in trusted Java code");
      return "";
    }
    return ESCAPERS + ".html(out, " + c.code() + ");\n";
  }
}
