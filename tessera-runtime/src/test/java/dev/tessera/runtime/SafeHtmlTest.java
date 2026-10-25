package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  void safeHtmlOfTemplateRendersAVerifiedFlowPartial() {
    Template t = template("<p>hi</p>", true);
    assertEquals("<p>hi</p>", SafeHtml.of(t, null).html());
    StringOutput out = new StringOutput();
    Fragment.of(t).render(null, out);
    assertEquals("<p>hi</p>", out.toString());
  }

  /** #396: a template the compiler didn't mark (a page, an {@code <li>} partial, ...) is refused. */
  @Test
  void safeHtmlOfTemplateRefusesAnUnverifiedOne() {
    Template t = template("<li>x</li>", false);
    assertFalse(t.verifiedFlowPartial()); // the default
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SafeHtml.of(t, null));
    assertTrue(e.getMessage().contains("SafeHtml.of") && e.getMessage().contains("'t'")
        && e.getMessage().contains("wrapping its root in a <div>"), e.getMessage());
    e = assertThrows(IllegalArgumentException.class, () -> Fragment.of(t));
    assertTrue(e.getMessage().contains("Fragment.of") && e.getMessage().contains("'t'"), e.getMessage());
    assertThrows(NullPointerException.class, () -> SafeHtml.of(null, null));
  }

  private static Template template(String html, boolean flow) {
    if (!flow) {
      return new Template() {
        @Override
        public String templateName() {
          return "t";
        }

        @Override
        public void render(RenderContext ctx, Output out) {
          out.write(html);
        }
      };
    }
    return new Template() {
      @Override
      public String templateName() {
        return "t";
      }

      @Override
      public void render(RenderContext ctx, Output out) {
        out.write(html);
      }

      @Override
      public boolean verifiedFlowPartial() {
        return true;
      }
    };
  }
}
