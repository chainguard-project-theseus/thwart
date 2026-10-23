# Embedded bean JSON (#197)

Generated serializers append JSON into a private buffer instead of allocating a map for
each bean and copying bean-containing collections into lists. Property names are escaped at
compile time. The completed value is written once; cycles, excessive nesting, invalid map
keys, and getter failures do not expose partial JSON. Scalar-only script expressions retain
the existing runtime serialization path.

The additive runtime method `Escapers.appendJson(StringBuilder, Object, int)` lets generated
helpers reuse contextual JSON escaping and the existing nesting limit. It accepts only
nonnegative depth and may leave an incomplete private buffer on failure; generated code
never publishes that buffer. Generated helpers preserve both historical depth counters,
because conversion removes bean-containing Optionals before the JSON depth traversal.

Property getter traversal order is preserved, but scalar serialization now happens before
the next getter runs. A getter that mutates an earlier returned collection or CharSequence
can therefore change snapshot timing relative to the old intermediate-graph conversion.
View getters should not mutate previously returned values. This is covered by a regression
and documented in the porting guide.

## Measurement

Measured October 22, 2026, on an Apple M5 Pro with Homebrew OpenJDK 21.0.13 and JMH 1.37.
The baseline is `2ed2af7` plus the same `JsonBenchmark` class/template. The candidate changes
only the bean serializer on that baseline. One thread, two forks, three one-second warmups,
four one-second measurements, and `-Xms256m -Xmx256m` in each benchmark JVM. All builds,
tests, and JMH runs were serialized with a shared process lock.

The fixture embeds a page bean containing a list of one or 100 rows, an Optional row,
and a String-keyed map containing a row. Each row has numbers, a scalar list, and a label
with script-breaking characters. A benchmark test compares the complete rendered JSON
against the equivalent map/list graph serialized by the runtime.

```sh
mvn -B -ntp -pl tessera-benchmarks -am package -DskipTests
java -Xmx128m -jar tessera-benchmarks/target/benchmarks.jar JsonBenchmark \
  -wi 3 -i 4 -w 1s -r 1s -f 2 -jvmArgs '-Xms256m -Xmx256m' \
  -prof gc -rf json -rff results.json
```

| Rows | Before ops/s | After ops/s | Before B/op | After B/op |
|---|---:|---:|---:|---:|
| 1 | 1,754,436 ± 56,677 | 3,447,404 ± 109,610 | 3,728 | 2,160 |
| 100 | 62,954 ± 4,542 | 111,930 ± 2,898 | 95,944 | 61,376 |

Throughput is about 1.96× and 1.78× higher; allocated bytes fall 42% and 36%, respectively.
Errors are JMH's 99.9% confidence intervals. These are local focused measurements, not
production latency or whole-page performance guarantees. Allocation includes output buffers
and the returned String, which remain necessary.

## Validation

`mvn -B -ntp clean verify` passes after rebasing onto the HTML and loop optimizations,
including compiler tests at the Java 8 floor, generated-code architecture checks, security
tests, benchmark-output validation, Spring compatibility tests, and PetClinic integration.
The real-JDK-8 processor test is skipped locally because `JDK8_HOME` is unavailable;
Chainguard CI supplies JDK 8. Its fixture now includes full bean/list JSON serialization.
