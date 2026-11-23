# The consumer benchmark

How fast are real applications on Thwart, compared with the template engine they started on? The
JMH benchmarks in [thwart-benchmarks](../../thwart-benchmarks/RESULTS.md) time rendering alone,
to a `String`. This benchmark times whole HTTP requests against the bench apps of
[bench/migration](../migration/README.md), each in two versions:

- the **original** app at the commit pinned in its `app.json`, and
- its **Thwart** version: the latest archived migration run that passed (strict `pass`, else
  `pass_forced`), rebuilt from that run's `diff.patch` with the Tessera to Thwart renames, as
  `hack/corpus.sh` does.

It reports numbers only: no pass or fail, no release bar. An app with no passing run, or whose
versions don't build or start, is listed as not benchmarked, with the reason.

### Ports that don't pass parity

Some Thwart versions build and answer every page with the recorded status and final URL, yet
compare finds pages that differ, for example because its `forced` rule doesn't recognise a
rewrite Thwart requires. `ALLOW_UNPASSED=<app>[,<app>]` opts such apps in: when an app has no
passing run, its latest run is benchmarked if its `score.json` says the build passed and lists no
status or final-URL mismatch (`status_mismatch` is empty). The report marks it as "port does not
pass parity: N differs, reasons in its notes" (N counted from the run's `compare.txt`) in the
Apps table, in a Summary table of its own, and at the top of its section, which comes after the
passing apps'. Without the opt-in, nothing changes.

## Run it

Needs Docker (8 GB of memory per app), git and Python 3, and a clone of the migration results
archive (see `hack/corpus.sh`).

```sh
RESULTS=<archive clone>/bench/migration/results bench/consumers/run.sh spring-petclinic jpetstore
bench/consumers/run.sh --selftest      # no network, no apps: planted samples through the statistics and report
```

With no app named it runs every app in `bench/migration/apps`, one after another. Settings, all
optional: `REPS` (repetitions per version, default 5), `WARMUP` (seconds, default 30), `DURATION`
(seconds per timed load run, default 5), `CONCURRENCY` (default `1 8 32`), `OUT` (default
`bench/consumers/results/<UTC time>/`, gitignored). At the defaults an app takes about 40 minutes
for 12 timed pages.

