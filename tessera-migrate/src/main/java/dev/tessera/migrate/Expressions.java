package dev.tessera.migrate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text-level helpers over Thymeleaf expression strings: find unsupported constructs, apply the
 * {@link ConversionRules} rewrites, and collect root variable names. Works on source text so that
 * everything it does not rewrite stays byte-identical; it is not a full expression parser.
 */
final class Expressions {
  private Expressions() {}

  private static final Pattern IDENT = Pattern.compile("(?<![\\w$#.@])[A-Za-z_]\\w*");
  private static final Pattern UTILITY = Pattern.compile("(?<![\\w$])#([A-Za-z_]\\w*)\\s*\\.");

  /** Report messages for every unsupported construct in {@code expr} (empty if convertible). */
  static List<String> unsupported(String expr) {
    String masked = mask(expr, false);
    List<String> messages = new ArrayList<>();
    for (ConversionRules.Unsupported u : ConversionRules.UNSUPPORTED_EXPRESSIONS) {
      if (u.pattern().matcher(u.inLiterals() ? expr : masked).find()) {
        messages.add(u.message());
      }
    }
    Matcher m = UTILITY.matcher(masked);
    Set<String> seen = new LinkedHashSet<>();
    while (m.find()) {
      String name = m.group(1);
      if (!ConversionRules.NAMESPACES.containsKey("#" + name) && seen.add(name)) {
        messages.add(String.format(ConversionRules.UNKNOWN_UTILITY_MESSAGE, name));
      }
    }
    return messages;
  }

  /**
   * Applies the expression rewrites: drop redundant {@code ${...}}, receiver calls such as
   * {@code #lists.isEmpty(x)} to {@code x.isEmpty()}, and utility objects to namespaces. Callers
   * must check {@link #unsupported} first; values with unsupported constructs stay untouched.
   */
  static String rewrite(String expr) {
    String s = dropDollar(expr);
    for (Map.Entry<String, String> e : ConversionRules.RENAMED_CALLS.entrySet()) {
      s = replaceOutsideLiterals(s, e.getKey(), e.getValue());
    }
    s = replaceOutsideLiterals(s, ConversionRules.NEW_INDEX, m -> ConversionRules.IS_NEW);
    for (Map.Entry<String, String> e : ConversionRules.RECEIVER_CALLS.entrySet()) {
      s = receiverCall(s, e.getKey(), e.getValue());
    }
    for (Map.Entry<String, String> e : ConversionRules.NAMESPACES.entrySet()) {
      s = replaceOutsideLiterals(s, e.getKey() + ".", e.getValue() + ".");
    }
    return s;
  }

  /** {@code ${x}} becomes {@code x}; a compound {@code ${...}} inside a larger value keeps parentheses. */
  private static String dropDollar(String expr) {
    String masked = mask(expr, false);
    String trimmed = Compat.strip(expr);
    int lead = expr.indexOf(trimmed);
    if (trimmed.startsWith("${") && matchBrace(expr, lead + 1) == lead + trimmed.length() - 1) {
      return expr.substring(0, lead) + Compat.strip(trimmed.substring(2, trimmed.length() - 1))
          + expr.substring(lead + trimmed.length());
    }
    StringBuilder sb = new StringBuilder();
    boolean inSubstitution = false;
    int i = 0;
    while (i < expr.length()) {
      if (masked.charAt(i) == '|') {
        inSubstitution = !inSubstitution;
        sb.append(expr.charAt(i++));
      } else if (expr.startsWith("${", i) && masked.startsWith("${", i)) {
        int close = matchBrace(expr, i + 1);
        if (close < 0) {
          break;
        }
        String inner = Compat.strip(expr.substring(i + 2, close));
        if (inSubstitution) {
          sb.append(expr, i, close + 1); // |...${x}...| keeps its wrapper
        } else {
          sb.append(needsParens(inner) ? "(" + inner + ")" : inner);
        }
        i = close + 1;
      } else {
        sb.append(expr.charAt(i++));
      }
    }
    sb.append(expr, i, expr.length());
    return sb.toString();
  }

