# Migration report: Thymeleaf → Tessera

## Result
- All 46 Thymeleaf templates are now Tessera templates in `src/main/resources/templates` (same view names, plus
  `admin/runtime_settings_team_options.html` and `info/rich_text.html`). Thymeleaf is gone: its templates, the
  `spring-boot-starter-thymeleaf`, `thymeleaf-extras-springsecurity6` and `thymeleaf-layout-dialect` dependencies, and the
  `spring.thymeleaf.cache` settings in `application-dev.yml`/`application-devpostgres.yml`.
- `mvn clean verify` passes: 123 tests, 0 failures, 0 errors. I did not change or disable any existing test.
- Security: no `SafeHtml.unchecked` / `TrustedUrl.unchecked`, `SecurityConfig` is unchanged (no new `permitAll`), CSRF is on
  (Tessera adds the hidden `_csrf` input to POST forms) and escaping is always on.

## How the output was checked
`src/test/java/.../web/snapshot/PageSnapshotMT.java` is a manual test (`*MT`, excluded from the normal build). It creates fixed
data, then renders 66 requests through MockMvc as admin and writes each response to a directory. The requests cover every GET
page, POSTs with validation errors, the htmx fragment, and the 404 and 500 error pages. I ran it on the original commit (in a
temporary git worktree) and again on the migrated code, then compared the outputs after normalizing whitespace, attribute
order, CSRF tokens and UUIDs.
- **Identical apart from noise:** most pages. The noise is the current time (systeminfo, runtime settings, match-create
  default, error timestamp) and the bootstrap theme link (see below).
- **Real differences that remain:**
  1. **Password inputs:** Tessera never writes `value=""` on `type="password"` inputs, even as a static attribute; Thymeleaf
     wrote `value=""`. The browser behaves the same.
  2. **`<select t:field>` with a null value (`/extrabets`):** the `value=""` "please choose" option gets `selected="selected"`.
     The browser selects that option anyway; no Tessera option avoids it.
  3. **Bootstrap theme link:** it was a raw string `th:href="${...getBootswatchThemeUrl()}"` and so was not versioned. A
     `<link href>` in Tessera must be a literal `@{}` link (or a `TrustedUrl`, which can only be created with `unchecked`). It is
     now `@{/webjars/bootstrap/css/...}` or `@{/webjars/bootswatch/{theme}/...}`, which the resource chain versions to
     `/webjars/bootstrap/5.3.3/...`. The same file is loaded.
  4. **Rich text on info rules/prices/misc (`th:utext`):** this was stored XSS. The admin-edited HTML was saved and rendered
     raw, and any logged-in user can post to `/info/editinfo`. `SafeHtml` can only be built with `unchecked`, so the stored
     HTML is now parsed (`web/info/RichTextParser`) into an allow-listed tree (`RichTextNode`) and rendered by the recursive
     fragment `info/rich_text.html`, with every value escaped by Tessera. Ordinary content renders the same; the default
     rules text is identical. Scripts and event handlers are dropped. **Lost:** inline `style` attributes from the Summernote
     editor (colour, size, alignment), because Tessera forbids expressions in `style`, and `data:` image URLs, which become
     `about:invalid#tessera`.
  5. **Point-course chart (`th:inline="javascript"`):** Tessera's `[[...]]` in a script only serializes JDK types, not
     records or beans. The controller now serializes the chart with Jackson into `chartDataJson`, and the script does
     `JSON.parse([[${chartDataJson}]])`. The JavaScript object is the same; the script text is different.
  6. **Malformed markup:** some originals had unclosed elements (e.g. the admin `<li>` in the navigation, systeminfo), which
     Tessera rejects. I added the missing end tags. The browser DOM is unchanged.
  7. **Error order (`POST-registration`):** several errors on one field come out in a different order between runs. This is
     Hibernate Validator ordering, not the view engine.
- **Original bug, not fixed:** `POST /config/points` with validation errors crashed before and still crashes. The controller
  binds `pointsConfiguration` but the template reads `pointsConfig`. Thymeleaf raised a `TemplateProcessingException`; Tessera
  now raises a `NullPointerException`. I kept the behaviour and did not change it.

## What changed
- **pom.xml:**
  - Added `tessera-spring-boot-starter`, with `spring-boot-webmvc` excluded (see shortfall 1).
  - Added `tessera-compiler` as an annotation processor, next to `spring-boot-configuration-processor`, with `-Atessera.templates`,
    `-Atessera.package=de.fred4jupiter.fredbet.web.templates` and `-Atessera.messages=.../msgs/TranslationMessages_de`.
  - Set `proc=none` for `default-testCompile` (see shortfall 2).
  - Added `spring-security-test` (test scope).
- **`MvcConfig`:** registers `ResourceUrlEncodingFilter`. Boot's Thymeleaf auto-configuration used to register it, and
  without it the webjar links lose their versions.
- **Layout (replaces the layout dialect):**
  - `_layout.html` is a fragment `layout(String title, Fragment head, Fragment content, Fragment scripts, LayoutModel layout,
    WebMessage globalMessage)`.
  - Each page replaces its `<html>` with it, passing `~{::headextra}`, `~{::content}` and `~{::scripts}`. These are
    placeholder elements with `t:remove="tag"`, so the output has no wrapper element, matching the layout dialect.
  - The title pattern `FredBet - <title>` is rebuilt in the layout.
