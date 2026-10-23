# Migration report: JSP + Apache Tiles → Tessera

## What changed

### Build (`pom.xml`, `espd-web/pom.xml`)
- `java.version` 1.7 → 1.8 (Tessera's compiler and runtime need Java 8). The build targets and runs on JDK 8
  (`/usr/lib/jvm/java-1.8-openjdk`); Lombok 1.16.6 does not run on newer JDKs.
- `maven-compiler-plugin` pinned to 3.8.1 on the plugin itself. The Boot 1.4 parent pins 3.1, and setting the
  `maven-compiler-plugin.version` property, as the guide says for Boot 1.5, has no effect under Boot 1.4.
  `annotationProcessorPaths` lists Lombok and `tessera-compiler`. Options: `tessera.templates`, `tessera.package`
  and `tessera.messages=i18n/messages`, so message keys are checked at build time.
- Removed `tomcat-embed-jasper`, `jstl` and `tiles-jsp` (and the `tiles.version` property). Added
  `tessera-spring-boot-starter-javax`.
- Added `commons-beanutils` (with `commons-logging` excluded, because the enforcer bans it) and `commons-collections`.
  The domain code used both, but they had only come in through Tiles.
- `templates/**` is excluded from resource filtering, so Maven doesn't rewrite `${...}` in the templates.
- Enforcer `requireMavenVersion` widened from `[3.0.3,3.5.0]` to `[3.0.3,)`. The installed Maven is 3.9, and the
  unchanged original build already failed on this rule.

### Old engine removed
- Deleted `WEB-INF/views/**` (50 JSPs) and `tiles.xml`. Removed the `TilesConfigurer`/`TilesView` beans from
  `WebConfiguration`, the `spring.mvc.view.prefix/suffix` properties from every profile, and `util/I18NFunc.java`, a
  JSP-only helper built on `PageContext`.
- `500.jsp` was not referenced by any Tiles definition, controller or error page, so it could not be reached. It was
  dropped and not converted.

### Java support for the templates (`eu.europa.ec.grow.espd.view`)
- `PageModelAdvice` / `PageInfo` (`@TemplateView`, model attribute `page`) holds what the JSPs read from the page
  context, the application scope and `@espdConfiguration`: the context path, the request URI (forms post back to it),
  the response locale, the configuration flags, the breadcrumb, the languages and the welcome-page language columns.
- `I18nFunctions` (`i18n.*`) replaces the old `${i18n[key]}` / `${span18n[key]}` maps for computed message keys.
  `i18n.html(key)` returns `SafeHtml.sanitize(message)`. A few messages contain `<p>` and one contains an `<a>`.
  No `unchecked` is used.
- `FormFunctions` (`form.*`) gives the `espd` form object binding by computed path, the way `<form:* path="${field}.x"/>`
  did. It reads the request's `BindingResult` (exposed by `ModelExposingInterceptor`), so rejected values, `@InitBinder`
  editors and error messages behave as with Spring's form tags.
- `CriterionSlot` / `CriteriaFunctions` (`crit.*`) turns the `CriteriaTemplates` `Map[]` tables into typed slots and
  merges in the defaults of the Tiles definitions they named (`tiles.xml` inheritance is reproduced in Java).
- Templates live in `espd-web/src/main/resources/templates`. Each old Tiles view name (`request_ca_procedure`, ...) has
  a small wrapper template that calls `fragments/layout :: layout(...)` with the right body fragment and the
  agent/flow values from the Tiles definition.

## Escaping and other intended differences
- Inside `<script>`, Tessera writes values as JSON. The eCertis URL in the layout's script therefore comes out as
  `"https:\/\/...\u0026lang=..."` where the JSP printed `"https://...&lang=..."`. The two are the same JavaScript
  string, but the text differs. This cannot be avoided without raw output in a script, which Tessera rightly forbids.
  As a result, `compare` reports every page as `differs` on this one script line.
- The footer's build timestamp changes with every build. Compare with `--ignore 'TODAY \d\d:\d\d'`.
- Two messages contain `<a href=... class="alert-link">`. The sanitizer drops `class` and adds `rel`.

## Tessera / documentation shortcomings found
1. **Lombok getters aren't visible** to the Tessera processor, even with Lombok listed first in
   `annotationProcessorPaths`. A `@TemplateView @Getter` class fails with "'x' is not a property". The guide mentions
   Lombok only for `getIsX`. All view classes needed hand-written getters.
2. **Boot 1.4 / compiler plugin:** the guide's `maven-compiler-plugin.version` property advice only works where the
   parent uses that property. Under Boot 1.4 the plugin stayed at 3.1 and `annotationProcessorPaths` was silently
   ignored.
3. **No dynamic field paths.** `Field` parameters cannot be extended (`field + '.exists'`) or read. Apps like this one,
   where one fragment binds `${field}.answer`, `${field}.description` and more for 150 criteria, can't use
   `t:field`/`t:errors` and need custom binding functions.
4. **No computed message keys.** Data-driven pages (keys from tables) need a custom function, and those keys lose the
   build-time check.
5. **JSON-only script output** has no way to write a JS string literal that matches what the old engine printed (see
   above). The guide doesn't say that `/` and `&` are escaped.
6. The view and fragment model has no equivalent of Tiles attribute inheritance: definition defaults had to be
   recreated in Java.
7. Removing Tiles silently dropped transitive dependencies (`commons-beanutils`, `commons-collections`) that the
   application used. That isn't a Tessera issue, but a migration guide could warn about it.
8. Static access to the request and model from `@TemplateFunctions` (through `RequestContextHolder`) was the only way
   to get form binding for computed paths. The guide offers no supported hook for it.

## Final state (as of the 90-minute limit)
- **Old engine:** gone. No JSPs, no `tiles.xml`, no Tiles, Jasper or JSTL dependencies, and no JSP view properties.
  Every view name the controllers return now resolves to a Tessera template.
- **Build:** `mvn clean verify` on JDK 8 passes with the enforcer on, and all 931 existing tests pass. No test was
  changed. I last ran the full build before the wizard templates from the agents were merged. Since then I have only
  run `mvn clean package -DskipTests`, which compiles all templates.
- **Security:** no `SafeHtml.unchecked`/`TrustedUrl.unchecked`, no new `permitAll`, and CSRF and escaping are
  untouched. Message HTML goes through `SafeHtml.sanitize`.
- **Parity:** run `compare --ignore 'TODAY \d\d:\d\d' --ignore 'var ecertisHandler = EcertisHandler\([^)]*\)'` over
  my 36-request `pages.txt`.
  - Every page gets its recorded status and final URL.
  - 11 pages are `same`: welcome (twice), contact, about, session expired, the 404 error page, the create-request
    POST, the CA procedure page, and both CA exclusion→selection steps.
  - The filter pages differ only by the sanitized `alert-link` anchor described above.
  - The wizard pages are **not done**. The procedure, exclusion, selection, finish and overview steps still differ.
    The diffs are mostly small markup details: stray `<p>`, textarea newlines, duplicate attributes and attribute
    values.
  - The finish page body (`wizard/finish.html`, from `finish.jsp`/`finishForm.jsp`) is still a placeholder, which
    also affects the finish steps of both flows.
  - The `compare` exit status is therefore non-zero. This is unfinished work; I don't claim these differences are
    correct.
- The multipart upload forms (reuse, review, import, merge) were not exercised, because `pages.txt` cannot send
  files.

## Known compromises in the converted wizard code (to revisit)
- `ProcedureFunctions.representatives()` and the selection/exclusion preparation code still add empty list entries
  to the session `espd`, as the JSP scriptlets did. That code now runs in Java: a function, an interceptor
  (`SelectionPagePreparation`) and `EspdController.prepareView`. The function is the one place where a template call
  has a side effect, and it should move into the controller.
- `PrintFunctions.content(...)` renders `print/content.html` a second time into a `SafeHtml`, using
  `SpringRenderContexts`, so the overview page can post its own HTML in the hidden `html` field. That is the
  JSP's `<c:set var="htmlToPrint">` capture. Tessera has no capture construct.
- Agents P and R each converted `procedureForm`/`partyInfo`, so that markup now exists twice: in
  `wizard/procedure.html` and in `print/forms.html`.
