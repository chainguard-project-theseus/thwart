package dev.tessera.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntConsumer;

/**
 * Contextual escapers. The compiler picks exactly one of these for every expression output
 * site based on its {@code OutputContext}; templates cannot choose. HTML methods treat
 * {@code null} as the empty string; JSON methods serialize it as {@code null}.
 *
 * <p>Conversion to text: {@code null} becomes {@code ""}; everything else uses
 * {@link String#valueOf(Object)}. Safe types are only honoured where noted.
 *
 * <p>HTML escapers also replace U+0000 and unpaired surrogates with U+FFFD so the output is
 * always well-formed text.
 */
public final class Escapers {
  private Escapers() {}

  /** Replacement for URLs whose scheme is not allowed. */
  static final String INVALID_URL = "about:invalid#tessera";

  /** Nesting limit for {@link #json}; deeper (or cyclic) values are rejected. */
  private static final int MAX_JSON_DEPTH = 64;

  private static final char[] HEX = "0123456789abcdef".toCharArray();
  //@ private static invariant HEX.length == 16 && (\forall int k; 0 <= k && k < 16;
  //@     ('0' <= HEX[k] && HEX[k] <= '9') || ('a' <= HEX[k] && HEX[k] <= 'f'));

  /** Converts a value to its display text ({@code null} becomes {@code ""}). */
  public static String toText(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  /**
   * Written between two {@code {} characters in element text, so that client-side frameworks
   * that compile the page's DOM as a template (Vue 2/3, petite-vue, AngularJS) never see a
   * {@code {{} interpolation in the value (#248). An empty comment is a node boundary: the text
   * that the page shows, {@code textContent} and copied text are unchanged.
   */
  static final /*@ spec_public @*/ String BRACE_BREAK = "<!---->";
  //@ public static invariant BRACE_BREAK.length() == 7 && BRACE_BREAK.charAt(0) == '<' && BRACE_BREAK.charAt(1) == '!'
  //@     && BRACE_BREAK.charAt(2) == '-' && BRACE_BREAK.charAt(3) == '-' && BRACE_BREAK.charAt(4) == '-'
  //@     && BRACE_BREAK.charAt(5) == '-' && BRACE_BREAK.charAt(6) == '>';

  /**
   * HTML element text (OutputContext.TEXT). Encodes {@code & < > " '}. A {@link SafeHtml}
   * argument is escaped like any other value: {@code t:text} always escapes.
   *
   * <p>Also breaks client-side template delimiters (#248): an empty comment ({@code <!---->})
   * is written between two adjacent {@code {}, before a {@code {} that starts the value and
   * after one that ends it, so neither the value alone nor the value next to static text or
   * another value can form {@code {{}. Values without {@code {} are written as by {@link #attr}.
   */
  public static void text(Output out, Object value) {
    escapeText(out, toText(value));
  }

  /**
   * Text in {@code <title>} or {@code <textarea>} (OutputContext.RCDATA). Encodes
   * {@code & < > " '} like {@link #attr}. A comment cannot be written there (the browser shows it
   * as text), so {@code {{} is not broken; see guide section 7.
   */
  public static void rcdata(Output out, Object value) {
    escapeHtml(out, toText(value));
  }

  /** Quoted attribute value (OutputContext.ATTRIBUTE). Encodes {@code & < > " '}. */
  public static void attr(Output out, Object value) {
    escapeHtml(out, toText(value));
  }

  /**
   * URL-valued attribute such as {@code href}/{@code action} (OutputContext.URL). A
   * {@link TrustedUrl} is attribute-encoded as is. Any other value is attribute-encoded after
   * a scheme check: only {@code http}, {@code https}, {@code mailto}, {@code tel} and
   * scheme-less (relative) URLs pass; anything else is replaced by {@code about:invalid#tessera}.
   */
  public static void url(Output out, Object value) {
    if (value instanceof TrustedUrl) {
      attr(out, ((TrustedUrl) value).url());
      return;
    }
    attr(out, checkedUrl(toText(value)));
  }

  /**
   * Resource URL attribute such as {@code <script src>} (OutputContext.RESOURCE_URL). Only
   * accepts compiler-vetted values: a {@link TrustedUrl}, or a String produced by a link
   * expression with a literal path. The compiler rejects every other expression.
   *
   * <p>As defence in depth a String also goes through the {@link #url} scheme check, and any
   * other type throws {@link IllegalArgumentException}.
   */
  public static void resourceUrl(Output out, Object value) {
    if (value == null) {
      return;
    }
    if (value instanceof TrustedUrl) {
      attr(out, ((TrustedUrl) value).url());
    } else if (value instanceof String) {
      attr(out, checkedUrl((String) value));
    } else {
      throw new IllegalArgumentException(
          "resource URL must be a TrustedUrl or a link, got " + value.getClass().getName());
    }
  }

  /**
   * The {@code content} of a {@code <meta http-equiv="refresh">} (OutputContext.REFRESH, #237).
   * The compiler only lets through {@code "<digits>;url=" + <link with a literal /path>}. The
   * URL part has {@code ; , ' " \}, ASCII whitespace and control characters percent-encoded, so a
   * browser's loose refresh parsing cannot end it early or strip characters from it, and it
   * must then be a same-origin path: one {@code /} not followed by {@code /} or {@code \}.
   * Anything else throws {@link IllegalArgumentException}. The result is attribute-encoded.
   */
  public static void refresh(Output out, Object value) {
    String s = toText(value);
    int sep = s.indexOf(";url=");
    boolean digits = sep > 0;
    for (int i = 0; i < sep && digits; i++) {
      digits = s.charAt(i) >= '0' && s.charAt(i) <= '9';
    }
    if (!digits) {
      throw new IllegalArgumentException("refresh content must be <seconds>;url=<path>");
    }
    StringBuilder sb = new StringBuilder(s.length() + 16).append(s, 0, sep + ";url=".length());
    int urlStart = sb.length();
    for (int i = sep + ";url=".length(); i < s.length(); i++) {
      char c = s.charAt(i);
      if (c <= 0x20 || c == 0x7f || ";,'\"\\".indexOf(c) >= 0) {
        sb.append('%').append(Character.toUpperCase(HEX[c >> 4])).append(Character.toUpperCase(HEX[c & 0xF]));
      } else {
        sb.append(c);
      }
    }
    if (sb.length() == urlStart || sb.charAt(urlStart) != '/'
        || (sb.length() > urlStart + 1 && sb.charAt(urlStart + 1) == '/')) {
      throw new IllegalArgumentException("refresh URL must be a same-origin path starting with a single '/'");
    }
    escapeHtml(out, sb.toString());
  }

  /** Raw HTML (t:utext and {@code [(...)]}). Only {@link SafeHtml} is accepted. */
  public static void html(Output out, SafeHtml value) {
    if (value != null) {
      out.write(value.html());
    }
  }

  /**
   * A bundle message rendered as HTML ({@code t:utext="#{key(args)}"}, {@code [(#{...})]}; #236).
   * {@code checked} holds the key's values in every bundle file the compiler checked as HTML. The
   * arguments are HTML-escaped (numbers and dates are kept for MessageFormat) and the message is looked up; the result is
   * returned as HTML only if it contains no {@code <}, or if it is exactly what one of the
   * {@code checked} patterns produces (as written, or formatted by {@link MessageFormat} in the
   * context's locale with the escaped arguments). Anything else (a bundle the build did not see,
   * a database-backed message source, a file changed after the build, a missing key) is escaped
   * as text. {@code null} becomes {@code ""}.
   */
  public static String htmlMessage(RenderContext ctx, String key, Object[] args, String[] checked) {
    Object[] escaped = htmlMessageArgs(args);
    String message = ctx.messages().get(key, escaped);
    if (message == null) {
      return "";
    }
    if (message.replace(BRACE_BREAK, "").indexOf('<') < 0) {
      // cannot open a tag, comment or other markup in text; the only markup is the empty
      // comments an argument's braces got (#248)
      return message;
    }
    for (String pattern : checked) {
      if (message.equals(pattern)) {
        return message;
      }
      try {
        if (message.equals(new MessageFormat(pattern, ctx.locale()).format(escaped))) {
          return message;
        }
      } catch (IllegalArgumentException e) {
        // an argument does not fit the pattern (e.g. text for {0,number}): not this pattern
      }
    }
    return escapeText(message);
  }

  /**
   * The arguments of {@link #htmlMessage}. The compiler has checked the message's markup and that
   * its placeholders only stand in text, so each argument only has to be escaped as HTML text:
   * every value becomes its escaped {@linkplain #toText text}, with {@code {{} broken as by
   * {@link #text}, except {@link Number} and
   * {@link java.util.Date}, which {@link MessageFormat} formats itself (to digits and locale
   * symbols or names, never markup) and which must stay numbers and dates for {@code {0,number}}
   * and {@code {0,date}}. {@code null} stays {@code null}, as with the escaped form. Returns a new
   * array.
   */
  static Object[] htmlMessageArgs(Object[] args) {
    Object[] escaped = new Object[args.length];
    for (int i = 0; i < args.length; i++) {
      Object a = args[i];
      escaped[i] = a == null || a instanceof Number || a instanceof java.util.Date ? a : escapeText(toText(a));
    }
    return escaped;
  }

  /**
   * JavaScript value inside {@code <script>} (OutputContext.SCRIPT). Serializes as JSON:
   * null, Boolean, Number, CharSequence, Character, Enum (name), java.time values (ISO string),
   * Optional, Collection, array and Map with String keys. Escapes {@code <}, {@code >},
   * {@code &}, {@code '}, U+2028 and U+2029 so the value cannot close the script element, and
   * {@code /}, {@code `} and {@code $} so a string cannot leave a JS comment, regex literal or
   * template literal it was inlined into. Other types throw IllegalArgumentException (the
   * compiler only allows the types above).
   *
   * <p>Numbers are limited to the JDK types ({@code Byte}, {@code Short}, {@code Integer},
   * {@code Long}, {@code Float}, {@code Double}, {@code BigInteger}, {@code BigDecimal}); a
   * non-finite float or double becomes {@code null}, as with {@code JSON.stringify}. The value
   * is fully serialized before anything is written, so a failure writes nothing.
   */
  public static void json(Output out, Object value) {
    StringBuilder sb = new StringBuilder();
    writeJson(sb, value, 0);
    out.write(sb.toString());
  }

  /**
   * Value of a Vue/Alpine bind attribute such as {@code :prop} or {@code x-bind:prop}
   * (OutputContext.SCRIPT_ATTRIBUTE, #246), which the framework evaluates as a JavaScript
   * expression: serialized exactly as {@link #json} (so a string is always a JSON string literal,
   * and {@code null} is {@code null}), then attribute-encoded as {@link #attr}. The value is fully
   * serialized before anything is written, so a failure writes nothing.
   */
  public static void jsonAttr(Output out, Object value) {
    StringBuilder sb = new StringBuilder();
    writeJson(sb, value, 0);
    escapeHtml(out, sb.toString());
  }

  /**
   * Appends script-safe JSON to a private serialization buffer at the supplied nesting depth.
   * Used by generated bean serializers to share this escaper without intermediate object graphs.
   * A failure may leave partial JSON in {@code buffer}; callers must discard it on failure and
   * only publish the completed value. Depth starts at zero and must not be negative.
   *
   * @throws IllegalArgumentException if depth is negative, nesting exceeds the JSON limit,
   *     or the value cannot be serialized
   */
  public static void appendJson(StringBuilder buffer, Object value, int depth) {
    if (depth < 0) {
      throw new IllegalArgumentException("JSON nesting depth must not be negative");
    }
    writeJson(buffer, value, depth);
  }

  // ---------------------------------------------------------------------------------------------
  // HTML
  //
  // The JML below is checked by OpenJML (docs/proofs.md). htmlSafe is the security property of
  // the HTML escapers: in the text they write, no character can end the element text or the
  // double-quoted attribute value it is written into.

  /*@ // A character the HTML escapers copy unchanged.
    @ public normal_behavior
    @   ensures \result <==> (c != '&' && c != '<' && c != '>' && c != '"' && c != '\'' && c != '\0'
    @       && !('\uD800' <= c && c <= '\uDFFF'));
    @ public static model no_state helper boolean htmlPlain(char c);
    @
    @ // One of the five entities the HTML escapers write starts at t[k] and ends before t[to].
    @ public normal_behavior
    @   ensures \result <==> (
    @       (k + 5 <= to && t[k] == '&' && t[k + 1] == 'a' && t[k + 2] == 'm' && t[k + 3] == 'p' && t[k + 4] == ';')
    @       || (k + 4 <= to && t[k] == '&' && t[k + 1] == 'l' && t[k + 2] == 't' && t[k + 3] == ';')
    @       || (k + 4 <= to && t[k] == '&' && t[k + 1] == 'g' && t[k + 2] == 't' && t[k + 3] == ';')
    @       || (k + 6 <= to && t[k] == '&' && t[k + 1] == 'q' && t[k + 2] == 'u' && t[k + 3] == 'o' && t[k + 4] == 't' && t[k + 5] == ';')
    @       || (k + 5 <= to && t[k] == '&' && t[k + 1] == '#' && t[k + 2] == '3' && t[k + 3] == '9' && t[k + 4] == ';'));
    @ public static model no_state helper boolean htmlEntityAt(\string t, \bigint k, \bigint to);
    @
    @ // t[from..to) contains none of < > " ' NUL.
    @ public normal_behavior
    @   ensures \result <==> (\forall int k; from <= k && k < to;
    @       t[k] != '<' && t[k] != '>' && t[k] != '"' && t[k] != '\'' && t[k] != '\0');
    @ public static model no_state helper boolean htmlNoDelimiters(\string t, \bigint from, \bigint to);
    @
    @ // Every & in t[from..to) starts one of the entities above.
    @ public normal_behavior
    @   ensures \result <==> (\forall int k; from <= k && k < to; t[k] == '&' ==> htmlEntityAt(t, k, to));
    @ public static model no_state helper boolean htmlEntitiesOnly(\string t, \bigint from, \bigint to);
    @
    @ // Every surrogate in t[from..to) is half of a pair, so the text is well-formed UTF-16.
    @ public normal_behavior
    @   ensures \result <==> (\forall int j, k; from <= j && j < to && k == j + 1;
    @       ('\uD800' <= t[j] && t[j] <= '\uDBFF' ==> k < to && '\uDC00' <= t[k] && t[k] <= '\uDFFF')
    @       && (k < to && '\uDC00' <= t[k] && t[k] <= '\uDFFF' ==> '\uD800' <= t[j] && t[j] <= '\uDBFF'))
    @       && !(from < to && '\uDC00' <= t[from] && t[from] <= '\uDFFF');
    @ public static model no_state helper boolean htmlPairedSurrogates(\string t, \bigint from, \bigint to);
    @
    @ // The security property of the HTML escapers for the text t[from..to) they wrote.
    @ public normal_behavior
    @   ensures \result <==> htmlNoDelimiters(t, from, to) && htmlEntitiesOnly(t, from, to)
    @       && htmlPairedSurrogates(t, from, to);
    @ public static model no_state helper boolean htmlSafe(\string t, \bigint from, \bigint to);
    @
    @ // Lemma: u keeps t[0..m), so entities in t[o..m) and u[m..e) make every & in u[o..e) an entity.
    @ public normal_behavior
    @   requires 0 <= o && o <= m && m <= t.length && m <= e && e <= u.length;
    @   requires (\forall \bigint k; 0 <= k && k < m; u[k] == t[k]);
    @   requires htmlEntitiesOnly(t, o, m) && htmlEntitiesOnly(u, m, e);
    @   ensures htmlEntitiesOnly(u, o, e);
    @   ensures \result;
    @ public static model pure boolean htmlEntitiesConcat(\string t, \string u, \bigint o, \bigint m, \bigint e) {
    @   return true;
    @ }
    @
    @ // Lemma: u keeps t[0..m), so paired surrogates in t[o..m) and u[m..e) are paired in u[o..e).
    @ public normal_behavior
    @   requires 0 <= o && o <= m && m <= t.length && m <= e && e <= u.length;
    @   requires (\forall \bigint k; 0 <= k && k < m; u[k] == t[k]);
    @   requires htmlPairedSurrogates(t, o, m) && htmlPairedSurrogates(u, m, e);
    @   // One-index forms of the two hypotheses, so the prover sees the pair (m - 1, m).
    @   ensures (\forall int j; o <= j && j < m && j < Integer.MAX_VALUE; '\uD800' <= u[j] && u[j] <= '\uDBFF'
    @       ==> j + 1 < m && '\uDC00' <= u[j + 1] && u[j + 1] <= '\uDFFF');
    @   ensures (\forall int j; m <= j && j < e && j < Integer.MAX_VALUE; '\uD800' <= u[j] && u[j] <= '\uDBFF'
    @       ==> j + 1 < e && '\uDC00' <= u[j + 1] && u[j + 1] <= '\uDFFF');
    @   ensures (\forall int k; o < k && k < m; '\uDC00' <= u[k] && u[k] <= '\uDFFF'
    @       ==> '\uD800' <= u[k - 1] && u[k - 1] <= '\uDBFF');
    @   ensures (\forall int k; m < k && k < e; '\uDC00' <= u[k] && u[k] <= '\uDFFF'
    @       ==> '\uD800' <= u[k - 1] && u[k - 1] <= '\uDBFF');
    @   ensures !(m < e && '\uDC00' <= u[m] && u[m] <= '\uDFFF');
    @   ensures htmlPairedSurrogates(u, o, e);
    @   ensures \result;
    @ public static model pure boolean htmlPairsConcat(\string t, \string u, \bigint o, \bigint m, \bigint e) {
    @   return true;
    @ }
    @
    @ // Element text (#248). t[from..to) contains none of " ' NUL.
    @ public normal_behavior
    @   ensures \result <==> (\forall int k; from <= k && k < to; t[k] != '"' && t[k] != '\'' && t[k] != '\0');
    @ public static model no_state helper boolean htmlNoQuotes(\string t, \bigint from, \bigint to);
    @
    @ // The empty comment BRACE_BREAK starts at t[k] and ends before t[to].
    @ public normal_behavior
    @   ensures \result <==> (k + 7 <= to && t[k] == '<' && t[k + 1] == '!' && t[k + 2] == '-' && t[k + 3] == '-'
    @       && t[k + 4] == '-' && t[k + 5] == '-' && t[k + 6] == '>');
    @ public static model no_state helper boolean htmlCommentAt(\string t, \bigint k, \bigint to);
    @
    @ // Every < and > in t[from..to) is part of an empty comment, the only markup in it.
    @ public normal_behavior
    @   ensures \result <==> (\forall int k; from <= k && k < to;
    @       (t[k] == '<' ==> htmlCommentAt(t, k, to)) && (t[k] == '>' ==> from <= k - 6 && htmlCommentAt(t, k - 6, to)));
    @ public static model no_state helper boolean htmlCommentsOnly(\string t, \bigint from, \bigint to);
    @
    @ // No two adjacent { in t[from..to).
    @ public normal_behavior
    @   ensures \result <==> (\forall int k; from <= k && k + 1 < to; !(t[k] == '{' && t[k + 1] == '{'));
    @ public static model no_state helper boolean htmlNoDoubleBrace(\string t, \bigint from, \bigint to);
    @
    @ // The security property of element text: htmlSafe except for empty comments, and no {{
    @ // inside t[from..to) or with a { written next to it.
    @ public normal_behavior
    @   ensures \result <==> htmlNoQuotes(t, from, to) && htmlCommentsOnly(t, from, to)
    @       && htmlEntitiesOnly(t, from, to) && htmlPairedSurrogates(t, from, to)
    @       && htmlNoDoubleBrace(t, from, to) && (from < to ==> t[from] != '{' && t[to - 1] != '{');
    @ public static model no_state helper boolean htmlTextSafe(\string t, \bigint from, \bigint to);
    @
    @ // Lemma: u keeps t[0..m), so comments in t[o..m) and u[m..e) make every < and > in u[o..e) part of one.
    @ public normal_behavior
    @   requires 0 <= o && o <= m && m <= t.length && m <= e && e <= u.length;
    @   requires (\forall \bigint k; 0 <= k && k < m; u[k] == t[k]);
    @   requires htmlCommentsOnly(t, o, m) && htmlCommentsOnly(u, m, e);
    @   ensures htmlCommentsOnly(u, o, e);
    @   ensures \result;
    @ public static model pure boolean htmlCommentsConcat(\string t, \string u, \bigint o, \bigint m, \bigint e) {
    @   return true;
    @ }
    @
    @ // Lemma: u keeps t[0..m), and no {{ in t[o..m), in u[m..e) or across m means none in u[o..e).
    @ public normal_behavior
    @   requires 0 <= o && o <= m && m <= t.length && m <= e && e <= u.length;
    @   requires (\forall \bigint k; 0 <= k && k < m; u[k] == t[k]);
    @   requires htmlNoDoubleBrace(t, o, m) && htmlNoDoubleBrace(u, m, e);
    @   requires !(o < m && m < e && u[m - 1] == '{' && u[m] == '{');
    @   ensures htmlNoDoubleBrace(u, o, e);
    @   ensures \result;
    @ public static model pure boolean htmlNoDoubleBraceConcat(\string t, \string u, \bigint o, \bigint m, \bigint e) {
    @   return true;
    @ }
    @*/

  private static void escapeHtml(Output out, String s) {
    if (out instanceof StringOutput) {
      ((StringOutput) out).writeHtml(s, false);
    } else {
      out.write(escapeHtml(s));
    }
  }

  private static void escapeText(Output out, String s) {
    if (out instanceof StringOutput) {
      ((StringOutput) out).writeHtml(s, true);
    } else {
      out.write(escapeText(s));
    }
  }

  /**
   * Append directly to StringOutput; other sinks retain one write per escaped value. With
   * {@code text}, also breaks {@code {{} as {@link #text} does.
   */
  /*@ normal_behavior
    @   requires !text;
    @   assignable sb.chars;
    @   ensures \old(sb.chars.length) <= sb.chars.length;
    @   ensures (\forall int k; 0 <= k && k < \old(sb.chars.length); sb.chars[k] == \old(sb.chars[k]));
    @   ensures htmlSafe(sb.chars, \old(sb.chars.length), sb.chars.length);
    @ also normal_behavior
    @   requires text;
    @   assignable sb.chars;
    @   ensures \old(sb.chars.length) <= sb.chars.length;
    @   ensures (\forall int k; 0 <= k && k < \old(sb.chars.length); sb.chars[k] == \old(sb.chars[k]));
    @   ensures htmlTextSafe(sb.chars, \old(sb.chars.length), sb.chars.length);
    @*/
  static void escapeHtml(StringBuilder sb, String s, boolean text) {
    int i = 0;
    //@ maintaining 0 <= i && i <= s.length();
    //@ maintaining (\forall int j; 0 <= j && j < i; htmlPlain(s.chars[j]) && (text ==> s.chars[j] != '{'));
    //@ loop_assigns i;
    //@ decreases s.length() - i;
    while (i < s.length() && !needsHtmlEscape(s, i, text)) {
      i++;
    }
    if (i == s.length()) {
      sb.append(s);
    } else {
      appendEscapedHtml(sb, s, i, text);
    }
  }

  /*@ behavior
    @   ensures htmlSafe(\result.chars, 0, \result.chars.length);
    @   signals_only NegativeArraySizeException;
    @   signals (NegativeArraySizeException) s.length() > Integer.MAX_VALUE - 16;
    @ pure
    @*/
  static String escapeHtml(String s) {
    return escape(s, false);
  }

  /** Escapes element text as {@link #text} does, {@code {{} broken. */
  static String escapeText(String s) {
    return escape(s, true);
  }

  /*@ private behavior
    @   requires !text;
    @   ensures htmlSafe(\result.chars, 0, \result.chars.length);
    @   signals_only NegativeArraySizeException;
    @   signals (NegativeArraySizeException) s.length() > Integer.MAX_VALUE - 16;
    @ also private behavior
    @   requires text;
    @   ensures htmlTextSafe(\result.chars, 0, \result.chars.length);
    @   signals_only NegativeArraySizeException;
    @   signals (NegativeArraySizeException) s.length() > Integer.MAX_VALUE - 16;
    @ pure
    @*/
  private static String escape(String s, boolean text) {
    int i = 0;
    int n = s.length();
    //@ maintaining 0 <= i && i <= n;
    //@ maintaining (\forall int j; 0 <= j && j < i; htmlPlain(s.chars[j]) && (text ==> s.chars[j] != '{'));
    //@ loop_assigns i;
    //@ decreases n - i;
    while (i < n && !needsHtmlEscape(s, i, text)) {
      i++;
    }
    if (i == n) {
      return s;
    }
    StringBuilder sb = new StringBuilder(n + 16);
    appendEscapedHtml(sb, s, i, text);
    return sb.toString();
  }

  /*@ private normal_behavior
    @   requires !text && 0 <= i && i <= s.length();
    @   requires (\forall int j; 0 <= j && j < i; htmlPlain(s.chars[j]));
    @   assignable sb.chars;
    @   ensures \old(sb.chars.length) <= sb.chars.length;
    @   ensures (\forall int k; 0 <= k && k < \old(sb.chars.length); sb.chars[k] == \old(sb.chars[k]));
    @   ensures htmlSafe(sb.chars, \old(sb.chars.length), sb.chars.length);
    @ also private normal_behavior
    @   requires text && 0 <= i && i <= s.length();
    @   requires (\forall int j; 0 <= j && j < i; htmlPlain(s.chars[j]) && s.chars[j] != '{');
    @   assignable sb.chars;
    @   ensures \old(sb.chars.length) <= sb.chars.length;
    @   ensures (\forall int k; 0 <= k && k < \old(sb.chars.length); sb.chars[k] == \old(sb.chars[k]));
    @   ensures htmlTextSafe(sb.chars, \old(sb.chars.length), sb.chars.length);
    @*/
  private static void appendEscapedHtml(StringBuilder sb, String s, int i, boolean text) {
    int n = s.length();
    sb.append(s, 0, i);
    //@ maintaining 0 <= i && i <= n;
    //@ maintaining \old(sb.chars.length) <= sb.chars.length;
    //@ maintaining (\forall int k; 0 <= k && k < \old(sb.chars.length); sb.chars[k] == \old(sb.chars[k]));
    //@ maintaining !text ==> htmlNoDelimiters(sb.chars, \old(sb.chars.length), sb.chars.length);
    //@ maintaining htmlEntitiesOnly(sb.chars, \old(sb.chars.length), sb.chars.length);
    //@ maintaining htmlPairedSurrogates(sb.chars, \old(sb.chars.length), sb.chars.length);
    //@ maintaining text ==> htmlNoQuotes(sb.chars, \old(sb.chars.length), sb.chars.length);
    //@ maintaining text ==> htmlCommentsOnly(sb.chars, \old(sb.chars.length), sb.chars.length);
    //@ maintaining text ==> htmlNoDoubleBrace(sb.chars, \old(sb.chars.length), sb.chars.length);
    //@ maintaining text && \old(sb.chars.length) == sb.chars.length ==> i == 0;
    //@ maintaining text && \old(sb.chars.length) < sb.chars.length ==> sb.chars[\old(sb.chars.length)] != '{';
    //@ maintaining text && \old(sb.chars.length) < sb.chars.length && sb.chars[sb.chars.length - 1] == '{'
    //@     ==> i < n && s.chars[i] != '{';
    //@ loop_assigns i, sb.chars;
    //@ decreases n - i;
    while (i < n) {
      //@ ghost \string t = sb.chars;
      i = appendEscapedHtmlChar(sb, s, i, text);
      //@ assert htmlEntitiesConcat(t, sb.chars, \old(sb.chars.length), t.length, sb.chars.length);
      //@ assert htmlPairsConcat(t, sb.chars, \old(sb.chars.length), t.length, sb.chars.length);
      //@ assert text ==> htmlCommentsConcat(t, sb.chars, \old(sb.chars.length), t.length, sb.chars.length);
      //@ assert text ==> htmlNoDoubleBraceConcat(t, sb.chars, \old(sb.chars.length), t.length, sb.chars.length);
    }
  }

  /**
   * Appends the escaped form of {@code s.charAt(i)} (or of the surrogate pair at i); returns the
   * index after it. With {@code text}, a {@code {} gets an empty comment before it if it starts
   * the value and after it if it ends the value or another {@code {} follows, so two braces never
   * meet: not inside the value, and not with whatever is written before or after it.
   */
  /*@ private normal_behavior
    @   requires !text && 0 <= i && i < s.length();
    @   assignable sb.chars;
    @   ensures i < \result && \result <= s.length();
    @   ensures \old(sb.chars.length) <= sb.chars.length;
    @   ensures (\forall \bigint k; 0 <= k && k < \old(sb.chars.length); sb.chars[k] == \old(sb.chars[k]));
    @   ensures htmlNoDelimiters(sb.chars, \old(sb.chars.length), sb.chars.length);
    @   ensures htmlEntitiesOnly(sb.chars, \old(sb.chars.length), sb.chars.length);
    @   ensures htmlPairedSurrogates(sb.chars, \old(sb.chars.length), sb.chars.length);
    @ also private normal_behavior
    @   requires text && 0 <= i && i < s.length();
    @   assignable sb.chars;
    @   ensures i < \result && \result <= s.length();
    @   ensures \old(sb.chars.length) < sb.chars.length;
    @   ensures (\forall \bigint k; 0 <= k && k < \old(sb.chars.length); sb.chars[k] == \old(sb.chars[k]));
    @   ensures htmlNoQuotes(sb.chars, \old(sb.chars.length), sb.chars.length);
    @   ensures htmlCommentsOnly(sb.chars, \old(sb.chars.length), sb.chars.length);
    @   ensures htmlEntitiesOnly(sb.chars, \old(sb.chars.length), sb.chars.length);
    @   ensures htmlPairedSurrogates(sb.chars, \old(sb.chars.length), sb.chars.length);
    @   ensures htmlNoDoubleBrace(sb.chars, \old(sb.chars.length), sb.chars.length);
    @   ensures sb.chars[\old(sb.chars.length)] == '{' ==> 0 < i && s.chars[i] == '{';
    @   ensures sb.chars[sb.chars.length - 1] == '{' ==> \result < s.length() && s.chars[\result] != '{';
    @*/
  private static int appendEscapedHtmlChar(StringBuilder sb, String s, int i, boolean text) {
    char c = s.charAt(i);
    if (c == '{' && text) {
      appendTextBrace(sb, s, i);
      return i + 1;
    }
    switch (c) {
      case '&':
        sb.append("&amp;");
        break;
      case '<':
        sb.append("&lt;");
        break;
      case '>':
        sb.append("&gt;");
        break;
      case '"':
        sb.append("&quot;");
        break;
      case '\'':
        sb.append("&#39;");
        break;
      case '\0':
        sb.append('\uFFFD');
        break;
      default:
        if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
          sb.append(c).append(s.charAt(i + 1));
          return i + 2;
        } else if (Character.isSurrogate(c)) {
          sb.append('\uFFFD');
        } else {
          sb.append(c);
        }
        break;
    }
    return i + 1;
  }

