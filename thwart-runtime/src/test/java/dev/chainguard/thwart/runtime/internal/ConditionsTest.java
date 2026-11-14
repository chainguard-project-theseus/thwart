package dev.chainguard.thwart.runtime.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConditionsTest {

  @Test
  void nullIsFalse() {
    assertFalse(Conditions.isTrue(null));
  }

  @Test
  void booleans() {
    assertTrue(Conditions.isTrue(Boolean.TRUE));
    assertFalse(Conditions.isTrue(Boolean.FALSE));
  }

  @Test
  void numbers() {
    assertTrue(Conditions.isTrue(1));
    assertTrue(Conditions.isTrue(-1L));
    assertTrue(Conditions.isTrue(0.5));
    assertTrue(Conditions.isTrue(0.1f));
    assertTrue(Conditions.isTrue(new BigDecimal("0.001")));
    assertTrue(Conditions.isTrue(BigInteger.TEN));
    assertTrue(Conditions.isTrue(Double.NaN));
    assertFalse(Conditions.isTrue(0));
    assertFalse(Conditions.isTrue(0L));
    assertFalse(Conditions.isTrue((short) 0));
    assertFalse(Conditions.isTrue((byte) 0));
    assertFalse(Conditions.isTrue(0.0));
    assertFalse(Conditions.isTrue(-0.0f));
    assertFalse(Conditions.isTrue(new BigDecimal("0.000")));
    assertFalse(Conditions.isTrue(BigInteger.ZERO));
  }

  @Test
  void characters() {
    assertTrue(Conditions.isTrue('a'));
    assertTrue(Conditions.isTrue('0'));
    assertFalse(Conditions.isTrue('\0'));
  }

  @Test
  void strings() {
    assertTrue(Conditions.isTrue("yes"));
    assertTrue(Conditions.isTrue("true"));
    assertTrue(Conditions.isTrue(""));
    assertTrue(Conditions.isTrue("0"));
    assertTrue(Conditions.isTrue(" false"));
    assertFalse(Conditions.isTrue("false"));
    assertFalse(Conditions.isTrue("FALSE"));
    assertFalse(Conditions.isTrue("off"));
    assertFalse(Conditions.isTrue("Off"));
    assertFalse(Conditions.isTrue("no"));
    assertFalse(Conditions.isTrue("NO"));
  }

  @Test
  void otherObjectsAreTrue() {
    assertTrue(Conditions.isTrue(List.of()));
    assertTrue(Conditions.isTrue(new Object()));
  }
}
