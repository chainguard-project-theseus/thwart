#!/usr/bin/env bash
# The Node oracle job (#322): parse5, headless Chromium, Firefox ESR and WebKit over the oracle's
# cases, ratcheted against known holes, and each engine's tree of each render compared with the vnu
# engine's tree (engine drift, #399). Chainguard Checks runs it (.chainguard/ci/oracle-node.yaml);
# it is not part of `mvn verify`.
#
# Locally: CHROMIUM_PATH=... [FIREFOX_PATH=...] [WEBKIT_PATH=...] [ORACLE_ENGINES=parse5,chromium]
# [ORACLE_EXPORT_VALUES=<n>] [ORACLE_CONCURRENCY=<pages per browser>] [ORACLE_CONCURRENCY_FIREFOX=<pages>] [ORACLE_SERIAL=1] bash run.sh
# (JAVA_HOME a JDK 21+, mvn on PATH)
# Without FIREFOX_PATH it downloads the pinned Firefox ESR (linux-x86_64) and checks its SHA-256.
# Without WEBKIT_PATH it downloads Playwright's pinned WebKit build (linux-x86_64, Ubuntu 26.04) and
# the Ubuntu 26.04 libraries it links that Wolfi doesn't ship, and checks every SHA-256.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
work=${ORACLE_WORK:-/var/tmp/tessera-oracle-node}
engines=${ORACLE_ENGINES:-parse5,chromium,firefox,webkit}

FIREFOX_VERSION=140.17.0esr
FIREFOX_SHA256=86d049070ad80b74d8e674793e22d28923b4a7a1da06b4d65402f91c67a10314

# The WebKit revision playwright-core (package.json) drives: its browsers.json, "webkit" (WebKit
# 27.2). Bump both together.
WEBKIT_REVISION=2370
WEBKIT_SHA256=d0667d52a00058b7fd8b5e338b54520bbb2c835c999f468790bec47df4534f01
# Ubuntu 26.04 (resolute, release pocket) libraries the build links that Wolfi lacks (flite,
# hyphen, libmanette and its gudev/hidapi, libsoup 3) or ships at another soname (libjxl 0.12).
# They go into the bundle's sys/lib, which its MiniBrowser wrapper puts on LD_LIBRARY_PATH.
UBUNTU_DEBS=(
  "universe/f/flite/libflite1_2.2-7build1_amd64.deb c43b7d1624664e581b09bdd3ba1782f08baea8239295f0fed39093bb41757f4a"
  "main/h/hyphen/libhyphen0_2.8.8-7build4_amd64.deb eda68421ab4d85d79cc3bdc0ee5cac6bf80b8741187446f019a98d9e40bc1101"
  "universe/libm/libmanette/libmanette-0.2-0_0.2.13-1_amd64.deb 2e68b011e47dd553c3142794f0f1ad86bff4924b1f4072ef2be2a447c5d69631"
  "main/libg/libgudev/libgudev-1.0-0_238-7build1_amd64.deb fdf0708cb0fbc95c553e3379cf77f6b1c9bb485cf3244c6246c31c7312467c81"
  "universe/h/hidapi/libhidapi-hidraw0_0.15.0-2_amd64.deb 839f3b4156a1c86d0353dac4f92deb4c910833dec49608635fc6a6f5eaba5e3b"
  "main/j/jpeg-xl/libjxl0.11_0.11.1-6ubuntu4_amd64.deb fddf95c7c5fcd2171a9a9096f07703b89359da4aee93919f79d7768b0e282b38"
  "main/libs/libsoup3/libsoup-3.0-0_3.6.6-1_amd64.deb 1e64ec18ac23c235174cd8a5a67b7b087af4172c40e8466216122982feff25a2"
)

# Downloads $1 to $2 once and checks its SHA-256 ($3).
fetch() {
  if [[ ! -f $2 ]]; then
    curl -fsSL --retry 3 -o "$2.part" "$1"
    mv "$2.part" "$2"
  fi
  echo "$3  $2" | sha256sum -c -
}

if [[ $engines == *firefox* && -z ${FIREFOX_PATH:-} ]]; then
  mkdir -p "$work"
  tarball=$work/firefox-$FIREFOX_VERSION.tar.xz
  fetch "https://archive.mozilla.org/pub/firefox/releases/$FIREFOX_VERSION/linux-x86_64/en-US/firefox-$FIREFOX_VERSION.tar.xz" \
    "$tarball" "$FIREFOX_SHA256"
  rm -rf "$work/firefox"
  tar -xJf "$tarball" -C "$work"
  export FIREFOX_PATH=$work/firefox/firefox
