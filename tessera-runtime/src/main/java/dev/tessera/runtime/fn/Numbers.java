package dev.tessera.runtime.fn;

import dev.tessera.runtime.TemplateFunction;
import dev.tessera.runtime.TemplateFunctions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Built-in {@code numbers} namespace (Thymeleaf's {@code #numbers}). Always registered.
 *
 * <p>Formatting uses no grouping separator, {@code .} as decimal point ({@link Locale#ROOT}
 * symbols) and half-up rounding. A {@code null} number formats as {@code null} (renders as
 * nothing).
 */
@TemplateFunctions("numbers")
public final class Numbers {
  private Numbers() {}

  /** {@code n} rounded to an integer and left-padded with zeros to {@code minDigits} digits. */
  @TemplateFunction
  public static String formatInteger(Object n, int minDigits) {
    return format(n, minDigits, 0);
  }

  /** {@code n} with at least {@code minIntDigits} integer digits and exactly {@code decimals} decimals. */
  @TemplateFunction
  public static String formatDecimal(Object n, int minIntDigits, int decimals) {
    return format(n, minIntDigits, decimals);
  }

  /**
   * The integers from {@code from} to {@code to}, both inclusive, counting down if {@code to <
   * from}. Used for pagination ({@code t:each="i : ${numbers.sequence(1, totalPages)}"}).
   */
  @TemplateFunction
  public static List<Integer> sequence(int from, int to) {
    int step = to >= from ? 1 : -1;
    List<Integer> result = new ArrayList<>();
    for (long i = from; step > 0 ? i <= to : i >= to; i += step) {
      result.add((int) i);
    }
    return result;
  }

  private static String format(Object n, int minIntDigits, int decimals) {
    if (n == null) {
      return null;
    }
    if (!(n instanceof Number number)) {
      throw new IllegalArgumentException("not a number: " + n.getClass().getName());
    }
    DecimalFormat format = new DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ROOT));
    format.setGroupingUsed(false);
    format.setMinimumIntegerDigits(minIntDigits);
    format.setMinimumFractionDigits(decimals);
    format.setMaximumFractionDigits(decimals);
    format.setRoundingMode(RoundingMode.HALF_UP);
    return format.format(toBigDecimal(number));
  }

  private static BigDecimal toBigDecimal(Number n) {
    return switch (n) {
      case BigDecimal b -> b;
      case Double d when d.isNaN() || d.isInfinite() -> throw new IllegalArgumentException("not finite: " + d);
      case Float f when f.isNaN() || f.isInfinite() -> throw new IllegalArgumentException("not finite: " + f);
      default -> new BigDecimal(n.toString());
    };
  }
}
