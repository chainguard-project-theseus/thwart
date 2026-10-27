package dev.tessera.migrate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
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
 * Converts one Thymeleaf template to Tessera syntax. The rewrite is source-preserving: attoparser
 * reports the exact offsets of element names and attributes, and only those byte ranges are
 * replaced; everything else is copied unchanged. Running it on its own output changes nothing.
 */
final class Migrator {
  private Migrator() {}

  /** The converted source and the report items for this file. */
  static final class Result {
    private final String output;
    private final List<Report.Item> items;

    Result(String output, List<Report.Item> items) {
      this.output = output;
      this.items = items;
    }

    String output() {
      return output;
    }

    List<Report.Item> items() {
      return items;
    }
  }

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

  private static final class Edit {
    private final int offset;
    private final int length;
    private final String replacement;

    Edit(int offset, int length, String replacement) {
      this.offset = offset;
      this.length = length;
      this.replacement = replacement;
    }

    int offset() {
      return offset;
    }

    int length() {
      return length;
    }

    String replacement() {
      return replacement;
    }
  }

  /** An open element: its lower-case name and whether {@code th:inline="none"} governs its body. */
  private static final class Open {
    private final String name;
    private final boolean noInline;

    Open(String name, boolean noInline) {
      this.name = name;
      this.noInline = noInline;
    }
  }

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
    /** Lower-case name of the element whose start tag is being read. */
    private String openName;
    /** The {@code type} of that {@code <script>}; {@code null} after a {@code t:type}. */
    private String scriptType;
    /** True between the start and end tag of a JavaScript or JSON {@code <script>}. */
    private boolean inScript;
    /** The {@code th:inline} value (stripped, lower case) of that element, or {@code null}. */
    private String inlineMode;
    /**
     * Open elements, innermost first. An approximation of the HTML tree: attoparser (no balancing)
     * reports no implied end tags, so a close tag pops to the innermost element of its name and a
     * stray close tag pops nothing; a misread can only cost a missed rewrite or report.
     */
    private final Deque<Open> open = new ArrayDeque<>();
    /** The end of the previous attribute's quoted value in this tag, or -1. */
    private int quotedValueEnd = -1;

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
    public void handleOpenElementEnd(char[] buffer, int nameOffset, int nameLen, int line, int col) {
      // The nearest th:inline wins; Thymeleaf leaves [[...]] alone in a th:inline="none" body.
      boolean none = inlineMode == null ? noInline() : inlineMode.equals(ConversionRules.INLINE_NONE);
      open.push(new Open(openName, none));
      inScript = !none && ConversionRules.SCRIPT.equals(openName) && scriptType != null
          && ConversionRules.SCRIPT_TYPES.contains(Compat.strip(scriptType).toLowerCase(Locale.ROOT));
    }

    @Override
    public void handleCloseElementStart(char[] buffer, int nameOffset, int nameLen, int line, int col) {
      inScript = false;
      renameElement(buffer, nameOffset, nameLen);
      closeElement(new String(buffer, nameOffset, nameLen).toLowerCase(Locale.ROOT));
    }

    private void closeElement(String name) {
      for (Open o : open) {
        if (o.name.equals(name)) {
          while (open.pop() != o) {
            // implicitly closed children
          }
          return;
        }
      }
    }

    private boolean noInline() {
      return !open.isEmpty() && open.peek().noInline;
    }

    /**
     * Whether the element whose start tag is being read is an SVG element: {@code <svg>} or one of
     * the {@link ConversionRules#SVG_HTML_PARENTS}, or inside an {@code <svg>} and not inside one of
     * those (their content is HTML). MathML is not tracked: it has none of these attributes.
     */
    private boolean inSvg() {
      if (openName.equals(ConversionRules.SVG) || ConversionRules.SVG_HTML_PARENTS.contains(openName)) {
        return true;
      }
      for (Open o : open) {
        if (o.name.equals(ConversionRules.SVG)) {
          return true;
        }
        if (ConversionRules.SVG_HTML_PARENTS.contains(o.name)) {
          return false;
        }
      }
      return false;
    }

    @Override
    public void handleUnmatchedCloseElementStart(
        char[] buffer, int nameOffset, int nameLen, int line, int col) {
      renameElement(buffer, nameOffset, nameLen);
    }

