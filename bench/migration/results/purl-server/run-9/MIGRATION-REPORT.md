# Migration report: Thymeleaf → Tessera

## Result

| Check | State |
|---|---|
| Every page is rendered by Tessera | Yes. All 25 templates (17 pages, 5 login pages, `error`, 3 fragment files, plus a new `fragments/html.html`) are Tessera templates compiled by `tessera-compiler` into `de.uni.rostock.ub.purl_server.templates`. |
| Old engine gone | `spring-boot-starter-thymeleaf` and `thymeleaf-extras-springsecurity6` are removed from `pom.xml`. All `th:`/`sec:` markup is gone. There was no Thymeleaf configuration in `application.properties`. |
| `mvn clean verify` (JDK 21, `java.version` 17 as before) | Passes. All existing tests pass unchanged; none were edited or disabled. `PurlClientTest` is skipped, as it was before. |
| Page parity (`tessera-migrate compare`, 91 requests in `pages.txt`) | **90 same, 1 differs** (page 58, explained below). Every page has its recorded status and final URL. |
| Security | No `SafeHtml.unchecked` / `TrustedUrl.unchecked`, no new `permitAll`, CSRF unchanged (the CSRF input is still added to every POST form), escaping never turned off. |

### How parity was checked
- `pages.txt` covers three states. Logged out: start page in both languages, login (empty, failed, logged out), password request (empty and unknown user), password form, PURL and domain info pages (normal, partial-redirect suffix, 404), and 404 error pages. Logged in as `user1` (non-admin): search, create, modify and delete for PURLs (with validation errors, successes and not-found ids), domain search, and the 403 pages. Logged in as `admin`: every domain and user page (empty, add-user row, validation errors, success, not found, delete), PURL pages, and logout.
- The test data (`data-test.sql`) holds passwords that no browser login can match, because H2 `HASH()` hashes the binary SHA-1. To log in for the recording, I started both apps from a copy of `data-test.sql` with working password hashes added (`/tmp/data-pages.sql`, outside the repo). I ran the H2 profile through `spring-boot:run` with the test classpath and the H2 console turned off, because the console makes Security's `requestMatchers` ambiguous. The original and the new app were started the same way, on the same port.
- I passed `compare` two `--ignore` patterns: the error page timestamp (`Date.toString()`) and `HH:mm` times, which come from `NOW(3)` in the seed data.

### The one remaining difference (page 58: a successful `POST /admin/manager/domain/create`)
The original app already fails here. On a successful create, `AdminDomainController.createDomain` doesn't put `error` in the model. Thymeleaf then fails on `error.ok` and returns a **500** whose page shows a Thymeleaf exception. The Tessera template fails the same way (an NPE reading `error.ok`), so the status is the same 500. The error page, however, shows the exception's message and stack trace (`server.error.include-stacktrace=always`). The trace now names Tessera classes instead of Thymeleaf ones, so this page can never match exactly. I left the bug in place, since fixing it would change the status. Every other 500 (the H2 SQL-grammar errors when creating or deleting a user) comes out `same`.

## What changed

### Build (`pom.xml`)
- Removed the Thymeleaf starter and the Spring Security extras.
- Added `dev.tessera:tessera-spring-boot-starter` (jakarta, Boot 3.1.3 kept) and `org.jsoup:jsoup` 1.17.2 (see "Configured HTML snippets" below).
- Configured `maven-compiler-plugin` with `tessera-compiler` as the annotation processor and `-Atessera.templates`, `-Atessera.package` and `-Atessera.messages`, so every `#{key}` is checked at build time.

