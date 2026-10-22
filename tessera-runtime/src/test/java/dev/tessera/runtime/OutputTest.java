package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OutputTest {

  @Test
  void stringOutputBuffers() {
    StringOutput out = new StringOutput();
    out.write("<p>");
    out.write('x');
    assertEquals("<p>x", out.toString());
  }

  @Test
  void writerOutputWrites() {
    StringWriter w = new StringWriter();
    WriterOutput out = new WriterOutput(w);
    out.write("ab");
    out.write('c');
    assertEquals("abc", w.toString());
  }

  @Test
  void registryOfFindsByName() {
    TemplateEntry e =
        new TemplateEntry("home", List.of(), (Map<String, ?> m, RenderContext c, Output o) -> o.write("hi"));
    TemplateRegistry r = TemplateRegistry.of(List.of(e));
    assertTrue(r.find("home").isPresent());
    assertTrue(r.find("../home").isEmpty());
  }

  @Test
  void loopStatusFollowsThymeleaf() {
    assertEquals(new LoopStatus(0, 1, 3, true, false, false, true), LoopStatus.of(0, 3));
    assertEquals(new LoopStatus(2, 3, 3, false, true, false, true), LoopStatus.of(2, 3));
  }
}
