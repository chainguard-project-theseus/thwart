package dev.tessera.compiler.verify;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.verify.engine.InsertionMode;
import dev.tessera.compiler.verify.engine.OpenElement;
import dev.tessera.compiler.verify.engine.Parser;
import dev.tessera.compiler.verify.engine.ParserState;
import dev.tessera.compiler.verify.engine.TokenizerState;
import java.util.EnumSet;
import java.util.Set;

/**
 * The verifier's rule table (docs/security-architecture.md §11.2, memo §3.1-§3.2): in which parser
 * states an escaper's output is inert, and a value is handled the same way whatever it is. Every
 * state not accepted here is rejected, so a state the table doesn't name fails closed.
 */
final class Rules {
  private Rules() {}

  /** Modes whose character tokens are all appended as they are. */
  private static final Set<InsertionMode> TEXT_MODES = EnumSet.of(
      InsertionMode.IN_BODY, InsertionMode.IN_CELL, InsertionMode.IN_CAPTION, InsertionMode.IN_TEMPLATE);

  /** Modes where text is table text. */
  private static final Set<InsertionMode> TABLE_MODES = EnumSet.of(
      InsertionMode.IN_TABLE, InsertionMode.IN_TABLE_BODY, InsertionMode.IN_ROW, InsertionMode.IN_COLUMN_GROUP);

  /** HTML elements whose raw text a browser never runs or renders as markup. */
  private static final Set<String> INERT_RAWTEXT = Backports.setOf("iframe", "xmp", "noembed", "noframes");

  /** MathML text integration points: their character tokens go through the insertion mode. */
  private static final Set<String> MATHML_TEXT_POINTS = Backports.setOf("mi", "mo", "mn", "ms", "mtext");

  /**
   * The problem with {@code hole} in state {@code s}, or null if it is accepted.
   *
   * <p>The state names the one escaper a hole there may have (#378): the script data of an HTML
   * {@code <script>}, {@code json}; a double-quoted attribute value, an attribute escaper for that
   * attribute (which one is policy, {@code Contexts.attribute}); element text, {@link #textEscaper}.
   * In never-run raw text any escaper is inert. A hole with another escaper is rejected, so a hole
   * whose paths reach states that name different escapers is rejected on one of them (the branch
   * rule). A {@code text} hole is element text whose escaper the verifier takes from the state
   * ({@code ContextVerifier#textEscaper}), so only where it is text at all is checked here.
   */
  static Problem hole(Skeleton.Hole hole, ParserState s) {
    if (s.tokenizer() == TokenizerState.RAWTEXT && neverRun(s)) {
      // No escaper writes '<', so none can end this raw text, which a browser neither renders as
      // markup nor runs (noscript with scripting on, iframe, xmp, noembed, noframes).
      return null;
    }
    switch (hole.escaper()) {
      case TEXT:
        return text(s);
      case RCDATA:
        // Without the comments that break {{ in text: inert only where the text isn't DOM text a
        // client framework reads (memo §3.1), RCDATA and never-run raw text.
        Problem p = text(s);
        return p == null && s.tokenizer() == TokenizerState.DATA ? Problem.RCDATA_NOT_IN_RCDATA : p;
      case JSON:
        // Right after '<' too: JSON never starts with '/' or '!', so its first character returns
        // the tokenizer to script data (the verifier feeds one, see ContextVerifier#hole).
        OpenElement current = s.currentNode();
        return (s.tokenizer() == TokenizerState.SCRIPT_DATA || s.tokenizer() == TokenizerState.SCRIPT_DATA_LESS_THAN_SIGN)
                && current != null && current.isHtml("script")
            ? null
            : Problem.JSON_NOT_IN_SCRIPT;
      default:
        return attribute(hole, s);
    }
  }

  /**
   * The escaper element text takes in {@code s}, where {@link #text} accepts text: {@code rcdata} in
   * RCDATA, without the comments that break {{ (they would show as text there); {@code text}, with
   * them, elsewhere; null in never-run raw text, where any escaper is inert.
   */
  static Skeleton.Escaper textEscaper(ParserState s) {
    if (s.tokenizer() == TokenizerState.RCDATA) {
      return Skeleton.Escaper.RCDATA;
    }
    return s.tokenizer() == TokenizerState.RAWTEXT && neverRun(s) ? null : Skeleton.Escaper.TEXT;
  }

