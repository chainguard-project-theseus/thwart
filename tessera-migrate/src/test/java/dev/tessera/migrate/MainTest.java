package dev.tessera.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CLI arguments, exit codes and output. */
class MainTest {

  @TempDir Path tmp;

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  private int run(String... args) {
    return Main.run(args,
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  private String out() {
    return out.toString(StandardCharsets.UTF_8);
  }

  private String err() {
    return err.toString(StandardCharsets.UTF_8);
  }

  @Test
  void noArgumentsPrintsUsageAndExits2() {
    assertEquals(2, run());
    assertTrue(err().contains(Main.USAGE), err());
  }

  @Test
  void missingOutputDirIsAUsageError() {
    assertEquals(2, run(tmp.toString()));
    assertTrue(err().contains(Main.USAGE));
  }

  @Test
  void helpPrintsUsageAndExits0() {
    assertEquals(0, run("--help"));
    assertEquals(Main.USAGE + System.lineSeparator(), out());
    assertEquals(0, run("-h"));
  }

  @Test
  void unknownOptionIsAUsageError() {
    assertEquals(2, run("--frobnicate", "a", "b"));
    assertTrue(err().contains("unknown option --frobnicate"), err());
  }

  @Test
  void reportWithoutFileIsAUsageError() {
    assertEquals(2, run("a", "b", "--report"));
    assertTrue(err().contains("--report needs a file name"), err());
  }

  @Test
  void extraArgumentIsAUsageError() {
    assertEquals(2, run("a", "b", "c"));
    assertTrue(err().contains("unexpected argument c"), err());
  }

  @Test
  void missingInputDirectoryIsAUsageError() {
    assertEquals(2, run(tmp.resolve("nope").toString(), tmp.resolve("out").toString()));
    assertTrue(err().contains("not a directory"), err());
  }

  @Test
  void reportGoesToStdoutWithoutReportOption() throws Exception {
    Path in = Files.createDirectories(tmp.resolve("in"));
    Files.writeString(in.resolve("a.html"), "<p th:text=\"${T(java.lang.System).exit(1)}\">x</p>\n");
    Files.writeString(in.resolve("notes.txt"), "not a template");

    assertEquals(0, run(in.toString(), tmp.resolve("out").toString()));

    assertTrue(out().startsWith("# tessera-migrate report"), out());
    assertTrue(out().contains("`a.html:1`: `T(...)`"), out());
    assertFalse(Files.exists(tmp.resolve("out/notes.txt")), "only .html templates are written");
  }

  @Test
  void reportItemsDoNotChangeTheExitCode() throws Exception {
    Path in = Files.createDirectories(tmp.resolve("in"));
    Files.writeString(in.resolve("a.html"), "<p th:include=\"f\">x</p>");
    Path report = tmp.resolve("reports/r.md");

    assertEquals(0, run(in.toString(), tmp.resolve("out").toString(), "--report", report.toString()));

    assertTrue(Files.readString(report).contains("`a.html:1`: `th:include`"));
    assertTrue(out().contains("converted 1 template(s), 1 report item(s)"), out());
  }

  @Test
  void unparseableTemplateExits1AndNamesTheFile() throws Exception {
    Path in = Files.createDirectories(tmp.resolve("in/sub"));
    Files.writeString(in.resolve("ok.html"), "<p th:text=\"${a}\">x</p>");
    Files.writeString(in.resolve("bad.html"), "<p>\n<a href=\"x");

    assertEquals(1, run(tmp.resolve("in").toString(), tmp.resolve("out").toString()));

    assertTrue(err().contains("sub/bad.html:2:"), err());
    assertEquals("<p t:text=\"a\" t:params=\"Object a\">x</p>", Files.readString(tmp.resolve("out/sub/ok.html")));
  }
}
