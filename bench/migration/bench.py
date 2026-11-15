#!/usr/bin/env python3
"""Thwart migration benchmark. See README.md.

usage: bench.py [all|image|baseline|migrate|recheck|rescore|report] [--runs N] [--jobs N] [--minutes N]
                [--budget-usd N] [--model M] [APP ...]
"""

import argparse
import collections
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
IMAGE = "thwart-migration-bench"
VOLUME = "thwart-migration-bench-m2"
M2 = "/home/bench/.m2"

# app.json "java" picks the JDK the original app builds and runs with (default 21).
JDKS = {8: "/usr/lib/jvm/java-1.8-openjdk", 21: "/usr/lib/jvm/java-21-openjdk"}
# A line of `thwart-migrate compare` output: page number, verdict, request.
VERDICT = re.compile(r"^(\d+) (same|reordered|differs)\s+(.*)$")

# Dependencies that mean the old view technology is still on the build.
LEGACY_DEPS = re.compile(r"<artifactId>(spring-boot-starter-(thymeleaf|freemarker|mustache|groovy-templates|velocity)"
                         r"|thymeleaf[\w-]*|freemarker|tomcat-embed-jasper|jakarta\.servlet\.jsp\.jstl[\w-]*"
                         r"|jstl|velocity[\w-]*|pebble[\w-]*|jmustache|tiles-[\w-]+)</artifactId>")
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
    # A TLS-intercepting proxy's CA for the image to trust;
    # the file stays empty when BENCH_EXTRA_CA_CERTS is unset.
    with open(os.path.join(ctx, "extra-ca.crt"), "w") as f:
        if os.environ.get("BENCH_EXTRA_CA_CERTS"):
            with open(os.environ["BENCH_EXTRA_CA_CERTS"]) as ca:
                f.write(ca.read())
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


def check(app, app_dir, out_dir, recording=None, browser=False):
    """Build, crawl and test a checkout inside the container. Without a recording the pages are
    recorded into out_dir/pages; with one they are compared against it (out_dir/compare.txt).
    With browser=True it is the browser smoke check instead (out_dir/browser.json; no tests).
    The original and the migrated app build, test and run on the app's JDK (Thwart supports 8+)."""
    cfg = config(app)
    shutil.rmtree(out_dir, ignore_errors=True)
    os.makedirs(out_dir)
    jdk = JDKS[cfg.get("java", 21)]
    env = {k: cfg[k.lower()] for k in ("BUILD", "TEST", "START", "READY", "BASE")}
    env.update(BUILD_JDK=jdk, RUN_JDK=jdk, IGNORE="\n".join(cfg.get("ignore", [])))
    if browser:
        env["BROWSER"] = "1"
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
    check(app, src, os.path.join(out, "browser"), browser=True)
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


SKILL = "thwart:migrate"  # plugins/thwart's skill, as Claude Code names a plugin skill


def invokes_skill(tool, args):
    """A Skill tool call for the Thwart skill, or a tool call that opens the skill's SKILL.md
    directly (an agent that finds /opt/thwart-plugin and reads the file uses the skill too)."""
    if tool == "Skill":
        return str(args.get("skill") or args.get("command") or "").lstrip("/") in (SKILL, "migrate")
    return "thwart-plugin/skills/migrate/SKILL.md" in json.dumps(args)


def skill_use(transcript):
    """Whether the session listed the Thwart skill (init event) and whether the agent invoked it
    (invokes_skill), from claude's stream-json output. Recorded in agent.json by migrate()."""
    listed = invoked = False
    with open(transcript, errors="replace") as f:
        for line in f:
            try:
                ev = json.loads(line)
            except ValueError:
                continue
            if ev.get("type") == "system" and ev.get("subtype") == "init":
                listed = SKILL in (ev.get("skills") or []) or SKILL in (ev.get("slash_commands") or [])
            elif ev.get("type") == "assistant":
                invoked |= any(c.get("type") == "tool_use" and invokes_skill(c.get("name"), c.get("input") or {})
                               for c in ev.get("message", {}).get("content") or [] if isinstance(c, dict))
    return {"skill_listed": listed, "skill_invoked": invoked}


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


