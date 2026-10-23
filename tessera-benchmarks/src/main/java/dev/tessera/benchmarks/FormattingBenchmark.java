package dev.tessera.benchmarks;

import dev.tessera.runtime.fn.Numbers;
import dev.tessera.runtime.fn.Temporals;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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

/** Repeated formatting, alternating patterns and a cache-miss control. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(2)
@State(Scope.Thread)
public class FormattingBenchmark {
  private final BigDecimal number = new BigDecimal("12345.675");
  private final LocalDateTime date = LocalDateTime.of(2024, 1, 5, 10, 15, 30);
  private final String[] patterns = {"yyyy-MM-dd", "d MMM yyyy EEE", "HH:mm:ss", "yyyy-MM-dd HH:mm"};
  private final String[] changingPatterns = new String[128];
  private int index;

  @Setup
  public void setup() {
    for (int i = 0; i < changingPatterns.length; i++) {
      changingPatterns[i] = "yyyy-MM-dd '" + i + "'";
    }
  }

  @Benchmark
  public String integer() {
    return Numbers.formatInteger(number, 8);
  }

  @Benchmark
  public String decimal() {
    return Numbers.formatDecimal(number, 1, 2);
  }

  @Benchmark
  public String temporalRepeated() {
    return Temporals.format(date, patterns[0]);
  }

  @Benchmark
  public String temporalAlternating() {
    return Temporals.format(date, patterns[index++ & 3]);
  }

  @Benchmark
  public String temporalChurn() {
    return Temporals.format(date, changingPatterns[index++ & 127]);
  }
}
