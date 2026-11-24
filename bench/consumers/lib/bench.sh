#!/bin/bash
# Times one app, original and Thwart-migrated. Runs inside the consumer bench container.
#
# usage: bench.sh
# mounts: /work/orig and /work/thwart (the two checkouts), /bench/pages.txt, /work/out
# env:    BUILD, START, READY, BASE (from app.json), JDK (JAVA_HOME of the app), REPS, WARMUP and
#         DURATION (seconds), CONCURRENCY (levels, space-separated), JVM_FLAGS
#
# Writes to /work/out: status.txt, meta.txt, pages.tsv, samples.tsv (version, rep, metric, case,
# value), and per version and repetition the logs and oha's JSON under <version>/<rep>/.
set -u
umask 0000
out=/work/out pages=/bench/pages.txt
py="python3 /bench/consumers/consumers.py"
mkdir -p "$out"
samples=$out/samples.tsv
printf 'version\trep\tmetric\tcase\tvalue\n' > "$samples"

# The app gets the first half of the CPUs, the load generator the rest.
cpus=()
IFS=, read -ra ranges <<< "$(sed -n 's/^Cpus_allowed_list:\s*//p' /proc/self/status)"
for r in "${ranges[@]}"; do
  if [[ $r == *-* ]]; then mapfile -t -O "${#cpus[@]}" cpus < <(seq "${r%-*}" "${r#*-}"); else cpus+=("$r"); fi