  /** Appends the {@code {} at {@code s.charAt(i)} of element text with the empty comments it needs. */
  /*@ private normal_behavior
    @   requires 0 <= i && i < s.length() && s.chars[i] == '{';
    @   assignable sb.chars;
    @   ensures \old(sb.chars.length) < sb.chars.length;
    @   ensures (\forall \bigint k; 0 <= k && k < \old(sb.chars.length); sb.chars[k] == \old(sb.chars[k]));
    @   ensures htmlNoQuotes(sb.chars, \old(sb.chars.length), sb.chars.length);
    @   ensures htmlCommentsOnly(sb.chars, \old(sb.chars.length), sb.chars.length);
    @   ensures htmlEntitiesOnly(sb.chars, \old(sb.chars.length), sb.chars.length);
    @   ensures htmlPairedSurrogates(sb.chars, \old(sb.chars.length), sb.chars.length);
    @   ensures htmlNoDoubleBrace(sb.chars, \old(sb.chars.length), sb.chars.length);
    @   ensures sb.chars[\old(sb.chars.length)] == '{' ==> 0 < i;
    @   ensures sb.chars[sb.chars.length - 1] == '{' ==> i + 1 < s.length() && s.chars[i + 1] != '{';
    @*/
  private static void appendTextBrace(StringBuilder sb, String s, int i) {
    if (i == 0) {
      sb.append(BRACE_BREAK);
    }
    sb.append('{');
    if (i + 1 == s.length() || s.charAt(i + 1) == '{') {
      sb.append(BRACE_BREAK);
    }
  }

