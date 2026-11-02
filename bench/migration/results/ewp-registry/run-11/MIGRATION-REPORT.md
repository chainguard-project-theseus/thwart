# Migration report: Pebble → Tessera

## Summary

Tessera now renders every server-side page of the registry service (the 10 page templates, the
validation result page, and the two layouts with their fragments). Pebble is gone:

- `pebble-spring-boot-starter` and `pebble` are removed from `pom.xml`.
- All `src/main/resources/templates/*.peb` files are deleted.
- `PebbleAsJsonFilter` (the custom `asJson` filter) is deleted.
- `pebble.strictVariables` and the devtools template reload path are removed from
  `application.properties`.

The starter logs `Found 16 compiled Tessera templates` at startup. It does not log
`another template engine is still configured`.

Results:

- **Static analysis:** checkstyle, PMD and SpotBugs pass.
- **Tests:** all 374 existing tests pass, plus two new ones. I changed one assertion; see "Tests"
  below.
- **Page parity:** 23 of the 32 requests in my `pages.txt` come out `same`. The other 9 differ,
  for the two reasons explained under "What could not be converted 1:1". I could not get those
  pages to `same` without breaking a Tessera rule.

## What changed

### Build (`pom.xml`, `spotbugs-exclude.xml`, `.gitignore`)

- Added `dev.tessera:tessera-spring-boot-starter` (the jakarta version, since this is Boot 3.2).
- `maven-compiler-plugin` now has `annotationProcessorPaths`. The list has
  `spring-boot-configuration-processor`, which the build used to find automatically, and
  `tessera-compiler`. It also passes `-Atessera.templates` and
  `-Atessera.package=eu.erasmuswithoutpaper.registry.templates`, and turns on `<parameters>`.
- Added `tessera-maven-plugin:check`, so that changing only a template triggers a recompile.
- The generated template package is excluded from SpotBugs and PMD, so the rules themselves are
  unchanged.
- `/.tessera/` (the migrate tool and my parity recordings) is in `.gitignore`.

### Templates (`src/main/resources/templates/**/*.html`)

| Old (Pebble) | New (Tessera) |
|---|---|
| `generic.peb` (base layout) | `layout/generic.html`: fragment `layout(title, page, uiScript, body)` |
| `generic-with-toolbar.peb` | `layout/toolbar.html`: fragment `toolbarLayout(title, page, uiScript, main)` that extends `layout` |
| `{% include "heiFilter" %}` | `fragments/heiFilter.html`: `heiFilter(pattern)` |
| macros in `validationResult.peb` | `fragments/validationReport.html`: `printKeyValue`, `printHeaders`, `printRequest`, `printResponse` |
| host table loop in `manifestsOverview.peb` | `fragments/manifestsOverview.html`: `host(hostView, hostNumber)` |
| every page | a page with the same view name, so every controller still returns the same view name |

- The `{% block scripts %}` block was only used for `ui-<hash>.js`, so it became a `boolean uiScript`
  layout parameter. Passing a `Fragment` through two layout levels into `<head>` is rejected
  (`RAW_NOT_FLOW`).
- The values every page needs are now one `PageCommons` record, model attribute `page`. It replaces
  the separate `isUsingDevDesign`, `cssFingerprint`, `jsFingerprint` and `uiJsFingerprint`
  attributes.
- Where the old markup only worked through browser error recovery, I wrote out the tree the browser
  actually built, so the page stays the same:
  - In the help popup, `<p>` was wrapped around `<h4>` and `<ol>`.
  - In the validation report, `<p>` was wrapped around `<div>` macro output.
  - `<tr>` rows sat directly in `<table>` (I added an explicit `<tbody>`).
  - The coverage matrix wrote `<thead>` after the rows.
  - In the manifests overview, `<h3>` headings and nested tables sat inside an outer `<table>`.
  - The "covered institutions" grid opened and closed `<tr>` depending on `loop.index % 5`.

  The last two are now shaped in Java by `ManifestsOverviewView`, which reproduces the browser's
  tree: leading headings, then an empty outer table, then the rest. It also adds the extra unstyled
  row of padding cells that browsers created when the count was a multiple of five.

### Java

