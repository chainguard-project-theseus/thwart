package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OutputTest {

  @Test
  void stringOutputBuffers() {
    StringOutput out = new StringOutput();
    out.write("<p>");
    out.write('x');
    assertEquals("<p>x", out.toString());
  }
}

