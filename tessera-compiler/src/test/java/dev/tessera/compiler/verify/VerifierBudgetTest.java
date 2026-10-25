package dev.tessera.compiler.verify;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The verifier's build-time budget (#367): verifying the repo's own templates, the examples of
 * docs/guide.md, must not take longer than {@link #BOUND_MILLIS}, three times what it took when
 * the bound was set. The numbers for real applications are in the PR for #367.
 */
class VerifierBudgetTest {
  private static final Path GUIDE = Path.of("../docs/guide.md");
  private static final Pattern EXAMPLE =
      Pattern.compile("<!-- compile: (java|messages|template (\\S+)) -->\\n```\\w*\\n(.*?)\\n```", Pattern.DOTALL);
  private static final Pattern JAVA_NAME =
      Pattern.compile("(?s)package ([\\w.]+);.*?\\b(?:class|record|enum|interface) (\\w+)");

  /** About 3x the best of four runs when the bound was set (18 ms for 17 templates, Oct 25, 2026). */
  static final long BOUND_MILLIS = 60;

  @Test
  void theGuideTemplatesVerifyWithinTheBound() throws IOException {
    String guide = Files.readString(GUIDE);
    Compilation.Builder b = Compilation.builder().option("tessera.package", "com.example.templates");
    List<String> views = new ArrayList<>();
    StringBuilder bundle = new StringBuilder();
    Matcher m = EXAMPLE.matcher(guide);
    while (m.find()) {
      String body = m.group(3);
      if (m.group(1).equals("java")) {
        Matcher n = JAVA_NAME.matcher(body);
        assertTrue(n.find());
        b.java(n.group(1) + "." + n.group(2), body);
      } else if (m.group(1).equals("messages")) {
        bundle.append(body).append('\n');
      } else {
        b.template(m.group(2), body);
        views.add(m.group(2).substring(0, m.group(2).length() - ".html".length()));
      }
    }
    Compilation c = b.messages(bundle.toString()).compile().assertSuccess();
    Map<String, Skeleton.TemplateSkeleton> skeletons = new LinkedHashMap<>();
    for (String v : views) {
      skeletons.put(v, c.skeleton(v));
    }
    long best = Long.MAX_VALUE;
    for (int i = 0; i < 4; i++) {
      long t0 = System.nanoTime();
      ContextVerifier verifier = new ContextVerifier(skeletons);
      for (Skeleton.TemplateSkeleton t : skeletons.values()) {
        assertTrue(verifier.check(t).isEmpty(), t.viewName());
      }
      best = Math.min(best, (System.nanoTime() - t0) / 1_000_000);
    }
    System.out.println("VerifierBudgetTest: " + skeletons.size() + " guide templates verified in " + best + " ms");
    assertTrue(best <= BOUND_MILLIS, "verifying " + skeletons.size() + " guide templates took " + best + " ms, more than "
        + BOUND_MILLIS + " ms");
  }
}
