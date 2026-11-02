package dev.tessera.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.codegen.attr.GenericAttrProcessor;
import org.junit.jupiter.api.Test;

class AttributeProcessorsTest {

  @Test
  void precedenceFollowsThymeleaf() {
    assertTrue(AttributeProcessors.precedence("insert") < AttributeProcessors.precedence("each"));
    assertTrue(AttributeProcessors.precedence("each") < AttributeProcessors.precedence("if"));
    assertTrue(AttributeProcessors.precedence("with") < AttributeProcessors.precedence("href"));
    assertTrue(AttributeProcessors.precedence("class") < AttributeProcessors.precedence("classappend"));
    assertTrue(AttributeProcessors.precedence("href") < AttributeProcessors.precedence("text"));
  }

  @Test
  void unknownNamesAreGenericAttributes() {
    assertEquals(GenericAttrProcessor.class, AttributeProcessors.forName("data-id").getClass());
    assertThrows(IllegalArgumentException.class, () -> AttributeProcessors.forName("params"));
  }
}
