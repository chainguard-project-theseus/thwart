#!/usr/bin/env python3
"""Helpers of the consumer benchmark (README.md). Python 3 standard library only.

usage: consumers.py replay BASE PAGES OUT_DIR       replay pages.txt; first-request times, cookie
       consumers.py probe BASE PAGES COOKIE OUT     status of each GET path, no redirects followed
       consumers.py select PAGES ORIG THWART DIR    the pages to time and the mix (pages.tsv, mixed.txt)
       consumers.py oha JSON PREFIX STATUS          oha's JSON as samples.tsv rows
       consumers.py gc LOG FROM                     live heap and bytes allocated in a GC log window
       consumers.py report OUT                      OUT/report.md from OUT/*/samples.tsv
       consumers.py selftest                        planted inputs through every pure step
"""

import html.parser
import http.client
import http.cookiejar
import json
import os
import re
import statistics
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

# GET paths never timed or mixed in: they end the session or change the data.
DESTRUCTIVE = re.compile(r"logout|signout|delete|remove", re.I)
HEADERS = {"Accept": "text/html", "Accept-Language": "en"}


def read_pages(path):
    """pages.txt as (method, path, form) tuples, as thwart-migrate's Crawler reads it."""
    out = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split(None, 2)
            out.append((parts[0], parts[1], parts[2] if len(parts) > 2 else ""))
    return out


def get_paths(pages):
    return [p for m, p, _ in pages if m == "GET" and not DESTRUCTIVE.search(p)]


# ------------------------------------------------------------------ replay and probe

class _Csrf(html.parser.HTMLParser):
    def __init__(self):
        super().__init__()
        self.token = None

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "input" and a.get("name") == "_csrf" and self.token is None:
            self.token = a.get("value") or ""


def csrf(body):
    p = _Csrf()
    p.feed(body)
    return p.token


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def _quote(path):
    return urllib.parse.quote(path, safe="%/?#[]@!$&'()*+,;=:-._~")


def _send(opener, url, data=None):
    req = urllib.request.Request(url, data=data, headers=HEADERS)
    try:
        with opener.open(req, timeout=60) as r:
            return r.status, r.geturl(), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, url, e.read().decode("utf-8", "replace")
    except (urllib.error.URLError, OSError, http.client.HTTPException):
        return 0, url, ""


def replay(base, pages_file, out_dir):
    """Sends pages.txt in order, as `thwart-migrate record` does (one cookie jar, redirects
    followed, each POST with the previous page's _csrf), so the data and the session match the
    recording. Writes first.tsv (each GET path's first request: status and ms, redirects
    included) and cookie.txt (the Cookie header for the load generator)."""
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    seen, body = set(), ""
    with open(os.path.join(out_dir, "first.tsv"), "w") as first:
        for method, path, form in read_pages(pages_file):
            data = None
            if method == "POST":
                pairs = urllib.parse.parse_qsl(form, keep_blank_values=True)
                token = csrf(body)
                if token is not None and not any(k == "_csrf" for k, _ in pairs):
                    pairs.append(("_csrf", token))
                data = urllib.parse.urlencode(pairs).encode()
            t0 = time.perf_counter()
            status, _, body = _send(opener, base + _quote(path), data)
            ms = (time.perf_counter() - t0) * 1000
            if method == "GET" and path not in seen:
                seen.add(path)
                first.write(f"{path}\t{status}\t{ms:.3f}\n")
    with open(os.path.join(out_dir, "cookie.txt"), "w") as f:
        f.write("; ".join(f"{c.name}={c.value}" for c in jar))


def probe(base, pages_file, cookie_file, out):
    """The status of each distinct GET path, sent once with the session cookie and no redirect
    followed, as the load generator sends it."""
    cookie = open(cookie_file).read().strip()
    opener = urllib.request.build_opener(_NoRedirect)
    with open(out, "w") as f:
        for path in dict.fromkeys(get_paths(read_pages(pages_file))):
            req = urllib.request.Request(base + _quote(path), headers=dict(HEADERS, Cookie=cookie))
            try:
                with opener.open(req, timeout=60) as r:
                    status = r.status
            except urllib.error.HTTPError as e:
                status = e.code
            except (urllib.error.URLError, OSError, http.client.HTTPException):
                status = 0
            f.write(f"{path}\t{status}\n")


