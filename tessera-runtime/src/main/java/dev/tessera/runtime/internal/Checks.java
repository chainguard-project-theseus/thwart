package dev.tessera.runtime.internal;

import java.math.BigDecimal;

/**
 * Checks shared by the escapers, {@code numbers.format*} and the compiler. Not API: public only so
 * that Tessera's other packages can call them.
 */
public final class Checks {
  private Checks() {}

  /** How far from the decimal point a formatted {@link BigDecimal} may reach; see {@link Escapers#messageArgs}. */
  public static final int MAX_NUMBER_DIGITS = 10000;

  /**
   * {@code d}, unless it is nonzero and reaches more than 10,000 places from the decimal point
   * either way ({@code precision() - scale()} above it, or at or below its negation),
   * which throws {@link IllegalArgumentException} with {@code message}. The rationale is on
   * {@link Escapers#messageArgs}; {@code numbers.format*} uses the same bound.
   */
  public static BigDecimal requireBoundedMagnitude(BigDecimal d, String message) {
    long magnitude = (long) d.precision() - d.scale();
    if (d.signum() != 0 && (magnitude > MAX_NUMBER_DIGITS || magnitude <= -MAX_NUMBER_DIGITS)) {
      throw new IllegalArgumentException(message);
    }
    return d;
  }

  /**
   * Whether {@code s} is scheme-less or has an allowed scheme. Mirrors how browsers find the
   * scheme: leading C0 controls and spaces are stripped, tab/CR/LF are ignored, and the scheme
   * ends at the first {@code :} unless a {@code / ? #} comes first. Anything that looks like a
   * scheme and is not exactly (ASCII case-insensitively) an allowed one is rejected.
   */
  public static boolean isAllowedUrl(String s) {
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
   * Whether a browser resolves {@code s}, written as a URL attribute, against the page's own
   * origin: {@code null} (the attribute is omitted), or a relative URL with no scheme that does not
   * start with two of {@code /} and {@code \} (protocol-relative). As in {@link #isAllowedUrl},
   * leading C0 controls and spaces are stripped and tab/CR/LF are ignored; any {@code :} before a
   * {@code / ? #} is a scheme, so an absolute URL to the page's own host is not same-origin. The
   * value is checked as {@link Escapers#url} writes it, so one that fails the scheme check (written as
   * {@code about:invalid#tessera}) is not same-origin.
   */
  public static boolean isSameOriginUrl(String s) {
    if (s == null) {
      return true;
    }
    if (!isAllowedUrl(s)) {
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

  /** Whether {@code url} is a same-origin path: it starts with one {@code /} and {@link #isSameOriginUrl}. */
  public static boolean isSameOriginPath(String url) {
    return url != null && url.startsWith("/") && isSameOriginUrl(url);
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
}
