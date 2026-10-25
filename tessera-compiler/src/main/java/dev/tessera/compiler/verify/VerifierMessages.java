package dev.tessera.compiler.verify;

import dev.tessera.compiler.verify.engine.Engines;
import dev.tessera.compiler.verify.engine.InsertionMode;
import dev.tessera.compiler.verify.engine.OpenElement;
import dev.tessera.compiler.verify.engine.ParserState;
import dev.tessera.compiler.verify.engine.TokenizerState;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every message text of the context verifier, in one place (#368 rewords them and adds fix-its).
 * A message names the rule ({@link Problem}, a stable id), the escaper or what is inserted, what a
 * browser does there in words (never a numeric parser state), and which parser and scripting mode
 * reject it when not all of them do.
 */
final class VerifierMessages {
  private VerifierMessages() {}

  static String message(Finding f) {
    StringBuilder sb = new StringBuilder();
    sb.append(text(f));
    if (f.inner != null) {
      sb.append(" (in fragment ").append(f.fragment).append(" at ").append(f.inner).append(", inserted here)");
    }
    if (f.recursive && f.problem != Problem.STRUCTURE_DEPENDS_ON_DATA) {
      sb.append("; the fragment reaches this only by calling itself, so the markup structure depends on data: "
          + "render rich text as SafeHtml with html.sanitize(...) or html.sanitizeRich(...) instead (guide section 4.10, Rich text)");
    }
    sb.append(engines(f.states, f.parsers));
    sb.append(" [verifier:").append(f.problem.name()).append(']');
    return sb.toString();
  }

  private static String text(Finding f) {
    ParserState s = f.states.isEmpty() ? null : f.states.get(0);
    String value = f.escaper == null ? "this value" : "this value (" + f.escaper.method() + " escaper)";
    switch (f.problem) {
      case TEXT_NOT_IN_TEXT:
        return "a browser reads " + value + " " + tokenizer(s) + ", not as text; close what is open before the expression";
      case TEXT_IN_SCRIPT:
        return "a browser reads " + value + " as " + rawText(s)
            + ", where HTML escaping doesn't keep it inert; pass data to a script as [[${...}]] inside an HTML <script>,"
            + " or put it in a data-* attribute";
      case TEXT_IN_TABLE:
        return s != null && s.mode() == InsertionMode.IN_COLUMN_GROUP
            ? "a browser reads " + value + " as text in a <colgroup>, which ends the column group and moves the text"
                + " in front of the table; put it in a <td> or <th>"
            : "a browser reads " + value + " as table text and moves it in front of the table; put it in a <td> or <th>";
      case TEXT_OUTSIDE_BODY:
        return "a browser reads " + value + " as text " + mode(s) + ", where text changes where the rest of the page"
            + " goes; put it in an element of the body";
      case TEXT_RECONSTRUCTS:
        return "before " + value + " a browser reopens formatting elements (<b>, <i>, <a>, ...) that were closed out of"
            + " order; close elements in the reverse order they were opened";
      case ATTRIBUTE_NOT_IN_VALUE:
        return "a browser reads " + value + " " + tokenizer(s) + ", not inside the double-quoted value of '"
            + f.attribute + "'; check the markup before it";
      case ATTRIBUTE_WRONG_NAME:
        return s != null && s.attributeName() == null
            ? "a browser drops " + value + ": '" + f.attribute + "' is a duplicate attribute there; write it once"
            : "a browser reads " + value + " as the value of '" + (s == null ? "?" : s.attributeName()) + "', not of '"
                + f.attribute + "'; check the markup before it";
      case ATTRIBUTE_VALUE_CHANGES_PARSE:
        return "the value of '" + f.attribute + "' decides how a browser parses the rest of the page here ("
            + "an <input type=hidden> stays in a table, any other input is moved in front of it; a MathML encoding makes"
            + " its content HTML); write it as a static value";
      case JSON_NOT_IN_SCRIPT:
        return "a browser reads " + value + " " + where(s) + ", not as the script of an HTML <script>; JSON is"
            + " safe only there";
      case RAW_NOT_FLOW:
        return "a browser reads " + source(f.source) + " " + where(s) + ", not as flow content in the body;"
            + " insert it in an element such as <div> in the body";
      case FRAMESET:
        return "<frameset> isn't supported: whether a browser uses it depends on the text before it";
      case NOAHS_ARK:
        return "three or more identical formatting elements (<b>, <i>, <a>, ...) are open here and one has a"
            + " computed attribute: a browser compares the computed values to decide which to reopen; close them";
      case LOOP_NO_FIXED_POINT:
        return "each pass of this loop leaves a browser in a new state (it doesn't settle in "
            + ContextVerifier.MAX_ROUNDS + " passes); balance the markup inside the loop";
      case STRUCTURE_DEPENDS_ON_DATA:
        return "markup structure depends on data: this fragment calls itself into ever new parser states (it"
            + " doesn't settle in " + ContextVerifier.MAX_ROUNDS + " calls); render rich text as SafeHtml with"
            + " html.sanitize(...) or html.sanitizeRich(...) instead (guide section 4.10, Rich text)";
      case TOO_MANY_STATES:
        return "too many distinct parser states here (more than " + ContextVerifier.MAX_STATES
            + "); split the template";
      case ENGINE_FAILURE:
        return "the HTML parser failed here, so the template can't be checked: " + f.detail;
      case RCDATA_NOT_IN_RCDATA:
        return "a browser reads " + value + " as text " + where(s) + ", not as the text of <title> or <textarea>:"
            + " escaped for those, it keeps {{ that Vue or AngularJS could run; check the markup around it";
      case UNKNOWN_NODE:
        return "the verifier can't interpret " + f.detail;
      default:
        throw new AssertionError(f.problem);
    }
  }

  private static String source(Skeleton.Source source) {
    if (source == null) {
      return "this HTML";
    }
    switch (source) {
      case SAFE_HTML:
        return "this SafeHtml value";
      case HTML_MESSAGE:
        return "this HTML message";
      default:
        return "this Fragment value";
    }
  }

  /** Where the parser is, in words: the tokenizer state if it is in a token, else the mode. */
  private static String where(ParserState s) {
    if (s == null) {
      return "here";
    }
    if (s.tokenizer() != TokenizerState.DATA) {
      return tokenizer(s);
    }
    OpenElement current = s.currentNode();
    if (current != null && !OpenElement.HTML.equals(current.namespace())) {
      return "inside " + (OpenElement.SVG.equals(current.namespace()) ? "SVG" : "MathML") + " <" + current.localName() + ">";
    }
    if (s.reconstructionPending()) {
      return "after formatting elements were closed out of order";
    }
    return mode(s);
  }

  private static String rawText(ParserState s) {
    OpenElement current = s == null ? null : s.currentNode();
    String name = current == null ? "?" : current.localName();
    String ns = current == null || OpenElement.HTML.equals(current.namespace()) ? ""
        : OpenElement.SVG.equals(current.namespace()) ? "SVG " : "MathML ";
    return name.equals("style") ? "the style sheet of " + ns + "<style>" : "the code of " + ns + "<" + name + ">";
  }

  private static String mode(ParserState s) {
    if (s == null) {
      return "here";
    }
    switch (s.mode()) {
      case INITIAL:
      case BEFORE_HTML:
        return "before <html>";
      case BEFORE_HEAD:
      case IN_HEAD:
      case IN_HEAD_NOSCRIPT:
        return "in the <head>";
      case AFTER_HEAD:
        return "between </head> and <body>";
      case IN_TABLE:
      case IN_TABLE_BODY:
      case IN_ROW:
        return "in a table outside any cell";
      case IN_COLUMN_GROUP:
        return "in a <colgroup>";
      case IN_SELECT:
      case IN_SELECT_IN_TABLE:
        return "in a <select>";
      case AFTER_BODY:
      case AFTER_AFTER_BODY:
        return "after </body>";
      case IN_FRAMESET:
      case AFTER_FRAMESET:
      case AFTER_AFTER_FRAMESET:
        return "in a frameset";
      default:
        OpenElement current = s.currentNode();
        return current == null ? "in the body" : "in <" + current.localName() + ">";
    }
  }

  /** The tokenizer state in words. */
  private static String tokenizer(ParserState s) {
    if (s == null) {
      return "here";
    }
    String name = s.tokenizer().name();
    switch (s.tokenizer()) {
      case DATA:
        return "as text " + mode(s);
      case RCDATA:
        return "as the text of <" + s.endTagExpectation() + ">";
      case RAWTEXT:
        return "as the raw text of <" + s.endTagExpectation() + ">";
      case PLAINTEXT:
        return "as plain text after <plaintext>";
      case ATTRIBUTE_VALUE_DOUBLE_QUOTED:
        return "inside the double-quoted value of '" + s.attributeName() + "'";
      case ATTRIBUTE_VALUE_SINGLE_QUOTED:
        return "inside a single-quoted attribute value";
      case ATTRIBUTE_VALUE_UNQUOTED:
        return "inside an unquoted attribute value";
      case TAG_OPEN:
      case CLOSE_TAG_OPEN:
      case TAG_NAME:
        return "as a tag name";
      case BEFORE_ATTRIBUTE_NAME:
      case ATTRIBUTE_NAME:
      case AFTER_ATTRIBUTE_NAME:
      case BEFORE_ATTRIBUTE_VALUE:
      case AFTER_ATTRIBUTE_VALUE_QUOTED:
      case SELF_CLOSING_START_TAG:
        return "inside a tag, as attribute names";
      default:
        break;
    }
    if (name.startsWith("SCRIPT_DATA")) {
      return "as the code of <script>";
    }
    if (name.contains("COMMENT")) {
      return "inside a comment";
    }
    if (name.contains("DOCTYPE") || name.startsWith("MARKUP_DECLARATION")) {
      return "inside a doctype or markup declaration";
    }
    if (name.startsWith("CDATA")) {
      return "inside a CDATA section";
    }
    if (name.contains("NCR") || name.contains("NRC") || name.contains("CHARACTER_REFERENCE") || name.equals("AMBIGUOUS_AMPERSAND")) {
      return "as part of a character reference (after an '&')";
    }
    if (name.contains("END_TAG") || name.contains("LESS_THAN")) {
      return "as part of an end tag";
    }
    if (name.startsWith("PROCESSING_INSTRUCTION")) {
      return "inside a processing instruction";
    }
    return "inside markup";
  }

  /**
   * Which parsers reject it, when not all four do: an engine that alone rejects means browsers
   * differ there.
   */
  private static String engines(List<ParserState> states, int parsers) {
    if (states.isEmpty() || states.size() == parsers) {
      return "";
    }
    Set<String> engines = new LinkedHashSet<>();
    Set<Boolean> scripting = new LinkedHashSet<>();
    for (ParserState s : states) {
      engines.add(s.engine());
      scripting.add(s.scripting());
    }
    List<String> parts = new ArrayList<>();
    if (engines.size() < Engines.all().size()) {
      String e = engines.iterator().next();
      parts.add("in " + e + " only: browsers differ here ("
          + (e.equals(Engines.CLASSIC.name()) ? "classic <select> parsing, as in Firefox ESR"
              : "customizable <select> parsing, as in current Chromium") + ")");
    }
    if (scripting.size() == 1 && parsers == 2 * Engines.all().size()) {
      parts.add(scripting.iterator().next() ? "with scripting on" : "with scripting off");
    }
    return parts.isEmpty() ? "" : " (" + String.join("; ", parts) + ")";
  }
}
