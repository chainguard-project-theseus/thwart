Migration report: Pebble → Tessera
==================================

The registry's server-rendered pages used Pebble 3.2.2 (`pebble-spring-boot-starter`, `*.peb`
templates in `src/main/resources/templates`, plus a standalone `PebbleEngine` in the console
validator's `ReportUtils`). All of them now render through Tessera 0.1.0-SNAPSHOT
(`tessera-spring-boot-starter`, Boot 3.2 / jakarta).

What changed
------------

### Build and configuration

* `pom.xml`: removed `pebble-spring-boot-starter` and `pebble`; added
  `dev.tessera:tessera-spring-boot-starter` (`${tessera.version}` = `0.1.0-SNAPSHOT`); configured
  `maven-compiler-plugin` with `tessera-compiler` **and** `spring-boot-configuration-processor`
  on `annotationProcessorPaths` (listing paths switches off processor discovery, so the
  configuration processor that used to be discovered had to be listed explicitly),
  `-Atessera.templates=…/src/main/resources/templates`,
  `-Atessera.package=eu.erasmuswithoutpaper.registry.web.templates`.
* `application.properties`: removed `pebble.strictVariables=true`.
* Deleted all 14 `*.peb` templates and `PebbleAsJsonFilter` (the custom `asJson` Pebble filter).

### Templates (`src/main/resources/templates`)

| Pebble | Tessera |
|---|---|
| `generic.peb` (base layout) | `layout.html`, fragment `generic(PageCommons common, String title, Fragment scripts, Fragment content)` |
| `generic-with-toolbar.peb` | `toolbar.html`, fragment `toolbar(common, title, scripts, main)` which itself calls `generic` |
| `heiFilter.peb` (include) | fragment `heiFilter(String pattern)` in `toolbar.html` |
| `index`, `coverage`, `heiSearch`, `status`, `statusPerUrl`, `statusPerEmail`, `schemaValidator`, `iiaHashValidator`, `manifestValidation`, `manifestsOverview`, `validationResult` | same names, `.html` |

`{% extends %}`/`{% block %}` became fragment calls with `Fragment` arguments (`~{::body}`,
`~{::head}`); Pebble macros in `validationResult` became typed fragments (`printKeyValue`,
`printHeaders`).

### Java

* Every page's model now has a `common` attribute (`web/PageCommons` record: dev design flag and
  the CSS/JS/UI-JS fingerprints) instead of four loose attributes.
* View types: `@TemplateView` on `Severity`, `ManifestUpdateStatus`, `UpdateNotice`,
  `HashComparisonResult` and the `manifestoverview` model classes (`ManifestAndHostIndex` made
  public so generated code can call its getters). `HostOverviewInfo` got getters (Pebble read its
  public fields) plus `getCoveredHeiIdRows()`/`getCoveredHeiIdsPadding()`.
* New records for data that used to be maps or library types: `HeiView` (HEI search; `HeiEntry`
  is from a library and its `getOtherIds(String)` is a method with an argument), `FlagView`
  (watched issues; the template used `Optional.get()`), `ApiEntryView` (API validator entries,
  JSON computed in Java with the same Jackson writer the `asJson` filter used),
  `SecurityLegendEntry`.
* `HtmlValidationReportFormatter.getPebbleContext()` (nested `HashMap<String,Object>`) became
  `getReport()` returning the `validators/ValidationReport` record tree.
* Coverage matrix: `CoverageMatrixGenerator.generateToHtmlTable()` built HTML with a
  `StringBuilder` and the page printed it with `| raw`. It now returns a `CoverageMatrix` record
  (rows → cells → lines, plus footnotes) rendered by `coverage.html`; the static table header moved
  into the template. `CatalogueDependantCache` caches the `CoverageMatrix`
  (`get/putCoverageMatrix`); the old `get/putCoverageMatrixHtml(String)` methods are kept because
  `ManifestRepositoryTest` exercises the cache through them.