- **New view types:**
  - `web/view/PageCommons`
  - `HeiRow`, which replaces the template call to `hei.otherIds('erasmus')`
  - `FlagView`, which unwraps `Optional<String> detailsUrl`
  - `ApiEntryView`
  - `ManifestsOverviewView`
  - `validators/ValidationReport`, which replaces the nested `Map<String, Object>` "pebble context"
  - `cmatrix/CoverageMatrix`
- **`@TemplateView` added to existing classes the templates read:**
  - `ManifestUpdateStatus`, `UpdateNotice`, `NotifierFlag`, `Severity` (a class used like an enum),
    `HashComparisonResult`, `ValidationParameter`
  - the manifest-overview classes (`ApiForHeiImplementationMapping`, `ApiHeiAndMajorVersionTuple`,
    `ManifestAndHostIndex`, `CoveredInstitutionsCounters`, `ImplementedApisCount`,
    `ImplementedApiCount`, `ImplementedApiVersionCount`, `ImplementedApiInfo`, `ApiVersion`)
  - `ManifestAndHostIndex` also had to become `public`.
- **Coverage matrix:** `CoverageMatrixGenerator.generateToHtmlTable()` used to build an HTML string
  that the page printed with `| raw`. It is now `generate()` and returns a `CoverageMatrix` record
  (rows → cells → lines, plus footnotes), and `coverage.html` renders it.
  - The cell, line and footnote classes now build records instead of appending markup. The
    "single plain line goes straight into the `<td>`" rule is kept.
  - `CatalogueDependantCache` has a typed `get/putCoverageMatrix`. The old String methods are kept
    because `ManifestRepositoryTest` uses them.
  - The coverage matrix could not go through a sanitizer instead: sanitizer policies drop tables
    and prefix ids, which would break the `#footnote-…` links.
- **`HtmlValidationReportFormatter.getPebbleContext()`** became `getReport()`, which returns a
  `ValidationReport`.
- **`UiController`:**
  - fills the new view types
  - passes `status` as nullable plus `sourcePresent` instead of two `Optional`s
  - drops `securityDisclaimer`, an HTML string that is now two literal branches in the template
- **Manifest update notices** are HTML stored in the database. They now render with
  `t:utext="notices.html(...)"`, which applies a narrow `HtmlPolicy` (`NoticeFunctions`: `p br b
  code ul li a`, no added `rel`). Before, they were printed raw.
- **`ReportUtils` (console validator HTML report)** now renders with
  `ValidationResultTemplate.of(page, report).renderToString(RenderContext.defaults())`.
  - It was effectively broken before: it looked for `templates/validationResult.pebble`, but the
    file was `.peb`, so it always returned "An error occurred".
  - It now produces the report. Asset links are root-relative, because a resource URL
    (`link[href]`, `script[src]`) must be a literal-path `@{…}` link. That means
    `https://<registryDomain>` can no longer be put in front of them, and the `registryDomain`
    parameter is unused.
- **`RegistryUpdaterImpl`:** fixed the unclosed `<code>` in the "invalid elements inside
  `<apis-implemented>`" notice. The sanitizer would have flattened the nesting anyway.

### Front end

- **`src/main/webapp/app/main.js`:** the Vue root component reads the API entries from a
  `<script type="application/json" id="api-entries">` block (see below).
- **`scripts.js`:** I left it byte-identical on purpose. It is served under a content hash
  (`/scripts-<sha1>.js`) that every page links to, so changing it would have changed every page.

### Tests

- **New:** `UiControllerIntegrationTest.testPagesRenderThroughTessera` renders nine pages through
  the real view resolver. `testIiaHashValidatorEscapesSubmittedXml` checks that the submitted XML is
  escaped in the `<textarea>`.
- **Changed:** one assertion in `ApiControllerIntegrationTest` (test H). It checked the raw HTML of
  a notice containing `didn't`. The notice now goes through the sanitizer, which writes the
  apostrophe as `&#39;`. The assertion now expects `didn&#39;t`. It checks the same content just as
  strictly; only the HTML serialization of one character differs, and the browser tree is the
  same. No test was deleted, disabled or loosened.

## Page parity

How I recorded and compared:

