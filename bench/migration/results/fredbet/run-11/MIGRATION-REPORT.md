# Migration report: Thymeleaf → Tessera

## Summary

All 46 Thymeleaf templates (pages, layout, navigation, fragments, error page) are now Tessera templates,
compiled by the `tessera-compiler` annotation processor during `javac`. Thymeleaf, the layout dialect and
the Spring Security dialect are removed. `mvn verify` on JDK 21 passes with all 117 existing tests unchanged.
`target/tessera-review.txt` lists 0 `SafeHtml.unchecked`/`TrustedUrl.unchecked` calls and one
`@TemplateFunction` (`teams.cssClass(Country)`, a pure enum → CSS class mapping). The startup warning
`Tessera: another template engine is still configured` does not appear.

## What changed

### Build and configuration
- `pom.xml`: removed `spring-boot-starter-thymeleaf`, `thymeleaf-extras-springsecurity6` and
  `thymeleaf-layout-dialect`. Added `tessera-spring-boot-starter`. Configured `maven-compiler-plugin` with
  `annotationProcessorPaths`, which lists `spring-boot-configuration-processor` (discovery is off once paths are
  listed) and `tessera-compiler`, plus `-Atessera.templates/package/messages`. Added `tessera-maven-plugin`
  (`check`, `review`). `<release>` was removed from the compiler plugin, so `java.version` (21) drives it.
- `tessera.messages` points at the `_en` files of `TranslationMessages`, `ValidationMessages` and
  `TeamKey`, because there are no base `.properties` files. Every `#{key}` is checked at build time.
- Removed `spring.thymeleaf.cache` from `application-dev.yml` and `application-devpostgres.yml`. Removed the
  Thymeleaf mentions from Javadoc in `Application`, `WebSecurityUtil` and `RuntimeSettingsUtil`, and from the
  README.
- `mvn dependency:list` before and after: only Thymeleaf, layout-dialect and their transitive dependencies
  (groovy, ognl, attoparser, unbescape, javassist) went away, and the application uses none of them.

### Shared layout
- `_layout.html` is a fragment `layout(String title, LayoutModel layout, WebMessage globalMessage,
  Fragment head, Fragment content, Fragment scripts)`. Every page replaces itself with it and passes its
  `<head>`/`<body>` (with `t:remove="tag"`) and a `<page-scripts>` block. This replaces `layout:decorate`
  and `layout:title-pattern`.
- Values the old templates read from beans (`@webSecurityUtil`, `@runtimeSettingsUtil`,
  `@groupAvailabilityUtil`, `@languageUtil`, `@languageIconUtil`) now come from
  `web/layout/LayoutModel` (a record). `web/layout/LayoutModelInterceptor` adds it to every model that renders
  a view (not to redirects or forwards), and `web/layout/LayoutConfig` registers the interceptor. It is a
  separate configuration class because registering it in `MvcConfig` created a bean cycle through
  `messageSource`.
- The bootswatch theme stylesheet is a `t:switch` over the `Theme` enum with static `href`s. A `<link href>`
  accepts only a literal `@{}` link or a `TrustedUrl`, and `@{}` would have added a webjar version the old
  page didn't have.
- `sec:authorize` became `t:if="auth.hasAuthority(...)"` / `auth.hasAnyAuthority(...)`.
- The navigation's malformed `</ul>` (a missing `</li>`) is closed explicitly. The browser DOM is identical.
- `error.html` gets a `timestampText` (the `Date.toString()` the old page printed) from
  `ApplicationErrorViewResolver`.
- `WebMessageUtil.WebMessage` is annotated `@TemplateView`.

### Pages (controllers now provide what the templates used to read from beans or the request)
- `@TemplateView` was added to the command, DTO and entity classes the templates read: `Match`, `Bet`,
  `ExtraBet`, `SessionTracking`, `RuntimeSettings`, `PointsConfiguration`, `ExtraPointsConfiguration`,
  `ChartData`, `UsernamePoints`, `TeamStandings`, `Statistic`, and the web `*Command`/`*Dto` classes. `AppUser`
  (a `UserDetails`) was not annotated: the last-logins page gets a `LastLogin` record instead.
- Message keys computed from data (`#{${group.titleMsgKey}}`, `#{'msg.'+role}`, `teamBundle.*`, team names,
  info titles, standings group titles) are resolved in Java and passed as strings or maps. See `ViewLabels`,
  `RoleOption`, `StandingsGroupView` and the label maps in the controllers.
- Profile images: controllers pass `Map<String,String> profileImageKeys` (username → key) to
  `fragments/profile_image`.
- `@teamUtil.cssClassFor(...)` became the template function `teams.cssClass(...)` (`web/util/TeamFunctions`).
- `param.mode` on the ranking page became a model attribute `requestedMode`. `isRoleSelectionDisabledForUser`
  is computed in `UserController` on every path that renders the create/edit forms.
