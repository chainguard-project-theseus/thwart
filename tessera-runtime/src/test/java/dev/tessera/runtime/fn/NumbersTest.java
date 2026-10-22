package dev.tessera.runtime.fn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
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
  void sequence() {
    assertEquals(List.of(1, 2, 3, 4), Numbers.sequence(1, 4));
    assertEquals(List.of(3, 2, 1), Numbers.sequence(3, 1));
    assertEquals(List.of(5), Numbers.sequence(5, 5));
    assertEquals(List.of(Integer.MAX_VALUE - 1, Integer.MAX_VALUE),
        Numbers.sequence(Integer.MAX_VALUE - 1, Integer.MAX_VALUE));
  }
}
