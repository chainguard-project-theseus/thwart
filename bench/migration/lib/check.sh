#!/bin/bash
# Build, crawl and test one app checkout. Runs inside the bench container.
#
# usage: check.sh APP_DIR PAGES_FILE OUT_DIR
# env:   BUILD, TEST, START (shell commands run in APP_DIR), READY (path polled until the app
#        answers), BASE (URL prefix of the crawl, e.g. http://localhost:8080)
#
# OUT_DIR gets counts.json, build.log/build.exit, app.log, pages/, test.log/test.exit, tests.json.
set -u
app=$1 pages=$2 out=$3
lib=$(dirname "$0")
mkdir -p "$out"
cd "$app" || exit 1

python3 "$lib/count.py" . > "$out/counts.json"

bash -c "$BUILD" > "$out/build.log" 2>&1
echo $? > "$out/build.exit"

if [ "$(cat "$out/build.exit")" = 0 ]; then
  bash -c "$START" > "$out/app.log" 2>&1 &
  for _ in $(seq 1 180); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE$READY")
    [ "$code" != 000 ] && break
    sleep 2
  done
  python3 "$lib/crawl.py" "$BASE" "$pages" "$out/pages" > "$out/crawl.log" 2>&1
  pkill -f java; sleep 3; pkill -9 -f java
fi

bash -c "$TEST" > "$out/test.log" 2>&1
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
