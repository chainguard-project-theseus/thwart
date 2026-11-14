package dev.chainguard.thwart.runtime.internal;

import dev.chainguard.thwart.runtime.Links;
import java.nio.charset.StandardCharsets;

/**
 * Percent-encoding (UTF-8, RFC 3986) and the render-time pieces of compiled link expressions
 * ({@link Links}). The compiler writes the literal parts of a link, query names, separators and
 * {@code int}/{@code long} values directly; only values it cannot know go through here.
 *
 * <p>A path variable value that is {@code null}, empty, {@code .} or {@code ..} is rejected with
 * an {@link IllegalArgumentException}: browsers resolve dot segments even when percent-encoded,
 * and an empty first segment would turn {@code /{x}/y} into the protocol-relative URL {@code //y}.
 *
 * <p>Multi-value parameters are traversed once and each element is converted immediately.
 * Values are not snapshotted before rendering: mutations by an iterator or an element's
 * {@code toString()} can affect subsequent elements or invalidate the iteration.
 */
public final class UrlEncoding {
  private UrlEncoding() {}

  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  /** Encodes a path variable value: everything except unreserved characters, so {@code /} becomes {@code %2F}. */
  public static String pathSegment(String value) {
    return encode(value, "");
  }

  /** Encodes a query parameter name or value: {@code & = + # ?} and space are encoded. */
  public static String queryParam(String value) {
    // Query characters with no meaning inside a name or value stay readable; all else is encoded.
    return encode(value, "/:@!$'()*,;");
  }

  /** The encoded path variable {@code name} filled by a value that is not a collection or array. */
  public static String pathVariable(String name, Object value) {
    return pathSegment(checkPathValue(name, value == null ? null : String.valueOf(value)));
  }

  /**
   * The encoded path variable {@code name} filled by any value. A collection or array fills it with
   * its elements joined by {@code ,} (a {@code null} element is empty), as in Thymeleaf; each
   * element is encoded on its own so the separator stays a literal comma.
   */
  public static String pathVariables(String name, Object value) {
    StringBuilder encoded = appendMultiValues(new StringBuilder(), null, value);
    if (encoded == null) {
      return pathVariable(name, value);
    }
    if (encoded.length() > 0) {
      encoded.setLength(encoded.length() - 1);
    }
    // Encoding preserves dots and escapes every other structural character. Only the empty
    // string, "." and ".." can still be empty/dot segments, so no raw joined copy is needed.
    return checkPathValue(name, encoded.toString());
  }

  /** An encoded query value that is not a collection or array; {@code null} is empty ({@code name=}). */
  public static String queryValue(Object value) {
    return value == null ? "" : queryParam(String.valueOf(value));
  }

  /**
   * {@code &name=value} for any value, once per element of a collection or array (a {@code null}
   * element is the bare {@code &name}; an empty one renders nothing).
   */
  public static String queryValues(String encodedName, Object value) {
    StringBuilder out = new StringBuilder();
    if (appendMultiValues(out, encodedName, value) == null) {
      out.append('&').append(encodedName).append('=').append(queryValue(value));
    }
    return out.toString();
  }

  /** {@code pairs} ({@code &a=1&b=2} or empty) with its first {@code &} as {@code ?}. */
  public static String firstQuery(String pairs) {
    return pairs.isEmpty() ? "" : "?" + pairs.substring(1);
  }

  /**
   * Appends each element of an Iterable or array to {@code out}, returning it, or null for a
   * scalar. With a null {@code encodedName} each element is an encoded path piece followed by
   * {@code ,}; otherwise a {@code &name=value} pair.
   */
  private static StringBuilder appendMultiValues(StringBuilder out, String encodedName, Object value) {
    if (value instanceof Iterable<?>) {
      for (Object item : (Iterable<?>) value) {
        appendItem(out, encodedName, item);
      }
    } else if (value instanceof Object[]) {
      for (Object item : (Object[]) value) {
        appendItem(out, encodedName, item);
      }
    } else if (value instanceof int[]) {
      for (int item : (int[]) value) {
        appendItem(out, encodedName, String.valueOf(item));
      }
    } else if (value instanceof long[]) {
      for (long item : (long[]) value) {
        appendItem(out, encodedName, String.valueOf(item));
      }
    } else if (value instanceof double[]) {
      for (double item : (double[]) value) {
        appendItem(out, encodedName, String.valueOf(item));
      }
    } else if (value instanceof float[]) {
      for (float item : (float[]) value) {
        appendItem(out, encodedName, String.valueOf(item));
      }
    } else if (value instanceof short[]) {
      for (short item : (short[]) value) {
        appendItem(out, encodedName, String.valueOf(item));
      }
    } else if (value instanceof byte[]) {
      for (byte item : (byte[]) value) {
        appendItem(out, encodedName, String.valueOf(item));
      }
    } else if (value instanceof char[]) {
      for (char item : (char[]) value) {
        appendItem(out, encodedName, String.valueOf(item));
      }
    } else if (value instanceof boolean[]) {
      for (boolean item : (boolean[]) value) {
        appendItem(out, encodedName, String.valueOf(item));
      }
    } else {
      return null;
    }
    return out;
  }

  private static void appendItem(StringBuilder out, String encodedName, Object item) {
    if (encodedName == null) {
      // A trailing separator also counts empty/null elements; pathVariables removes the last one.
      out.append(pathSegment(item == null ? "" : String.valueOf(item))).append(',');
    } else {
      // A null element of a collection is the bare name, as in Thymeleaf.
      out.append('&').append(encodedName);
      if (item != null) {
        out.append('=').append(queryParam(String.valueOf(item)));
      }
    }
  }

  private static String checkPathValue(String name, String s) {
    if (s == null || s.isEmpty() || s.equals(".") || s.equals("..")) {
      throw new IllegalArgumentException(
          "link path variable '"
              + name
              + "' must not be null, empty, '.' or '..' (was "
              + (s == null ? "null" : "'" + s + "'")
              + ")");
    }
    return s;
  }

  private static String encode(String value, String alsoAllowed) {
    int i = 0;
    while (i < value.length() && kept(value.charAt(i), alsoAllowed)) {
      i++;
    }
    if (i == value.length()) {
      return value;
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    StringBuilder sb = new StringBuilder(bytes.length);
    for (byte b : bytes) {
      int c = b & 0xFF;
      if (kept(c, alsoAllowed)) {
        sb.append((char) c);
      } else {
        percent(sb, c);
      }
    }
    return sb.toString();
  }

  /** Appends {@code octet} (0-255) as {@code %XX}, upper-case hex. */
  public static void percent(StringBuilder sb, int octet) {
    sb.append('%').append(HEX[octet >> 4]).append(HEX[octet & 0xF]);
  }

  private static boolean kept(int c, String alsoAllowed) {
    return isUnreserved(c) || (c < 0x80 && alsoAllowed.indexOf(c) >= 0);
  }

  private static boolean isUnreserved(int c) {
    return (c >= 'A' && c <= 'Z')
        || (c >= 'a' && c <= 'z')
        || (c >= '0' && c <= '9')
        || c == '-'
        || c == '.'
        || c == '_'
        || c == '~';
  }
}
