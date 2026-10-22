# Migration report: FreeMarker → Tessera

## Summary

All 49 FreeMarker views (`src/main/resources/templates/**/*.ftlh`) are replaced by Tessera templates
(`**/*.html`, same paths, so every controller still returns the same view name). FreeMarker is gone:
`spring-boot-starter-freemarker` is removed from `pom.xml`, the `spring.freemarker` block is removed
from `application.yaml`, and no `.ftlh` file is left. `mvn clean package` passes, including the
existing `JobSearchApplicationTests` and one new test.

How equivalence was checked:
1. The original app (git `HEAD`) was run with a fresh H2 database, and a scripted crawl captured
   117 responses as anonymous, employer and job-seeker users. These include error pages, forms
   re-rendered with validation errors, and every reachable template (46 of 49; `auth/forbidden` is
   not returned by any controller, and `layout`/`navbar` are only included by other pages).
2. The migrated app was run against a fresh database and crawled the same way.
3. The outputs were compared after normalizing whitespace, attribute order, CSRF tokens, UUIDs and
   timestamps. The differences that remain are listed under "Known HTML differences" below; every
   one has a reason.

## What changed

### Build (`pom.xml`)
- Swapped `spring-boot-starter-freemarker` for `dev.tessera:tessera-spring-boot-starter:0.1.0-SNAPSHOT`.
- Added `tessera-compiler` to `annotationProcessorPaths`, next to Lombok and MapStruct.
- Added the compiler arguments `-parameters`, `-Atessera.templates`,
  `-Atessera.package=kg.attractor.jobsearch.templates` and `-Atessera.messages`, so message keys
  are checked at build time.
- **Excluded `org.springframework.boot:spring-boot-webmvc` from the starter.** The starter
  declares this Spring Boot **4.1.1** module as a dependency. It registers a second
  `ErrorMvcAutoConfiguration`, and the Boot 3.4 app then fails at startup with
  `BeanDefinitionOverrideException: conventionErrorViewResolver`. The starter's own classes only
  use auto-configuration annotations that Boot 3 also has, so it works once the module is excluded.
- **Turned annotation processing off for `default-testCompile`** (`<proc>none</proc>`). Otherwise
  the Tessera processor runs again while test sources compile, can't see the main-source
  `@TemplateFunctions` classes, and fails with "unknown function namespace 'fmt'".

### Templates
- `layout.html` holds the fragment `layout(Fragment content)`, replacing the `<@layout.layout>`
  macro. `layout/navbar.html` holds the fragment `navbar(UserDto authorizedUser, boolean hasUser)`,
  replacing `<#include "../layout/navbar.ftlh">`.
- Each page is a `<t:block>` root with typed `t:params`. It prints what the old page printed before
  the layout, then calls `~{layout :: layout(~{::pagecontent})}`, where `<pagecontent t:remove="tag">`
  wraps the page's own content.
- The old pages produced unusual markup: the stylesheet links come before the doctype, there is a
  second `<body>`, and the navbar include prints a second full `<!doctype html><html>` document.
  This is reproduced as it was, because the requirement is identical output.
- `hasUser` reproduces the navbar's `<#if user??>`. It is true only on pages whose model has a
  `user` attribute (profile, statistics).
- Messages use `#{key}` and are checked at build time. Spring form macros became
  `t:object`/`t:field`, or explicit `fields.errors(...)` loops where `showErrors` markup has to be
  reproduced exactly (`<span class="…">msg</span><br>…`). Manual CSRF inputs in post forms were
  removed, because Tessera inserts them itself.

