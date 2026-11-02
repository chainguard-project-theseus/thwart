package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.testing.Compilation.model;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class UnusedLoopStatusTest {
  private static Compilation.Builder page(String params, String body) {
    return Compilation.builder().template("page.html",
        "<html xmlns:t=\"https://tessera.dev\" t:params=\"" + params + "\"><body>" + body + "</body></html>");
  }

  private static String source(Compilation c) {
    return c.assertSuccess().generatedSource("tessera.generated.PageTemplate");
  }

  private static Iterable<String> once(String... items) {
    AtomicInteger calls = new AtomicInteger();
    return () -> {
      assertEquals(1, calls.incrementAndGet(), "Iterable must only be opened once");
      return List.of(items).iterator();
    };
  }

  @Test
  void unusedImplicitAndExplicitStatusStreamAndEscape() {
    for (String declaration : List.of("item : items", "item, status : items")) {
      Compilation c = page("Iterable<String> items",
          "\n  <p t:each=\"" + declaration + "\" t:text=\"item\"></p>").compile();
      assertFalse(source(c).contains("new java.util.ArrayList"));
      assertFalse(source(c).contains("LoopStatus.of"));
      assertEquals("<html><body>\n  <p>&lt;script&gt;</p>\n  <p>&amp;</p></body></html>",
          c.render("page", model("items", once("<script>", "&"))));
      assertEquals("<html><body>\n  </body></html>", c.render("page", model("items", once())));
      assertEquals("<html><body>\n  </body></html>", c.render("page", model("items", null)));
    }
  }

  @Test
  void unusedStatusIsOmittedForCollectionAndArray() {
    for (String type : List.of("List<String>", "String[]")) {
      Compilation c = page(type + " items", "<p t:each=\"item : items\">[[item]]</p>").compile();
      assertFalse(source(c).contains("LoopStatus.of"));
      Object items = type.startsWith("List") ? List.of("a", "b") : new String[] {"a", "b"};
      assertEquals("<html><body><p>a</p><p>b</p></body></html>", c.render("page", model("items", items)));
    }
  }

  @Test
  void inlineAndEntityDecodedAttributeStatusAreRetained() {
    Compilation c = page("Iterable<String> items",
        "<p t:each=\"item : items\" t:data-last=\"item&#83;tat.last\">[[itemStat.count]]/[[itemStat.size]]</p>")
        .compile();
    assertTrue(source(c).contains("new java.util.ArrayList"));
    assertEquals("<html><body><p data-last=\"false\">1/2</p><p data-last=\"true\">2/2</p></body></html>",
        c.render("page", model("items", once("a", "b"))));
  }

  @Test
  void statusThroughAliasAndLinkArgumentIsRetained() {
    Compilation c = page("Iterable<String> items",
        "<a t:each=\"item, status : items\" t:with=\"s=status\" t:href=\"@{/row(n=s.index)}\">[[s.last]]</a>")
        .compile();
    assertEquals("<html><body><a href=\"/row?n=0\">false</a><a href=\"/row?n=1\">true</a></body></html>",
        c.assertSuccess().render("page", model("items", once("a", "b"))));
  }

  @Test
  void messageArgumentCanReadStatus() {
    Compilation c = page("Iterable<String> items",
        "<p t:each=\"item : items\" t:text=\"#{row(itemStat.count)}\"></p>").compile();
    dev.tessera.runtime.RenderContext defaults = dev.tessera.runtime.RenderContext.defaults();
    dev.tessera.runtime.RenderContext ctx = new dev.tessera.runtime.RenderContext(defaults.locale(),
        (key, args) -> key + args[0], defaults.links(), defaults.forms());
    assertEquals("<html><body><p>row1</p><p>row2</p></body></html>",
        c.assertSuccess().render("page", model("items", once("a", "b")), ctx));
  }

  @Test
  void nestedLoopSeesOuterStatusAndMayShadowIt() {
    Compilation c = page("Iterable<String> items, List<String> inner",
        "<p t:each=\"item : items\"><i t:each=\"x : inner\">[[itemStat.count]]</i>"
            + "<b t:each=\"item : inner\">[[itemStat.count]]</b></p>").compile();
    assertEquals("<html><body><p><i>1</i><b>1</b></p><p><i>2</i><b>1</b></p></body></html>",
        c.assertSuccess().render("page", model("items", once("a", "b"), "inner", List.of("x"))));
  }

  @Test
  void fragmentArgumentCanReadStatus() {
    Compilation c = page("Iterable<String> items",
        "<b t:fragment=\"badge(LoopStatus status)\">[[status.count]]/[[status.size]]</b>"
            + "<p t:each=\"item : items\"><i t:replace=\"~{::badge(itemStat)}\"></i></p>").compile();
    assertEquals("<html><body><p><b>1/2</b></p><p><b>2/2</b></p></body></html>",
        c.assertSuccess().render("page", model("items", once("a", "b"))));
  }

  @Test
  void fragmentSelectionOutsideLoopCapturesItsStatus() {
    Compilation c = page("Iterable<String> items, LoopStatus itemStat",
        "<aside>[[itemStat.count]]</aside>"
            + "<div t:fragment=\"wrap(Fragment body)\"><i t:replace=\"body\"></i></div>"
            + "<section t:each=\"item : items\"><i t:replace=\"~{::wrap(~{::aside})}\"></i></section>").compile();
    assertTrue(source(c).contains("new java.util.ArrayList"));
    assertEquals("<html><body><aside>1</aside><section><div><aside>1</aside></div></section>"
            + "<section><div><aside>2</aside></div></section></body></html>",
        c.render("page", model("items", once("a", "b"), "itemStat", dev.tessera.runtime.LoopStatus.of(0, 1))));
  }

  @Test
  void streamingKeepsSingleSourceEvaluationAndInterleavesIterationWithRendering() {
    for (boolean status : List.of(false, true)) {
      String body = "[[rows.seen]]:[[item]]" + (status ? ":[[itemStat.size]]" : "");
      Compilation c = page("com.example.Rows rows", "<p t:each=\"item : rows.items\">" + body + "</p>")
          .java("com.example.Rows", "package com.example; @dev.tessera.runtime.TemplateView public class Rows {"
              + "private int calls; private int seen; public int getSeen() { return seen; }"
              + "public Iterable<String> getItems() { if (++calls != 1) throw new AssertionError();"
              + "return () -> new java.util.Iterator<String>() { public boolean hasNext() { return seen < 2; }"
              + "public String next() { return Integer.toString(++seen); } }; }}")
          .compile().assertSuccess();
      assertEquals(status ? "<html><body><p>2:1:2</p><p>2:2:2</p></body></html>" : "<html><body><p>1:1</p><p>2:2</p></body></html>",
          c.render("page", model("rows", c.newInstance("com.example.Rows"))));
    }
  }

  @Test
  void unusedStatusOptimizationDoesNotBypassViewSafety() {
    page("Iterable<String> items", "<p t:each=\"item : items\" t:text=\"item.getClass()\"></p>")
        .compile().assertError("page.html:1:", "getClass");
    page("Iterable<String> items", "<p t:each=\"item : items\" t:utext=\"item\"></p>")
        .compile().assertError("page.html:1:", "SafeHtml");
    page("Iterable<String> items", "<p t:each=\"item : items\"></p>[[itemStat.count]]")
        .compile().assertError("page.html:1:", "unknown identifier 'itemStat'");
  }
}
