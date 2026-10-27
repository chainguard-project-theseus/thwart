package dev.tessera.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #425: the same bytes as the processor's fingerprint for the same templates (tessera-compiler's
 * {@code dev.tessera.compiler.processor.TemplateFingerprintTest} has the same {@link #EXPECTED}).
 */
class TemplateFingerprintTest {
  static final String EXPECTED =
      "tessera-templates 1\n"
          + "11e6d60e8d8b1830e6ebe95ad0d470f546936d07a5109b8b2004e3f81fbdf847 a.html\n"
          + "7c7e7d38bb1f2d2688a52fd9cf45c5d4a3fb9a9a1283548b4ce2576bbfd4a26c dir/b.html\n";

  @Test
  void sameBytesAsTheProcessor(@TempDir Path root) throws Exception {
    Files.createDirectories(root.resolve("dir"));
    Files.write(root.resolve("dir/b.html"), "<p>café</p>\n".getBytes(StandardCharsets.UTF_8));
    Files.write(root.resolve("a.html"), "<p>hi</p>\n".getBytes(StandardCharsets.UTF_8));
    // Not templates: the processor reads only *.html.
    Files.write(root.resolve("notes.txt"), "x".getBytes(StandardCharsets.UTF_8));
    Files.createDirectories(root.resolve("empty.html.d"));
    assertEquals(EXPECTED, new String(TemplateFingerprint.of(root), StandardCharsets.UTF_8));
  }
}
