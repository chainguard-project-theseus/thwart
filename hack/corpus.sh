#!/bin/bash
# Regression corpus: rebuild and test every migrated bench app against the locally installed
# Tessera 0.1.0-SNAPSHOT.
#
# usage: hack/corpus.sh [APP ...]        (default: every app with a run under RESULTS)
#
# For each app under RESULTS with a run-*/diff.patch: clone the repo from
# bench/migration/apps/<app>/app.json at its pinned commit, apply the latest run's diff.patch,
# then run the app's build and test commands on its JDK ("java": 8 or the default 21). Prints a
# table of app, run, compile and tests; exits non-zero if any app fails.
#
# Install Tessera first: in a fresh session `mvn -B -q install -DskipTests -Dinvoker.skip` (tier0 installs
# nothing on a clean checkout), after a change `hack/quick.sh tier0`. Needs git, jq, procps,
# JDK 21 and 8 under /usr/lib/jvm, network for the apps' own dependencies, and nodejs/npm for
# ewp-registry. Env: RESULTS (bench output to read, default bench/migration/results; point it at
# an archive of past runs laid out the same way), CORPUS_DIR (work dir, default
# /tmp/tessera-corpus), JOBS (parallel apps, default 2).
set -uo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
bench=$root/bench/migration
RESULTS=${RESULTS:-$bench/results}
work=${CORPUS_DIR:-/tmp/tessera-corpus}
jobs=${JOBS:-2}
# Parallel builds share ~/.m2: lock per artifact, as the bench image does.
export MAVEN_ARGS="${MAVEN_ARGS:-} -Daether.syncContext.named.factory=file-lock -Daether.syncContext.named.nameMapper=file-gav"

latest_run() {
  ls -d "$RESULTS/$1"/run-*/diff.patch 2>/dev/null | sed 's|.*/run-\([0-9]*\)/diff.patch|\1|' | sort -n | tail -1
}

# One app: writes $work/out/<app>/{run,build.exit,test.exit,tests} and the logs beside them.
one() {
  local app=$1 cfg=$bench/apps/$1/app.json run out=$work/out/$1 src=$work/src/$1 cache=$work/cache/$1
  run=$(latest_run "$app")
  rm -rf "$out" "$src"
  mkdir -p "$out"
  echo "$run" > "$out/run"
  local repo commit jdk
  repo=$(jq -r .repo "$cfg") commit=$(jq -r .commit "$cfg")
  jdk=/usr/lib/jvm/java-21-openjdk
  [ "$(jq -r '.java // 21' "$cfg")" = 8 ] && jdk=/usr/lib/jvm/java-1.8-openjdk
  {
    [ -d "$cache" ] || git clone -q "$repo" "$cache"
    git -C "$cache" fetch -q origin "$commit" &&
      git clone -q --no-checkout "$cache" "$src" &&
      git -C "$src" checkout -q -B bench "$commit" &&
      git -C "$src" apply --whitespace=nowarn "$RESULTS/$app/run-$run/diff.patch"
  } > "$out/setup.log" 2>&1 || { echo setup > "$out/build.exit"; return; }
  # The bench container mounts the app's fixtures at /bench/app and lib/ at /bench/lib.
  local build test
  build=$(jq -r .build "$cfg" | sed "s|/bench/app|$bench/apps/$app|g; s|/bench/lib|$bench/lib|g")
  test=$(jq -r .test "$cfg" | sed "s|/bench/app|$bench/apps/$app|g; s|/bench/lib|$bench/lib|g")
  (cd "$src" && JAVA_HOME=$jdk PATH=$jdk/bin:$PATH bash -c "$build") > "$out/build.log" 2>&1
  echo $? > "$out/build.exit"
  [ "$(cat "$out/build.exit")" = 0 ] || return
  (cd "$src" && JAVA_HOME=$jdk PATH=$jdk/bin:$PATH bash -c "$test") > "$out/test.log" 2>&1
  echo $? > "$out/test.exit"
  # tests run / failed+errors, summed over the surefire and failsafe reports (as lib/check.sh counts).
  find "$src" -path '*/target/*-reports/TEST-*.xml' -exec sed -n 's/.*<testsuite \(.*\)/\1/p' {} + |
    awk '{ for (i = 1; i <= NF; i++) { split($i, kv, "="); gsub(/[">]/, "", kv[2]);
             if (kv[1] == "tests") t += kv[2]; if (kv[1] == "failures" || kv[1] == "errors") f += kv[2] } }
         END { printf "%d %d\n", t, f }' > "$out/tests"
}

if [ $# -gt 0 ]; then
  apps=("$@")
else
  apps=()
  for d in "$RESULTS"/*/; do
    a=$(basename "$d")
    [ -n "$(latest_run "$a")" ] && apps+=("$a")
  done
fi
[ ${#apps[@]} -gt 0 ] || { echo "no run-*/diff.patch under $RESULTS" >&2; exit 2; }
for a in "${apps[@]}"; do
  [ -f "$bench/apps/$a/app.json" ] && [ -n "$(latest_run "$a")" ] || { echo "no app.json or diff.patch for $a" >&2; exit 2; }
done

mkdir -p "$work/cache" "$work/src" "$work/out"
export -f one latest_run
export bench work RESULTS
printf '%s\n' "${apps[@]}" | xargs -P "$jobs" -I{} bash -c 'one "$1"; echo "done: $1" >&2' _ {}

fail=0
printf '%-38s %-6s %-8s %s\n' APP RUN COMPILE TESTS
for a in "${apps[@]}"; do
  o=$work/out/$a b=$(cat "$work/out/$a/build.exit" 2>/dev/null || echo "?")
  compile=pass tests=-
  case $b in
    0)
      read -r t f < <(cat "$o/tests" 2>/dev/null || echo "0 0")
      if [ "$(cat "$o/test.exit")" = 0 ]; then tests="pass ($t)"; else tests="FAIL ($f of $t)"; fail=1; fi
      ;;
    setup) compile="SETUP"; fail=1 ;;
    *) compile=FAIL; fail=1 ;;
  esac
  printf '%-38s %-6s %-8s %s\n' "$a" "run-$(cat "$o/run")" "$compile" "$tests"
done
echo "logs: $work/out/<app>/{setup,build,test}.log"
exit $fail
