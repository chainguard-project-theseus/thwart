package dev.tessera.compiler.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.tessera.compiler.testing.Compilation;
import org.junit.jupiter.api.Test;

/**
 * #425: the processor records the templates it compiled. tessera-maven-plugin's {@code
 * TemplateFingerprintTest} expects the same bytes for the same templates.
 */
class TemplateFingerprintTest {
  static final String EXPECTED =
      "tessera-templates 1\n"
          + "11e6d60e8d8b1830e6ebe95ad0d470f546936d07a5109b8b2004e3f81fbdf847 a.html\n"
          + "7c7e7d38bb1f2d2688a52fd9cf45c5d4a3fb9a9a1283548b4ce2576bbfd4a26c dir/b.html\n";

  @Test
  void writesSortedPathsAndHashesAfterASuccessfulCompile() {
    Compilation c =
        Compilation.builder()
            .template("dir/b.html", "<p>café</p>\n")
            .template("a.html", "<p>hi</p>\n")
            .compile()
            .assertSuccess();
    assertEquals(EXPECTED, c.generatedResource(TemplateFingerprint.RESOURCE));
  }

  @Test
  void writesNothingWhenATemplateFails() {
    Compilation c =
        Compilation.builder()
            .template("a.html", "<p>hi</p>\n")
            .template("bad.html", "<p t:text=\"${nope}\">x</p>\n")
            .compile();
    c.assertError("bad.html");
    assertNull(c.generatedResource(TemplateFingerprint.RESOURCE));
  }
}
