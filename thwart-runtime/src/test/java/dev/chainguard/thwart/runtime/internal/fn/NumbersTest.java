package dev.chainguard.thwart.runtime.internal.fn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class NumbersTest {
  @Test
  void formatInteger() {
    assertEquals("007", Numbers.formatInteger(Locale.ROOT, 7, 3));
    assertEquals("12345", Numbers.formatInteger(Locale.ROOT, 12345L, 3));
    assertEquals("-07", Numbers.formatInteger(Locale.ROOT, -7, 2));
    assertEquals("2", Numbers.formatInteger(Locale.ROOT, 2.5, 1));
    assertEquals("4", Numbers.formatInteger(Locale.ROOT, 3.5, 1));
    assertEquals("123456789012345678901234567890",
        Numbers.formatInteger(Locale.ROOT, new BigInteger("123456789012345678901234567890"), 1));
    assertNull(Numbers.formatInteger(Locale.ROOT, null, 2));
  }

  @Test
  void formatDecimal() {
    assertEquals("3.14", Numbers.formatDecimal(Locale.ROOT, 3.14159, 1, 2));
    assertEquals("03.00", Numbers.formatDecimal(Locale.ROOT, 3, 2, 2));
    assertEquals("0.12", Numbers.formatDecimal(Locale.ROOT, new BigDecimal("0.125"), 1, 2));
    assertEquals("0.10", Numbers.formatDecimal(Locale.ROOT, 0.1f, 1, 2));
    assertEquals("1234567.50", Numbers.formatDecimal(Locale.ROOT, 1234567.5, 1, 2));
    assertNull(Numbers.formatDecimal(Locale.ROOT, null, 1, 2));
  }

  @Test
  void groupingAndDecimalPointTypes() {
    assertEquals("1,234,567", Numbers.formatInteger(Locale.ROOT, 1234567, 1, "COMMA"));
    assertEquals("1.234.567", Numbers.formatInteger(Locale.ROOT, 1234567, 1, "POINT"));
    assertEquals("1 234 567", Numbers.formatInteger(Locale.ROOT, 1234567, 1, "WHITESPACE"));
    assertEquals("1234567", Numbers.formatInteger(Locale.ROOT, 1234567, 1, "NONE"));
    assertEquals("-2,550", Numbers.formatInteger(Locale.US, -2550, 1, "DEFAULT"));
    assertEquals("-2.550", Numbers.formatInteger(Locale.GERMANY, -2550, 1, "DEFAULT"));
    assertEquals("3,14", Numbers.formatDecimal(Locale.ROOT, 3.14159, 1, 2, "COMMA"));
    assertEquals("3.14", Numbers.formatDecimal(Locale.GERMANY, 3.14159, 1, 2, "POINT"));
    assertEquals("1.234.567,50", Numbers.formatDecimal(Locale.ROOT, 1234567.5, 1, "POINT", 2, "COMMA"));
    assertEquals("1,234,567.50", Numbers.formatDecimal(Locale.GERMANY, 1234567.5, 1, "COMMA", 2, "POINT"));
    assertEquals("1.234.567,50", Numbers.formatDecimal(Locale.GERMANY, 1234567.5, 1, "DEFAULT", 2, "DEFAULT"));
    assertNull(Numbers.formatInteger(Locale.ROOT, null, 1, "COMMA"));
    assertThrows(IllegalArgumentException.class, () -> Numbers.formatInteger(Locale.ROOT, 1, 1, "comma"));
    assertThrows(IllegalArgumentException.class, () -> Numbers.formatDecimal(Locale.ROOT, 1, 1, 2, "NONE"));
    assertThrows(IllegalArgumentException.class, () -> Numbers.formatDecimal(Locale.ROOT, 1, 1, 2, null));
  }

  @Test
  void decimalPointFollowsTheLocale() {
    assertEquals("3,14", Numbers.formatDecimal(Locale.GERMANY, 3.14159, 1, 2));
    assertEquals("1234567,50", Numbers.formatDecimal(Locale.GERMANY, 1234567.5, 1, 2));
    assertEquals("3.14", Numbers.formatDecimal(Locale.US, 3.14159, 1, 2));
    assertEquals("2550", Numbers.formatInteger(Locale.GERMANY, 2550, 1));
  }

  @Test
  void percentAndCurrency() {
    assertEquals("12.5%", Numbers.formatPercent(Locale.US, 0.125, 1, 1));
    assertEquals("12%", Numbers.formatPercent(Locale.US, 0.125, 1, 0));
    assertEquals("$1,234.50", Numbers.formatCurrency(Locale.US, 1234.5));
    assertEquals("1.234,50\u00a0\u20ac", Numbers.formatCurrency(Locale.GERMANY, new BigDecimal("1234.5")));
    assertNull(Numbers.formatPercent(Locale.US, null, 1, 1));
    assertNull(Numbers.formatCurrency(Locale.US, null));
    assertThrows(IllegalArgumentException.class, () -> Numbers.formatCurrency(Locale.US, "1"));
  }

  /**
   * #917: Thymeleaf's NumberUtils hands the Number itself to a DecimalFormat with its default
   * rounding (half-even), so a double rounds from its exact binary value: 16.65 is 16.6499...
   */
  @Test
  void roundsAsThymeleafDoes() {
    for (Object n : List.of(16.65, 16.75, 16.85, 0.125, 2.5, -2.5, 1.005, 16.65f, 0.1f,
        new BigDecimal("16.65"), new BigDecimal("16.85"), new BigDecimal("-0.125"), 7L)) {
      DecimalFormat thymeleaf = (DecimalFormat) java.text.NumberFormat.getNumberInstance(Locale.ROOT);
      thymeleaf.setMinimumFractionDigits(1);
      thymeleaf.setMaximumFractionDigits(1);
      thymeleaf.setGroupingUsed(false);
      assertEquals(thymeleaf.format(n), Numbers.formatDecimal(Locale.ROOT, n, 1, 1), String.valueOf(n));
      java.text.NumberFormat percent = java.text.NumberFormat.getPercentInstance(Locale.US);
      percent.setMinimumFractionDigits(0);
      percent.setMaximumFractionDigits(0);
      assertEquals(percent.format(n), Numbers.formatPercent(Locale.US, n, 1, 0), String.valueOf(n));
      assertEquals(
          java.text.NumberFormat.getCurrencyInstance(Locale.US).format(n),
          Numbers.formatCurrency(Locale.US, n),
          String.valueOf(n));
    }
    assertEquals("16,6", Numbers.formatDecimal(Locale.ROOT, 16.65, 1, "POINT", 1, "COMMA"));
    assertEquals("16,9", Numbers.formatDecimal(Locale.ROOT, 16.85, 1, "POINT", 1, "COMMA"));
    assertEquals("16,8", Numbers.formatDecimal(Locale.ROOT, new BigDecimal("16.85"), 1, "POINT", 1, "COMMA"));
  }

  @Test
  void formatRejectsNonNumbersAndNonFinite() {
    assertThrows(IllegalArgumentException.class, () -> Numbers.formatInteger(Locale.ROOT, "7", 1));
    assertThrows(IllegalArgumentException.class, () -> Numbers.formatDecimal(Locale.ROOT, Double.NaN, 1, 2));
    assertThrows(IllegalArgumentException.class, () -> Numbers.formatDecimal(Locale.ROOT, Float.POSITIVE_INFINITY, 1, 2));
  }

  @Test
  void configurationsRemainIndependentAcrossCallsAndThreads() throws Exception {
    // Exercise different padding/scale, including the JDK's clamping of negative settings.
    List<Callable<Void>> calls = new ArrayList<>();
    for (int task = 0; task < 16; task++) {
      final int digits = task % 8 - 1;
      final int decimals = task % 5 - 1;
      final BigDecimal value = new BigDecimal(task % 2 == 0 ? "-0.125" : "12345.675");
      DecimalFormat reference = new DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ROOT));
      reference.setGroupingUsed(false);
      reference.setMinimumIntegerDigits(digits);
      reference.setMinimumFractionDigits(decimals);
      reference.setMaximumFractionDigits(decimals);
      reference.setRoundingMode(RoundingMode.HALF_EVEN);
      String expected = reference.format(value);
      calls.add(() -> {
        for (int i = 0; i < 500; i++) {
          assertEquals(expected, Numbers.formatDecimal(Locale.ROOT, value, digits, decimals));
        }
        return null;
      });
    }
    try (var executor = Executors.newFixedThreadPool(8)) {
      for (var result : executor.invokeAll(calls)) {
        result.get();
      }
    }
    assertEquals("2", Numbers.formatInteger(Locale.ROOT, 2.5, 1));
    assertNull(Numbers.formatDecimal(Locale.ROOT, null, -1, -1));
  }

  /** Every function that hands a value to DecimalFormat, with the default digit counts. */
  private static final List<java.util.function.Function<Object, String>> FORMATS =
      List.of(
          n -> Numbers.formatInteger(Locale.ROOT, n, 1),
          n -> Numbers.formatInteger(Locale.ROOT, n, 1, "COMMA"),
          n -> Numbers.formatDecimal(Locale.ROOT, n, 1, 2),
          n -> Numbers.formatDecimal(Locale.ROOT, n, 1, 2, "COMMA"),
          n -> Numbers.formatDecimal(Locale.ROOT, n, 1, "POINT", 2, "COMMA"),
          n -> Numbers.formatPercent(Locale.ROOT, n, 1, 1),
          n -> Numbers.formatCurrency(Locale.US, n));

  @Test
  void bigDecimalsFarFromThePointFailFast() {
    for (String value : new String[] {"1E+999999999", "-1E+999999999", "1E+10000", "1E-10001", "1E-999999999"}) {
      for (var format : FORMATS) {
        assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () -> assertThrows(IllegalArgumentException.class, () -> format.apply(new BigDecimal(value))),
            value);
      }
    }
  }

  /** A Number that holds its text, as Gson's LazilyParsedNumber does. */
  private static final class TextNumber extends Number {
    private static final long serialVersionUID = 1L;
    private final String text;

    TextNumber(String text) {
      this.text = text;
    }

    @Override
    public int intValue() {
      return 0;
    }

    @Override
    public long longValue() {
      return 0;
    }

    @Override
    public float floatValue() {
      return 0;
    }

    @Override
    public double doubleValue() {
      return 0;
    }

    @Override
    public String toString() {
      return text;
    }
  }

  @Test
  void otherNumbersFarFromThePointFailFast() {
    for (var format : FORMATS) {
      assertTimeoutPreemptively(
          Duration.ofSeconds(5),
          () -> assertThrows(IllegalArgumentException.class, () -> format.apply(new TextNumber("1e999999999"))));
    }
    assertEquals("1,234.50", Numbers.formatDecimal(Locale.ROOT, new TextNumber("1234.5"), 1, "COMMA", 2, "POINT"));
  }

  /** An application subclass of BigDecimal whose own precision and scale say 1 but whose toString() is its label. */
  private static final class LabeledDecimal extends BigDecimal {
    private static final long serialVersionUID = 1L;
    private final String label;

    LabeledDecimal(String label) {
      super(1);
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  @Test
  void bigDecimalSubclassesAreCheckedAndFormattedAsTheJdkValueOfTheirText() {
    // DecimalFormat copies a BigDecimal's toString() as digits, so the subclass's own precision()
    // and scale() must not decide the bound.
    assertEquals(1, new LabeledDecimal("1E+999999999").precision());
    for (var format : FORMATS) {
      assertTimeoutPreemptively(
          Duration.ofSeconds(5),
          () -> assertThrows(IllegalArgumentException.class, () -> format.apply(new LabeledDecimal("1E+999999999"))));
      assertThrows(IllegalArgumentException.class, () -> format.apply(new LabeledDecimal("1E-10001")));
      assertThrows(IllegalArgumentException.class, () -> format.apply(new LabeledDecimal("<b>")));
    }
    // Within the bound, the JDK value of the text is what is formatted.
    assertEquals("1,234.50", Numbers.formatDecimal(Locale.ROOT, new LabeledDecimal("1234.5"), 1, "COMMA", 2, "POINT"));
    assertEquals("1,000", Numbers.formatInteger(Locale.ROOT, new LabeledDecimal("1E+3"), 1, "COMMA"));
  }

  @Test
  void bigDecimalsWithinTheBoundFormatAsBefore() {
    assertEquals("1,000", Numbers.formatInteger(Locale.ROOT, new BigDecimal("1E+3"), 1, "COMMA"));
    assertEquals("1234.50", Numbers.formatDecimal(Locale.ROOT, new BigDecimal("1234.5"), 1, 2));
    assertEquals("0.00", Numbers.formatDecimal(Locale.ROOT, new BigDecimal("1E-10000"), 1, 2));
    assertEquals("$1,234.50", Numbers.formatCurrency(Locale.US, new BigDecimal("1234.5")));
    // 10,000 integer digits is the most that formats; formatPercent's x100 adds two.
    assertEquals(10000, Numbers.formatInteger(Locale.ROOT, new BigDecimal("1E+9999"), 1).length());
    assertEquals(13336, Numbers.formatPercent(Locale.ROOT, new BigDecimal("1E+9999"), 1, 0).length());
    // Zero formats as 0 whatever its scale.
    assertEquals("0", Numbers.formatInteger(Locale.ROOT, new BigDecimal("0E+10001"), 1));
    assertEquals("0.00", Numbers.formatDecimal(Locale.ROOT, new BigDecimal(BigInteger.ZERO, 10002), 1, 2));
  }

  @Test
  void digitCountsAboveTheBoundFailFast() {
    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
      assertThrows(IllegalArgumentException.class, () -> Numbers.formatInteger(Locale.ROOT, 7, 1000000000));
      assertThrows(IllegalArgumentException.class, () -> Numbers.formatDecimal(Locale.ROOT, 7, 1, 1000000000));
      assertThrows(IllegalArgumentException.class, () -> Numbers.formatPercent(Locale.ROOT, 7, 1, 1000000000));
      assertThrows(IllegalArgumentException.class, () -> Numbers.formatPercent(Locale.ROOT, 7, 10001, 0));
    });
    assertEquals(10000, Numbers.formatInteger(Locale.ROOT, 7, 10000).length());
    assertEquals(10002, Numbers.formatDecimal(Locale.ROOT, 7, 1, 10000).length());
  }

  @Test
  void sequence() {
    assertEquals(List.of(1, 2, 3, 4), Numbers.sequence(1, 4));
    assertEquals(List.of(3, 2, 1), Numbers.sequence(3, 1));
    assertEquals(List.of(5), Numbers.sequence(5, 5));
    assertEquals(List.of(Integer.MAX_VALUE - 1, Integer.MAX_VALUE),
        Numbers.sequence(Integer.MAX_VALUE - 1, Integer.MAX_VALUE));
  }
}
