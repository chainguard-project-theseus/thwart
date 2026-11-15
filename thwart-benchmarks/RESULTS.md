# Thwart vs Thymeleaf benchmarks

JMH benchmarks that render the same pages with Thwart and Thymeleaf. `EquivalenceTest` checks
that both engines produce identical HTML for the benchmark data.

| Page | Templates | What it exercises |
|---|---|---|
| Owner table | `owners.html` | 100 rows, `each` with a status variable, path-variable and query links, `if`/`unless`, ternaries, string concatenation, escaping (some names contain `'`, `&`, `<`) |
| Layout page | `home.html`, `fragments/{layout,menu,cards}.html` | petclinic-style `~{::body}` layout, 4 parameterized menu items with `classappend`, 20 cards, each making nested fragment calls for pet labels and a status badge |

The two pages are `RenderBenchmark`, the only class that runs Thymeleaf. The other classes are
microbenchmarks of one Thwart feature each (links, loops, escaping, JSON, formatting) and measure
Thwart alone.

Thwart templates live in `src/main/thwart` (compiled by the annotation processor), Thymeleaf
templates in `src/main/resources/thymeleaf`. Both use a root context path. Thwart renders through
the generated typed `render(...)` method; Thymeleaf uses a `TemplateEngine` with template caching
on, so the steady state measures rendering, not parsing. Both render to a `String`.

## Running

`mvn verify` only builds the benchmarks. To run them:

```sh
mvn -B -ntp -pl thwart-benchmarks -am package -DskipTests
java -jar thwart-benchmarks/target/benchmarks.jar
```

[Chainguard Checks](../.chainguard/ci/benchmarks.yaml) runs every benchmark and parameter
combination through `ci.sh`, which clean-builds the benchmark reactor and runs the benchmark
module's tests, including the equivalence checks, before measuring:

- Pull requests: `benchmarks/smoke`, one fork with one 200 ms warmup and one 200 ms measurement.
  This checks that benchmarks execute; these timings are not performance evidence.
- Each merge to main: `benchmarks/measure`, two forks with three 1-second warmups and three
  1-second measurements per fork, JDK 21, one thread, 256 MiB fork heaps, the GC profiler.

Reproduce either locally with `bash thwart-benchmarks/ci.sh smoke|measure`; results land in
`target/ci-results/`. Timings on shared CI VMs are informational: no baseline comparison or
performance budget is enforced.

## Results

Current numbers are in the `benchmarks/measure` check summary on main: the JMH table with
`ops/s` and `gc.alloc.rate.norm` (B/op), and raw JSON between `::benchmark-json-start::` and
`::benchmark-json-end::` in its log.

For reference, the measure run of Nov 2, 2026 on commit `c0f02b3f` (JDK 21.0.13, JMH 1.37,
`medium-amd64`):

| Benchmark | ops/s | B/op | Thwart speedup |
|---|---:|---:|---:|
| `ownersTableThwart` | 21,590 ± 222 | 88,736 | **19x** |
| `ownersTableThymeleaf` | 1,129 ± 193 | 1,589,734 | |
| `layoutPageThwart` | 179,437 ± 12,021 | 18,456 | **42x** |
| `layoutPageThymeleaf` | 4,245 ± 242 | 391,554 | |

The fragment-heavy page shows the larger gap: a Thwart fragment call is a static method call,
while Thymeleaf resolves the fragment template, evaluates the selector and binds parameters as
variables on every insertion.

What these numbers do not show: they are render-only, to a `String`, against a plain Thymeleaf
`TemplateEngine` with OGNL, not the `SpringTemplateEngine` with SpEL that Spring apps use, and
they leave out the request path (dispatch, binding, the view resolver, writing the response).
Much of the gap comes from not interpreting templates at runtime, which other compiled engines
share: JTE, for example, renders in the same range (an audit run measured it about 1.2x faster
than Thwart on the owner table). Thwart's aim is safety at compiled-engine speed, not being the
fastest engine.
