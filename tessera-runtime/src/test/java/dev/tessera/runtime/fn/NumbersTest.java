package dev.tessera.runtime.fn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class NumbersTest {
  @Test
  void formatInteger() {
    assertEquals("007", Numbers.formatInteger(7, 3));
    assertEquals("12345", Numbers.formatInteger(12345L, 3));
    assertEquals("-07", Numbers.formatInteger(-7, 2));
    assertEquals("3", Numbers.formatInteger(2.5, 1));
    assertEquals("123456789012345678901234567890",
        Numbers.formatInteger(new BigInteger("123456789012345678901234567890"), 1));
    assertNull(Numbers.formatInteger(null, 2));
  }

  @Test
  void formatDecimal() {
    assertEquals("3.14", Numbers.formatDecimal(3.14159, 1, 2));
    assertEquals("03.00", Numbers.formatDecimal(3, 2, 2));
    assertEquals("0.13", Numbers.formatDecimal(new BigDecimal("0.125"), 1, 2));
    assertEquals("0.10", Numbers.formatDecimal(0.1f, 1, 2));
    assertEquals("1234567.50", Numbers.formatDecimal(1234567.5, 1, 2));
    assertNull(Numbers.formatDecimal(null, 1, 2));
  }

  @Test
  void formatRejectsNonNumbersAndNonFinite() {
    assertThrows(IllegalArgumentException.class, () -> Numbers.formatInteger("7", 1));
    assertThrows(IllegalArgumentException.class, () -> Numbers.formatDecimal(Double.NaN, 1, 2));
    assertThrows(IllegalArgumentException.class, () -> Numbers.formatDecimal(Float.POSITIVE_INFINITY, 1, 2));
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
      reference.setRoundingMode(RoundingMode.HALF_UP);
      String expected = reference.format(value);
      calls.add(() -> {
        for (int i = 0; i < 500; i++) {
          assertEquals(expected, Numbers.formatDecimal(value, digits, decimals));
        }
        return null;
      });
    }
    try (var executor = Executors.newFixedThreadPool(8)) {
      for (var result : executor.invokeAll(calls)) {
        result.get();
      }
    }
    assertEquals("3", Numbers.formatInteger(2.5, 1));
    assertNull(Numbers.formatDecimal(null, -1, -1));
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
