# BudgetMaster: FreeMarker → Tessera migration report

## Summary

All server-rendered pages of BudgetMasterServer (69 FreeMarker `.ftl` files, ~5,800 lines) are now Tessera
templates (`src/main/resources/templates/**/*.html`), compiled by `tessera-compiler` during `javac` into
`de.deadlocker8.budgetmaster.views.*`. FreeMarker is gone: no `.ftl` files, no `spring-boot-starter-freemarker`
dependency, no `spring.freemarker.*` properties, and `StaticClassesAdvice` (which exposed
`BeansWrapper.getStaticModels()` to templates) is deleted. The only remaining mention of "freemarker" is the
library list inside the `credits` message text of the language bundles (user-visible text on the About page,
left unchanged so the page stays identical).

No `SafeHtml.unchecked` / `TrustedUrl.unchecked`, no new `permitAll`, CSRF and escaping untouched, no tests
changed or disabled.

## Build

- `BudgetMasterServer/pom.xml`: `spring-boot-starter-freemarker` → `dev.tessera:tessera-spring-boot-starter`
  (`jakarta.servlet`, Boot 3.5 kept); `maven-compiler-plugin` gets `annotationProcessorPaths` for
  `tessera-compiler` **plus Lombok and hibernate-jpamodelgen** (they were discovered implicitly before; listing
  processors turns discovery off), `-Atessera.templates`, `-Atessera.package`, `<parameters>true`.
- `-Atessera.messages` is not set: the app does not use Spring's `MessageSource`; it uses its own
  `Localization` (three bundles `languages/base|news|hints`). See "Shortcomings".
- `mvn clean verify` from the repository root (JDK 21) gives the same result as the untouched original in
  this environment: 315 tests run, 4 failures and 42 errors in exactly the same tests. All are
  environmental:
  - Testcontainers needs Docker (`*ImporterTest`, `ImportServiceTest`, `Transaction*SpecificationsTest`,
    `TransactionServiceDatabaseTest`).
  - The Selenium tests need Firefox/geckodriver.
  - `DatabaseServiceTest` asserts on captured log output; it fails on the original too, even when run alone.

  All other tests pass. No test was changed. (The first run of the original crashed in
  `LocalGitBackupTaskTest` with a forked-VM exit; on a second run the original matched the numbers above.)

## How the templates get their data

The FreeMarker templates read a lot from outside the model (static classes via `static[...]`, Spring service
beans put into the model and called with arguments, session attributes via `expose-session-attributes`, the
`_csrf` request attribute). Tessera templates only see typed `t:params` and can only call
`@TemplateFunction`s, so:

- `views/*Functions.java`: static `@TemplateFunctions` bridges with the same names the templates used, so the
  calls stayed readable: `locale.getString(...)` (Localization), `helpers.*` (HelpersService),
  `currencyService.*`, `dateService.*`, `updateService.*`, `programArgs.*`, `entityType.of('ACCOUNT')`
  (static enum access), `views.*` (small computations such as `?capitalize[0]`, `enumName`, `fontColor`),
  `validation.*`, plus one namespace per page group (`accountsViews`, `categoriesViews`, `transactionsViews`,
  `chartsViews`, `settingsViews`). `ViewServices` hands the Spring beans to these static functions.
- `@dev.tessera.runtime.TemplateView` on all entities and on the DTOs/interfaces templates read (`Iconizable`,
  `Notification`, `FilterConfiguration`, `CsvRow`, `HotKey`, `Build`, ...). Getter aliases added where the old
  templates called non-getter methods (`Settings.isShowBackupReminder()`, `isShowWhatsNew()`;
  `TransactionBase.getName()/getDescription()`).
- Session attributes the old views read are now put into the model by their controllers
  (`filterConfiguration`, `accountsFilterConfiguration`, the CSV import state).
- Types templates can't read were replaced in the model by view-safe equivalents: Spring `Page` →
  `search/SearchResultPage` record, `UUID` → `String`, Gson `JsonObject`/JSON strings → `String`/`List`.
