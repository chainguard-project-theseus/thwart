# Migration report: Thymeleaf → Tessera

## Result

| Check | State |
|---|---|
| Every page is rendered by Tessera | Yes. All 25 Thymeleaf templates (20 pages and 3 fragment files, under `src/main/resources/templates`) are now Tessera templates and are compiled by `javac`. |
| Old engine removed | `spring-boot-starter-thymeleaf` and `thymeleaf-extras-springsecurity6` are gone from `pom.xml`, and `mvn dependency:tree` lists no Thymeleaf artifact. No `th:` or `sec:` markup is left. The two properties that only Thymeleaf read (`purl_server.web.html_fragment.*`) are removed. |
| `mvn clean verify` (JDK 21, `java.version` 17 as before) | Passes. 50 tests ran, 0 failures, and 1 skipped (`PurlClientTest`, which was already skipped before). No existing test was changed. |
| Page parity (`tessera-migrate compare`) | `migration/pages.txt` (115 requests): 113 `same`, 2 `differs`; both are explained below. `migration/pages2.txt` (49 requests): 49 `same`. |
| Security | No `SafeHtml.unchecked` or `TrustedUrl.unchecked` anywhere. `SpringSecurityConfig` is unchanged: no new `permitAll`, and CSRF is still on (forms get `_csrf` automatically). Escaping is not turned off. No test was disabled. Three places that printed raw HTML now print escaped text (see below). |

## What I changed

**Build (`pom.xml`)**
- Swapped the Thymeleaf starters for `dev.tessera:tessera-spring-boot-starter:0.1.0-SNAPSHOT` (jakarta, Boot 3.1.3 is unchanged).
- Added `maven-compiler-plugin` configuration:
  - `tessera-compiler` as the annotation processor;
  - `-Atessera.templates` and `-Atessera.package=de.uni.rostock.ub.purl_server.templates`;
  - `-Atessera.messages` pointing at `messages.properties`, so every `#{key}` is checked at build time.

**Java**
- `model/{Domain,DomainUser,Purl,PurlHistory,PurlServerError,User}`: added `@TemplateView`, because templates may only read view-safe types.
- `web/ViewModelAdvice` (new): a `@ControllerAdvice` that supplies `locale` (for example `de`) and `lang` to every model. These replace Thymeleaf's `#locale`.
- `web/HtmlErrorViewResolver` (new): an `ErrorViewResolver` that renders `error` with Boot's error attributes. It passes `timestamp` (a `java.util.Date`, which isn't view-safe) as the same `Date.toString()` text Thymeleaf printed. JSON error responses are unchanged.
- `web/TemplateFormat` (new): the `@TemplateFunctions("fmt")` namespace.
  - `fmt.dateTime(Instant)` formats `yyyy-MM-dd HH:mm` in the system zone, like Thymeleaf's `#temporals.format`. Tessera's `temporals` has no zone for an `Instant`.
  - `fmt.lines(String)` splits the login error text on `<br />` so each line prints escaped.
- `authentication/LoginController`: added four `@ModelAttribute` methods (`paramError`, `paramLogout`, `paramToken`, `paramLogin`) that replace Thymeleaf's `${param.*}`.
  - I appended them at the end of the class and used fully qualified annotation names, so no existing line moved. The 500 error pages print stack traces with controller line numbers, and those still match the recording.
- No other controller was touched, for the same reason.

**Templates**
- **Layout fragments.** `fragments/page.html`, `forms.html` and `info.html` became typed fragments: `title(String locale)`, `errorView(PurlServerError)`, `domainForm(Domain, boolean, List<User>, String form, String locale)`, `infoPurlTable(Purl, String, String, String)`, and so on.
  - Every value they used to read from the caller's scope is now a parameter.
  - Each form fragment carries its own `t:object`.
- **`sec:authorize`** became `t:if` with `auth.isAuthenticated()` or `auth.hasRole('ADMIN')`. `#authentication.name` became `auth.name()`.
- **`__${...}__` links** became link parameters (`@{/admin/manager/purl/modify(id=purl.id)}`) or string concatenation for the info links (`'/info/purl' + purl.path`).
  - The path contains `/`, and a link path variable would percent-encode it.
  - Concatenation also keeps the old output `/info/domainnull` when a posted domain has no path.