  /*@ private normal_behavior
    @   requires 0 <= i && i < s.length();
    @   ensures \result <==> (text && s.chars[i] == '{') || !htmlPlain(s.chars[i]);
    @ pure
    @*/
  private static boolean needsHtmlEscape(String s, int i, boolean text) {
    char c = s.charAt(i);
    switch (c) {
      case '{':
        return text;
      case '&':
      case '<':
      case '>':
      case '"':
      case '\'':
      case '\0':
        return true;
      default:
        return Character.isSurrogate(c);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // URL scheme check

  /*@ // The ASCII lower case of c, as schemeEquals computes it.
    @ public normal_behavior
    @   ensures \result == ('A' <= c && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
    @ public static model no_state helper char asciiLower(char c);
    @
    @ // The scheme a browser reads from s[from..to): tab, LF and CR removed, ASCII lower-cased.
    @ public normal_behavior
    @   requires 0 <= from && from <= to && to <= s.length();
    @   ensures \result == (to == from ? \string.empty
    @       : (s.charAt(to - 1) == '\t' || s.charAt(to - 1) == '\n' || s.charAt(to - 1) == '\r')
    @           ? schemeText(s, from, to - 1)
    @           : schemeText(s, from, to - 1).append(asciiLower(s.charAt(to - 1))));
    @ public static model pure helper \string schemeText(String s, int from, int to);
    @
    @ // Where a browser starts reading s: the index of its first character above ' '.
    @ public normal_behavior
    @   ensures 0 <= \result && \result <= s.length();
    @   ensures (\forall int j; 0 <= j && j < \result; s.chars[j] <= ' ');
    @   ensures \result < s.length() ==> s.chars[\result] > ' ';
    @ public static model pure helper int urlStart(String s);
    @
    @ // Where the scheme of s would end: the first ':', '/', '?' or '#' from urlStart(s) on.
    @ public normal_behavior
    @   ensures urlStart(s) <= \result && \result <= s.length();
    @   ensures (\forall int j; urlStart(s) <= j && j < \result;
    @       s.chars[j] != ':' && s.chars[j] != '/' && s.chars[j] != '?' && s.chars[j] != '#');
    @   ensures \result < s.length() ==> (s.chars[\result] == ':' || s.chars[\result] == '/'
    @       || s.chars[\result] == '?' || s.chars[\result] == '#');
    @ public static model pure helper int urlSchemeEnd(String s);
    @*/

  /**
   * Whether {@code s} is scheme-less or has an allowed scheme. Mirrors how browsers find the
   * scheme: leading C0 controls and spaces are stripped, tab/CR/LF are ignored, and the scheme
   * ends at the first {@code :} unless a {@code / ? #} comes first. Anything that looks like a
   * scheme and is not exactly (ASCII case-insensitively) an allowed one is rejected.
   */
  /*@ // If s has a scheme (a ':' before any '/', '?' or '#'), it is http, https, mailto or tel
    @ // as a browser reads it.
    @ normal_behavior
    @   ensures \result ==> urlSchemeEnd(s) == s.length() || s.chars[urlSchemeEnd(s)] != ':'
    @       || schemeText(s, urlStart(s), urlSchemeEnd(s)) == "http".chars
    @       || schemeText(s, urlStart(s), urlSchemeEnd(s)) == "https".chars
    @       || schemeText(s, urlStart(s), urlSchemeEnd(s)) == "mailto".chars
    @       || schemeText(s, urlStart(s), urlSchemeEnd(s)) == "tel".chars;
    @ pure
    @*/
  static boolean isAllowedUrl(String s) {
    int i = 0;
    //@ maintaining 0 <= i && i <= s.length();
    //@ maintaining (\forall int j; 0 <= j && j < i; s.chars[j] <= ' ');
    //@ loop_assigns i;
    //@ decreases s.length() - i;
    while (i < s.length() && s.charAt(i) <= ' ') {
      i++;
    }
    int start = i;
    //@ maintaining start <= i && i <= s.length();
    //@ maintaining (\forall int j; start <= j && j < i;
    //@     s.chars[j] != ':' && s.chars[j] != '/' && s.chars[j] != '?' && s.chars[j] != '#');
    //@ loop_assigns i;
    //@ decreases s.length() - i;
    for (; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\t' || c == '\n' || c == '\r') {
        continue;
      }
      if (c == '/' || c == '?' || c == '#') {
        return true;
      }
      if (c == ':') {
        return schemeEquals(s, start, i, "http")
            || schemeEquals(s, start, i, "https")
            || schemeEquals(s, start, i, "mailto")
            || schemeEquals(s, start, i, "tel");
      }
    }
    return true;
  }

  /** {@code s} if it {@linkplain #isAllowedUrl passes the scheme check}, else {@link #INVALID_URL}. */
  private static String checkedUrl(String s) {
    return isAllowedUrl(s) ? s : INVALID_URL;
  }

  /** Compare without allocating a normalized scheme; only ASCII case and tab/CR/LF are ignored. */
  /*@ private normal_behavior
    @   requires 0 <= start && start <= end && end <= s.length();
    @   ensures \result ==> schemeText(s, start, end) == expected.chars;
    @ pure
    @*/
  private static boolean schemeEquals(String s, int start, int end, String expected) {
    int matched = 0;
    //@ maintaining start <= i && i <= end;
    //@ maintaining 0 <= matched && matched <= expected.length();
    //@ maintaining schemeText(s, start, i) == expected.chars.substring(0, matched);
    //@ loop_assigns i, matched;
    //@ decreases end - i;
    for (int i = start; i < end; i++) {
      char c = s.charAt(i);
      if (c == '\t' || c == '\n' || c == '\r') {
        continue;
      }
      if (c >= 'A' && c <= 'Z') {
        c = (char) (c + ('a' - 'A'));
      }
      if (matched == expected.length() || c != expected.charAt(matched)) {
        return false;
      }
      matched++;
    }
    return matched == expected.length();
  }

  // ---------------------------------------------------------------------------------------------
  // JSON

  private static void writeJson(StringBuilder sb, Object value, int depth) {
    if (depth > MAX_JSON_DEPTH) {
      throw new IllegalArgumentException("value nested deeper than " + MAX_JSON_DEPTH + " (cycle?)");
    }
    if (value == null) {
      sb.append("null");
    } else if (value instanceof Boolean) {
      sb.append(((Boolean) value).booleanValue());
    } else if (value instanceof Byte) {
      sb.append(((Byte) value).byteValue());
    } else if (value instanceof Short) {
      sb.append(((Short) value).shortValue());
    } else if (value instanceof Integer) {
      sb.append(((Integer) value).intValue());
    } else if (value instanceof Long) {
      sb.append(((Long) value).longValue());
    } else if (value instanceof Float) {
      Float v = (Float) value;
      writeDouble(sb, v.doubleValue(), Float.toString(v));
    } else if (value instanceof Double) {
      Double v = (Double) value;
      writeDouble(sb, v, Double.toString(v));
    } else if (value instanceof BigInteger || value instanceof BigDecimal) {
      sb.append(value.toString());
    } else if (value instanceof CharSequence) {
      writeJsonString(sb, value.toString());
    } else if (value instanceof Character) {
      writeJsonString(sb, String.valueOf(((Character) value).charValue()));
    } else if (value instanceof Enum) {
      writeJsonString(sb, ((Enum<?>) value).name());
    } else if (value instanceof Optional) {
      writeJson(sb, ((Optional<?>) value).orElse(null), depth + 1);
    } else if (value instanceof Map) {
      writeJsonObject(sb, (Map<?, ?>) value, depth);
    } else if (value instanceof Collection) {
      sb.append('[');
      boolean first = true;
      for (Object item : (Collection<?>) value) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        writeJson(sb, item, depth + 1);
      }
      sb.append(']');
    } else if (value instanceof Object[]) {
      writeJson(sb, Arrays.asList((Object[]) value), depth);
    } else if (value instanceof boolean[]) {
      boolean[] a = (boolean[]) value;
      writeArray(sb, a.length, i -> sb.append(a[i]));
    } else if (value instanceof byte[]) {
      byte[] a = (byte[]) value;
      writeArray(sb, a.length, i -> sb.append(a[i]));
    } else if (value instanceof short[]) {
      short[] a = (short[]) value;
      writeArray(sb, a.length, i -> sb.append(a[i]));
    } else if (value instanceof int[]) {
      int[] a = (int[]) value;
      writeArray(sb, a.length, i -> sb.append(a[i]));
    } else if (value instanceof long[]) {
      long[] a = (long[]) value;
      writeArray(sb, a.length, i -> sb.append(a[i]));
    } else if (value instanceof float[]) {
      float[] a = (float[]) value;
      writeArray(sb, a.length, i -> writeDouble(sb, a[i], Float.toString(a[i])));
    } else if (value instanceof double[]) {
      double[] a = (double[]) value;
      writeArray(sb, a.length, i -> writeDouble(sb, a[i], Double.toString(a[i])));
    } else if (value instanceof char[]) {
      writeJsonString(sb, new String((char[]) value));
    } else if (value instanceof ZoneId) {
      writeJsonString(sb, ((ZoneId) value).getId());
    } else if (
    // The java.time types below must match ScriptJson.JSON_TIME in tessera-compiler, which
    // decides at build time which types may be inlined into <script>.
    value instanceof Instant
        || value instanceof LocalDate
        || value instanceof LocalTime
        || value instanceof LocalDateTime
        || value instanceof OffsetTime
        || value instanceof OffsetDateTime
        || value instanceof ZonedDateTime
        || value instanceof Year
        || value instanceof YearMonth
        || value instanceof MonthDay
        || value instanceof Duration
        || value instanceof Period) {
      writeJsonString(sb, value.toString());
    } else {
      throw new IllegalArgumentException(
          "cannot serialize " + value.getClass().getName() + " as JSON");
    }
  }