def baseline_browser(app):
    """The original app's browser smoke check (results/<app>/baseline/browser/), if it is missing:
    from the baseline checkout, which is cloned again if work/ doesn't have it."""
    out = os.path.join(RESULTS, app, "baseline", "browser")
    if os.path.exists(os.path.join(out, "browser.json")):
        return
    src = os.path.join(WORK, "baseline", app)
    if not os.path.isdir(src):
        checkout(app, src)
    check(app, src, out, browser=True)


# Line and column numbers move when markup moves; nonces differ per response.
BROWSER_NOISE = re.compile(r"(?<=\S):\d+(:\d+)?(?=[)\s]|$)|(?<=nonce-)[\w+/=-]+")


def browser_messages(page):
    """A page's browser findings as normalized one-line messages (kind: text)."""
    msgs = [f"csp: {c['directive']} blocked {c['blocked'] or 'inline'} in {c['source'] or 'attribute'}"
            for c in page.get("csp", [])]
    for kind in ("exceptions", "console"):
        for e in page.get(kind, []):
            text = (e["text"].strip().splitlines() or [""])[0]
            msgs.append(f"{kind[:-1] if kind == 'exceptions' else kind}: {text}" + (f" ({e['url']})" if e.get("url") else ""))
    return [BROWSER_NOISE.sub("", m) for m in msgs]


def browser_report(app, run_dir):
    """What the migrated app's pages log in the browser at load that the original's don't, per
    page (run_dir/browser.txt). Report only: it doesn't change the Pass verdict. None when either
    browser.json is missing."""
    paths = [os.path.join(RESULTS, app, "baseline", "browser", "browser.json"),
             os.path.join(run_dir, "check", "browser", "browser.json")]
    if not all(os.path.exists(p) for p in paths):
        if os.path.exists(os.path.join(run_dir, "browser.txt")):
            os.remove(os.path.join(run_dir, "browser.txt"))  # from an earlier check
        return None
    base, got = ({p["n"]: p for p in json.load(open(path))["pages"]} for path in paths)
    lines, totals = [], {"csp": 0, "exception": 0, "console": 0, "not loaded": 0}
    for n, page in sorted(got.items()):
        before = base.get(n, {})
        new = list((collections.Counter(browser_messages(page)) - collections.Counter(browser_messages(before))).elements())
        if before.get("loaded") and not page.get("loaded"):
            new.insert(0, f"not loaded: {page.get('error', 'no load event')}")
        if new:
            lines.append(f"{n:03d} {page['request']}")
            lines += [f"   {m}" for m in new]
            for m in new:
                totals[m.split(":", 1)[0]] += 1
    s = {"new": sum(totals.values()), "pages": sum(1 for l in lines if not l.startswith(" ")),
         "loaded": sum(1 for p in got.values() if p.get("loaded")), "total": len(got),
         "base_loaded": sum(1 for p in base.values() if p.get("loaded")), **totals}
    head = [f"# Browser smoke check (lib/browser.py): CSP violations, uncaught exceptions and console errors at",
            "# page load that the migrated app has and the original doesn't, per page. Report only.",
            f"# {s['new']} new on {s['pages']} pages ({s['csp']} CSP, {s['exception']} exceptions, {s['console']} console,"
            f" {s['not loaded']} not loaded); {s['loaded']}/{s['total']} pages loaded (original {s['base_loaded']})"]
    with open(os.path.join(run_dir, "browser.txt"), "w") as f:
        f.write("\n".join(head + lines) + "\n")
    return s


# What Thwart forces a migration to change (#863): inline on* handlers and javascript: URLs, which
# a strict CSP blocks, and the data-* attributes, href="#" and <script> elements that replace them.
FORCED_ATTR = re.compile(r' (?:on[a-z]+|data-[\w.:-]+)(?:="[^"]*")?| href="(?:javascript:[^"]*|#)"')


def forced_only(diff):
    """Whether a page's compare diff is only what Thwart forces: the recorded and the new side of
    its hunks are equal once FORCED_ATTR is deleted from tag lines and <script> elements (tag
    and body lines) are dropped. A diff that compare cut short never is."""
    sides = ([], [])
    for line in diff:
        if line.startswith("... "):
            return False
        if not line.startswith(("--- recorded ", "+++ now", "@@")):
            for side, mark in zip(sides, "-+"):
                if line[:1] in (" ", mark):
                    side.append(line[1:])

    def kept(lines):
        out, script = [], None  # script: the indent of the <script> tag whose lines are dropped
        for l in lines:
            indent = len(l) - len(l.lstrip())
            if script is not None and indent > script:
                continue
            script = indent if l.lstrip().startswith("<script") else None
            if script is None:
                out.append(FORCED_ATTR.sub("", l) if l.lstrip().startswith("<") else l)
        return out
    return sorted(kept(sides[0])) == sorted(kept(sides[1]))  # in any order, as compare's reordered