def read_status(path):
    with open(path) as f:
        return dict(line.rstrip("\n").split("\t")[:2] for line in f if line.strip())


def select(pages_file, orig_file, thwart_file, out_dir):
    """OUT_DIR/pages.tsv: each distinct GET path with its status on both versions and whether
    it is timed. One page per path pattern (each run of digits counts as one), so /owners/1 and
    /owners/2 are timed once; never a redirect, a failed request, or a status that differs.
    OUT_DIR/mixed.txt: the mix, every GET line of pages.txt whose path has the same status on
    both versions (redirects too), but logout and delete links and failed requests."""
    orig, thwart = read_status(orig_file), read_status(thwart_file)
    seen, patterns, same = set(), set(), set()
    with open(os.path.join(out_dir, "pages.tsv"), "w") as f:
        f.write("path\torig\tthwart\ttimed\n")
        for m, path, _ in read_pages(pages_file):
            if m != "GET" or path in seen:
                continue
            seen.add(path)
            key = re.sub(r"\d+", "N", path)
            a, b = orig.get(path, "-"), thwart.get(path, "-")
            if DESTRUCTIVE.search(path):
                why = "no: logout or delete"
            elif a != b:
                why = "no: status differs"
            elif a in ("0", "-"):
                why = "no: no response"
            else:
                same.add(path)
                if a.startswith("3"):
                    why = "no: redirect"
                elif key in patterns:
                    why = "no: same pattern as an earlier timed page"
                else:
                    why = "yes"
                    patterns.add(key)
            f.write(f"{path}\t{a}\t{b}\t{why}\n")
    with open(os.path.join(out_dir, "mixed.txt"), "w") as f:
        f.writelines(p + "\n" for m, p, _ in read_pages(pages_file) if m == "GET" and p in same)


# ------------------------------------------------------------------ load generator and GC log

def oha_rows(json_file, prefix, status, case):
    """samples.tsv rows from one oha run: req/s, p50/p90/p99 in ms, and responses whose status
    is not STATUS (the one probed before timing) plus connection errors."""
    with open(json_file) as f:
        d = json.load(f)
    lat = d["latencyPercentiles"]
    codes = d.get("statusCodeDistribution") or {}
    total = sum(codes.values())
    bad = sum(n for c, n in codes.items() if status != "*" and c != status)
    bad += sum((d.get("errorDistribution") or {}).values())
    rows = [("rps", d["summary"]["requestsPerSec"])]
    rows += [(f"{p}_ms", (lat.get(p) or 0) * 1000) for p in ("p50", "p90", "p99")]
    rows += [("bad_responses", bad), ("requests", total)]
    return [f"{prefix}{m}\t{case}\t{v:.6g}" for m, v in rows]


SIZE = re.compile(r"(\d+(?:\.\d+)?)([BKMG])B?->(\d+(?:\.\d+)?)([BKMG])B?\(\d+(?:\.\d+)?[BKMG]B?\)")
UNIT = {"B": 1, "K": 1024, "M": 1024 ** 2, "G": 1024 ** 3}


def gc_window(lines):
    """(live, allocated) in bytes over GC log lines that start at a forced GC (and may end at
    another): live is the heap after the first, allocated sums, for each later collection, its
    heap before minus the previous one's heap after (None with no later collection). Both JDK 8's
    -Xloggc and JDK 9+'s -Xlog:gc print each collection as BEFORE->AFTER(CAPACITY)."""
    sizes = []
    for line in lines:
        m = SIZE.search(line)
        if m:
            sizes.append((float(m.group(1)) * UNIT[m.group(2)], float(m.group(3)) * UNIT[m.group(4)]))
    if not sizes:
        return None, None
    if len(sizes) < 2:
        return sizes[0][1], None
    alloc = sum(max(0.0, sizes[i][0] - sizes[i - 1][1]) for i in range(1, len(sizes)))
    return sizes[0][1], alloc


# ------------------------------------------------------------------ statistics

def median(v):
    return statistics.median(v)