- Both apps were started the same way: `.tessera/run.sh`, H2 in memory, a fresh git repository,
  the `development` profile, root URL `https://localhost:8443`.
- A small local HTTPS proxy served the app's own self-manifests plus several test manifests from
  `src/test/resources/test-files`, so the status, overview, coverage and validation pages have real
  data: duplicates, warnings, errors, a 404 manifest, and HEIs.
- `.tessera/pages.txt` has 32 requests covering:
  - the index page
  - the status list, plus per-URL status in OK, warning, error, unknown and fetch-failed states
  - per-email status, with flags and with none
  - coverage, unfiltered and filtered, including no matches
  - HEI search, empty, with hits and with no hits
  - the schema validator
  - the manifests overview
  - the manifest validator for three manifests, plus its 404
  - an unknown page (404)
  - the IIA hash validator: empty form, invalid XML, correct hash, wrong hash, and no IIAs
- I also recorded and compared a production-design run (root URL
  `registry.erasmuswithoutpaper.eu`, validators off) for 7 pages. All were `same` except `/status`,
  for the reason below.

Result: 23 `same`, 9 `differs`:

| Pages | Why they differ |
|---|---|
| `/status` and `/status?url=…` (manifest known and loaded): 6 requests | The manifest reload link carried the manifest URL in `data-manifest-url="https://…"`. Tessera does not allow a computed string in a non-standard attribute (`data-*`), because client code may run what it reads there. Only numbers, booleans and same-origin `@{…}` links are allowed, and a manifest URL is none of these. I used the guide's recipe for "data your own script reads": a `<input type="hidden" class="ewp-manifest-url">` before the link, the link class renamed to `ewp-manifest-reload-link`, and a static inline script that posts the input's value to `/reload`. The class is renamed so the old handler in `scripts.js` no longer binds to it (see "Front end"). The DOM therefore differs on purpose. Five of these six pages also show `Last access attempt: TODAY hh:mm:ss (moments ago)`, which differs between any two runs. |
| `/manifestValidation?url=…`: 3 requests | The page was a **Vue in-DOM template**: `<api-entry name="{{…}}" :security='{{ … \| asJson \| raw }}'>`, compiled in the browser by Vue's runtime compiler. Tessera does not support client frameworks that compile the server's DOM as a template, and the `:security` JSON could not be computed either. I used the guide's recipe ("Vue props → a named component that reads a JSON block"): the mounted markup is now a static template (`<api-entry v-for="api in apis" :name="api.name" …>`), and the data comes from `[[${apis}]]` in a `<script type="application/json">`. The JSON has the same fields Jackson used to write. The `ui-<hash>.js` fingerprint changes because `main.js` changed. |

Every page not listed above is `same`, including all IIA hash validator states, the coverage
matrix, the manifests overview and the 404 responses.

## What could not be converted 1:1, and why

1. **Manifest reload links** (`status.html`, `statusPerUrl.html`): see the table above. A data
   attribute holding an arbitrary URL has no Tessera form that keeps the DOM unchanged.
   - I did not use a script that copies the values back into `data-manifest-url`: the guide lists
     it as a workaround that brings back what the rule prevents.
   - I did not edit `scripts.js`: its hash would change on every page.
2. **The API validator page** (`manifestValidation.html`): it was a Vue in-DOM template over
   server-rendered data. This is unsupported, so I restructured it as described.
   - The new JSON data is equivalent and the bundle builds.
   - I could not click through the page in a real browser here, so the Vue wiring is checked only
     by the build and by reading the code.
3. **The validation result page** (`POST /validateApi`, JSON body, posted by the Vue app): I
   converted it and it compiles, and the console report renders it. But `tessera-migrate record`
   only sends form posts, so I could not record it or compare it.
4. **Console HTML report:** the asset links lost their absolute `https://<registry>` prefix (see
   `ReportUtils` above).
5. **Notices with broken markup:** the sanitizer flattens nested `<code>`, so a notice relying on
   that renders slightly differently. The only producer was the typo I fixed in
   `RegistryUpdaterImpl`.

## Where Tessera or its documentation fell short

