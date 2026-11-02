package dev.tessera.benchmarks;

import dev.tessera.benchmarks.templates.JsonTemplate;
import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.internal.StringOutput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

/** Embedded bean JSON, including nested containers and characters requiring script escaping. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(2)
public class JsonBenchmark {
  public record Row(int id, String label, List<Integer> values) {}
  public record Page(List<Row> rows, Optional<Row> selected, Map<String, Row> byName) {}

  @Param({"1", "100"})
  public int size;

  private Page page;
  private RenderContext ctx;

  @Setup
  public void setUp() {
    List<Row> rows = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      rows.add(new Row(i, "Row </script> & ${" + i + "}", List.of(i, i + 1, i + 2)));
    }
    page = new Page(rows, Optional.of(rows.get(0)), Map.of("first", rows.get(0)));
    ctx = RenderContext.defaults();
  }

  @Benchmark
  public String embeddedBeans() {
    StringOutput out = new StringOutput();
    JsonTemplate.render(page, ctx, out);
    return out.toString();
  }
}
