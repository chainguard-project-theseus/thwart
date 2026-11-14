package dev.chainguard.thwart.runtime.internal;

import java.math.BigDecimal;
import java.math.BigInteger;

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
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    if (value instanceof BigDecimal) {
      return ((BigDecimal) value).signum() != 0;
    }
    if (value instanceof BigInteger) {
      return ((BigInteger) value).signum() != 0;
    }
    if (value instanceof Double || value instanceof Float) {
      return ((Number) value).doubleValue() != 0.0;
    }
    if (value instanceof Number) {
      return ((Number) value).longValue() != 0L;
    }
    if (value instanceof Character) {
      return (Character) value != '\0';
    }
    if (value instanceof String) {
      String v = Ascii.lower((String) value);
      return !(v.equals("false") || v.equals("off") || v.equals("no"));
    }
    return true;
  }
}
