package dev.tessera.benchmarks;

import dev.tessera.runtime.DefaultLinks;
import dev.tessera.runtime.Escapers;
import dev.tessera.runtime.StringOutput;
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

/** URL hot paths; use {@code -prof gc} to measure allocation as well as throughput. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class UrlBenchmark {
  @State(Scope.Thread)
  public static class LinkData {
    @Param({"42", "a/b &c", "café😀"})
    public String value;

    final DefaultLinks links = new DefaultLinks("/app");
    final String[] names = {"id"};
    Object[] values;

    @Setup
    public void setUp() {
      values = new Object[] {value};
    }
  }

  @State(Scope.Thread)
  public static class UrlData {
    @Param({"/owners/42", "https://example.com/owners/42", "Ht\tTpS://example.com/", "javascript:alert(1)"})
    public String value;
  }

  @State(Scope.Thread)
  public static class LiteralData {
    final DefaultLinks links = new DefaultLinks("/app");
    final String[] names = {};
    final Object[] values = {};
  }

  @Benchmark
  public String pathVariable(LinkData data) {
    return data.links.build("/owners/{id}", data.names, data.values);
  }

  @Benchmark
  public String queryParameter(LinkData data) {
    return data.links.build("/owners", data.names, data.values);
  }

  @Benchmark
  public String literalLink(LiteralData data) {
    return data.links.build("/owners?sort=name#top", data.names, data.values);
  }

  @Benchmark
  public String urlAttribute(UrlData data) {
    StringOutput out = new StringOutput(64);
    Escapers.url(out, data.value);
    return out.toString();
  }
}