def bootstrap(a, b, draws=2000):
    """95% percentile bootstrap interval on median(b) / median(a): resamples each side's
    per-repetition samples, with a Park-Miller generator and a fixed seed, so the same samples
    always give the same interval. None with fewer than two samples a side, or a zero median."""
    if len(a) < 2 or len(b) < 2:
        return None
    seed = 12345

    def draw(n):
        nonlocal seed
        seed = (seed * 16807) % 2147483647
        return int(seed / 2147483647 * n)

    r = []
    for _ in range(draws):
        x = [a[draw(len(a))] for _ in a]
        y = [b[draw(len(b))] for _ in b]
        mx = median(x)
        if mx == 0:
            return None
        r.append(median(y) / mx)
    r.sort()
    return r[int(0.025 * len(r))], r[int(0.975 * len(r)) - 1]


def floor2(x):
    return int(x * 100) / 100


def ceil2(x):
    c = int(x * 100)
    return (c + 1 if x * 100 - c > 1e-9 else c) / 100


def ratio_text(a, b):
    """'1.74x (1.70-1.79)': median(b) / median(a), truncated, and its interval rounded outward."""
    if not a or not b or median(a) == 0:
        return "-"
    r = median(b) / median(a)
    ci = bootstrap(a, b)
    if ci is None:
        return f"{floor2(r):.2f}x"
    return f"{floor2(r):.2f}x ({floor2(ci[0]):.2f}-{ceil2(ci[1]):.2f})"


def num(v):
    if v is None:
        return "-"
    if abs(v) >= 100:
        return f"{v:,.0f}"
    if abs(v) >= 10:
        return f"{v:.1f}"
    return f"{v:.2f}"


# ------------------------------------------------------------------ report

def read_kv(path):
    d = {}
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            if ":" in line:
                k, v = line.split(":", 1)
                d[k.strip()] = v.strip()
    return d


def read_samples(path):
    """{(metric, case): {"orig": [...], "thwart": [...]}} from samples.tsv
    (version, rep, metric, case, value)."""
    s = {}
    if not os.path.exists(path):
        return s
    for line in open(path):
        f = line.rstrip("\n").split("\t")
        if len(f) != 5 or f[0] not in ("orig", "thwart"):
            continue
        try:
            v = float(f[4])
        except ValueError:
            continue
        s.setdefault((f[2], f[3]), {"orig": [], "thwart": []})[f[0]].append(v)
    return s


def cell(s, metric, case):
    v = s.get((metric, case))
    if not v:
        return "-"
    a, b = v["orig"], v["thwart"]
    return f"{num(median(a)) if a else '-'} / {num(median(b)) if b else '-'}: {ratio_text(a, b)}"


def row(s, label, metric, case, unit):
    v = s.get((metric, case))
    if not v:
        return None
    a, b = v["orig"], v["thwart"]
    return (f"| {label} | {unit} | {num(median(a)) if a else '-'} | {num(median(b)) if b else '-'} "
            f"| {ratio_text(a, b)} | {len(a)}, {len(b)} |")


def app_section(app, d):
    meta = read_kv(os.path.join(d, "meta.txt"))
    s = read_samples(os.path.join(d, "samples.tsv"))
    out = [f"## {app}", ""]
    for k in ("engine", "run", "jdk", "spring orig", "spring thwart", "artifact orig", "artifact thwart"):
        if k in meta:
            out.append(f"- {k}: {meta[k]}")
    out += ["", "Each cell: median of the original / median of Thwart: Thwart / original (95% interval).",
            ""]
    head = ["| figure | unit | original | Thwart | Thwart / original (95%) | samples |", "|---|---|---:|---:|---|---|"]
    single = [("clean build", "build_s", "-", "s"), ("artifact size", "artifact_mb", "-", "MB"),
              ("cold start to ready", "cold_start_ms", "-", "ms"),
              ("live heap after warm-up", "heap_live_mb", "-", "MB"),
              ("RSS after warm-up", "rss_mb", "-", "MB"), ("peak RSS", "rss_peak_mb", "-", "MB"),
              ("allocated per request, mixed", "alloc_kb", "mixed", "KB")]
    rows = [r for r in (row(s, *x) for x in single) if r]
    if rows:
        out += ["### Build, start and memory", ""] + head + rows + [""]
    levels = sorted({int(c.rsplit("@c", 1)[1]) for m, c in s if m == "rps" and "@c" in c})
    mixed = [f"| {c} | {cell(s, 'rps', f'mixed @c{c}')} | {cell(s, 'p50_ms', f'mixed @c{c}')} "
             f"| {cell(s, 'p90_ms', f'mixed @c{c}')} | {cell(s, 'p99_ms', f'mixed @c{c}')} |" for c in levels
             if (("rps", f"mixed @c{c}") in s)]
    tbl = "|---|---|---|---|---|"
    if mixed:
        out += ["### Mixed traffic (GET lines of pages.txt with the same status on both versions)", "",
                "| concurrency | req/s | p50 ms | p90 ms | p99 ms |", tbl] + mixed + [""]
    pages = list(dict.fromkeys(c.rsplit(" @c", 1)[0] for m, c in s if m == "rps" and not c.startswith("mixed")))
    if pages:
        out += ["### Pages", "", "| page | concurrency | req/s | p50 ms | p90 ms | p99 ms |", "|---" + tbl]
        for p in pages:
            for c in levels:
                k = f"{p} @c{c}"
                if ("rps", k) in s:
                    out.append(f"| `{p}` | {c} | {cell(s, 'rps', k)} | {cell(s, 'p50_ms', k)} "
                               f"| {cell(s, 'p90_ms', k)} | {cell(s, 'p99_ms', k)} |")
        out += ["", "| page | first request ms | allocated per request KB |", "|---|---|---|"]
        for p in pages:
            out.append(f"| `{p}` | {cell(s, 'first_ms', p)} | {cell(s, 'alloc_kb', p)} |")
        out.append("")
    bad = sorted({c for (m, c), v in s.items() if m == "bad_responses" and sum(v["orig"] + v["thwart"]) > 0})
    if bad:
        out += ["Responses with another status than the probe's, or failed, during: " +
                ", ".join(f"`{c}`" for c in bad), ""]
    return out


