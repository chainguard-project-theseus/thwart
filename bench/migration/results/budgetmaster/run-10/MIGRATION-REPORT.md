# BudgetMaster: FreeMarker → Tessera migration report

## Result in brief

- Tessera now renders all 74 views. FreeMarker is gone: its 75 `.ftl` templates, the
  `spring-boot-starter-freemarker` dependency, the `spring.freemarker.*` properties and `StaticClassesAdvice`
  have all been removed. `mvn dependency:list` lists no FreeMarker artifact, and the app starts with
  `Found 74 compiled Tessera templates` and without the "another template engine is still configured" warning.
- **Build and tests.** `mvn verify` gives the same result as the unmodified app in this environment: 315 tests, with
  the same 46 failing before and after (4 failures, 42 errors). Every one of those fails because of the environment,
  not the migration:
  - 42 need Docker through Testcontainers: all Selenium tests, the database unit tests and the importer tests. This
    machine has no `/var/run/docker.sock`.
  - The 4 `DatabaseServiceTest` failures also fail on the original source.

  I compared the list of failing test classes before and after the migration and it is identical. No test was
  changed, disabled or deleted.
- **Page parity.** The original app was recorded with `tessera-migrate record` against a fresh PostgreSQL
  database. The request list is `pages.txt`, 78 requests: login (success, failure, logout), every list page, every
  form empty, with validation errors and after success, the delete modals, the error pages 400/404/418, and so on.
  - The latest `compare` gives 44 `same` and 34 `differs`.
  - Every request has the recorded status and final URL.
  - The 34 differences come from the categories listed under "Remaining page differences" below. Each follows from a
    Tessera security rule, or from old broken markup that is now fixed.
  - **So `compare` does not exit 0.**
- **Security.**
  - There is no `SafeHtml.unchecked`, `TrustedUrl.unchecked` or `Fragment.unverifiedForGeneratedCode`
    (`target/tessera-review.txt` reports 0 unchecked calls).
  - `WebSecurityConfig` is unchanged: no new `permitAll`, and CSRF is still on.
  - No test was disabled.
  - FreeMarker printed everything raw. All values are now contextually escaped.

## What changed

### Build (`BudgetMasterServer/pom.xml`)
- Added the `dev.tessera:tessera-spring-boot-starter` dependency and removed `spring-boot-starter-freemarker`.
- `maven-compiler-plugin` now has `annotationProcessorPaths` for Lombok, `hibernate-jpamodelgen` and
  `tessera-compiler`. Discovery is off, so all processors the build used are listed.
- Compiler arguments: `-Atessera.templates`, `-Atessera.package=de.deadlocker8.budgetmaster.views`, and
  `-Atessera.messages`, which points at `languages/base_en`, `news_en` and `hints_en` so every `#{...}` key is
  checked at build time.
- Added `tessera-maven-plugin` with the `check` and `review` goals.
- The removed starter brought in only FreeMarker itself and `spring-context-support`. The app uses neither (I
  checked for Quartz, mail, cache and freemarker imports), so nothing had to be declared in their place.

### Infrastructure (`src/main/java/de/deadlocker8/budgetmaster/viewsupport/`)
FreeMarker templates read the `helpers`, `dateService`, `currencyService` and `updateService` service beans,
`static[...]` classes and `settings` from the model, and macros saw these globals too. Tessera fragments see only
their parameters, so these values became function namespaces:
- **`LocaleFunctions` (`locale.getString(key, args…)`)** delegates to the app's `Localization`. It also has
  `notificationHtml`, which sanitizes the notification messages the controllers build in Java (they contain an
  `<a class="text-default">` edit link). It uses an `HtmlPolicy` that allows that class and adds no `rel`. Because
  the message is sanitized, a name containing markup is now harmless; before, `NotificationLinkBuilder` inserted it
  raw.
- **`HelpersFunctions` (`helpers.*`)** wraps every `HelpersService` method the views used, plus:
  - `getSettings`, `entityType(name)`, `isDebug`/`isTest`
  - the header globals `greenTextColor`/`redTextColor`
  - `isUpdateAvailable`/`getAvailableVersionString`
  - `needToShowWhatsNew`, `needToShowBackupReminder`
  - `fontColor(...)`, `accountName`, `categoryName`
- **`FormatFunctions` (`fmt.*`)** wraps the `DateService`/`CurrencyService` formatting and replaces the FreeMarker
  built-ins (`?c`, the default number format, `?capitalize`, `text[0]`, `?has_content`).
