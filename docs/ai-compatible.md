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

**Summary of the results so far.** The latest round (run 15, details in [Results](#results)):

- **PetClinic family** (7 apps, Spring Boot 1.5 to 4.1 and Spring MVC 4.3 to 7, Thymeleaf and
  JSP): all 7 passed strictly. Each migration took 6 to 16 minutes and cost about $2 to $4.50.
  Over every archived run, the family passed strictly in **41 of 42 runs**.
- **Larger real-world apps** (10 apps, 38 to 169 checked pages each; Thymeleaf, Thymeleaf with
  htmx, FreeMarker, JSP, JSP with Apache Tiles, Velocity and Pebble): every migrated app builds
  and passes all of its tests, and each migration took 10 to 51 minutes and cost about $3.50 to
  $32. **But none of the 10 passed strictly, and only 1 passed under the looser `pass_forced`
  rule.** On average 46% of their pages matched. None of the 20 larger-app runs numbered 12 to 15
  passed strictly. Over every archived run, the larger apps passed strictly in 6 of 73 runs (5 of
  42 on the held-out apps), and all six are older runs; 70.6% of their pages matched, on average
  over runs.
- Some of the remaining differences are what Thwart requires (inline event handlers moved out
  for the CSP, output the old engine printed raw now escaped, stored HTML rewritten by the
  sanitizer). Some are agent mistakes, and some are scripts that Thwart's CSP blocks. Strict
  parity on larger apps is the gap Thwart has to close. It is described in
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
   The error names the file, line and column, says what is wrong, and says how to fix it:

   ```text
   error: customers/show.html:7:55: error: onclick on <button> is not allowed: inline event handlers are not supported: a strict CSP blocks them; remove onclick and attach the listener from a <script> with addEventListener, or mark the element with a data-* attribute and handle it with one delegated listener
   ```

   The messages are the detailed documentation for each rule, so an agent's normal loop (edit,
   compile, read the error) is also how it learns Thwart.
3. **A checker.** `thwart-migrate record` saves every page of the old app (body, status and final
   URL) from a list of requests. `thwart-migrate compare` replays the list against the migrated
   app and compares the pages as a browser builds them ([migration guide](guide/migrate.md)). It
   reports each page as `same`, `reordered` (the same elements, with some siblings in another
   order, such as validation messages from a `HashSet`) or `differs`. Markup comparison can't
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
  make the agent pick it up. In all 96 runs that recorded it, the agent invoked the skill.

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
  load. This condition is newer than the latest round (see [Results](#results)).

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
cumulative tables include every archived run: 123 runs, between 2 and 13 per app. Thwart, the
plugin and the scoring changed between rounds, so the cumulative numbers mix setups (see
[Limitations](#limitations)). The latest round, **run 15, ran on 2026-11-15 from Thwart commit
`4e83f9ff`**, one run of each of the 18 apps. It cost $179 in total. Every round so far used the
plugin's earlier layout: a skill named `migrate` with the guide as a single file.

Run 15 was scored before `pass_forced` existed and before new browser errors failed a run: its
browser check was report only. The `pass_forced` and "forced rule" figures for run 15 were
computed afterwards, from its archived `compare` output, with the current scorer. Making browser
errors fail a run changes no run-15 verdict, since none of the 7 strict passes had any.

The plugin and guide have changed since run 15, for example to cover third-party scripts that need
`eval`. A fresh round will be run before the release, and this page will be updated from it
([how](../bench/migration/README.md#updating-the-published-results)).

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

Run 15 totals: 7 of 18 strict passes, 8 of 18 with `pass_forced`. "Matched" counts the pages that
are `same` or `reordered`. "Matched, forced rule" adds the differing pages that the `pass_forced`
rule accepts.

### Why most larger apps miss strict parity

The differences that remain fall into these kinds:

- **Changes Thwart forces (intended).** Thwart rejects inline `on*` handlers and `javascript:`
  URLs, so a correct migration moves them into a listener script with a `data-*` hook, and every
  page that had one differs. Computed attributes outside Thwart's allow-list move too (a computed
  `hx-get` becomes `data-hx-get`), script values become JSON declarations at the top of the
  script, and so on. The `pass_forced` rule accepts only the simplest of these shapes.
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

Run 15 hasn't been triaged in full, page by page. This table classifies the pages of each app that
the `pass_forced` rule doesn't accept, from their diffs and the browser check:

| App | Pages left | What they are | Kinds |
|---|---|---|---|
| jobsearch | 0 | Only handler moves (and `HashSet`-ordered messages) | Passes with `pass_forced` |
| jpetstore | 1 | A seeded first name with `<i>` in it, which the original rendered as markup (a stored XSS) and Thwart prints as text | Output escaped (intended) |
| spring-petclinic-mustache | 7 | Stray `th:*` attributes that the original's templates sent to the browser, which Thwart rejects, are gone | Forced (intended) |
| luckyblog | 19 | The avatar's CSS `url(...)` is quoted (13 pages); sanitized posts gain `rel="nofollow noopener"` and `user-content-` ids | Sanitizer and forced (intended) |
| purl-server | 5 | An empty `<tbody>` on 3 pages: the guide says to write `<tbody>` around rows, and the agent wrapped a conditional row, where putting the `t:if` on the `<tbody>` would have kept parity. Stack traces on 2 error pages | Forced change not adapted; test noise |
| ewp-registry | 4 | Vue component content and attributes moved into JSON `<script>` blocks (Thwart rejects computed `:name` attributes); sanitizer `rel`; a content-hashed `ui-*.js` name | Forced, sanitizer and test noise |
| posters-demo-store | 64 | Computed `hx-*` attributes became `data-hx-*`, `hx-confirm` text moved into hidden elements, the CSRF token moved into a script; 1 page cut short | Mostly forced; not fully classified |
| espd | 11 | Form actions lost `?lang=` on the language-switch pages. A hidden input holds a copy of the page for its PDF export, so it carries the forced script changes. One tooltip has a character that the message file mis-encodes, and it prints differently | Agent mistake; forced; encoding |
| fredbet | 3 | A ranking link lost the `=` in `?mode=`; a settings error page lists options the original left empty; 1 page cut short. The browser check finds vendored jQuery Rotate calling `eval`, which the CSP blocks (3 pages) | Agent mistakes; blocked script |
| jprime | 30 | `<meta name="description">` lost text (17 pages); a seeded `The <first> speakers`, which the original rendered as markup, now escaped (2 pages); 11 pages cut short. The carousel plugin calls `eval`, which the CSP blocks, so it fails on 62 pages | Agent mistake; output escaped (intended); blocked script; not classified |
| budgetmaster | 77 | Script globals declared with `var`, `data-tooltip` moved to `title`, seeded names with `<shop>` and an unclosed `<b>` (`Savings <b>`), which the original rendered as markup, now escaped; 37 pages cut short. The browser check finds Materialize's `<base>` blocked by `base-uri 'none'` on 93 pages, with no exception logged | Mostly forced or escaped where visible; mostly not classified |

So in run 15, one larger app passes with `pass_forced`, and in jpetstore, the Mustache PetClinic
and luckyblog every remaining difference is intended. In purl-server and ewp-registry the rest is
forced changes and test noise. espd, fredbet and jprime have real agent mistakes, fredbet and
jprime have scripts the CSP blocks, and budgetmaster and posters-demo-store can't be fully
classified from the archived output. The guide and skill now say how to handle a script that
needs `eval`; run 15 predates that.

### Cumulative, by group

| Group | Apps | Runs | Strict passes | Build and tests pass | Mean pages matched, over runs | Mean pages matched, app-weighted | Median minutes | Median cost |
|---|---|---|---|---|---|---|---|---|
| PetClinic family (Thymeleaf, JSP) | 7 | 42 | 41 | 42 | 99.5% | 99.6% | 9.4 | $2.60 |
| Mustache PetClinic | 1 | 8 | 4 | 8 | 92.9% | 92.9% | 9.2 | $2.75 |
| Larger apps, development | 4 | 31 | 1 | 31 | 81.7% | 68.0% | 28.8 | $16.06 |
| Larger apps, held out | 6 | 42 | 5 | 42 | 62.4% | 64.8% | 33.4 | $14.62 |
| **All** | **18** | **123** | **51** | **123** | 81.9% | 80.6% | 15.6 | $4.49 |

"Mean pages matched, over runs" is the mean, over every run, of the share of checked pages that
compare reports as `same` or `reordered`, so apps with many runs weigh more (fredbet has 13).
"App-weighted" averages each app's own mean instead.

The larger apps' six strict passes are purl-server runs 4, 9, 10 and 11, luckyblog run 2, and
jobsearch run 2. The last was scored by the first round's crawler, which was more lenient than
`compare`. All six came before Thwart started rejecting inline handlers. The one PetClinic-family
run that failed (Spring Framework PetClinic, first round) also used that crawler.

Across all 123 runs, every migrated app builds and passes all of its tests, and no diff adds any of
the security tripwires above. Two runs left old templates behind: budgetmaster run 4, which
stopped early, and espd run 2, whose session ended before its last step.

fredbet run 12 is an incident, not a result. After 11.3 of its 90 minutes, the agent ran
`kill $(lsof -t -i:8085)` to stop its app. The test image's `lsof` is busybox, which ignores
those options and lists every open file, so the `kill` stopped every process, the agent's
included. The run was recorded as "killed (time budget)" and scored 5 of 169 pages; fredbet run 13
is its re-run. The cumulative tables count it, since they include every archived run. Without it,
no strict-pass count changes, and mean pages matched (over runs, then app-weighted) becomes
84.3% and 69.7% for the larger development apps (30 runs), 71.6% and 66.8% for all larger apps,
92.4% and 85.5% for Thymeleaf, and 82.6% and 81.0% for all runs.

### Cumulative, by engine

| Engine | Apps | Runs | Strict passes | Mean pages matched, over runs | Mean pages matched, app-weighted | Median minutes | Median cost |
|---|---|---|---|---|---|---|---|
| Thymeleaf | 8 (4 PetClinic) | 53 | 28 | 90.7% | 84.7% | 11.8 | $3.78 |
| JSP | 5 (3 PetClinic; 1 with Apache Tiles) | 32 | 17 | 81.9% | 83.5% | 15.2 | $3.91 |
| FreeMarker | 2 | 18 | 1 | 54.2% | 54.2% | 35.2 | $26.56 |
| Mustache | 1 | 8 | 4 | 92.9% | 92.9% | 9.2 | $2.75 |
| Velocity | 1 | 6 | 1 | 73.7% | 73.7% | 13.1 | $4.13 |
| Pebble | 1 | 6 | 0 | 81.1% | 81.1% | 40.5 | $9.18 |

Every Thymeleaf and JSP strict pass outside the PetClinic family is purl-server (4 runs).
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
  could not fail it.
- **One app per engine** for Mustache, Velocity, Pebble and Apache Tiles, and two for
  FreeMarker. One FreeMarker app has a single test.
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
