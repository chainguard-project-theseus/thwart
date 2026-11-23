package dev.chainguard.thwart.compiler.html;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.chainguard.thwart.compiler.testing.Compilation;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** #916: attribute values decode every named character reference, as Thymeleaf and browsers do. */
class CharacterReferenceTest {
  private static final String NS = "xmlns:t=\"https://thwart.chainguard.dev\" ";

  private static String render(String markup) {
    return Compilation.builder().template("a.html", markup).compile().assertSuccess().render("a", Map.of());
  }

  @Test
  void tAttributeValuesAreDecodedThenEscaped() {
    assertEquals("<p>© 2025 &amp; ©</p>", render("<p " + NS + "t:text=\"|&copy; 2025 &amp; &#169;|\">x</p>"));
    assertEquals("<p title=\"½ &lt;b&gt;\">x</p>", render("<p " + NS + "t:title=\"'&frac12; &lt;b&gt;'\">x</p>"));
    // A name the standard doesn't define stays as written, as a browser leaves it.
    assertEquals("<p>&amp;nosuch;</p>", render("<p " + NS + "t:text=\"'&nosuch;'\">x</p>"));
  }

  @Test
  void staticValuesAreWrittenAsInTheSource() {
    assertEquals("<p title=\"&copy; x\">&copy;</p>", render("<p " + NS + "title=\"&copy; x\">&copy;</p>"));
  }

  @Test
  void decodedReferencesCannotHideAScriptUrl() {
    // Decoded to java<TAB>script:..., which the URL escaper replaces like any script URL.
    assertEquals(
        "<a href=\"about:invalid#thwart\">x</a>", render("<a " + NS + "t:href=\"'java&Tab;script&colon;alert(1)'\">x</a>"));
    Compilation.builder()
        .template("a.html", "<a " + NS + "href=\"java&Tab;script&colon;alert(1)\">x</a>")
        .compile()
        .assertError("a.html:1:", "javascript: URLs are not supported");
  }
}
