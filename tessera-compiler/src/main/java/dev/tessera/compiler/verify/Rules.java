package dev.tessera.compiler.verify;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.verify.engine.Engines;
import dev.tessera.compiler.verify.engine.InsertionMode;
import dev.tessera.compiler.verify.engine.OpenElement;
import dev.tessera.compiler.verify.engine.ParserState;
import dev.tessera.compiler.verify.engine.TokenizerState;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The verifier's rule table (docs/security-architecture.md §11.2, memo §3.1-§3.2): in which parser
 * states an escaper's output is inert, and a value is handled the same way whatever it is. Every
 * state not accepted here is rejected, so a state the table doesn't name fails closed.
 */
final class Rules {
  private Rules() {}

  /**
   * What the verifier writes for an attribute hole: a private-use character, which the double
   * quoted attribute value state takes as is. It marks the formatting elements that carry a
   * computed attribute ({@link #noahsArk}); nothing else reads attribute values (the two values
   * that change parsing are rejected, {@link Problem#ATTRIBUTE_VALUE_CHANGES_PARSE}).
   */
  static final String MARK = "";

  /**
   * Modes whose character tokens are all handled alike: reconstruct the active formatting
   * elements, then append (in select: append). Text in any other mode is held back, foster-parented,
   * ignored, or closes elements, depending on whether it is whitespace.
   */
  private static final Set<InsertionMode> TEXT_MODES = EnumSet.of(
      InsertionMode.IN_BODY, InsertionMode.IN_CELL, InsertionMode.IN_CAPTION, InsertionMode.IN_SELECT,
      InsertionMode.IN_SELECT_IN_TABLE, InsertionMode.IN_TEMPLATE);

  /** Modes where text is table text. */
  private static final Set<InsertionMode> TABLE_MODES = EnumSet.of(
      InsertionMode.IN_TABLE, InsertionMode.IN_TABLE_BODY, InsertionMode.IN_ROW, InsertionMode.IN_COLUMN_GROUP);

  /** Modes whose text is table text when the current node is a table part. */
  private static final Set<InsertionMode> FOSTER_MODES = EnumSet.of(
      InsertionMode.IN_TABLE, InsertionMode.IN_TABLE_BODY, InsertionMode.IN_ROW);

  /** The table parts whose text a browser holds back and foster-parents. */
  private static final Set<String> FOSTER_PARENTS = Backports.setOf("table", "tbody", "thead", "tfoot", "tr", "template");

  /**
   * Modes where an {@code <input>} start tag is handled by the "in table" rules, which keep an
   * {@code input type=hidden} in the table and foster-parent any other.
   */
  private static final Set<InsertionMode> INPUT_TYPE_MODES = EnumSet.of(
      InsertionMode.IN_TABLE, InsertionMode.IN_TABLE_BODY, InsertionMode.IN_ROW, InsertionMode.IN_COLUMN_GROUP,
      InsertionMode.IN_SELECT_IN_TABLE);

  /** HTML elements whose raw text a browser never runs or renders as markup. */
  private static final Set<String> INERT_RAWTEXT = Backports.setOf("iframe", "xmp", "noembed", "noframes");

  /** MathML text integration points: their character tokens go through the insertion mode. */
  private static final Set<String> MATHML_TEXT_POINTS = Backports.setOf("mi", "mo", "mn", "ms", "mtext");

