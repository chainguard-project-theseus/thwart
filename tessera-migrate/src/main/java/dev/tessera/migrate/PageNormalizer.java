package dev.tessera.migrate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
 *       path parameters.
 * </ul>
 */
final class PageNormalizer {
  private PageNormalizer() {}

  /** Two pages compare as one of these. */
  enum Verdict { SAME, REORDERED, DIFFERS }

  private static final Set<String> BOOLEAN = Set.of("allowfullscreen", "async", "autofocus",
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

  /** A printed node and its children. */
  private record Line(String text, List<Line> children) {}

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
      if (node instanceof TextNode t) {
        text.append(t.getWholeText());
        continue;
      }
      if (node instanceof DataNode d) { // script and style content
        text.append(d.getWholeData());
        continue;
      }
      if (node instanceof Element || node instanceof DocumentType) {
        flushText(text, out);
      }
      if (node instanceof Element e) {
        out.add(new Line(startTag(e), children(e)));
      } else if (node instanceof DocumentType dt) {
        out.add(new Line("<!doctype " + dt.name().toLowerCase() + publicId(dt) + ">", List.of()));
      }
      // Comments and other nodes are dropped; text either side of them joins.
    }
    flushText(text, out);
    return out;
  }

  private static String publicId(DocumentType dt) {
    String pub = dt.publicId();
    return pub.isEmpty() ? "" : " public \"" + pub + "\"";
  }

  private static void flushText(StringBuilder text, List<Line> out) {
    String collapsed = WS.matcher(text).replaceAll(" ").strip();
    text.setLength(0);
    if (!collapsed.isEmpty()) {
      out.add(new Line(escapeText(collapsed), List.of()));
    }
  }

  private static String startTag(Element e) {
    String tag = e.normalName();
    Map<String, String> attrs = new TreeMap<>();
    for (Attribute a : e.attributes()) {
      String name = a.getKey().toLowerCase();
      String value = WS.matcher(a.getValue()).replaceAll(" ").strip();
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
      for (String masked : List.of("value", "content")) {
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
    out.add("  ".repeat(depth) + line.text());
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
    record Keyed(String key, Line line) {}
    return lines.stream()
        .map(l -> new Line(l.text(), sorted(l.children())))
        .map(l -> {
          List<String> out = new ArrayList<>();
          print(l, 0, out);
          return new Keyed(String.join("\n", out), l);
        })
        .sorted(Comparator.comparing(Keyed::key))
        .map(Keyed::line)
        .toList();
  }
}
