package dev.tessera.compiler.html;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.DiagnosticSink;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.TemplateSource;
import dev.tessera.compiler.html.MarkupTokenizer.Handler;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Comment;
import dev.tessera.compiler.model.DocType;
import dev.tessera.compiler.model.Document;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.Node;
import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.model.Text;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses a template into a {@link Document} using {@link MarkupTokenizer} (non-validating,
 * non-normalizing, HTML-aware for void elements and raw-text elements). Records exact
 * positions for every element, attribute name and attribute value, decodes character
 * references in attribute values ({@code Attribute.value}) while keeping the raw form, and
 * classifies every text node's {@code OutputContext} via {@link Contexts}.
 *
 * <p>The content of {@code <script>}, {@code <style>}, {@code <textarea>} and {@code <title>}
 * is a single raw {@link Text} node: markup inside it is not parsed.
 *
 * <p>The content of {@code <xmp>}, {@code <iframe>}, {@code <noembed>}, {@code <noframes>} and
 * {@code <noscript>} is parsed as markup, but a browser reads it as raw text (for {@code
 * <noscript>}, when scripting is on) up to the first {@code </name}. That must be the element's
 * own end tag, so both readings end it at the same place. {@code <plaintext>} is rejected.
 *
 * <p>Build errors (reported, then parsing continues): unclosed or mismatched non-void elements,
 * duplicate attributes on one element, malformed markup (including markup a browser would end
 * earlier than the tokenizer does).
 */
public final class HtmlParser {

