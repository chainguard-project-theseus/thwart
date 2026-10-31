package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.types.ExpressionCompiler.isDeclared;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.Emitted;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.html.HtmlParser;
import dev.tessera.compiler.html.ParsingContexts;
import dev.tessera.compiler.messages.MessageCompiler;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.Node;
import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.model.Text;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.Scope;
import dev.tessera.compiler.verify.Skeleton;
import dev.tessera.runtime.SafeHtml;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.lang.model.type.TypeKind;

/**
 * Text inlining: {@code [[expr]]} (escaped for the text node's context: TEXT via {@code Escapers.text},
 * or {@code Escapers.rcdata} where the verifier finds RCDATA (#378), SCRIPT via {@code Escapers.javaScript},
 * or {@code Escapers.json} in a JSON {@code <script>})
 * and {@code [(expr)]} (SafeHtml or a checked bundle message, TEXT only).
 * STYLE/FORBIDDEN contexts with inlined expressions are build errors, and so is a
 * {@code [[expr]]} in JavaScript {@code <script>} text that is not the value of a declaration in
 * the script's prelude ({@link #checkPrelude}). {@link #checkBraces} holds static text to the
 * client-side interpolation rule (#248, #336).
 *
 * <p>Also holds the output helpers shared with {@code t:text} and {@code t:utext}.
 */
public final class TextInlining {
  private TextInlining() {}

  private static final String SAFE_HTML = SafeHtml.class.getName();

  /**
   * Statements that render {@code text}, or {@code null} if it has no inlined expressions (the
   * generator then emits it verbatim).
   */
  public static Emitted render(CodegenContext ctx, Text text, Scope scope) {
    String raw = text.raw();
    if (!raw.contains("[[") && !raw.contains("[(")) {
      return null;
    }
    List<Emitted> code = new ArrayList<>();
    StringBuilder literal = new StringBuilder();
    // Offset in raw of the first char of literal.
    int literalStart = 0;
    boolean found = false;
    String parsing = ctx.template().parsingContexts().text(text);
    boolean rawText = ctx.template().parsingContexts().insideRawText(text);
    if (text.context() == OutputContext.SCRIPT) {
      // Reported only: the code is still emitted, and the skeleton keeps each hole for the verifier (#367).
      if (ctx.inJsonScript()) {
        checkJsonScript(ctx, text);
      } else {
        checkPrelude(ctx, text);
      }
    }
    int i = 0;
    while (i < raw.length()) {
      int end = holeEnd(raw, i);
      if (end >= 0) {
        boolean escaped = raw.charAt(i + 1) == '[';
        found = true;
        SourcePos pos = text.pos().advance(raw, i + 2);
        String before = literal.toString();
        flush(code, literal, text.pos().advance(raw, literalStart));
        literalStart = end + 2;
        String markup = markupBefore(text.context(), before);
        if (markup != null) {
          String form = escaped ? "[[...]]" : "[(...)]";
          ctx.diagnostics()
              .error(
                  pos,
                  form + " may not follow '" + markup + "' directly: the browser would read the value as markup;"
                      + " write &lt; for a literal '<'");
          // Still emitted: nothing is written on error, and the skeleton keeps the hole for the
          // verifier's own check (#367).
        }
        code.add(inline(ctx, text.context(), parsing, rawText, raw.substring(i + 2, end), pos, escaped, scope));
        i = end + 2;
        continue;
      }
      literal.append(raw.charAt(i));
      i++;
    }
    if (!found) {
      return null;
    }
    flush(code, literal, text.pos().advance(raw, literalStart));
    return Emitted.concat(code.toArray(new Emitted[0]));
  }

  /** Offset of the first {@code [(...)]} in {@code raw} that {@link #render} writes as raw HTML, or -1. */
  public static int firstUnescaped(String raw) {
    int i = 0;
    while (i < raw.length()) {
      int end = holeEnd(raw, i);
      if (end >= 0 && raw.charAt(i + 1) == '(') {
        return i;
      }
      i = end >= 0 ? end + 2 : i + 1;
    }
    return -1;
  }

  /** Whether {@code e} is written without its tags: {@code <t:block>} or a static {@code t:remove="tag"}. */
  public static boolean tagNotRendered(Element e) {
    return e.name().equals("t:block")
        || e.attribute("t:remove").map(Attribute::value).map(JavaStrings::trimJava).filter("tag"::equals).isPresent();
  }