    private void elementStart(char[] buffer, int nameOffset, int nameLen, int line) {
      checkBuffer(buffer);
      openName = new String(buffer, nameOffset, nameLen).toLowerCase(Locale.ROOT);
      scriptType = "";
      inlineMode = null;
      inScript = false;
      quotedValueEnd = -1;
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
      if (ConversionRules.SCRIPT.equals(openName)) {
        if (lower.equals(ConversionRules.TYPE) && scriptType != null) {
          scriptType = value == null ? "" : value;
        } else if (lower.equals(ConversionRules.THYMELEAF_PREFIX + ConversionRules.TYPE)
            || lower.equals(ConversionRules.TESSERA_PREFIX + ConversionRules.TYPE)) {
          scriptType = null;
        }
      }
      if (lower.equals(ConversionRules.THYMELEAF_INLINE) && value != null) {
        inlineMode = Compat.strip(value).toLowerCase(Locale.ROOT);
      }
      if (quotedValueEnd == nameOffset) {
        report(nameLine, String.format(ConversionRules.ATTRIBUTE_SPACE_MESSAGE, name));
      }
      quotedValueEnd = quoted ? valueOuterOffset + valueOuterLen : -1;

      if (lower.equals(ConversionRules.THYMELEAF_XMLNS)) {
        edits.add(new Edit(nameOffset, nameLen, ConversionRules.TESSERA_XMLNS));
        if (value != null && ConversionRules.THYMELEAF_NAMESPACES.contains(Compat.strip(value))) {
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
      if (thymeleaf && inSvg()
          && ConversionRules.svgStyleAttribute(lower.substring(ConversionRules.THYMELEAF_PREFIX.length()), openName)) {
        report(nameLine, String.format(ConversionRules.SVG_STYLE_MESSAGE, name,
            name.substring(ConversionRules.THYMELEAF_PREFIX.length())));
        return;
      }
      // TODO(#331): report a computed name the compiler does not know (#330): not a standard HTML,
      // SVG, MathML or ARIA attribute, data-*, a custom element's prop or a framework name, such as
      // th:hx-target (fix-it: th:data-hx-target) or th:up-on-loaded.
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
          : expressionValue(local, value, thymeleaf, name, nameLine);
      if (thymeleaf && local.equals(ConversionRules.UTEXT)
          && Expressions.unsupported(Expressions.rewriteLinks(value)).isEmpty()) {
        rawOutput(nameLine, name, converted);
      }
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
        String param = Compat.strip(p);
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
     * literal tokens ({@code th:class="nav-item"}, {@code @{/r(mode=mixed)}}) are quoted;
     * {@code t:} values are already Tessera, which keeps the rewrite idempotent.
     */
    private String expressionValue(String local, String original, boolean thymeleaf, String name, int line) {
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
        return assignmentsValue(local, value, thymeleaf, name, line);
      }
      boolean selection = ConversionRules.SELECTION_VALUES.contains(local);
      boolean fragmentReference = ConversionRules.FRAGMENT_REFERENCES.contains(local);
      if (thymeleaf && !selection) {
        value = Expressions.quoteTokens(value, fragmentReference);
      }
      String rewritten = Expressions.rewrite(value);
      if (!selection) {
        roots.addAll(Expressions.roots(rewritten, fragmentReference));
      }
      nullablePathVariables(line, rewritten);
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
      vars.forEach(v -> locals.add(Compat.strip(v)));
      if (vars.size() == 1) {
        locals.add(Compat.strip(vars.get(0)) + ConversionRules.IMPLICIT_STATUS_SUFFIX);
      }
      if (!convertible) {
        return original;
      }
      String rewritten = Expressions.rewrite(value.substring(colon + 1));
      roots.addAll(Expressions.roots(rewritten, false));
      return value.substring(0, colon + 1) + rewritten;
    }

    /**
     * {@code name=expr, ...}: each expression is rewritten; {@code with} names become locals, and
     * a {@code th:attr} entry that sets a CSS attribute of an SVG element is reported.
     */
    private String assignmentsValue(String local, String value, boolean thymeleaf, String name, int line) {
      List<String> parts = new ArrayList<>();
      for (String part : Expressions.splitTopLevel(value, false)) {
        int eq = Expressions.indexOfTopLevel(part, '=');
        if (eq < 0) {
          parts.add(part);
          continue;
        }
        String target = Compat.strip(part.substring(0, eq));
        if (local.equals(ConversionRules.WITH)) {
          locals.add(target);
        } else if (thymeleaf && inSvg()
            && ConversionRules.svgStyleAttribute(target.toLowerCase(Locale.ROOT), openName)) {
          report(line, String.format(ConversionRules.SVG_STYLE_IN_ATTR_MESSAGE,
              name, target));
        }
        String expr = part.substring(eq + 1);
        String rewritten = Expressions.rewrite(thymeleaf ? Expressions.quoteTokens(expr, false) : expr);
        roots.addAll(Expressions.roots(rewritten, false));
        nullablePathVariables(line, rewritten);
        parts.add(part.substring(0, eq + 1) + rewritten);
      }
      return String.join(",", parts);
    }

    /**
     * Reports each link in {@code expr} (Tessera syntax) with a path variable that is not a literal:
     * Tessera fails the render on a {@code null} one, where Thymeleaf wrote an empty segment.
     */
    private void nullablePathVariables(int line, String expr) {
      for (Map.Entry<String, Map<String, String>> link : Expressions.linkPathVariables(expr).entrySet()) {
        String vars = link.getValue().entrySet().stream()
            .map(v -> "`" + v.getKey() + "` = " + codeSpan(v.getValue()))
            .collect(Collectors.joining(", "));
        report(line, String.format(ConversionRules.NULL_PATH_VARIABLE_MESSAGE, codeSpan(link.getKey()), vars));
      }
    }

    /**
     * {@code [[...]]} and {@code [(...)]} in text: collect roots and report. The only rewrite is in
     * a JavaScript or JSON {@code <script>}, see {@link #quotedInlines}.
     */
    @Override
    public void handleText(char[] buffer, int offset, int len, int line, int col) {
      checkBuffer(buffer);
      if (noInline()) {
        return; // literal text in Thymeleaf: no rewrite and no roots
      }
      String text = new String(buffer, offset, len);
      if (inScript) {
        quotedInlines(text, offset, line);
      }
      inline(text, line, "[[", "]]");
      inline(text, line, "[(", ")]");
    }

    private static final int CODE = 0;
    private static final int STRING = 1;
    private static final int LINE_COMMENT = 2;
    private static final int BLOCK_COMMENT = 3;

    /**
     * Finds {@code [[...]]} inside JavaScript string literals of script text that starts at
     * {@code offset} of the document. In Tessera {@code [[...]]} writes a JSON value with its own
     * quotes, so a Thymeleaf {@code "[[${x}]]"} would close the string (the compiler rejects it, #281).
     * A {@code "..."} or {@code '...'} literal that is exactly one inline loses its quotes; any other
     * inline in a literal is reported, including {@code `[[...]]`} (the backticks may follow a tag,
     * {@code String.raw`...`}, which dropping them would break), and so is an inline in a comment. The scan tracks strings, template
     * literal {@code ${...}} and comments, not regular expression literals, with the compiler's rules
     * (#281): an unterminated {@code '}/{@code "} string ends at {@code \n} or {@code \r}, not at
     * U+2028/U+2029 (ES2019), and {@code \} followed by CRLF is a line continuation. The compiler is
     * the safety net, so a misread only misses or adds a report, and dropping the quotes can't make
     * it unsafe.
     */
    private void quotedInlines(String text, int offset, int line) {
      int state = CODE;
      char quote = 0;
      int quoteAt = -1; // the opening quote when the current literal so far is only that quote
      int braces = 0;
      Deque<Integer> templates = new ArrayDeque<>(); // brace depth at each ${ of a template literal
      for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        if (text.startsWith("[[", i)) {
          int end = Expressions.inlineEnd(text, i + 2, "]]");
          if (end < 0) {
            return;
          }
          int after = end + 2;
          if (state == STRING) {
            String inline = text.substring(i, after);
            int at = lineAt(text, line, i);
            boolean whole = quoteAt == i - 1 && after < text.length() && text.charAt(after) == quote;
            if (whole && quote == '`') {
              report(at, String.format(ConversionRules.SCRIPT_INLINE_TEMPLATE_LITERAL_MESSAGE,
                  codeSpan(quote + inline + quote)));
              state = CODE;
              i = after; // the closing backtick
              continue;
            }
            if (whole) {
              edits.add(new Edit(offset + quoteAt, 1, ""));
              edits.add(new Edit(offset + after, 1, ""));
              report(at, String.format(ConversionRules.SCRIPT_INLINE_UNQUOTED_MESSAGE,
                  codeSpan(quote + inline + quote)));
              state = CODE;
              i = after; // the closing quote
              continue;
            }
            report(at, String.format(ConversionRules.SCRIPT_INLINE_IN_STRING_MESSAGE, inline, inline));
            quoteAt = -1;
          } else if (state == LINE_COMMENT || state == BLOCK_COMMENT) {
            // The compiler rejects an inline in a comment (#281). TODO(#315): rewrite the natural
            // template /*[[${x}]]*/ 'd' to [[${x}]] instead of only reporting it.
            String inline = text.substring(i, after);
            report(lineAt(text, line, i), String.format(ConversionRules.SCRIPT_INLINE_IN_COMMENT_MESSAGE, inline));
          }
          i = after - 1;
          continue;
        }
        boolean lineEnd = c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029';
        switch (state) {
          case CODE:
            if (c == '"' || c == '\'' || c == '`') {
              state = STRING;
              quote = c;
              quoteAt = i;
            } else if (c == '/' && text.startsWith("//", i)) {
              state = LINE_COMMENT;
            } else if (c == '/' && text.startsWith("/*", i)) {
              state = BLOCK_COMMENT;
              i++; // so that "/*/" does not end the comment
            } else if (c == '{') {
              braces++;
            } else if (c == '}' && !templates.isEmpty() && templates.peek() == braces) {
              templates.pop();
              state = STRING;
              quote = '`';
              quoteAt = -1;
            } else if (c == '}') {
              braces--;
            }
            break;
          case STRING:
            quoteAt = -1;
            if (c == '\\') {
              i++;
              if (text.startsWith("\r\n", i)) {
                i++; // "\" + CRLF is one line continuation
              }
            } else if (c == quote || ((c == '\n' || c == '\r') && quote != '`')) {
              state = CODE;
            } else if (quote == '`' && text.startsWith("${", i)) {
              templates.push(braces);
              state = CODE;
              i++;
            }
            break;
          case LINE_COMMENT:
            if (lineEnd) {
              state = CODE;
            }
            break;
          default:
            if (text.startsWith("*/", i)) {
              state = CODE;
              i++;
            }
            break;
        }
      }
    }

    private static String codeSpan(String s) {
      return s.indexOf('`') >= 0 ? "`` " + s + " ``" : "`" + s + "`";
    }

    /** The line of {@code text.charAt(index)}, where {@code text} starts on {@code line}. */
    private static int lineAt(String text, int line, int index) {
      return line + (int) text.substring(0, index).chars().filter(c -> c == '\n').count();
    }

    private void inline(String text, int line, String open, String close) {
      int at = text.indexOf(open);
      while (at >= 0) {
        int end = Expressions.inlineEnd(text, at + open.length(), close);
        if (end < 0) {
          return;
        }
        String expr = text.substring(at + open.length(), end);
        int exprLine = lineAt(text, line, at);
        List<String> problems = Expressions.unsupported(expr);
        problems.forEach(p -> report(exprLine, p));
        if (problems.isEmpty()) {
          roots.addAll(Expressions.roots(expr, false));
          if (open.equals("[(")) {
            rawOutput(exprLine, "[(...)]", Expressions.rewrite(expr));
          }
        }
        at = text.indexOf(open, end + close.length());
      }
    }

    /** Reports raw output of {@code expr} (Tessera syntax), unless a message or already sanitized. */
    private void rawOutput(int line, String construct, String expr) {
      String e = Compat.strip(expr);
      if (!ConversionRules.SAFE_UTEXT.matcher(e).matches()) {
        report(line, String.format(ConversionRules.UTEXT_MESSAGE, construct, e, e));
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
      return new Result(out.toString(), Collections.unmodifiableList(new ArrayList<>(items)));
    }
  }
}
