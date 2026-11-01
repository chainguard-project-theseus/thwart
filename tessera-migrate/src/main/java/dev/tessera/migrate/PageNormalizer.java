package dev.tessera.migrate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.DocumentType;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * Turns an HTML page into what a browser builds from it, printed one node per line, so two
 * renderings of the same page compare equal even when their bytes differ.
 *
 * <p>The page is parsed with jsoup's HTML5 tree builder, which already closes optional and
 * implicit end tags, drops end tags that close nothing, and keeps the first of duplicate
 * attributes. On top of that:
 * <ul>
 *   <li>attributes print as a sorted set, class tokens sorted, values whitespace-collapsed;
 *   <li>text is whitespace-collapsed, whitespace-only text is dropped, comments are dropped
 *       (text on both sides of a comment joins);
 *   <li>boolean attributes ({@code checked}, {@code selected}, ...) have no value, and an empty
 *       {@code value} on an {@code input} other than a checkbox or radio is dropped;
 *   <li>CSRF tokens and session ids are masked: the {@code value}/{@code content} of an element
 *       named or id'd {@code *csrf*}, {@code _csrf=} query parameters, and {@code ;jsessionid=}
 *       path parameters;
 *   <li>{@code nonce} attributes are dropped: the Tessera starter puts a fresh CSP nonce on every
 *       script and style (#335), which the old page did not have or had with another value;
 *   <li>{@code v-pre} on {@code title} and {@code textarea} is dropped: the compiler adds it where
 *       their content is computed (#549);
 *   <li>in JavaScript and JSON scripts, whitespace next to {@code [ ] { } , :} outside string
 *       literals is dropped: {@code [[...]]} writes compact JSON where the old page often had
 *       {@code [ "a", "b" ]} (#183). Whitespace inside strings still counts;
 *   <li>in JavaScript scripts, a JSON value in parentheses after {@code =} and before {@code ;}
 *       loses them: the compiler writes {@code var x = ("a");} where the old page had
 *       {@code var x = "a";} (#540, #625).
 * </ul>
 *
 * <p>Pages that still differ are compared once more with each script's prelude expanded: every
 * declaration at the top of a JavaScript script whose value is JSON ({@code var _t1 = "a";}) is
 * removed and its value written where the code below uses the name. {@code tessera-migrate}
 * hoists values into such declarations (#541), so a page equal only in that form is
 * {@link Verdict#REORDERED}, not {@link Verdict#SAME}: a declared {@code var} is not the
 * same JavaScript as the value written in place (#625).
 */
final class PageNormalizer {
  private PageNormalizer() {}

  /** Two pages compare as one of these. */
  enum Verdict { SAME, REORDERED, DIFFERS }

  private static final Set<String> BOOLEAN = Compat.setOf("allowfullscreen", "async", "autofocus",
      "autoplay", "checked", "controls", "default", "defer", "disabled", "formnovalidate",
      "hidden", "inert", "ismap", "itemscope", "loop", "multiple", "muted", "nomodule",
      "novalidate", "open", "playsinline", "readonly", "required", "reversed", "selected");
  private static final Pattern WS = Pattern.compile("[ \\t\\n\\f\\r]+");
  private static final Pattern TOKEN_NAME = Pattern.compile("csrf|jsessionid",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern JSESSIONID = Pattern.compile(";jsessionid=[^?#\"'\\s]*",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern CSRF_PARAM = Pattern.compile("([?&]_csrf=)[^&#\"'\\s]*");
  static final String MASK = "MASKED";
  private static final String JSON_PUNCTUATION = "[]{},:";
  private static final Set<String> JSON_SCRIPT_TYPES =
      Compat.setOf("application/json", "application/ld+json");
  private static final Pattern DECLARATION = Pattern.compile(
      "(?:var|let|const)[ \\t\\n\\f\\r]+([A-Za-z_$][A-Za-z0-9_$]*)[ \\t\\n\\f\\r]*=[ \\t\\n\\f\\r]*");
  private static final Pattern JSON_ATOM =
      Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|true|false|null");

  /** A printed node and its children. */
  private static final class Line {
    private final String text;
    private final List<Line> children;

    Line(String text, List<Line> children) {
      this.text = text;
      this.children = children;
    }

    String text() {
      return text;
    }

    List<Line> children() {
      return children;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof Line)) {
        return false;
      }
      Line o = (Line) obj;
      return text.equals(o.text)
          && children.equals(o.children);
    }

    @Override
    public int hashCode() {
      return Objects.hash(text, children);
    }

    @Override
    public String toString() {
      return "Line[text=" + text + ", children=" + children + "]";
    }
  }

  /** The normalized page, one node per line, indented by depth. */
  static List<String> lines(String html) {
    List<String> out = new ArrayList<>();
    for (Line line : tree(html)) {
      print(line, 0, out);
    }
    return out;
  }

  /** Compares two pages. {@code REORDERED}: the same lines, but some element's children are in
   * a different order, or some script's prelude declarations were substituted back. */
  static Verdict compare(String expected, String actual) {
    List<Line> a = tree(expected, false);
    List<Line> b = tree(actual, false);
    if (a.equals(b)) {
      return Verdict.SAME;
    }
    if (sortedForm(a).equals(sortedForm(b))) {
      return Verdict.REORDERED;
    }
    return sortedForm(tree(expected, true)).equals(sortedForm(tree(actual, true)))
        ? Verdict.REORDERED : Verdict.DIFFERS;
  }

  /** Strips session ids from a URL (redirect targets carry them before the cookie is set). */
  static String maskUrl(String url) {
    return JSESSIONID.matcher(url).replaceAll("");
  }

  private static List<Line> tree(String html) {
    return tree(html, false);
  }

  /** The page's tree; with {@code expandPrelude}, scripts as {@link #expandPrelude} prints them. */
  private static List<Line> tree(String html, boolean expandPrelude) {
    Document doc = Jsoup.parse(html);
    return children(doc, expandPrelude ? pageNames(doc) : null);
  }

  /**
   * How often each identifier occurs in the page's inline JavaScript: the JavaScript scripts, the
   * event-handler ({@code on*}) attributes and {@code javascript:} URLs, all together. Scripts
   * loaded with {@code src} can't be seen from the page, so a global only they read is not counted.
   */
  private static Map<String, Integer> pageNames(Document doc) {
    Map<String, Integer> counts = new HashMap<>();
    for (Element el : doc.getAllElements()) {
      if (isScript(el) && !JSON_SCRIPT_TYPES.contains(scriptType(el))) {
        for (DataNode data : el.dataNodes()) {
          countNames(tightenJson(unwrapValues(data.getWholeData())), counts);
        }
      }
      for (Attribute a : el.attributes()) {
        String value = a.getValue();
        if (a.getKey().toLowerCase(Locale.ROOT).startsWith("on")
            || Compat.strip(value).toLowerCase(Locale.ROOT).startsWith("javascript:")) {
          countNames(value, counts);
        }
      }
    }
    return counts;
  }

  private static void countNames(String script, Map<String, Integer> counts) {
    for (String t : tokens(script, 0)) {
      if (isIdentifierPart(t.charAt(0))) {
        counts.merge(t, 1, Integer::sum);
      }
    }
  }

  /** The identifiers that occur in the page's other scripts, given this script's text. */
  private static Set<String> readElsewhere(String script, Map<String, Integer> pageNames) {
    Map<String, Integer> here = new HashMap<>();
    countNames(script, here);
    Set<String> out = new HashSet<>();
    for (Map.Entry<String, Integer> e : here.entrySet()) {
      if (pageNames.getOrDefault(e.getKey(), 0) > e.getValue()) {
        out.add(e.getKey());
      }
    }
    return out;
  }

  /**
   * The nodes under {@code parent}. With {@code pageNames} ({@link #pageNames}; null when not
   * expanding), JavaScript scripts are printed with their preludes expanded.
   */
  private static List<Line> children(Node parent, Map<String, Integer> pageNames) {
    List<Line> out = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    for (Node node : parent.childNodes()) {
      if (node instanceof TextNode) {
        text.append(((TextNode) node).getWholeText());
        continue;
      }
      if (node instanceof DataNode) { // script and style content
        String data = ((DataNode) node).getWholeData();
        if (isScript(parent)) {
          boolean javaScript = !JSON_SCRIPT_TYPES.contains(scriptType(parent));
          data = tightenJson(javaScript ? unwrapValues(data) : data);
          if (javaScript && pageNames != null) {
            data = expandPrelude(data, readElsewhere(data, pageNames));
          }
        }
        text.append(data);
        continue;
      }
      if (node instanceof Element || node instanceof DocumentType) {
        flushText(text, out);
      }
      if (node instanceof Element) {
        out.add(new Line(startTag((Element) node), children(node, pageNames)));
      } else if (node instanceof DocumentType) {
        DocumentType dt = (DocumentType) node;
        out.add(new Line("<!doctype " + dt.name().toLowerCase() + publicId(dt) + ">",
            Collections.<Line>emptyList()));
      }
      // Comments and other nodes are dropped; text either side of them joins.
    }
    flushText(text, out);
    return out;
  }

  /** A script element whose content is JavaScript or JSON (not, say, a {@code text/template}): the
   * converter's and compiler's list of script types. */
  private static boolean isScript(Node parent) {
    if (!(parent instanceof Element) || !((Element) parent).normalName().equals("script")) {
      return false;
    }
    return ConversionRules.SCRIPT_TYPES.contains(scriptType(parent));
  }

  private static String scriptType(Node script) {
    return Compat.strip(script.attr("type")).toLowerCase(Locale.ROOT);
  }

  /**
   * Drops the parentheses around a JSON value that is the whole right-hand side of an
   * {@code =}, up to a {@code ;}: {@code var x = ("a");} prints as {@code var x = "a";}. Strings
   * and comments are left alone. Regular expression literals are not recognised, so
   * {@code /=(1);/} and {@code /=1;/} print the same; a page that differs only there is not worth
   * a JavaScript lexer.
   */
  static String unwrapValues(String script) {
    StringBuilder out = new StringBuilder(script.length());
    int n = script.length();
    int i = 0;
    while (i < n) {
      int skip = skipStringOrComment(script, i);
      if (skip > i) {
        out.append(script, i, skip);
        i = skip;
        continue;
      }
      char c = script.charAt(i);
      out.append(c);
      i++;
      if (c != '=' || !isAssignment(script, i - 1)) {
        continue;
      }
      int open = skipWhitespace(script, i);
      if (open >= n || script.charAt(open) != '(') {
        continue;
      }
      int value = skipWhitespace(script, open + 1);
      int valueEnd = jsonEnd(script, value);
      if (valueEnd < 0) {
        continue;
      }
      int close = skipWhitespace(script, valueEnd);
      if (close >= n || script.charAt(close) != ')') {
        continue;
      }
      int semi = skipWhitespace(script, close + 1);
      if (semi < n && script.charAt(semi) == ';') {
        out.append(script, i, open).append(script, value, valueEnd);
        i = close + 1;
      }
    }
    return out.toString();
  }

  /**
   * Writes the value of each declaration at the top of a script whose value is one JSON value
   * ({@code var|let|const name = json;}, only whitespace and comments around them) where the
   * code below uses its name (an identifier not after a {@code .}, outside strings and
   * comments), and removes the declarations so used. A declaration is kept, and its name not
   * replaced, when the script doesn't use it or {@code readElsewhere} holds its name (another
   * inline script, an event handler or a {@code javascript:} URL on the page names it): that code
   * may read the global, and the value still counts. A script loaded with {@code src} isn't seen,
   * so a page that inlines a value an external script reads as a global still compares
   * {@code reordered}; its diff shows the declaration that went. A
   * script that declares a name twice, or assigns or binds a declared name again below
   * ({@link #rebinds}), is left as it is: there the value is not a constant. Runs on
   * {@link #tightenJson} output.
   */
  static String expandPrelude(String script, Set<String> readElsewhere) {
    Map<String, String> values = new HashMap<>();
    List<int[]> spans = new ArrayList<>();
    List<String> names = new ArrayList<>();
    int n = script.length();
    int i = 0;
    while (true) {
      int start = skipWhitespaceAndComments(script, i);
      Matcher m = DECLARATION.matcher(script).region(start, n);
      if (!m.lookingAt()) {
        i = start;
        break;
      }
      int valueEnd = jsonEnd(script, m.end());
      int semi = valueEnd < 0 ? n : skipWhitespace(script, valueEnd);
      if (semi >= n || script.charAt(semi) != ';') {
        i = start;
        break;
      }
      if (values.put(m.group(1), script.substring(m.end(), valueEnd)) != null) {
        return script; // declared twice: not a simple substitution
      }
      spans.add(new int[] {start, semi + 1});
      names.add(m.group(1));
      i = semi + 1;
    }
    values.keySet().removeAll(readElsewhere);
    // A template literal is copied as it is, so a name read in its ${...} keeps its declaration.
    values.keySet().removeAll(templateNames(script, i));
    if (values.isEmpty() || rebinds(tokens(script, i), values.keySet())) {
      return script;
    }
    Set<String> used = new HashSet<>();
    String body = substitute(script, i, values, used);
    StringBuilder out = new StringBuilder(script.length());
    int from = 0;
    for (int d = 0; d < spans.size(); d++) {
      int[] span = spans.get(d);
      out.append(script, from, span[0]);
      if (!used.contains(names.get(d))) {
        out.append(script, span[0], span[1]);
      }
      from = span[1];
    }
    return out.append(script, from, i).append(body).toString();
  }

  private static final List<String> OPERATORS = Compat.listOf(">>>=", "===", "!==", "**=", "<<=",
      ">>=", "&&=", "||=", "??=", "...", "==", "!=", "<=", ">=", "=>", "++", "--", "+=", "-=", "*=",
      "/=", "%=", "&=", "|=", "^=");
  private static final Set<String> ASSIGNMENTS = Compat.setOf("=", ">>>=", "**=", "<<=", ">>=",
      "&&=", "||=", "??=", "++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=");
  private static final Set<String> BINDERS = Compat.setOf("var", "let", "const", "function",
      "class", "++", "--");
  private static final Set<String> STATEMENTS = Compat.setOf("if", "while", "for", "switch",
      "with", "return", "typeof", "void", "delete", "in", "of", "new", "await", "yield");

  /**
   * The index just past the template literal that starts with the backtick at {@code start}; the
   * code of each {@code ${...}} in it is added to {@code code}.
   */
  private static int templateEnd(String s, int start, List<String> code) {
    int n = s.length();
    int i = start + 1;
    while (i < n) {
      char c = s.charAt(i);
      if (c == '\\') {
        i += 2;
      } else if (c == '`') {
        return i + 1;
      } else if (c == '$' && i + 1 < n && s.charAt(i + 1) == '{') {
        int j = i + 2;
        for (int depth = 0; j < n; ) {
          char d = s.charAt(j);
          int skip = d == '`' ? templateEnd(s, j, code) : skipStringOrComment(s, j);
          if (skip > j) {
            j = skip;
            continue;
          }
          if (d == '{') {
            depth++;
          } else if (d == '}') {
            if (depth == 0) {
              break;
            }
            depth--;
          }
          j++;
        }
        code.add(s.substring(i + 2, Math.min(j, n)));
        i = j + 1;
      } else {
        i++;
      }
    }
    return n;
  }

  /** The identifiers in the {@code ${...}} parts of the template literals of the script from
   * {@code i}. */
  private static Set<String> templateNames(String script, int i) {
    Set<String> out = new HashSet<>();
    int n = script.length();
    while (i < n) {
      if (script.charAt(i) == '`') {
        List<String> code = new ArrayList<>();
        i = templateEnd(script, i, code);
        for (String c : code) {
          for (String t : tokens(c, 0)) {
            if (isIdentifierPart(t.charAt(0))) {
              out.add(t);
            }
          }
        }
        continue;
      }
      int skip = skipStringOrComment(script, i);
      i = skip > i ? skip : i + 1;
    }
    return out;
  }

  /**
   * The script from {@code i} as tokens: identifiers and numbers, operators, and one
   * {@code "} for each string literal, followed by the tokens of each {@code ${...}} of a
   * template literal. Comments and whitespace are dropped. Regular expression literals are not
   * recognised; a {@code /} is an operator.
   */
  private static List<String> tokens(String script, int i) {
    List<String> out = new ArrayList<>();
    int n = script.length();
    while (i < n) {
      char c = script.charAt(i);
      if (c == '`') {
        List<String> code = new ArrayList<>();
        i = templateEnd(script, i, code);
        out.add("\"");
        for (String part : code) {
          out.add("(");
          out.addAll(tokens(part, 0));
          out.add(")");
        }
        continue;
      }
      int skip = skipStringOrComment(script, i);
      if (skip > i) {
        if (c != '/') {
          out.add("\"");
        }
        i = skip;
      } else if (isWhitespace(c)) {
        i++;
      } else if (isIdentifierPart(c)) {
        int j = i;
        while (j < n && isIdentifierPart(script.charAt(j))) {
          j++;
        }
        out.add(script.substring(i, j));
        i = j;
      } else {
        String op = String.valueOf(c);
        for (String o : OPERATORS) {
          if (script.startsWith(o, i)) {
            op = o;
            break;
          }
        }
        out.add(op);
        i += op.length();
      }
    }
    return out;
  }

  /**
   * True if a name in {@code names} is assigned ({@code x = }, {@code x += }, {@code x++},
   * {@code --x}), bound again ({@code var x}, {@code function x}, a parameter of a
   * {@code function}, method, arrow function or {@code catch}, a destructuring pattern, a
   * {@code for...in/of} target, {@code let a, x;}) or used as something other than a reference
   * (an object key, method name or label) in {@code tokens}, the {@code ${...}} of template
   * literals included: there writing the declared value at every use would hide a difference.
   */
  static boolean rebinds(List<String> tokens, Set<String> names) {
    for (int k = 0; k < tokens.size(); k++) {
      if (!names.contains(tokens.get(k))) {
        continue;
      }
      String prev = k > 0 ? tokens.get(k - 1) : "";
      String next = k + 1 < tokens.size() ? tokens.get(k + 1) : "";
      if (prev.equals(".")) {
        continue;
      }
      if (ASSIGNMENTS.contains(next) || BINDERS.contains(prev) || next.equals("=>")
          || next.equals("of") || isForInTarget(tokens, k, next) || isParameter(tokens, k)
          || isInPattern(tokens, k) || isInDeclarationList(tokens, k, prev)
          || isKeyOrLabel(tokens, k, prev, next)) {
        return true;
      }
    }
    return false;
  }

  /**
   * A name that is not a reference: an object key or label ({@code x:}, but not the middle of
   * {@code c ? x : y}), a method or accessor name ({@code {x() {}}}, {@code get x}), or a label
   * after {@code break} or {@code continue}. Writing a value there changes the code's meaning.
   */
  private static boolean isKeyOrLabel(List<String> tokens, int k, String prev, String next) {
    if (next.equals(":") && !prev.equals("?")) {
      return true;
    }
    if (prev.equals("break") || prev.equals("continue") || prev.equals("get")
        || prev.equals("set") || prev.equals("static") || prev.equals("async")) {
      return true;
    }
    if (next.equals("(") && (prev.equals("{") || prev.equals(",") || prev.equals("}")
        || prev.equals(";"))) {
      int close = matchingCloser(tokens, k + 1);
      return close >= 0 && close + 1 < tokens.size() && tokens.get(close + 1).equals("{");
    }
    return false;
  }

  /** The index of the nearest {@code ( [ {} that is still open at token {@code k}, or -1. */
  private static int enclosingOpener(List<String> tokens, int k) {
    for (int j = k - 1, depth = 0; j >= 0; j--) {
      String t = tokens.get(j);
      if (t.equals(")") || t.equals("]") || t.equals("}")) {
        depth++;
      } else if (t.equals("(") || t.equals("[") || t.equals("{")) {
        if (depth == 0) {
          return j;
        }
        depth--;
      }
    }
    return -1;
  }

  /** The index of the bracket that closes the one at {@code open}, or -1. */
  private static int matchingCloser(List<String> tokens, int open) {
    for (int j = open + 1, depth = 0; j < tokens.size(); j++) {
      String t = tokens.get(j);
      if (t.equals("(") || t.equals("[") || t.equals("{")) {
        depth++;
      } else if (t.equals(")") || t.equals("]") || t.equals("}")) {
        if (depth == 0) {
          return j;
        }
        depth--;
      }
    }
    return -1;
  }

  /** {@code for (x in o)}: the name before {@code in}, directly inside a {@code for (}. */
  private static boolean isForInTarget(List<String> tokens, int k, String next) {
    if (!next.equals("in")) {
      return false;
    }
    int open = enclosingOpener(tokens, k);
    return open > 0 && tokens.get(open).equals("(") && tokens.get(open - 1).equals("for");
  }

  /**
   * A name inside a destructuring pattern: a {@code { }} or {@code [ ]} (or several nested) that
   * follows {@code var}, {@code let} or {@code const}, or is followed by {@code =} or {@code of}.
   */
  private static boolean isInPattern(List<String> tokens, int k) {
    for (int open = enclosingOpener(tokens, k);
        open >= 0 && !tokens.get(open).equals("(");
        open = enclosingOpener(tokens, open)) {
      int close = matchingCloser(tokens, open);
      String before = open > 0 ? tokens.get(open - 1) : "";
      String after = close >= 0 && close + 1 < tokens.size() ? tokens.get(close + 1) : "";
      if (before.equals("var") || before.equals("let") || before.equals("const")
          || after.equals("=") || after.equals("of")) {
        return true;
      }
    }
    return false;
  }

  /** {@code let a, x;}: a name after a comma in a {@code var}, {@code let} or {@code const}
   * statement, at the statement's own bracket depth. */
  private static boolean isInDeclarationList(List<String> tokens, int k, String prev) {
    if (!prev.equals(",")) {
      return false;
    }
    int j = k - 1;
    for (int depth = 0; j >= 0; j--) {
      String t = tokens.get(j);
      if (t.equals(")") || t.equals("]") || t.equals("}")) {
        if (depth == 0 && t.equals("}")) {
          break;
        }
        depth++;
      } else if (t.equals("(") || t.equals("[") || t.equals("{")) {
        if (depth == 0) {
          break;
        }
        depth--;
      } else if (depth == 0 && t.equals(";")) {
        break;
      }
    }
    String first = j + 1 < tokens.size() ? tokens.get(j + 1) : "";
    return first.equals("var") || first.equals("let") || first.equals("const");
  }

  /** True if the token at {@code k} is in the parameter list of a function, method, arrow
   * function or {@code catch}. */
  private static boolean isParameter(List<String> tokens, int k) {
    int open = -1;
    for (int j = k - 1, depth = 0; j >= 0 && open < 0; j--) {
      String t = tokens.get(j);
      if (t.equals(")")) {
        depth++;
      } else if (t.equals("(")) {
        if (depth == 0) {
          open = j;
        }
        depth--;
      } else if (depth == 0 && (t.equals("{") || t.equals("}") || t.equals(";"))) {
        return false;
      }
    }
    if (open < 0) {
      return false;
    }
    String before = open > 0 ? tokens.get(open - 1) : "";
    if (before.equals("function") || before.equals("catch")
        || open > 1 && tokens.get(open - 2).equals("function")) {
      return true;
    }
    int close = -1;
    for (int j = open + 1, depth = 0; j < tokens.size() && close < 0; j++) {
      String t = tokens.get(j);
      if (t.equals("(")) {
        depth++;
      } else if (t.equals(")")) {
        if (depth == 0) {
          close = j;
        }
        depth--;
      }
    }
    String after = close >= 0 && close + 1 < tokens.size() ? tokens.get(close + 1) : "";
    if (after.equals("=>")) {
      return true;
    }
    // A method shorthand, m(x) { ... }: a name, not a statement keyword, before the list.
    boolean name = !before.isEmpty() && isIdentifierPart(before.charAt(0))
        && !Character.isDigit(before.charAt(0)) && !STATEMENTS.contains(before);
    return after.equals("{") && name;
  }

  /** The script from {@code i} with each identifier in {@code values} replaced by its value;
   * adds the names replaced to {@code used}. */
  private static String substitute(String script, int i, Map<String, String> values,
      Set<String> used) {
    StringBuilder out = new StringBuilder(script.length() - i);
    int n = script.length();
    while (i < n) {
      int skip = skipStringOrComment(script, i);
      if (skip > i) {
        out.append(script, i, skip);
        i = skip;
        continue;
      }
      char c = script.charAt(i);
      if (!isIdentifierPart(c)) {
        out.append(c);
        i++;
        continue;
      }
      int j = i;
      while (j < n && isIdentifierPart(script.charAt(j))) {
        j++;
      }
      String word = script.substring(i, j);
      String value = values.get(word);
      int before = out.length() - 1;
      while (before >= 0 && isWhitespace(out.charAt(before))) {
        before--;
      }
      if (value != null && !(before >= 0 && out.charAt(before) == '.')) {
        out.append(value);
        used.add(word);
      } else {
        out.append(word);
      }
      i = j;
    }
    return out.toString();
  }

  /** True if the {@code =} at {@code at} is an assignment, not part of {@code == != <= >= =>}. */
  private static boolean isAssignment(String s, int at) {
    boolean before = at > 0 && "=!<>".indexOf(s.charAt(at - 1)) >= 0;
    boolean after = at + 1 < s.length() && (s.charAt(at + 1) == '=' || s.charAt(at + 1) == '>');
    return !before && !after;
  }

  /** The index past the string literal or comment at {@code i}, or {@code i} if none starts there. */
  private static int skipStringOrComment(String s, int i) {
    char c = s.charAt(i);
    if (c == '"' || c == '\'' || c == '`') {
      return stringEnd(s, i);
    }
    if (c == '/' && i + 1 < s.length()) {
      if (s.charAt(i + 1) == '/') {
        int end = i + 2;
        while (end < s.length() && s.charAt(end) != '\n' && s.charAt(end) != '\r') {
          end++;
        }
        return end;
      }
      if (s.charAt(i + 1) == '*') {
        int end = s.indexOf("*/", i + 2);
        return end < 0 ? s.length() : end + 2;
      }
    }
    return i;
  }

  private static int skipWhitespace(String s, int i) {
    while (i < s.length() && isWhitespace(s.charAt(i))) {
      i++;
    }
    return i;
  }

  private static int skipWhitespaceAndComments(String s, int i) {
    while (true) {
      int j = skipWhitespace(s, i);
      int k = j < s.length() && s.charAt(j) == '/' ? skipStringOrComment(s, j) : j;
      if (k == i) {
        return i;
      }
      i = k;
    }
  }

  private static boolean isIdentifierPart(char c) {
    return c == '_' || c == '$' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
        || c >= '0' && c <= '9';
  }

  /**
   * The index just past the JSON value (string, number, {@code true}, {@code false},
   * {@code null}, array or object, whitespace allowed between tokens) that starts at {@code i},
   * or -1 if none does.
   */
  static int jsonEnd(String s, int i) {
    int n = s.length();
    if (i >= n) {
      return -1;
    }
    char c = s.charAt(i);
    if (c == '"') {
      for (int j = i + 1; j < n; j++) {
        char d = s.charAt(j);
        if (d == '\\') {
          j++;
        } else if (d == '"') {
          return j + 1;
        } else if (d == '\n' || d == '\r') {
          return -1;
        }
      }
      return -1;
    }
    if (c == '[' || c == '{') {
      char close = c == '[' ? ']' : '}';
      int j = skipWhitespace(s, i + 1);
      if (j < n && s.charAt(j) == close) {
        return j + 1;
      }
      while (true) {
        if (c == '{') {
          j = s.startsWith("\"", j) ? jsonEnd(s, j) : -1;
          j = j < 0 ? -1 : skipWhitespace(s, j);
          if (j < 0 || j >= n || s.charAt(j) != ':') {
            return -1;
          }
          j = skipWhitespace(s, j + 1);
        }
        j = jsonEnd(s, j);
        if (j < 0) {
          return -1;
        }
        j = skipWhitespace(s, j);
        if (j >= n) {
          return -1;
        }
        if (s.charAt(j) == close) {
          return j + 1;
        }
        if (s.charAt(j) != ',') {
          return -1;
        }
        j = skipWhitespace(s, j + 1);
      }
    }
    Matcher m = JSON_ATOM.matcher(s).region(i, n);
    if (!m.lookingAt()) {
      return -1;
    }
    int end = m.end();
    boolean joined = end < n && (isIdentifierPart(s.charAt(end)) || s.charAt(end) == '.');
    return joined ? -1 : end;
  }

  /**
   * Drops whitespace next to JSON punctuation ({@code [ ] { } , :}) outside string literals, so
   * {@code [ "a", "b" ]} and {@code ["a","b"]} print the same. A string runs from a quote
   * ({@code "}, {@code '} or a backtick) to the matching unescaped quote; a {@code "} or
   * {@code '} string also ends at a line break, so a stray quote (an apostrophe in a comment)
   * cannot swallow the rest of the script. Quote style is left alone.
   */
  static String tightenJson(String script) {
    StringBuilder out = new StringBuilder(script.length());
    int n = script.length();
    int i = 0;
    while (i < n) {
      char c = script.charAt(i);
      if (c == '"' || c == '\'' || c == '`') {
        int end = stringEnd(script, i);
        out.append(script, i, end);
        i = end;
      } else if (isWhitespace(c)) {
        int j = i;
        while (j < n && isWhitespace(script.charAt(j))) {
          j++;
        }
        boolean afterPunctuation = out.length() > 0
            && JSON_PUNCTUATION.indexOf(out.charAt(out.length() - 1)) >= 0;
        boolean beforePunctuation = j < n && JSON_PUNCTUATION.indexOf(script.charAt(j)) >= 0;
        if (!afterPunctuation && !beforePunctuation) {
          out.append(script, i, j);
        }
        i = j;
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }

  /** The index just past the string literal that starts with the quote at {@code start}. */
  private static int stringEnd(String s, int start) {
    char quote = s.charAt(start);
    int i = start + 1;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (c == quote) {
        return i + 1;
      }
      if (quote != '`' && (c == '\n' || c == '\r')) {
        return i;
      }
      i++;
    }
    return s.length();
  }

  private static boolean isWhitespace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\f' || c == '\r';
  }

  private static String publicId(DocumentType dt) {
    String pub = dt.publicId();
    return pub.isEmpty() ? "" : " public \"" + pub + "\"";
  }

  private static void flushText(StringBuilder text, List<Line> out) {
    String collapsed = Compat.strip(WS.matcher(text).replaceAll(" "));
    text.setLength(0);
    if (!collapsed.isEmpty()) {
      out.add(new Line(escapeText(collapsed), Collections.<Line>emptyList()));
    }
  }

  private static String startTag(Element e) {
    String tag = e.normalName();
    Map<String, String> attrs = new TreeMap<>();
    for (Attribute a : e.attributes()) {
      String name = a.getKey().toLowerCase();
      if (name.equals("nonce")
          || name.equals("v-pre") && (tag.equals("title") || tag.equals("textarea"))) {
        continue;
      }
      String value = Compat.strip(WS.matcher(a.getValue()).replaceAll(" "));
      if (name.equals("class")) {
        String[] tokens = value.isEmpty() ? new String[0] : value.split(" ");
        Arrays.sort(tokens);
        value = String.join(" ", tokens);
      }
      value = CSRF_PARAM.matcher(JSESSIONID.matcher(value).replaceAll("")).replaceAll("$1" + MASK);
      attrs.put(name, BOOLEAN.contains(name) ? null : value);
    }
    String type = nonNull(attrs.get("type"));
    if (tag.equals("input") && "".equals(attrs.get("value"))
        && !type.equalsIgnoreCase("checkbox") && !type.equalsIgnoreCase("radio")) {
      attrs.remove("value");
    }
    if (TOKEN_NAME.matcher(nonNull(attrs.get("name"))).find()
        || TOKEN_NAME.matcher(nonNull(attrs.get("id"))).find()) {
      for (String masked : Compat.listOf("value", "content")) {
        if (attrs.get(masked) != null && !attrs.get(masked).isEmpty()) {
          attrs.put(masked, MASK);
        }
      }
    }
    StringBuilder sb = new StringBuilder("<").append(tag);
    attrs.forEach((name, value) -> {
      sb.append(' ').append(name);
      if (value != null) {
        sb.append("=\"").append(escapeAttr(value)).append('"');
      }
    });
    return sb.append('>').toString();
  }

  private static String nonNull(String s) {
    return s == null ? "" : s;
  }

  private static String escapeText(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(" ", "&nbsp;");
  }

  private static String escapeAttr(String s) {
    return s.replace("&", "&amp;").replace("\"", "&quot;").replace(" ", "&nbsp;");
  }

  private static void print(Line line, int depth, List<String> out) {
    out.add(Compat.repeat("  ", depth) + line.text());
    for (Line kid : line.children()) {
      print(kid, depth + 1, out);
    }
  }

  /** The tree with every element's children sorted, recursively: equal for reordered pages. */
  private static List<String> sortedForm(List<Line> roots) {
    List<String> out = new ArrayList<>();
    for (Line line : sorted(roots)) {
      print(line, 0, out);
    }
    return out;
  }

  private static List<Line> sorted(List<Line> lines) {
    // Each line keyed by its printed form, sorted by key.
    TreeMap<String, List<Line>> byKey = new TreeMap<>();
    for (Line l : lines) {
      Line s = new Line(l.text(), sorted(l.children()));
      List<String> out = new ArrayList<>();
      print(s, 0, out);
      byKey.computeIfAbsent(String.join("\n", out), k -> new ArrayList<>()).add(s);
    }
    List<Line> result = new ArrayList<>();
    byKey.values().forEach(result::addAll);
    return result;
  }
}