- `views/FieldErrorsInterceptor` exposes the controllers' `error` BindingResult as
  `Map<String,String> fieldErrors`; `validation.cssClass/hasError/tooltip` replace the old `validation` macro,
  which wrote attributes into the caller's `<input>` (a fragment can't do that).
- Layout: the old `header`/`navbar`/`scripts` macros became fragments (`helpers/header.html`,
  `helpers/navbar.html`, `helpers/scripts.html`); macros with `<#nested>` became fragments with a trailing
  `Fragment content` parameter. Tessera has no default arguments, so every call passes all of them.
- New endpoint `GET /media/getImageByID/{ID}` (authenticated like everything else): the image picker used
  `data:` URLs built from the image bytes, which Tessera's URL context turns into `about:invalid` and which only
  `TrustedUrl.unchecked` would let through.

## Escaping

The old app ran FreeMarker **without** an HTML output format, so nothing was escaped. Tessera now escapes all
output. That is intended, but some *trusted* strings contain markup: localized messages with `<br>` and
`<a ...>`, and notification messages built in Java with a link. Those are rendered via
`settings/settingsMacros :: localizedHtml(text)`, which splits the string in Java into text / `<br>` / `<a>`
parts (`settingsViews.htmlParts`) and rebuilds the elements with escaped text and URL-checked `href`. That keeps
the pages identical without any unchecked HTML. User data such as names or descriptions containing markup now
comes out escaped, where it used to be injected raw. That fixes a stored-XSS hole and is not a regression.

## Page parity

`pages.txt` (81 requests: logged out/in, wrong password, every list page empty and with data, every "new"
form empty and with validation errors and after success, edit pages, delete modals, error pages 404/400/418,
`/error`, date-picker navigation, hint dismissal, template-group and chart forms with errors, settings container saves) was recorded from the original app with
`tessera-migrate record` (fresh Postgres DB per run) and compared with
`--ignore '(?<=verificationCode">)[A-Z0-9]{4}' --ignore '(?<=verificationCode" value=")[A-Z0-9]{4}'`
(the database-delete verification code is random per request).

**Result: 81 pages, 78 `same`, 0 `reordered`, 3 `differ`.** The 3 are `/settings/database/requestImport`,
`/settings/database/requestDelete` and `/templates/fromTransactionModal`, all because of item 2 below. The
following differences can't be reproduced without raw output. Items 1 and 3 are handled for every value the
application ships with and only show up for other values:

1. **JavaScript literals in inline scripts.** Tessera writes `[[...]]` inside `<script>` as JSON, so
   `x = 'Copied!'` would become `x = "Copied!"`, and hand-built JS arrays/objects would become compact JSON.
   The templates handle this by choosing between literals. For the translations the app ships (English,
   German) of the date-picker strings, `copiedToClipboard`, `localizedSearch` and
   `createTemplateWithErrorInForm`, for the default and empty transaction-name keyword lists, and for empty tag
   lists, they write the old single-quoted literal. The condition compares the actual (Java-computed) value
   with the literal, so a changed translation or a user-edited list falls back to the JSON form. That
   fallback has the same values but different script text.
2. **Junk attribute `text-white"`.** Three buttons in the old templates had a stray `"` in a macro argument,
   which broke the `class` attribute; the browser turned the rest into attributes named `modal-action`,
   `modal-close` and `text-white"`. The first two are reproduced. An attribute name containing `"` can't be
   written in a Tessera template (or any well-formed one).
3. **Inline colours outside the known set.** Tessera rejects expressions in `style`. `helpers/styles.html`
   chooses between literal `style="color: #..."` / `background-color` for every colour the app offers
   (category palette, the theme font colours, the default category colours). Any other colour, picked freely
   by the user, is written as `data-style-color` / `data-style-background-color`, and `main.js`
   (`applyDataStyles`, also called after modal/custom-select content is swapped in) applies it. Such
   elements differ in the DOM, but they look the same.
