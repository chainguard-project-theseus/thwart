# Migration benchmark

Can one coding agent, given only the Claude Code plugin we ship
([plugins/thwart](../../plugins/thwart): the `thwart` skill and its guide) and a legacy Spring
app, migrate the app's views to Thwart on its own without breaking anything? This directory
measures that. Its output goes to `results/`, which is local and not committed.

## Run it

Needs Docker (about 4 GB of memory per job), Python 3 and `ANTHROPIC_API_KEY`.

```sh
bench/migration/run.sh --runs 3                # milestone: image, baselines, 3 runs of every app, summary
python3 bench/migration/bench.py migrate --first-run 4 spring-petclinic jpetstore   # quick round
python3 bench/migration/bench.py baseline      # or one step: image | baseline | migrate | report
```

Options: `--runs N` (default 1), `--first-run K` (number new runs from K to add to existing ones),
`--jobs N` parallel containers (default 2), `--minutes N` wall-clock budget per run (default 90),
`--budget-usd N` spend cap per run (default 40), `--model M` (default `opus`).

Behind a TLS-intercepting proxy, set `BENCH_EXTRA_CA_CERTS` to the path of a file holding exactly
one PEM CA certificate, the proxy's, before `image`, so the image trusts it (the JDK trust store
imports only the first certificate in the file).
`ANTHROPIC_BASE_URL` is passed to the agent's container when set.

Two kinds of round:

- **Quick round**: after a change to Thwart, `thwart-migrate` or the plugin, run only the apps
  the change affects, one run each (`migrate` with the default `--runs 1` and a `--first-run` past
  the existing runs). Rebuild the image first (`bench.py image`) so the runs use the change.
- **Milestone round**: all apps, 3 runs each (`run.sh --runs 3`, or `migrate --runs 3`).

`report` counts each app's runs, so apps with different numbers of runs sit in the same tables.

`recheck` re-runs the check and the browser smoke check of every run that has a checkout in
`work/runs/` (others are skipped), against the baseline recording, so `baseline` must have run in
this clone. To recheck a run from another clone or an archive, rebuild its checkout from its patch
first: `git clone -q <repo> work/runs/<app>/run-<k>`, `git -C work/runs/<app>/run-<k> checkout -q -B
bench <commit>` and `git -C work/runs/<app>/run-<k> apply results/<app>/run-<k>/diff.patch` (repo
and commit from `app.json`).

## What happens

1. **Image.** `Dockerfile` builds Thwart 0.1.0-SNAPSHOT from a `git archive` of this repo's
   `HEAD` and installs it into a Maven repository. The final image holds that repository (and
   the same `thwart-migrate` jar at `/opt/thwart-migrate.jar`), the Claude Code plugin from
   `plugins/thwart` at `/opt/thwart-plugin` (same commit), JDK 21 (default) and JDK 8, Maven,
   Python, Chromium (for the browser smoke check; the migrating agent can use it too) and the
   Claude Code CLI, but none of this repo's other sources, docs or history.
2. **Baseline.** Each app in `apps/<name>/app.json` is cloned at its pinned commit and checked
   in a container (`lib/check.sh`): template and construct counts (`lib/count.py`), the build,
   `thwart-migrate record` of every request in `apps/<name>/pages.txt` against the running app
   with its seeded data, and the app's tests. Output: `results/<app>/baseline/` (the recording in
   `pages/`). The app is then checked a second time with `thwart-migrate compare` against that
   recording: `baseline/determinism.txt` must show every page `same` or `reordered`, or the
   pages are not deterministic enough to score a migration against.
3. **Migration.** For each run, a fresh clone goes into a new container and `claude -p` runs
   [prompt.md](prompt.md) (`lib/run-agent.sh`) with `--plugin-dir /opt/thwart-plugin`,
   `--dangerously-skip-permissions`, no web tools or workflows, a time limit (`timeout`) and a
   spend cap (`--max-budget-usd`). `CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS=0` keeps `claude -p`
   from stopping the agent's background tasks 10 minutes after its last turn; the time limit
   still bounds them. The container's home is empty, so there is no CLAUDE.md,
   memory, settings or other plugin: a fresh context with the standard tools and the Thwart
   plugin, as a user who installed it would have. The prompt asks for the migration and says what
   "done" is checked against, but doesn't name the skill or its guide: the skill's description
   has to trigger it. `agent.json` records `skill_listed` (the session's init event lists
   `thwart:thwart`) and `skill_invoked` (the transcript has a `Skill` tool call for it, or a tool call that opens its
   `SKILL.md` directly). The
   skill's `mvn dependency:copy` of `thwart-migrate` resolves from the seeded Maven volume.