  private static Problem text(ParserState s) {
    OpenElement current = s.currentNode();
    switch (s.tokenizer()) {
      case DATA:
        if (current != null && !OpenElement.HTML.equals(current.namespace())
            && (current.localName().equals("script") || current.localName().equals("style"))) {
          // An SVG <script> runs its text and an SVG <style> applies it: no HTML escaper helps.
          return Problem.TEXT_IN_SCRIPT;
        }
        if (foreignCharacters(current)) {
          // In foreign content every character is appended as it is, whatever the mode.
          return null;
        }
        // Formatting elements to reopen first, or a foster-parented element in a table mode, are
        // reached only by error recovery: parse errors (#406, #410).
        if (!TEXT_MODES.contains(s.mode())) {
          return TABLE_MODES.contains(s.mode()) ? Problem.TEXT_IN_TABLE : Problem.TEXT_OUTSIDE_BODY;
        }
        return null;
      case RCDATA:
        return null;
      case RAWTEXT:
        return neverRun(s) ? null : Problem.TEXT_IN_SCRIPT;
      case SCRIPT_DATA:
      case SCRIPT_DATA_ESCAPED:
      case SCRIPT_DATA_DOUBLE_ESCAPED:
        return Problem.TEXT_IN_SCRIPT;
      default:
        return Problem.TEXT_NOT_IN_TEXT;
    }
  }

  /** In raw text, whether it is the content of an element a browser never renders as markup or runs. */
  private static boolean neverRun(ParserState s) {
    OpenElement current = s.currentNode();
    return current != null && OpenElement.HTML.equals(current.namespace())
        && (INERT_RAWTEXT.contains(current.localName()) || current.localName().equals("noscript") && s.scripting());
  }

  /**
   * Whether character tokens go to the foreign content rules: the current node is SVG or MathML,
   * and not an HTML or MathML text integration point.
   */
  private static boolean foreignCharacters(OpenElement current) {
    if (current == null || OpenElement.HTML.equals(current.namespace()) || current.htmlIntegrationPoint()) {
      return false;
    }
    return !(OpenElement.MATHML.equals(current.namespace()) && MATHML_TEXT_POINTS.contains(current.localName()));
  }

  private static Problem attribute(Skeleton.Hole hole, ParserState s) {
    if (s.tokenizer() != TokenizerState.ATTRIBUTE_VALUE_DOUBLE_QUOTED) {
      return Problem.ATTRIBUTE_NOT_IN_VALUE;
    }
    // Names fold A-Z only, as in a browser: \u017Frc is not src, encod\u0131ng is not encoding.
    String name = s.attributeName() == null ? null : asciiLower(s.attributeName());
    if (name == null || hole.attribute() == null || !name.equals(asciiLower(hole.attribute()))) {
      return Problem.ATTRIBUTE_WRONG_NAME;
    }
    // An <input> in a table mode is a parse error whatever its type (#410).
    OpenElement current = s.currentNode();
    if (name.equals("encoding") && current != null && OpenElement.MATHML.equals(current.namespace())) {
      return Problem.ATTRIBUTE_VALUE_CHANGES_PARSE;
    }
    return null;
  }

