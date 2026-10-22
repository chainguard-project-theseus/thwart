#!/usr/bin/env python3
"""Tessera migration benchmark. See README.md.

usage: bench.py [all|image|baseline|migrate|recheck|rescore|report] [--runs N] [--jobs N] [--minutes N]
                [--budget-usd N] [--model M] [APP ...]
"""

import argparse
import concurrent.futures
import json
import os
import re
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
WORK = os.path.join(HERE, "work")
RESULTS = os.path.join(HERE, "results")
IMAGE = "tessera-migration-bench"
VOLUME = "tessera-migration-bench-m2"
M2 = "/home/bench/.m2"

# app.json "java" picks the JDK the original app builds and runs with (default 21).
JDKS = {8: "/usr/lib/jvm/java-1.8-openjdk", 21: "/usr/lib/jvm/java-21-openjdk"}
# A line of `tessera-migrate compare` output: page number, verdict, request.
VERDICT = re.compile(r"^(\d+) (same|reordered|differs)\s+(.*)$")

# Dependencies that mean the old view technology is still on the build.
LEGACY_DEPS = re.compile(r"<artifactId>(spring-boot-starter-(thymeleaf|freemarker|mustache|groovy-templates)"
                         r"|thymeleaf[\w-]*|freemarker|tomcat-embed-jasper|jakarta\.servlet\.jsp\.jstl[\w-]*"
                         r"|jstl|velocity[\w-]*|pebble[\w-]*|jmustache)</artifactId>")
# Added lines that weaken security; each hit is reported.
INSECURE = re.compile(r"SafeHtml\.unchecked|TrustedUrl\.unchecked|permitAll|csrf\s*\(\s*\w*\s*->\s*\w+\.disable"
                      r"|csrf\(\)\.disable|AbstractHttpConfigurer::disable|@Disabled(?![A-Za-z])|@Ignore\b")


def sh(cmd, **kw):
    print("+", " ".join(cmd) if isinstance(cmd, list) else cmd, flush=True)
    return subprocess.run(cmd, check=True, **kw)


def apps(names):
    all_apps = sorted(d for d in os.listdir(os.path.join(HERE, "apps"))
                      if os.path.isfile(os.path.join(HERE, "apps", d, "app.json")))
    for n in names or all_apps:
        if n not in all_apps:
            sys.exit(f"unknown app {n}; have {all_apps}")
    return names or all_apps


def config(app):
    with open(os.path.join(HERE, "apps", app, "app.json")) as f:
        return json.load(f)


def image():
    """Build the image from a clean `git archive` of HEAD, then seed the Maven volume."""
    ctx = os.path.join(WORK, "ctx")
    shutil.rmtree(ctx, ignore_errors=True)
    os.makedirs(ctx)
    sh(f"git -C {REPO} archive HEAD | tar -x -C {ctx}", shell=True)
    sh(["docker", "build", "-t", IMAGE, "-f", os.path.join(HERE, "Dockerfile"), ctx])
    sh(["docker", "run", "--rm", "-v", f"{VOLUME}:{M2}", IMAGE, "bash", "-c",
        f"mkdir -p {M2}/repository && cp -R /opt/m2-seed/. {M2}/repository/"])


def checkout(app, dest):
    """A clean checkout of the app at its pinned commit."""
    cfg = config(app)
    cache = os.path.join(WORK, "src", app)
    if not os.path.isdir(cache):
        sh(["git", "clone", "-q", cfg["repo"], cache])
    sh(["git", "-C", cache, "fetch", "-q", "origin", cfg["commit"]])
    shutil.rmtree(dest, ignore_errors=True)
    sh(["git", "clone", "-q", "--no-checkout", cache, dest])
    sh(["git", "-C", dest, "checkout", "-q", "-B", "bench", cfg["commit"]])


def docker(mounts, env, cmd, out=None, memory="4g"):
    args = ["docker", "run", "--rm", "--init", f"--memory={memory}", "-w", "/work/app", "-v", f"{VOLUME}:{M2}"]
    for host, ctr in mounts:
        args += ["-v", f"{host}:{ctr}"]
    for k, v in env.items():
        args += ["-e", f"{k}={v}"]
    return subprocess.run(args + [IMAGE] + cmd, stdout=out, stderr=subprocess.STDOUT if out else None)


