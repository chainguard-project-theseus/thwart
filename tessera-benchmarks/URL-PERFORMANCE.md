# Runtime URL rendering (#191)

The runtime builds links and checks URL schemes on every render. Literal links now skip
placeholder/query builders, and scheme checks compare against the allow-list without
allocating normalized strings. Parameterized links retain their existing construction
path in a separate method. Escaping and URL
acceptance rules are unchanged. No runtime API or dependency changes are needed.

## Measurement

Measured on October 22, 2026, on an Apple M5 Pro (18 cores, 48 GiB RAM), macOS 27.0.1,
with Homebrew OpenJDK 21.0.13 and JMH 1.37. One benchmark thread, two JVM forks, three
one-second warmup iterations and three one-second measurement iterations per fork;
`-Xms256m -Xmx256m` for benchmark JVMs. Builds and tests were stopped during measurements.
The fixed heap limits memory pressure on the shared development machine.

The baseline is `ace871d` with the same `UrlBenchmark` harness added. The measured candidate
is based on `999e1ec`; the intervening commit changes Spring resource-filter integration,
not the runtime, compiler, or benchmarks. Both measurements render the existing full-page
fixtures unchanged. The focused cases include plain IDs, escaped delimiters, Unicode,
relative URLs, mixed-case schemes containing a tab, and rejected schemes.

```sh
mvn -B -ntp -pl tessera-benchmarks -am package -DskipTests
java -Xmx128m -jar tessera-benchmarks/target/benchmarks.jar \
  'UrlBenchmark|RenderBenchmark.*Tessera' \
  -wi 3 -i 3 -w 1s -r 1s -f 2 -jvmArgs '-Xms256m -Xmx256m' \
  -prof gc -rf json -rff results.json
```

Run the same command and harness against the baseline and changed runtime. Throughput
is machine- and workload-dependent; these are local measurements, not production latency
guarantees. Allocation is JMH's `gc.alloc.rate.norm`, including each benchmark's output.

## Results

Throughput is ops/s; ± is JMH’s 99.9% confidence interval. Allocation is bytes/op, rounded.
The final query-parameter cases were measured in a separate invocation with the same settings.

| Case | Before ops/s | After ops/s | Before B/op | After B/op |
|---|---:|---:|---:|---:|
| `layoutPageTessera` | 161,518 ± 42,112 | 186,101 ± 10,061 | 26,904 | 27,384 |
| `ownersTableTessera` | 10,893 ± 2,759 | 11,882 ± 1,408 | 248,657 | 241,833 |
| `literalLink` | 20,145,801 ± 2,769,479 | 119,944,212 ± 7,818,020 | 392 | 72 |
| `pathVariable` (`42`) | 11,320,749 ± 22,806,505 | 20,747,717 ± 4,056,349 | 284 | 296 |
| `pathVariable` (`a/b &c`) | 6,489,960 ± 1,554,364 | 11,129,621 ± 1,861,652 | 432 | 456 |
| `pathVariable` (`café😀`) | 11,317,429 ± 3,149,633 | 10,637,033 ± 3,939,592 | 616 | 616 |
| `queryParameter` (`42`) | 19,175,777 ± 2,322,212 | 17,860,417 ± 4,048,272 | 448 | 448 |
| `queryParameter` (`a/b &c`) | 8,710,817 ± 1,108,514 | 9,115,719 ± 2,165,706 | 544 | 520 |
| `queryParameter` (`café😀`) | 9,909,327 ± 782,241 | 8,448,136 ± 3,040,049 | 696 | 720 |
| `urlAttribute` (`/owners/42`) | 109,460,402 ± 3,555,808 | 105,039,968 ± 15,812,760 | 136 | 136 |
| `urlAttribute` (`https://example.com/owners/42`) | 20,937,586 ± 1,990,230 | 34,019,532 ± 2,634,448 | 232 | 152 |
| `urlAttribute` (`Ht<TAB>TpS://example.com/`) | 16,018,069 ± 2,133,476 | 36,360,945 ± 1,705,776 | 224 | 144 |
| `urlAttribute` (`javascript:alert(1)`) | 28,198,048 ± 473,733 | 54,266,570 ± 5,189,464 | 240 | 144 |

The literal-link case improves about 6× and drops from 392 to 72 B/op (82%). Scheme-bearing
URL attributes also improve and allocate less; relative URL attributes do not require scheme
normalization and retain the same allocation. Whole-page and parameterized-link throughput
has substantial run-to-run uncertainty on this shared machine, so these measurements do not
establish a general page-rendering speedup. Some parameterized cases allocate slightly more
in the measured JVM forks; the optimization targets literal links and scheme validation.

## Validation

`mvn -B -ntp verify` passes on JDK 21, including the release-8 compiler pass, security
tests, Spring compatibility matrix, PetClinic integration tests, and template equivalence
checks. The real-JDK-8 processor test is skipped locally because `JDK8_HOME` is unset;
the repository's Chainguard CI supplies JDK 8. Added URL regressions cover browser-ignored
controls, scheme lookalikes, long relative names, and preservation of literal links.
