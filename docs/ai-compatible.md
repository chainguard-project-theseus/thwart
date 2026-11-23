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

**Summary of the results so far.** The latest round (run 16, details in [Results](#results)):

- **Larger real-world apps** (10 apps, 38 to 169 checked pages each; Thymeleaf, Thymeleaf with
  htmx, FreeMarker, JSP, JSP with Apache Tiles, Velocity and Pebble): every migrated app builds
  and passes all of its tests, and each migration took 8 to 37 minutes and cost about $3.50 to
  $28. **But only 1 of the 10 passed strictly, and 4 under the looser `pass_forced` rule. Of the
  6 held-out apps, none passed strictly and 1 passed under `pass_forced`.** On average 51% of
  their pages matched. Over every archived run, the larger apps passed strictly in 7 of 83 runs (5
  of 48 on the held-out apps), and only one of those (jpetstore, in this round) came after Thwart
  started rejecting inline handlers. On average 64.7% of their pages matched, taking each app's
  mean over its runs (68.2% as a mean over all 83 runs, which weighs apps with more runs more).
- **PetClinic family** (7 apps, Spring Boot 1.5 to 4.1 and Spring MVC 4.3 to 7, Thymeleaf and
  JSP): all 7 passed strictly. Each migration took 6 to 11 minutes and cost about $1.85 to $3.70.
  Over every archived run, the family passed strictly in **48 of 49 runs**. All 7 are development
  apps, and Thwart's design was shaped by its own PetClinic port, so this is the best case (see
  [Limitations](#limitations)).
- **Mustache PetClinic** (1 app): passed strictly, with all 30 pages matched. Over every archived
  run it passed strictly in 5 of 9.
- **Round total:** 9 of 18 apps passed strictly (7 in run 15), and 12 of 18 under `pass_forced`
  (8 in run 15, computed afterwards by the earlier `pass_forced` rule).
- Some of the remaining differences are what Thwart requires (inline event handlers moved out
  for the CSP, output the old engine printed raw now escaped, stored HTML rewritten by the
  sanitizer), and compare doesn't recognise all of them. Some are agent mistakes, and some are
  scripts that Thwart's CSP blocks. Strict parity on larger apps is the gap Thwart has to close.
  It is described in
  [Why most larger apps miss strict parity](#why-most-larger-apps-miss-strict-parity).

## The four parts

1. **A plugin written for an agent that has never seen Thwart.** The Claude Code plugin in
   [plugins/thwart](../plugins/thwart) has one skill,
   [thwart](../plugins/thwart/skills/thwart/SKILL.md), which works from the guide, in four parts:
   [setup](guide/setup.md), [templates](guide/templates.md), [migration](guide/migrate.md) and
   [limitations](guide/limitations.md). The skill says what Thwart is and which part of the guide
   to read for the task; the migration part names the tools, how to work and what "done" is
   checked against. The guide covers what an agent can't find out from the tools: the model,
   build setup, the output contexts and the traps of each old engine.
2. **Compiler errors written as fixes.** A template that Thwart can't prove safe is a build error.
   The error names the file, line and column, says what is wrong, and says how to fix it. For the
   README's example template with `<button onclick="newOrder()">` added right after
   "No orders yet.":

   ```text
   error: customers/show.html:7:54: error: onclick on <button> is not allowed: inline event handlers are not supported: a strict CSP blocks them; remove onclick and attach the listener from a <script> with addEventListener, or mark the element with a data-* attribute and handle it with one delegated listener
   ```

   The messages are the detailed documentation for each rule, so an agent's normal loop (edit,
   compile, read the error) is also how it learns Thwart.
3. **A checker.** `thwart-migrate record` saves every page of the old app (body, status and final
   URL) from a list of requests. `thwart-migrate compare` replays the list against the migrated
   app and compares the pages as a browser builds them ([migration guide](guide/migrate.md)). It
   reports each page as `same`, `reordered` (the same elements, with some siblings in another
   order, such as validation messages from a `HashSet`), `forced` (it differs only in what Thwart
   makes a migration change, such as handlers moved to a script) or `differs`. Markup comparison can't
   see a script that throws or a CSP violation, so the migration guide also tells the agent to load
   the pages in a headless browser on both apps and compare the console.
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
- **No web tools and no human.** Claude's WebFetch, WebSearch and Workflow tools are off; the
  network is up so that Maven and npm can download dependencies. Nobody answers questions.
- **A budget.** 90 minutes of wall time and $40 of API spend per run.
- **One prompt**, [prompt.md](../bench/migration/prompt.md). It asks for the migration and says
  what the result is checked against. It doesn't name the skill: the skill's description has to
  make the agent pick it up. In all 114 runs that recorded it, the agent invoked the skill.

The check's list of requests for each app (`pages.txt`) is never shown to the agent. It is written
to cover every template, both states of each form (empty and with validation errors), and the
error pages, though some pages can't be reached (see [Limitations](#limitations)). The agent
writes its own list, as the guide tells it to.

### What a pass means

A run is scored by the same tools the guide tells the agent to use. It **passes** when all of these
hold:

- The app's normal Maven build passes, and so do all of its tests, with no fewer tests than
  before.
- `thwart-migrate compare` against a recording of the original app reports every page `same` or
  `reordered`, with the recorded status and final URL.
- The diff adds no `permitAll`, no CSRF disabling, no `@Disabled` or `@Ignore`, and no
  `SafeHtml.unchecked` or `TrustedUrl.unchecked`.
- No template, dependency or configuration of the old view engine is left (Apache Tiles counts
  as one).
- The browser smoke check finds no new CSP violation, uncaught exception or console error at page
  load.

Strict parity is a hard bar on purpose. It treats every difference as a failure, including the
ones Thwart requires. So the test also reports a second, looser verdict, **`pass_forced`**: a run
that meets every other condition, and whose pages compare reports as `same`, `reordered` or
`forced`. A page is `forced` when it differs only in what Thwart forces a migration to change:
inline `on*` handlers, `href="javascript:…"` and `href="#"`, `data-*` attributes, `<script>` and
`<style>` elements, `hx-X` written as `data-hx-X` with the same value, a query moved out of a path
in a custom attribute, and an `<svg>` in a table replaced (the full rule is in the
[migration guide](guide/migrate.md#the-tools-and-the-loop)). The rule is mechanical: it can't tell
a correct listener from a broken one (the browser check covers that), and it misses other forced
changes, such as an `id` added for a listener or a removed `hx-on` attribute. `pass_forced` is
report only. The strict Pass is the headline number.

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
cumulative tables include every archived run: 141 runs, between 3 and 14 per app. Thwart, the
plugin and the scoring changed between rounds, so the cumulative numbers mix setups (see
[Limitations](#limitations)). The latest round, **run 16, ran on 2026-11-22 from Thwart commit
`6329e7d4`** (main after #881), one run of each of the 18 apps. It cost $155 in total, plus one
run whose cost wasn't recorded (spring-framework-petclinic-spring43, see below). It is the first
round with the plugin's current layout, the `thwart` skill with the guide in four parts; earlier
rounds used a skill named `migrate` with the guide as a single file.

Run 16 was scored with compare's `forced` verdict, and new browser errors failed a run. Earlier
rounds were scored before both. The `pass_forced` figures for run 15 were computed afterwards,
from its archived `compare` output, by the rule that preceded the `forced` verdict.

Two changes merged after run 16, so it didn't measure them: a computed `name` on `<meta>` is now a
build error (#882), and a descriptive `<meta>` (`description`, `og:*`, `twitter:*`) can now take
a computed `content` (#883). A fresh round will be run before the release, and this page will be
updated from it ([how](../bench/migration/README.md#updating-the-published-results)).

### Latest round (run 16), by app

| App | Engine | Set | Pages | Matched | Matched, forced rule | Pass | `pass_forced` | New browser errors (pages) | Minutes | Cost |
|---|---|---|---|---|---|---|---|---|---|---|
| spring-petclinic | Thymeleaf | dev | 30 | 30 | 30 | yes | yes | 0 | 9.6 | $2.08 |
| spring-petclinic-boot27 | Thymeleaf | dev | 30 | 30 | 30 | yes | yes | 0 | 6.2 | $1.85 |
| spring-petclinic-boot20 | Thymeleaf | dev | 28 | 28 | 28 | yes | yes | 0 | 8.1 | $2.22 |
| spring-petclinic-boot15 | Thymeleaf | dev | 28 | 28 | 28 | yes | yes | 0 | 9.6 | $2.52 |
| spring-framework-petclinic | JSP | dev | 26 | 26 | 26 | yes | yes | 0 | 10.1 | $3.68 |
| spring-framework-petclinic-spring5 | JSP | dev | 27 | 27 | 27 | yes | yes | 0 | 10.5 | $3.11 |
| spring-framework-petclinic-spring43 | JSP | dev | 27 | 27 | 27 | yes | yes | 0 | 7.9 | not recorded |
| spring-petclinic-mustache | Mustache | dev | 30 | 30 | 30 | yes | yes | 0 | 13.0 | $3.39 |
| jpetstore | Thymeleaf | dev | 59 | 59 | 59 | yes | yes | 0 | 8.3 | $3.49 |
| fredbet | Thymeleaf | dev | 169 | 123 | 169 | no | yes | 0 | 27.6 | $9.09 |
| posters-demo-store | Thymeleaf + htmx | dev | 141 | 41 | 124 | no | no | 0 | 16.7 | $13.73 |
| jobsearch | FreeMarker | dev | 92 | 36 | 92 | no | yes | 0 | 34.4 | $25.08 |
| purl-server | Thymeleaf | held out | 92 | 56 | 92 | no | yes | 0 | 19.5 | $6.78 |
| jprime | JSP | held out | 150 | 110 | 131 | no | no | 186 (62) | 32.5 | $23.21 |
| espd | JSP + Tiles | held out | 60 | 0 | 44 | no | no | 0 | 31.9 | $15.47 |
| budgetmaster | FreeMarker | held out | 121 | 32 | 51 | no | no | 93 (93) | 37.0 | $27.68 |
| luckyblog | Velocity | held out | 64 | 21 | 45 | no | no | 0 | 12.7 | $4.57 |
| ewp-registry | Pebble | held out | 38 | 27 | 34 | no | no | 0 | 29.2 | $7.51 |

Run 16 totals: 9 of 18 strict passes, 12 of 18 with `pass_forced`. "Matched" counts the pages that
are `same` or `reordered`. "Matched, forced rule" adds the pages that compare reports as `forced`.

### Why most larger apps miss strict parity

The differences that remain fall into these kinds:

- **Changes Thwart forces (intended).** Thwart rejects inline `on*` handlers and `javascript:`
  URLs, so a correct migration moves them into a listener script with a `data-*` hook, and every
  page that had one differs. Computed attributes outside Thwart's allow-list move too (a computed
  `hx-get` becomes `data-hx-get`), script values become JSON declarations at the top of the
  script, and so on. Compare's `forced` verdict recognises only the simplest of these shapes.
- **Output now escaped (intended).** Where the original printed a value raw, Thwart escapes it.
- **Stored HTML rewritten by the sanitizer (intended).** Thwart's HTML sanitizer adds
  `rel="nofollow noopener"` to links and a `user-content-` prefix to ids, and `css.url()` quotes
  a CSS URL.
- **Forced changes not adapted to keep parity.** The agent followed a rule but in a way that
  changed the page where another way wouldn't have.
- **Test noise.** Text that differs between any two starts, such as stack traces on error pages
  or a content-hashed script name.
- **Agent mistakes (not intended).**
- **Scripts the CSP blocks (not intended).** Only the browser check catches these.
- **Not yet classified.** The `compare` diff is cut short on long pages, so those can't be
  classified from its output.

Run 16 hasn't been triaged in full, page by page. This table classifies the pages of each app that
compare reports as `differs`, from their diffs and the browser check:

| App | Pages left | What they are | Kinds |
|---|---|---|---|
| fredbet, jobsearch, purl-server | 0 | Every page that differs is `forced` | Pass with `pass_forced` |
| ewp-registry | 4 | Vue component content and attributes moved into JSON `<script>` blocks (Thwart rejects computed `:name` attributes) and a content-hashed `ui-*.js` name, its path now absolute (3 pages, harmless); an absolute URL in a `data-*` attribute now set by a script, and sanitizer `rel` (1 page) | Forced, sanitizer and test noise |
| posters-demo-store | 17 | Computed `hx-*` URLs: the agent moved the query string (or `hx-vals`) into `data-*` attributes that a script reads, and `hx-confirm` text into a hidden element (9 pages). Forms that only htmx submits gain the CSRF token input and lose an `hx-on` handler (6 pages). The customer list has one more customer than the recording (2 pages; every archived posters-demo-store run has it) | Forced, not recognised by compare; not classified |
| espd | 16 | Form actions lost `?lang=` (3 pages) and an error container's id changed (1 page); two overview pages lost a criterion block. Sanitized message links gain `rel="noopener"` (4 pages); Thwart's attribute escaper replaces a stray control character in one tooltip message (3 pages); 3 overview pages cut short | Agent mistakes; sanitizer; escaped (intended); not classified |
| luckyblog | 19 | The avatar's CSS `url(...)` is quoted (13 pages); sanitized posts gain `user-content-` ids (6 pages) | Sanitizer and forced (intended) |
| jprime | 19 | `<meta name="description">` lost (17 pages): the agent wrote one `<meta>` per year from 2015 to 2050, and the seeded year is 2099. A seeded `The <first> speakers`, which the original rendered as markup, now escaped (2 pages). The carousel plugin calls `eval`, which the CSP blocks, so it fails on 62 pages | Agent mistake; output escaped (intended); blocked script |
| budgetmaster | 70 | `data-tooltip` moved to `title` (42 pages where the diff shows both); seeded names with markup in them (`Weekly <shop>`, an unclosed `Savings <b>`, `Old <i>`), which the original rendered as markup, now escaped (42 pages); links gain `rel="noopener"` (4 pages); a broken attribute in the original's markup is gone (1 page); 36 pages cut short. The browser check finds Materialize's `<base>` reported by the `base-uri 'none'` CSP on 93 pages, with no exception logged | Mostly forced, escaped or sanitizer where visible; partly not classified |

So in run 16, three larger apps pass only under `pass_forced` (four, counting jpetstore's strict
pass), and in luckyblog every remaining difference is intended. In ewp-registry the rest is
forced changes and test noise, and in posters-demo-store it is mostly forced changes that compare
doesn't recognise. espd and jprime have real agent mistakes, which the agents' own compare lists
missed. jprime has a script the CSP blocks; budgetmaster has CSP reports with no exception
logged. fredbet's agent removed the `eval` that blocked a script in run 15. budgetmaster can't be
fully classified from the archived output.

#883, merged after run 16 and not yet measured, lets a template set the content of a descriptive
`<meta>` (`description`, Open Graph, Twitter cards) from data. It was a deliberate decision for
that general need. jprime, a held-out app, hit the same limit in run 16, so jprime's description
in the next round won't be a clean held-out measure of that change.

### Cumulative, by group

| Group | Apps | Runs | Strict passes | Build and tests pass | Mean pages matched, over runs | Mean pages matched, app-weighted | Median minutes | Median cost |
|---|---|---|---|---|---|---|---|---|
| PetClinic family (Thymeleaf, JSP) | 7 | 49 | 48 | 49 | 99.5% | 99.6% | 9.4 | $2.51 |
| Mustache PetClinic | 1 | 9 | 5 | 9 | 93.7% | 93.7% | 9.8 | $2.77 |
| Larger apps, development | 4 | 35 | 2 | 35 | 79.3% | 68.5% | 28.7 | $15.83 |
| Larger apps, held out | 6 | 48 | 5 | 48 | 60.2% | 62.1% | 32.4 | $14.62 |
| **All** | **18** | **141** | **60** | **141** | 80.7% | 79.9% | 14.4 | $4.55 |

"Mean pages matched, over runs" is the mean, over every run, of the share of checked pages that
compare reports as `same` or `reordered`, so apps with many runs weigh more (fredbet has 14).
"App-weighted" averages each app's own mean instead. Median cost leaves out the two runs whose
cost wasn't recorded.

The larger apps' seven strict passes are purl-server runs 4, 9, 10 and 11, luckyblog run 2,
jobsearch run 2 and jpetstore run 16. jobsearch run 2 was scored by the first round's crawler,
which was more lenient than `compare`. The first six came before Thwart started rejecting inline
handlers; jpetstore's pages have none to move. The one PetClinic-family run that failed (Spring
Framework PetClinic, first round) also used that crawler.

Across all 141 runs, every migrated app builds and passes all of its tests, and no diff adds any of
the security tripwires above. Two runs left old templates behind: budgetmaster run 4, which
stopped early, and espd run 2, whose session ended before its last step.

fredbet run 12 is an incident, not a result. After 11.3 of its 90 minutes, the agent ran
`kill $(lsof -t -i:8085)` to stop its app. The test image's `lsof` is busybox, which ignores
those options and lists every open file, so the `kill` stopped every process, the agent's
included. The run was recorded as "killed (time budget)" and scored 5 of 169 pages; fredbet run 13
is its re-run. The cumulative tables count it, since they include every archived run. Without it,
no strict-pass count changes, and mean pages matched (over runs, then app-weighted) becomes
81.5% and 70.1% for the larger development apps (34 runs), 69.0% and 65.3% for all larger apps,
91.1% and 85.8% for Thymeleaf, and 81.3% and 80.2% for all runs.

spring-framework-petclinic-spring43 run 16 is a second incident. Its `claude` process exited with
status 143 (terminated) after 7.9 of its 90 minutes, with no result event, so the run has no cost
and no `MIGRATION-REPORT.md`; "killed (time budget)" is the bench's label for any run without one.
The round's notes attribute it to the agent's own `kill` command. Unlike fredbet run 12, the
migration was complete: what it left builds, passes all 62 tests and matches all 27 pages, so it
counts as a strict pass.

### Cumulative, by engine

| Engine | Apps | Runs | Strict passes | Mean pages matched, over runs | Mean pages matched, app-weighted | Median minutes | Median cost |
|---|---|---|---|---|---|---|---|
| Thymeleaf | 8 (4 PetClinic) | 61 | 33 | 89.7% | 85.1% | 11.7 | $3.63 |
| JSP | 5 (3 PetClinic; 1 with Apache Tiles) | 37 | 20 | 80.9% | 82.4% | 14.4 | $3.91 |
| FreeMarker | 2 | 20 | 1 | 52.1% | 52.1% | 35.2 | $26.56 |
| Mustache | 1 | 9 | 5 | 93.7% | 93.7% | 9.8 | $2.77 |
| Velocity | 1 | 7 | 1 | 67.9% | 67.9% | 12.8 | $4.17 |
| Pebble | 1 | 7 | 0 | 79.7% | 79.7% | 38.7 | $8.47 |

Every Thymeleaf and JSP strict pass outside the PetClinic family is purl-server (4 runs) or
jpetstore (run 16).
Coverage is thin for Mustache, Velocity and Pebble, with one app each, and the only Velocity app
(luckyblog) is old, since Spring 5 dropped Velocity. Both FreeMarker apps are large, so that row
says more about app size than about the engine.

### Limitations

- **Small samples.** One run per app per round in most rounds, and the larger apps vary a lot
  between runs (espd matched 50 of 60 pages in one run and 0 in another).
- **Rounds aren't directly comparable.** The first rounds gave the agent only the guide (the
  plugin came later), and the very first was scored by an older crawler. Thwart's rules changed
  between rounds (the rejection of inline handlers, for example, cost every app with one a
  visible difference). The browser smoke check exists only from the last rounds, so earlier runs
  could not fail it; new browser errors fail a run only from run 16 on.
- **One app per engine** for Mustache, Velocity, Pebble and Apache Tiles, and two for
  FreeMarker. One FreeMarker app has a single test, and luckyblog (held out) has none, so "passes
  all of its tests" says nothing there.
- **Not every page is reachable.** Pages that need multipart uploads, cookies the replay tool
  can't set, captchas or htmx request headers aren't checked.
- **The PetClinic family shaped Thwart.** Thwart's own example port (`examples/petclinic`) was
  built from Spring PetClinic. The agents never saw that port, but Thwart's design was shaped by
  it, so the PetClinic results are the best case.
- **Load-time behaviour only.** The browser check clicks nothing, so a handler that breaks only
  when it fires isn't seen. It records errors, not what the page looks like.

## Running it yourself

[bench/migration/README.md](../bench/migration/README.md) is the operator's manual: what you need
(Docker, Python 3, an Anthropic API key), how to run a full round or a quick one, how each step
works, how to add an app, and how to update this page after a round. Per-run output goes to
`bench/migration/results/`, which isn't committed.
