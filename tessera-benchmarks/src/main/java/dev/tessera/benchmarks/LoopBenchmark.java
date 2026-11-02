package dev.tessera.benchmarks;

import dev.tessera.benchmarks.templates.loops.CollectionTemplate;
import dev.tessera.benchmarks.templates.loops.CollectionStatusTemplate;
import dev.tessera.benchmarks.templates.loops.IterableTemplate;
import dev.tessera.benchmarks.templates.loops.IterableStatusTemplate;
import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.internal.StringOutput;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Generated loops, with and without status access. Run with {@code -prof gc}. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(2)
public class LoopBenchmark {
  @Param({"100", "1000"})
  public int size;

  private List<String> collection;
  private Iterable<String> iterable;
  private RenderContext ctx;

  @Setup
  public void setUp() {
    collection = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      collection.add("item " + i);
    }
    iterable = collection::iterator;
    ctx = RenderContext.defaults();
  }

  @Benchmark
  public String iterableUnusedStatus() {
    StringOutput out = new StringOutput(size * 40);
    IterableTemplate.render(iterable, ctx, out);
    return out.toString();
  }

  @Benchmark
  public String iterableUsedStatus() {
    StringOutput out = new StringOutput(size * 40);
    IterableStatusTemplate.render(iterable, ctx, out);
    return out.toString();
  }

  @Benchmark
  public String collectionUnusedStatus() {
    StringOutput out = new StringOutput(size * 40);
    CollectionTemplate.render(collection, ctx, out);
    return out.toString();
  }

  @Benchmark
  public String collectionUsedStatus() {
    StringOutput out = new StringOutput(size * 40);
    CollectionStatusTemplate.render(collection, ctx, out);
    return out.toString();
  }
}