fi

if [[ $engines == *webkit* && -z ${WEBKIT_PATH:-} ]]; then
  mkdir -p "$work/debs"
  zip=$work/webkit-$WEBKIT_REVISION-ubuntu-26.04.zip
  fetch "https://playwright.download.prss.microsoft.com/dbazure/download/playwright/builds/webkit/$WEBKIT_REVISION/webkit-ubuntu-26.04.zip" \
    "$zip" "$WEBKIT_SHA256"
  rm -rf "$work/webkit"
  mkdir -p "$work/webkit"
  bsdtar -xf "$zip" -C "$work/webkit"
  syslib=$work/webkit/minibrowser-wpe/sys/lib
  for d in "${UBUNTU_DEBS[@]}"; do
    read -r pool sha <<<"$d"
    deb=$work/debs/${pool##*/}
    fetch "https://archive.ubuntu.com/ubuntu/pool/$pool" "$deb" "$sha"
    rm -rf "$work/deb-root"
    mkdir -p "$work/deb-root"
    bsdtar -xOf "$deb" 'data.tar.*' | bsdtar -xf - -C "$work/deb-root"
    cp -a "$work/deb-root"/usr/lib/x86_64-linux-gnu/*.so* "$syslib"/
  done
  export WEBKIT_PATH=$work/webkit/pw_run.sh
fi

cd "$here"
echo "oracle-node: $(nproc 2>/dev/null || sysctl -n hw.ncpu) CPUs, ORACLE_CONCURRENCY=${ORACLE_CONCURRENCY:-4}, ORACLE_CONCURRENCY_FIREFOX=${ORACLE_CONCURRENCY_FIREFOX:-12}"
npm ci --ignore-scripts --no-audit --no-fund

# Self-check on the fixture (renders from 0dda2f1 and synthetic renders for every detector): with no
# known holes the checker must fail and report every hole fixture-known-holes.txt lists, so a
# checker, or one of its detectors, that has gone blind can't pass.
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
# With its own list the fixture is clean: no finding beyond the listed ones, none stale. S9's trees
# are real vnu trees (VnuTreesTest), so no engine may drift on it...
if ! node check.mjs --engines "$engines" --known-holes fixture-known-holes.txt fixture.jsonl >/dev/null; then
  node check.mjs --engines "$engines" --known-holes fixture-known-holes.txt fixture.jsonl | grep -E '^(FAIL|  drift)' >&2 || true
  echo "self-check: the fixture isn't clean against fixture-known-holes.txt" >&2
  exit 1
fi
# ...and a deliberately wrong normal form (whitespace-only text dropped) must be reported as drift.
if [[ $engines == *parse5* ]]; then
  out=$(node check.mjs --engines parse5 --mutate-normal-form --known-holes fixture-known-holes.txt fixture.jsonl) \
    && status=0 || status=$?
  if [[ $status -ne 1 ]] || ! grep -Eq '^FAIL new hole: 646b066c48b3 \(S9-drift-none\) ENGINE_DRIFT parse5 at ' <<<"$out"; then
    echo "$out" >&2
    echo "self-check: a mutated normal form was not reported as ENGINE_DRIFT (exit $status)" >&2
    exit 1
  fi
fi
echo "self-check: ok"

# The real cases: ProfileOracleTest (#324) compiles the seeds and exports
# every accepted seed's benign and first hostile renders (default: the #320 values and
# the structure values, ProfileOracleTest.EXPORT_DEFAULT; ORACLE_EXPORT_VALUES overrides), each with
# its vnu trees. The job ratchets them against ../resources/oracle/known-holes.txt, and every render
# must carry its trees. The value count keeps the three browsers inside the job's timeout.
cases=$work/cases.jsonl
mkdir -p "$work"
rm -f "$cases"
started=$(date +%s)
values=()
[[ -n ${ORACLE_EXPORT_VALUES:-} ]] && values=("-Dtessera.oracle.export.values=$ORACLE_EXPORT_VALUES")
(cd "$here/../../../.." && mvn -B -ntp -q -pl tessera-compiler -am test -Dtest=ProfileOracleTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dtessera.oracle.export="$cases" "${values[@]}")
echo "oracle-node: exported $(wc -l <"$cases") records in $(($(date +%s) - started)) s"
node check.mjs --engines "$engines" --require-trees "$cases"
