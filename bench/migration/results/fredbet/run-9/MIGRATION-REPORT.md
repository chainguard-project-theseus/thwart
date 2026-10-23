# Migration report: Thymeleaf → Tessera

## Result

- **All 46 templates** under `src/main/resources/templates` are now Tessera templates, compiled by javac via
  `tessera-compiler` into `de.fred4jupiter.fredbet.templates`. Every page, including the Boot error page
  (`error.html`) and the htmx partial, is rendered by Tessera.
- **Thymeleaf is gone**: `spring-boot-starter-thymeleaf`, `thymeleaf-extras-springsecurity6` and
  `thymeleaf-layout-dialect` were removed from the pom, `spring.thymeleaf.*` was removed from
  `application-dev.yml`/`application-devpostgres.yml`, and the README and code comments were updated. There are
  no `th:`/`sec:`/`layout:` attributes anywhere in `src`.
- **Build**: `mvn clean verify` passes on JDK 21 (the project's `java.version`). All 117 existing tests pass,
  plus 3 new ones (below). No test was changed, disabled or deleted.
- **Security**: there is no `SafeHtml.unchecked`/`TrustedUrl.unchecked`, no new `permitAll`, and CSRF and
  escaping are unchanged. Tessera adds the `_csrf` input to every POST form, as before.
- **Page parity**: `.tessera/pages.txt` has 104 requests covering every page and form state: logged out,
  login failure, every list, every create/edit form empty, with validation errors and after success, the 404
  and 500 error pages, and a language switch. They were recorded from the original app (`.tessera/baseline/`)
  and compared against the migrated app, both started the same way (`java -jar fredbet.jar` on an in-memory H2
  database). The result is **97 same, 3 reordered, 4 differ**.
  - The **reordered** pages are 068 and 100, plus 090 or 092 depending on the run. Only the order of two Bean Validation messages inside one
    `t:errors` element changes, and that order isn't guaranteed (the original app shuffles it between its own
    runs too).
  - **058 / 059** (`POST /info/editinfo` with `<p>First <b>prize</b> <script>alert(1)</script></p>`, then the
    prices page) differ **on purpose**. The old page printed stored rich text raw with `th:utext`, so this was
    a stored XSS. The new page drops the `<script>` element. Everything else on the page is identical.
  - **076** (`/runtimesettings/show`) differs only in the registration code, which the app generates at
    random on each start. The original app differs from itself the same way.
  - **081** (`/systeminfo`) differs only in values that come from the environment: the build timestamp of the
    jar, and the JDBC URL (which contains the port in my test setup). The template output is otherwise identical.
  - Masks used when comparing (all narrow): UUIDs (profile image keys are random), `HH:mm[:ss]` times
    (the "new match" form defaults to now), `N ms`, and `localhost:<port>`. The language links are absolute
    URLs that contain the port.

## What changed

### Build (`pom.xml`)
- Added `dev.tessera:tessera-spring-boot-starter` (Boot 3 / jakarta). The `maven-compiler-plugin` now has
  `annotationProcessorPaths` for `tessera-compiler` and for `spring-boot-configuration-processor`, which was
  discovered automatically before and has to be listed now. It also passes `-Atessera.templates`,
  `-Atessera.package` and `<parameters>true</parameters>`.
- Added `org.jsoup:jsoup` for the rich-text renderer (below).
- `-Atessera.messages` is **not** set: the app uses four bundles (TranslationMessages, ValidationMessages,
  TeamKey, Spring Security), and Tessera can check only one. Messages resolve through the app's Spring
  `MessageSource` at runtime, as before.

