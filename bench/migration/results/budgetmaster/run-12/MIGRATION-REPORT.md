# Migration report: FreeMarker → Tessera

BudgetMaster's server-rendered views were FreeMarker (`.ftl`, 75 files, ~6,500 lines, no auto-escaping).
Every view is now a Tessera template. The FreeMarker starter, its `spring.freemarker.*` configuration,
`StaticClassesAdvice` (exposed `static[...]` to templates) and every `.ftl` file are gone. The startup log says
`Found 76 compiled Tessera templates`, and the "another template engine is still configured" warning does not appear.

## What changed

### Build (`BudgetMasterServer/pom.xml`)
- `spring-boot-starter-freemarker` was replaced by `dev.tessera:tessera-spring-boot-starter:0.1.0-SNAPSHOT`.
- `maven-compiler-plugin` now lists its annotation processors: Lombok, then `hibernate-jpamodelgen` (which the build already used through discovery), then `tessera-compiler`. It passes `-Atessera.templates=…/templates` and `-Atessera.package=de.deadlocker8.budgetmaster.views`.
- `tessera-maven-plugin:check` was added, so an edit to a template alone still triggers recompilation.
- `-Atessera.messages` is not set. The app's texts come from `de.thecodelabs` `Localization` bundles (`languages/base_*.properties`), not from a Spring `MessageSource` (see "Shortfalls").
- `mvn dependency:list` contains no FreeMarker or `spring-context-support`. Nothing in the app used either.

### Java
- New package `templating/`:
  - **`LocalizationFunctions`** (`loc.*`) replaces `locale.getString(...)`. `loc.get(key, args…)` returns text. `loc.html(...)` returns `SafeInlineHtml`, for bundle texts containing `<br>`/`<b>`; arguments are escaped. `loc.block(...)` returns `SafeHtml`, for bundle texts with links.
  - **`BudgetMasterFunctions`** (`bm.*`) replaces the old template calls into `helpers`, `dateService`, `currencyService` and `updateService`, and the static `EntityType`/`ProgramArgs`: currency and date formatting, hints, the current account and its budget, and so on.
  - **`ViewServices`** gives those static functions access to the Spring services (explained under "Shortfalls").
  - **`ValidationErrorsInterceptor`** turns the `BindingResult` that controllers put in the model as `error` into `validationErrors` (a map from field to localized message), because templates cannot read a `BindingResult`.
  - **Area function classes:** `AccountViewFunctions` (`acc`), `TransactionListViewFunctions` (`tl`), `ChartViewFunctions` (`ch`), `TransactionFormViewFunctions` (`tf`), `TransactionImportViewFunctions` (`ti`), `SettingsViewFunctions` (`st`).
- `@TemplateView` was added to all 25 `@Entity` classes, plus `Notification`, `Build`, `AccountsFilterConfiguration`, `FilterConfiguration`, `FilterObject`, `Search`, `ChartSettings`, `ImportResultItem`, `StatisticItem`, `HotKey`, `CsvRow` and `CsvTransaction`.
- New `search/SearchResultPage` record, because Spring's `Page` is not view-safe.
- **Controllers** now put into the model what the templates used to read from the session or request:
  - `filterConfiguration` (transactions, reports) and `accountsFilterConfiguration`;
  - `csvImportInProgress`, plus the CSV-import session data on `/transactionImport`;
  - `numberOfEntitiesByType` on import step 1;
  - `toastContent` as a `String`.

  Behaviour, status codes and redirects are unchanged.
- **New endpoint `GET /media/getImageByID/{ID}`** (authenticated like every other `/media` URL). The image picker used to inline uploaded images as `data:` URLs built from data, which Tessera's URL context rejects.

