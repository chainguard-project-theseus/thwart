package dev.chainguard.thwart.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chainguard.thwart.runtime.internal.Output;
import dev.chainguard.thwart.runtime.internal.TemplateEntry;
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
  void fragmentEntriesAreFoundButKeptOutOfAll() {
    TemplateEntry page =
        new TemplateEntry("list", List.of(), (Map<String, ?> m, RenderContext c, Output o) -> o.write("page"));
    TemplateEntry rows =
        new TemplateEntry("list :: rows", List.of(), (Map<String, ?> m, RenderContext c, Output o) -> o.write("rows"));
    TemplateRegistry r = TemplateRegistry.of(List.of(page), List.of(rows));
    assertEquals(List.of(page), List.copyOf(r.all()));
    assertEquals(List.of(rows), List.copyOf(r.fragments()));
    assertEquals(rows, r.find("list :: rows").orElseThrow());
    assertTrue(r.find("list::rows").isEmpty());
    assertTrue(r.find("list :: rows(x)").isEmpty());
    // A registry that predates fragment entries has none.
    assertTrue(TemplateRegistry.of(List.of(page)).fragments().isEmpty());
  }

  @Test
  void ofRejectsADuplicateNameAcrossTemplatesAndFragments() {
    TemplateEntry a =
        new TemplateEntry("list :: rows", List.of(), (Map<String, ?> m, RenderContext c, Output o) -> o.write("a"));
    TemplateEntry b =
        new TemplateEntry("list :: rows", List.of(), (Map<String, ?> m, RenderContext c, Output o) -> o.write("b"));
    assertThrows(IllegalStateException.class, () -> TemplateRegistry.of(List.of(a), List.of(b)));
    assertThrows(IllegalStateException.class, () -> TemplateRegistry.of(List.of(), List.of(a, b)));
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