The image is the migration bench image (`bench.py image`: Thwart built from this checkout's
`HEAD`, JDK 8 and 21, Maven, the apps' helpers) plus [Dockerfile](Dockerfile): the
[oha](https://github.com/hatoo/oha) load generator, pinned by SHA-256, and `taskset`. `run.sh`
builds both when no image is tagged with the current `HEAD`.

### Reportable numbers

Numbers from a shared machine only show that the harness works, and the report says so at the
top. Reportable numbers come from a dedicated VM:

```sh
bench/consumers/vm.sh --project <GCP project> --results <archive clone>/bench/migration/results \
  --arch amd64 spring-petclinic jpetstore
```

It creates a GCE spot VM (`c4-standard-16` for amd64, `c4a-standard-16` for arm64, no service
account, deleted after `--max-run`, default 8h, and on every exit), copies `HEAD` and the
results' patches over, runs `run.sh` there with `BENCH_REPORTABLE=yes`, and copies the output back
to `bench/consumers/results/vm-<time>-<arch>/`. The project is an argument (or
`BENCH_VM_PROJECT`), never written here. All 18 apps at the defaults need about 12 hours, so
split them over several VMs.

## What it measures

Both versions run in one container, on the same machine, one after the other. Each repetition
builds the version, starts a fresh JVM and measures it; the order alternates between repetitions
(original first in odd ones). The app runs on the first half of the container's CPUs and oha on
the other half. Both versions of an app run on the app's JDK (`"java"` in `app.json`) with the
same flags: `-Xms1g -Xmx1g -XX:+UseG1GC`, plus a GC log, passed in `JAVA_TOOL_OPTIONS` so they
reach the JVM whatever the start command (`java -jar`, `mvn spring-boot:run`, `jetty:run-war`).

Before anything is timed, each version is built once (untimed: this fetches dependencies),
started, sent `pages.txt` in order (`replay`: one cookie jar, redirects followed, each POST with
the previous page's `_csrf`, as `thwart-migrate record` does, but no request to a logout or
signout path, so the session stays logged in), and then sent each GET path once
with the session cookie and no redirect followed (`probe`). `pages.tsv` lists every GET path, its
status on each version, and whether it is timed. A page is timed when both versions give it the
same status, the status is not a redirect, the path has no `logout`, `signout`, `delete` or
`remove` (they end the session or change the data), and no earlier timed page has the same
pattern (each run of digits counts as one, so `/owners/1` and `/owners/2` are timed once). Error pages
(4xx, 5xx) with the same status are timed: they render templates too.

Then, per repetition and version:

1. **Build cost:** the app's `build` command from `app.json` (a clean build), timed, and the size
   of the largest jar or war under `target/` (or `build/`).
2. **Cold start:** time from launching the `start` command to the `ready` URL answering. For apps
   started through Maven this includes Maven.
3. **First requests:** `pages.txt` is replayed again (this also rebuilds the data the POSTs make
   and logs in); the time of each timed page's first request, redirects included.
4. **Warm-up:** `WARMUP` seconds of mixed traffic at the highest concurrency.
5. **Memory after warm-up:** a forced GC (`jcmd GC.run`); the live heap after it, from the GC log,
   and the JVM's RSS.
6. **Pages:** for each timed page and each concurrency level, `DURATION` seconds of oha (closed
   loop, keep-alive, the session cookie, no redirects followed): requests/s and p50, p90 and p99
   latency. A forced GC before and after each page's runs gives the bytes allocated per request
   (the heap growth between collections in the GC log, over the requests sent).
7. **Mixed traffic:** the GET lines of `pages.txt` as one request mix (`mixed.txt`), oha picking a
   line at random for each request, at each concurrency level; allocation per request as for
   pages. A line is in the mix when both versions gave its path the same status (redirects
   included), and it is not a logout or delete link. The warm-up uses the same mix.
8. **Peak RSS** of the JVM (`VmHWM`).

The start command runs in its own session; stopping the app kills that session's process group
(and any PostgreSQL that `pg_ctl` detached) and removes the start scripts' state under `/tmp`, so
every start is a first start. The JVM measured is the newest `java` process once the app answers
(for `mvn spring-boot:run`, the forked app). oha counts responses whose status is not the probed one, and failed requests;
the report names any page that had some.

## Output

`OUT/` holds `machine.txt` (date, Thwart commit, CPU, cores, memory, kernel, Docker, oha, JVM
flags, settings), `report.md`, and per app:

- `status.txt` (benchmarked, or not and why), `meta.txt` (engine, JDK, the run used, the
  artifact and the Spring and Boot jars in it), `container.txt` (JDK, Maven, CPU split),
  `pages.tsv`;
- `samples.tsv`: every sample, one per line: version (`orig` or `thwart`), repetition, metric,
  case, value. Metrics: `rps`, `p50_ms`, `p90_ms`, `p99_ms`, `requests`, `bad_responses` (case
  `<path> @c<concurrency>` or `mixed @c<concurrency>`), `first_ms` and `alloc_kb` (case: the path,
  or `mixed`), `build_s`, `artifact_mb`, `cold_start_ms`, `heap_live_mb`, `rss_mb`,
  `rss_peak_mb`;
- `<version>/<rep>/`: oha's JSON for each run, the GC log, the build and app logs.

## Statistics

For each figure, the report gives the median over the repetitions of each version and their
ratio, Thwart over original (above 1 means more of the figure: more requests/s, or more
milliseconds, megabytes or seconds). The 95% interval on the ratio is a percentile bootstrap: 2000
times, it resamples each version's per-repetition values with replacement and takes the ratio of
the two medians; the interval runs from the 2.5th to the 97.5th percentile of those ratios. The
draws come from a Park-Miller generator with a fixed seed, so the same samples always give the
same interval. Ratios are truncated to two places and intervals rounded outward. With fewer than
five repetitions a side (the default) there is no interval: with three, the resampled median is
always one of the three values, so the percentiles are the whole range from the smallest to the
largest ratio (with four, nearly so), and a run of the same app on both sides would leave out 1
far more often than 5% of the time. Release readiness is judged elsewhere, from these numbers.

## Limits

- GET only: POST lines build the state (and log in), but are not timed.
- One session cookie is shared by every load connection.
- Allocation is read from the GC log in the units it prints (K or M per collection), so it is
  exact over many collections and coarse over few; pages with very short runs read low.
- ewp-registry's and posters-demo-store's start scripts put a Python forwarder on port 8080 that
  opens only once the app is ready (see bench/migration's README); load goes through it, so their
  throughput is the forwarder's as much as the app's, on both versions.
- A start command that runs through Maven (`jetty:run-war`, `spring-boot:run`) gives Maven's JVM
  the same flags; its cold start includes Maven's startup and up-to-date checks.
