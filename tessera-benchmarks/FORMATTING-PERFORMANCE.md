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
