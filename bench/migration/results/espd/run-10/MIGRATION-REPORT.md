# Migration report: JSP + Apache Tiles → Tessera

## What changed

### Build
- `pom.xml`: `java.version` 1.7 → 1.8 (Tessera's processor, runtime and generated code are Java 8 class
  files; the app's JDK is now 8), `tessera.version` property, Tiles removed from `dependencyManagement`.
- `espd-web/pom.xml`:
  - removed `tomcat-embed-jasper`, `javax.servlet:jstl`, `org.apache.tiles:tiles-jsp` (and Jasper from the
    `non-embedded` profile);
  - added `dev.tessera:tessera-spring-boot-starter-javax` (Boot 1.4 / `javax.servlet`);
  - `maven-compiler-plugin` pinned to 3.8.1 (Boot 1.4's parent pins 3.1, which ignores
    `annotationProcessorPaths`), with Lombok and `tessera-compiler` as processors and
    `-Atessera.templates`, `-Atessera.package=eu.europa.ec.grow.espd.templates`,
    `-Atessera.messages=.../i18n/messages_en` (every `#{key}` is checked at build time; `messages_en`
    because `messages.properties` is partial and the default locale is English);
  - `src/main/resources/templates/**` copied without resource filtering (the module filters resources);
  - declared `commons-beanutils` 1.9.2 and `commons-collections` 3.2.2, which the application imports
    directly and which only came in transitively through Tiles (found by diffing `mvn dependency:list`
    before/after, `migration/deps-before.txt` / `deps-after.txt`); `commons-digester` is no longer pulled
    and nothing uses it;
  - the enforcer's `requireMavenVersion` range `[3.0.3,3.5.0]` widened to `[3.0.3,)`: the wrapper jar is
    not in the repository and the installed Maven is 3.9, so the normal build could not run at all
    (it failed before the migration too). `tessera-maven-plugin` is **not** used: it needs Maven 3.6.3+,
    while the project's wrapper pins 3.3.9. Consequence: a template-only edit needs `mvn clean` to be
    recompiled (see "Where Tessera fell short").
- Build: `mvn clean verify` on JDK 8 (`/usr/lib/jvm/java-1.8-openjdk`) passes: 933 tests (the 931 existing ones, none changed, plus the 2 new rendering tests).

### Configuration
- `WebConfiguration`: the Tiles `UrlBasedViewResolver`/`TilesView` and `TilesConfigurer` beans are gone;
  Tessera's resolver comes from the starter's auto-configuration. No "another template engine is still
  configured" warning at startup.
- `application*.properties`: `spring.mvc.view.prefix/suffix` (JSP) removed from all profiles.
- `tiles.xml`, every JSP under `META-INF/resources/WEB-INF/views/` and `util/I18NFunc` (the JSP message
  helper built on `PageContext`) deleted.
- `web.xml` (only used when deployed as a WAR to an external container): the 500 error page pointed at
  `500.jsp`; it now points at `/error`, Boot's error controller, rendered by the Tessera `error` template.
  (The old `500.jsp` could not have rendered there anyway: it called `<tiles:insertAttribute>` outside a
  Tiles request.)

### Model side (Java)
- All `eu.europa.ec.grow.espd.domain` classes are `@TemplateView` (templates read their Lombok getters).
- `view/LayoutModel` + `view/LayoutModelInterceptor`: the JSP layout read beans (`@espdConfiguration`
  via `<s:eval>`), the session `espd`, the response locale and `Language.VALUES`; templates see only their
  parameters, so an interceptor adds a `layout` model attribute to every rendered view, including Boot's
  error pages (country codes for the eCertis script, breadcrumb entries with resolved texts, footer,
  piwik, environment banner, language list and welcome-page columns).

- `view/filter`: `EspdInitializationParameters` (the filter form object) made `public` + `@TemplateView`.
- `view/procedure` (`ProcedureModelInterceptor`, `ProcedureView`): replaces the scriptlet in `procedure.jsp`
  (empty economic operator / first representative added before render) and provides a message that had to be
  restructured (below).
- `view/criteria` (`CriteriaViewInterceptor`, `CriteriaView`, `CriteriaFunctions` = `crit.*`): replaces the
  scriptlets of `exclusionEO.jsp`/`selectionCA.jsp`/`selectionEO.jsp` (empty unbounded rows) and supplies values
  the JSPs computed (eCertis country name, default currency, current year, currency labels, `lang` parameter).
- `view/print` (`PrintModel`, `PrintModelInterceptor`): values for the finish/overview pages; requirement-group
  field values come from Spring's `BindStatus` so formatting and rejected values are the form tags'. The hidden
  `html` field of the overview page (the printed ESPD, used for the PDF export) is now rendered from two partial
  templates in Java (`renderToString`) instead of a JSP `<c:set>` capture.
- Each area registers its interceptor through its own `WebMvcConfigurerAdapter`. `EspdController` is unchanged
  except for a comment.
- `messages_fi.properties`: `filter_alert` ended with `<p>` instead of `</p>` (typo); Tessera's build-time
  message check rejects it, so it is fixed (the Finnish filter page loses one stray empty `<p>`).
- New test `src/test/groovy/.../view/TesseraRenderingTest.groovy` (+ `spring-boot-starter-test`, test scope):
  renders through the real resolvers and checks that user data in the wizard comes out escaped.
- `.mvn/jvm.config` (`-Xmx1200m -XX:+UseSerialGC -Xss4m`): the generated print/selection templates are big
  (one class ~730 KB); in this 4 GB container javac's default heap (sized from host RAM) got the build
  OOM-killed. With the cap `mvn clean verify` passes.

### Templates (`espd-web/src/main/resources/templates`)
- `fragments/layout.html` (was `espdTemplate.jsp`), `fragments/header.html` (header, breadcrumb, footer),
  `fragments/wizard.html` (progress, viewChangeRole, footerButtons), `welcome`, `contact`, `about`,
  `error`, `sessionExpired`.
- `filter.html`, `filter/countries.html`; `procedure/*`; `criteria/*` (exclusion/selection);
  `finish/*`, `print/*` (finish, overview).
- Tiles definitions became one thin template per view name the controller returns (`request_ca_procedure`,
  ... 20 wizard views), each calling the layout and its body fragment with the definition's literal
  `agent`/`flow` (resolving the `extends` chains of tiles.xml, e.g. `request_ca_overview` is agent `eo`).
- The criteria pages were driven at render time by static Java data (`CriteriaTemplates`) through nested
  `tiles:insertDefinition` with computed definition names, field paths (`${field}.exists`), message keys and
  `data-*` values. Tessera only accepts those as literals, so they are expanded at development time by
  generators, committed with their input: `migration/criteria/` (`DumpCriteria.java` dumps the data to
  `criteria.json`, `gen_criteria.py` writes `templates/criteria/*`, `regen.sh`), `migration/gen-D/` (print and
  finish), `migration/gen_procedure.py`, `migration/gen_countries_A.py`. Rerun them when `CriteriaTemplates`,
  the countries or the currencies change.

## Page parity
`migration/pages.txt` (37 requests: static pages, error pages 400/404, session-expired, filter in two
languages, initialization, CA request and EO response flows through every step, rows added, binding
errors) recorded from the original app in `migration/baseline/`; plus per-area lists with more states
(`pages-A..D.txt`, 101 requests: answered criteria, added/removed rows, filled forms, other languages,
binding-error re-renders of finish/overview) with their recordings `baseline-A..D/`.

Result, plain `compare`: every page `differs`, because of difference 1 and 2 below, which are in the layout.
With those and the build timestamp masked (`migration/cmp.sh`): `pages.txt` 34/37 `same`; A 11/12, B 19/19,
C 37/42, D 16/28. Every remaining page differs only in difference 4 or 5 (and the intended Finnish fix).
Status codes and final URLs match everywhere.

## Differences that remain, and why
1. **Layout `<script>` (every page).** The JSP wrote values straight into JavaScript
   (`var pageLanguage = "${locale}".toLowerCase()`, `validator(..., "${i18n['validator_required']}")`,
   `EcertisHandler("${url}", "${country}")`). Tessera accepts a value in a script only as the whole value of
   a declaration at the top (`var x = [[...]];`, written as JSON in parentheses), so the script now starts
   with `var pageLanguage = ("en"); var _msgRequired = (...); ...` and uses those names. The JavaScript does
   the same thing, but `compare` compares script text, so every page is reported `differs` in that script.
   There is no Tessera construct that writes a value into a script as the old text.
2. **Session-expiry popup (every page).** The message `expire_reminder_message` contains `<p>` elements
   and the JSP wrapped it in `<p><span>`, which a browser only reads by error recovery; Tessera rejects that
   markup (`PARSE_ERROR` / `MESSAGE_CHANGES_STATE`). It is now `<div data-i18n=...>` holding the message:
   same text and paragraphs, slightly different tree.
3. **Build timestamp** (`last.build.date`, footer and about page) differs between any two builds.

4. **A control character in a message** (`crit_selection_suitability_article_tooltip`, English, contains
   U+0081 from a mis-encoding): Tessera writes it as U+FFFD by design (CA selection page). Correct the bundle.
5. **The overview page's hidden `html` field** (the escaped HTML of the printed ESPD, posted back for the PDF
   export). It is now rendered by Tessera, so its serialisation differs (attribute order/quoting, `v-pre` on
   textareas removed); parsed as HTML its content is the same as the original's. The PDF export itself fails in
   this environment in the original app as well (FOP: "URI is not absolute" loading fonts), so it could not be
   compared end to end.
