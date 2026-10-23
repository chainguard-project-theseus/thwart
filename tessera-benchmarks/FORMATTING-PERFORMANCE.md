# Formatter reuse (#199)

`Numbers` clones a private, never-used DecimalFormat prototype for each call. The mutable
formatter and its configuration remain private to that call. Root-locale symbols, no grouping,
half-up rounding, padding, and precision settings are unchanged.

`Temporals` retains at most 32 immutable DateTimeFormatter instances in a direct-mapped cache.
Entries include the pattern, so hash collisions replace entries without affecting output.
Patterns longer than 128 characters bypass the cache, bounding retained pattern and formatter
size as well as entry count. Cache misses and collisions still compile the supplied pattern;
invalid patterns throw as before. English text fields and `formatIso` are unchanged.

## Measurement

Baseline: upstream `2ed2af7` plus the same FormattingBenchmark source. Candidate changes only
formatter reuse. Apple M5 Pro, macOS 27.0.1, OpenJDK 21.0.13. JMH 1.37, two forks, three
one-second warmup iterations and three one-second measurement iterations, GC profiler,
256 MiB fork heaps. All Maven and JMH runs serialized with the other performance work.

Build and run:

```sh
mvn -B -ntp -pl tessera-benchmarks -am package -DskipTests
java -Xmx128m -jar tessera-benchmarks/target/benchmarks.jar FormattingBenchmark \
  -prof gc -jvmArgs '-Xms256m -Xmx256m' -rf json -rff formatting.json
java -Xmx128m -jar tessera-benchmarks/target/benchmarks.jar \
  'FormattingBenchmark.(decimal|temporalRepeated)' -t 8 \
  -prof gc -jvmArgs '-Xms256m -Xmx256m' -rf json -rff formatting-concurrent.json
```

The date scenarios use one repeated pattern, four alternating patterns, and 128 changing
patterns that exceed the cache capacity. The last is a miss/churn control rather than a
typical fixed-template workload. Number inputs are BigDecimal values. These are formatting
microbenchmarks, not whole-page rendering measurements; throughput depends on the host and JIT.

## Results

Throughput is millions of operations per second, with JMH's reported 99.9% error interval.
Allocation is bytes per operation.

| One thread | Before ops/s | After ops/s | Before B/op | After B/op |
| --- | ---: | ---: | ---: | ---: |
| Decimal | 4.90 ± 0.20 M | 7.75 ± 0.80 M | 1056 | 536 |
| Integer | 4.79 ± 0.73 M | 7.64 ± 0.48 M | 1056 | 536 |
| Repeated date pattern | 14.24 ± 0.35 M | 16.56 ± 3.06 M | 520 | 152 |
| Four alternating patterns | 9.67 ± 0.33 M | 14.98 ± 1.46 M | 572 | 354 |
| 128 changing patterns | 10.58 ± 0.15 M | 9.31 ± 0.44 M | 608 | 625 |

Number formatting improved about 58–59% with 49% less allocation. Repeated dates allocate
71% less, although the throughput intervals overlap; the point estimate alone does not
establish a speedup. Four alternating patterns improved about 55% with 38% less allocation.
The cache intentionally remains small: patterns that collide can be recompiled, including
some in the alternating-pattern workload. The 128-pattern churn control is about 12% slower
and allocates 17 additional bytes per operation because misses also publish a cache entry.
This is a tradeoff for repeated template patterns, not a universal date-formatting speedup.

| Eight threads, total throughput | Before ops/s | After ops/s | Before B/op | After B/op |
| --- | ---: | ---: | ---: | ---: |
| Decimal | 9.20 ± 0.57 M | 52.39 ± 6.30 M | 1056 | 536 |
| Repeated date pattern | 74.34 ± 4.21 M | 108.11 ± 81.69 M | 520 | 152 |

The decimal throughput gain under contention was larger than the single-thread gain.
The concurrent date timing was too variable for a speedup claim; its allocation reduction
matches the single-thread result. Neither test establishes whole-application latency gains.
