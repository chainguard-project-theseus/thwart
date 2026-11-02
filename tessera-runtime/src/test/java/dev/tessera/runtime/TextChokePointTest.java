package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Markup reaches element text only as escaped text ({@link Escapers#appendHtml}, the one HTML
 * escaper, #679) or as a {@link SafeHtml} built from a checked source. A new way to build a
 * {@code SafeHtml} fails here and has to be reviewed.
 */
class TextChokePointTest {

  /** Exactly the arguments {@code new SafeHtml(...)} is built from: a new or a stale entry fails. */
  private static final Set<String> SAFE_HTML_SOURCES =
      Set.of(
          "\"\"", "Escapers.escapeHtml(text == null ? \"\" : text)", "HtmlSanitizer.sanitize(html, policy)",
          "sb.toString()");

  @Test
  void safeHtmlIsBuiltOnlyFromCheckedSources() throws IOException {
    Set<String> args = new TreeSet<>();
    Matcher m = Pattern.compile("new SafeHtml\\((.*)\\);")
        .matcher(Files.readString(Path.of("src/main/java/dev/tessera/runtime/SafeHtml.java")));
    while (m.find()) {
      args.add(m.group(1));
    }
    assertEquals(SAFE_HTML_SOURCES, args);
  }
}