### Java
- `model/*`: added `@TemplateView` to `Domain`, `DomainUser`, `Purl`, `PurlHistory`, `PurlServerError` and `User`. JSON output is unchanged.
- `web/LayoutAdvice` (`@ControllerAdvice`) adds the model attribute `layout` (a `Layout` record) to every page. It replaces what the templates used to read from outside the model: `#locale` (the `lang` attribute, the flag image, the language name) and `@environment.getProperty('purl_server.web.html_fragment.title/footer')`.
- `web/HtmlNode` and `templates/fragments/html.html` (configured HTML snippets): the title and footer properties are operator-configured HTML. Thymeleaf printed them raw (`th:utext`). `html.sanitize` would remove their `class` attributes (`<hr class="mb-1">`, `<span class="ms-4">`), so it isn't enough. The snippets are now parsed once with jsoup into an allowlisted tree: `h1`–`h6 p div span hr br b i em strong small u a img`, with only `class id title href src alt`. A recursive Tessera fragment renders that tree, so every text and attribute is escaped and `href`/`src` are URL-checked. This is the "template over a parsed model" approach from guide §7, so there's no `unchecked`.
- `web/Dates` (`@TemplateFunctions("dates")`): `dates.format(Instant, pattern)` formats in the system default zone, as Thymeleaf's `#temporals.format` did for an `Instant`.
- `web/ErrorPageViewResolver` (`ErrorViewResolver`): renders `error` with Boot's error attributes, and adds what the template can't read: `timestampText` (a `Date` isn't view-safe), `trace`/`exception` as `String`s, and `layout`. Advice model attributes don't reach Boot's error model, so it adds `layout` itself. This replaces Boot's `DefaultErrorViewResolver`. That resolver only looked for `error/<status>` templates, and the app has none.
- `LoginController`:
  - `@ModelAttribute` methods `paramError`, `paramLogout`, `paramToken` and `paramLogin` replace Thymeleaf's `${param.*}`.
  - The authentication error message was a string joined with `<br />` and printed raw. It is now a `SafeHtml` (`loginErrorMessage`) built with `SafeHtml.escape` plus `SafeHtml.sanitize("<br />")`.
- `AdminDomainController`: the domain form shows one row per domain user plus one empty row. Thymeleaf filled the extra row by reading `domainUserList[size]` through Spring's `BindingResult`, whose auto-grow appends an empty `DomainUser`. Tessera reads the model value directly and throws `IndexOutOfBoundsException` for that index. `addEmptyDomainUserRow` now puts the row count in the model (`domainUserCount`) and appends the empty `DomainUser`/`User` itself before rendering, which is the same state Thymeleaf's auto-grow produced. The bound values are unchanged, because `cleanUpDomain` still drops rows without a login on submit.

### Templates
- Layout fragments (`fragments/page.html`):
  - `html_header`: Thymeleaf's selector picked up three elements named `html_header`; Tessera needs unique names, so they are one `<t:block>` fragment.
  - `title(layout)`: Thymeleaf's `:: title` selector also matched the file's own `<title>Page fragments</title>` element and printed it into every page body. The fragment keeps that `<title>` element so the output is the same.
  - `sec:authorize` became `t:if="auth.isAuthenticated()"` / `auth.hasRole('ROLE_ADMIN')`. Where it shared an element with `th:replace`, it now sits on an enclosing `<t:block>`: Thymeleaf evaluated `sec:authorize` first, but Tessera runs `t:replace` before `t:if`.
- Fragments now get their data as typed parameters. In Thymeleaf they read the page's variables (`errorView(error)`, `infoPurlTable(purl, purl_target_suffix, …)`, `nextPurlAction(purl)`, `domainForm(domain, domainUserCount, submitted, usersLogin, form, layout)`, …). The form fragments carry their own `t:object`, which still binds the model attributes `domain`, `user` and `purl`.
- `__…__` preprocessing was replaced:
  - message keys built from `form` → conditionals between literal keys;
  - `@{/x?id=__${id}__}` → `@{/x(id=…)}`;
  - flag image → `@{/static/images/flags/{locale}.png(…)}`;
  - field paths → `*{domainUserList[i].…}`;
  - `@{/info/purl__*{path}__}` → `'/info/purl' + purl.path` (see the limitations below).
- `th:style="#{…css}"`: a style can't take a `String`, so the two per-locale values from `purl_server.domain.form.{create,modify}.css` are now literals chosen by `layout.en`. The message keys are still in the bundles but no longer used.
- `password_form.html`: the inline script printed the missing key `purl_server.error.validate.passwords_different`, i.e. `"??…_de??"`. Tessera rejects missing keys at build time, so the same output is now produced as a string literal chosen per locale. The bug is kept on purpose: the existing key is `purl_server.error.login.passwords_different`, and fixing the call is a one-line change if wanted.
- Checkbox `id`s: Thymeleaf numbered them (`admin1`, `domainUserList0.canCreate1`). They are now set explicitly so ids and `<label for>` don't change.
- Thymeleaf treats `""` as true in `th:if`, so `purl_target_suffix != null` keeps the empty `<span class="fst-italic">`. A `null` `th:value` became `''` where Thymeleaf wrote `value=""`.

### Output that is now escaped (intended)
- Error page `message` and `password_request` `errorMessage` were printed raw (`th:utext`); they are now `t:text`. For every message the app produces, this looks the same. The only message key that contains markup (`purl_server.error.api.purl.gone`, which has a link) is never used: `PurlController` asks for the non-existent key `purl_server.error.purl.gone`.
- The login headings (`th:utext` on plain bundle messages) are now `t:text`.

### New test
`TemplateRenderingTest` renders through the real view resolvers. It checks the start page (configured title snippet, login link), checks that user input (`<script>` in a search field) comes out escaped, and checks the 404 error page.

## What I couldn't convert one to one
- **Page 58's 500 body** (above): the stack trace names the engine.
- **Links built from a stored path** (`/info/purl` + `/test/x`): Tessera percent-encodes path variables, so `{path}` would turn the `/`s into `%2F`. They are now plain `String` hrefs, which are still URL-checked. They don't get the servlet context path or `encodeURL`, which Thymeleaf's `@{}` added. The app runs at the root context, so the output is identical.
- `href="?&lang=…"` and `?format=json` are now static attributes; Thymeleaf left them unchanged too.

## Where Tessera or its documentation fell short
1. **Indexed `t:field` evaluates the model value eagerly.** For `*{list[i].x}`, the generated code calls `list.get(i)` as the fallback value even when a `BindingResult` exists. An index past the end therefore throws `IndexOutOfBoundsException`, where Spring's form tags and Thymeleaf auto-grow the list. The guide says values come "from the BindingResult for that path" but doesn't mention this.
2. **No way to keep `/` in a path variable** (no `{*path}`-style link variable). Links to hierarchical ids must give up `@{}`, and with it the context path and `encodeURL`. The guide doesn't cover this case.
3. **Fragment selection differs from Thymeleaf's** in ways the guide doesn't mention: Thymeleaf's `:: name` also matches *elements* named `name` (here `<title>`) and *several* elements with the same `th:fragment` name. Tessera reports duplicate fragment names and never matches tag names in another file. Neither `tessera-migrate`'s report nor the guide flags this, and the old output changes silently.
4. **Attribute precedence vs. extras dialects**: the guide explains that `t:if` doesn't guard `t:replace`. It doesn't say that `sec:authorize` *did* guard `th:replace` in Thymeleaf, so a mechanical `sec:` → `t:if` port renders the navigation for anonymous users.
5. **No raw rendering of trusted configuration HTML.** `html.sanitize` strips every `class`, and the only options left are `unchecked` or building a node tree by hand (done here with jsoup and a recursive fragment). An allowlist-configurable sanitizer, or a documented recipe, would help.
6. **Missing message keys**: Thymeleaf printed `??key_locale??`. With `-Atessera.messages`, Tessera fails the build, which is good, but there is no way to keep the old output except hard-coding it.
7. **`tessera-migrate` stubs**: `th:style="#{…}"`, `@environment`, `${param.*}` and `#authentication` are only reported, not converted, and the stub `t:params` are all `Object`. Expected, but most of the manual work was there.
8. **Error pages**: the guide lists Boot's error attributes, but doesn't say that `trace`/`exception` must be passed as `String`s, that `timestamp` needs converting, or that `@ControllerAdvice` model attributes don't reach `BasicErrorController`'s model. I needed my own `ErrorViewResolver` for all of that.
9. **Truthiness**: Thymeleaf's `th:if` truthiness (`""` is true, `null` Booleans are false) isn't covered in the guide. I wrote `x == true` / `x != null` explicitly.
