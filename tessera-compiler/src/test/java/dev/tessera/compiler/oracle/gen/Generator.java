package dev.tessera.compiler.oracle.gen;

import dev.tessera.compiler.oracle.OracleCase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The oracle's template generator v1 (#321; design: §5.7 step 1 of docs/security-architecture.md).
 * Writes {@link GeneratedCase}s from a small grammar with seven axes:
 *
 * <ol>
 *   <li>outer wrapper and
 *   <li>middle wrapper ({@link Container#WRAPPERS}, either may be {@code none}),
 *   <li>innermost container ({@link Container}, covering every parse mode of §5.3),
 *   <li>static interference before the hole ({@link Interference}),
 *   <li>hole position ({@link Hole}),
 *   <li>composition ({@link Composition}),
 *   <li>insertion point ({@link Container#INSERTION_POINTS}; only for {@code t:insert}/{@code t:replace}).
 * </ol>
 *
 * <p><b>Default enumeration</b> ({@link #defaultCases()}, what {@code mvn verify} runs): the {@link
 * #ANCHORS} (seed probes of #320 the grammar reproduces exactly), then a deterministic pairwise
 * covering array: every pair of values of two axes appears in at least one case. Same grammar, same
 * cases, same ids. The count is bounded by {@link #DEFAULT_CAP}; {@code -Dtessera.oracle.gen.max=<n>}
 * changes it for deeper local or nightly runs: below the pairwise size it truncates (losing
 * coverage), above it adds seeded draws from the full product.
 *
 * <p><b>Explore mode</b> ({@link #exploreFromSystemProperties}, {@code -Dtessera.oracle.explore=<n>
 * -Dtessera.oracle.seed=<s>}): {@code n} cases, half random points of the full product and half
 * random mutations ({@link Mutator}) of the default cases and the given seed templates. Same seed,
 * same cases. Never part of {@code mvn verify}.
 *
 * <p>Changing the grammar (adding a value, reordering constants) changes the enumeration; a
 * known-holes line (#324) whose case is no longer generated should become a seed (#320).
 *
 * <p>No compiler call here: this only writes template text.
 */
public final class Generator {

  /** System property: the number of cases in the default enumeration. */
  public static final String MAX_CASES = "tessera.oracle.gen.max";
  /** System property: run explore mode with this many cases. */
  public static final String EXPLORE = "tessera.oracle.explore";
  /** System property: the explore-mode seed (default 1). */
  public static final String SEED = "tessera.oracle.seed";

  /** The most cases the default enumeration may hold, so the gate stays within its time budget. */
  public static final int DEFAULT_CAP = 2000;

  private static final long ENUMERATION_SEED = 321L;

  private static final Container[] INNER = Container.values();
  private static final Interference[] INTERFERENCE = Interference.values();
  private static final Hole[] HOLES = Hole.values();
  private static final Composition[] COMPOSITIONS = Composition.values();
  private static final int[] SIZES = {
    Container.WRAPPERS.size(),
    Container.WRAPPERS.size(),
    INNER.length,
    INTERFERENCE.length,
    HOLES.length,
    COMPOSITIONS.length,
    Container.INSERTION_POINTS.size()
  };
  private static final int COMPOSITION_AXIS = 5;
  private static final int INSERTION_AXIS = 6;

  /** A seed probe of #320 that the grammar reproduces exactly (same template, same id). */
  public record Anchor(String probe, String body, Shape shape) {}

  /**
   * Seeds from #320 that the default enumeration always contains, with the probe body (inside
   * {@code <div t:params="String s">}) they reproduce.
   */
  public static final List<Anchor> ANCHORS =
      List.of(
          new Anchor("A2", "<script>var a = \"[[s]]\";</script>",
              Shape.of(Container.SCRIPT, Interference.NONE, Hole.SCRIPT_DOUBLE_QUOTED)),
          new Anchor("A4", "<svg><script>var a = &quot;[[s]]&quot;;</script></svg>",
              Shape.of(Container.SVG_SCRIPT, Interference.QUOT_REFERENCES, Hole.SCRIPT_VAR)),
          new Anchor("A5", "<svg><title><script>var a = [[s]];</script></title></svg>",
              Shape.of(Container.SVG_TITLE, Interference.NONE, Hole.SCRIPT_VAR)),
          new Anchor("A7", "<svg><title>[[s]]</title></svg>",
              Shape.of(Container.SVG_TITLE, Interference.NONE, Hole.TEXT_INLINE)),
          new Anchor("A13", "<p title=a\"><script>//\">\n[[s]]\n//</p><!-- </script> -->",
              Shape.of(Container.NONE, Interference.QUOTE_HIDDEN_SCRIPT, Hole.TEXT_INLINE)),
          new Anchor("A14", "<!--> <script> -->\n[[s]]\n<!-- </script> -->",
              Shape.of(Container.NONE, Interference.COMMENT_HIDDEN_SCRIPT, Hole.TEXT_INLINE)),
          new Anchor("A15", "<p><[[s]]></p>",
              Shape.of(Container.P, Interference.LT_GT, Hole.TEXT_INLINE)),
          new Anchor("A16", "<title t:remove=\"tag\"><script>[[s]]</script></title>",
              Shape.of(Container.TITLE, Interference.NONE, Hole.SCRIPT_BARE).with(Composition.REMOVE_TAG, null)),
          new Anchor("A33", "<script>var r = x\n/\"/.test(y); var z = [[s]];</script>",
              Shape.of(Container.SCRIPT, Interference.NONE, Hole.SCRIPT_AFTER_REGEX)),
          new Anchor("B0", "<svg><script>/*</svg><img src=x title=*/[[s]]//></script></svg>",
              Shape.of(Container.SVG_SCRIPT, Interference.SVG_SCRIPT_BREAKOUT, Hole.SCRIPT_BARE)),
          new Anchor("A17", "<script t:remove=\"tag\"><img src=x onerror=[[s]]></script>",
              Shape.of(Container.SCRIPT, Interference.NONE, Hole.INLINE_HANDLER_UNQUOTED)
                  .with(Composition.REMOVE_TAG, null)),
          new Anchor("#338 textarea", "<textarea\u000B><a onclick=\"[[s]]\">x</a></textarea>",
              Shape.of(Container.NONE, Interference.TEXTAREA_VT, Hole.INLINE_HANDLER_QUOTED)),
          new Anchor("#296 processing instruction", "<?x > <script>//?>\n[[s]]\n<!-- </script> -->",
              Shape.of(Container.NONE, Interference.PI_HIDDEN_SCRIPT, Hole.TEXT_INLINE)),
          new Anchor("#298 review",
              "<textarea x=y/><a title=\"</textarea><script>//\">\n[[s]]\n//</a><!-- </script> -->",
              Shape.of(Container.NONE, Interference.UNQUOTED_SLASH_TEXTAREA, Hole.TEXT_INLINE)));

  private static List<Shape> pairwise;

  private Generator() {}

  /**
   * The default enumeration: anchors plus the pairwise covering array, at most {@link #DEFAULT_CAP}
   * cases, or {@code -Dtessera.oracle.gen.max} cases when set.
   */
  public static List<GeneratedCase> defaultCases() {
    Integer max = Integer.getInteger(MAX_CASES);
    return max == null ? enumerate(DEFAULT_CAP, false) : enumerate(max, true);
  }

  /**
   * The first {@code max} cases of the default enumeration; with {@code fill}, seeded draws from
   * the full product top it up to {@code max} when the pairwise set is smaller.
   */
  public static List<GeneratedCase> enumerate(int max, boolean fill) {
    if (max < 0) {
      throw new IllegalArgumentException(MAX_CASES + " must not be negative: " + max);
    }
    Map<String, GeneratedCase> cases = new LinkedHashMap<>();
    for (Shape shape : pairwiseShapes()) {
      if (cases.size() >= max) {
        break;
      }
      GeneratedCase c = GeneratedCase.of(shape, OracleCase.Origin.GENERATED);
      cases.putIfAbsent(c.oracleCase().id(), c);
    }
    Random random = new Random(ENUMERATION_SEED + 1);
    for (int attempts = 0; fill && cases.size() < max && attempts < 50 * max; attempts++) {
      GeneratedCase c = GeneratedCase.of(randomShape(random), OracleCase.Origin.GENERATED);
      cases.putIfAbsent(c.oracleCase().id(), c);
    }
    return List.copyOf(cases.values());
  }

  /**
   * Explore mode from system properties: empty unless {@code -Dtessera.oracle.explore=<n>} is set.
   * {@code seeds} are extra templates to mutate (the #320 corpus, bodies or full templates).
   */
  public static List<GeneratedCase> exploreFromSystemProperties(List<String> seeds) {
    Integer n = Integer.getInteger(EXPLORE);
    if (n == null) {
      return List.of();
    }
    return explore(Long.getLong(SEED, 1L), n, seeds);
  }

  /**
   * {@code n} explore-mode cases for {@code seed}: even-numbered ones are random points of the full
   * product, odd-numbered ones are mutants of a default case or of one of {@code seeds}.
   */
  public static List<GeneratedCase> explore(long seed, int n, List<String> seeds) {
    Random random = new Random(seed);
    List<GeneratedCase> defaults = defaultCases();
    Map<String, GeneratedCase> cases = new LinkedHashMap<>();
    for (int i = 0, attempts = 0; cases.size() < n && attempts < 50 * n + 50; i++, attempts++) {
      GeneratedCase c;
      if (i % 2 == 0) {
        Shape shape = randomShape(random);
        c = new GeneratedCase(
            OracleCase.of("explore " + shape.name(), shape.template(), OracleCase.Origin.EXPLORE), shape.params(), shape);
      } else {
        int pick = random.nextInt(defaults.size() + seeds.size());
        String base;
        String baseName;
        List<Shape.Param> params;
        if (pick < defaults.size()) {
          GeneratedCase d = defaults.get(pick);
          base = d.oracleCase().template();
          baseName = d.oracleCase().name();
          params = d.params();
        } else {
          base = seeds.get(pick - defaults.size());
          baseName = "seed " + OracleCase.idOf(base);
          params = List.of(Shape.STRING);
        }
        Mutator.Mutant mutant = Mutator.mutate(base, random);
        c = new GeneratedCase(
            OracleCase.of("explore " + mutant.ops() + " of " + baseName, mutant.template(), OracleCase.Origin.EXPLORE),
            params, null);
      }
      cases.putIfAbsent(c.oracleCase().id(), c);
    }
    return List.copyOf(cases.values());
  }

  /** The anchors, then the pairwise covering array; computed once. */
  static synchronized List<Shape> pairwiseShapes() {
    if (pairwise == null) {
      pairwise = computePairwise();
    }
    return pairwise;
  }

  /** The anchors, then the pairwise covering array, computed afresh (for the determinism test). */
  static List<Shape> computePairwise() {
    List<int[]> forced = new ArrayList<>();
    for (Anchor anchor : ANCHORS) {
      forced.add(indices(anchor.shape()));
    }
    Pairwise p = new Pairwise(SIZES, INSERTION_AXIS, COMPOSITION_AXIS, v -> COMPOSITIONS[v].inserts(), ENUMERATION_SEED);
    List<Shape> shapes = new ArrayList<>();
    Set<Shape> seen = new LinkedHashSet<>();
    for (int[] row : p.cover(forced)) {
      Shape shape = shape(row);
      if (seen.add(shape)) {
        shapes.add(shape);
      }
    }
    return List.copyOf(shapes);
  }

  private static Shape randomShape(Random random) {
    int[] row = new int[SIZES.length];
    for (int i = 0; i < SIZES.length; i++) {
      row[i] = random.nextInt(SIZES[i]);
    }
    return shape(row);
  }

  private static Shape shape(int[] row) {
    Composition composition = COMPOSITIONS[row[COMPOSITION_AXIS]];
    return new Shape(
        Container.WRAPPERS.get(row[0]),
        Container.WRAPPERS.get(row[1]),
        INNER[row[2]],
        INTERFERENCE[row[3]],
        HOLES[row[4]],
        composition,
        composition.inserts() ? Container.INSERTION_POINTS.get(row[INSERTION_AXIS]) : null);
  }

  private static int[] indices(Shape s) {
    return new int[] {
      wrapperIndex(s.outer()),
      wrapperIndex(s.middle()),
      s.inner().ordinal(),
      s.interference().ordinal(),
      s.hole().ordinal(),
      s.composition().ordinal(),
      s.insertion() == null ? 0 : Container.INSERTION_POINTS.indexOf(s.insertion())
    };
  }

  private static int wrapperIndex(Container c) {
    int i = Container.WRAPPERS.indexOf(c);
    if (i < 0) {
      throw new IllegalArgumentException("not a wrapper: " + c);
    }
    return i;
  }
}
