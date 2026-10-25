package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.types.ExpressionCompiler.isDeclared;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.Emitted;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.html.HtmlParser;
import dev.tessera.compiler.html.ParsingContexts;
import dev.tessera.compiler.messages.MessageCompiler;
import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.model.Text;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.Scope;
import dev.tessera.compiler.verify.Skeleton;
import dev.tessera.runtime.SafeHtml;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.lang.model.type.TypeKind;

/**
 * Text inlining: {@code [[expr]]} (escaped for the text node's context: TEXT via
 * {@code Escapers.text}, RCDATA via {@code Escapers.rcdata}, SCRIPT via {@code Escapers.json})
 * and {@code [(expr)]} (SafeHtml or a checked bundle message, TEXT and RCDATA only).
 * STYLE/FORBIDDEN contexts with inlined expressions are build errors, and so is an expression
 * inside a static client-side {@code {{ ... }}} interpolation in TEXT or RCDATA (#248), and a
 * {@code [[expr]]} in SCRIPT that is not in a JavaScript expression position: inside a string
 * literal, comment or regex literal (#281, #315).
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
    boolean html = text.context() == OutputContext.TEXT || text.context() == OutputContext.RCDATA;
    String parsing = ctx.template().parsingContexts().text(text);
    boolean rawText = ctx.template().parsingContexts().insideRawText(text);
    boolean inMustache = false;
    JsLexState js = text.context() == OutputContext.SCRIPT ? new JsLexState() : null;
    int lastSlash = -1;
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
          flush(code, literal, text.pos().advance(raw, literalStart));
          literalStart = end + 2;
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
            // Still emitted: nothing is written on error, and the skeleton keeps the hole for the
            // verifier's own check (#367).
          }
          String misplaced =
              escaped && js != null ? misplacedInScript(js, text.pos().advance(raw, Math.max(lastSlash, 0))) : null;
          if (misplaced != null) {
            ctx.diagnostics().error(pos, misplaced);
            i = end + 2;
            continue;
          }
          code.add(inline(ctx, text.context(), parsing, rawText, raw.substring(i + 2, end), pos, escaped, scope));
          if (js != null) {
            js.expression();
          }
          i = end + 2;
          continue;
        }
      }
      if (js != null) {
        js.feed(raw.charAt(i));
        if (raw.charAt(i) == '/' && !js.unsure()) {
          lastSlash = i;
        }
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

  /** Offset of the first {@code [[...]]} or {@code [(...)]} in {@code raw} that {@link #render} writes as an expression, or -1. */
  public static int firstHole(String raw) {
    for (int i = 0; i < raw.length(); i++) {
      boolean escaped = raw.startsWith("[[", i);
      if ((escaped || raw.startsWith("[(", i)) && findEnd(raw, i + 2, escaped ? "]]" : ")]") >= 0) {
        return i;
      }
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
  private static String markupBefore(OutputContext context, String before) {
    if (context == OutputContext.SCRIPT) {
      return before.endsWith("<!-") ? "<!-" : null;
    }
    if (context != OutputContext.TEXT && context != OutputContext.RCDATA) {
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
   * The error for a {@code [[...]]} where {@code js} is, or null in an expression position: in a
   * {@code <script>} the value is a complete JSON value, so it goes only where a JavaScript
   * expression goes, not inside a string, comment or regular expression literal (#281, #315).
   */
  private static String misplacedInScript(JsLexState js, SourcePos slash) {
    JsLexState.Position position = js.position();
    if (js.unsure()) {
      return "[[...]] comes after a '/' at " + slash.line() + ":" + slash.column() + " that could be a"
          + " division or start a regular expression, so it is unclear where the value would be written; make"
          + " that '/' unambiguous: wrap a regular expression in parentheses, (/.../), or the left operand of a"
          + " division, (...) / 2";
    }
    switch (position) {
      case STRING:
        char q = js.quote();
        return "[[...]] is inside a quoted JavaScript string (" + q + "..." + q + "), but it writes a"
            + " complete JSON value with its own quotes, which would close a \"...\" string and run"
            + " as code, or end up quoted twice; remove the quotes, e.g. var a = [[...]]; or \"Hello \" + [[...]]";
      case COMMENT:
        return "[[...]] is inside a JavaScript comment (" + js.commentOpener() + "), so its value is written"
            + " inside the comment and the page never uses it; move it out of the comment: Thymeleaf's"
            + " natural-template var a = /*[[${x}]]*/ 'd'; becomes var a = [[${x}]];";
      case REGEX:
        return "[[...]] is inside a JavaScript regular expression literal (/.../), but it writes a complete"
            + " JSON value with its own quotes, and its characters would change or break the pattern; build"
            + " the pattern from the value in code, e.g. new RegExp([[...]])";
      default:
        return null;
    }
  }

  /**
   * Minimal JavaScript lexical state over the literal text of a {@code <script>}, enough to tell
   * whether a {@code [[...]]} sits in an expression position ({@link #position}): not inside a
   * string literal ({@code "..."}, {@code '...'} or template-literal text), where the JSON value's
   * own quotes would close the string or land inside it, a comment, where the page would never use
   * the value, or a regex literal, whose pattern the value would change. {@code ${...}} code in a
   * template literal is an expression position. Fed only the static text; {@link #expression}
   * stands for each inlined value. Annex B comments count too: {@code -->} after only whitespace and comments on
   * its line, and {@code #!} at the very start (a hashbang); either is a comment or a syntax error
   * in a browser, so taking it for a comment is safe.
   *
   * <p>As in Go's {@code html/template}, whether a {@code /} starts a regex or is a division is
   * decided from the previous significant token: a regex follows an operator, punctuator or one of
   * {@link #KEYWORDS_BEFORE_REGEX} (not as a property name: a word right after a single
   * {@code .}), or a spread {@code ...}; a division follows an identifier, a number, a literal,
   * {@code )}, {@code ]} or an inlined value; but after the {@code )} that closes the condition of
   * {@code if}, {@code while}, {@code for}, {@code for await} or {@code with} a regex follows.
   * Where the tokens cannot tell (after a closing brace: a block or an object literal; after
   * {@code ++} or {@code --}; after {@code of}, {@code yield} or {@code await}, which may be
   * identifiers), the lexer is {@link #unsure} and every {@code [[...]]} after that {@code /} is an
   * error, so a guess never leads to accepting a value (security-architecture.md, section 9).
   */
  static final class JsLexState {
    private enum S { CODE, SLASH, DQ, SQ, TPL, LINE_COMMENT, BLOCK_COMMENT, REGEX, REGEX_CLASS }

    /** Last significant char of code after which a {@code /} starts a regex. */
    private static final String BEFORE_REGEX = "(,=:[!&|?{};+-*%<>~^";

    private static final Set<String> KEYWORDS_BEFORE_REGEX =
        Backports.setOf(
            "return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw", "case", "do", "else",
            "yield", "await", "default", "extends");

    /** Words that are keywords only in some places, so a {@code /} after them may be a division too. */
    private static final Set<String> CONTEXTUAL_KEYWORDS = Backports.setOf("of", "yield", "await");

    /** Keywords whose parenthesized condition is followed by a statement, so a regex may follow its ')'. */
    private static final Set<String> KEYWORDS_BEFORE_CONDITION = Backports.setOf("if", "while", "for", "with");

    private S s = S.CODE;
    private boolean escape;
    /** A backslash escaped a CR in a string literal, so a following LF continues the line too. */
    private boolean escapedCr;
    private char prevSignificant = '(';
    /** The identifier (or number) that {@code prevSignificant} ends, if it is one. */
    private final StringBuilder word = new StringBuilder();
    private boolean wordOpen;
    private char prev;
    private int braceDepth;
    /** Brace depths at which an open {@code ${} of a template literal started. */
    private final ArrayDeque<Integer> tplBraces = new ArrayDeque<>();
    /** For each open '(': whether it starts the condition of an if, while, for or with. */
    private final ArrayDeque<Boolean> parens = new ArrayDeque<>();
    /** Only whitespace and comments since the start of the text or the last line break. */
    private boolean lineStart = true;
    /** Dashes seen at the start of a line, towards an Annex B "-->" comment. */
    private int dashes;
    /** Chars fed so far, inlined values included; "#!" is a hashbang only at the very start. */
    private int fed;
    private String commentOpener = "";
    /** The word before {@link #word}, if only whitespace separates them ("for" in "for await"). */
    private String previousWord = "";
    /** {@link #word} directly follows a single '.', so it is a property name, never a keyword. */
    private boolean wordAfterDot;
    /** Consecutive '.' chars ending at {@code prevSignificant}: 3 is a spread. */
    private int dots;
    /** {@code prevSignificant} is the second char of {@code ++} or {@code --}. */
    private boolean incDec;
    /** A {@code /} could have been a division or a regex; nothing after it can be trusted. */
    private boolean unsure;

    enum Position { CODE, STRING, COMMENT, REGEX }

    /** Where the next char would be; a {@code /} before it is decided first (#281, #315). */
    Position position() {
      if (s == S.SLASH) {
        s = slashStartsRegex() ? S.REGEX : S.CODE;
      }
      switch (s) {
        case DQ:
        case SQ:
        case TPL:
          return Position.STRING;
        case LINE_COMMENT:
        case BLOCK_COMMENT:
          return Position.COMMENT;
        case REGEX:
        case REGEX_CLASS:
          return Position.REGEX;
        default:
          return Position.CODE;
      }
    }

    /** How the comment the lexer is in started: {@code //}, a block comment, {@code -->} or {@code #!}. */
    String commentOpener() {
      return commentOpener;
    }

    char quote() {
      return s == S.DQ ? '"' : s == S.SQ ? '\'' : '`';
    }

    /** An inlined value: inside code it is an expression, so a {@code /} after it is division. */
    void expression() {
      if (s == S.SLASH) {
        s = slashStartsRegex() ? S.REGEX : S.CODE;
      }
      if (s == S.CODE) {
        value();
      }
      escape = false;
      escapedCr = false;
      prev = 0;
      lineStart = false;
      fed++;
    }

    void feed(char c) {
      char p = prev;
      prev = c;
      int index = fed++;
      switch (s) {
        case DQ:
        case SQ:
          if (escape) {
            escape = false;
            escapedCr = c == '\r';
          } else if (c == '\n' && escapedCr) {
            escapedCr = false;
          } else {
            escapedCr = false;
            if (c == '\\') {
              escape = true;
            } else if (c == (s == S.DQ ? '"' : '\'') || c == '\n' || c == '\r') {
              // LS and PS are allowed in a string literal (ES2019); an unterminated one ends at a line break.
              s = S.CODE;
              value();
              if (c == '\n' || c == '\r') {
                newLine();
              }
            }
          }
          return;
        case TPL:
          if (escape) {
            escape = false;
            prev = 0;
          } else if (c == '\\') {
            escape = true;
          } else if (c == '`') {
            s = S.CODE;
            value();
          } else if (c == '{' && p == '$') {
            tplBraces.push(braceDepth);
            s = S.CODE;
            operator();
          }
          return;
        case LINE_COMMENT:
          if (lineTerminator(c)) {
            s = S.CODE;
            newLine();
          }
          return;
        case BLOCK_COMMENT:
          if (c == '/' && p == '*') {
            s = S.CODE;
          } else if (lineTerminator(c)) {
            newLine();
          }
          return;
        case REGEX:
          if (escape) {
            escape = false;
          } else if (c == '\\') {
            escape = true;
          } else if (c == '[') {
            s = S.REGEX_CLASS;
          } else if (c == '/' || lineTerminator(c)) {
            s = S.CODE;
            value();
            if (c != '/') {
              newLine();
            }
          }
          return;
        case REGEX_CLASS:
          if (escape) {
            escape = false;
          } else if (c == '\\') {
            escape = true;
          } else if (c == ']') {
            s = S.REGEX;
          } else if (lineTerminator(c)) {
            s = S.CODE;
            value();
            newLine();
          }
          return;
        case SLASH:
          // The char after a '/': a comment, a regex or a division.
          if (c == '/') {
            s = S.LINE_COMMENT;
            commentOpener = "//";
            return;
          }
          if (c == '*') {
            s = S.BLOCK_COMMENT;
            commentOpener = "/* */";
            prev = 0; // "/*/" does not close the comment
            return;
          }
          lineStart = false;
          if (slashStartsRegex()) {
            s = S.REGEX;
            prev = 0;
            feed(c);
            return;
          }
          s = S.CODE;
          operator();
          break;
        case CODE:
        default:
          break;
      }
      if (index == 1 && p == '#' && c == '!') {
        s = S.LINE_COMMENT; // hashbang
        commentOpener = "#!";
        return;
      }
      if (lineTerminator(c)) {
        newLine();
      } else if (lineStart) {
        if (c == '>' && dashes == 2) {
          s = S.LINE_COMMENT; // Annex B "-->" at the start of a line
          commentOpener = "-->";
          return;
        }
        if (c == '-' && dashes < 2) {
          dashes++;
        } else if (dashes > 0 || !(space(c) || c == '/')) {
          lineStart = false; // a '/' may open a comment, which keeps the line start
        }
      }
      if (c == '"') {
        s = S.DQ;
      } else if (c == '\'') {
        s = S.SQ;
      } else if (c == '`') {
        s = S.TPL;
      } else if (c == '/') {
        s = S.SLASH;
        return;
      } else if (c == '(') {
        parens.push(
            keyword()
                && (KEYWORDS_BEFORE_CONDITION.contains(word.toString())
                    || (word.toString().equals("await") && previousWord.equals("for"))));
      } else if (c == ')') {
        if (!parens.isEmpty() && parens.pop()) {
          operator(); // "if (c) /re/.test(s)": the statement after a condition may start with a regex
          return;
        }
      } else if (c == '{') {
        braceDepth++;
      } else if (c == '}') {
        if (!tplBraces.isEmpty() && tplBraces.peek() == braceDepth) {
          tplBraces.pop();
          s = S.TPL;
          return;
        }
        braceDepth--;
      }
      if (space(c)) {
        wordOpen = false;
      } else if (Character.isJavaIdentifierPart(c)) {
        if (!wordOpen) {
          previousWord = word.toString();
          wordAfterDot = prevSignificant == '.' && dots == 1;
          word.setLength(0);
          wordOpen = true;
        }
        word.append(c);
        prevSignificant = c;
        dots = 0;
        incDec = false;
      } else {
        word.setLength(0);
        wordOpen = false;
        dots = c != '.' ? 0 : prevSignificant == '.' && p == '.' ? dots + 1 : 1;
        incDec = (c == '+' || c == '-') && p == c;
        prevSignificant = c;
      }
    }

    /** Decides a {@code /} in code; a guess (#307: reject when unsure) makes {@link #unsure} true. */
    private boolean slashStartsRegex() {
      if (prevSignificant == '}' || incDec || (keyword() && CONTEXTUAL_KEYWORDS.contains(word.toString()))) {
        unsure = true; // a block or an object literal; postfix or prefix; a keyword or an identifier
      }
      if (prevSignificant == '.') {
        return dots == 3; // a spread
      }
      return BEFORE_REGEX.indexOf(prevSignificant) >= 0
          || (keyword() && KEYWORDS_BEFORE_REGEX.contains(word.toString()));
    }

    /** {@code prevSignificant} ends a word that may be a keyword (not a property name). */
    private boolean keyword() {
      return Character.isJavaIdentifierPart(prevSignificant) && !wordAfterDot;
    }

    /**
     * A {@code /} could have been a division or a regex (after a closing brace, {@code ++},
     * {@code --}, {@code of}, {@code yield} or {@code await}), so the position of anything after it
     * is a guess.
     */
    boolean unsure() {
      return unsure;
    }

    /** The end of an operand: a {@code /} after it is division. */
    private void value() {
      prevSignificant = ')';
      endToken();
    }

    /** The end of an operator: a {@code /} after it starts a regex. */
    private void operator() {
      prevSignificant = '*';
      endToken();
    }

    private void endToken() {
      word.setLength(0);
      wordOpen = false;
      wordAfterDot = false;
      dots = 0;
      incDec = false;
    }

    private void newLine() {
      lineStart = true;
      dashes = 0;
    }

    /** JavaScript WhiteSpace or LineTerminator. */
    private static boolean space(char c) {
      return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\ufeff';
    }

    private static boolean lineTerminator(char c) {
      return c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029';
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
    if (!escaped && context != OutputContext.TEXT && context != OutputContext.RCDATA) {
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
   * A statement writing {@code e} escaped for {@code context} (TEXT, RCDATA or SCRIPT), or nothing
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
