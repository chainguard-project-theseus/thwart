# Migration report: Thymeleaf → Tessera

All 46 Thymeleaf templates now run on Tessera 0.1.0-SNAPSHOT. Thymeleaf, `thymeleaf-extras-springsecurity6`,
`thymeleaf-layout-dialect`, the old templates and the `spring.thymeleaf.*` configuration are gone.

## Results

- **Build.** `mvn clean verify` passes: 119 tests, 0 failures. That is the 117 existing tests, none changed or
  disabled, plus 2 new ones.
- **Parity.** I recorded the original app with `pages.txt` (155 requests) and compared my app against it, both
  started on port 8080 from a fresh H2 database. The result is 150 `same`, 2 `reordered` and 3 `differs`.
  - **Reordered:** the validation messages on the empty change-password and registration POSTs come back in a
    different order. The Bean Validation order is not fixed, so this is harmless.
  - **Differs, `/runtimesettings/show` (2 pages):** the registration code is random for each new database, so it
    differs between any two runs, even of the original app.
  - **Differs, `/pointcourse/show` with data (1 page):** see "What I could not convert" below.
- **Ignore patterns.** I ran compare with these narrow `--ignore` patterns for values that change on every start
  or with the clock:
  - profile-image keys (random UUIDs): `[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`
  - the error page timestamp (`Date.toString()`): `[A-Z][a-z]{2} [A-Z][a-z]{2} [0-9]{2} [0-9:]{8} [A-Z]+ [0-9]{4}`
  - clock times (system info, last logins, the default kick-off time): `TODAY[ T][0-9:.]+(Z| GMT\+0000| \+0000)?`
- **`pages.txt` change.** I removed one request, `POST /config/points` with an empty field. In the original app
  it crashes partway through the response (Thymeleaf: "Neither BindingResult nor plain target object"), and the
  recorder gets no response at all, so there is nothing to compare against.

## What changed

**Build (`pom.xml`)**
- Removed the Thymeleaf starter, the Spring Security extras and the layout dialect.
- Added `tessera-spring-boot-starter`.
- Added `tessera-compiler` as an annotation processor, next to `spring-boot-configuration-processor`, which has to
  be listed explicitly now. The processor gets `-Atessera.templates` and
  `-Atessera.package=de.fred4jupiter.fredbet.templates`, plus `parameters=true`.
- Annotation processing is off for test compilation.
- Added `jsoup` (see "Rich HTML" below) and `spring-security-test` (test scope).

**Shared infrastructure**
- **`templates/_layout.html`:** the layout is now the fragment
  `layout(Fragment content, String title, Fragment head, Fragment scripts, WebMessage globalMessage)`.
  - Each page replaces its root with it and passes `~{::div}` (the content div, marked `t:remove="tag"`), the
    title, and optional `pagehead`/`pagescripts` fragments.
  - The title is built as `FredBet - <title>`, as `layout:title-pattern` did.
  - The bootswatch stylesheet is chosen with a `t:switch` over literal `<link>` tags, one per `Theme` constant. A
    computed `link[href]` would need `TrustedUrl.unchecked`, which isn't allowed.
- **`web/view/ViewFunctions.java` (`@TemplateFunctions("fb")`) and `ViewSupport`:** static functions that replace
  the old template features:
  - every `${@bean.method()}` call (settings, group availability, team and language CSS classes, language URLs,
    profile-image keys, and so on);
  - `sec:authorize="hasAuthority(...)"`, as `fb.hasAuthority` / `fb.hasAnyAuthority`. Tessera's `auth` namespace
    has only `hasRole`;
  - dynamic message keys `#{${...}}`, as `fb.msg`. These keys are not checked at build time;
  - `#temporals.format`, as `fb.formatDate`. It uses the request locale, as Thymeleaf did; the built-in
    `temporals` uses English names only.
- **`config/ErrorPageConfig`:** adds `timestampText` (a String) to Boot's error attributes, because `Date` is not
  view-safe.
- **`config/MvcConfig`:** registers Spring's `ResourceUrlEncodingFilter`. Thymeleaf's auto-configuration used to
  register it, and without it the webjar links lose their version segments (`/webjars/jquery/3.7.1/...`).
- **`@TemplateView`** is added to the command, DTO and entity classes the templates read (`AppUser`, `Match`,
  `Bet`, `ExtraBet`, `RuntimeSettings`, the `*Command` classes, and others). `MatchCommand` and
  `AbstractMatchHeaderCommand` get `isX()` wrappers for `hasMatchFinished()` / `hasMatchStarted()`.

**Controllers (values the templates can no longer compute)**
- **`SystemInfoController`:** passes a `LinkedHashMap<String,String>`. The `SortedMap<String,Object>` it had
  before is rejected by Tessera.
- **`RuntimeSettingsController`:** the htmx endpoint returns the new template
  `admin/runtime_settings_favourite_countries`. A view name like `page :: fragment` falls through to other
  resolvers.
- **`StandingsController`:** builds `StandingsGroupView` rows with their CSS class.
- **`PointCourseController`:** passes the chart data as a JSON string.
- **`RankingController`:** passes `modeParam` (a request parameter).
- **`MatchController`:** `redirectViewName` is now always a String. It used to be an enum on some pages and a
  String on others.
- **`MatchResultController`:** passes the object-level `globalErrors`.
- **`InfoController`:** passes the parsed rich text (next point).