- **Per-area classes written during the conversion** (`itemViews`, `accountViews`, `chartViews`, `templateViews`,
  `transactionViews`, `settingsViews`) hold small calculations that the old templates did inline.
- **`ViewServices`** holds static references to the four services so the functions can reach them. See the
  shortcomings section.
- **Messages.** `MessagesConfiguration` registers a Spring `MessageSource` over the app's `languages/*` bundles. Boot
  doesn't auto-configure one, because there is no base file without a locale suffix. `SettingsLocaleResolver`
  renders with the language chosen in the settings, so `#{key}` matches `Localization`. Bundle messages that contain
  markup (`<br>`, links) are written with `t:utext="#{key(args)}"`, which the build checks.
- **Validation.** `FieldErrorsInterceptor`, registered in `WebMvcConfiguration`, turns the `error` `BindingResult` the
  controllers put in the model into `Map<String,String> fieldErrors` (field → localized message). Templates can't read
  a `BindingResult`.
- **Removed advices.** `StaticClassesAdvice` (FreeMarker's `BeansWrapper` static models) and `ServiceAdvice` (it put
  the service beans in the model) are deleted.

### Domain classes and controllers
- **`@TemplateView`** added to the entities and other classes the views read: `Account`, `Category`, `Transaction`,
  `TransactionBase`, `Template`, `Settings`, `Hint`, `Icon`, `Iconizable`, `ProvidesID`, `Notification`, `Build`,
  the filter, CSV-import, statistics, hotkey and import-result types, and others.
- **`TransactionBase`** gained `getName()` and `getDescription()`; both implementations already had them.
- **Values FreeMarker read from the session** now go into the model:
  - the filter configurations (accounts, transactions, reports)
  - the CSV-import rows and errors
- **`SearchController`** passes a `SearchResultPage` record, because Spring's `Page` is not view-safe.
- **`ChartController`:**
  - `containerID` is now a `String`.
  - The new `GET /charts/{ID}/script/{containerID}` serves a user chart's script with the two substitutions the
    template used to make by string replacement. The ID is validated as a UUID, and the script is no longer inlined.
- **`MediaController`:** the new `GET /media/getImageByID/{ID}` serves the image bytes. Tessera rejects the old
  `data:` URLs in `img src`.
- **`SettingsController`** passes `toastContent` as a `String`.

### Templates
- Each `x.ftl` became `x.html` with the same view name. `helpers/validation.ftl` is written inline at each input, as
  described in the next section.
- `helpers/header.html` holds the `page(title, head, body)` layout and the shared fragments. Pages call it with
  `t:replace="~{helpers/header :: page(...)}"`.
- Each macro became a typed fragment. Fragments have no default arguments, so every caller passes every value.
- Unbalanced macro pairs became single fragments with `Fragment` parameters. This affected
  `customSelectStart`/`customSelectEnd`, and loops that opened and closed `<div>`/`<tr>` elements conditionally.

### JavaScript
- **`main.js`:** before the Materialize tooltips start, an HTML-escaped `title` is copied into `data-tooltip`. This
  keeps the tooltips on validation errors and on account and transfer icons.
- **`transactionImport.js`:** DataTables now reads its sort and search values from a JSON block.
- **`settingsContainers.js`:** reads the toast JSON with `textContent`. Escaped text breaks `JSON.parse(innerHTML)`.
- **`import.js`:** the help modal reads its title and text from the page instead of from `data-title`/`data-text`.

## Remaining page differences (34 pages) and why

1. **Inline script values.** This is the biggest category: login, the new/edit forms, transactions, settings, charts,
   reports and the import page.
   - Tessera allows a computed value in a `<script>` only as `var x = [[...]];` at the top of the script, and writes
     it as `var x = ("value");`. So `startDate = "01.11.2026".split(".")` and similar lines now read
     `var startDate = ("01.11.2026"); startDate = startDate.split(".")`.
   - The guide calls this difference expected; the JavaScript values are the same.
   - Where a value is a localized constant, I avoided the difference by writing one literal `<script>` per language.
     This covers the account placeholder and the date-picker month and day names.
2. **Validation messages** (forms with errors): the message moved from `data-tooltip` to `title`. A computed String
   isn't allowed in `data-*`, and Materialize inserts `data-tooltip` as HTML. The tooltip still works through the
   `main.js` change above.
3. **Free text in `data-*`.** `data-order`/`data-search` in the CSV import table became a JSON block, and the
   import-help `data-title`/`data-text` moved into the page.
   - Constant texts kept identical output as a choice between their German and English literals: the settings help
     tooltips and "Set as default account".
   - Hint and notification ids (`hint-6`, `notification-0`) kept identical output as a choice between literals over
     the fixed id range.
