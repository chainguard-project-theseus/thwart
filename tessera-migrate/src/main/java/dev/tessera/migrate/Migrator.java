package dev.tessera.migrate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.attoparser.AbstractMarkupHandler;
import org.attoparser.MarkupParser;
import org.attoparser.ParseException;
import org.attoparser.config.ParseConfiguration;

/**
 * Converts one Thymeleaf template to Tessera syntax. The rewrite is source-preserving: attoparser
 * reports the exact offsets of element names and attributes, and only those byte ranges are
 * replaced; everything else is copied unchanged. Running it on its own output changes nothing.
 */
final class Migrator {
  private Migrator() {}

  /** The converted source and the report items for this file. */
  record Result(String output, List<Report.Item> items) {}

  private static final MarkupParser PARSER = new MarkupParser(parseConfiguration());

  private static final Pattern FRAGMENT_SIGNATURE =
      Pattern.compile("(\\s*)([\\w-]+)\\s*(?:\\((.*)\\))?(\\s*)", Pattern.DOTALL);

  private static ParseConfiguration parseConfiguration() {
    ParseConfiguration config = ParseConfiguration.htmlConfiguration();
    config.setElementBalancing(ParseConfiguration.ElementBalancing.NO_BALANCING);
    return config;
  }

  /**
   * Converts {@code source}; {@code file} is the path used in report items.
   *
   * @throws ParseException if attoparser cannot parse the markup
   */
  static Result migrate(String file, String source) throws ParseException {
    char[] doc = source.toCharArray();
    Handler handler = new Handler(file, doc);
    PARSER.parse(doc, handler);
    return handler.finish(source);
  }

  private record Edit(int offset, int length, String replacement) {}

  private static final class Handler extends AbstractMarkupHandler {
    private final String file;
    private final char[] doc;
    private final List<Edit> edits = new ArrayList<>();
    private final List<Report.Item> items = new ArrayList<>();
    private final Set<String> roots = new LinkedHashSet<>();
    private final Set<String> locals = new LinkedHashSet<>();
    private boolean rootSeen;
    private boolean inRoot;
    private boolean rootHasParams;
    private int rootLine;
    private int rootInsertAt;

    Handler(String file, char[] doc) {
      this.file = file;
      this.doc = doc;
    }

    @Override
    public void handleOpenElementStart(char[] buffer, int nameOffset, int nameLen, int line, int col) {
      elementStart(buffer, nameOffset, nameLen, line);
    }

    @Override
    public void handleStandaloneElementStart(
        char[] buffer, int nameOffset, int nameLen, boolean minimized, int line, int col) {
      elementStart(buffer, nameOffset, nameLen, line);
    }

    @Override
    public void handleCloseElementStart(char[] buffer, int nameOffset, int nameLen, int line, int col) {
      renameElement(buffer, nameOffset, nameLen);
    }

    @Override
    public void handleUnmatchedCloseElementStart(
        char[] buffer, int nameOffset, int nameLen, int line, int col) {
      renameElement(buffer, nameOffset, nameLen);
    }

    private void elementStart(char[] buffer, int nameOffset, int nameLen, int line) {
      checkBuffer(buffer);
      inRoot = !rootSeen;
      if (!rootSeen) {
        rootSeen = true;
        rootLine = line;
        rootInsertAt = nameOffset + nameLen;
      }
      renameElement(buffer, nameOffset, nameLen);
    }

    /** {@code <th:block>} becomes {@code <t:block>}. */
    private void renameElement(char[] buffer, int nameOffset, int nameLen) {
      checkBuffer(buffer);
      String name = new String(buffer, nameOffset, nameLen);
      if (name.toLowerCase(Locale.ROOT).startsWith(ConversionRules.THYMELEAF_PREFIX)) {
        edits.add(new Edit(nameOffset, ConversionRules.THYMELEAF_PREFIX.length(),
            ConversionRules.TESSERA_PREFIX));
      }
    }

