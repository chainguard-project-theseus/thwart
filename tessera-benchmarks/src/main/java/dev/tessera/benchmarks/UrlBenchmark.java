package dev.tessera.benchmarks;

import dev.tessera.benchmarks.templates.links.IntPathVariableTemplate;
import dev.tessera.benchmarks.templates.links.LiteralTemplate;
import dev.tessera.benchmarks.templates.links.PathVariableTemplate;
import dev.tessera.benchmarks.templates.links.QueryParameterTemplate;
import dev.tessera.runtime.DefaultLinks;
import dev.tessera.runtime.internal.Escapers;
import dev.tessera.runtime.Forms;
import dev.tessera.runtime.Messages;
import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.internal.StringOutput;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * URL hot paths: each link benchmark renders a one-link template ({@code src/main/tessera/links})
 * under context path {@code /app}; use {@code -prof gc} to measure allocation as well as throughput.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class UrlBenchmark {
  private static final LiteralTemplate LITERAL = LiteralTemplate.of();

  static final RenderContext CTX =
      new RenderContext(Locale.ROOT, Messages.NONE, new DefaultLinks("/app"), Forms.NONE);

  @State(Scope.Thread)
  public static class LinkData {
    @Param({"42", "a/b &c", "café😀"})
    public String value;
  }

  @State(Scope.Thread)
  public static class UrlData {
    @Param({"/owners/42", "https://example.com/owners/42", "Ht\tTpS://example.com/", "javascript:alert(1)"})
    public String value;
  }

  @Benchmark
  public String pathVariable(LinkData data) {
    StringOutput out = new StringOutput(64);
    PathVariableTemplate.render(data.value, CTX, out);
    return out.toString();
  }

  @Benchmark
  public String intPathVariable() {
    StringOutput out = new StringOutput(64);
    IntPathVariableTemplate.render(42, CTX, out);
    return out.toString();
  }

  @Benchmark
  public String queryParameter(LinkData data) {
    StringOutput out = new StringOutput(64);
    QueryParameterTemplate.render(data.value, CTX, out);
    return out.toString();
  }

  @Benchmark
  public String literalLink() {
    StringOutput out = new StringOutput(64);
    LITERAL.render(CTX, out);
    return out.toString();
  }

  @Benchmark
  public String urlAttribute(UrlData data) {
    StringOutput out = new StringOutput(64);
    Escapers.url(out, data.value);
    return out.toString();
  }
}
