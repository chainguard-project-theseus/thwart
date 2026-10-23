# Migration report: Thymeleaf → Tessera

## Result

- All 47 Thymeleaf templates are replaced by Tessera templates under `src/main/resources/templates`
  (same view names, so the controllers still return the same strings). The htmx partial
  `admin/runtime_settings :: favouriteCountryOptions` is now its own template
  `admin/runtime_settings_country_options`.
- Thymeleaf is gone: `spring-boot-starter-thymeleaf`, `thymeleaf-extras-springsecurity6` and
  `thymeleaf-layout-dialect` are removed from `pom.xml`, as are `spring.thymeleaf.*` from the dev
  profiles and the remaining Thymeleaf mentions in comments and the README. `mvn dependency:tree` shows
  no Thymeleaf artifact.
- `mvn clean verify` passes: 120 tests, 0 failures. No test was changed, disabled or deleted. Two
  tests were added (see below).
- Parity: `pages.txt` (123 requests) was recorded from the original app and compared against the
  new one, both started the same way (`--spring.datasource.url=jdbc:h2:mem:...`, same port). The
  result is 117 `same`, 4 `reordered` and 2 `differs`, explained below.

## What changed

**Build (`pom.xml`).** Added `tessera-spring-boot-starter` and set up `maven-compiler-plugin`
following guide section 2:
- `tessera-compiler` goes in `annotationProcessorPaths`, plus `spring-boot-configuration-processor`,
  because listing processors turns off discovery.
- `-Atessera.templates` and `-Atessera.package=de.fred4jupiter.fredbet.templates` are set.
- `<proc>none</proc>` is set for test compilation.
- `jsoup` is added; it is used for rich-text rendering (see below).

**Layout and navigation.**
- `_layout.html` is a fragment `layout(layout, globalMessage, title, head, content, scripts)`. Every
  page does `t:replace` on its `<html>` and passes its `<body t:remove="tag">`, plus optional
  same-file `extraHead`/`scripts` fragments. This replaces `layout:decorate`/`layout:fragment` and the
  `$LAYOUT_TITLE - $CONTENT_TITLE` title pattern.
- `_navigation.html` and `fragments/language_selector.html` became parameterized fragments.
- `fragments/alert.html` was inlined into the layout.

**Data the templates used to pull from beans, the security dialect or the request.**
- A new `@ControllerAdvice` (`web/layout/LayoutModelAdvice`) builds a `LayoutView` record for every
  request. It carries: login state, theme, navbar class, main groups with resolved titles, knockout
  group availability, the navigation's `PERM_*` flags (replacing `sec:authorize`), H2 console
  entries, language URLs and icon, profile image key, username, self-registration and `bettable`.
- Page-specific values became model attributes in the page's controller: `canEditUser`,
  `canDeleteMatch`, `groupTitles`, `profileImageKeys`, `roleSelectionDisabled`, role options with
  resolved labels (these replace dynamic message keys), `globalErrors`, and others.
- `@TemplateView` was added to the entities, commands and DTOs that templates read.
- The new `teams.cssClass(country)` template function (`web/util/TeamFunctions`) replaces
  `@teamUtil.cssClassFor`.

**Error page.** `ApplicationErrorViewResolver` passes `timestamp` as `String.valueOf(date)`, because
`java.util.Date` is not view-safe. The output is unchanged.

**Webjar URLs.** Boot registered `ResourceUrlEncodingFilter` only through Thymeleaf's
auto-configuration. Without it, `@{/webjars/...}` links lose their version segment. `MvcConfig` now
registers the filter itself.

**Rich text (rules, prices, misc).** The old pages printed stored editor HTML raw with `th:utext`.
`SafeHtml.unchecked` is not allowed, and Tessera has no sanitizer. So `web/richtext/RichTextParser`:
- parses the HTML with jsoup;
- drops script-like elements together with their content;
- unwraps tags that are not on the allowlist;
- keeps only `class`, `href`, `src`, `alt` and `value`.

`fragments/rich_text.html` then renders the resulting node tree recursively, with a `t:switch` over
literal tag names. Every text and attribute still goes through Tessera's escapers and URL checks, and
no markup is ever trusted. The seeded rules page comes out `same` as the original. Attributes outside
the allowlist (for example `style` from the editor) are dropped on purpose.