def score(app, run_dir):
    base = load_check(os.path.join(RESULTS, app, "baseline"))
    got = load_check(os.path.join(run_dir, "check"))
    engine = config(app)["engine"]

    # Pages: `thwart-migrate compare` against the baseline recording. A page passes as "same" or
    # "reordered" (siblings in another order, such as validation messages from a HashSet) and only
    # with its recorded status and final URL.
    verdicts, mismatch, request, diffs = {}, [], None, {}  # verdicts and diff lines by page number
    for line in got["compare"].splitlines():
        m = VERDICT.match(line)
        if m:
            request = m.group(3)
            verdicts[int(m.group(1))] = m.group(2)
            diffs[int(m.group(1))] = []
        elif request and line.startswith(("   status: ", "   final URL: ")):
            mismatch.append(f"{request}: {line.strip()}")
        else:
            request = None  # status lines come right after their verdict line
            if diffs:
                diffs[max(diffs)].append(line)
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
    # Tiles definitions left behind count as legacy templates for any engine.
    leftover = {"files": [], "constructs": {}}
    for e in (engine, "tiles"):
        leftover["files"] += got["counts"].get(e, {}).get("files", [])
        leftover["constructs"].update(got["counts"].get(e, {}).get("constructs", {}))
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
        "thwart_templates": got["counts"].get("thwart", {}).get("templates", 0),
        "baseline_templates": base["counts"].get(engine, {}).get("templates", 0),
    }
    rest = (baseline_ok(base) and s["build_ok"] and s["tests_ok"] and not status_mismatch
            and not insecure and not s["legacy_templates"] and not legacy_deps)
    s["pass"] = rest and got["compare_exit"] == 0 and identical + reordered == len(base["pages"])
    # Pass, except that pages may differ in what Thwart forces (#863). Report only, beside Pass.
    s["pass_forced"] = s["pass"] or rest and all(forced_only(diffs[n]) for n, v in verdicts.items() if v == "differs")
    # Report only. None (shown as "-") when the browser check left no browser.json, for example
    # when the app no longer builds: a stale result is never carried over.
    s["browser"] = browser_report(app, run_dir)
    meta = json.load(open(os.path.join(run_dir, "agent.json")))
    transcript = os.path.join(run_dir, "transcript.jsonl")
    if os.path.exists(transcript):  # gitignored, so absent in a fresh clone; agent.json has the totals
        meta.update(agent_usage(transcript))
    s.update(meta)
    with open(os.path.join(run_dir, "score.json"), "w") as f:
        json.dump(s, f, indent=1)
    return s


def migrate(app, k, args):
    cfg = config(app)
    run_dir = os.path.join(RESULTS, app, f"run-{k}")
    app_dir = os.path.join(WORK, "runs", app, f"run-{k}")
    if os.path.exists(run_dir):  # earlier runs are results; add new ones with --first-run
        sys.exit(f"{run_dir} exists; pick unused run numbers with --first-run")
    os.makedirs(run_dir)
    checkout(app, app_dir)
    prompt = open(os.path.join(HERE, "prompt.md")).read().replace("{minutes}", str(args.minutes))
    # The container is the sandbox: its home is empty (no CLAUDE.md, memory, plugins or settings)
    # and it sees only the checkout and the image's Thwart plugin, which lib/run-agent.sh loads.
    start = time.time()
    with open(os.path.join(run_dir, "transcript.jsonl"), "w") as out:
        env = {"PROMPT": prompt, "ANTHROPIC_API_KEY": os.environ["ANTHROPIC_API_KEY"],
               "AGENT_MINUTES": args.minutes, "AGENT_MODEL": args.model, "AGENT_BUDGET_USD": args.budget_usd}
        if os.environ.get("ANTHROPIC_BASE_URL"):
            env["ANTHROPIC_BASE_URL"] = os.environ["ANTHROPIC_BASE_URL"]
        p = docker([(app_dir, "/work/app")], env, ["run-agent"], out=out)
    meta = {"wall_minutes": round((time.time() - start) / 60, 1), "exit": p.returncode,
            "model": args.model, "budget_usd": args.budget_usd, "budget_minutes": args.minutes}
    meta.update(agent_usage(os.path.join(run_dir, "transcript.jsonl")))
    meta.update(skill_use(os.path.join(run_dir, "transcript.jsonl")))
    with open(os.path.join(run_dir, "agent.json"), "w") as f:
        json.dump(meta, f, indent=1)
    finish(app, k)


