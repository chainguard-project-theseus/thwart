package dev.tessera.migrate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.attoparser.AbstractMarkupHandler;
import org.attoparser.MarkupParser;
import org.attoparser.ParseException;
import org.attoparser.config.ParseConfiguration;

/**
 * Layout markup that a browser reads only by error recovery, which the build rejects (#406, #409):
 * a page skeleton element ({@code <body>}, {@code <head>}, {@code <html>}) nested inside another of
 * the same name. Runs over all converted templates, because whether a page's {@code <body>} nests
 * depends on where the layout inserts it. Rewrites only what keeps the page the same in a browser:
 * a tag is removed only if it has no attributes, or a browser would merge them into a page element
 * that already has them; everything else is reported with the fix. Also reports, without
 * rewriting, other parse errors that are cheap to find in the source.
 *
 * <ul>
 *   <li>A page passes {@code ~{::body}} (or {@code ::head}, {@code ::html}) to a fragment that
 *       inserts it inside its own element of that name: the page's element gets
 *       {@code t:remove="tag"}.
 *   <li>A {@code <body>} or {@code <html>} inside a {@code <body>} of the same template: its tags
 *       are deleted.
 *   <li>A fragment, inserted only into page bodies, that writes a whole {@code <html>} document:
 *       its {@code <html>} and {@code <body>} tags and its {@code <head>} are deleted.
 * </ul>
 */
final class Layouts {
  private Layouts() {}

  private static final MarkupParser PARSER = new MarkupParser(parseConfiguration());

  private static ParseConfiguration parseConfiguration() {
    ParseConfiguration config = ParseConfiguration.htmlConfiguration();
    config.setElementBalancing(ParseConfiguration.ElementBalancing.NO_BALANCING);
    return config;
  }

  private static final Set<String> VOID_ELEMENTS = Compat.setOf("area", "base", "br", "col", "embed",
      "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr");

  /** Start tags that close an open {@code <p>} (the HTML parser's "close a p element"). */
  private static final Set<String> CLOSES_P = Compat.setOf("address", "article", "aside",
      "blockquote", "center", "details", "dialog", "dir", "div", "dl", "dd", "dt", "fieldset",
      "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header",
      "hgroup", "hr", "li", "listing", "main", "menu", "nav", "ol", "p", "plaintext", "pre",
      "search", "section", "summary", "table", "ul", "xmp");
  /** Elements whose content a {@code <p>} outside them can't be closed from (button scope). */
  private static final Set<String> P_SCOPE = Compat.setOf("button", "applet", "object", "marquee",
      "template", "td", "th", "caption", "table", "html", "svg", "math");
  /** What each table part may hold directly; anything else a browser moves out of the table. */
  private static final Map<String, Set<String>> TABLE_CONTENT = tableContent();

  private static Map<String, Set<String>> tableContent() {
    Map<String, Set<String>> m = new LinkedHashMap<>();
    m.put("table", Compat.setOf("caption", "colgroup", "col", "thead", "tbody", "tfoot", "tr",
        "script", "template", "style"));
    Set<String> section = Compat.setOf("tr", "script", "template", "style");
    m.put("thead", section);
    m.put("tbody", section);
    m.put("tfoot", section);
    m.put("tr", Compat.setOf("td", "th", "script", "template", "style"));
    return m;
  }

  /** {@code ~{path :: name(args)}} or {@code path :: name(args)}; groups: path, name, args. */
  private static final Pattern CALL = Pattern.compile(
      "\\s*(?:~\\{)?\\s*([\\w./-]*)\\s*::\\s*([\\w-]+)\\s*(?:\\((.*)\\))?\\s*\\}?\\s*", Pattern.DOTALL);
  /** A fragment argument that selects a page element: {@code ~{::body}}, {@code ~{this :: head}}. */
  private static final Pattern PAGE_SELECTOR =
      Pattern.compile("\\s*(?:([\\w$]+)\\s*=)?\\s*~\\{\\s*(?:this\\s*)?::\\s*(html|head|body)\\s*\\}\\s*");
  private static final Pattern SIGNATURE = Pattern.compile("\\s*([\\w-]+)\\s*(?:\\((.*)\\))?\\s*", Pattern.DOTALL);
  private static final String THIS_TEMPLATE = "this";

