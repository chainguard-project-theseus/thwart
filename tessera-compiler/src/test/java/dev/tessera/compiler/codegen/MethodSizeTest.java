package dev.tessera.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #658: a template, fragment or selection whose generated method would pass the JVM limit of 64 KB
 * of bytecode per method is a Tessera error that names it, not a javac "code too large" on the
 * generated class.
 */
class MethodSizeTest {
  private static final String NS = "xmlns:t=\"https://tessera.dev\"";
  private static final String PARAMS = "String a, int n";
  /** About 380 chars of Java and 116 bytes of bytecode. */
  private static final String ROW = "<a t:href=\"@{/x/{id}(id=${n})}\" t:title=\"${a}\">x</a>\n";
  private static final String RECIPE = " is too large for one Java method: its generated code may exceed the JVM's 64"
      + " KB limit per method, and javac would fail with \"code too large\"; split it into smaller fragments"
      + " (t:fragment, in this file or in other templates) and insert them with t:insert or t:replace";

  @Test
  void templateTooLarge() {
    // 600 rows compile to about 70 KB.
    List<String> errors = Compilation.builder()
        .template("big.html", "<div " + NS + " t:params=\"" + PARAMS + "\">" + ROW.repeat(600) + "</div>")
        .compile()
        .errors();
    assertEquals(List.of("big.html:1:1: error: template 'big'" + RECIPE), errors);
  }

  @Test
  void fragmentTooLarge() {
    List<String> errors = Compilation.builder()
        .template("big.html", "<div " + NS + ">\n<section t:fragment=\"rows(" + PARAMS + ")\">" + ROW.repeat(600)
            + "</section></div>")
        .compile()
        .errors();
    assertEquals(List.of("big.html:2:1: error: fragment 'rows'" + RECIPE), errors);
  }

  @Test
  void selectionTooLarge() {
    List<String> errors = Compilation.builder()
        .template("layout.html",
            "<div " + NS + "><main t:fragment=\"l(Fragment c)\"><div t:replace=\"${c}\"></div></main></div>")
        .template("page.html", "<div " + NS + " t:params=\"" + PARAMS + "\" t:replace=\"~{layout :: l(~{::section})}\">\n"
            + "<section>" + ROW.repeat(600) + "</section></div>")
        .compile()
        .errors();
    assertEquals(List.of("page.html:2:1: error: the element ~{::section} selects" + RECIPE), errors);
  }

  /** 500 rows, about 58 KB, compile and render. */
  @Test
  void largeButUnderTheLimitCompiles() {
    Compilation c = Compilation.builder()
        .template("big.html", "<div " + NS + " t:params=\"" + PARAMS + "\">" + ROW.repeat(500) + "</div>")
        .compile()
        .assertSuccess();
    assertEquals(500, c.render("big", Map.of("a", "t", "n", 1)).split("<a ", -1).length - 1);
  }
}
