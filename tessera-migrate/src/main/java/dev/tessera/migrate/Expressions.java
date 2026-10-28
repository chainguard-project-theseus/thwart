package dev.tessera.migrate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
  /** {@code #request}, {@code #ctx.session}, ...: an ambient expression object and its property, if any. */
  private static final Pattern AMBIENT_OBJECT = Pattern.compile("(?<![\\w$])#("
      + String.join("|", ConversionRules.AMBIENT_OBJECTS.keySet()) + ")(?![\\w$])(?:\\s*\\.\\s*([A-Za-z_]\\w*)(?![\\w$]|\\s*\\())?");
  /** What follows an ambient root: {@code .name}, {@code ?.name} or {@code ['key']}. */
  private static final Pattern AMBIENT_PROPERTY =
      Pattern.compile("\\s*(?:\\??\\.\\s*([A-Za-z_]\\w*)(?![\\w$]|\\s*\\()|\\[\\s*'([^']*)'\\s*\\])");

  /** Report messages for every unsupported construct in {@code expr} (empty if convertible). */
  static List<String> unsupported(String expr) {
    String masked = mask(expr, false);
    List<String> messages = new ArrayList<>();
    for (ConversionRules.Unsupported u : ConversionRules.UNSUPPORTED_EXPRESSIONS) {
      if (u.pattern().matcher(u.inLiterals() ? expr : masked).find()) {
        messages.add(u.message());
      }
    }
    Set<String> seen = new LinkedHashSet<>();
    Matcher a = AMBIENT_OBJECT.matcher(masked);
    while (a.find()) {
      String read = "#" + a.group(1) + (a.group(2) == null ? "" : "." + a.group(2));
      if (seen.add(read)) {
        messages.add(String.format(ConversionRules.AMBIENT_OBJECTS.get(a.group(1)), "`" + read + "`"));
      }
    }
    Matcher m = UTILITY.matcher(masked);
    while (m.find()) {
      String name = m.group(1);
      if (!ConversionRules.NAMESPACES.containsKey("#" + name) && !ConversionRules.AMBIENT_OBJECTS.containsKey(name)
          && seen.add(name)) {
        messages.add(String.format(ConversionRules.UNKNOWN_UTILITY_MESSAGE, name));
      }
    }
    return messages;
  }

  /**
   * Applies the expression rewrites: drop redundant {@code ${...}}, receiver calls such as
   * {@code #lists.isEmpty(x)} to {@code x.isEmpty()}, {@code *{{path}}} to
   * {@code fields.value('path')}, and utility objects to namespaces. Callers
   * must check {@link #unsupported} first; values with unsupported constructs stay untouched.
   */
  static String rewrite(String expr) {
    String s = dropDollar(expr);
    for (Map.Entry<String, String> e : ConversionRules.RENAMED_CALLS.entrySet()) {
      s = replaceOutsideLiterals(s, e.getKey(), e.getValue());
    }
    s = replaceOutsideLiterals(s, ConversionRules.NEW_INDEX, m -> ConversionRules.IS_NEW);
    s = replaceOutsideLiterals(s, ConversionRules.DOUBLE_BRACE_SELECTION, m -> "fields.value('" + m.group(1) + "')");
    for (Map.Entry<String, String> e : ConversionRules.RECEIVER_CALLS.entrySet()) {
      s = receiverCall(s, e.getKey(), e.getValue());
    }
    for (Map.Entry<String, String> e : ConversionRules.NAMESPACES.entrySet()) {
      s = replaceOutsideLiterals(s, e.getKey() + ".", e.getValue() + ".");
    }
    return s;
  }

  /** A property path in a selection: {@code a}, {@code a.b}, {@code a?.b}. */
  private static final Pattern SELECTION_PATH = Pattern.compile("[A-Za-z_]\\w*(?:\\??\\.[A-Za-z_]\\w*)*");

  /**
   * {@code *{firstName + ' ' + lastName}} becomes {@code *{firstName} + ' ' + *{lastName}}, in
   * parentheses unless it is the whole value: Tessera's {@code *{...}} holds one property path, and
   * in Thymeleaf every name inside a selection is a property of the selected object, so each path
   * becomes its own selection. Only selections made of paths ({@code a.b}, {@code a?.b}, ending in at
   * most one {@code ()} after a {@code .}), literals, word operators, parentheses and operators are
   * split; the others are left in place, and {@link #compoundSelections} reports those that are not
   * one path ({@code *{items[0]}} is). Selections inside string literals and the text of
   * {@code |...|} are not read.
   */
  static String splitSelections(String expr) {
    return selections(expr, null);
  }

  /** The compound selections ({@code *{a[0] + b}}) that {@link #splitSelections} leaves in place. */
  static List<String> compoundSelections(String expr) {
    List<String> unsplit = new ArrayList<>();
    selections(expr, unsplit);
    return unsplit;
  }

  private static String selections(String expr, List<String> unsplit) {
    String masked = mask(expr, false);
    StringBuilder sb = new StringBuilder();
    int pos = 0;
    int at = masked.indexOf("*{");
    while (at >= 0) {
      int close = matchBrace(expr, at + 1);
      if (close < 0) {
        break;
      }
      String content = Compat.strip(expr.substring(at + 2, close));
      String split = splitSelection(content);
      if (split == null) {
        if (unsplit != null && needsParens(content)) {
          unsplit.add(expr.substring(at, close + 1)); // not one path: the compiler rejects it
        }
      } else if (!split.equals("*{" + content + "}")) {
        boolean whole = Compat.strip(expr.substring(0, at)).isEmpty()
            && Compat.strip(expr.substring(close + 1)).isEmpty();
        sb.append(expr, pos, at).append(whole || !needsParens(split) ? split : "(" + split + ")");
        pos = close + 1;
      }
      at = masked.indexOf("*{", close + 1);
    }
    return sb.append(expr, pos, expr.length()).toString();
  }

  /** Word operators of SpEL that are not Tessera keywords; a selection holding one is not split. */
  private static final Set<String> SPEL_OPERATORS = Compat.setOf("matches", "instanceof", "between", "this");

  /** {@link #splitSelections} of one selection's content, or {@code null} if it does not qualify. */
  private static String splitSelection(String content) {
    StringBuilder out = new StringBuilder();
    int paths = 0;
    int i = 0;
    while (i < content.length()) {
      char c = content.charAt(i);
      if (c == '\'') {
        int end = endOfString(content, i, content.length());
        if (content.charAt(end - 1) != '\'' || end - 1 == i) {
          return null; // unterminated
        }
        out.append(content, i, end);
        i = end;
      } else if (Character.isDigit(c)) {
        Matcher m = ConversionRules.NUMBER.matcher(content).region(i, content.length());
        m.lookingAt();
        out.append(m.group());
        i = m.end();
      } else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_') {
        Matcher m = SELECTION_PATH.matcher(content).region(i, content.length());
        m.lookingAt();
        String path = m.group();
        int end = m.end();
        String lower = path.toLowerCase(Locale.ROOT);
        if (SPEL_OPERATORS.contains(lower)) {
          return null;
        }
        if (ConversionRules.KEYWORDS.contains(lower)) {
          out.append(path);
        } else {
          if (path.indexOf('.') >= 0 && content.startsWith("()", end)) {
            path += "()";
            end += 2;
          }
          char next = nextNonSpace(content, end);
          if (next == '(' || next == '.' || next == '[') {
            return null; // a call with arguments, a call on a call, or an index
          }
          out.append("*{").append(path).append('}');
          paths++;
        }
        i = end;
      } else if (Character.isWhitespace(c) || "+-*/%<>=!?:()".indexOf(c) >= 0) {
        out.append(c);
        i++;
      } else {
        return null;
      }
    }
    return paths > 0 ? out.toString() : null;
  }

  /**
   * False if {@code expr} (Tessera syntax) is one link, message, string literal, {@code |...|} or
   * number, which are never {@code null}.
   */
  static boolean mayBeNull(String expr) {
    String e = Compat.strip(expr);
    if (e.isEmpty()) {
      return false;
    }
    char c = e.charAt(0);
    int end;
    if ((c == '@' || c == '#') && e.startsWith("{", 1)) {
      end = matchBrace(e, 1) + 1;
    } else if (c == '\'') {
      end = endOfString(e, 0, e.length());
    } else if (c == '|') {
      end = endOfSubstitution(e, 0);
    } else {
      return !ConversionRules.NUMBER.matcher(e).matches();
    }
    return end != e.length();
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

  private static final Pattern BLOCK_SELECTOR = Pattern.compile("::(\\s*)th:block(?![\\w:-])");

  /** {@code ~{::th:block}} selects Tessera's {@code <t:block>}, so it becomes {@code ~{::t:block}} (#137). */
  static String rewriteBlockSelectors(String expr) {
    return replaceOutsideLiterals(expr, BLOCK_SELECTOR, m -> "::" + m.group(1) + "t:block");
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
    int open = argumentsOpen(content);
    if (open < 0) {
      return content;
    }
    int close = content.lastIndexOf(')');
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

  /**
   * The index of the {@code (} opening the trailing {@code (...)} arguments of link or fragment
   * content, or -1 if there are none or nothing comes before them (not an argument list). The
   * arguments end at the last {@code )}.
   */
  private static int argumentsOpen(String content) {
    String trimmed = Compat.strip(content);
    int lead = content.indexOf(trimmed);
    int close = lead + trimmed.length() - 1;
    if (trimmed.isEmpty() || content.charAt(close) != ')') {
      return -1;
    }
    String masked = mask(content, false);
    int depth = 0;
    for (int i = close; i >= lead; i--) {
      char c = masked.charAt(i);
      if (c == ')') {
        depth++;
      } else if (c == '(' && --depth == 0) {
        return i > lead ? i : -1;
      }
    }
    return -1;
  }

  /**
   * The path variables of each link in {@code expr} (Tessera syntax) whose value is not a
   * non-empty string or number literal: the link source to its {@code name -> value} pairs, in
   * order. Links inside string literals and the text of {@code |...|} are not read.
   */
  static Map<String, Map<String, String>> linkPathVariables(String expr) {
    Map<String, Map<String, String>> links = new LinkedHashMap<>();
    int at = indexOutsideLiterals(expr, "@{", 0);
    while (at >= 0) {
      int close = matchBrace(expr, at + 1);
      if (close < 0) {
        break;
      }
      Map<String, String> vars = pathVariables(expr.substring(at + 2, close));
      if (!vars.isEmpty()) {
        links.put(expr.substring(at, close + 1), vars);
      }
      at = indexOutsideLiterals(expr, "@{", close + 1);
    }
    return links;
  }

  private static final Pattern PATH_VARIABLE = Pattern.compile("\\{\\s*([^{}\\s]+)\\s*\\}");
  private static final Pattern NON_EMPTY_LITERAL =
      Pattern.compile("'(?:[^'\\\\]|\\\\.)+'|" + ConversionRules.NUMBER.pattern());

  /** {@link #linkPathVariables} of one link's content, {@code /p/{id}(id=x)}. */
  private static Map<String, String> pathVariables(String content) {
    Map<String, String> vars = new LinkedHashMap<>();
    int open = argumentsOpen(content);
    if (open < 0) {
      return vars;
    }
    Set<String> names = new LinkedHashSet<>();
    Matcher m = PATH_VARIABLE.matcher(content.substring(0, open));
    while (m.find()) {
      names.add(m.group(1));
    }
    if (names.isEmpty()) {
      return vars;
    }
    for (String arg : splitTopLevel(content.substring(open + 1, content.lastIndexOf(')')), false)) {
      int eq = indexOfTopLevel(arg, '=');
      if (eq < 0 || (eq + 1 < arg.length() && arg.charAt(eq + 1) == '=')) {
        continue;
      }
      String name = Compat.strip(arg.substring(0, eq));
      String value = Compat.strip(arg.substring(eq + 1));
      if (names.contains(name) && !NON_EMPTY_LITERAL.matcher(value).matches()) {
        vars.put(name, value);
      }
    }
    return vars;
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
    String masked = rootMask(expr, bareFragmentReference);
    Set<String> roots = new LinkedHashSet<>();
    Matcher m = IDENT.matcher(masked);
    while (m.find()) {
      if (isRoot(masked, m)) {
        roots.add(m.group());
      }
    }
    return roots;
  }

  /**
   * The reads of Thymeleaf's ambient scopes ({@link ConversionRules#AMBIENT_ROOTS}) among the roots
   * of {@code expr}, in order and without repeats, each with its property: {@code session.user},
   * {@code param['q']}, or the bare root ({@code session}).
   */
  static Set<String> ambientReads(String expr, boolean bareFragmentReference) {
    String masked = rootMask(expr, bareFragmentReference);
    Set<String> reads = new LinkedHashSet<>();
    Matcher m = IDENT.matcher(masked);
    while (m.find()) {
      if (!ConversionRules.AMBIENT_ROOTS.containsKey(m.group()) || !isRoot(masked, m)) {
        continue;
      }
      Matcher p = AMBIENT_PROPERTY.matcher(expr).region(m.end(), expr.length());
      String property = !p.lookingAt() ? "" : p.group(1) != null ? "." + p.group(1) : "['" + p.group(2) + "']";
      reads.add(m.group() + property);
    }
    return reads;
  }

  private static String rootMask(String expr, boolean bareFragmentReference) {
    String masked = mask(expr, true);
    return bareFragmentReference ? maskBareFragmentReference(expr, masked) : masked;
  }

  /** Whether the identifier {@code m} found in {@code masked} is a root variable. */
  private static boolean isRoot(String masked, Matcher m) {
    String name = m.group();
    char prev = prevNonSpace(masked, m.start());
    char next = nextNonSpace(masked, m.end());
    int nextAt = nextNonSpaceIndex(masked, m.end());
    boolean assignment = next == '=' && !(nextAt + 1 < masked.length() && masked.charAt(nextAt + 1) == '=');
    boolean namespace = next == '.' && ConversionRules.NAMESPACES.containsKey("#" + name);
    return !(prev == '.' || next == '(' || assignment || namespace || ConversionRules.KEYWORDS.contains(name));
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

  /**
   * Index of the first {@code close} at or after {@code from} outside a {@code '...'} string literal,
   * or -1: the compiler's {@code TextInlining.findEnd}, so both tools read where an inline ends the
   * same way ({@code [[${m['k]]']}]]}).
   */
  static int inlineEnd(String text, int from, String close) {
    boolean inString = false;
    for (int i = from; i < text.length(); i++) {
      char c = text.charAt(i);
      if (inString) {
        if (c == '\\') {
          i++;
        } else if (c == '\'') {
          inString = false;
        }
      } else if (c == '\'') {
        inString = true;
      } else if (text.startsWith(close, i)) {
        return i;
      }
    }
    return -1;
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
    return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '/' || c == '.' || c == ':';
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
