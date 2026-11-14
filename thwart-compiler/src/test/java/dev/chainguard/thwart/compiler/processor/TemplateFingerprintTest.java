package dev.chainguard.thwart.compiler.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.chainguard.thwart.compiler.testing.Compilation;
import org.junit.jupiter.api.Test;

/**
 * #425, #447: the processor records the templates and message files it read. thwart-maven-plugin's
 * {@code TemplateFingerprintTest} expects the same bytes for the same files.
 */
class TemplateFingerprintTest {
  static final String EXPECTED =
      "thwart-templates 2\n"
          + "11e6d60e8d8b1830e6ebe95ad0d470f546936d07a5109b8b2004e3f81fbdf847 a.html\n"
          + "7c7e7d38bb1f2d2688a52fd9cf45c5d4a3fb9a9a1283548b4ce2576bbfd4a26c dir/b.html\n";

  static final String EXPECTED_WITH_MESSAGES =
      EXPECTED
          + "messages messages\n"
          + "a1e5f1c10ffc2b5d2727627cc0fe6f03c030331fdd53e7e5798b9734b3ce0071 messages.properties\n"
          + "ecb55823e58686614ad5d5e9d1810e4ee2fc3a2a5e9d64bb343dee4d0c153a7d messages_fr.properties\n";

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
  void recordsTheMessageFilesAfterTheTemplates() {
    Compilation c =
        Compilation.builder()
            .template("dir/b.html", "<p>café</p>\n")
            .template("a.html", "<p>hi</p>\n")
            .messages("greeting=Hello\n")
            .localeMessages("fr", "greeting=Bonjour\n")
            .compile()
            .assertSuccess();
    assertEquals(EXPECTED_WITH_MESSAGES, c.generatedResource(TemplateFingerprint.RESOURCE));
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

  @Test
  void writesNothingWhenAMessageFails() {
    Compilation c =
        Compilation.builder()
            .template("a.html", "<div>\n<p t:utext=\"#{nope}\">x</p></div>")
            .messages("greeting=<b>Hello</b>\n")
            .localeMessages("fr", "greeting=<b onclick=\"alert(1)\">Bonjour</b>\n")
            .compile();
    c.assertError("a.html:2:", "unknown message key 'nope'");
    assertNull(c.generatedResource(TemplateFingerprint.RESOURCE));
  }
}
