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
 * Every message text of the context verifier, in one place (#368). A message says, in plain words,
 * what a browser does with the value at that point and the smallest template change that fixes it.
 * It never shows a parser state, an insertion mode or an engine class. When the hole is inside a
 * fragment, the error is at the call site and the message names the fragment and the hole; when
 * only one engine or scripting mode rejects it, the message says so. Every message ends with
 * {@code [verifier:RULE_ID]} ({@link Problem}, stable ids). Guide section 7 has the catalogue, and
 * {@code VerifierMessagesTest} the golden text.
 */
final class VerifierMessages {
  private VerifierMessages() {}

  private static final String RICH_TEXT = "for stored rich text, print it with t:utext=\"html.sanitize(...)\""
      + " (or html.sanitizeRich, or an application HtmlPolicy; guide section 4.10, Rich text)";

  static String message(Finding f) {
    StringBuilder sb = new StringBuilder();
    if (f.inner != null && f.problem != Problem.STRUCTURE_DEPENDS_ON_DATA) {
      sb.append("in fragment ").append(f.fragment).append(", inserted here, at ").append(f.inner).append(": ");
    }
    sb.append(text(f));
    if (f.recursive && f.problem != Problem.STRUCTURE_DEPENDS_ON_DATA) {
      sb.append("; this happens only because the fragment calls itself, so the markup structure depends on data: ")
          .append(RICH_TEXT);
    }
    sb.append(engines(f.states, f.parsers));
    sb.append(" [verifier:").append(f.problem.name()).append(']');
    return sb.toString();
  }

  private static String text(Finding f) {
    ParserState s = f.states.isEmpty() ? null : f.states.get(0);
    String value = value(f);
    switch (f.problem) {
      case TEXT_NOT_IN_TEXT:
        if (s != null && script(s)) {
          return "a browser runs " + value + " " + reading(s) + ", not as text; " + scriptFix(s);
        }
        return "a browser reads " + value + " " + reading(s) + ", not as text; " + markupFix(s);
      case TEXT_IN_SCRIPT:
        return textInScript(value, s);
      case TEXT_IN_TABLE:
        return s != null && s.mode() == InsertionMode.IN_COLUMN_GROUP
            ? "a browser reads " + value + " as text in a <colgroup>, which ends the column group, and moves it in"
                + " front of the table; put it in a <td> or <th>"
            : "a browser reads " + value + " as table text and moves it in front of the table; put it in a <td>"
                + " or <th>";
      case TEXT_OUTSIDE_BODY:
        return textOutsideBody(value, s);
      case TEXT_RECONSTRUCTS:
        return "before " + value + " a browser reopens formatting elements (<b>, <i>, <a>, ...) that an end tag"
            + " closed out of order, and puts the value inside the copies; close elements in the reverse order they"
            + " were opened (</b> before </p>)";
      case RCDATA_NOT_IN_RCDATA:
        return "this value is escaped for the text of <title> or <textarea>, but a browser reads it " + reading(s)
            + ", where a {{ in it could run in Vue or AngularJS; " + (foreign(s)
                ? "insert the <title> or <textarea> outside <svg> and <math>"
                : "check the markup before it");
      case ATTRIBUTE_NOT_IN_VALUE:
        return "a browser reads " + value + " " + reading(s) + ", not inside the double-quoted value of '"
            + f.attribute + "'; check the markup before the tag: a browser ended the tag, or started raw text, there";
      case ATTRIBUTE_WRONG_NAME:
        return s != null && s.attributeName() == null
            ? "a browser keeps only the first '" + f.attribute + "' attribute of a tag and drops this one; write"
                + " the attribute once"
            : "a browser reads " + value + " as the value of '" + (s == null ? "?" : s.attributeName())
                + "', not of '" + f.attribute + "'; check the markup before it in the tag";
      case ATTRIBUTE_VALUE_CHANGES_PARSE:
        return "encoding".equalsIgnoreCase(f.attribute)
            ? "a computed 'encoding' on MathML <annotation-xml> decides whether a browser parses its content as HTML"
                + " or as MathML; write the encoding as a static value"
            : "a computed '" + f.attribute + "' on an <input> directly in a table decides where a browser puts it"
                + " (type=hidden stays in the table, any other input is moved in front of it); move the input into"
                + " a <td>, or write the type as a static value";
      case JSON_NOT_IN_SCRIPT:
        return jsonNotInScript(value, s);
      case RAW_NOT_FLOW:
        return rawNotFlow(f.source, s);
      case FRAMESET:
        return "<frameset> isn't supported: a browser uses it only if no body content came before it, so the page"
            + " would depend on what precedes it; use an <iframe> or an ordinary layout";
      case NOAHS_ARK:
        return "three or more identical formatting elements (<b>, <i>, <a>, ...) are open here, one with a computed"
            + " attribute: a browser reopens at most three of them, comparing attribute values, so the structure"
            + " can depend on the value; close them before opening another, or move the computed attribute to a <span>";
      case LOOP_NO_FIXED_POINT:
        return "each pass of this loop leaves a browser with more elements open (it doesn't settle in "
            + ContextVerifier.MAX_ROUNDS + " passes); close inside the loop every element the loop opens";
      case STRUCTURE_DEPENDS_ON_DATA:
        return structure(f);
      case TOO_MANY_STATES:
        return "the branches and loops before this point can leave a browser in more than " + ContextVerifier.MAX_STATES
            + " different states (different elements open), more than Tessera checks; close inside each t:if,"
            + " t:switch and t:each the elements it opens, or split the template into fragments";
      case ENGINE_FAILURE:
        return "Tessera can't check what a browser does here: the HTML parser it checks with failed (" + f.detail
            + "); this is a compiler bug, report it with the template";
      case UNKNOWN_NODE:
        return "Tessera can't check what a browser does here: " + f.detail + " is unknown to the verifier;"
            + " this is a compiler bug, report it with the template";
      default:
        throw new AssertionError(f.problem);
    }
  }

  /** What is written at the hole: the value, or for an attribute, the attribute's value. */
  private static String value(Finding f) {
    if (f.escaper == null) {
      return "this value";
    }
    switch (f.escaper) {
      case TEXT:
        return "this value";
      case RCDATA:
        return "this value (escaped for <title> or <textarea>)";
      case JSON:
        return "this value (written as JSON for a script)";
      default:
        return f.attribute == null ? "this attribute value" : "this value of '" + f.attribute + "'";
    }
  }

  private static String textInScript(String value, ParserState s) {
    OpenElement current = s == null ? null : s.currentNode();
    String name = current == null ? "script" : current.localName();
    if (name.equals("style")) {
      return "a browser reads " + value + " as CSS in " + element(current) + ", where escaping for text doesn't keep it"
          + " inert; Tessera doesn't write values into style sheets: set a class instead";
    }
    if (foreign(s)) {
      return "a browser runs " + value + " as the code of " + element(current) + ", where escaping for text doesn't"
          + " keep it inert; write the script as an HTML <script> outside <svg> and <math>, with data as [[${...}]]";
    }
    return "a browser runs " + value + " " + reading(s) + ", where escaping for text doesn't keep it inert; "
        + scriptFix(s);
  }

  private static String textOutsideBody(String value, ParserState s) {
    String fix = "; put it in an element in the body";
    if (s == null) {
      return "a browser reads " + value + " as text outside the body" + fix;
    }
    switch (s.mode()) {
      case INITIAL:
      case BEFORE_HTML:
      case BEFORE_HEAD:
        return "a browser reads " + value + " as text before the <head>, which starts the body there, so what"
            + " follows is in the body" + fix;
      case IN_HEAD:
        return "a browser reads " + value + " as text in the <head>, which ends the head there, so the rest of the"
            + " head lands in the body; put it in <title>, in <meta content>, or in an element in the body";
      case IN_HEAD_NOSCRIPT:
        return "a browser with scripting off reads " + value + " as text in a <noscript> in the <head>, which ends"
            + " the head there; put it in an element in the body";
      case AFTER_HEAD:
        return "a browser reads " + value + " as text between </head> and <body>, which starts the body there" + fix;
      case AFTER_BODY:
      case AFTER_AFTER_BODY:
        return "a browser reads " + value + " as text after </body> and moves it back into the body" + fix;
      default:
        return "a browser reads " + value + " as text " + mode(s) + ", where it drops it or moves it elsewhere" + fix;
    }
  }

  private static String jsonNotInScript(String value, ParserState s) {
    String head = "a browser reads " + value + " " + reading(s) + "; JSON is safe only as the code of an HTML <script>";
    if (s != null && script(s) && s.tokenizer() != TokenizerState.SCRIPT_DATA) {
      return head + "; " + scriptFix(s);
    }
    if (foreign(s) && s.tokenizer() == TokenizerState.DATA) {
      return head + "; write the script as an HTML <script> outside <svg> and <math>";
    }
    return head + "; check the markup before it: a browser ended the <script>, or never started it, there";
  }

  private static String rawNotFlow(Skeleton.Source source, ParserState s) {
    String what = source(source);
    String where = s == null ? "here" : s.tokenizer() != TokenizerState.DATA ? reading(s)
        : foreign(s) ? "inside " + element(s.currentNode()) : mode(s);
    String head = "a browser parses " + what + " " + where + ", not as content of the body";
    if (s == null) {
      return head + "; insert it in an element such as <div> in the body";
    }
    OpenElement current = s.currentNode();
    if (s.tokenizer() != TokenizerState.DATA) {
      return head + ", so its markup is shown or dropped; insert it outside " + element(current);
    }
    if (foreign(s)) {
      return head + ", where HTML tags end the <svg> or <math> early; insert it in an HTML element outside <svg>"
          + " and <math>";
    }
    switch (s.mode()) {
      case INITIAL:
      case BEFORE_HTML:
      case BEFORE_HEAD:
      case IN_HEAD:
      case IN_HEAD_NOSCRIPT:
      case AFTER_HEAD:
        return head + "; insert HTML only in the body, and write head content (<link>, <meta>) in the template or"
            + " pass it as a fragment";
      case IN_TABLE:
      case IN_TABLE_BODY:
      case IN_ROW:
      case IN_COLUMN_GROUP:
        return head + ", and moves what isn't table markup in front of the table; put it in a <td> or <th>";
      case IN_SELECT:
      case IN_SELECT_IN_TABLE:
        return head + ", where a browser keeps only options; write the <option> elements in the template";
      default:
        if (s.reconstructionPending()) {
          return head + ", because formatting elements (<b>, <i>, <a>, ...) were closed out of order before it;"
              + " close elements in the reverse order they were opened";
        }
        return head + "; insert it in an element such as <div> in the body";
    }
  }

  private static String structure(Finding f) {
    String who = f.fragment == null ? "this fragment calls itself" : "fragment " + f.fragment + " calls itself"
        + (f.inner == null ? "" : " (at " + f.inner + ")");
    return who + ", and each call nests deeper in a browser (it doesn't settle in " + ContextVerifier.MAX_ROUNDS
        + " calls), so the markup structure depends on data; " + RICH_TEXT + " and delete the fragment; for"
        + " other trees, render a fixed depth or flatten the tree in Java";
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

  /** Whether the tokenizer is in a script's code (any of its states after {@code <script>}). */
  private static boolean script(ParserState s) {
    return s.tokenizer().name().startsWith("SCRIPT_DATA");
  }

  /** In script data after {@code <!--}, where browsers look for {@code <script} and {@code -->}. */
  private static boolean escapedScript(ParserState s) {
    String name = s.tokenizer().name();
    return name.startsWith("SCRIPT_DATA_ESCAPE") || name.startsWith("SCRIPT_DATA_DOUBLE");
  }

  private static String scriptFix(ParserState s) {
    if (s != null && escapedScript(s)) {
      return "remove the '<!--' (and any '<script' after it) from the script";
    }
    // Tessera wrote this hole as text, so the template shows no <script> here: one starts in the
    // markup before it, where Tessera and a browser read the markup differently.
    return "this <script> starts in the markup before it, where the template shows none: move a <script>"
        + " out of a <title> inside <svg>, <math> or <select>, and write a literal '<' in attribute values, comments"
        + " and CDATA sections as &lt;";
  }

  /** Whether the current node is SVG or MathML. */
  private static boolean foreign(ParserState s) {
    OpenElement current = s == null ? null : s.currentNode();
    return current != null && !OpenElement.HTML.equals(current.namespace());
  }

  /** The element, as written: {@code <style>}, {@code SVG <script>}. */
  private static String element(OpenElement e) {
    if (e == null) {
      return "the element";
    }
    String ns = OpenElement.HTML.equals(e.namespace()) ? "" : OpenElement.SVG.equals(e.namespace()) ? "SVG " : "MathML ";
    return ns + "<" + e.localName() + ">";
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
        return "in a <frameset>";
      default:
        OpenElement current = s.currentNode();
        return current == null ? "in the body" : "in " + element(current);
    }
  }

  /** How a browser reads what comes next, in words: from the tokenizer, and in text from where it goes. */
  private static String reading(ParserState s) {
    if (s == null) {
      return "here";
    }
    OpenElement current = s.currentNode();
    String name = s.tokenizer().name();
    switch (s.tokenizer()) {
      case DATA:
        if (foreign(s) && (current.localName().equals("script") || current.localName().equals("style"))) {
          return "inside " + element(current) + ", where a browser decodes character references and reads tags";
        }
        return foreign(s) ? "as text inside " + element(current) : "as text " + mode(s);
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
      case BEFORE_ATTRIBUTE_VALUE:
        return "inside an unquoted attribute value";
      case TAG_OPEN:
      case TAG_NAME:
        return "as a tag name, after a literal '<'";
      case RAWTEXT_RCDATA_LESS_THAN_SIGN:
        return "as the start of an end tag, after a literal '<'";
      case CLOSE_TAG_OPEN:
      case NON_DATA_END_TAG_NAME:
        return "as the name of an end tag, after a literal '</'";
      case BEFORE_ATTRIBUTE_NAME:
      case ATTRIBUTE_NAME:
      case AFTER_ATTRIBUTE_NAME:
      case AFTER_ATTRIBUTE_VALUE_QUOTED:
      case SELF_CLOSING_START_TAG:
        return "inside a tag, as attribute names";
      case BOGUS_COMMENT:
      case BOGUS_COMMENT_HYPHEN:
      case PROCESSING_INSTRUCTION:
      case PROCESSING_INSTRUCTION_QUESTION_MARK:
        return "inside a comment that a '<!' or '<?' started and the next '>' ends";
      default:
        break;
    }
    if (name.startsWith("SCRIPT_DATA")) {
      return escapedScript(s) ? "as the code of <script>, after a '<!--' in it" : "as the code of <script>";
    }
    if (name.contains("COMMENT")) {
      return "inside a comment";
    }
    if (name.contains("DOCTYPE") || name.startsWith("MARKUP_DECLARATION")) {
      return "inside a '<!' declaration";
    }
    if (name.startsWith("CDATA")) {
      return "inside a CDATA section";
    }
    if (name.contains("NCR") || name.contains("NRC") || name.contains("CHARACTER_REFERENCE") || name.equals("AMBIGUOUS_AMPERSAND")) {
      return "as part of a character reference, after a literal '&'";
    }
    if (name.contains("END_TAG") || name.contains("LESS_THAN")) {
      return "as the name of an end tag, after a literal '</'";
    }
    return "inside markup";
  }

  /** The fix for a text hole that a browser reads inside markup, by where it reads it. */
  private static String markupFix(ParserState s) {
    if (s == null) {
      return "check the markup before it";
    }
    String reading = reading(s);
    if (reading.startsWith("as a tag name") || reading.startsWith("as the name of an end tag")
        || reading.startsWith("as the start of an end tag")) {
      return "write the literal '<' before it as &lt;";
    }
    if (reading.startsWith("as part of a character reference")) {
      return "write the literal '&' before it as &amp;";
    }
    if (s.tokenizer() == TokenizerState.PLAINTEXT) {
      return "remove the <plaintext>, which makes the rest of the page plain text; use <pre> instead";
    }
    if (reading.startsWith("inside a comment that")) {
      return "write the '<' of the '<!' or '<?' before it as &lt;";
    }
    if (reading.startsWith("inside a CDATA section")) {
      return "move the expression out of the CDATA section";
    }
    if (reading.startsWith("inside a comment") || reading.startsWith("inside a '<!'")) {
      return "move the expression out of the comment";
    }
    return "check the markup before it: a browser started a tag there";
  }

  /**
   * Which parsers reject it, when not all do: an engine that alone rejects means browsers differ
   * there; a scripting mode that alone rejects is named.
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
      parts.add(engines.iterator().next().equals(Engines.CLASSIC.name())
          ? "in the older HTML parsing some browsers still use"
          : "in current HTML parsing");
    }
    if (scripting.size() == 1 && parsers == 2 * Engines.all().size()) {
      parts.add(scripting.iterator().next() ? "with scripting on" : "with scripting off");
    }
    return parts.isEmpty() ? "" : " (only " + String.join(", ", parts) + (engines.size() < Engines.all().size()
        ? ": browsers differ here)" : ")");
  }
}
