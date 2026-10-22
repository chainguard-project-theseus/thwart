package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DayOfWeek;
import org.junit.jupiter.api.Test;

class ValuesTest {

  @Test
  void nav() {
    String s = null;
    assertNull(Values.nav(s, String::length));
    assertEquals(3, Values.<String, Integer>nav("abc", String::length));
  }

  @Test
  void elvis() {
    assertEquals("x", Values.elvis(null, "x"));
    assertEquals("a", Values.elvis("a", "x"));
    assertNull(Values.elvis(null, null));
  }

  @Test
  void enumName() {
    assertEquals("MONDAY", Values.enumName(DayOfWeek.MONDAY));
    assertNull(Values.enumName(null));
  }

  @Test
  void numEquals() {
    assertTrue(Values.numEquals(1, 1L));
    assertTrue(Values.numEquals(1, 1.0));
    assertTrue(Values.numEquals((short) 2, (byte) 2));
    assertTrue(Values.numEquals(new BigDecimal("10.00"), 10));
    assertTrue(Values.numEquals(BigInteger.TWO, 2.0));
    assertTrue(Values.numEquals(null, null));
    assertFalse(Values.numEquals(null, 0));
    assertFalse(Values.numEquals(0, null));
    assertFalse(Values.numEquals(1, 2L));
    assertFalse(Values.numEquals(0.1, 0.2));
    assertFalse(Values.numEquals(Double.NaN, BigDecimal.ONE));
    assertFalse(Values.numEquals(Long.MAX_VALUE, Long.MAX_VALUE - 1));
  }
}