6. Finnish filter page: one empty `<p>` fewer (the bundle typo fix above).

`migration/cmp.sh` runs `compare` with differences 1-3 masked.

## Things I could not convert one-to-one (behaviour notes)
- `data-*` attributes whose value was built from a loop index (`#createeo_info_respresent_div<i>`,
  `#...-self-cleaning-<i>`) are a chain of literal choices: up to 50 representatives, about 20-30 rows of
  criminal convictions etc. Beyond that, the collapse/toggle of that row has no target.
- Requirement-group entries (`unboundedGroups[0]["reason"]`, `DynamicRequirementGroup` is a
  `Map<String,Object>`) cannot be bound with `t:field` (Object is never view-safe, and Spring's quoted map
  paths cannot be produced); those inputs are written by hand with values from `BindStatus`/`crit.groupValue`.
- Messages containing `<p/>` or unbalanced `<p>` in some locale (`createeo_separate_espd_sections_a_b`,
  `crit_eu_text_guilty_misinterpretation`, ...) are split into paragraphs in Java and sanitised, reproducing the
  tree browsers built from the old output.
- Message keys that exist only in `messages.properties` (`currency_*`, `type_of_procedure*`, ...) are resolved in
  Java, because the build checks keys against `messages_en`.
- `requestCriterion.jsp` and `500.jsp` were not used by any reachable view (see web.xml above) and were deleted.

