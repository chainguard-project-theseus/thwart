# Migration benchmark

Can one coding agent, given only [docs/guide.md](../../docs/guide.md) and a legacy Spring app,
migrate the app's views to Tessera on its own without breaking anything? This directory measures
that. Results and failure analysis: [RESULTS.md](RESULTS.md).

## Run it

Needs Docker (about 4 GB of memory per job), Python 3 and `ANTHROPIC_API_KEY`.

```sh
bench/migration/run.sh                        # everything: image, baselines, 3 runs per app, summary
bench/migration/run.sh --runs 1 spring-petclinic
python3 bench/migration/bench.py baseline     # or one step: image | baseline | migrate | report
```

Options: `--runs N` (default 3), `--first-run K` (number new runs from K to add to existing ones),
`--jobs N` parallel containers (default 2), `--minutes N` wall-clock budget per run (default 90),
`--budget-usd N` spend cap per run (default 40), `--model M` (default `opus`).

## What happens

1. **Image.** `Dockerfile` builds Tessera 0.1.0-SNAPSHOT from a `git archive` of this repo's
   `HEAD` and installs it into a Maven repository. The final image holds that repository, JDK 21,
   Maven, Python and the Claude Code CLI, but none of this repo's sources, docs or history.
2. **Baseline.** Each app in `apps/<name>/app.json` is cloned at its pinned commit and checked
   in a container (`lib/check.sh`): template and construct counts (`lib/count.py`), the build,
   a crawl of every request in `apps/<name>/pages.txt` against the running app with its seeded
   data (`lib/crawl.py`, which normalizes whitespace, attribute order, class order, comments and
   CSRF tokens), and the app's tests. Output: `results/<app>/baseline/`.
3. **Migration.** For each run, a fresh clone goes into a new container with `/work/guide.md`
   mounted read-only, and `claude -p` runs [prompt.md](prompt.md) with
   `--dangerously-skip-permissions`, no web tools or workflows, a time limit (`timeout`) and a
   spend cap (`--max-budget-usd`). The container's home is empty, so there is no CLAUDE.md,
   memory, settings or plugin: a fresh context with the standard tools.
4. **Scoring.** The migrated checkout goes through the same check. `results/<app>/run-<k>/` gets
   `score.json`, the agent's `diff.patch`, `pages.diff` (normalized HTML against the baseline),
   the agent's `MIGRATION-REPORT.md`, and `agent.json` (wall time, cost, tokens, outcome).
   A run **passes** when the build and all tests pass (no fewer tests than the baseline), every
   page has the baseline's status and identical normalized HTML, the diff adds no
   `SafeHtml.unchecked`, `TrustedUrl.unchecked`, `permitAll`, CSRF disabling or `@Disabled`, and
   no legacy template or view-engine dependency is left.
5. **Report.** `results/summary.md` has a row per run and a row per app.

## Adding an app

Add `apps/<name>/app.json` (repo, commit, engine, build/test/start commands, readiness path,
base URL) and `apps/<name>/pages.txt` (`GET /path` or `POST /path a=1&b=2` per line, run in
order; a POST takes the previous page's `_csrf` token). Cover every template, both states of
each form, and the error pages.