  private static void writeArray(StringBuilder sb, int length, IntConsumer element) {
    sb.append('[');
    for (int i = 0; i < length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      element.accept(i);
    }
    sb.append(']');
  }

  private static void writeDouble(StringBuilder sb, double d, String text) {
    sb.append(Double.isFinite(d) ? text : "null");
  }

  private static void writeJsonObject(StringBuilder sb, Map<?, ?> m, int depth) {
    sb.append('{');
    boolean first = true;
    for (Map.Entry<?, ?> e : m.entrySet()) {
      if (!(e.getKey() instanceof String)) {
        throw new IllegalArgumentException(
            "JSON object keys must be Strings, got "
                + (e.getKey() == null ? "null" : e.getKey().getClass().getName()));
      }
      String key = (String) e.getKey();
      if (!first) {
        sb.append(',');
      }
      first = false;
      writeJsonString(sb, key);
      sb.append(':');
      writeJson(sb, e.getValue(), depth + 1);
    }
    sb.append('}');
  }

  /*@ // A character writeJsonString copies unchanged.
    @ public normal_behavior
    @   ensures \result <==> (c >= 0x20 && c != 0x7f && c != '"' && c != '\\' && c != '/'
    @       && c != '<' && c != '>' && c != '&' && c != '\'' && c != '`' && c != '$'
    @       && c != '\u2028' && c != '\u2029' && !('\uD800' <= c && c <= '\uDFFF'));
    @ public static model no_state helper boolean jsonPlain(char c);
    @
    @ // A digit of a \\uXXXX escape as unicodeEscape writes it.
    @ public normal_behavior
    @   ensures \result <==> (('0' <= c && c <= '9') || ('a' <= c && c <= 'f'));
    @ public static model no_state helper boolean jsonHex(char c);
    @
    @ // The character after the backslash of a two-character escape.
    @ public normal_behavior
    @   ensures \result <==> (c == '"' || c == '\\' || c == '/' || c == 'n' || c == 'r' || c == 't'
    @       || c == 'b' || c == 'f');
    @ public static model no_state helper boolean jsonEscaped(char c);
    @
    @ // m marks where the tokens of t[from..to) start ('1'). Starting at from, each token is a
    @ // jsonPlain character, a surrogate pair, a two-character escape or a \\uXXXX escape, and
    @ // the next token starts right after it, up to to. No token is a prefix of another, so a
    @ // JSON or JavaScript lexer reads t[from..to) as exactly these tokens: it never sees an
    @ // unescaped quote, backslash or line terminator.
    @ public normal_behavior
    @   ensures \result <==> (m.length == to - from && (from < to ==> m[0] == '1')
    @       && (\forall int k; from <= k && k < to; m[k - from] == '1' ==> (
    @           (jsonPlain(t[k]) && (k + 1 == to || m[k + 1 - from] == '1'))
    @           || ('\uD800' <= t[k] && t[k] <= '\uDBFF' && k + 1 < to && '\uDC00' <= t[k + 1]
    @               && t[k + 1] <= '\uDFFF' && (k + 2 == to || m[k + 2 - from] == '1'))
    @           || (t[k] == '\\' && k + 1 < to && jsonEscaped(t[k + 1]) && (k + 2 == to || m[k + 2 - from] == '1'))
    @           || (t[k] == '\\' && k + 5 < to && t[k + 1] == 'u' && jsonHex(t[k + 2]) && jsonHex(t[k + 3])
    @               && jsonHex(t[k + 4]) && jsonHex(t[k + 5]) && (k + 6 == to || m[k + 6 - from] == '1')))));
    @ public static model no_state helper boolean jsonTokens(\string t, \string m, \bigint from, \bigint to);
    @
    @ // Lemmas, each proved on its own: appending one token to t keeps jsonTokens and
    @ // jsonScriptSafe. The loop in
    @ // writeJsonString asserts one after each append, which keeps its own proof small.
    @ public normal_behavior
    @   requires 0 <= b && b <= a && a <= t.length && jsonTokens(t, m, a, t.length) && jsonScriptSafe(t, b, t.length);
    @   requires jsonPlain(c);
    @   ensures jsonTokens(t.append(c), m.append('1'), a, t.length + 1);
    @   ensures jsonScriptSafe(t.append(c), b, t.length + 1);
    @   ensures \result;
    @ public static model pure boolean jsonAppend1(\string t, \string m, \bigint b, \bigint a, char c) {
    @   return true;
    @ }
    @
    @ public normal_behavior
    @   requires 0 <= b && b <= a && a <= t.length && jsonTokens(t, m, a, t.length) && jsonScriptSafe(t, b, t.length);
    @   requires u.length == t.length + 2 && (\forall \bigint k; 0 <= k && k < t.length; u[k] == t[k]);
    @   requires (u[t.length] == '\\' && jsonEscaped(u[t.length + 1]))
    @       || ('\uD800' <= u[t.length] && u[t.length] <= '\uDBFF'
    @           && '\uDC00' <= u[t.length + 1] && u[t.length + 1] <= '\uDFFF');
    @   ensures jsonTokens(u, m.append('1').append('0'), a, u.length);
    @   ensures jsonScriptSafe(u, b, u.length);
    @   ensures \result;
    @ public static model pure boolean jsonAppend2(\string t, \string u, \string m, \bigint b, \bigint a) {
    @   return true;
    @ }
    @
    @ public normal_behavior
    @   requires 0 <= b && b <= a && a <= t.length && jsonTokens(t, m, a, t.length) && jsonScriptSafe(t, b, t.length);
    @   requires u.length == t.length + 6 && (\forall \bigint k; 0 <= k && k < t.length; u[k] == t[k]);
    @   requires u[t.length] == '\\' && u[t.length + 1] == 'u';
    @   requires (\forall \bigint k; t.length + 2 <= k && k < u.length;
    @       ('0' <= u[k] && u[k] <= '9') || ('a' <= u[k] && u[k] <= 'f'));
    @   ensures jsonTokens(u, m.append('1').append('0').append('0').append('0').append('0').append('0'), a, u.length);
    @   ensures jsonScriptSafe(u, b, u.length);
    @   ensures \result;
    @ public static model pure boolean jsonAppend6(\string t, \string u, \string m, \bigint b, \bigint a) {
    @   return true;
    @ }
    @
    @ public normal_behavior
    @   requires 0 <= b && b <= a && a <= t.length && jsonTokens(t, m, a, t.length) && jsonScriptSafe(t, b, t.length);
    @   requires u.length == t.length + 1 && (\forall \bigint k; 0 <= k && k < t.length; u[k] == t[k]);
    @   requires u[t.length] == '"';
    @   ensures jsonTokens(u, m, a, u.length - 1) && jsonScriptSafe(u, b, u.length);
    @   ensures \result;
    @ public static model pure boolean jsonClose(\string t, \string u, \string m, \bigint b, \bigint a) {
    @   return true;
    @ }
    @
    @ // t[from..to) contains none of < > & ' ` $ U+2028 U+2029, C0 controls or DEL, so it cannot
    @ // close a <script> element, start an HTML comment, or end a JS template literal.
    @ public normal_behavior
    @   ensures \result <==> (\forall int k; from <= k && k < to;
    @       t[k] >= 0x20 && t[k] != 0x7f && t[k] != '<' && t[k] != '>' && t[k] != '&' && t[k] != '\''
    @       && t[k] != '`' && t[k] != '$' && t[k] != '\u2028' && t[k] != '\u2029');
    @ public static model no_state helper boolean jsonScriptSafe(\string t, \bigint from, \bigint to);
    @*/

