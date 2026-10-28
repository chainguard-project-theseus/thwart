package dev.tessera.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #425, #447: the same bytes as the processor's fingerprint for the same files (tessera-compiler's
 * {@code dev.tessera.compiler.processor.TemplateFingerprintTest} has the same {@link #EXPECTED} and
 * {@link #EXPECTED_WITH_MESSAGES}).
 */
class TemplateFingerprintTest {
  static final String EXPECTED =
      "tessera-templates 2\n"
          + "11e6d60e8d8b1830e6ebe95ad0d470f546936d07a5109b8b2004e3f81fbdf847 a.html\n"
          + "7c7e7d38bb1f2d2688a52fd9cf45c5d4a3fb9a9a1283548b4ce2576bbfd4a26c dir/b.html\n";

  static final String EXPECTED_WITH_MESSAGES =
      EXPECTED
          + "messages messages\n"
          + "a1e5f1c10ffc2b5d2727627cc0fe6f03c030331fdd53e7e5798b9734b3ce0071 messages.properties\n"
          + "ecb55823e58686614ad5d5e9d1810e4ee2fc3a2a5e9d64bb343dee4d0c153a7d messages_fr.properties\n";

  private static void templates(Path root) throws Exception {
    Files.createDirectories(root.resolve("dir"));
    Files.write(root.resolve("dir/b.html"), "<p>café</p>\n".getBytes(StandardCharsets.UTF_8));
    Files.write(root.resolve("a.html"), "<p>hi</p>\n".getBytes(StandardCharsets.UTF_8));
    // Not templates: the processor reads only *.html.
    Files.write(root.resolve("notes.txt"), "x".getBytes(StandardCharsets.UTF_8));
    Files.createDirectories(root.resolve("empty.html.d"));
  }

  @Test
  void sameBytesAsTheProcessor(@TempDir Path root) throws Exception {
    templates(root);
    assertEquals(EXPECTED, new String(TemplateFingerprint.of(root, Collections.<Path>emptyList()), StandardCharsets.UTF_8));
  }

  @Test
  void sameBytesAsTheProcessorWithMessages(@TempDir Path base) throws Exception {
    Path root = base.resolve("templates");
    templates(root);
    write(base.resolve("messages.properties"), "greeting=Hello\n");
    write(base.resolve("messages_fr.properties"), "greeting=Bonjour\n");
    // Not in the family of basename messages.
    write(base.resolve("messagesx.properties"), "x=y\n");
    write(base.resolve("application.properties"), "x=y\n");
    write(base.resolve("messages_fr.txt"), "x=y\n");
    Files.createDirectories(base.resolve("messages_de.properties.d"));
    assertEquals(
        EXPECTED_WITH_MESSAGES,
        new String(TemplateFingerprint.of(root, Collections.singletonList(base.resolve("messages"))), StandardCharsets.UTF_8));
  }

  /** A named default locale ({@code messages_fr}) has the same family as its base. */
  @Test
  void defaultLocaleBasenameCoversTheWholeFamily(@TempDir Path base) throws Exception {
    Path root = base.resolve("templates");
    templates(root);
    write(base.resolve("messages.properties"), "greeting=Hello\n");
    write(base.resolve("messages_fr.properties"), "greeting=Bonjour\n");
    String fp = new String(
        TemplateFingerprint.of(root, Arrays.asList(base.resolve("messages_fr"), base.resolve("missing/other"))),
        StandardCharsets.UTF_8);
    assertEquals(
        EXPECTED
            + "messages messages_fr\n"
            + "a1e5f1c10ffc2b5d2727627cc0fe6f03c030331fdd53e7e5798b9734b3ce0071 messages.properties\n"
            + "ecb55823e58686614ad5d5e9d1810e4ee2fc3a2a5e9d64bb343dee4d0c153a7d messages_fr.properties\n"
            + "messages other\n",
        fp);
  }

  private static void write(Path p, String s) throws Exception {
    Files.write(p, s.getBytes(StandardCharsets.UTF_8));
  }
}