  /**
   * {@code s} with only A-Z lowercased, as a browser compares names. The verifier keeps its own copy, so it shares
   * no code with the parser it checks.
   */
  private static String asciiLower(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      sb.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
    }
    return sb.toString();
  }

  // ---- error-free parses and the coarse exclusions (#406, security-architecture.md §11.4) ----

  /**
   * Whether a parse error is on the allow-list, the measurement's (#406): the missing-doctype
   * messages ("... without seeing a doctype first"), which say nothing about the tree (most views
   * are partials, and many pages start with {@code <html>}); and "Duplicate ID", which isn't a
   * parse error in the HTML standard but a conformance check of validator.nu's tree builder, and
   * which it makes only among the elements of one chunk of input. Don't widen it without a corpus
   * measurement.
   */
  static boolean allowedError(String message) {
    return message.contains("without seeing a doctype first") || message.startsWith("Duplicate ID \u201C");
  }

  /** Below SVG or MathML on the stack, these split browsers (#398): exclusion (a). */
  private static final Set<String> TABLE_PARTS_BELOW_FOREIGN =
      Backports.setOf("td", "th", "caption", "tr", "tbody", "thead", "tfoot", "table", "template");

  /** Directly in {@code <template>}, these split browsers (#401): exclusion (b). */
  private static final Set<String> TABLE_PARTS_IN_TEMPLATE =
      Backports.setOf("caption", "colgroup", "col", "tbody", "thead", "tfoot", "tr", "td", "th");

  /** Template modes a table part directly in {@code <template>} switches to: exclusion (b). */
  private static final Set<InsertionMode> TABLE_TEMPLATE_MODES = EnumSet.of(
      InsertionMode.IN_TABLE, InsertionMode.IN_TABLE_BODY, InsertionMode.IN_ROW, InsertionMode.IN_COLUMN_GROUP);

  /** What classic {@code <select>} parsing keeps in a select (#305): exclusion (c). */
  private static final Set<String> SELECT_CONTENT = Backports.setOf("option", "optgroup", "hr");

  /** An excluded stack shape: the rule and the shape in words, for the message. */
  static final class Excluded {
    final Problem problem;
    final String detail;

    Excluded(Problem problem, String detail) {
      this.problem = problem;
      this.detail = detail;
    }
  }

  /**
   * Whether the stack, right after an element was inserted, has a shape where supported browsers
   * are known to build different trees (security-architecture.md §11.4), or null. Every element
   * is checked when it is inserted, so the shapes are found also when they last only inside one
   * static. The checks are coarse on purpose: they reject the whole shape, not only the markup
   * after it that splits browsers.
   *
   * <ul>
   *   <li>(a) {@link Problem#FOREIGN_IN_TABLE}: an {@code svg} or {@code math} element open with a
   *       table part or {@code template} below it (the fragment context counts);
   *   <li>(b) {@link Problem#TABLE_IN_TEMPLATE}: an HTML table part directly in {@code
   *       <template>}, or a table mode in the template-mode stack;
   *   <li>(c) {@link Problem#SELECT_CONTENT}: any element but {@code option}, {@code optgroup} and
   *       {@code hr} inserted into an open {@code select} (the fragment context counts);
   *   <li>{@link Problem#FRAMESET}: any {@code frameset} element. Whether a {@code <frameset>}
   *       start tag applies depends on the content before it; where it doesn't, it is a parse
   *       error.
   * </ul>
   */
  static Excluded exclusion(Parser.Stack s) {
    String ns = s.insertedNamespace();
    String name = s.insertedName();
    boolean html = OpenElement.HTML.equals(ns);
    // The elements below the inserted one, the top first; then the fragment context.
    int below = s.insertedOnStack() ? s.size() - 2 : s.size() - 1;
    if (html && "frameset".equals(name)) {
      return new Excluded(Problem.FRAMESET, "<frameset>");
    }
    if (!html && ("svg".equals(name) || "math".equals(name))) {
      for (int i = below; i >= -1; i--) {
        String part = htmlName(s, i);
        if (part != null && TABLE_PARTS_BELOW_FOREIGN.contains(part)) {
          return new Excluded(Problem.FOREIGN_IN_TABLE, "<" + name + "> inside <" + part + ">");
        }
      }
    }
    if (html && TABLE_PARTS_IN_TEMPLATE.contains(name) && "template".equals(htmlName(s, below))) {
      return new Excluded(Problem.TABLE_IN_TEMPLATE, "<" + name + "> directly in <template>");
    }
    for (int i = 0; i < s.templateModeCount(); i++) {
      if (TABLE_TEMPLATE_MODES.contains(s.templateMode(i))) {
        return new Excluded(Problem.TABLE_IN_TEMPLATE, "a table part directly in <template>");
      }
    }
    if (!(html && SELECT_CONTENT.contains(name))) {
      for (int i = below; i >= -1; i--) {
        if ("select".equals(htmlName(s, i))) {
          return new Excluded(Problem.SELECT_CONTENT, "<" + name + "> in a <select>");
        }
      }
    }
    return null;
  }

  /**
   * The problem with a CDATA section the parser starts while {@code current} (not HTML) is the
   * current node, or null: exclusion (d), {@link Problem#CDATA_IN_INTEGRATION_POINT} (#441). In an
   * HTML integration point ({@code foreignObject}, {@code desc} and {@code title} in SVG, an HTML
   * {@code annotation-xml}) or a MathML text integration point, the engine and Firefox read a CDATA
   * section, as the standard does, but parse5 and Chromium read a comment that ends at the
   * first {@code >}. Coarse on purpose: any CDATA section there, whatever is in it. Elsewhere in
   * SVG and MathML (a {@code <style>}, a {@code <script>}, a {@code <text>}) they agree.
   */
  static Problem cdata(OpenElement current) {
    return foreignCharacters(current) ? null : Problem.CDATA_IN_INTEGRATION_POINT;
  }

  /** The local name of HTML element {@code i} on the stack ({@code -1}: the fragment context), else null. */
  private static String htmlName(Parser.Stack s, int i) {
    if (i < 0) {
      OpenElement c = i == -1 ? s.context() : null;
      return c != null && OpenElement.HTML.equals(c.namespace()) ? c.localName() : null;
    }
    return OpenElement.HTML.equals(s.namespace(i)) ? s.localName(i) : null;
  }
}
