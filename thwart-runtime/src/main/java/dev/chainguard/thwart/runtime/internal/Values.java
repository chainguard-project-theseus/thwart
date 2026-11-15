package dev.chainguard.thwart.runtime.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.function.Function;

/**
 * Small helpers called by generated template code (null-safe navigation, {@code ?:}, numeric
 * and enum equality). Not intended for application code.
 */
public final class Values {
  private Values() {}

  /** {@code target?.x}: {@code null} if {@code target} is null, else {@code step(target)}. */
  public static <T, R> R nav(T target, Function<? super T, ? extends R> step) {
    return target == null ? null : step.apply(target);
  }

  /** {@code value ?: fallback}. */
  public static <T> T elvis(T value, T fallback) {
    return value != null ? value : fallback;
  }

  /** {@code list.first}: the first element, or null if the list is empty (so {@code list.first?.x} works). */
  public static <T> T first(List<T> list) {
    return list.isEmpty() ? null : list.get(0);
  }

  /** {@code list.last}: the last element, or null if the list is empty. */
  public static <T> T last(List<T> list) {
    return list.isEmpty() ? null : list.get(list.size() - 1);
  }

  /** The constant name of {@code value}, or null. Used for {@code status == 'ACTIVE'}. */
  public static String enumName(Enum<?> value) {
    return value == null ? null : value.name();
  }

  /**
   * {@code ==} on numbers with numeric promotion: {@code 1 == 1L} and {@code 1 == 1.0} are true;
   * two nulls are equal, null and a number are not.
   */
  public static boolean numEquals(Number a, Number b) {
    if (a == null || b == null) {
      return a == b;
    }
    if (isIntegral(a) && isIntegral(b)) {
      return a.longValue() == b.longValue();
    }
    boolean big = a instanceof BigDecimal || a instanceof BigInteger || b instanceof BigDecimal || b instanceof BigInteger;
    if (big && isFinite(a) && isFinite(b)) {
      return toBigDecimal(a).compareTo(toBigDecimal(b)) == 0;
    }
    return a.doubleValue() == b.doubleValue();
  }

  private static boolean isIntegral(Number n) {
    return n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte;
  }

  private static boolean isFinite(Number n) {
    return !(n instanceof Double || n instanceof Float) || Double.isFinite(n.doubleValue());
  }

  private static BigDecimal toBigDecimal(Number n) {
    if (n instanceof BigDecimal) {
      return (BigDecimal) n;
    }
    if (n instanceof BigInteger) {
      return new BigDecimal((BigInteger) n);
    }
    if (isIntegral(n)) {
      return BigDecimal.valueOf(n.longValue());
    }
    return BigDecimal.valueOf(n.doubleValue());
  }
}