### Templates
- Every `.ftl` became an `.html` at the same view name.
- Macros became typed fragments. `<#nested>` became `Fragment` parameters. The `header.body`/`header.content` wrapper macros became the shared fragments in `helpers/header.html`, `helpers/navbar.html`, `helpers/scripts.html`, `helpers/customSelectMacros.html`, `helpers/globalDatePicker.html` and `helpers/validation.html`.
- **Split `customSelectStart`/`customSelectEnd` macros** (open in one macro, close in another) became single fragments.
- **Unbalanced loops** (date groups opened and closed by counters, datepicker table rows, colour-grid rows) became balanced loops over values grouped in Java.

### JavaScript
These changes support the recipes below:
- `main.js`: notification and hint ids now carry a number, and the JS adds the prefix.
- New `validation.js` and `titleTooltips.js`.
- `accounts.js`, `settingsContainers.js`, `import.js`, `transactionImport.js`.

## How it was checked
- **Page list:** `.tessera/pages.txt` has 92 requests:
  - login (wrong and right password), the home page;
  - every list, new, edit and delete page;
  - forms posted empty (validation errors) and posted successfully, including a category named `<b>Food</b>`;
  - modals, search with and without results;
  - error pages: 404, `/error`, a 405 POST.
- **Snapshot approach:** the demo data uses `SecureRandom`, so the original was recorded in two parts. `.tessera/pages1.txt` runs against an empty database up to `createDemoData`. The database is then dumped. `.tessera/pages2.txt` runs against that dump. The new app is compared the same way, restored from the same dump.
- **Result:** with `--ignore` for only the `var ` before `accountPlaceholderName` and the `hint-`/`notification-` prefix of `data-id` (both on every page), 36 of 94 pages are `same` or `reordered` (32 same, 4 reordered) and 58 still `differ`. Without the masks the first one alone makes every page `differs`, since it appears on all of them. Each remaining difference falls into one of the categories below; none is missing data or a missing element:
  1. **Inline script values (every page).** Tessera only allows `[[...]]` as the whole value of a declaration at the top of a script. So `accountPlaceholderName = "All Accounts";` became `var accountPlaceholderName = "All Accounts";`. The same applies to every page global (validation messages, datepicker month and week names, `startDate`, the `currentDate` cookie script, name suggestions, …). JSON output also changes `'x'` to `"x"` and drops trailing commas. The JavaScript values are the same. There is no way to keep the old text.
  2. **Materialize `data-tooltip` from data or bundle text.** Computed strings are not allowed in non-standard attributes (guide 4.4).
     - Help or star tooltips: the text moved to `aria-label` or `title`, and a script passes it, HTML-escaped, to `M.Tooltip.init`.
     - Validation tooltips: each field carries `data-validation-field="<literal field name>"`, and the messages are in a `<script type="application/json" id="validationMessages">` block read by `validation.js`.
     - Materialize treated the old tooltip text as HTML, so this also closes an injection path.
  3. **Escaping.** FreeMarker printed everything raw. A category named `<b>Food</b>` used to render bold, and now renders as the text `<b>Food</b>`. That is the intended fix.
     - Notification messages are markup built in Java (a link to the saved item), so they go through a policy allowing `a, b, i, br, span` and the `text-default` class.
     - Bundle messages with links go through `loc.block`, whose sanitizer adds nothing visible except `rel` handling.
  4. **Broken markup the old engine passed through.** These produced odd DOM trees that the new, well-formed templates don't reproduce:
     - an unclosed `transaction-date-group` div on `/transactions`, which nested the filter modal and scripts two levels deeper;
     - an unclosed div on `/templateGroups`;
     - duplicate `class` attributes (browsers kept the first; that first value is kept);
     - stray `</p>` and stray quote characters in class arguments.
  5. **`data-id` of hints and notifications** is now the bare number, because `"hint-" + id` is a computed string. `main.js` adds the prefix.
  6. **CSRF.** Tessera writes the token input itself. The old settings forms gave it `id="token"`; the one script that used that id now selects by `name`. Compare masks the value.
  7. **Data, not templates:**
     - Search results with equal sort keys come back from Postgres in a different order after the dump/restore.
     - Two recorded pages (`POST /templates/newTemplate`) and two account pages were cut off by FreeMarker errors in the original (`InvalidReferenceException` on a null account description). Tessera renders them completely, so status and final URL differ from the empty recording (recorded status 0).