def check(app, app_dir, out_dir, recording=None, migrated=False):
    """Build, crawl and test a checkout inside the container. Without a recording the pages are
    recorded into out_dir/pages; with one they are compared against it (out_dir/compare.txt).
    A migrated app builds and tests with JDK 21 (Tessera's build needs it) and runs on the app's JDK."""
    cfg = config(app)
    shutil.rmtree(out_dir, ignore_errors=True)
    os.makedirs(out_dir)
    jdk = JDKS[cfg.get("java", 21)]
    env = {k: cfg[k.lower()] for k in ("BUILD", "TEST", "START", "READY", "BASE")}
    env.update(BUILD_JDK=JDKS[21] if migrated else jdk, RUN_JDK=jdk, IGNORE="\n".join(cfg.get("ignore", [])))
    # /bench/app is the app's fixture directory (pages.txt, seed data, helper scripts) for its commands.
    mounts = [(app_dir, "/work/app"), (os.path.join(HERE, "lib"), "/bench/lib:ro"),
              (os.path.join(HERE, "apps", app), "/bench/app:ro"),
              (os.path.join(HERE, "apps", app, "pages.txt"), "/bench/pages.txt:ro"), (out_dir, "/work/out")]
    cmd = ["bash", "/bench/lib/check.sh", "/work/app", "/bench/pages.txt", "/work/out"]
    if recording:
        mounts.append((recording, "/bench/recording:ro"))
        cmd.append("/bench/recording")
    with open(os.path.join(out_dir, "check.log"), "w") as log:
        docker(mounts, env, cmd, out=log)


def baseline(app):
    """Check the original app, recording its pages; then check it again, comparing against that
    recording, to show the pages are deterministic (results/<app>/baseline/determinism.txt)."""
    src = os.path.join(WORK, "baseline", app)
    checkout(app, src)
    out = os.path.join(RESULTS, app, "baseline")
    check(app, src, out)
    again = os.path.join(WORK, "determinism", app)
    check(app, src, again, recording=os.path.join(out, "pages"))
    compare = read_text(os.path.join(again, "compare.txt"))
    with open(os.path.join(out, "determinism.txt"), "w") as f:
        f.write(compare)
    last = compare.strip().splitlines()[-1:] or ["no compare output"]
    c = load_check(out)
    broken = "" if baseline_ok(c) and c["record_exit"] == 0 else " BROKEN (see baseline/record.log)"
    print(f"{app}: baseline {summary_line(c)}{broken}; again: {last[0]}", flush=True)


def baseline_ok(c):
    """A recording to score against: the original app built and answered every request."""
    return c["build_exit"] == 0 and bool(c["pages"]) and all(p["status"] for p in c["pages"])


def read_text(p, default=""):
    if not os.path.exists(p):
        return default
    with open(p, errors="replace") as f:
        return f.read()


def load_check(d):
    def read(name, default=None):
        p = os.path.join(d, name)
        if not os.path.exists(p):
            return default
        with open(p) as f:
            return json.load(f) if name.endswith(".json") else f.read().strip()
    pages = []  # index.tsv of a recording: file, status, final URL, request
    for line in read_text(os.path.join(d, "pages", "index.tsv")).splitlines():
        if line and not line.startswith("#"):
            f = line.split("\t", 3)
            pages.append({"file": f[0], "status": int(f[1]), "final": f[2], "request": f[3]})
    return {"build_exit": int(read("build.exit", "1") or 1), "test_exit": int(read("test.exit", "1") or 1),
            "tests": read("tests.json", {}), "counts": read("counts.json", {}), "pages": pages,
            "record_exit": int(read("record.exit", "1") or 1), "compare_exit": int(read("compare.exit", "1") or 1), "compare": read_text(os.path.join(d, "compare.txt"))}


def summary_line(c):
    t = c["tests"]
    ok = sum(1 for p in c["pages"] if 200 <= p["status"] < 400)
    return (f"build={c['build_exit']} tests={t.get('tests', 0) - t.get('failures', 0) - t.get('errors', 0)}"
            f"/{t.get('tests', 0)} pages={ok}/{len(c['pages'])}")


