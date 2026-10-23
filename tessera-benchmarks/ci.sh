#!/usr/bin/env bash
set -euo pipefail

# Keep the same entry point locally and in Chainguard Checks.
cd "$(dirname "${BASH_SOURCE[0]}")/.."
mode=${1:-smoke}
if [[ $# -gt 1 ]]; then
  echo 'Usage: bash tessera-benchmarks/ci.sh [smoke|measure]' >&2
  exit 2
fi
case "$mode" in
  smoke) settings=(-f 1 -wi 1 -i 1 -w 200ms -r 200ms) ;;
  measure) settings=(-f 2 -wi 3 -i 3 -w 1s -r 1s) ;;
  *) echo 'Usage: bash tessera-benchmarks/ci.sh [smoke|measure]' >&2; exit 2 ;;
esac
java_command=java
if [[ -n "${JAVA_HOME:-}" ]]; then
  java_command="$JAVA_HOME/bin/java"
fi

# Maven does not notice template-only edits without clean. Run equivalence tests too.
mvn -B -ntp -pl tessera-benchmarks -am clean verify
results=tessera-benchmarks/target/ci-results
mkdir -p "$results"
revision=$(git rev-parse HEAD)
printf 'Benchmark mode: %s\nRevision: %s\n' "$mode" "$revision"
"$java_command" -version
uname -sm

# Fail on the first benchmark error: JMH otherwise continues after failed forks.
# A single worker and fixed fork heaps make configuration comparable between runs.
"$java_command" -Xmx128m -jar tessera-benchmarks/target/benchmarks.jar \
  'dev.tessera.benchmarks.*' "${settings[@]}" \
  -t 1 -jvmArgs '-Xms256m -Xmx256m' -prof gc -foe true \
  -rf json -rff "$results/results.json" | tee "$results/jmh.log"
test -s "$results/results.json"

# The VM filesystem is ephemeral. Preserve raw results in the downloadable check log.
echo '::benchmark-json-start::'
cat "$results/results.json"
echo '::benchmark-json-end::'

# Print the summary last: Checks reads summary markers from the end of the log.
echo '::sandbox-summary::'
printf '### JMH %s\n\nCommit: `%s`\n\n' "$mode" "$revision"
if [[ "$mode" == smoke ]]; then
  echo 'Smoke results only: one short fork checks execution, not performance.'
else
  echo 'Two forks, 3 x 1s warmup and measurement, one thread, 256 MiB heaps, GC profiler.'
  echo 'Shared-runner timings are informational; no regression threshold is enforced.'
fi
echo
echo '```text'
table=false
while IFS= read -r line; do
  if [[ "$line" == Benchmark*Mode* ]]; then
    table=true
  fi
  if [[ "$table" == true ]]; then
    printf '%s\n' "$line"
  fi
done < "$results/jmh.log"
echo '```'
echo '::end-sandbox-summary::'
if [[ "$table" != true ]]; then
  echo 'JMH did not produce a result table.' >&2
  exit 1
fi
