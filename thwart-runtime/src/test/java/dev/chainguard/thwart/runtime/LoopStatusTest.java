package dev.chainguard.thwart.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LoopStatusTest {

  @Test
  void loopStatusFollowsThymeleaf() {
    LoopStatus first = LoopStatus.of(0, 3);
    assertEquals(0, first.getIndex());
    assertEquals(1, first.getCount());
    assertEquals(3, first.getSize());
    assertTrue(first.isFirst());
    assertFalse(first.isLast());
    assertFalse(first.isEven());
    assertTrue(first.isOdd());

    LoopStatus last = LoopStatus.of(2, 3);
    assertEquals(2, last.getIndex());
    assertEquals(3, last.getCount());
    assertFalse(last.isFirst());
    assertTrue(last.isLast());
    assertFalse(last.isEven());
    assertTrue(last.isOdd());
    assertTrue(LoopStatus.of(1, 3).isEven());
  }

  @Test
  void equalityIsIndexAndSize() {
    assertEquals(LoopStatus.of(2, 3), LoopStatus.of(2, 3));
    assertEquals(LoopStatus.of(2, 3).hashCode(), LoopStatus.of(2, 3).hashCode());
    assertNotEquals(LoopStatus.of(0, 3), LoopStatus.of(2, 3));
    assertEquals("LoopStatus[index=2, size=3]", LoopStatus.of(2, 3).toString());
  }
}
