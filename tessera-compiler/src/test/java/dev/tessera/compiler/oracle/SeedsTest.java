package dev.tessera.compiler.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.oracle.Seeds.Seed;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeedsTest {

  @Test
  void everySeedFileParsesWithUniqueNamesAndTemplates() {
    List<Seed> seeds = Seeds.load();
    assertEquals(seeds.size(), seeds.stream().map(Seed::name).distinct().count());
    assertEquals(seeds.size(), seeds.stream().map(s -> s.toCase().id()).distinct().count());
    for (String file : Seeds.FILES) {
      assertTrue(seeds.stream().anyMatch(s -> s.file().equals(file)), file);
    }
    for (Seed s : seeds) {
      assertTrue(s.template().contains("t:params="), s.name());
      assertEquals(Seeds.wrap(s.body()), s.template(), s.name());
    }
    assertEquals(seeds.size(), Seeds.cases().size());
    assertEquals("<div t:params=\"String s\"><svg><title><script>var a = [[s]];</script></title></svg></div>",
        seeds.stream().filter(s -> s.name().equals("306-A5")).findFirst().orElseThrow().template());
  }

  @Test
  void controlCharactersAreEscaped() {
    assertTrue(Seeds.load().stream().anyMatch(s -> s.body().equals("<script\u000b>var a = [[s]];</script>")));
    String odd = "a\u000b\r\t é😀{";
    assertEquals("a{U+000B}{U+000D}{U+0009}{U+2028}{U+00E9}{U+1F600}{", Seeds.escape(odd));
    assertEquals(odd, Seeds.unescape("t", 0, Seeds.escape(odd)));
    // text that looks like an escape survives the round trip
    String literal = "{U+0041} {U+12} {u+0041}";
    assertEquals("{U+007B}U+0041} {U+12} {u+0041}", Seeds.escape(literal));
    assertEquals(literal, Seeds.unescape("t", 0, Seeds.escape(literal)));
  }

  @Test
  void parsesHeadersBodiesAndOwnRoots() {
    List<Seed> seeds =
        Seeds.parse(
            "t.txt",
            "# preamble only\n====\n# name: a\n# expect-0dda2f1: LIVE_SCRIPT\n# note: n\n# a comment\n<p>\n[[s]]</p>\n====\n"
                + "# name: b\n<div t:params=\"String x\"><p>[[x]]</p></div>\n");
    assertEquals(2, seeds.size());
    Seed a = seeds.get(0);
    assertEquals("a", a.name());
    assertEquals(3, a.line());
    assertEquals("<p>\n[[s]]</p>", a.body());
    assertEquals("<div t:params=\"String s\"><p>\n[[s]]</p></div>", a.template());
    assertEquals("n", a.note());
    Seed b = seeds.get(1);
    assertEquals("<div t:params=\"String x\"><p>[[x]]</p></div>", b.template());
    assertEquals("", b.note());
    assertEquals(OracleCase.idOf(b.template()), b.toCase().id());
  }

  @Test
  void formatErrorsFail() {
    String[] bad = {
      "# note: x\n<p>x</p>", // no name
      "# name: a", // no template
      "# name: a\n# name: b\n<p>x</p>", // duplicate key
      "# name: a b\n<p>x</p>", // bad name
    };
    for (String text : bad) {
      IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Seeds.parse("t.txt", text), text);
      assertTrue(e.getMessage().matches("t\\.txt:[1-3]: .*"), e.getMessage());
    }
  }
}
