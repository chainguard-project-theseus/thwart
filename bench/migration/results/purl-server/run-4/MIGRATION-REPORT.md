# Migration report: Thymeleaf → Tessera

## Result

- Every view is now a Tessera template: 21 pages (`index`, `help`, `error`, `purl*`, `domain*`, `user*`,
  `login/*`) and 4 fragment files (`fragments/page`, `forms`, `info` and the new `markup`). The build
  generates 26 template classes in `de.uni.rostock.ub.purl_server.templates`.
- Thymeleaf is gone: `spring-boot-starter-thymeleaf` and `thymeleaf-extras-springsecurity6` are removed
  from `pom.xml`, and every template was rewritten in place. The app had no Thymeleaf configuration in
  its properties.
- `mvn clean verify` passes: all 46 existing tests plus 4 new ones in `ViewRenderingTests`. No existing
  test was changed.
- Page parity: `parity/pages.txt` has 120 requests covering logged out, admin and non-admin users,
  German and English, every form empty / with validation errors / after success, empty and full
  result lists, and 400/403/404/500 error pages. Result: **119 `same`, 1 `differs`** (see "Remaining
  difference" below).
- Security: no `SafeHtml.unchecked` or `TrustedUrl.unchecked`, no new `permitAll`, CSRF and escaping
  untouched (the CSRF input is still injected into every POST form), no test disabled.

## What changed

### Build (`pom.xml`)
- Added `dev.tessera:tessera-spring-boot-starter` (the app is Boot 3.1, jakarta).
- `maven-compiler-plugin`: `tessera-compiler` as the annotation processor, plus `-Atessera.templates`,
  `-Atessera.package` and `-Atessera.messages`, so message keys are checked at build time. Test
  compilation uses `<proc>none</proc>`, as the guide says. The Java release stays at 17 (from the Boot
  parent).

### Java
- `@TemplateView` on the model classes the templates read: `Domain`, `DomainUser`, `Purl`,
  `PurlHistory`, `PurlServerError`, `User`.
- New package `de.uni.rostock.ub.purl_server.web`:
  - `ViewModelAdvice` (`@ControllerAdvice`) puts into the model what the Thymeleaf templates read from
    outside it: `#locale` becomes `pageLang`/`pageLocale`; `@environment.getProperty(...)` for the
    title and footer HTML becomes `titleHtml`/`footerHtml`; `${param.*}` on the login pages becomes
    `paramError`, `paramLogout`, `paramToken` and `paramLogin`.
  - `ErrorPageViewResolver` (an `ErrorViewResolver`) renders `error` with Boot's error attributes plus
    `timestampText`, because `timestamp` is a `Date` and is not view-safe. It also adds the advice
    values in case the advice did not run.
  - `ViewFunctions` (`@TemplateFunctions("view")`):
    - `format(Instant, pattern)`: the built-in `temporals.format` throws at runtime for an `Instant`.
    - `domainUserRows(Domain)`: the rows of the domain form's user table.
  - `Markup` (`@TemplateFunctions("markup")`), `MarkupNode` and `fragments/markup.html` replace
    `th:utext`. The old templates printed raw HTML from four places: the configured page title and
    footer, the error page `message` (for example `purl_server.error.api.purl.gone` contains an
    `<a href>`), and the login `errorMessage` (joined with `<br />`).
    - `Markup.parse` turns the string into a tree that keeps only allowlisted elements (`a, b, strong,
      i, em, u, small, code, span, div, p, h1–h6, hr, br`) and attributes (`class, id, title, href`).
      It drops script and style content and decodes entities.
    - A recursive fragment renders the tree, with each value escaped for its context (so `href` is
      scheme-checked).
    - The output for the existing values is unchanged, without raw-HTML output or `unchecked`.
  - `WebViewConfig` registers `ResourceUrlEncodingFilter` for the REQUEST and ERROR dispatches. Boot
    registered it only as part of the Thymeleaf auto-configuration. Without it,
    `/webjars/bootstrap/css/...` links lose the `5.3.3/` version segment.
- No controller was edited. Keeping their line numbers unchanged keeps the stack traces on the error
  pages (`server.error.include-stacktrace=always`) identical to the original.

### Templates (main conversion decisions)
- Layout pieces became typed fragments: `title(titleHtml, pageLocale)`, `switchLang(pageLocale)`,
  `navigation`, `errorView(error)`, `footer(footerHtml)`, `domainForm(...)`, `userForm(...)`,
  `purlForm(...)`, `help(text)`, `nextPurlAction(purl)`, `nextDomainAction(domain)`,
  `infoDomainTable(domain)` and `infoPurlTable(purl, createdBy, lastmodifiedBy, targetSuffix)`. Each
  form fragment carries its own `t:object`.
- `sec:authorize` became `auth.isAuthenticated()` and `auth.hasRole('ADMIN')`, wrapped in `<t:block>`
  where it guarded a `th:replace`. `#authentication.name` became `auth.name()`.
- Links built with `__${...}__`:
  - `?id=__${x.id}__` became `@{...(id=x.id)}`.
  - `@{/info/purl__*{purl.path}__}` became the string `|/info/purl${purl.path}|`, because a path
    variable would percent-encode the `/` characters in the PURL path. Consequence: these links don't
    get the context path (the app runs at `/`).
- Message keys built with `__${form}__` or `__${#locale}__` became conditionals between literal keys.
- Faithfully kept quirks of the original:
  - `~{fragments/page :: title}` also inserted the `<title>Page fragments</title>` element (Thymeleaf
    matches the selector against tag names too), so the fragment emits it.
  - `help(text)` ignores its argument and always shows the PURL create help.
  - `usersearch` reads a `tombstoned` attribute that the controller never sets.
  - The password form's `/*[[#{...passwords_different}]]*/` key does not exist, so the page printed
    `??purl_server.error.validate.passwords_different_de??`. That text is now a literal per locale,
    since the key would fail the build.
- `th:style="#{purl_server.domain.form.*.css}"` (CSS from the message bundle, different per locale):
  expressions in `style` are forbidden, so the domain form has one literal-styled label per locale,
  chosen with `t:if="pageLang == 'en'"`. The comment there names the message keys. If those messages
  change, the template has to change too.
- Indexed form fields (`domainUserList[i].user.login`, `.canCreate`, `.canModify`) are written by hand,
  because `t:field` has no indexed paths. They copy Spring's output: `id="domainUserList0.canCreate1"`,
  the `_name` marker input (left out when the checkbox is disabled), and `selected` on the matching
  option. The `admin` checkbox of the user form is written the same way.

## Remaining difference (1 page)

`POST /admin/manager/domain/create ... &submit=` (a successful domain create) returned **500** in the
original app. `createDomain` does not put `error` in the model on success, so Thymeleaf failed on
`error.ok`. Its error page shows a Thymeleaf-specific message ("An error happened during template
parsing ...") and a Thymeleaf stack trace, which no other engine can reproduce. I changed `errorView`
to treat a null `error` as "no error", so the page now renders the success state with 200. That is
what the code intends, but it differs from the recording in status and content.

## Parity setup (`parity/`)

- `pages.txt`: the request list. `baseline/`: the recording of the original app.
  - Compare with `--ignore 'TODAY \d\d:\d\d' --ignore '\d\d:\d\d:\d\d (UTC|GMT)'`. The seed data
    stamps PURLs and users with `NOW()`, and error pages show the current time; only the clock time
    is ignored.
- `data-parity.sql` is `src/test/resources/data-test.sql` with working password hashes.
  - In the test data, `HASH('SHA-256', HASH('SHA-1', ...))` hashes the raw SHA-1 bytes, while the login
    sends the SHA-1 hex string, so no seed user can log in through the form.
  - Both apps ran with `--spring.profiles.active=test --spring.h2.console.enabled=false
    --spring.sql.init.data-locations=file:parity/data-parity.sql`. The H2 console has to be off:
    otherwise the security config fails at startup with two servlets.
- Some pages are 500 in both apps, and their recordings match: H2 rejects the unquoted table name `user`
  in several DAO and login queries. Because of that, the password-reset "unknown user" and "mail sent"
  states can't be reached on H2. When logged out, `POST /admin/login/password` returns 401 because of
  the security config.

## Where the guide or Tessera fell short

1. **No way to render trusted configured HTML without `unchecked`.**
   - `SafeHtml` offers only `escape`, `of(template)` and `unchecked`, and the guide offers no sanitizer.
     Configured site HTML (`purl_server.web.html_fragment.*`) and HTML in message-bundle error messages
     could only be done with my own allowlist parser and a recursive fragment.
   - A built-in sanitizer that returns `SafeHtml` would remove that.
2. **Thymeleaf fragment selector semantics are not covered.**
   - `:: name` in Thymeleaf matches `th:fragment="name"` *and* elements named `name`, and several
     elements may share a fragment name. Tessera reports duplicate fragments and matches only one.
   - The guide doesn't mention either. The visible effect (a stray `<title>` in the body) shows up
     only in `compare`.
3. **`ResourceUrlEncodingFilter` disappears with Thymeleaf.** Boot registers it in
   `ThymeleafAutoConfiguration`. Without it, versionless webjar links are no longer rewritten. It also
   needs the ERROR dispatcher type, or error pages differ. The guide's section 2 doesn't mention this.
4. **`temporals.format` on an `Instant`** compiles but fails at render time with
   `UnsupportedTemporalTypeException`, although the guide says everything other than model and null
   problems is a build error. It also doesn't say which zone, if any, Tessera assumes for `Instant`.
5. **Link path variables percent-encode `/`.** Thymeleaf's `@{/info/purl__${path}__}` idiom has no
   Tessera equivalent that keeps the slashes and still gets the context path. The guide's advice to
   use `{placeholders}` changes the URL.
6. **Locale-dependent values in `style`** (CSS from messages) have no supported conversion. The guide
   suggests `t:classappend`, which changes the markup.
7. **No `#locale`.** The guide lists it among the objects that don't exist, but not its replacement (a
   model attribute). `lang` attributes and locale-keyed messages need it on every page, including
   error pages, where a `@ControllerAdvice` is not enough on its own.
8. **Error pages:** the guide names Boot's model but not how to show `timestamp`, which is a `Date`. I
   used an `ErrorViewResolver`. It also doesn't mention the `exception` and `trace` attributes, which
   appear when `server.error.include-*` is on.
9. **Checkbox / `t:field` output is undocumented**, for example whether Tessera writes Spring's
   `_field` marker input and `id="field1"`. I wrote checkboxes by hand to match Spring's output.
10. **Missing message keys:** `-Atessera.messages` makes a missing key a build error. Pages that
    rendered `??key_locale??` before can't keep that behaviour without hard-coding the text.
11. `tessera-migrate compare` masks only today's *date*. Pages that show times (seed data stamped
    `NOW()`, error timestamps) need `--ignore`, which the guide does mention.
