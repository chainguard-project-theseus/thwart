package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SafeHtmlTest {

  @Test
  void safeHtmlEscapeAndEquality() {
    assertEquals("&lt;i&gt;&amp;&lt;/i&gt;", SafeHtml.escape("<i>&</i>").html());
    assertEquals("", SafeHtml.escape(null).html());
    assertEquals(SafeHtml.escape("a"), SafeHtml.escape("a"));
    assertEquals(SafeHtml.escape("a").hashCode(), SafeHtml.escape("a").hashCode());
    assertNotEquals(SafeHtml.escape("a"), SafeHtml.escape("b"));
    assertEquals(SafeHtml.escape("a"), SafeHtml.concat(SafeHtml.escape("a"), null));
    assertThrows(NullPointerException.class, () -> SafeHtml.sanitize("a", null));
  }

  @Test
  void escapeBreaksMustacheDelimiters() {
    // #248: SafeHtml.escape is element text, like t:text.
    assertEquals("<!---->{<!---->{<!---->x<!---->}<!---->}<!---->", SafeHtml.escape("{{x}}").html());
    assertEquals(
        "a<!---->{<!----><!---->{<!---->b<!---->}<!---->}<!---->",
        SafeHtml.concat(SafeHtml.escape("a{"), SafeHtml.escape("{b}}")).html());
  }
}
