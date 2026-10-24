package dev.tessera.compiler.oracle.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.tessera.compiler.oracle.OracleCase;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class GeneratorTest {

  private static final String ROOT = "<div t:params=\"String s\">";

  private static List<String> ids(List<GeneratedCase> cases) {
    return cases.stream().map(c -> c.oracleCase().id()).toList();
  }

  // ---------------------------------------------------------------- default enumeration

  @Test
  void defaultEnumerationIsDeterministic() {
    List<Shape> first = Generator.computePairwise();
    List<Shape> second = Generator.computePairwise();
    assertEquals(first, second);
    List<String> again = first.stream().map(s -> OracleCase.idOf(s.template())).toList();
    assertEquals(again, ids(Generator.defaultCases()));
    assertEquals(ids(Generator.defaultCases()), ids(Generator.defaultCases()));
  }

  @Test
  void defaultEnumerationIsWithinTheCapAndNotTruncated() {
    List<GeneratedCase> cases = Generator.defaultCases();
    System.out.println("oracle generator: " + cases.size() + " default cases (cap " + Generator.DEFAULT_CAP + ")");
    assertTrue(cases.size() <= Generator.DEFAULT_CAP, cases.size() + " cases");
    // The pairwise set fits: nothing was cut off, so the coverage below holds for mvn verify.
    assertEquals(Generator.pairwiseShapes().size(), cases.size());
  }

  @Test
  void everyCaseIsAValidOracleCaseWithAUniqueStableId() {
    List<GeneratedCase> cases = Generator.defaultCases();
    Set<String> ids = new HashSet<>();
    Set<String> names = new HashSet<>();
    for (GeneratedCase c : cases) {
      OracleCase oc = c.oracleCase();
      assertEquals(OracleCase.idOf(oc.template()), oc.id());
      assertEquals(OracleCase.Origin.GENERATED, oc.origin());
      assertTrue(ids.add(oc.id()), "duplicate id " + oc.id());
      assertTrue(names.add(oc.name()), "duplicate name " + oc.name());
      assertEquals(c.shape().name(), oc.name());
      assertEquals(c.shape().params(), c.params());
    }
  }

  @Test
  void everyCaseIsASyntacticallyCompleteTemplateBody() {
    for (GeneratedCase c : Generator.defaultCases()) {
      Shape shape = c.shape();
      String t = c.oracleCase().template();
      String declared = c.params().stream().map(Shape.Param::toString).collect(Collectors.joining(", "));
      assertTrue(t.startsWith("<div t:params=\"" + declared + "\">"), t);
      assertTrue(t.endsWith("</div>"), t);
      // Every declared parameter is used.
      String value = shape.hole().safeHtml() ? "h" : "s";
      assertTrue(t.contains("[[" + value + "]]") || t.contains("[(" + value + ")]") || t.contains("=\"" + value + "\"")
          || t.contains("x=s") || t.contains("${s}"), "hole missing: " + t);
      if (c.params().contains(Shape.FRAGMENT)) {
        assertTrue(t.contains("t:replace=\"f\""), t);
      }
      // The static interference is written as given, and the rest is a balanced element tree.
      Interference i = shape.interference();
      assertTrue(t.contains(i.prefix()) && t.contains(i.suffix()), t);
      String clean = shape.with(Interference.NONE).template();
      assertBalanced(clean);
      assertEquals(clean.length() + i.prefix().length() + i.suffix().length(), t.length(), t);
    }
  }

  private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z][\\w:-]*)[^<>]*>");
  private static final Set<String> VOID = Set.of("img", "input");

  /** The elements the generator writes (interference aside) nest properly. */
  private static void assertBalanced(String template) {
    Deque<String> open = new ArrayDeque<>();
    Matcher m = TAG.matcher(template);
    while (m.find()) {
      String name = m.group(2);
      if (m.group(1).isEmpty()) {
        if (!VOID.contains(name)) {
          open.push(name);
        }
      } else if (open.isEmpty() || !open.pop().equals(name)) {
        fail("unbalanced </" + name + "> in " + template);
      }
    }
    assertTrue(open.isEmpty(), "unclosed " + open + " in " + template);
  }

  @Test
  void everyAxisValueIsCovered() {
    List<Shape> shapes = Generator.defaultCases().stream().map(GeneratedCase::shape).toList();
    assertAll(Container.values(), shapes, Shape::inner);
    assertAll(Container.WRAPPERS.toArray(Container[]::new), shapes, Shape::outer);
    assertAll(Container.WRAPPERS.toArray(Container[]::new), shapes, Shape::middle);
    assertAll(Interference.values(), shapes, Shape::interference);
    assertAll(Hole.values(), shapes, Shape::hole);
    assertAll(Composition.values(), shapes, Shape::composition);
    assertAll(Container.INSERTION_POINTS.toArray(Container[]::new), shapes, Shape::insertion);
    // Depth 0 to 3 under the root.
    assertEquals(Set.of(0, 1, 2, 3), shapes.stream().map(s -> s.chain().size()).collect(Collectors.toSet()));
  }

  private static <T> void assertAll(T[] values, List<Shape> shapes, Function<Shape, T> axis) {
    Set<T> seen = shapes.stream().map(axis).collect(Collectors.toSet());
    for (T v : values) {
      assertTrue(seen.contains(v), "no case with " + v);
    }
  }

  @Test
  void everyParseModeOfTheProfileIsTheInnermostContextOfSomeCase() {
    Set<Container.Mode> modes = EnumSet.noneOf(Container.Mode.class);
    for (GeneratedCase c : Generator.defaultCases()) {
      modes.add(c.shape().inner().mode());
    }
    assertEquals(EnumSet.allOf(Container.Mode.class), modes);
    // The parse modes of §5.3 and #321, by the container that exercises them.
    Map<Container.Mode, List<Container>> expected =
        Map.of(
            Container.Mode.FOREIGN_OBJECT, List.of(Container.SVG_FOREIGN_OBJECT),
            Container.Mode.FOSTER, List.of(Container.TABLE, Container.TABLE_TR),
            Container.Mode.SELECT, List.of(Container.SELECT, Container.SELECT_OPTION),
            Container.Mode.INTEGRATION_POINT,
                List.of(Container.SVG_TITLE, Container.SVG_DESC, Container.MATH_MI, Container.MATH_MTEXT,
                    Container.MATH_ANNOTATION_XML));
    expected.forEach((mode, containers) -> containers.forEach(c -> assertEquals(mode, c.mode(), c.name())));
  }

  @Test
  void everyPairOfAxisValuesIsCovered() {
    List<Shape> shapes = Generator.defaultCases().stream().map(GeneratedCase::shape).toList();
    List<Function<Shape, Object>> axes =
        List.of(Shape::outer, Shape::middle, Shape::inner, Shape::interference, Shape::hole, Shape::composition,
            Shape::insertion);
    List<List<?>> values =
        List.of(Container.WRAPPERS, Container.WRAPPERS, List.of(Container.values()), List.of(Interference.values()),
            List.of(Hole.values()), List.of(Composition.values()), Container.INSERTION_POINTS);
    int insertion = 6;
    int composition = 5;
    int missing = 0;
    StringBuilder report = new StringBuilder();
    for (int a = 0; a < axes.size(); a++) {
      for (int b = a + 1; b < axes.size(); b++) {
        Set<List<Object>> seen = new HashSet<>();
        for (Shape s : shapes) {
          seen.add(List.of(String.valueOf(axes.get(a).apply(s)), String.valueOf(axes.get(b).apply(s))));
        }
        for (Object va : values.get(a)) {
          for (Object vb : values.get(b)) {
            if (b == insertion && a == composition && !((Composition) va).inserts()) {
              continue; // an insertion point goes with t:insert/t:replace only
            }
            if (!seen.contains(List.of(String.valueOf(va), String.valueOf(vb)))) {
              missing++;
              if (missing < 20) {
                report.append(va).append(" x ").append(vb).append('\n');
              }
            }
          }
        }
      }
    }
    assertEquals(0, missing, "uncovered pairs:\n" + report);
  }

  @Test
  void theInsertionPointIsSetExactlyForInsertAndReplace() {
    for (GeneratedCase c : Generator.defaultCases()) {
      Shape s = c.shape();
      assertEquals(s.composition().inserts(), s.insertion() != null, s.name());
    }
    assertThrows(IllegalArgumentException.class,
        () -> Shape.of(Container.DIV, Interference.NONE, Hole.TEXT_INLINE).with(Composition.INSERT, null));
    assertThrows(IllegalArgumentException.class,
        () -> Shape.of(Container.DIV, Interference.NONE, Hole.TEXT_INLINE).with(Composition.NONE, Container.DIV));
  }

  // ---------------------------------------------------------------- rendering of each dimension

  @Test
  void compositionsAreWrittenAsTesseraSyntax() {
    Shape base = Shape.of(Container.SVG_TITLE, Interference.NONE, Hole.TEXT_INLINE);
    assertEquals(
        ROOT + "<svg><title t:fragment=\"frag(String s)\">[[s]]</title></svg>"
            + "<template t:insert=\"~{:: frag(s)}\"></template></div>",
        base.with(Composition.INSERT, Container.TEMPLATE).template());
    assertEquals(
        ROOT + "<svg><title t:fragment=\"frag(String s)\">[[s]]</title></svg>"
            + "<table><tbody><tr><td><t:block t:replace=\"~{:: frag(s)}\"></t:block></td></tr></tbody></table></div>",
        base.with(Composition.REPLACE, Container.TABLE_TD).template());
    assertEquals(
        "<div t:params=\"String s, Fragment f\"><svg><title>[[s]]<t:block t:replace=\"f\"></t:block></title></svg></div>",
        base.with(Composition.FRAGMENT_PARAM, null).template());
    assertEquals(ROOT + "<svg><title t:remove=\"tag\">[[s]]</title></svg></div>",
        base.with(Composition.REMOVE_TAG, null).template());
    // SafeHtml holes declare SafeHtml h, and a fragment takes it.
    assertEquals(
        "<div t:params=\"SafeHtml h\"><select t:fragment=\"frag(SafeHtml h)\">[(h)]</select>"
            + "<svg t:insert=\"~{:: frag(h)}\"></svg></div>",
        Shape.of(Container.SELECT, Interference.NONE, Hole.SAFE_HTML_INLINE).with(Composition.INSERT, Container.SVG)
            .template());
  }

  @Test
  void holesArePlacedByKind() {
    assertEquals(ROOT + "<title t:text=\"s\"></title></div>",
        Shape.of(Container.TITLE, Interference.NONE, Hole.T_TEXT).template());
    // t:text with interference: around the element that carries it; a span when there's no container.
    assertEquals(ROOT + "<!--><span t:text=\"s\"></span></div>",
        Shape.of(Container.NONE, Interference.EMPTY_COMMENT, Hole.T_TEXT).template());
    assertEquals(ROOT + "<li></title><a t:href=\"s\">x</a></li></div>",
        Shape.of(Container.LI, Interference.TITLE_END, Hole.ATTR_HREF).template());
    // Script holes are wrapped in a <script> unless the container is one; interference goes before [[s]].
    assertEquals(ROOT + "<div><script>var a = \"//[[s]]\";</script></div></div>",
        Shape.of(Container.DIV, Interference.LINE_COMMENT_START, Hole.SCRIPT_DOUBLE_QUOTED).template());
    assertEquals(ROOT + "<script>// [[s]]\n</script></div>",
        Shape.of(Container.SCRIPT, Interference.NONE, Hole.SCRIPT_LINE_COMMENT).template());
    assertEquals(ROOT + "<svg><style>a { color: [[s]]; }</style></svg></div>",
        Shape.of(Container.SVG_STYLE, Interference.NONE, Hole.STYLE_BLOCK).template());
    assertEquals(ROOT + "<p><style>a { color: [[s]]; }</style></p></div>",
        Shape.of(Container.P, Interference.NONE, Hole.STYLE_BLOCK).template());
    // Non-HTML whitespace in a tag name (#338) is written as the raw character.
    assertEquals(ROOT + "<p><textarea\u000B><span t:title=\"s\">x</span></textarea></p></div>",
        Shape.of(Container.P, Interference.TEXTAREA_VT, Hole.ATTR_TITLE).template());
    // Depth 3 with the attribute of annotation-xml.
    assertEquals(
        ROOT + "<table><caption><math><annotation-xml encoding=\"text/html\"><svg><title>[[s]]</title></svg>"
            + "</annotation-xml></math></caption></table></div>",
        Shape.of(Container.SVG_TITLE, Interference.NONE, Hole.TEXT_INLINE)
            .wrappedIn(Container.TABLE_CAPTION, Container.MATH_ANNOTATION_XML).template());
  }

  // ---------------------------------------------------------------- seeds the grammar reproduces

  /**
   * The #320 seeds the generator reproduces exactly, as generated cases of the default enumeration
   * (same template, so same id once the seed loader wraps bodies in {@code <div t:params="String
   * s">}):
   *
   * <pre>
   * A2   script                  | none                  | script:"[[s]]"
   * A4   svg>script              | &amp;quot;..&amp;quot;        | script:var a = [[s]];
   * A5   svg>title               | none                  | script:var a = [[s]];  (svg title script)
   * A7   svg>title               | none                  | [[s]]
   * A13  none                    | quote-hidden script   | [[s]]
   * A14  none                    | &lt;!--&gt; comment-hidden script | [[s]]
   * A15  p                       | &lt;..&gt;                  | [[s]]
   * A16  title, t:remove="tag"   | none                  | script:[[s]]
   * A33  script                  | none                  | script:x\n/"/.test(y); [[s]]
   * B0   svg>script              | /*&lt;/svg&gt;&lt;img src=x title=*&#47;..//&gt; | script:[[s]]  (svg script breakout)
   * #298 review none             | &lt;textarea x=y/&gt;       | [[s]]
   * </pre>
   */
  @Test
  void reproducesTheSeedProbes() {
    Set<String> generated = new HashSet<>(ids(Generator.defaultCases()));
    Set<String> probes = new HashSet<>();
    for (Generator.Anchor anchor : Generator.ANCHORS) {
      String expected = ROOT + anchor.body() + "</div>";
      assertEquals(expected, anchor.shape().template(), anchor.probe());
      assertTrue(generated.contains(OracleCase.idOf(expected)), anchor.probe() + " is not generated");
      probes.add(anchor.probe());
    }
    assertTrue(probes.containsAll(List.of("A4", "A5", "A14", "A15", "A16", "B0")), probes.toString());
  }

  // ---------------------------------------------------------------- configuration

  @Test
  void theCaseCountIsConfigurable() {
    List<GeneratedCase> all = Generator.defaultCases();
    List<GeneratedCase> few = Generator.enumerate(10, false);
    assertEquals(ids(all).subList(0, 10), ids(few));
    // The anchors come first, so even a small run has them.
    assertEquals(Generator.ANCHORS.get(0).shape().template(), few.get(0).oracleCase().template());
    List<GeneratedCase> more = Generator.enumerate(all.size() + 100, true);
    assertEquals(all.size() + 100, more.size());
    assertEquals(ids(all), ids(more).subList(0, all.size()));
    assertEquals(ids(more), ids(Generator.enumerate(all.size() + 100, true)));
    assertEquals(all.size(), Generator.enumerate(all.size() + 100, false).size());
    assertThrows(IllegalArgumentException.class, () -> Generator.enumerate(-1, false));
  }

  // ---------------------------------------------------------------- explore mode

  private static final List<String> SEEDS =
      List.of("<svg><title><script>var a = [[s]];</script></title></svg>", "<p t:title=\"s\">x</p>");

  @Test
  void exploreIsOffByDefault() {
    if (System.getProperty(Generator.EXPLORE) == null) {
      assertEquals(List.of(), Generator.exploreFromSystemProperties(SEEDS));
    }
  }

  @Test
  void exploreWithTheSameSeedGivesTheSameCases() {
    List<GeneratedCase> a = Generator.explore(42, 300, SEEDS);
    List<GeneratedCase> b = Generator.explore(42, 300, SEEDS);
    assertEquals(300, a.size());
    assertEquals(ids(a), ids(b));
    assertEquals(a.stream().map(c -> c.oracleCase().name()).toList(), b.stream().map(c -> c.oracleCase().name()).toList());
    assertNotEquals(ids(a), ids(Generator.explore(43, 300, SEEDS)));
    assertEquals(300, new HashSet<>(ids(a)).size());
    int mutants = 0;
    for (GeneratedCase c : a) {
      OracleCase oc = c.oracleCase();
      assertEquals(OracleCase.Origin.EXPLORE, oc.origin());
      assertEquals(OracleCase.idOf(oc.template()), oc.id());
      assertTrue(oc.template().startsWith("<div t:params=\"") && oc.template().endsWith("</div>"), oc.template());
      if (c.shape() == null) {
        mutants++;
        String value = c.params().contains(Shape.SAFE_HTML) ? "h" : "s";
        assertTrue(oc.template().contains(value), "a mutant keeps its hole: " + oc.template());
      }
    }
    assertTrue(mutants > 100, mutants + " mutants");
  }

  @Test
  void mutatorAppliesEachOperationAndKeepsRootAndHoles() {
    String template = ROOT + "<p title=\"a\"><svg><title>[[s]]</title></svg><span t:title=\"s\">x</span></p></div>";
    Random random = new Random(5);
    Set<Mutator.Op> seen = EnumSet.noneOf(Mutator.Op.class);
    for (int i = 0; i < 500; i++) {
      Mutator.Mutant m = Mutator.mutate(template, random);
      seen.addAll(m.ops());
      assertTrue(m.template().startsWith(ROOT) && m.template().endsWith("</div>"), m.template());
      assertTrue(m.template().contains("[[s]]"), m.template());
      assertTrue(m.template().contains("t:title=\"s\"") || m.template().contains("t:title='s'"), m.template());
      if (m.ops().equals(List.of(Mutator.Op.TAG_CASE))) {
        assertFalse(m.template().equals(template), "tag case changed nothing");
      }
      if (m.ops().equals(List.of(Mutator.Op.SWAP_QUOTES))) {
        assertTrue(m.template().contains("'"), m.template());
      }
    }
    assertEquals(EnumSet.allOf(Mutator.Op.class), seen);
    // A body without a t:params root is wrapped.
    assertTrue(Mutator.mutate("<p>[[s]]</p>", new Random(1)).template().startsWith(ROOT));
  }

  @Test
  void seedTextIsInTheSeedFileFormat() {
    GeneratedCase c = GeneratedCase.of(Generator.ANCHORS.get(2).shape(), OracleCase.Origin.EXPLORE);
    assertEquals(
        "# name: gen-" + c.oracleCase().id() + "\n# expect-0dda2f1: TODO\n# fixed-by: TODO\n"
            + "<svg><title><script>var a = [[s]];</script></title></svg>\n",
        c.seedText());
    assertNull(new GeneratedCase(c.oracleCase(), List.of(), null).shape());
  }

  /**
   * Explore mode: {@code mvn -pl tessera-compiler test -Dtest=GeneratorTest -Dtessera.oracle.explore=500
   * -Dtessera.oracle.seed=7} prints the drawn cases in the seed format. The gate (#324) runs them
   * through the checks and prints the counterexamples the same way.
   */
  @Test
  @EnabledIfSystemProperty(named = Generator.EXPLORE, matches = "\\d+")
  void exploreModePrintsTheDrawnCases() {
    List<GeneratedCase> cases = Generator.exploreFromSystemProperties(new ArrayList<>(SEEDS));
    assertFalse(cases.isEmpty());
    for (GeneratedCase c : cases) {
      System.out.println("# " + c.oracleCase().name().replace("\n", "\\n"));
      System.out.println(c.seedText() + "====");
    }
  }
}
