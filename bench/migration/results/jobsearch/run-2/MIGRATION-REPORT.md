# Migration report: FreeMarker → Tessera

## Result

- **Tessera renders every server-side page.** All 48 FreeMarker templates (`*.ftlh`) are now Tessera
  templates (`*.html`) under `src/main/resources/templates`, keeping the same view names.
- **FreeMarker is gone.** `spring-boot-starter-freemarker` and the `spring.freemarker` settings in
  `application.yaml` are removed, and no `.ftl`/`.ftlh` files remain.
- **The build and tests pass.** `mvn clean verify` runs the existing `JobSearchApplicationTests` plus one new
  test, `view/TemplateEscapingTest`, which renders `errors/error` with `<script>` in the data and checks
  that it comes out escaped. No existing test was changed.
- **Security is not weakened.**
  - There is no `SafeHtml.unchecked` or `TrustedUrl.unchecked`.
  - `SecurityConfig` is unchanged: no new `permitAll`, CSRF still on.
  - The hand-written `_csrf` hidden inputs in POST forms were removed. Tessera adds the same input
    (`name="_csrf" value="…"`) to every `<form method="post">` by itself.

### How the output was checked

I built the original commit (`c78a61c`) and the migrated app and ran both on a fresh H2 database. A
crawler then fetched about 105 pages from each:
- logged out, as a newly registered job seeker, and as a newly registered employer;
- including invalid form POSTs (registration, reset password, groups, channels, resumes) and 404 error
  pages.

The two outputs were compared as normalised token streams: whitespace and attribute order ignored, the
first of any duplicate attribute kept (as browsers do), and CSRF tokens and timestamps masked.

The remaining differences fall into three groups:
- **Nondeterminism already in the old app.** Two crawls of the *original* app also differ here: the order
  of the vacancy lists, the order of validation messages, bcrypt salts, and the port number printed on
  error pages.
- **Pages the old app could not render.** These failed with a FreeMarker error and now render (listed
  below).
- **The deliberate differences listed in the next section.**

## Intended differences from the old HTML

1. **Escaping.** Spring's `FreeMarkerConfigurationFactory` creates FreeMarker with
   `incompatible_improvements` 2.3.0. At that level `.ftlh` is **not** auto-escaped, so every old
   `${…}` was printed raw (an XSS hole). Tessera always escapes, and messages (`<@spring.message>` used
   `?no_esc`) are escaped too.
   - The DOM changes only when data or messages contain `& < > " '`; `don't` becomes `don&#39;t`,
     which is the same text in the browser.
   - In `vacancies.html`, `window.currentUser` is now written as a JSON string.
2. **Junk attributes the old macros produced cannot be reproduced.** Some `<@spring.formInput>` calls
   passed attribute strings that FreeMarker printed literally, for example
   `class='form-control' + (spring.status.error?then(' is-invalid', ''))`. The browser read that as
   attributes named `+`, `(spring.status.error?then('`, `is-invalid',` and `''))`.
   - Tessera rejects attribute names containing quotes or parentheses.
   - The real attributes (`class`, `id`, `name`, `value`) are identical, and the junk ones are dropped
     (the bare `+` kept where possible).
   - Affected: the login/name inputs in `channel/new_channel`, `channel/update_channel`,
     `groups/new_groups` and `groups/update_groups`.
   - A similar case in `resumes/user_resumes`: an attribute literally named `text-bg-success"` (with
     the quote) is now written as `text-bg-success`.
3. **Expressions inside `onclick` are build errors in Tessera.** In `groups/groups` (row link, leave
   confirmation) and `groups/members` (remove confirmation), the handler is now fixed text that reads a
   `data-*` attribute (`this.dataset.href`, `confirm(this.dataset.confirm)`). It behaves the same, but
   the DOM has an extra `data-*` attribute and a different `onclick` text.
4. **Boolean attributes.** Tessera writes `checked="checked"`, `selected="selected"` and
   `disabled="disabled"` where the old output had `checked=""`, `selected=""` or a bare `disabled`.
   The DOM meaning is the same.
5. **Unclosed tags are now closed explicitly.** Tessera needs well-formed nesting, so the closing tag
   sits where the browser used to imply it:
   - `auth/register`: two `div`s;
   - `messages/chat`: one `div`;
   - `resumes/user_resumes`: one `span`.
6. **Links use the URL check.** URL attributes (`href`, `src`, `action`) go through Tessera's check. An
   avatar or logo value that is not http(s), mailto, tel or relative now renders as
   `about:invalid#tessera`. Real values are unaffected.
