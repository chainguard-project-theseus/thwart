package dev.tessera.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.OutputContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class ElementPlanTest {
  private static final SourcePos POS = new SourcePos("t.html", 1, 1);

  private static ElementPlan plan(String name, Attribute... attributes) {
    return new ElementPlan(new Element(name, List.of(attributes), List.of(), false, true, POS), null);
  }

  @Test
  void replaceBodyRejectsContextOtherThanTheElementContent() {
    ElementPlan script = plan("script");
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> script.replaceBody(Emitted.code("x"), OutputContext.TEXT));
    assertEquals("body written for TEXT inside <script> whose content is SCRIPT", e.getMessage());
    assertNull(script.body());

    ElementPlan style = plan("style");
    assertThrows(IllegalStateException.class, () -> style.replaceBody(Emitted.code("x"), OutputContext.TEXT));
    ElementPlan div = plan("div");
    assertThrows(IllegalStateException.class, () -> div.replaceBody(Emitted.code("x"), OutputContext.SCRIPT));
    assertNull(div.body());
  }

  @Test
  void replaceBodyAcceptsTheElementContent() {
    ElementPlan script = plan("script");
    script.replaceBody(Emitted.code("x"), OutputContext.SCRIPT);
    assertEquals("x", script.body().java());
    ElementPlan div = plan("div");
    div.replaceBody(Emitted.code("y"), OutputContext.TEXT);
    assertEquals("y", div.body().java());
  }

  @Test
  void setAttributeDerivesContextFromElementAndName() {
    ElementPlan a = plan("a");
    a.setAttribute("href", "h", POS);
    a.setAttribute("TITLE", "v", POS);
    a.setAttribute("onclick", "c", POS);
    List<OutputContext> contexts =
        a.attributes().stream().map(x -> ((ElementPlan.DynamicAttr) x).context()).toList();
    assertEquals(List.of(OutputContext.URL, OutputContext.ATTRIBUTE, OutputContext.EVENT_HANDLER), contexts);
  }

  @Test
  void attributeNamesFoldOnlyAscii() {
    // data-\u00C9 and data-\u00E9 are two attributes to a browser, which lower-cases A-Z only; DATA-X is data-x.
    ElementPlan p =
        plan(
            "p",
            new Attribute("data-\u00C9", "a", "a", '"', POS, POS),
            new Attribute("data-\u00E9", "b", "b", '"', POS, POS),
            new Attribute("DATA-X", "c", "c", '"', POS, POS));
    assertEquals(3, p.attributes().size());
    assertEquals("\"a\"", p.attribute("data-\u00C9").orElseThrow().valueCode());
    assertEquals("\"b\"", p.attribute("data-\u00E9").orElseThrow().valueCode());
    assertEquals("\"c\"", p.attribute("data-x").orElseThrow().valueCode());
  }

  @Test
  void setAttributeKeepsANameThatDiffersBeyondAsciiCase() {
    // A static \u212Aind (Kelvin sign) is not kind to a browser: a computed kind is a second attribute.
    ElementPlan p = plan("track", new Attribute("\u212Aind", "a", "a", '"', POS, POS));
    p.setAttribute("kind", "k", POS);
    assertEquals(2, p.attributes().size());
    assertEquals("\"a\"", p.attribute("\u212Aind").orElseThrow().valueCode());
    assertEquals("k", p.attribute("kind").orElseThrow().valueCode());
  }

  @Test
  void valueCode() {
    ElementPlan p =
        plan(
            "input",
            new Attribute("value", "a\"b", "a&quot;b", '"', POS, POS),
            new Attribute("checked", null, null, '\0', POS, null));
    assertEquals("\"a\\\"b\"", p.attribute("value").orElseThrow().valueCode());
    assertEquals("\"\"", p.attribute("checked").orElseThrow().valueCode());
    p.setAttribute("value", "expr()", POS);
    assertEquals("expr()", p.attribute("value").orElseThrow().valueCode());
  }
}
