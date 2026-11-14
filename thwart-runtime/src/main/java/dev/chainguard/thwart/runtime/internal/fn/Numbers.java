package dev.chainguard.thwart.runtime.internal.fn;

import dev.chainguard.thwart.runtime.TemplateFunction;
import dev.chainguard.thwart.runtime.TemplateFunctions;
import dev.chainguard.thwart.runtime.internal.Checks;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Built-in {@code numbers} namespace (Thymeleaf's {@code #numbers}). Always registered.
 *
 * <p>The leading {@link Locale} parameter is the render locale ({@code RenderContext.locale()}),
 * supplied by the compiler; templates pass only the remaining arguments. Formatting follows
 * Thymeleaf's {@code NumberUtils}: the locale's digits, decimal point and grouping separator,
 * which a point type ({@code 'POINT'}, {@code 'COMMA'}, {@code 'WHITESPACE'}, {@code 'DEFAULT'}
 * for the locale's own, and for grouping {@code 'NONE'} for none) can override. Unlike Thymeleaf,
 * rounding is half-up. A {@code null} number formats as {@code null} (renders as nothing).
 *
 * <p>A number reaching more than 10,000 places from the decimal point, and a digit count
 * ({@code minIntDigits}, {@code minDigits}, {@code decimals}) above 10,000, throw
 * {@link IllegalArgumentException}: the bound of {@link Checks#requireBoundedMagnitude}, whose
 * rationale is on {@code Escapers.messageArgs}. A number of any {@link Number} type but a JDK
 * {@code BigDecimal} (a subclass included) is first converted to one from its {@code toString()},
 * and the converted value is checked and formatted.
 * {@code formatPercent} multiplies by 100, which adds two digits.
 */
@TemplateFunctions("numbers")
public final class Numbers {
  private Numbers() {}

  /** The bound on how far a value reaches from the decimal point, and on a requested digit count. */
  private static final int MAX_DIGITS = 10000;

  /** {@code n} rounded to an integer and left-padded with zeros to {@code minDigits} digits, ungrouped. */
  @TemplateFunction
  public static String formatInteger(Locale locale, Object n, int minDigits) {
    return format(locale, n, minDigits, "NONE", 0, "DEFAULT");
  }

  /**
   * {@code n} rounded to an integer, left-padded with zeros to {@code minDigits} digits and grouped
   * in thousands with {@code thousandsPoint}: {@code formatInteger(1234567, 1, 'COMMA')} is
   * {@code 1,234,567}.
   */
  @TemplateFunction
  public static String formatInteger(Locale locale, Object n, int minDigits, String thousandsPoint) {
    return format(locale, n, minDigits, thousandsPoint, 0, "DEFAULT");
  }

  /** {@code n} with at least {@code minIntDigits} integer digits and exactly {@code decimals} decimals, ungrouped. */
  @TemplateFunction
  public static String formatDecimal(Locale locale, Object n, int minIntDigits, int decimals) {
    return format(locale, n, minIntDigits, "NONE", decimals, "DEFAULT");
  }

  /** As {@link #formatDecimal(Locale, Object, int, int)}, with {@code decimalPoint} as the decimal point. */
  @TemplateFunction
  public static String formatDecimal(Locale locale, Object n, int minIntDigits, int decimals, String decimalPoint) {
    return format(locale, n, minIntDigits, "NONE", decimals, decimalPoint);
  }

  /**
   * {@code n} grouped with {@code thousandsPoint}, with {@code decimals} decimals after
   * {@code decimalPoint}: {@code formatDecimal(1234.5, 1, 'POINT', 2, 'COMMA')} is {@code 1.234,50}.
   */
  @TemplateFunction
  public static String formatDecimal(
      Locale locale, Object n, int minIntDigits, String thousandsPoint, int decimals, String decimalPoint) {
    return format(locale, n, minIntDigits, thousandsPoint, decimals, decimalPoint);
  }

  /** {@code n} as a percentage of 1 in the locale's format: {@code formatPercent(0.125, 1, 1)} is {@code 12.5%}. */
  @TemplateFunction
  public static String formatPercent(Locale locale, Object n, int minIntDigits, int decimals) {
    if (n == null) {
      return null;
    }
    BigDecimal value = toBigDecimal(n);
    checkDigits(minIntDigits, decimals);
    NumberFormat format = NumberFormat.getPercentInstance(locale);
    format.setMinimumIntegerDigits(minIntDigits);
    format.setMinimumFractionDigits(decimals);
    format.setMaximumFractionDigits(decimals);
    format.setRoundingMode(RoundingMode.HALF_UP);
    return format.format(value);
  }

  /**
   * {@code n} as an amount in the locale's currency ({@link NumberFormat#getCurrencyInstance}): a
   * locale without a country, such as {@code en}, has no currency and prints {@code ¤}.
   */
  @TemplateFunction
  public static String formatCurrency(Locale locale, Object n) {
    if (n == null) {
      return null;
    }
    NumberFormat format = NumberFormat.getCurrencyInstance(locale);
    format.setRoundingMode(RoundingMode.HALF_UP);
    return format.format(toBigDecimal(n));
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

  private static String format(
      Locale locale, Object n, int minIntDigits, String thousandsPoint, int decimals, String decimalPoint) {
    if (n == null) {
      return null;
    }
    DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(locale);
    char grouping = point(thousandsPoint, true);
    char decimal = point(decimalPoint, false);
    if (grouping != DEFAULT && grouping != NO_GROUPING) {
      symbols.setGroupingSeparator(grouping);
    }
    if (decimal != DEFAULT) {
      symbols.setDecimalSeparator(decimal);
    }
    BigDecimal value = toBigDecimal(n);
    checkDigits(minIntDigits, decimals);
    NumberFormat localeFormat = NumberFormat.getNumberInstance(locale);
    DecimalFormat format = localeFormat instanceof DecimalFormat ? (DecimalFormat) localeFormat : new DecimalFormat("#,##0.###");
    format.setDecimalFormatSymbols(symbols);
    format.setGroupingUsed(grouping != NO_GROUPING);
    format.setMinimumIntegerDigits(minIntDigits);
    format.setMinimumFractionDigits(decimals);
    format.setMaximumFractionDigits(decimals);
    format.setRoundingMode(RoundingMode.HALF_UP);
    return format.format(value);
  }

  /** {@link #point} results that are not a separator: the locale's own, and no grouping. */
  private static final char DEFAULT = 0;
  private static final char NO_GROUPING = 1;

  /**
   * The separator for a Thymeleaf point type, {@link #DEFAULT} for {@code 'DEFAULT'}, or
   * {@link #NO_GROUPING} for {@code 'NONE'} (thousands only).
   */
  private static char point(String type, boolean grouping) {
    if ("DEFAULT".equals(type)) {
      return DEFAULT;
    }
    if ("POINT".equals(type)) {
      return '.';
    }
    if ("COMMA".equals(type)) {
      return ',';
    }
    if ("WHITESPACE".equals(type)) {
      return ' ';
    }
    if (grouping && "NONE".equals(type)) {
      return NO_GROUPING;
    }
    throw new IllegalArgumentException(
        "unknown " + (grouping ? "thousands" : "decimal") + " point type '" + type + "': use 'POINT', 'COMMA', 'WHITESPACE'"
            + (grouping ? ", 'NONE'" : "") + " or 'DEFAULT'");
  }

  private static void checkDigits(int minIntDigits, int decimals) {
    if (minIntDigits > MAX_DIGITS || decimals > MAX_DIGITS) {
      throw new IllegalArgumentException("cannot format more than " + MAX_DIGITS + " integer or fraction digits");
    }
  }

  private static BigDecimal toBigDecimal(Object n) {
    if (!(n instanceof Number)) {
      throw new IllegalArgumentException("not a number: " + n.getClass().getName());
    }
    if ((n instanceof Double || n instanceof Float) && !Double.isFinite(((Number) n).doubleValue())) {
      throw new IllegalArgumentException("not finite: " + n);
    }
    // Any Number can say 1e999999999 (Gson's LazilyParsedNumber does), and so can a BigDecimal
    // subclass whose own precision() and scale() say 1, since DecimalFormat copies its toString().
    // So anything but a JDK BigDecimal is converted from its text, and the bound is checked on, and
    // DecimalFormat formats, the converted value.
    BigDecimal d = n.getClass() == BigDecimal.class ? (BigDecimal) n : new BigDecimal(n.toString());
    return Checks.requireBoundedMagnitude(
        d, "cannot format a number more than " + MAX_DIGITS + " places from the decimal point");
  }
}