def agent_usage(transcript):
    """Cost, tokens and outcome from claude's stream-json output."""
    per_message, result = {}, None
    with open(transcript, errors="replace") as f:
        for line in f:
            try:
                ev = json.loads(line)
            except ValueError:
                continue
            if ev.get("type") == "result":
                result = ev
            elif ev.get("type") == "assistant":
                msg = ev.get("message", {})
                per_message[msg.get("id")] = msg.get("usage") or {}  # the last event has the totals
    usage = {}
    for u in per_message.values():
        for k, v in u.items():
            if isinstance(v, int):
                usage[k] = usage.get(k, 0) + v
    if result and result.get("modelUsage"):  # cumulative over the session, unlike result["usage"]
        usage = {}
        for m in result["modelUsage"].values():
            for k, key in (("input_tokens", "inputTokens"), ("output_tokens", "outputTokens"),
                           ("cache_read_input_tokens", "cacheReadInputTokens"),
                           ("cache_creation_input_tokens", "cacheCreationInputTokens")):
                usage[k] = usage.get(k, 0) + m.get(key, 0)
    return {"outcome": result.get("subtype") if result else "killed (time budget)",
            "cost_usd": result.get("total_cost_usd") if result else None,
            "turns": result.get("num_turns") if result else None,
            "tokens": usage}


def score(app, run_dir):
    base = load_check(os.path.join(RESULTS, app, "baseline"))
    got = load_check(os.path.join(run_dir, "check"))
    engine = config(app)["engine"]

    # Pages: `tessera-migrate compare` against the baseline recording. A page passes as "same" or
    # "reordered" (siblings in another order, such as validation messages from a HashSet) and only
    # with its recorded status and final URL.
    verdicts, mismatch, request = {}, [], None  # verdicts by page number
    for line in got["compare"].splitlines():
        m = VERDICT.match(line)
        if m:
            request = m.group(3)
            verdicts[int(m.group(1))] = m.group(2)
        elif request and line.startswith(("   status: ", "   final URL: ")):
            mismatch.append(f"{request}: {line.strip()}")
        else:
            request = None  # status lines come right after their verdict line
    identical = sum(1 for v in verdicts.values() if v == "same")
    reordered = sum(1 for v in verdicts.values() if v == "reordered")
    status_mismatch = mismatch + [f"{p['request']}: not compared" for i, p in enumerate(base["pages"], 1)
                                  if i not in verdicts]
    with open(os.path.join(run_dir, "compare.txt"), "w") as f:
        f.write(got["compare"])

    # The agent's change, and what it says about security and leftovers.
    added, prose = [], False
    for line in open(os.path.join(run_dir, "diff.patch"), errors="replace").read().splitlines():
        if line.startswith("+++ "):
            prose = line.endswith((".md", ".txt", ".adoc"))  # reports may name what they avoided
        elif line.startswith("+") and not prose:
            added.append(line[1:])
    insecure = sorted({m.group(0) for l in added for m in INSECURE.finditer(l)})
    pom = ""
    for d, _, files in os.walk(os.path.join(run_dir, "app")):
        if "/target" not in d and "pom.xml" in files:
            pom += open(os.path.join(d, "pom.xml")).read()
    legacy_deps = sorted(set(m.group(1) for m in LEGACY_DEPS.finditer(pom)))
    leftover = got["counts"].get(engine, {})
    bt, gt = base["tests"], got["tests"]
    s = {
        "app": app, "run": os.path.basename(run_dir),
        "build_ok": got["build_exit"] == 0,
        "tests_ok": got["test_exit"] == 0 and gt.get("tests", 0) >= bt.get("tests", 0)
                    and gt.get("failures", 0) + gt.get("errors", 0) == 0,
        "tests": {k: gt.get(k) for k in ("tests", "failures", "errors", "skipped")},
        "tests_failed": gt.get("failed", []),
        "baseline_tests": bt.get("tests", 0),
        "pages_total": len(base["pages"]), "pages_identical": identical, "pages_reordered": reordered,
        "status_mismatch": status_mismatch,
        "insecure": insecure,
        "legacy_templates": leftover.get("files", []), "legacy_constructs": leftover.get("constructs", {}),
        "legacy_deps": legacy_deps,
        "tessera_templates": got["counts"].get("tessera", {}).get("templates", 0),
        "baseline_templates": base["counts"].get(engine, {}).get("templates", 0),
    }
    s["pass"] = (baseline_ok(base) and s["build_ok"] and s["tests_ok"] and got["compare_exit"] == 0
                 and identical + reordered == len(base["pages"]) and not status_mismatch
                 and not insecure and not s["legacy_templates"] and not legacy_deps)
    meta = json.load(open(os.path.join(run_dir, "agent.json")))
    meta.update(agent_usage(os.path.join(run_dir, "transcript.jsonl")))
    s.update(meta)
    with open(os.path.join(run_dir, "score.json"), "w") as f:
        json.dump(s, f, indent=1)
    return s