### Java
- All classes in `dto/` and `exceptions/body/` are annotated `@TemplateView`.
- New package `kg.attractor.jobsearch.view`:
  - `FmtFunctions` (namespace `fmt`): `number` reproduces FreeMarker's default number format, with
    locale grouping, so `90,000` and ids `1,234`; `temporal` gives `toString()`, which is what
    FreeMarker 2.3.34 printed for `java.time` values.
  - `ViewFunctionsB`, `ViewFunctionsC`, `ViewFunctionsD`, `ViewFunctionsE` (namespaces `vb`, `vc`,
    `vd`, `ve`) replace FreeMarker built-ins and `?filter` lambdas (`?keep_after_last`, favorite
    lookup and so on). They also provide `spring.bind` values and errors for index-built paths
    (`workExperienceInfoDtos[${i}].years`), which `t:field` cannot express.
  - Records `CsrfView`, `ErrorRequestView`, `ValidationErrorView`, and `FormBindingB`.
    `FormBindingB` is an opaque holder: it has no getters and is only passed to `vb.*`.
- `config/PathVariablesModelConfig` is an interceptor that copies the resolved `@PathVariable`
  values into the model. FreeMarker's `AbstractView` exposed them, and pages print `groupId`,
  `respondId` and similar; `TesseraView` does not expose them.
- `GlobalControllerAdvice` has only additions:
  - a `csrf` model attribute, used for the one CSRF token printed outside a post form (in a GET
    form on `video/video`);
  - view-safe error attributes (`messageText`, `requestDetails`, `validationErrors`), because the
    old error page read the `HttpServletRequest` (`details`), which is never view-safe.
- Controllers have only additions:
  - Values that FreeMarker reached outside the model or through types that are not view-safe are
    now put in the model directly. This includes `categories` from the HTTP session in
    `SkillController`: FreeMarker's `AllHttpScopesHashModel` fell back to session attributes.
  - Lists of resolved error messages for `MultipartFile` fields (`imageErrors`, `avatarErrors`).
  - Typed page holders for the profile page (`resumePageHolder`/`vacancyPageHolder`).
  - String copies of `ObjectError`/`FieldError` messages.
  - Two fixes needed to render at all: `UserController` returned `"/auth/register"` with a leading
    slash, which only FreeMarker's loader tolerated, and `ChannelController` passed `ChannelAccess[]`
    where every other path passes a list.
- New test `TemplateEscapingTests` puts `<script>` and attribute-breaking input into user data and
  checks that it comes out escaped.

### Security
- No `SafeHtml.unchecked` or `TrustedUrl.unchecked`, no change to `SecurityConfig`, and CSRF is
  untouched (the tokens are now inserted by Tessera).
- Escaping got stricter in two places:
  - Message text FreeMarker printed raw (`?no_esc`, attribute strings built from messages) is now
    escaped.
  - URLs taken from data (avatars, link messages) pass Tessera's URL filter.

## Known HTML differences (and why)

1. **Event-handler attributes that contained expressions** are a Tessera build error by design.
   They now carry the data in `data-*` and use a static handler. Behaviour is unchanged.
   - `groups/groups`: `<tr onclick="window.location.href='/groups_messages/group/1';">` became
     `<tr data-href="/groups_messages/group/1" onclick="window.location.href=this.dataset.href;">`.
     The leave-group confirm now uses `data-confirm`.
   - `groups/members`: the remove-member confirm uses `data-confirm`.
2. **Duplicate and garbage attributes produced by `spring.formInput`/`formTextarea`.**
   - FreeMarker printed things like `type="text" … type="number"` and `id="x" … id="x"`. Tessera
     rejects duplicate attributes, so only the first one is kept, which is also the one browsers use.
   - On `new_groups`, `update_groups`, `new_channel` and `update_channel`, FreeMarker printed
     attribute names containing quotes and parentheses (`(spring.status.error?then('`, `+`, …),
     because an attribute string was never evaluated. Tessera cannot write such names, so the output
     is just `class="form-control"`, the class the browser actually applied.
   - The leading newline inside `<textarea>` is gone; browsers drop it anyway.
3. **Malformed markup that Tessera cannot emit.**
   - `resumes/user_resumes` had a `<span text-bg-success">` attribute name containing a quote and a
     span that was never closed.
   - `auth/register` and `messages/chat` left `<div>`s unclosed.
   - Tessera needs well-formed nesting, so the closing tags are added. The DOM a browser builds is
     the same.