def finish(app, k):
    """Score a run whose agent has finished: diff.patch, report, check and score.json."""
    cfg = config(app)
    run_dir = os.path.join(RESULTS, app, f"run-{k}")
    app_dir = os.path.join(WORK, "runs", app, f"run-{k}")
    # baseline/ is the agent's own page recording (the skill's `thwart-migrate record ... baseline/`).
    # Reset its index entries to the pinned commit after adding rather than excluding it: an exclude
    # pathspec fails when the agent has gitignored baseline/, and resetting (not `rm --cached`) keeps
    # a baseline/ the app itself tracks out of the diff.
    sh(["git", "-C", app_dir, "add", "-A", "--", ".", ":!**/target/**"])
    sh(["git", "-C", app_dir, "reset", "-q", cfg["commit"], "--", "baseline"])
    with open(os.path.join(run_dir, "diff.patch"), "w") as f:
        subprocess.run(["git", "-C", app_dir, "diff", "--cached", cfg["commit"]], stdout=f, check=True)
    report = os.path.join(app_dir, "MIGRATION-REPORT.md")
    if os.path.exists(report):
        shutil.copy(report, run_dir)

    check(app, app_dir, os.path.join(run_dir, "check"), os.path.join(RESULTS, app, "baseline", "pages"))
    check(app, app_dir, os.path.join(run_dir, "check", "browser"), browser=True)
    s = score(app, run_dir)
    print(f"{app} run-{k}: pass={s['pass']} {summary_line(load_check(os.path.join(run_dir, 'check')))}"
          f" identical={s['pages_identical']}+{s['pages_reordered']}/{s['pages_total']} {s['wall_minutes']}min"
          f" ${s['cost_usd']} skill={'invoked' if s['skill_invoked'] else 'not invoked'}",
          flush=True)


