# FreeMarker → Tessera migration report

## Summary

All 49 FreeMarker views (`*.ftlh`, ~6,800 lines) are now Tessera templates (`*.html`, same paths
under `src/main/resources/templates`). FreeMarker is gone: no templates, no
`spring-boot-starter-freemarker` dependency and no `spring.freemarker.*` configuration.
`mvn clean verify` passes, including the existing test (`JobSearchApplicationTests`, unchanged).
No `SafeHtml.unchecked`/`TrustedUrl.unchecked`, no change to `SecurityConfig`, CSRF is still on, and
no escaping was turned off.

### How output equivalence was checked
Before changing anything I built and started the original app. I captured about 85 pages: anonymous,
job seeker and employer sessions, the 400/404 error pages, and POSTs that fail validation (including a
`<script>` payload). I then started the migrated app on the same H2 database and captured the same
URLs. Both captures were normalised (whitespace collapsed, attributes sorted, entities decoded, CSRF
token masked) and diffed:

- Every URL returns the same HTTP status in both apps.
- 36 pages are identical after normalisation.
- The other pages differ only in:
  - row order or page contents that the **old** app also changes between two runs (nondeterministic
    DB ordering);
  - request id, port and timestamp on error pages;
  - the intentional differences listed below.
- Forms with validation errors (registration, new vacancy, new group/channel multipart, update forms)
  show the same error messages in the same places, and user input is escaped in both.
- Number formatting matches in `en` and `ru` locales (salaries, counts, the port on error pages).

I captured the pages from a copy of the original build (kept outside git, deleted afterwards). To log
in I temporarily set the seeded users' passwords in the local, git-ignored H2 database `db/`, then
restored the original hashes.

## What changed

### Build (`pom.xml`, `application.yaml`)
- Replaced `spring-boot-starter-freemarker` with `dev.tessera:tessera-spring-boot-starter:0.1.0-SNAPSHOT`.
  - **It excludes `org.springframework.boot:spring-boot-webmvc`.** The starter depends on that Spring
    Boot **4.1.1** module, and next to this app's Boot 3.4.3 the context fails to start (duplicate
    `conventionErrorViewResolver` bean from two `ErrorMvcAutoConfiguration` classes). The starter's
    classes only use Boot autoconfigure annotations that exist in 3.4, so the exclusion is safe.
- Added `tessera-compiler` to `annotationProcessorPaths`, next to Lombok and MapStruct, with
  `-Atessera.templates`, `-Atessera.package=kg.attractor.jobsearch.templates`, `-Atessera.messages`
  (message keys are checked at build time) and `-parameters`.
- **`default-testCompile` gets its own processor path without Tessera.** The shared configuration also
  applies to `testCompile`, so the processor re-compiled every template during test compilation. There
  the `@TemplateFunctions` classes are not part of the javac run, and `mvn verify` failed with
  `unknown identifier or function namespace 'umfmt'` and 139 similar errors, while `mvn compile`
  passed.
- Removed `spring.freemarker.expose-spring-macro-helpers`.

### Templates
- `layout.html`: the old `layout.ftlh` macro as a `layout(Fragment content)` fragment.
- `layout/navbar.html`: a `navbar(UserDto authorizedUser, boolean user)` fragment.
  - The old navbar was `#include`d inside the page's layout call and itself called the layout macro
    again, which produced a nested `<!doctype html><html>…` inside the page body. I kept that
    structure exactly, because the HTML must not change.
  - `user` replicates the old `user??` check (true only where the model has a `user` attribute).
- Each page is a root `<t:block>` with `t:params`. It keeps the markup that was before and after the
  `<@layout.layout>` call, and passes its body to the layout as `~{::tcontent}` (a `<tcontent
  t:remove="tag">` element).
- Spring macros mapped to Tessera:
  - `<@spring.message>` → `#{...}`;
  - `formInput`/`formHiddenInput`/`formTextarea`/`formSingleSelect` → `t:field`;
  - `spring.status.error` → `fields.hasErrors(...)`;
  - `showErrors` → a loop over `fields.errors(...)` that renders the same `<b>`/`<span class>` markup
    and separators.
- Hand-written `_csrf` hidden inputs were removed from POST forms. Tessera injects the same input
  right after each `<form method="post">` tag.

### Java
- All classes in `dto/` are annotated `@TemplateView`. So are `ValidationExceptionBody` and
  `ValidationErrorBody`; the latter has a new `getRejectedValueText()`, because `Object` is not
  view-safe.