7. **Old pages that always or sometimes failed now render.** For these I followed the old template's
   evident intent; there was no working old output to match.
   - `/vacancies` with no `categories` in the session.
   - `resumes/new_resume` re-rendered after a failed POST (it read `resume.contactInfos`, which was
     missing).
   - `errors/validation_error`: `ErrorServiceImpl` never sets `method`, so `${exception.method}` always
     failed.
   - `video/video`: `${likeQuantity!0}` printed a `List`. It now shows `likeQuantity.size()`, and the
     delete-like form action uses `like.id` instead of `LikeDto@hash`.
   - `messages/chat` when `resumeId` was not in the model.
8. **Error-page timestamp.** The `${.now}` timestamp on error pages is now computed in the exception
   handler (the `now` model attribute), with the same locale format.
9. **Three cases where an old bug was copied on purpose, because the HTML had to match:**
   - **Plain-text password fields.** The old macros wrote `type="text" … type="password"` (or
     `type="email"`, `type="number"`, `type="date"`, …). Browsers keep the *first* `type`, so these were
     text inputs. They are reproduced as `type="text"`, so the registration password is still shown in
     clear text and echoed back after a failed submit, exactly as before. This deserves a deliberate fix
     outside this migration.
   - **Separators printed as text.** Some `showErrors` calls passed a field path as the separator, so the
     literal text (e.g. `user.password`) appears between messages.
   - **Number formatting.** Numbers, including ids inside URLs and hidden inputs, still use
     FreeMarker-style locale grouping (`1,234`) through `fmt.num`.

## What changed

### Build (`pom.xml`)
- **Dependency.** `tessera-spring-boot-starter` 0.1.0-SNAPSHOT replaces `spring-boot-starter-freemarker`.
  The starter is built against Spring Boot 4.1 and depends on the Boot 4 module
  `org.springframework.boot:spring-boot-webmvc`. That module is **excluded**, because this app runs on
  Boot 3.4 and every class the starter uses exists in Boot 3 / Spring 6.2 (checked with `javap`).
- **Annotation processor.** `tessera-compiler` is added next to Lombok and MapStruct, with:
  - `-Atessera.templates`;
  - `-Atessera.package=kg.attractor.jobsearch.templates`;
  - `-Atessera.messages` (every `#{key}` is checked at build time);
  - `-parameters`.
- **Test compile.** Annotation processing is turned off for `default-testCompile` (see shortcomings).

### Templates
- **`layout.html`** is the old layout macro, now the fragment `layout(Fragment content)`.
- **`layout/navbar.html`** is the old include, now the fragment
  `navbar(UserDto authorizedUser, boolean user)`. `user` replaces the old `user??` test.
- **Pages.** Each page is a `<t:block>` root carrying `t:params`, followed by:
  - whatever the old page printed before the layout;
  - `<t:block t:replace="~{layout :: layout(~{::body})}"><body t:remove="tag">…</body></t:block>`;
  - whatever it printed after the layout.

  This reproduces the old structure exactly, including the oddities: `<link>`/`<style>` before the
  doctype, and the navbar's nested second document.
- **Spring form macros** became `t:object`/`t:field`, plus loops over `fields.errors('path')` that
  reproduce `showErrors`' `<span class=…>` and separators.
- **Indexed fields** in the resume forms (`workExperienceInfoDtos[i].…` and similar) cannot use `t:field`.
  They use a small view model built by `view/ResumeFormFields` from the real `BindingResult`.

### Java
- **Model types.**
  - `@TemplateView` is added to every class in `dto/` and `exceptions/body/`.
  - New view records: `dto/CsrfView`, `view/ErrorDetailsView`, `view/ErrorView`, `view/ResumeFormField`.
  - `ValidationErrorBody.getRejectedValueText()` (`@JsonIgnore`) exists because templates cannot read
    `Object`.
- **`view/ViewFunctions`** (`@TemplateFunctions("fmt")`) reproduces FreeMarker built-ins: `num`
  (default number format, request locale), `plain`, `keepAfterLast`, `initial`, and
  `isFavorite`/`favoriteId` (they replace `?filter` lambdas).
- **`view/FreemarkerFormat`** is a plain helper for the error pages.
- **`GlobalControllerAdvice`:**
  - adds `csrf` (for the one GET form that carries the token);
  - error pages get `details` as an `ErrorDetailsView` instead of the `HttpServletRequest`, plus `now`.
