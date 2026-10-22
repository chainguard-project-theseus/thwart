# Migration report: Thymeleaf → Tessera

All 46 Thymeleaf templates (plus the layout dialect and the Spring Security dialect) were ported to Tessera
0.1.0-SNAPSHOT. Every page is now rendered by compiled Tessera templates; Thymeleaf, `thymeleaf-layout-dialect` and
`thymeleaf-extras-springsecurity6` are gone, as is the `spring.thymeleaf.*` configuration.

## Verification

- `mvn clean package` passes: 122 tests, 0 failures, 0 errors, 0 skipped. That is all existing tests unchanged, plus
  5 new ones in `RichTextParserUT`. No test asserted on Thymeleaf details, so none had to be edited.
- **Old vs. new HTML.** Before the migration the original app was packaged, and a capture script logged in and
  created deterministic data through the app's own forms. That data includes `<script>alert(1)</script> &"'` in team
  names, stadium, user name, image group and info text. The script then fetched 190 pages, de and en: every GET page,
  pages with ids, the error page, 404 and 500, the htmx fragment, and about 35 invalid form posts with validation
  errors. The same script ran against the migrated app, and both captures were canonicalized (attribute order,
  whitespace, CSRF tokens and timestamps normalized) and diffed.
  - **184 of 190 pages are identical**, apart from one extra `</li>` in the navigation (see below).
  - **The remaining 6 differ only as intended** (see "Intentional differences").

## What changed

### Build (`pom.xml`)
- `spring-boot-starter-thymeleaf`, `thymeleaf-extras-springsecurity6` and `thymeleaf-layout-dialect` were replaced by
  `tessera-spring-boot-starter`.
- `tessera-compiler` was added as an annotation processor, next to `spring-boot-configuration-processor`, which had to
  be listed explicitly once `annotationProcessorPaths` existed. The compiler options are `-Atessera.templates`,
  `-Atessera.package=de.fred4jupiter.fredbet.templates`, `-Atessera.messages=.../msgs/TranslationMessages_en`,
  `-parameters` and `-Xmaxerrs 1000`.
- Test compilation runs with `<proc>none</proc>` (see "Shortcomings" #2).
- `spring-boot-webmvc` is excluded from the starter (see "Shortcomings" #1).

### Shared Java glue (new package `de.fred4jupiter.fredbet.web.view`)
- **`LayoutView`** (`@TemplateView`) is added to every model as `layout` by `LayoutModelAdvice` (a
  `@ControllerAdvice`), and by `ApplicationErrorViewResolver` for error views.
  - It replaces every `${@bean.method(...)}` call from the old templates: runtime settings, logged-in state,
    navigation groups, H2 console, profile image keys, language URLs and icons, team CSS classes, translated team
    names, `isBettable`, and role-selection rules.
  - Values are computed lazily at render time, so they behave exactly like the old bean calls.
  - Bean methods that took an argument became lazily computed read-only maps, read in templates as
    `layout.profileImageKeys[userName]` (class `LazyMap`).
- **`SecurityFunctions`** (`@TemplateFunctions("sec")`) provides `sec.hasAuthority(..)` and
  `sec.hasAnyAuthority(..)`, replacing `sec:authorize="hasAuthority(..)"`. The starter's `auth` namespace only has
  role checks, but the app checks `PERM_*` authorities.
- **`NavGroup`** holds the matches-menu groups with already-translated titles, replacing the dynamic key
  `#{${group.titleMsgKey}}`.
- **`MvcConfig`** registers `ResourceUrlEncodingFilter`. Spring Boot used to register it together with Thymeleaf.
  Without it, the webjar links lose their version segment (`/webjars/jquery/3.7.1/...`).

### Layout
- **Layout dialect.** It was replaced by a layout fragment:
  `_layout :: layout(LayoutView layout, WebMessage globalMessage, String title, Fragment head, Fragment content, Fragment scripts)`.
  - Each page replaces its `<html>` with this layout. It passes:
    - its title;
    - a same-file `headExtras` fragment, for the page-specific CSS that the layout dialect used to merge into
      `<head>`;
    - its content, as `~{::main}` on a `<main t:remove="tag">` element (the old layout dropped the content div's tag
      too);
    - a same-file `scripts` fragment.
  - The layout dialect's `$LAYOUT_TITLE - $CONTENT_TITLE` pattern became `|FredBet - ${title}|`.
