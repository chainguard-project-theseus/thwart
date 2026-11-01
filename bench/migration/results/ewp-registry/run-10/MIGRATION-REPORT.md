# Migration report: Pebble → Tessera

The EWP Registry Service's server-rendered UI was rendered by Pebble 3.2.2
(`pebble-spring-boot-starter`, 14 `.peb` templates, one custom Pebble extension). Every page is
now a Tessera template compiled by `javac` into `eu.erasmuswithoutpaper.registry.templates`.
Pebble's templates, dependencies and configuration are gone.

## What changed

### Build and configuration

- `pom.xml`
  - removed `io.pebbletemplates:pebble-spring-boot-starter` and `io.pebbletemplates:pebble`;
  - added `dev.tessera:tessera-spring-boot-starter` (Boot 3 / `jakarta.servlet`), version in
    `tessera.version`;
  - `maven-compiler-plugin`: `annotationProcessorPaths` with `tessera-compiler` **and**
    `spring-boot-configuration-processor` (it was discovered from the classpath before, and
    `annotationProcessorPaths` turns discovery off), plus `-Atessera.templates` and
    `-Atessera.package`;
  - `tessera-maven-plugin` (`check` + `review`, with `failOnUnchecked=true`, so a
    `SafeHtml.unchecked`/`TrustedUrl.unchecked` call fails the build);
  - the generated package is excluded from SpotBugs (`spotbugs-exclude.xml`), PMD
    (`excludeRoot`), Checkstyle (`excludes`) and Javadoc (`excludePackageNames`);
  - two unrelated build fixes (Node version, Javadoc lint of JAXB sources), see "Build fixes
    outside the migration".
- `application.properties`: removed `pebble.strictVariables=true` (typed `t:params` replace it).
- `mvn dependency:list` before/after: the only artifacts that disappeared are
  `pebble-spring-boot-starter`, `pebble-spring6`, `pebble` and their own transitive
  `unbescape`; nothing the application imports came with Pebble, so no dependency had to be
  declared. The starter brings only `tessera-runtime`.
- No "another template engine is still configured" warning at startup; the starter logs
  `Found 14 compiled Tessera templates`.

### Templates (`src/main/resources/templates`)

| Pebble | Tessera |
|---|---|
| `generic.peb` (`{% block %}` base) | `fragments/generic.html`, fragment `generic(title, cssFingerprint, jsFingerprint, Fragment scripts, Fragment body)` |
| `generic-with-toolbar.peb` (extends `generic`) | `fragments/toolbar.html`, fragment `toolbar(...)` that replaces itself with `generic` (extends chain, guide 4.10) |
| `heiFilter.peb` (`{% include %}`) | `fragments/heiFilter.html`, fragment `heiFilter(String pattern)` |
| `index`, `coverage`, `heiSearch`, `iiaHashValidator`, `manifestValidation`, `manifestsOverview`, `schemaValidator`, `status`, `statusPerEmail`, `statusPerUrl`, `validationResult` | same names, `.html` |

`validationResult`'s Pebble macros became same-file fragments (`keyValue`, `headers`).

### Java

- Pages read typed view data instead of Pebble's reflective access:
  - new records in `web/views` (`HeiRow`, `ManifestStatusRow`, `ManifestStatusView`,
    `RecipientFlagRow`, `ApiEntryView`, `ManifestOverviewRow`), built in `UiController`;
  - `validators/ValidationReport` (records) replaces the nested `Map<String, Object>` "Pebble
    context" of `HtmlValidationReportFormatter` (`getPebbleContext` → `getReport`);
  - `@TemplateView` on the existing value classes the templates read directly:
    `HashComparisonResult`, `ValidationParameter`, and the manifest-overview statistics classes
    (`ImplementedApisCount`, `ImplementedApiCount`, `ImplementedApiVersionCount`,
    `ApiForHeiImplementationMapping`, `CoveredInstitutionsCounters`,
    `ApiHeiAndMajorVersionTuple`, `ManifestAndHostIndex` – the last one made `public`, as
    view types must be).
- **Coverage matrix**: `CoverageMatrixGenerator` built the whole table as an HTML string
  (`StringBuilder`, `HtmlUtils.htmlEscape`, an `isContentSafeHtml` flag) printed with `| raw`.
  It now builds a `CoverageMatrix` record tree (rows → cells → lines, footnotes) that
  `coverage.html` renders; the static table header moved into the template.
  `CatalogueDependantCache` caches the `CoverageMatrix` instead of the HTML string.
- **Raw output** (`| raw`), each reviewed:
  - `coverageMatrixHtml` – see above (markup built in Java → records + template);
  - `securityDisclaimer` – constant HTML built in the controller → moved into
    `manifestValidation.html` as a condition on `isUsingDevDesign`;
  - `flag.name` (`statusPerEmail`) – plain text (`Manifest import status for '<url>'.`) → now
    escaped;
  - `notice.messageHtml` (`statusPerUrl`) – HTML that the Registry's constraints and updater
    write → printed through `notices.html(...)` (`web/NoticeFunctions`), an `HtmlPolicy` that
    allows exactly the tags those messages use (`p br b i code ul li a`) and adds no `rel`;
  - `asJson | raw` in Vue attributes – see "API validator" below.
