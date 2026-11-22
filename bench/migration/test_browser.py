#!/usr/bin/env python3
"""Tests for the browser smoke check (#790): lib/browser.py against a stub app, and bench.py's
diffing, scoring and recheck wiring. Run: python3 -m unittest bench/migration/test_browser.py
(the end-to-end test needs Chromium and websocket-client, as in the bench image, and is skipped
without them)."""

import contextlib
import http.server
import importlib.util
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
# By path: run from the repo root, the name `bench` is the bench/ directory.
_spec = importlib.util.spec_from_file_location("bench_py", os.path.join(HERE, "bench.py"))
bench = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bench)

CSP = "script-src 'nonce-abc' 'strict-dynamic'; object-src 'none'; base-uri 'none'"


class Stub(http.server.BaseHTTPRequestHandler):
    """/a: an inline script without the nonce (blocked), a nonce script that throws, a
    console.error. POST: echoes the form it got in the redirect's query. /plain: no errors."""

    def log_message(self, *args):
        pass

    def do_GET(self):
        body = "<p>plain</p>"
        if self.path == "/a":
            body = ('<form><input name=_csrf value=tok1></form><script>var blocked = 1</script>'
                    '<script nonce=abc>console.error("boom"); document.getElementById("none").x()</script>')
        self.send_response(200)
        self.send_header("Content-Type", "text/html")
        self.send_header("Content-Security-Policy", CSP)
        self.end_headers()
        self.wfile.write(f"<!doctype html><html><body>{body}</body></html>".encode())

    def do_POST(self):
        form = self.rfile.read(int(self.headers["Content-Length"])).decode()
        self.send_response(303)
        self.send_header("Location", "/plain?" + form.replace("&", "_"))
        self.end_headers()


def browser_available():
    return (any(os.path.exists(p) for p in ("/usr/bin/chromium", "/usr/bin/chromium-browser"))
            and importlib.util.find_spec("websocket") is not None)


@unittest.skipUnless(browser_available(), "needs Chromium and websocket-client")
class BrowserPyTest(unittest.TestCase):
    def test_records_csp_exceptions_console_and_replays_post_with_csrf(self):
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Stub)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.shutdown)
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d)
        pages = os.path.join(d, "pages.txt")
        with open(pages, "w") as f:
            f.write("# comment\nGET /a\nPOST /form a=1&b=x+y\n")
        out = os.path.join(d, "browser.json")
        subprocess.run([sys.executable, os.path.join(HERE, "lib", "browser.py"),
                        f"http://127.0.0.1:{server.server_port}", pages, out], check=True, capture_output=True)
        a, post = json.load(open(out))["pages"]
        self.assertTrue(a["loaded"])
        self.assertEqual([c["directive"] for c in a["csp"]], ["script-src-elem"])
        self.assertEqual(len(a["exceptions"]), 1)
        self.assertIn("TypeError", a["exceptions"][0]["text"])
        self.assertEqual([c["text"] for c in a["console"]], ["boom"])
        self.assertEqual((post["status"], post["final"]), (200, "/plain?a=1_b=x+y__csrf=tok1"))
        self.assertEqual(post["csp"] + post["exceptions"] + post["console"], [])


def page(n, loaded=True, csp=(), exceptions=(), console=()):
    return {"n": n, "request": f"GET /{n}", "loaded": loaded, "csp": list(csp),
            "exceptions": list(exceptions), "console": list(console)}


