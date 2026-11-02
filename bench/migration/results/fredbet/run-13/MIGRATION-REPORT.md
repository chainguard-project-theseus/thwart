# Migration report: Thymeleaf → Tessera

## Summary

All 45 server-rendered pages, the shared layout, navigation, fragments and the error page are now
compiled Tessera templates (`src/main/resources/templates`, generated package
`de.fred4jupiter.fredbet.templates`). Thymeleaf is gone: `spring-boot-starter-thymeleaf`,
`thymeleaf-extras-springsecurity6` and `thymeleaf-layout-dialect` are removed from the pom, and so are
the `spring.thymeleaf.*` settings in `application-dev.yml` and `application-devpostgres.yml`. The
startup warning `Tessera: another template engine is still configured` no longer appears.
`mvn clean install` passes on JDK 21 (the project's `java.version`), with all existing tests. No test
was changed.

No security setting was changed. There is no `TrustedUrl.unchecked`, no new `permitAll`, CSRF is
unchanged and Tessera adds the token to every same-origin POST form, and escaping is on everywhere.

## What changed

### Build (`pom.xml`)
- Added `dev.tessera:tessera-spring-boot-starter` (Boot 3 / jakarta).
- `maven-compiler-plugin` now lists its annotation processors explicitly: Boot's
  configuration processor, then `tessera-compiler`. It passes `-Atessera.templates`,
  `-Atessera.package` and `-Atessera.messages`. The messages are checked against the English bundles
  `msgs/TranslationMessages_en`, `ValidationMessages_en` and `TeamKey_en`, because there are no base
  bundles.
- Added `tessera-maven-plugin:check`, so an edit to a template or message alone still triggers a
  recompile.
- `.tessera/` (the migrate jar, `pages.txt`, recordings) is in `.gitignore`.

### Shared infrastructure (new Java)
- `web/view/LayoutView` + `web/view/LayoutModelAdvice`: a `@ControllerAdvice` that puts a
  `layout` model attribute on every page. It replaces every `${@bean.method()}` the old templates
  called: `webSecurityUtil`, `runtimeSettingsUtil`, `groupAvailabilityUtil`, `languageUtil`,
  `languageIconUtil` and `bettingWebUtil`. Its getters are lazy. The profile image key of any user is
  available as `layout.profileImageKeys[name]`.
- `web/util/TeamFunctions` (`teams.css(country)`) replaces `@teamUtil.cssClassFor(...)`. The flag
  lookup in `TeamUtil` became static so a template function can use it.
- `web/view/ErrorPageFunctions` (`errorPage.timestamp(...)`) prints the error timestamp in the old
  `Date.toString()` format.
- `web/info/InfoFunctions` (`infos.html(...)`): an `HtmlPolicy` for the summernote-edited
  rules, prices and misc pages.
- `@TemplateView` on the command, DTO and entity classes the templates read (`WebMessage`, the
  match, bet, user, image, admin and info commands, `Match`, `Bet`, `RuntimeSettings`,
  `PointsConfiguration`, `ChartData`, `Statistic`, `TeamStandings`, ...).
- New view records where the old template computed something, mostly a message key built from data
  (`#{${...}}`): `RoleView`, `GroupOption`, `GroupStandingsView`, `ExtraBetView`, `UserLoginView`,
  `TeamBundleOption`, and `groupTitle` on `AbstractMatchHeaderCommand`.

### Templates
- `_layout.html` is a fragment, `layout(title, head, content, scripts, layout, globalMessage)`.
  Every page replaces its `<html>` with it, which replaces the layout dialect's `layout:decorate`
  and title pattern. The bootswatch theme stylesheet is a `t:switch` over the closed `Theme`
  enum, using static hrefs as before.
- `_navigation.html`, `fragments/*`: typed fragments. `sec:authorize` → `t:if="auth.hasAuthority(...)"`.
  I added a missing `</li>` in the navigation's admin dropdown; the DOM is the same.
- The htmx partial `runtime_settings :: favouriteCountryOptions` moved to its own template,
  `admin/runtime_settings_options.html`. A fragment can't see the page's `t:object`, so if the
  fragment stayed inside the page, the full page's options would lose their `selected`.

### Controllers
- Controllers now put into the model the values the old templates read from elsewhere:
  - `UserController`: `roleSelectionDisabled`, plus the role labels and tooltips.
  - `RankingController`: `mode`, which replaces `param.mode`.
  - `InfoController`: `infoTitle`.
  - `MatchController`: `redirectViewName`, always as a String.
  - `StandingsController`, `ExtraBetController`, `CreateEditMatchController`: the view records above.
  - `SystemInfoController`: a `Map<String,String>`, because `Object` values aren't view-safe.
- `PointsConfigController.save` now has `@ModelAttribute("pointsConfig")`, so the page can find
  its `BindingResult`. Before, posting the points form with validation errors broke the original
  page: Thymeleaf failed mid-render and the response was cut off. Now the page renders with its
  field errors. This is the one behaviour change, and it fixes a bug.

## Page parity

My `pages.txt` (189 requests, in `.tessera/pages.txt`) covers:
- Every page logged out, as admin and as a normal user, including 403 and 404 error pages.
- Every form empty, with validation errors and after success.
- Lists both empty and filled, the runtime settings save (theme, navbar, self-registration) and
  the registration form.

Result against the original: **157 same, 2 reordered, 24 differ.** Every remaining difference is
one of the following:

1. **Values that change on every run.** These are not template differences:
   - Error-page timestamps.
   - The random registration code (`/runtimesettings/show`).
   - Build time and JDBC URL (`/systeminfo`).
   - `user.home` (`/backup/show`).
   - Profile image UUIDs (masked with `--ignore`).
2. **`data-lightbox` on profile thumbnails** (shared `fragments/profile_image` and `user/list`).
   - Before: the image UUID. Now: the same-origin link `/gallery/show/<uuid>`.
   - Tessera only accepts numbers, booleans or literal-path links in non-standard attributes. This
     is the guide's "a key a plugin only compares" recipe.
   - Lightbox only compares the value to group images, so the behaviour is the same.
   - It shows on `/user`, `/ranking`, `/info/statistic`, `/bet/others/...` and `/extrabets/others`.
     It's the reason most of the non-environment differences remain.
3. **`data-gallery` on `/gallery/show`.** Before: `#blueimp-gallery-<group>`. Now: the group's
   loop index (same recipe). `gallery.js` doesn't read it.
4. **Stored rich text goes through a sanitizer** (`/info/rules`, `/info/misc`, `/info/prices`):
   - A stored `<script>` is dropped. This is the intended security fix.
   - Links get `rel="noopener"`.
   - `<table>` markup is reduced to its text, because tables are not in `HtmlPolicy.ELEMENTS`.
   - The seed rules text's invalid `<li value="NaN">` loses its `value`. Browsers ignore that
     value anyway.
5. **`/pointcourse/show` script.** `var chartData = [[${chartData}]];` has to be the first statement
   in the script, so it now comes before `var ctx = $('#myChart');`. The JSON is byte-identical and
   the behaviour is the same.
6. `reordered`: field validation messages whose order comes from a `HashSet` (POST `/user`, POST
   `/profile/changePassword`).

## Not converted

Nothing was left on Thymeleaf. Two things could not be reproduced exactly; both are explained
above:
- The opaque string keys in `data-lightbox` and `data-gallery`.
- The crashing points-config error page.

## Where Tessera or its documentation fell short

- **Parity versus the `data-*` rule.** No value allowed in a neutral attribute can reproduce an
  opaque string id (a UUID used as a lightbox or gallery group key). Every documented recipe changes
  the page, and `compare` has no way to accept a recipe change except a blanket `--ignore`.
  - A page-relative link (`@{{key}(...)}`), which would print exactly the key, is rejected in
    `data-*`. The guide doesn't say that; only the compiler does.
- **Script declarations.** The rule that `[[...]]` must be the first declaration forces a visible
  reorder of an otherwise unchanged script. `compare` doesn't normalise it, so the page can never
  be `same`.
- **`HtmlPolicy`:**
  - The guide doesn't list `HtmlPolicy.ELEMENTS` and `ATTRIBUTES`; we had to find them with `javap`.
  - Tables are not allowable at all.
  - `linkRel(...)` always adds at least `noopener`, even to relative links.
  - `value` is silently dropped unless it is numeric.
  - Stored content therefore changes without warning.
- **Incremental migration.** A Thymeleaf template left under the Tessera templates root fails the
  build (`xmlns:th` is a parse error). So the guide's "the old engine can stay while you migrate"
  only works if the old templates move to another directory or prefix. The guide doesn't say so.
- **tessera-migrate:**
  - It does not convert the layout dialect, `sec:` or `@bean` calls. Those are expected.
  - It also leaves `t:attr` in its output without reporting it, although `th:attr` isn't
    supported.
  - It leaves `@{'/x/' + ${k}}` and `${#ids.prev(...)}` unconverted.
  - It doesn't touch `${@bean}` inside `@{}` and `#{}` arguments.
  - Its output can't compile until the whole layout is ported by hand.
  - It turns CRLF files into LF, which bloats diffs.
- **Fragments and forms.** A fragment called inside a `<select t:field>` doesn't see the form
  binding, so bound `<option>` fragments have to become separate templates. The guide doesn't
  mention this.
- **Undocumented behaviours:**
  - What `t:each` does over `null`. We guarded the loops with `t:if`.
  - That `[[...]]` serialisation includes `isX()` getters such as `isEmpty`. This does match
    Jackson.
- **Links.** There is no way to pass a computed query value unencoded. Thymeleaf wrote
  `?redirectViewName=GROUP@GROUP_A` raw, so to keep it those hrefs are built as plain
  (scheme-checked) strings.
- **Typing.** A model attribute whose type changes between pages (an enum on some, a String on
  others) can't be typed, so the controllers had to change.
  - Every `hasX()`, or method with arguments, needs a getter or a record.
  - `Object`-valued maps need converting.
- **Every page passes `layout` and `globalMessage` to the layout explicitly**, because fragments
  only see their parameters. This works but is verbose.
