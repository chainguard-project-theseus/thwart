# FreeMarker → Tessera migration report (BudgetMaster)

## Result

- Every view is now a Tessera template (`BudgetMasterServer/src/main/resources/templates/**/*.html`, 75 `.ftl` files → 70 `.html` files).
- FreeMarker is gone: all `.ftl` files, `spring-boot-starter-freemarker`, the `spring.freemarker.*` properties and
  `advices/StaticClassesAdvice` (which exposed FreeMarker's `static` hash model).
- The startup warning `Tessera: another template engine is still configured` no longer appears.
- Nothing that `spring-context-support` provides is imported by the app, so losing that transitive
  dependency of the old starter is harmless.
- Build: `mvn package` on JDK 21 (the project's `java.version`).
- Tests:
  - The suite has the same result as the untouched original in this environment: 315 tests, 4 failures,
    42 errors, in both. I checked this by running `mvn test` on a clean checkout of the original commit.
  - All of those fail for environmental reasons:
    - The 42 errors are all Testcontainers' "no Docker environment" (the integration, selenium and
      database tests).
    - The 4 failures are `DatabaseServiceTest`'s log-capture assertions.
  - No test was changed, disabled or deleted.
- Security:
  - No `permitAll` was added, and CSRF is untouched.
  - No escaping bypass exists: there is no unchecked `SafeHtml`; all markup goes through `SafeHtml.sanitize`,
    `SafeInlineHtml` or a policy.
  - Hand-written `_csrf` inputs were removed, because Tessera adds the token to same-origin POST forms itself.

## What changed

### Build (`BudgetMasterServer/pom.xml`)
- Added `tessera-spring-boot-starter` as a dependency.
- `maven-compiler-plugin` lists the annotation processors explicitly: Lombok, hibernate-jpamodelgen, then
  tessera-compiler.
- Compiler arguments: `-Atessera.templates=src/main/resources/templates`,
  `-Atessera.package=de.deadlocker8.budgetmaster.views`.
- Added `tessera-maven-plugin:check`.

### Java glue (`de.deadlocker8.budgetmaster.templating`)
- **What the templates used to reach.** FreeMarker templates called services and static classes directly:
  `helpers.*`, `currencyService.*`, `dateService.*`, `updateService.*`, `static["…Localization"]`,
  `entityType.X` and `programArgs`. These calls are now `@TemplateFunctions`:
  - `locale.getString(key, args…)` wraps the app's own `Localization`. The app doesn't use Spring's
    `MessageSource`, and many keys are computed from data, such as enum localization keys.
  - `helpers.*`: lookups (current account, hints, budgets, …), `entityType('X')`, `categoryName`,
    `initial`, and the font colours.
  - `fmt.*`: currency and date formatting, which depends on the persisted settings.
  - `validation.*`: replaces `helpers/validation.ftl`.
  - `notice.message`: sanitizes notification messages.
  - Per-group helpers in `AccFunctions`, `TxFunctions`, `NewTxFunctions`, `SetFunctions` and
    `MiscFunctions`: enum names, `?abs`, grouping and chunking that the old templates did with
    unbalanced markup, inline HTML messages with `<br>`, and similar.
- **`TemplateBeans` (deviation from the guide).** The functions reach the existing Spring services
  through `TemplateBeans`, a static `ApplicationContext` holder. The guide advises against this. I did it
  anyway because the 70 templates make hundreds of calls with arguments into those services; turning all
  of them into precomputed view models was not feasible in the time available. Those services only read
  data and settings.
- **`ValidationErrorsInterceptor`.** The old views read the `error` `BindingResult`, which isn't
  view-safe. The interceptor adds `validationErrors` (field → error code) to the model of every rendered
  view.
- **Session values moved to the model.** FreeMarker exposed session attributes
  (`expose-session-attributes=true`), and the new pages need the same values:
  - `NewTxSessionModelConfiguration` copies `currentCsvTransaction` into the model.
  - These controllers now add values to the model:
    - Transaction import: `csvRows`, `csvTransactions`, `errorsColumnSettings`, the file name and the
      name suggestions as a list.
    - Accounts: the filter configuration.
    - Transactions: the filter configuration.
    - Reports: the filter configuration.
  - `SearchController` adds a `SearchPage` record, because Spring's `Page` isn't view-safe.
  - `SettingsController` passes `toastContent` as a `String`.
  - `TemplateController` passes the existing template names as a list.
  - `ChartController`'s `containerID` is now a `String`.
- **View types.** `@TemplateView` was added to the entities and view classes the templates read (Account,
  Category, Transaction, Template, Settings, Chart, Icon, …) and to the `HotKey` interface.
- **`ChartController` endpoint `/charts/{ID}/script/{containerID}.js`.** The old page printed the
  user-defined chart script into an inline `<script>`, splicing the data in with `?replace`. Tessera
  can't write code into a script. The page now declares the data (`chartTransactionData`) and loads the
  script from this same-origin endpoint. The container id is validated as a UUID.
- **`MediaController` endpoint `/media/getImageByID/{ID}`.** The available-images list used base64
  `data:` URLs, which can't be computed in Tessera.

### JavaScript (`static/js`)
- **Inline event handlers moved to listeners.** Tessera rejects `on*` attributes, so every
  `onclick`/`onsubmit`/`oninput`/… is now attached from JS:
  - login: `main.js`
  - transaction save buttons: `transactions.js`
  - reports form: `reports.js`
  - settings containers: their inline scripts
  - icon search: `iconSelect.js`
- **Tooltips.** `initTooltips()` in `main.js` turns a `title` on `.tooltipped` elements into the Materialize
  tooltip, escaping the text first because the plugin's only text option is HTML. It replaces
  `$('.tooltipped').tooltip()` and the calls in `settingsContainers.js` and `fetchAndShowModal`.
- **Hint and notification close links** now carry the numeric id; `main.js` adds the `hint-` or
  `notification-` prefix.
- **Other values the scripts read now come from JSON:**
  - `import.js` reads the help texts from a JSON block.
  - `iconSelect.js` reads the focus target from a declared variable.

## Page parity

- **How it was checked.**
  - Ran `tessera-migrate record` against the original jar and `compare` against the migrated one.
  - Both apps started from the same seeded Postgres dump (demo data).
  - The list has 57 requests, in `.tessera/pages.txt`, which is untracked. It covers:
    - login, including the failed-login state
    - every menu page
    - the new/edit forms, including validation-error POSTs and a successful save with its notification
    - the delete and other modals fetched by JS
    - search with and without results
    - settings, including its delete and import modals
    - the 404 and 418 pages and the not-found transaction
  - The agents also compared their own extra states: filters, repeating transactions, transfers, settings
    POSTs, the import flow driven by curl, and the error pages.
- **Outcome.** 17 pages are `same`. Every remaining `differs` comes from one of the causes below, and none
  is missing or wrong content. Status codes and final URLs match on every page.

1. **Values in scripts** (guide 4.4). A value may enter a script only as a whole declaration. So old
   assignments like `x = '${…}'` became `var x = [[…]];` (JSON, double quotes, no trailing commas), and
   code that used a value inside a string was split into a declaration plus code, e.g.
   `var currentDate = "…"; document.cookie = "currentDate=" + currentDate;`. This affects most form pages.
   - Exception: `accountPlaceholderName`, which every page wrote but no script reads, is static text
     chosen per language (en/de) so the shared layout stays identical. This is a deliberate exception to
     "no message hard-coded per locale", because the value is dead.
2. **Text in `data-*`** (guide 4.4).
   - `data-tooltip` texts moved to `title`.
   - `data-id="hint-N"` and `data-id="notification-N"` became the number.
   - `data-focus-on-close`, `data-title`/`data-text` (import help) and the transaction import table's
     `data-order`/`data-search` became declarations or JSON blocks, or were dropped. DataTables now sorts
     and searches the cell text.
3. **Inline handlers removed** (`onclick`, `onsubmit`, `on*` on `#searchIcons`), as described above.
4. **CSRF inputs.** The auto-inserted token input has no `id="token"`. No script reads that id.
5. **Sanitized markup.**
   - Notification messages contain Java-built links next to user-supplied names; FreeMarker printed them
     raw, which made them injectable. They now go through a sanitizer. The link and its class survive,
     but Tessera adds `rel="nofollow noopener"`.
   - Bundle messages with `<br>` or links (418 credits, chart wiki, settings reload, first-use steps,
     delete confirmations) are sanitized inline HTML.
6. **Broken old markup.**
   - Some buttons passed `'"modal-action …'` as their class, with a stray quote. That produced junk
     attributes `modal-action="" modal-close="" text-white"=""`, and those buttons now get their intended
     class.
   - Duplicate `class` attributes and an unclosed `<div>` on the transactions and template-group pages:
     the new markup is what a browser builds. The compare tool's parser nests the trailing scripts
     differently there.
7. **Integers above 999.** FreeMarker formatted them with locale grouping; most places now print them
   plain, e.g. entity counts on the import result page. The hidden transaction amount keeps the old
   grouping (`numbers.formatInteger(…, 'DEFAULT')`).
8. **Verification code.** `/settings/database/requestDelete` shows a random code, which differs between
   any two runs.

## Not converted or not verified

- **Multipart pages.** `tessera-migrate` can only send url-encoded requests, so the states behind a
  multipart upload weren't compared by the tool:
  - The CSV import column-settings and transaction-list states were converted but not compared.
  - The database import step 1 and result pages were checked by hand with curl; the DOM is the same apart
    from attribute order and the recipe differences.
- **Backup settings POST.** It returns 405 in both apps, because of an existing bug: `@RequestParam`
  sits between `@ModelAttribute` and `BindingResult`.
- **CSP.** The Tessera starter now sends a CSP header (`script-src 'nonce-…' 'strict-dynamic'`). All
  scripts get nonces, and inline handlers are gone. Client libraries (Plotly, CodeMirror, Materialize)
  were not exercised in a real browser here.

## Where Tessera or its documentation fell short

- **Static bridge to services.** No supported way exists for a function to use a Spring bean. An app
  whose templates call services with arguments everywhere needs either a full view-model rewrite or a
  static bridge (used here, against the guide's advice).
- **`#{}` needs Spring's `MessageSource`.** Apps with their own localization library get no checked
  message support, and computed keys always need a function.
- **Neutral `data-*` is very strict.** Every tooltip, prefixed id and DataTables `data-order` needs JS
  changes, and each change makes `compare` report `differs` on otherwise identical pages. The same goes
  for scripts: `var` declarations make nearly every page with an inline value differ.
- **Fragment syntax pitfalls.**
  - `t:replace` runs before `t:each`/`t:if` on the same element. This is documented, but easy to trip
    over, and every loop over a fragment call needs an extra `<t:block>`.
  - A function namespace that shares its name with a loop variable (`notification`) gives a misleading
    "method call not allowed" error.
- **Enums.** Printing an enum uses `toString()`, not `name()`, so enums with a custom `toString` print
  junk. The guide says only "printing an enum" without saying which method is used.
- **Getters and URLs.** Getters not named `get`/`is` (`hasFontAwesomeIcon()`, `needToShow…()`) and
  methods with arguments need functions. `data:` URLs have no template-side route.
- **Sanitizer `rel`.** `HtmlPolicy` always adds `rel="noopener"` (plus `nofollow` by default), and
  `linkRel()` can't remove it.
- **`tessera-migrate` limitations.**
  - It can't send multipart requests.
  - `--ignore` doesn't seem to apply to normalized diff lines, according to one agent.
  - Its parser doesn't close elements left open at `</main>` the way browsers do.
  - It has no FreeMarker converter; only Thymeleaf has one.
- **Duplicate static attributes.** The guide doesn't mention that duplicate static attributes in the old
  markup have to be collapsed by hand.