    @Override
    public void handleAttribute(
        char[] buffer,
        int nameOffset, int nameLen, int nameLine, int nameCol,
        int operatorOffset, int operatorLen, int operatorLine, int operatorCol,
        int valueContentOffset, int valueContentLen, int valueOuterOffset, int valueOuterLen,
        int valueLine, int valueCol) {
      checkBuffer(buffer);
      boolean hasValue = operatorLen > 0;
      if (inRoot) {
        rootInsertAt = hasValue ? valueOuterOffset + valueOuterLen : nameOffset + nameLen;
      }
      String name = new String(buffer, nameOffset, nameLen);
      String lower = name.toLowerCase(Locale.ROOT);
      String value = hasValue ? new String(buffer, valueContentOffset, valueContentLen) : null;
      boolean quoted = valueOuterLen > valueContentLen;

      if (lower.equals(ConversionRules.THYMELEAF_XMLNS)) {
        edits.add(new Edit(nameOffset, nameLen, ConversionRules.TESSERA_XMLNS));
        if (value != null && ConversionRules.THYMELEAF_NAMESPACES.contains(value.strip())) {
          edits.add(new Edit(valueContentOffset, valueContentLen, ConversionRules.TESSERA_NAMESPACE));
        }
        return;
      }
      String unsupported = ConversionRules.unsupportedAttribute(name);
      if (unsupported != null) {
        report(nameLine, unsupported);
        return;
      }
      boolean thymeleaf = lower.startsWith(ConversionRules.THYMELEAF_PREFIX);
      String local;
      if (thymeleaf) {
        local = lower.substring(ConversionRules.THYMELEAF_PREFIX.length());
        edits.add(new Edit(nameOffset, ConversionRules.THYMELEAF_PREFIX.length(),
            ConversionRules.TESSERA_PREFIX));
      } else if (lower.startsWith(ConversionRules.TESSERA_PREFIX)) {
        local = lower.substring(ConversionRules.TESSERA_PREFIX.length());
      } else {
        return; // plain HTML attribute
      }
      if (inRoot && (ConversionRules.TESSERA_PREFIX + local).equals(ConversionRules.PARAMS)) {
        rootHasParams = true;
      }
      if (value == null || ConversionRules.LITERAL_VALUES.contains(local)) {
        return;
      }
      String converted = local.equals(ConversionRules.FRAGMENT)
          ? fragmentSignature(value, nameLine)
          : expressionValue(local, value, thymeleaf, nameLine);
      if (!converted.equals(value)) {
        boolean singleQuoted = quoted && buffer[valueOuterOffset] == '\'';
        boolean needsQuoting = converted.chars().anyMatch(
            c -> Character.isWhitespace(c) || ConversionRules.UNQUOTED_VALUE_FORBIDDEN.indexOf(c) >= 0);
        // A single-quoted original that already holds '"' keeps the content-only edit: no silent escaping.
        if ((!quoted && needsQuoting)
            || (singleQuoted && converted.indexOf('\'') >= 0 && converted.indexOf('"') < 0)) {
          edits.add(new Edit(valueOuterOffset, valueOuterLen, '"' + converted + '"'));
        } else {
          edits.add(new Edit(valueContentOffset, valueContentLen, converted));
        }
      }
    }

    /** {@code f(a, b)} becomes {@code f(Object a, Object b)} plus a report item to type it. */
    private String fragmentSignature(String value, int line) {
      Matcher m = FRAGMENT_SIGNATURE.matcher(value);
      if (!m.matches()) {
        report(line, String.format(ConversionRules.FRAGMENT_SIGNATURE_MESSAGE, value));
        return value;
      }
      if (m.group(3) == null) {
        return value;
      }
      List<String> params = new ArrayList<>();
      List<String> untyped = new ArrayList<>();
      for (String p : Expressions.splitTopLevel(m.group(3), true)) {
        String param = p.strip();
        if (param.isEmpty()) {
          continue;
        }
        String[] tokens = param.split("\\s+");
        locals.add(tokens[tokens.length - 1]);
        if (tokens.length == 1) {
          untyped.add(param);
          params.add(ConversionRules.UNKNOWN_TYPE + " " + param);
        } else {
          params.add(param);
        }
      }
      if (untyped.isEmpty()) {
        return value;
      }
      report(line, String.format(ConversionRules.FRAGMENT_PARAMS_MESSAGE, m.group(2),
          untyped.stream().map(p -> "`" + p + "`").collect(Collectors.joining(", "))));
      return m.group(1) + m.group(2) + "(" + String.join(", ", params) + ")" + m.group(4);
    }

    /**
     * Rewrites an attribute value. {@code thymeleaf} is true for {@code th:} attributes, whose
     * literal tokens ({@code th:class="nav-item"}) are quoted; {@code t:} values are already
     * Tessera, which keeps the rewrite idempotent.
     */
    private String expressionValue(String local, String original, boolean thymeleaf, int line) {
      String value = Expressions.rewriteLinks(original);
      List<String> problems = Expressions.unsupported(value);
      for (String problem : problems) {
        report(line, problem);
      }
      boolean convertible = problems.isEmpty();
      if (local.equals(ConversionRules.EACH)) {
        return eachValue(original, value, convertible);
      }
      if (!convertible) {
        return original; // left in place for the compiler to reject; roots would be guesses
      }
      if (ConversionRules.ASSIGNMENT_VALUES.contains(local)) {
        return assignmentsValue(local, value, thymeleaf);
      }
      if (thymeleaf && Expressions.isLiteralToken(value) && !ConversionRules.SELECTION_VALUES.contains(local)
          && !ConversionRules.FRAGMENT_REFERENCES.contains(local)) {
        return "'" + value.strip() + "'";
      }
      String rewritten = Expressions.rewrite(value);
      if (!ConversionRules.SELECTION_VALUES.contains(local)) {
        roots.addAll(Expressions.roots(rewritten,
            ConversionRules.FRAGMENT_REFERENCES.contains(local)));
      }
      return rewritten;
    }

