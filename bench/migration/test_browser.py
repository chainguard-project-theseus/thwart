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
       Night
-    <form action="/s" method="post" onsubmit="store()">
+    <form action="/s" data-action="store" method="post">
     <script>
-      function store(){}
+      function store(){} document.addEventListener('click',function(e){});
+    <script src="/js/actions.js">
   <div>""".splitlines()


class ForcedOnlyTest(unittest.TestCase):
    """pass_forced (#863): diffs confined to what Thwart forces, from run-15's compare.txt."""

    def test_handlers_moved_to_data_attributes_and_scripts(self):
        self.assertTrue(bench.forced_only(HANDLER_MOVED))

    def test_hashset_order_is_allowed(self):
        self.assertTrue(bench.forced_only([" <span>", "-  size", "+  blank", " <br>", "-  blank", "+  size"]))

    def test_any_other_change_is_not(self):
        for old, new in (('<a href="/ranking/pdf?mode=">', '<a href="/ranking/pdf?mode">'),
                         ('<h2 id="h2-notes">', '<h2 id="user-content-h2-notes">'),
                         ('<form action="/login" onsubmit="go()">', '<form action="/login" id="f">'),
                         ("  Jay", "  Jay &lt;i&gt;"),
                         ('<input name="id" th:value="*{id}">', '<input name="id">')):
            self.assertFalse(bench.forced_only([" <body>", "-  " + old, "+  " + new]), old)

    def test_text_after_a_script_is_still_compared(self):
        self.assertFalse(bench.forced_only([" <div>", "   <script>", "-  <p>", "+  <p class='x'>"]))

    def test_a_diff_compare_cut_short_is_not(self):
        self.assertFalse(bench.forced_only(HANDLER_MOVED + ["... 12 more diff line(s)"]))

    def test_score_keeps_strict_pass_and_adds_pass_forced(self):
        results = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, results)
        base, run = os.path.join(results, "app", "baseline"), os.path.join(results, "app", "run-1")
        os.makedirs(os.path.join(base, "pages"))
        os.makedirs(os.path.join(run, "check"))
        files = {os.path.join(base, "pages", "index.tsv"): "01.html\t200\t/\tGET /\n02.html\t200\t/b\tGET /b\n",
                 os.path.join(run, "check", "build.exit"): "0", os.path.join(run, "check", "test.exit"): "0",
                 os.path.join(run, "check", "compare.exit"): "1", os.path.join(run, "diff.patch"): "",
                 os.path.join(run, "agent.json"): "{}", os.path.join(base, "build.exit"): "0"}
        for compare, forced in ((["01 differs   GET /", *HANDLER_MOVED, "02 same      GET /b"], True),
                                (["01 differs   GET /", *HANDLER_MOVED, "   <p>", "-x", "+y", "02 same      GET /b"], False),
                                (["01 differs   GET /", "   status: recorded 200, got 500", *HANDLER_MOVED,
                                  "02 same      GET /b"], False)):
            files[os.path.join(run, "check", "compare.txt")] = "\n".join(compare + ["thwart-migrate compare: 2 page(s)"])
            for p, text in files.items():
                with open(p, "w") as f:
                    f.write(text)
            with mock.patch.object(bench, "RESULTS", results), \
                    mock.patch.object(bench, "config", lambda app: {"engine": "thymeleaf"}):
                s = bench.score("app", run)
            self.assertEqual((s["pass"], s["pass_forced"]), (False, forced), compare)


if __name__ == "__main__":
    unittest.main()
