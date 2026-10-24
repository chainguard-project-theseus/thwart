# Migration report: Pebble → Tessera

## Result

| Check | Status |
|---|---|
| Every page rendered by Tessera; Pebble templates, dependency and configuration removed | Done |
| `mvn verify` (checkstyle, SpotBugs, PMD, all 373 tests) | Passes, see "Build environment" for two problems that were already there and are unrelated to the migration |
| `tessera-migrate compare` against the recorded original app | 43/43 pages `same` (exit 0) |
| Security | No `SafeHtml.unchecked` / `TrustedUrl.unchecked`, no `permitAll`, no CSRF or escaping changes, no test disabled |

## What changed

### Build (`pom.xml`, `spotbugs-exclude.xml`)
- Replaced `pebble-spring-boot-starter` and `pebble` with `dev.tessera:tessera-spring-boot-starter`
  (`${tessera.version}` = `0.1.0-SNAPSHOT`; Boot 3 / jakarta).
- `maven-compiler-plugin`: added `annotationProcessorPaths` with `tessera-compiler`. I also listed
  `spring-boot-configuration-processor` there, because `annotationProcessorPaths` turns off processor
  discovery and the build used it before. I added `-Atessera.templates` and `-Atessera.package=eu.erasmuswithoutpaper.registry.templates`,
  plus `<parameters>true</parameters>`.
- The generated template package is excluded from SpotBugs, PMD (`excludeRoot`) and Javadoc. The
  thresholds for the application's own code are unchanged.
- `mvn dependency:list` before and after: only the Pebble artifacts and `org.unbescape:unbescape`
  went away. The app never used `unbescape`, so nothing had to be declared in their place.

### Configuration
- `application.properties`: removed `pebble.strictVariables=true` (typed `t:params` do the same job)
  and `spring.devtools.restart.additional-paths=src/main/resources/templates` (live reload of
  Pebble templates; Tessera templates are compiled).
- Removed `web/PebbleAsJsonFilter` (the Pebble `asJson` extension). Its only job was to feed Vue
  `:prop='{{ x | asJson | raw }}'` bindings, which are now Tessera `t::prop="${x}"` JSON bindings.

### Templates (`src/main/resources/templates`)
All 14 `.peb` files are gone. Here is what replaced them:

| Pebble | Tessera |
|---|---|
| `generic.peb` | `fragments/layout.html` → `generic(PageCommons page, String title, Fragment scripts, Fragment body)` |
| `generic-with-toolbar.peb` (`extends generic`) | `fragments/toolbar.html` → `toolbar(page, title, scripts, main)`, which calls `generic` (an extends chain, guide 4.10) |
| `heiFilter.peb` (`include`) | `fragments/heiFilter.html` → `heiFilter(String pattern)` |
| `{{ ... \| raw }}` of HTML built in Java | `fragments/html.html` → `nodes(List<HtmlNode>)` / recursive `node(HtmlNode)` (see below) |
| `validationResult.peb` macros | typed fragments in `validationResult.html` |
| every other page | a page template with the same name (`index`, `status`, `statusPerUrl`, `statusPerEmail`, `coverage`, `heiSearch`, `schemaValidator`, `iiaHashValidator`, `manifestValidation`, `manifestsOverview`, `validationResult`) |

### Java
- `web/view/PageCommons` (record): the layout values that every page read implicitly before
  (`isUsingDevDesign` and the CSS, JS and UI-JS fingerprints). `UiController.initializeMavCommons`
  now puts one `page` attribute in the model.
- `UiController`:
  - `statusPerUrl` passes `manifestStatus` (nullable) and `sourcePresent` instead of two
    `Optional`s, because templates can't call `Optional.get()`.
  - `heiSearch` passes `HeiRow` records. `HeiEntry` is a library interface, and the template called
    `otherIds('erasmus')`, which needs an argument.
  - `manifestsOverview` passes a `List` instead of a `Collection`.
  - `validateApi` passes a typed `report`.
  - The security disclaimer HTML string moved from the controller into the template, chosen by
    `page.usingDevDesign`.
- `HtmlValidationReportFormatter`: `getPebbleContext` (nested `Map<String,Object>`) became
  `getReport`, which returns records (`ValidationReport`, `ValidationInfo`, `TestStep`,
  `RequestSnapshot`, `ResponseSnapshot`, `SecurityExplanation`). It computes the same data.