def report(root):
    m = read_kv(os.path.join(root, "machine.txt"))
    out = ["# Consumer benchmark", ""]
    if m.get("reportable") != "yes":
        out += ["**Local validation run: these numbers are not reportable.** They come from a shared "
                "machine and only show that the harness works.", ""]
    out += ["Each app's original version against its Thwart-migrated version, on the same machine in "
            "the same session, in alternating order. Ratios are Thwart over original: above 1 means "
            "more requests/s, or more milliseconds, megabytes or seconds. The interval is a 95% "
            "percentile bootstrap of the ratio of medians (2000 draws, fixed seed).", "",
            "## Machine and settings", ""]
    out += [f"- {k}: {v}" for k, v in m.items() if k != "reportable"] + [""]
    apps = sorted(a for a in os.listdir(root) if os.path.isfile(os.path.join(root, a, "status.txt")))
    out += ["## Apps", "", "| app | engine | Thwart version | status |", "|---|---|---|---|"]
    status = {}
    for a in apps:
        meta = read_kv(os.path.join(root, a, "meta.txt"))
        status[a] = open(os.path.join(root, a, "status.txt")).read().strip()
        out.append(f"| {a} | {meta.get('engine', '-')} | {meta.get('run', '-')} | {status[a]} |")
    out.append("")
    # Only apps that ran to the end: a failed one's samples are partial.
    done = [a for a in apps if status[a] == "benchmarked"]
    if done:
        out += ["## Summary", "", "Thwart / original (95% interval) per app; mixed traffic at the highest concurrency.", "",
                "| app | mixed req/s | mixed p99 | cold start | live heap | allocated per request | clean build | artifact |",
                "|---|---|---|---|---|---|---|---|"]
        for a in done:
            s = read_samples(os.path.join(root, a, "samples.tsv"))
            top = max((int(c.rsplit("@c", 1)[1]) for m, c in s if m == "rps" and c.startswith("mixed @c")), default=0)

            def r(metric, case):
                v = s.get((metric, case))
                return ratio_text(v["orig"], v["thwart"]) if v else "-"
            out.append(f"| {a} | {r('rps', f'mixed @c{top}')} | {r('p99_ms', f'mixed @c{top}')} | {r('cold_start_ms', '-')} "
                       f"| {r('heap_live_mb', '-')} | {r('alloc_kb', 'mixed')} | {r('build_s', '-')} | {r('artifact_mb', '-')} |")
        out.append("")
    for a in done:
        out += app_section(a, os.path.join(root, a))
    text = "\n".join(out) + "\n"
    with open(os.path.join(root, "report.md"), "w") as f:
        f.write(text)
    return text


# ------------------------------------------------------------------ selftest