  /** The rewritten templates (all of them, same keys and order) and the report items. */
  static final class Result {
    private final Map<String, String> templates;
    private final List<Report.Item> items;

    Result(Map<String, String> templates, List<Report.Item> items) {
      this.templates = templates;
      this.items = items;
    }

    Map<String, String> templates() {
      return templates;
    }

    List<Report.Item> items() {
      return items;
    }
  }

  /** An element with the source offsets of its start tag and end tag ({@code -1}: none). */
  private static final class El {
    final String name;
    final int line;
    final El parent;
    final int start;
    int nameEnd;
    int openEnd;
    int closeStart = -1;
    int closeEnd = -1;
    int closeLine;
    /** Attributes in source order, lower-case names; a valueless attribute maps to "". */
    final Map<String, String> attrs = new LinkedHashMap<>();
    final List<El> children = new ArrayList<>();

    El(String name, int line, El parent, int start) {
      this.name = name;
      this.line = line;
      this.parent = parent;
      this.start = start;
    }

    /** A Tessera (or still Thymeleaf) attribute by local name. */
    String t(String local) {
      String v = attrs.get(ConversionRules.TESSERA_PREFIX + local);
      return v != null ? v : attrs.get(ConversionRules.THYMELEAF_PREFIX + local);
    }

    boolean isBlock() {
      return name.equals("t:block") || name.equals("th:block");
    }

    /** Plain HTML attributes, without namespace declarations. */
    Map<String, String> htmlAttrs() {
      Map<String, String> m = new LinkedHashMap<>();
      attrs.forEach((k, v) -> {
        if (!k.startsWith("xmlns")) {
          m.put(k, v);
        }
      });
      return m;
    }

    boolean hasTemplateAttrs() {
      return attrs.keySet().stream().anyMatch(k -> k.startsWith(ConversionRules.TESSERA_PREFIX)
          || k.startsWith(ConversionRules.THYMELEAF_PREFIX));
    }

    boolean hasAncestor(String tag, El upTo) {
      for (El e = parent; e != null; e = e.parent) {
        if (e.name.equals(tag)) {
          return true;
        }
        if (e == upTo) {
          return false;
        }
      }
      return false;
    }
  }

  private static final class Tree {
    final String file;
    final String template;
    final String source;
    final El root;
    final List<El> all = new ArrayList<>();
    final List<int[]> edits = new ArrayList<>();
    final List<String> replacements = new ArrayList<>();

    Tree(String file, String source, El root) {
      this.file = file;
      this.template = file.endsWith(".html") ? file.substring(0, file.length() - 5) : file;
      this.source = source;
      this.root = root;
      collect(root);
    }

    private void collect(El e) {
      for (El c : e.children) {
        all.add(c);
        collect(c);
      }
    }

    El first(String name) {
      for (El e : all) {
        if (e.name.equals(name)) {
          return e;
        }
      }
      return null;
    }

    void edit(int from, int to, String replacement) {
      for (int[] e : edits) {
        if (from < e[1] && e[0] < to || from == to && from == e[0] && e[0] == e[1]) {
          return; // overlaps an earlier edit; that one wins
        }
      }
      edits.add(new int[] {from, to});
      replacements.add(replacement);
    }

    /** Deletes {@code [from, to)}, and its line too if nothing else is on it. */
    void delete(int from, int to) {
      int ls = from;
      while (ls > 0 && (source.charAt(ls - 1) == ' ' || source.charAt(ls - 1) == '\t')) {
        ls--;
      }
      int le = to;
      while (le < source.length() && (source.charAt(le) == ' ' || source.charAt(le) == '\t')) {
        le++;
      }
      boolean lineStart = ls == 0 || source.charAt(ls - 1) == '\n';
      if (lineStart && le < source.length() && source.charAt(le) == '\r') {
        le++;
      }
      if (lineStart && le < source.length() && source.charAt(le) == '\n') {
        edit(ls, le + 1, "");
      } else {
        edit(from, to, "");
      }
    }

