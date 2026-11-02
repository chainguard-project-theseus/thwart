package dev.tessera.compiler.verify;

import dev.tessera.compiler.verify.engine.InsertionMode;
import dev.tessera.compiler.verify.engine.OpenElement;
import dev.tessera.compiler.verify.engine.ParserState;
import dev.tessera.compiler.verify.engine.TokenizerState;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every message text of the context verifier, in one place (#368). A message says, in plain words,
 * what a browser does with the value at that point and the smallest template change that fixes it.
 * It never shows a parser state, an insertion mode or an engine class. When the hole is inside a
 * fragment, the error is at the call site and the message names the fragment and the hole; when
 * only one scripting mode rejects it, the message says so. Every message ends with
 * {@code [verifier:RULE_ID]} ({@link Problem}, stable ids). Guide section 5 summarizes them, and
 * {@code VerifierMessagesTest} the golden text.
 */
final class VerifierMessages {
  private VerifierMessages() {}

  private static final String RICH_TEXT = "for stored rich text, print it with t:utext=\"html.sanitize(...)\""
      + " (or html.sanitizeRich, or an application HtmlPolicy; guide section 4.10, Rich text)";

  static String message(Finding f) {
    StringBuilder sb = new StringBuilder();
    if (f.inner != null && f.problem != Problem.STRUCTURE_DEPENDS_ON_DATA) {
      sb.append("in fragment ").append(f.fragment).append(", inserted here, at ").append(f.inner);
      if (f.calls.size() > 1) {
        sb.append(", through the calls ").append(chain(f.calls, "here"));
      }
      sb.append(": ");
    }
    sb.append(text(f));
    if (f.causeAt != null) {
      // The markup before the value that put the parser there (#393): <svg> itself is named without "SVG".
      String name = f.cause.localName().equals("svg") || f.cause.localName().equals("math")
          ? "<" + f.cause.localName() + ">" : element(f.cause);
      sb.append("; a browser opened the ").append(name).append(" it is in at ").append(f.causeAt);
    }
    if (f.recursive && f.problem != Problem.STRUCTURE_DEPENDS_ON_DATA) {
      sb.append("; this happens only because the fragment calls itself, so the markup structure depends on data: ")
          .append(RICH_TEXT);
    }
    sb.append(reportedByParser(f.problem) ? scripting(f.states) : scriptingOnly(f.states, f.parsers));
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
        return "a computed 'encoding' on MathML <annotation-xml> decides whether a browser parses its content as HTML"
            + " or as MathML; write the encoding as a static value";
      case JSON_NOT_IN_SCRIPT:
        return jsonNotInScript(value, s);
      case RAW_NOT_FLOW:
        return rawNotFlow(f.source, s);
      case RAW_CLOSES_ELEMENT:
        return "a browser lets the markup of " + source(f.source) + " close " + f.detail + " around it"
            + closedBy(f.detail) + ", so the rest of the page would parse differently; insert it in a <div> instead,"
            + " outside the open element";
      case FRAMESET:
        return "<frameset> isn't supported: a browser uses it only if no body content came before it, so the page"
            + " would depend on what precedes it; use an <iframe> or an ordinary layout";
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
      case PARSE_ERROR:
        return parseError(f.detail);
      case FOREIGN_IN_TABLE:
        return f.detail + ": browsers parse a nested table, <template> or <select> that ends inside <svg> or <math>"
            + " in a table differently (Firefox goes on as if outside the table, Chrome and Safari go back to the"
            + " cell), so Tessera doesn't accept <svg> or <math> anywhere in a table or <template>; move it out of"
            + " the table, or show the image with <img src=\"....svg\">";
      case TABLE_IN_TEMPLATE:
        return f.detail + ": browsers handle a </table> inside it differently (Firefox closes a table around the"
            + " <template>, Chrome and Safari ignore it), so Tessera doesn't accept table parts directly in a"
            + " <template>; put a whole <table> in the <template>";
      case SELECT_CONTENT:
        return f.detail + ": browsers that parse <select> the classic way (Firefox ESR) drop everything in it but"
            + " <option>, <optgroup> and <hr>, and newer ones keep it, so the rest of the page can parse differently;"
            + " insert only <option>, <optgroup> and <hr> into a <select> (also from fragments, t:remove=\"tag\" and"
            + " <template>)";
      case CDATA_IN_INTEGRATION_POINT:
        return "a CDATA section in " + f.detail + ", an integration point: Firefox reads a CDATA section there, as the"
            + " standard does, but Chrome reads a comment that ends at the first '>', so the markup after it would"
            + " differ; write the text without"
            + " <![CDATA[ and ]]>, with '<' as &lt; and '>' as &gt;";
      case UNCALLED_FRAGMENT_FITS_NOWHERE:
        return "no template inserts fragment " + f.detail + ", and it is declared where no fragment fits (in raw text, in"
            + " an <svg> or <math> that holds an HTML element, or under t:remove=\"tag\"), so Tessera can't check what it"
            + " writes on its own; declare it in HTML, <svg> or <math> content, or delete it";
      case VALUE_IN_RAW_TEXT:
        return "a browser reads " + value + " as the raw text of "
            + (s == null || s.currentNode() == null ? "the element" : element(s.currentNode()))
            + ", which decodes no escaping; write the content as"
            + " static text, or move the value outside the element";
      default:
        throw new AssertionError(f.problem);
    }
  }

  /** Problems a parser reports while it parses (#406), not found in a state at a hole. */
  private static boolean reportedByParser(Problem p) {
    return p == Problem.PARSE_ERROR || p == Problem.FOREIGN_IN_TABLE || p == Problem.TABLE_IN_TEMPLATE
        || p == Problem.SELECT_CONTENT || p == Problem.FRAMESET || p == Problem.CDATA_IN_INTEGRATION_POINT;
  }

  /** A second {@code <html>}, {@code <head>} or {@code <body>}, as the parser words it. */
  private static final Pattern SECOND_PAGE_ELEMENT = Pattern.compile(
      "Start tag \u201C(html|head|body)\u201D seen but an element of the same type was already open\\.|"
          + "Stray (start|end) tag \u201C(html|head|body)\u201D\\.");

  /** An end tag after the body was closed (by a second {@code </body>}, say). */
  private static final String AFTER_BODY = "Saw an end tag after \u201Cbody\u201D had been closed.";

  /** A {@code </body>} with elements still open in the body. */
  private static final Pattern BODY_UNCLOSED =
      Pattern.compile("End tag for +\u201Cbody\u201D seen, but there were unclosed elements\\.");

  private static final Pattern BETWEEN_HEAD_AND_BODY =
      Pattern.compile("\u201C([A-Za-z0-9_]+)\u201D element between \u201Chead\u201D and \u201Cbody\u201D\\.");

  private static final Pattern IN_TABLE = Pattern.compile("Start tag \u201C([A-Za-z0-9_]+)\u201D seen in \u201Ctable\u201D\\.");

  private static final String SELF_CLOSING = "Self-closing syntax (\u201C/>\u201D) used on a non-void HTML element."
      + " Ignoring the slash and treating as a start tag.";

  private static final Pattern FOREIGN_HTML =
      Pattern.compile("HTML start tag \u201C([^\u201D]+)\u201D in a foreign namespace context\\.");

  private static final Pattern TAG_NAME = Pattern.compile("<([A-Za-z][^\\t\\n\\f\\r />]*)");

  /** What a hidden-markup fix-it says a bogus comment does. */
  private static final String BOGUS = " as the start of a comment that ends at the first '>', so the markup up to"
      + " there is hidden; ";

  /**
   * The detail of a {@link Problem#PARSE_ERROR}: the parser's {@code message} and the {@code
   * markup} it is in (from its {@code <}, or ""), which some fix-its name (#442).
   */
  static String parseErrorDetail(String message, String markup) {
    return markup.isEmpty() ? message : message + '\u0000' + markup;
  }

  /** How a browser recovers from the parse error in {@code detail} ({@link #parseErrorDetail}), and the fix. */
  private static String parseError(String detail) {
    int split = detail.indexOf('\u0000');
    String message = split < 0 ? detail : detail.substring(0, split);
    String markup = split < 0 ? "" : detail.substring(split + 1);
    if (message.equals(SELF_CLOSING)) {
      Matcher t = TAG_NAME.matcher(markup);
      String name = t.lookingAt() ? Rules.asciiLower(t.group(1)) : null;
      return name == null
          ? "a browser ignores the '/' of '/>' on an element that isn't void and reads a start tag, so what follows"
              + " is inside the element; write the end tag"
          : "a browser ignores the '/' of <" + name + "/>, because <" + name + "> isn't a void element, and reads what"
              + " follows as inside the <" + name + ">; write the end tag: <" + name + " ...></" + name + ">";
    }
    Matcher f = FOREIGN_HTML.matcher(message);
    if (f.matches()) {
      String name = f.group(1);
      return "a browser reads <" + name + "> inside <svg> or <math> as HTML: it closes the <svg> or <math> there and"
          + " parses the rest as HTML; close the <svg> or <math> before the <" + name + ">, or put the HTML in a"
          + " <foreignObject> (in <math>, an <mtext>)";
    }
    if (Rules.asciiStartsWith(markup, 0, "<!doctype")) {
      return "a browser reads this <!doctype> only by error recovery (the HTML parser reports \"" + message + "\")"
          + " and ends it at its first '>'; write <!doctype html>";
    }
    if (message.startsWith("Saw \u201C<?\u201D.")) {
      return "a browser reads '<?'" + BOGUS + "HTML has no processing instructions: remove the <?...?>, or write"
          + " &lt; for a literal '<'";
    }
    if (message.equals("Bogus comment.")) {
      return markup.startsWith("<![CDATA[")
          ? "a browser reads <![CDATA[ outside <svg> and <math>" + BOGUS + "write the text without <![CDATA[ and ]]>,"
              + " with '<' as &lt; and '>' as &gt;"
          : "a browser reads '<!' that doesn't start '<!--' or '<!doctype'" + BOGUS + "write &lt; for a literal"
              + " '<', or write a comment as <!-- ... -->";
    }
    if (message.equals("Garbage after \u201C</\u201D.")) {
      return "a browser reads '</' not followed by a letter" + BOGUS + "write &lt; for a literal '<'";
    }
    String layout = "; a page has one <html>, <head> and <body>: a layout call such as"
        + " t:replace=\"~{layout :: layout(~{::body})}\" passes the whole element into the layout's own, so mark"
        + " the element the page passes t:remove=\"tag\" (<body t:remove=\"tag\">) to pass only its content"
        + " (guide section 4.8, Layouts)";
    Matcher m = SECOND_PAGE_ELEMENT.matcher(message);
    if (m.matches()) {
      String name = m.group(1) != null ? m.group(1) : m.group(3);
      // An element a page writes before a layout call opens the page's <html> before the layout's. Both
      // fixes migrate's report gave are here: the layout may or may not already take a head fragment.
      String before = name.equals("html")
          ? "; an element written before a layout call comes before the layout's doctype and <html>: move it into"
              + " a <head t:remove=\"tag\"> in the page and pass ~{::head} as the layout's head fragment; if the"
              + " layout takes none, give it a Fragment head parameter written as"
              + " <t:block t:replace=\"head\"></t:block> in its <head>, and pass null for it from its other calls"
          : "";
      return m.group(1) != null || "start".equals(m.group(2))
          ? "a browser finds a second <" + name + "> here and merges it into the first" + layout + before
          : "a browser finds an extra </" + name + "> here and ignores it" + layout;
    }
    if (message.equals(AFTER_BODY)) {
      return "a browser reads this end tag after a </body> closed the body, and goes back into the body" + layout;
    }
    if (BODY_UNCLOSED.matcher(message).matches()) {
      return "a browser closes the elements still open in the body at this </body>; close each element before"
          + " </body>, and if this </body> is a second one" + layout.substring(1);
    }
    if (message.equals("No \u201Cp\u201D element in scope but a \u201Cp\u201D end tag seen.")) {
      return "a browser closes an open <p> at the first block element inside it (<div>, <form>, <ul>, <table>, a"
          + " heading, ...), so this </p> has no <p> to close and adds an empty paragraph; a <p> can't hold block"
          + " elements: use a <div> instead of the <p>";
    }
    m = BETWEEN_HEAD_AND_BODY.matcher(message);
    if (m.matches()) {
      return "a browser moves this <" + m.group(1) + "> from between </head> and <body> back into the <head>; write"
          + " it inside the <head> or inside the <body>";
    }
    m = IN_TABLE.matcher(message);
    if (m.matches()) {
      return "a browser moves this <" + m.group(1) + "> out of the table, in front of it, because only table parts"
          + " may sit directly in a <table>; put it in a <td> or <th>, or outside the table";
    }
    if (message.equals("Start tag for \u201Ctable\u201D seen but the previous \u201Ctable\u201D is still open.")) {
      return "a browser closes the open table at a <table> directly inside it, outside any cell; put the nested"
          + " table in a <td> or <th>";
    }
    if (message.equals("No space between attributes.")) {
      return "two attributes are written without a space between them, which a browser reads only by error"
          + " recovery; write a space between the attributes";
    }
    return "the HTML parser reports \"" + message + "\" here: a browser reads this markup only by error recovery,"
        + " where browsers can differ; Tessera accepts only markup that parses without errors, so fix the markup";
  }

  /** For a problem the parsers report: which scripting mode alone reports it, if one does. */
  private static String scripting(List<ParserState> states) {
    for (ParserState s : states) {
      if (s.scripting()) {
        return "";
      }
    }
    return states.isEmpty() ? "" : " (only with scripting off)";
  }

  /** What is written at the hole: the value, or for an attribute, the attribute's value. */
  private static String value(Finding f) {
    if (f.escaper == null) {
      return "this value";
    }
    switch (f.escaper) {
      case TEXT:
        return "this value";
      case JSON:
      case JAVASCRIPT:
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

  /** Which markup in a value closes {@code detail} ({@code ContentKinds.closer}), in parentheses, or "". */
  private static String closedBy(String detail) {
    if (detail == null) {
      return "";
    }
    if (detail.equals("an open <p>")) {
      return " (a <p>, <div>, list, table or heading in the value ends it)";
    }
    if (detail.matches("an open <h[1-6]>")) {
      return " (a heading in the value ends it)";
    }
    if (detail.equals("an open <a>")) {
      return " (a link in the value ends it)";
    }
    return "";
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
      default:
        return head + "; insert it in an element such as <div> in the body";
    }
  }

  /**
   * Fragment calls in words: {@code first} inserts {@code a :: x}, which at {@code a.html:3:5}
   * inserts {@code b :: y}, ... ({@code first} is where the first call is).
   */
  private static String chain(List<Finding.Step> steps, String first) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < steps.size(); i++) {
      Finding.Step s = steps.get(i);
      sb.append(i == 0 ? first + " inserts " : ", which at " + s.at + " inserts ").append(s.fragment);
    }
    return sb.toString();
  }

  private static String structure(Finding f) {
    String who;
    if (f.cycle.size() > 1) {
      // Mutual recursion: the whole cycle, each call with its position.
      Finding.Step last = f.cycle.get(f.cycle.size() - 1);
      StringBuilder sb = new StringBuilder("fragments call each other in a cycle: ").append(last.fragment);
      for (Finding.Step s : f.cycle) {
        sb.append(" calls ").append(s.fragment).append(" (at ")
            .append(s.at).append(')').append(s == last ? "" : ", which");
      }
      who = sb.toString();
    } else if (f.cycle.size() == 1) {
      who = "fragment " + f.cycle.get(0).fragment + " calls itself (at " + f.cycle.get(0).at + ")";
    } else {
      who = f.fragment == null ? "this fragment calls itself" : "fragment " + f.fragment + " calls itself"
          + (f.inner == null ? "" : " (at " + f.inner + ")");
    }
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
      case LINKED_HTML_MESSAGE:
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
  static String element(OpenElement e) {
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

  /** Which scripting mode alone rejects it, when only one of the two parsers does. */
  private static String scriptingOnly(List<ParserState> states, int parsers) {
    if (states.size() != 1 || parsers != 2) {
      return "";
    }
    return states.get(0).scripting() ? " (only with scripting on)" : " (only with scripting off)";
  }
}
