package dev.tessera.migrate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
    String trimmed = expr.strip();
    int lead = expr.indexOf(trimmed);
    if (trimmed.startsWith("${") && matchBrace(expr, lead + 1) == lead + trimmed.length() - 1) {
      return expr.substring(0, lead) + trimmed.substring(2, trimmed.length() - 1).strip()
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
        String inner = expr.substring(i + 2, close).strip();
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
      String arg = s.substring(open + 1, close).strip();
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
          m -> "@{" + m.group(1) + "(" + m.group(2) + "=${" + m.group(3).strip() + "})}");
    }
    return s;
  }

  /**
   * True if a {@code th:} value is a Thymeleaf literal token such as {@code nav-item}: literal
   * text in Thymeleaf, but a variable (or {@code nav - item}) in Tessera, so it must be quoted.
   */
  static boolean isLiteralToken(String value) {
    String v = value.strip();
    return ConversionRules.LITERAL_TOKEN.matcher(v).matches()
        && !ConversionRules.NUMBER.matcher(v).matches()
        && !ConversionRules.TOKEN_KEYWORDS.contains(v);
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

  /** Blanks a bare {@code tpl :: name} fragment reference prefix (no {@code ~{}} wrapper). */
  private static String maskBareFragmentReference(String value, String masked) {
    String trimmed = value.strip();
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
