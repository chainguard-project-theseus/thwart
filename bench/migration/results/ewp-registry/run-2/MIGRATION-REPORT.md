# Migration report: Pebble → Tessera

All server-rendered pages of the EWP Registry Service are now Tessera templates
(`src/main/resources/templates/*.html`), compiled into
`eu.erasmuswithoutpaper.registry.templates.*Template` by the `tessera-compiler` annotation
processor during `javac`. Pebble is gone: no `.peb` templates, no `pebble` /
`pebble-spring-boot-starter` dependency, no `pebble.strictVariables` property, no Pebble extension
(`PebbleAsJsonFilter`), and the console report no longer uses `PebbleEngine`.

## What changed

### Build (`pom.xml`, `spotbugs-exclude.xml`, `.gitignore`)

- `pebble-spring-boot-starter` and `pebble` replaced by `dev.tessera:tessera-spring-boot-starter`
  (`${tessera.version}` = `0.1.0-SNAPSHOT`; the app is Boot 3 / `jakarta.servlet`).
- `maven-compiler-plugin`: `annotationProcessorPaths` with `tessera-compiler`, plus
  `spring-boot-configuration-processor`. Listing processors turns off discovery, and that one was
  on the classpath before, so it is listed explicitly. Added `-Atessera.templates` and
  `-Atessera.package`, and `<parameters>true</parameters>`.
- The generated template package is excluded from SpotBugs (exclude filter), PMD (`excludeRoot`
  for `target/generated-sources/annotations`) and Javadoc (`excludePackageNames`). The rules for the
  application's own code are unchanged.
- `mvn dependency:list` before and after differs only in the Pebble artifacts and
  `org.unbescape:unbescape`, which the application never imported. Nothing else had to be declared.
- `/.tessera/` (migration tool, recordings, run scripts) is git-ignored.

### Templates

| Pebble | Tessera | Notes |
|---|---|---|
| `generic.peb` | `generic.html`, fragment `layout(page, title, scripts, body)` | `{% block %}`s became `Fragment`/`String` parameters. CSS/JS links use `@{/style-{v}.css(...)}`. |
| `generic-with-toolbar.peb` | `generic-with-toolbar.html`, fragment `toolbarLayout(...)` | Extends `layout` through a `<withtoolbar t:remove="tag">` selection (guide 4.10). |
| `heiFilter.peb` (include) | `heiFilter.html`, fragment `heiFilter(String pattern)` | |
| `index`, `status`, `statusPerUrl`, `statusPerEmail`, `coverage`, `heiSearch`, `schemaValidator`, `iiaHashValidator`, `manifestsOverview`, `manifestValidation`, `validationResult` | same names, `.html` | Pebble macros in `validationResult` became fragments in the same file. |
| — | `richText.html` | Recursive fragment that renders an allow-listed HTML tree (see below). |

The common model values (`isUsingDevDesign`, fingerprints) are still added to every model, plus a
`page` record (`web/PageCommons`). Fragments can't see the caller's variables, so pages pass it to
the layouts.

### Java changes made for the templates

- `@TemplateView` on the classes templates read: `ManifestUpdateStatus`, `UpdateNotice`,
  `NotifierFlag`, `Severity`, `HashComparisonResult`, the `manifestoverview` model classes, the
  coverage-matrix classes and `ManifestApiEntry`. `ManifestAndHostIndex` became public.
- Getters added where Pebble read public fields or called methods with arguments or `Optional.get()`:
  `HostOverviewInfo` getters (and `getCoveredHeiIdRows()`, see below), `ManifestApiEntry` getters,
  `NotifierFlag.getDetailsUrlOrNull()`. `UiController` adds `statusValue` and `sourcePresent` next
  to the `Optional`s it already put in the model for `/status?url=`.
- `HeiEntry` comes from an external library, so `/heiSearch` puts `HeiView` records
  (name, id, first Erasmus code) in the model. Before, the template called `hei.otherIds('erasmus')`.
- `HtmlValidationReportFormatter.getPebbleContext` (nested `Map<String, Object>`s) is now
  `getReportContext`, built from records (`ValidationInfo`, `TestStep`, `RequestSnapshot`,
  `ResponseSnapshot`, `SecurityExplanation`).
- `ReportUtils.generateHtmlReport` (console validator) renders `ValidationResultTemplate` directly,
  with a `RenderContext` whose `DefaultLinks` use the registry URL as the link prefix. Pebble's
  `baseUrl` did the same job. Note: the old code loaded `templates` + `validationResult` +
  `.pebble`, which doesn't exist (the files were `.peb` and the prefix had no `/`), so it always
  returned "An error occurred". It now produces the report.
