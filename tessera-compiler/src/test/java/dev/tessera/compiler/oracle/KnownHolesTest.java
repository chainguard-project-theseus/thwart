package dev.tessera.compiler.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KnownHolesTest {

  private static final Finding LIVE_VNU14 = new Finding(Finding.Kind.LIVE_SCRIPT, "vnu-1.4", "/html", "v");

  @Test
  void checkedInFileParses() {
    assertNotNull(KnownHoles.load());
  }

  @Test
  void parsesEntriesCommentsAndBlankLines() {
    KnownHoles holes =
        KnownHoles.parse(
            "t.txt",
            "# header\n\n0123456789ab LIVE_SCRIPT vnu-1.4 #305 classic select only\n"
                + "abcdef012345 CSTI * #248\n");
    assertEquals(2, holes.entries().size());
    KnownHoles.Entry first = holes.entries().get(0);
    assertEquals(305, first.issue());
    assertEquals("classic select only", first.note());
    assertEquals("", holes.entries().get(1).note());

    assertNotNull(holes.find("0123456789ab", LIVE_VNU14));
    assertNull(holes.find("0123456789ab", new Finding(Finding.Kind.LIVE_SCRIPT, "vnu-26", "/html", "v")));
    assertNull(holes.find("0123456789ab", new Finding(Finding.Kind.LIVE_HANDLER, "vnu-1.4", "/html", "v")));
    assertNotNull(holes.find("abcdef012345", new Finding(Finding.Kind.CSTI, "chromium", "/html", "v")));
  }

  @Test
  void lineWithoutIssueNumberFails() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> KnownHoles.parse("t.txt", "# ok\n0123456789ab LIVE_SCRIPT vnu-1.4 classic select\n"));
    assertTrue(e.getMessage().startsWith("t.txt:2: "), e.getMessage());
  }

  @Test
  void otherFormatErrorsFail() {
    for (String bad :
        new String[] {
          "0123456789ab LIVE_SCRIPT vnu-1.4 #", // empty issue number
          "0123456789ab LIVE_SCRIPT vnu-1.4 #0", // not an issue number
          "0123456789ab LIVE_SCRIPT #305", // missing engine
          "0123456789AB LIVE_SCRIPT * #305", // id not lower-case hex
          "0123456789 LIVE_SCRIPT * #305", // id too short
          "0123456789ab LIVE_JAVA * #305", // unknown kind
          "0123456789ab LIVE_SCRIPT * #305\n0123456789ab LIVE_SCRIPT * #306", // duplicate
          "0123456789ab LIVE_SCRIPT * #305\n0123456789ab LIVE_SCRIPT vnu-26 #305", // overlap
          "0123456789ab LIVE_SCRIPT vnu-26 #305\n0123456789ab LIVE_SCRIPT * #305", // overlap
          "0123456789ab LIVE_SCRIPT #305 #306 engine missing", // '#305' is not an engine
          "0123456789ab LIVE_SCRIPT * #99999999999", // issue number out of range
          "0123456789ab LIVE_SCRIPT * #305x", // issue number not separated from the note
          "0123456789ab\tLIVE_SCRIPT", // truncated
        }) {
      assertThrows(IllegalArgumentException.class, () -> KnownHoles.parse("t.txt", bad), bad);
    }
  }

  @Test
  void exportLineEscapesLineBreaksAndSurrogates() {
    ExportRecord r = new ExportRecord("id", "n", "<p>\n</p>", "a\u2028\ud800\"", null, "CLEAN");
    assertEquals(
        "{\"id\":\"id\",\"name\":\"n\",\"template\":\"<p>\\n</p>\",\"value\":\"a\\u2028\\ud800\\\"\","
            + "\"render\":null,\"verdict\":\"CLEAN\"}",
        r.toJsonLine());
  }

  @Test
  void caseIdIsStableShaPrefix() {
    // printf '%s' '<p>x</p>' | shasum -a 256
    assertEquals("31d8e07ec305", OracleCase.idOf("<p>x</p>"));
    assertEquals(OracleCase.idOf("<p>x</p>"), OracleCase.of("n", "<p>x</p>", OracleCase.Origin.SEED).id());
    assertThrows(
        IllegalArgumentException.class,
        () -> new OracleCase("000000000000", "n", "<p>x</p>", OracleCase.Origin.SEED));
  }
}
