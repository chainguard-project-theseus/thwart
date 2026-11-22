package dev.chainguard.thwart.runtime.internal;

import dev.chainguard.thwart.runtime.CssValue;
import dev.chainguard.thwart.runtime.HtmlPolicy;
import dev.chainguard.thwart.runtime.Links;
import dev.chainguard.thwart.runtime.Messages;
import dev.chainguard.thwart.runtime.RenderContext;
import dev.chainguard.thwart.runtime.SafeHtml;
import dev.chainguard.thwart.runtime.SafeInlineHtml;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Contextual escapers. The compiler picks exactly one of these for every expression output
 * site based on its {@code OutputContext}; templates cannot choose. HTML methods treat
 * {@code null} as the empty string; JSON methods serialize it as {@code null}.
 *
 * <p>Conversion to text: {@code null} becomes {@code ""}; everything else uses
 * {@link String#valueOf(Object)}. Safe types are only honoured where noted.
 *
 * <p>HTML escapers also replace controls other than ASCII whitespace, noncharacters and unpaired
 * surrogates with U+FFFD ({@link #forbiddenCodePoint}), so the output parses without a parse error.
 * The JSON escapers write the same code points as {@code \\uXXXX} escapes, the same JS value.
 */
public final class Escapers {
  private Escapers() {}

  /** Replacement for URLs whose scheme is not allowed. */
  static final String INVALID_URL = "about:invalid#thwart";

  /** Nesting limit for {@link #json}; deeper (or cyclic) values are rejected. */
  private static final int MAX_JSON_DEPTH = 64;

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  /** Converts a value to its display text ({@code null} becomes {@code ""}). */
  public static String toText(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  /**
   * HTML element text, the RCDATA of {@code <title>} and {@code <textarea>} included
   * (OutputContext.TEXT). Encodes {@code & < > " '}, as {@link #attr} does. A {@link SafeHtml}
   * argument is escaped like any other value: {@code t:text} always escapes. Client-side
   * frameworks that compile the page's DOM as a template are not supported (guide limitations.md).
   */
  public static void text(Output out, Object value) {
    escapeHtml(out, toText(value));
  }

  // Typed overloads of text: javac picks them for a primitive value, whose text is only
  // digits, '-' or a literal, written without escaping. A char is escaped like any other value (it
  // would otherwise widen to the int overload); a boxed value, and an enum, whose toString() an
  // application can override, go to text(Output, Object) and are escaped.

  /** {@link #text(Output, Object)} for an {@code int} (and a {@code byte} or {@code short}): its digits. */
  public static void text(Output out, int value) {
    out.write(Integer.toString(value));
  }

  /** {@link #text(Output, Object)} for a {@code long}: its digits. */
  public static void text(Output out, long value) {
    out.write(Long.toString(value));
  }

  /** {@link #text(Output, Object)} for a {@code boolean}: {@code true} or {@code false}. */
  public static void text(Output out, boolean value) {
    out.write(value ? "true" : "false");
  }

  /** {@link #text(Output, Object)} for a {@code char}, escaped. */
  public static void text(Output out, char value) {
    escapeHtml(out, String.valueOf(value));
  }

  /** Quoted attribute value (OutputContext.ATTRIBUTE). Encodes {@code & < > " '}. */
  public static void attr(Output out, Object value) {
    escapeHtml(out, toText(value));
  }

  /**
   * URL-valued attribute such as {@code href}/{@code action} (OutputContext.URL). Every value is
   * attribute-encoded after a scheme check: only {@code http}, {@code https}, {@code mailto},
   * {@code tel} and scheme-less (relative) URLs pass; anything else is replaced by
   * {@code about:invalid#thwart}.
   */
  public static void url(Output out, Object value) {
    attr(out, checkedUrl(toText(value)));
  }

  /**
   * Resource URL attribute such as {@code <script src>} (OutputContext.RESOURCE_URL). Only
   * accepts a String produced by a link expression with a literal path; the compiler rejects
   * every other expression.
   *
   * <p>As defence in depth the String also goes through the {@link #url} scheme check, and any
   * other type throws {@link IllegalArgumentException}.
   */
  public static void resourceUrl(Output out, Object value) {
    if (value == null) {
      return;
    }
    if (value instanceof String) {
      attr(out, checkedUrl((String) value));
    } else {
      throw new IllegalArgumentException(
          "resource URL must be a link, got " + value.getClass().getName());
    }
  }

  /**
   * The {@code content} of a {@code <meta http-equiv="refresh">} (OutputContext.REFRESH).
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
        UrlEncoding.percent(sb, c);
      } else {
        sb.append(c);
      }
    }
    if (!Checks.isSameOriginPath(sb.substring(urlStart))) {
      throw new IllegalArgumentException("refresh URL must be a same-origin path starting with a single '/'");
    }
    escapeHtml(out, sb.toString());
  }

  /**
   * A {@code BigDecimal} or {@code BigInteger} piece of a {@code style} attribute value,
   * which generated code joins with literals and {@link CssValue}s before {@link #attr} escapes it.
   * Neither class is final, so only the JDK classes themselves are accepted, as in {@link #json};
   * a subclass throws {@link IllegalArgumentException}. {@code null} stays {@code null}.
   */
  public static String styleNumber(Number value) {
    return value == null ? null : requireJdkNumber(value).toString();
  }

  /** Raw HTML (t:utext and {@code [(...)]}). Only {@link SafeHtml} is accepted. */
  public static void html(Output out, SafeHtml value) {
    if (value != null) {
      out.write(value.html());
    }
  }

  /** Raw inline HTML: t:utext and {@code [(...)]} of a {@link SafeInlineHtml}. */
  public static void html(Output out, SafeInlineHtml value) {
    if (value != null) {
      out.write(value.html());
    }
  }

  /**
   * Checks the arguments of a message {@code #{key(args)}} before they are formatted, in every form
   * of the message: generated code calls it before {@link Messages#get} (escaped text, an attribute,
   * a {@code <script>}) and passes its result to {@link Messages#get}, and {@link #htmlMessage}
   * calls it for the HTML form. Returns {@code args}.
   *
   * <p>{@link MessageFormat} writes a number in plain notation, one character per integer digit,
   * so a {@link BigDecimal} with a large exponent expands: {@code 1E+999999999}, 12 characters,
   * formats as {@code {0}} to 1,333,333,333 characters, which took 25 s and 2.5 GB of heap. A
   * {@code BigDecimal} with more than 10,000 integer digits ({@code precision() - scale()}) throws
   * {@link IllegalArgumentException}. That is far more than any quantity a page shows, and caps a
   * formatted argument at about 13,300 characters with grouping separators. One whose first digit is
   * more than 10,000 places below the decimal point ({@code precision() - scale() <= -10000}, so
   * {@code 1E-10000} passes and {@code 1E-10001} throws) throws too: it formats as 0, and turning it
   * into a {@code long}, as {@code {0,date}} does, can compute a power of ten that large (JDK 8
   * does). Zero passes whatever its scale. Other numbers are bounded already: a {@code double} has
   * at most 309 integer digits, and a {@code BigInteger} writes the digits it holds.
   *
   * <p>{@link MessageFormat} formats a {@link BigDecimal} or {@link BigInteger} from its
   * {@code toString()}, copying its characters as digits, and both classes can be subclassed. Only
   * the JDK classes themselves are accepted: a subclass throws {@link IllegalArgumentException}, so
   * the formatter never calls a subclass's methods.
   */
  public static Object[] messageArgs(Object[] args) {
    for (Object a : args) {
      if (a instanceof BigDecimal || a instanceof BigInteger) {
        requireJdkNumber((Number) a);
      }
      if (a instanceof BigDecimal) {
        Checks.requireBoundedMagnitude(
            (BigDecimal) a, "a message argument has a decimal exponent beyond " + Checks.MAX_NUMBER_DIGITS + " either way");
      }
    }
    return args;
  }

  /** How many distinct messages without arguments {@link #htmlMessage} keeps sanitized, per policy. */
  static final int MESSAGE_CACHE_SIZE = 4096;

  /**
   * The fixed policy of HTML messages ({@code t:utext="#{key}"}): phrasing markup only,
   * {@code b i em strong u s sub sup small mark code kbd span br} with a {@code title}, and
   * {@code a} with a checked {@code href}, {@code rel="noopener noreferrer"} and
   * {@code target="_blank"} kept. Phrasing content never closes a {@code <p>} or heading around it,
   * so a message can stand in one. Not configurable.
   */
  public static final HtmlPolicy MESSAGE_POLICY =
      messagePolicy(HtmlPolicy.builder()).allowElements("a").linkRel("noopener", "noreferrer").allowLinkTarget().build();

  /**
   * {@link #MESSAGE_POLICY} without links, an inline policy: the compiler picks it for a key with no
   * {@code <a} in any of its bundle values, and the message is a {@link SafeInlineHtml}, so it can
   * also stand inside a link.
   */
  public static final HtmlPolicy MESSAGE_POLICY_NO_LINKS = messagePolicy(HtmlPolicy.inlineBuilder()).build();

  private static HtmlPolicy.Builder messagePolicy(HtmlPolicy.Builder b) {
    return b
        .allowElements("b", "i", "em", "strong", "u", "s", "sub", "sup", "small", "mark", "code", "kbd", "span", "br")
        .allowAttributes("title");
  }

  private static final Map<String, SafeHtml> MESSAGES = new ConcurrentHashMap<>();
  private static final Map<String, SafeInlineHtml> MESSAGES_NO_LINKS = new ConcurrentHashMap<>();
  private static final SafeInlineHtml NO_MESSAGE = SafeInlineHtml.escape("");

  /**
   * A bundle message rendered as HTML ({@code t:utext="#{key(args)}"}, {@code [(#{...})]}) for a
   * key with no {@code <a} in any bundle value. The arguments are escaped as HTML text (numbers and
   * dates are kept for MessageFormat), the message is looked up with them, and the result is cleaned
   * with the fixed inline policy {@link #MESSAGE_POLICY_NO_LINKS}: markup outside it is dropped
   * and its text kept, whatever the bundle or the message source holds, and an argument is only ever
   * text. A message without arguments is sanitized once per distinct text (up to
   * {@link #MESSAGE_CACHE_SIZE}). A missing message ({@code null}) gives empty HTML.
   */
  public static SafeInlineHtml htmlMessage(RenderContext ctx, String key, Object[] args) {
    return message(ctx, key, args, MESSAGES_NO_LINKS, NO_MESSAGE, m -> SafeInlineHtml.sanitize(m, MESSAGE_POLICY_NO_LINKS));
  }

  /**
   * As {@link #htmlMessage}, for a key that may have a link (a value with {@code <a}, or an
   * unchecked bundle): cleaned with {@link #MESSAGE_POLICY}, which also keeps a checked link, so
   * the result is a {@link SafeHtml}. A missing message gives {@link SafeHtml#EMPTY}.
   */
  public static SafeHtml linkedHtmlMessage(RenderContext ctx, String key, Object[] args) {
    return message(ctx, key, args, MESSAGES, SafeHtml.EMPTY, m -> SafeHtml.sanitize(m, MESSAGE_POLICY));
  }

  private static <T> T message(
      RenderContext ctx, String key, Object[] args, Map<String, T> cache, T missing, Function<String, T> sanitize) {
    if (args.length > 0) {
      String message = ctx.messages().get(key, escapeArgs(messageTextArgs(args)));
      return message == null ? missing : sanitize.apply(message);
    }
    String message = ctx.messages().get(key, args);
    if (message == null) {
      return missing;
    }
    T html = cache.get(message);
    if (html == null) {
      html = sanitize.apply(message);
      if (cache.size() < MESSAGE_CACHE_SIZE) {
        cache.put(message, html);
      }
    }
    return html;
  }

  /** A copy of {@code text} with each {@code String} escaped as HTML text. */
  private static Object[] escapeArgs(Object[] text) {
    Object[] escaped = text.clone();
    for (int i = 0; i < escaped.length; i++) {
      if (escaped[i] instanceof String) {
        escaped[i] = escapeHtml((String) escaped[i]);
      }
    }
    return escaped;
  }

  /**
   * The arguments of {@link #htmlMessage} as plain text: every value becomes its
   * {@linkplain #toText text}, except {@link Number} and {@link java.util.Date}, which
   * {@link MessageFormat} formats itself (to digits and locale symbols or names, never markup) and
   * which must stay numbers and dates for {@code {0,number}} and {@code {0,date}}. {@code null}
   * stays {@code null}, as with the escaped form. The result then goes through
   * {@link #messageArgs}, which rejects a {@code BigDecimal} or {@code BigInteger} subclass and
   * bounds a {@code BigDecimal}, so the formatter never calls a subclass's methods. Returns a new
   * array.
   */
  static Object[] messageTextArgs(Object[] args) {
    Object[] text = new Object[args.length];
    for (int i = 0; i < args.length; i++) {
      Object a = args[i];
      text[i] = a == null || a instanceof Number || a instanceof java.util.Date ? a : toText(a);
    }
    return messageArgs(text);
  }

  /**
   * Value inside a {@code <script>} of JSON data (OutputContext.SCRIPT with type
   * {@code application/json} or {@code application/ld+json}; a JavaScript {@code <script>} uses
   * {@link #javaScript}). Serializes as JSON: null, Boolean, Number, CharSequence, Character, Enum (name), java.time values (ISO string),
   * Optional, Collection, array and Map with String keys. Escapes {@code <}, {@code >},
   * {@code &}, {@code '}, U+2028 and U+2029 so the value cannot close the script element,
   * and {@code {}, {@code /}, {@code `} and
   * {@code $} as defence in depth: the compiler never places a value inside a JS string, comment,
   * regex or template literal. Other types throw IllegalArgumentException (the
   * compiler only allows the types above).
   *
   * <p>Numbers are limited to the JDK types ({@code Byte}, {@code Short}, {@code Integer},
   * {@code Long}, {@code Float}, {@code Double}, {@code BigInteger}, {@code BigDecimal}); a
   * non-finite float or double becomes {@code null}, as with {@code JSON.stringify}. Only the
   * JDK classes themselves are accepted, never a subclass of {@code BigInteger} or
   * {@code BigDecimal}, whose {@code toString()} an application can override: a subclass throws
   * IllegalArgumentException. The value is fully serialized before anything is written, so a
   * failure writes nothing.
   */
  public static void json(Output out, Object value) {
    StringBuilder sb = new StringBuilder();
    writeJson(sb, value, 0);
    out.write(sb.toString());
  }

  /**
   * Value inside a JavaScript {@code <script>} (OutputContext.SCRIPT with no type, a JavaScript
   * type or {@code module}): serialized exactly as {@link #json} and always written in parentheses,
   * {@code (json)}, so whatever its type the value is one primary expression and an operator,
   * member access or call next to it applies to the whole value. The compiler only allows it as the
   * value of a declaration at the top of the script ({@code const x = [[...]];}). The value is fully
   * serialized before anything is written, so a failure writes nothing.
   */
  public static void javaScript(Output out, Object value) {
    StringBuilder sb = new StringBuilder("(");
    writeJson(sb, value, 0);
    out.write(sb.append(')').toString());
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

  private static void escapeHtml(Output out, String s) {
    if (out instanceof StringOutput) {
      appendHtml(((StringOutput) out).sb, s);
    } else {
      out.write(escapeHtml(s));
    }
  }

  /** The replacement of every ASCII character {@link #appendHtml} escapes; {@code null} if kept. */
  private static final String[] HTML_ASCII = new String[128];

  /** U+FFFD, written for a {@link #forbiddenCodePoint}. */
  private static final String REPLACEMENT = String.valueOf((char) 0xFFFD);

  /** Bit {@code c} is set if {@code HTML_ASCII[c]} is, for {@code c < 64}. */
  private static final long HTML_ASCII_LOW;

  static {
    for (int c = 0; c < 128; c++) {
      if (forbiddenCodePoint(c)) {
        HTML_ASCII[c] = REPLACEMENT;
      }
    }
    HTML_ASCII['&'] = "&amp;";
    HTML_ASCII['<'] = "&lt;";
    HTML_ASCII['>'] = "&gt;";
    HTML_ASCII['"'] = "&quot;";
    HTML_ASCII['\''] = "&#39;";
    long low = 0;
    for (int c = 0; c < 64; c++) {
      low |= HTML_ASCII[c] != null ? 1L << c : 0;
    }
    HTML_ASCII_LOW = low;
  }

  /**
   * Whether {@link #appendHtml} may have to replace {@code c}: an {@link #HTML_ASCII} character,
   * U+0080-U+009F, a surrogate (kept if half of an allowed pair) or U+FDD0 and above.
   */
  private static boolean mayEscapeHtml(char c) {
    if (c < 64) {
      return (HTML_ASCII_LOW >>> c & 1) != 0;
    }
    return c >= 0x7F && (c <= 0x9F || (c >= 0xD800 && (c <= 0xDFFF || c >= 0xFDD0)));
  }

  /** The index of the first char of {@code s} that {@link #mayEscapeHtml may need escaping}, or its length. */
  private static int firstHtmlEscape(String s) {
    int i = 0;
    while (i < s.length() && !mayEscapeHtml(s.charAt(i))) {
      i++;
    }
    return i;
  }

  /**
   * The one HTML escaper (element text, RCDATA and attribute values alike): {@code & < > " '},
   * and {@link #forbiddenCodePoint forbidden code points} as U+FFFD, nothing else.
   */
  public static void appendHtml(StringBuilder sb, String s) {
    int i = firstHtmlEscape(s);
    if (i == s.length()) {
      sb.append(s);
    } else {
      appendEscapedHtml(sb, s, i);
    }
  }

  /** Escapes {@code s} as {@link #appendHtml} does. */
  public static String escapeHtml(String s) {
    int i = firstHtmlEscape(s);
    if (i == s.length()) {
      return s;
    }
    StringBuilder sb = new StringBuilder(s.length() + 16);
    appendEscapedHtml(sb, s, i);
    return sb.toString();
  }

  /** {@link #appendHtml} from index {@code i}, copying runs of kept characters in bulk. */
  private static void appendEscapedHtml(StringBuilder sb, String s, int i) {
    int n = s.length();
    int kept = 0;
    for (; i < n; i++) {
      char c = s.charAt(i);
      if (!mayEscapeHtml(c)) {
        continue;
      }
      String replacement;
      int next = i + 1;
      if (c < 128) {
        replacement = HTML_ASCII[c];
      } else {
        int cp = Character.codePointAt(s, i); // a pair or a lone surrogate
        next = i + Character.charCount(cp);
        replacement = forbiddenCodePoint(cp) ? REPLACEMENT : null;
      }
      if (replacement != null) {
        if (i > kept) {
          sb.append(s, kept, i);
        }
        sb.append(replacement);
        kept = next;
      }
      i = next - 1;
    }
    sb.append(s, kept, n);
  }

  /**
   * Whether {@code cp} is a parse error in the parser's input: a control other than
   * ASCII whitespace (U+0000-U+001F but TAB, LF, FF and CR, and U+007F-U+009F), a noncharacter
   * (U+FDD0-U+FDEF and the last two code points of every plane) or a surrogate (a lone one, as
   * {@link Character#codePointAt(CharSequence, int)} returns it). The HTML escapers, and so the
   * sanitizer, which writes text and attribute values through them, write it as U+FFFD, and the
   * JSON escapers as {@code \\uXXXX}: the one rule for every producer.
   */
  static boolean forbiddenCodePoint(int cp) {
    if (cp < 0x20) {
      return cp != '\t' && cp != '\n' && cp != '\f' && cp != '\r';
    }
    return (cp >= 0x7F && cp <= 0x9F) || (cp >= 0xD800 && cp <= 0xDFFF) || (cp >= 0xFDD0 && cp <= 0xFDEF)
        || (cp & 0xFFFE) == 0xFFFE;
  }

  // ---------------------------------------------------------------------------------------------
  // Same-origin links

  /**
   * {@code url}, a link built by {@link Links} for a slot that must stay on the page's origin (a
   * {@code <base href>}, a refresh URL,
   * the link {@code action} of any form and every {@code formaction}, which may receive the
   * CSRF token), if it starts
   * with {@code /} and {@linkplain Checks#isSameOriginUrl is same-origin}. The compiler checks only the
   * literal path; a context path ending in {@code /}, a custom {@link Links}, or a path variable can
   * still turn {@code /a} into the protocol-relative {@code //a}. Anything else fails the
   * render with an {@link IllegalStateException} rather than leave the origin.
   */
  public static String sameOriginLink(String url) {
    if (!Checks.isSameOriginPath(url)) {
      throw new IllegalStateException(
          "link must be a same-origin path starting with a single '/' (the Links implementation or the"
              + " context path produced " + (url == null ? "null" : "'" + url + "'") + ")");
    }
    return url;
  }

  /**
   * A number in an attribute outside HTML ({@code data-*}, {@code hx-*}, ...), which client frameworks may run as code, in
   * plain decimal {@code -?(0|[1-9][0-9]*)(\.[0-9]+)?}: no exponent, {@code +}, NaN or Infinity, which
   * client code could read as an identifier. Only the JDK's own {@code Byte Short Integer Long
   * BigInteger BigDecimal Float Double} are accepted, a subclass throws (it could override toString), as
   * does a NaN or infinite value. {@code null} stays {@code null}.
   */
  public static String neutralNumber(Object value) {
    if (value == null) {
      return null;
    }
    Class<?> c = value.getClass();
    String s;
    if (c == Integer.class || c == Long.class || c == Short.class || c == Byte.class || c == BigInteger.class) {
      s = value.toString();
    } else if (c == BigDecimal.class) {
      s = ((BigDecimal) value).toPlainString();
    } else if ((c == Double.class || c == Float.class) && !Double.isNaN(((Number) value).doubleValue())
        && !Double.isInfinite(((Number) value).doubleValue())) {
      s = new BigDecimal(value.toString()).toPlainString();
    } else {
      throw new IllegalArgumentException(
          "a " + c.getName() + " value '" + value + "' cannot be written as a plain decimal number in an attribute"
              + " outside HTML: only finite JDK Byte, Short, Integer, Long, Float, Double, BigInteger and BigDecimal"
              + " values can; convert it in Java first");
    }
    if (!isPlainDecimal(s)) {
      throw new IllegalStateException("not a plain decimal number: '" + s + "'");
    }
    return s;
  }

  /** {@code -?(0|[1-9][0-9]*)(\.[0-9]+)?}. */
  private static boolean isPlainDecimal(String s) {
    int i = s.startsWith("-") ? 1 : 0;
    int digits = i;
    while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
      i++;
    }
    if (i == digits || (s.charAt(digits) == '0' && i - digits > 1)) {
      return false;
    }
    if (i == s.length()) {
      return true;
    }
    if (s.charAt(i) != '.' || i + 1 == s.length()) {
      return false;
    }
    for (i++; i < s.length(); i++) {
      if (s.charAt(i) < '0' || s.charAt(i) > '9') {
        return false;
      }
    }
    return true;
  }

  /**
   * {@code url}, a link built by {@link Links} for an attribute outside HTML ({@code data-*},
   * {@code hx-*}, ...), which client frameworks may run as code, in the alphabet client code reads as a regular expression
   * literal and nothing else: every byte outside {@code A-Z a-z 0-9 . _ ~ / -} is percent-encoded
   * (UTF-8), except a {@code %} that already starts a {@code %XX}, and the second {@code -} of
   * {@code --} (a decrement, which writes) becomes {@code %2D}. Path variables come from data, and a
   * collection fills one with {@code ,}. The URL must first pass {@link #sameOriginLink}, which
   * throws otherwise; the encoding keeps its single leading {@code /}.
   */
  public static String neutralPath(String url) {
    if (url == null) {
      return null;
    }
    byte[] bytes = sameOriginLink(url).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    StringBuilder sb = new StringBuilder(bytes.length + 8);
    for (int i = 0; i < bytes.length; i++) {
      int b = bytes[i] & 0xFF;
      boolean escape = b == '%' && i + 2 < bytes.length && Ascii.isHexDigit(bytes[i + 1]) && Ascii.isHexDigit(bytes[i + 2]);
      if (escape || (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9')
          || b == '.' || b == '_' || b == '~' || b == '/'
          || (b == '-' && (sb.length() == 0 || sb.charAt(sb.length() - 1) != '-'))) {
        sb.append((char) b);
      } else {
        UrlEncoding.percent(sb, b);
      }
    }
    return sb.toString();
  }

  /** {@code s} if it {@linkplain Checks#isAllowedUrl passes the scheme check}, else {@link #INVALID_URL}. */
  private static String checkedUrl(String s) {
    return Checks.isAllowedUrl(s) ? s : INVALID_URL;
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
      sb.append(requireJdkNumber((Number) value).toString());
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
    // The java.time types below must match ScriptJson.JSON_TIME in thwart-compiler, which
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

  /**
   * {@code value} if its class is exactly {@link BigInteger} or {@link BigDecimal}, whose
   * {@code toString()} always writes a JSON number ({@code 1E+3}, {@code 1E-7}, {@code 0.00}; JSON
   * allows the {@code +}). Neither class is final, and an application subclass can override
   * {@code toString()} to return anything: it throws {@link IllegalArgumentException}.
   */
  private static Number requireJdkNumber(Number value) {
    if (value.getClass() != BigInteger.class && value.getClass() != BigDecimal.class) {
      throw new IllegalArgumentException(
          value.getClass().getName() + " is a subclass of "
              + (value instanceof BigDecimal ? "java.math.BigDecimal" : "java.math.BigInteger") + ": only the JDK class itself can be written, convert it in Java first");
    }
    return value;
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

  /**
   * Writes a double-quoted JSON string that is also safe inside an HTML {@code <script>}
   * element, and as defence in depth inside a JS comment, regex or template literal, where the
   * compiler never places a value: {@code < > & ' ` $ {}, U+2028, U+2029 and every
   * {@link #forbiddenCodePoint} (controls, noncharacters as their two halves when supplementary, and
   * lone surrogates) become {@code \\uXXXX}, and {@code /} becomes {@code \\/}. An escape is the same
   * JS value, and the page parses without a parse error.
   */
  private static void writeJsonString(StringBuilder sb, String s) {
    sb.append('"');
    int n = s.length();
    for (int i = 0; i < n; i++) {
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
          sb.append('\\').append('b'); // JSON's backspace escape
          break;
        case '\f':
          sb.append("\\f");
          break;
        // Defence in depth (values sit only outside JS comments, regexes and template literals):
        // '/' could close a comment or regex literal, '`' and '$' a template literal or ${...}.
        case '/':
          sb.append("\\/");
          break;
        case '<':
        case '>':
        case '&':
        case '\'':
        case '`':
        case '$':
        case '{':
        case '\u2028':
        case '\u2029':
          unicodeEscape(sb, c);
          break;
        default:
          int cp = Character.codePointAt(s, i); // a pair or a lone surrogate
          int end = i + Character.charCount(cp);
          if (forbiddenCodePoint(cp)) {
            for (; i < end; i++) {
              unicodeEscape(sb, s.charAt(i)); // a pair as its two halves: the same JS value
            }
          } else {
            sb.append(s, i, end);
          }
          i = end - 1;
          break;
      }
    }
    sb.append('"');
  }

  private static void unicodeEscape(StringBuilder sb, char c) {
    sb.append("\\u")
        .append(HEX[(c >> 12) & 0xf])
        .append(HEX[(c >> 8) & 0xf])
        .append(HEX[(c >> 4) & 0xf])
        .append(HEX[c & 0xf]);
  }
}
