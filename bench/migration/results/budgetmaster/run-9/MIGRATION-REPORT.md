# Migration report: FreeMarker → Tessera (BudgetMaster 2.18.2)

## Summary

BudgetMaster now renders all of its pages with Tessera 0.1.0-SNAPSHOT. That covers all 75 FreeMarker templates: every page, modal, fragment and error page.

- **FreeMarker is gone:** the `.ftl` files, `spring-boot-starter-freemarker`, the `spring.freemarker.*` properties, and the two advices that only existed for FreeMarker (`StaticClassesAdvice`, `ServiceAdvice`).
- **Page parity:** against my own request list (80 requests, recorded from the original app), 75 pages come out `same`.
- **The 5 that differ** each differ only for reasons Tessera can't reproduce (below).
- **A second list** of 115 requests (extra edit, select, highlight, search, report and chart states) gives the same result. The only other mismatches there are requests where the original app itself crashes in the middle of a FreeMarker render (status 0).

## What changed

### Build
`BudgetMasterServer/pom.xml`:
- `tessera-spring-boot-starter` replaces `spring-boot-starter-freemarker`.
- `maven-compiler-plugin` now has `annotationProcessorPaths` for the Tessera compiler, Lombok and hibernate-jpamodelgen. Listing the other two is needed because that setting switches off processor discovery.
- Two compiler arguments: `-Atessera.templates=…/templates` and `-Atessera.package=de.deadlocker8.budgetmaster.templates.generated`.
- Java 21 and Boot 3.5.9 are unchanged.

### Templates
Each `templates/**/X.ftl` became `templates/**/X.html` at the same path, so every view name the controllers return still matches.
- **Macros** became typed `t:fragment`s. `#nested` became a `Fragment` parameter, and default values are passed explicitly at each call.
- **Layout pieces** (`helpers/header`, `navbar`, `scripts`) are fragments that each page calls.
- **No `<!DOCTYPE>` on pages:** the old pages had none, and adding one shows up in the compare.
- **Hand-written `_csrf` inputs** were removed. Tessera adds the token to every POST form itself.

### Data that templates used to pull from outside the model
FreeMarker templates called Spring beans (`helpers`, `dateService`, `currencyService`, `updateService`), static classes (through `static[...]`: `Localization`, `ProgramArgs`, `EntityType`) and session attributes. Tessera templates can only call functions, so these became `@TemplateFunctions` classes in `de.deadlocker8.budgetmaster.templating`:
- `loc` (`LocalizationFunctions`): `loc.get(key, args…)` replaces `locale.getString`.
- `bm` (`AppFunctions`):
  - current account, budget, hints, settings and theme colours;
  - date and currency formatting;
  - update status, test/debug mode and `EntityType`;
  - `notificationMessage` (see Security);
  - `isGerman()` (see the script limitation below).
- `val` (`ValidationFunctions`) with `ValidationErrors` and `ValidationErrorsInterceptor`, replacing `helpers/validation.ftl`.
  - Controllers put a raw `BindingResult` named `error` in the model, which a template can't read.
  - The interceptor copies its field errors into a view-safe `validation` attribute, and the `val.*` functions write the same `class`/`data-tooltip` attributes the macro wrote.
- One namespace per area: `acc`, `tx`, `txf`, `cat`, `misc`, `mischarts`, `set`.
- `SpringContextHolder` gives these static functions access to the services.

Other Java changes:
- **Controllers:** values the old templates read from the HTTP session or derived from Spring types now go into the model explicitly:
  - `accountsFilterConfiguration`, `filterConfiguration`, and the CSV import state (`csvImport`, `csvRows`, `csvTransactions`, `errorsColumnSettings`);
  - `page` (a `SearchResultPage` record instead of Spring's `Page`), `containerID`, `toastContent` as a String, and `existingTemplateNames` as a `List`.
- **`@TemplateView`** on the entities, `Iconizable`, `Notification`, `Build`, the filter and search objects, `CsvRow`/`CsvTransaction`, `HotKey`, `StatisticItem`, `ChartSettings`, `InternalDatabase` and `ImportResultItem`. Explicit getters were added where Lombok getters weren't visible to the template compiler.
- **`MediaController`:** new `GET /media/getImageByID/{ID}`. The image picker used to embed images as `data:` URLs, which Tessera only allows through `TrustedUrl.unchecked`; it now loads them from this URL.

## Security
- **No `SafeHtml.unchecked` or `TrustedUrl.unchecked`** anywhere. No `permitAll` was added, CSRF is unchanged, and no tests were touched.
- **Values FreeMarker printed raw are now escaped.** FreeMarker `.ftl` had no auto-escaping, so this is intended.
- **Messages containing `<br>`** are escaped first, and the line breaks are put back with `SafeHtml.sanitize("<br>")`.
- **Success notifications with a link:** the link is built in Java by `NotificationLinkBuilder`. `bm.notificationMessage` escapes the text around it and renders the link through the `helpers/notificationLink.html` template, so the link name is escaped too. Before, it was a stored-XSS vector.
- **Two static links** (`errorpages.418.credits`, `chart.new.info.wiki`) are now written into the template as markup. `html.sanitize` would strip their `title`/`target` attributes, and the text is the same in both languages.
- **Custom chart scripts:** these are stored JavaScript that the old page printed raw into a `<script>`. They are now passed as a JSON string and run with an indirect `eval` in `charts/charts.html`. It's the same feature (run the user's own chart code), but a top-level `const`/`class` in a chart script is no longer global. **Please review this one.**

