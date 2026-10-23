# Migration report: JSP + Apache Tiles → Tessera

## Summary

All server-rendered pages of `espd-web` are now Tessera templates under
`espd-web/src/main/resources/templates`, compiled by the `tessera-compiler` annotation processor during
the normal `javac` run. JSP, JSTL, the Spring form tags, Apache Tiles, `tiles.xml`, Jasper and the JSP
view configuration are gone.

Build: Spring Boot stays at 1.4.1 (javax servlet), so the app uses `tessera-spring-boot-starter-javax`.
The project now targets Java 8 (`java.version` 1.7 → 1.8), because Tessera's runtime, starter and
generated code are Java 8 class files. Build and tests: `mvn clean verify` with JDK 8
(`JAVA_HOME=/usr/lib/jvm/java-1.8-openjdk`).

## What changed

### Build (`pom.xml`, `espd-web/pom.xml`)
- `java.version` 1.8; new `tessera.version` property.
- Removed `tomcat-embed-jasper`, `javax.servlet:jstl`, `org.apache.tiles:tiles-jsp` (and the Tiles
  version/dependencyManagement entry, and the `provided` Jasper/EL entries of the `non-embedded` profile).
- Added `dev.tessera:tessera-spring-boot-starter-javax`.
- Tiles had brought `commons-beanutils` 1.9.2 and `commons-collections` 3.2.2 transitively; the domain and
  XML code import both, so they are now declared explicitly (`commons-logging` excluded from beanutils,
  as the enforcer bans it; `jcl-over-slf4j` provides it). `mvn dependency:list` before/after differs
  only by Tiles/JSTL/Jasper/digester leaving and Tessera arriving.
- `maven-compiler-plugin` pinned to 3.8.1 (the Boot 1.4 parent pins 3.1, which ignores
  `annotationProcessorPaths`), with Lombok then `tessera-compiler` as processors and
  `-Atessera.templates`, `-Atessera.package=eu.europa.ec.grow.espd.templates`,
  `-Atessera.messages=.../i18n/messages_en`.
- Resource filtering excludes `templates/**` (filtering would rewrite `${...}` in templates).
- The enforcer's `requireMavenVersion` range `[3.0.3,3.5.0]` was widened to `[3.0.3,)`: the original
  build already failed with the installed Maven 3.9 (and the checked-in `mvnw` cannot run: its
  wrapper jar is not in the repository).

### Java
- `web/PageInfo` (`@TemplateView`) + `web/PageModelInterceptor`: everything the JSPs read from outside
  the model — `pageContext.request.contextPath`, `pageContext.response.locale`, `@espdConfiguration`
  beans via `<s:eval>`, `Language.VALUES`/`Country.VALUES` statics, the session `espd` used for the
  eCertis country, the breadcrumb messages computed from configuration — is computed once per request
  and put in the model as `page`. `page.i18n[key]` (`web/MessageMap`) replaces the `I18NFunc` maps for
  message keys computed from data (it keeps the old `"? key"` fallback for missing keys).
- `util/I18NFunc` (built on `javax.servlet.jsp.PageContext`) was deleted.
- `WebConfiguration`: Tiles view resolver and `TilesConfigurer` removed; interceptor registered. The
  `ResourceUrlEncodingFilter` bean is kept: the starter registers it only when the resource chain is
  enabled (it is not here), and removing it would also change the filter chain.
- `EspdController`: the Tiles definition names `flow_agent_step` are replaced by `wizardView(...)`,
  which returns the template name and puts the effective Tiles attributes `agent`/`flow` in the model
  (only the request flow keeps its own agent; overviews and the whole response flow rendered with
  `agent=eo, flow=response` in `tiles.xml`), plus the raw path variables (`pathAgent`, `pathFlow`,
  `step`) which nested JSPs saw through Spring's exposed path variables.
- `web/WizardPreparation`: the JSP scriptlets that changed the form object while rendering (adding an
  empty representative, empty unbounded requirement groups, ...) now run before rendering.
- `EspdInitializationParameters` is public and `@TemplateView`; all domain classes in
  `eu.europa.ec.grow.espd.domain` are `@TemplateView`.
