package dev.tessera.runtime.internal;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ASCII-only character helpers, as HTML, CSS and URLs define them. Use these, not Java's Unicode
 * case folding or character classes ({@code AsciiOnlyTest}).
 */
public final class Ascii {
  private Ascii() {}

  public static boolean isLetter(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  /** {@code [A-Za-z0-9_-]}: a character of a kept class token or id. */
  public static boolean isClassChar(char c) {
    return isLetter(c) || (c >= '0' && c <= '9') || c == '_' || c == '-';
  }

  /** The value of an ASCII hex digit, or -1. */
  public static int hexValue(int c) {
    if (c >= '0' && c <= '9') {
      return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
      return c - 'a' + 10;
    }
    return c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
  }

  public static boolean isHexDigit(int c) {
    return hexValue(c) >= 0;
  }

  /** HTML whitespace: TAB, LF, FF, CR and SPACE. */
  public static boolean isWhitespace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\f' || c == '\r';
  }

  /**
   * {@code s} with only A-Z lower-cased, as a browser folds names and keywords. Not
   * {@code toLowerCase}: that maps U+212A (Kelvin) to {@code k} and U+0130 to {@code i} plus a
   * combining dot, so a value a browser reads as another would match here.
   */
  public static String lower(String s) {
    char[] cs = s.toCharArray();
    for (int i = 0; i < cs.length; i++) {
      if (cs[i] >= 'A' && cs[i] <= 'Z') {
        cs[i] = (char) (cs[i] + ('a' - 'A'));
      }
    }
    return new String(cs);
  }

  /**
   * {@code s} without leading and trailing HTML whitespace (TAB, LF, FF, CR, SPACE; also CSS
   * whitespace after preprocessing). Not {@code trim}: that also strips U+000B and every other C0
   * control, which a browser keeps.
   */
  public static String stripWhitespace(String s) {
    int start = 0;
    int end = s.length();
    while (start < end && isWhitespace(s.charAt(start))) {
      start++;
    }
    while (end > start && isWhitespace(s.charAt(end - 1))) {
      end--;
    }
    return s.substring(start, end);
  }

  /** An unmodifiable set of {@code names} in their given order. */
  public static Set<String> set(String... names) {
    return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(names)));
  }
}