  /** The problem with {@code hole} in state {@code s}, or null if it is accepted. */
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
        if (FOSTER_MODES.contains(s.mode()) && current != null && OpenElement.HTML.equals(current.namespace())
            && !FOSTER_PARENTS.contains(current.localName()) && !s.pendingTableText()) {
          // In a table mode, text goes to an element that isn't a table part (a foster-parented
          // <h3>, say) as in body: reconstruct, then append. Only text in a table part is table text.
          return s.reconstructionPending() ? Problem.TEXT_RECONSTRUCTS : null;
        }
        if (!TEXT_MODES.contains(s.mode())) {
          return TABLE_MODES.contains(s.mode()) ? Problem.TEXT_IN_TABLE : Problem.TEXT_OUTSIDE_BODY;
        }
        return s.reconstructionPending() ? Problem.TEXT_RECONSTRUCTS : null;
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
    String name = s.attributeName();
    if (name == null || hole.attribute() == null || !name.equalsIgnoreCase(hole.attribute())) {
      return Problem.ATTRIBUTE_WRONG_NAME;
    }
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.equals("type") && INPUT_TYPE_MODES.contains(s.mode())) {
      return Problem.ATTRIBUTE_VALUE_CHANGES_PARSE;
    }
    OpenElement current = s.currentNode();
    if (lower.equals("encoding") && current != null && OpenElement.MATHML.equals(current.namespace())) {
      return Problem.ATTRIBUTE_VALUE_CHANGES_PARSE;
    }
    return null;
  }

  /** Modes that the reset of the insertion mode can pick and that stay as it picked them until the stack changes. */
  private static final Set<InsertionMode> RESET_MODES = EnumSet.of(
      InsertionMode.IN_BODY, InsertionMode.IN_TABLE, InsertionMode.IN_CAPTION, InsertionMode.IN_COLUMN_GROUP,
      InsertionMode.IN_TABLE_BODY, InsertionMode.IN_ROW, InsertionMode.IN_CELL, InsertionMode.IN_SELECT,
      InsertionMode.IN_SELECT_IN_TABLE, InsertionMode.IN_TEMPLATE);

  /**
   * Whether the mode of {@code s} isn't the one the HTML standard's "reset the insertion mode
   * appropriately" (§13.2.4.1) picks from its stack ({@link #standardReset}), for the modes in
   * which the standard keeps the two equal. Both validator.nu engines break this only after
   * their own reset ({@code TreeBuilder.resetTheInsertionMode}) stopped at an SVG or MathML element
   * and picked "in body", where the standard skips foreign elements and walks on down to a
   * {@code td}, {@code th}, {@code caption}, table part or {@code template} ({@link
   * #resetDivergence}): from there on the verifier's parsers and a browser that follows the
   * standard (Chrome, Safari) parse differently (#398). In text mode, the mode it returns to counts.
   */
  static boolean modeLeftTheStandard(ParserState s) {
    InsertionMode mode = s.mode() == InsertionMode.TEXT ? s.originalMode() : s.mode();
    return mode != null && RESET_MODES.contains(mode) && !s.stack().isEmpty() && mode != standardReset(s);
  }

  /**
   * Where a reset of the insertion mode from the stack of {@code s} would make validator.nu and the
   * HTML standard disagree, in words ({@link #resetPlace}), or null if they agree.
   */
  static String resetDivergence(ParserState s) {
    return s.stack().isEmpty() || vnuReset(s) == standardReset(s) ? null : resetPlace(s);
  }

  /**
   * In words, where the standard's reset of the insertion mode from the stack of {@code s} stops,
   * and the SVG or MathML element above it that validator.nu stops at instead: "the <td> that
   * holds the <svg>", or "the <tr>" when none is open any more.
   */
  static String resetPlace(ParserState s) {
    OpenElement foreign = null;
    for (int i = s.stack().size() - 1; i >= 0; i--) {
      OpenElement e = s.stack().get(i);
      if (!OpenElement.HTML.equals(e.namespace())) {
        foreign = e;
      } else if (RESET_STOPS.contains(e.localName())) {
        return "the <" + e.localName() + ">" + (foreign == null ? "" : " that holds the <" + foreign.localName() + ">");
      }
    }
    return foreign == null ? "the table" : "the table that holds the <" + foreign.localName() + ">";
  }

  /** HTML elements at which the standard's reset of the insertion mode stops. */
  private static final Set<String> RESET_STOPS = Backports.setOf("td", "th", "tr", "tbody", "thead", "tfoot", "caption",
      "colgroup", "table", "template", "head", "body", "frameset", "html", "select");

  /**
   * The mode the HTML standard's "reset the insertion mode appropriately" (§13.2.4.1) picks from
   * the stack of {@code s}. Its steps name HTML elements only, so it walks past SVG and MathML
   * elements. For the classic engine, the standard of its time, which also has the {@code select}
   * step (classic {@code <select>} parsing).
   */
  static InsertionMode standardReset(ParserState s) {
    List<OpenElement> stack = s.stack();
    boolean classic = s.engine().equals(Engines.CLASSIC.name());
    for (int i = stack.size() - 1; i >= 0; i--) {
      boolean last = i == 0;
      OpenElement node = last && s.fragmentContext() != null ? s.fragmentContext() : stack.get(i);
      if (!OpenElement.HTML.equals(node.namespace())) {
        continue;
      }
      String name = node.localName();
      if (classic && name.equals("select")) {
        if (!last) {
          for (int j = i - 1; j >= 0; j--) {
            OpenElement ancestor = stack.get(j);
            if (ancestor.isHtml("template")) {
              break;
            }
            if (ancestor.isHtml("table")) {
              return InsertionMode.IN_SELECT_IN_TABLE;
            }
          }
        }
        return InsertionMode.IN_SELECT;
      }
      InsertionMode m = resetStep(s, name, last);
      if (m != null) {
        return m;
      }
      if (last) {
        return InsertionMode.IN_BODY;
      }
    }
    return InsertionMode.IN_BODY;
  }

  /**
   * The mode validator.nu's {@code TreeBuilder.resetTheInsertionMode} picks from the stack of
   * {@code s} (26.10.12 and 1.4.16, which adds the {@code select} step). It compares the table
   * names before the namespace, and at the first other SVG or MathML element it picks "in body"
   * ({@code else if ("http://www.w3.org/1999/xhtml" != ns) mode = IN_BODY}), where the standard
   * walks on.
   */
  static InsertionMode vnuReset(ParserState s) {
    List<OpenElement> stack = s.stack();
    boolean classic = s.engine().equals(Engines.CLASSIC.name());
    OpenElement context = s.fragmentContext();
    for (int i = stack.size() - 1; i >= 0; i--) {
      OpenElement node = stack.get(i);
      if (i == 0 && context != null) {
        if (context.isHtml("td") || context.isHtml("th")) {
          return InsertionMode.IN_BODY;
        }
        node = context;
      }
      String name = node.localName();
      if (classic && name.equals("select")) {
        for (int j = i; j > 0; j--) {
          OpenElement ancestor = stack.get(j);
          if (ancestor.isHtml("template")) {
            break;
          }
          if (ancestor.isHtml("table")) {
            return InsertionMode.IN_SELECT_IN_TABLE;
          }
        }
        return InsertionMode.IN_SELECT;
      }
      if (TABLE_RESET_NAMES.contains(name)) {
        return resetStep(s, name, false);
      }
      if (!OpenElement.HTML.equals(node.namespace())) {
        return InsertionMode.IN_BODY;
      }
      if (name.equals("head")) {
        return context != null && context.localName().equals("head") ? InsertionMode.IN_BODY : InsertionMode.IN_HEAD;
      }
      InsertionMode m = resetStep(s, name, false);
      if (m != null) {
        return m;
      }
      if (i == 0) {
        return InsertionMode.IN_BODY;
      }
    }
    return InsertionMode.IN_BODY;
  }

  private static final Set<String> TABLE_RESET_NAMES =
      Backports.setOf("td", "th", "tr", "tbody", "thead", "tfoot", "caption", "colgroup", "table");

  /** The standard's reset step for an HTML element {@code name}, or null to walk on. */
  private static InsertionMode resetStep(ParserState s, String name, boolean last) {
    switch (name) {
      case "td":
      case "th":
        return last ? null : InsertionMode.IN_CELL;
      case "tr":
        return InsertionMode.IN_ROW;
      case "tbody":
      case "thead":
      case "tfoot":
        return InsertionMode.IN_TABLE_BODY;
      case "caption":
        return InsertionMode.IN_CAPTION;
      case "colgroup":
        return InsertionMode.IN_COLUMN_GROUP;
      case "table":
        return InsertionMode.IN_TABLE;
      case "template":
        List<InsertionMode> t = s.templateModes();
        return t.isEmpty() ? InsertionMode.IN_TEMPLATE : t.get(t.size() - 1);
      case "head":
        return last ? null : InsertionMode.IN_HEAD;
      case "body":
        return InsertionMode.IN_BODY;
      case "frameset":
        return InsertionMode.IN_FRAMESET;
      case "html":
        return s.headPointer() ? InsertionMode.AFTER_HEAD : InsertionMode.BEFORE_HEAD;
      default:
        return null;
    }
  }

  /**
   * Whether the active formatting list may depend on a computed attribute (memo §3.4.3): an entry
   * after the last marker carries {@link #MARK}, and at least two others have its name, so the
   * next element of that name can make the parser compare the computed value.
   */
  static boolean noahsArk(ParserState s) {
    List<Integer> list = s.activeFormatting();
    int start = list.size();
    while (start > 0 && list.get(start - 1) != ParserState.MARKER) {
      start--;
    }
    for (int i = start; i < list.size(); i++) {
      if (!marked(s.activeFormattingAttributes().get(i))) {
        continue;
      }
      OpenElement e = s.activeFormattingElements().get(i);
      int same = 0;
      for (int j = start; j < list.size(); j++) {
        if (e.equals(s.activeFormattingElements().get(j))) {
          same++;
        }
      }
      if (same >= 3) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether an active formatting entry carries a computed attribute (also before a marker, which
   * a later end tag can remove).
   */
  static boolean hasMarkedFormatting(ParserState s) {
    for (int i = 0; i < s.activeFormatting().size(); i++) {
      if (marked(s.activeFormattingAttributes().get(i))) {
        return true;
      }
    }
    return false;
  }

  private static boolean marked(List<String> attributes) {
    if (attributes == null) {
      return false;
    }
    for (String a : attributes) {
      if (a.contains(MARK)) {
        return true;
      }
    }
    return false;
  }
}
