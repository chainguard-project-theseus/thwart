package dev.tessera.compiler.testing;

import dev.tessera.compiler.verify.Skeleton;
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
      n.accept(new Skeleton.Visitor<Void>() {
        @Override
        public Void visitStatic(Skeleton.Static s) {
          add(s.pos() == null ? "Static (generated markup)" : "Static");
          return null;
        }

        @Override
        public Void visitHole(Skeleton.Hole h) {
          add("Hole " + h.escaper());
          return null;
        }

        @Override
        public Void visitRawInsert(Skeleton.RawInsert r) {
          add("RawInsert " + r.source());
          return null;
        }

        @Override
        public Void visitCall(Skeleton.Call call) {
          add(call.fragmentArgs().isEmpty() ? "Call" : "Call with fragment arguments");
          for (Skeleton.Seq a : call.fragmentArgs().values()) {
            count(c, a, seen);
          }
          Skeleton.Seq target = c.fragmentSkeleton(call.target());
          if (target != null) {
            count(c, target, seen);
          }
          return null;
        }

        @Override
        public Void visitParamInsert(Skeleton.ParamInsert p) {
          add("ParamInsert");
          return null;
        }

        @Override
        public Void visitSeq(Skeleton.Seq q) {
          count(c, q, seen);
          return null;
        }

        @Override
        public Void visitOpt(Skeleton.Opt o) {
          add("Opt " + o.kind());
          count(c, o.body(), seen);
          return null;
        }

        @Override
        public Void visitAlt(Skeleton.Alt a) {
          add("Alt");
          for (Skeleton.Seq b : a.branches()) {
            count(c, b, seen);
          }
          return null;
        }

        @Override
        public Void visitRep(Skeleton.Rep r) {
          add("Rep " + r.kind() + (r.separator().nodes().isEmpty() ? "" : " with separator"));
          count(c, r.body(), seen);
          count(c, r.separator(), seen);
          return null;
        }
      });
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
    String release = System.getProperty("tessera.test.release", "default");
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