- `consoleapplication/ReportUtils`: renders `ValidationResultTemplate` directly, with
  `DefaultLinks("https://<registry>")`, so the report's CSS, JS and logo links point at the
  registry, as the Pebble `baseUrl` intended. Before, this path loaded `templates/*.pebble`, which
  didn't exist (the files were `.peb`), so it always returned "An error occurred". The report now
  renders.
- `@TemplateView` added to the domain classes that templates read: `Severity`,
  `ManifestUpdateStatus`, `UpdateNotice`, `NotifierFlag`, `HashComparisonResult`, the
  `manifestoverview` classes and `ValidationParameter`. Also:
  - `ManifestApiEntry` and `HostOverviewInfo` got getters for their public fields, because Pebble
    reads public fields and Tessera doesn't.
  - `ManifestAndHostIndex` became `public`.
  - `HostOverviewInfo`'s new `getCoveredHeiIds()` triggers SpotBugs `NM_CONFUSING`, so I
    suppressed it on that class only, with a justification.
- `web/view/HtmlTreeParser`, `HtmlNode` and `ViewFunctions` (`view.tree`, `view.orNull`,
  `view.rowsOfFive`): see "Raw output" below.

### Tests
- New: `UiControllerIntegrationTest.testUserInputIsEscaped` renders `/heiSearch` through the real
  view resolver and checks that `<script>` in the search pattern comes out escaped.
- Changed one assertion in `ApiControllerIntegrationTest.testScenario1`. It matched the raw bytes
  `didn't` in a notice that Pebble printed unescaped. Tessera escapes text, so the bytes are now
  `didn&#39;t`. The text a browser shows is the same. Nothing else in the test changed.

## Raw output (review points)

| Old `| raw` output | Now |
|---|---|
| `notice.messageHtml` (statusPerUrl) and `coverageMatrixHtml` (coverage): HTML strings built in Java. Some notices put manifest data in unescaped, e.g. `EndpointUniqueConstraint`/`ApiUniqueConstraint` print the API URL raw, so a manifest could inject markup into the status page. | `HtmlTreeParser` parses the string into an allow-listed tree: elements `a b br code div em i li ol p pre small span strong sub sup table tbody td th thead tr ul`, attributes `class id title href colspan rowspan`. Everything else is dropped and its text kept. The recursive `fragments/html :: node` fragment renders the tree, so all text and attribute values are escaped and `href` gets Tessera's URL check. Output is identical for the real notices and the matrix (compare: `same`). I didn't use `html.sanitize`: it drops `class`/`colspan`/`rowspan` (the matrix needs them) and adds `rel="nofollow noopener"` to the notices' links, so pages would differ. |
| `flag.name` (statusPerEmail) | Escaped text. The flag names are plain text. |
| `securityDisclaimer` (manifestValidation) | Constant markup, moved into the template. |
| `:security='{{ api.securities \| asJson \| raw }}'` (and `:parameters`, `:security-notices`) | `t::security="${api.securities}"`: Tessera writes JSON, then escapes it for the attribute. The old form would have broken out of the single-quoted attribute on a `'` in a value. Same data, and Vue gets the same value. |

## Page parity

The setup is in `.tessera/work/` (`pages.txt` and `baseline/`, kept out of git):
- I ran the original jar and the new jar the same way, from the same data. Six manifest sources
  were served over local HTTPS (copied from `src/test/resources/test-files`: duplicates, invalid
  entries, a Registry-API implementer, one missing URL and one echo API), with an in-memory H2
  database and the `development` profile.
- `pages.txt` has 43 requests:
  - index; `/status`; `/status?url=` for every source plus an unknown URL with `<b>`;
    `/status?email=` for an admin, an address with `<b>` and an unknown address;
  - `/coverage` with no pattern, a matching pattern, a non-matching one and `<script>`;
  - `/heiSearch` with an empty, matching and `<script>` pattern, and with no pattern (500);
  - `/schemaValidator`;
  - `/iiaHashValidator` GET and POST: not XML, empty, valid hash, wrong hash, v6, and no IIAs;
  - `/manifestValidation` for every source, a missing one (404) and an unknown one (404);
  - `/manifestsOverview`; an unknown path (404 XML).
