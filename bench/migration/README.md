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
   `HEAD` and installs it into a Maven repository. The final image holds that repository (and
   the same `tessera-migrate` jar at `/opt/tessera-migrate.jar`), JDK 21 (default) and JDK 8,
   Maven, Python and the Claude Code CLI, but none of this repo's sources, docs or history.
2. **Baseline.** Each app in `apps/<name>/app.json` is cloned at its pinned commit and checked
   in a container (`lib/check.sh`): template and construct counts (`lib/count.py`), the build,
   `tessera-migrate record` of every request in `apps/<name>/pages.txt` against the running app
   with its seeded data, and the app's tests. Output: `results/<app>/baseline/` (the recording in
   `pages/`). The app is then checked a second time with `tessera-migrate compare` against that
   recording: `baseline/determinism.txt` must show every page `same` or `reordered`, or the
   pages are not deterministic enough to score a migration against.
3. **Migration.** For each run, a fresh clone goes into a new container with `/work/guide.md`
   mounted read-only, and `claude -p` runs [prompt.md](prompt.md) with
   `--dangerously-skip-permissions`, no web tools or workflows, a time limit (`timeout`) and a
   spend cap (`--max-budget-usd`). The container's home is empty, so there is no CLAUDE.md,
   memory, settings or plugin: a fresh context with the standard tools.
4. **Scoring.** The migrated checkout goes through the same check, with `tessera-migrate
   compare` against the baseline recording: the same tool and the same page comparison the
   agent's guide tells it to use. `results/<app>/run-<k>/` gets `score.json`, the agent's
   `diff.patch`, `compare.txt` (compare's output: per page `same`, `reordered` or `differs`,
   status and final-URL mismatches, and a diff), the agent's `MIGRATION-REPORT.md`, and
   `agent.json` (wall time, cost, tokens, outcome). A run **passes** when the build and all tests
   pass (no fewer tests than the baseline), compare reports every page `same` or `reordered` with
   its recorded status and final URL, the diff adds no `SafeHtml.unchecked`,
   `TrustedUrl.unchecked`, `permitAll`, CSRF disabling or `@Disabled`, and no legacy template or
   view-engine dependency is left. Runs from #115 were scored by an older Python crawler and
   normalizer; they have `pages.diff` instead of `compare.txt`, and `rescore` leaves them alone.
5. **Report.** `results/summary.md` has a row per run and a row per app.

## Adding an app

Add `apps/<name>/app.json` (repo, commit, engine, build/test/start commands, readiness path,
base URL) and `apps/<name>/pages.txt` (`GET /path` or `POST /path a=1&b=2` per line, run in
order; a POST takes the previous page's `_csrf` token). Cover every template, both states of
each form, and the error pages. Optional keys:

- `"java": 8` builds, tests and runs the original app on JDK 8 (default 21). A migrated app
  builds and tests on JDK 21, which Tessera's build needs, and still runs on the app's JDK.
- `"ignore": [regex, ...]` deletes matches from both the recorded and the new raw page before
  comparing (`compare --ignore`), for accepted differences such as broken markup in the original.
  Prefer fixing the data in `pages.txt` (absolute dates, no random generators) over ignoring.
- `"heldout": true` marks an app that only measures the guide and `tessera-migrate`. Never change
  either because of what a held-out app does; `report` lists held-out apps in their own table.

The check container (never the migrating agent) sees the app's fixture directory read-only at
`/bench/app`, so a start command can load seed data from it (for example
`spring.sql.init.data-locations=file:/bench/app/seed.sql`). An app whose pages send mail can start
`python3 /bench/lib/smtp.py &`, an SMTP sink on `localhost:2525`, in its start command. Helper
scripts go there too (budgetmaster's `start.sh` and surefire `test-excludes.txt`). The image has
PostgreSQL 18 (`initdb`, `pg_ctl`, `psql`) for apps that need it and libfaketime for an app whose
pages show "today".

Run `bench.py baseline <name>` and check `results/<name>/baseline/determinism.txt`.

Current ignores: times of day (fredbet, jobsearch: seeded data is stamped at startup; the date
is still compared), UUIDs (fredbet: image keys generated at startup), the webjar version path
segment (spring-petclinic, fredbet: Boot adds it for Thymeleaf through `ResourceUrlEncodingFilter`,
which Tessera's starter does not register yet, #122), JobSearch's `<span text-bg-...">`, an
attribute built from an unbalanced quote in the original template, and order timestamps (jpetstore:
stamped from the clock; no seeded order has one). Held-out: the request timestamp and the webjar
version segment (purl-server: Boot's error page prints the date and time of the request; the webjar
segment as above), the invoice date input (jprime: the invoice form defaults to today), and chart
container UUIDs and the delete-database verification code (budgetmaster: random per request).

Licenses: JobSearch and jprime have no license, so their runs' `diff.patch` files are not
committed (`.gitignore`).

Per-app environment fixes (in the app's commands, not its code):

- jpetstore (`"java": 8`): `-Dlicense.skip=true` stops license-maven-plugin rewriting source
  headers at `initialize`, and `-Ddependency-check.skip=true` skips the OWASP plugin, whose NVD feed
  is gone (it is bound to `verify`, so this only matters if a build runs that far). The baseline
  builds, tests and runs on JDK 8. A migrated jpetstore builds and tests on JDK 21 and runs on
  JDK 8, so the agent has to make the original build work on JDK 21 (e.g. a newer Lombok, JaCoCo
  that understands Java 21 classes) while keeping Java 8 bytecode.
- budgetmaster: runs only on PostgreSQL or MariaDB, so `start.sh` creates a throwaway PostgreSQL
  cluster under `/tmp`, writes the settings file the app requires, and starts the app with its
  clock pinned by libfaketime to 2025-06-15 10:00 UTC (its pages show the current month, and its
  forms need a date cookie that only `/today` sets). The build pins `app.versionDate`, which is
  otherwise the build day and shown on the About page. The test command creates the settings file
  the test JVM requires and excludes, via `test-excludes.txt`, the tests that need Docker
  (Testcontainers) or Firefox (Selenium), plus one that depends on their logging setup.