## Security
No `SafeHtml.unchecked`, `TrustedUrl.unchecked` or `Fragment.unverifiedForGeneratedCode`; no security
configuration touched (the app has no Spring Security); no escaping turned off. Message markup is printed with
`t:utext="#{...}"`, which the compiler checks against its allow-list for every locale; JSP printed messages and
EL values raw, so model values that used to be raw are now escaped (the new test checks that).

## Where Tessera or its documentation fell short
- **Script values can never match the old text.** The prelude rule makes any page that interpolated values into
  JavaScript `differs` in `compare`. The guide calls such differences "expected", yet `compare` has no
  normalisation for them, so a JSP/Thymeleaf app with a value in its layout script can never reach "every page
  same" without `--ignore`.
- **Error-recovery markup in messages**: a message with `<p>` inside the template's `<p><span>` is rejected
  (correctly), but there is no way to reproduce the old DOM; the bundle text has to change or the DOM differs.
- `t:utext` is not allowed in `<option>`, a POST form's `t:action` can't be a ternary of two links, a `style`
  attribute can't take a `String`, `data-*` can't take a string even for app-controlled message keys or ids
  with a prefix (the guide's "write the number, let the script add the prefix" doesn't keep the page identical).
- `<select t:field>` doesn't select the empty placeholder for a `null` value where Spring's tags did
  (`t:selected="fields.value(f).isEmpty()"` needed).
- A `Map<String,Object>` bean (Spring's usual way to bind dynamic rows) can't be bound at all, and Spring's
  quoted map keys can't be produced.
- The message markup check fails the build for one malformed locale file without naming the file.
- No warning before javac's 64 KB method limit ("code too large"): large pages had to be split into one fragment
  per criterion; the generated classes then need a bigger compiler heap.
- `tessera-maven-plugin` needs Maven 3.6.3+, so on this project (wrapper 3.3.9) a template-only change is not
  recompiled by an incremental build: always build with `clean`.
- Tessera adds a valueless `v-pre` to every computed `<textarea>`, which breaks consumers that parse rendered
  HTML as XML (the PDF export path); the guide mentions `v-pre` only in the Vue context.
- The guide's limitations say `java.util.Date` is not view-safe, but `t:field` on a `Date` property bound and
  formatted fine through the property editor; the docs are unclear about which reads are allowed.
- Boot 1.4 is not in Tessera's test matrix; the javax starter worked unchanged.
