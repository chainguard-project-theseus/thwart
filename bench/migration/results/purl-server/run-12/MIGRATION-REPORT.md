# Migration report: Thymeleaf → Tessera

## Summary

Every server-rendered page (25 templates: 18 pages, 3 fragment libraries, the Boot error page and
the login pages) now renders through Tessera 0.1.0-SNAPSHOT. Thymeleaf is gone: the
`spring-boot-starter-thymeleaf` and `thymeleaf-extras-springsecurity6` dependencies were removed,
every template was rewritten to `t:` syntax, and no Thymeleaf configuration was left (there was
none in `application.properties`). The startup warning `Tessera: another template engine is still
configured` does not appear. The log shows `Found 25 compiled Tessera templates`.

`mvn clean verify` (Java 17 target, built on the default JDK 21) passes. That is 52 tests: the
existing 46 (one of them, `PurlClientTest`, was already `@Disabled`) plus 6 new ones. No existing
test was changed, disabled or weakened. Security config is untouched: no new `permitAll`, CSRF
still on, nothing printed raw that the old app escaped.

## What changed

### Build (`pom.xml`)
- Removed the Thymeleaf starter and the Spring Security dialect. Added
  `dev.tessera:tessera-spring-boot-starter` (jakarta / Boot 3).
- `maven-compiler-plugin` now runs `tessera-compiler` as an annotation processor, with
  `tessera.templates` = `src/main/resources/templates`,
  `tessera.package` = `de.uni.rostock.ub.purl_server.templates` and
  `tessera.messages` = `src/main/resources/messages`, so every `#{key}` is checked at build time.
- Added `tessera-maven-plugin:check`, so a change to only a template or only a message file still
  triggers a recompile.
- The Spring Boot and Spring versions are unchanged.

### Java
- `model/Domain`, `DomainUser`, `Purl`, `PurlHistory`, `User`, `PurlServerError`: annotated
  `@TemplateView` so templates can read their getters.