4. **Numbers ≥ 1000** printed by FreeMarker without `?c` got locale grouping (`1,234`); Tessera prints plain
   numbers. This only shows up for IDs/counts ≥ 1000, and none are in the recorded data.

Markup the old templates left unbalanced (unclosed divs in `transactions`, `templateGroups`, `templates`) is
reproduced as the browser built it, with comments in the templates.

Other behaviour notes:
- Custom chart scripts (user-written JavaScript stored with a chart) were pasted raw into a `<script>`. They
  are now passed as a JSON string and run with indirect `eval`, which keeps the same global-scope semantics.
  It is the same feature (user-authored code) with the same trust model, but it isn't textually identical.
- Settings containers: the old form carried a hand-written CSRF input with `id="token"` that a script reads.
  It is kept, filled from the request's `CsrfToken` via `views.csrfToken()`. To avoid a second, auto-inserted
  token input, that one form writes its method as `t:method="'post'"`. CSRF protection itself is unchanged:
  the token is still sent and checked.
- Transaction POSTs (`/transactions/newTransaction`) answered 405 to `tessera-migrate`'s form-encoded POSTs on
  the *original* app as well, so I couldn't record those states. They are only checked by compiling and by
  the GET form pages.
- The original crashes (`InvalidReferenceException` → truncated response) on `/accounts` when an account has no
  description. The Tessera page renders it.

## Where Tessera or its documentation fell short

- **No way to compute an inline style**, not even from a value the application controls (a colour from a
  colour picker). Every app with user-chosen colours needs a workaround (data attributes + JS, or switching
  between literals). A safe CSS-value context (e.g. colour/length validation) would cover this.
- **`[[...]]` in scripts is JSON only.** There's no way to output a JS string with the same quoting as before,
  so every inline script that embedded values differs textually. The guide's "Expected output differences"
  lists only attribute order, `<td >` and `null` attributes, not this.
- **No dedupe/opt-out for the automatic CSRF input**: a form that needs its own token input (here with an `id`
  a script uses) gets two, unless `method` is computed. A documented attribute (`t:csrf="false"` or reuse of an
  existing `_csrf` input) would be cleaner.
- **Messages:** `-Atessera.messages` supports one bundle and assumes Spring's `MessageSource`. Apps with their
  own localization (here `de.thecodelabs` `Localization`, three bundles) get no key checking. A
  `@TemplateFunction` bridge works, but keys are unchecked.
- **No default fragment arguments.** FreeMarker macros with 5–10 defaulted parameters became calls that spell
  out every argument, which is noisy and error-prone.
- **`~{::name}` with a same-file `t:fragment`** does not see the page's variables (only element selections
  do). The guide's layout example uses `~{::body}`. It doesn't say that a *named fragment* selected with
  `~{::name}` loses the caller's scope, which is the natural way to pass several blocks.
- **Enums print with `toString()`.** Several enums override `toString()`, and the guide doesn't say what
  printing an enum does, nor how to get `name()` (it's not a getter). We added `views.enumName`.
- **Lombok:** the guide says to list Lombok in `annotationProcessorPaths`, but templates still can't resolve
  Lombok-generated getters on some classes (agents had to add explicit getters to
  `AccountsFilterConfiguration`). Processor ordering/visibility isn't documented.
- **`data:` URLs** (images stored in the database) can't be emitted at all without `TrustedUrl.unchecked`.
  A `TrustedUrl.dataImage(mime, bytes)` factory would remove the need for an extra endpoint.
- **Raw-output engines:** the guide treats "now escaped" as always intended. For FreeMarker without an output
  format, the app's own message bundles contained markup, and a small sanctioned way to render a whitelisted
  subset (`<br>`, `<a href>`) would help. We wrote one in Java (`settingsViews.htmlParts`).
- `tessera-migrate --ignore` runs on the raw HTML before parsing. A broad pattern (e.g. `verificationCode[^>]*>`)
  silently corrupts the markup and produces confusing diffs. Lookbehind patterns work.