    void deleteTags(El e) {
      delete(e.start, e.openEnd);
      if (e.closeStart >= 0) {
        delete(e.closeStart, e.closeEnd);
      }
    }

    String apply() {
      List<Integer> order = new ArrayList<>();
      for (int i = 0; i < edits.size(); i++) {
        order.add(i);
      }
      order.sort(Comparator.comparingInt(i -> edits.get(i)[0]));
      StringBuilder out = new StringBuilder(source.length());
      int pos = 0;
      for (int i : order) {
        out.append(source, pos, edits.get(i)[0]).append(replacements.get(i));
        pos = edits.get(i)[1];
      }
      return out.append(source, pos, source.length()).toString();
    }
  }

  /** A fragment definition: its element and parameter names in order. */
  private static final class Def {
    final Tree tree;
    final El el;
    final String name;
    final List<String> params = new ArrayList<>();

    Def(Tree tree, El el, String name) {
      this.tree = tree;
      this.el = el;
      this.name = name;
    }
  }

  /** A fragment call from an element's {@code t:insert}/{@code t:replace}. */
  private static final class Call {
    final Tree tree;
    final El host;
    final String target;
    final String args;

    Call(Tree tree, El host, String target, String args) {
      this.tree = tree;
      this.host = host;
      this.target = target;
      this.args = args;
    }
  }

  /**
   * Rewrites and checks {@code templates} (relative path with '/' and {@code .html} to converted
   * source). Templates attoparser cannot parse are left as they are.
   */
  static Result rewrite(Map<String, String> templates) {
    List<Tree> trees = new ArrayList<>();
    for (Map.Entry<String, String> e : templates.entrySet()) {
      try {
        trees.add(new Tree(e.getKey(), e.getValue(), parse(e.getValue())));
      } catch (ParseException ex) {
        // the conversion already reported it
      }
    }
    Map<String, Def> defs = new LinkedHashMap<>();
    List<Call> calls = new ArrayList<>();
    for (Tree t : trees) {
      index(t, defs, calls);
    }
    List<Report.Item> items = new ArrayList<>();
    // Each page element passed to a fragment that inserts it inside its own element of that name.
    Map<El, El> passedInto = new LinkedHashMap<>();
    Map<El, Call> passedBy = new LinkedHashMap<>();
    for (Call c : calls) {
      resolvePassed(c, defs, passedInto, passedBy, items);
    }

    for (Map.Entry<El, El> p : passedInto.entrySet()) {
      El selected = p.getKey();
      Call c = passedBy.get(selected);
      if (selected.t("remove") != null) {
        continue; // t:remove="tag" passes the content; other values are the author's choice
      }
      if (attributesMerge(selected, p.getValue())) {
        c.tree.edit(selected.nameEnd, selected.nameEnd, " t:remove=\"tag\"");
        items.add(new Report.Item(c.tree.file, selected.line, String.format(
            ConversionRules.LAYOUT_REMOVE_TAG_MESSAGE, selected.name, display(c.target), selected.name)));
      } else {
        items.add(new Report.Item(c.tree.file, selected.line, String.format(
            ConversionRules.LAYOUT_ATTRIBUTES_MESSAGE, startTag(c.tree, selected), display(c.target),
            selected.name, selected.name, selected.name)));
      }
    }
    for (Tree t : trees) {
      for (El e : t.all) {
        if ((e.name.equals("body") || e.name.equals("html")) && e.hasAncestor("body", null)) {
          innerPageElement(t, e, passedInto, items);
        } else if (e.name.equals("head") && e.hasAncestor("body", null)) {
          items.add(new Report.Item(t.file, e.line, ConversionRules.HEAD_IN_BODY_MESSAGE));
        }
        if (e.name.equals("html") && e.parent != t.root && !e.hasAncestor("html", null)) {
          documentInFragment(t, e, calls, passedInto, items);
        }
      }
      reportOtherErrors(t, items);
    }
    for (Call c : calls) {
      Def d = defs.get(c.target);
      if (d != null && d.el.name.equals("html")) {
        beforeLayout(c, items);
      }
    }

    Map<String, String> out = new LinkedHashMap<>(templates);
    for (Tree t : trees) {
      out.put(t.file, t.apply());
    }
    items.sort(Comparator.comparing(Report.Item::file).thenComparingInt(Report.Item::line));
    return new Result(out, Collections.unmodifiableList(items));
  }