- Stored rich text (rules/prices/misc) is rendered with `html.sanitizeRich(...)` instead of raw `th:utext`.
- The `MatchCommand.isMatchStarted()` getter wraps `hasMatchStarted()`. `SystemInfoController` passes its build
  info as `Map<String,String>`. `MatchController` passes `redirectViewName` as a `String`.

## Page parity

The pages list (`.tessera/pages.txt`, 111 requests) covers every view: login (including the error state),
every match list, match create/edit/result with and without validation errors, bet create with errors and
success, other users' bets, extra bets, ranking (both modes, with data), standings, statistics, points
frequency, point course, info pages and their editors, gallery, image upload and groups, user list, create
(with errors) and edit, every admin page, profile pages (with errors), 404/500 error pages, and switching the
language to English and back.

Result: every page is `same` or `reordered`, apart from these:

1. **Values that change on every run, so the original differs from itself.** These are kick-off default
   = now on `/match/create`, the current time and the random registration code on `/runtimesettings/show`,
   login times on active users / last logins, build/current time on `/systeminfo`, the random UUID profile
   image keys, the error-page timestamp, and `${user.home}` on `/backup/show` when the apps start with
   different homes.
2. **`/pointcourse/show`:** in the inline chart script, `var chartData = [[${chartData}]];` now comes before
   `var ctx = $('#myChart');`. Tessera requires every `[[...]]` declaration at the top of the script. The
   JavaScript behaves the same.
3. **`/info/rules`:** the stored default rules contain `<li value="NaN">`. The sanitizer drops the invalid
   `value`. Browsers ignored it anyway, so the numbering is unchanged.
4. **`/user` (list) and `/gallery/show`:** `data-lightbox="<uuid>"` is now `data-lightbox="<userId>"`, and
   `data-gallery="#blueimp-gallery-<group name>"` is now the group index. Tessera allows only numbers,
   booleans or same-origin links in non-standard attributes. The values stay unique per group, which is all
   the plugins compare. `fragments/profile_image` (ranking, statistic, other bets) now writes the gallery link
   instead of the bare key, so those pages will show the same one-attribute difference once they list
   users with profile images. None of the recorded pages renders that fragment with an image.
5. **`reordered` pages:** validation messages on one field come from a `HashSet`, so their order varies.

## What could not be converted 1:1, and why

- Raw stored HTML: now sanitized. The `img-responsive` class the controller adds to `<img>` (Bootstrap 3, no
  effect under Bootstrap 5) and `data:` images inserted by summernote are dropped, and links get
  `rel="nofollow noopener"`.
- `data-lightbox` / `data-gallery` string keys: see item 4 above.
- Script layout: see item 2 above.
- The registration page is disabled in the default settings, so it compiles but no recorded run renders it.

## Where Tessera or its documentation fell short

- **No way to reproduce a plain string in a `data-*` attribute.** A lightbox or gallery group key that is
  a UUID or a group name can't be written unchanged, so `compare` necessarily reports `differs` for such
  pages, while the done-criteria require `same`. The guide's recipes acknowledge the diff, but nothing lets
  an inert string id through.
- **`[[...]]` must open the script**, so scripts with a statement before the data declaration can't stay
  byte-identical.
- **`HtmlPolicy.builder()` can't allow `<a>`.** `a` is not in `HtmlPolicy.ELEMENTS`, so a custom policy
  that keeps links is impossible; only the built-in `sanitize` / `sanitizeRich` keep links.
- **An in-place `t:fragment` renders nothing on its own page.** Thymeleaf rendered such an element in place.
  The guide doesn't say Tessera doesn't, and it compiled without a warning; only `compare` caught the empty
  `<select>` on the runtime settings page.
- **Links with a `null` query parameter** write `?mode=` where Thymeleaf wrote `?mode`. A conditional was
  needed for parity.
- **`?:` requires both branches to have the same type** (`Integer ?: '?'` fails), and methods that aren't
  getters (`hasMatchStarted()`) can't be called, so small wrappers were needed.
- **Message keys computed from data have no template form.** Functions can't see the `MessageSource` or
  beans, so every such key needed a Java-side map. This was the bulk of the controller changes.
- **`tessera-migrate` conversion:** the converter carries an old `th:if` and a `sec:authorize` into two `t:if`
  attributes on the same element (a compile error to fix by hand). It also leaves `layout:` dialect markup for
  manual porting, so every page needed the layout restructuring.
- **`compare --ignore` applies after date normalization** (dates show up as `TODAY` in the diff), so masks
  written against the raw page didn't match the clock-dependent values. The guide doesn't say in which order
  the two happen.
- **Missing base message bundle:** with only locale files (`_en`, `_de`, ...), `tessera.messages` must name
  the `_en` files. Keys that exist only in another locale wouldn't be checked.
