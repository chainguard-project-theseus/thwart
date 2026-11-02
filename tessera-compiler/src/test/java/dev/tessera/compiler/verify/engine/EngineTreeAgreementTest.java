package dev.tessera.compiler.verify.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.oracle.SpecParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The shaded engine parses exactly like the published jar (#365): on a sample of html5lib
 * tree-construction cases, the tree built with the renamed, Java 8 engine equals the tree the
 * test-scope oracle jar (validator 26.10.12) builds. This checks the build (fetch, rename,
 * compile), not the spec.
 */
class EngineTreeAgreementTest {

  record Case(String source, String data, boolean scripting) {
    @Override
    public String toString() {
      return source;
    }
  }

  static List<Case> cases() throws IOException {
    String text;
    try (InputStream in = EngineTreeAgreementTest.class.getResourceAsStream("/verify/engine/html5lib-sample.dat")) {
      text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    List<Case> cases = new ArrayList<>();
    String[] blocks = text.split("(?m)^#data\n");
    for (int i = 1; i < blocks.length; i++) {
      String b = blocks[i];
      int errors = b.indexOf("\n#errors\n");
      String data = b.substring(0, errors);
      String rest = b.substring(errors + "\n#errors\n".length());
      String source = rest.lines().filter(l -> l.startsWith("#source ")).findFirst().orElseThrow().substring(8);
      cases.add(new Case(source, data, rest.lines().anyMatch(l -> l.equals("#script-on"))));
    }
    return cases;
  }

  static Stream<Arguments> sample() throws IOException {
    return cases().stream().map(Arguments::of);
  }

  @org.junit.jupiter.api.Test
  void sampleIsAboutAHundredCases() throws IOException {
    List<Case> cases = cases();
    assertTrue(cases.size() >= 100 && cases.size() <= 130, "cases: " + cases.size());
    assertTrue(cases.stream().anyMatch(Case::scripting));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sample")
  void currentAgreesWithValidator26(Case c) throws Exception {
    String oracle = TestTree.fromDom(SpecParser.parse(c.data(), c.scripting())).serialize();
    assertEquals(oracle, CurrentTreeBuilder.parse(c.data(), c.scripting()), c.data());
  }
}
