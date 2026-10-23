# Migration report: FreeMarker → Tessera (BudgetMaster 2.18.2)

## What changed

### Build (`BudgetMasterServer/pom.xml`)
- `spring-boot-starter-freemarker` was replaced by `dev.tessera:tessera-spring-boot-starter:0.1.0-SNAPSHOT`
  (Boot 3.5 / jakarta).
- `maven-compiler-plugin` now has `annotationProcessorPaths` with `tessera-compiler`, plus **Lombok and
  hibernate-jpamodelgen**. Both used to be found by processor discovery, which `annotationProcessorPaths` turns off.
  It also gets `-Atessera.templates=…/src/main/resources/templates`, `-Atessera.package=de.deadlocker8.budgetmaster.templates.generated`
  and `<proc>none</proc>` for `default-testCompile` (guide §2, #118).
- `application.properties`: removed the `spring.freemarker.*` keys.
- Removed `advices/StaticClassesAdvice.java`. It exposed FreeMarker's `BeansWrapper.getStaticModels()` as the
  `static` model attribute, so templates could reach any static member of any class.

### Replacing what templates used to reach (`de.deadlocker8.budgetmaster.tessera`)
The FreeMarker templates called Spring beans directly from the model, with arguments
(`helpers.getHintByLocalizationKey("…")`, `dateService.getLongDateString(d)`, `currencyService.getCurrencyString(x)`).
They also reached static classes through `static[...]` (`Localization`, `ProgramArgs`, `EntityType`). Tessera
templates can only read getters, so each of these became a `@TemplateFunctions` namespace with the **same name
and method names**. That kept most call sites unchanged:

| namespace | class | delegates to |
|---|---|---|
| `locale` | `LocaleFunctions` | `Localization.getString(key, args…)` (fixed overloads for 0–4 args) |
| `helpers` | `HelpersFunctions` | the `HelpersService` bean, plus `greenTextColor()`/`redTextColor()` (the old `header.globals` globals) |
| `dateService`, `currencyService`, `updateService` | `*Functions` | the beans |
| `programArgs` | `ProgramArgsFunctions` | `ProgramArgs` statics |
| `entityType` | `EntityTypeFunctions` | `EntityType.X` becomes `entityType.X()` |
| `fm` | `FreemarkerBuiltinFunctions` | `?capitalize[0]`, `?js_string` |
| `validation` | `ValidationFunctions` | replaces `helpers/validation.ftl` |

The static functions get beans through `TemplateBeans`, an `ApplicationContextAware` holder. Tessera functions
must be static and cannot be injected.

**Form errors.** Controllers put the `BindingResult` into the model as `error`, and templates read
`error.hasFieldErrors(..)`/`getFieldError(..).getCode()`. A `BindingResult` is not view-safe, and these forms use
hand-written `name=` inputs, not `t:field`. So `FormErrorsInterceptor` (`postHandle`) adds a view-safe
`FormErrors` (field name → first error code) as the model attribute `formErrors`, and the `validation.*`
functions rebuild the old `class`/`data-tooltip` attributes from it.

**View types.** Every `@Entity`/`@MappedSuperclass`, `Notification`, `Build` and the `Iconizable` interface got
`@TemplateView`. The conversion agents added it to other model classes where templates needed it (see git diff).

### Templates
Every `.ftl` became a `.html` with the same view name. Macros became typed fragments; `<#nested>` became a
`Fragment` parameter. FreeMarker default arguments don't exist in Tessera, so every call site passes all
arguments. Shared fragments live in `helpers/header.html` (`header`, `logo`, `modalConfirmDelete`, `content`,
`hint`, `buttonLink`, `buttonSubmit`, `buttonFlat`, `entityIcon`) and the other `helpers/*.html`.
The hand-written `_csrf` hidden inputs were removed, because Tessera adds the token to every
`<form method="post">`.

### Parity tooling
`parity/pages.txt` is the request list. `parity/baseline/` was recorded from the original jar against a fresh
Postgres database. `parity/compare.sh` builds the migrated app in a scratch copy, starts it on :9001 against a
fresh database and runs `tessera-migrate compare`.

### Other changes needed to make pages render the same
- **Values the old templates got from the session** (`spring.freemarker.expose-session-attributes=true`) are now put
  in the model by the controllers:
  - `AccountController` adds `accountsFilterConfiguration`.
  - `TransactionController.prepareModelTransactions` and `ReportController` add `filterConfiguration`.
  - The `TransactionImportController` GET handler copies `csvImport`, `csvRows`, `csvTransactions` and
    `errorsColumnSettings` from the session.
- **Model values that are not view-safe**, changed to view-safe ones:
  - `ChartController` puts `containerID` in as a `String`, not a `UUID`.
  - `SettingsController` puts `toastContent` in as a `String`, not a Gson `JsonObject`.
  - `SearchController` adds a `SearchResultPage` record next to Spring's `Page`.
- **`ResourceUrlConfiguration`** registers `ResourceUrlEncodingFilter` (REQUEST, FORWARD, ERROR and ASYNC
  dispatches). Boot's FreeMarker auto-configuration used to register it, and it is what adds the webjar versions
  (`/webjars/jquery/3.7.1/…`) to links.
- **`MediaController`**: new `GET /media/getImageByID/{ID}` (authenticated like the rest of `/media`). The image
  picker used inline `data:` URLs, which Tessera's URL escaper replaces with `about:invalid#tessera`.
- **Markup inside messages.** Localized messages and notifications contain `<br>` and the links built by
  `NotificationLinkBuilder`. FreeMarker printed them raw, including the user data inside them (an account named
  `<script>…` was an XSS). `RichTextFunctions.parts()` splits a message into text, `<br>` and `<a href class
  target>` parts. The fragment `helpers/header :: richText` renders those as real elements with escaped text. The
  DOM is the same as before for normal data, and user data stays text.
- **Inline colours.** The old templates wrote `style="color: ${…}"` / `style="background-color: ${…}"`, and Tessera
  rejects expressions in `style`. `parity/tools/style_variants.py` rewrote those elements into one copy per known
  colour with a *literal* `style`: the 17 category palette colours, black/white and the default category colours.
  Arbitrary custom colours fall back to `data-font-color`/`data-background-color`, which `applyDataColors()` in
  `static/js/main.js` applies, also after `fetchModalContent.js` loads a modal. Rendered output is identical for
  palette colours. A custom colour gets a data attribute instead of a style.
- **Unclosed `<div>`s.** `transactions.ftl` (when the month has transactions) and `templateGroups.ftl` left a
  `<div>` unclosed, so browsers nested what followed (modal containers and scripts) inside the card. Tessera needs
  well-formed markup, so the new templates place those elements inside the card explicitly.
- **Fragment/macro signatures.** Macros that read globals take them as extra parameters, and fragments with
  several types got split. This is documented in a comment above each fragment, together with the old FreeMarker
  defaults.

## Results

**Build and tests.** `mvn clean package` compiles every template; there is no FreeMarker dependency, template or
configuration left. The test results match the untouched original exactly. Both run 315 existing tests with the
same 46 failing, all environmental:
- 42 errors: the selenium/integration tests and `TransactionServiceDatabaseTest` / `*ImporterTest` /
  `*SpecificationsTest` need Docker for Testcontainers, and there is no Docker here (`/var/run/docker.sock`
  missing).
- 4 failures: `DatabaseServiceTest.test_determineFilesToDelete_*` fail on the original too.

Because of these pre-existing failures, `mvn package` ends in BUILD FAILURE in this environment before and after
the migration. Compilation succeeds and no test was disabled or changed. I added `RichTextFunctionsTest` and
`ValidationFunctionsTest`, which pass; the first checks that user data in messages never becomes markup.
I could not add a test that renders through the real view resolvers, as the guide suggests: every `@SpringBootTest`
here needs a Testcontainers Postgres.

**Page parity.** `parity/compare.sh` with `parity/pages.txt` (54 requests: login failed/ok, every page, new/edit/
delete forms with and without validation errors, flash notifications, modals, 404 pages) gives
**40 `same`, 0 `reordered`, 14 `differs`**. Every status code and final URL matches. The remaining differences
come from three causes, and I believe each one is right:

1. **Values inside `<script>` are now JSON** (12 of the 14 pages). In a script Tessera only writes `[[…]]` as JSON,
   so `monthNames = ['January', …, ];` becomes `monthNames = ["January",…];`, `'Copied!'` becomes `"Copied!"`,
   `[ ]` becomes `[]`, and `{tag: 'x'}` becomes `{"tag":"x"}`. The JavaScript values are identical. The only
   alternatives were copying the localization strings into templates per language, or `SafeHtml.unchecked`.
   Pages: `/accounts/newAccount` (3 states), `/accounts/3/edit`, `/transactions/newTransaction/*` (3),
   `/templates/newTemplate`, `/charts`, `/search`, `/settings`, `/transactionImport`.
2. **The CSRF input has no `id="token"`** (`/settings`, `/settings/database/requestDelete|requestImport`). The old
   `settingsContainer` wrote its own `_csrf` input with `id="token"`. Tessera adds the CSRF input itself, and the
   brief forbids weakening CSRF, so the input has no id. The one script that read `#token` (backup "test git
   connection") now selects `form[name="BackupSettingsContainer"] input[name="_csrf"]`.
3. **Broken markup that cannot be rebuilt** (`/settings/database/requestImport`, and the database-delete modal).
   The old call passed `classes='"modal-action …'` with a stray quote, so the browser produced attributes such as
   `text-white"=""`. That is not a valid attribute name in a template. The buttons keep the same class and
   `modal-action`/`modal-close`, but not the junk attribute.

Not covered by the recording, but known to differ:
- Custom (non-palette) colours render as `data-*` attributes instead of `style`.
- Chart scripts are stored in the database and were printed raw inside `<script>`. They are now passed as a JSON
  string and run with an indirect `eval` (`charts/charts.html`). This is the same trust level as before (the user's
  own chart code runs either way), but top-level `let`/`const` in a chart script are no longer page globals.
- Messages that FreeMarker printed raw are escaped, apart from the `<br>`/link structure above.
- IDs and amounts that FreeMarker printed with grouping when `?c` was missing (`1,234`) now print plain.
- The about page's credits text (in the language bundles) still lists `spring-boot-starter-freemarker`. It is
  translation content, and changing it would break parity of `/about`.

**Security.** There is no `SafeHtml.unchecked`/`TrustedUrl.unchecked`, no new `permitAll` (`WebSecurityConfig` is
unchanged), CSRF is on (Tessera adds the token to every `<form method="post">`), and no test was disabled. The
migration removed the `static` model attribute (FreeMarker access to arbitrary static members) and the
raw-HTML-in-notification XSS.

## How the work was done
I recorded the baseline from the original jar before any change. I wrote the build setup, the function namespaces,
`header.html` and a porting brief. Five parallel agents converted the templates by area (helpers + top-level pages,
accounts/categories/tags/search, transactions, templates/groups/charts, settings/reports/filter). I then fixed the
cross-area signatures and worked through `compare` output until only the differences above were left.

## Where the guide or Tessera fell short
- **Service beans called from templates.** The guide covers request/session scope, but not the very common
  "Spring bean in the model, called with arguments" pattern (`helpers.foo(x)`). The only route is static
  `@TemplateFunctions`, and those cannot be Spring beans. I had to write an `ApplicationContext` holder, which is
  a static service locator. A way to register a bean instance as a namespace would remove that.
- **No varargs guidance for functions.** `Localization.getString(key, Object...)` needed fixed-arity overloads.
  The guide doesn't say whether varargs `@TemplateFunction`s work.
- **Static members/enum constants.** There is no way to reference `EntityType.ACCOUNT`, and every constant
  needed a function.
- **No default fragment arguments.** FreeMarker macros with eight optional parameters (`buttonLink`,
  `buttonSubmit`, `buttonFlat`) force every call site to spell out all of them. That is the biggest source of
  noise in the converted templates.
- **Dynamic `link[href]`.** `<@header.style name>` built `/css/${name}.css`. Resource URLs must be literal
  `@{…}`, so every call had to be inlined.
- **`on*` attributes.** The `buttonSubmit` macro took an `onclick` argument. Tessera rejects any expression there,
  including a fragment parameter that is always a literal at the call site, so those buttons had to be inlined
  with literal `onclick`. Per-variant duplication is needed when the handler embedded a value
  (`validateForm(false, ${allowEmptyAmount?c}, false)`).
- **Forms not bound through Spring tags.** The guide assumes `t:field`/`t:object`. This app hand-writes inputs
  and reads a `BindingResult` stored under a custom name (`error`), which the template cannot declare. I needed
  an interceptor to give it a view-safe shape.
- **Cookies.** `tessera-migrate` cannot set cookies. `/transactions/newTransaction/*` needs a `currentDate`
  cookie, so `pages.txt` first calls `/today?target=…`, which sets it server-side.
- **No way to compose `SafeHtml`.** Messages that legitimately contain a little markup around user data need
  `escape(a) + "<br>" + escape(b)`. The only API is `escape`, `of(template)` and `unchecked`, so I had to parse the
  message and rebuild it as elements.
- **`style` with computed values.** Even a value validated in Java (a `#rrggbb` colour) cannot go into `style`.
  There is no "trusted CSS" type, so I fell back to literal variants plus a `data-*` fallback.
- **JSON-only script output.** Any page that wrote JS literals differs in `compare`, although the values are equal.
  The guide never mentions that `compare` will flag this.
- **Malformed old markup.** Unclosed `<div>`s and stray quotes in the old output changed the browser DOM. Tessera
  requires well-formed templates, so this structure has to be rebuilt by hand. The guide only mentions "broken
  markup the old engine passed through" in passing.
- **The CSRF input has no hook.** An old page that gave its token input an id (for a script) cannot keep it.
- **Resource URL encoding.** Removing the old engine silently drops Boot's `ResourceUrlEncodingFilter` (it was
  registered by the FreeMarker auto-configuration), so versionless webjar links stop being rewritten. The guide's
  view-resolver warning doesn't mention this. Registering the filter for the ERROR dispatch is needed for error
  pages.
- **Parameterless fragments render in place**, so a reusable "page scripts" block needs a dummy parameter.
- **Tooling:** `tessera-migrate` only rewrites Thymeleaf, so it gave nothing for FreeMarker. `record` cannot send
  cookies, so pages behind a cookie need a server endpoint that sets it.
- **Docs gaps** found while working: there is no statement about whether `t:text` works on `<t:block>`, or how
  boolean attributes like `disabled` on `<a>` are rendered (`disabled="disabled"` vs bare `disabled`).

## Re-running the parity check
`parity/pages.txt` is the request list and `parity/baseline/` the recording of the original. `parity/compare.sh`
expects the scratch Postgres used here: a socket in `/tmp/pg`, user `budgetmaster`, and a database `neu` that it
re-creates. The app must have its settings file in `~/.Deadlocker/BudgetMaster/`. Adjust these for another
machine.