**Tests added.**
- `TesseraRenderingIT` renders `/login` through the real resolvers and the security filter chain.
  It checks the CSRF input and that `<script>` in request data is not echoed raw.
- `RichTextParserUT` covers the allowlist behaviour.

## Remaining differences (and why they are right)

- **`GET /pointcourse/show`.** The chart data used to be inlined as a JS object literal. Tessera's
  `[[...]]` cannot serialize beans, records or maps with mixed value types (`Map<String, Object>` is
  rejected: `Object` is never view-safe). The controller therefore serializes the same `ChartData`
  with Jackson, and the script does `JSON.parse([[${chartDataJson}]])`. The chart receives the same
  object; only the script source text differs.
- **`GET /systeminfo`.** This page shows the git commit id, message and time of the build. It differs
  between any two builds from different commits. The rendering itself matches.
- **4 `reordered` pages** (`POST /user`, `/user/edit`, `/profile/changeUsername`, `/registration`
  with empty fields). Several validation messages for one field come from a `HashSet` and are joined
  in a different order. This is harmless.

My compare runs used `--ignore` for values that change on every run: profile image UUIDs, the random
registration code, times of day and timestamps, and CSRF tokens. With those ignores, the original app
compared against its own recording gives 123/123 `same`.

## Things to be aware of

- **Forms.**
  - Some text inputs are written as `name` plus `t:value="x ?: ''"` instead of `t:field`, so that
    they still render `value=""` the way Thymeleaf did. For these String fields the bound value
    equals the rejected value, so nothing is lost.
  - Selects whose empty option must not be pre-selected use `t:selected` per option. A
    `<select t:field>` bound to `null` selects `<option value="">` (guide §7), while Thymeleaf
    selected nothing.
  - Upload fields (`MultipartFile`) have a literal `name`.
  - Radio buttons get their `id`s from the loop counter, because Tessera gives radios no `id`.
- **The matchresult global-error box** (`globalErrors`) has no recorded page in `pages.txt`.
- **Links that carry `redirectViewName`** (`GROUP@GROUP_A`) are built as `|...|` strings, because
  Thymeleaf left the `@` unencoded. **Image links** use `|/gallery/show/${key}|` as before. Neither
  gets the context path; the app is served at `/`, so the output is the same as before.

## Where the guide or Tessera fell short

1. **`ResourceUrlEncodingFilter`.** The guide says links get `response.encodeURL`, but it doesn't
   mention that under Boot the filter doing webjar versioning comes from Thymeleaf's
   auto-configuration. Removing Thymeleaf silently drops versioned webjar URLs.
2. **Resource-URL context (`link[href]`).** Only literal `@{...}` or `TrustedUrl` compile, and
   `TrustedUrl` has no constructor other than `unchecked`. A computed stylesheet URL (the bootswatch
   theme) therefore needs one literal `<link t:if>` per theme. Using `@{...}` for it would also
   change the URL, because the filter versions it.
3. **No safe path for stored rich HTML.** The only ways to get a `SafeHtml` are `escape`,
   another template's output, or `unchecked`. There is no sanitizer. I worked around it with a node
   tree and a recursive fragment.
4. **`[[...]]` in scripts cannot serialize beans, records or mixed-value maps**, so the exact JS
   object literal output can't be reproduced (`/pointcourse/show`).
5. **`sec:authorize="hasAuthority(...)"` has no equivalent.** `auth` has only roles, so every
   permission check became a model attribute.
6. **The `tessera-migrate` converter** stubbed everything as `Object`, didn't handle the layout
   dialect, `sec:` or `@bean`, and wrote `\"`-style quoting that needed manual fixing. It helped little
   for this codebase.
7. **`-Atessera.messages` checks one bundle only.** The app uses four (`TranslationMessages`,
   `ValidationMessages`, `TeamKey`, Spring Security), so message keys are not checked at build time
   (warning only).
8. **Reorder versus difference.** `compare` treats differently ordered `<br>`-joined validation
   messages as `reordered`, which is correct. Absolute URLs built from the request (the language
   switcher) contain the port, so recording and comparing must use the same port. The guide doesn't
   say this.

## Files for the parity check

- `pages.txt` is in the repository root. It creates its own data through the forms (three matches,
  bets, a result, extra bets, an image group, a user, runtime settings with self-registration),
  covers validation-error states of every form, logged-out, admin and plain-user views, and the
  403, 404 and 500 error pages.
