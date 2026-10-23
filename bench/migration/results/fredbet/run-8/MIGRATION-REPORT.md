# Migration report: Thymeleaf → Tessera

## Summary

All 46 Thymeleaf templates are now Tessera templates, compiled by `javac` through the
`tessera-compiler` annotation processor. Thymeleaf and its dialects are gone: the
`spring-boot-starter-thymeleaf`, `thymeleaf-extras-springsecurity6` and
`thymeleaf-layout-dialect` dependencies, the `spring.thymeleaf.*` properties, and every `th:`,
`layout:` and `sec:` attribute.

Parity was checked with `tessera-migrate record`/`compare` over 131 requests. I recorded them
from the original app on a fresh database and replayed them against the new app, started the same
way. They cover:

- logged out and logged in as admin and as a plain user;
- empty and full lists;
- every form empty, with validation errors and after success;
- 403, 404 and 500 error pages;
- a language switch.

Result: 127 pages are `same` and 3 are `reordered`, all with the recorded status and final URL. The
3 reordered pages are validation messages of one field coming from a `Set`, so their order is
arbitrary. One page differs: `/runtimesettings/show` shows a registration code that the original
app generates at random on every start (see "Remaining differences"). Once that value is also masked, `compare` exits 0. Each run differs slightly in which pages come out `reordered`, depending on the order of those sets.

## Build

- `pom.xml`: added `dev.tessera:tessera-spring-boot-starter` (`${tessera.version}` =
  0.1.0-SNAPSHOT). The `maven-compiler-plugin` now has:
  - `parameters`;
  - `annotationProcessorPaths` listing `tessera-compiler` and `spring-boot-configuration-processor`
    (the latter had been picked up by discovery, which `annotationProcessorPaths` turns off);
  - `-Atessera.templates`;
  - `-Atessera.package=de.fred4jupiter.views`. I chose a package outside
    `de.fred4jupiter.fredbet` so the ArchUnit rules don't analyse generated code;
  - `-Atessera.messages=.../msgs/TranslationMessages_en`, so message keys are checked at build
    time.
- Java 21 and Spring Boot 3.5.6 are unchanged.

## What changed in the code

**Layout.** `_layout.html` is a fragment
`layout(String title, WebMessage globalMessage, Fragment head, Fragment content, Fragment scripts)`.
It replaces `layout:decorate` and `layout:title-pattern`. Each page replaces its `<html>` with a
call to it and passes:

- its `<head t:remove="tag">` (extra stylesheets);
- its `<body t:remove="tag">` (content);
- an optional `<pagescripts t:remove="tag">` element (page scripts).

They are passed with `~{::head}`, `~{::body}` and `~{::pagescripts}`.

**Bean references.** The old templates called Spring beans (`@webSecurityUtil`,
`@runtimeSettingsUtil`, `@groupAvailabilityUtil`, `@teamUtil`, `@languageUtil`,
`@languageIconUtil`, `@bettingWebUtil`) and used `sec:authorize`. These are now static
`@TemplateFunctions("fb")` in `web/view/ViewFunctions.java`, which delegate to the same beans
through `web/view/ViewBeans.java`. This also gives:

- `fb.hasAuthority(...)` / `fb.hasAnyAuthority(...)`;
- `fb.msg(key)` for computed message keys (`#{${key}}`);
- `fb.formatTemporal(...)` for locale-aware dates. Tessera's `temporals.format` only has English
  names, and the pages show German ones.

**Bootswatch theme stylesheet.** The URL is computed at runtime, but `link[href]` only accepts a
literal-path `@{}` link or a `TrustedUrl`. The layout therefore has one `<link>` per `Theme`
constant with a static href, each guarded by `t:if`. This avoids `TrustedUrl.unchecked`.

**View types.** `@TemplateView` was added to the commands, DTOs and entities the templates read.

**Controller and Java changes**, for values the templates used to take from outside the model or
that are not view types:

- `ApplicationErrorViewResolver`: the `timestamp` is passed as a String (`Date` isn't view-safe).
- `SystemInfoController`: `buildInfoMap` is a `LinkedHashMap<String,String>` instead of a
  `SortedMap<String,Object>`.
- `RankingController`: adds the `mode` request parameter to the model.
- `MatchController`: always adds `redirectViewName`.
- `MatchResultController`: adds the global (object-level) error messages.
- `InfoController` and `StandingsContainer` (new `GroupStandings`): data
  that the templates filtered or grouped is now prepared in Java.
- New getters `isMatchStarted()` and `isMatchFinished()`: templates can't call `hasX()` methods.
- The htmx endpoint `/runtimesettings/team-bundle` returned the fragment view name
  `admin/runtime_settings :: favouriteCountryOptions`, which Tessera can't resolve. It now returns
  the new template `admin/runtime_settings_team_options.html`.

**Info pages (rules, prices, misc).** These show admin-entered HTML, which Thymeleaf printed raw
with `th:utext`. They now go through `web/info/HtmlContentParser`, which parses the HTML into
`HtmlNode` records. The recursive template `info/html_content.html` renders only an allow-list of
elements and attributes, with all text and attribute values escaped by Tessera and URLs
scheme-checked.

What the old page rendered that is now dropped: `style` and `on*` attributes, `<script>`, and any
element or attribute that is not on the list (the text inside is kept). `data:` image URLs become
`about:invalid#tessera`. This is intended: it is stricter than before and contains no
`SafeHtml.unchecked`. With the recorded data the output is identical.

**Forms.** Replacements for places where Tessera's form support is narrower:

- `#ids.prev(...)` labels: radios get no `id`, so `id` and `for` are written explicitly (`roles1`,
  `level1`, `penaltyWinnerOne1`, ...).
- Radios bound to a `Set<String>` (user roles): `name`/`checked` are written with
  `lists.contains`.
- `MultipartFile` inputs: `name` is written by hand.
- `#fields.hasErrors('global')`: the controller passes the global error messages.

**Cleanup.** Removed the `spring.thymeleaf.cache` properties from `application-dev*.yml` and
updated the Thymeleaf mentions in comments and the README.

## Security

- No `SafeHtml.unchecked` and no `TrustedUrl.unchecked` anywhere.
- No new `permitAll`, CSRF unchanged (forms get the `_csrf` input automatically), no escaping
  turned off, no tests changed or disabled.

## Remaining differences (all judged correct)

- **Run-dependent text** in the original app too, masked with `compare --ignore`:
  - random UUID image keys;
  - the random registration code in runtime settings;
  - the current time (match-create default kick-off, runtime settings, system info, active users,
    last logins);
  - the error page timestamp;
  - the user home directory on the backup page;
  - the host and port in the language links (built from the request URL).
- **Validation messages for one field** are joined in `Set` iteration order, which differs between
  runs (`reordered`).
- **Not exercised by the recorded data:**
  - info HTML with markup outside the allow-list (see above);
  - the parent/child ranking links, which only show when that setting is on.

## Where Tessera or its documentation fell short

1. **Same-file named fragments can't see the page's variables, and the guide doesn't say so.**
   A `~{::content}` that selects `<t:block t:fragment="content">` fails with "unknown identifier"
   for every page parameter. Only selecting by tag name (`~{::body}`) renders with the caller's
   variables. The guide's layout example happens to use `~{::body}`, but section 4.8 says
   `~{::name}` is "a Fragment over the same-file fragment name", without warning that it loses
   the page variables. Since `t:block` can't be selected by tag name either, page scripts needed a
   made-up element (`<pagescripts t:remove="tag">`).
2. **No safe way to compute a resource URL.** `link[href]` / `script[src]` accept only a
   literal-path `@{}` or `TrustedUrl`, and the only way to build a `TrustedUrl` is `unchecked`. A
   runtime-selected stylesheet (the bootswatch theme) needed one `t:if` per enum constant. An
   allow-list factory (for example `TrustedUrl.ofResourcePath`) would help.
3. **No HTML sanitizer.** Legitimate rich text (summernote) can only become `SafeHtml` through
   `unchecked`. I had to write a parser plus an allow-list template.
4. **Functions are static, with no supported access to Spring beans.** Every `@bean` reference
   needs a static holder (`ViewBeans`) or a model attribute on every page. The guide recommends
   `@ControllerAdvice`, but that doesn't cover values like the navigation bar's.
5. **`auth` has no `hasAuthority`.** The app checks `PERM_*` authorities everywhere; this needed
   its own function.
6. **`temporals.format` is English-only and `numbers.*` has no locale.** Localized dates needed a
   custom function.
7. **Form gaps.**
   - Radios get no `id`, which is incompatible with `#ids.prev`.
   - No `checked` for radios or checkboxes bound to a collection.
   - No global errors except `fields.errors('*')`.
   - `MultipartFile` fields can't use `t:field`.
8. **View resolution can't select a fragment (`view :: fragment`),** which htmx endpoints commonly
   return. Such a fragment has to become its own template.
9. **The `tessera-migrate` converter turns `#{${expr}}` into a literal key `#{expr}`** instead of
   reporting it, and it guessed undefined variables (`param`, `child`, ...) as `t:params`.
10. **Concrete map types (`SortedMap`) are rejected** as parameter types. The guide mentions this,
    but the error points at the template, not at the controller that has to change.
11. **Maven doesn't recompile when only a template changes,** so you must run `mvn clean compile`.
    The guide says so, but it is easy to compare against stale classes.
12. **SpEL null semantics.** `null gt 0` was `false` in Thymeleaf. In Tessera it unboxes and throws
    a `NullPointerException` at render time, and neither the compiler nor the guide warns about it.

## Things not converted

None. Every page the application renders goes through Tessera. The non-HTML responses (PDF, Excel,
ICS, JSON, images) were never Thymeleaf views.
