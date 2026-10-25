package dev.tessera.migrate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.attoparser.AbstractMarkupHandler;
import org.attoparser.MarkupParser;
import org.attoparser.ParseException;
import org.attoparser.config.ParseConfiguration;

/**
 * Finds recursive rich-text fragments in converted templates: a fragment that inserts itself,
 * directly or through other fragments, from inside a {@code t:switch} whose cases render the
 * element their value names ({@code <p t:case="'p'">}). Such a fragment rebuilds stored HTML from
 * a tree parsed in Java, so the data decides the element structure. The fix moves into Java (keep
 * the HTML string and sanitize it), so this is a report, never a rewrite. Reported: the fragment
 * where it is defined, and every insert of it from outside its recursion.
 */
final class RichTextCheck {
  private RichTextCheck() {}

  private static final MarkupParser PARSER = new MarkupParser(parseConfiguration());

  /** {@code tpl :: name(args)}, optionally in {@code ~{...}}; groups: template, fragment name. */
  private static final Pattern REFERENCE =
      Pattern.compile("\\s*(?:~\\{)?\\s*([\\w./-]*)\\s*::\\s*([\\w-]+).*", Pattern.DOTALL);
  private static final Pattern FRAGMENT_NAME = Pattern.compile("\\s*([\\w-]+).*", Pattern.DOTALL);
  private static final Pattern CASE_LITERAL = Pattern.compile("\\s*'([A-Za-z][A-Za-z0-9]*)'\\s*");
  private static final Set<String> VOID_ELEMENTS = Compat.setOf("area", "base", "br", "col", "embed",
      "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr");
  private static final String THIS_TEMPLATE = "this";

  private static ParseConfiguration parseConfiguration() {
    ParseConfiguration config = ParseConfiguration.htmlConfiguration();
    config.setElementBalancing(ParseConfiguration.ElementBalancing.NO_BALANCING);
    return config;
  }

  /** An element; {@code attrs} holds Tessera attributes by local name ({@code th:} too). */
  private static final class Node {
    final String name;
    final int line;
    final Node parent;
    final Map<String, String> attrs = new LinkedHashMap<>();
    final List<Node> children = new ArrayList<>();

    Node(String name, int line, Node parent) {
      this.name = name;
      this.line = line;
      this.parent = parent;
    }

    boolean isBlock() {
      return name.equals("t:block") || name.equals("th:block");
    }
  }

  /** A fragment insertion; {@code from} is the enclosing fragment's key, or null in a page body. */
  private static final class Ref {
    final String file;
    final int line;
    final String from;
    final String to;
    final boolean underTagSwitch;

    Ref(String file, int line, String from, String to, boolean underTagSwitch) {
      this.file = file;
      this.line = line;
      this.from = from;
      this.to = to;
      this.underTagSwitch = underTagSwitch;
    }
  }

  private static final class Def {
    final String file;
    final int line;

    Def(String file, int line) {
      this.file = file;
      this.line = line;
    }
  }

  /**
   * Checks every template. Keys of {@code templates} are paths relative to the template root,
   * with '/', ending in {@code .html}; values are the converted sources. Templates attoparser
   * cannot parse are skipped (the conversion already reported them).
   */
  static List<Report.Item> check(Map<String, String> templates) {
    Map<String, Def> defs = new LinkedHashMap<>();
    List<Ref> refs = new ArrayList<>();
    for (Map.Entry<String, String> e : templates.entrySet()) {
      Node root;
      try {
        root = parse(e.getValue());
      } catch (ParseException ex) {
        continue;
      }
      collect(e.getKey(), templateName(e.getKey()), root, null, defs, refs);
    }

    Map<String, Set<String>> edges = new LinkedHashMap<>();
    for (Ref r : refs) {
      if (r.from != null && defs.containsKey(r.to)) {
        edges.computeIfAbsent(r.from, k -> new LinkedHashSet<>()).add(r.to);
      }
    }
    // The fragments that recurse through an insert under a tag switch, in definition order.
    Set<String> richText = new LinkedHashSet<>();
    for (Ref r : refs) {
      if (r.underTagSwitch && r.from != null && defs.containsKey(r.to)
          && reachable(edges, r.to).contains(r.from)) {
        richText.add(r.from);
      }
    }

    List<Report.Item> items = new ArrayList<>();
    Set<String> done = new HashSet<>();
    for (String fragment : richText) {
      if (done.contains(fragment)) {
        continue;
      }
      Set<String> group = group(fragment, edges, refs);
      done.addAll(group);
      Set<String> flagged = new LinkedHashSet<>();
      for (String f : richText) {
        if (group.contains(f)) {
          flagged.add(f);
        }
      }
      String entry = entry(group, refs, flagged.iterator().next());
      for (String f : flagged) {
        Def d = defs.get(f);
        items.add(new Report.Item(d.file, d.line,
            String.format(ConversionRules.RICH_TEXT_FRAGMENT_MESSAGE, display(f))));
      }
      Def at = defs.get(flagged.iterator().next());
      for (Ref r : refs) {
        if (group.contains(r.to) && (r.from == null || !group.contains(r.from))) {
          items.add(new Report.Item(r.file, r.line, String.format(
              ConversionRules.RICH_TEXT_CALL_SITE_MESSAGE, display(entry), at.file, at.line)));
        }
      }
    }
    return items;
  }

  /** {@code fragments/markup.html} is template {@code fragments/markup}. */
  private static String templateName(String file) {
    return file.endsWith(".html") ? file.substring(0, file.length() - ".html".length()) : file;
  }

