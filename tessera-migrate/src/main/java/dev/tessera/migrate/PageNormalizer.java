package dev.tessera.migrate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
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
 *   <li>in JavaScript and JSON scripts, whitespace next to {@code [ ] { } , :} outside string
 *       literals is dropped: {@code [[...]]} writes compact JSON where the old page often had
 *       {@code [ "a", "b" ]} (#183). Whitespace inside strings still counts.
 * </ul>
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
   * a different order. */
  static Verdict compare(String expected, String actual) {
    List<Line> a = tree(expected);
    List<Line> b = tree(actual);
    if (a.equals(b)) {
      return Verdict.SAME;
    }
    return sortedForm(a).equals(sortedForm(b)) ? Verdict.REORDERED : Verdict.DIFFERS;
  }

  /** Strips session ids from a URL (redirect targets carry them before the cookie is set). */
  static String maskUrl(String url) {
    return JSESSIONID.matcher(url).replaceAll("");
  }

  private static List<Line> tree(String html) {
    Document doc = Jsoup.parse(html);
    return children(doc);
  }

  private static List<Line> children(Node parent) {
    List<Line> out = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    for (Node node : parent.childNodes()) {
      if (node instanceof TextNode) {
        text.append(((TextNode) node).getWholeText());
        continue;
      }
      if (node instanceof DataNode) { // script and style content
        String data = ((DataNode) node).getWholeData();
        text.append(isScript(parent) ? tightenJson(data) : data);
        continue;
      }
      if (node instanceof Element || node instanceof DocumentType) {
        flushText(text, out);
      }
      if (node instanceof Element) {
        out.add(new Line(startTag((Element) node), children(node)));
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
    String type = Compat.strip(parent.attr("type")).toLowerCase(Locale.ROOT);
    return ConversionRules.SCRIPT_TYPES.contains(type);
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
      if (name.equals("nonce")) {
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