- New `web/PageModelAdvice` (`@ControllerAdvice`): adds a `page` model attribute (`PageInfo`) to
  every view. It holds the locale and language (Thymeleaf's `#locale`), plus the configurable
  `purl_server.web.html_fragment.title`/`.footer` HTML. The old templates read that HTML with
  `@environment.getProperty(...)` and printed it raw. It is now turned into a `SafeHtml` through an
  `HtmlPolicy` that allows headings, `hr`, `span`, inline formatting and Bootstrap spacing/text
  classes.
- New `web/ViewFunctions` (`@TemplateFunctions("views")`):
  - `dateTime(Instant)` replaces `#temporals.format(x, 'yyyy-MM-dd HH:mm')`, in the JVM default
    zone as before.
  - `timestamp(ZonedDateTime)` makes the error page show the timestamp in `java.util.Date#toString()`
    format, as Thymeleaf did.
- `LoginController`: puts the request parameters the login templates used to read via `${param.*}`
  (`error`, `logout`, `token`, `login`) into the model as `paramError`, `paramLogout`, `paramToken`
  and `paramLogin`.
- `AdminDomainController`: before rendering the create/modify form it adds one empty `DomainUser`
  to the domain. Thymeleaf's binder used to do this by auto-growing `domainUserList[n]` while
  rendering, so the page always showed one more empty row. Tessera reads the list directly and
  threw `IndexOutOfBoundsException`. The form now iterates over the list instead of
  `#numbers.sequence(0, size)`.

### Templates
- Layout pieces in `fragments/page.html` became typed fragments: `html_header`, `title(page)`,
  `switchLang(page)`, `navigation`, `errorView(error)`, `footer(page)`, `html_footer`.
  `sec:authorize` became `auth.isAuthenticated()` / `auth.hasRole(...)`, and
  `#authentication.name` became `auth.name()`.
- `fragments/info.html`: typed fragments `infoDomainTable(Domain)` and
  `infoPurlTable(Purl, suffix, createdBy, lastModifiedBy)`. Explicit `<tbody>` elements were added;
  the DOM is the same as before.
- Form fragments (`domainForm`, `userForm`, `purlForm`) were written out in each create/modify page.
  They bound the caller's `th:object`, and each page now binds its own form object. The
  `__${form}__` message keys and conditions became literals per page. `help` and
  `next*Action(...)` stay fragments.
- `__...__` link preprocessing became `@{/path(id=x.id)}`, or a string path for `/info/purl` +
  path. The path contains `/`, which a link placeholder would percent-encode.
- `th:style="#{...css}"` (a message used as CSS) became a choice between the two literal CSS
  values, picked by the page language (see the limitations section).

## Verifying parity

The H2 seed data (`src/test/resources/data-test.sql`) stores password hashes that no password can
match under H2 2.x: `HASH()` returns binary, and `CONCAT` turns it into garbage text. So nobody can
log in when the app runs from that file. To exercise the logged-in pages I started both apps from a
copy of that file with valid hashes for `admin` and `user1`. The copy is
`.tessera/data-login.sql`, outside the source tree and git-ignored. The command was
`-Dspring.profiles.active=test -Dspring.sql.init.data-locations=file:...`, run on the test
classpath with `spring.h2.console.enabled=false`; with the H2 console on, the original app fails
at startup with an `MvcRequestMatcher` error.

`.tessera/pages.txt` has 123 requests:
- anonymous, logged in as `user1` (not an admin), and logged in as `admin`;
- German and English;
- every search page empty, with results and with no results;
- every create/modify/delete form empty, with validation errors and after success;
- the add-user state of the domain form, and unknown ids;
- the info pages, including a partial-redirect suffix and history;
- error pages: 400, 403, 404 and 500.

The result of `tessera-migrate compare` (masking only the port and the current-minute timestamps,
which change between runs):

- **69 pages `same`, 54 `differs`, and every recorded status and final URL matches.** A script
  (`.tessera/residual.py`) confirmed that each of the 54 differences comes only from the two
  causes below, which Tessera does not let a template avoid.

  1. **Inline event handlers (49 pages).** These are the login, password, search, user and
     domain-info pages. Tessera rejects every `on*` attribute, static ones included (verified:
     `onclick on <button> is not allowed`). `<body onload>`, `onclick` and `onsubmit` became
     `data-action="..."` markers. The page's existing `<script>` gained `addEventListener` lines
     (the guide's recipe), with the same behaviour: a click handler that returns `false` now calls
     `preventDefault()`.
     On the password form, the message for "passwords differ" became a top-level
     `const msgPasswordsDifferent = [[#{...}]];` declaration. A `[[...]]` inside a function is
     rejected.
  2. **Stack traces on the error page.** The app sets `server.error.include-stacktrace=always`,
     and 403/400/500 pages print the trace. Every trace now contains three frames of Tessera's
     `dev.tessera.spring.view.CspFilter`. A trace thrown during rendering names Tessera's generated
     classes instead of Thymeleaf's: `domaincreate` after a successful create fails with a 500 in
     both apps, because the controller never sets `error`. That bug was kept for parity.

- **Intended differences that compare does not show:**
  - `errorMessage` on the login page was `th:utext` and is now escaped. It is built from exception
    messages, so this closes an injection point; the branch is unreachable in practice.
  - The `message` cell on the error page is now escaped. Boot leaves it empty here.
  - The configurable title/footer HTML goes through a sanitizer.
  - Pages get a CSP header with nonces, which compare ignores.
- **One deliberate content fix:** `login/password_form.html` used the message key
  `purl_server.error.validate.passwords_different`, which doesn't exist. Thymeleaf printed
  `??purl_server.error.validate.passwords_different_de??` into the alert. With `tessera.messages`
  set, a missing key is a build error, so the template now uses the existing key
  `purl_server.error.login.passwords_different`. That line already differs because of the
  declaration recipe.
- **Excluded:** requests that redirect to an external site (`/test/redirect1` →
  `www.google.de`). The resolver redirects even after a PURL is deleted, so the `GONE_410` branch
  never runs.

## Not converted

Nothing. All 25 templates are Tessera templates. No view falls back to another engine.

## Where Tessera or its documentation fell short

1. **Inline event handlers rule out strict page parity.** Static `on*` attributes are build errors,
   with no escape hatch. The `tessera-migrate compare` tool flags the guide's own recipe as
   `differs`, so every page that had an `onclick`/`onload`/`onsubmit` can't come out `same`. The
   guide says a recipe "changes the page, and compare shows it", but `compare` has no way to accept
   a documented recipe difference short of `--ignore`, which would hide too much.
2. **`t:field` does not behave like Spring's binder for list paths.** The guide says forms "bind
   through Spring's `BindingResult`, as Spring's form tags and macros do". Thymeleaf (through
   Spring's `BeanWrapper` with `autoGrowNestedPaths`) silently grew `domainUserList[n]` while
   rendering. Tessera's generated code calls `getDomainUserList().get(i)` and fails at runtime with
   `IndexOutOfBoundsException`. Neither the guide nor the converter's report mentions this.
3. **Thymeleaf markup selectors that match element names went unreported.** In Thymeleaf,
   `~{fragments/page :: title}` matched `th:fragment="title"` and also the `<title>` element of the
   fragment file. Every page therefore had `<title>Page fragments</title>` inside its `<body>`.
   Likewise, three elements shared `th:fragment="html_header"` and all three were inserted.
   `tessera-migrate` copied both constructs unchanged (three `t:fragment="html_header"`) and
   reported nothing. Only `compare` revealed them.
4. **`CspFilter` shows up in user-visible output.** Apps that print stack traces on error pages
   (`include-stacktrace=always`) get extra frames, an unavoidable `compare` difference. The guide
   doesn't mention it. Turning the CSP off (`tessera.csp.enabled=false`) would hide the frames but
   weakens security, so I didn't.
5. **Error page `timestamp`.** The guide says to declare it as `Instant` or `ZonedDateTime`, but not
   that Thymeleaf printed `java.util.Date#toString()`, nor which zone the value carries. Matching
   the old page needed a custom function.
6. **The zone `temporals.format` uses for an `Instant` is undocumented.** I used my own function
   with the JVM default zone, which is what Thymeleaf used.
7. **`HtmlPolicy.allowClasses` fails silently.** A plain prefix such as `"m"` matched nothing and
   classes were silently stripped (`<hr class="mb-1">` became `<hr>`). Only the `"mb-*"` form
   works. A builder that validates only names could also reject a pattern that can never match.
8. **No way to build a style from a bundle message.** `th:style="#{key}"` (CSS kept in
   `messages*.properties`) can't be expressed: `t:style` takes literals and `css.*` values, and
   `css.*` has no general declaration. The two locale-specific `transform` values are now literals
   in `domaincreate.html`/`domainmodify.html`, chosen by `page.lang`. The keys
   `purl_server.domain.form.create.css`/`.modify.css` in the bundles are no longer read, so
   editing them has no effect.
9. **The converter report misses things.**
   - It lists `sec:authorize` as "port by hand" even for the documented one-to-one mappings
     (`isAuthenticated()`, `hasRole(...)`).
   - It doesn't flag `th:if` + `th:unless` on the same element.
   - It doesn't flag an expression that compares against a bare class name
     (`${exception} == org.springframework...ResponseStatusException`). That had to be rewritten as
     a string comparison on Boot's `exception` attribute.
10. **Missing message keys.** The build rejects them, which is good, but there is no documented way
    to reproduce the old `??key_locale??` output when exact parity is wanted.

## Files outside the source tree

`.tessera/` (git-ignored) holds:
- the `tessera-migrate` jar;
- `pages.txt` and the recorded baseline;
- the login-capable seed data and the start/compare scripts;
- the generators used to write the templates.

To repeat the comparison:
1. Start the original build with `.tessera/run.sh .tessera/orig/classes .tessera/orig/test-classes .tessera/orig/cp.txt 8081`.
2. Record it with `java -jar .tessera/tessera-migrate.jar record http://localhost:8081 .tessera/pages.txt .tessera/baseline`.
3. Run `.tessera/cmp.sh $(cat .tessera/ign)`.
