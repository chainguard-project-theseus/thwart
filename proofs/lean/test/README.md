# Vendored test data

The differential checks in `../Diff` run over unmodified copies of two upstream test suites.
Each file is byte-identical to the upstream file at the commit below (git blob hashes match).

| Directory | Source | Upstream path | Commit | License |
|---|---|---|---|---|
| `html5lib/` | [html5lib/html5lib-tests](https://github.com/html5lib/html5lib-tests) | `tokenizer/*.test` | `c777c408b61078ea2eb4acefc2535f54dbc8b28a` (2026-10-01) | MIT, `html5lib/LICENSE` |
| `wpt-tree/` | [web-platform-tests/wpt](https://github.com/web-platform-tests/wpt) | `html/syntax/parsing/resources/*.dat` | `ffe56d4420d270941329689aff42d925f615d0b4` (2026-10-24), also in `wpt-tree/SOURCE` | BSD-3-Clause, `wpt-tree/LICENSE.md` |

The tree-construction tests used to live in html5lib-tests and moved to WPT.

## Licenses

- `html5lib/`: SPDX `MIT`. Copyright (c) 2006-2013 James Graham, Geoffrey Sneddon, and other
  contributors. The full text is in `html5lib/LICENSE`, copied from the upstream `LICENSE`.
- `wpt-tree/`: SPDX `BSD-3-Clause`. Copyright © web-platform-tests contributors. The full text
  is in `wpt-tree/LICENSE.md`, copied from the upstream `LICENSE.md`.

Both licenses require the copyright notice and license text to travel with the files; keep the
two license files next to the data when copying or updating it.

## Updating

Copy the files from a newer upstream commit, update the commit in this table (and in
`wpt-tree/SOURCE`), and rerun the three executables. A change in the pass or skip counts goes in
`../README.md`.
