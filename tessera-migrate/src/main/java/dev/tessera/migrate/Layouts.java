package dev.tessera.migrate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.attoparser.AbstractMarkupHandler;
import org.attoparser.ParseException;

/**
 * Layout markup that a browser reads only by error recovery, which the build rejects:
 * a page passes {@code ~{::body}} (or {@code ::head}, {@code ::html}) to a fragment that inserts
 * it inside its own element of that name. Runs over all converted templates, because whether a
 * page's {@code <body>} nests depends on where the layout inserts it. The page's element gets
 * {@code t:remove="tag"} only if that keeps the page the same in a browser: it has no attributes,
 * or a browser would merge them into a layout element that already has them; otherwise it is
 * reported with the fix. Other parse errors, such as a second {@code <body>} written in one
 * template, are the compiler's to report.
 */
final class Layouts {
  private Layouts() {}

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

  /** An element with the source offsets of its start tag. */
  private static final class El {
    final String name;
    final int line;
    final El parent;
    final int start;
    int nameEnd;
    int openEnd;
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
  }

  private static final class Tree {
    final String file;
    final String template;
    final String source;
    final List<El> all = new ArrayList<>();
    final SourceEdits edits = new SourceEdits();

    Tree(String file, String source, El root) {
      this.file = file;
      this.template = file.endsWith(".html") ? file.substring(0, file.length() - 5) : file;
      this.source = source;
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
      resolvePassed(c, defs, passedInto, passedBy);
    }

    for (Map.Entry<El, El> p : passedInto.entrySet()) {
      El selected = p.getKey();
      Call c = passedBy.get(selected);
      if (selected.t("remove") != null) {
        continue; // t:remove="tag" passes the content; other values are the author's choice
      }
      if (attributesMerge(selected, p.getValue())) {
        c.tree.edits.add(selected.nameEnd, selected.nameEnd, " t:remove=\"tag\"");
        items.add(new Report.Item(c.tree.file, selected.line, String.format(
            ConversionRules.LAYOUT_REMOVE_TAG_MESSAGE, selected.name, display(c.target), selected.name)));
      } else {
        items.add(new Report.Item(c.tree.file, selected.line, String.format(
            ConversionRules.LAYOUT_ATTRIBUTES_MESSAGE, startTag(c.tree, selected), display(c.target),
            selected.name, selected.name, selected.name, moveFix(selected, selected.name, c),
            selected.name)));
      }
    }

    Map<String, String> out = new LinkedHashMap<>(templates);
    for (Tree t : trees) {
      out.put(t.file, t.edits.apply(t.source));
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
          String path = m.group(1).isEmpty() || m.group(1).equals(THIS_TEMPLATE) ? t.template
              : m.group(1).startsWith("/") ? m.group(1).substring(1) : m.group(1);
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
    if (into.t("remove") != null) {
      return false;
    }
    Map<String, String> theirs = into.htmlAttrs();
    for (Map.Entry<String, String> a : mine.entrySet()) {
      // t:<name> replaces the static value and omits it when null
      if (!a.getValue().equals(theirs.get(a.getKey())) || into.t(a.getKey()) != null) {
        return false;
      }
    }
    return true;
  }

  /**
   * The fix for attributes of {@code e} that {@code t:remove="tag"} would drop: move them to the
   * {@code <tag>} of {@code layout}'s fragment. Only called when {@code e} has attributes.
   */
  private static String moveFix(El e, String tag, Call layout) {
    List<String> list = new ArrayList<>();
    e.htmlAttrs().forEach((k, v) -> list.add("`" + k + (v.isEmpty() ? "" : "=\"" + v + "\"") + "`"));
    String attrs = String.join(", ", list);
    Map.Entry<String, String> first = e.htmlAttrs().entrySet().iterator().next();
    String key = first.getKey();
    boolean expression = key.startsWith(ConversionRules.TESSERA_PREFIX) || key.startsWith(ConversionRules.THYMELEAF_PREFIX);
    String name = expression ? key.substring(key.indexOf(':') + 1) : key;
    StringBuilder param = new StringBuilder(tag);
    boolean upper = true;
    for (char ch : name.toCharArray()) {
      if (!Character.isLetterOrDigit(ch)) {
        upper = true;
      } else {
        param.append(upper ? Character.toUpperCase(ch) : ch);
        upper = false;
      }
    }
    return String.format(ConversionRules.LAYOUT_PARAMETER_FIX, display(layout.target), attrs, tag, param, tag,
        name, param, expression ? "`" + first.getValue() + "`" : "`'" + first.getValue() + "'`");
  }

  private static String startTag(Tree t, El e) {
    return t.source.substring(e.start, e.openEnd).replaceAll("\\s+", " ");
  }

  /**
   * Records {@code layout(~{::body})} calls whose fragment inserts the page's element inside its
   * own element of that name. One whose fragment or parameter isn't found is left to the compiler.
   */
  private static void resolvePassed(Call c, Map<String, Def> defs, Map<El, El> passedInto,
      Map<El, Call> passedBy) {
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
      El into = param == null ? null : insertedInside(d, param, tag);
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

  private static String display(String key) {
    return key.replace("::", " :: ");
  }

  private static El parse(String source) throws ParseException {
    Builder b = new Builder(source);
    Markup.PARSER.parse(source.toCharArray(), b);
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
      pendingClosed = standalone || Markup.VOID_ELEMENTS.contains(name);
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
      close(new String(buffer, nameOffset, nameLen).toLowerCase(Locale.ROOT));
    }

    @Override
    public void handleUnmatchedCloseElementStart(
        char[] buffer, int nameOffset, int nameLen, int line, int col) {
      close(new String(buffer, nameOffset, nameLen).toLowerCase(Locale.ROOT));
    }

    /** Closes the innermost open element of that name and everything inside it; else ignored. */
    private void close(String name) {
      for (El n = current; n != root; n = n.parent) {
        if (n.name.equals(name)) {
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