* Console validator (`ReportUtils.generateHtmlReport`) renders `ValidationResultTemplate` directly
  with a `RenderContext` whose `DefaultLinks` base is the registry URL (replacing Pebble's
  `baseUrl`). Note: the old code looked for `templates/validationResult.pebble`, which never
  existed (files were `.peb`), so it always returned "An error occurred"; it now produces a report.
* `web/TemplateCssClasses` (`@TemplateFunctions("ewp")`): see the shortcomings below.
* New test `UiTemplatesIntegrationTest`: renders pages through the real view resolver and checks
  that user input (`email`, `pattern`, `url` query parameters) is escaped.

### Build tooling

* `spotbugs-exclude.xml` excludes the generated package `eu.erasmuswithoutpaper.registry.web.templates`
  and the PMD configuration excludes `target/generated-sources/annotations` (like the existing
  JAXB exclusions). Spotbugs (threshold Low) and PMD otherwise analyse the generated template
  classes and fail the build on generated-code patterns (redundant null checks, dead stores,
  `Objects.equals` on unrelated types). Nothing in hand-written code is excluded.
* `maven-javadoc-plugin`: `<doclint>none</doclint>`. The unmodified project already failed
  `mvn package` here with three doclint errors in JAXB-generated sources ("unknown tag:
  identifier/description"); `excludePackageNames` can't drop them (they are on the source path
  that `SelfManifestBuilder` needs).

### Test changes

* `ApiControllerIntegrationTest.testScenario1` (one assertion): it checked the raw HTML of the
  status page for `didn't`. Tessera writes `'` as `&#39;` in all escaped/sanitized output (only
  `SafeHtml.unchecked` would avoid that), so the expected string now contains `didn&#39;t`. The
  browser text is the same. No other test was changed; all 375 tests pass.

### Escaping decisions (old `| raw` output)

* `coverageMatrixHtml | raw` → structured model, everything escaped by the template.
* `notice.messageHtml | raw` (manifest status notices) and `flag.name | raw` (watched issues) →
  `html.sanitize(...)`. The stored notices only use `<p>`, `<ul>`, `<li>`, `<code>`, which the
  sanitizer keeps, so the pages are identical; `flag.name` embeds manifest URLs, which used to be
  printed raw (an injection point) and are now sanitized.
* `securityDisclaimer | raw` (constant HTML built in the controller) → the two literal texts moved
  into the template as a conditional.
* `api.* | asJson | raw` inside single-quoted attributes → JSON strings in Java, escaped as
  attribute values (same attribute value after parsing).

No `SafeHtml.unchecked` / `TrustedUrl.unchecked`, no security configuration touched (the app has
no Spring Security), no tests disabled or changed.

Page parity
-----------

`pages.txt` (52 requests) covers: index; manifest importer status; status per URL for every state
(fresh/OK with notices, warnings, unknown URL, URL with markup); status per e-mail (with flags,
without, with markup); coverage matrix unfiltered/filtered/empty/with markup; HEI search empty,
with results, no results, with markup, missing parameter (500); schema validator; manifests
overview; API validator for six manifests and an unknown one (404); IIA hash validator GET and
POSTs with invalid XML, empty input, correct and wrong hashes, a non-IIA document; error pages
(404 XML, unknown params), catalogue. The data is six manifests served over local HTTPS
(test-resource manifests) with an in-memory H2 database.

Final result of `compare` (with `--ignore 'TODAY [0-9:.]+'`): **50 `same`, 2 `differs`**,
statuses and final URLs all as recorded. The two differences are explained below.

Remaining differences
---------------------