4. **User data with quotes.** The old pages broke their own markup here (`value="Food &amp;" drinks""=""`). Tessera
   writes the whole value escaped. This is the intended fix for an injection point, so the difference stays.
5. **Old broken markup that can't be written.**
   - A stray quote in `classes='"modal-action …'` made the browser apply none of those classes, and the attributes it
     produced can't be written in Tessera. This affects the create-template and database-import buttons.
   - On `/transactions`, the old page left a `<div>` unclosed, so the parser the compare tool uses puts the closing
     scripts at a different depth.
   - On `/templateGroups` I reproduced the old nesting.

## Shortcomings of Tessera and its documentation found during the port

- **No FreeMarker support in `tessera-migrate`.** The tool rewrites only Thymeleaf, and guide section 4.10 has no
  FreeMarker part. Everything was converted by hand, about 6,500 lines.
- **Strings in `data-*` make page parity impossible.** A computed String in `data-*` is a build error, and both
  alternative recipes (a JSON block, or configuring the plugin in JS) change the DOM. So `compare` can't come out
  `same` for pages whose old markup had such values, and the guide offers no exactly-equivalent form.
  - Materialize tooltips have no plain-text option, and re-initialising re-reads `data-tooltip`. The guide's recipe 5
    therefore doesn't hold, and the practical fix (copying escaped text into `data-tooltip` from script) is one the
    guide discourages.
- **The script prelude rule changes every script that held a value.** Each such script comes out textually different
  (`var x = (…)`), so `compare` reports `differs` for something the guide calls expected. There is no option to
  compare JavaScript values instead of text, and no full-diff option (long diffs are truncated).
- **`SafeHtml` inside `<p>`.** `RAW_CLOSES_ELEMENT` rejects a `SafeHtml` from a policy of the app's own inside a
  `<p>`, even when the policy allows only `<br>`/`<a>`. Only build-checked bundle messages fit there, which forced the
  extra `MessageSource` and locale-resolver setup.
- **Head content.** A `Fragment` value was refused in `<head>` (`RAW_NOT_FLOW`) when it was passed as a `<head>`
  selection.
- **`t:with` and conditionals with `t:replace`.**
  - `t:with` on the element carrying a layout `t:replace` is not visible to the replaced content.
  - `t:with` entries can't see earlier entries in the same attribute.
  - `t:if` on the same element as `t:replace` compiles but doesn't guard anything. The guide mentions the attribute
    order, but it's easy to miss and the compiler doesn't warn.
- **Lost `SafeHtml` type.** A conditional choosing between two `#{...}` messages loses the `SafeHtml` type, so
  `t:utext` rejects it, although the guide shows `a ? #{x} : #{y}`.
- **No default fragment arguments.** Every call to a macro with many defaults, like `buttonLink`, must spell out all
  of them.
- **Fragments can't share an element.** One fragment can't open an element that another closes, so macro start/end
  pairs had to be restructured.
- **Escaped JSON breaks `innerHTML` reads.** Wrapping `{`/`}` in comments breaks scripts that read escaped JSON
  through `innerHTML`; the guide doesn't mention this. It affected the settings toast.
- **Not view-safe and not covered.** `UUID` and Spring's `Page` are not view-safe. There is no safe way to write a
  `data:` image URL into `img src`: `allowDataImages` works only through `sanitize`, which drops `data-*` and SVG.
- **No guidance on application-wide services.** The guide says a `@TemplateFunction` must not reach Spring beans or
  request state through static state, but has no recipe for application-wide services that every fragment used
  (current account, settings, formatting by the user's language).
  - Passing them as parameters through every fragment of a ~75-template app was not practical, so `ViewServices`
    keeps static references. This is a deliberate deviation.
  - Likewise, `settingsViews.csrfToken()` reads the request's CSRF token through `RequestContextHolder`, because
    `settingsBackup.js` reads the token input by id.
- **Undocumented behaviour.** The guide doesn't say that a page whose root is `<t:block t:params>` works, or that an
  interface can be `@TemplateView`. Both work.
- **Running user-authored JavaScript.** The guide has nothing on pages whose job is to run user-written JavaScript
  (custom charts), so the script endpoint above was invented for this port.

## Working files
- `pages.txt` is the request list. Scripts, the baseline recording (`baseline/`) and the per-agent copies are under
  `.tessera/` (git-ignored) and `baseline/`.
- The app was run against a local PostgreSQL with `--customFolder`, the same way for the original and the migrated
  build.