def report():
    rows, per_app = [], {}
    for app in apps([]):
        d = os.path.join(RESULTS, app)
        if not os.path.isdir(d):
            continue
        for run in sorted(os.listdir(d), key=lambda r: [int(x) if x.isdigit() else x for x in re.split(r"(\d+)", r)]):
            p = os.path.join(d, run, "score.json")
            if os.path.exists(p):
                s = json.load(open(p))
                rows.append(s)
                per_app.setdefault(app, []).append(s)
    out = ["| App | Run | Pass | Build | Tests | Pages identical+reordered | Status diffs | Insecure | Legacy left"
           " | Browser (report only) | Skill | Minutes | Cost $ | Output tokens |", "|" + "---|" * 14]
    for s in rows:
        t = s["tests"]
        tests = f"{(t['tests'] or 0) - (t['failures'] or 0) - (t['errors'] or 0)}/{t['tests'] or 0} (base {s['baseline_tests']})"
        legacy = len(s["legacy_templates"]) + len(s["legacy_deps"])
        cost = "-" if s["cost_usd"] is None else f"{s['cost_usd']:.2f}"
        # Runs from #115 were scored by lib/crawl.py (pages.diff, no compare.txt).
        old = not os.path.exists(os.path.join(RESULTS, s["app"], s["run"], "compare.txt"))
        run = s["run"] + (" (crawl.py)" if old else "")
        # Runs before #174 had the guide mounted instead of the plugin: no skill to invoke.
        skill = "-" if "skill_invoked" not in s else "invoked" if s["skill_invoked"] else "no"
        b = s.get("browser")  # runs before #790 had no browser check
        browser = f"{b['new']} new errors on {b['pages']} pages" if b else "-"
        verdict = "yes" if s["pass"] else "forced" if s.get("pass_forced") else "no"
        out.append(f"| {s['app']} | {run} | {verdict} | {'ok' if s['build_ok'] else 'FAIL'}"
                   f" | {tests} | {s['pages_identical']}+{s['pages_reordered']}/{s['pages_total']} | {len(s['status_mismatch'])}"
                   f" | {', '.join(s['insecure']) or '-'} | {legacy} | {browser} | {skill} | {s['wall_minutes']} | {cost}"
                   f" | {s['tokens'].get('output_tokens', '-')} |")
    # Held-out apps ("heldout": true) only measure the guide and tools, and below-floor apps
    # ("belowfloor": true) are older than Thwart supports, so each gets its own table. Apps can
    # have different numbers of runs (quick rounds add one), so counts are out of each app's runs.
    def group_of(app):
        c = config(app)
        return "Below-floor apps" if c.get("belowfloor") else "Held-out apps" if c.get("heldout") else "Development apps"
    for title in ("Development apps", "Held-out apps", "Below-floor apps"):
        group = {a: ss for a, ss in per_app.items() if group_of(a) == title}
        if not group:
            continue
        out += ["", f"**{title}**", "", "| App | Runs | Passed | Build ok | Tests ok | Skill invoked"
                " | Mean pages identical+reordered |", "|---|---|---|---|---|---|---|"]
        def skill_count(ss):  # only runs that had the plugin (#174) could invoke the skill
            had = [s for s in ss if "skill_invoked" in s]
            return f"{sum(s['skill_invoked'] for s in had)}/{len(had)}" if had else "-"
        def passed(ss):  # strict passes, then passes once Thwart-forced diffs are allowed
            return f"{sum(s['pass'] for s in ss)}/{len(ss)} (forced {sum(bool(s.get('pass_forced')) for s in ss)}/{len(ss)})"
        for app, ss in group.items():
            n = len(ss)
            mean = sum((s["pages_identical"] + s["pages_reordered"]) / max(1, s["pages_total"]) for s in ss) / n
            out.append(f"| {app} | {n} | {passed(ss)} | {sum(s['build_ok'] for s in ss)}/{n}"
                       f" | {sum(s['tests_ok'] for s in ss)}/{n} | {skill_count(ss)}"
                       f" | {mean:.0%} |")
        runs = [s for ss in group.values() for s in ss]
        out.append(f"| all | {len(runs)} | {passed(runs)} | | | | |")
    text = "\n".join(out) + "\n"
    with open(os.path.join(RESULTS, "summary.md"), "w") as f:
        f.write(text)
    print(text)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", nargs="?", default="all", choices=["all", "image", "baseline", "migrate", "recheck", "rescore", "report"])
    ap.add_argument("apps", nargs="*")
    ap.add_argument("--runs", type=int, default=1, help="runs per app (quick round 1, milestone 3)")
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
            list(ex.map(baseline_browser, names))  # for a baseline recorded before #790
            for f in [ex.submit(migrate, a, k, args) for a, k in tasks]:
                f.result()
    if args.cmd in ("recheck", "rescore"):
        # recheck: build, crawl and test each migrated checkout again (after a pages.txt change);
        # rescore: score the existing check output again (after a scoring change). Runs scored by
        # the old Python crawler (#115: pages.diff, no compare.txt) are left as they are. recheck
        # also runs the browser smoke check, of the original app first if it has none yet, and
        # skips runs without a work/runs checkout (README: rebuilding one from diff.patch).
        if args.cmd == "recheck":
            with concurrent.futures.ThreadPoolExecutor(args.jobs) as ex:
                list(ex.map(baseline_browser, names))
        for a in names:
            for run in sorted(os.listdir(os.path.join(RESULTS, a))):
                d = os.path.join(RESULTS, a, run)
                if not os.path.exists(os.path.join(d, "agent.json")):
                    continue
                if os.path.exists(os.path.join(d, "pages.diff")):
                    print(f"{a} {run}: old-format run (pages.diff), left as it is")
                    continue
                if args.cmd == "recheck":
                    src = os.path.join(WORK, "runs", a, run)
                    if not os.path.isdir(src):
                        print(f"{a} {run}: no checkout in work/runs, not rechecked")
                        continue
                    check(a, src, os.path.join(d, "check"), os.path.join(RESULTS, a, "baseline", "pages"))
                    check(a, src, os.path.join(d, "check", "browser"), browser=True)
                if os.path.exists(os.path.join(d, "check", "compare.txt")):
                    score(a, d)
                else:
                    print(f"{a} {run}: no compare output, not rescored")
    if args.cmd in ("all", "migrate", "recheck", "rescore", "report"):
        report()


if __name__ == "__main__":
    main()
