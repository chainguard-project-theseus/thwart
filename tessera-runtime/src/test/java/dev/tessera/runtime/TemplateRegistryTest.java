package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateRegistryTest {

  @Test
  void registryOfFindsByName() {
    TemplateEntry e =
        new TemplateEntry("home", List.of(), (Map<String, ?> m, RenderContext c, Output o) -> o.write("hi"));
    TemplateRegistry r = TemplateRegistry.of(List.of(e));
    assertTrue(r.find("home").isPresent());
    assertTrue(r.find("../home").isEmpty());
  }

  @Test
  void ofRejectsDuplicateNames() {
    TemplateEntry a =
        new TemplateEntry("home", List.of(), (Map<String, ?> m, RenderContext c, Output o) -> o.write("a"));
    TemplateEntry aAgain =
        new TemplateEntry("home", List.of(), (Map<String, ?> m, RenderContext c, Output o) -> o.write("b"));
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> TemplateRegistry.of(List.of(a, aAgain)));
    assertTrue(ex.getMessage().contains("home"), ex.getMessage());
  }
}