- `manifestsOverview`: Pebble opened `<tr>` in one `if` and closed it in another, which Tessera
  can't express. `HostOverviewInfo.getCoveredHeiIdRows()` now does the 5-per-row chunking in Java
  and reproduces the same table, including the quirk where a full last row is followed by a row of
  five empty cells. In the old page the browser created that row implicitly, without a class.

### Raw output (`| raw`) and how each case was replaced

| Old raw output | Source | Replacement |
|---|---|---|
| `coverageMatrixHtml` | HTML assembled with `StringBuilder` in `cmatrix` | The `cmatrix` classes now expose a model (`CoverageMatrix` → rows → cells → content lines, plus footnotes). `coverage.html` renders it, with the static table header moved into the template. The Erasmus-code `&nbsp;` is now a U+00A0 character (same DOM). The catalogue cache stores the `CoverageMatrix` (new `get/putCoverageMatrix`; the old String methods stay because `ManifestRepositoryTest` uses them). |
| `notice.messageHtml` (`statusPerUrl`) | HTML generated by the manifest constraints, stored as JSON in the DB | `UpdateNotice.getMessageNodes()` parses it as XML (DTDs disabled) into an allow-listed `HtmlNode` tree (`p code b i em strong ul ol li br pre span a[href]`; other elements are replaced by their content, other attributes dropped). `richText.html` renders the tree recursively, escaping text and URL-checking `href`. A message that isn't well-formed falls back to `html.sanitize`. See "shortfalls" for why not just `html.sanitize`. |
| `flag.name` (`statusPerEmail`) | plain-text flag names, one including a manifest URL | Escaped text. |
| `securityDisclaimer` (`manifestValidation`) | constant HTML in `UiController` | Moved into the template as markup, chosen by `page.usingDevDesign`. Removed from the controller. |
| `api.securities \| asJson \| raw` etc. in Vue `:security='…'` | data from manifests | See "not converted 1:1" below. |

## Verification

- `tessera-migrate record` against the original jar, `compare` against the migrated jar, both
  started the same way (`.tessera/run.sh`, `.tessera/run2.sh`: dev profile, in-memory H2, empty git
  repo, fixed manifest sources):
  - `.tessera/pages.txt` (27 requests: home; status list; status per URL (error with notices,
    URL with `&`, unknown URL with `<script>`); status per e-mail (with flags, unknown address with
    markup); coverage (all, filter with markup); HEI search (empty, pattern, hostile pattern,
    missing parameter → 500); schema validator; manifests overview; manifest validation of an
    unknown manifest (404); unknown path (404); IIA hash validator GET plus POST with empty, not-XML,
    correct-hash, wrong-hash and empty-response input). **27/27 `same`**, with
    `--ignore 'localhost:808[12]'` (the two apps run on different ports and the home page prints the
    root URL) and `--ignore '\d\d:\d\d:\d\d\.\d+ \([^)]*\)'` (last-access timestamps).
  - `.tessera/pages2.txt` (17 requests against data served over HTTPS from local manifests:
    populated coverage matrix with a multi-version cell and an Erasmus code with a space, HEI search
    hits, manifests overview with duplicates, admin notes and provider, status pages with constraint
    notices that contain links, `<ul>` and `<code>`, stale or new status, manifest validation of three
    manifests). **14 `same`, 3 `differs`**: the three manifest-validation pages, explained below.
- `mvn verify` (JDK 21, frontend built with the system Node, see the environment notes):
  **all 376 tests pass** (`Tests run: 376, Failures: 0, Errors: 0, Skipped: 0`), along with
  checkstyle, SpotBugs and PMD. The build then stops at `maven-javadoc-plugin`, on the same 5
  doclint errors in JAXB-generated sources that the unmodified project hits on JDK 21. None are in
  application or template code.
- Test updated: `ApiControllerIntegrationTest.testScenario1` compared raw HTML bytes containing
  `didn't`. Tessera escapes `'` in text as `&#39;` (same text in the browser), so the expected
  string now contains `didn&#39;t`. This is the only change to an existing test, and it asserted
  on Pebble's serialization.
- Tests added: `UiControllerIntegrationTest.testUserInputIsEscapedInRenderedPage` (renders
  `/heiSearch` through the real view resolver and checks that `"><script>` comes out escaped) and
  `HtmlNodeTest`. No test was removed, disabled or weakened. None asserted on Pebble details.

## What could not be converted 1:1