- `ServerErrorController` + `templates/500.html` replace `500.jsp`, which `web.xml` used as the
  container 500 page for WAR deployments (`<location>/500</location>`).

### Templates
- `layout.html` (was `espdTemplate.jsp` + `header.jsp`, `breadcrumb.jsp`, `footer.jsp` in
  `fragments/header.html`), `welcome`, `contact`, `about`, `error`, `sessionExpired`, `filter`, `500`,
  `wizard/procedure` + `wizard/procedureForm` (shared with the overview), `fragments/wizard.html`
  (progress bar, footer buttons, session timer), `fragments/common.html` (country select).
- Exclusion, selection, finish and overview (print) pages and the criteria fragments: see
  "Criteria pages" below.

### Messages
- `phase_out_text` contains `<a href=" https://...">` (leading space), which Tessera's build-time HTML
  check for `t:utext` rejects. Its six text parts were added as `phase_out_text_1..6` in all 25 locale
  files (the original key stays, the client-side translator still uses it), and the markup moved into
  `filter.html`, so the page is unchanged. (In `messages_et` a trailing "." after the last `</p>` is
  not carried over.)
- Keys that exist only in `messages.properties` (not in `messages_en.properties`, the bundle checked at
  build time) are read through `page.i18n['key']`.

### Docs
`espd-docs` (`view.adoc`, `overview.adoc`, `configuration.adoc`) now describe the Tessera views.

## Verification
- `mvn clean verify` (JDK 8): passes, all existing tests pass; one new Spock test
  (`TemplateEscapingTest`) renders a page through its layout and checks that model values are escaped.
- Parity: `.tessera/pages.txt` (74 requests: every page, both flows, CA/CE/EO, add/remove rows, forms
  re-rendered with binding errors, 400/404/500 error pages) recorded from the original app and
  compared with `tessera-migrate compare`.

## Remaining differences and why

1. **Inline script values on every page.** The layout passes the eCertis URL and country to
   `EcertisHandler(...)` inside a `<script>`. Tessera writes `[[...]]` in scripts as JSON, escaping
   `/` as `\/` and `&` as `&`, where the JSP printed the URL raw. The JavaScript string value is
   identical; only the source text differs. There is no Tessera construct to print a value raw in a
   script, and moving the value to a `data-*` attribute would change the DOM instead. `compare` was run
   with `--ignore 'EcertisHandler\([^)]*\)'` to look past this one known difference.
2. Build timestamp (`Last Update:` / `Last built on:`) and `;jsessionid=` differ between any two runs
   of either app (masked with `--ignore`).

## Where Tessera or its documentation fell short
- **JSON in scripts can't match a raw-printed value**: no way to emit a configuration string in a
  script without JSON escaping (see above). The guide says such a compare difference is "expected",
  but `compare` still reports it as `differs`, so a parity check can't pass without `--ignore`.
- **`-Atessera.messages` checks one bundle file.** Spring resolves `messages_en` and falls back to
  `messages.properties`; keys only in the fallback fail the build, so they go through an unchecked
  map lookup.
- **Allow-list rejects harmless markup in messages** (an `href` with a leading space); the fix needs the
  bundle split in every locale.
- **Boolean getters `getIsX()`** (Lombok on `Boolean isX`) can't be bound with `t:field`
  (`isSmallSizedEnterprise`); the radio buttons had to be written by hand with `name` and `t:checked`.
- **Radios get no `id`**: Spring's generated ids (`path1`, `path2`) had to be written literally.
- **`Field` arguments can't be indexed by a loop variable** (#193): the representative's country select
  is inlined instead of using the country fragment.
- **No `null`→selected placeholder**: Spring's `form:option value=""` is selected when the bound value
  is null, Tessera's is not; needs an explicit `t:selected`.
- **ResourceUrlEncodingFilter**: the guide says to remove a hand-written one, but the starter only
  registers it with the resource chain enabled; removing it would change the app.
- **Tiles semantics the guide doesn't mention**: Spring exposes path variables as request attributes,
  so nested Tiles JSPs that didn't import an attribute saw the path variable of the same name.
- Missing in the docs: how to render error-page markup for a WAR `web.xml` `<error-page>` (a JSP path
  there has no Tessera equivalent; a controller endpoint was needed).