4. **SVG `<path … />`** is written as `<path …></path>` (`vacancies/*`), which gives the same DOM.
   Boolean attributes render as `selected="selected"`/`disabled="disabled"` instead of bare names,
   and `&` in URL attributes is written as `&amp;amp;`.
5. **CSRF input position.** Tessera always puts the token first in a post form. In
   `auth/reset_password_form` it used to come after the hidden `token` input.
6. **Inline script values** are written as JSON. For example, `const groupId = '5'` became
   `const groupId = "5"`, and the search page's field list is a JSON array. These are the same
   JavaScript values.
7. **Pages that crashed under FreeMarker now render.** The crawl captured each of these, and the
   old output was a FreeMarker error stack trace.
   - `video/video` crashed on every request: `${likeQuantity!0}` was given a `List`. It now prints
     the like count.
   - `errors/validation_error` crashed every time, because `exception.method` is always null.
   - The re-rendered invalid-message chat page crashed on a missing `resumeId`.
   - More generally, values FreeMarker treated as a fatal "missing" (null fields) now print empty
     or omit the attribute.
8. **Validation message order** for a field with several errors is random in both engines (it
   comes from Bean Validation), so these pages differ between runs of either version.

## Not converted

Nothing. All 49 templates were converted. `auth/forbidden` is converted but no controller returns
it, so it was not exercised.

## Where the guide or Tessera fell short

- **The starter's dependencies are wrong for Spring Boot 3.** The guide's §2 says to just add the
  starter, but the starter pulls in Spring Boot 4.1.1 `spring-boot-webmvc`, and a Boot 3.4 app no
  longer starts (see Build). The guide does not state which Boot versions are supported.
- **The processor also runs during `testCompile`** with the same `compilerArgs`, and fails because
  the `@TemplateFunctions` classes are in the main sources. The guide's build snippet does not
  mention this. The workaround is `<proc>none</proc>` on `default-testCompile`.
- **Path variables are dropped.** `TesseraView` does not expose `@PathVariable` values in the model
  as Spring's `AbstractView` does. The guide (§12) says nothing about it.
- **Session and request attributes are dropped.** The guide's "no ambient context" covers these.
  But the porting checklist doesn't warn that FreeMarker's Spring view silently fell back to
  request, session (`@SessionAttributes` of other controllers) and servlet-context attributes, so
  templates that rely on it look correct and only fail at runtime with null values.
- **There is no guidance for a layout whose page content isn't a single element.** The guide shows
  `~{::body}`. It is undocumented that a selected element keeps its own tag, that `t:remove="tag"`
  on the selected element strips it, and that tag names with `-` can't be selected
  (`~{::page-content}` is a syntax error).
- **`<!DOCTYPE>` is not emitted from a fragment.** The doctype placed before a `t:fragment` root
  element is only written when that file renders as a page. The guide's layout example suggests
  otherwise. Putting the doctype inside a `<t:block t:fragment>` works.
- **`fields.hasErrors`/`fields.errors` reject paths whose property type isn't view-safe**
  (`MultipartFile`), even though they only read the `BindingResult`. Errors for upload fields
  therefore have to be passed separately.
- **Lombok `Boolean isX` getters** (`getIsApproved()`) are not reachable as `x.isApproved`; only
  `x.getIsApproved()` works. This is undocumented.
- **Dynamic, index-based field paths** (`list[${i}].field`) have no supported equivalent. The
  `Field` fragment parameter also needs a literal path.
- **The guide does not say what `t:field` prints with or without a `BindingResult`.** In the
  runtime it is `BindStatus.getDisplayValue()` or plain `toString`, and `value=""` for null.
- **`null` inside literal substitution** (`|/x/${v}|`) prints `"null"`, contrary to the guide's
  "null text renders empty".
- **Duplicate attributes are a hard error.** That is fine, but it means output produced by
  FreeMarker macros cannot be reproduced byte for byte. The guide could recommend keeping the first
  attribute.
- **The guide does not say that HTML comments are copied to the output**, or that a doctype and
  text before or after the root element are allowed. Both are allowed.
- **`tessera-migrate` handles Thymeleaf only.** For FreeMarker everything was converted by hand.
