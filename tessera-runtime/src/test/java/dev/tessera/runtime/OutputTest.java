package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.StringWriter;
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
}

