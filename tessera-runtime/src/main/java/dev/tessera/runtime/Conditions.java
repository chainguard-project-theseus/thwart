package dev.tessera.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;

/** Thymeleaf-compatible truthiness for {@code t:if}/{@code t:unless} on non-boolean values. */
public final class Conditions {
  private Conditions() {}

  /**
   * {@code null} is false; Boolean is its value; Number is {@code != 0}; Character is
   * {@code != '\0'}; a String is false only for {@code "false"}, {@code "off"}, {@code "no"}
   * (case-insensitive); anything else non-null is true.
   */
  public static boolean isTrue(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof BigDecimal d) {
      return d.signum() != 0;
    }
    if (value instanceof BigInteger i) {
      return i.signum() != 0;
    }
    if (value instanceof Double || value instanceof Float) {
      return ((Number) value).doubleValue() != 0.0;
    }
    if (value instanceof Number n) {
      return n.longValue() != 0L;
    }
    if (value instanceof Character c) {
      return c != '\0';
    }
    if (value instanceof String s) {
      String v = s.toLowerCase(Locale.ROOT);
      return !(v.equals("false") || v.equals("off") || v.equals("no"));
    }
    return true;
  }
}
