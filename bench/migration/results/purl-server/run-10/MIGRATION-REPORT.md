# Migration report: Thymeleaf → Tessera

## Result

| Check | State |
|---|---|
| Every page is rendered by Tessera | Yes. All 25 templates (20 pages, 3 fragment files, error page, login pages) are Tessera templates. At startup the log says "Found 25 compiled Tessera templates" and does not warn that another template engine is still configured. |
| Old engine removed | Yes. `spring-boot-starter-thymeleaf` and `thymeleaf-extras-springsecurity6` are gone from the pom. Comparing `mvn dependency:list` before and after, only `thymeleaf`, `thymeleaf-spring6`, `thymeleaf-extras-springsecurity6`, `attoparser` and `unbescape` were dropped. Nothing else the app uses came in through them. There was no Thymeleaf configuration in the properties. |
| `mvn clean verify` (JDK 21, `release 17` as before) | Passes. All existing tests pass unchanged (PurlClientTest was already `@Disabled`). I added `ViewRenderingTests`, which renders through the real view resolver and checks that request data is escaped. |
| Page parity (`tessera-migrate compare`, 148 requests) | 147 `same`, 1 `differs` (page 070, explained below). |
| Security | No `SafeHtml.unchecked` or `TrustedUrl.unchecked` (`target/tessera-review.txt` lists 0). No new `permitAll`. CSRF and escaping are unchanged, and no tests are disabled. |

## What changed

* **pom.xml**: adds `tessera-spring-boot-starter`, plus `tessera-compiler` as an annotation processor with `-Atessera.templates`, `-Atessera.package=de.uni.rostock.ub.purl_server.templates` and `-Atessera.messages` (so every `#{key}` is checked at build time). It also adds `tessera-maven-plugin` (`check` and `review` goals). The Java level is still `java.version` 17.
* **Templates** (`src/main/resources/templates/**`): rewritten with `t:` attributes. Each one declares typed `t:params`. Includes are now typed fragment calls:
  * `fragments/page`: `html_header` and `title` are `<t:block>` fragments. That keeps the original output exactly, including the stray `<title>Page fragments</title>` that Thymeleaf's `:: title` selector copied into every page body. Also here: `title(String)`, `footer(String)` and `errorView(PurlServerError)`.
  * `sec:authorize` → `auth.isAuthenticated()` / `auth.hasRole(...)`. `#authentication.name` → `auth.name()`.
  * `#locale` → `site.lang()` / `site.en()` (a `@TemplateFunction` that takes the render locale).
  * The `__${form}__` message keys and the `__${#locale}__` flag image became choices between literals.
  * The `__*{path}__` info links became `'/info/purl' + purl.path`.
  * `?id=__${x.id}__` → `@{...(id=x.id)}`.
  * The form fragments (`domainForm`, `userForm`, `purlForm`) take the form object and their flags as parameters and carry `t:object` themselves.
* **`@TemplateView`** on the model classes the templates read: `Domain`, `DomainUser`, `Purl`, `PurlHistory`, `PurlServerError`, `User`.
* **New `web` package**:
  * `SiteFunctions` (`@TemplateFunctions("site")`) provides:
    * `lang` and `en` (locale);
    * `dateTime(Instant)`, replacing `#temporals.format(..., 'yyyy-MM-dd HH:mm')` in the JVM zone, as before;
    * `timestamp(ZonedDateTime)`, the error page's `Date.toString()` form;
    * `html(Object)`, which sanitizes with an app `HtmlPolicy` (headings, `p br hr b i em strong small span div a`, Bootstrap spacing and text classes).
  * `SiteModelAdvice`: a `@ControllerAdvice` that puts the configured `purl_server.web.html_fragment.title` and `.footer` into every model. It is also the `ErrorViewResolver`, so the error page gets them too. They used to be read with `@environment.getProperty(...)` and printed with `th:utext`; now they go through `site.html(...)`.
  * `DomainFormInterceptor`: the domain create and modify forms always showed one empty "add user" row past the end of `domainUserList`. Thymeleaf got that row from Spring's auto-growing `BeanWrapper`. Tessera reads the bean and throws `IndexOutOfBoundsException`, so the interceptor appends the empty `DomainUser` before these two views render.
* **`LoginController`**: `GET /admin/login` puts `error` and `logout` (formerly `param.error` and `param.logout`) into the model. The password form puts `token` and `login` (formerly `param.*`) into the model.
* Raw output that is now sanitized: the configured title and footer, the error page `message`, and the login and password-request `errorMessage`. All of these go through `site.html(...)`. The two `th:utext="#{...}"` messages on the login page stay `t:utext` with build-time-checked bundle messages.

## Parity method

* `pages.txt` has 148 requests. Every page is covered in these states: logged out, as `user1` (non-admin) and as `admin`; German and English; forms empty, with validation errors, after success and after "add user"; empty and full search lists; not-found records; 400, 403, 404 and 500 error pages; logout.
* Both apps were started the same way from the test data:
  * the test classpath with `--spring.profiles.active=test`;
  * `--spring.h2.console.enabled=false`, because Spring Security 6.1 refuses to start with the H2 console servlet registered;
  * one extra fixture script (`.tessera/extra.sql`, not part of the app) that gives `admin` and `user1` `{noop}` passwords. The seed data in `data-test.sql` hashes the raw SHA-1 *bytes*, which no form login can produce, so without it no logged-in page could be recorded.