  private static void index(Tree t, Map<String, Def> defs, List<Call> calls) {
    for (El e : t.all) {
      String signature = e.t("fragment");
      Matcher sm = signature == null ? null : SIGNATURE.matcher(signature);
      if (sm != null && sm.matches()) {
        Def d = new Def(t, e, sm.group(1));
        if (sm.group(2) != null) {
          for (String p : Expressions.splitTopLevel(sm.group(2), true)) {
            String[] tokens = Compat.strip(p).split("\\s+");
            d.params.add(tokens[tokens.length - 1]);
          }
        }
        defs.putIfAbsent(t.template + "::" + d.name, d);
      }
      for (String attr : new String[] {"insert", "replace"}) {
        String value = e.t(attr);
        Matcher m = value == null ? null : CALL.matcher(value);
        if (m != null && m.matches()) {
          String path = m.group(1).isEmpty() || m.group(1).equals(THIS_TEMPLATE) ? t.template : m.group(1);
          calls.add(new Call(t, e, path + "::" + m.group(2), m.group(3)));
        }
      }
    }
  }

  /**
   * Whether removing tag {@code e} keeps the page the same in a browser: it has no template
   * attributes, and each of its attributes is already on {@code into}, the element a browser
   * merges them into ({@code null}: not known).
   */
  private static boolean attributesMerge(El e, El into) {
    if (e.hasTemplateAttrs()) {
      return false;
    }
    Map<String, String> mine = e.htmlAttrs();
    if (mine.isEmpty()) {
      return true;
    }
    if (into == null) {
      return false;
    }
    Map<String, String> theirs = into.htmlAttrs();
    for (Map.Entry<String, String> a : mine.entrySet()) {
      if (!a.getValue().equals(theirs.get(a.getKey()))) {
        return false;
      }
    }
    return true;
  }

  /**
   * The {@code <tag>} (html or body) a browser merges a stray {@code <tag>} inside {@code inside}
   * into: the layout's, if the page's (outermost) body is passed to one, else the page's own;
   * {@code null} if not known.
   */
  private static El documentElement(Tree t, El inside, String tag, Map<El, El> passedInto) {
    El body = null; // the outermost: a browser merges inner ones into it
    for (El p = inside; p != null; p = p.parent) {
      if (p.name.equals("body")) {
        body = p;
      }
    }
    if (body == null) {
      return null;
    }
    El layoutBody = passedInto.get(body);
    El pageBody = layoutBody != null ? layoutBody : body.t("remove") == null ? body : null;
    if (pageBody == null || tag.equals("body")) {
      return pageBody;
    }
    for (El p = pageBody.parent; p != null; p = p.parent) {
      if (p.name.equals("html")) {
        return p.t("replace") == null && p.t("insert") == null ? p : null;
      }
    }
    return null;
  }

  private static String startTag(Tree t, El e) {
    return t.source.substring(e.start, e.openEnd).replaceAll("\\s+", " ");
  }

  /**
   * Records {@code layout(~{::body})} calls whose fragment inserts the page's element inside its
   * own element of that name; reports those whose fragment or parameter isn't found.
   */
  private static void resolvePassed(Call c, Map<String, Def> defs, Map<El, El> passedInto,
      Map<El, Call> passedBy, List<Report.Item> items) {
    if (c.args == null) {
      return;
    }
    List<String> args = Expressions.splitTopLevel(c.args, false);
    for (int i = 0; i < args.size(); i++) {
      Matcher m = PAGE_SELECTOR.matcher(args.get(i));
      if (!m.matches()) {
        continue;
      }
      String tag = m.group(2);
      El selected = c.tree.first(tag);
      if (selected == null || passedBy.containsKey(selected)) {
        continue;
      }
      Def d = defs.get(c.target);
      int index = m.group(1) == null ? i : d == null ? -1 : d.params.indexOf(m.group(1));
      String param = d == null || index < 0 || index >= d.params.size() ? null : d.params.get(index);
      if (param == null) {
        if (selected.t("remove") == null) {
          items.add(new Report.Item(c.tree.file, selected.line, String.format(
              ConversionRules.LAYOUT_UNRESOLVED_MESSAGE, tag, display(c.target), tag, tag)));
        }
        continue;
      }
      El into = insertedInside(d, param, tag);
      if (into != null) {
        passedInto.put(selected, into);
        passedBy.put(selected, c);
      }
    }
  }

