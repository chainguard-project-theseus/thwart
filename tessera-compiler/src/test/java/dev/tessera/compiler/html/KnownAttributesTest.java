package dev.tessera.compiler.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The standard-name allow-list for computed attributes (#330) is a well-formed, sorted data file. */
class KnownAttributesTest {

  private static final Set<String> SOURCES = Set.of("html", "html-obsolete", "svg", "mathml", "aria");

  @Test
  void dataFileIsSortedLowerCaseAndSourced() throws Exception {
    List<String> names = new ArrayList<>();
    try (InputStream in = KnownAttributes.class.getResourceAsStream("attributes.txt");
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        if (line.startsWith("#")) {
          continue;
        }
        String[] fields = line.split(" ");
        assertTrue(fields[0].matches("[a-z][a-z0-9]*([:-][a-z0-9]+)*"), line);
        assertTrue(!fields[0].startsWith("on"), "event handlers are always EVENT_HANDLER: " + line);
        assertTrue(fields.length > 1, "no source: " + line);
        for (int i = 1; i < fields.length; i++) {
          assertTrue(SOURCES.contains(fields[i]), "unknown source: " + line);
        }
        if (!names.isEmpty()) {
          String previous = names.get(names.size() - 1);
          assertTrue(previous.compareTo(fields[0]) < 0, "not sorted or repeated: " + previous + ", " + fields[0]);
        }
        names.add(fields[0]);
      }
    }
    assertTrue(names.size() > 400, "only " + names.size() + " names");
    for (String n : List.of("href", "stroke-dasharray", "aria-label", "role", "mathvariant", "bgcolor", "face", "d")) {
      assertTrue(names.contains(n), n);
    }
    assertEquals(false, names.contains("up-on-loaded"));
    assertEquals(false, names.contains("hx-action"));
  }
}