* `compare` ran with `--ignore '\d\d:\d\d(:\d\d)?'`. Rows created by `NOW()` and the error page timestamp show the clock time, which changes on every run.

### The remaining difference (page 070) and why it is right

`POST /admin/manager/domain/create` with valid data. In the original app `createDomain` never puts `error` into the model on success, so `errorView`'s `${error.ok}` failed. The page came back as a **500** whose message and stack trace are Thymeleaf internals (`TemplateInputException ... AbstractMarkupTemplateParser`). A Thymeleaf stack trace can't be reproduced without Thymeleaf. I made `errorView` treat a missing error as "no error", so the page now renders the "domain created" result with status 200. That is what the controller intended, and it is the same page as the other create pages.

## Deliberate deviations kept for parity (worth cleaning up later)

* `login/password_form.html`: the script uses the message key `purl_server.error.validate.passwords_different`, which doesn't exist. Thymeleaf therefore wrote `"??purl_server.error.validate.passwords_different_de??"` (or `_en`). Tessera rejects unknown keys at build time and only allows `[[...]]` in a script's prelude. So the page writes the same literal in two `<script>` blocks, chosen by locale. The real key is `purl_server.error.login.passwords_different`; fixing it is a one-line change, but it would change the page.
* `purl_server.domain.form.create.css` and `.modify.css` are messages used as `th:style`. A Tessera `style` attribute accepts only literals and `CssValue`s, and `css.*` has nothing for `transform`. So the two per-locale values are now written as literals in `fragments/forms.html`, chosen with `site.en()`. The message keys are no longer read from templates.
* The `help` fragment ignored its argument and always printed `purl_server.purl.create.help`. It still does; it now takes no argument.
* The literal `~{` text left over in `password_form.html` is kept, written as `[['{']]` because Tessera forbids a bare `{` in text.

## Where Tessera or its documentation fell short

1. **`th:field` on an index past the end of a list.** Thymeleaf binds through Spring's auto-growing `BeanWrapper`, so `*{list[size]...}` renders an empty row. Tessera's `t:field` throws `IndexOutOfBoundsException` at render time, on GET and on POST alike. The guide's "Lists of beans" section doesn't mention this, and the migrate report gave no warning (it only reported the `__...__` preprocessing).
2. **Thymeleaf fragment selectors that match several elements.** `~{fragments/page :: title}` also matched the `<title>` element, and `:: html_header` matched three sibling elements. The migrate tool converts these silently. Tessera takes only one element per fragment, so without a baseline the stray `<title>` and the missing `<link>`/`<style>` would have gone unnoticed. Only the comparison found it, and I used `<t:block t:fragment>`. The guide says a `~{::name}` selects only the first match, but not that a `t:fragment` name declared on several elements behaves differently.
3. **Messages in `style`.** Nothing in the guide covers `th:style="#{key}"`. The `css.*` functions don't cover `transform`, and a per-locale message can't be validated into a `CssValue`.
4. **`tessera-migrate` and `th:object` together with `th:if` on the same `<form>`.** The converter kept these attributes, but when I moved `t:object` into the form fragments the compiler error ("t:field can only be used inside t:object") gave no hint about fragments. A `Field<Domain>` covering the whole object isn't possible, so I put `t:object` on the fragment's root element. That works, but it isn't documented as the pattern for form fragments.
5. **Boot error pages and `@ControllerAdvice`.** The guide explains the error model's types (`timestamp` as `ZonedDateTime`, which works). It doesn't say that model attributes from `@ControllerAdvice`, which it recommends for values every page needs, never reach `templates/error.html`. I had to add an `ErrorViewResolver` to pass the site title and footer.
6. **`Date.toString()` parity.** The guide suggests `temporals.format(timestamp, 'EEE MMM dd HH:mm:ss zzz yyyy')`, but that formats in the render locale (German day and month names here, plus a different zone name). A custom function was needed to match the old output.
7. **Running the app for recording.** The skill assumes the app can simply be started and logged into. Here that needed the H2 console disabled (Spring Security 6.1 refuses a second servlet otherwise) and a credential fixture. `mvn spring-boot:run -Dspring-boot.run.useTestClasspath=true` did not find the test resources. This is the app's problem, not Tessera's, but the guide could warn about it.
8. Minor: in the repackaged Boot jar, `META-INF/tessera/functions/*` and `templates.fingerprint` end up at the jar root, not under `BOOT-INF/classes` (Boot moves `META-INF` there). Rendering is unaffected because the generated code calls the functions directly. The guide's packaging note doesn't say whether the runtime ever needs these files.

## Files that are not part of the application

* `pages.txt` is the parity request list. Keep it to re-run `compare`.
* `.tessera/` (the migrate jar, run scripts, credential fixture), `baseline/` and `migrate-report.md` are git-ignored working files.