  private static String receiverCall(String s, String call, String method) {
    int from = 0;
    while (true) {
      int at = indexOutsideLiterals(s, call, from);
      if (at < 0) {
        return s;
      }
      int open = at + call.length();
      while (open < s.length() && Character.isWhitespace(s.charAt(open))) {
        open++;
      }
      int close = open < s.length() && s.charAt(open) == '(' ? matchParen(s, open) : -1;
      if (close < 0) {
        from = at + 1;
        continue;
      }
      String arg = Compat.strip(s.substring(open + 1, close));
      String replacement = (needsParens(arg) ? "(" + arg + ")" : arg) + "." + method + "()";
      s = s.substring(0, at) + replacement + s.substring(close + 1);
      from = at + replacement.length();
    }
  }

  private static String replaceOutsideLiterals(String s, String from, String to) {
    int at = indexOutsideLiterals(s, from, 0);
    while (at >= 0) {
      s = s.substring(0, at) + to + s.substring(at + from.length());
      at = indexOutsideLiterals(s, from, at + to.length());
    }
    return s;
  }

  /**
   * Replaces matches of {@code p} that start outside string literals with {@code to.apply(match)},
   * appended verbatim (never through {@link Matcher#appendReplacement}, so {@code $} in the
   * replacement is literal).
   */
  private static String replaceOutsideLiterals(String s, Pattern p, Function<MatchResult, String> to) {
    String masked = mask(s, false);
    StringBuilder sb = new StringBuilder();
    Matcher m = p.matcher(s);
    int pos = 0;
    while (m.find()) {
      if (masked.charAt(m.start()) == s.charAt(m.start())) {
        sb.append(s, pos, m.start()).append(to.apply(m));
        pos = m.end();
      }
    }
    return sb.append(s, pos, s.length()).toString();
  }

  /**
   * {@code @{'/p?k=' + ${x}}} and {@code @{'/p?k=__${x}__'}} become {@code @{/p(k=${x})}}. Runs
   * before {@link #unsupported}, which reports the string-built forms it does not cover.
   */
  static String rewriteLinks(String expr) {
    String s = expr;
    for (Pattern p : ConversionRules.STRING_LINKS) {
      s = replaceOutsideLiterals(s, p,
          m -> "@{" + m.group(1) + "(" + m.group(2) + "=${" + Compat.strip(m.group(3)) + "})}");
    }
    return s;
  }

  /**
   * Quotes Thymeleaf token literals in a {@code th:} value: outside {@code ${...}}, {@code *{...}},
   * string literals and {@code |...|} text, a run of token characters such as {@code nav-item} or
   * {@code mixed} is literal text in Thymeleaf but a variable in Tessera, so it becomes
   * {@code 'mixed'}. This covers whole values ({@code th:class="nav-item"}), operands
   * ({@code ${x} ? active}) and the arguments of links, messages and fragment expressions
   * ({@code @{/ranking(mode=mixed)}}). Numbers, {@code true}/{@code false}/{@code null}, word
   * operators and the no-op token {@code _} are left alone, as are link paths, message keys,
   * fragment names and link parameter names. With {@code bareFragmentReference}, a value that is
   * not {@code ~{...}} or {@code ${...}} is read as the content of a fragment expression.
   */
  static String quoteTokens(String expr, boolean bareFragmentReference) {
    String trimmed = Compat.strip(expr);
    if (bareFragmentReference && !trimmed.startsWith("~{") && !trimmed.startsWith("${")) {
      return withQuotedArguments(expr, true);
    }
    return quoteTokens(expr);
  }

