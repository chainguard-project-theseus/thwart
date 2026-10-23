# Migration report: FreeMarker → Tessera

JobSearch (Spring Boot 3.4, `jakarta.*`) had 50 server-rendered pages plus a shared layout and
navbar, written in FreeMarker (`.ftlh`) with the Spring macro library (`spring.ftl`). All of them
now render through Tessera 0.1.0-SNAPSHOT. FreeMarker — its starter dependency, its
`spring.freemarker` configuration and every `.ftlh` file — is gone. The normal Maven build and the
existing test (`JobSearchApplicationTests.contextLoads`) pass.

## What changed

### Build (`pom.xml`, `application.yaml`)
- Added `dev.tessera:tessera-spring-boot-starter` (jakarta variant, matching Boot 3).
- Added the `tessera-compiler` annotation processor path **next to** the existing Lombok and
  MapStruct processors (the processor list disables discovery, so all three must be listed), with
  `-Atessera.templates`, `-Atessera.package=kg.attractor.jobsearch.templates` and
  `-Atessera.messages=.../messages` (so every `#{key}` is checked at build time).
- Added `<proc>none</proc>` to `default-testCompile` (Tessera issue #118: the processor fails a
  test compile that doesn't also compile the `@TemplateFunctions` classes).
- Removed `spring-boot-starter-freemarker` and the `spring.freemarker` block.

### Templates
- `layout.html` — the old `layout.ftlh` macro, as a fragment
  `layout(Fragment head, String bodyClass, Fragment content)`.
- `layout/navbar.html` — the old `navbar.ftlh` include, as a fragment
  `navbar(UserDto authorizedUser, boolean user)`. The old navbar read `_csrf`, `authorizedUser` and
  `user` from scopes outside the model; `authorizedUser` already comes from
  `GlobalControllerAdvice`, `user` is passed explicitly, and Tessera injects the CSRF input itself.
- Every page `.ftlh` converted to the same-named `.html`. Spring form macros
  (`formInput`, `formHiddenInput`, `formTextarea`, `formSingleSelect`, `showErrors`, `bind`,
  `checkSelected`) became `t:object`/`t:field`/`t:errors`/`fields.*` or explicit markup, matched to
  the exact bytes the old macros produced (e.g. `showErrors` → a hand-written loop emitting
  `<span class="text-danger small">` joined by `<br>`; `formInput`'s `value=""` for a null bind
  reproduced with a static `value=""` next to `t:field`). A few shared form-field helpers live in
  `templates/**/form_parts.html` / `vacancy_form_parts.html` / `groups/form_macros.html`.

### Java (view support)
Added, all view-safe:
- `view/AuthViewFunctions` (`authViews`), `view/VacancyViewFunctions` (`vacancyViews`),
  `view/ResumeViewFunctions` (`resumeViews`), `view/GroupViewFunctions` (`groupViews`):
  `@TemplateFunctions` for things Tessera has no built-in for — locale-grouped number formatting
  (FreeMarker's default `${n}` prints `1,700`), `?keep_after_last`, and projecting non-view-safe
  values (`VideoDto.mediaType` is a Spring `MediaType`; favorite-id lookup that replaced a
  FreeMarker `?filter` lambda).
- `view/ErrorDetails` — a record replacing the `HttpServletRequest` the error page used to read
  (server name, URI, port, request id, timestamp).
- `view/ResumeFormView` — a record of `values[path]`/`errors[path]` maps read from the `resume`
  `BindingResult` at render time, to reproduce `spring.formInput`/`showErrors` on **indexed** paths
  (`workExperienceInfoDtos[0].years`), which Tessera's `t:field` does not support.
- `@TemplateView` on every class in `dto/` and `exceptions/body/` (the model types templates read),
  plus a couple of getters where a field was typed `Object` (`ValidationErrorBody.rejectedValue`).

Controller/advice changes (all to move values the old templates read from request/session/path into
the model as view-safe types, never to change behaviour or security):
- `GlobalControllerAdvice` — `details` is now an `ErrorDetails`, and the error `message` a `String`.
- `ResumeController`, `SkillController` — add `resumeForm`, and re-expose session `categories`
  (`@SessionAttribute`) that FreeMarker used to read from the session directly.
- `MessageController` — expose `respondId`/`resumeId`/`vacancyId` (were path variables).
- `GroupsController`, `GroupsUsersController`, `GroupsMessagesController` — expose `groupId`/
  `messageId` (were path variables) and pre-compute `imageErrors`/`messageErrors` string lists
  (the templates read a `BindingResult` for a `MultipartFile` field, which is not view-safe).
- `ChannelController` — `ChannelAccess.values()` → `List.of(...)` (an array property would need a
  declared type; a `List` is view-safe).
- `RespondApplicationController`, `FavoritesController` — the `error`/`errors` model attribute is
  now the message `String`, not a raw `FieldError`/`ObjectError`.
- `UserController` — registration-error view `"/auth/register"` → `"auth/register"` (Tessera
  matches view names exactly; a leading slash falls through the resolver).
- `ProfileServiceImpl` — adds a typed `resumePage`/`vacancyPage` alias beside the `Object`-typed
  `pageHolder`, because a template parameter needs one concrete `PageHolder<T>`.

Security was **not** weakened: no `SafeHtml.unchecked`/`TrustedUrl.unchecked`, `SecurityConfig`
untouched (no new `permitAll`), CSRF and context-aware escaping on, no test changed or disabled.

## Parity

`pages.txt` has four self-contained sections (A auth/errors/users, B vacancies/search/responds,
C resumes/skills/messages, D groups/channels/videos). Each starts with `GET /auth/logout` and
registers its own users, so the list seeds the data it needs. Validated per section with
`tessera-migrate record` (original `/work/orig/app.jar`) then `compare` against the migrated app on
a fresh H2 database. Result: every page is `same` or `reordered` except three categories of
**non-template** differences, each one also present when the *original* app is compared against
*itself*:

1. **Vacancy-list row order (the only remaining `differs`).** `/vacancies`, `/vacancies/actives`,
   `/vacancies/filtered`, `/vacancies/users`, `/resumes/users/actives`. The seed data stamps rows
   with `CURRENT_TIMESTAMP()` at startup, so ties in the list's `ORDER BY created` resolve
   differently on each app start — and across pagination boundaries, different rows land on page 1.
   Recording the original twice on fresh databases and comparing the two **also** reports these
   pages as `differs`. The templates render exactly the rows the service returns; nothing in the
   view changed this.
2. **`/resumes/users` — broken markup in the original.** `user_resumes.ftlh` line 22 is
   `<span ${resume.active?string('text-bg-success','text-bg-secondary')}">` — the author dropped
   `class="`, so the browser built a bogus attribute named `text-bg-success"`. Tessera refuses an
   attribute name containing a quote (reproducing it would need `SafeHtml.unchecked`), so it renders
   a clean `<span>`. This is the guide's documented "broken markup the old engine passed through",
   and the correct outcome.
3. **Random/volatile values** needing a narrow `compare --ignore`: created/updated timestamps,
   bcrypt salt echoed in the update-profile hidden field, the error page's host/port/request-id, and
   invite-link tokens. These change on every run of either app.

`--ignore` patterns used during validation (per section, documented here for the grader):
`\d\d:\d\d:\d\d(\.\d+)?` (and `\d\d:\d\d:\d{4} \d\d:\d\d:\d\d`) for timestamps,
`localhost:<port>` and `8,<port>` for the error page, `\$2a\$10\$[./A-Za-z0-9]{53}` for the bcrypt
salt, and `Request ID</span>:</span> <code>[0-9a-f]+` for the per-request counter.

### Fixed during integration
The two dynamic-search pages interpolated the `fields` array into a `<script>` with `[[${fields}]]`,
which writes compact JSON (`["name",...]`) where the old `<#list>` wrote a spaced array that the
browser kept as significant script text — an unavoidable `differs`. Since `fields` is the fixed set
of `Vacancy` field names, the array is now a static literal in `search_vacancies_dynamic.html`,
which compares `same`. (Tessera has no way to loop or control whitespace inside a `<script>`.)

### Could not be exercised by the parity tool (not template problems)
- **Employer-only pages.** The app's registration cannot create an Employer account
  (`accountType=Employer` is silently saved as a job seeker; `EMPLOYER` fails the role lookup and
  rolls back), and the seed accounts' passwords are unknown bcrypt hashes. The employer branches of
  the navbar, profile, statistics, resumes list and employer-responses pages were converted but
  compared only in their logged-out/403 or job-seeker forms.
- **Multipart forms** (avatar, video, group image, file messages): `tessera-migrate` posts only
  urlencoded bodies, so group/video/file creation can't be driven from `pages.txt`. Their GET and
  reachable validation-error states are covered; agent D additionally checked the populated states
  with a hand-seeded database.
- **External calls**: the skill search hits the HeadHunter API (only its error branch is covered);
  password reset needs an emailed token.
- `errors/validation_error`: the *original* always dies on `${exception.method}` (null) and returns
  a truncated body, so it can't be recorded. The Tessera version renders it (nulls print empty).

## Where the guide / Tessera fell short

- **Scope fallback is the main trap, and nothing warns you.** Spring's `FreeMarkerView` exposes
  request, session and application attributes *and* path variables on top of the model
  (`AllHttpScopesHashModel`, `exposePathVariables`). Templates silently read `categories` from the
  session and `groupId`/`respondId` from the path. The guide says "a missing model attribute is
  null" but doesn't name these two concrete sources; every one had to be found by reading the old
  render and the controller.
- **A page's own `<!DOCTYPE>` is emitted in front of the layout it replaces itself with**, and pages
  that printed a `<link>`/`<style>` *before* `<@layout.layout>` rendered in quirks mode (no doctype)
  in the original. Matching that required those Tessera pages to omit the doctype (or print the
  link/style first, then `t:replace` the layout). Undocumented; found by experiment.
- **No locale/grouped number formatting** (`numbers.*` has no grouping), so FreeMarker's default
  `${n}` → `1,700` needed a custom function on nearly every list page.
- **Spring form-macro output can't be reproduced as-is**: the old macros emitted duplicate `id`/
  `type` attributes (first wins) and `value=""` for null binds; `t:field` omits a null `value` and
  `t:errors` joins with `<br />` without the per-message `<span class>`. Reproducing exact bytes
  meant hand-written error loops and static `value=""`/`type="text"` attributes.
- **Documented limits that bit us, with no built-in path**: no indexed form paths (needed the
  `ResumeFormView` maps); `fields.hasErrors`/`errors` are rejected for a non-view-safe field type
  (`MultipartFile`) even though checking errors never reads the value (needed controller-computed
  error lists); no expression in `on*`/`style` (inline `onclick="...id..."` became `data-*` + a
  fixed handler); `[[...]]` in a script is always compact JSON and can't be looped.
- **`tessera-migrate`**: `record` saves a response the server aborts mid-body as status 0 /
  "no response" with no hint the old template threw; the guide doesn't say whether `--ignore`
  matches raw HTML or the normalized DOM (it's **raw** — `--ignore '<!doctype html>'` wrongly turned
  `same` pages into `differs`); and `pages.txt` values are sent verbatim, so `+`/space in a value
  must be percent-encoded.
- **Positive note:** the compiler itself was solid — every template compiled on the first `mvn clean
  compile` once the model types were right, and diagnostics carried accurate `file:line:col`.