### Shared view infrastructure (`web/view/`)
- `ViewFunctions` (namespace `fb`) replaces everything the templates read from Spring beans and the security
  dialect: `fb.hasAuthority(...)` for `sec:authorize`, `fb.settings()`, `fb.theme()`, `fb.mainGroups()`,
  `fb.groupAvailable(..)`, `fb.teamCss(..)`, `fb.languageUrl(..)`, `fb.currentUserProfileImageKey()`, and others.
  Each function is a thin static delegation to the existing beans (`WebSecurityUtil`, `RuntimeSettingsUtil`,
  `GroupAvailabilityUtil`, `TeamUtil`, ...). `ViewBeans`, an `ApplicationContextAware` holder, gives them access
  to those beans. It also has `fb.msg(key[, arg])` for computed message keys (`#{${...}}`) and
  `fb.formatDate(t, pattern)`, which is locale-aware like Thymeleaf's `#temporals.format`. Tessera's
  `temporals.format` is English-only, and the app prints German day and month names.
- Smaller namespaces from the per-area conversions: `fbb` (`BetViewFunctions`: team names, "match started"),
  `fbi` (`InfoViewFunctions`: standings per group, standings CSS), and `fbu` (`UserViewFunctions`:
  `contains(Collection, String)` for the role radios).
- `RichText` + `RichNode` + `templates/fragments/richtext.html`: stored rich text (rules, prices, misc) is
  parsed with jsoup into an allow-listed node tree. A recursive Tessera fragment renders it, so Tessera escapes
  every text and attribute value and checks every URL. Scripts, styles, frames, forms, event handlers and
  `style` attributes are dropped. Tables, images, `class`, `li value` and the other formatting the Summernote
  editor produces are kept. I chose this over `html.sanitize`, which would have dropped the `value` attribute
  of the default rules page's `<li value="NaN">` (a visible difference on `/info/rules`) and any images and
  tables users add in the editor.

### Layout
- `_layout.html` is a fragment
  `layout(String title, WebMessage globalMessage, Fragment head, Fragment content, Fragment scripts)`, replacing
  the layout dialect. Each page does `t:replace="~{_layout :: layout(#{title}, globalMessage, ~{::head},
  ~{::body}, ~{::scripts})}"`, with `<head>`/`<body>` marked `t:remove="tag"` and the old
  `layout:fragment="scripts"` block moved into a `<scripts t:remove="tag">` element after `</body>`.
  `globalMessage` (the flash message) is declared on every page and passed through, because the layout cannot
  see the page's model.
- The Bootswatch theme stylesheet was a computed `href` without `@{}` (so not resource-versioned). Tessera only
  allows a literal `@{}` link or a `TrustedUrl` in `link[href]`, and a `@{}` link would have gained the webjar
  version. The layout therefore has one static `<link>` per `Theme` constant, each guarded by
  `t:if="fb.theme() == 'X'"`.
- `_navigation.html` had malformed nesting (an `<li>` closed by `</ul>`). I added the `</li>` where the browser
  implied it, so the DOM is unchanged.

### Controllers and model classes
- `@TemplateView` was added to every command, DTO and entity the templates read (`MatchCommand`, `BetCommand`,
  `AppUser`, `Match`, `Bet`, `RuntimeSettings`, `WebMessage`, ...).
- `ApplicationErrorViewResolver` turns Boot's `timestamp` (`java.util.Date`, not view-safe) into its
  `String.valueOf` text for `error.html`, which is what Thymeleaf printed.
- `SystemInfoController` copies the `SortedMap<String,Object>` into a `Map<String,String>`, in the same order.
- `RuntimeSettingsController` `/team-bundle` (htmx) returns the new template
  `admin/runtime_settings_favourite_country_options`, because Tessera can't resolve `page :: fragment` view names.
- `RankingController` puts `modeParam` in the model, replacing `${param.mode}`.
- `MatchController` always puts `redirectViewName` in the model as a String.
- `MatchResultController` puts the object-level validation messages in the model as `globalErrors`. Tessera
  has no equivalent of `#fields.hasErrors('global')`.
- `MatchCommand.isMatchFinished()` was added as a getter for `hasMatchFinished()`.

