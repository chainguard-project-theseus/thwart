package dev.tessera.benchmarks;

import dev.tessera.runtime.RenderContext;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.thymeleaf.TemplateEngine;

/**
 * Tessera vs Thymeleaf on equivalent templates. Run manually:
 * {@code java -jar tessera-benchmarks/target/benchmarks.jar}.
 *
 * <p>Both engines render to a String. Thymeleaf caches parsed templates, so the steady state
 * measures rendering, not parsing.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class RenderBenchmark {
  private List<Owner> owners;
  private List<Owner> featured;
  private TemplateEngine thymeleaf;
  private RenderContext ctx;

  @Setup
  public void setUp() {
    owners = BenchmarkData.owners();
    featured = BenchmarkData.featured();
    thymeleaf = Engines.thymeleaf();
    ctx = RenderContext.defaults();
  }

  /** 100-row owner table with links and conditionals. */
  @Benchmark
  public String ownersTableTessera() {
    return Engines.tesseraOwners(ctx, owners, 1);
  }

  @Benchmark
  public String ownersTableThymeleaf() {
    return Engines.thymeleafOwners(thymeleaf, owners, 1);
  }

  /** Layout page built from many fragment calls (menu items, cards, pet labels, badges). */
  @Benchmark
  public String layoutPageTessera() {
    return Engines.tesseraHome(ctx, featured);
  }

  @Benchmark
  public String layoutPageThymeleaf() {
    return Engines.thymeleafHome(thymeleaf, featured);
  }
}
