# Multi-value link performance (#198)

Measured on Apple M5 Pro (18 cores, 48 GiB), macOS 27.0.1, OpenJDK 21.0.13. JMH 1.37; two forks, three 1-second warmups and three 1-second measurements per fork; fork heaps `-Xms256m -Xmx256m`, launcher `-Xmx128m`, `-prof gc`. All Maven/JMH work was serialized with the shared benchmark lock. Baseline is `2ed2af7` plus the same benchmark fixture; the final candidate is `4b3c894` (rebased onto subsequent main without changes to these files). Other intervening performance changes do not affect these direct `DefaultLinks` calls.

The fixtures build three- and 100-element queries and path variables from a list, object array, or primitive int array. String elements include slash, spaces, ampersand and Unicode; integer elements are outside the boxing cache. Query names require percent-encoding. Scalar controls use the existing `UrlBenchmark` with `value=42`; the empty control runs the multi-value query fixture with `size=0,type=list`.

| Operation | Elements | Type | Before ops/s | After ops/s | Ratio | Before B/op | After B/op |
|---|---:|---|---:|---:|---:|---:|---:|
| path | 3 | list | 3,994,302 | 5,062,697 | 1.27x | 1792 | 1540 |
| path | 3 | objects | 4,543,090 | 5,094,023 | 1.12x | 1824 | 1552 |
| path | 3 | ints | 7,820,115 | 12,076,972 | 1.54x | 1000 | 700 |
| path | 100 | list | 177,916 | 182,494 | 1.03x | 46128 | 40040 |
| path | 100 | objects | 178,353 | 169,946 | 0.95x | 45584 | 41240 |
| path | 100 | ints | 426,153 | 580,199 | 1.36x | 24248 | 19520 |
| query | 3 | list | 2,295,072 | 3,656,164 | 1.59x | 2512 | 2036 |
| query | 3 | objects | 2,296,389 | 3,692,310 | 1.61x | 2544 | 2036 |
| query | 3 | ints | 4,471,444 | 6,564,052 | 1.47x | 1992 | 1648 |
| query | 100 | list | 97,438 | 169,264 | 1.74x | 61204 | 45584 |
| query | 100 | objects | 94,398 | 172,170 | 1.82x | 60660 | 44384 |
| query | 100 | ints | 144,063 | 425,926 | 2.96x | 43432 | 29812 |
| pathVariable | 1 | scalar | 16,910,598 | 20,548,975 | 1.22x | 284 | 296 |
| queryParameter | 1 | scalar | 11,866,898 | 20,369,096 | 1.72x | 424 | 448 |
| query | 0 | list | 22,815,038 | 23,509,709 | 1.03x | 408 | 440 |

Nonempty multi-value queries reduce allocation by 17–31% and their throughput point estimates improve 1.47–2.96x. Paths reduce allocation by 10–30%; throughput is mixed for large string inputs. The 100-element object-array path is 5% slower by point estimate, with overlapping confidence intervals (before 178,353 ± 7,699 ops/s; after 169,946 ± 26,918). The large list path and small list baseline also have wide intervals. These are local microbenchmarks, not an end-to-end page throughput claim.

Scalar controls allocate slightly more: +12 B/op for paths and +24 B/op for queries. Their throughput point estimates improve, but intervals are broad, especially the scalar-query baseline (11.9M ± 16.2M ops/s); no reliable scalar speedup is claimed. The empty-list control retains similar throughput (22.8M ± 3.8M versus 23.5M ± 0.7M ops/s), with +32 B/op. Initial candidates that eagerly allocated a path buffer or encoded an unused query name had materially larger scalar/empty regressions; the final implementation makes both lazy.

## Behavior and limits

- Iterables and arrays are traversed directly, with each element converted before advancing. This removes the old eager shallow snapshot: mutating iterators or `toString()` implementations can now change later values or cause concurrent-modification errors. Tests document single-use iteration and object-array mutation timing. Ordinary immutable inputs retain their output.
- Query names are percent-encoded once per nonempty parameter. Empty arrays/iterables still emit nothing and do not encode the unused name. Tests retain the distinction between empty values with null names (omitted) and nonempty values with null names (rejected).
- All eight primitive array types and object arrays remain supported. Primitive values are converted directly to strings without wrapper objects.
- A joined path is validated after encoding instead of keeping a second raw copy. The encoder preserves dots and encodes percent signs, commas inside values, slashes, backslashes and other structural characters, so only an empty encoded string, `.` or `..` can be an empty/dot segment. Those are still rejected. Null/empty elements and literal comma separators retain their previous meanings.
- No public API or dependency changes. Runtime remains Java 8.

## Validation and reproduction

`mvn -B -ntp clean verify` passed with JDK 21, including compiler tests repeated at `--release 8`, runtime architecture checks and Spring compatibility tests. The real-JDK-8 processor test was skipped because `JDK8_HOME` was unavailable locally; CI supplies that JDK.

```sh
JAVA_HOME=/path/to/jdk21 mvn -B -ntp -pl tessera-benchmarks -am package -DskipTests
java -Xmx128m -jar tessera-benchmarks/target/benchmarks.jar MultiValueLinkBenchmark \
  -f 2 -wi 3 -i 3 -w 1s -r 1s -jvmArgs "-Xms256m -Xmx256m" -prof gc
# Repeat with UrlBenchmark.(pathVariable|queryParameter), -p value=42 for scalar controls.
# Repeat MultiValueLinkBenchmark.query with -p size=0 -p type=list for the empty control.
```