- **Tests.** `mvn package` on JDK 21 (the target) gives the same result as the original commit on this machine: 315 tests run, 4 failures and 42 errors in both.
  - The 42 errors are integration and Selenium tests that need Docker/Testcontainers, which this machine doesn't have.
  - The 4 failures are `DatabaseServiceTest.test_determineFilesToDelete_*`, which fails identically on the untouched original.
  - No test was changed, deleted or disabled.
  - The Selenium tests could not run here. Some of them may look for `data-tooltip` on validated fields, which no longer exists (difference 2).
- **Security:** no security configuration was changed (no new `permitAll`, CSRF still on), and no raw output of unsanitized data was added. Every `t:utext` takes a `SafeHtml`/`SafeInlineHtml` from a sanitizing function.

## What could not be converted 1:1
- **Pages with these constructs can't pass `compare` as `same`**, even though nothing is lost: inline script globals assigned as statements, `data-tooltip` with text, and `data-id` with a string prefix (all above).
- **Custom chart scripts.** The user-defined chart JavaScript stored in the database used to be pasted raw into a `<script>`. It now arrives as JSON string declarations and runs through `(0, eval)(...)` with the same placeholder substitution. The feature still works, but it is still "run user-written JS from the DB". Tessera has no sanctioned form for that; it is an existing app design decision.
- **`/settings/database/requestExport` and `/settings/updateSearch`** are not HTML views (a JSON download and `@ResponseBody`), so they need no template.

## Where Tessera or its documentation fell short
- **Services from templates.** Functions are static and "must not reach Spring beans through static state", but the app's templates were built around service calls with arguments (`currencyService.getCurrencyString(amount)` per transaction, dates, hints, current-account budget). Moving all of that into controllers was not feasible in the time available. `templating/ViewServices` holds the four services statically for the `bm.*` functions. A supported way for a `@TemplateFunctions` class to get Spring beans (or request-scoped helpers) would remove this workaround.
- **Messages from a non-Spring bundle.** `#{...}` only works with Spring's `MessageSource`; this app uses its own `Localization` library, with the language taken from the app's settings rather than the request locale. All 439 lookups go through the `loc.*` functions, so keys are not checked at build time, and bundle HTML needs its own sanitizer policies.
- **No script form that keeps old code.** `name = "${x}";` cannot be kept, and neither can a value inside an expression or string concatenation. Every inline-script page therefore differs in `compare`, and `compare` has no option to treat "same JS value, declaration instead of assignment" as equal.
- **Common plugins have no recipe.** Materialize's tooltip (`data-tooltip`, whose only option is HTML) and DataTables' `data-order`/`data-search` both needed hand-written init scripts.
- **No way to pass a field name through a fragment into a neutral attribute.** Literal choices had to be enumerated.
- **Enums in neutral attributes.** Enum values are rejected in `data-*`, so an enum has to be mapped to a chain of literal names, which must be updated by hand when a constant is added.
- **No `data:` image sources from data.** A server endpoint was needed.
- **Fragment parameters.** Interface types (e.g. `LocalizedEnum`) can't be fragment parameters, and there are no defaults, so macros with many defaulted arguments became long positional calls.
- **No `<!DOCTYPE>` guidance for layouts.** A layout fragment's `<!DOCTYPE>` is emitted, which changes the page when the old pages had none.
- **Empty tables.** An empty `<tbody>` is written for an empty loop where the old markup let the browser omit it. The guide's advice to always write `<tbody>` produces a `compare` difference on empty tables unless the `<tbody>` is made conditional.