**Rich HTML (rules, prices, misc)**
- These pages printed editor HTML with `th:utext`. Tessera prints raw HTML only from a `SafeHtml`, and
  `SafeHtml.unchecked` is not allowed.
- `RichTextParser` parses the stored HTML with jsoup into `HtmlNode` records, keeping only an allow-list of tags
  and attributes.
- `fragments/rich_html.html` renders the tree with a recursive fragment that switches over literal tag names.
  Text is escaped, and URLs go through Tessera's URL checks.

**Tests and cleanup**
- **New test:** `TesseraViewRenderingIT` renders pages through the real view resolvers. It checks the layout and
  the CSRF input, and that `<script>` in user data (team name, stadium) comes out escaped.
- **Docs and config:** updated the README, the Javadoc that mentioned Thymeleaf, and the dev profiles (removed
  `spring.thymeleaf.cache`).

## What I could not convert exactly, and why

- **`/pointcourse/show` chart data.**
  - Thymeleaf inlined the chart object as a JS literal. In a script, Tessera's `[[...]]` serializes only values of
    one JSON type: it cannot serialize records, beans, or maps whose values mix types. A dataset
    `{"label":"admin","data":[0]}` mixes a string and a list.
  - The page now runs `JSON.parse("…")` on the same JSON produced by Jackson. The chart data is identical, but the
    script text differs, so compare reports it.
- **Rich HTML that the allow-list drops.**
  - `style` attributes are dropped, because Tessera rejects expressions in `style`. So are `<font>`,
    `<script>`/`<iframe>`/form elements and `data:` images, which Tessera turns into `about:invalid#tessera`.
  - The default rules text and the pages in `pages.txt` render the same as before. Content that relied on inline
    styles loses that formatting. This is a deliberate security trade-off.
- **Image uploads and other multipart forms** are not in `pages.txt`: the tool can only send urlencoded POSTs.
  - Those forms have hand-written `name` attributes, because `t:field` cannot bind `MultipartFile`.
  - The pages that show the forms are compared.

## Where the guide or Tessera fell short

1. **No `hasAuthority`.** The whole app uses `PERM_*` authorities, so I needed my own function for every
   `sec:authorize`.
2. **No sanctioned way to output trusted HTML or a computed stylesheet URL.** The only factories are
   `SafeHtml.unchecked`, `TrustedUrl.unchecked` and `SafeHtml.of(template)`; there is no sanitizer and no
   `TrustedUrl` from a literal set.
   - Rich HTML needed a parser plus a recursive fragment that lists every allowed tag and attribute.
   - The theme stylesheet needed 26 literal `<link>` cases.
3. **Webjar URL versioning disappears without warning when Thymeleaf is removed.** The `ResourceUrlEncodingFilter`
   was registered by Thymeleaf's auto-configuration. The guide says "Links get … `response.encodeURL`", but does
   not say that the filter doing the rewriting must exist.
4. **Same-file fragments as layout arguments.** A same-file fragment passed as `~{::name}` does not see the page's
   parameters; it needs its own parameters and `~{::name(x)}`. The guide's wording ("rendered with the call site's
   variables") suggests otherwise. Tag selections such as `~{::div}` do see them.
5. **JSON in scripts cannot mix value types** in a map, and records and beans are not serialized (item above).
   The advice "pass a `Map`/`List` built in Java" does not work for mixed data.
6. **Forms.**
   - Radios get no `id` and there is no `#ids.prev`.
   - Checkbox or radio groups bound to a `Set` get no `checked`, so I computed it with `lists.contains` (which
     accepts any collection, though the docs say list).
   - A `<select t:field>` bound to `null` selects the `value=""` option, unlike Thymeleaf, so I wrote the country
     selects by hand.
   - There is no "global errors only" (`#fields.hasErrors('global')`), so I computed them in the controller.
7. **Types.**
   - `SortedMap`/`TreeMap` and `Object` map values are rejected.
   - The branches of `x != null ? x : '?'` must have the same type (Integer vs String), so I used `'' + x`.
   - A model attribute that is an enum on one page and a String on another cannot be one typed parameter.
8. **Null path variables.** A `null` link path variable fails the render, while Thymeleaf's string links printed
   `null`. In the user list, `key ?: 'null'` keeps the old output.
9. **Links built from strings.** Paths such as `'/matches#' + id` or the back URLs cannot be written as `@{...}`.
   They are plain `t:href` Strings (the context path is empty, so the output is unchanged).
10. **`t:case` needs quoted literals** (`t:case="'DEFAULT'"`) for enums; the guide does not say so. It is also
    not documented whether `<t:block>` may be a template's root element (it works).
11. **`compare --ignore`:** the docs do not say whether it runs before or after the `TODAY` date masking.
12. **No messages check.** The app uses several bundles (`TranslationMessages`, `ValidationMessages`, `TeamKey`)
    with locale suffixes only, so I did not set `-Atessera.messages`, and `#{...}` keys are not checked at build
    time.
13. **`tessera-migrate` converter output** needed rewriting by hand almost everywhere: `layout:` and `sec:`
    dialects, bean references, string-built links, and `Object` parameter stubs.

## Notes for running the parity check

- Profile-image keys and the registration code are random for each new database. If both apps start from the same
  pre-populated database they are equal; on fresh databases they need `--ignore`.
- The language links contain the absolute request URL, so the original and the new app must run on the same host
  and port.
