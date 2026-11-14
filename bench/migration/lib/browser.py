#!/usr/bin/env python3
"""Load-time browser smoke check. Runs inside the bench container, against a running app.

usage: browser.py BASE PAGES_FILE OUT_JSON

Replays pages.txt in order in one headless Chromium tab (one cookie jar, like `thwart-migrate
record`): a GET navigates to the path; a POST submits a form built from the line's fields, plus the
current page's `_csrf` hidden input unless the line sets one, from the current page. For each
request it records, through the load event plus a short settle, the page's CSP violations
(`securitypolicyviolation`), uncaught exceptions and console errors (console.error and the
browser's own error log: failed loads and the like). Nothing is clicked: only load-time behaviour
is checked. bench.py compares two such files (original and migrated app) and keeps what is new.
Talks to Chromium over the DevTools protocol (websocket-client); no CSP is bypassed.
"""

import json
import os
import subprocess
import sys
import time
import urllib.parse
import urllib.request

import websocket

SETTLE_S = 1.5  # after the load event, for listeners, timers and fetches that run at load
LOAD_TIMEOUT_S = 30
PORT = 9222

# Injected before any page script, in the page's world, through the DevTools protocol (which the
# page's CSP does not govern). The binding hands each violation to this script.
LISTEN = """document.addEventListener('securitypolicyviolation', function (e) {
  __benchCsp(JSON.stringify({directive: e.effectiveDirective, blocked: e.blockedURI,
    source: e.sourceFile, line: e.lineNumber, sample: e.sample}));
}, true);"""