  /** The element {@code <tag>} of fragment {@code d} that it inserts parameter {@code param} in. */
  private static El insertedInside(Def d, String param, String tag) {
    Pattern use = Pattern.compile("(?<![\\w$.:])" + Pattern.quote(param) + "(?![\\w$(])");
    for (El e : d.tree.all) {
      if (!isWithin(e, d.el)) {
        continue;
      }
      for (String attr : new String[] {"insert", "replace"}) {
        String v = e.t(attr);
        if (v == null || !use.matcher(v).find()) {
          continue;
        }
        if (attr.equals("insert") && e.name.equals(tag)) {
          return e;
        }
        for (El p = e.parent; e != d.el && p != null; p = p.parent) {
          if (p.name.equals(tag)) {
            return p;
          }
          if (p == d.el) {
            break;
          }
        }
      }
    }
    return null;
  }

  private static boolean isWithin(El e, El ancestor) {
    for (El p = e; p != null; p = p.parent) {
      if (p == ancestor) {
        return true;
      }
    }
    return false;
  }

  /** A literal {@code <body>} or {@code <html>} inside a {@code <body>}: a browser ignores its tags. */
  private static void innerPageElement(Tree t, El e, Map<El, El> passedInto, List<Report.Item> items) {
    if (e.closeStart >= 0 && attributesMerge(e, documentElement(t, e.parent, e.name, passedInto))) {
      t.deleteTags(e);
      items.add(new Report.Item(t.file, e.line,
          String.format(ConversionRules.INNER_PAGE_ELEMENT_MESSAGE, e.name, e.name, e.name)));
    } else {
      items.add(new Report.Item(t.file, e.line, String.format(
          ConversionRules.INNER_PAGE_ELEMENT_ATTRIBUTES_MESSAGE, startTag(t, e), e.name, e.name, e.name, e.name, e.name)));
    }
  }

  /**
   * A fragment that writes a whole {@code <html>} document (a copied include), inserted only into
   * page bodies: a browser ignores its {@code <html>}, {@code <head>} and {@code <body>} tags and
   * keeps the head's elements in the body.
   */
  private static void documentInFragment(Tree t, El html, List<Call> calls, Map<El, El> passedInto,
      List<Report.Item> items) {
    El fragment = null;
    for (El p = html.parent; p != null && fragment == null; p = p.parent) {
      if (p.t("fragment") != null) {
        fragment = p;
      }
    }
    if (fragment == null) {
      return;
    }
    Matcher sm = SIGNATURE.matcher(fragment.t("fragment"));
    String key = t.template + "::" + (sm.matches() ? sm.group(1) : "");
    List<El> heads = new ArrayList<>();
    List<El> bodies = new ArrayList<>();
    for (El c : html.children) {
      if (c.name.equals("head")) {
        heads.add(c);
      } else if (c.name.equals("body")) {
        bodies.add(c);
      }
    }
    boolean rewritable = false;
    for (Call c : calls) {
      if (!c.target.equals(key)) {
        continue;
      }
      // Every insert is in a page body, and a browser merges nothing new into that page.
      rewritable = attributesMerge(html, documentElement(c.tree, c.host, "html", passedInto));
      for (El b : bodies) {
        rewritable &= attributesMerge(b, documentElement(c.tree, c.host, "body", passedInto));
      }
      if (!rewritable || documentElement(c.tree, c.host, "body", passedInto) == null) {
        rewritable = false;
        break;
      }
    }
    for (El h : heads) {
      rewritable &= h.attrs.isEmpty() && staticMarkup(h);
    }
    if (!rewritable) {
      items.add(new Report.Item(t.file, html.line,
          String.format(ConversionRules.FRAGMENT_DOCUMENT_MESSAGE, display(key))));
      return;
    }
    t.deleteTags(html);
    List<String> dropped = new ArrayList<>();
    for (El h : heads) {
      for (El c : h.children) {
        dropped.add(startTag(t, c));
      }
      t.delete(h.start, h.closeStart >= 0 ? h.closeEnd : h.openEnd);
    }
    for (El b : bodies) {
      t.deleteTags(b);
    }
    items.add(new Report.Item(t.file, html.line, String.format(
        ConversionRules.FRAGMENT_DOCUMENT_REWRITE_MESSAGE, display(key),
        dropped.isEmpty() ? "nothing" : dropped.stream().map(s -> "`" + s + "`").collect(Collectors.joining(", ")))));
  }

