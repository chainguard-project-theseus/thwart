package dev.tessera.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.verify.Skeleton;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** #666: a private fragment copy is written only if it writes exactly the skeleton of the fragment it copies. */
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

  private static Skeleton.Hole hole() {
    return new Skeleton.Hole(Skeleton.Escaper.TEXT, null, POS);
  }

  @Test
  void aCopyWithTheFragmentsSkeletonIsWritten() {
    assertEquals("AB", result("AB", seq(hole()), seq(hole())).files(Set.of()).get(0).content());
  }

  /** Negative: a copy that writes anything else than the fragment the verifier checked is refused. */
  @Test
  void aCopyWithAnotherSkeletonIsRefused() {
    TemplateCompiler.Result r = result("AB", seq(hole()), seq(new Skeleton.Static("<script>", POS), hole()));
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> r.files(Set.of()));
    assertTrue(e.getMessage().contains("writes another skeleton than the fragment"), e.getMessage());
  }
}