class Tab:
    def __init__(self, ws_url):
        self.ws = websocket.create_connection(ws_url, suppress_origin=True, timeout=LOAD_TIMEOUT_S)
        self.next_id = 0
        self.events = []

    def call(self, method, **params):
        self.next_id += 1
        self.ws.send(json.dumps({"id": self.next_id, "method": method, "params": params}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == self.next_id:
                if "error" in msg:
                    raise RuntimeError(f"{method}: {msg['error']}")
                return msg.get("result", {})
            if "method" in msg:
                self.events.append(msg)

    def pump(self, seconds, until=None):
        """Collect events for up to `seconds`, or until an event (already collected or new)
        satisfies `until`."""
        if until and any(until(e) for e in self.events):
            return True
        end = time.time() + seconds
        while time.time() < end:
            self.ws.settimeout(max(0.05, end - time.time()))
            try:
                msg = json.loads(self.ws.recv())
            except websocket.WebSocketTimeoutException:
                break
            if "method" in msg:
                self.events.append(msg)
                if until and until(msg):
                    return True
        return False


def start_chromium():
    exe = next(p for p in ("/usr/bin/chromium", "/usr/bin/chromium-browser") if os.path.exists(p))
    # --no-sandbox: the bench container has no user namespaces for Chromium's sandbox.
    # --ignore-certificate-errors: Chromium reads its own NSS store, not the image's CA bundle,
    # so behind a TLS-intercepting proxy (BENCH_EXTRA_CA_CERTS) every CDN script would fail to
    # load in both apps alike. The pages are test data; nothing secret is sent.
    proc = subprocess.Popen([exe, "--headless=new", "--no-sandbox", "--ignore-certificate-errors",
                             "--disable-gpu", "--disable-dev-shm-usage",
                             "--no-first-run", "--disable-background-networking", "--disable-component-update",
                             "--disable-sync", "--lang=en", f"--remote-debugging-port={PORT}",
                             "--user-data-dir=/tmp/bench-chromium", "about:blank"],
                            stdout=subprocess.DEVNULL, stderr=open("/tmp/bench-chromium.log", "w"))
    for _ in range(100):
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json/list") as r:
                pages = [t for t in json.load(r) if t["type"] == "page"]
            if pages:
                return proc, pages[0]["webSocketDebuggerUrl"]
        except OSError:
            pass
        time.sleep(0.2)
    sys.exit("chromium did not start; see /tmp/bench-chromium.log")


def read_pages(path):
    out = []
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#"):
            parts = line.split(None, 2)
            out.append((line, parts[0], parts[1], parts[2] if len(parts) > 2 else ""))
    return out


def form_pairs(form):
    pairs = []
    for pair in form.split("&") if form else []:
        name, _, value = pair.partition("=")
        pairs.append([urllib.parse.unquote_plus(name), urllib.parse.unquote_plus(value)])
    return pairs


# Builds and submits the POST from the current page; `_csrf` comes from that page, as in record.
SUBMIT = """(function (action, pairs) {
  var f = document.createElement('form');
  f.method = 'POST'; f.action = action; f.enctype = 'application/x-www-form-urlencoded';
  var token = document.querySelector('input[name=_csrf]');
  if (token && !pairs.some(function (p) { return p[0] === '_csrf'; })) pairs.push(['_csrf', token.value]);
  pairs.forEach(function (p) {
    var i = document.createElement('input'); i.type = 'hidden'; i.name = p[0]; i.value = p[1]; f.appendChild(i);
  });
  (document.body || document.documentElement).appendChild(f);
  HTMLFormElement.prototype.submit.call(f);
})(%s, %s)"""


def text_of(args):
    return " ".join(str(a.get("value", a.get("description", a.get("type", "")))) for a in args)


def main():
    base, pages_file, out_file = sys.argv[1].rstrip("/"), sys.argv[2], sys.argv[3]
    proc, ws_url = start_chromium()
    tab = Tab(ws_url)
    for domain in ("Page", "Runtime", "Log", "Network"):
        tab.call(f"{domain}.enable")
    tab.call("Network.setExtraHTTPHeaders", headers={"Accept-Language": "en"})
    # Every page fetches its scripts and styles itself: what a cached copy hides would otherwise
    # depend on the cache's timing (fredbet's first-login redirect also catches static files).
    tab.call("Network.setCacheDisabled", cacheDisabled=True)
    tab.call("Runtime.addBinding", name="__benchCsp")
    tab.call("Page.addScriptToEvaluateOnNewDocument", source=LISTEN)
    main_frame = tab.call("Page.getFrameTree")["frameTree"]["frame"]["id"]
    results = []
    for n, (line, method, path, form) in enumerate(read_pages(pages_file), 1):
        tab.events = []
        url = base + path
        try:
            if method == "GET":
                nav = tab.call("Page.navigate", url=url)
                if nav.get("errorText"):  # a download or a refused connection: no page to check
                    results.append({"n": n, "request": line, "loaded": False, "error": nav["errorText"]})
                    continue
            else:
                here = tab.call("Runtime.evaluate", expression="location.href")["result"].get("value", "")
                if not here.startswith(base):  # a POST first, or after a page elsewhere
                    tab.call("Page.navigate", url=base + "/")
                    tab.pump(LOAD_TIMEOUT_S, lambda m: m["method"] == "Page.loadEventFired")
                    tab.events = []
                tab.call("Runtime.evaluate", expression=SUBMIT % (json.dumps(url), json.dumps(form_pairs(form))))
            loaded = tab.pump(LOAD_TIMEOUT_S, lambda m: m["method"] == "Page.loadEventFired")
            tab.pump(SETTLE_S)
        except (RuntimeError, OSError, websocket.WebSocketException) as e:
            results.append({"n": n, "request": line, "loaded": False, "error": str(e)})
            continue
        csp, exceptions, console, status, final = [], [], [], None, None
        for ev in tab.events:
            m, p = ev["method"], ev.get("params", {})
            if m == "Runtime.bindingCalled" and p.get("name") == "__benchCsp":
                csp.append(json.loads(p["payload"]))
            elif m == "Runtime.exceptionThrown":
                d = p["exceptionDetails"]
                exceptions.append({"text": (d.get("exception") or {}).get("description") or d.get("text", ""),
                                   "url": d.get("url", ""), "line": d.get("lineNumber")})
            elif m == "Runtime.consoleAPICalled" and p.get("type") in ("error", "assert"):
                console.append({"text": text_of(p.get("args", [])), "url": "", "line": None})
            elif m == "Log.entryAdded" and p["entry"].get("level") == "error":
                e = p["entry"]
                # The browser logs each CSP violation too; the event above already has it.
                if "Content Security Policy" not in e.get("text", ""):
                    console.append({"text": e.get("text", ""), "url": e.get("url", ""), "line": e.get("lineNumber")})
            elif m == "Network.responseReceived" and p.get("type") == "Document" and p.get("frameId") == main_frame:
                status, final = p["response"]["status"], p["response"]["url"]
        results.append({"n": n, "request": line, "loaded": loaded, "status": status,
                        "final": final[len(base):] if final and final.startswith(base) else final,
                        "csp": csp, "exceptions": exceptions, "console": console})
    proc.terminate()
    with open(out_file, "w") as f:
        json.dump({"pages": results}, f, indent=1)
    print(f"browser: {sum(r['loaded'] for r in results)}/{len(results)} pages loaded")


if __name__ == "__main__":
    main()