- **Controllers.** These now put into the model values the old templates got implicitly:
  - `ChannelController`, `GroupsController`: errors of `MultipartFile` fields, because `fields.errors`
    rejects that type;
  - `VideoController`: `videoMediaType` as a String;
  - `GroupsController`, `GroupsUsersController`, `MessageController`: `groupId`, `resumeId` and
    `respondId` (see "path variables" below);
  - `SkillController`: `categories` (see "session attributes" below);
  - `UserController`: `resumesPage`/`vacanciesPage`;
  - `GroupsMessagesController`: `groupsMessageErrors`;
  - `VacancyController`: `favoritesError`;
  - `RespondApplicationController`: `respondError`.

  Existing model attributes are unchanged, with two exceptions: `details` on error pages (above), and
  `channelAccesses` on the channel-update error path, now a `List` like on the GET path instead of an
  array. No JSON/REST response was changed.
- **`UserController.createUser`** returns `"auth/register"` instead of `"/auth/register"`, because the
  Tessera resolver does not normalise a leading slash.

## Could not convert

There is nothing left in FreeMarker. The only places where the old HTML could not be reproduced exactly
are differences 2 and 3 above (invalid attribute names, and expressions in `onclick`). Tessera forbids
both by design.

## Where the guide or Tessera fell short

1. **The Spring starter targets Spring Boot 4 only.** It depends on `spring-boot-webmvc` (a Boot 4
   module) and ships `…boot.webmvc.test.autoconfigure` imports. The guide does not mention a Boot
   version. On Boot 3 the dependency must be excluded by hand; nothing in the guide says this works.
2. **The annotation processor also runs on `testCompile`.** There, templates that call
   `@TemplateFunctions` classes from `src/main` fail with `unknown identifier or function namespace`.
   The guide says the class "must be compiled in the same javac run" but does not warn that the test
   compile is a second run. Workaround: `<proc>none</proc>` for `default-testCompile`.
3. **Incremental compiles fail.** An incremental `mvn compile` that does not recompile the function class
   fails the same way. The guide's "Incremental builds" note only says template changes are not
   noticed; `mvn clean compile` is effectively required.
4. **Path variables are not exposed.** Spring's `AbstractView` merges URI path variables into the model,
   and FreeMarker templates read them. `TesseraView` does not, and the guide's model rules (section 12)
   don't mention it. Pages silently rendered empty values until the controllers added the attributes
   explicitly.
5. **Session and request attributes are not visible.** FreeMarker's `AllHttpScopesHashModel` lets
   templates see session and request attributes (`categories` here). The guide covers "no ambient
   authority" in general, but the FreeMarker table does not mention this implicit lookup.
6. **View names with a leading slash are not resolved** (`"/auth/register"`). Spring's other resolvers
   accept them; the guide does not mention it.
7. **No FreeMarker-compatible number formatting.** The guide's FreeMarker table maps `${x}` to `[[${x}]]`,
   but FreeMarker formats numbers with locale grouping (`90,000`) and Tessera prints `90000`. There is
   no built-in for this, and `numbers.formatDecimal` has no grouping, so an app function was needed.
   The same applies to `${date}`/`${.now}`.
8. **The guide misdescribes FreeMarker's escaping.** Its tables treat FreeMarker output as escaped. With
   Spring's default `incompatible_improvements` (2.3.0), `.ftlh` is not auto-escaped, so "same HTML"
   and "always escaped" conflict for data containing markup.
9. **`t:field` and `fields.*` reject `MultipartFile` properties**, which file-upload forms bind and
   validate. Errors for those fields had to be resolved in the controller.
10. **No computed field paths.** `t:field` cannot take an index (`list[i].name`), and the guide offers only
    `Field` fragment parameters with literal paths. Indexed Spring forms need hand-built inputs.
11. **Password inputs bound with `t:field` omit `value`**, where Spring's macros wrote `value=""`. This
    is not documented.
12. **Boolean attributes are always `name="name"`**, even through `t:attr`. The bare `disabled` form
    cannot be produced (the DOM is equivalent).
13. **Lombok getters.** `getIsApproved()` for a `Boolean isApproved` field is not found as the property
    `isApproved`; the template has to call `getIsApproved()` explicitly.
14. **No conditionals inside `<script>`.** `<t:block>` cannot be used there (script content is raw
    text), so a script with an `if/else` body had to be split into two conditional `<script>` elements.
15. **Thymeleaf-only migration tool.** `tessera-migrate` only reads Thymeleaf, so the FreeMarker port was
    done by hand.

## Notes for maintainers

- Run `mvn clean compile` after editing templates or function classes (see items 2–3).
- All number output goes through `fmt.num`, which copies FreeMarker's locale formatting, including
  inside URLs. You may want to replace it with plain numbers in links once exact HTML parity no longer
  matters.