- `config/PathVariablesModelConfig` (new): a `HandlerInterceptor` that copies URI template variables
  into the view model. Spring's `AbstractView`, which FreeMarker used, did this, so pages such as
  `groups/members` read `${groupId}` without the controller adding it. `TesseraView` implements
  `View` directly and does not. Model attributes still take precedence, as in `AbstractView`.
- `templates/fn/` (new `@TemplateFunctions`):
  - `umfmt.number` reproduces FreeMarker's default locale-dependent number format (`90,000` /
    `90 000`), and `umfmt.keepAfterLast` replaces `?keep_after_last`;
  - `vacFn.favoriteFor` replaces `?filter(...)` and the `<#break>` loop;
  - `videoFn.mediaType` prints Spring's `MediaType`, which is not view-safe.
- Error pages:
  - `exceptions/body/ErrorRequestDetails` (new record) replaces passing `HttpServletRequest` as
    `details`. It carries server name, request URL, port and request id, plus the current time in the
    locale's medium format, matching the old output.
  - `GlobalControllerAdvice` uses it.
- Resume forms:
  - `dto/ResumeFormFields` and `util/ResumeFormFieldsFactory` (new) produce what `spring.bind` gave
    the templates for **indexed** paths (`workExperienceInfoDtos[0].years` and so on): bound or rejected
    value, and resolved error messages.
  - `ResumeController` and `SkillController` add that object to the model.
- Values that are not view-safe were replaced by what the page printed:
  - `GroupsMessagesController`: `bindingResult` → `messageErrors`, a `List<String>`;
  - `GroupsController`: `imageErrors`, because `fields.*` cannot be used on a `MultipartFile` field;
  - `RespondApplicationController`: `FieldError` → its message;
  - `FavoritesController`: global `ObjectError` → its message.
- `ProfileServiceImpl`: `pageHolder` was either `PageHolder<ResumeDto>` or `PageHolder<VacancyDto>`.
  It is now split into `resumePageHolder` and `vacancyPageHolder`, because a template parameter has
  one static type.
- `MessageController` now adds `resumeId` and `respondId` to the model. These were path variables;
  this is redundant with the interceptor but harmless.
- `ChannelController`: one error path passed `ChannelAccess.values()` (an array) where every other
  path passes a `List`. It is now `List.of(...)`.
- `UserController`: `return "/auth/register"` is now `"auth/register"`. Tessera's resolver matches
  view names exactly, so the leading slash made the failed-registration page unresolvable.

## Known, intentional HTML differences (could not or should not be identical)
1. **Duplicate attributes are gone.**
   - Spring's `formInput` always wrote `type="text"` and an `id`, and several pages then added their
     own `id=`/`type=`, which gave `<input type="text" … type="email">`.
   - Tessera rejects duplicate attributes. I kept the first one, which is the one browsers apply.
   - Consequence: the registration password field is still a visible text input, as it effectively
     was before. Fixing it is a behaviour change I did not make.
2. **Junk attributes from broken macro calls are gone.** On new/update group and channel, a literal
   `class='form-control' + (spring.status.error?then(' is-invalid', ''))` string was printed as
   attributes. It never had an effect; the output is now `class="form-control"`.
3. **Boolean attributes** render as `selected="selected"` / `checked="checked"` instead of
   `selected=""`.
   - A bound `<select>` whose value is null marks its empty `<option value="">` as selected
     (`new_channel`, `update_channel`).
4. **Unclosed elements** in the old `register`, `chat` and `user_resumes` markup now have explicit
   closing tags where the browser closed them implicitly. Tessera requires well-formed nesting.
   - `user_resumes` had a broken `<span text-bg-success">`; the attribute name is now
     `text-bg-success` without the stray quote.
5. **Null values.**
   - The old templates threw a FreeMarker error mid-page on some nulls. Example: `POST
     /resumes/new_resume` with errors printed a FreeMarker debug stack trace into the page at
     `resume.contactInfos`.
   - Tessera renders these pages fully: null text is empty, a null attribute is omitted, and a null
     list has zero iterations.
   - Spring printed `value=""` for null bound fields; Tessera omits `value`. Password inputs never echo
     a value.
6. **Ids and page numbers in URLs/hidden inputs** print raw in most templates. FreeMarker would have
   printed `1,234` for values ≥ 1000 (a latent bug); with the current data all values are < 1000, so
   the output is identical.
   - The resume/skills templates replicate the grouping instead.
   - Visible numbers (salaries, counts) keep FreeMarker's locale grouping everywhere.
7. **Inline scripts** now use Tessera's JSON output:
   - `window.currentUser = "…"` becomes a JSON string or `null`;
   - `const groupId = "5"` was `'5'`;
   - `const fields = [...]` is a JSON array.
   The resulting JS is equivalent.