class BrowserReportTest(unittest.TestCase):
    def setUp(self):
        self.results = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.results)
        patcher = mock.patch.object(bench, "RESULTS", self.results)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.run_dir = os.path.join(self.results, "app", "run-1")
        os.makedirs(os.path.join(self.run_dir, "check", "browser"))
        os.makedirs(os.path.join(self.results, "app", "baseline", "browser"))

    def write(self, where, pages):
        d = os.path.join(self.results, "app", "baseline") if where == "base" else os.path.join(self.run_dir, "check")
        with open(os.path.join(d, "browser", "browser.json"), "w") as f:
            json.dump({"pages": pages}, f)

    def test_messages_drop_line_numbers_and_nonces(self):
        p = page(1, exceptions=[{"text": "TypeError: x\n    at http://h:8080/a.js:12:7", "url": "http://h:8080/a.js:3"}],
                 console=[{"text": "policy 'nonce-Zm9v+/=' failed at app.js:4:2", "url": ""}])
        self.assertEqual(bench.browser_messages(p), ["exception: TypeError: x (http://h:8080/a.js)",
                                                     "console: policy 'nonce-' failed at app.js"])

    def test_reports_only_what_is_new_per_page(self):
        err = {"text": "ReferenceError: $ is not defined", "url": "http://h/a.js"}
        self.write("base", [page(1, exceptions=[err]), page(2), page(3)])
        self.write("run", [page(1, exceptions=[err, err]), page(2, csp=[{"directive": "script-src", "blocked": "eval",
                                                                       "source": "http://h/r.js"}]),
                           page(3, loaded=False)])
        s = bench.browser_report("app", self.run_dir)
        self.assertEqual((s["new"], s["pages"], s["exception"], s["csp"], s["not loaded"]), (3, 3, 1, 1, 1))
        text = open(os.path.join(self.run_dir, "browser.txt")).read()
        self.assertIn("001 GET /1\n   exception: ReferenceError: $ is not defined (http://h/a.js)\n", text)
        self.assertIn("002 GET /2\n   csp: script-src blocked eval in http://h/r.js\n", text)
        self.assertIn("003 GET /3\n   not loaded: no load event\n", text)

    def test_missing_browser_json_gives_none_and_drops_stale_report(self):
        self.write("base", [page(1)])
        with open(os.path.join(self.run_dir, "browser.txt"), "w") as f:
            f.write("old\n")
        self.assertIsNone(bench.browser_report("app", self.run_dir))
        self.assertFalse(os.path.exists(os.path.join(self.run_dir, "browser.txt")))


class RecheckTest(unittest.TestCase):
    def test_runs_baseline_browser_and_skips_runs_without_checkout(self):
        results, work = tempfile.mkdtemp(), tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, results)
        self.addCleanup(shutil.rmtree, work)
        for run in ("run-1", "run-2"):
            os.makedirs(os.path.join(results, "luckyblog", run))
            with open(os.path.join(results, "luckyblog", run, "agent.json"), "w") as f:
                f.write("{}")
        os.makedirs(os.path.join(work, "runs", "luckyblog", "run-2"))
        os.makedirs(os.path.join(work, "baseline", "luckyblog"))
        calls = []
        with mock.patch.object(bench, "RESULTS", results), mock.patch.object(bench, "WORK", work), \
                mock.patch.object(bench, "check", lambda app, src, out, recording=None, browser=False:
                                  calls.append((os.path.relpath(src, work), os.path.relpath(out, results), browser))), \
                mock.patch.object(sys, "argv", ["bench.py", "recheck", "luckyblog"]), \
                contextlib.redirect_stdout(io.StringIO()) as out:
            bench.main()
        self.assertEqual(calls, [
            ("baseline/luckyblog", "luckyblog/baseline/browser", True),
            ("runs/luckyblog/run-2", "luckyblog/run-2/check", False),
            ("runs/luckyblog/run-2", "luckyblog/run-2/check/browser", True)])
        self.assertIn("luckyblog run-1: no checkout in work/runs, not rechecked", out.getvalue())


HANDLER_MOVED = """--- recorded 01.html
+++ now
@@ -3,7 +3,9 @@
   <body>
-    <a href="javascript:void(0)" onclick="day_night()">
+    <a data-action="day-night" href="#">
       Night""".splitlines()


