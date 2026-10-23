# Migration report: FreeMarker → Tessera

## What changed
- **Build** (`pom.xml`): removed `spring-boot-starter-freemarker`, added `tessera-spring-boot-starter`
  (jakarta), and added `tessera-compiler` to `annotationProcessorPaths` next to Lombok/MapStruct
  (`-Atessera.templates`, `-Atessera.package=kg.attractor.jobsearch.templates`,
  `-Atessera.messages=.../messages_en`). Also set `<release>21</release>` and `<parameters>`, and
  `proc=none` for test compilation. Removed `spring.freemarker.*` from `application.yaml`.
- **Templates**: all 47 `.ftlh` pages are now `.html` Tessera templates at the same view names, and every
  `.ftlh` is deleted. The `layout.ftlh` macro became `fragments/layout.html` (`layout(Fragment head,
  Fragment content)`), and `layout/navbar.ftlh` became `fragments/navbar.html` (`navbar(UserDto
  authorizedUser, boolean userMenu)`). `fragments/resume_errors.html` reproduces `<@spring.showErrors>`
  markup. The old pages produced tag soup (a whole second `<html>` document inside `<body>`); the new
  templates emit markup that the browser parses into the same DOM.
- **View types**: every class in `dto/`, `model/` and `exceptions/body/` is annotated `@TemplateView`.
- **Template functions** (`kg.attractor.jobsearch.view.*`, namespaces `amfmt`, `vacfmt`, `resfmt`,
  `ufmt`, `grpfmt`): FreeMarker-style locale number formatting (`9,000`), `?keep_after_last`, date
  formatting, and favourite lookup. There is also a runtime message lookup for keys that exist only in
  `messages.properties` (e.g. `chat`), which the single-bundle build check against `messages_en`
  rejects.
- **Model data that FreeMarker took from outside the model** (path variables, session attributes,
  `HttpServletRequest`) is now added explicitly by the controllers:
  - `MessageController`: `resumeId`, `vacancyId`, `respondId`.
  - `GroupsController` / `GroupsUsersController`: `groupId`.
  - `SkillController`: `categories`, which FreeMarker read from the session.
  - `GlobalControllerAdvice`: the error page's `details` (the request) is replaced by a view-safe
    `ErrorDetails` record.
  - `VideoController`: `likeRef` and `videoMediaType`.
  - `UserController` profile: `resumePage` / `vacancyPage`.
  - `VacancyController`: `searchFields`, because `fields` is a reserved namespace.
  - Validation-error lists the templates cannot get from `BindingResult`: `resumeErrors` (indexed paths),
    `imageErrors` (`MultipartFile` fields), `messageErrors`, `respondError` and `favoriteErrors`.
- **Small fixes**:
  - `UserController` returned `"/auth/register"`; it now returns `"auth/register"`, since Tessera
    matches view names exactly.
  - `ChannelController`'s update error path passed an array; it now passes a `List`.
- **CSRF**: the hand-written `_csrf` inputs were removed; Tessera adds them to every POST form.
  `SecurityConfig` is unchanged.
- **Tests**: the existing `contextLoads` test is unchanged. I added `TesseraViewsTests`, which renders
  through the real resolvers and checks CSRF injection and the escaping of `<script>` in user input.
- **Parity tooling** in `migration/`: `pages.txt` (106 requests: anonymous, job seeker, employer;
  validation errors; 400/403/404 pages), plus `run.sh` and `compare.sh` (they start the app with a fresh
  DB and compare with `--ignore` for clock times and ports). Extra page lists used for groups are in
  `migration/groups/`.

## Parity result (`compare` against the original app's recording)
66 `same`, 4 `reordered` (validation messages from a `HashSet`), 36 `differs`. I checked every
`differs` diff, and each one falls into one of these groups:
1. **Noise that also differs when the original app is compared against itself.**
   - Vacancy list order: seed rows share a `CURRENT_TIMESTAMP`, so ties sort differently between runs.
   - The error page's Tomcat request ID and its timestamp date.
   - Microsecond timestamps in chat.
   - The bcrypt hash of the hidden password on update-profile (random salt).
   - Registration POST pages that vary between runs.
