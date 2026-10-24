package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.Backports;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/**
 * Tracks whether the static text of a {@code <script>} is inside a {@code "..."} JavaScript
 * string, so {@code [[...]]} can be rejected there. {@code [[...]]} writes a whole JSON value,
 * opening quote included, so {@code var x = "[[s]]";} would end the author's string at that quote
 * and run the rest of the value as code. Comments, {@code '...'} strings, template literals and
 * regex literals are tracked only to keep their quote characters from being mistaken for
 * {@code "} strings; {@code Escapers.json} makes inlining safe in those (#45).
 *
 * <p>Feed the static text with {@link #text} and each inlined value with {@link #value}, in
 * order. Whether a {@code /} starts a regex is decided from the previous token: it does after an
 * operator, {@code (}, {@code ,}, the {@code )} of {@code if}/{@code while}/{@code for}/{@code
 * with}, or a keyword such as {@code return}; it is a division after a value, a name, a {@code )}
 * or {@code ]}, and a postfix {@code ++}/{@code --}. Known gaps: a {@code /} after an object
 * literal's {@code }} is taken for a regex, and the Annex B comments ({@code -->} at the start of
 * a line, a {@code #!} first line) are read as code.
 */
final class ScriptStrings {
  private enum State {
    CODE,
    SLASH,
    SINGLE,
    DOUBLE,
    TEMPLATE,
    REGEX,
    REGEX_CLASS,
    LINE_COMMENT,
    BLOCK_COMMENT
  }

  private static final String REGEX_AFTER = "(,=:[!&|?{};+-*%<>~^";
  private static final Set<String> REGEX_AFTER_KEYWORDS =
      Backports.setOf(
          "return", "typeof", "case", "do", "else", "in", "instanceof", "new", "delete", "void", "throw",
          "yield", "await");
  /** Keywords whose {@code (...)} is followed by a statement, so a {@code /} after it starts a regex. */
  private static final Set<String> CONDITION_KEYWORDS = Backports.setOf("if", "while", "for", "with");

  private State state = State.CODE;
  private boolean escape;
  /** A string's escaped CR, which a following LF joins into one line continuation. */
  private boolean escapedCr;
  private char previous;
  /** The last significant code character: 0 at the start, 'a' after a word, '"' after a value. */
  private char last;

  private final StringBuilder word = new StringBuilder();
  private String lastWord = "";
  private boolean wordAfterDot;
  /** Open {@code {} counts of each {@code ${...}} the code is nested in. */
  private final Deque<int[]> substitutions = new ArrayDeque<>();
  /** For each open {@code (}, whether it is the condition of {@link #CONDITION_KEYWORDS}. */
  private final Deque<Boolean> parens = new ArrayDeque<>();

  /**
   * Whether the text is inside a {@code "..."} string literal. A {@code '...'} string is safe:
   * {@code Escapers.json} writes {@code '} as {@code \\u0027}, so the value only keeps its quotes.
   */
  boolean inDoubleQuotes() {
    return state == State.DOUBLE;
  }

  /** An inlined value, which is a complete JSON value. */
  void value() {
    if (state == State.SLASH) {
      state = regexAllowed() ? State.REGEX : State.CODE;
    }
    if (state == State.CODE) {
      endWord();
      last = '"';
    }
    escape = false;
    escapedCr = false;
    previous = 0;
  }

  void text(char c) {
    switch (state) {
      case SLASH:
        if (c == '/') {
          state = State.LINE_COMMENT;
          return;
        }
        if (c == '*') {
          state = State.BLOCK_COMMENT;
          previous = 0;
          return;
        }
        if (regexAllowed()) {
          state = State.REGEX;
          regex(c);
          return;
        }
        state = State.CODE;
        last = '/';
        code(c);
        return;
      case SINGLE:
      case DOUBLE:
        string(c, state == State.SINGLE ? '\'' : '"');
        return;
      case TEMPLATE:
        template(c);
        return;
      case REGEX:
        regex(c);
        return;
      case REGEX_CLASS:
        if (escape) {
          escape = false;
        } else if (c == '\\') {
          escape = true;
        } else if (c == ']') {
          state = State.REGEX;
        } else if (isLineTerminator(c)) {
          state = State.CODE;
        }
        return;
      case LINE_COMMENT:
        if (isLineTerminator(c)) {
          state = State.CODE;
        }
        return;
      case BLOCK_COMMENT:
        if (previous == '*' && c == '/') {
          state = State.CODE;
          previous = 0;
        } else {
          previous = c;
        }
        return;
      default:
        code(c);
    }
  }

  private void code(char c) {
    if (Character.isJavaIdentifierPart(c)) {
      if (word.length() == 0) {
        wordAfterDot = last == '.';
      }
      word.append(c);
      return;
    }
    endWord();
    if (Character.isWhitespace(c)) {
      return;
    }
    switch (c) {
      case '/':
        state = State.SLASH;
        return;
      case '\'':
        state = State.SINGLE;
        return;
      case '"':
        state = State.DOUBLE;
        return;
      case '`':
        state = State.TEMPLATE;
        previous = 0;
        return;
      case '(':
        parens.push(last == 'a' && !wordAfterDot && CONDITION_KEYWORDS.contains(lastWord));
        break;
      case ')':
        if (!parens.isEmpty() && parens.pop()) {
          last = '(';
          return;
        }
        break;
      case '+':
      case '-':
        if (last == c) {
          // ++ or --: taken as postfix, so a following / divides.
          last = ')';
          return;
        }
        break;
      case '{':
        if (!substitutions.isEmpty()) {
          substitutions.peek()[0]++;
        }
        break;
      case '}':
        if (!substitutions.isEmpty() && substitutions.peek()[0]-- == 0) {
          substitutions.pop();
          state = State.TEMPLATE;
          previous = 0;
          return;
        }
        break;
      default:
        break;
    }
    last = c;
  }

  private void string(char c, char quote) {
    if (escapedCr) {
      escapedCr = false;
      if (c == '\n') {
        return;
      }
    }
    if (escape) {
      escape = false;
      escapedCr = c == '\r';
    } else if (c == '\\') {
      escape = true;
    } else if (c == quote || c == '\n' || c == '\r') {
      // U+2028 and U+2029 are allowed inside string literals.
      state = State.CODE;
      last = '"';
    }
  }

  private void template(char c) {
    if (escape) {
      escape = false;
      c = 0;
    } else if (c == '\\') {
      escape = true;
    } else if (c == '`') {
      state = State.CODE;
      last = '"';
    } else if (previous == '$' && c == '{') {
      substitutions.push(new int[] {0});
      state = State.CODE;
      last = '{';
      c = 0;
    }
    previous = c;
  }

  private void regex(char c) {
    if (escape) {
      escape = false;
    } else if (c == '\\') {
      escape = true;
    } else if (c == '[') {
      state = State.REGEX_CLASS;
    } else if (c == '/') {
      state = State.CODE;
      last = '"';
    } else if (isLineTerminator(c)) {
      state = State.CODE;
    }
  }

  private void endWord() {
    if (word.length() != 0) {
      lastWord = word.toString();
      word.setLength(0);
      last = 'a';
    }
  }

  private boolean regexAllowed() {
    if (last == 'a') {
      return !wordAfterDot && REGEX_AFTER_KEYWORDS.contains(lastWord);
    }
    return last == 0 || REGEX_AFTER.indexOf(last) >= 0;
  }

  private static boolean isLineTerminator(char c) {
    return c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029';
  }
}
