#!/bin/bash
# Fast inner loop for a change: offline, narrow Maven runs instead of a full `mvn verify`.
#
# usage: hack/quick.sh tier0|tier1|tier2 [BASE]
#
#   tier0  install the modules changed since BASE (and what they depend on), no tests
#   tier1  the security core tests (verifier, hostile sweep, escapers, SSTI, ASCII, choke point)
#   tier2  tier0, then the tests of the changed modules
#
# Changed modules come from `git diff` against the merge base of HEAD and BASE (default
# origin/main), plus uncommitted and untracked files. A change to the root pom.xml counts as
# every module. Maven runs offline (-o); set ONLINE=1 for the first run in a fresh session.
set -euo pipefail
cd "$(dirname "$0")/.."

tier=${1:-}
base=${2:-origin/main}
mvn=(mvn -B -q -T1C -Dinvoker.skip)
[ "${ONLINE:-}" = 1 ] || mvn+=(-o)

pom_modules() { sed -n 's|.*<module>\(.*\)</module>.*|\1|p' "$1/pom.xml"; }

# Leaf modules: an aggregator (tessera-spring-tests) stands for its children.
modules=$(for m in $(pom_modules .); do
  subs=$(pom_modules "$m")
  if [ -n "$subs" ]; then printf "$m/%s\n" $subs; else echo "$m"; fi
done)

changed_modules() {
  local mb files m out=()
  mb=$(git merge-base HEAD "$base")
  files=$( (git diff --name-only "$mb"; git ls-files --others --exclude-standard) | sort -u)
  for m in $modules; do
    # A file in the module, or the root or the aggregator pom above it. The javax starter has no
    # sources: it is the jakarta starter's jar relocated, so it changes whenever that one does.
    if grep -q -e "^$m/" -e '^pom.xml$' -e "^$(dirname "$m")/pom.xml\$" <<<"$files" \
        || { [ "$m" = tessera-spring-boot-starter-javax ] && grep -q '^tessera-spring-boot-starter/' <<<"$files"; }; then
      out+=("$m")
    fi
  done
  local IFS=,
  echo "${out[*]}"
}

tier0() {
  mods=$(changed_modules)
  if [ -z "$mods" ]; then
    echo "tier0: no module changed since $base"
    return
  fi
  echo "tier0: install $mods"
  "${mvn[@]}" install -DskipTests -pl "$mods" -am
}

case $tier in
  tier0)
    tier0
    ;;
  tier1)
    tests=SeedVerifierTest,ExitStateTest,HostileSweepTest,EscapersTest,EscaperFuzzTest,ErrorFreeTest,AsciiOnlyTest,TextChokePointTest,SstiPayloadTest
    echo "tier1: $tests"
    "${mvn[@]}" test -pl tessera-runtime,tessera-compiler -am \
      -Dtest="$tests" -Dsurefire.failIfNoSpecifiedTests=false
    ;;
  tier2)
    tier0
    [ -n "$mods" ] || exit 0
    echo "tier2: test $mods"
    "${mvn[@]}" test -pl "$mods"
    ;;
  *)
    sed -n '2,12s/^# \{0,1\}//p' "$0" >&2
    exit 2
    ;;
esac
echo "$tier: ok"
