# Migration report: Thymeleaf → Tessera

FredBet's server-rendered pages used Thymeleaf 3 with the layout dialect (`layout:decorate`) and the Spring Security
dialect (`sec:authorize`). They now all render through Tessera 0.1.0-SNAPSHOT (`tessera-spring-boot-starter`, compiled
templates). Thymeleaf, its Spring Security extras and the layout dialect are gone from the POM, the Thymeleaf settings
are gone from the YAML profiles, and no `th:`/`layout:`/`sec:` attribute is left in `src/main/resources/templates`.

## Build

* `pom.xml`
  * `spring-boot-starter-thymeleaf`, `thymeleaf-extras-springsecurity6` and `thymeleaf-layout-dialect` are removed.
  * `dev.tessera:tessera-spring-boot-starter` is added. It **excludes `org.springframework.boot:spring-boot-webmvc`**:
    the starter is built against Spring Boot 4.1.1 and declares that Boot 4 module, but FredBet runs on Boot 3.5.6. I
    checked the starter's bytecode. It only uses Spring Framework APIs (`ViewResolver`, `WebMvcConfigurer`,
    `RequestContext`, `BindStatus`, `CsrfToken`, ...) that exist in Spring 6.2, plus the Boot `@AutoConfiguration`
    annotations, which Boot 3.5 also has. `spring-boot-autoconfigure` is therefore resolved at 3.5.6 by the Boot parent.
  * `maven-compiler-plugin`: `tessera-compiler` is an annotation processor (next to
    `spring-boot-configuration-processor`, which has to be listed explicitly once `annotationProcessorPaths` is set),
    with `-parameters`, `-Atessera.templates`, `-Atessera.package=de.fred4jupiter.fredbet.templates` and
    `-Atessera.messages=.../msgs/TranslationMessages_en`. Message keys are checked against the English bundle at build time.
  * `default-testCompile` runs with `<proc>none</proc>` (see "Shortcomings" #2).
* `application-dev.yml`, `application-devpostgres.yml`: removed `spring.thymeleaf.cache`.

## Shared infrastructure (replaces bean access from templates)

Thymeleaf templates called Spring beans directly (`${@runtimeSettingsUtil...}`, `${@webSecurityUtil...}`,
`${@teamUtil...}`, `${@groupAvailabilityUtil...}`, `${@languageUtil...}`). Tessera has no ambient access, so:

* `web/template/LayoutModel` (record) + `LayoutModelInterceptor` (registered in `MvcConfig`): `postHandle` adds the
  model attribute `layout` to every rendered (non-redirect) view, including Boot's error view. It holds everything the
  layout, navigation, alert and language selector need: runtime settings, extra points config, login state, current
  user name and profile image key, main groups with resolved titles (formerly the dynamic key `#{${group.titleMsgKey}}`),
  group availability, H2 console flags, language URLs/flag classes and the flash `globalMessage`. Every page declares
  `LayoutModel layout` and passes it to the layout.
* `web/template/SecurityFunctions` (`@TemplateFunctions("sec")`): `sec.hasAuthority('PERM_X')` /
  `sec.hasAnyAuthority(...)` replace `sec:authorize`. Tessera's built-in `auth` namespace only checks roles
  (`ROLE_` prefix), but FredBet authorizes on permissions/authorities.
* `web/template/TeamFunctions` (`teams.cssClassFor`, `teams.i18n`) and `UserFunctions` (`users.profileImageKey`) delegate
  to the existing `TeamUtil`/`WebSecurityUtil` beans through `TemplateFunctionsBridge`, because Tessera functions must
  be static.
* `MvcConfig` registers `ResourceUrlEncodingFilter`. Spring Boot's Thymeleaf auto-configuration used to register it,
  and it turns `@{/webjars/jquery/jquery.min.js}` into `/webjars/jquery/3.7.1/jquery.min.js`. Without it the links lost
  their version segment.
* `@TemplateView` is added to the command/DTO/entity/settings classes the templates read (these classes have getters,
  so they are not records). `WebMessageUtil.WebMessage` is annotated too.

## Templates

* `_layout.html` is now the fragment `layout(Fragment content, String title, LayoutModel layout, Fragment head, Fragment scripts)`.
  Each page does `t:replace="~{_layout :: layout(~{::pagecontent}, #{...title}, layout, ~{::pageHead}, ~{::pageScripts})}"`.
  * The layout dialect dropped the page's `<div layout:fragment="content">` tag and kept only its children. Pages
    therefore wrap their content in `<pagecontent t:remove="tag">`, selected by tag name (a selected element sees the
    page's variables).
  * Extra `<head>` links and the former `layout:fragment="scripts"` blocks are same-file fragments
    `pageHead`/`pageScripts` on `<t:block>`. A page without them passes `~{fragments/empty :: none}`.
  * `layout:title-pattern="$LAYOUT_TITLE - $CONTENT_TITLE"` → `t:text="|FredBet - ${title}|"`.
  * The Bootswatch theme stylesheet was `th:href="${...getBootswatchThemeUrl()}"`, a plain string. Tessera only accepts
    literal `@{...}` links or a `TrustedUrl` in `link[href]`, and `TrustedUrl.unchecked` is not allowed. The layout now
    switches over the `Theme` enum (`t:switch`/`t:case`), with one `<link>` per theme carrying the same static URL as
    before.
* `_navigation.html` is the fragment `menu(LayoutModel layout)`. `fragments/language_selector` takes the layout model,
  `fragments/alert` takes the `WebMessage`, `fragments/delete_confirm` takes an already built link (`@{...}` at the
  call site, because a link path cannot be dynamic), and `fragments/profile_image` takes a `String` key.
* **Pages** (all 40+ page templates rewritten from the `tessera-migrate` output by hand, following the pattern above):
  * Bean calls on pages became model attributes added by the controllers on every path that renders the view, including
    validation-error re-renders: `roleSelectionDisabled` (user create/edit), `bettable` and `noCountryLabel`
    (extra bets), translated group titles (`GroupOption`, `MatchCommand.groupTitle`, `AllBetsCommand.groupTitle`,
    `GroupStandingsView`), `modeParam` (ranking, formerly `${param.mode}`), `globalErrors` (match result), and
    `timestampText` (error page; Boot's `timestamp` is a `java.util.Date`, which is not view-safe, so this copies its
    `toString()` format). New view records: `LastLoginView`, `GroupOption`, `GroupStandingsView`, `RichTextNode`.
    `SystemInfoController` passes a `SortedMap<String,String>`.
  * Dynamic message keys (`#{${...}}`, `#{'msg.' + role}`, `#{${'teamBundle.' + x}}`) became conditionals over the
    literal keys, or texts resolved in Java.
  * String-built links became `@{/path/{var}(var=...)}`. Where a null path variable is possible, a conditional
    reproduces Thymeleaf's `/.../null` output instead of failing the render. Back URLs that the controllers compute
    stay plain `String` hrefs.
  * htmx: `RuntimeSettingsController`'s `@HxRequest` handler returned the Thymeleaf fragment view
    `"admin/runtime_settings :: ..."`. Tessera cannot resolve fragment view names, so that markup now has its own
    template, `admin/runtime_settings_team_options.html`. Its response is byte-identical.
  * `#ids.prev(...)` and the numbered radio ids Thymeleaf generated (`roles1`, `level1`, `penaltyWinnerOne1`) are
    written explicitly. Password inputs use a plain `name`/`value=""`, because `t:field` drops `value=""`. File inputs
    use a plain `name`, because `MultipartFile` is never view-safe.
  * Kick-off dates: `temporals.format` always formats in English, so `MatchCommand`/`AllBetsCommand` got
    `getKickOffDateFormatted()` getters that use the request locale (German month/day names, as before).
  * **Rich text** (rules/prices/misc, edited with summernote and formerly `th:utext` of a raw HTML string): a new
    `RichTextSanitizer` (jsoup, **new dependency `org.jsoup:jsoup:1.18.3`**) parses the stored HTML against a strict
    allow-list into `RichTextNode` records. These are rendered by the recursive fragment `fragments/richtext.html`,
    which writes literal tags and lets Tessera escape every text and attribute. No `SafeHtml.unchecked` is involved.
  * `info/pointcourse`: Tessera cannot serialize the `DataSet` records/`Map<String,Object>` to JSON inside `<script>`.
    `ChartData` now exposes `getDatasetLabels()`/`getDatasetValues()`, and the script assembles the same Chart.js
    object from them.
* `PointsConfigController.saveConfig`: added `@ModelAttribute("pointsConfig")`. Before, a validation error put the
  object into the model under the wrong name: Thymeleaf sent a truncated page with status 200, and Tessera would have
  returned a 500. The page now shows the field errors.

## Verification

I ran the original application (Thymeleaf build of the starting commit) and the migrated one side by side, each with
an in-memory H2 database, logged in as admin on both and diffed every page after normalising whitespace, attribute
order, CSRF tokens and session ids.

Every page was checked: GET pages, form validation-error re-renders (user create/edit, registration, change
password/username, image group, match create/edit, match result, bet edit, admin forms, runtime settings), the htmx
response and the 404 error page. Where both apps had the same data (created through the same form POSTs on both),
they render the same, except for the items listed below. Differences in random demo data (uuids, registration codes,
timestamps) were judged by hand.

`TemplateEscapingUT` (new) renders the error page and the rules page with `<script>` in user data, a hostile flash
message and malicious rich text, and checks that everything comes out escaped or sanitized.

The Maven build (`mvn clean verify`) passes: 119 tests, the original 117 plus the 2 new ones, with no failures. No existing test had to change.

## What is not identical, could not be converted, or had to be worked around

1. **Rich text (rules/prices/misc).** It is no longer output raw. It is sanitized and rendered through Tessera:
   * Allowed tags and attributes render the same. Entities come out DOM-equivalent but spelled differently (`&nbsp;`
     → U+00A0, `"` → `&quot;`).
   * Dropped: all `style` attributes (summernote alignment, font styles, image sizes), because Tessera forbids
     expressions in `style`. Also dropped: `id` and other non-allow-listed attributes, `<script>`/`<style>`/`<iframe>`,
     event handlers and comments. Unknown tags are unwrapped.
   * `javascript:` links and **`data:` image URLs** become `about:invalid#tessera`. Summernote stores pasted images as
     base64 `data:` URIs, so such images no longer show. Making them work would need `TrustedUrl.unchecked`, which is
     forbidden here. Images served by the app or over http(s) are fine.
   * This is a deliberate change: the old `th:utext` let an editor inject script into every user's page.
2. **Navigation:** one extra `</li>` (the original markup never closed the admin `<li>`). The DOM is the same.
3. **`info/pointcourse` script:** the chart data is assembled in JavaScript from JSON arrays, not emitted as one
   Jackson-serialized object (it no longer has the unused `"empty"` field, and non-ASCII text is written as UTF-8
   rather than `\uXXXX`). Chart.js receives the same data.
4. **Country selects** in match edit/create: `<select t:field>` marks `<option value="">` as `selected` when the field
   is null, which Thymeleaf did not. Those selects therefore use explicit `id`/`name`/`t:selected` and give identical
   output. A rejected value would not be echoed back, which cannot happen for an enum select.
5. **Not verified with real data:** other users' rows on `bet/others`, the closed-betting state of `extra_bets`, and
   `/config/points/reset` (skipped so the shared reference app's data stayed intact). The templates are straight ports.

## Where the guide or Tessera fell short

1. **Starter built for Spring Boot 4.** The guide says to add `tessera-spring-boot-starter` and says nothing about
   versions. The starter's POM pulls `spring-boot-webmvc` 4.1.1, a Boot 4 module, into a Boot 3 application. I had to
   exclude it (see Build).
2. **The annotation processor also runs in `testCompile`.** Section 2's `compilerArgs`/`annotationProcessorPaths` apply
   to test compilation too. There the processor compiles every template again, but the `@TemplateFunctions` classes are
   not in that javac run (they are on the classpath, already compiled), so every `sec.`/`teams.` call fails with
   "unknown function namespace". The guide only says functions "must be compiled in the same javac run". The fix,
   `<proc>none</proc>` for `default-testCompile`, is not in the guide.
3. **Incremental compilation** also breaks function namespaces, not only template edits (section 17 mentions only
   templates). When only some Java files changed, Maven's incremental javac run did not contain the function classes,
   and templates failed with "unknown function namespace". Always build with `clean`.
4. **Element selection by tag name**: `~{::block}`/`~{::t:block}` cannot select a `<t:block>`, and names with `-` are
   rejected (`expected '}' to close '~{' but found '-'`), although section 16 says names may contain `-`. I used a
   custom `<pagecontent>` element with `t:remove="tag"`.
5. **No layout-dialect equivalent for "insert the children of the page element".** Section 11's `~{::body}` pattern
   inserts the `<body>` element itself, so the layout dialect's output (content without the wrapper tag) needs the
   `t:remove="tag"` trick above. The guide's layout example would change the HTML.
6. **No "optional" Fragment argument.** Pages without extra head/scripts must pass an explicit empty fragment.
7. **Spring Security authorities.** `auth.hasRole` only knows roles. There is no `hasAuthority`/`hasAnyAuthority`
   equivalent of `sec:authorize="hasAuthority(...)"`, so I needed a custom function namespace. The mapping table
   implies roles cover all cases.
8. **Messages from several bundles.** FredBet's `MessageSource` has four basenames (`TranslationMessages`,
   `ValidationMessages`, `TeamKey`, Spring Security's). `-Atessera.messages` takes a single bundle and expects a
   `<base>.properties` without locale suffix. FredBet has only `_xx` files, so I pointed it at
   `TranslationMessages_en`.
9. **Thymeleaf's lenient HTML.** The original navigation never closed an `<li>`. Tessera requires well-formed nesting,
   so the output now contains one extra `</li>` (the DOM is the same).
10. **`link[href]` from data.** A computed stylesheet URL needs a `TrustedUrl`, and the only way to build one is
    `TrustedUrl.unchecked`. The guide offers no safe constructor (for example one that validates a relative path), so
    a closed set of URLs has to be spelled out in the template.
11. **`SafeHtml` from stored rich text.** The guide's only routes to raw markup are `SafeHtml.unchecked` (forbidden
    here) and `SafeHtml.of(template)`. There is no sanitizer and no safe way to render user-authored HTML. See the info
    pages above.
12. **The migrator** left `layout:`/`sec:` attributes in place and stubs every parameter as `Object`, which the report
    states. It also mapped `th:utext` for HTML strings to `t:utext`, which cannot compile with a `String`.

13. **`temporals.format` ignores the request locale.** It always formats in English, so `'EEE dd. MMMM'` renders
    "Thu 01. October" on a German page. The guide does not say so.
14. **Forms:**
    * `t:field` on radios writes no numbered `id` (Thymeleaf wrote `field1`, `field2`, ...), and there is no
      `#ids.prev`.
    * `t:field` on a password input drops even a static `value=""`.
    * `<select t:field>` selects the empty option for a null value.
    * A hidden input with `t:field` and `t:value` behaves differently from Thymeleaf.
    * There is no equivalent of `#fields.hasErrors('global')`/`th:errors="*{global}"` (only `'*'`, which includes field
      errors).
    * `t:field` cannot be used on `<input type="file">` bound to `MultipartFile`.

    The guide covers none of these.
15. **Fragment view names** (`return "page :: fragment"`, as used for htmx) are not mentioned. They need a separate
    template.
16. **`<script>` JSON** cannot serialize records or `Map<String,Object>`. Section 7 lists what works, but not how to
    carry structured data.
17. **Fragment recursion** works (it was needed for the rich text) but is not documented. Whitespace between
    `t:case` siblings is rendered.
18. **`?:` branch types** must match (`int` vs `String` is a build error, Thymeleaf allowed it). A `null` query
    parameter renders as a bare `?mode`, where Thymeleaf wrote `?mode=`.
19. **Static `on*` attributes** compile fine. Section 7 reads as if every event-handler attribute is a build error.
20. **The migrator** turned Thymeleaf literal tokens in link parameters (`@{/ranking(mode=mixed)}`) into variables,
    declaring `Object mixed` parameters.
