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
          String before = literal.toString();
          inMustache = html && inMustache(inMustache, before);
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
          String markup = markupBefore(text.context(), before);
          if (markup != null) {
            String form = escaped ? "[[...]]" : "[(...)]";
            ctx.diagnostics()
                .error(
                    pos,
                    text.context() == OutputContext.SCRIPT
                        ? form + " may not follow '<!-' directly in <script>: a negative number would make it"
                            + " '<!--'; write a space after '<'"
                        : form + " may not follow '" + markup + "' directly: the browser would read the value as"
                            + " markup; write &lt; for a literal '<'");
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

  /** Offset of the first {@code [(...)]} in {@code raw} that {@link #render} writes as raw HTML, or -1. */
  public static int firstUnescaped(String raw) {
    int i = 0;
    while (i < raw.length()) {
      boolean escaped = raw.startsWith("[[", i);
      if (escaped || raw.startsWith("[(", i)) {
        int end = findEnd(raw, i + 2, escaped ? "]]" : ")]");
        if (end >= 0) {
          if (!escaped) {
            return i;
          }
          i = end + 2;
          continue;
        }
      }
      i++;
    }
    return -1;
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

  /**
   * The ending of {@code before} (the static text right before an inlined expression) that the
   * value would continue into markup in {@code context}, or null. In TEXT and RCDATA these are
   * {@code <}, {@code <!}, {@code <!-}, {@code <?} and {@code </} with any ASCII letters after
   * it: the tokenizer reads them as text (in TEXT, {@code <} or {@code </} and a letter would start
   * a tag), but a browser reads {@code <} and a value starting with a letter as a tag, and in RCDATA
   * {@code </text} and a value {@code area} (even {@code </textarea} and an empty value) as the
   * end tag. In SCRIPT it is {@code <!-}: a negative number makes it {@code <!--}, and a browser
   * then hides the next {@code </script>} after a {@code <script} in the code.
   */
  static String markupBefore(OutputContext context, String before) {
    int start =
        context == OutputContext.SCRIPT
            ? scriptMarkupStart(before)
            : context == OutputContext.TEXT || context == OutputContext.RCDATA ? htmlMarkupStart(before) : -1;
    return start < 0 ? null : before.substring(start);
  }

  /*@ // A markup ending of t starts at t[j] (docs/rule-contracts.md, htmlMarkupStart): t[j..] is
    @ // "<", "<!", "<!-", "<?", or "</" followed only by ASCII letters.
    @ public normal_behavior
    @   ensures \result <==> (0 <= j && j < t.length && t[j] == '<'
    @       && (j + 1 == t.length
    @           || (j + 2 == t.length && (t[j + 1] == '!' || t[j + 1] == '?'))
    @           || (j + 3 == t.length && t[j + 1] == '!' && t[j + 2] == '-')
    @           || (t[j + 1] == '/' && letters(t, j + 2))));
    @ public static model no_state helper boolean markupEndingAt(\string t, int j);
    @
    @ // t[from..] is ASCII letters only.
    @ public normal_behavior
    @   ensures \result <==> (\forall int k; from <= k && k < t.length;
    @       'a' <= t[k] && t[k] <= 'z' || 'A' <= t[k] && t[k] <= 'Z');
    @ public static model no_state helper boolean letters(\string t, int from);
    @*/

  /** In SCRIPT, the index of a final {@code <!-} in {@code before}, or -1. */
  /*@ normal_behavior
    @   requires before != null;
    @   ensures \result == (before.length() >= 3 && before.chars[before.length() - 3] == '<'
    @       && before.chars[before.length() - 2] == '!' && before.chars[before.length() - 1] == '-'
    @       ? before.length() - 3 : -1);
    @ pure
    @*/
  static int scriptMarkupStart(String before) {
    int n = before.length();
    return n >= 3 && before.charAt(n - 3) == '<' && before.charAt(n - 2) == '!' && before.charAt(n - 1) == '-'
        ? n - 3
        : -1;
  }

  /**
   * In TEXT and RCDATA, the index of the last {@code <} in {@code before} if the text from there
   * is one of the endings {@link #markupBefore} lists, or -1.
   */
  /*@ normal_behavior
    @   requires before != null;
    @   ensures \result == -1 || 0 <= \result && \result < before.length();
    @   ensures \result >= 0 ==> markupEndingAt(before.chars, \result);
    @   ensures \result == -1 ==> (\forall int j; 0 <= j && j < before.length(); !markupEndingAt(before.chars, j));
    @ pure
    @*/
  static int htmlMarkupStart(String before) {
    int n = before.length();
    int lt = n - 1;
    //@ maintaining -1 <= lt && lt < n;
    //@ maintaining (\forall int k; lt < k && k < n; before.chars[k] != '<');
    //@ loop_assigns lt;
    //@ decreases lt + 1;
    while (lt >= 0 && before.charAt(lt) != '<') {
      lt--;
    }
    if (lt < 0) {
      //@ assert (\forall int j; 0 <= j && j < n; before.chars[j] != '<');
      //@ assert (\forall int j; 0 <= j && j < n; !markupEndingAt(before.chars, j));
      return -1;
    }
    if (lt + 1 == n) {
      return lt;
    }
    char c = before.charAt(lt + 1);
    if (lt + 2 == n && (c == '!' || c == '?') || lt + 3 == n && c == '!' && before.charAt(lt + 2) == '-') {
      return lt;
    }
    if (c != '/') {
      return -1;
    }
    //@ maintaining lt + 2 <= i && i <= n;
    //@ maintaining (\forall int k; lt + 2 <= k && k < i;
    //@     'a' <= before.chars[k] && before.chars[k] <= 'z' || 'A' <= before.chars[k] && before.chars[k] <= 'Z');
    //@ loop_assigns i;
    //@ decreases n - i;
    for (int i = lt + 2; i < n; i++) {
      char d = before.charAt(i);
      if (!((d >= 'a' && d <= 'z') || (d >= 'A' && d <= 'Z'))) {
        return -1;
      }
    }
    return lt;
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
