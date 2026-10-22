package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LoopStatusTest {

  @Test
  void loopStatusFollowsThymeleaf() {
    assertEquals(new LoopStatus(0, 1, 3, true, false, false, true), LoopStatus.of(0, 3));
    assertEquals(new LoopStatus(2, 3, 3, false, true, false, true), LoopStatus.of(2, 3));
  }
}
