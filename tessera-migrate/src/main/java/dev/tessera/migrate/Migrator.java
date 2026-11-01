package dev.tessera.migrate;

import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.attoparser.AbstractMarkupHandler;
import org.attoparser.ParseException;

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

  private static final Pattern FRAGMENT_SIGNATURE =
      Pattern.compile("(\\s*)([\\w-]+)\\s*(?:\\((.*)\\))?(\\s*)", Pattern.DOTALL);

  /**
   * Converts {@code source}; {@code file} is the path used in report items.
   *
   * @throws ParseException if attoparser cannot parse the markup
   */
  static Result migrate(String file, String source) throws ParseException {
    source = prototypeOnlyContent(source);
    char[] doc = source.toCharArray();
    Handler handler = new Handler(file, doc);
    Markup.PARSER.parse(doc, handler);
    return handler.finish(source);
  }

  /**
   * {@code source} without the markers of Thymeleaf's prototype-only comments {@code <!--/*&#47; ...
   * /*&#47;-->}: Thymeleaf drops them before parsing and renders the content, and Tessera rejects
   * them (#132). Like Thymeleaf, it applies everywhere, and leaves one without its end alone.
   */
  static String prototypeOnlyContent(String source) {
    StringBuilder sb = new StringBuilder();
    int from = 0;
    for (int start = source.indexOf("<!--/*/"); start >= 0; start = source.indexOf("<!--/*/", from)) {
      int end = source.indexOf("/*/-->", start + 7);
      if (end < 0) {
        break;
      }
      sb.append(source, from, start).append(source, start + 7, end);
      from = end + 6;
    }
    return from == 0 ? source : sb.append(source, from, source.length()).toString();
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

  /**
   * An open element: its lower-case name and the {@code th:inline} mode (stripped, lower case) that
   * governs its body, its own or the nearest ancestor's, or {@code null}.
   */
  private static final class Open {
    private final String name;
    private final String inline;

    Open(String name, String inline) {
      this.name = name;
      this.inline = inline;
    }
  }

  private static final class Handler extends AbstractMarkupHandler {
    private final String file;
    private final char[] doc;
    private final List<Edit> edits = new ArrayList<>();
    private final List<Report.Item> items = new ArrayList<>();
    private final Set<String> roots = new LinkedHashSet<>();
    private final Set<String> locals = new LinkedHashSet<>();
    /** Names declared in a {@code t:params} already in the template. */
    private final Set<String> declared = new LinkedHashSet<>();
    /** Reads of ambient scopes ({@code session.user}), each with its line; see {@link #addRoots}. */
    private final List<Map.Entry<Integer, String>> ambientReads = new ArrayList<>();
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
    /** True if that script is inlined as JavaScript ({@code th:inline="javascript"} on it or an ancestor). */
    private boolean javaScriptInline;
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
      String mode = inlineMode != null ? inlineMode : open.isEmpty() ? null : open.peek().inline;
      boolean none = ConversionRules.INLINE_NONE.equals(mode);
      open.push(new Open(openName, mode));
      inScript = !none && ConversionRules.SCRIPT.equals(openName) && scriptType != null
          && ConversionRules.SCRIPT_TYPES.contains(Compat.strip(scriptType).toLowerCase(Locale.ROOT));
      javaScriptInline = ConversionRules.INLINE_JAVASCRIPT.equals(mode);
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
      return !open.isEmpty() && ConversionRules.INLINE_NONE.equals(open.peek().inline);
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
        // A browser reads the two attributes the same with or without the space (#331).
        edits.add(new Edit(nameOffset, 0, " "));
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
      String local;
      if (thymeleaf) {
        local = lower.substring(ConversionRules.THYMELEAF_PREFIX.length());
      } else if (lower.startsWith(ConversionRules.TESSERA_PREFIX)) {
        local = lower.substring(ConversionRules.TESSERA_PREFIX.length());
      } else {
        return; // plain HTML attribute
      }
      if (thymeleaf) {
        edits.add(new Edit(nameOffset, ConversionRules.THYMELEAF_PREFIX.length(),
            ConversionRules.TESSERA_PREFIX));
      }
      if (inRoot && (ConversionRules.TESSERA_PREFIX + local).equals(ConversionRules.PARAMS)) {
        rootHasParams = true;
      }
      if (value != null && (ConversionRules.TESSERA_PREFIX + local).equals(ConversionRules.PARAMS)) {
        for (String param : Expressions.splitTopLevel(value, true)) {
          String[] tokens = Compat.strip(param).split("\\s+");
          declared.add(tokens[tokens.length - 1]);
        }
      }
      if (value == null || ConversionRules.LITERAL_VALUES.contains(local)) {
        return;
      }
      String converted = local.equals(ConversionRules.FRAGMENT)
          ? fragmentSignature(value, nameLine)
          : expressionValue(local, value, thymeleaf, name, nameLine);
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
      String value = Expressions.rewriteLinks(thymeleaf ? Expressions.rewriteBlockSelectors(original) : original);
      List<String> problems = Expressions.unsupported(value);
      for (String problem : problems) {
        report(line, problem);
      }
      boolean convertible = problems.isEmpty();
      if (local.equals(ConversionRules.EACH)) {
        return eachValue(original, value, convertible, line);
      }
      if (!convertible) {
        return original; // left in place for the compiler to reject; roots would be guesses
      }
      if (ConversionRules.ASSIGNMENT_VALUES.contains(local)) {
        return assignmentsValue(local, value, thymeleaf, line);
      }
      boolean selection = ConversionRules.SELECTION_VALUES.contains(local);
      boolean fragmentReference = ConversionRules.FRAGMENT_REFERENCES.contains(local);
      if (thymeleaf && !selection) {
        value = Expressions.quoteTokens(value, fragmentReference);
      }
      String rewritten = Expressions.rewrite(value);
      if (!selection) {
        rewritten = selections(line, rewritten);
        addRoots(line, rewritten, fragmentReference);
      }
      nullablePathVariables(line, rewritten);
      if (thymeleaf && ConversionRules.EMPTY_FOR_NULL.contains(local) && Expressions.mayBeNull(rewritten)) {
        report(line, String.format(ConversionRules.NULL_ATTRIBUTE_MESSAGE, name, codeSpan(Compat.strip(rewritten)),
            name.substring(name.indexOf(':') + 1)));
      }
      return rewritten;
    }

    /**
     * {@code th:each="x, st : expr"}: the loop variables become locals even when the expression
     * is not convertible, so they are never mistaken for parameters elsewhere in the template.
     */
    private String eachValue(String original, String value, boolean convertible, int line) {
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
      String rewritten = selections(line, Expressions.rewrite(value.substring(colon + 1)));
      addRoots(line, rewritten, false);
      return value.substring(0, colon + 1) + rewritten;
    }

    /** {@code name=expr, ...}: each expression is rewritten, and {@code with} names become locals. */
    private String assignmentsValue(String local, String value, boolean thymeleaf, int line) {
      List<String> parts = new ArrayList<>();
      for (String part : Expressions.splitTopLevel(value, false)) {
        int eq = Expressions.indexOfTopLevel(part, '=');
        if (eq < 0) {
          parts.add(part);
          continue;
        }
        String head = part.substring(0, eq + 1);
        if (local.equals(ConversionRules.WITH)) {
          locals.add(Compat.strip(part.substring(0, eq)));
        }
        String expr = part.substring(eq + 1);
        String rewritten = selections(line,
            Expressions.rewrite(thymeleaf ? Expressions.quoteTokens(expr, false) : expr));
        addRoots(line, rewritten, false);
        nullablePathVariables(line, rewritten);
        parts.add(head + rewritten);
      }
      return String.join(",", parts);
    }

    /**
     * Adds the roots of {@code expr} on {@code line}, and records its reads of ambient scopes
     * ({@code session.user}, {@code param.q}): {@link #finish} reports those whose root the template
     * does not declare itself.
     */
    private void addRoots(int line, String expr, boolean bareFragmentReference) {
      roots.addAll(Expressions.roots(expr, bareFragmentReference));
      for (String read : Expressions.ambientReads(expr, bareFragmentReference)) {
        ambientReads.add(new AbstractMap.SimpleImmutableEntry<>(line, read));
      }
    }

    /** {@link Expressions#splitSelections}, reporting the compound selections it leaves in place. */
    private String selections(int line, String expr) {
      for (String selection : Expressions.compoundSelections(expr)) {
        report(line, String.format(ConversionRules.COMPOUND_SELECTION_MESSAGE, codeSpan(selection)));
      }
      return Expressions.splitSelections(expr);
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
     * quotes, so a Thymeleaf {@code "[[${x}]]"} would close the string.
     * A {@code "..."} or {@code '...'} literal that is exactly one inline loses its quotes. Any other
     * inline stays, except in a natural-template comment ({@link #naturalComment}), and the compiler
     * reports it where it rejects it: in a string, a template literal (even {@code `[[...]]`: the
     * backticks may follow a tag, {@code String.raw`...`}, which dropping them would break) or a
     * comment (#595), or anywhere but the whole value of a declaration at the top of a JavaScript
     * script (#540). The scan tracks strings, template
     * literal {@code ${...}} and comments, not regular expression literals: an
     * unterminated {@code '}/{@code "} string ends at {@code \n} or {@code \r}, not at U+2028/U+2029
     * (ES2019), and {@code \} followed by CRLF is a line continuation. The compiler is
     * the safety net, so a misread only misses or adds a rewrite, and dropping the quotes can't make
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
            boolean whole = quoteAt == i - 1 && after < text.length() && text.charAt(after) == quote;
            if (whole && quote != '`') {
              edits.add(new Edit(offset + quoteAt, 1, ""));
              edits.add(new Edit(offset + after, 1, ""));
              report(lineAt(text, line, i), String.format(ConversionRules.SCRIPT_INLINE_UNQUOTED_MESSAGE,
                  codeSpan(quote + text.substring(i, after) + quote)));
              state = CODE;
              i = after; // the closing quote
              continue;
            }
            // The compiler reports any other inline in a string.
            quoteAt = -1;
          }
          // The compiler reports one in a comment too; a natural-template comment in a
          // th:inline="javascript" script was handled by naturalComment.
          i = after - 1;
          continue;
        }
        boolean lineEnd = c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029';
        switch (state) {
          case CODE:
            int natural = javaScriptInline && text.startsWith("/*", i) ? naturalComment(text, offset, line, i) : -1;
            if (natural >= 0) {
              i = natural - 1;
            } else if (c == '"' || c == '\'' || c == '`') {
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

    /** A default that a natural-template comment may drop: a string, number, boolean or {@code null}. */
    private static final Pattern NATURAL_DEFAULT = Pattern.compile(
        "\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?|true|false|null");

    /**
     * Thymeleaf's JavaScript natural template (#315): in a {@code th:inline="javascript"} script, a
     * {@code /*...*}{@code /} comment whose whole content is {@code [[...]]} or {@code [(...)]} is
     * replaced by its value, and the text after it is dropped up to the next {@code ;}, {@code ,},
     * {@code )}, {@code }}, {@code ]}, {@code //} or line feed outside quotes, brackets and braces
     * ({@code CommentProcessorTextHandler.computeFilterOffset} in thymeleaf 3.1), or up to the next
     * comment. For the comment at {@code i} of {@code text} (which starts at {@code offset} of the
     * document), rewrites the comment and the dropped text to the inline if that text is empty or one
     * literal (a string, number, boolean, {@code null}, array or object) and returns the offset after
     * it; reports it for a manual rewrite if it is anything else and returns the same offset; returns
     * -1 if the comment is not a natural-template comment.
     */
    private int naturalComment(String text, int offset, int line, int i) {
      int close = text.indexOf("*/", i + 2);
      String content = close < 0 ? "" : text.substring(i + 2, close);
      boolean escaped = content.startsWith("[[") && content.endsWith("]]");
      if (content.length() < 4 || !escaped && !(content.startsWith("[(") && content.endsWith(")]"))) {
        return -1;
      }
      int end = close + 2;
      int dropEnd = naturalDefaultEnd(text, end);
      String dropped = text.substring(end, dropEnd);
      String comment = text.substring(i, end);
      int at = lineAt(text, line, i);
      boolean one = Expressions.inlineEnd(text, i + 4, escaped ? "]]" : ")]") == close - 2;
      if (!one || !naturalDefault(Compat.strip(dropped))) {
        report(at, String.format(ConversionRules.SCRIPT_NATURAL_COMMENT_MANUAL_MESSAGE,
            codeSpan(comment), codeSpan(Compat.strip(dropped))));
        return dropEnd; // Thymeleaf drops that text, so nothing in it is rewritten
      }
      edits.add(new Edit(offset + i, dropEnd - i, content));
      String def = Compat.strip(dropped).isEmpty() ? "no default" : "the default " + codeSpan(Compat.strip(dropped));
      report(at, String.format(ConversionRules.SCRIPT_NATURAL_COMMENT_MESSAGE, codeSpan(comment + dropped.replaceAll("\\s+$", "")),
          codeSpan(content), def) + (escaped ? "" : ConversionRules.SCRIPT_NATURAL_UNESCAPED_MESSAGE));
      return dropEnd;
    }

    /**
     * The end of the text that Thymeleaf drops after a natural-template comment that ends at
     * {@code from}: {@code computeFilterOffset}, which stops at the next comment too because the
     * dropped text ends at the next structure of its text parser.
     */
    private static int naturalDefaultEnd(String text, int from) {
      char quote = 0;
      int depth = 0;
      for (int i = from; i < text.length(); i++) {
        char c = text.charAt(i);
        if (quote != 0) {
          if (c == quote && text.charAt(i - 1) != '\\') {
            quote = 0;
          }
        } else if (c == '\'' || c == '"') {
          quote = c;
        } else if (text.startsWith("/*", i)) {
          return i;
        } else if (c == '{' || c == '[') {
          depth++;
        } else if (depth > 0 && (c == '}' || c == ']')) {
          depth--;
        } else if (depth == 0 && (c == '\n' || c == ';' || c == ',' || c == ')' || c == '}' || c == ']'
            || text.startsWith("//", i))) {
          return i;
        }
      }
      return text.length();
    }

    /**
     * Whether {@code s} (stripped) may be dropped by the rewrite: empty, a {@link #NATURAL_DEFAULT},
     * or an array or object. It must hold no backslash, backtick, {@code [[}, {@code [(} or
     * {@code [#}, and no {@code /} outside quotes (which Thymeleaf's text parser reads as an escape,
     * template literal, inline, element, comment or regular expression), so that the rewrite drops
     * exactly what Thymeleaf drops.
     */
    private static boolean naturalDefault(String s) {
      if (s.indexOf('\\') >= 0 || s.indexOf('`') >= 0 || s.contains("[[") || s.contains("[(") || s.contains("[#")) {
        return false;
      }
      if (s.isEmpty() || NATURAL_DEFAULT.matcher(s).matches()) {
        return true;
      }
      char first = s.charAt(0);
      if (first != '[' && first != '{') {
        return false;
      }
      // The bracket that opens s closes at its last character.
      char quote = 0;
      int depth = 0;
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (quote != 0) {
          quote = c == quote ? 0 : quote;
        } else if (c == '\'' || c == '"') {
          quote = c;
        } else if (c == '/') {
          return false;
        } else if (c == '[' || c == '{') {
          depth++;
        } else if ((c == ']' || c == '}') && --depth == 0) {
          return i == s.length() - 1;
        }
      }
      return false;
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
          addRoots(exprLine, expr, false);
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
      // An ambient read moves into the model under its own name: no `Object session` stub for it.
      Set<String> reported = new HashSet<>();
      for (Map.Entry<Integer, String> read : ambientReads) {
        String root = read.getValue().split("[.\\[]", 2)[0];
        if (locals.contains(root) || declared.contains(root)) {
          continue; // the template's own variable, not the scope
        }
        params.remove(root);
        if (reported.add(read.getKey() + " " + read.getValue())) {
          report(read.getKey(), ConversionRules.ambientReadMessage(read.getValue()));
        }
      }
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
