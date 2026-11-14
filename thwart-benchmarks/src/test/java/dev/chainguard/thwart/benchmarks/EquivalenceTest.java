package dev.chainguard.thwart.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chainguard.thwart.runtime.RenderContext;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;

/** The benchmarks only compare like with like if both engines produce the same HTML. */
class EquivalenceTest {
  private final TemplateEngine thymeleaf = Engines.thymeleaf();
  private final RenderContext ctx = RenderContext.defaults();

  @Test
  void ownersTableIsIdentical() {
    String thwart = Engines.thwartOwners(ctx, BenchmarkData.owners(), 1);
    assertEquals(Engines.thymeleafOwners(thymeleaf, BenchmarkData.owners(), 1), thwart);
    assertEquals(BenchmarkData.OWNER_COUNT, count(thwart, "<tr class="));
    assertTrue(thwart.contains("<a href=\"/owners/1\">George Franklin</a>"), thwart);
    assertTrue(thwart.contains("/owners?page=2&amp;size=100"), thwart);
    // Data needing escaping is escaped (and identically, by the equality above) by both engines.
    assertTrue(thwart.contains("O&#39;Brien"), thwart);
    assertTrue(thwart.contains("Black &amp; Sons"), thwart);
    assertTrue(thwart.contains("&lt;Estaban&gt;"), thwart);
    assertFalse(thwart.contains("<Estaban>"), thwart);
  }

  @Test
  void layoutPageIsIdentical() {
    String thwart = Engines.thwartHome(ctx, BenchmarkData.featured());
    assertEquals(Engines.thymeleafHome(thymeleaf, BenchmarkData.featured()), thwart);
    assertEquals(BenchmarkData.CARD_COUNT, count(thwart, "<div class=\"card\">"));
    assertTrue(thwart.contains("<li class=\"nav-item active\">"), thwart);
    assertTrue(thwart.contains("<footer><p>Thwart benchmarks</p></footer>"), thwart);
  }

  private static int count(String haystack, String needle) {
    int n = 0;
    for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
      n++;
    }
    return n;
  }
}