4. **Scoring.** The migrated checkout goes through the same check, with `thwart-migrate
   compare` against the baseline recording: the same tool and the same page comparison the
   skill tells the agent to use. The migrated app builds, tests and runs on the app's JDK, like
   the original (Thwart builds and runs on JDK 8+). `results/<app>/run-<k>/` gets `score.json`, the agent's
   `diff.patch`, `compare.txt` (compare's output: per page `same`, `reordered` or `differs`,
   status and final-URL mismatches, and a diff), the agent's `MIGRATION-REPORT.md`, and
   `agent.json` (wall time, cost, tokens, outcome, skill use). A run **passes** when the build and all tests
   pass (no fewer tests than the baseline), compare reports every page `same` or `reordered` with
   its recorded status and final URL, the diff adds no `permitAll`, CSRF disabling, `@Disabled`
   or `@Ignore` (bench.py still rejects the removed `SafeHtml.unchecked` and
   `TrustedUrl.unchecked` as a tripwire), and no legacy template or
   view-engine dependency is left (Apache Tiles counts as one: a `tiles-*` dependency or a Tiles
   definitions XML file fails the run whatever the app's engine), and the browser smoke check
   below finds nothing new (#866). Runs from #115 were scored by an older Python crawler and
   normalizer; they have `pages.diff` instead of `compare.txt`, and `rescore` leaves them alone.
   - **Pass with forced diffs (`pass_forced`, report only).** Thwart rejects inline `on*`
     handlers and `javascript:` URLs (a strict CSP blocks them), so a correct migration moves them
     to `data-*` attributes and a listener script, and its pages differ. A run gets `pass_forced`
     when it meets every other Pass condition and each differing page's diff in `compare.txt` is
     equal on both sides, in any order, once `on*` and `data-*` attributes, `href="javascript:…"`
     and `href="#"` are deleted from tag lines and `<script>` elements are dropped. A diff compare
     cut short never qualifies. The summary shows such a run as `forced` and counts it beside the
     strict passes; the strict Pass is unchanged. `rescore` applies it to archived runs.
   - **Browser smoke check.** `compare` reads HTML and never runs a script, so a
     page can compare as acceptable while its scripts throw or the starter's CSP blocks them.
     `lib/browser.py` replays `pages.txt` in one headless Chromium tab (from the image's Wolfi
     packages, over the DevTools protocol) against a fresh start of the app, in its own container:
     a GET navigates; a POST submits a form with the line's fields, plus the current page's
     `_csrf` hidden input as record does, from the current page, so the cookie jar and the data
     follow the same sequence as the recording. Per request it records, through the load event
     plus 1.5 s, CSP violations (`securitypolicyviolation`), uncaught exceptions and console
     errors (`console.error` and the browser's own error log, such as failed loads). It runs on
     the original app at `baseline` (`results/<app>/baseline/browser/`, gitignored; `migrate` and
     `recheck` make it when it is missing) and on each migrated checkout after its check.
     `browser.txt` in the run directory lists, per page, only what is **new** in the migrated app,
     after line and column numbers and nonces are removed (the originals have their own console
     noise), and the summary's Browser column counts it. Anything new fails both Pass and
     `pass_forced`: in run-15 this check alone found the real breakage (third-party scripts that
     need `eval`, blocked by the CSP), which markup comparison can't see.
     Only load-time behaviour is checked: nothing is clicked and no handler runs. That still
     catches listener-wiring scripts that throw at load, data reads that fail and anything the
     CSP blocks at load; a handler that breaks only when it fires is not seen, and neither is an
     inline `on*` attribute blocked by the CSP, which Chromium reports only when it fires (Thwart
     rejects static ones anyway, #768). Chromium runs with `--ignore-certificate-errors` so that
     CDN scripts load behind a TLS-intercepting proxy. If the check leaves no `browser.json` (the
     app doesn't build, Chromium fails), the run's Browser column is `-`, never an older result,
     and the run doesn't pass (before #866 it could). `rescore` of a run checked before #790
     fails it the same way; `recheck` adds the browser check.
     Tests: `python3 -m unittest bench/migration/test_browser.py` (its end-to-end test needs
     Chromium and websocket-client, as in the image, and is skipped without them).
5. **Report.** `results/summary.md` has a row per run, then a row per app in three tables:
   development apps, held-out apps and below-floor apps, each with its pass count out of all its
   runs.

## Adding an app

Add `apps/<name>/app.json` (repo, commit, engine, build/test/start commands, readiness path,
base URL) and `apps/<name>/pages.txt` (`GET /path` or `POST /path a=1&b=2` per line, run in
order; a POST takes the previous page's `_csrf` token). Cover every template, both states of
each form, and the error pages. Optional keys:

- `"java": 8` builds, tests and runs the app on JDK 8 (default 21), the original and the migrated
  one alike.
- `"ignore": [regex, ...]` deletes matches from both the recorded and the new raw page before
  comparing (`compare --ignore`), for accepted differences such as broken markup in the original.
  Prefer fixing the data in `pages.txt` (absolute dates, no random generators) over ignoring.
- `"heldout": true` marks an app that only measures the plugin (skill and guide) and
  `thwart-migrate`. Never change either because of what a held-out app does; `report` lists held-out apps in their own table.
- `"belowfloor": true` marks an app older than Thwart supports (before Spring 4.3 / Boot 1.5).
  It is informational: it shows whether an agent upgrades Spring first. `report` lists
  below-floor apps in their own table. No app has it since #179 made the Spring 4.3 rungs
  supported.

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
segment (spring-petclinic, its Boot 2.0 and 1.5 rungs, fredbet: Boot adds it for Thymeleaf through `ResourceUrlEncodingFilter`;
the starter now registers that filter too, #122, so these can go once a bench round confirms
the segment matches), JobSearch's `<span text-bg-...">`, an
attribute built from an unbalanced quote in the original template, and its
`+ (spring.status.error?then(...))` left as literal attribute text in two inputs, the Mustache
petclinic's `<span">` typo on the pagination pages and the stray `th:*` attributes its
templates send to the browser (Thwart rejects them, #833), order timestamps (jpetstore:
stamped from the clock; no seeded order has one), jpetstore's seeded `Jay <i>` first name (the
original prints it unescaped; Thwart escapes it), and order numbers, times of day and
month-name dates (posters-demo-store: order numbers are `ORD-` plus the clock in milliseconds,
and orders, users and the audit log are stamped at request time). Held-out: the request
timestamp and the webjar version segment (purl-server: Boot's error page prints the date and time of the request; the webjar
segment as above), the invoice date input (jprime: the invoice form defaults to today), and chart
container UUIDs, the delete-database verification code (budgetmaster: random per request),
the webjar version segment (budgetmaster, as above), the free-memory figures on the admin
monitor page (luckyblog: read from the operating system), and the time each manifest was last
fetched and the home page's commit and build day (ewp-registry), and the eCertis URL handed to
`EcertisHandler(...)` in espd's layout script (the original printed `&amp;` inside a `<script>`, a
bug a port fixes by writing the URL as JSON; the country argument is still compared).

Licenses: JobSearch and jprime have no license and ESPD is EUPL-1.1 (copyleft), so don't publish
their runs' `diff.patch` files.

The version ladder: old commits of the two petclinics, so differences between rungs come from
the Spring and Java generation rather than the app. Each rung builds, tests and runs on JDK 8 with
in-memory seed data, and its `pages.txt` is the current app's list adapted to that version's
routes and forms:

| App | Commit | Stack |
|---|---|---|
| spring-petclinic-boot27 | `9ecdc11` (2022-10) | Boot 2.7.3, Thymeleaf 3.0, H2, javax |
| spring-petclinic-boot20 | `60105d5` (2018-08) | Boot 2.0.4, Thymeleaf 3.0, HSQLDB, javax; no pagination |
| spring-petclinic-boot15 | `c36452a` (2017-11) | Boot 1.5.4 (Spring 4.3), Thymeleaf 2.1, HSQLDB |
| spring-framework-petclinic-spring5 | `f5ab627` (v5.3.22, 2022-09) | Spring 5.3 MVC, JSP on Jetty 9.4, H2, javax; `yyyy/MM/dd` dates |
| spring-framework-petclinic-spring43 | `6e3b582` (2018-02) | Spring 4.3.13 MVC, JSP on Tomcat 7 (`tomcat7:run-war`, port 9966, `/petclinic`), HSQLDB |

None needs an environment fix: they build with the image's Maven 3.9 as they are.

Per-app environment fixes (in the app's commands, not its code):

- jpetstore (`"java": 8`): `-Dlicense.skip=true` stops license-maven-plugin rewriting source
  headers at `initialize`, and `-Ddependency-check.skip=true` skips the OWASP plugin, whose NVD feed
  is gone (it is bound to `verify`, so this only matters if a build runs that far). It builds,
  tests and runs on JDK 8, before and after migration.
- budgetmaster: runs only on PostgreSQL or MariaDB, so `start.sh` creates a throwaway PostgreSQL
  cluster under `/tmp`, writes the settings file the app requires, and starts the app with its
  clock pinned by libfaketime to 2025-06-15 10:00 UTC (its pages show the current month, and its
  forms need a date cookie that only `/today` sets). The build pins `app.versionDate`, which is
  otherwise the build day and shown on the About page. The test command creates the settings file
  the test JVM requires and excludes, via `test-excludes.txt`, the tests that need Docker
  (Testcontainers) or Firefox (Selenium), plus one that depends on their logging setup.
- espd (held out; ESPD-Service, JSP composed with Apache Tiles 3, Spring Boot 1.4.1 / Spring 4.3.3,
  `"java": 8`): Boot 1.4 is below Thwart's Boot 1.5 floor (Spring 4.3 is supported), so a
  migration may need to bump Boot to 1.5 first. Only the `espd-web` module is built (`-pl espd-web
  -am`); `-Denforcer.skip=true` lifts its enforcer rule that Maven be at most 3.5.0, and the build
  pins `espd.build.timestamp`, which every page footer shows. There is no database: the wizard
  keeps its document in the session. `start.sh` pins the clock with libfaketime to 2025-06-15 10:00
  UTC (the response wizard's turnover years and some criteria show the current year), turns the
  TED lookup off and Piwik off, points the eCertis URL handed to the pages' JavaScript at a local
  placeholder, leaves the stack trace out of error pages, and keeps the session id out of URLs
  (`server.session.tracking-modes=cookie`; otherwise the first page's links carry a random
  `;jsessionid=`). One ignore: the eCertis URL argument (above).
- luckyblog (held out, Apache Velocity, `"java": 8`): Boot 1.4 is below Thwart's Boot 1.5 floor
  (Spring 4.3 is supported), so a migration may need to bump Boot to 1.5 first. The build has no
  `spring-boot-maven-plugin` (a thin jar plus `lib/`), so `start.sh` runs `target/classes` on the
  Maven runtime classpath. MySQL is replaced by H2 2.2 in MySQL mode (the last H2 line for Java 8),
  which takes the mappers' MySQL dialect (`LIMIT a,b`, `INSERT IGNORE`, `INSERT ... SET`,
  `UPDATE/DELETE ... LIMIT`, `LAST_INSERT_ID()`), with `schema.sql` (the app's `import.sql` without
  the MySQL dump clauses) and `seed.sql`. Druid's `wall` filter, which does not know H2, is off; the
  undertow access log (under `/data`) is off; `log4j.properties` logs INFO to the console instead
  of DEBUG to the console and `logs/`. libfaketime starts the clock at 2025-06-15 10:00 UTC, the
  date of the post the pages add. The pom skips tests (`maven.test.skip`) and its one test class
  has none, so the baseline has 0 tests.
- ewp-registry (Pebble, held out): the build's frontend-maven-plugin downloads a Node.js that
  needs `libatomic`, which the image lacks, so the build skips it (`-Dskip.installnodenpm
  -Dskip.npm`) and runs the image's `npm` for the Vue bundle instead; it also skips Checkstyle,
  SpotBugs, PMD and Javadoc, which are bound to the build and would judge generated code. The app
  takes only https manifest URLs, so `start.sh` serves the fixture manifests in `manifests/` over
  local HTTPS with the localhost certificate from the app's `local_configuration/keys` (the JVM
  trusts only that one), gives it a fresh git working copy, in-memory H2 and the SMTP sink, and
  opens port 8080 (forwarded to the app on 8081) only after every manifest source has been fetched
  once, since the app fetches them in the background after it starts answering.
- posters-demo-store (Xceptance's demo shop, Boot 4.0, Thymeleaf with htmx, added for #760: its
  templates compute `hx-*` attributes, which the computed-attribute allow-list of #761 rejects):
  the Maven project is in `implementation/`. `start.sh` makes the app's H2 file database
  in-memory and puts its Lucene index under `/tmp`. The app answers while its startup runners
  still import the catalog and build the search index, so `start.sh` runs it on 8081 and opens
  8080 (forwarded) only after the last runner has started. Its customer import gives each demo
  customer a random UUID, which backoffice URLs carry, so `seed.sql` inserts the same ten
  customers with fixed ids first (`spring.jpa.defer-datasource-initialization`, after Hibernate
  creates the tables); the import skips them. The JSON APIs, the `hx-delete` endpoints and the backoffice's credit-card form post
  (it fails in the app itself) are not in `pages.txt`.
