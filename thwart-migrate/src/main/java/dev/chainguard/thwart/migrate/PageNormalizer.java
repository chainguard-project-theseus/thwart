package dev.chainguard.thwart.migrate;

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
 *   <li>{@code nonce} attributes are dropped: the Thwart starter puts a fresh CSP nonce on every
 *       script and style, which the old page did not have or had with another value;
 *   <li>in JavaScript and JSON scripts, whitespace next to {@code [ ] { } , :} outside string
 *       literals is dropped: {@code [[...]]} writes compact JSON where the old page often had
 *       {@code [ "a", "b" ]}. Whitespace inside strings still counts;
 *   <li>in JavaScript scripts, a JSON value in parentheses after {@code =} and before {@code ;}
 *       loses them: the compiler writes {@code var x = ("a");} where the old page had
 *       {@code var x = "a";}.
 * </ul>
 *
 * <p>Pages that still differ are compared once more with each script's prelude expanded: every
 * declaration at the top of a JavaScript script whose value is JSON ({@code var lang = "a";}) is
 * removed and its value written where the code below uses the name. Agents hoist values into
 * such declarations by hand under the guide's script prelude rule, so a page equal only in that
 * form is {@link Verdict#REORDERED}, not {@link Verdict#SAME}: a declared {@code var} is not the
 * same JavaScript as the value written in place.
 *
 * <p>Pages that still differ are compared once more without what Thwart makes a migration change
 * (inline handlers moved to {@code data-*} hooks and a listener script, {@code javascript:} URLs,
 * computed {@code hx-*} renamed to {@code data-hx-*}, a query moved out of a custom attribute,
 * {@code <svg>} in a table replaced, {@code <style>} rewritten, and what the sanitizer and
 * {@code css.url} write: link {@code rel} tokens, the {@code user-content-} id prefix, a quoted
 * {@code url(...)}): equal in that form, in any order,
 * is {@link Verdict#FORCED}. It still differs from the old page, and its scripts need checking in
 * a browser, so compare does not pass it. Scripts, styles, {@code data-*} values, queries in
 * custom attributes and which decorative icon sits in a table are not compared in that form, so a
 * wrong value in any of them is {@code FORCED} too; the printed diff shows it. The whole pages are compared, so how much of the diff compare prints does not matter.
 */
final class PageNormalizer {
  private PageNormalizer() {}

  /** Two pages compare as one of these. */
  enum Verdict { SAME, REORDERED, FORCED, DIFFERS }

  private static final Pattern HANDLER = Pattern.compile("on[a-z]+");
  /** What an {@code <svg>} or {@code <math>} in a table and its replacement print as, forced. */
  private static final String FOREIGN = "<svg or its replacement>";
  /** The query of a path, up to a fragment. */
  private static final Pattern QUERY = Pattern.compile("\\?[^#]*");
  /** The id prefix Thwart's HTML sanitizer writes, so stored ids can't clobber the page's. */
  private static final String USER_CONTENT = "user-content-";
  /** The link {@code rel} tokens Thwart's sanitizer and message policy add. */
  private static final Set<String> SANITIZER_REL =
      Compat.setOf("nofollow", "noopener", "noreferrer", "ugc");
  /** A CSS {@code url(...)}, quoted or not, whose URL has no quote, space, paren or escape. */
  private static final Pattern CSS_URL = Pattern.compile(
      "url\\(\\s*(?:\"([^\"'()\\\\\\s]*)\"|'([^\"'()\\\\\\s]*)'|([^\"'()\\\\\\s]*))\\s*\\)",
      Pattern.CASE_INSENSITIVE);

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
  /**
   * A declared name ({@code @}) assigned or bound again: {@code x =}, {@code x +=} and the other
   * compound assignments, {@code x++}, {@code --x}, {@code var|let|const|function|class x} (the
   * first name only), {@code for (x in|of}, a flat {@code [.., x]} or {@code {x}} before
   * {@code =}, {@code catch (x)}, and {@code x} as a function or arrow parameter. A coarse scan
   * of the script text, not a tokenizer: the name inside a string or comment
   * ({@code alert("x = 1")}) or a computed member ({@code o[x] = 1}) also matches, and the page
   * then compares {@code differs}. That only fails safe; {@code compare} is a developer tool,
   * and an agent reads the diff either way.
   */
  private static final String REBOUND =
      "@\\s*(?:[-+*/%&|^]|\\*\\*|<<|>>>?|&&|\\|\\||\\?\\?)?=(?![=>])"
      + "|@\\s*(?:\\+\\+|--)|(?:\\+\\+|--)\\s*@"
      + "|\\b(?:var|let|const|function|class)\\s+@"
      + "|\\bfor\\s*\\(\\s*@\\s+(?:in|of)\\b"
      + "|[\\[{][^\\[\\]{}=;]*@[^\\[\\]{}=;]*[\\]}]\\s*=(?![=>])"
      + "|\\bcatch\\s*\\(\\s*@\\s*\\)"
      + "|\\bfunction\\b[^(]*\\([^)]*@[^)]*\\)"
      + "|\\([^()]*@[^()]*\\)\\s*=>|@\\s*=>";
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
   * a different order, or some script's prelude declarations were substituted back.
   * {@code FORCED}: equal, in any order, once what Thwart makes a migration change is set aside
   * (see {@link #startTag(Element, boolean)}). */
  static Verdict compare(String expected, String actual) {
    Document da = Jsoup.parse(expected);
    Document db = Jsoup.parse(actual);
    List<Line> a = children(da, false, false);
    List<Line> b = children(db, false, false);
    if (a.equals(b)) {
      return Verdict.SAME;
    }
    if (sortedForm(a).equals(sortedForm(b))) {
      return Verdict.REORDERED;
    }
    if (sortedForm(children(da, true, false)).equals(sortedForm(children(db, true, false)))) {
      return Verdict.REORDERED;
    }
    return sortedForm(children(da, false, true)).equals(sortedForm(children(db, false, true)))
        ? Verdict.FORCED : Verdict.DIFFERS;
  }

  /** Strips session ids from a URL (redirect targets carry them before the cookie is set). */
  static String maskUrl(String url) {
    return JSESSIONID.matcher(url).replaceAll("");
  }

  private static List<Line> tree(String html) {
    return children(Jsoup.parse(html), false, false);
  }

  /** The nodes under {@code parent}; with {@code expandPrelude}, JavaScript scripts are printed
   * with their preludes expanded. With {@code forced}, {@code <script>} and {@code <style>}
   * elements are dropped, what {@link #replacesForeign(Element)} names prints as one
   * {@link #FOREIGN} line, and tags print as {@link #startTag(Element, boolean)} sets them aside. */
  private static List<Line> children(Node parent, boolean expandPrelude, boolean forced) {
    List<Line> out = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    for (Node node : parent.childNodes()) {
      if (forced && node instanceof Element && isScriptOrStyle((Element) node)) {
        continue;
      }
      if (node instanceof TextNode) {
        text.append(((TextNode) node).getWholeText());
        continue;
      }
      if (node instanceof DataNode) { // script and style content
        String data = ((DataNode) node).getWholeData();
        if (isScript(parent)) {
          boolean javaScript = !JSON_SCRIPT_TYPES.contains(scriptType(parent));
          data = tightenJson(javaScript ? unwrapValues(data) : data);
          if (javaScript && expandPrelude) {
            data = expandPrelude(data);
          }
        }
        text.append(data);
        continue;
      }
      if (node instanceof Element || node instanceof DocumentType) {
        flushText(text, out);
      }
      if (forced && node instanceof Element && replacesForeign((Element) node)) {
        out.add(new Line(FOREIGN, Collections.<Line>emptyList()));
      } else if (node instanceof Element) {
        out.add(new Line(startTag((Element) node, forced),
            children(node, expandPrelude, forced)));
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

  /**
   * The elements a migration rewrites, so the forced form drops them: Thwart allows no expression
   * in a style, and none of the old engine's inline forms in a script.
   */
  private static boolean isScriptOrStyle(Element e) {
    return e.normalName().equals("script") || e.normalName().equals("style");
  }

  /**
   * Inside a table or {@code <template>}, where Thwart rejects {@code <svg>} and {@code <math>}:
   * those elements and a decorative element that can replace one, an {@code <img alt="">} or an
   * element with no content marked {@code aria-hidden="true"}. The forced form prints each as the
   * same {@link #FOREIGN} line, so an icon swapped for another compares equal, but an icon added
   * or removed, an image with alt text, or a visible element still differs.
   */
  private static boolean replacesForeign(Element e) {
    if (!inTableOrTemplate(e)) {
      return false;
    }
    String tag = e.normalName();
    if (tag.equals("svg") || tag.equals("math")) {
      return true;
    }
    if (tag.equals("img")) {
      return e.hasAttr("alt") && Compat.strip(e.attr("alt")).isEmpty();
    }
    return e.attr("aria-hidden").equals("true") && e.childNodeSize() == 0;
  }

  private static boolean inTableOrTemplate(Element e) {
    for (Element p = e.parent(); p != null; p = p.parent()) {
      if (p.normalName().equals("table") || p.normalName().equals("template")) {
        return true;
      }
    }
    return false;
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
   * comments), and removes the declarations so used; one the script doesn't use is kept. Other
   * scripts and event handlers on the page are not consulted, nor are a name read in a template
   * literal's {@code ${...}}, a rest binding ({@code ...x}), a later name in a declaration list
   * ({@code let a, x}), a nested or defaulted pattern ({@code [[x]] =}, {@code [a = 1, x] =}) or
   * an object key or method name ({@code {x: 1}}): such a page compares {@code reordered}, and
   * the printed diff shows the declaration that went for the agent to judge. A script that
   * declares a name twice, or whose code below matches {@link #REBOUND} for a declared name, is
   * left as it is: there the value may not be a constant. Runs on {@link #tightenJson} output.
   */
  static String expandPrelude(String script) {
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
    String rest = script.substring(i);
    for (String name : values.keySet()) {
      String word = "(?<![\\w$.])" + Pattern.quote(name) + "(?![\\w$])";
      if (Pattern.compile(REBOUND.replace("@", word)).matcher(rest).find()) {
        return script;
      }
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

  /**
   * The start tag as compare prints it. With {@code forced}, what Thwart makes a migration change
   * is set aside: {@code data-hx-X} prints as {@code hx-X} (htmx reads both), and inline
   * {@code on*} handlers, {@code href="javascript:…"}, {@code href="#"} and every other
   * {@code data-*} attribute are dropped. The guide's recipes move computed {@code data-*}
   * strings to numbers, links, {@code title} or a JSON block, so their values are not compared.
   * A path in an attribute whose name has a {@code -} (other than {@code aria-*}) prints without
   * its query: HTML's own attributes that hold paths have no {@code -}, a template can't compute
   * any other, so a computed query there had to move (into {@code data-*} attributes, say).
   * Thwart's HTML sanitizer adds {@code rel} tokens to links and the {@code user-content-} prefix
   * to ids (and to {@code href="#..."} links to them), and {@code css.url} quotes a URL, so those
   * tokens, that prefix and the quotes around a {@code url(...)} in a {@code style} are dropped.
   */
  private static String startTag(Element e, boolean forced) {
    String tag = e.normalName();
    Map<String, String> attrs = new TreeMap<>();
    for (Attribute a : e.attributes()) {
      String name = a.getKey().toLowerCase();
      if (name.equals("nonce")) {
        continue;
      }
      String value = Compat.strip(WS.matcher(a.getValue()).replaceAll(" "));
      if (forced) {
        if (name.startsWith("data-hx-") && !e.hasAttr(name.substring(5))) {
          name = name.substring(5);
        }
        if (HANDLER.matcher(name).matches()
            || name.equals("href") && (value.equals("#")
                || value.toLowerCase(Locale.ROOT).startsWith("javascript:"))
            || name.startsWith("data-")) {
          continue;
        }
        if (name.indexOf('-') >= 0 && !name.startsWith("aria-") && value.startsWith("/")) {
          value = QUERY.matcher(value).replaceFirst("");
        }
        if (tag.equals("a") && name.equals("rel")) {
          value = withoutSanitizerRel(value);
          if (value.isEmpty()) {
            continue;
          }
        }
        if (name.equals("id") && value.startsWith(USER_CONTENT)) {
          value = value.substring(USER_CONTENT.length());
        }
        if (name.equals("href") && value.startsWith("#" + USER_CONTENT)) {
          value = "#" + value.substring(1 + USER_CONTENT.length());
        }
        if (name.equals("style")) {
          value = unquoteCssUrls(value);
        }
      }
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

  /** {@code rel} without the tokens Thwart's sanitizer adds, in its own order. */
  private static String withoutSanitizerRel(String rel) {
    StringBuilder sb = new StringBuilder();
    for (String token : rel.split(" ")) {
      if (!token.isEmpty() && !SANITIZER_REL.contains(token.toLowerCase(Locale.ROOT))) {
        sb.append(sb.length() == 0 ? "" : " ").append(token);
      }
    }
    return sb.toString();
  }

  /** Each {@code url("x")} or {@code url('x')} as {@code url(x)}: the same URL to CSS. */
  static String unquoteCssUrls(String css) {
    Matcher m = CSS_URL.matcher(css);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String url = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
      m.appendReplacement(sb, Matcher.quoteReplacement("url(" + url + ")"));
    }
    return m.appendTail(sb).toString();
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
