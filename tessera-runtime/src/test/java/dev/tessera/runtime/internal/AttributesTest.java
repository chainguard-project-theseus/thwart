package dev.tessera.runtime.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class AttributesTest {

  @Test
  void appendClass() {
    assertEquals("a b", Attributes.appendClass("a", "b"));
    assertEquals("b", Attributes.appendClass(null, "b"));
    assertEquals("b", Attributes.appendClass("", "b"));
    assertEquals("a", Attributes.appendClass("a", null));
    assertEquals("a", Attributes.appendClass("a", ""));
    assertEquals("", Attributes.appendClass("", null));
    assertNull(Attributes.appendClass(null, null));
    assertNull(Attributes.appendClass(null, ""));
    assertEquals("a 1", Attributes.appendClass("a", 1));
    // #586: no class token is dropped any more, ng-like or not
    assertEquals("a ng-init:x ngBind", Attributes.appendClass("a", "ng-init:x ngBind"));
  }
}