2. **Broken old markup that Tessera cannot emit.**
   - On new/update group and new/update channel, the old template passed a FreeMarker expression to
     `formInput` as a literal string, so the output contained attributes named `+`,
     `(spring.status.error?then('`, `is-invalid',` and `'')`. Tessera rejects attribute names that
     contain quotes, so these inputs are now a plain `class="form-control"`, which gives the same
     effective rendering.
   - `user_resumes` had a similar junk attribute.
3. **Values inside `<script>`.** `[[...]]` always writes compact JSON, so `[ "name", "description" ]`
   becomes `["name","description"]` (search pages 017/018), and `'1'` becomes `"1"` in
   groups_messages. The JavaScript values are identical.
4. **`onclick` with expressions** (groups list and members). Tessera bans them, so the value now goes in
   `data-*` and a static `onclick` reads `this.dataset`. The behaviour is the same. This is not reached
   by the main `pages.txt`.
5. **Pages where the original crashed mid-render.**
   - POST `/resumes/new_resume` with errors: the original's recorded status is 0 (truncated response,
     null `contactInfos`). Tessera renders the page fully, and I did not reproduce a crash on purpose.
   - Similarly, `update_groups` with a null logo and `video/video` (it printed a List) failed in the
     original and now render.

## Not converted / not verified
Everything is converted. Several views could not be exercised over HTTP, so they are only
compile-checked and read against the old template:
- **`video/video`**: creating a video needs a multipart upload, and `tessera-migrate` only sends
  urlencoded POSTs.
- **Groups with data**: the same multipart limit applies. They were verified with a seeded DB
  (`migration/groups`).
- **Employer-only pages** (new/update vacancy with data, resumes list, `responds/employers`,
  `vacancies_*_skills`): an employer registered through the form gets 403 on employer routes in the
  original app too, and the seed passwords are unknown.
- **Templates no code path reaches**: `auth/reset_password_message` (needs mail), `errors/validation_error`
  (only for `@Valid @RequestBody`) and `auth/forbidden` (nothing returns it; 403s show the Whitelabel
  page).

## Where the guide or Tessera fell short
- Spring's FreeMarker view exposes `@PathVariable`s and `@SessionAttributes` to templates, and the old
  pages relied on that silently. The guide only says path variables aren't in the model, and nothing
  warns when a converted template misses one.
- `fields.hasErrors` / `fields.errors` on a field whose type isn't view-safe (`MultipartFile`) is a build
  error; the guide only mentions `t:field`. Indexed paths (`items[0].x`) aren't supported, so
  list-backed forms needed hand-written inputs and a controller-built error map.
- `-Atessera.messages` checks one bundle. The app's default bundle (`messages.properties`, Russian) has
  keys that are missing from `messages_en`, so those keys need a runtime lookup function and are not
  checked.
- A model attribute named `fields` collides with the reserved `fields` namespace; the guide doesn't say
  so.
- There is no way to write attribute names containing quotes. That is reasonable, but it means junk
  markup from old templates can't be reproduced, and compare then reports `differs`.
- `[[...]]` in a script always writes compact JSON, so whitespace or quote style inside scripts can never
  match the old output.
- A getter typed `Object` cannot be passed even to a `@TemplateFunction` that takes `Object`.
- A `null` field value omits `value=` where the Spring macros wrote `value=""`; compare tolerated it.
- The guide does not say whether `t:block` supports `t:text` (it does), or that `method="POST"` in
  upper case also gets the CSRF input (it does). Both were found by trial.
- The page-parity tool masks today's date but not times or other dates, and it has no way to deal with
  nondeterministic row order in the old app. The Tomcat request IDs shown on the error page depend on
  how many requests (e.g. readiness polls) hit the server before recording.
- `SafeHtml.unchecked` / `TrustedUrl.unchecked` are not used anywhere.
