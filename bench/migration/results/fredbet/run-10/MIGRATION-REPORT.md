# Migration report: Thymeleaf → Tessera

## Summary

All server-rendered views of FredBet are now Tessera templates, compiled by `javac` through the
`tessera-compiler` annotation processor into `de.fred4jupiter.views.*Template` classes. Thymeleaf,
the Thymeleaf layout dialect and the Thymeleaf Spring Security extras are gone (templates,
dependencies and configuration).

## What changed

### Build (`pom.xml`)

- Removed `spring-boot-starter-thymeleaf`, `thymeleaf-extras-springsecurity6` and
  `thymeleaf-layout-dialect`; added `dev.tessera:tessera-spring-boot-starter` (Boot 3 / jakarta).
- `maven-compiler-plugin`: `annotationProcessorPaths` with `tessera-compiler` (and the
  `spring-boot-configuration-processor` the build used before, since `annotationProcessorPaths`
  turns off discovery), `-Atessera.templates`, `-Atessera.package=de.fred4jupiter.views`
  (outside `de.fred4jupiter.fredbet`, so the ArchUnit rules don't analyse generated code) and
  `-Atessera.messages` pointing at the `_en` files of `TranslationMessages`, `ValidationMessages`
  and `TeamKey` (the app's `MessageSource` default locale is English), so every `#{key}` is
  checked at build time.
- The `<release>` override on the compiler plugin was removed: the Boot parent passes
  `java.version` (21) as the release, as the guide recommends.
- `tessera-maven-plugin` (`check` + `review`) so template-only edits recompile.
- `git-commit-id-plugin` moved from `initialize` to `process-resources`: on a clean build
  Tessera's `check` goal (phase `process-sources`) deletes `target/classes`, which wiped the
  `git.properties` the plugin had just written, so the system-info page lost its Git cards.
- `mvn dependency:list` before/after: the only libraries that left are Thymeleaf's own
  (thymeleaf, thymeleaf-spring6, extras, layout dialect, groovy, groovy-extensions,
  expression-processor, ognl, attoparser, unbescape, javassist). The application imports none of
  them, so nothing had to be declared.

### Configuration

- `spring.thymeleaf.cache` removed from `application-dev.yml` and `application-devpostgres.yml`.
- Javadoc/README mentions of Thymeleaf updated.
- No security configuration was touched: no new `permitAll`, CSRF stays on (the starter writes
  the `_csrf` hidden input into every same-origin POST form, as Thymeleaf did).

### Layout and shared data

- `_layout.html` is a fragment `layout(String title, Fragment head, Fragment content, Fragment
  scripts, LayoutModel layout, WebMessage globalMessage)`. Each page replaces its `<html>` with a
  call to it and passes `<head t:remove="tag">` (page CSS), `<page-content t:remove="tag">` and
  `<page-scripts t:remove="tag">`; this reproduces the layout dialect's merge (layout head first,
  page head appended, title pattern `FredBet - <page title>`, page scripts after the layout's).
- The templates used to call Spring beans (`@webSecurityUtil`, `@runtimeSettingsUtil`,
  `@groupAvailabilityUtil`, `@languageUtil`, `@languageIconUtil`). Those values now come from
  `web/layout/LayoutModelAdvice` (`@ControllerAdvice`, `@ModelAttribute("layout")`) as the
  `LayoutModel` record. Navigation group titles that were computed message keys
  (`#{${group.titleMsgKey}}`) are resolved in Java.
- `sec:authorize` became `t:if="auth.hasAuthority(...)"`/`auth.hasAnyAuthority(...)`.
- The bootswatch theme stylesheet (`link[href]` only takes literal links or a `TrustedUrl`) is a
  set of static `<link>`s, one per `Theme` constant, guarded by `t:if`; static so that the URL stays
  exactly as before (unversioned, as Thymeleaf's `${...}` href was).
- `error.html` gets Boot's error attributes typed (`ZonedDateTime timestamp`, ...). The timestamp
  is printed with `fmt.dateToString` (`web/layout/FormatFunctions`), which formats like
  `Date.toString()` in English, as Thymeleaf printed the `Date`.
- `_navigation.html` had an unclosed `<li>` (admin dropdown); Tessera rejects it, so the `</li>`
  was added where the browser closed it implicitly (same DOM).

### Pages

All 46 templates were converted (`tessera-migrate` did the mechanical first pass, the rest by
hand). Values the templates used to compute themselves now come from the controllers:

- **View types:** `@TemplateView` on the command/DTO/settings classes the forms bind
  (`BetCommand`, `MatchCommand`, `CreateUserCommand`, `RuntimeSettings`, `PointsConfiguration`, ...),
  and small view records where a page needed per-row data from beans: `RankingRow`,
  `AllBetsView`, `ExtraBetOthersRow`, `ExtraBetsPageInfo`, `StatisticRow`, `StandingsGroupView`,
  `UserLoginRow`, `TeamBundleOption`, `RoleOption`, `GroupOption`.
- **Computed message keys** (`#{${...}}`, `#{'msg.' + role}`, `#{'teamBundle.' + x}`, group
  titles) are resolved in Java with the request locale.
- **Bean calls** (`@webSecurityUtil.getUserProfileImageKeyFor(...)`,
  `isRoleSelectionDisabledForUser`, `@teamUtil.cssClassFor`, `@runtimeSettingsUtil...`) were
  moved into the controllers. `@teamUtil.cssClassFor(Country)` became the static
  `matchView.flagCss` template function.
- **`param.mode`** on the ranking page became the `requestedMode` model attribute.
- **Inline JavaScript values** were moved into declarations at the top of each script (the
  script prelude rule).
- **Stored rich text** (rules, prices and misc, edited with summernote and printed raw by
  `th:utext` before) is now `t:utext="html.sanitizeRich(...)"`.
- **htmx team-bundle endpoint** (`/runtimesettings/team-bundle`): renders a parameterised fragment
  of `admin/runtime_settings` that returns only the `<option>` list, as before.
- **New test:** `ViewRenderingIT` renders pages through the real view resolvers. It checks the
  layout, that the CSRF input is present, and that request data is escaped.

No `SafeHtml.unchecked`/`TrustedUrl.unchecked` is used. `target/tessera-review.txt` lists 0
unchecked calls and two plain-`String` template functions. `SecurityConfig` is unchanged, and no
test was changed, disabled or removed (one was added).

## Parity check

`pages.txt` (96 requests: logged out, login failure, every page as admin, empty and filled forms,
validation errors, success redirects, the error pages 404/500, language switch, logout) was
recorded from the original app with `tessera-migrate record` (fresh in-memory H2), and compared
against the migrated app started the same way:

```
java -jar tessera-migrate.jar compare http://localhost:8081 pages.txt baseline/ \
  --ignore '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' \
  --ignore '\w{3} \w{3} \d\d \d\d:\d\d:\d\d \w+ \d{4}'
  --ignore 'TODAY[T ]\d\d:\d\d(:\d\d\.\d+Z)?' \
  --ignore '(?<=registrationCode" value=")[A-Za-z]{6}|(?<=registrationCode" type="text" value=")[A-Za-z]{6}'
```

The ignores mask values that also change between two runs of the *original* app:
- the random UUID image keys;
- the error page timestamp;
- times of day, such as the "now + 1h" default kick-off, login times and build time;
- the random registration code.

A second list, `.tessera/pages2.txt` (135 requests, not committed), extends `pages.txt` with:
- creating two more users (one with markup in its name);
- a normal user's session: forced password change on first login, betting, others' bets,
  ranking, statistics, and the 403 error pages for admin URLs;
- a result-entry user's session;
- the admin editing their own record and another user.

It was recorded from the original jar and compared in the same way.

Final result, against the packaged jar (`target/fredbet.jar`) started from a fresh in-memory H2:

- `pages.txt` (96 requests): 86 `same` and 4 `reordered`. The reordered pages are 63, 65, 69
  and 93. On each, only the order of several validation messages for one field changed; Bean
  Validation returns them from a `HashSet`, so their order is not stable.
- `differs`: 51, 52, 58, 67, 72 and 83. Each is explained below. All statuses and final URLs
  match.
- `pages2.txt`: the same pages differ, plus 99, 101, 102 and 115, which differ for the
  `data-lightbox` reason below. Everything else is `same` or `reordered`.

`compare` therefore exits 1 on both lists; the remaining differences are each explained below.

## Could not convert identically / intended differences

Each of these comes from a Tessera rule that I chose not to work around with
`SafeHtml.unchecked`/`TrustedUrl.unchecked`:

1. **`data-lightbox` on profile-image links** (user list, ranking, others' bets, statistics, image
   upload).
   - Thymeleaf wrote the image key, a String, into `data-lightbox`.
   - A computed `data-*` value must be framework-neutral, so a String is a build error. An
     `HtmlPolicy` cannot allow `data-*` attributes either.
   - The attribute now holds the image's same-origin link (`/gallery/show/<key>`), which is
     unique per image just as the key was.
   - Lightbox uses the value only to group links, so behaviour is identical.
   - An empty value is not an option: lightbox2 then looks for `rel` groups and builds an empty
     album.
2. **`data-gallery="#blueimp-gallery-<group>"`** on `/gallery/show` (page 58) was dropped, for the
   same reason. Only `jquery.blueimp-gallery.js` reads it, and the page doesn't load that
   script: `gallery.js` opens `blueimp.Gallery(links, ...)` itself. Behaviour is unchanged.
3. **`/pointcourse/show`** (page 51): the chart-data declaration must open the script, so
   `var chartData = (...)` now comes before `var ctx = $('#myChart');`, with the JSON in
   parentheses. The JSON is identical and the script does the same thing.
4. **`/info/rules`** (page 52): the default rules text contains `<li value="NaN">`.
   `html.sanitizeRich` keeps `li value` only when it is a number, so it is dropped. Browsers
   ignore a non-numeric `value`, so the numbering is unchanged.
   - This text used to be printed raw. It is admin-editable HTML, so sanitizing it is the intended
     security change: tables, inline styles, `font` tags and classes from the summernote editor
     are now removed.
   - The `img-responsive` class that `InfoController` adds is also removed; it is a Bootstrap 3
     class that this app's CSS doesn't define.
5. **`/systeminfo`** (page 83): only the Git branch, commit ID, message and time differ. They
   describe the build's own Git commit, so they differ between any two builds. Before the
   `git-commit-id` phase fix above, the Git cards were missing altogether.

## Where Tessera or its documentation fell short

- **The `check` goal can silently drop generated resources.**
  `tessera-maven-plugin:check` deletes `target/classes` when it finds no template fingerprint,
  which is every clean build. Resources that other plugins generate before `process-sources`
  (here `git.properties` from git-commit-id in `initialize`) silently disappear from the jar. The
  guide describes the deletion but not this consequence.
- **Plugin-read `data-*` strings have no safe recipe.** The neutral-attribute rule has no answer
  for third-party plugins that read *group names* from `data-*` (lightbox2's `data-lightbox`,
  blueimp's `data-gallery`).
  - Recipe 5, "configure the plugin from a JSON block", needs JavaScript rewrites. It also cannot
    give exact parity, because it changes the markup.
  - `HtmlPolicy` cannot allow `data-*` either.
  - There is no way to declare an attribute as an inert identifier, or to pass a value that
    matches a pattern (UUID, slug).
- **No way to keep script order.** The script prelude rule forces `[[...]]` to the top of the
  script and wraps values in `(...)`, so a converted inline script never matches the old one
  byte for byte.
- **`html.sanitizeRich` is too narrow for an editor like summernote.** It cannot keep tables or
  inline styles, and `HtmlPolicy.Builder.allowAttributes` accepts only a fixed list. The guide
  doesn't say that `li value` must be numeric or that a non-numeric one is silently dropped.
- **`compare --ignore` runs after date masking.** It is applied after today's date has been
  replaced with `TODAY`, which the help text doesn't say, so a regex for a full date-time
  silently matches nothing.
- **`tessera-migrate` misses some type errors.** It doesn't flag Thymeleaf ternaries whose
  branches have different types (`cond ? 1 : '?'`), which then fail to compile. It does report
  computed message keys (`#{${x}}`), but with no hint that the only fix is in Java.
- **Many unreported missing-model-attribute cases.** Several templates read model attributes
  that the controller never set (for example `useJoker` on `bet/edit`, which Thymeleaf rendered
  as `value="true"`), and the migrator reports none of them. Only `compare` shows them.
- **Neutral-attribute errors arrive late.** The neutral-attribute rule (`data-*`) is reported
  only by the compiler, after the conversion, never in `migrate-report.md`.
- **No guidance on running the app with a plain classpath.** With `java -cp` (instead of the
  packaged jar), ical4j's time-zone provider adds about 350 `ical4j~<uuid>` time zones to the
  runtime-settings page. This is not Tessera's fault, but the guide's "start the app the same
  way" advice could warn that the classpath form changes behaviour. Final checks were done
  against the packaged jar.

### Other notes

- **Existing app bugs kept as they were.** `PointsConfigController.saveConfig` binds the form
  as `pointsConfiguration` while the page reads `pointsConfig`, so validation errors on that
  form are not shown, before or after the port. The runtime-settings POST error path never put
  the team lists in the model.
- **Sporadic startup failure.** The original app occasionally fails to start with a duplicate
  `RUNTIME_SETTING` primary key, an existing startup race; retrying works.