  /** Attributes whose element's own text never renders: a value or fragment replaces it. */
  private static final Set<String> REPLACES_TEXT = Backports.setOf("t:text", "t:utext", "t:insert", "t:replace", "t:errors");

  /**
   * Checks the client-side interpolation rule (#248, #336, #571) on the static text of {@code nodes}.
   * The runtime writes every brace of a value between two empty comments ({@code
   * Escapers.appendText}), so only static text can form a {@code {{}, and the rule looks at each
   * static literal (the text between two values, tags or comments) on its own, never at its
   * neighbours:
   *
   * <ul>
   *   <li>in element text (CDATA in foreign content included), a {@code {} or {@code }} is allowed
   *       only inside a complete {@code {{ ... }}} in one literal, the author's own Vue template;
   *   <li>in a {@code <title>} or {@code <textarea>}, which get {@code v-pre} once computed (#549),
   *       not even there;
   *   <li>in script text, which petite-vue reads, a {@code {{} is closed in the same literal.
   * </ul>
   */
  public static void checkBraces(CodegenContext ctx, List<Node> nodes, Element parent, boolean vPreHost) {
    boolean replaced = false;
    if (parent != null) {
      for (Attribute a : parent.attributes()) {
        replaced |= REPLACES_TEXT.contains(a.name());
      }
      String lower = HtmlParser.asciiLower(parent.name());
      vPreHost |= lower.equals("title") || lower.equals("textarea");
    }
    for (Node n : nodes) {
      if (n instanceof Element) {
        checkBraces(ctx, ((Element) n).children(), (Element) n, vPreHost);
      } else if (n instanceof Text && !replaced) {
        Text t = (Text) n;
        OutputContext c = t.context();
        if (c != OutputContext.TEXT && c != OutputContext.RCDATA && c != OutputContext.SCRIPT) {
          continue;
        }
        // A CDATA section's characters join the text around it, and its wrapper holds no brace.
        boolean refs = c != OutputContext.SCRIPT && !t.raw().startsWith("<![CDATA[");
        String raw = t.raw();
        int start = 0;
        for (int i = 0; i <= raw.length(); i++) {
          int end = i == raw.length() ? i : holeEnd(raw, i);
          if (end >= 0) {
            checkLiteral(ctx, t, start, raw.substring(start, i), refs, c != OutputContext.SCRIPT, vPreHost);
            start = end + 2;
            i = end + 1;
          }
        }
      }
    }
  }

  private static void checkLiteral(
      CodegenContext ctx, Text text, int start, String literal, boolean refs, boolean lone, boolean vPreHost) {
    String s = decode(literal, refs);
    int at = lone ? loneBrace(s, !vPreHost) : unclosed(s);
    if (at < 0) {
      return;
    }
    String message;
    if (!lone) {
      message = "a client-side {{ is not closed by }} in the same text: petite-vue would run a value written"
          + " after it in this script as code; close it before any [[...]]";
    } else if (vPreHost) {
      message = "'" + s.charAt(at) + "' in the static text of a <title> or <textarea> is not supported: Tessera"
          + " adds v-pre there once the content is computed, because a comment cannot break {{ in a value in it,"
          + " and v-pre would show a static {{ ... }} as text; write the whole content with Tessera, or bind it"
          + " with Vue";
    } else {
      message = "'" + s.charAt(at) + "' outside a complete {{ ... }} is not supported in element text: with a"
          + " brace from other text it could form a client-side {{ that Vue and petite-vue would run as code (a"
          + " character reference does not help). A static brace is allowed only in a {{ ... }} that opens and"
          + " closes before any [[...]], tag or comment; write the text as a value with [[...]] or t:text ([['{']])";
    }
    ctx.diagnostics().error(text.pos().advance(text.raw(), start + (s.equals(literal) ? at : 0)), message);
  }

  /**
   * The offset of the first '{' or '}' in {@code s} outside a complete {@code {{ ... }}} (with
   * {@code pairs}; without, of the first brace), or -1: the rule for static element text and
   * checked message text (#571).
   */
  public static int loneBrace(String s, boolean pairs) {
    for (int i = 0; i < s.length(); i++) {
      int close = pairs && s.startsWith("{{", i) ? s.indexOf("}}", i + 2) : -1;
      if (close >= 0) {
        i = close + 1;
      } else if (s.charAt(i) == '{' || s.charAt(i) == '}') {
        return i;
      }
    }
    return -1;
  }