1. **No compliant way to emit a string `data-*` value unchanged.** Every recipe in guide 4.4
   changes the page, yet `compare` only passes `same`/`reordered`. This puts "parity" and "no
   escape hatch" in direct conflict for very common jQuery code (`$(el).attr("data-…")`). The
   guide admits recipes change the page, but neither `compare` nor its report can mark a difference
   as an accepted recipe; `--ignore` only deletes text.
2. **Vue in-DOM templates are unsupported.** Pages built that way need front-end changes and a JS
   rebuild, which also changes content-hashed asset URLs.
3. **Content-hashed assets.** Any recipe that touches a shared script changes its hash on every
   page. To avoid that I kept `scripts.js` unchanged and inlined the new handler. The guide only
   suggests masking the hash with `--ignore`, which a fixed external check cannot do.
4. **The resource-URL rule** (literal-path `@{…}` only) blocks an absolute base URL for
   stylesheet and script links in emails or offline reports rendered outside a request. Nothing in
   the guide covers that case.
5. **Sanitizer output is not DOM-identical** to what the old engine wrote raw:
   - it writes `'` as `&#39;`, which broke an existing test that asserted on raw HTML
   - it flattens nested inline elements such as `<code><code>`
   - it cannot keep tables, and always prefixes ids with `user-content-`

   So an app-generated HTML string such as the coverage matrix had to be rebuilt as records,
   about 150 lines of Java changes.
6. **`Optional`** is listed as view-safe, but `opt.get()` is rejected ("not a property"). The
   guide never says how to read an `Optional`'s value; I unwrapped it in a view record.
7. **Fragment parameters into `<head>`.** The guide's layout example passes a `head` `Fragment`
   into `<head>`. Passing such a parameter through a second layout level (toolbar → generic) is
   rejected with `RAW_NOT_FLOW`, and the message ("parses this Fragment value in the <head>")
   does not say the cause is that the fragment's content is unknown at that call site. I replaced it
   with a boolean.
8. **Build snippet:** with `annotationProcessorPaths`, every processor needs an explicit
   `<version>`. Under the Boot 3.2 parent, `${spring-boot.version}` does not resolve (I used
   `${project.parent.version}`). The guide's snippet says to list every processor but not this.
9. **Printing a `@TemplateView` object.** `Severity` is a class used like an enum. The compiler
   first said it "is not view-safe" for output. After annotating it, printing it uses `toString()`,
   which is what I wanted, but the guide does not document that printing a view bean calls
   `toString()`.
10. **Error-recovered markup must be written out by hand.** The verifier (correctly) rejects
    `<p>` around block content, but to keep parity I had to write the tree the browser built (empty
    `<p></p>` before and after) instead of the obvious fix. The guide's `PARSE_ERROR` row suggests
    closing the `<p>`, which changes the page.

## Environment notes (not caused by the migration)

- **Front-end build:** `frontend-maven-plugin` downloads Node 26 from nodejs.org, but that binary
  cannot run in this sandbox (Wolfi: `libatomic.so.1` is missing). So `npm install` fails in the
  normal build, before and after the migration. For local verification I ran `npm install` and
  `npm run build-prod` with the system Node 22, then Maven with
  `-Dskip.installnodenpm -Dskip.npm`. I did not change the plugin configuration.
- **Javadoc:** `mvn verify` on JDK 21 stops at `maven-javadoc-plugin:jar` because of 5 doclint
  errors in JAXB-generated sources (`unknown tag: identifier/description`). The untouched original
  fails in exactly the same way. Excluding those packages breaks javadoc's symbol resolution, so I
  left it as it was. Everything before that step passes: compile, checkstyle, PMD, SpotBugs, all
  tests, and packaging.
- **JDK:** the project targets Java 17. Only JDK 21 and 8 are installed, so I built with JDK 21 at
  release 17.

## Security

- No Spring Security in this app, so no `permitAll` or CSRF settings were involved. Nothing in
  Tessera's escaping was turned off.
- There is no `TrustedUrl.unchecked`; the 0.1.0 runtime has no such class.
- These values used to print raw and are now handled safely:
  - the coverage matrix: now data, escaped
  - notifier flag names: escaped
  - stored update notices: sanitized
  - the security disclaimer: static markup in the template
  - the API entries' JSON: script-safe JSON in a data block instead of HTML attributes
