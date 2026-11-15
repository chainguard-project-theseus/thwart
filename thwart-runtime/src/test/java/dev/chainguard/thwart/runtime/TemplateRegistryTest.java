package dev.chainguard.thwart.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chainguard.thwart.runtime.internal.Output;
import dev.chainguard.thwart.runtime.internal.TemplateEntry;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

  /** A registry generated before the ABI check, or by another release's compiler, fails to load. */
  @Test
  void loadRejectsARegistryFromAnotherCompiler(@TempDir Path dir) throws Exception {
    assertEquals(1, TemplateRegistry.load(loader(dir, CurrentRegistry.class)).all().size());
    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> TemplateRegistry.load(loader(dir, OldRegistry.class)));
    assertTrue(ex.getMessage().contains(OldRegistry.class.getName()), ex.getMessage());
    assertTrue(ex.getMessage().contains("(ABI 0)"), ex.getMessage());
  }

  private static ClassLoader loader(Path dir, Class<?> provider) throws Exception {
    Path services = dir.resolve(provider.getSimpleName() + "/META-INF/services");
    Files.createDirectories(services);
    Files.write(services.resolve(TemplateRegistry.class.getName()), provider.getName().getBytes(StandardCharsets.UTF_8));
    return new URLClassLoader(new URL[] {services.getParent().getParent().toUri().toURL()}, TemplateRegistryTest.class.getClassLoader());
  }

  public static class OldRegistry implements TemplateRegistry {
    @Override
    public java.util.Optional<TemplateEntry> find(String viewName) {
      return java.util.Optional.empty();
    }

    @Override
    public java.util.Collection<TemplateEntry> all() {
      return List.of(new TemplateEntry("home", List.of(), (Map<String, ?> m, RenderContext c, Output o) -> o.write("hi")));
    }
  }

  public static class CurrentRegistry extends OldRegistry {
    @Override
    public int abi() {
      return TemplateEntry.ABI;
    }
  }
}
