package dev.chainguard.thwart.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SourcePosTest {

  @Test
  void advanceTracksLinesAndColumns() {
    SourcePos start = new SourcePos("a.html", 3, 10);
    assertEquals(new SourcePos("a.html", 3, 13), start.advance("abcdef", 3));
    assertEquals(new SourcePos("a.html", 4, 2), start.advance("ab\ncd", 4));
  }

  @Test
  void diagnosticFormat() {
    Diagnostic d = new Diagnostic(Diagnostic.Severity.ERROR, new SourcePos("x/y.html", 1, 4), "boom");
    assertEquals("x/y.html:1:4: error: boom", d.toString());
  }
}
