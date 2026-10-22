package dev.tessera.compiler.forms;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FormPathsTest {

  @Test
  void acceptsAsciiIdentifiers() {
    for (String s : new String[] {"a", "Z", "_", "lastName", "_x1", "a_b_9"}) {
      assertTrue(FormPaths.isIdentifier(s), s);
    }
  }

  @Test
  void rejectsEverythingElse() {
    // Non-ASCII letters and digits, '$' (internal names such as select$value), and other
    // punctuation are all rejected.
    for (String s : new String[] {"", "1a", "a-b", "a.b", "a b", "select$value", "$x", "é", "aé", "a١", "Ж"}) {
      assertFalse(FormPaths.isIdentifier(s), s);
    }
  }
}
