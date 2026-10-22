"""Crawl an app's pages in order and save their HTML; normalize() is used to compare them.

usage: crawl.py BASE_URL PAGES_FILE OUT_DIR

PAGES_FILE has one request per line: `GET /path` or `POST /path a=1&b=2`. Blank lines and `#`
comments are ignored. Requests share one cookie jar, so a `POST /login ...` line logs in for the
rest of the crawl. A POST picks up the `_csrf` token from the previous page, follows redirects,
and snapshots the page it lands on. OUT_DIR gets one `NN.html` per request plus `index.json`.
"""

import datetime
import html
import html.parser
import http.client
import http.cookiejar
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param",
        "source", "track", "wbr"}
TOKEN = re.compile(r"(_csrf|csrf|jsessionid)", re.I)
# Values that change from run to run of the same app: random UUIDs, times of day on pages crawled
# today, and java.util.Date.toString() timestamps.
VOLATILE = [
    (re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", re.I), "UUID"),
    (re.compile(r"TODAY[ T]\d\d:\d\d(:\d\d(\.\d+)?)?"), "TODAY TIME"),
    (re.compile(r"\b(Mon|Tue|Wed|Thu|Fri|Sat|Sun) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \d\d"
                r" \d\d:\d\d:\d\d \w+ \d{4}\b"), "DATETIME"),
    (re.compile(r"\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \d{1,2}, \d{4}, \d{1,2}:\d\d:\d\d\s[AP]M"),
     "DATETIME"),
    (re.compile(r"\b\d\d:\d\d:\d{4} \d\d:\d\d:\d\d\b"), "DATETIME"),
]


class Normalizer(html.parser.HTMLParser):
    """Re-serializes HTML one node per line: sorted attributes and class names, collapsed
    whitespace, no comments, masked CSRF tokens and session ids. Self-closing and void tags print
    the same, and end tags that close nothing are dropped, as browsers do."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.lines = []
        self.open = []
        self.text = []

    def emit(self, s):
        # Text arrives in pieces (an unescaped "<" that starts no tag splits it); print it whole.
        text = " ".join("".join(self.text).split())
        self.text = []
        if text:
            self.lines.append("  " * len(self.open) + html.escape(text, quote=False))
        if s:
            self.lines.append("  " * len(self.open) + s)

    def handle_decl(self, decl):
        self.emit("<!" + " ".join(decl.split()).lower() + ">")

    def handle_starttag(self, tag, attrs, closed=False):
        named = {}
        for k, v in attrs:
            v = " ".join((v or "").split())
            if k == "class":
                v = " ".join(sorted(v.split()))
            named[k] = re.sub(r";jsessionid=[^?#\"]*", "", v, flags=re.I)
        if TOKEN.search(named.get("name", "")) or TOKEN.search(named.get("id", "")):
            named["value"] = named.get("value") and "MASKED"
            if "content" in named:
                named["content"] = "MASKED"
        parts = [tag] + [f'{k}="{html.escape(named[k], quote=True)}"' for k in sorted(named)]
        self.emit("<" + " ".join(parts) + ">")
        if tag not in VOID and not closed:
            self.open.append(tag)

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs, closed=True)
        if tag not in VOID:
            self.emit(f"</{tag}>")

    def handle_endtag(self, tag):
        if tag not in self.open:
            return
        self.emit("")
        while self.open.pop() != tag:
            pass
        self.emit(f"</{tag}>")

    def handle_data(self, data):
        self.text.append(data)


def normalize(body):
    for pattern, mask in VOLATILE:
        body = pattern.sub(mask, body)
    n = Normalizer()
    n.feed(body)
    n.close()
    n.emit("")
    return "\n".join(n.lines) + "\n"


def last_csrf(body):
    m = re.search(r'name="_csrf"[^>]*value="([^"]*)"', body) or \
        re.search(r'value="([^"]*)"[^>]*name="_csrf"', body)
    return html.unescape(m.group(1)) if m else None


def main(base, pages_file, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    index, prev = [], ""
    lines = [l.strip() for l in open(pages_file) if l.strip() and not l.lstrip().startswith("#")]
    for i, line in enumerate(lines, 1):
        method, path, *rest = line.split(None, 2)
        data = None
        if method == "POST":
            form = urllib.parse.parse_qsl(rest[0] if rest else "", keep_blank_values=True)
            token = last_csrf(prev)
            if token and not any(k == "_csrf" for k, _ in form):
                form.append(("_csrf", token))
            data = urllib.parse.urlencode(form).encode()
        req = urllib.request.Request(base + path, data=data, method=method,
                                     headers={"Accept": "text/html", "Accept-Language": "en"})
        try:
            resp = opener.open(req, timeout=60)
            status, body, final = resp.status, resp.read().decode("utf-8", "replace"), resp.url
        except urllib.error.HTTPError as e:
            try:
                body = e.read().decode("utf-8", "replace")
            except http.client.IncompleteRead as cut:  # the app failed mid-render
                body = cut.partial.decode("utf-8", "replace")
            status, final = e.code, e.url
        except Exception as e:  # connection reset, timeout: record and go on
            status, body, final = 0, f"<!-- {type(e).__name__}: {e} -->", base + path
        prev = body
        name = f"{i:02d}.html"
        with open(os.path.join(out_dir, name), "w") as f:
            # Pages that default a date to today must not differ between days.
            f.write(body.replace(datetime.date.today().isoformat(), "TODAY"))
        index.append({"file": name, "request": line, "status": status,
                      "final": final[len(base):] if final.startswith(base) else final})
        print(f"{status} {line}", flush=True)
    with open(os.path.join(out_dir, "index.json"), "w") as f:
        json.dump(index, f, indent=1)


if __name__ == "__main__":
    main(*sys.argv[1:4])