  private static boolean staticMarkup(El e) {
    if (e.hasTemplateAttrs() || e.name.contains(":")) {
      return false;
    }
    for (El c : e.children) {
      if (!staticMarkup(c)) {
        return false;
      }
    }
    return true;
  }

  /** Elements a page writes before the layout it calls, so before the layout's doctype. */
  private static void beforeLayout(Call c, List<Report.Item> items) {
    for (El h = c.host; h != null && h != c.tree.root; h = h.parent) {
      El parent = h.parent;
      for (El sibling : parent.children) {
        if (sibling == h) {
          break;
        }
        String remove = sibling.t("remove");
        if (sibling.t("fragment") != null || "all".equals(remove)) {
          continue;
        }
        items.add(new Report.Item(c.tree.file, sibling.line, String.format(
            ConversionRules.BEFORE_LAYOUT_MESSAGE, sibling.name, display(c.target))));
      }
    }
  }

  /** Report-only: a block in a {@code <p>}, elements between head and body, content in a table. */
  private static void reportOtherErrors(Tree t, List<Report.Item> items) {
    for (El e : t.all) {
      if (e.name.equals("p") && e.closeStart >= 0) {
        El block = closesP(e);
        if (block != null) {
          items.add(new Report.Item(t.file, block.line,
              String.format(ConversionRules.BLOCK_IN_P_MESSAGE, block.name, e.line, e.closeLine)));
        }
      }
      if (e.name.equals("html") && e.t("replace") == null && e.t("insert") == null) {
        boolean afterHead = false;
        for (El c : e.children) {
          if (c.name.equals("head")) {
            afterHead = c.closeStart >= 0;
          } else if (c.name.equals("body")) {
            break;
          } else if (afterHead) {
            items.add(new Report.Item(t.file, c.line,
                String.format(ConversionRules.BETWEEN_HEAD_AND_BODY_MESSAGE, c.name)));
          }
        }
      }
      Set<String> allowed = TABLE_CONTENT.get(e.name);
      if (allowed != null && (e.parent == null || !e.parent.isBlock() || e.name.equals("table"))) {
        tableContent(t, e, e.name, allowed, items);
      }
    }
  }

  private static El closesP(El p) {
    for (El c : p.children) {
      String remove = c.t("remove");
      if ("all".equals(remove) || c.t("replace") != null) {
        continue;
      }
      if (CLOSES_P.contains(c.name) && remove == null) {
        return c;
      }
      if (!P_SCOPE.contains(c.name)) {
        El inner = closesP(c);
        if (inner != null) {
          return inner;
        }
      }
    }
    return null;
  }

  private static void tableContent(Tree t, El part, String partName, Set<String> allowed,
      List<Report.Item> items) {
    for (El c : part.children) {
      String remove = c.t("remove");
      if ("all".equals(remove) || c.t("replace") != null || c.t("fragment") != null) {
        continue;
      }
      if (c.isBlock() || "tag".equals(remove)) {
        tableContent(t, c, partName, allowed, items);
      } else if (!allowed.contains(c.name) && !(c.name.equals("input")
          && "hidden".equalsIgnoreCase(c.attrs.get("type")))) {
        items.add(new Report.Item(t.file, c.line,
            String.format(ConversionRules.IN_TABLE_MESSAGE, c.name, partName)));
      }
    }
  }