def selftest():
    def check(cond, what):
        if not cond:
            sys.exit(f"selftest: {what}")
        print(f"ok: {what}")

    # The interval: identical samples give one value; a known spread gives a fixed interval.
    check(ratio_text([1.0, 1.0, 1.0], [1.013] * 3) == "1.01x (1.01-1.02)", "an upper bound is rounded up")
    check(ratio_text([1.0, 1.0, 1.0], [1.01] * 3) == "1.01x (1.01-1.01)", "a whole hundredth stays")
    check(ratio_text([1.0], [2.0]) == "2.00x", "one sample a side: no interval")
    a, b = [100, 101, 99, 100, 102], [200, 202, 198, 201, 199]
    t = ratio_text(a, b)
    check(t == ratio_text(a, b) and t.startswith("2.00x (1.9"), f"a 2x ratio with a narrow interval: {t}")
    lo, hi = bootstrap([1, 1.3, 0.8, 1.1, 0.9], [0.95, 1.25, 0.8, 1.05, 0.9])
    check(lo < 1 < hi, f"a noisy pair's interval spans 1 ({lo:.3f}-{hi:.3f})")

    # The GC log, in both formats.
    j8 = ["[Full GC (System.gc())  23M->20M(1024M), 0.0911 secs]",
          "[GC pause (G1 Evacuation Pause) (young) 61M->7843K(1024M), 0.0101 secs]",
          "[GC concurrent-root-region-scan-start]",
          "[Full GC (System.gc())  30M->21M(1024M), 0.0800 secs]"]
    live, alloc = gc_window(j8)
    check(live == 20 * 2 ** 20 and alloc == (61 - 20) * 2 ** 20 + 30 * 2 ** 20 - 7843 * 1024,
          "JDK 8 GC log: live heap and allocation")
    j21 = ["[1.0s][info][gc] GC(4) Pause Full (System.gc()) 50M->12M(1024M) 40.1ms",
           "[1.5s][info][gc] GC(5) Pause Young (Normal) (G1 Evacuation Pause) 112M->14M(1024M) 2.0ms",
           "[1.6s][info][gc] GC(6) Concurrent Mark Cycle",
           "[2.0s][info][gc] GC(7) Pause Full (System.gc()) 64M->13M(1024M) 41.0ms"]
    live, alloc = gc_window(j21)
    check(live == 12 * 2 ** 20 and alloc == 150 * 2 ** 20, "JDK 21 GC log: live heap and allocation")
    check(gc_window(j21[:1]) == (12 * 2 ** 20, None), "one collection: the live heap, no allocation")

    tmp = tempfile.mkdtemp()

    # Page selection.
    p = os.path.join(tmp, "pages.txt")
    with open(p, "w") as f:
        f.write("GET /\nGET /owners/1\nGET /owners/2\nPOST /owners/new a=1\nGET /owners/3/edit\n"
                "GET /logout\nGET /gone\nGET /moved\nGET /oups\n")
    o, w = os.path.join(tmp, "o.tsv"), os.path.join(tmp, "t.tsv")
    with open(o, "w") as f:
        f.write("/\t200\n/owners/1\t200\n/owners/2\t200\n/owners/3/edit\t200\n/gone\t404\n/moved\t302\n/oups\t500\n")
    with open(w, "w") as f:
        f.write("/\t200\n/owners/1\t302\n/owners/2\t200\n/owners/3/edit\t200\n/gone\t500\n/moved\t302\n/oups\t500\n")
    sel = os.path.join(tmp, "sel")
    os.makedirs(sel)
    select(p, o, w, sel)
    got = {line.split("\t")[0]: line.rstrip("\n").split("\t")[3] for line in open(os.path.join(sel, "pages.tsv"))}
    check([k for k, v in got.items() if v == "yes"] == ["/", "/owners/2", "/owners/3/edit", "/oups"],
          "the timed pages: one per pattern, errors kept, redirects and differing statuses left out")
    check(got["/gone"] == "no: status differs" and got["/logout"].startswith("no: logout")
          and got["/owners/1"] == "no: status differs", "each page left out says why")
    check(open(os.path.join(sel, "mixed.txt")).read() == "/\n/owners/2\n/owners/3/edit\n/moved\n/oups\n",
          "the mix: lines whose status matches, redirects too")

    # oha's JSON.
    j = os.path.join(tmp, "oha.json")
    with open(j, "w") as f:
        json.dump({"summary": {"requestsPerSec": 1234.5},
                   "latencyPercentiles": {"p50": 0.002, "p90": 0.004, "p99": 0.01},
                   "statusCodeDistribution": {"200": 900, "500": 3}, "errorDistribution": {"timeout": 2}}, f)
    rows = oha_rows(j, "thwart\t1\t", "200", "/x @c8")
    check(rows[0] == "thwart\t1\trps\t/x @c8\t1234.5" and rows[2] == "thwart\t1\tp90_ms\t/x @c8\t4"
          and rows[4] == "thwart\t1\tbad_responses\t/x @c8\t5", "oha JSON to samples rows")

    # The report, from planted samples of two apps and one app not benchmarked.
    root = os.path.join(tmp, "out")
    for app in ("alpha", "beta", "gamma", "delta"):
        os.makedirs(os.path.join(root, app))
    with open(os.path.join(root, "machine.txt"), "w") as f:
        f.write("cpu: planted\nreportable: no\n")
    for app, k in (("alpha", 2.0), ("beta", 0.5), ("delta", 1.0)):
        with open(os.path.join(root, app, "status.txt"), "w") as f:
            f.write("not benchmarked: the thwart version failed to build in repetition 3\n" if app == "delta"
                    else "benchmarked\n")
        with open(os.path.join(root, app, "meta.txt"), "w") as f:
            f.write("engine: thymeleaf\nrun: run-3 (pass)\n")
        with open(os.path.join(root, app, "samples.tsv"), "w") as f:
            f.write("version\trep\tmetric\tcase\tvalue\n")
            for rep, x in enumerate([100, 101, 99, 100, 102], 1):
                for v, f_ in (("orig", 1), ("thwart", k)):
                    f.write(f"{v}\t{rep}\trps\t/ @c8\t{x * f_}\n{v}\t{rep}\tp50_ms\t/ @c8\t{x / 50 / f_}\n"
                            f"{v}\t{rep}\trps\tmixed @c8\t{x * f_}\n{v}\t{rep}\tbuild_s\t-\t{x / 4}\n"
                            f"{v}\t{rep}\tfirst_ms\t/\t{x}\n{v}\t{rep}\tbad_responses\t/ @c8\t0\n"
                            f"{v}\t{rep}\trss_mb\t-\t\n")
    with open(os.path.join(root, "gamma", "status.txt"), "w") as f:
        f.write("not benchmarked: no passing migration run\n")
    text = report(root)
    check("RSS after warm-up" not in text, "an empty sample is skipped, not fatal")
    check("not reportable" in text, "a local run is labelled not reportable")
    check("| gamma | - | - | not benchmarked: no passing migration run |" in text, "an app not benchmarked, with why")
    check("| `/` | 8 | 100 / 200: 2.00x (" in text, "alpha's page row")
    check("| `/` | 8 | 100 / 50.0: 0.50x (" in text, "beta's page row")
    check("| clean build | s | 25.0 | 25.0 | 1.00x (0.98-1.02) | 5, 5 |" in text, "the build row")
    check(not re.search(r"\b(PASS|FAIL|READY|verdict|block)\b", text), "the report gives numbers, no verdict")
    check("| alpha | 2.00x (" in text and "| beta | 0.50x (" in text, "the summary rows")
    check("| delta | 1.00x" not in text and "## delta" not in text,
          "an app that failed partway has no summary row or section, only its status")
    check(report(root) == text, "the same samples give the same report")
    print("consumers selftest: ok")


def main():
    a = sys.argv[1:]
    cmd = a[0] if a else ""
    if cmd == "replay" and len(a) == 4:
        replay(a[1].rstrip("/"), a[2], a[3])
    elif cmd == "probe" and len(a) == 5:
        probe(a[1].rstrip("/"), a[2], a[3], a[4])
    elif cmd == "select" and len(a) == 5:
        select(*a[1:])
    elif cmd == "oha" and len(a) == 5:
        print("\n".join(oha_rows(*a[1:])))
    elif cmd == "gc" and len(a) == 3:
        with open(a[1], errors="replace") as f:
            w = gc_window(f.readlines()[int(a[2]) - 1:])
        print(" ".join("-" if x is None else f"{x:.0f}" for x in w))
    elif cmd == "report" and len(a) == 2:
        report(a[1])
    elif cmd == "selftest":
        selftest()
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
