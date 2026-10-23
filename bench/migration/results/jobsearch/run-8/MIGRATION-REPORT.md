# Migration report: FreeMarker → Tessera

## What changed

- **Build** (`pom.xml`): `spring-boot-starter-freemarker` removed; `tessera-spring-boot-starter`
  added; `tessera-compiler` added to `annotationProcessorPaths` **after** Lombok and MapStruct
  (if it comes first, Lombok getters are invisible to it); `-Atessera.templates`,
  `-Atessera.package=kg.attractor.jobsearch.templates`, `-Atessera.messages` (keys checked at build
  time against `messages.properties`); `<parameters>true</parameters>`.
- **Config**: `spring.freemarker.*` removed from `application.yaml`.
- **Templates**: all 49 `.ftlh` files removed (including `layout.ftlh` and `layout/navbar.ftlh`) and
  replaced by `.html` Tessera templates at the same view names. The shared layout and navbar are now
  the fragments `fragments/layout.html` (`layout(Fragment head, Fragment content)`) and
  `fragments/navbar.html` (`navbar(UserDto authorizedUser, boolean userMenu)`). The old navbar showed
  the user menu when the model happened to contain `user`; pages now pass that flag explicitly.
- **View types**: `@TemplateView` on the classes in `dto/`, `model/` and `exceptions/body/`.
- **Controllers and model**:
  - `GlobalControllerAdvice` no longer puts the `HttpServletRequest` into the model. It puts a
    view-safe `ErrorDetails` record there instead.
  - `ValidationErrorBody` has a String getter for the rejected value.
  - `UserController` returns `auth/register` instead of `/auth/register`, and its profile model has
    typed `resumePage`/`vacancyPage` entries.
  - `ChannelController` now passes the channel access values as a `List` in the update error
    branch.
  - `VideoDto` has a media-type String getter.
  - The view-function classes `util/view/FtlFunctions` and `util/ViewFunctions` reproduce
    FreeMarker's grouped number output (`9,000`).
  - Other controllers were changed where their templates needed values from outside the model.
- **Tests**: existing test unchanged and passing. New `TesseraRenderingTests`: it checks that user
  data is escaped, and it renders a page through the real view resolver with a CSRF input.
- `pages/*.txt`: the request lists used with `tessera-migrate record/compare`, one per area.
  Run each list against a freshly started app.

## Status / verification

`mvn clean verify` passes on JDK 21 with FreeMarker removed: 3 tests, 0 failures.

Compare results (each list against fresh original and new apps). Times of day are masked: seed
rows use `CURRENT_TIMESTAMP` and the error pages print the current time. The port is masked too,
because my two apps ran on different ports.

| list | result |
|---|---|
| core | 8/8 same |
| auth-errors | 42 same, 2 reordered (validation messages from a Set, CSRF input position), 1 differs (login redirect to a vacancies list, see below) |
| users-channel | 32 same, 6 differ (channel create/update forms, see below) |
| groups | 13 same, 2 differ (`/groups`, `/messages/chat/1`) |
| resumes | 20 same, 32 differ |
| vacancies | 12 same, 1 reordered, 54 differ |

**These aren't finished.** The vacancies, resumes/skills and groups/messages templates were
converted by parallel workers. I had to stop those workers before their compare loops finished,
because I hit my time and budget limits. Every template compiles and every page renders through
Tessera, but many pages in those areas still differ from the original. Some of those differences
are the vacancy list order and grouped numbers. Others are small text and markup changes, such as
`Employer : Tom` against `Employer: Tom` and icon class names. Some vacancy-list differences may come
from the seed data rather than the templates: all seed vacancies get the same
`CURRENT_TIMESTAMP`, and an earlier run showed their order changing between runs of the original
app itself. I haven't separated the two causes. Fixing the rest needs more compare/fix rounds on
those templates.

### Differences I believe are correct / unavoidable

- **Channel new/update forms** (6 pages). The old template passed a plain string expression to
  `spring.formInput`, so it printed text like `class='form-control' + (spring.status.error?then(...`
  inside the tag. The browser turned that text into junk attribute names containing quotes and
  parentheses. Tessera rejects such attribute names at compile time. The new page has the intended
  `class="form-control"`.
- **`errors/validation_error`** can't render in the original: `${exception.method}` is null and
  the render aborts. The Tessera version renders, with an empty method.
- **`auth/forbidden`** is never returned by any controller, so it is checked by the build only.
  The same goes for `auth/reset_password_message`, which needs a random reset token.

## Security

No `SafeHtml.unchecked` or `TrustedUrl.unchecked`, no new `permitAll`, CSRF untouched (Tessera adds
the `_csrf` input to POST forms automatically; the hand-written inputs were removed), no escaping
disabled, and no tests disabled.

## Where Tessera or its documentation fell short

- **Processor order with Lombok.** If `tessera-compiler` is listed before Lombok in
  `annotationProcessorPaths`, every Lombok getter is reported as "not a property". The guide says to
  list other processors but not that order matters.
- **No way to reproduce broken legacy markup.** Attribute names with quotes or parentheses don't
  compile, so pages whose old markup was broken can't be made identical.
- **Doctype and quirks mode.** Old pages emitted elements before `<!doctype>`, which puts the
  browser in quirks mode with no doctype node. To match that, the page must leave out
  `<!DOCTYPE>`, and extra head content is passed with `~{::head}` and `<head t:remove="tag">`. The
  guide shows neither pattern.
- **`<select t:field>` and empty strings.** It doesn't select the `value=""` option when the bound
  value is `""`; Spring's `formSingleSelect` does. Worked around with `t:selected`.
- **Spring `showErrors` markup.** Reproducing it (one element per error, joined by `<br>`) needs a
  hand-written loop. `t:errors` doesn't fit, and the guide has no recipe for it.
- **`Object`-typed getters.** They are rejected even when the page only prints the value, and
  `'' + x` doesn't help. A String getter had to be added.
- **`?:` branch types.** Both branches must have the same type (`x == null ? 0 : obj.id` with a
  Long fails), and there is no null-safe "print empty for null" helper for non-strings.
- **Number formatting.** `numbers.*` has no grouping or locale, so FreeMarker's default `${n}`
  output (`9,000`) needed a custom `@TemplateFunction`.
- **One parameter, several types.** A template parameter can't have a different generic type per
  branch (`PageHolder<ResumeDto>` or `PageHolder<VacancyDto>`), so the controller splits it into
  two attributes and the template duplicates the pagination markup.
- **`MultipartFile` fields.** `fields.hasErrors('avatar')` doesn't work on a `MultipartFile`
  field, because the type isn't view-safe, even though only the errors are read.
- **compare tool masking order.** Today's date is masked to `TODAY` before `--ignore` runs, so
  ignore patterns must target the masked text. This isn't documented.
- **compare tool requests.** It sends only urlencoded POSTs, so multipart forms (group, channel and
  video creation with file uploads) can't be exercised in their success state.
