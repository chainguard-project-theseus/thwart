"""Count templates and template constructs under an app checkout.

usage: count.py APP_DIR

Prints JSON: for each engine, the template files found under src/ and a count per construct.
Run on a baseline checkout it sizes the job; run on a migrated one it shows what was left.
"""

import collections
import json
import os
import re
import sys

ENGINES = {
    "jsp": {
        "ext": (".jsp", ".jspf", ".tag", ".tagx"),
        "constructs": [r"<(c|fmt|fn|form|spring|sec|jsp|tiles):[\w-]+", r"<%[@=!-]?",
                       r"\$\{", r"#\{", r"<[a-z]\w*:[\w-]+"],
    },
    "freemarker": {
        "ext": (".ftl", ".ftlh", ".ftlx"),
        "constructs": [r"<#[\w-]+", r"<@[\w.]+", r"\$\{", r"\?[a-z_]+", r"![\"'\w]"],
    },
    "velocity": {
        "ext": (".vm", ".vtl"),
        "constructs": [r"#(if|elseif|else|end|foreach|set|parse|include|macro|define|evaluate)\b",
                       r"#@?\w+\(", r"\$!?\{?\w+"],
    },
    "mustache": {
        "ext": (".mustache", ".hbs", ".handlebars"),
        "constructs": [r"\{\{[#^/>!&{]?"],
    },
    "pebble": {
        "ext": (".peb", ".pebble"),
        "constructs": [r"\{%-?\s*\w+", r"\{\{", r"\|\s*\w+"],
    },
    "thymeleaf": {
        "ext": (".html",),
        "marker": re.compile(r"\b(th|layout|sec|data-th)[:-][\w-]+="),
        "constructs": [r"\bth:[\w-]+(?==)", r"\bdata-th-[\w-]+(?==)", r"\blayout:[\w-]+(?==)",
                       r"\bsec:[\w-]+(?==)", r"\$\{", r"\*\{", r"#\{", r"@\{", r"~\{", r"__\$\{",
                       r"\[\[", r"\[\(", r"#[a-z]+\.\w+", r"\bT\(", r"\.\?\[", r"\.!\["],
    },
    "tessera": {
        "ext": (".html",),
        "marker": re.compile(r"xmlns:t=\"https://tessera\.dev\""),
        "constructs": [r"\bt:[\w-]+(?==)", r"<t:block", r"SafeHtml|TrustedUrl"],
    },
}

SKIP = {"target", "build", "node_modules", ".git", ".mvn", "test"}


def template_files(root):
    for d, dirs, files in os.walk(os.path.join(root, "src")):
        dirs[:] = [x for x in dirs if x not in SKIP and not x.startswith(".")]
        for f in files:
            yield os.path.join(d, f)


def count(root):
    out = {}
    files = list(template_files(root))
    for engine, spec in ENGINES.items():
        hits, found = collections.Counter(), []
        for path in files:
            if not path.endswith(spec["ext"]) or "/static/" in path:
                continue
            text = open(path, encoding="utf-8", errors="replace").read()
            if spec["ext"] == (".html",):  # an HTML comment that mentions th:value is not Thymeleaf
                text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
            if "marker" in spec and not spec["marker"].search(text):
                continue
            found.append(os.path.relpath(path, root))
            for pat in spec["constructs"]:
                for m in re.finditer(pat, text):
                    hits[m.group(0)] += 1
        if found:
            out[engine] = {"templates": len(found), "files": sorted(found),
                           "constructs": dict(hits.most_common())}
    return out


if __name__ == "__main__":
    json.dump(count(sys.argv[1]), sys.stdout, indent=1)
    print()