  /**
   * Writes a double-quoted JSON string that is also safe inside an HTML {@code <script>}
   * element or a JS comment, regex or template literal: {@code < > & ' ` $}, U+2028, U+2029,
   * controls and unpaired surrogates become {@code \\uXXXX}, and {@code /} becomes {@code \\/}.
   */
  /*@ private normal_behavior
    @   assignable sb.chars;
    @   ensures \old(sb.chars.length) + 2 <= sb.chars.length;
    @   ensures (\forall \bigint k; 0 <= k && k < \old(sb.chars.length); sb.chars[k] == \old(sb.chars[k]));
    @   ensures sb.chars[\old(sb.chars.length)] == '"' && sb.chars[sb.chars.length - 1] == '"';
    @   ensures (\exists \string m; jsonTokens(sb.chars, m, \old(sb.chars.length) + 1, sb.chars.length - 1));
    @   ensures jsonScriptSafe(sb.chars, \old(sb.chars.length), sb.chars.length);
    @*/
  private static void writeJsonString(StringBuilder sb, String s) {
    sb.append('"');
    //@ ghost \string m = \string.empty;
    int n = s.length();
    int i = 0;
    //@ maintaining 0 <= i && i <= n;
    //@ maintaining \old(sb.chars.length) + 1 <= sb.chars.length;
    //@ maintaining (\forall \bigint k; 0 <= k && k < \old(sb.chars.length); sb.chars[k] == \old(sb.chars[k]));
    //@ maintaining sb.chars[\old(sb.chars.length)] == '"';
    //@ maintaining jsonTokens(sb.chars, m, \old(sb.chars.length) + 1, sb.chars.length);
    //@ maintaining jsonScriptSafe(sb.chars, \old(sb.chars.length), sb.chars.length);
    //@ loop_assigns i, sb.chars, m;
    //@ decreases n - i;
    while (i < n) {
      //@ ghost \string t = sb.chars;
      //@ ghost char c = s.chars[i];
      i = appendJsonChar(sb, s, i);
      //@ assert sb.chars.length == t.length + 1 ==> jsonAppend1(t, m, \old(sb.chars.length), \old(sb.chars.length) + 1, c);
      //@ assert sb.chars.length == t.length + 2 ==> jsonAppend2(t, sb.chars, m, \old(sb.chars.length), \old(sb.chars.length) + 1);
      //@ assert sb.chars.length == t.length + 6 ==> jsonAppend6(t, sb.chars, m, \old(sb.chars.length), \old(sb.chars.length) + 1);
      //@ set m = sb.chars.length == t.length + 1 ? m.append('1') : sb.chars.length == t.length + 2 ? m.append('1').append('0') : m.append('1').append('0').append('0').append('0').append('0').append('0');
    }
    //@ ghost \string body = sb.chars;
    sb.append('"');
    //@ assert jsonClose(body, sb.chars, m, \old(sb.chars.length), \old(sb.chars.length) + 1);
    //@ assert jsonTokens(sb.chars, m, \old(sb.chars.length) + 1, sb.chars.length - 1);
  }