  private static String display(String key) {
    return key.replace("::", " :: ");
  }

  /**
   * The fragment's recursion (every fragment it reaches that reaches it back), plus the fragments
   * of the same template that only lead into it ({@code nodes(list)} looping over {@code node(n)}),
   * so call sites are the inserts from pages, not the wrapper's own.
   */
  private static Set<String> group(String fragment, Map<String, Set<String>> edges, List<Ref> refs) {
    Set<String> group = new LinkedHashSet<>();
    for (String f : reachable(edges, fragment)) {
      if (reachable(edges, f).contains(fragment)) {
        group.add(f);
      }
    }
    group.add(fragment);
    String template = fragment.substring(0, fragment.indexOf("::"));
    boolean grown = true;
    while (grown) {
      grown = false;
      for (Ref r : refs) {
        if (r.from != null && group.contains(r.to) && !group.contains(r.from)
            && r.from.startsWith(template + "::")) {
          group.add(r.from);
          grown = true;
        }
      }
    }
    return group;
  }

  /** The fragment pages insert: the first group member inserted from outside, else {@code fallback}. */
  private static String entry(Set<String> group, List<Ref> refs, String fallback) {
    for (Ref r : refs) {
      if (group.contains(r.to) && (r.from == null || !group.contains(r.from))) {
        return r.to;
      }
    }
    return fallback;
  }

  /** Fragments reachable from {@code start} by one or more inserts. */
  private static Set<String> reachable(Map<String, Set<String>> edges, String start) {
    Set<String> seen = new LinkedHashSet<>();
    Deque<String> todo = new ArrayDeque<>();
    todo.push(start);
    while (!todo.isEmpty()) {
      Set<String> next = edges.get(todo.pop());
      if (next == null) {
        continue;
      }
      for (String n : next) {
        if (seen.add(n)) {
          todo.push(n);
        }
      }
    }
    return seen;
  }

  private static void collect(String file, String template, Node node, String fragment,
      Map<String, Def> defs, List<Ref> refs) {
    String signature = node.attrs.get("fragment");
    if (signature != null) {
      Matcher m = FRAGMENT_NAME.matcher(signature);
      if (m.matches()) {
        fragment = template + "::" + m.group(1);
        if (!defs.containsKey(fragment)) {
          defs.put(fragment, new Def(file, node.line));
        }
      }
    }
    for (String attr : new String[] {"insert", "replace"}) {
      String value = node.attrs.get(attr);
      Matcher m = value == null ? null : REFERENCE.matcher(value);
      if (m != null && m.matches()) {
        String target = m.group(1).isEmpty() || m.group(1).equals(THIS_TEMPLATE) ? template : m.group(1);
        refs.add(new Ref(file, node.line, fragment, target + "::" + m.group(2),
            fragment != null && underTagSwitch(node)));
      }
    }
    for (Node child : node.children) {
      collect(file, template, child, fragment, defs, refs);
    }
  }

  /** Whether {@code node} or an ancestor up to its fragment's element is a tag switch. */
  private static boolean underTagSwitch(Node node) {
    for (Node n = node; n != null; n = n.parent) {
      if (n.attrs.containsKey("switch") && isTagSwitch(n)) {
        return true;
      }
      if (n.attrs.containsKey("fragment")) {
        return false;
      }
    }
    return false;
  }

  /**
   * A {@code t:switch} at least two of whose cases render the element their literal names:
   * {@code <p t:case="'p'">}, or {@code <t:block t:case="'p'"><p>}.
   */
  private static boolean isTagSwitch(Node sw) {
    Set<String> tags = new HashSet<>();
    for (Node c : sw.children) {
      String value = c.attrs.get("case");
      Matcher m = value == null ? null : CASE_LITERAL.matcher(value);
      if (m == null || !m.matches()) {
        continue;
      }
      Node rendered = c.isBlock() && !c.children.isEmpty() ? c.children.get(0) : c;
      String tag = m.group(1).toLowerCase(Locale.ROOT);
      if (rendered.name.equals(tag)) {
        tags.add(tag);
      }
    }
    return tags.size() >= 2;
  }

  private static Node parse(String source) throws ParseException {
    Builder builder = new Builder();
    PARSER.parse(source.toCharArray(), builder);
    return builder.root;
  }

  /** Builds the element tree; void elements and {@code <x/>} have no children. */
  private static final class Builder extends AbstractMarkupHandler {
    final Node root = new Node("#root", 1, null);
    private Node current = root;
    private Node pending;
    private boolean pendingClosed;

    private void start(char[] buffer, int offset, int len, int line, boolean standalone) {
      String name = new String(buffer, offset, len).toLowerCase(Locale.ROOT);
      pending = new Node(name, line, current);
      pendingClosed = standalone || VOID_ELEMENTS.contains(name);
      current.children.add(pending);
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
      for (Node n = current; n != root; n = n.parent) {
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
      if (pending == null || operatorLen == 0) {
        return;
      }
      String name = new String(buffer, nameOffset, nameLen).toLowerCase(Locale.ROOT);
      String local = name.startsWith(ConversionRules.TESSERA_PREFIX)
          ? name.substring(ConversionRules.TESSERA_PREFIX.length())
          : name.startsWith(ConversionRules.THYMELEAF_PREFIX)
              ? name.substring(ConversionRules.THYMELEAF_PREFIX.length())
              : null;
      if (local != null) {
        pending.attrs.put(local, new String(buffer, valueContentOffset, valueContentLen));
      }
    }
  }
}
