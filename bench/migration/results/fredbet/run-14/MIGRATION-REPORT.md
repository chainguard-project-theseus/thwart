# Migration report: Thymeleaf → Tessera

FredBet's server-rendered views (46 Thymeleaf templates using the Spring Security extras and the
layout dialect) are now compiled Tessera templates. Thymeleaf, its extras, the layout dialect and
their configuration are gone. Nothing else renders HTML.

## Result

- `mvn -B verify` (JDK 21, the project's `java.version`) passes: 118 tests. That is the 117
  existing tests, none changed, plus one new one.
- `tessera-migrate compare` against a recording of the original app over 96 requests (`pages.txt`
  covers every page logged out and as admin, forms empty, with validation and conversion errors
  and after success, plus the 404 and 500 error pages):
  - Every request has the recorded status and final URL.
  - Every page is `same` or `reordered`, except for the differences listed below. Each of those is
    either a value that changes on every run or the documented effect of a Tessera rule.
  - At startup the app logs neither `another template engine is still configured` nor any other
    Tessera warning.
- Security:
  - `SecurityConfig` is unchanged: no new `permitAll`, and CSRF is still on.
  - No escaping is bypassed. The one raw output (stored rules/prices/misc HTML) now goes through
    `html.sanitizeRich`.
  - Inline event handlers moved into script files.
  - Tessera's CSP nonce/header is active.

## What changed

**Build (`pom.xml`)**
- Removed `spring-boot-starter-thymeleaf`, `thymeleaf-extras-springsecurity6` and
  `thymeleaf-layout-dialect`.
- Added `tessera-spring-boot-starter`, plus `tessera-compiler` as an annotation processor (listed
  together with `spring-boot-configuration-processor`, since `annotationProcessorPaths` turns off
  discovery).
- Added `tessera-maven-plugin:check`.
- Generated package: `de.fred4jupiter.views`. It is outside the ArchUnit-scanned base package.
- `tessera.messages` points at the `_en` bundles. There are no base `.properties` files.
- Removed `spring.thymeleaf.cache` from `application-dev.yml` and `application-devpostgres.yml`.
- Updated the README and Javadoc mentions of Thymeleaf.

**Shared view model**
- `web/view/SiteModel` (`@TemplateView`) replaces every `@bean` call the layout, navigation and
  pages made: `@webSecurityUtil`, `@runtimeSettingsUtil`, `@groupAvailabilityUtil`,
  `@languageIconUtil` and `@languageUtil`. It also carries the flash `globalMessage`.
  - Its values are computed lazily during rendering, as the old bean calls were.
  - `site.profileImageKeys[username]` replaces `getUserProfileImageKeyFor(...)`.
- `web/view/SiteModelInterceptor` adds the model as `site` to every rendered view, error views
  included. It is registered in `MvcConfig` through an `ObjectProvider`, which avoids a bean cycle
  with `messageSource`.
- `web/view/ViewFunctions` (`fb.dateToString`) prints Boot's error timestamp exactly as Thymeleaf
  printed the `java.util.Date`.

**Layout**
- `_layout.html` is a fragment `layout(title, head, content, scripts, site)`. Every page replaces
  its `<html>` with it, passing `~{::body}` (`t:remove="tag"`), its extra `<link>`s and its scripts.
  This replaces `layout:decorate` and `layout:title-pattern`.
- `_navigation.html` and `fragments/*` became typed fragments.
- `sec:authorize` became `auth.hasAuthority(...)` / `auth.hasAnyAuthority(...)`.

**Pages (38)**
- Converted, with `@TemplateView` on the commands, DTOs and entities the templates read.
- Controllers now put into the model what templates used to compute:
  - translated texts for message keys built from data: group titles, roles, team bundles, info
    titles;
  - flag CSS classes;
  - `mode` (was `${param.mode}`);
  - `roleSelectionDisabled`, `bettable` and the extra-points config;
  - view records for statistics, standings and extra bets.
- Main controller changes:
  - `RuntimeSettingsController`: the htmx partial `admin/runtime_settings :: favouriteCountryOptions`
    is now a typed fragment.
  - `SystemInfoController`: `buildInfoMap` is now a `Map<String,String>`.
  - `MatchController`: `redirectViewName` is now always a String.
- New files: `web/user/RoleOption.java`, and `test/.../web/view/PageRenderingIT.java` (renders
  `/login` through the real resolver; the app had no view-rendering test).
- Inline `onclick`/`onchange` handlers became `data-action`/`data-change` attributes, with
  delegated listeners in `static/js/pages/*.js`: `edit_match`, `bet_and_result`, `random_dice`,
  `ranking_list`, `runtime_settings`, `crop_image` and `image_group`.

## Remaining page differences (all intended)

1. **Clock values:** error page timestamps, last-login and active-user times, system info build
   and current time, the default kick-off time, and the random registration code on runtime
   settings. These differ between any two runs of the original too.
2. **Inline event handlers removed** (match edit, bet edit, match result, ranking, runtime
   settings, image group, crop image). Tessera rejects every `on*` attribute, static or computed.
   The behaviour is the same, now attached from JS.
3. **`data-lightbox` value:** it was the bare image key and is now `/gallery/show/<key>`. That
   affects ranking, statistic, other bets, extra bets of others, the user list and image upload.
   Tessera allows only neutral values (numbers, same-origin links) in computed `data-*`. Lightbox
   only compares the value, and each image still gets its own group.
4. **`data-gallery` on the gallery page:** it was `#blueimp-gallery-<group>` and is now the group
   index. Same rule. The page uses the vanilla blueimp build through `gallery.js`, which never reads
   the attribute.
5. **pointcourse inline script:** the `chartData` declaration moved above `var ctx` (script values
   must be top-of-script declarations), and JSON escapes `<` as `<`. The JavaScript value is
   identical.
6. **Rules/prices/misc rich text** now goes through `html.sanitizeRich`. With the seeded rules this
   only drops an invalid `<li value="NaN">`. Summernote tables, inline `style` and the
   `img-responsive` class the controller adds to images would now be dropped; the HTML policy
   builder can't allow them.
7. **Validation messages** come out `reordered` (they come from a `HashSet`, as before).
8. **Bootswatch theme link:** for a non-default theme the `<link>` is now built with `@{...}`, so
   the webjar filter may add the version segment. The default theme's link is static and unchanged.

## Not converted

None. Pre-existing app bugs were reproduced, not fixed:
- `POST /config/points` with invalid input crashes in the original too (no `pointsConfig` model
  attribute). My `pages.txt` posts valid values instead.
- `/backup/show` returns 500 once the points config has been saved, because both settings share
  config id 2.
- Startup can race on the `RUNTIME_SETTING` insert.

## Where Tessera or its documentation fell short

- **`tessera-migrate` conversion:** it doesn't convert the layout dialect (`layout:decorate`,
  `layout:fragment`, `layout:title-pattern`) or `sec:authorize`; it only reports them. The guide
  (section 3) says it rewrites "layout markup".
