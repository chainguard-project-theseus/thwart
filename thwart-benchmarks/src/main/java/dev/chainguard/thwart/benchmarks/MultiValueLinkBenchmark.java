package dev.chainguard.thwart.benchmarks;

import dev.chainguard.thwart.benchmarks.templates.links.MultiPathTemplate;
import dev.chainguard.thwart.benchmarks.templates.links.MultiQueryTemplate;
import dev.chainguard.thwart.runtime.internal.StringOutput;
import java.util.Arrays;
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

/** Multi-value links rendered through a one-link template; run with {@code -prof gc}. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(2)
@State(Scope.Thread)
public class MultiValueLinkBenchmark {
  @Param({"list", "objects", "ints"})
  public String type;

  @Param({"3", "100"})
  public int size;

  private List<String> list;
  private String[] objects;
  private int[] ints;

  @Setup
  public void setUp() {
    int[] is = new int[size];
    String[] strings = new String[size];
    for (int i = 0; i < size; i++) {
      is[i] = 1000 + i;
      strings[i] = "item/" + i + " &é";
    }
    list = type.equals("list") ? Arrays.asList(strings) : null;
    objects = type.equals("objects") ? strings : null;
    ints = type.equals("ints") ? is : null;
  }

  @Benchmark
  public String query() {
    StringOutput out = new StringOutput(256);
    MultiQueryTemplate.render(list, objects, ints, UrlBenchmark.CTX, out);
    return out.toString();
  }

  @Benchmark
  public String path() {
    StringOutput out = new StringOutput(256);
    MultiPathTemplate.render(list, objects, ints, UrlBenchmark.CTX, out);
    return out.toString();
  }
}