- Result: **43 same, 0 reordered, 0 differ**. I passed one `--ignore 'TODAY [0-9:.]+ \(moments ago\)'`:
  the manifest's last-access time, which changes on every run, even between two runs of the
  original app.
- `POST /validateApi` (the validation report) takes a JSON body, which `pages.txt` can't express.
  I recorded it from both apps with curl: an echo API run with request/response snapshots, a
  developer message and headers, plus an unregistered URL containing `<b>`. I masked dates and
  compared both with `tessera-migrate compare` by serving the saved pages statically: both `same`.
- Error pages are XML from `RegistryErrorController`, as before, so no template is involved.

## Build environment

These problems already existed on this machine with the original code, and aren't caused by the
migration:
- `frontend-maven-plugin` installs node v26.12.1, and that binary fails here
  (`libatomic.so.1: cannot open shared object file`). I built the Vue bundle with the system npm
  (`npm install && npm run build-prod` in `src/main/webapp`) and ran Maven with
  `-Dskip.installnodenpm -Dskip.npm`. On a machine where that node binary runs, the normal build
  runs those steps itself.
- `maven-javadoc-plugin` (`attach-javadocs`) fails, before and after the migration, with the same
  5 `error: unknown tag` errors in JAXB-generated classes
  (`target/generated-sources/jaxb/...ImobilityTorsGetResponse`, `ManifestApiEntryBase`). None come
  from the application's or Tessera's code. Changing the exclusion to `https.github_com.*` breaks
  symbol resolution, so I left it as it was. With `-Dmaven.javadoc.skip`, `mvn verify` passes.
- The app targets Java 17 (`java.version`), but only JDK 21 and 8 are installed. I built with
  JDK 21 and `release` 17 from the Boot parent.

## Not converted

Nothing. Every view the app renders is a Tessera template, and Pebble is gone from the code,
the build and the configuration. The only remaining mention is a historical line in
`CHANGELOG.md`.

## Where Tessera or its documentation fell short

1. **No sanitizer for app-built table or rich markup.** `html.sanitize` has one policy that drops
   `class`, `colspan` and `rowspan` and adds `rel="nofollow noopener"`. That doesn't fit HTML built
   by the app (the coverage matrix, the notices). The guide's advice (parse into records and render
   them with a recursive fragment) works, but I had to write the HTML tokenizer and entity decoder
   myself (`HtmlTreeParser`, about 250 lines). A configurable allow-list sanitizer, or a
   documented helper for this, would avoid that.
2. **Whitespace between `t:case` siblings is output.** A generic element switch has to be written
   on one line to avoid adding text nodes (`fragments/html.html`). It is mentioned in 4.10, but
   there is no `t:case` form that trims it.
3. **Empty link path variables fail at runtime.** `@{/style-{fp}.css(fp=...)}` with `fp = ""`
   throws `IllegalArgumentException` while rendering. The guide only says that a *null* path
   variable fails, and this isn't caught at build time.
4. **`Optional` in attributes.** An `Optional` prints its value as text, but there is no way to
   unwrap it for `t:href`, so I needed a custom `view.orNull` function. A built-in would help.
5. **Public fields aren't readable** (Pebble reads them). Adding getters is fine, but it triggered
   SpotBugs `NM_CONFUSING` on existing code. Reading public fields of `@TemplateView` classes as an
   option would avoid touching domain classes.
6. **Small documentation gaps I had to resolve by trial or `javap`:**
   - the separator for several `t:import` entries (a comma works);
   - that `~{::fragment(args)}` (a same-file fragment with arguments) works;
   - `Messages.NONE` and `Forms.NONE` for rendering outside Spring;
   - that `DefaultLinks` accepts an absolute base URL as its "context path";
   - that the Spring Boot parent has no `${spring-boot.version}` property for listing the Boot
     configuration processor in `annotationProcessorPaths` (I used `${project.parent.version}`).
7. **`tessera-migrate` can't express JSON request bodies** in `pages.txt`, so JSON-posting pages
   (here `/validateApi`) can't be recorded and compared. I checked that page by hand.
8. Templates live under `src/main/resources/templates` (as the guide shows), so Maven also copies
   the `.html` sources into the jar, where they aren't needed at runtime. It's harmless, but the
   guide doesn't say whether they should be left out.
