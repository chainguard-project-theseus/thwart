# Tessera vs Thymeleaf benchmarks

The [runtime URL performance comparison](URL-PERFORMANCE.md) records before/after
throughput and allocation for the URL rendering improvements in #191.

JMH benchmarks that render the same pages with Tessera and Thymeleaf. `EquivalenceTest` checks
that both engines produce identical HTML for the benchmark data.

| Page | Templates | What it exercises |
|---|---|---|
| Owner table | `owners.html` | 100 rows, `each` with a status variable, path-variable and query links, `if`/`unless`, ternaries, string concatenation, escaping (some names contain `'`, `&`, `<`) |
| Layout page | `home.html`, `fragments/{layout,menu,cards}.html` | petclinic-style `~{::body}` layout, 4 parameterized menu items with `classappend`, 20 cards, each making nested fragment calls for pet labels and a status badge |

Tessera templates live in `src/main/tessera` (compiled by the annotation processor), Thymeleaf
templates in `src/main/resources/thymeleaf`. Both use a root context path. Tessera renders through
the generated typed `render(...)` method; Thymeleaf uses a `TemplateEngine` with template caching
on, so the steady state measures rendering, not parsing. Both render to a `String`.

`mvn verify` only builds the benchmarks. To run them:

```sh
mvn -B -ntp -pl tessera-benchmarks -am package -DskipTests
java -jar tessera-benchmarks/target/benchmarks.jar
```

## Results

### Continuous benchmarks

[Chainguard Checks](../.chainguard/ci/benchmarks.yaml) runs all benchmark methods and their
parameter combinations automatically:

- Pull requests: `benchmarks/smoke`, one fork with one 200 ms warmup and one 200 ms measurement.
  This checks that benchmarks execute; these short timings are not performance evidence.
- Each merge to the default branch: `benchmarks/measure`, two forks with three 1-second
  warmups and three 1-second measurements per fork, for throughput and allocation tracking.

Both checks use JDK 21, one benchmark thread, 256 MiB fork heaps, the GC profiler, and a pinned
`medium-amd64` resource class. They clean-build the benchmark reactor and run its tests,
including template equivalence checks, before measuring. Build/test failures and benchmark
exceptions fail the check. Timing changes do not: shared VM CPUs, JIT decisions and short
measurement windows can produce noise. Compare like-for-like measurement runs, examine error
intervals, and confirm regressions on a quiet machine. These checks do not automatically compare
against a baseline or enforce performance budgets.

Open the benchmark check's GitHub summary for the JMH result table (including `ops/s` and
`gc.alloc.rate.norm` in B/op); its Details link opens the full run log. Raw JMH JSON is printed
between `::benchmark-json-start::` and `::benchmark-json-end::` in that log. Locally, JSON and
the JMH log are in `target/ci-results/`. The CI VM's files are ephemeral; no separate downloadable
artifact or external storage is configured. The JSON also records the JDK and JMH settings.

Reproduce either check from a checkout with JDK 21+ and Maven:

```sh
bash tessera-benchmarks/ci.sh smoke
bash tessera-benchmarks/ci.sh measure
```

Set `JAVA_HOME` to the JDK and, when available, `JDK8_HOME` for the real-JDK-8 processor test.
CI supplies both. The script prints the revision and OS/architecture alongside the results.

### Original rendering comparison

Oct 22, 2026, default settings (2 forks, 5 x 1 s warmup, 5 x 1 s measurement), throughput in
operations per second (higher is better).

- Machine: Apple M4 (10 cores), 24 GB RAM, macOS 26.7.1, on AC power
- JVM: OpenJDK 26.0.2 (Homebrew), default flags; JMH 1.37; Thymeleaf 3.1.5.RELEASE

| Benchmark | Score (ops/s) | Error (99.9%) | Tessera speedup |
|---|---:|---:|---:|
| `ownersTableTessera` | 12,740 | ± 804 | **6.9x** |
| `ownersTableThymeleaf` | 1,844 | ± 106 | |
| `layoutPageTessera` | 147,378 | ± 4,800 | **21.3x** |
| `layoutPageThymeleaf` | 6,924 | ± 315 | |

The fragment-heavy page shows the larger gap: a Tessera fragment call is a static method call,
while Thymeleaf resolves the fragment template, evaluates the selector and binds parameters as
variables on every insertion.