- **Neutral `data-*` values:** no recipe keeps a string-valued plugin attribute byte-identical. So
  pages that used `th:attr="data-lightbox=..."` or `data-gallery` can never compare `same`.
  Likewise, removing inline handlers always shows up as a diff. `compare` has no way to mark a
  documented recipe difference as accepted, so you need `--ignore` or a written justification.
- **htmx partials in a `<select>`:** a `t:fragment` can't sit inside `<select>`, and a fragment
  can't see the form object. The htmx partial needed restructuring and explicit `t:selected`. The
  guide doesn't cover this pattern.
- **Hidden-input `t:field`:** the guide doesn't say whether `t:field` on a hidden input writes the
  bound value (it does, as Thymeleaf did).
- **Sanitizer limits:** the HTML policy can't allow tables, `style` or arbitrary classes, so WYSIWYG
  editor output (summernote) loses formatting the old raw output kept.
- **Script declarations:** the top-of-script rule forces reordering existing inline scripts.
- **Message bundles without a base file:** `tessera.messages` must point at a locale file (`_en`).
  Keys present only in other locales aren't detected.
- **Bean cycle:** a `HandlerInterceptor` that needs `MessageSource` cannot be built eagerly in a
  `WebMvcConfigurer` that defines `messageSource`. The guide's `@ControllerAdvice` advice doesn't
  cover views from `ParameterizableViewController` or error views, which is why I used an
  interceptor.
