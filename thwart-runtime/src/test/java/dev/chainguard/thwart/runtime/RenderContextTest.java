package dev.chainguard.thwart.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;
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
  void contextsWithTheSameServicesAreNotEqual() {
    // #818: a context carries per-render counters, so it has identity, not value, equality.
    Links links = new DefaultLinks("");
    RenderContext a = new RenderContext(Locale.ROOT, Messages.NONE, links, Forms.NONE);
    RenderContext b = new RenderContext(Locale.ROOT, Messages.NONE, links, Forms.NONE);
    a.sequenceId("roles");
    assertNotEquals(a, b);
  }

  @Test
  void previousBeforeAnyIdFails() {
    RenderContext ctx = RenderContext.defaults();
    ctx.nextSequenceId("roles");
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> ctx.previousSequenceId("roles"));
    assertEquals("Cannot obtain previous ID count for ID \"roles\"", e.getMessage());
  }
}
