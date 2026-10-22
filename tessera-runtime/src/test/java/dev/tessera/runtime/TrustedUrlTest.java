package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TrustedUrlTest {

  @Test
  void trustedUrlValueSemantics() {
    assertEquals("/x", TrustedUrl.unchecked("/x").url());
    assertEquals("/x", TrustedUrl.unchecked("/x").toString());
    assertEquals(TrustedUrl.unchecked("/x"), TrustedUrl.unchecked("/x"));
    assertEquals(TrustedUrl.unchecked("/x").hashCode(), TrustedUrl.unchecked("/x").hashCode());
    assertNotEquals(TrustedUrl.unchecked("/x"), TrustedUrl.unchecked("/y"));
    assertThrows(NullPointerException.class, () -> TrustedUrl.unchecked(null));
  }
}