  private static String display(String key) {
    return key.replace("::", " :: ");
  }

  private static El parse(String source) throws ParseException {
    Builder b = new Builder(source);
    PARSER.parse(source.toCharArray(), b);
    return b.root;
  }

  /** Builds the element tree with offsets; void elements and {@code <x/>} have no children. */
  private static final class Builder extends AbstractMarkupHandler {
    private final String source;
    final El root = new El("#root", 1, null, 0);
    private El current = root;
    private El pending;
    private boolean pendingClosed;

    Builder(String source) {
      this.source = source;
    }

    private void start(char[] buffer, int offset, int len, int line, boolean standalone) {
      String name = new String(buffer, offset, len).toLowerCase(Locale.ROOT);
      pending = new El(name, line, current, offset - 1);
      pending.nameEnd = offset + len;
      pending.openEnd = tagEnd(offset + len);
      pendingClosed = standalone || VOID_ELEMENTS.contains(name);
      current.children.add(pending);
    }

    /** The offset after the {@code >} that ends the tag, skipping quoted values. */
    private int tagEnd(int from) {
      char quote = 0;
      for (int i = from; i < source.length(); i++) {
        char ch = source.charAt(i);
        if (quote != 0) {
          if (ch == quote) {
            quote = 0;
          }
        } else if (ch == '"' || ch == '\'') {
          quote = ch;
        } else if (ch == '>') {
          return i + 1;
        }
      }
      return source.length();
    }

    private void end() {
      if (pending != null && !pendingClosed) {
        current = pending;
      }
      pending = null;
    }

    @Override
    public void handleOpenElementStart(char[] buffer, int nameOffset, int nameLen, int line, int col) {
      start(buffer, nameOffset, nameLen, line, false);
    }

    @Override
    public void handleOpenElementEnd(char[] buffer, int nameOffset, int nameLen, int line, int col) {
      end();
    }

    @Override
    public void handleStandaloneElementStart(
        char[] buffer, int nameOffset, int nameLen, boolean minimized, int line, int col) {
      start(buffer, nameOffset, nameLen, line, true);
    }

    @Override
    public void handleStandaloneElementEnd(
        char[] buffer, int nameOffset, int nameLen, boolean minimized, int line, int col) {
      end();
    }

    @Override
    public void handleCloseElementStart(char[] buffer, int nameOffset, int nameLen, int line, int col) {
      close(new String(buffer, nameOffset, nameLen).toLowerCase(Locale.ROOT), nameOffset, line);
    }

    @Override
    public void handleUnmatchedCloseElementStart(
        char[] buffer, int nameOffset, int nameLen, int line, int col) {
      close(new String(buffer, nameOffset, nameLen).toLowerCase(Locale.ROOT), nameOffset, line);
    }

    /** Closes the innermost open element of that name and everything inside it; else ignored. */
    private void close(String name, int nameOffset, int line) {
      for (El n = current; n != root; n = n.parent) {
        if (n.name.equals(name)) {
          n.closeStart = nameOffset - 2;
          n.closeEnd = tagEnd(nameOffset);
          n.closeLine = line;
          current = n.parent;
          return;
        }
      }
    }

    @Override
    public void handleAttribute(
        char[] buffer,
        int nameOffset, int nameLen, int nameLine, int nameCol,
        int operatorOffset, int operatorLen, int operatorLine, int operatorCol,
        int valueContentOffset, int valueContentLen, int valueOuterOffset, int valueOuterLen,
        int valueLine, int valueCol) {
      if (pending == null) {
        return;
      }
      String name = new String(buffer, nameOffset, nameLen).toLowerCase(Locale.ROOT);
      pending.attrs.put(name, operatorLen == 0 ? "" : new String(buffer, valueContentOffset, valueContentLen));
    }
  }
}
