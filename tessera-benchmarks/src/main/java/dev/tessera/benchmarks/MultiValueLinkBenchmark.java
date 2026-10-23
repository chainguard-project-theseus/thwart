package dev.tessera.benchmarks;

import dev.tessera.runtime.DefaultLinks;
import java.util.Arrays;
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

/** Multi-value links, including repeated query-name encoding; run with {@code -prof gc}. */
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

  private final DefaultLinks links = new DefaultLinks("/app");
  private final String[] names = {"selected items"};
  private Object[] values;

  @Setup
  public void setUp() {
    int[] ints = new int[size];
    String[] strings = new String[size];
    for (int i = 0; i < size; i++) {
      ints[i] = 1000 + i;
      strings[i] = "item/" + i + " &é";
    }
    values = new Object[] {type.equals("ints") ? ints
        : type.equals("objects") ? strings : Arrays.asList(strings)};
  }

  @Benchmark
  public String query() {
    return links.build("/items?sort=name#top", names, values);
  }

  @Benchmark
  public String path() {
    return links.build("/items/{selected items}/edit", names, values);
  }
}