- `PebbleAsJsonFilter` (the `asJson` Pebble extension) is deleted.
- **Console validator** (`ReportUtils.generateHtmlReport`) rendered `validationResult` with a
  hand-built `PebbleEngine`. It now calls `ValidationResultTemplate.of(...)` with a
  `RenderContext` whose `Links` prefix the Registry URL (what Pebble's `baseUrl` did). Note: the
  old code loaded `templates/validationResult.pebble`, which never existed (the file is `.peb`),
  so the console HTML report always fell back to "An error occurred"; it now renders.

### Front-end

- `src/main/webapp/app/main.js`: the Vue app gets the API list from a
  `<script type="application/json" id="api-entries">` block and renders
  `<api-entry v-for="api in apis" :name="api.name" ...>` (see below).
- `status.html` and `statusPerUrl.html` carry a small static inline script that copies the
  manifest URL from a hidden input to the reload link's `data-manifest-url` (see below);
  `scripts.js` itself is unchanged, so its fingerprinted URL stays the same on every page.

### Tests

- New: `UiControllerIntegrationTest.testUserInputIsEscaped` (renders `/status?email=<script>…`
  through the real view resolver and checks it is escaped) and
  `ValidationResultTemplateTest` (renders the validation report, which no GET/POST form page
  reaches, with request/response snapshots and hostile values).
- Updated, because they asserted on details of the old view technology:
  - `ManifestRepositoryTest.testCatalogueDependantCache` stored/compared HTML strings in the
    coverage-matrix cache; it now stores `CoverageMatrix` objects (same cache semantics
    checked).
  - `ApiControllerIntegrationTest.testScenario1` expected the notice text byte-for-byte as
    Pebble printed it raw (`didn't`); the sanitizer re-escapes text, so it is `didn&#39;t` (the
    same text in the browser). Only that literal changed.

## Page parity

The request list is `pages.txt`, the recording of the original app is `baseline/`.
`.tessera/` (the `tessera-migrate` jar and local builds) is git-ignored.

`pages.txt` (36 requests) covers every page and state I could reach: index; importer status
list; per-URL status for an OK manifest, a manifest with warnings, a failing (404) source, an
unknown URL and an HTML-injection URL; per-recipient status with flags, without flags and with
an injection string; the API validator for three manifests and an unknown one (404); the
schema validator; the IIA hash validator empty, with empty/invalid XML, a correct v7 IIA, a
wrong-hash v7 IIA and a v6 IIA; the coverage matrix unfiltered, filtered, empty and with an
injection pattern; the HEI search empty, with results, without results, with an injection
string and without the required parameter (500); the manifests overview; the 404 and 500
error responses. The app ran with an in-memory H2 database and 13 demo manifests (from
`src/test/resources/test-files`) served over local HTTPS. A second, shorter list was checked
with the production root URL (non-dev design, validators disabled).

Result (`compare --ignore '\d\d:\d\d:\d\d(\.\d+)?'`, masking only the clock time of the
"last access attempt"):

- development root URL, 36 requests: **28 `same`, 8 `differs`** – `/status`, four
  `/status?url=…` pages of fetched manifests, and three `/manifestValidation?url=…` pages. Every
  diff line is one of the two by-design changes below; I checked them all (`differs` lines grouped
  and counted).
- production root URL, 10 requests (index, status pages, coverage, HEI search, manifests
  overview, and the validator pages, which are 404 there): **8 `same`, 2 `differs`** (`/status`
  and `/status?url=…`, reload link only).

So `compare` does not exit 0; each remaining difference is explained here.

### Remaining differences, and why they are right

1. **Manifest reload link** (`/status`, and `/status?url=…` for a manifest that has been
   fetched): Pebble wrote `<a class="ewp-manifest-reloader" data-manifest-url="{{ url }}">`.
   Tessera does not let a computed string into a `data-*` attribute (client code may execute
   what it reads there), and there is no override. The URL is now in
   `<input type="hidden" class="ewp-manifest-url" value="…">` next to the link, and a static
   inline script sets the link's `data-manifest-url` from it, so `scripts.js` (unchanged) works as
   before. The rendered page differs by the hidden input and the script.
2. **API validator** (`/manifestValidation?url=…` for a known manifest): Pebble wrote one
   `<api-entry name="{{…}}" … :security='{{ x | asJson | raw }}'>` per API. These are Vue
   bindings: Vue evaluates `:security` as a JavaScript expression, and the JSON was printed raw
   inside a single-quoted attribute, so a `'` in manifest data (manifests are written by third
   parties) closed the attribute – an injection point. Tessera rejects any computed string in
   these attributes. The data now goes in a JSON block that `main.js` reads, and the server
   page contains one `<api-entry v-for="api in apis" …>` element with static bindings; after
   Vue mounts, the DOM is the same as before. The `ui-<fingerprint>.js` URL changes too,
   because `main.js` changed.
3. **Time of the last access attempt** on `/status?url=…` is the time of the fetch, which is
   different on every run; it is masked with the narrow `--ignore` above (the formatter drops
   trailing zeros of the milliseconds, hence `(\.\d+)?`).

Pages that print user input (`/status?email=<script>…`, `/heiSearch?pattern="><script>…`,
`/coverage?pattern=<script>`, `/status?url=<b>…`) were already escaped by Pebble's autoescaping
and are `same`.

## What Tessera or its documentation did not cover

- **No string can go into a `data-*` attribute, even one read only as text by the page's own
  jQuery** (`$(el).attr('data-manifest-url')`). The recipes (hidden input, JSON block) change
  the markup, so pages that used such attributes can never compare `same`. The guide says
  `compare` is the acceptance check but does not say that these recipes always produce a
  `differs` page you then have to justify.
- **Fingerprinted assets**: editing a JS file the layout links with a content hash (here
  `scripts.js`) changes the URL in every page and makes every page `differs`. The recipes
  suggest "read it from your script" without warning about this. I kept `scripts.js` unchanged
  and used an inline script instead.
- **`TrustedUrl` has only `unchecked`**: there is no checked factory (e.g. for an absolute
  `https` URL), so a page rendered outside a request whose resource links need another origin
  (the console report links the Registry's stylesheet) needs a custom `Links`
  implementation. That works but is not documented as the way to do it.
- **Error-recovery markup is a build error.** The old `manifestValidation` help text had
  `<p><h4>…</h4>…</p>` and `manifestsOverview` nested `<h3>`/`<table>` inside a `<table>` and
  emitted stray `<td>`s after a closed `<tr>`. The verifier's messages were clear for the
  first; for the table it accepted my rewrite, but I had to work out the foster-parenting
  result (the outer table ends up empty before the first host table; trailing cells get an
  implicit `<tr>` without a class) by hand to keep the page `same`. A note in the guide on
  reproducing the DOM a browser built from broken markup would help.
- **`t:replace` is evaluated before `t:each` on the same element** (as in Thymeleaf); this is
  documented, but the error ("unknown identifier 'header'") does not mention the precedence.
- **Spring Boot 3 parent and `annotationProcessorPaths`**: listing
  `spring-boot-configuration-processor` needs an explicit version (`${spring-boot.version}` is
  not defined by the Boot 3.2 parent; `${project.parent.version}` works). The guide says to
  list every processor, but not how to version a Boot-managed one.
- **Sanitizer output is not byte-identical** to trusted HTML that was printed raw (`'` becomes
  `&#39;`), which breaks tests that match rendered bytes. Expected, but worth a sentence in the
  guide next to the sanitizer.
- The guide's Pebble notes do not mention that `{% for %}` over a `Map` yields entries, or
  `loop.revindex` (mapped here to `stat.last` / `stat.size - stat.index`).

## Build fixes outside the migration

`mvn clean verify` did not pass on this machine before the migration either. Two changes make it
pass; both are independent of Tessera and easy to revert:

- **Node.js version.** The last-but-one commit (`73b4382`, "Upgrade node.js (20.9.0 ->
  26.12.1)") pins Node 26.12.1 for `frontend-maven-plugin`. The official Node 26 binary needs
  `libatomic.so.1`, which this (Wolfi) build host lacks, so `npm install` exited with 127
  before any Java was compiled. I pinned **Node 24.11.0** (current LTS), whose official
  binary runs here and builds the upgraded Vue/webpack toolchain. Hosts with `libatomic` can
  go back to 26.
- **Javadoc.** `attach-javadocs` (bound to `package`) failed on JDK 21 with
  `error: unknown tag: identifier` / `description`: HTML from the XSD documentation copied into
  the JAXB-generated `https.github_com…` sources. The errors were the same before and after the
  migration. I added `-Xdoclint/package:-https.*`, which turns doclint off for those generated
  packages only; the application's own Javadoc is still linted as before.

With these, `mvn clean verify` (JDK 21, the default; the code targets `java.version` 17) passes
with nothing skipped: frontend build, Checkstyle, SpotBugs, PMD, Javadoc, 374 tests, and
`tessera:review` (0 unchecked calls, 1 reviewed `@TemplateFunction`).

## Not covered by `compare`

- Validating an API (`POST /validateApi`, JSON body) can't be expressed in `pages.txt` (form
  bodies only) and needs a live EWP endpoint, so `validationResult` is covered by
  `ValidationResultTemplateTest` instead of `compare`.