- **Message keys built at runtime** (`#{purl_server.domain.__${form}__.submit}`) became conditionals between literal keys.
- **`#{...}` used as a `style`** (the rotated "Create" and "Modify" column headings in the domain form) is now two literal `style` variants, chosen by `locale`. Tessera rejects computed styles.
- **Thymeleaf output quirks kept on purpose**, so pages stay identical:
  - the stray `<title>Page fragments</title>` in every body (Thymeleaf's `:: title` selector also matched the `<title>` tag);
  - the `1` suffix on checkbox ids (`admin1`, `domainUserList0.canCreate1`);
  - `value=""` on the empty user option after "Add user";
  - the `??purl_server.error.validate.passwords_different_<locale>??` text for the message key that is missing from both bundles. I left the key missing and noted it here.
- **Domain form's empty "new user" row.** Thymeleaf bound it through Spring's auto-growing list. Tessera reads the real list and threw `IndexOutOfBoundsException`. The bound rows now use `t:each` with `t:field`, and the extra empty row is written by hand with the same names and ids.
- **The `help(text)` fragment** ignored its argument and always showed the PURL-create help. It is now a fragment without parameters, with the same output.
- **The user-search "tombstoned" checkbox** read a model attribute that never exists (`tombstoned`), so it was never checked. It is now a static unchecked checkbox, with the same output.

**Tests**
- Added `ViewRenderingTest`. It renders the info page, an error page and the domain form through the real view resolvers, as admin, and checks that `<script>` in user input comes out escaped.

**Migration artefacts**
- `migration/pages.txt` and `migration/pages2.txt` are the request lists I compared against.
- `migration/data-record.sql` is a copy of `data-test.sql` whose password hashes work with the login form. The H2 `HASH()` expressions in `data-test.sql` produce hashes that no login can match, so logged-in pages could not be recorded with it.
- I recorded both apps the same way:

  ```
  mvn spring-boot:test-run -Dspring-boot.run.profiles=test \
    -Dspring-boot.run.arguments="--spring.h2.console.enabled=false --spring.sql.init.data-locations=file:migration/data-record.sql"
  ```

  The H2 console has to be off: with it on, the original app does not start, because `requestMatchers` can't tell which servlet a pattern belongs to.
- Compare ran with these `--ignore` patterns: the error timestamp (`Fri Oct 23 04:29:14 GMT 2026`), the port in `localhost:808x` (the two apps ran side by side), and the `HH:mm` of rows created with `NOW()`.

## Remaining differences, and why they are right

1. **`GET /test/redirect1`** redirects to `http://www.google.de`. The status and final URL match; only Google's own page differs from run to run (nonces, ids). The app renders nothing here.
2. **`POST /admin/manager/domain/create` that succeeds.** The original returns **500**: `fragments/page :: errorView(${error})` evaluated `error.ok` on a null `error`, because `createDomain` doesn't add `error` on success (`SpelEvaluationException: Property or field 'ok' cannot be found on null`). Tessera renders the intended success page with 200 instead.
   - I didn't reproduce the crash on purpose. Even a deliberate 500 would differ, because its page shows a Thymeleaf-internal stack trace.

## Behaviour changes (intended)

- **Output that was raw is now escaped**:
  - `error.html` message (`th:utext="${message}"`);
  - `login/password_request.html` `errorMessage`;
  - the `login.html` and `logout` notices.

  The login error detail keeps its `<br />` line breaks: each line is escaped, and the breaks are real `<br />` elements. With the current messages and data none of these strings contains markup, so the pages are identical.
- **Title and footer markup are no longer configurable.** `purl_server.web.html_fragment.title` and `.footer` were raw HTML read from configuration through `@environment` and printed with `th:utext`. Tessera can't print configurable markup without `SafeHtml.unchecked`, so the markup now lives in the `brand` and `footer` fragments of `templates/fragments/page.html`. Changing it now means editing that file and rebuilding. A deployment that overrode these properties will no longer see its own markup.

## Where Tessera or its documentation fell short

- **No way to output configurable trusted HTML** (from properties or a CMS) without `SafeHtml.unchecked`. There is no built-in sanitizer that returns a `SafeHtml`, so configurability had to go.
- **The `temporals` namespace can't format an `Instant`.** The guide doesn't say which zone, if any, is used. Thymeleaf used the system zone. I wrote `fmt.dateTime`.
- **Indexed form paths don't emulate Spring's auto-growing lists.** `*{list[i].x}` with `i == size` throws `IndexOutOfBoundsException` at render time, where Thymeleaf/Spring silently grew the list. The guide's "Lists of beans" section doesn't mention it.
- **`t:field` checkbox ids differ from Thymeleaf's.** Thymeleaf numbers checkbox ids (`admin1`); Tessera writes `admin`. Section 7 lists "Radios get no id", but not this. Setting `id`/`t:id` works around it.
- **Fragment selectors.** The guide doesn't warn that Thymeleaf's `:: name` also matches elements by *tag name* (here `<title>`), which Tessera's named fragments don't. That is an easy-to-miss parity difference.
- **`tessera-migrate` conversion:**
  - it left `#locale`, `sec:authorize`, `@environment` and `__...__` for hand work (expected), and also emitted `t:lang="${#locale.language}"` unchanged;
  - it typed the fragment stubs as `Object`;
  - it kept `th:inline="javascript"` and the `/*[[...]]*/` natural-template comment, which the guide says are unsupported;
  - it turned the `th:if="${tombstoned}"`-style lookups of attributes that never exist into `t:params`, with nothing to say they are always null.
- **`compare` and changing data.**
  - It can't mask a different port, or times that aren't today's date (`NOW()` rows), without `--ignore`.
  - Running `compare` twice against one app instance silently compares mutated data. The guide's "start from the same data" is easy to miss when re-running.
- **No warning for unsafe parameter types.** Passing a missing `Boolean` model attribute to a `boolean` fragment parameter is legal at compile time but would throw at render time. I passed `submitted == true` to avoid it, and the guide doesn't mention this pattern.
- **Missing message keys.** With `-Atessera.messages`, a key that Thymeleaf rendered as `??key_locale??` (missing in the original) is a build error. Keeping the old output needs a hand-written string, which is not documented.
