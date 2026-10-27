package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** The per-render id counters behind t:field's numbered ids and ids.seq/next/prev (#136, #458). */
class RenderContextTest {
  @Test
  void sequenceIdsFollowThymeleafIdentifierSequences() {
    RenderContext ctx = RenderContext.defaults();
    assertEquals("roles1", ctx.nextSequenceId("roles"));
    assertEquals("roles1", ctx.nextSequenceId("roles"));
    assertEquals("roles1", ctx.sequenceId("roles"));
    assertEquals("roles1", ctx.previousSequenceId("roles"));
    assertEquals("roles2", ctx.nextSequenceId("roles"));
    assertEquals("roles2", ctx.sequenceId("roles"));
    assertEquals("roles2", ctx.previousSequenceId("roles"));
    assertEquals("pet1", ctx.sequenceId("pet"));
    assertEquals("roles3", ctx.nextSequenceId("roles"));
    // A new context starts again.
    assertEquals("roles1", RenderContext.defaults().nextSequenceId("roles"));
  }

  @Test
  void previousBeforeAnyIdFails() {
    RenderContext ctx = RenderContext.defaults();
    ctx.nextSequenceId("roles");
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> ctx.previousSequenceId("roles"));
    assertEquals("Cannot obtain previous ID count for ID \"roles\"", e.getMessage());
  }
}
