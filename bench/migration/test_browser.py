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


if __name__ == "__main__":
    unittest.main()