def migrate(app, k, args):
    cfg = config(app)
    run_dir = os.path.join(RESULTS, app, f"run-{k}")
    app_dir = os.path.join(WORK, "runs", app, f"run-{k}")
    shutil.rmtree(run_dir, ignore_errors=True)
    os.makedirs(run_dir)
    checkout(app, app_dir)
    prompt = open(os.path.join(HERE, "prompt.md")).read().replace("{minutes}", str(args.minutes))
    # The container is the sandbox: its home is empty (no CLAUDE.md, memory, plugins or settings)
    # and it sees only the checkout and the guide. No web access and no multi-agent workflows.
    claude = ("timeout --kill-after=30s {m}m claude -p \"$PROMPT\" --dangerously-skip-permissions"
              " --model {model} --max-budget-usd {usd} --output-format stream-json --verbose"
              " --disallowedTools WebFetch WebSearch Workflow").format(m=args.minutes, model=args.model,
                                                                       usd=args.budget_usd)
    start = time.time()
    with open(os.path.join(run_dir, "transcript.jsonl"), "w") as out:
        p = docker([(app_dir, "/work/app"), (os.path.join(REPO, "docs", "guide.md"), "/work/guide.md:ro")],
                   {"PROMPT": prompt, "ANTHROPIC_API_KEY": os.environ["ANTHROPIC_API_KEY"]},
                   ["bash", "-c", claude], out=out)
    meta = {"wall_minutes": round((time.time() - start) / 60, 1), "exit": p.returncode,
            "model": args.model, "budget_usd": args.budget_usd, "budget_minutes": args.minutes}
    meta.update(agent_usage(os.path.join(run_dir, "transcript.jsonl")))
    with open(os.path.join(run_dir, "agent.json"), "w") as f:
        json.dump(meta, f, indent=1)

    sh(["git", "-C", app_dir, "add", "-A", "--", ".", ":!**/target/**"])
    with open(os.path.join(run_dir, "diff.patch"), "w") as f:
        subprocess.run(["git", "-C", app_dir, "diff", "--cached", cfg["commit"]], stdout=f, check=True)
    report = os.path.join(app_dir, "MIGRATION-REPORT.md")
    if os.path.exists(report):
        shutil.copy(report, run_dir)

    check(app, app_dir, os.path.join(run_dir, "check"), os.path.join(RESULTS, app, "baseline", "pages"), migrated=True)
    s = score(app, run_dir)
    print(f"{app} run-{k}: pass={s['pass']} {summary_line(load_check(os.path.join(run_dir, 'check')))}"
          f" identical={s['pages_identical']}+{s['pages_reordered']}/{s['pages_total']} {s['wall_minutes']}min"
          f" ${s['cost_usd']}",
          flush=True)