  /**
   * Appends the JSON string form of {@code s.charAt(i)} (or of the surrogate pair at i) for
   * {@link #writeJsonString}; returns the index after it.
   */
  /*@ private normal_behavior
    @   requires 0 <= i && i < s.length();
    @   assignable sb.chars;
    @   ensures i < \result && \result <= s.length();
    @   ensures (\forall \bigint k; 0 <= k && k < \old(sb.chars.length); sb.chars[k] == \old(sb.chars[k]));
    @   ensures sb.chars.length == \old(sb.chars.length) + 1 || sb.chars.length == \old(sb.chars.length) + 2
    @       || sb.chars.length == \old(sb.chars.length) + 6;
    @   ensures sb.chars.length == \old(sb.chars.length) + 1
    @       ==> jsonPlain(s.chars[i]) && sb.chars == \old(sb.chars).append(s.chars[i]);
    @   ensures sb.chars.length == \old(sb.chars.length) + 2
    @       ==> (sb.chars[\old(sb.chars.length)] == '\\' && jsonEscaped(sb.chars[\old(sb.chars.length) + 1]))
    @           || ('\uD800' <= sb.chars[\old(sb.chars.length)] && sb.chars[\old(sb.chars.length)] <= '\uDBFF'
    @               && '\uDC00' <= sb.chars[\old(sb.chars.length) + 1] && sb.chars[\old(sb.chars.length) + 1] <= '\uDFFF');
    @   ensures sb.chars.length == \old(sb.chars.length) + 6
    @       ==> sb.chars[\old(sb.chars.length)] == '\\' && sb.chars[\old(sb.chars.length) + 1] == 'u'
    @           && (\forall \bigint k; \old(sb.chars.length) + 2 <= k && k < sb.chars.length;
    @               ('0' <= sb.chars[k] && sb.chars[k] <= '9') || ('a' <= sb.chars[k] && sb.chars[k] <= 'f'));
    @*/
  private static int appendJsonChar(StringBuilder sb, String s, int i) {
    char c = s.charAt(i);
    switch (c) {
      case '"':
        sb.append("\\\"");
        break;
      case '\\':
        sb.append("\\\\");
        break;
      case '\n':
        sb.append("\\n");
        break;
      case '\r':
        sb.append("\\r");
        break;
      case '\t':
        sb.append("\\t");
        break;
      case '\b':
        sb.append("\\b");
        break;
      case '\f':
        sb.append("\\f");
        break;
      // '/' stops a value closing a /*[[...]]*/ comment or a regex literal; '`' and '$' stop
      // it closing a template literal or starting ${...} inside one.
      case '/':
        sb.append("\\/");
        break;
      case '<':
      case '>':
      case '&':
      case '\'':
      case '`':
      case '$':
      case '\u2028':
      case '\u2029':
        unicodeEscape(sb, c);
        break;
      default:
        if (c < 0x20 || c == 0x7f) {
          unicodeEscape(sb, c);
        } else if (Character.isHighSurrogate(c)
            && i + 1 < s.length()
            && Character.isLowSurrogate(s.charAt(i + 1))) {
          sb.append(c).append(s.charAt(i + 1));
          return i + 2;
        } else if (Character.isSurrogate(c)) {
          unicodeEscape(sb, c);
        } else {
          sb.append(c);
        }
        break;
    }
    return i + 1;
  }

  /*@ private normal_behavior
    @   assignable sb.chars;
    @   ensures sb.chars.length == \old(sb.chars.length) + 6;
    @   ensures (\forall \bigint k; 0 <= k && k < \old(sb.chars.length); sb.chars[k] == \old(sb.chars[k]));
    @   ensures sb.chars[\old(sb.chars.length)] == '\\' && sb.chars[\old(sb.chars.length) + 1] == 'u';
    @   ensures (\forall \bigint k; \old(sb.chars.length) + 2 <= k && k < sb.chars.length;
    @       ('0' <= sb.chars[k] && sb.chars[k] <= '9') || ('a' <= sb.chars[k] && sb.chars[k] <= 'f'));
    @*/
  private static void unicodeEscape(StringBuilder sb, char c) {
    sb.append("\\u")
        .append(HEX[(c >> 12) & 0xf])
        .append(HEX[(c >> 8) & 0xf])
        .append(HEX[(c >> 4) & 0xf])
        .append(HEX[c & 0xf]);
  }
}
