# Migration report: Thymeleaf → Tessera

All 25 server-rendered views (20 pages, the Spring Boot error page and 3 fragment libraries) are
now compiled Tessera templates. Thymeleaf is gone: its two dependencies
(`spring-boot-starter-thymeleaf`, `thymeleaf-extras-springsecurity6`) are gone from `pom.xml`, and
every template was rewritten in place under `src/main/resources/templates`. The app had no
Thymeleaf configuration in Java or properties. At startup the app logs
`Found 25 compiled Tessera templates` and does not log the "another template engine is still
configured" warning.

## Result

* `mvn clean package` (Java 17 target, built with the default JDK 21) passes: 46 tests, 0
  failures, 1 skipped. The skipped one is `PurlClientTest`, which was already `@Disabled`. No
  test was changed.
* Page parity: I recorded the original app with `tessera-migrate record` against a list of 156
  requests (`.tessera/pages.txt`), then compared the new app started the same way from the same
  data. Result: **153 same, 2 reordered, 1 differs** (details below). A second run of 10 error
  pages with `server.error.include-message=always` came out 10 of 10 same.
  * The list covers anonymous, admin and plain-user sessions, German and English, and every
    page: index, help, login (plain, `?error`, `?logout`), password request, password form,
    the error pages (404, 403, 500 and `/error`), domain and PURL info pages (partial suffix,
    history, deleted), and create, modify, delete and search for PURLs, domains and users. Each
    form is covered empty, with validation errors, after "add user", after success and with a
    "not found" id.
  * I masked three things with `--ignore` because they change on every run: the error page
    timestamp, the port in absolute PURL URLs, and today's time in PURL dates.
* Security: I added no `permitAll` and changed nothing in `SpringSecurityConfig`. CSRF is still
  on, and every POST form, including the logout form, still gets its token. There is no
  `TrustedUrl.unchecked`, and no test is disabled. Output that used to be printed raw now goes
  through a sanitizer (see "Intended differences").

## What changed

* **`pom.xml`**
  * Added `tessera-spring-boot-starter`.
  * Configured the `tessera-compiler` annotation processor in `maven-compiler-plugin`:
    templates root, package `de.uni.rostock.ub.purl_server.templates`, and `tessera.messages`
    pointing at `messages.properties`, so every `#{key}` is checked at build time.
  * Added `tessera-maven-plugin` `check`, so an edit to only a template or a message still
    triggers a recompile.
* **View types.** `@TemplateView` on `Domain`, `DomainUser`, `Purl`, `PurlHistory`, `User` and
  `PurlServerError`.
* **`web/PageModelAdvice`** (`@ControllerAdvice`) provides what the templates used to read from
  outside the model:
  * `pageLang` and `pageLocale`, which replace `#locale`.
  * `titleHtml` and `footerHtml`, which replace `@environment.getProperty('purl_server.web.html_fragment.*')`.
    They are now a `SafeHtml` sanitized with a small policy: headings, `p`, `div`, `span`, `hr`,
    `br`, inline formatting, `a`, `img`, and spacing/text utility classes.
* **`authentication/LoginModelAdvice`** (scoped to `LoginController`) replaces `param.error`,
  `param.logout`, `param.token` and `param.login`. It also provides the alert text of the
  password form (see below). I kept this out of `LoginController` on purpose: that keeps its line
  numbers, so the stack traces the error page shows stay identical.
* **`web/DateFunctions`** (`dates.*`):
  * `dates.format(Instant, pattern)` formats in the JVM zone, as `#temporals.format` did.
  * `dates.legacy(Instant)` prints Boot's error `timestamp` the way `Date.toString()` did.
* **`sec:authorize` and `#authentication`** became `auth.isAuthenticated()`,
  `auth.hasRole('ROLE_ADMIN')` and `auth.name()`.
* **Fragments** are typed:
  * `fragments/page.html` holds `html_header`, `title(...)`, `switchLang(...)`, `navigation`,
    `errorView(PurlServerError)`, `footer(SafeHtml)` and `html_footer`.
  * `fragments/forms.html` holds `domainForm`, `userForm` and `purlForm`. Each now contains its
    own `<form t:object=...>`, because a fragment can't bind to its caller's `t:object`. The
    action is a choice between two literal links, so CSRF still applies.
  * `help`, `nextPurlAction(Purl)` and `nextDomainAction(Domain)`.
  * `fragments/info.html` holds `infoDomainTable(Domain)` and `infoPurlTable(...)`.
* **`__${...}__` preprocessing was removed:**
  * Edit and delete links use `@{/admin/manager/x/modify(id=...)}`.
  * Info links are `|/info/purl${purl.path}|`, because the path already contains `/`.
  * The domain-user rows bind `*{domainUserList[i]...}`.
  * Message keys built from `form` became a choice between literal keys.
* **Tables** now have explicit `<tbody>` elements. The browser builds the same DOM as before.

## Intended differences, and places I reproduced old quirks on purpose