### New tests
- `RichTextUT`: scripts, styles and event handlers are dropped, and unknown elements are unwrapped.
- `TesseraRenderingIT`: renders `/login` through the real view resolvers and checks that request data
  containing `<script>` comes out escaped.

## Not converted / left as is
- Nothing was left unconverted.
- **Not a migration problem:** `POST /config/points` with a validation error returned 500 in the original app
  too. Spring binds the object as `pointsConfiguration` but the template reads `pointsConfig`. I kept the
  existing behaviour and left this case out of `pages.txt`.
- **Intended behaviour change:** user-supplied rich text is now filtered, where it used to be printed raw.
  Content that relied on inline `style`, scripts or iframes loses those parts. Image URLs other than
  http(s)/relative, such as `data:` URLs from pasted images, become `about:invalid#tessera`.

## Where Tessera or its documentation fell short
1. **No safe way to emit a computed stylesheet URL.** `link[href]` takes only a literal `@{}` link or a
   `TrustedUrl`, and the only way to make a `TrustedUrl` is `unchecked`. There's also no way to opt a single
   `@{}` link out of resource versioning. I needed one static `<link>` per theme to keep the output identical.
2. **`html.sanitize` is too strict for real rich-text editors**: no images, tables, `class` or `li value`, and
   the policy can't be changed. Building a safe renderer meant writing a parser plus a recursive fragment (a
   `t:switch` over allow-listed literal tags). The guide hints at this ("a template over a parsed model") but has
   no example.
3. **`-Atessera.messages` supports only one bundle**, so apps with several bundles lose build-time key checking
   entirely.
4. **No object-level (global) errors** in `fields.*`. `fields.errors('*')` mixes in field errors, and the
   guide offers no recipe, so the controller has to supply them.
5. **Checkboxes and radios bound to a collection** get no `checked`, radios get no `id`, and `#ids` has no
   replacement. The guide lists the checkbox case under limitations but not radios, and there's no Set
   `contains` built-in (`lists.contains` is for lists).
6. **A null `Integer` in a comparison** (`x > 0`) compiles but throws a NullPointerException at render time,
   where Thymeleaf evaluated it to false. The guide's runtime-error list doesn't mention it.
7. **Ternaries with mixed types** (`n != null ? n : '?'`) don't compile; you need `'' + n`. `t:value="null"`
   omits the attribute, so `<option th:value="null">` placeholders must become a static `value=""`.
8. **Undocumented behaviour** that I had to find by trying: whether `<t:block>` can be a template root, whether
   custom tag names work for `~{::tag}` selections (they do: `<scripts>`), whether `[[` in a script that isn't
   an expression is left alone (it is), what `t:each` does over `null`, and whether `t:if` and `t:each` on one
   element work together.
9. **Fragment-only view names** (`"page :: fragment"`, used by htmx endpoints) don't resolve. Each fragment has
   to become its own template and the controller has to change.
10. **`SortedMap`/`TreeMap` and other concrete collection types** are rejected, which forced a copy in
    `SystemInfoController`.
11. **`tessera-migrate`** left all layout-dialect, `sec:` and `@bean` constructs to do by hand, which was most
    of the work in this app. `compare` has no option to mask a value inside one attribute only, so the random
    registration code couldn't be masked narrowly.

## How to reproduce the parity check
```sh
java -jar .tessera/tessera-migrate.jar record  http://localhost:8081 .tessera/pages.txt .tessera/baseline   # original app
java -jar .tessera/tessera-migrate.jar compare http://localhost:8082 .tessera/pages.txt .tessera/baseline \
  --ignore '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' --ignore 'TODAY \d\d:\d\d(:\d\d)?' \
  --ignore '\d\d:\d\d:\d\d' --ignore '\d+ ms' --ignore 'localhost:80[0-9][0-9]'
```
Both apps were started with
`java -jar fredbet.jar --server.port=P --spring.datasource.url="jdbc:h2:mem:fbP;DB_CLOSE_DELAY=-1"`.
`.tessera/` is in `.git/info/exclude` and is not part of the change.
