#!/bin/bash
# Build, crawl and test one app checkout. Runs inside the bench container.
#
# usage: check.sh APP_DIR PAGES_FILE OUT_DIR [RECORDING_DIR]
# env:   BUILD, TEST, START (shell commands run in APP_DIR), READY (path polled until the app
#        answers), BASE (base URL of the crawl, e.g. http://localhost:8080),
#        BUILD_JDK, RUN_JDK (JAVA_HOME for BUILD/TEST and for START),
#        IGNORE (newline-separated regexes passed to compare as --ignore)
#
# Without RECORDING_DIR the pages are recorded (`tessera-migrate record`) into OUT_DIR/pages;
# with it they are compared against that recording (`tessera-migrate compare`), whose output goes
# to OUT_DIR/compare.txt and exit code to OUT_DIR/compare.exit (record's to OUT_DIR/record.exit).
# OUT_DIR also gets counts.json, build.log/build.exit, app.log, test.log/test.exit, tests.json.
set -u
app=$1 pages=$2 out=$3 recording=${4:-}
lib=$(dirname "$0")
migrate="/usr/lib/jvm/default-jvm/bin/java -jar /opt/tessera-migrate.jar"
mkdir -p "$out"
cd "$app" || exit 1

with_jdk() { JAVA_HOME=$1 PATH=$1/bin:$PATH bash -c "$2"; }

python3 "$lib/count.py" . > "$out/counts.json"

with_jdk "$BUILD_JDK" "$BUILD" > "$out/build.log" 2>&1
echo $? > "$out/build.exit"

if [ "$(cat "$out/build.exit")" = 0 ]; then
  with_jdk "$RUN_JDK" "$START" > "$out/app.log" 2>&1 &
  for _ in $(seq 1 180); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE$READY")
    [ "$code" != 000 ] && break
    sleep 2
  done
  if [ -z "$recording" ]; then
    $migrate record "$BASE" "$pages" "$out/pages" > "$out/record.log" 2>&1
    echo $? > "$out/record.exit"
  else
    ignore=()
    while IFS= read -r p; do [ -n "$p" ] && ignore+=(--ignore "$p"); done <<< "${IGNORE:-}"
    $migrate compare "$BASE" "$pages" "$recording" "${ignore[@]}" > "$out/compare.txt" 2>&1
    echo $? > "$out/compare.exit"
  fi
  pkill -f java; sleep 3; pkill -9 -f java
fi

with_jdk "$BUILD_JDK" "$TEST" > "$out/test.log" 2>&1
echo $? > "$out/test.exit"
python3 - "$out/tests.json" <<'EOF'
import glob, json, sys, xml.etree.ElementTree as ET
t = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "failed": []}
for f in glob.glob("**/target/*-reports/TEST-*.xml", recursive=True):
    s = ET.parse(f).getroot()
    for k in ("tests", "failures", "errors", "skipped"):
        t[k] += int(s.get(k, 0))
    for c in s.iter("testcase"):
        if c.find("failure") is not None or c.find("error") is not None:
            t["failed"].append(f'{c.get("classname")}.{c.get("name")}')
json.dump(t, open(sys.argv[1], "w"), indent=1)
EOF
