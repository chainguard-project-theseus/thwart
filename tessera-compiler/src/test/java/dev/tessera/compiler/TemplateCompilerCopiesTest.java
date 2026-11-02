package dev.tessera.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.verify.Skeleton;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #666: a private fragment copy writes its text holes with the escapers the verifier named for the
 * fragment it copies, and only if it writes exactly that fragment's skeleton.
 */
class TemplateCompilerCopiesTest {
  private static final SourcePos POS = new SourcePos("lay.html", 1, 1);
  private static final Skeleton.FragmentId LAY = new Skeleton.FragmentId("lay", "lay");

  private static Skeleton.Seq seq(Skeleton.Node... nodes) {
    return new Skeleton.Seq(List.of(nodes));
  }

  /** A result with one template class whose Java is {@code java}, and a copy of {@code lay :: lay}. */
  private static TemplateCompiler.Result result(String java, Skeleton.Seq fragment, Skeleton.Seq copy) {
    Skeleton.TemplateSkeleton lay = new Skeleton.TemplateSkeleton("lay", seq(), Map.of("lay", fragment));
    return new TemplateCompiler.Result(
        List.of(new GeneratedFile(GeneratedFile.Kind.JAVA, "p.PageTemplate", java)), Map.of("lay", lay), null,
        null, List.of(new AbstractMap.SimpleImmutableEntry<>(LAY, copy)));
  }

  /** The text-hole marker generated code holds until the verifier names the escaper. */
  private static String marker(Skeleton.Hole h) {
    return "\0" + h.id() + "\0";
  }

  @Test
  void aCopysHoleGetsTheEscaperOfTheFragmentsHole() {
    Skeleton.Hole original = new Skeleton.Hole(Skeleton.Escaper.TEXT, "title", null, POS);
    Skeleton.Hole copied = new Skeleton.Hole(Skeleton.Escaper.TEXT, "title", null, POS);
    String java = result("A" + marker(copied) + "B", seq(original), seq(copied))
        .files(Map.of(original, Skeleton.Escaper.RCDATA)).get(0).content();
    assertEquals("A" + Skeleton.Escaper.RCDATA.method() + "B", java);
  }

  /** Negative: a copy that writes anything else than the fragment the verifier checked is refused. */
  @Test
  void aCopyWithAnotherSkeletonIsRefused() {
    Skeleton.Hole original = new Skeleton.Hole(Skeleton.Escaper.TEXT, "title", null, POS);
    Skeleton.Hole copied = new Skeleton.Hole(Skeleton.Escaper.TEXT, "title", null, POS);
    TemplateCompiler.Result r =
        result("A" + marker(copied) + "B", seq(original), seq(new Skeleton.Static("<script>", POS), copied));
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> r.files(Map.of(original, Skeleton.Escaper.RCDATA)));
    assertTrue(e.getMessage().contains("writes another skeleton than the fragment"), e.getMessage());
  }
}
