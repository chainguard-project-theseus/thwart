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
    /** True if that script is JSON ({@code application/json}, {@code application/ld+json}). */
    private boolean jsonScript;
    /** The number of the last {@code _tN} variable a value of this file was hoisted into (#541). */
    private int hoisted;
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
      jsonScript = inScript && Compat.strip(scriptType).toLowerCase(Locale.ROOT).endsWith("json");
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
        int first = edits.size();
        quotedInlines(text, offset, line);
        if (!jsonScript) {
          hoist(text, offset, line, edits.subList(first, edits.size()));
        }
      }
      inline(text, line, "[[", "]]");
      inline(text, line, "[(", ")]");
    }

    /**
     * Hoists each {@code [[...]]} of a JavaScript script that is outside the script prelude
     * ({@code TextInlining.checkPrelude}, #540) into a declaration {@code var _tN = [[...]];} at the
     * end of the prelude, and writes {@code _tN} in its place (#541); {@code var}, so that a fragment
     * included twice doesn't redeclare a {@code const}. {@code text} is the script text at
     * {@code offset} of the document, and {@code scriptEdits} the edits {@link #quotedInlines} made
     * to it, which are applied first; if a value is hoisted they are replaced by one edit of the
     * whole text. A {@code '...'} or {@code "..."} string around a value is split, {@code "Hello
     * [[${x}]]"} becoming {@code "Hello " + _tN}, in parentheses unless the tokens on both sides bind
     * looser than {@code +}. A value in a template literal, a regular expression or a comment stays,
     * and the compiler reports it (#595). The lexer is an
     * approximation, as in {@link #quotedInlines}, plus regular expression literals; the compiler is
     * the safety net: every hoisted value is in its prelude grammar, so a misread cannot put a value
     * anywhere else, though it can break the code around a value or change what that code means.
     */
    private void hoist(String text, int offset, int line, List<Edit> scriptEdits) {
      String raw = applied(text, offset, scriptEdits);
      int start = 0;
      for (int end = preludeDeclaration(raw, preludeBlank(raw, 0)); end >= 0;
          end = preludeDeclaration(raw, preludeBlank(raw, start))) {
        start = end;
      }
      int code = preludeBlank(raw, start);
      // On a line of its own the hoisted declarations go on lines of their own above the code.
      int insertAt = code;
      String indent = null;
      String lineBreak = null;
      for (int k = code - 1; k >= start; k--) {
        char ch = raw.charAt(k);
        if (ch == '\n' || ch == '\r') {
          insertAt = k + 1;
          indent = raw.substring(insertAt, code);
          lineBreak = ch == '\n' && k > 0 && raw.charAt(k - 1) == '\r' ? "\r\n" : String.valueOf(ch);
          break;
        }
      }
      StringBuilder out = new StringBuilder(raw.length() + 64).append(raw, 0, insertAt);
      StringBuilder declarations = new StringBuilder();
      int state = CODE;
      char quote = 0;
      int stringStart = -1; // where the current string starts in out
      int pieceStart = -1; // where the opening quote of the current piece of that string is in out
      boolean split = false; // a value was hoisted out of the current string
      boolean afterValue = false; // the current string continues right after a hoisted value
      boolean inClass = false; // in a [...] class of a regular expression
      // For each open brace: true for a block or function body, false for an object literal.
      Deque<Boolean> blocks = new ArrayDeque<>();
      Deque<Integer> templates = new ArrayDeque<>(); // block depth at each ${ of a template literal
      // For each open ( and [: true for the ( of an if, while, for or with, after whose ) a / starts a regular expression.
      Deque<Boolean> openers = new ArrayDeque<>();
      boolean closeStartsRegex = true; // after the last ), ] or }: whether a / there starts a regular expression
      Deque<Integer> arrows = new ArrayDeque<>(); // paren and bracket depth at each => whose body may still run later
      for (int i = insertAt; i < raw.length(); i++) {
        char c = raw.charAt(i);
        int end = holeEnd(raw, i);
        if (end >= 0) {
          String hole = raw.substring(i, end + 2);
          int after = end + 2;
          int source = sourceIndex(i, offset, scriptEdits);
          int at = lineAt(text, line, source);
          boolean value = raw.charAt(i + 1) == '[';
          boolean dropped = false; // in text Thymeleaf drops after a natural-template comment
          for (int[] m : manual) {
            dropped |= source >= m[0] && source < m[1];
          }
          boolean inCode = state == CODE || state == STRING && quote != '`'; // a '...' or "..." string is split
          if (value && !dropped && inCode && templates.isEmpty()) {
            String name = nextName();
            if (indent != null) {
              declarations.append(indent);
            }
            declarations.append("var ").append(name).append(" = ").append(hole).append(';')
                .append(indent != null ? lineBreak : " ");
            String message = String.format(ConversionRules.SCRIPT_HOISTED_MESSAGE, hole, name);
            if (state == CODE) {
              boolean nameBefore = out.length() > 0 && isNameChar(out.charAt(out.length() - 1));
              boolean nameAfter = after < raw.length() && isNameChar(raw.charAt(after));
              out.append(nameBefore ? " " : "").append(name).append(nameAfter ? " " : "");
            } else {
              if (afterValue) {
                out.append(" + ");
              } else if (out.length() == pieceStart + 1) {
                out.setLength(pieceStart); // nothing before the value in this string
                if (raw.startsWith("[[", after)) {
                  out.append(quote).append(quote).append(" + "); // so that two values are joined as strings
                }
              } else {
                out.append(quote).append(" + ");
              }
              out.append(name);
              afterValue = true;
              split = true;
              message += ConversionRules.SCRIPT_HOISTED_STRING_MESSAGE;
            }
            if (blocks.contains(Boolean.TRUE) || !arrows.isEmpty()) {
              message += String.format(ConversionRules.SCRIPT_HOISTED_DEFERRED_MESSAGE, name);
            }
            report(at, message);
          } else {
            out.append(hole); // not hoisted; the compiler reports it where it rejects it
          }
          i = after - 1;
          continue;
        }
        if (afterValue) {
          afterValue = false;
          if (c == quote) {
            state = CODE; // the string ends with the value: no empty piece
            parenthesize(out, stringStart, raw, i + 1);
            continue;
          }
          out.append(" + ").append(quote);
          pieceStart = out.length() - 1;
        }
        out.append(c);
        boolean lineEnd = c == '\n' || c == '\r';
        switch (state) {
          case CODE:
            if (c == '"' || c == '\'' || c == '`') {
              state = STRING;
              quote = c;
              stringStart = out.length() - 1;
              pieceStart = stringStart;
              split = false;
            } else if (raw.startsWith("//", i) || raw.startsWith("/*", i)) {
              state = raw.charAt(i + 1) == '/' ? LINE_COMMENT : BLOCK_COMMENT;
              out.append(raw.charAt(++i));
            } else if (c == '/' && regexAllowed(out, out.length() - 1, closeStartsRegex)) {
              state = REGEX;
              inClass = false;
            } else if (raw.startsWith("=>", i)) {
              arrows.push(openers.size());
              out.append(raw.charAt(++i));
            } else if (c == '(' || c == '[') {
              openers.push(c == '(' && CONTROL.contains(wordBefore(out, out.length() - 1)));
            } else if (c == ')' || c == ']') {
              closeStartsRegex = !openers.isEmpty() && openers.pop();
              while (!arrows.isEmpty() && arrows.peek() > openers.size()) {
                arrows.pop();
              }
            } else if (c == ';' || c == ',') {
              while (!arrows.isEmpty() && arrows.peek() == openers.size()) {
                arrows.pop(); // the end of an arrow function's expression body
              }
            } else if (c == '{') {
              blocks.push(!objectLiteral(out, out.length() - 1));
            } else if (c == '}' && !templates.isEmpty() && templates.peek() == blocks.size()) {
              templates.pop();
              state = STRING;
              quote = '`';
            } else if (c == '}' && !blocks.isEmpty()) {
              closeStartsRegex = blocks.pop(); // after a block, not after an object literal
            }
            break;
          case STRING:
            if (c == '\\' && i + 1 < raw.length()) {
              out.append(raw.charAt(++i));
              if (raw.startsWith("\r\n", i)) {
                out.append(raw.charAt(++i)); // "\" + CRLF is one line continuation
              }
            } else if (c == quote) {
              state = CODE;
              if (split) {
                parenthesize(out, stringStart, raw, i + 1);
              }
            } else if (lineEnd && quote != '`') {
              state = CODE;
            } else if (quote == '`' && raw.startsWith("${", i)) {
              templates.push(blocks.size());
              state = CODE;
              out.append(raw.charAt(++i));
            }
            break;
          case REGEX:
            if (c == '\\' && i + 1 < raw.length()) {
              out.append(raw.charAt(++i));
            } else if (c == '[') {
              inClass = true;
            } else if (c == ']') {
              inClass = false;
            } else if (c == '/' && !inClass || lineEnd) {
              state = CODE;
            }
            break;
          case LINE_COMMENT:
            state = lineEnd ? CODE : state;
            break;
          default:
            if (raw.startsWith("*/", i)) {
              state = CODE;
              out.append(raw.charAt(++i));
            }
            break;
        }
      }
      if (declarations.length() == 0) {
        return;
      }
      for (String directive : new String[] {"\"use strict\"", "'use strict'"}) {
        if (raw.startsWith(directive, code)) {
          report(lineAt(text, line, sourceIndex(code, offset, scriptEdits)),
              String.format(ConversionRules.SCRIPT_DIRECTIVE_MESSAGE, codeSpan(directive)));
        }
      }
      out.insert(insertAt, declarations);
      scriptEdits.clear();
      edits.add(new Edit(offset, text.length(), out.toString()));
    }

    /**
     * Puts the split string that starts at {@code stringStart} of {@code out} and ends there in
     * parentheses unless the token before it and the one at {@code next} of {@code raw} bind looser
     * than {@code +}, so that {@code "a [[${x}]]".length} keeps its meaning. A {@code +} before it
     * binds looser only if it is binary and the split string still starts with a string:
     * {@code n + "[[${x}]]!"} is {@code n + (_t1 + "!")}, since {@code n + _t1} would add two numbers,
     * and {@code +"0.[[${x}]]"} is {@code +("0." + _t1)}.
     */
    private static void parenthesize(StringBuilder out, int stringStart, String raw, int next) {
      int p = stringStart - 1;
      while (p >= 0 && " \t\n\r".indexOf(out.charAt(p)) >= 0) {
        p--;
      }
      int q = p - 1;
      while (q >= 0 && " \t\n\r".indexOf(out.charAt(q)) >= 0) {
        q--;
      }
      boolean binary = q >= 0 && (isNameChar(out.charAt(q)) || ")]\"'`".indexOf(out.charAt(q)) >= 0);
      String loose = binary && "\"'".indexOf(out.charAt(stringStart)) >= 0 ? "=(,[:?{;+&|<>" : "=(,[:?{;&|<>";
      boolean before = p < 0 || loose.indexOf(out.charAt(p)) >= 0
          || p >= 5 && out.substring(p - 5, p + 1).equals("return") && (p == 5 || !isNameChar(out.charAt(p - 6)));
      while (next < raw.length() && (raw.charAt(next) == ' ' || raw.charAt(next) == '\t')) {
        next++;
      }
      boolean after = next >= raw.length() || ";,)]}:?+=!&|<>\r\n".indexOf(raw.charAt(next)) >= 0;
      if (!before || !after) {
        out.insert(stringStart, '(').append(')');
      }
    }

    /**
     * Whether the opening brace at {@code brace} of {@code out} starts an object literal: after
     * {@code =}, {@code (}, {@code ,}, {@code :}, {@code [}, {@code ?} or {@code return}. Anything
     * else, such as {@code )}, {@code =>} or {@code else}, opens a block or function body.
     */
    private static boolean objectLiteral(StringBuilder out, int brace) {
      int p = brace - 1;
      while (p >= 0 && " \t\n\r".indexOf(out.charAt(p)) >= 0) {
        p--;
      }
      return p >= 0 && ("=(,:[?".indexOf(out.charAt(p)) >= 0
          || p >= 5 && out.substring(p - 5, p + 1).equals("return") && (p == 5 || !isNameChar(out.charAt(p - 6))));
    }

    /** Words after which a {@code /} starts a regular expression. */
    private static final Set<String> REGEX_AFTER = Compat.setOf("return", "typeof", "instanceof", "in", "of", "new",
        "delete", "void", "throw", "case", "do", "else", "yield", "await");

    /** The words before a {@code (} after whose {@code )} a {@code /} starts a regular expression. */
    private static final Set<String> CONTROL = Compat.setOf("if", "while", "for", "with");

    /**
     * Whether a {@code /} at {@code slash} of {@code out} starts a regular expression: not after a
     * value. After a {@code )}, {@code ]} or {@code }} that is {@code closeStartsRegex}: after the
     * condition of an {@code if}, {@code while}, {@code for} or {@code with}, or after a block.
     */
    private static boolean regexAllowed(StringBuilder out, int slash, boolean closeStartsRegex) {
      int p = slash - 1;
      while (p >= 0 && " \t\n\r".indexOf(out.charAt(p)) >= 0) {
        p--;
      }
      if (p < 0) {
        return true;
      }
      char c = out.charAt(p);
      if (isNameChar(c)) {
        return REGEX_AFTER.contains(wordBefore(out, slash));
      }
      if (c == ')' || c == ']' || c == '}') {
        return closeStartsRegex;
      }
      return "\"'`".indexOf(c) < 0;
    }

    /** The name before offset {@code at} of {@code out}, after any whitespace, or "". */
    private static String wordBefore(StringBuilder out, int at) {
      int p = at - 1;
      while (p >= 0 && " \t\n\r".indexOf(out.charAt(p)) >= 0) {
        p--;
      }
      int w = p + 1;
      while (w > 0 && isNameChar(out.charAt(w - 1))) {
        w--;
      }
      return out.substring(w, p + 1);
    }

    private static boolean isNameChar(char c) {
      return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '$';
    }

    /** {@code text}, which starts at {@code offset} of the document, with {@code scriptEdits} applied. */
    private static String applied(String text, int offset, List<Edit> scriptEdits) {
      StringBuilder converted = new StringBuilder(text.length());
      int pos = 0;
      for (Edit e : scriptEdits) {
        converted.append(text, pos, e.offset() - offset).append(e.replacement());
        pos = e.offset() - offset + e.length();
      }
      return converted.append(text, pos, text.length()).toString();
    }

    /** The offset in {@code text} of offset {@code at} of {@link #applied}. */
    private static int sourceIndex(int at, int offset, List<Edit> scriptEdits) {
      int source = at;
      int shift = 0;
      for (Edit e : scriptEdits) {
        if (e.offset() - offset + shift >= at) {
          break;
        }
        shift += e.replacement().length() - e.length();
        source = at - shift;
      }
      return source;
    }

    /** The whole document, for {@link #nextName}. */
    private String document;

    /** The next {@code _tN} that the document doesn't use as a name. */
    private String nextName() {
      if (document == null) {
        document = new String(doc);
      }
      String name;
      do {
        name = "_t" + ++hoisted;
      } while (Pattern.compile("(?<![\\w$])" + name + "(?![\\w$])").matcher(document).find());
      return name;
    }

    /**
     * The offset after the ASCII spaces, tabs, line breaks and {@code //} comments at {@code i}. A
     * value in a comment stays there and was reported, so unlike the compiler this skips it.
     */
    private static int preludeBlank(String raw, int i) {
      while (i < raw.length()) {
        char c = raw.charAt(i);
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
          i++;
        } else if (raw.startsWith("//", i)) {
          while (i < raw.length() && raw.charAt(i) != '\n' && raw.charAt(i) != '\r') {
            i++;
          }
        } else {
          break;
        }
      }
      return i;
    }

    /** Names a prelude declaration may not use; {@code TextInlining.RESERVED} in the compiler. */
    private static final Set<String> PRELUDE_RESERVED = Compat.setOf(
        "location",
        "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do",
        "else", "enum", "export", "extends", "false", "finally", "for", "function", "if", "implements", "import",
        "in", "instanceof", "interface", "let", "new", "null", "package", "private", "protected", "public",
        "return", "static", "super", "switch", "this", "throw", "true", "try", "typeof", "var", "void", "while",
        "with", "yield");

    /**
     * The offset after the prelude declaration {@code const|let|var NAME = [[...]];} at {@code i},
     * or -1: the compiler's {@code TextInlining.declaration}.
     */
    private static int preludeDeclaration(String raw, int i) {
      int j = -1;
      for (String keyword : new String[] {"const", "let", "var"}) {
        if (raw.startsWith(keyword, i)) {
          j = i + keyword.length();
        }
      }
      if (j < 0) {
        return -1;
      }
      int name = spaces(raw, j);
      if (name == j) {
        return -1;
      }
      j = name;
      while (j < raw.length() && isNameChar(raw.charAt(j)) && (j > name || raw.charAt(j) < '0' || raw.charAt(j) > '9')) {
        j++;
      }
      if (j == name || PRELUDE_RESERVED.contains(raw.substring(name, j))) {
        return -1;
      }
      j = spaces(raw, j);
      if (!raw.startsWith("=", j)) {
        return -1;
      }
      j = spaces(raw, j + 1);
      int end = raw.startsWith("[[", j) ? holeEnd(raw, j) : -1;
      if (end < 0) {
        return -1;
      }
      j = spaces(raw, end + 2);
      return raw.startsWith(";", j) ? j + 1 : -1;
    }

    /** The offset after the ASCII spaces and tabs at {@code i}. */
    private static int spaces(String raw, int i) {
      while (i < raw.length() && (raw.charAt(i) == ' ' || raw.charAt(i) == '\t')) {
        i++;
      }
      return i;
    }

    /** The offset of the {@code ]]} or {@code )]} of a {@code [[...]]} or {@code [(...)]} at {@code i}, or -1. */
    private static int holeEnd(String raw, int i) {
      boolean escaped = raw.startsWith("[[", i);
      return escaped || raw.startsWith("[(", i) ? Expressions.inlineEnd(raw, i + 2, escaped ? "]]" : ")]") : -1;
    }

    private static final int CODE = 0;
    private static final int STRING = 1;
    private static final int LINE_COMMENT = 2;
    private static final int BLOCK_COMMENT = 3;
    private static final int REGEX = 4;

    /**
     * The natural-template comments of the script that {@link #naturalComment} reported but did not
     * rewrite, each with the text Thymeleaf drops after it: {@code {start, end}} in the script text.
     */
    private final List<int[]> manual = new ArrayList<>();

    /**
     * Finds {@code [[...]]} inside JavaScript string literals of script text that starts at
     * {@code offset} of the document. In Tessera {@code [[...]]} writes a JSON value with its own
     * quotes, so a Thymeleaf {@code "[[${x}]]"} would close the string.
     * A {@code "..."} or {@code '...'} literal that is exactly one inline loses its quotes; any other
     * inline in one is left to {@link #hoist} in a JavaScript script. An inline in a JSON string, a
     * template literal (even {@code `[[...]]`: the backticks may follow a tag, {@code String.raw`...`},
     * which dropping them would break) or a comment stays, except a natural-template comment
     * ({@link #naturalComment}), and the compiler reports it (#595). The scan tracks strings, template
     * literal {@code ${...}} and comments, not regular expression literals: an
     * unterminated {@code '}/{@code "} string ends at {@code \n} or {@code \r}, not at U+2028/U+2029
     * (ES2019), and {@code \} followed by CRLF is a line continuation. The compiler is
     * the safety net, so a misread only misses or adds a rewrite, and dropping the quotes can't make
     * it unsafe.
     */
    private void quotedInlines(String text, int offset, int line) {
      manual.clear();
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
            // In a JavaScript script, hoist splits a '...' or "..." string around it; the compiler
            // reports one in a template literal or a JSON string.
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
        manual.add(new int[] {i, dropEnd});
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