1. **The one `differs`: `POST /admin/manager/domain/create` succeeding.** The old app returned a
   500 here. On success the controller doesn't put `error` in the model, and Thymeleaf threw on
   `${error.ok}` part-way through rendering. This happens with every database, not just H2. Now
   `errorView` treats a missing `error` as "no error", and the page renders the "Domain
   erstellt!" success state with status 200. An exact match isn't possible anyway: the old body
   was a half-rendered page followed by a Thymeleaf stack trace.
2. **The two `reordered`: the password form script (`login/password_form.html`) in German and
   English.** Thymeleaf wrote the message into the middle of the function. Tessera only allows
   a value as a declaration at the top of the script, so the script now starts with
   `const passwordsDifferentMessage = ...;`. The key `purl_server.error.validate.passwords_different`
   doesn't exist in the bundles. To keep the same output, `LoginModelAdvice` returns
   Thymeleaf's missing-key marker `??key_locale??`. It's an app bug I didn't fix: adding the key
   would change the page.
3. **Raw output is now sanitized:**
   * the error page `message` uses `html.sanitize`;
   * `errorMessage` on the login and password-request pages uses `html.sanitize`;
   * the title and footer property fragments use the policy above.

   No recorded page changed because of this.
4. **Old quirks I kept on purpose:**
   * `fragments/page :: title` used to match the `<title>Page fragments</title>` element of the
     fragment file as well as the fragment. So every page had a second `<title>` in its body,
     and the fragment still writes it.
   * The `help(text)` fragment ignored its argument and always showed
     `purl_server.purl.create.help`. It still does.
   * The stray `~{` text in `password_form.html` is still there.
5. **`purl_server.domain.form.{create,modify}.css` in a `style` attribute.** Tessera doesn't
   accept a message in `style`. The template chooses between the two literal values, detecting
   the English bundle by `#{purl_server.domain.form.create} == 'Create'`. That works like the
   bundle lookup did (English → `messages_en`, anything else → `messages`). If those CSS
   messages change, the template has to change with them.

## What I couldn't convert

Nothing. I converted every template.

The checker doesn't need any special app setup, but recording the logged-in pages locally did.
`src/test/resources/data-test.sql` hashes passwords with `HASH('SHA-256', HASH('SHA-1', ...))`,
which hashes raw bytes. The app's `{SHA-256}` encoder expects the SHA-256 of the hex SHA-1, so no
seeded user can log in through the login form. That is true of the original app as well. For my
recordings I used a copy in `.tessera/data.sql` that wraps the hashes in `RAWTOHEX`. I didn't
change the test data or the app.

A few failures happen on H2 only and are the same in both apps:
* `UPDATE user ...` and `INSERT INTO user ...` are invalid SQL on H2, so user create, user delete
  and password reset return 500.
* Domain search by user returns 500.

## Where Tessera or its documentation fell short

1. **`HtmlPolicy.Builder.allowAttributes("href", "src")` fails only at runtime.** It throws
   `IllegalArgumentException: not an allowed attribute: href` from a static initializer, so the
   app failed at startup with `ExceptionInInitializerError`. The guide doesn't say that
   `href`/`src` are handled implicitly for `a`/`img` and aren't in `HtmlPolicy.ATTRIBUTES`. A
   compile-time check, or a sentence in 4.4, would have caught it.
2. **`t:field` on a list index past the end fails the render** (`Index 0 out of bounds for
   length 0`). Thymeleaf/Spring auto-grow nested paths there, and Tessera doesn't. The guide
   doesn't mention it. The domain form always shows one empty row past the end of
   `domainUserList`, so I write that row by hand with computed `id`/`name` attributes.
3. **Fragments can't see the caller's `t:object`.** Shared form-body fragments (Thymeleaf's
   `th:replace` inside `<form th:object>`) have to take the form object and declare `t:object`
   themselves, or take each `Field`. The guide shows `Field` parameters but not this common
   "whole form body" case.
4. **No way to put a `#{message}` in `style`.** Section 4.4 lists what `style` accepts, but has
   no recipe for locale-dependent CSS that lives in the bundle.
5. **Scripts:** the "declaration at the top" rule always changes the script text when the
   original value sat inside a function. `compare` reports this as `reordered` (it passes), but
   the guide doesn't say that it will.
6. **Missing message keys:** with `-Atessera.messages`, a missing key is a build error (good).
   The guide doesn't say how to reproduce Thymeleaf's `??key_locale??` output where parity
   needs it. I had to resolve it in Java.
7. **`@{...}` and values containing `/`:** the guide says link placeholders are percent-encoded,
   and it describes no form that inserts a stored path such as `/test/x` unencoded. So I used a
   string (`|/info/purl${path}|`) without trying one. A string gets the URL-scheme check but not
   the context path or `encodeURL`. The app runs at the root context, so the output is the same.
   The guide's link section doesn't cover this case.
8. **`temporals.format` with an `Instant`:** the guide doesn't say which zone it uses. I wrote
   `dates.format` to match Thymeleaf's JVM-zone behaviour rather than guess.
9. **`compare`** normalizes today's dates to `TODAY`, but not `Date.toString()` timestamps or the
   port in absolute URLs, so those needed `--ignore`. That's minor and was easy to handle.
