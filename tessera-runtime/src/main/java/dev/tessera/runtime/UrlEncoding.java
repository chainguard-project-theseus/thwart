package dev.tessera.runtime;

import java.nio.charset.StandardCharsets;

/** Percent-encoding used by link expressions (UTF-8, RFC 3986). */
final class UrlEncoding {
  private UrlEncoding() {}

  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  /** Encodes a path variable value: everything except unreserved characters, so {@code /} becomes {@code %2F}. */
  static String pathSegment(String value) {
    return encode(value, "");
  }

  /** Encodes a query parameter name or value: {@code & = + # ?} and space are encoded. */
  static String queryParam(String value) {
    // Query characters with no meaning inside a name or value stay readable; all else is encoded.
    return encode(value, "/:@!$'()*,;");
  }

  private static String encode(String value, String alsoAllowed) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    StringBuilder sb = new StringBuilder(bytes.length);
    for (byte b : bytes) {
      int c = b & 0xFF;
      if (isUnreserved(c) || (c < 0x80 && alsoAllowed.indexOf(c) >= 0)) {
        sb.append((char) c);
      } else {
        sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
      }
    }
    return sb.toString();
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
