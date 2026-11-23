#!/bin/bash
# The consumer benchmark: each bench app's original version against its Thwart-migrated version,
# timed over HTTP on this machine (README.md). Numbers only; no verdict.
#
# usage: bench/consumers/run.sh [APP ...]      (default: every app in bench/migration/apps)
#        bench/consumers/run.sh --selftest     planted samples through the statistics and report
#
# env: RESULTS      the migration results to take the Thwart versions from: a clone of the
#                   archive's bench/migration/results (required)
#      OUT          output directory (default bench/consumers/results/<UTC time>)
#      REPS         repetitions per version (default 5)
#      WARMUP       seconds of mixed traffic before timing, per JVM (default 30)
#      DURATION     seconds per timed load run (default 5)
#      CONCURRENCY  concurrency levels (default "1 8 32")
#      BENCH_REPORTABLE=yes   set by vm.sh on a dedicated VM; anything else labels the report
#                   as a local validation run
#
# Needs Docker, git and Python 3. The image is the migration bench image (bench.py image, from
# this checkout's HEAD) plus bench/consumers/Dockerfile; it is rebuilt when HEAD changes.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
bench=$root/bench/migration

if [ "${1:-}" = --selftest ]; then
  exec python3 "$here/lib/consumers.py" selftest
fi
[ -n "${RESULTS:-}" ] && [ -d "$RESULTS" ] || { echo "run.sh: set RESULTS to the migration results directory (see the header)" >&2; exit 2; }
reps=${REPS:-5} warmup=${WARMUP:-30} duration=${DURATION:-5} concurrency=${CONCURRENCY:-1 8 32}
flags="-Xms1g -Xmx1g -XX:+UseG1GC"
commit=$(git -C "$root" rev-parse --short=10 HEAD)
image=thwart-consumer-bench:$commit
out=${OUT:-$here/results/$(date -u +%Y%m%d-%H%M%S)}
work=$here/work
mkdir -p "$out" "$work/src"

json() { python3 -c 'import json,sys; v=json.load(open(sys.argv[1])).get(sys.argv[2], sys.argv[3]); print(str(v).lower() if isinstance(v, bool) else v)' "$@"; }

if ! docker image inspect "$image" > /dev/null 2>&1; then
  python3 "$bench/bench.py" image
  docker build -t "$image" "$here"
fi

if [ $# -gt 0 ]; then apps=("$@"); else mapfile -t apps < <(ls "$bench/apps"); fi

{
  echo "date: $(date -u +%Y-%m-%dT%H:%MZ)"
  echo "Thwart commit: $commit"
  echo "cpu: $(sed -n 's/^model name\s*: //p' /proc/cpuinfo | head -1)"
  echo "cores: $(nproc)"
  echo "memory: $(awk '/^MemTotal/ { printf "%.0f GiB", $2 / 1048576 }' /proc/meminfo)"
  echo "kernel: $(uname -r)"
  echo "docker: $(docker version --format '{{.Server.Version}}' 2>/dev/null)"
  echo "load generator: $(docker run --rm "$image" oha --version)"
  echo "JVM flags: $flags"
  echo "repetitions: $reps; warm-up ${warmup}s per JVM; ${duration}s per load run; concurrency $concurrency"
  echo "reportable: ${BENCH_REPORTABLE:-no}"
} > "$out/machine.txt"

# The latest run that passed strictly, else the latest that passed with forced diffs.
passing_run() {
  local key d k
  for key in pass pass_forced; do
    for k in $(find "$RESULTS/$1" -maxdepth 1 -name 'run-*' -printf '%f\n' 2>/dev/null | sed 's/^run-//' | sort -nr); do
      d=$RESULTS/$1/run-$k
      [ -f "$d/score.json" ] && [ -f "$d/diff.patch" ] && [ "$(json "$d/score.json" "$key" false)" = true ] &&
        { echo "$k $key"; return; }
    done
  done
}

checkout() {
  local repo=$1 commit=$2 dest=$3 cache=$work/src/$4
  [ -d "$cache" ] || git clone -q "$repo" "$cache"
  git -C "$cache" fetch -q origin "$commit"
  rm -rf "$dest"
  git clone -q --no-checkout "$cache" "$dest"
  git -C "$dest" checkout -q -B bench "$commit"
}

for app in "${apps[@]}"; do
  cfg=$bench/apps/$app/app.json
  [ -f "$cfg" ] || { echo "run.sh: no app $app" >&2; exit 2; }
  o=$out/$app
  mkdir -p "$o"
  jdk=21
  [ "$(json "$cfg" java 21)" = 8 ] && jdk=8
  {
    echo "engine: $(json "$cfg" engine -)"
    echo "jdk: $jdk"
  } > "$o/meta.txt"
  read -r k how <<< "$(passing_run "$app")"
  if [ -z "${k:-}" ]; then
    echo "not benchmarked: no passing migration run with a diff.patch" > "$o/status.txt"
    continue
  fi
  echo "run: run-$k ($how)" >> "$o/meta.txt"
  echo "== $app: original against run-$k ($how)"
  repo=$(json "$cfg" repo -) pin=$(json "$cfg" commit -)
  if ! { checkout "$repo" "$pin" "$work/$app/orig" "$app" && checkout "$repo" "$pin" "$work/$app/thwart" "$app"; } > "$o/setup.log" 2>&1; then
    echo "not benchmarked: could not check out the app (see setup.log)" > "$o/status.txt"
    continue
  fi
  # Archived patches use the old Tessera names (hack/corpus.sh).
  if ! sed 's/tessera\.dev/thwart.chainguard.dev/g; s/dev\.tessera/dev.chainguard.thwart/g; s/Tessera/Thwart/g; s/tessera/thwart/g' \
      "$RESULTS/$app/run-$k/diff.patch" | git -C "$work/$app/thwart" apply --whitespace=nowarn >> "$o/setup.log" 2>&1; then
    echo "not benchmarked: run-$k's diff.patch does not apply (see setup.log)" > "$o/status.txt"
    continue
  fi
  # The container's user is not this one: let it write both trees and the output.
  chmod -R a+rwX "$work/$app" "$o"
  jdk_home=/usr/lib/jvm/java-21-openjdk
  [ "$jdk" = 8 ] && jdk_home=/usr/lib/jvm/java-1.8-openjdk
  docker run --rm --init --memory=8g -w /work \
    -v thwart-migration-bench-m2:/home/bench/.m2 \
    -v "$work/$app/orig:/work/orig" -v "$work/$app/thwart:/work/thwart" -v "$o:/work/out" \
    -v "$bench/lib:/bench/lib:ro" -v "$bench/apps/$app:/bench/app:ro" \
    -v "$bench/apps/$app/pages.txt:/bench/pages.txt:ro" -v "$here/lib:/bench/consumers:ro" \
    -e BUILD="$(json "$cfg" build -)" -e START="$(json "$cfg" start -)" \
    -e READY="$(json "$cfg" ready /)" -e BASE="$(json "$cfg" base -)" -e JDK="$jdk_home" \
    -e REPS="$reps" -e WARMUP="$warmup" -e DURATION="$duration" -e CONCURRENCY="$concurrency" \
    -e JVM_FLAGS="$flags" "$image" bash /bench/consumers/bench.sh > "$o/bench.log" 2>&1 ||
    echo "not benchmarked: the bench container failed (see bench.log)" > "$o/status.txt"
  echo "   $(cat "$o/status.txt" 2>/dev/null || echo 'not benchmarked: no status')"
done

python3 "$here/lib/consumers.py" report "$out"
echo "report: $out/report.md"
