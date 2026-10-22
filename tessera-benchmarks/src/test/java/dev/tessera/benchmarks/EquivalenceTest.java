package dev.tessera.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.runtime.RenderContext;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;

/** The benchmarks only compare like with like if both engines produce the same HTML. */
class EquivalenceTest {
  private final TemplateEngine thymeleaf = Engines.thymeleaf();
  private final RenderContext ctx = RenderContext.defaults();

  @Test
  void ownersTableIsIdentical() {
    String tessera = Engines.tesseraOwners(ctx, BenchmarkData.owners(), 1);
    assertEquals(Engines.thymeleafOwners(thymeleaf, BenchmarkData.owners(), 1), tessera);
    assertEquals(BenchmarkData.OWNER_COUNT, count(tessera, "<tr class="));
    assertTrue(tessera.contains("<a href=\"/owners/1\">George Franklin</a>"), tessera);
    assertTrue(tessera.contains("/owners?page=2&amp;size=100"), tessera);
  }

  @Test
  void layoutPageIsIdentical() {
    String tessera = Engines.tesseraHome(ctx, BenchmarkData.featured());
    assertEquals(Engines.thymeleafHome(thymeleaf, BenchmarkData.featured()), tessera);
    assertEquals(BenchmarkData.CARD_COUNT, count(tessera, "<div class=\"card\">"));
    assertTrue(tessera.contains("<li class=\"nav-item active\">"), tessera);
    assertTrue(tessera.contains("<footer><p>Tessera benchmarks</p></footer>"), tessera);
  }

  @Test
  void dataNeedingEscapingIsEscapedByBoth() {
    String tessera = Engines.tesseraOwners(ctx, BenchmarkData.owners(), 1);
    assertTrue(tessera.contains("O&#39;Brien"), tessera);
    assertTrue(tessera.contains("Black &amp; Sons"), tessera);
    assertTrue(tessera.contains("&lt;Estaban&gt;"), tessera);
    assertTrue(!tessera.contains("<Estaban>"), tessera);
  }

  private static int count(String haystack, String needle) {
    int n = 0;
    for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
      n++;
    }
    return n;
  }
}
