package dev.chainguard.thwart.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JavaStringsTest {

  @Test
  void literalEscapesEverything() {
    assertEquals("\"a\\\"b\\\\c\\n\\r\\t\\001\\u00e9\"", JavaStrings.literal("a\"b\\c\n\r\t\u0001é"));
    // A backslash-u sequence in a template string must not become a unicode escape in Java.
    assertEquals("\"\\\\u0022\"", JavaStrings.literal("\\u0022"));
    assertEquals("\"\\u007f\"", JavaStrings.literal("\u007f"));
  }
}
