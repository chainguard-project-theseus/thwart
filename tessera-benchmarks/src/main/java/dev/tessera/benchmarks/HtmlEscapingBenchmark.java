package dev.tessera.benchmarks;

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

/** HTML escaping into the built-in sinks; use {@code -prof gc} for allocation. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(2)
@State(Scope.Thread)
public class HtmlEscapingBenchmark {
  @Param({"plain", "unicode", "escaped", "dense"})
  public String content;

  private String value;

  @Setup
  public void setUp() {
    switch (content) {
      case "plain":
        value = "Welcome to the pet clinic. Find owners, pets, and appointments here.";
        break;
      case "unicode":
        value = "Welcome to the pet clinic. Find owners, pets, and appointments here. café 中文 😀";
        break;
      case "escaped":
        value = "Welcome to <Pet Clinic> — owners & pets: \"café\", '中文' 😀. Find appointments here.";
        break;
      case "dense":
        value = "<>&\"'<>&\"'<>&\"'<>&\"'<>&\"'<>&\"'<>&\"'<>&\"'";
        break;
      default:
        throw new IllegalArgumentException(content);
    }
  }

  @Benchmark
  public String stringOutput() {
    StringOutput out = new StringOutput();
    Escapers.text(out, value);
    return out.toString();
  }
}