done
half=$((${#cpus[@]} / 2))
app_cpus=$(IFS=,; echo "${cpus[*]:0:half}")
load_cpus=$(IFS=,; echo "${cpus[*]:half}")

gclog="-Xlog:gc:file=/tmp/gc-%p.log"
"$JDK/bin/java" -version 2>&1 | grep -q '"1\.8' && gclog="-Xloggc:/tmp/gc-%p.log -XX:+PrintGC"
jdk() { JAVA_HOME=$JDK PATH=$JDK/bin:$PATH bash -c "$1"; }
now() { date +%s%N; }
ms_since() { echo $(( ($(now) - $1) / 1000000 )); }
mb() { awk -v b="$1" 'BEGIN { printf "%.3f", b / 1048576 }'; }
sample() { [ -n "$5" ] || return 0; printf '%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" "$5" >> "$samples"; }
fail() { echo "not benchmarked: $1" > "$out/status.txt"; exit 0; }

# build VERSION: the app's build command in its checkout; prints seconds, 1 on failure.
build() {
  local t0 rc
  t0=$(now)
  (cd "/work/$1" && jdk "$BUILD") > "$2" 2>&1
  rc=$?
  awk -v d=$(($(now) - t0)) 'BEGIN { printf "%.3f\n", d / 1e9 }'
  return $rc
}

# The largest jar or war under a target or build directory: the app's artifact.
artifact() {
  find "/work/$1" -maxdepth 4 \( -path '*/target/*' -o -path '*/build/*' \) \( -name '*.jar' -o -name '*.war' \) \
    ! -name '*sources*' ! -name '*.original' -printf '%s %P\n' 2>/dev/null | sort -n | tail -1
}

# Spring and Boot versions, from the libraries in the artifact or beside it.
spring() {
  local a=$1 names
  names=$({ "$JDK/bin/jar" tf "/work/$a" 2>/dev/null; find "$(dirname "/work/$a")" -name '*.jar' 2>/dev/null; } |
    sed -n 's#.*/\(spring-\(webmvc\|boot\)-[0-9][^/]*\)\.jar$#\1#p' | sort -u | tr '\n' ' ')
  echo "${names:-unknown}"
}

# start VERSION LOG: starts the app in its own session on the app CPUs with the pinned JVM
# flags; sets group (the session's process group), pid (the newest java process) and cold (ms
# to the ready URL answering), or returns 1.
start() {
  local t0 code
  rm -f /tmp/gc-*.log
  t0=$(now)
  # taskset and setsid exec in place, so $! is the new session's leader and process group.
  JAVA_TOOL_OPTIONS="$JVM_FLAGS $gclog" taskset -c "$app_cpus" setsid bash -c \
    "cd /work/$1 || exit 1; export JAVA_HOME=$JDK PATH=$JDK/bin:\$PATH; $START" > "$2" 2>&1 < /dev/null &
  group=$!
  for _ in $(seq 1 6000); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE$READY")
    [ "$code" != 000 ] && break
    sleep 0.1
  done
  [ "$code" != 000 ] || return 1
  cold=$(ms_since "$t0")
  pid=$(pgrep -n java)
  # JDK 8 names the log gc-pidN.log, JDK 9+ gc-N.log.
  gcfile=/tmp/gc-$pid.log
  [ -f "$gcfile" ] || gcfile=/tmp/gc-pid$pid.log
}

# stop: ends everything the start command left (its session, and the PostgreSQL that pg_ctl
# detaches), and removes the start scripts' state under /tmp, so the next start is a first one.
stop() {
  kill -- "-$group" 2> /dev/null; pkill -f java; pkill -f postgres; pkill -x mysqld
  sleep 2
  kill -9 -- "-$group" 2> /dev/null; pkill -9 -f java; pkill -9 -f postgres; pkill -9 -x mysqld
  for _ in $(seq 1 100); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE$READY")" = 000 ] && break
    sleep 0.2
  done
  rm -rf /tmp/pgdata /tmp/ewp-* /tmp/posters-* /tmp/luckyblog.cp /tmp/h2 /tmp/mysql /tmp/mysql.sock* /tmp/mysql.pid /tmp/mysql*.log
  sleep 1
}

# gc_mark: a forced collection; prints the GC log's line count after it.
gc_mark() { jdk "jcmd $pid GC.run" > /dev/null 2>&1; sleep 0.5; wc -l < "$gcfile"; }

# load NAME CONC SECONDS ARGS...: one oha run on the load CPUs into $dir/NAME.json.
load() {
  local name=$1 c=$2 secs=$3
  shift 3
  taskset -c "$load_cpus" oha --no-tui -w -z "${secs}s" -c "$c" --redirect 0 --output-format json \
    -H 'Accept: text/html' -H 'Accept-Language: en' -H "Cookie: $(cat "$dir/cookie.txt")" "$@" \
    > "$dir/$name.json" 2> "$dir/$name.err"
}

# requests NAME...: total responses of these oha runs.
requests() {
  local n=0 f
  for f in "$@"; do
    n=$((n + $(python3 -c 'import json,sys; print(sum(json.load(open(sys.argv[1]))["statusCodeDistribution"].values()))' "$dir/$f.json")))
  done
  echo "$n"
}

# alloc VERSION REP CASE FROM REQUESTS: KB allocated per request since the mark FROM.
alloc() {
  local w
  w=$($py gc "$gcfile" "$4")
  [ "${w#* }" != - ] && [ "$5" -gt 0 ] && sample "$1" "$2" alloc_kb "$3" "$(awk -v b="${w#* }" -v n="$5" 'BEGIN { printf "%.3f", b / n / 1024 }')"
}

{
  echo "load generator: $(oha --version)"
  echo "jdk: $("$JDK/bin/java" -version 2>&1 | head -2 | tr '\n' ' ')"
  echo "maven: $(mvn -v 2>/dev/null | head -1)"
  echo "cpus: app on $app_cpus, load generator on $load_cpus"
} > "$out/container.txt"

# 1. A first build of each version (untimed: it also fetches dependencies).
for v in orig thwart; do
  mkdir -p "$out/$v"
  build "$v" "$out/$v/build-first.log" > /dev/null || fail "the $v version does not build (see $v/build-first.log)"
  read -r size path <<< "$(artifact "$v")"
  [ -n "${path:-}" ] || fail "no jar or war under target/ or build/ in the $v version"
  echo "artifact $v: $path" >> "$out/meta.txt"
  echo "spring $v: $(spring "$v/$path")" >> "$out/meta.txt"
done

# 2. Both versions answer each GET path with the same status before anything is timed.
for v in orig thwart; do
  start "$v" "$out/$v/app-probe.log" || { stop; fail "the $v version never answered $READY (see $v/app-probe.log)"; }
  ok=0
  $py replay "$BASE" "$pages" "$out/$v" > "$out/$v/probe.log" 2>&1 &&
    $py probe "$BASE" "$pages" "$out/$v/cookie.txt" "$out/$v/probe.tsv" >> "$out/$v/probe.log" 2>&1 && ok=1
  stop
  [ "$ok" = 1 ] || fail "replaying pages.txt on the $v version failed (see $v/probe.log)"
done
$py select "$pages" "$out/orig/probe.tsv" "$out/thwart/probe.tsv" "$out" || fail "could not select the pages to time"
mapfile -t timed < <(awk -F '\t' '$4 == "yes" { print $1 "\t" $2 }' "$out/pages.tsv")
[ "${#timed[@]}" -gt 0 ] && [ -s "$out/mixed.txt" ] || fail "no page has the same status on both versions (see pages.tsv)"
sed "s|^|$BASE|" "$out/mixed.txt" > "$out/mixed-urls.txt"
read -ra levels <<< "$CONCURRENCY"
top=${levels[${#levels[@]} - 1]}

# 3. Repetitions, alternating which version goes first; each starts a fresh JVM.
for rep in $(seq 1 "$REPS"); do
  order="orig thwart"
  [ $((rep % 2)) = 0 ] && order="thwart orig"
  for v in $order; do
    dir=$out/$v/$rep
    mkdir -p "$dir"
    s=$(build "$v" "$dir/build.log") || fail "the $v version failed to build in repetition $rep"
    sample "$v" "$rep" build_s - "$s"
    read -r size _ <<< "$(artifact "$v")"
    sample "$v" "$rep" artifact_mb - "$(mb "$size")"
    start "$v" "$dir/app.log" || { stop; fail "the $v version never answered $READY in repetition $rep"; }
    sample "$v" "$rep" cold_start_ms - "$cold"
    $py replay "$BASE" "$pages" "$dir"
    for t in "${timed[@]}"; do
      p=${t%%$'\t'*}
      sample "$v" "$rep" first_ms "$p" "$(awk -F '\t' -v p="$p" '$1 == p { print $3 }' "$dir/first.tsv")"
    done
    load warmup "$top" "$WARMUP" --urls-from-file "$out/mixed-urls.txt"
    mark=$(gc_mark)
    read -r live _ <<< "$($py gc "$gcfile" "$mark")"
    [ "$live" != - ] && sample "$v" "$rep" heap_live_mb - "$(mb "$live")"
    sample "$v" "$rep" rss_mb - "$(awk '/^VmRSS/ { print $2 / 1024 }' "/proc/$pid/status")"
    i=0
    for t in "${timed[@]}"; do
      p=${t%%$'\t'*} st=${t#*$'\t'} i=$((i + 1)) runs=()
      mark=$(gc_mark)
      for c in "${levels[@]}"; do
        load "p$i-c$c" "$c" "$DURATION" "$BASE$p"
        $py oha "$dir/p$i-c$c.json" "$v"$'\t'"$rep"$'\t' "$st" "$p @c$c" >> "$samples"
        runs+=("p$i-c$c")
      done
      gc_mark > /dev/null
      alloc "$v" "$rep" "$p" "$mark" "$(requests "${runs[@]}")"
    done
    runs=()
    mark=$(gc_mark)
    for c in "${levels[@]}"; do
      load "mixed-c$c" "$c" "$DURATION" --urls-from-file "$out/mixed-urls.txt"
      $py oha "$dir/mixed-c$c.json" "$v"$'\t'"$rep"$'\t' '*' "mixed @c$c" >> "$samples"
      runs+=("mixed-c$c")
    done
    gc_mark > /dev/null
    alloc "$v" "$rep" mixed "$mark" "$(requests "${runs[@]}")"
    sample "$v" "$rep" rss_peak_mb - "$(awk '/^VmHWM/ { print $2 / 1024 }' "/proc/$pid/status")"
    cp "$gcfile" "$dir/gc.log"
    stop
  done
done
echo benchmarked > "$out/status.txt"