1. **`manifestValidation` → Vue props.** The old page passed JSON to the `<api-entry>` Vue
   component through Vue directive attributes with raw output:
   `:security='{{ api.securities | asJson | raw }}'`. Tessera rejects any expression in Vue
   directives (`:x`, `v-*`), because Vue runs their value as JavaScript. The raw JSON inside a
   single-quoted attribute was also injectable: a `'` in a manifest-supplied security notice would
   have ended the attribute. The server now renders `data-security`, `data-parameters` and
   `data-security-notices` (escaped JSON, built in Java by the same Jackson writer the Pebble filter
   used). `apiEntry.vue` declares them as `dataSecurity`/… string props and parses them in computed
   properties named `security`, `parameters` and `securityNotices`, so the rest of the component is
   unchanged. As a result `compare` reports these pages as `differs`, and that is right:
   (a) the attribute names differ (`:security="[…]"` → `data-security="[…]"`), and (b) the
   `ui-<sha1>.js` fingerprint in the `<script src>` differs because the Vue bundle changed. Pages
   without API entries differ only in (b).
2. **`/validateApi` (validation result page)** takes a JSON `@RequestBody`, which `tessera-migrate`
   (form-encoded POSTs only) can't send, so it is not in either page list. It was converted with
   the same care: the same markup, Pebble's `loop.revindex`/`loop.index` arithmetic reproduced, and
   `<p>{{ macro }}<p>` (where the browser closes the `<p>` before the macro's `<div>`s) written as
   the DOM the browser built from it.

## Environment notes (not caused by the migration)

- The bundled `frontend-maven-plugin` Node (v26.12.1) can't run in this container:
  `libatomic.so.1` is missing. That affects the original build exactly as much. Locally I built
  with `-Dskip.installnodenpm -Dskip.npm` after `npm install && npm run build-prod` with the
  system Node (`.tessera/build.sh`). The pom's frontend configuration is unchanged.
- JDK 17 (`java.version`) isn't installed here; the build ran on JDK 21. On JDK 21,
  `maven-javadoc-plugin` already failed on the original code (5 doclint errors such as
  `unknown tag: description` in JAXB-generated sources under `target/generated-sources/jaxb`).
  I left the Javadoc configuration as it was, apart from excluding the generated template package.

## Where Tessera or its documentation fell short

1. **Misleading diagnostic for a non-public class.** A `Map<ApiHeiAndMajorVersionTuple,
   Map<ManifestAndHostIndex, …>>` failed with ``cannot read 'key': java.util.Map.Entry<…> is not
   view-safe``. The real cause was that `ManifestAndHostIndex` was package-private and not
   annotated. The message blames `Map.Entry` (which the guide says is view-safe) instead of naming
   the nested type. The follow-on "unknown identifier" errors for every `t:with` variable add noise.
2. **`html.sanitize` can't reproduce trusted stored markup exactly.** It always adds
   `rel="nofollow noopener"` to links, and its policy is fixed. For HTML that the application
   generates itself, the only parity-preserving option without `SafeHtml.unchecked` is to write a
   parser and a recursive fragment (what I did). The guide mentions the approach (section 4.10 and
   section 7) but has no worked example. A sanitizer mode without `rel` rewriting, or a
   configurable allow-list, would have saved about 50 lines of Java and a template.
3. **Vue directives with server data.** The guide says to move data to `data-*` or a `<script>`,
   but doesn't mention that this forces a change to the client-side component and its bundle (and
   so to every page fingerprinting that bundle), which `compare` then always reports as `differs`.
   There is no supported way to keep the old DOM.
4. **Resource-URL links with a non-context-path prefix.** `<link href>` and `<script src>` accept
   only a literal-path `@{…}` or a `TrustedUrl`, and a `TrustedUrl` can only be made with
   `TrustedUrl.unchecked`. The console report needs absolute links to the registry. Passing the
   registry URL as the `DefaultLinks` "context path" works, but the documentation doesn't say that
   this is supported. A checked `TrustedUrl` factory (for example https-only) would be clearer.
5. **`t:if` doesn't guard `t:replace`/`t:insert` on the same element.** This is documented, but
   easy to miss. A compiler warning when both are on one element would help: the NPE only shows up
   at render time.
6. **No overview of `t:import` syntax** (comma-separated list vs. wildcard). It worked with
   comma-separated names, but the guide only shows single entries and wildcards.
7. **`tessera-migrate` sends only form-encoded requests**, so JSON endpoints that render views
   (`POST /validateApi`) can't be recorded or compared.
8. **Template-only changes need `mvn clean`**, documented. Combined with this project's
   `maven-clean-plugin` configuration, which deletes `node_modules`, every template iteration costs
   a full frontend reinstall. That's a project quirk, but hot reload (Pebble's
   `spring.devtools.restart.additional-paths` on the templates) no longer does anything for
   templates.