class PassForcedTest(unittest.TestCase):
    """pass_forced (#863): every page same, reordered or compare's forced."""

    def test_score_needs_no_new_browser_errors_and_adds_pass_forced(self):
        results = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, results)
        base, run = os.path.join(results, "app", "baseline"), os.path.join(results, "app", "run-1")
        os.makedirs(os.path.join(base, "pages"))
        os.makedirs(os.path.join(base, "browser"))
        os.makedirs(os.path.join(run, "check", "browser"))
        files = {os.path.join(base, "pages", "index.tsv"): "01.html\t200\t/\tGET /\n02.html\t200\t/b\tGET /b\n",
                 os.path.join(run, "check", "build.exit"): "0", os.path.join(run, "check", "test.exit"): "0",
                 os.path.join(run, "diff.patch"): "", os.path.join(run, "agent.json"): "{}",
                 os.path.join(base, "build.exit"): "0",
                 os.path.join(base, "browser", "browser.json"): json.dumps({"pages": [page(1), page(2)]})}
        same, forced = ["01 same      GET /"], ["01 forced    GET /", *HANDLER_MOVED]
        differs = ["01 differs   GET /", *HANDLER_MOVED]
        # run-15's jprime and fredbet: a third-party script that needs eval, blocked by the CSP (#866).
        clean, evil = [page(1), page(2)], [page(1, csp=[{"directive": "script-src", "blocked": "eval", "source": "http://h/r.js"}]), page(2)]
        summary = "thwart-migrate compare: 2 page(s): 1 same, 0 reordered, 1 forced, 0 differs"
        # A compare.txt from before the forced verdict keeps the pass_forced score.json had.
        archived = "thwart-migrate compare: 2 page(s): 1 same, 0 reordered, 1 differ"
        for compare, browser, verdict, last, before in (
                (same, clean, (True, True), summary, None), (same, evil, (False, False), summary, None),
                (same, None, (False, False), summary, None),
                (forced, clean, (False, True), summary, None), (forced, evil, (False, False), summary, None),
                (forced, None, (False, False), summary, None),
                (differs, clean, (False, False), summary, True),
                (["01 differs   GET /", "   status: recorded 200, got 500", *HANDLER_MOVED], clean, (False, False),
                 summary, None),
                (differs, clean, (False, True), archived, True), (differs, clean, (False, False), archived, False),
                (differs, evil, (False, False), archived, True), (differs, clean, (False, False), archived, None)):
            files[os.path.join(run, "check", "compare.txt")] = "\n".join(compare + ["02 same      GET /b", last])
            files[os.path.join(run, "check", "compare.exit")] = "1" if compare is not same else "0"
            for p, text in files.items():
                with open(p, "w") as f:
                    f.write(text)
            if os.path.exists(os.path.join(run, "score.json")):
                os.remove(os.path.join(run, "score.json"))
            if before is not None:
                with open(os.path.join(run, "score.json"), "w") as f:
                    json.dump({"pass_forced": before}, f)
            browser_json = os.path.join(run, "check", "browser", "browser.json")
            if browser is None:
                os.remove(browser_json)
            else:
                with open(browser_json, "w") as f:
                    json.dump({"pages": browser}, f)
            with mock.patch.object(bench, "RESULTS", results), \
                    mock.patch.object(bench, "config", lambda app: {"engine": "thymeleaf"}):
                s = bench.score("app", run)
            self.assertEqual((s["pass"], s["pass_forced"]), verdict, (compare, browser, last, before))
        # The last case is archived: a rescore that fails another condition (the browser here)
        # keeps the old rule's verdict on the pages for the next rescore.
        with open(os.path.join(run, "score.json"), "w") as f:
            json.dump({"pass_forced": True}, f)
        for browser, want in ((evil, False), (clean, True)):
            with open(os.path.join(run, "check", "browser", "browser.json"), "w") as f:
                json.dump({"pages": browser}, f)
            with mock.patch.object(bench, "RESULTS", results), \
                    mock.patch.object(bench, "config", lambda app: {"engine": "thymeleaf"}):
                self.assertEqual(bench.score("app", run)["pass_forced"], want, browser)

    def test_report_shows_strict_and_forced_verdicts(self):
        results = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, results)
        # run-1 is an old pages.diff run: rescore leaves it without a pass_forced key.
        for run, ok, forced in (("run-1", True, None), ("run-2", False, True), ("run-3", False, False)):
            os.makedirs(os.path.join(results, "app", run))
            with open(os.path.join(results, "app", run, "score.json"), "w") as f:
                score = {"app": "app", "run": run, "pass": ok, "pass_forced": forced, "build_ok": True,
                         "tests_ok": True, "tests": {"tests": 1, "failures": 0, "errors": 0}, "baseline_tests": 1,
                         "legacy_templates": [], "legacy_deps": [], "cost_usd": None, "pages_identical": 1,
                         "pages_reordered": 0, "pages_total": 1, "status_mismatch": [], "insecure": [],
                         "wall_minutes": 1, "tokens": {}}
                if forced is None:
                    del score["pass_forced"]
                json.dump(score, f)
        with mock.patch.object(bench, "RESULTS", results), mock.patch.object(bench, "apps", lambda a: ["app"]), \
                mock.patch.object(bench, "config", lambda app: {}), contextlib.redirect_stdout(io.StringIO()):
            bench.report()
        with open(os.path.join(results, "summary.md")) as f:
            rows = [l.split(" | ")[:3] for l in f.read().splitlines() if l.startswith("| app |") or l.startswith("| all |")]
        self.assertEqual(rows, [["| app", "run-1 (crawl.py)", "yes"], ["| app", "run-2 (crawl.py)", "forced"],
                                ["| app", "run-3 (crawl.py)", "no"], ["| app", "3", "1/3 (forced 2/3)"],
                                ["| all", "3", "1/3 (forced 2/3)"]])


if __name__ == "__main__":
    unittest.main()