- `_navigation`, `fragments/alert`, `fragments/language_selector`, `fragments/delete_confirm` and
  `fragments/profile_image` are typed fragments.

### Model and controllers (only what the templates need)
- **`@TemplateView` annotations** were added to the entities `AppUser`, `Bet`, `ExtraBet`, `Match` and
  `SessionTracking`, to `RuntimeSettings`, `PointsConfiguration` and `ExtraPointsConfiguration`, and to all command
  and DTO classes the pages read.
- **New getters** `MatchCommand.isMatchFinished()` and `isMatchStarted()`.
- **New model data:**
  - `UserController`: `roleLabels` and `roleTooltips`, replacing dynamic message keys.
  - `StandingsController`: `groupStandings` (record `GroupStandings`), replacing a method call with an argument and a
    dynamic key.
  - `PointCourseController`: `chartDataJson`.
  - `InfoController`: `richText`.
  - `MatchResultController`: `globalErrors` (see "Shortcomings").
  - `RankingController`: puts `mode` in the model, replacing `${param.mode}`.
- **`SystemInfoController`** passes a `TreeMap<String, String>`, because `Object` values are not view-safe.
- **`MatchController`** always passes `redirectViewName` as a String; before it was sometimes an enum and sometimes a
  String.
- **`RuntimeSettingsController /team-bundle`** returned the fragment view name `"admin/runtime_settings :: ..."`. It
  now returns the small template `admin/runtime_settings_favourite_country_options`.
- **`PointsConfigController.saveConfig`** got `@ModelAttribute("pointsConfig")`, so that the binding result matches
  the form object name (see below).
- **`ApplicationErrorViewResolver`** converts the Boot error attributes to Strings, using `String.valueOf`, which
  prints the same as before, and adds `layout`.

## Intentional differences (the 6 non-identical pages)

1. **Rules, prices and misc text** (`info_misc`, `post_info_misc`).
   - **Before:** the pages printed admin/user-edited HTML with `th:utext`. The capture proved this was a stored XSS:
     the injected `<script>` ran on the misc page.
   - **Why it had to change:** `SafeHtml.unchecked` is not allowed, and Tessera has no sanitizer.
   - **Now:** the stored HTML is parsed by a small allow-list parser (`web/info/RichTextParser`, tested in
     `RichTextParserUT`) into a `RichTextNode` tree. The fragment `fragments/rich_text` renders the tree with one
     literal element per allowed tag, so all text and attributes are escaped by Tessera and URLs are checked by
     Tessera.
   - **Result:**
     - Rules page (normal formatted text): identical output.
     - `<script>`, `<style>` and `<iframe>` are dropped with their content.
     - Event handlers and `javascript:` URLs are removed.
     - Unknown tags are unwrapped.
     - **Inline `style` attributes are lost** (for example editor alignment and colours), because Tessera forbids
       computed `style` values.
   - The PDF export still uses the stored text, as before.
2. **Point course chart** (`pointcourse`, `en_pointcourse`).
   - **Before:** Thymeleaf serialized the `ChartData` record into the script as an object literal.
   - **Why it changed:** Tessera's script context only serializes strings, numbers, collections and String-keyed
     maps of those; records and beans are not supported.
   - **Now:** the controller serializes the record with Jackson, and the script does
     `JSON.parse([[${chartDataJson}]])`. The resulting JS object is identical; the page source differs.
3. **Points config with invalid input** (`post_config_points_invalid`, `en_...`).
   - **Before:** the old page crashed halfway through rendering (HTTP 200 with a truncated body). The binding result
     was named `pointsConfiguration`, but the template used `pointsConfig`.
   - **Now:** Tessera's `t:object` must name the model attribute, so the attribute is now named explicitly. The page
     renders completely, with its field errors.

**Present on every logged-in page:** `_navigation.html` had an unclosed `<li class="nav-item dropdown">` (the admin
menu). Tessera requires well-formed markup, so a `</li>` was added before `</ul>`. A browser closes the element at the
same place, so the DOM is unchanged.

## Not converted / left as is

- **`MvcConfig` `/error` view controller** (`registry.addViewController("/error")`): left unchanged. It is shadowed by
  Spring Boot's `BasicErrorController`, as before, so it never renders. If it ever did, it would provide no `layout`
  and the page would fail. Removing it is a sensible follow-up.
