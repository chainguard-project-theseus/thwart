package dev.tessera.compiler.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.model.Element;
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

  private static final Set<String> GROUPS =
      Set.of("text", "keyword", "number", "boolean", "idref", "tokens", "obsolete", "aria", "mathml", "svg");

  /** The inert-text list (#539) is sorted, scoped, grouped and a subset of attributes.txt. */
  @Test
  void inertFileIsSortedScopedAndStandard() throws Exception {
    List<String> names = new ArrayList<>();
    try (InputStream in = KnownAttributes.class.getResourceAsStream("inert-attributes.txt");
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        if (line.startsWith("#")) {
          continue;
        }
        String[] f = line.split(" ");
        assertTrue(f.length == 3 || f.length == 4, line);
        assertTrue(Set.of("html", "foreign", "any").contains(f[1]), "unknown scope: " + line);
        assertTrue(GROUPS.contains(f[2]), "unknown group: " + line);
        assertTrue(f.length == 3 || f[1].equals("html"), "elements only in scope html: " + line);
        assertTrue(KnownAttributes.isStandard(f[0]), "not in attributes.txt: " + line);
        if (!names.isEmpty()) {
          assertTrue(names.get(names.size() - 1).compareTo(f[0]) < 0, "not sorted or repeated: " + f[0]);
        }
        names.add(f[0]);
      }
    }
    // Inert text, ARIA and the global token attributes.
    for (String n : List.of("title", "alt", "id", "class", "aria-label", "role", "lang", "name", "placeholder")) {
      assertFalse(KnownAttributes.needsSafeValue(html("p"), n), n);
    }
    // Never inert: they fetch, navigate, grant, run, or are CSS.
    for (String n : List.of("rel", "target", "sandbox", "allow", "crossorigin", "itemtype", "srcset", "srcdoc", "style")) {
      assertTrue(KnownAttributes.needsSafeValue(html("a"), n), n);
    }
    // data-* names are not standard, so not TYPED (they are NEUTRAL); a custom element has no exemption (#584).
    assertFalse(KnownAttributes.needsSafeValue(html("p"), "data-rel"));
    assertTrue(KnownAttributes.needsSafeValue(html("my-el"), "target"));
    // Scoped by namespace and, for for, by element.
    assertTrue(KnownAttributes.needsSafeValue(foreign("rect"), "width"));
    assertFalse(KnownAttributes.needsSafeValue(html("img"), "width"));
    assertFalse(KnownAttributes.needsSafeValue(foreign("rect"), "aria-label"));
    assertFalse(KnownAttributes.needsSafeValue(html("label"), "for"));
    assertTrue(KnownAttributes.needsSafeValue(html("template"), "for"));
    assertFalse(KnownAttributes.needsSafeValue(html("option"), "value"));
    assertTrue(KnownAttributes.needsSafeValue(html("param"), "value"));
  }

  /**
   * The class claim: the names behind #415, #416, #419 and #421 fall out of the inert-text list, so
   * a String would have been a build error without their specific fixes. #414 (ping) and the
   * {@code <animate>}/{@code <set>} values of #421 are not covered here: they are TRUSTED_URL (#539).
   */
  @Test
  void historicBugNamesAreNotInert() {
    for (String n : List.of("srcset", "imagesrcset", "manifest")) { // #415
      assertTrue(KnownAttributes.needsSafeValue(html("img"), n), n);
    }
    for (String n : List.of("fill", "stroke", "clip-path", "mask", "filter", "marker-start", "cursor")) { // #416
      assertTrue(KnownAttributes.needsSafeValue(foreign("rect"), n), n);
    }
    for (String n : List.of("values", "to", "from", "by", "attributename")) { // #421
      assertTrue(KnownAttributes.needsSafeValue(foreign("animatetransform"), n), n);
    }
    assertTrue(KnownAttributes.needsSafeValue(html("p"), "is")); // #419
    assertTrue(KnownAttributes.needsSafeValue(html("template"), "shadowrootmode"));
    assertTrue(KnownAttributes.needsSafeValue(html("template"), "for"));
    assertTrue(KnownAttributes.needsSafeValue(html("meta"), "charset"));
  }

  private static Element html(String name) {
    return new Element(name, List.of(), List.of(), false, true, new SourcePos("t.html", 1, 1), false);
  }

  private static Element foreign(String name) {
    return new Element(name, List.of(), List.of(), false, true, new SourcePos("t.html", 1, 1), true);
  }
}