8. **Inline handlers.** On `groups/groups` and `groups/members`, inline handlers that contained
   expressions became static handlers that read `data-*` attributes (`data-href`, `data-confirm`).
   Behaviour is the same.
9. **Messages are escaped.** `<@spring.message>` output was unescaped (`?no_esc`); Tessera escapes it.
   Only apostrophes occur in the bundles, so the text is the same.
10. **A form that was already broken stayed broken.** In `video/video`, the like-delete form is a GET
    form to a POST-only endpoint, and it contained a hand-written CSRF input. Tessera adds CSRF only to
    POST forms, and there is no supported way to emit a token elsewhere, so that input is gone. The
    form did not work before either.
11. **Errors for the wrong field, kept as before.** In `video/new_video`, the error blocks under the
    file inputs showed the *description* field's errors (the last field bound by the old macros). I
    kept that.
12. **Dead blocks removed.** Blocks that could never render were removed: `spring.hasGlobalErrors??`,
    `resume.position`, unused `<#assign>`.

## Shortcomings of the guide / Tessera hit during the port
- **Spring Boot version.** `tessera-spring-boot-starter` depends on Spring Boot 4.1.1's
  `spring-boot-webmvc`, which breaks a Boot 3.x app at startup. The guide states no supported Boot
  version.
- **The processor also runs in `testCompile`.** With the guide's suggested configuration, any
  `@TemplateFunctions` class makes `mvn verify` fail during test compilation, while `mvn compile`
  passes. The guide does not mention it. I fixed it with a separate `default-testCompile` processor
  path.
- **Path variables and request attributes.** `TesseraView` does not expose URI path variables (or
  request/session attributes), unlike Spring's `AbstractView`-based views. This silently changes pages
  that relied on them; the guide only says "each parameter is read from the model attribute".
- **View names are matched exactly.** `"/auth/register"` falls through to no view at runtime instead
  of failing the build or being normalised. Not documented.
- **No indexed form paths.** `t:field`/`fields.*` reject indexed paths (`list[0].x`), even literal
  ones: `t:field needs a field path such as lastName or *{address.city}, not an expression`. Forms
  over lists need hand-written binding code; the guide offers nothing for this common Spring case.
- **No `fields.*` on non-view-safe fields.** `fields.hasErrors('image')`/`fields.errors('image')`
  fail when the field type is not view-safe (`MultipartFile`), although only errors are read:
  `cannot read 'image' of …GroupsDto: it returns org.springframework.web.multipart.MultipartFile,
  and … is never view-safe`.
- **One template error blocks all generated code.** Any template error fails javac and leaves *no*
  generated classes. The guide says other templates "still report their errors", which is true, but
  none get generated.
- **Lombok `Boolean isX` fields.** The getter is `getIsApproved()`, and `skill.isApproved` is
  rejected (`'isApproved' is not a property of …SkillDto`). Only `skill.getIsApproved()` works.
- **Null in literal substitution.** A null inside `|...${x}...|` renders the text `null`, unlike a
  null attribute value. Not documented.
- **`<t:block>` inside `<script>`** is emitted literally. Not documented.
- **`t:if` with `t:replace`.** `t:if` on the same element as `t:replace` does not guard it (replace
  runs first, per the order table), which is easy to miss when porting `<#if><#include>`.
- **`fields` as a model attribute name.** It worked as a `t:param` outside `t:object` even though
  `fields` is the reserved forms namespace. The guide is unclear whether this is supported.
- **Undocumented `t:field` behaviour:**
  - static `<option>`s get `selected`;
  - the empty option is selected for a null value;
  - password inputs omit `value`;
  - null values omit `value`.
- **The guide covers only part of FreeMarker:**
  - **Spring form macros:** none are covered except `formInput`/`showErrors`.
  - **`showErrors` output:** it renders `<b>` or `<span class>` per message, which differs from
    `t:errors` (joined by `<br />`, element removed when empty). Replicating it needs an explicit
    loop.
  - **Number formatting:** FreeMarker's default locale-dependent number format (`${n}` → `90,000`)
    has no built-in equivalent (`numbers.formatDecimal` takes fixed digit counts), so a custom
    function was needed.
- **No CSRF token outside POST forms.** There is no way to emit a CSRF token outside a `<form
  method="post">` (for example a GET form or a JS meta tag) without `unchecked`. Not needed here,
  beyond the already-broken form above.
- `tessera-migrate` handles only Thymeleaf, so this FreeMarker port was done by hand.