- **Thymeleaf natural-template prototypes:** kept as static text.
- **Static `onclick`/`onchange` attributes:** kept unchanged; Tessera only forbids expressions in them.

## Where the guide or Tessera fell short

1. **The starter has a broken dependency for Spring Boot 3.** `tessera-spring-boot-starter` depends on
   `org.springframework.boot:spring-boot-webmvc:4.1.1` (Boot 4). Next to Boot 3.5, this puts two
   `ErrorMvcAutoConfiguration`s on the classpath, and startup fails with a `BeanDefinitionOverrideException`
   (`errorAttributes`). The fix was to exclude that artifact; the starter's own classes only use Boot 3-compatible
   annotations. The guide does not state which Boot version is supported.
2. **The annotation processor also runs during `testCompile`.** There, `@TemplateFunctions` classes from
   `src/main` are not in the same javac run, so every template fails with "unknown function namespace". The guide's
   build setup does not mention this; the fix was `<proc>none</proc>` on `default-testCompile`.
3. **`ResourceUrlEncodingFilter` was registered by Boot's Thymeleaf auto-configuration.** Removing Thymeleaf silently
   changes webjar URLs. The guide does not mention it.
4. **javac stops after 100 errors,** which hides errors in later templates. The guide should recommend `-Xmaxerrs`.
5. **No markup sanitizer and no safe way to output stored rich text.** `SafeHtml` only offers `escape`,
   `of(template)` and `unchecked`. A whole parser plus a recursive fragment was needed. (Recursive fragment calls
   work, but that is not documented.)
6. **Script JSON cannot serialize records or `@TemplateView` beans.** This is not documented.
7. **Layout dialect gaps:**
   - There is no way to select a `<t:block>` (`~{::t:block}` is a syntax error).
   - Head merging and title patterns are not covered by the "layouts" section; the `<main t:remove="tag">` and
     `headExtras` fragment pattern had to be invented.
   - The guide does not mention that same-file fragments can take arguments when passed as layout arguments
     (`~{::scripts(x)}`).
8. **Optional `Fragment` arguments:** there is no documented way to pass "nothing"; an empty fragment
   (`fragments/alert :: empty`) is used instead.
9. **No `#ids.seq` / `#ids.prev`:** radios and checkboxes with `t:field` get no generated ids. The ids
   `penaltyWinnerOne1`, `level1`, ... are now written by hand.
10. **`t:field` quirks that are not documented:**
    - On password inputs it drops even a static `value` (Thymeleaf wrote `value=""`).
    - On radios/checkboxes bound to a collection (`Set<String> roles`), it does not compute `checked`.
    - A blank `<option>` inside `<select t:field>` gets `selected` when the field is null (Thymeleaf did not do this).
    - It is a build error on `type="file"` inputs (`MultipartFile` is never view-safe).

    Each has a workaround in the templates (an explicit `name`/`value`/`checked`, or the option rendered through a
    fragment).
11. **No equivalent of `th:errors="*{global}"`** (object-level errors). They are now passed as a `globalErrors`
    list by the controller.
12. **`temporals.format` ignores the request locale** and always formats in English (`June` instead of `Juni`).
    `LayoutView.dateTimeFormats[pattern][value]` formats in the current locale instead.
13. **`-Atessera.messages` checks a single bundle.** Keys from the app's other bundles (`TeamKey`,
    `ValidationMessages`) cannot be used as `#{...}`; they are looked up at runtime through `layout.messages[key]`.
14. **Fragment view names (`"view :: fragment"` returned from a controller) are not supported,** and this is not
    documented.
15. **The resource-URL context forbids computed stylesheet URLs,** and `TrustedUrl` has only `unchecked`. The
    bootswatch theme link is now a literal `@{/webjars/bootswatch/{theme}/bootstrap.min.css(...)}`, with a separate
    literal link for the default theme.
16. **`tessera-migrate` issues:**
    - It turned dynamic keys like `#{${x.titleMsgKey}}` into the literal key `#{x.titleMsgKey}` without reporting
      them.
    - It left Thymeleaf's unquoted link tokens (`@{/ranking(mode=mixed)}`), which Tessera reads as identifiers.
