package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SafeHtmlTest {

  @Test
  void safeHtmlEscapeAndUnchecked() {
    assertEquals("&lt;i&gt;&amp;&lt;/i&gt;", SafeHtml.escape("<i>&</i>").html());
    assertEquals("", SafeHtml.escape(null).html());
    assertEquals("<i>x</i>", SafeHtml.unchecked("<i>x</i>").html());
    assertEquals(SafeHtml.unchecked("a"), SafeHtml.unchecked("a"));
    assertEquals(SafeHtml.unchecked("a").hashCode(), SafeHtml.unchecked("a").hashCode());
    assertNotEquals(SafeHtml.unchecked("a"), SafeHtml.unchecked("b"));
    assertThrows(NullPointerException.class, () -> SafeHtml.unchecked(null));
  }

  @Test
  void escapeBreaksMustacheDelimiters() {
    // #248: SafeHtml.escape is element text, like t:text.
    assertEquals("<!---->{<!---->{x}}", SafeHtml.escape("{{x}}").html());
    assertEquals(
        "a{<!----><!---->{b}}",
        SafeHtml.concat(SafeHtml.escape("a{"), SafeHtml.escape("{b}}")).html());
  }

  @Test
  void safeHtmlOfTemplateRendersIt() {
    Template t =
        new Template() {
          @Override
          public String templateName() {
            return "t";
          }

          @Override
          public void render(RenderContext ctx, Output out) {
            out.write("<p>hi</p>");
          }
        };
    assertEquals("<p>hi</p>", SafeHtml.of(t, null).html());
  }
}