- **No bean access from templates:** `web/view/LayoutModel`, `LayoutModelFactory` and `LayoutModelAdvice` (a
  `@ControllerAdvice` with `@ModelAttribute("layout")`) give every page what the navigation used to read from beans and
  Spring Security:
  - runtime settings and extra-points config
  - authorities: `sec:authorize="hasAuthority('X')"` became `lists.contains(layout.authorities, 'X')`; Tessera's `auth` only
    has role checks
  - available groups and translated group titles
  - language URLs and icons, the current user name and profile image, and the H2 settings

  `ApplicationErrorViewResolver` adds the layout model to error pages and turns the `Date` timestamp into its `toString()`.
- **Template functions:**
  - `fb.*` (`web/view/ViewFunctions`) replaces the calls to `@teamUtil`, `@webSecurityUtil.getUserProfileImageKeyFor` and
    `isRoleSelectionDisabledForUser`, and the dynamic message keys `#{${key}}` (`fb.msg(key)`). The beans are handed over
    once at startup by a registrar component.
  - `pageDates.format` (`web/view/PageDateFunctions`) formats dates in the request locale (see shortfall 3).
- **Model classes:** `@TemplateView` added to the command classes, DTOs and entities the pages read. Getters added where
  templates called non-getters (`MatchCommand.isMatchFinished`, `AbstractMatchHeaderCommand.isMatchStarted`,
  `StandingsContainer.getStandingsByGroup`).
- **Controllers, small changes:**
  - htmx team options now return a separate template instead of `"page :: fragment"`.
  - systeminfo converts its `SortedMap<String,Object>` to `Map<String,String>`.
  - `MatchResultController` resolves global errors into a list.
  - `ExtraBetController` adds `bettable`.
  - `RankingController` passes `modeParam` instead of `${param.mode}`.
  - `MatchController` always passes `redirectViewName` as a String.
  - `PointCourseController` adds the chart JSON.
  - `InfoController` parses the rich text.
- **New tests:** `web/view/TesseraViewsIT` (layout, CSRF, versioned webjars, escaping of `<script>` in the error page path),
  `web/info/RichTextParserTest`, and the manual `PageSnapshotMT`.
- **Comments and README** no longer mention Thymeleaf.

## Where the guide or Tessera fell short
1. **Starter built for the wrong Spring Boot version:** `tessera-spring-boot-starter` is built against Spring Boot 4.1.1 and
   brings in `spring-boot-webmvc:4.1.1`. On Boot 3.5 the context fails to start with a `BeanDefinitionOverrideException` on
   `errorAttributes`. The auto-configuration itself only uses annotations that exist in Boot 3, so excluding the module works.
   The guide states no supported Boot version. Also, its `@WebMvcTest` import file uses the Boot 4 package name.
2. **Processor runs again in test compile:** the processor also runs during `testCompile`, where the `@TemplateFunctions`
   classes are not in the same javac run. Every template using `fb.` then fails with
   `unknown identifier or function namespace 'fb': ...`. The fix is `<proc>none</proc>` on `default-testCompile`; the guide
   does not mention it. Partial incremental recompiles hit the same error.
3. **`temporals.format` ignores the locale:** it always uses `Locale.ENGLISH` ("Sun 10. June" instead of "So. 10. Juni"),
   with no warning and nothing in the guide. I replaced it with `pageDates.format`.
4. **Only one message bundle is checked:** `-Atessera.messages` accepts a single bundle, but the app uses three
   (TranslationMessages, TeamKey, ValidationMessages). Keys from `TeamKey` (`country.none`) fail the build, so they go through
   `fb.msg(...)` and lose the build-time check.
5. **`SafeHtml` and `TrustedUrl` can only be built with `unchecked`:** this forbids `unchecked`, which leaves no safe way to
   render stored rich text or a computed `<link href>`. Tessera ships no sanitizer.
6. **Script JSON excludes records and beans:** `[[...]]` in a script rejects records and `@TemplateView` beans.
7. **No reliable `selected` with `<select t:field>`:** a null field selects the `value=""` option, and an explicit
   `t:selected` on that option is silently ignored.
8. **Radios get no `id`:** `t:field` writes none on radios, despite the guide's "writes id (unless present)". There is no
   `#ids` replacement, so the ids are written by hand.
9. **Password inputs never get `value=""`:** not even as a static attribute; the guide does not say so.
10. **No global errors:** `fields.hasErrors('global')` is not supported. The guide only offers `'*'`, which includes field
    errors too.
11. **File inputs are rejected:** `t:field` on `<input type="file">` fails because `MultipartFile` is never view-safe. I
    wrote a static `name` instead.
12. **`SortedMap` is rejected:** it is not view-safe although the guide lists `Map`, and the rejection also produces
    misleading follow-on "unknown identifier" errors.
13. **`?:` cannot mix Integer and String:** Thymeleaf allowed `x ?: '?'` across types; the workaround is `'' + x`.
14. **No recipe for `"view :: fragment"` names:** the guide has none for replacing these view names (used by the htmx
    endpoint).
15. **Layout pattern needs undocumented details:**
    - The guide's layout example inserts `~{::body}`, which outputs a `<body>` inside the layout's container. Element
      selection plus `t:remove="tag"` on a placeholder element was needed to reproduce the layout dialect's output; this
      pattern is not documented.
    - `<head>` merging and the title pattern have to be rebuilt by hand.
    - Optional fragment arguments are not covered.
16. **Links that are not `@{}` skip context path and `encodeURL`:** links built at runtime (`@{${backUrl}}`,
    delete-confirm `href`) can only be plain String hrefs, which get no context path and no `encodeURL`. That is the same
    today because the app runs at `/`, but it would differ under a context path.
17. **Undocumented features that work:** recursive fragments, nested-class `t:import`
    (`WebMessageUtil.WebMessage`) and `t:switch` on `<t:block>` all work but are not documented.