  private static String quoteTokens(String s) {
    StringBuilder out = new StringBuilder(s.length() + 8);
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      int end;
      if (c == '\'') {
        end = endOfString(s, i, s.length());
        out.append(s, i, end);
      } else if (c == '|') {
        end = endOfSubstitution(s, i);
        out.append(s, i, end);
      } else if (i + 1 < s.length() && s.charAt(i + 1) == '{' && "$*#@~".indexOf(c) >= 0) {
        int close = matchBrace(s, i + 1);
        if (close < 0) {
          out.append(s, i, s.length());
          return out.toString();
        }
        end = close + 1;
        String content = s.substring(i + 2, close);
        if (c == '@' || c == '~') {
          content = withQuotedArguments(content, c == '~');
        } else if (c == '#') {
          content = messageArguments(content);
        }
        out.append(s, i, i + 2).append(content).append('}');
      } else if (isTokenChar(s, i)) {
        end = i + 1;
        while (end < s.length() && isTokenChar(s, end)) {
          end++;
        }
        String token = s.substring(i, end);
        boolean code = nextNonSpace(s, end) == '(' || (i > 0 && s.charAt(i - 1) == '#');
        out.append(!code && isTokenLiteral(token) ? "'" + token + "'" : token);
      } else {
        end = i + 1;
        out.append(c);
      }
      i = end;
    }
    return out.toString();
  }

  /**
   * Quotes the tokens in the trailing {@code (...)} arguments of link or fragment content, the way
   * Thymeleaf splits {@code base(args)}. Link parameters without a value are names, kept as is.
   */
  private static String withQuotedArguments(String content, boolean positional) {
    String trimmed = Compat.strip(content);
    int lead = content.indexOf(trimmed);
    int close = lead + trimmed.length() - 1;
    if (trimmed.isEmpty() || content.charAt(close) != ')') {
      return content;
    }
    String masked = mask(content, false);
    int depth = 0;
    int open = -1;
    for (int i = close; i >= lead; i--) {
      char c = masked.charAt(i);
      if (c == ')') {
        depth++;
      } else if (c == '(' && --depth == 0) {
        open = i;
        break;
      }
    }
    if (open <= lead) {
      return content; // no base before the parentheses: not an argument list
    }
    List<String> args = new ArrayList<>();
    for (String arg : splitTopLevel(content.substring(open + 1, close), false)) {
      int eq = indexOfTopLevel(arg, '=');
      if (eq >= 0 && !(eq + 1 < arg.length() && arg.charAt(eq + 1) == '=')) {
        args.add(arg.substring(0, eq + 1) + quoteTokens(arg.substring(eq + 1)));
      } else {
        args.add(positional ? quoteTokens(arg) : arg);
      }
    }
    return content.substring(0, open + 1) + String.join(",", args) + content.substring(close);
  }

  /** {@code #{key(args)}}: the key is literal, each argument is an expression. */
  private static String messageArguments(String content) {
    int open = content.indexOf('(');
    int close = open < 0 ? -1 : matchParen(content, open);
    if (close < 0) {
      return content;
    }
    List<String> args = new ArrayList<>();
    for (String arg : splitTopLevel(content.substring(open + 1, close), false)) {
      args.add(quoteTokens(arg));
    }
    return content.substring(0, open + 1) + String.join(",", args) + content.substring(close);
  }

  /** True if a token run is a text literal in Thymeleaf, not a number, keyword or operator. */
  private static boolean isTokenLiteral(String token) {
    String lower = token.toLowerCase(Locale.ROOT);
    return !ConversionRules.NUMBER.matcher(token).matches()
        && !ConversionRules.TOKEN_KEYWORDS.contains(lower);
  }

  /** Index just past the {@code |} closing the substitution opening at {@code start}. */
  private static int endOfSubstitution(String s, int start) {
    int k = start + 1;
    while (k < s.length() && s.charAt(k) != '|') {
      if (s.startsWith("${", k) || s.startsWith("*{", k)) {
        int close = matchBrace(s, k + 1);
        k = close < 0 ? s.length() : close + 1;
      } else {
        k++;
      }
    }
    return Math.min(k + 1, s.length());
  }

  /**
   * Thymeleaf's {@code Token.isTokenChar}: letters, digits, {@code []._} and a range of non-ASCII
   * letters; {@code -} only when an adjacent run of token characters is not purely numeric, so
   * {@code nav-item} is one token and {@code 1-2} is a subtraction.
   */
  static boolean isTokenChar(String s, int pos) {
    char c = s.charAt(pos);
    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
        || c == '[' || c == ']' || c == '.' || c == '_') {
      return true;
    }
    if (c == '-') {
      for (int i = pos - 1; i >= 0 && isTokenChar(s, i); i--) {
        char cc = s.charAt(i);
        if (!((cc >= '0' && cc <= '9') || cc == '.')) {
          return true;
        }
      }
      for (int i = pos + 1; i < s.length(); i++) {
        char cc = s.charAt(i);
        if (cc == '-') {
          return true; // as Thymeleaf, which stops here to avoid a cycle
        }
        if (!isTokenChar(s, i)) {
          break;
        }
        if (!((cc >= '0' && cc <= '9') || cc == '.')) {
          return true;
        }
      }
      return false;
    }
    return c == '\u00B7'
        || (c >= '\u00C0' && c <= '\u00D6') || (c >= '\u00D8' && c <= '\u00F6')
        || (c >= '\u00F8' && c <= '\u02FF') || (c >= '\u0300' && c <= '\u036F')
        || (c >= '\u0370' && c <= '\u037D') || (c >= '\u037F' && c <= '\u1FFF')
        || (c >= '\u200C' && c <= '\u200D') || (c >= '\u203F' && c <= '\u2040')
        || (c >= '\u2070' && c <= '\u218F') || (c >= '\u2C00' && c <= '\u2FEF')
        || (c >= '\u3001' && c <= '\uD7FF') || (c >= '\uF900' && c <= '\uFDCF')
        || (c >= '\uFDF0' && c <= '\uFFFD');
  }

  private static int indexOutsideLiterals(String s, String needle, int from) {
    String masked = mask(s, false);
    int at = s.indexOf(needle, from);
    while (at >= 0 && !masked.startsWith(needle, at)) {
      at = s.indexOf(needle, at + 1);
    }
    return at;
  }

  /** True if {@code x} has a top-level operator or space, so it needs parentheses as an operand. */
  private static boolean needsParens(String x) {
    String masked = mask(x, false);
    int depth = 0;
    for (int i = 0; i < masked.length(); i++) {
      char c = masked.charAt(i);
      if (isOpen(c)) {
        depth++;
      } else if (isClose(c)) {
        depth--;
      } else if (c == '?') {
        if (depth == 0 && !(i + 1 < masked.length() && masked.charAt(i + 1) == '.')) {
          return true;
        }
      } else if (depth == 0 && (Character.isWhitespace(c) || "+-*/%<>=!:&|,".indexOf(c) >= 0)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Root variable names referenced by {@code expr}, in order of first use. With
   * {@code bareFragmentReference}, a leading {@code tpl :: name} (no {@code ~{}}) is skipped.
   */
  static Set<String> roots(String expr, boolean bareFragmentReference) {
    String masked = mask(expr, true);
    if (bareFragmentReference) {
      masked = maskBareFragmentReference(expr, masked);
    }
    Set<String> roots = new LinkedHashSet<>();
    Matcher m = IDENT.matcher(masked);
    while (m.find()) {
      String name = m.group();
      char prev = prevNonSpace(masked, m.start());
      char next = nextNonSpace(masked, m.end());
      int nextAt = nextNonSpaceIndex(masked, m.end());
      boolean assignment = next == '=' && !(nextAt + 1 < masked.length() && masked.charAt(nextAt + 1) == '=');
      boolean namespace = next == '.' && ConversionRules.NAMESPACES.containsKey("#" + name);
      if (prev == '.' || next == '(' || assignment || namespace
          || ConversionRules.KEYWORDS.contains(name)) {
        continue;
      }
      roots.add(name);
    }
    return roots;
  }

  /** Splits at top-level commas (outside literals and brackets, including {@code <>} if asked). */
  static List<String> splitTopLevel(String s, boolean angleBrackets) {
    String masked = mask(s, false);
    List<String> parts = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < masked.length(); i++) {
      char c = masked.charAt(i);
      if (isOpen(c) || (angleBrackets && c == '<')) {
        depth++;
      } else if (isClose(c) || (angleBrackets && c == '>')) {
        depth--;
      } else if (c == ',' && depth == 0) {
        parts.add(s.substring(start, i));
        start = i + 1;
      }
    }
    parts.add(s.substring(start));
    return parts;
  }

  /** Index of the first top-level {@code ch} outside literals and brackets, or -1. */
  static int indexOfTopLevel(String s, char ch) {
    String masked = mask(s, false);
    int depth = 0;
    for (int i = 0; i < masked.length(); i++) {
      char c = masked.charAt(i);
      if (c == ch && depth == 0) {
        return i;
      }
      if (isOpen(c)) {
        depth++;
      } else if (isClose(c)) {
        depth--;
      }
    }
    return -1;
  }

  private static boolean isOpen(char c) {
    return c == '(' || c == '[' || c == '{';
  }

  private static boolean isClose(char c) {
    return c == ')' || c == ']' || c == '}';
  }

  /**
   * Returns {@code s} with the same length, literal text blanked to spaces: the contents of
   * {@code '...'} strings and the text parts of {@code |...|} substitutions (delimiters kept).
   * With {@code forRoots}, also blanks everything that cannot contain a root variable: string
   * delimiters, {@code *{...}} selections, message keys, literal link paths, fragment template
   * names and fragment names.
   */
  private static String mask(String s, boolean forRoots) {
    char[] m = s.toCharArray();
    mask(s, 0, s.length(), m, forRoots);
    return new String(m);
  }

  private static void mask(String s, int from, int to, char[] m, boolean forRoots) {
    int i = from;
    while (i < to) {
      char c = s.charAt(i);
      if (c == '\'') {
        int end = endOfString(s, i, to);
        blank(m, i + 1, end - 1);
        if (forRoots) {
          blank(m, i, end);
        }
        i = end;
      } else if (c == '|') {
        int k = i + 1;
        while (k < to && s.charAt(k) != '|') {
          if (s.startsWith("${", k)) {
            int close = matchBrace(s, k + 1);
            if (close < 0 || close >= to) {
              close = to - 1;
            }
            mask(s, k + 2, close, m, forRoots);
            k = close + 1;
          } else {
            m[k++] = ' ';
          }
        }
        if (forRoots) {
          m[i] = ' ';
          if (k < to) {
            m[k] = ' ';
          }
        }
        i = Math.min(k + 1, to);
      } else if (forRoots && s.startsWith("*{", i)) {
        int close = matchBrace(s, i + 1);
        int end = close < 0 || close >= to ? to : close + 1;
        blank(m, i, end);
        i = end;
      } else if (forRoots && s.startsWith("#{", i)) {
        int end = i + 2;
        while (end < to && s.charAt(end) != '(' && s.charAt(end) != '}') {
          end++;
        }
        blank(m, i, end);
        i = end;
      } else if (forRoots && s.startsWith("@{", i)) {
        int end = i + 2;
        while (end < to && Character.isWhitespace(s.charAt(end))) {
          end++;
        }
        if (end < to && "'$|(*".indexOf(s.charAt(end)) < 0) {
          int depth = 0;
          while (end < to) {
            char e = s.charAt(end);
            if (depth == 0 && (e == '(' || e == '}')) {
              break;
            }
            depth += e == '{' ? 1 : e == '}' ? -1 : 0;
            end++;
          }
          if (end < to && s.charAt(end) == '(') {
            blankValuelessParameters(s, end, to, m);
          }
        }
        blank(m, i, end);
        i = end;
      } else if (forRoots && s.startsWith("~{", i)) {
        int close = matchBrace(s, i + 1);
        int limit = close < 0 || close >= to ? to : close;
        int sep = s.indexOf("::", i);
        int end = sep >= 0 && sep < limit ? endOfFragmentName(s, sep + 2, limit) : limit;
        blank(m, i, end);
        i = end;
      } else {
        i++;
      }
    }
  }

  /** Blanks link parameters without a value ({@code add} in {@code @{/cart(add, id=x)}}): names. */
  private static void blankValuelessParameters(String s, int open, int to, char[] m) {
    int close = matchParen(s, open);
    if (close < 0 || close >= to) {
      return;
    }
    int start = open + 1;
    for (String arg : splitTopLevel(s.substring(start, close), false)) {
      if (indexOfTopLevel(arg, '=') < 0) {
        blank(m, start, start + arg.length());
      }
      start += arg.length() + 1;
    }
  }

  /** Blanks a bare {@code tpl :: name} fragment reference prefix (no {@code ~{}} wrapper). */
  private static String maskBareFragmentReference(String value, String masked) {
    String trimmed = Compat.strip(value);
    if (trimmed.startsWith("~{") || trimmed.startsWith("${")) {
      return masked;
    }
    int sep = mask(value, false).indexOf("::");
    if (sep < 0) {
      return masked;
    }
    int end = endOfFragmentName(value, sep + 2, value.length());
    char[] m = masked.toCharArray();
    blank(m, 0, end);
    return new String(m);
  }

  /** Index just past the fragment name after {@code ::}: optional whitespace, then name characters. */
  private static int endOfFragmentName(String s, int afterSep, int limit) {
    int end = afterSep;
    while (end < limit && Character.isWhitespace(s.charAt(end))) {
      end++;
    }
    while (end < limit && isNameChar(s.charAt(end))) {
      end++;
    }
    return end;
  }

  private static boolean isNameChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '/' || c == '.';
  }

  private static void blank(char[] m, int from, int to) {
    for (int i = Math.max(from, 0); i < to && i < m.length; i++) {
      m[i] = ' ';
    }
  }

  /** Index just past the closing quote of the string literal opening at {@code start}. */
  private static int endOfString(String s, int start, int to) {
    int i = start + 1;
    while (i < to) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < to) {
        i += 2;
      } else if (c == '\'') {
        return i + 1;
      } else {
        i++;
      }
    }
    return to;
  }

  /** Index of the '}' matching the '{' at {@code open}, skipping string literals; -1 if none. */
  private static int matchBrace(String s, int open) {
    return match(s, open, '{', '}');
  }

  private static int matchParen(String s, int open) {
    return match(s, open, '(', ')');
  }

  private static int match(String s, int open, char o, char c) {
    int depth = 0;
    int i = open;
    while (i < s.length()) {
      char ch = s.charAt(i);
      if (ch == '\'') {
        i = endOfString(s, i, s.length());
        continue;
      }
      if (ch == o) {
        depth++;
      } else if (ch == c && --depth == 0) {
        return i;
      }
      i++;
    }
    return -1;
  }

  private static char prevNonSpace(String s, int before) {
    for (int i = before - 1; i >= 0; i--) {
      if (!Character.isWhitespace(s.charAt(i))) {
        return s.charAt(i);
      }
    }
    return 0;
  }

  private static int nextNonSpaceIndex(String s, int from) {
    int i = from;
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
      i++;
    }
    return i;
  }

  private static char nextNonSpace(String s, int from) {
    int i = nextNonSpaceIndex(s, from);
    return i < s.length() ? s.charAt(i) : 0;
  }
}
