#!/usr/bin/env bash
# The Node oracle job (#322): parse5, headless Chromium and Firefox ESR over the oracle's cases,
# ratcheted against known holes. Chainguard Checks runs it (.chainguard/ci/oracle-node.yaml); it is
# not part of `mvn verify`.
#
# Locally: CHROMIUM_PATH=... [FIREFOX_PATH=...] [ORACLE_ENGINES=parse5,chromium] bash run.sh
# Without FIREFOX_PATH it downloads the pinned Firefox ESR (linux-x86_64) and checks its SHA-256.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
work=${ORACLE_WORK:-/var/tmp/tessera-oracle-node}
engines=${ORACLE_ENGINES:-parse5,chromium,firefox}

FIREFOX_VERSION=140.17.0esr
FIREFOX_SHA256=86d049070ad80b74d8e674793e22d28923b4a7a1da06b4d65402f91c67a10314

if [[ $engines == *firefox* && -z ${FIREFOX_PATH:-} ]]; then
  mkdir -p "$work"
  tarball=$work/firefox-$FIREFOX_VERSION.tar.xz
  if [[ ! -f $tarball ]]; then
    curl -fsSL --retry 3 -o "$tarball.part" \
      "https://archive.mozilla.org/pub/firefox/releases/$FIREFOX_VERSION/linux-x86_64/en-US/firefox-$FIREFOX_VERSION.tar.xz"
    mv "$tarball.part" "$tarball"
  fi
  echo "$FIREFOX_SHA256  $tarball" | sha256sum -c -
  rm -rf "$work/firefox"
  tar -xJf "$tarball" -C "$work"
  export FIREFOX_PATH=$work/firefox/firefox
fi

cd "$here"
npm ci --ignore-scripts --no-audit --no-fund

# Self-check on the fixture (renders from 0dda2f1): with no known holes the checker must fail and
# report every hole fixture-known-holes.txt lists (A5 and A31), so a checker that has gone blind
# can't pass.
out=$(node check.mjs --engines "$engines" --known-holes /dev/null fixture.jsonl) && status=0 || status=$?
echo "$out"
if [[ $status -ne 1 ]]; then
  echo "self-check: expected exit 1 with no known holes, got $status" >&2
  exit 1
fi
while read -r id kind engine _; do
  [[ -z $id || $id == \#* ]] && continue
  [[ $engines == *$engine* ]] || continue
  if ! grep -Eq "^FAIL new hole: $id \([^)]*\) $kind $engine at " <<<"$out"; then
    echo "self-check: no '$kind $engine' finding for fixture case $id" >&2
    exit 1
  fi
done <fixture-known-holes.txt
echo "self-check: ok"

# TODO(#324): export the real cases with ProfileOracleTest (-Dtessera.oracle.export) and ratchet
# against ../resources/oracle/known-holes.txt instead of the fixture.
node check.mjs --engines "$engines" --known-holes fixture-known-holes.txt fixture.jsonl