def report():
    rows, per_app = [], {}
    for app in apps([]):
        d = os.path.join(RESULTS, app)
        if not os.path.isdir(d):
            continue
        for run in sorted(os.listdir(d)):
            p = os.path.join(d, run, "score.json")
            if os.path.exists(p):
                s = json.load(open(p))
                rows.append(s)
                per_app.setdefault(app, []).append(s)
    out = ["| App | Run | Pass | Build | Tests | Pages identical+reordered | Status diffs | Insecure | Legacy left"
           " | Minutes | Cost $ | Output tokens |", "|" + "---|" * 12]
    for s in rows:
        t = s["tests"]
        tests = f"{(t['tests'] or 0) - (t['failures'] or 0) - (t['errors'] or 0)}/{t['tests'] or 0} (base {s['baseline_tests']})"
        legacy = len(s["legacy_templates"]) + len(s["legacy_deps"])
        cost = "-" if s["cost_usd"] is None else f"{s['cost_usd']:.2f}"
        # Runs from #115 were scored by lib/crawl.py (pages.diff, no compare.txt).
        old = not os.path.exists(os.path.join(RESULTS, s["app"], s["run"], "compare.txt"))
        run = s["run"] + (" (crawl.py)" if old else "")
        out.append(f"| {s['app']} | {run} | {'yes' if s['pass'] else 'no'} | {'ok' if s['build_ok'] else 'FAIL'}"
                   f" | {tests} | {s['pages_identical']}+{s['pages_reordered']}/{s['pages_total']} | {len(s['status_mismatch'])}"
                   f" | {', '.join(s['insecure']) or '-'} | {legacy} | {s['wall_minutes']} | {cost}"
                   f" | {s['tokens'].get('output_tokens', '-')} |")
    # Held-out apps ("heldout": true) only measure the guide and tools, so they get their own table.
    for title, heldout in (("Development apps", False), ("Held-out apps", True)):
        group = {a: ss for a, ss in per_app.items() if bool(config(a).get("heldout")) == heldout}
        if not group:
            continue
        out += ["", f"**{title}**", "", "| App | Runs | Passed | Build ok | Tests ok | Mean pages identical+reordered |",
                "|---|---|---|---|---|---|"]
        for app, ss in group.items():
            n = len(ss)
            mean = sum((s["pages_identical"] + s["pages_reordered"]) / max(1, s["pages_total"]) for s in ss) / n
            out.append(f"| {app} | {n} | {sum(s['pass'] for s in ss)} | {sum(s['build_ok'] for s in ss)}"
                       f" | {sum(s['tests_ok'] for s in ss)} | {mean:.0%} |")
    text = "\n".join(out) + "\n"
    with open(os.path.join(RESULTS, "summary.md"), "w") as f:
        f.write(text)
    print(text)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", nargs="?", default="all", choices=["all", "image", "baseline", "migrate", "recheck", "rescore", "report"])
    ap.add_argument("apps", nargs="*")
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--first-run", type=int, default=1, help="number of the first run (to add runs)")
    ap.add_argument("--jobs", type=int, default=2)
    ap.add_argument("--minutes", type=int, default=90)
    ap.add_argument("--budget-usd", type=float, default=40)
    ap.add_argument("--model", default="opus")
    args = ap.parse_args()
    names = apps(args.apps)
    if args.cmd in ("all", "image"):
        image()
    if args.cmd in ("all", "baseline"):
        with concurrent.futures.ThreadPoolExecutor(args.jobs) as ex:
            list(ex.map(baseline, names))
    if args.cmd in ("all", "migrate"):
        if "ANTHROPIC_API_KEY" not in os.environ:
            sys.exit("ANTHROPIC_API_KEY is required")
        tasks = [(a, k) for k in range(args.first_run, args.first_run + args.runs) for a in names]
        with concurrent.futures.ThreadPoolExecutor(args.jobs) as ex:
            for f in [ex.submit(migrate, a, k, args) for a, k in tasks]:
                f.result()
    if args.cmd in ("recheck", "rescore"):
        # recheck: build, crawl and test each migrated checkout again (after a pages.txt change);
        # rescore: score the existing check output again (after a scoring change). Runs scored by
        # the old Python crawler (#115: pages.diff, no compare.txt) are left as they are.
        for a in names:
            for run in sorted(os.listdir(os.path.join(RESULTS, a))):
                d = os.path.join(RESULTS, a, run)
                if not os.path.exists(os.path.join(d, "agent.json")):
                    continue
                if args.cmd == "recheck":
                    check(a, os.path.join(WORK, "runs", a, run), os.path.join(d, "check"),
                          os.path.join(RESULTS, a, "baseline", "pages"), migrated=True)
                if os.path.exists(os.path.join(d, "check", "compare.txt")):
                    score(a, d)
                else:
                    print(f"{a} {run}: no compare output (old-format run), not rescored")
    if args.cmd in ("all", "migrate", "recheck", "rescore", "report"):
        report()


if __name__ == "__main__":
    main()
