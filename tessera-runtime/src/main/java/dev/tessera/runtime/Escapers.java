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
  static final String BRACE_BREAK = "<!---->";

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
   * The {@code values} list of an SVG {@code <animate>}/{@code <set>} (OutputContext.URL_LIST,
   * #284). SVG applies the {@code ;}-separated items in turn, so every item is scheme-checked as
   * in {@link #url}; if any item fails, the whole attribute is replaced by
   * {@code about:invalid#tessera}. A {@link TrustedUrl} is attribute-encoded as is. Lists without
   * a scheme ({@code 0;1}, {@code red;blue}) pass.
   */
  public static void urlList(Output out, Object value) {
    if (value instanceof TrustedUrl) {
      attr(out, ((TrustedUrl) value).url());
      return;
    }
    String s = toText(value);
    for (String item : s.split(";", -1)) {
      if (!isAllowedUrl(item)) {
        attr(out, INVALID_URL);
        return;
      }
    }
    attr(out, s);
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
  static void escapeHtml(StringBuilder sb, String s, boolean text) {
    int i = 0;
    while (i < s.length() && !needsHtmlEscape(s, i, text)) {
      i++;
    }
    if (i == s.length()) {
      sb.append(s);
    } else {
      appendEscapedHtml(sb, s, i, text);
    }
  }

  static String escapeHtml(String s) {
    return escape(s, false);
  }

  /** Escapes element text as {@link #text} does, {@code {{} broken. */
  static String escapeText(String s) {
    return escape(s, true);
  }

  private static String escape(String s, boolean text) {
    int i = 0;
    int n = s.length();
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

  private static void appendEscapedHtml(StringBuilder sb, String s, int i, boolean text) {
    int n = s.length();
    sb.append(s, 0, i);
    for (; i < n; i++) {
      char c = s.charAt(i);
      if (c == '{' && text) {
        // Never let two braces meet: not inside the value, and not with whatever is written
        // before or after it (static text, another value).
        if (i == 0) {
          sb.append(BRACE_BREAK);
        }
        sb.append('{');
        if (i + 1 == n || s.charAt(i + 1) == '{') {
          sb.append(BRACE_BREAK);
        }
        continue;
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
          if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
            sb.append(c).append(s.charAt(++i));
          } else if (Character.isSurrogate(c)) {
            sb.append('\uFFFD');
          } else {
            sb.append(c);
          }
          break;
      }
    }
  }

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

  /**
   * Whether {@code s} is scheme-less or has an allowed scheme. Mirrors how browsers find the
   * scheme: leading C0 controls and spaces are stripped, tab/CR/LF are ignored, and the scheme
   * ends at the first {@code :} unless a {@code / ? #} comes first. Anything that looks like a
   * scheme and is not exactly (ASCII case-insensitively) an allowed one is rejected.
   */
  static boolean isAllowedUrl(String s) {
    int i = 0;
    while (i < s.length() && s.charAt(i) <= ' ') {
      i++;
    }
    int start = i;
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

  /**
   * Whether a browser resolves {@code value}, written as a URL attribute, against the page's own
   * origin: {@code null} (the attribute is omitted), or a relative URL with no scheme that does not
   * start with two of {@code /} and {@code \} (protocol-relative). As in {@link #isAllowedUrl},
   * leading C0 controls and spaces are stripped and tab/CR/LF are ignored; any {@code :} before a
   * {@code / ? #} is a scheme, so an absolute URL to the page's own host is not same-origin. A
   * {@link TrustedUrl} is checked too: it vouches for the scheme, not for the origin. Any other
   * value is checked as {@link #url} writes it, so one that fails the scheme check (written as
   * {@code about:invalid#tessera}) is not same-origin. Used for the CSRF token, which goes only into
   * forms that post to the page's origin (#285).
   */
  public static boolean isSameOriginUrl(Object value) {
    if (value == null) {
      return true;
    }
    String s = value instanceof TrustedUrl ? ((TrustedUrl) value).url() : toText(value);
    if (!(value instanceof TrustedUrl) && !isAllowedUrl(s)) {
      return false;
    }
    int i = 0;
    while (i < s.length() && s.charAt(i) <= ' ') {
      i++;
    }
    if (i < s.length() && (s.charAt(i) == '/' || s.charAt(i) == '\\')) {
      // A path, unless a second slash makes it protocol-relative (a browser reads \ as /).
      i++;
      while (i < s.length() && (s.charAt(i) == '\t' || s.charAt(i) == '\n' || s.charAt(i) == '\r')) {
        i++;
      }
      return i == s.length() || (s.charAt(i) != '/' && s.charAt(i) != '\\');
    }
    for (; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '/' || c == '?' || c == '#') {
        return true;
      }
      if (c == ':') {
        return false;
      }
    }
    return true;
  }

  /** {@code s} if it {@linkplain #isAllowedUrl passes the scheme check}, else {@link #INVALID_URL}. */
  private static String checkedUrl(String s) {
    return isAllowedUrl(s) ? s : INVALID_URL;
  }

  /** Compare without allocating a normalized scheme; only ASCII case and tab/CR/LF are ignored. */
  private static boolean schemeEquals(String s, int start, int end, String expected) {
    int matched = 0;
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

  /**
   * Writes a double-quoted JSON string that is also safe inside an HTML {@code <script>}
   * element or a JS comment, regex or template literal: {@code < > & ' ` $}, U+2028, U+2029,
   * controls and unpaired surrogates become {@code \\uXXXX}, and {@code /} becomes {@code \\/}.
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
              && i + 1 < n
              && Character.isLowSurrogate(s.charAt(i + 1))) {
            sb.append(c).append(s.charAt(++i));
          } else if (Character.isSurrogate(c)) {
            unicodeEscape(sb, c);
          } else {
            sb.append(c);
          }
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