## Tests and build
- `mvn clean verify` compiles everything, including tests, on JDK 21.
- **The test run is not green in this environment, and the untouched original isn't either:**
  - 42 integration tests need Docker (Testcontainers PostgreSQL), and the Selenium tests need Firefox. Neither exists here.
  - `DatabaseServiceTest` (4 tests that capture log output) fails exactly the same way on the untouched original.
  - Every other unit test passes. No test asserted on FreeMarker details, so none was changed.
- The Selenium tests check the DOM, and the DOM is unchanged on the pages that compare `same`.

## What could not be converted exactly (remaining compare differences)

| Page | Difference | Why |
|---|---|---|
| `/settings`, `/settings/database/requestDelete`, `…/requestImport`, `/settings/update` | Each settings form now has two `_csrf` inputs: Tessera's automatic one, plus the old `<input id="token" name="_csrf">` | `static/js` reads the token through `#token`. Tessera always adds its own input to a POST form, and there's no way to give it an `id` or to say "this form already has one". So it's either one extra input or one missing `id`. |
| same pages | `initialKeywords = [ {tag: 'x'}, ]` is now `[{"tag":"x"}]` | Inside `<script>`, Tessera can only write JSON (`[[…]]`, and also `t:text` on a script). A loop that builds JavaScript text can't be reproduced. The JavaScript means the same thing. |
| `/settings/database/requestDelete`, `…/requestImport`, `/templates/fromTransactionModal` | Old markup had a stray `"` in a class argument, which produced junk attributes (`text-white"=""`) | An attribute name containing a quote can't be written in a Tessera template. The classes are now correct. |
| `/templates/fromTransactionModal` | `existingTemplateNames = [ "Rent" ]` (Gson pretty print) is now `["Rent"]` | Same JSON-only limitation inside scripts. |
| `/settings/database/requestDelete` | Verification code | It's random on every request, in the old app too. My compare masks it with `--ignore`. |
| chart display (`POST /charts`, not in my list) | Script text: the chart script now runs through `eval`, and its strings are double-quoted | Same JSON-only limitation; see Security. |

Three places where old single-quoted JavaScript strings came from localization would also have differed:
- the date picker's month and day arrays;
- `createTemplateWithErrorInForm`, `localizedSearch` and `copiedToClipboard`.

The app has exactly two languages, so these are now written out literally for each language and chosen with `t:if="bm.isGerman()"`. The text is byte-identical to before.

Two old pages left an element unclosed: the date group in `transactions.ftl` and the main card in `templateGroups.ftl`. The compare tool's parser then nests the following elements inside it. The new templates nest them the same way so the pages stay identical; there are comments in those templates.

## Where Tessera or its documentation fell short
1. **No way to write non-JSON text inside `<script>`.** `[[…]]` and `t:text` both write JSON, and `[(…)]`/`t:utext` aren't allowed in scripts. So pages that built JavaScript by hand (single-quoted strings, arrays from loops, stored scripts) can't keep their exact script text. The guide only covers the Thymeleaf case `var x = [[${x}]]`.
2. **The automatic CSRF input can't be customised:** you can't give it an `id`, and you can't suppress it when the form already has a token input. Not documented.
3. **Lombok-generated getters aren't seen** by the template compiler ("is not a property"), even though Lombok runs as a processor in the same javac. The guide doesn't mention this, apart from the `getIsX` note.
4. **`data:` image URLs** can only get through `TrustedUrl.unchecked`, and the guide has no safe pattern for inline images.
5. **Enum `name()`:** if `toString()` is overridden, printing an enum gives `toString()`, and a template can't call `name()`. That needs a function.
6. **`~{::name}` scope is unclear:** for a same-file `t:fragment` it doesn't see the caller's variables, but a plain tag selection like `~{::section}` does. It also selects only the first element with that tag, and not `t:block` or hyphenated tags. The guide doesn't say this clearly.
7. **`t:replace` runs before `t:each` and `t:if`,** so a loop or condition around a fragment call needs a wrapping `<t:block>`. The guide does document this, but it's the most common mistake.
8. **No raw-text path for trusted markup that has attributes** (a localized message with `<a title target>`). `html.sanitize` strips those attributes and there's no policy option, so the markup had to be hardcoded.
9. **No helper for the classic "template reads beans and statics" engines** (FreeMarker `static`/beans, JSP scriptlets). The only way out is function namespaces backed by a static application-context holder, which works against the "templates see only their parameters" model. The guide could describe a recommended pattern.
10. **`tessera-migrate` only rewrites Thymeleaf.** For FreeMarker everything was converted by hand.
11. **`compare` and `record` can't send cookies or headers,** so pages behind a required cookie have to be reached through app endpoints that set it (here `/today?target=…`). The tool also records a status of `0` when the old app crashes mid-render, and the new app can never match that.
12. **Old pages have no `<!DOCTYPE>`,** while every guide example has one. Adding it to pages shows up as a diff on every page.

## Files for reproducing
`.tessera/` holds the request lists, the recorded baselines and the run scripts. It's excluded through `.git/info/exclude`.
- `pages.txt` and `baseline/`, plus `pages-extra.txt` and `baseline-extra/`, recorded from the original app on a fresh PostgreSQL database.
- `bin/record-orig.sh` records the original app.
- `bin/run-copy.sh` builds a copy of the tree, starts it on a fresh database and runs `compare`.
