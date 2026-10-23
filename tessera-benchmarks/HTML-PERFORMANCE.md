# HTML escaping into StringOutput (#195)

HTML text and attributes now escape directly into StringOutput's existing buffer. This removes
an intermediate builder and escaped string when a value needs escaping. WriterOutput and custom
Output implementations still receive one write per escaped value. Escaping rules and public APIs
are unchanged; the new StringOutput helper is package-private.

## Measurement

Measured October 22, 2026, on the same Apple M5 Pro host described in
[URL-PERFORMANCE.md](URL-PERFORMANCE.md), with OpenJDK 21.0.13 and JMH 1.37. Baseline
`2ed2af7` plus the same benchmark harness versus this change. Both runs use one thread,
two JVM forks, three one-second warmup iterations and three one-second measurement iterations,
and `-Xms256m -Xmx256m` per fork. Builds, tests, and benchmarks across the concurrent worktrees
were serialized with a shared file lock.

```sh
mvn -B -ntp -pl tessera-benchmarks -am package -DskipTests
java -Xmx128m -jar tessera-benchmarks/target/benchmarks.jar HtmlEscapingBenchmark \
  -jvmArgs '-Xms256m -Xmx256m' -prof gc -rf json -rff results.json
```

The harness covers ordinary ASCII, unescaped Unicode including a surrogate pair, prose containing
HTML special characters and Unicode, and a dense run of HTML special characters. The writer case
wraps StringWriter; it does not model servlet/network IO. Results include constructing the sink and
returning the rendered string. Allocation is JMH's `gc.alloc.rate.norm`, rounded to bytes/op.
Throughput is ops/s; ± is JMH's 99.9% confidence interval.

| Case | Before ops/s | After ops/s | Before B/op | After B/op |
|---|---:|---:|---:|---:|
| `stringOutput` / plain | 40,551,018 ± 1,389,660 | 40,947,929 ± 2,786,568 | 384 | 384 |
| `stringOutput` / unicode | 13,804,031 ± 1,382,502 | 16,589,896 ± 460,470 | 1,520 | 1,024 |
| `stringOutput` / escaped | 4,943,810 ± 179,471 | 5,668,069 ± 92,456 | 2,096 | 1,088 |
| `stringOutput` / dense | 7,624,454 ± 451,735 | 10,337,178 ± 3,356,785 | 1,192 | 528 |
| `writerOutput` / plain | 28,885,224 ± 864,623 | 26,831,133 ± 1,400,151 | 440 | 456 |
| `writerOutput` / unicode | 11,473,830 ± 196,286 | 11,799,712 ± 293,578 | 1,576 | 1,576 |
| `writerOutput` / escaped | 4,483,837 ± 201,403 | 4,393,060 ± 169,548 | 2,152 | 2,152 |
| `writerOutput` / dense | 6,790,553 ± 252,655 | 7,577,048 ± 510,714 | 1,248 | 1,264 |

In these fixtures, StringOutput with Unicode or escaping allocates 33–56% fewer bytes and has
15–36% higher mean throughput. Plain ASCII StringOutput allocation is unchanged and its throughput
intervals overlap. These are focused local measurements, not a whole-page performance claim.

WriterOutput retains buffered emission but its measured JVM allocation and throughput are not
identical: the plain and dense cases allocate 16 additional bytes/op, and plain throughput is
about 7% lower (confidence intervals narrowly overlap). Other writer timings vary by workload;
this change is aimed at StringOutput and does not promise a WriterOutput speedup.

## Validation

Regression tests cover every BMP character, required HTML entities, NUL and unpaired-surrogate
replacement, valid surrogate pairs next to escapes, injection payloads, appending into an existing
buffer, SafeHtml.escape, and parity across StringOutput, WriterOutput, and a custom Output.
The custom sink test also requires one write per value. Full Maven verification includes the
release-8 compiler pass and the security and Spring compatibility suites. The real JDK 8 processor
test is skipped locally because JDK8_HOME is unset; Chainguard CI supplies JDK 8.