  /** The offset of the first {@code {{} in {@code s} not closed by a {@code }}} after it, or -1. */
  public static int unclosed(String s) {
    for (int open = s.indexOf("{{"); open >= 0; open = s.indexOf("{{", s.indexOf("}}", open + 2) + 2)) {
      if (s.indexOf("}}", open + 2) < 0) {
        return open;
      }
    }
    return -1;
  }

  /** Text as the frameworks read it: with {@code refs}, decoded, brace character references included. */
  public static String decode(String literal, boolean refs) {
    if (!refs) {
      return literal;
    }
    return HtmlParser.decode(
        literal
            .replace("&lbrace;", "{")
            .replace("&lcub;", "{")
            .replace("&rbrace;", "}")
            .replace("&rcub;", "}"));
  }

  /**
   * The ending of {@code before} (the static text right before an inlined expression) that the
   * value would continue into markup in {@code context}, or null. In TEXT these are
   * {@code <}, {@code <!}, {@code <!-}, {@code <?} and {@code </} with any ASCII letters after
   * it: the tokenizer reads them as text (in TEXT, {@code <} or {@code </} and a letter would start
   * a tag), but a browser reads {@code <} and a value starting with a letter as a tag, and in RCDATA
   * {@code </text} and a value {@code area} (even {@code </textarea} and an empty value) as the
   * end tag. In SCRIPT, {@link #checkPrelude} allows only a declaration before a value.
   */
  private static String markupBefore(OutputContext context, String before) {
    if (context != OutputContext.TEXT) {
      return null;
    }
    int lt = before.lastIndexOf('<');
    if (lt < 0) {
      return null;
    }
    String end = before.substring(lt);
    if (end.equals("<") || end.equals("<!") || end.equals("<!-") || end.equals("<?")) {
      return end;
    }
    if (!end.startsWith("</")) {
      return null;
    }
    for (int i = 2; i < end.length(); i++) {
      char c = end.charAt(i);
      if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
        return null;
      }
    }
    return end;
  }

  /**
   * The script prelude rule (security-architecture.md §5.4) for the text {@code text} of a
   * JavaScript {@code <script>}: each {@code [[...]]} must be the whole value of a declaration
   * {@code const|let|var NAME = [[...]];} in a prelude at the top of the script, one value per
   * declaration, before any other code. Only ASCII spaces, tabs, line breaks and {@code //} line
   * comments may come before and between the declarations, and {@code NAME} is an ASCII identifier.
   * The code after the prelude is static. So the code around every value is fixed by this grammar,
   * not found by lexing JavaScript, and {@code Escapers.javaScript} writes each value in parentheses
   * as a whole JSON value. Reports the first {@code [[...]]} outside the prelude.
   */
  private static void checkPrelude(CodegenContext ctx, Text text) {
    String raw = text.raw();
    int i = 0;
    while (true) {
      i = skipBlank(raw, i);
      int end = declaration(raw, i);
      if (end < 0) {
        break;
      }
      i = end;
    }
    for (; i < raw.length(); i++) {
      int end = holeEnd(raw, i);
      if (end >= 0 && raw.charAt(i + 1) == '[') {
        ctx.diagnostics()
            .error(
                text.pos().advance(raw, i + 2),
                "[[...]] in a JavaScript <script> is only allowed as the whole value of a declaration at the"
                    + " top of the script, before any other code: const name = [[...]]; (one value per"
                    + " declaration, a name that is not a reserved word or location, only whitespace and //"
                    + " comments before it). Move the value into such a"
                    + " declaration at the top and use the name in the code below, or write it into a"
                    + " <script type=\"application/json\"> and read it with JSON.parse");
        return;
      }
      // [(...)] has its own error.
      i = end >= 0 ? end + 1 : i;
    }
  }

  /**
   * The rule for a JSON {@code <script>} ({@code application/json}, {@code application/ld+json}),
   * which a browser never runs: each {@code [[...]]} is a JSON value outside a {@code "..."} string,
   * and the text has no {@code /} outside strings (JSON has no comments). JSON's tokens are
   * context-free, so this is exact. Reports the first offender.
   */
  private static void checkJsonScript(CodegenContext ctx, Text text) {
    String raw = text.raw();
    boolean inString = false;
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      int end = holeEnd(raw, i);
      if (end >= 0) {
        if (inString && raw.charAt(i + 1) == '[') {
          ctx.diagnostics()
              .error(
                  text.pos().advance(raw, i + 2),
                  "[[...]] is inside a quoted string of a JSON <script>, but it writes a complete JSON value"
                      + " with its own quotes; remove the quotes: {\"name\": [[...]]}");
          return;
        }
        i = end + 1;
      } else if (inString) {
        if (c == '\\') {
          i++;
        } else if (c == '"') {
          inString = false;
        }
      } else if (c == '"') {
        inString = true;
      } else if (c == '/') {
        ctx.diagnostics()
            .error(
                text.pos().advance(raw, i),
                "'/' outside a string of a JSON <script> with a [[...]]: JSON has no comments or division;"
                    + " write only JSON around the values");
        return;
      }
    }
  }

  /** The offset of the first character at or after {@code i} that isn't an ASCII space, tab, line break or in a {@code //} comment. */
  private static int skipBlank(String raw, int i) {
    while (i < raw.length()) {
      char c = raw.charAt(i);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        i++;
      } else if (raw.startsWith("//", i)) {
        // Stops at a value in the comment, which is then outside the prelude.
        while (i < raw.length() && !isLineBreak(raw.charAt(i)) && holeEnd(raw, i) < 0) {
          i++;
        }
        if (i < raw.length() && !isLineBreak(raw.charAt(i))) {
          return i;
        }
      } else {
        break;
      }
    }
    return i;
  }

  /**
   * Names a declaration may not use: JavaScript's reserved words, the strict-mode and module ones,
   * and {@code location}. In a classic script, {@code var location = v} declares nothing new (the
   * global object already has the property) and assigns {@code v} to {@code window.location},
   * whose setter navigates, to a {@code javascript:} URL too.
   */
  private static final Set<String> RESERVED = Backports.setOf(
      "location",
      "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do",
      "else", "enum", "export", "extends", "false", "finally", "for", "function", "if", "implements", "import",
      "in", "instanceof", "interface", "let", "new", "null", "package", "private", "protected", "public",
      "return", "static", "super", "switch", "this", "throw", "true", "try", "typeof", "var", "void", "while",
      "with", "yield");

  /**
   * The offset after the declaration {@code const|let|var NAME = [[...]];} at {@code i}, or -1. At
   * least one space or tab follows the keyword, and {@code NAME} is not a {@link #RESERVED} word.
   */
  private static int declaration(String raw, int i) {
    int j = -1;
    for (String keyword : new String[] {"const", "let", "var"}) {
      if (raw.startsWith(keyword, i)) {
        j = i + keyword.length();
      }
    }
    if (j < 0) {
      return -1;
    }
    int name = spaces(raw, j);
    if (name == j) {
      return -1;
    }
    j = name;
    while (j < raw.length() && isNameChar(raw.charAt(j), j > name)) {
      j++;
    }
    if (j == name || RESERVED.contains(raw.substring(name, j))) {
      return -1;
    }
    j = spaces(raw, j);
    if (!raw.startsWith("=", j)) {
      return -1;
    }
    j = spaces(raw, j + 1);
    int end = raw.startsWith("[[", j) ? holeEnd(raw, j) : -1;
    if (end < 0) {
      return -1;
    }
    j = spaces(raw, end + 2);
    return raw.startsWith(";", j) ? j + 1 : -1;
  }

  /** The offset after the ASCII spaces and tabs at {@code i}. */
  private static int spaces(String raw, int i) {
    while (i < raw.length() && (raw.charAt(i) == ' ' || raw.charAt(i) == '\t')) {
      i++;
    }
    return i;
  }

  /** Whether {@code c} is an ASCII identifier character: a letter, {@code _}, {@code $}, or with {@code digit} a digit. */
  private static boolean isNameChar(char c, boolean digit) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$' || (digit && c >= '0' && c <= '9');
  }

  private static boolean isLineBreak(char c) {
    return c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029';
  }

  /** The offset of the {@code ]]} or {@code )]} of a {@code [[...]]} or {@code [(...)]} that starts at {@code i}, or -1. */
  private static int holeEnd(String raw, int i) {
    boolean escaped = raw.startsWith("[[", i);
    return escaped || raw.startsWith("[(", i) ? findEnd(raw, i + 2, escaped ? "]]" : ")]") : -1;
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

  private static void flush(List<Emitted> code, StringBuilder literal, SourcePos pos) {
    if (literal.length() != 0) {
      code.add(Emitted.text(literal.toString(), pos).then("\n"));
      literal.setLength(0);
    }
  }

  private static Emitted inline(
      CodegenContext ctx, OutputContext context, String parsing, boolean rawText, String source, SourcePos pos,
      boolean escaped, Scope scope) {
    String form = escaped ? "[[...]]" : "[(...)]";
    if (context.escaper() == null) {
      ctx.diagnostics().error(pos, form + " is not allowed " + where(context));
      return Emitted.EMPTY;
    }
    if (!escaped && context != OutputContext.TEXT) {
      ctx.diagnostics()
          .error(pos, "[(...)] is only allowed in element text; inside <script> use [[...]], which outputs JSON");
      return Emitted.EMPTY;
    }
    if (!escaped && !requireHtmlContent(ctx, parsing, rawText, pos, form)) {
      return Emitted.EMPTY;
    }
    Expr e = ctx.parser().parseExpression(source, pos, ctx.diagnostics());
    if (e == null) {
      return Emitted.EMPTY;
    }
    return escaped ? valueStatement(ctx, e, context, scope) : safeHtmlStatement(ctx, e, form, scope);
  }

  /**
   * Reports and returns false unless raw HTML written by {@code form} at {@code pos} lands in HTML
   * content: {@code parsing} is its {@link ParsingContexts} context. A {@code SafeHtml} or an HTML
   * message is HTML content, like a {@code Fragment} value. {@code rawText}: inside a {@link
   * ParsingContexts#rawTextElement}, where the raw-text rule (TemplateGenerator) reports it instead.
   */
  static boolean requireHtmlContent(CodegenContext ctx, String parsing, boolean rawText, SourcePos pos, String form) {
    if (parsing.equals(ParsingContexts.HTML) || rawText) {
      return true;
    }
    ctx.diagnostics()
        .error(
            pos,
            form + " with a SafeHtml value or HTML message is only allowed in HTML content, not in "
                + ParsingContexts.describe(parsing) + " content, where a browser parses its markup differently: write"
                + " the markup in the template, or move the " + form + " into HTML content");
    return false;
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
   * A statement writing {@code e} escaped for {@code context} (TEXT or SCRIPT), or nothing
   * after reporting an error. The value must be view-safe; in SCRIPT it must also be
   * JSON-serializable ({@link ScriptJson}).
   */
  static Emitted valueStatement(CodegenContext ctx, Expr e, OutputContext context, Scope scope) {
    CompiledExpr c = ctx.expressions().compileOutput(e, scope);
    if (c == null) {
      return Emitted.EMPTY;
    }
    if (context == OutputContext.SCRIPT) {
      Emitted json = ScriptJson.statement(ctx, c);
      return json == null ? Emitted.EMPTY : json;
    }
    Skeleton.Escaper escaper = Skeleton.Escaper.forMethod(context.escaper());
    if (isDeclared(c.type(), "java.util.Optional")) {
      String o = ctx.freshName("opt");
      return Emitted.wrap(
          "{ java.util.Optional<?> " + o + " = " + c.code() + "; ",
          Emitted.hole(escaper, ctx.hostElement(), null, c.pos(), o + " == null ? null : " + o + ".orElse(null)"),
          " }\n");
    }
    return Emitted.hole(escaper, ctx.hostElement(), null, c.pos(), c.code()).then("\n");
  }

  /**
   * A statement writing {@code e} as raw HTML, or nothing after reporting an error. {@code e} must
   * have static type {@code SafeHtml} or be a bundle message {@code #{...}} that passes the
   * build-time markup check; {@code form} names the construct in messages.
   */
  static Emitted safeHtmlStatement(CodegenContext ctx, Expr e, String form, Scope scope) {
    if (e instanceof Expr.Message) {
      // A bundle message is developer-authored markup, checked at build time (#236).
      String message = MessageCompiler.htmlValue((Expr.Message) e, form, scope, ctx.expressions());
      return message == null
          ? Emitted.EMPTY
          : Emitted.htmlMessage(
                  message, MessageCompiler.htmlSkeleton((Expr.Message) e, ctx.expressions().env(), ctx.hostElement()))
              .then("\n");
    }
    CompiledExpr c = ctx.expressions().compile(e, scope);
    if (c == null) {
      return Emitted.EMPTY;
    }
    if (!isDeclared(c.type(), SAFE_HTML)) {
      String type = c.type().getKind() == TypeKind.NULL ? "null" : c.type().toString();
      ctx.diagnostics()
          .error(
              c.pos(),
              form + " needs a value of type " + SAFE_HTML + ", but the expression has type " + type
                  + ": use t:text or [[...]] (escaped), or build a SafeHtml in trusted Java code");
      return Emitted.EMPTY;
    }
    return Emitted.safeHtml(c.pos(), c.code()).then("\n");
  }
}