  private static final Set<String> RAW_TEXT_ELEMENTS = Backports.setOf("script", "style", "textarea", "title");
  private static final Pattern ELEMENT_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.:-]*");
  private static final Map<String, String> NAMED_REFERENCES = new HashMap<>();

  static {
    String[] refs = {"amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "nbsp", " "};
    for (int i = 0; i < refs.length; i += 2) {
      NAMED_REFERENCES.put(refs[i], refs[i + 1]);
    }
  }

  /**
   * True if the content of an element named {@code name} (any case) is parsed as one raw text
   * node ({@code <script>}, {@code <style>}, {@code <textarea>}, {@code <title>}): a browser
   * reads it as text only inside that element, never as markup elsewhere.
   */
  public static boolean isRawText(String name) {
    return RAW_TEXT_ELEMENTS.contains(name.toLowerCase(Locale.ROOT));
  }

  public Document parse(TemplateSource source, DiagnosticSink diagnostics) {
    char[] chars = source.content().toCharArray();
    Builder builder = new Builder(source, chars, diagnostics);
    try {
      new MarkupTokenizer(chars, builder).run();
      builder.finish(true);
    } catch (MarkupTokenizer.MarkupException e) {
      diagnostics.error(builder.position(e.offset), "malformed markup: " + describe(e.getMessage()));
      builder.finish(false);
    }
    return new Document(source, builder.topLevel);
  }

  /** The message's first line, at most 80 chars. */
  private static String describe(String m) {
    int nl = m.indexOf('\n');
    if (nl >= 0) {
      m = m.substring(0, nl);
    }
    return m.length() > 80 ? m.substring(0, 80) + "..." : m;
  }

  /**
   * Index of a {@code >} before the last char of {@code outer}, a CDATA section, DOCTYPE, XML
   * declaration or processing instruction as the tokenizer read it, or -1. A browser ends each of
   * these at its first {@code >} (outside svg and math, a CDATA section is a bogus comment; a
   * {@code <?...>} is a processing instruction or, for some targets, a bogus comment), quotes and
   * brackets included, so the contexts after an earlier {@code >} would differ from Tessera's.
   */
  /*@ // The first '>' among all but the last char of outer (docs/rule-contracts.md, earlyEnd).
    @ normal_behavior
    @   requires outer != null;
    @   ensures -1 <= \result && \result + 1 < outer.length() || \result == -1;
    @   ensures \result >= 0 ==> outer.chars[\result] == '>';
    @   ensures (\forall int k; 0 <= k && k < (\result < 0 ? outer.length() - 1 : \result);
    @       outer.chars[k] != '>');
    @ pure
    @*/
  static int earlyEnd(String outer) {
    int n = outer.length();
    //@ maintaining 0 <= i && (i < n || i == 0);
    //@ maintaining (\forall int k; 0 <= k && k < i && k + 1 < n; outer.chars[k] != '>');
    //@ loop_assigns i;
    //@ decreases n - i;
    for (int i = 0; i + 1 < n; i++) {
      if (outer.charAt(i) == '>') {
        return i;
      }
    }
    return -1;
  }

  /*@ // A bogus comment (for '<?', a processing instruction or a bogus comment) starts at t[i]:
    @ // '<' then '!' or '?', or "</" not followed by an ASCII letter or '>' (the end of t counts as neither).
    @ public normal_behavior
    @   ensures \result <==> (0 <= i && i + 1 < t.length && t[i] == '<'
    @       && (t[i + 1] == '!' || t[i + 1] == '?'
    @           || (t[i + 1] == '/' && !(i + 2 < t.length
    @               && ('a' <= t[i + 2] && t[i + 2] <= 'z' || 'A' <= t[i + 2] && t[i + 2] <= 'Z'
    @                   || t[i + 2] == '>')))));
    @ public static model no_state helper boolean bogusAt(\string t, int i);
    @*/

  /**
   * Index in {@code text}, element text the tokenizer did not read as markup, of a {@code <!},
   * {@code <?} or {@code </} not followed by an ASCII letter or {@code >}, or -1. A browser starts
   * a bogus comment there (for {@code <?}, a processing instruction or a bogus comment) that ends at
   * the first {@code >}, hiding markup Tessera reads.
   */
  /*@ // The first index where bogusAt holds (docs/rule-contracts.md, bogusComment).
    @ normal_behavior
    @   requires text != null;
    @   ensures \result == -1 || 0 <= \result && \result < text.length();
    @   ensures \result >= 0 ==> bogusAt(text.chars, \result);
    @   ensures (\forall int k; 0 <= k && k < (\result < 0 ? text.length() : \result);
    @       !bogusAt(text.chars, k));
    @ pure
    @*/
  static int bogusComment(CharSequence text) {
    //@ maintaining 0 <= i && (i < text.length() || i == 0);
    //@ maintaining (\forall int k; 0 <= k && k < i; !bogusAt(text.chars, k));
    //@ loop_assigns i;
    //@ decreases text.length() - i;
    for (int i = 0; i + 1 < text.length(); i++) {
      if (text.charAt(i) != '<') {
        continue;
      }
      char c = text.charAt(i + 1);
      if (c == '!' || c == '?') {
        return i;
      }
      if (c == '/') {
        char d = i + 2 < text.length() ? text.charAt(i + 2) : ' ';
        if (!(d >= 'a' && d <= 'z' || d >= 'A' && d <= 'Z' || d == '>')) {
          return i;
        }
      }
    }
    return -1;
  }

  /** The build error for the {@link #bogusComment} at {@code i} of {@code text}. */
  static String bogusCommentMessage(CharSequence text, int i) {
    char c = text.charAt(i + 1);
    String what = c == '!' ? "'<!'" : c == '?' ? "'<?'" : "'</' not followed by a letter";
    return "malformed markup: " + what + " starts a comment in a browser that ends at the first '>':"
        + " write &lt; for a literal '<'";
  }

  /**
   * Index in the CDATA section {@code outer} of the first {@code [[} or {@code [(}, which {@code
   * TextInlining} would render as an expression, or -1. Outside svg and math a browser reads the
   * section as a comment, and inside them as text that does not decode {@code &lt;}; in neither is
   * the value element text, so no escaping fits.
   */
  /*@ // The first '[' followed by '[' or '(' (docs/rule-contracts.md, cdataHole).
    @ normal_behavior
    @   requires outer != null;
    @   ensures -1 <= \result && \result + 1 < outer.length() || \result == -1;
    @   ensures \result >= 0 ==> outer.chars[\result] == '['
    @       && (outer.chars[\result + 1] == '[' || outer.chars[\result + 1] == '(');
    @   ensures (\forall int k; 0 <= k && k + 1 < outer.length() && (\result < 0 || k < \result);
    @       !(outer.chars[k] == '[' && (outer.chars[k + 1] == '[' || outer.chars[k + 1] == '(')));
    @ pure
    @*/
  static int cdataHole(String outer) {
    int n = outer.length();
    //@ maintaining 0 <= i && (i < n || i == 0);
    //@ maintaining (\forall int k; 0 <= k && k < i && k + 1 < n;
    //@     !(outer.chars[k] == '[' && (outer.chars[k + 1] == '[' || outer.chars[k + 1] == '(')));
    //@ loop_assigns i;
    //@ decreases n - i;
    for (int i = 0; i + 1 < n; i++) {
      if (outer.charAt(i) == '[' && (outer.charAt(i + 1) == '[' || outer.charAt(i + 1) == '(')) {
        return i;
      }
    }
    return -1;
  }

  /** The build error when {@link #cdataHole} finds an expression. */
  static final String CDATA_HOLE_MESSAGE =
      "malformed markup: [[...]] and [(...)] are not supported inside a CDATA section: outside <svg> and <math>"
          + " a browser reads the section as a comment, and inside them it shows &lt; as written; write the text"
          + " without <![CDATA[";

  /** The build error for {@code outer} when {@link #earlyEnd} finds a {@code >}. */
  static String earlyEndMessage(String outer) {
    if (outer.startsWith("<![")) {
      return "malformed markup: '>' inside a CDATA section is not supported, even in <svg> and <math>: elsewhere a"
          + " browser ends it at the first '>'; write the text without <![CDATA[, with '>' as &gt;";
    }
    if (outer.startsWith("<?")) {
      return "malformed markup: '>' inside '<?...?>' is not supported: a browser ends it at the first '>';"
          + " remove the '>'";
    }
    return "malformed markup: '>' inside <!DOCTYPE> is not supported: a browser ends it at the first '>';"
        + " write <!DOCTYPE html>";
  }

  /**
   * Decodes HTML character references: numeric ({@code &#106;}, {@code &#x6A;}, semicolon
   * optional) and the named references {@code amp lt gt quot apos nbsp} (semicolon required).
   * Unknown named references are left as written; invalid code points become U+FFFD.
   */
  public static String decode(String raw) {
    if (raw.indexOf('&') < 0) {
      return raw;
    }
    StringBuilder out = new StringBuilder(raw.length());
    int i = 0;
    while (i < raw.length()) {
      char c = raw.charAt(i);
      if (c != '&') {
        out.append(c);
        i++;
        continue;
      }
      int end = decodeOne(raw, i, out);
      if (end < 0) {
        out.append('&');
        i++;
      } else {
        i = end;
      }
    }
    return out.toString();
  }

  /** Decodes the reference at {@code amp}; returns the index after it, or -1 if none. */
  private static int decodeOne(String s, int amp, StringBuilder out) {
    int i = amp + 1;
    if (i < s.length() && s.charAt(i) == '#') {
      i++;
      boolean hex = i < s.length() && (s.charAt(i) == 'x' || s.charAt(i) == 'X');
      if (hex) {
        i++;
      }
      int start = i;
      long cp = 0;
      while (i < s.length() && Character.digit(s.charAt(i), hex ? 16 : 10) >= 0) {
        if (cp <= 0x10FFFF) {
          cp = cp * (hex ? 16 : 10) + Character.digit(s.charAt(i), hex ? 16 : 10);
        }
        i++;
      }
      if (i == start) {
        return -1;
      }
      if (i < s.length() && s.charAt(i) == ';') {
        i++;
      }
      boolean valid = cp > 0 && cp <= 0x10FFFF && !(cp >= 0xD800 && cp <= 0xDFFF);
      out.appendCodePoint(valid ? (int) cp : 0xFFFD);
      return i;
    }
    int semi = s.indexOf(';', i);
    if (semi < 0) {
      return -1;
    }
    String replacement = NAMED_REFERENCES.get(s.substring(i, semi));
    if (replacement == null) {
      return -1;
    }
    out.append(replacement);
    return semi + 1;
  }

  /**
   * The offset in {@code comment} where a browser ends it before its last {@code >}, or -1.
   * {@code comment} runs from {@code <!--} through the first {@code -->} at least 7 chars in, as
   * the tokenizer reads it. The HTML tokenizer also ends a comment at {@code <!-->}, at {@code
   * <!--->} and at a {@code --!>} whose dashes come after {@code <!--}; any of these in {@code
   * comment} means a browser reads the markup after it differently (offset 0 for the first two).
   */
  /*@ normal_behavior
    @   requires comment.length() >= 4 && comment.chars[0] == '<' && comment.chars[1] == '!'
    @       && comment.chars[2] == '-' && comment.chars[3] == '-';
    @   ensures -1 <= \result && \result < comment.length();
    @   // -1 exactly when a browser emits no comment token before the last char. Strings of 4 to 6
    @   // chars, which the tokenizer never delimits, are read to their end, so <!--> and <!---> give 0.
    @   ensures \result == -1 <==> (\forall int j; 4 < j && j <= (comment.length() < 7 ? comment.length() : comment.length() - 1);
    @       commentRun(comment, j) != 10);
    @   // and then a comment ending in "-->" (as the tokenizer delimits them) ends at its last char
    @   ensures \result == -1 && comment.length() >= 7 && comment.chars[comment.length() - 3] == '-'
    @       && comment.chars[comment.length() - 2] == '-' && comment.chars[comment.length() - 1] == '>'
    @       ==> commentRun(comment, comment.length()) == 10;
    @   // the same in the syntactic form (docs/rule-contracts.md, section 2): it does not start with
    @   // "<!-->" or "<!--->", and has no "--!>" from index 4 on whose '>' is before the last char
    @   ensures \result == -1 && comment.length() >= 7 ==> comment.chars[4] != '>'
    @       && !(comment.chars[4] == '-' && comment.chars[5] == '>');
    @   ensures \result == -1 ==> (\forall int p; 4 <= p && p + 4 < comment.length();
    @       !(comment.chars[p] == '-' && comment.chars[p + 1] == '-' && comment.chars[p + 2] == '!'
    @           && comment.chars[p + 3] == '>'));
    @ pure
    @*/
  static int earlyCommentEnd(String comment) {
    int n = comment.length();
    int state = COMMENT_START;
    int last = n < 7 ? n : n - 1;
    //@ maintaining 4 <= k && (k <= last || k == 4);
    //@ maintaining 0 <= state && state <= 9 && state == commentRun(comment, k);
    //@ maintaining (\forall int j; 4 <= j && j <= k; 0 <= commentRun(comment, j) && commentRun(comment, j) <= 9);
    //@ loop_assigns k, state;
    //@ decreases n - k;
    for (int k = 4; k < last; k++) {
      char c = comment.charAt(k);
      //@ assert c == comment.chars[k];
      int next = commentStep(state, c);
      if (next == COMMENT_EMIT) {
        //@ assert commentRun(comment, k + 1) == COMMENT_EMIT;
        // <!--> and <!---> end in the comment start states; otherwise "--!>" (or "-->") ends it
        return k <= 5 ? 0 : state == COMMENT_END_BANG ? k - 3 : k - 2;
      }
      state = next;
    }
    //@ assert n >= 7 ==> commentRun(comment, 4) == 0 && commentRun(comment, 5) <= 9 && commentRun(comment, 6) <= 9;
    //@ assert n >= 7 ==> commentRun(comment, 5) == whatwgComment(0, comment.chars[4]);
    //@ assert n >= 7 ==> commentRun(comment, 6) == whatwgComment(commentRun(comment, 5), comment.chars[5]);
    //@ assert n >= 7 ==> commentEndsAtDashDashGt(commentRun(comment, n - 3));
    //@ assert (\forall int p; 4 <= p && p + 4 < n; commentEndsAtDashDashBangGt(commentRun(comment, p)));
    //@ assert (\forall int p; 4 <= p && p + 4 < n && comment.chars[p] == '-' && comment.chars[p + 1] == '-'
    //@     && comment.chars[p + 2] == '!' && comment.chars[p + 3] == '>' ==> commentRun(comment, p + 4) == 10);
    return -1;
  }

  // The WHATWG comment states (HTML 13.2.5.43-52) after "<!--", and COMMENT_EMIT where a browser
  // emits the comment token.
  private static final int COMMENT_START = 0;
  private static final int COMMENT_START_DASH = 1;
  private static final int COMMENT_TEXT = 2;
  private static final int COMMENT_LT = 3;
  private static final int COMMENT_LT_BANG = 4;
  private static final int COMMENT_LT_BANG_DASH = 5;
  private static final int COMMENT_LT_BANG_DASH_DASH = 6;
  private static final int COMMENT_END_DASH = 7;
  private static final int COMMENT_END = 8;
  private static final int COMMENT_END_BANG = 9;
  private static final int COMMENT_EMIT = 10;

  /*@ // WHATWG HTML 13.2.5.43-52: the comment state after consuming c in state s, with "reconsume in
    @ // state X" followed through. 10 is where the comment token is emitted. No state ends the
    @ // comment at any other char.
    @ public normal_behavior
    @   ensures \result == (
    @       // 43 comment start: '-' -> start dash, '>' -> emit (abrupt-closing-of-empty-comment)
    @       s == 0 ? (c == '-' ? 1 : c == '>' ? 10 : whatwgComment(2, c))
    @       // 44 comment start dash: '-' -> end, '>' -> emit (abrupt), else reconsume in comment
    @     : s == 1 ? (c == '-' ? 8 : c == '>' ? 10 : whatwgComment(2, c))
    @       // 45 comment: '<' -> less-than sign, '-' -> end dash
    @     : s == 2 ? (c == '<' ? 3 : c == '-' ? 7 : 2)
    @       // 46 comment less-than sign: '!' -> bang, '<' stays, else reconsume in comment
    @     : s == 3 ? (c == '!' ? 4 : c == '<' ? 3 : whatwgComment(2, c))
    @       // 47 comment less-than sign bang: '-' -> bang dash, else reconsume in comment
    @     : s == 4 ? (c == '-' ? 5 : whatwgComment(2, c))
    @       // 48 comment less-than sign bang dash: '-' -> bang dash dash, else reconsume in end dash
    @     : s == 5 ? (c == '-' ? 6 : whatwgComment(7, c))
    @       // 49 comment less-than sign bang dash dash: reconsume in comment end (nested-comment)
    @     : s == 6 ? whatwgComment(8, c)
    @       // 50 comment end dash: '-' -> end, else reconsume in comment
    @     : s == 7 ? (c == '-' ? 8 : whatwgComment(2, c))
    @       // 51 comment end: '>' -> emit, '!' -> end bang, '-' stays, else reconsume in comment
    @     : s == 8 ? (c == '>' ? 10 : c == '!' ? 9 : c == '-' ? 8 : whatwgComment(2, c))
    @       // 52 comment end bang: '-' -> end dash, '>' -> emit (incorrectly-closed-comment), else
    @       // reconsume in comment
    @     : s == 9 ? (c == '-' ? 7 : c == '>' ? 10 : whatwgComment(2, c))
    @     : 10);
    @ public static model no_state helper int whatwgComment(int s, char c);
    @
    @ // The comment state after comment[4..k), from the comment start state; 10 is final.
    @ public normal_behavior
    @   requires 4 <= k && k <= comment.length();
    @   ensures \result == (k == 4 ? 0
    @       : commentRun(comment, k - 1) == 10 ? 10
    @       : whatwgComment(commentRun(comment, k - 1), comment.chars[k - 1]));
    @ public static model pure helper int commentRun(String comment, int k);
    @
    @ // Lemma: from any state, "--" then ">" ends the comment (and "--" alone does not).
    @ public normal_behavior
    @   requires 0 <= s && s <= 9;
    @   ensures 0 <= whatwgComment(s, '-') && whatwgComment(s, '-') <= 9;
    @   ensures 0 <= whatwgComment(whatwgComment(s, '-'), '-') && whatwgComment(whatwgComment(s, '-'), '-') <= 9;
    @   ensures whatwgComment(whatwgComment(whatwgComment(s, '-'), '-'), '>') == 10;
    @   ensures \result;
    @ public static model pure boolean commentEndsAtDashDashGt(int s) {
    @   return true;
    @ }
    @
    @ // Lemma: from any state, "--!" then ">" ends the comment (and no shorter prefix does).
    @ public normal_behavior
    @   requires 0 <= s && s <= 9;
    @   ensures 0 <= whatwgComment(s, '-') && whatwgComment(s, '-') <= 9;
    @   ensures 0 <= whatwgComment(whatwgComment(s, '-'), '-') && whatwgComment(whatwgComment(s, '-'), '-') <= 9;
    @   ensures 0 <= whatwgComment(whatwgComment(whatwgComment(s, '-'), '-'), '!')
    @       && whatwgComment(whatwgComment(whatwgComment(s, '-'), '-'), '!') <= 9;
    @   ensures whatwgComment(whatwgComment(whatwgComment(whatwgComment(s, '-'), '-'), '!'), '>') == 10;
    @   ensures \result;
    @ public static model pure boolean commentEndsAtDashDashBangGt(int s) {
    @   return true;
    @ }
    @*/

  /** One step of {@link #earlyCommentEnd}'s comment state machine. */
  /*@ private normal_behavior
    @   requires 0 <= state && state <= 9;
    @   ensures \result == whatwgComment(state, c);
    @ pure helper
    @*/
  private static int commentStep(int state, char c) {
    switch (state) {
      case COMMENT_START:
        return c == '-' ? COMMENT_START_DASH : c == '>' ? COMMENT_EMIT : commentText(c);
      case COMMENT_START_DASH:
        return c == '-' ? COMMENT_END : c == '>' ? COMMENT_EMIT : commentText(c);
      case COMMENT_TEXT:
        return commentText(c);
      case COMMENT_LT:
        return c == '!' ? COMMENT_LT_BANG : c == '<' ? COMMENT_LT : commentText(c);
      case COMMENT_LT_BANG:
        return c == '-' ? COMMENT_LT_BANG_DASH : commentText(c);
      case COMMENT_LT_BANG_DASH:
        return c == '-' ? COMMENT_LT_BANG_DASH_DASH : commentText(c); // reconsume in end dash: not '-'
      case COMMENT_LT_BANG_DASH_DASH:
        return commentEnd(c);
      case COMMENT_END_DASH:
        return c == '-' ? COMMENT_END : commentText(c);
      case COMMENT_END:
        return commentEnd(c);
      default: // COMMENT_END_BANG
        return c == '-' ? COMMENT_END_DASH : c == '>' ? COMMENT_EMIT : commentText(c);
    }
  }

  /*@ private normal_behavior
    @   ensures \result == whatwgComment(2, c);
    @ pure helper
    @*/
  private static int commentText(char c) {
    return c == '<' ? COMMENT_LT : c == '-' ? COMMENT_END_DASH : COMMENT_TEXT;
  }

  /*@ private normal_behavior
    @   ensures \result == whatwgComment(8, c);
    @ pure helper
    @*/
  private static int commentEnd(char c) {
    return c == '>' ? COMMENT_EMIT : c == '!' ? COMMENT_END_BANG : c == '-' ? COMMENT_END : commentText(c);
  }

  /**
   * Offset in {@code content} of the first {@code </name} at or after {@code from} that a browser
   * takes as the end tag of a raw-text element called {@code name}, or -1: {@code name} ASCII
   * case-insensitive, then whitespace, {@code /}, {@code >} or the end of {@code content}.
   */
  /*@ normal_behavior
    @   requires 0 <= from && from <= content.length();
    @   ensures -1 <= \result && \result < content.length();
    @   ensures \result == -1 <==> (\forall int i; from <= i && i < content.length(); !endTagAt(content, name, i));
    @   ensures \result != -1 ==> from <= \result && endTagAt(content, name, \result)
    @       && (\forall int i; from <= i && i < \result; !endTagAt(content, name, i));
    @ pure
    @*/
  static int browserEndTag(String content, String name, int from) {
    int n = content.length();
    int len = name.length();
    int i = from;
    //@ maintaining from <= i && i <= n;
    //@ maintaining (\forall int j; from <= j && j < i; !endTagAt(content, name, j));
    //@ loop_assigns i;
    //@ decreases n - i;
    for (; len <= n - 2 - i; i++) {
      //@ assert content.charAt(i) == content.chars[i] && content.charAt(i + 1) == content.chars[i + 1];
      if (content.charAt(i) == '<' && content.charAt(i + 1) == '/' && asciiMatchesIgnoreCase(content, i + 2, name)) {
        int next = i + 2 + len;
        //@ assert next < n ==> content.charAt(next) == content.chars[next];
        if (next == n || endTagDelimiter(content.charAt(next))) {
          //@ assert endTagAt(content, name, i);
          return i;
        }
      }
    }
    //@ assert (\forall int j; i <= j && j < n; !endTagAt(content, name, j));
    return -1;
  }

  /*@ // WHATWG HTML 13.2.5.12, 13.2.5.15 and 13.2.5.18 (RCDATA, RAWTEXT and script data end tag
    @ // name states): after "</" and the element's name, ASCII case-insensitive, whitespace, '/' or
    @ // '>' make it the appropriate end tag. The end of the input counts too here (see
    @ // docs/rule-contracts.md, section 4).
    @ public normal_behavior
    @   ensures \result <==> (0 <= i && i <= content.length() - 2 - name.length()
    @       && content.chars[i] == '<' && content.chars[i + 1] == '/'
    @       && (\forall int k; 0 <= k && k < name.length(); asciiLow(content.chars[i + 2 + k]) == asciiLow(name.chars[k]))
    @       && (i + 2 + name.length() == content.length() || endTagDelimiterSpec(content.chars[i + 2 + name.length()])));
    @ public static model pure helper boolean endTagAt(String content, String name, int i);
    @
    @ public normal_behavior
    @   ensures \result == ('A' <= c && c <= 'Z' ? (char) (c + 32) : c);
    @ public static model no_state helper char asciiLow(char c);
    @
    @ public normal_behavior
    @   ensures \result <==> (c == '\t' || c == '\n' || c == '\f' || c == '\r' || c == ' ' || c == '/' || c == '>');
    @ public static model no_state helper boolean endTagDelimiterSpec(char c);
    @*/

  /*@ private normal_behavior
    @   ensures \result <==> endTagDelimiterSpec(c);
    @ pure helper
    @*/
  private static boolean endTagDelimiter(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\f' || c == '\r' || c == '/' || c == '>';
  }

  /** Whether {@code s} has {@code part} at {@code offset}, up to ASCII case. */
  /*@ normal_behavior
    @   ensures \result <==> (0 <= offset && offset <= s.length() - part.length()
    @       && (\forall int k; 0 <= k && k < part.length(); asciiLow(s.chars[offset + k]) == asciiLow(part.chars[k])));
    @ pure
    @*/
  static boolean asciiMatchesIgnoreCase(String s, int offset, String part) {
    if (offset < 0 || offset > s.length() - part.length()) {
      return false;
    }
    //@ maintaining 0 <= k && k <= part.length();
    //@ maintaining (\forall int j; 0 <= j && j < k; asciiLow(s.chars[offset + j]) == asciiLow(part.chars[j]));
    //@ loop_assigns k;
    //@ decreases part.length() - k;
    for (int k = 0; k < part.length(); k++) {
      //@ assert s.charAt(offset + k) == s.chars[offset + k] && part.charAt(k) == part.chars[k];
      if (asciiLower(s.charAt(offset + k)) != asciiLower(part.charAt(k))) {
        return false;
      }
    }
    return true;
  }

  /*@ private normal_behavior
    @   ensures \result == asciiLow(c);
    @ pure helper
    @*/
  private static char asciiLower(char c) {
    return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
  }

  /** An element whose end tag has not been seen yet. */
  private static final class Open {
    final String name;
    final SourcePos pos;
    final List<Attribute> attributes = new ArrayList<>();
    final Set<String> attributeNames = new HashSet<>();
    final List<Node> children = new ArrayList<>();
    OutputContext textContext = OutputContext.TEXT;
    /** For a {@link Contexts#browserRawText} element, where a browser ends it; -1 if nowhere. */
    int browserEnd = -1;

    Open(String name, SourcePos pos) {
      this.name = name;
      this.pos = pos;
    }

    Element toElement(boolean selfClosing, boolean hasEndTag) {
      return new Element(name, attributes, children, selfClosing, hasEndTag, pos);
    }
  }

  private static final class Builder implements Handler {
    private final String file;
    private final String content;
    /** The buffer the tokenizer is reading; see {@link #matchEndTagCase}. */
    private final char[] chars;
    private final DiagnosticSink diagnostics;
    private final int[] lineStarts;
    final List<Node> topLevel = new ArrayList<>();
    private final Deque<Open> stack = new ArrayDeque<>();

    /** Start tag being read (between startTag and its end). */
    private Open tag;

    /** Raw-text element whose content we are skipping, its content offset and position. */
    private Open raw;
    private int rawStart;
    /** Offset of the first {@code </name} that a browser would take as the raw element's end. */
    private int rawEnd;

    private final StringBuilder text = new StringBuilder();
    private SourcePos textPos;
    private int textStart;

    Builder(TemplateSource source, char[] chars, DiagnosticSink diagnostics) {
      this.file = source.path();
      this.content = source.content();
      this.chars = chars;
      this.diagnostics = diagnostics;
      this.lineStarts = source.lineStarts();
    }

    /** Line and column of {@code offset}: only LF starts a line, every other char is one column. */
    SourcePos position(int offset) {
      int lo = 0;
      int hi = lineStarts.length - 1;
      while (lo < hi) {
        int mid = (lo + hi + 1) >>> 1;
        if (lineStarts[mid] <= offset) {
          lo = mid;
        } else {
          hi = mid - 1;
        }
      }
      return new SourcePos(file, lo + 1, offset - lineStarts[lo] + 1);
    }

    private String str(int start, int end) {
      return new String(chars, start, end - start);
    }

    private List<Node> children() {
      return stack.isEmpty() ? topLevel : stack.peek().children;
    }

    private OutputContext textContext() {
      return stack.isEmpty() ? OutputContext.TEXT : stack.peek().textContext;
    }

    private void flushText() {
      if (text.length() != 0) {
        int bogus = bogusComment(text);
        if (bogus >= 0) {
          diagnostics.error(position(textStart + bogus), bogusCommentMessage(text, bogus));
        }
        children().add(new Text(text.toString(), textContext(), textPos));
        text.setLength(0);
      }
    }

    @Override
    public void text(int start, int end) {
      if (raw != null) {
        return;
      }
      if (text.length() == 0) {
        textPos = position(start);
        textStart = start;
      }
      text.append(chars, start, end - start);
    }

    @Override
    public void comment(int start, int end) {
      if (raw != null) {
        return;
      }
      flushText();
      String comment = str(start, end);
      int early = earlyCommentEnd(comment);
      if (early >= 0) {
        diagnostics.error(position(start + early), early == 0
            ? "malformed markup: a comment cannot start with '<!-->' or '<!--->' (a browser ends it there):"
                + " put a space after '<!--'"
            : "malformed markup: '--!>' ends a comment in a browser: end the comment with '-->' or remove the '!'");
      }
      children().add(new Comment(comment, position(start)));
    }

    @Override
    public void cdata(int start, int end) {
      if (raw != null) {
        return;
      }
      flushText();
      checkEarlyEnd(start, end);
      int hole = cdataHole(str(start, end));
      if (hole >= 0) {
        diagnostics.error(position(start + hole), CDATA_HOLE_MESSAGE);
      }
      children().add(new Text(str(start, end), textContext(), position(start)));
    }

    @Override
    public void declaration(int start, int end) {
      if (raw != null) {
        return;
      }
      flushText();
      checkEarlyEnd(start, end);
      children().add(new DocType(str(start, end), position(start)));
    }

    private void checkEarlyEnd(int start, int end) {
      String outer = str(start, end);
      int gt = earlyEnd(outer);
      if (gt >= 0) {
        diagnostics.error(position(start + gt), earlyEndMessage(outer));
      }
    }

    @Override
    public void startTag(int lt, int nameEnd) {
      if (raw != null) {
        return;
      }
      flushText();
      String name = str(lt + 1, nameEnd);
      tag = new Open(name, position(lt));
      if (name.equalsIgnoreCase("plaintext")) {
        diagnostics.error(tag.pos, "<" + name + "> is not supported: a browser treats the rest of the page as plain"
            + " text; use <pre>");
      }
      if (!ELEMENT_NAME.matcher(name).matches()) {
        diagnostics.error(tag.pos, "malformed markup: invalid element name '" + name
            + "': element names start with a letter and use letters, digits, '-', '_', ':' and '.'");
      }
    }

    @Override
    public void attribute(int nameStart, int nameEnd, int valueStart, int valueEnd, char quote) {
      if (raw != null || tag == null) {
        return;
      }
      String name = str(nameStart, nameEnd);
      SourcePos namePos = position(nameStart);
      if (name.chars().anyMatch(ch -> ch == '<' || ch == '"' || ch == '\'' || ch == '=')) {
        diagnostics.error(namePos, "malformed markup: invalid attribute name '" + name
            + "': attribute names cannot contain <, quotes or =");
      }
      if (!tag.attributeNames.add(name.toLowerCase(Locale.ROOT))) {
        diagnostics.error(namePos, "duplicate attribute '" + name + "' on <" + tag.name + ">: remove one of them");
      }
      Attribute attribute;
      if (valueStart < 0) {
        attribute = new Attribute(name, null, null, (char) 0, namePos, null);
      } else {
        String rawValue = str(valueStart, valueEnd);
        attribute = new Attribute(name, decode(rawValue), rawValue, quote, namePos, position(valueStart));
      }
      tag.attributes.add(attribute);
    }

    @Override
    public void selfClosedTagEnd(boolean minimized) {
      if (raw != null || tag == null) {
        return;
      }
      String lower = tag.name.toLowerCase(Locale.ROOT);
      if (minimized && (lower.equals("script") || lower.equals("style"))) {
        // A browser treats <script/> as an open tag, so the template text after it would run as
        // script (or style) while Tessera escaped it for TEXT. <textarea/>, <title/> and the other
        // raw text elements are checked where Tessera writes them (TemplateGenerator).
        diagnostics.error(tag.pos, "<" + lower + "/> is not self-closing in HTML: the browser treats everything"
            + " after it as " + lower + "; write <" + lower + " ...></" + lower + ">");
      }
      children().add(tag.toElement(minimized, false));
      tag = null;
    }

    @Override
    public void openTagEnd(int gt) {
      if (raw != null || tag == null) {
        return;
      }
      Open open = tag;
      tag = null;
      open.textContext = Contexts.text(open.toElement(false, false));
      stack.push(open);
      if (RAW_TEXT_ELEMENTS.contains(open.name.toLowerCase(Locale.ROOT))) {
        raw = open;
        rawStart = gt + 1;
        rawEnd = browserEndTag(content, open.name, rawStart);
        matchEndTagCase(open.name);
      } else if (Contexts.browserRawText(open.name)) {
        open.browserEnd = browserEndTag(content, open.name, gt + 1);
      }
    }

    /**
     * The tokenizer ends script and style content only at {@code </name>} spelled in the start
     * tag's case, while browsers match the end tag case-insensitively ({@code <Script>...</sCrIpT>}).
     * Rewrites the name of the end tag a browser would use, in the tokenizer's not-yet-read input,
     * to the start tag's case. Lengths and offsets are unchanged and node text comes from {@link
     * #content}, so only where the tokenizer stops (and the text its messages quote) changes.
     */
    private void matchEndTagCase(String name) {
      if (rawEnd >= 0) {
        name.getChars(0, name.length(), chars, rawEnd + 2);
      }
    }

    @Override
    public boolean rawText() {
      return raw != null;
    }

    @Override
    public void endTag(int lt, int nameEnd) {
      String name = str(lt + 2, nameEnd);
      SourcePos closePos = position(lt);
      if (raw != null) {
        if (!raw.name.equalsIgnoreCase(name)) {
          return;
        }
        if (lt != rawEnd) {
          // The tokenizer read markup (a comment, an attribute value) that hid an earlier end
          // tag; a browser would end the element there, so the contexts after it would be wrong.
          hiddenEndTag(raw.name, rawEnd);
        }
        endRaw(lt);
      }
      flushText();
      boolean open = stack.stream().anyMatch(o -> o.name.equalsIgnoreCase(name));
      if (!open) {
        diagnostics.error(closePos, "end tag </" + name + "> does not match any open element: remove it or add the"
            + " matching <" + name + ">");
        return;
      }
      while (true) {
        Open top = stack.pop();
        if (top.name.equalsIgnoreCase(name)) {
          if (top.browserEnd >= 0 && top.browserEnd != lt) {
            // A comment, attribute value, nested raw text or a nested <noscript> hid the end tag
            // a browser uses: it would end the element there and parse the rest as markup.
            hiddenEndTag(top.name, top.browserEnd);
          }
          children().add(top.toElement(false, true));
          return;
        }
        diagnostics.error(top.pos, "element <" + top.name + "> is not closed (found </" + name + "> at "
            + closePos.line() + ":" + closePos.column() + "): add </" + top.name + ">");
        children().add(top.toElement(false, false));
      }
    }

    private void hiddenEndTag(String name, int offset) {
      String fix = Contexts.browserRawText(name)
          ? "a browser ends the element there, so the markup after it would be read differently; remove it or"
              + " move it outside the element"
          : "escape it (in a script string, write '<\\/" + name + "')";
      diagnostics.error(position(offset), "malformed markup: content of <" + name + "> contains '</" + name
          + "' before its end tag: " + fix);
    }

    /** Ends the current raw-text element's content at {@code end} (exclusive). */
    private void endRaw(int end) {
      if (end > rawStart) {
        String body = content.substring(rawStart, end);
        int comment = body.indexOf("<!--");
        if (raw.name.equalsIgnoreCase("script") && comment >= 0) {
          // Browsers apply script-data escape rules after "<!--" ("<script" inside it hides
          // the next "</script>"); the tokenizer does not. Fail closed.
          diagnostics.error(position(rawStart + comment),
              "malformed markup: '<!--' inside <script> is not supported: remove it (it is not needed in HTML5)");
        }
        raw.children.add(new Text(body, raw.textContext, position(rawStart)));
      }
      raw = null;
    }

    /** Closes everything still open at the end of input; reports them if parsing completed. */
    void finish(boolean report) {
      if (raw != null) {
        endRaw(content.length());
      }
      flushText();
      while (!stack.isEmpty()) {
        Open top = stack.pop();
        if (report) {
          diagnostics.error(top.pos, "element <" + top.name + "> is not closed: add </" + top.name + ">");
        }
        children().add(top.toElement(false, false));
      }
    }
  }
}
