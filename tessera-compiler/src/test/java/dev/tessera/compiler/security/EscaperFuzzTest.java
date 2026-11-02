package dev.tessera.compiler.security;

import static dev.tessera.compiler.security.HostileStrings.show;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import dev.tessera.runtime.Escapers;
import dev.tessera.runtime.StringOutput;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/**
 * Feeds random strings biased towards context-breaking characters through every runtime
 * escaper and checks with {@link ContextOracle} that the output cannot leave its context. The
 * seed is printed on failure so a run can be reproduced.
 */
class EscaperFuzzTest {
  private static final int ITERATIONS = 20_000;

  private static String run(BiConsumer<StringOutput, Object> escaper, Object value) {
    StringOutput out = new StringOutput();
    escaper.accept(out, value);
    return out.toString();
  }

  private interface Check {
    String problem(String input, String output);
  }

  private static void fuzz(String name, BiConsumer<StringOutput, Object> escaper, Check check) {
    HostileStrings.forEachSeeded(
        ITERATIONS,
        (rnd, i) -> {
          String s = HostileStrings.random(rnd, 16);
          String out = run(escaper, s);
          String problem = check.problem(s, out);
          if (problem != null) {
            fail(name + ": " + problem + "\ninput:  " + show(s) + "\noutput: " + show(out));
          }
        });
    for (String s : HostileStrings.VECTORS) {
      String problem = check.problem(s, run(escaper, s));
      if (problem != null) {
        fail(name + ": " + problem + " for " + show(s));
      }
    }
  }

  @Test
  void text() {
    fuzz("text", Escapers::text, (s, out) -> ContextOracle.html(out, s));
  }

  @Test
  void attr() {
    fuzz("attr", Escapers::attr, (s, out) -> ContextOracle.html(out, s));
  }

  @Test
  void url() {
    fuzz("url", Escapers::url, (s, out) -> ContextOracle.url(out, s));
  }

  @Test
  void resourceUrl() {
    fuzz("resourceUrl", Escapers::resourceUrl, (s, out) -> ContextOracle.url(out, s));
  }

  @Test
  void json() {
    fuzz("json", Escapers::json, (s, out) -> ContextOracle.jsonString(out, s));
  }

  @Test
  void jsonContainers() {
    HostileStrings.forEachSeeded(
        ITERATIONS / 10,
        (rnd, i) -> {
          String k = HostileStrings.random(rnd, 8);
          String v = HostileStrings.random(rnd, 8);
          String w = HostileStrings.random(rnd, 8);
          String out = run(Escapers::json, Map.of(k, List.of(v, w)));
          // {"k":["v","w"]} with every string checked on its own.
          String[] strings = splitJsonStrings(out, "{", ":[", ",", "]}");
          String[] expected = {k, v, w};
          for (int j = 0; j < 3; j++) {
            String problem = strings == null ? "structure changed" : ContextOracle.jsonString(strings[j], expected[j]);
            if (problem != null) {
              fail("json container: " + problem + "\noutput: " + show(out));
            }
          }
        });
  }

  @Test
  void oracleRejectsBrokenOutput() {
    // The oracle itself must catch what it is there to catch.
    assertEquals("raw '<' at 0", ContextOracle.html("<b>", null));
    assertEquals("raw '\"' at 1", ContextOracle.html("a\"b", null));
    assertEquals("bare & at 0", ContextOracle.html("&#106;", null));
    assertEquals("scheme javascript passed", ContextOracle.url("java&#9;script:x".replace("&#9;", "\t"), null));
    assertEquals("scheme javascript passed", ContextOracle.url(" JavaScript:x", null));
    assertEquals("raw '/' at 2", ContextOracle.jsonString("\"*/\"", null));
    assertEquals("raw '`' at 1", ContextOracle.jsonString("\"`\"", null));
    assertEquals("unescaped quote at 2", ContextOracle.jsonString("\"a\"b\"", null));
    assertEquals("decodes to a different value", ContextOracle.jsonString("\"a\"", "b"));
  }

  /** Splits {@code out} at the given literal separators; null if they are not where expected. */
  private static String[] splitJsonStrings(String out, String open, String sep1, String sep2, String close) {
    if (!out.startsWith(open) || !out.endsWith(close)) {
      return null;
    }
    String body = out.substring(open.length(), out.length() - close.length());
    int a = endOfString(body, 0);
    if (a < 0 || !body.startsWith(sep1, a)) {
      return null;
    }
    int b = endOfString(body, a + sep1.length());
    if (b < 0 || !body.startsWith(sep2, b)) {
      return null;
    }
    int c = endOfString(body, b + sep2.length());
    if (c != body.length()) {
      return null;
    }
    return new String[] {
      body.substring(0, a), body.substring(a + sep1.length(), b), body.substring(b + sep2.length(), c)
    };
  }

  /** Index just past the JSON string literal starting at {@code i}, or -1. */
  private static int endOfString(String s, int i) {
    if (i >= s.length() || s.charAt(i) != '"') {
      return -1;
    }
    for (int j = i + 1; j < s.length(); j++) {
      char c = s.charAt(j);
      if (c == '\\') {
        j++;
      } else if (c == '"') {
        return j + 1;
      }
    }
    return -1;
  }
}
