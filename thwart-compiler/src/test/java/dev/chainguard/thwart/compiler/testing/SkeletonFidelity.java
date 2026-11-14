package dev.chainguard.thwart.compiler.testing;

import dev.chainguard.thwart.compiler.verify.Skeleton;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Counts what the render-membership check of {@link Compilation#render} covered (#366): renders,
 * distinct templates, and the emission paths (skeleton node kinds) in the templates rendered. Written
 * to {@code target/skeleton-fidelity-<release>.txt} when the test JVM exits.
 */
final class SkeletonFidelity {
  private SkeletonFidelity() {}

  private static int renders;
  private static int failures;
  private static final Set<Skeleton.TemplateSkeleton> templates = Collections.newSetFromMap(new IdentityHashMap<>());
  private static final Map<String, Integer> kinds = new TreeMap<>();

  static {
    Runtime.getRuntime().addShutdownHook(new Thread(SkeletonFidelity::write));
  }

  static synchronized void record(Compilation c, String view, Skeleton.TemplateSkeleton t, boolean matched) {
    renders++;
    if (!matched) {
      failures++;
    }
    if (templates.add(t)) {
      Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
      count(c, t.body(), seen);
    }
  }

  /** Adds the node kinds reachable from {@code seq} (through calls) once per rendered template. */
  private static void count(Compilation c, Skeleton.Seq seq, Set<Object> seen) {
    if (!seen.add(seq)) {
      return;
    }
    for (Skeleton.Node n : seq.nodes()) {
      if (n instanceof Skeleton.Static s) {
        add(s.pos() == null ? "Static (generated markup)" : "Static");
      } else if (n instanceof Skeleton.Hole h) {
        add("Hole " + h.escaper());
      } else if (n instanceof Skeleton.RawInsert r) {
        add("RawInsert " + r.source());
      } else if (n instanceof Skeleton.Call call) {
        add(call.fragmentArgs().isEmpty() ? "Call" : "Call with fragment arguments");
        for (Skeleton.Seq a : call.fragmentArgs().values()) {
          count(c, a, seen);
        }
        Skeleton.Seq target = c.fragmentSkeleton(call.target());
        if (target != null) {
          count(c, target, seen);
        }
      } else if (n instanceof Skeleton.ParamInsert) {
        add("ParamInsert");
      } else if (n instanceof Skeleton.Seq q) {
        count(c, q, seen);
      } else if (n instanceof Skeleton.Opt o) {
        add("Opt " + o.kind());
        count(c, o.body(), seen);
      } else if (n instanceof Skeleton.Alt a) {
        add("Alt");
        for (Skeleton.Seq b : a.branches()) {
          count(c, b, seen);
        }
      } else if (n instanceof Skeleton.Rep r) {
        add("Rep " + r.kind() + (r.separator().nodes().isEmpty() ? "" : " with separator"));
        count(c, r.body(), seen);
        count(c, r.separator(), seen);
      } else {
        throw new IllegalArgumentException("unknown node " + n.getClass().getSimpleName());
      }
    }
  }

  private static void add(String kind) {
    kinds.merge(kind, 1, Integer::sum);
  }

  private static synchronized void write() {
    if (renders == 0) {
      return;
    }
    List<String> lines = new ArrayList<>();
    lines.add("renders checked against their skeleton: " + renders + " (" + failures + " outside it)");
    lines.add("distinct templates rendered: " + templates.size());
    lines.add("emission paths in the rendered templates (skeleton nodes of each kind, fragments called included):");
    kinds.forEach((k, v) -> lines.add("  " + k + ": " + v));
    String release = System.getProperty("thwart.test.release", "default");
    try {
      Path target = Path.of("target");
      if (Files.isDirectory(target)) {
        Files.write(target.resolve("skeleton-fidelity-" + release + ".txt"), lines, StandardCharsets.UTF_8);
      }
    } catch (IOException e) {
      // best effort: a report, not a check
    }
  }
}
