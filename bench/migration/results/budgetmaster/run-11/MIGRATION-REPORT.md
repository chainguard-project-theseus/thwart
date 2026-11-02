# BudgetMaster: FreeMarker → Tessera migration report

## Summary

All 75 views are now Tessera templates (`src/main/resources/templates/**/*.html`). These include every page, every AJAX view or modal, the error pages and the settings containers. FreeMarker is completely gone:

- all `.ftl` files are deleted;
- `spring-boot-starter-freemarker` is removed from the pom;
- the `spring.freemarker.*` properties are removed;
- `StaticClassesAdvice` is removed (it exposed `static[...]` to templates through FreeMarker's `BeansWrapper`).

At startup the app logs `Found 75 compiled Tessera templates`. The warning `another template engine is still configured` no longer appears.

## What changed

### Build (`BudgetMasterServer/pom.xml`)
- Added `dev.tessera:tessera-spring-boot-starter` (Boot 3 / jakarta) at `${tessera.version}=0.1.0-SNAPSHOT`.
- `maven-compiler-plugin` now has explicit `annotationProcessorPaths`, in this order: Lombok, `hibernate-jpamodelgen`, `tessera-compiler`. Both Lombok and jpamodelgen were previously picked up by processor discovery.
- Compiler arguments added: `-Atessera.templates=…/templates` and `-Atessera.package=de.deadlocker8.budgetmaster.views`.
- Added `tessera-maven-plugin` `check`, so that template-only edits trigger a recompile.

### Java support code (`de.deadlocker8.budgetmaster.templating`)
- **`loc` (`LocFunctions`):** `loc.get(key[, args…])` wraps the app's thecodelabs `Localization`, which replaces FreeMarker's `locale.getString`. The app doesn't use Spring's `MessageSource`, so `#{…}` isn't used.
- **`bm` (`BudgetMasterFunctions`):** replaces the FreeMarker calls on the `helpers`, `dateService`, `currencyService` and `updateService` model beans, `static[...]` (`EntityType`, `ProgramArgs`, `Localization`) and the template globals `greenTextColor`/`redTextColor`.
  - These functions reach the singleton services through `TemplateBeans`, a static `ApplicationContext` holder.
  - The guide advises against reaching beans through static state. I chose it because about 440 `locale.getString` calls and some 100 service calls are spread over 95 templates. A per-page view record for each would not have fit the time box.
  - None of these functions reads request state.
- **`ViewModelInterceptor`:**
  - copies the session attributes into the model, as `spring.freemarker.expose-session-attributes=true` did (filter configuration, CSV import state, etc.);
  - replaces the `BindingResult` that controllers put in the model as `error` with the view-safe record `FormErrors`. The `val` functions (`ValidationFunctions`) use it to replace `helpers/validation.ftl`.
- **Per-area function namespaces:** `tx`, `acc`, `st`, `misc`, `viz`. Each class says what it replaces.
- **`@TemplateView`** is on the entities and DTOs that the templates read. `User` deliberately does not have it.

### Templates
- **Shared files:**
  - `helpers/layout.html` (`page(title, head, content)`) replaces the `<html>`/`header.globals`/`header.header`/`header.body` structure.
  - Every FreeMarker macro library became a fragment library: `header`, `navbar`, `scripts`, `customSelectMacros`, `iconSelect`, `fontColorPicker`, `globalDatePicker`, `filterMacros`, `transactionsMacros`, `newTransactionMacros`, `settingsMacros`, `chartFunctions`, `searchMacros`, `templateFunctions`, `templateGroupFunctions`, `categoriesFunctions` and `indexFunctions`.
  - Macro defaults are now passed explicitly. Each library's header comment documents the defaults.
- **Hand-written `_csrf` inputs are removed.** Tessera adds the token to every same-origin POST form.
- **Raw output:**
  - FreeMarker had no HTML output format here, so everything was printed raw. Values are now escaped.
  - Bundle texts with `<br>`/links go through narrow `HtmlPolicy` sanitizers or are split into lines in Java.
  - Notification messages (which contain an `<a>` built in Java, `NotificationLinkBuilder`) go through `html.sanitize`.
- **Chart scripts:** stored, user-authored chart JavaScript was pasted raw into an inline `<script>`, together with JS-string-concatenated chart names and dates. Now:
  - the values go in a `<script type="application/json">` block;
  - `static/js/chartScript.js` sets the same globals, applies the same text replacements and runs the same script under the page's nonce.
  - The same code runs as before, and the chart-name and `</script>` injection points are closed.

### Controllers / JS adapted to Tessera's rules
| Change | Reason |
|---|---|
| `SearchController`: model attribute `page` is now a record (`SearchResultPage`) | Spring's `Page` is not view-safe |
| `ChartController`: `containerID` is now a String; display and group types are now Lists | `UUID` and arrays are not readable from templates |
| `AccountController`: account states are now a List | arrays are not readable from templates |
| `TemplateController`: existing template names are passed as a List, not a Gson string | they are written as script JSON |
| `SettingsController`: toast content is now a String | |
| `SettingsController`: new import-step-1 attribute | |
| `TransactionImportController`: new `csvFileName` attribute | `MultipartFile` is not view-safe |
| `MediaController`: new `GET /media/getImage/{ID}` | the image picker used `data:` URLs, which Tessera's URL context rejects |
| `main.js`: `initTooltips` | computed `data-tooltip` (a Materialize HTML channel) is now `title`, passed to the plugin escaped |
| `main.js`: hint/notification `data-id` | now a number; the JS adds the `hint-`/`notification-` prefix |
| `import.js`, `transactionImport.js` | computed `data-title`/`data-text`/`data-order`/`data-search` strings are now JSON blocks read by index |
| `settingsContainers.js` / backup script | read the CSRF token from the form's `_csrf` input, because the old `id="token"` input is gone |

## Verification
- **Parity:**
  - `.tessera/pages.txt` has 214 requests covering every page and form state the tool can reach (lists empty and full, forms empty, with errors and after success, error pages, AJAX modals, dark theme, German). It was recorded from the original app on a fresh database.
  - Ignored when comparing: the random chart container UUID, and the random delete-database verification code.
  - Result: every page has its recorded status and final URL, and the rendered content is the same apart from the differences listed below. 34 pages come out `same`. The others are `differs` **only** because of these systematic, intended changes:
    1. **Script values must be declarations at the top of a script** (guide 4.4). For example, `accountPlaceholderName = "All Accounts"` is now `var accountPlaceholderName = "All Accounts"`. This is in the common scripts fragment, so it touches every full page. Month names, validation messages, dates and tags are also now `var` declarations of JSON values.
    2. **Tooltip recipe:** computed `data-tooltip` is now `title` (validation tooltips, account names, help texts).
    3. **Hint and notification `data-id`** is now a number instead of `hint-N` / `notification-N` (the id-with-prefix recipe).
    4. **CSRF:** the hand-written `<input id="token" name="_csrf">` is now Tessera's own token input, which has no `id`.
    5. **Sanitizer output:** links in sanitized messages and notifications get `rel="noopener"` / `rel="nofollow noopener"` and lose `class="text-default"`. Notification links were raw HTML before.
    6. **Broken old markup:**
       - FreeMarker output with a stray quote produced junk attributes such as `text-white"`, which Tessera can't write. Pages now carry what a browser makes of the rest.
       - Pages whose old markup left `<div>`s unclosed before `</main>` now close them where the HTML spec says. `compare`'s parser nests the trailing `<script>`s deeper than ours on those pages (`/transactions`, `/templateGroups`, …).
    7. **Hidden amount inputs** print the raw number (`250000`), where FreeMarker printed a grouped one (`250,000`).
    8. **`import.js`, the CSV import table and the delete modals:** the JSON-block recipes from guide 4.4.
- **Tests:** `mvn package` runs the same tests as before; no test was changed, disabled or deleted.
  - On the original code, 27 tests already fail in this environment: every `@Testcontainers` test (no Docker here) and `DatabaseServiceTest` (4 file-ordering assertions).
  - The migrated build fails the same set and no other. See "Test status" below.

## Could not convert / could not check
- **No page was left in FreeMarker.**
- **Not reachable by the parity list:**
  - error/403: a POST without a token ends in a 405;
  - the multipart flows `settings/importStepOne` and `importResult`, and the CSV import rows (`transactionImportRow`);
  - the date-dependent backup and account-end-date reminder modals in the navbar.
  - The agents checked the multipart pages by hand with curl against a copy of the original app; they matched apart from the `data-index` recipe.
- **Exact parity is impossible** for the differences in "Verification" items 1–4 (and 8). Tessera rejects the original form by design.

## Where Tessera or its documentation fell short
1. **Messages:** `#{key}` only works with Spring's `MessageSource`. Apps with their own localization (here thecodelabs `Localization`) get no build-time key checking and need a wrapper function.
2. **Raw HTML inside `<p>`:** `SafeHtml` / `t:utext` inside a `<p>` is rejected (`RAW_CLOSES_ELEMENT`) even when the policy allows only inline elements such as `<br>`, because the verifier ignores the policy. Bundle texts with `<br>` had to be split into lines in Java.
3. **`HtmlPolicy` surprises:**
   - `HtmlPolicy.builder().allowAttributes("href")` compiles but throws at runtime ("not an allowed attribute"). Inside a static initializer this turned pages into 500s. The allowed-name sets aren't documented.
   - The sanitizer adds `rel="noopener"` (or `nofollow noopener`) whenever `allowLinkTarget()` is used, even with `linkRel()` given no tokens. The guide mentions only `noopener noreferrer`, and only for messages.
4. **No `t:with` on a layout call:** `t:replace` runs before `t:with`, so a page can't define a local for its own layout call. The title expressions had to be repeated.
5. **Enums in `data-*`:** enum names need a hand-written nested choice of every constant (`state == 'A' ? 'A' : …`). `.name()` isn't readable, and the choice silently goes stale when a constant is added.
6. **No recipe for:**
   - per-record `data:` image URLs (a new endpoint was needed);
   - stored, user-authored JavaScript (charts);
   - a template that is both a returnable view and a fragment library (the root calling its own fragment works, but isn't documented);
   - error pages under a custom `ErrorController` (the guide only describes `BasicErrorController`'s attributes).
7. **Not view-safe:** `UUID`, Spring's `Page`, `MultipartFile`, primitive/enum arrays passed to `List` parameters and non-getter methods such as `hasFontAwesomeIcon()`. Each needs Java changes. `UUID` in particular is a JDK value type one would expect to be allowed.
8. **The auto-inserted CSRF input has no `id`.** JavaScript that read `#token` broke without warning.
9. **Old markup with a quote inside an attribute name** (FreeMarker junk such as `text-white"`) can't be reproduced, so parity is impossible on those pages.
10. **Script declarations:** the "declaration at top of script" rule changes every page with an inline assignment such as `x = "…"`, so `compare` can never report `same` there. A plain global assignment is not accepted.
11. **The `tessera-migrate` tool:**
    - `compare` truncates long diffs ("… N more diff line(s)") with no option to print them all.
    - Its parser appears to handle unclosed elements before `</main>` differently from the HTML spec.
    - `--ignore` patterns on `<script>` miss Tessera's pages because of the added `nonce` attribute.
    - It has no mechanical rewrite mode for FreeMarker (only Thymeleaf).
12. **Compiler:**
    - Errors in an uncalled fragment library surface only once a page calls the fragment.
    - One failing template blocks the whole module, so templates can't be checked in isolation during an incremental port.
13. **`t:replace` / `t:if` on the same element:** that `t:if` doesn't guard the `t:replace` is documented, but it is an easy trap. One such guard in our own shared header was silently ineffective until it was found.

## Test status
`mvn package` (JDK 21) runs 315 tests: 4 failures and 42 errors.

- **All 42 errors** are `IllegalStateException: Could not find a valid Docker environment`. They come from the `@Testcontainers` and Selenium tests, and there is no Docker in this environment.
- **The 4 failures** are `DatabaseServiceTest.test_determineFilesToDelete_*`, file-ordering assertions that fail identically on the untouched original code.
- **The original code's own run** was cut short: its surefire fork was terminated, so it reported only 241 tests. Before that it showed the same Docker errors and the same 4 failures.
- **Every other test passes.** No test was modified, disabled or deleted.

## Final parity run
I started the migrated app on a fresh database and replayed the 214-request list, with the two ignore patterns above.

- **Status and final URL:** every page has its recorded status and final URL. That includes the original's expected 404s and the 500s on `/accounts/{id}/select` and `/accounts/{id}/setAsDefault`, which are caused by the missing Referer header in the tool.
- **Content:** 34 pages are `same`; 180 are `differs`. Every remaining difference is one of the intended categories 1–8 under "Verification".
- **Exit code:** `compare` therefore exits 1. Category 1 alone (the shared `var accountPlaceholderName` declaration) is on every full page.