    /**
     * {@code th:each="x, st : expr"}: the loop variables become locals even when the expression
     * is not convertible, so they are never mistaken for parameters elsewhere in the template.
     */
    private String eachValue(String original, String value, boolean convertible) {
      int colon = Expressions.indexOfTopLevel(value, ':');
      if (colon < 0) {
        return original; // not a loop expression; the compiler reports it
      }
      List<String> vars = Expressions.splitTopLevel(value.substring(0, colon), false);
      vars.forEach(v -> locals.add(v.strip()));
      if (vars.size() == 1) {
        locals.add(vars.get(0).strip() + ConversionRules.IMPLICIT_STATUS_SUFFIX);
      }
      if (!convertible) {
        return original;
      }
      String rewritten = Expressions.rewrite(value.substring(colon + 1));
      roots.addAll(Expressions.roots(rewritten, false));
      return value.substring(0, colon + 1) + rewritten;
    }

    /** {@code name=expr, ...}: each expression is rewritten; {@code with} names become locals. */
    private String assignmentsValue(String local, String value, boolean thymeleaf) {
      List<String> parts = new ArrayList<>();
      for (String part : Expressions.splitTopLevel(value, false)) {
        int eq = Expressions.indexOfTopLevel(part, '=');
        if (eq < 0) {
          parts.add(part);
          continue;
        }
        if (local.equals(ConversionRules.WITH)) {
          locals.add(part.substring(0, eq).strip());
        }
        String expr = part.substring(eq + 1);
        String rewritten = thymeleaf && Expressions.isLiteralToken(expr)
            ? "'" + expr.strip() + "'"
            : Expressions.rewrite(expr);
        roots.addAll(Expressions.roots(rewritten, false));
        parts.add(part.substring(0, eq + 1) + rewritten);
      }
      return String.join(",", parts);
    }

    /** {@code [[...]]} and {@code [(...)]} in text: collect roots and report, never rewrite. */
    @Override
    public void handleText(char[] buffer, int offset, int len, int line, int col) {
      String text = new String(buffer, offset, len);
      inline(text, line, "[[", "]]");
      inline(text, line, "[(", ")]");
    }

    private void inline(String text, int line, String open, String close) {
      int at = text.indexOf(open);
      while (at >= 0) {
        int end = text.indexOf(close, at + open.length());
        if (end < 0) {
          return;
        }
        String expr = text.substring(at + open.length(), end);
        int exprLine = line + (int) text.substring(0, at).chars().filter(c -> c == '\n').count();
        List<String> problems = Expressions.unsupported(expr);
        problems.forEach(p -> report(exprLine, p));
        if (problems.isEmpty()) {
          roots.addAll(Expressions.roots(expr, false));
        }
        at = text.indexOf(open, end + close.length());
      }
    }

    private void report(int line, String message) {
      items.add(new Report.Item(file, line, message));
    }

    private void checkBuffer(char[] buffer) {
      if (buffer != doc) {
        throw new IllegalStateException("attoparser did not parse in place; offsets are not absolute");
      }
    }

    Result finish(String source) {
      Set<String> params = new LinkedHashSet<>(roots);
      params.removeAll(locals);
      if (rootSeen && !rootHasParams && !params.isEmpty()) {
        edits.add(new Edit(rootInsertAt, 0, " " + ConversionRules.PARAMS + "=\""
            + params.stream().map(p -> ConversionRules.UNKNOWN_TYPE + " " + p)
                .collect(Collectors.joining(", "))
            + "\""));
        params.forEach(p -> report(rootLine, String.format(ConversionRules.PARAM_STUB_MESSAGE, p)));
      }
      edits.sort(Comparator.comparingInt(Edit::offset));
      StringBuilder out = new StringBuilder(source.length() + 64);
      int pos = 0;
      for (Edit e : edits) {
        out.append(source, pos, e.offset()).append(e.replacement());
        pos = e.offset() + e.length();
      }
      out.append(source, pos, source.length());
      return new Result(out.toString(), List.copyOf(items));
    }
  }
}
