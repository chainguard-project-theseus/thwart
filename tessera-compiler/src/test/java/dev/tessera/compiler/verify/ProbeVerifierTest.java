package dev.tessera.compiler.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The Oct 24 review probes and the #351 table finding (src/test/resources/verify/probes.txt), #367. */
class ProbeVerifierTest {

  record Probe(String name, String expect, boolean profileRejects, String template) {
    @Override
    public String toString() {
      return name + " " + expect;
    }
  }

  static Stream<Probe> probes() throws IOException {
    String text;
    try (InputStream in = ProbeVerifierTest.class.getResourceAsStream("/verify/probes.txt")) {
      assertNotNull(in);
      text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    List<Probe> out = new ArrayList<>();
    for (String chunk : text.split("\n====\n")) {
      String name = null;
      String expect = null;
      boolean profile = false;
      StringBuilder body = new StringBuilder();
      for (String line : chunk.split("\n")) {
        if (line.startsWith("# name:")) {
          name = line.substring(7).trim();
        } else if (line.startsWith("# expect:")) {
          expect = line.substring(9).trim();
        } else if (line.startsWith("# profile:")) {
          profile = true;
        } else if (!line.startsWith("#")) {
          body.append(body.length() > 0 ? "\n" : "").append(line);
        }
      }
      if (name != null) {
        String b = body.toString().strip();
        String t = b.matches("(?s)\\A<[A-Za-z][^>]*t:params=.*") ? b : "<div t:params=\"String s\">" + b + "</div>";
        out.add(new Probe(name, expect, profile, t));
      }
    }
    return out.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("probes")
  void probe(Probe p) {
    switch (p.expect()) {
      case "VERIFIER" -> {
        Compilation c = Compilation.builder().template("t.html", p.template()).verifierOnly().compile();
        assertFalse(c.success(), p.name() + ": the verifier alone accepted it");
        assertTrue(c.errors().stream().allMatch(e -> e.contains("[verifier:")), c.errors().toString());
        assertFalse(Compilation.builder().template("t.html", p.template()).compile().success());
      }
      case "PROFILE" -> assertFalse(Compilation.builder().template("t.html", p.template()).compile().success(), p.name());
      case "ACCEPT" -> {
        Compilation.builder().template("t.html", p.template()).verifierOnly().compile().assertSuccess();
        assertEquals(!p.profileRejects(), Compilation.builder().template("t.html", p.template()).compile().success(), p.name());
      }
      case "NOT_COVERED" -> {
        // Outside markup contexts; other checks own these.
      }
      default -> throw new AssertionError("unknown expectation " + p.expect());
    }
  }
}