* **API validator `<api-entry>` bindings** (`/manifestValidation?url=…` for manifests that have
  validatable APIs): the original emitted Vue shorthand bindings `:security='[...]'`,
  `:parameters=…`, `:security-notices=…`. Tessera cannot compute an attribute whose name starts
  with `:` (`t:`/`t:attr` names must match `[A-Za-z_][A-Za-z0-9_.:-]*`, and `[[...]]` inside a
  static attribute value is copied literally, not evaluated). The page now emits the equivalent
  long form `v-bind:security="[...]"` etc., which Vue treats identically (the Vue app compiles the
  in-DOM template of `#api-entry-set`). `compare` reports these pages as `differs` on the
  attribute name only. The only way to emit `:security` with a computed value would be
  `SafeHtml.unchecked`, which is not allowed.
* **"Last access attempt" timestamps** on `/status?url=…` show the time of the last manifest
  import ("TODAY 14:32:01.09 (moments ago)"), which differs on every run; compared with
  `--ignore 'TODAY [0-9:.]+'`.

Tessera / documentation shortcomings found
------------------------------------------

1. **`__` anywhere in an expression is rejected as Thymeleaf preprocessing**, even inside a string
   literal (`t:class="'ewpsrc__item ewpsrc__item--status-' + s"` → "preprocessing (__...__) is not
   supported"). BEM class names (`block__element--modifier`) are therefore impossible to build in
   a template; I had to add a `@TemplateFunctions("ewp")` class that returns the class names. The
   guide doesn't mention this.
2. **No way to compute attributes whose names start with `:` or `@`** (Vue/Alpine shorthand), and
   inline `[[...]]` in a static attribute is silently emitted literally (no warning or error),
   which is easy to miss. The guide's section 4.3 says "Any `t:<name>` sets that attribute" but the
   name regex is only in the diagnostic catalogue.
3. **Layout composition**: a layout fragment that wraps another layout (toolbar → generic) needs a
   `~{::tag}` selection of an element with an invented tag name plus `t:remove="tag"`, because
   `~{::...}` can't select `t:block`, ids or hyphenated names. Works, but it is not documented as
   a pattern.
4. Non-well-formed but valid HTML (unclosed `<p>`, `</p>` without start tag, rows opened in one
   loop iteration and closed in another) must be rewritten by hand into the DOM the browser built;
   the guide only says "broken markup the old engine passed through".
5. The guide documents the processor and Spring wiring, but not how to render a template from
   non-Spring code inside a Spring app (the generated class name/package and `of(...)` signature
   had to be read from `target/generated-sources/annotations`).
6. Not specified in the guide, found by trying: printing a `@TemplateView` object (`ApiVersion`,
   `Severity`) uses its `toString()`; whether methods of a view-safe `Optional` (`isPresent()`,
   `get()`) may be called is not stated, so I passed plain nullable values instead.
7. The skill tells you to fetch `tessera-migrate` with `mvn dependency:copy` "from
   `github.com/chainguard-dev/harder`" — the jar was only in the local repository; fine here.

Environment notes (not Tessera)
-------------------------------

* The build's `frontend-maven-plugin` downloads Node v26.12.1, whose binary can't run in this
  container (`libatomic.so.1` missing on this aarch64 image), so `npm install` exits 127 —
  this already fails on the unmodified `master`. Locally I built the Vue bundle with the system
  Node (`npm install && npm run build-prod` in `src/main/webapp`) and ran Maven with
  `-Dskip.installnodenpm -Dskip.npm`. The pom's Node version was not changed.
* Final verification: `mvn clean` + Vue bundle with the system Node + `mvn package
  -Dskip.installnodenpm -Dskip.npm` → BUILD SUCCESS on JDK 21 (checkstyle, spotbugs, PMD,
  375 tests, javadoc). JDK 17 (the `java.version`) isn't installed; JDK 21 is the only JDK here
  that can build a Java 17 target.
* Local tooling (not committed, git-excluded): `.tessera/` holds `tessera-migrate.jar`, a build
  helper and the run setup (an HTTPS file server for the manifests, trust store,
  `application.properties`, start script); `baseline/` holds the recording. `pages.txt` is
  committed.
