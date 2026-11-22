# AI compatible: how Thwart is meant to replace Thymeleaf

The old rule for replacing a library that many applications depend on is "be a drop-in or lose":
match its API, or its ABI, so that nobody has to change their code. Thwart breaks that rule on
purpose. It is not API compatible with Thymeleaf, because the things that make Thymeleaf
unsafe are part of its API: expressions that reach any bean or static method, escaping that a
template can turn off, logic in templates, and inline scripts that a strict Content Security
Policy (CSP) would block.

Thwart is **AI compatible** instead. The migration is real work, but it is built so that a coding
agent that has never seen Thwart can do it on its own, and show that the migrated app still
works. This page explains the four parts that make that work, and then tests the claim: a fresh
agent with nothing but Thwart's plugin migrates real Spring applications, and a checker scores
each result.

**Summary of the results so far** (details in [Results](#results)):

- **PetClinic family** (7 apps, Spring Boot 1.5 to 4.1 and Spring MVC 4.3 to 7, Thymeleaf and
  JSP): a strict pass in **41 of 42 runs**. In the latest round each migration took 6 to 16
  minutes and cost $2 to $4.
- **Larger real-world apps** (10 apps, 38 to 169 checked pages each; Thymeleaf with htmx,
  FreeMarker, JSP with Apache Tiles, Velocity, Pebble): every run builds and passes all of the
  app's tests, and in the latest round each migration took 10 to 51 minutes and cost $3.50 to
  $32.
  **But strict page-by-page parity is rare**: 6 of 73 runs, and 5 of 42 on the held-out apps.
  Some of the remaining differences are what Thwart requires (inline event handlers moved out
  for the CSP, output the old engine printed raw now escaped). Some are mistakes. The gap is
  described in [Why most larger apps miss strict parity](#why-most-larger-apps-miss-strict-parity).

## The four parts

1. **A plugin written for an agent that has never seen Thwart.** The Claude Code plugin in
   [plugins/thwart](../plugins/thwart) has one skill,
   [thwart](../plugins/thwart/skills/thwart/SKILL.md), which works from the guide, in four parts:
   [setup](guide/setup.md), [templates](guide/templates.md), [migration](guide/migrate.md) and
   [limitations](guide/limitations.md). The skill says what Thwart is, which tools to use and
   what "done" means. The guide covers what an agent can't find out from the tools: the model,
   build setup, the output contexts and the traps of each old engine.
2. **Compiler errors written as fixes.** A template that Thwart can't prove safe is a build error.
   The error names the file, line and column, says what is wrong, and says how to fix it:

   ```text
   error: customers/show.html:7:55: error: onclick on <button> is not allowed: inline event handlers are not supported: a strict CSP blocks them; remove onclick and attach the listener from a <script> with addEventListener, or mark the element with a data-* attribute and handle it with one delegated listener
   ```

   The messages are the detailed documentation for each rule, so an agent's normal loop (edit,
   compile, read the error) is also how it learns Thwart.
3. **A checker.** `thwart-migrate record` saves every page of the old app (body, status and final
   URL) from a list of requests. `thwart-migrate compare` replays the list against the migrated
   app and reports each page as `same`, `reordered` or `differs`, comparing pages as a browser
   builds them ([migration guide](guide/migrate.md)). Markup comparison can't see a script that
   throws or a CSP violation, so the skill also tells the agent to load the pages in a headless
   browser on both apps and compare the console.
4. **The migration test.** [bench/migration](../bench/migration/README.md) runs the whole loop
   with a fresh agent on real applications and scores every result the same way. It is how we
   find out whether the first three parts work, and it is the rest of this page.

## The migration test

### Method

For each run, a fresh clone of the app goes into a new container, and Claude Code runs headless
(`claude -p`, model `opus`) with:

- **The plugin and nothing else from Thwart.** The container has Thwart's jars in a local Maven
  repository, the plugin, JDK 8 and 21, Maven and Chromium. It has none of Thwart's source,
  docs or history. The agent's home directory is empty: no CLAUDE.md, memory, settings or
  other plugins.
- **No web and no human.** Web tools are off, and nobody answers questions.
- **A budget.** 90 minutes of wall time and $40 of API spend per run.
- **One prompt**, [prompt.md](../bench/migration/prompt.md). It asks for the migration and says
  what the result is checked against. It doesn't name the skill: the skill's description has to
  make the agent pick it up. In all 96 runs that recorded it, the agent invoked the skill.

The check list of requests for each app (`pages.txt`) is never shown to the agent. It covers every
template, both states of each form (empty and with validation errors), and the error pages. The
agent writes its own list, as the skill tells it to.

### What a pass means

A run is scored by the same tools the skill tells the agent to use. It **passes** when all of these
hold:

- The app's normal Maven build passes, and so do all of its tests, with no fewer tests than
  before.
- `thwart-migrate compare` against a recording of the original app reports every page `same` or
  `reordered`, with the recorded status and final URL.
- The diff adds no `permitAll`, no CSRF disabling and no `@Disabled` or `@Ignore`.
- No template, dependency or configuration of the old view engine is left (Apache Tiles counts
  as one).
- The browser smoke check finds no new CSP violation, uncaught exception or console error at page
  load. This check was added late (see [Limitations](#limitations)).

Strict parity is a hard bar on purpose. It treats every difference as a failure, including the
ones Thwart requires. So the test also reports a second, looser verdict, **`pass_forced`**: a run
that meets every other condition, and whose differing pages are equal once inline `on*`
handlers, `data-*` attributes, `href="javascript:…"` and `href="#"`, and `<script>` elements are
removed from both sides. This is the change Thwart forces when it rejects inline handlers for the
CSP. The rule is mechanical: it can't tell a correct listener from a broken one (the browser check
covers that), and it misses forced changes of other shapes, such as an `id` added for a listener.
`pass_forced` is report only. The strict Pass is the headline number.

### Development and held-out apps

The 18 apps are real open-source Spring applications pinned to a commit, each seeded with fixed
data so that its pages are the same on every start. They are in two sets:

- **Development apps** (12) are the ones Thwart, the plugin and `thwart-migrate` were improved
  against: after a round, failures on these apps lead to fixes.
- **Held-out apps** (6) only measure. The rule is that the plugin and `thwart-migrate` are never
  changed because of what a held-out app does.

The PetClinic family is a version ladder: the current Spring PetClinic and Spring Framework
PetClinic, plus older commits of both, so that the difference between rungs is the Spring and Java
generation rather than the app.

## Results

**Where the numbers come from.** The test has been run in rounds since it was written, and the
tables below include every archived run: 123 runs, between 2 and 13 per app. Thwart, the plugin
and the scoring changed between rounds, so the cumulative numbers mix setups (see
[Limitations](#limitations)). The latest round, **run 15, ran on 2026-11-15 from Thwart commit
`cfebef4`**, one run of each of the 18 apps. It cost $179 in total. The plugin and guide have
changed since then, for example to cover third-party scripts that need `eval`. A fresh round
will be run before the release, and these tables will be updated from it. To update them, follow
[Updating these numbers](#updating-these-numbers).

### By group

| Group | Apps | Runs | Strict passes | Build and tests pass | Mean pages matched | Median minutes | Median cost |
|---|---|---|---|---|---|---|---|
| PetClinic family (Thymeleaf, JSP) | 7 | 42 | 41 | 42 | 99.5% | 9.4 | $2.60 |
| Mustache PetClinic | 1 | 8 | 4 | 8 | 92.9% | 9.2 | $2.75 |
| Larger apps, development | 4 | 31 | 1 | 31 | 81.7% | 28.8 | $16.06 |
| Larger apps, held out | 6 | 42 | 5 | 42 | 62.4% | 33.4 | $14.62 |
| **All** | **18** | **123** | **51** | **123** | 81.9% | 15.6 | $4.49 |

"Mean pages matched" is the mean, over runs, of the share of checked pages that compare reports as
`same` or `reordered`. The one PetClinic-family run that failed (Spring Framework PetClinic, first
round) failed on 6 pages under an older scorer.

Across all 123 runs, every migrated app builds and passes all of its tests, and no diff adds
`permitAll`, CSRF disabling, `@Disabled` or `@Ignore`. Two runs left old templates behind: one
stopped early, and one ran out of session before its last step. One run (fredbet) was stopped
at the time limit.

### By engine

| Engine | Apps | Runs | Strict passes | Mean pages matched | Median minutes | Median cost |
|---|---|---|---|---|---|---|
| Thymeleaf | 8 (4 PetClinic) | 53 | 28 | 90.7% | 11.8 | $3.78 |
| JSP | 5 (3 PetClinic; 1 with Apache Tiles) | 32 | 17 | 81.9% | 15.2 | $3.91 |
| FreeMarker | 2 | 18 | 1 | 54.2% | 35.2 | $26.56 |
| Mustache | 1 | 8 | 4 | 92.9% | 9.2 | $2.75 |
| Velocity | 1 | 6 | 1 | 73.7% | 13.1 | $4.13 |
| Pebble | 1 | 6 | 0 | 81.1% | 40.5 | $9.18 |

Every Thymeleaf and JSP strict pass outside the PetClinic family is purl-server (4 runs).
Coverage is thin for Mustache, Velocity and Pebble, with one app each, and the only Velocity app
(luckyblog) is old, since Spring 5 dropped Velocity. Both FreeMarker apps are large, so that row
says more about app size than about the engine.

### Latest round (run 15), by app

| App | Engine | Set | Pages | Matched | Matched, forced rule | Pass | `pass_forced` | New browser errors (pages) | Minutes | Cost |
|---|---|---|---|---|---|---|---|---|---|---|
| spring-petclinic | Thymeleaf | dev | 30 | 30 | 30 | yes | yes | 0 | 6.4 | $2.14 |
| spring-petclinic-boot27 | Thymeleaf | dev | 30 | 30 | 30 | yes | yes | 0 | 6.4 | $2.11 |
| spring-petclinic-boot20 | Thymeleaf | dev | 28 | 28 | 28 | yes | yes | 0 | 8.8 | $2.27 |
| spring-petclinic-boot15 | Thymeleaf | dev | 28 | 28 | 28 | yes | yes | 0 | 7.1 | $2.18 |
| spring-framework-petclinic | JSP | dev | 26 | 26 | 26 | yes | yes | 0 | 11.8 | $3.70 |
| spring-framework-petclinic-spring5 | JSP | dev | 27 | 27 | 27 | yes | yes | 0 | 16.0 | $4.34 |
| spring-framework-petclinic-spring43 | JSP | dev | 27 | 27 | 27 | yes | yes | 0 | 11.4 | $3.83 |
| spring-petclinic-mustache | Mustache | dev | 30 | 23 | 23 | no | no | 0 | 8.6 | $2.55 |
| jpetstore | Thymeleaf | dev | 59 | 58 | 58 | no | no | 0 | 10.1 | $3.48 |
| fredbet | Thymeleaf | dev | 169 | 123 | 166 | no | no | 6 (3) | 32.6 | $12.74 |
| posters-demo-store | Thymeleaf + htmx | dev | 141 | 11 | 77 | no | no | 0 | 35.0 | $18.03 |
| jobsearch | FreeMarker | dev | 92 | 36 | 92 | no | yes | 0 | 38.4 | $26.61 |
| purl-server | Thymeleaf | held out | 92 | 51 | 87 | no | no | 0 | 21.6 | $6.07 |
| jprime | JSP | held out | 150 | 108 | 120 | no | no | 186 (62) | 50.5 | $21.40 |
| espd | JSP + Tiles | held out | 60 | 0 | 49 | no | no | 0 | 39.0 | $23.12 |
| budgetmaster | FreeMarker | held out | 121 | 14 | 44 | no | no | 93 (93) | 35.7 | $31.68 |
| luckyblog | Velocity | held out | 64 | 21 | 45 | no | no | 0 | 13.4 | $4.43 |
| ewp-registry | Pebble | held out | 38 | 27 | 34 | no | no | 0 | 30.6 | $8.47 |

Run 15 totals: 7 of 18 strict passes, 8 of 18 with `pass_forced`. "Matched, forced rule" counts
the pages that are `same` or `reordered`, plus the differing pages that the `pass_forced` rule
accepts.

### Why most larger apps miss strict parity

The differences fall into a few kinds. The earlier rounds were triaged page by page, and the
examples below are from run 15.

- **Changes Thwart forces (intended).** Thwart rejects inline `on*` handlers and `javascript:`
  URLs, so a correct migration moves them into a listener script with a `data-*` hook, and every
  page that had one differs. Computed attributes outside Thwart's allow-list move too:
  posters-demo-store's computed `hx-get` became `data-hx-get`. Script values become JSON
  declarations at the top of the script. The `pass_forced` rule accepts the simplest of these
  shapes: it raises espd from 0 to 49 of 60 pages and jobsearch to all 92. It misses others,
  such as an `id` added to a form so that a listener can find it (budgetmaster's login form).
- **Output now escaped (intended).** Where the original printed a value raw, Thwart escapes it.
  jpetstore's only differing page shows a seeded first name with `<i>` in it, which the original
  rendered as markup (a stored XSS in the original) and Thwart prints as text. The Mustache
  PetClinic's differences are stray `th:*` attributes that its templates sent to the browser and
  that Thwart rejects. Both apps now mask these with the test's `ignore` setting, so they are
  expected to pass at the next round, but they haven't been re-run.
- **Agent mistakes (not intended).** espd's language-switch pages lost `?lang=` from the form
  action; purl-server added an empty `<tbody>` to three pages; jprime dropped text from its
  `<meta name="description">`.
- **Scripts the CSP blocks (not intended).** Only the browser check catches these. jprime's
  carousel plugin calls `eval`, which Thwart's default CSP blocks, so the carousel fails on 62
  pages; fredbet's vendored jQuery Rotate does the same on 3 pages. budgetmaster's 93 reports are
  Materialize appending a `<base>` to an inert document, which `base-uri 'none'` blocks: nothing
  visible breaks, but every page logs it. The guide and skill now say how to handle a script that
  needs `eval`; run 15 predates that.
- **Not yet classified.** The test's diff output is cut short on long pages (37 budgetmaster
  pages and 11 jprime pages in run 15), so those can't be classified from the archive.

Run 15 has not been triaged page by page. What can be said from it: one larger app passes with
the forced rule, the only differences in jpetstore and the Mustache PetClinic are intended, and
every other larger app has at least one difference that is a real mistake, a blocked script, or
not yet classified. Strict parity on the larger apps is the gap Thwart has to close.

### Limitations

- **Small samples.** One run per app per round in most rounds, and the larger apps vary a lot
  between runs (espd matched 50 of 60 pages in one run and 0 in another).
- **Rounds aren't directly comparable.** The first rounds gave the agent only the guide (the
  plugin came later), and the very first was scored by an older crawler. Thwart's rules changed
  between rounds (the rejection of inline handlers, for example, cost every app with one a
  visible difference). The browser smoke check exists only from the last rounds, so earlier runs
  could not fail it.
- **One app per engine** for Mustache, Velocity, Pebble and Apache Tiles, and two for
  FreeMarker. One FreeMarker app has a single test.
- **Not every page is reachable.** Pages that need multipart uploads, cookies the replay tool
  can't set, captchas or htmx request headers aren't checked.
- **The PetClinic family shaped Thwart.** Thwart's own example port (`examples/petclinic`) was
  built from Spring PetClinic. The agents never saw that port, but Thwart's design was shaped by
  it, so the PetClinic results are the best case.
- **Load-time behaviour only.** The browser check clicks nothing, so a handler that breaks only
  when it fires isn't seen.

## Running it yourself

[bench/migration/README.md](../bench/migration/README.md) is the operator's manual: what you need
(Docker, Python 3, an Anthropic API key), how to run a full round or a quick one, how each step
works, and how to add an app. Per-run output goes to `bench/migration/results/`, which isn't
committed.

### Updating these numbers

After a round, `bench.py report` writes `results/summary.md` with a row per run and the pass
counts per app. The tables above are computed from each run's `score.json`: `pass`,
`pass_forced`, `build_ok`, `tests_ok`, `pages_identical + pages_reordered` over `pages_total`,
`browser.new` and `browser.pages`, `wall_minutes` and `cost_usd`. Engines and sets come from each
app's `app.json` (`engine`, `heldout`). Update the round, commit and date in
[Where the numbers come from](#results), then the three tables and the summary at the top.
