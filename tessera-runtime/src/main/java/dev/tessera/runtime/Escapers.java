package dev.tessera.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
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
 * site based on its {@code OutputContext}; templates cannot choose. Every method except
 * {@link #json} treats {@code null} as the empty string ({@code json} writes {@code null}).
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
   * HTML element text (OutputContext.TEXT). Encodes {@code & < > " '}. A {@link SafeHtml}
   * argument is escaped like any other value: {@code t:text} always escapes.
   */
  public static void text(Output out, Object value) {
    out.write(escapeHtml(toText(value)));
  }

  /** Quoted attribute value (OutputContext.ATTRIBUTE). Encodes {@code & < > " '}. */
  public static void attr(Output out, Object value) {
    out.write(escapeHtml(toText(value)));
  }

  /**
   * URL-valued attribute such as {@code href}/{@code action} (OutputContext.URL). A
   * {@link TrustedUrl} is attribute-encoded as is. Any other value is attribute-encoded after
   * a scheme check: only {@code http}, {@code https}, {@code mailto}, {@code tel} and
   * scheme-less (relative) URLs pass; anything else is replaced by {@code about:invalid#tessera}.
   */
  public static void url(Output out, Object value) {
    if (value instanceof TrustedUrl trusted) {
      attr(out, trusted.url());
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
    switch (value) {
      case null -> {}
      case TrustedUrl trusted -> attr(out, trusted.url());
      case String s -> attr(out, checkedUrl(s));
      default ->
          throw new IllegalArgumentException(
              "resource URL must be a TrustedUrl or a link, got " + value.getClass().getName());
    }
  }

  /** Raw HTML (t:utext and {@code [(...)]}). Only {@link SafeHtml} is accepted. */
  public static void html(Output out, SafeHtml value) {
    if (value != null) {
      out.write(value.html());
    }
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

  // ---------------------------------------------------------------------------------------------
  // HTML

  static String escapeHtml(String s) {
    int i = 0;
    int n = s.length();
    while (i < n && !needsHtmlEscape(s, i)) {
      i++;
    }
    if (i == n) {
      return s;
    }
    StringBuilder sb = new StringBuilder(n + 16);
    sb.append(s, 0, i);
    for (; i < n; i++) {
      char c = s.charAt(i);
      switch (c) {
        case '&' -> sb.append("&amp;");
        case '<' -> sb.append("&lt;");
        case '>' -> sb.append("&gt;");
        case '"' -> sb.append("&quot;");
        case '\'' -> sb.append("&#39;");
        case '\0' -> sb.append('\uFFFD');
        default -> {
          if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
            sb.append(c).append(s.charAt(++i));
          } else if (Character.isSurrogate(c)) {
            sb.append('\uFFFD');
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.toString();
  }

  private static boolean needsHtmlEscape(String s, int i) {
    char c = s.charAt(i);
    return switch (c) {
      case '&', '<', '>', '"', '\'', '\0' -> true;
      default -> Character.isSurrogate(c);
    };
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
    StringBuilder scheme = new StringBuilder();
    int i = 0;
    while (i < s.length() && s.charAt(i) <= ' ') {
      i++;
    }
    for (; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\t' || c == '\n' || c == '\r') {
        continue;
      }
      if (c == '/' || c == '?' || c == '#') {
        return true;
      }
      if (c == ':') {
        String name = asciiLower(scheme);
        return name.equals("http")
            || name.equals("https")
            || name.equals("mailto")
            || name.equals("tel");
      }
      scheme.append(c);
    }
    return true;
  }

  /** {@code s} if it {@linkplain #isAllowedUrl passes the scheme check}, else {@link #INVALID_URL}. */
  private static String checkedUrl(String s) {
    return isAllowedUrl(s) ? s : INVALID_URL;
  }

  private static String asciiLower(CharSequence cs) {
    StringBuilder sb = new StringBuilder(cs.length());
    for (int i = 0; i < cs.length(); i++) {
      char c = cs.charAt(i);
      sb.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // JSON

  private static void writeJson(StringBuilder sb, Object value, int depth) {
    if (depth > MAX_JSON_DEPTH) {
      throw new IllegalArgumentException("value nested deeper than " + MAX_JSON_DEPTH + " (cycle?)");
    }
    switch (value) {
      case null -> sb.append("null");
      case Boolean b -> sb.append(b.booleanValue());
      case Byte b -> sb.append(b.byteValue());
      case Short v -> sb.append(v.shortValue());
      case Integer v -> sb.append(v.intValue());
      case Long v -> sb.append(v.longValue());
      case Float v -> writeDouble(sb, v.doubleValue(), Float.toString(v));
      case Double v -> writeDouble(sb, v, Double.toString(v));
      case BigInteger v -> sb.append(v.toString());
      case BigDecimal v -> sb.append(v.toString());
      case CharSequence cs -> writeJsonString(sb, cs.toString());
      case Character c -> writeJsonString(sb, String.valueOf(c.charValue()));
      case Enum<?> e -> writeJsonString(sb, e.name());
      case Optional<?> o -> writeJson(sb, o.orElse(null), depth + 1);
      case Map<?, ?> m -> writeJsonObject(sb, m, depth);
      case Collection<?> c -> {
        sb.append('[');
        boolean first = true;
        for (Object item : c) {
          if (!first) {
            sb.append(',');
          }
          first = false;
          writeJson(sb, item, depth + 1);
        }
        sb.append(']');
      }
      case Object[] a -> writeJson(sb, Arrays.asList(a), depth);
      case boolean[] a -> writeArray(sb, a.length, i -> sb.append(a[i]));
      case byte[] a -> writeArray(sb, a.length, i -> sb.append(a[i]));
      case short[] a -> writeArray(sb, a.length, i -> sb.append(a[i]));
      case int[] a -> writeArray(sb, a.length, i -> sb.append(a[i]));
      case long[] a -> writeArray(sb, a.length, i -> sb.append(a[i]));
      case float[] a -> writeArray(sb, a.length, i -> writeDouble(sb, a[i], Float.toString(a[i])));
      case double[] a -> writeArray(sb, a.length, i -> writeDouble(sb, a[i], Double.toString(a[i])));
      case char[] a -> writeJsonString(sb, new String(a));
      // The java.time cases below must match TextInlining.JSON_TIME in tessera-compiler, which
      // decides at build time which types may be inlined into <script>.
      case Instant v -> writeJsonString(sb, v.toString());
      case LocalDate v -> writeJsonString(sb, v.toString());
      case LocalTime v -> writeJsonString(sb, v.toString());
      case LocalDateTime v -> writeJsonString(sb, v.toString());
      case OffsetTime v -> writeJsonString(sb, v.toString());
      case OffsetDateTime v -> writeJsonString(sb, v.toString());
      case ZonedDateTime v -> writeJsonString(sb, v.toString());
      case Year v -> writeJsonString(sb, v.toString());
      case YearMonth v -> writeJsonString(sb, v.toString());
      case MonthDay v -> writeJsonString(sb, v.toString());
      case Duration v -> writeJsonString(sb, v.toString());
      case Period v -> writeJsonString(sb, v.toString());
      case ZoneId v -> writeJsonString(sb, v.getId());
      default ->
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
      if (!(e.getKey() instanceof String key)) {
        throw new IllegalArgumentException(
            "JSON object keys must be Strings, got "
                + (e.getKey() == null ? "null" : e.getKey().getClass().getName()));
      }
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
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        // '/' stops a value closing a /*[[...]]*/ comment or a regex literal; '`' and '$' stop
        // it closing a template literal or starting ${...} inside one.
        case '/' -> sb.append("\\/");
        case '<', '>', '&', '\'', '`', '$', '\u2028', '\u2029' -> unicodeEscape(sb, c);
        default -> {
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
        }
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
