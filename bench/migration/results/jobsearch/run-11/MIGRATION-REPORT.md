# Migration report: FreeMarker → Tessera

## What changed

**Build (`pom.xml`)**
- Added `tessera-spring-boot-starter` (Boot 3 / jakarta) and the `tessera-compiler` annotation processor, listed after Lombok and MapStruct.
- Added the compiler args `tessera.templates`, `tessera.package=kg.attractor.jobsearch.templates` and `tessera.messages` (so every `#{key}` is checked at build time), plus `<parameters>true</parameters>`.
- Added `tessera-maven-plugin:check`, so an edit to only a template or a message file still triggers a recompile.
- Removed `spring-boot-starter-freemarker`. In `mvn dependency:list`, the only artifacts that disappear are `freemarker` and the starter itself; `spring-context-support` is still there through the mail starter.
- Removed `spring.freemarker.*` from `application.yaml`.
- Added `.tessera/` (the migration tooling) to `.gitignore`.

**Templates**
- All 49 `.ftlh` files under `src/main/resources/templates` are now `.html` Tessera templates with the same view names. No `.ftlh` file is left, and the app starts without the "another template engine is still configured" warning.
- `layout.ftlh` (a macro with `<#nested>`) became the fragment `layout :: layout(Fragment head, String bodyClass, Fragment content, Fragment tail)`.
  - The old pages wrote broken documents: content before `<!doctype>`, a whole second document from `<#include "../layout/navbar.ftlh">`, and `<body class>` in the middle of the body.
  - The new templates produce the DOM browsers built from that markup, without relying on browser error recovery.
  - `head` is what a page printed before the layout call, `tail` is what it printed after it, and `bodyClass` is the merged `<body class>`. A page has a doctype only where the original's survived parsing.
- `layout/navbar.ftlh` became the fragment `layout/navbar :: navbar(UserDto authorizedUser, Boolean hasUserAttribute)`. It writes the stray `<style>`, `<meta>`, `<link>` and `<title>` elements browsers saw.
- `spring.ftl` macros became `t:object` / `t:field` / `fields.errors(...)`.
- Hand-written `_csrf` inputs were removed: Tessera injects the token into same-origin POST forms.
- FreeMarker's default number formatting is now `fm.num`, and `?keep_after_last` is `fm.afterLastSlash` / `groupview.keepAfterLast`.

**Java (data the templates used to find outside the model)**
- `config/NavbarModelConfig`: an interceptor that adds `hasUserAttribute`. The old navbar tested `<#if user??>`, which is true whenever the page's model has a `user`.
- **`GlobalControllerAdvice`:**
  - `details` was the `HttpServletRequest`; it is now the `view.auth.ErrorDetails` view record, with the host, URL, port, request id and formatted timestamp.
  - The validation exception is wrapped in `view.auth.ValidationErrorView`.
  - The method-validation handler's message is converted to a `String`.
- `SkillController`: the `returnNew/UpdateVacancy` and `returnNew/UpdateResume` handlers now copy the `categories` session attribute into the model. FreeMarker found it in the session by itself.
- `UserController.getProfile`: adds `resumesPage` / `vacanciesPage` as typed lists.
- `RespondApplicationController`: adds `errorMessage` as a `String`, because a `FieldError` isn't view-safe.
- `VacancyController`: adds `favoriteErrors` from the flash `errors`.
- `GroupsMessagesController`: adds `messageErrors` instead of the raw `BindingResult`.
- `VideoController`: adds `videoMediaType` as a `String`, because `MediaType` isn't view-safe.
- `ChannelController`: puts a `List` instead of an array in the model, for consistency.
- Every class in `kg.attractor.jobsearch.dto` is annotated `@TemplateView`.
- New `view` package: `FmFunctions` (`fm.*`), `vacancies/VacancyFunctions` (`vac.*`), `groups/GroupViewFunctions`.
- `static/css/vacancy.css`: added rules for the skill-match circle recipe described below.

**Unchanged:** security configuration, CSRF, the one existing test, and Spring and Boot versions. Nothing uses `TrustedUrl.unchecked`, `SafeHtml` or `t:utext`.

## Verification

- `mvn clean verify` on JDK 21 (the `java.version` the project targets) passes, including `JobSearchApplicationTests`.
- `.tessera/pages.txt` has 120 requests:
  - logged out, as an employer (`Tom@gmail.com`) and as a job seeker (`Simon@gmail.com`);
  - every form empty, with validation errors and after success;
  - 400 and 404 error pages.
- It was recorded from the original app and compared against the new one, both started fresh on the same port. Run-to-run noise was masked: CSRF tokens, timestamps and seed `NOW()` dates.
- **Result: 94 `same`, 8 `reordered` (validation messages come from a `HashSet`), 18 `differs`.** Every remaining difference is one of the following:

1. **Not determined by the templates (5 requests: `/`, the two post-login landing pages, the profile after update, part of the vacancy lists).** Every seed row has the same `created` timestamp, so `ORDER BY created ... LIMIT` returns a different set of rows on each fresh start. Comparing the original app against its own recording gives the same differences.
2. **The skill-match circle on vacancy lists (`/`, `/vacancies*`, `/vacancies/actives`).**
   - The old page computed `stroke-dasharray` and the `<text>` inside an `<svg>`. Tessera forbids any value inside `<svg>`.
   - I used the guide's CSS-variable recipe: `style="--progress: N"` plus a colour-band class on the wrapper `<div>`, and an HTML `<span>` label instead of `<text>`.
   - I did not write one static `<svg>` per percentage value (101 cases), because the guide lists that as a workaround not to use.
3. **The `window.currentUser` script on vacancy pages.** A value may only be the whole value of a top-of-script declaration, so the script is now `const currentUserEmail = [[...]]; window.currentUser = currentUserEmail;`. It behaves the same.
4. **Broken markup the old engine passed through, which Tessera refuses to compile:**
   - **Channel and group forms** (`/channel/new_channel`, `/channel/create`, `/channel/update_channel/1`, `/channel/update`, `/groups/new_groups`): `formInput` with `class='form-control' + (spring.status.error?then(...))` inside a string literal. It printed junk attribute names such as `+` and `(spring.status.error?then('`.
   - **`/resumes/users`:** a `<span text-bg-success">` attribute name containing a quote.
   - The new templates write the intended clean attribute.
5. **`POST /like/create videoId=` (`errors/validation_error`).** The original page always fails part-way, because `ErrorServiceImpl` never sets `method` and FreeMarker aborts. That was recorded as a truncated response with status 0. The new page renders completely with status 400 and an empty Method, as the guide asks for error pages.

**Intended differences not visible in the recorded data:**
- **Groups pages:**
  - Values the old templates printed raw are now escaped.
  - A stored `javascript:` link message renders as `about:invalid#tessera`.
  - `onclick` handlers no longer get computed values or messages: they read the row's cells or the button's `title`, which hold the same text.
- **`video/video`:**
  - The Dislike form action printed the whole `LikeDto` object; it now uses `like.id`.
  - `likeQuantity` printed a `List`; it now prints its size.
  - A hand-written `_csrf` in a GET form, which would have leaked the token into the URL, was removed.

## What could not be converted or checked

- Everything was converted. The following could not be checked with compare:
  - **`video/video` and the groups pages with an existing group:** the data needs a multipart upload, which the compare tool can't send. The helper that converted groups checked those pages against the original by inserting group data with SQL (38/40 same; the 2 others are the ordering noise above).
  - **`auth/reset_password_message`:** needs an emailed token.
  - **`auth/forbidden`:** no handler returns it.
- **App bugs left as they are, since the migration doesn't change behaviour:**
  - Registration can't create an employer: `UserMapper` and `Util.convertToSneakyCase` disagree on the role name.
  - **`/skills/*` add/delete forms.** In the original these POST forms (`/skills/resumes/new_skills`, `/skills/resumes/delete`, and the vacancy equivalents) rendered without a CSRF token, so submitting them always returned 403.
    - Tessera injects the token into every same-origin POST form, so they now work. A request that posts one of these forms right after its page now gets the handler's response instead of 403.
    - This is a fix, not a weakening, and Tessera gives no way to turn it off.
    - `pages.txt` doesn't include these POSTs, because the original only ever answers 403.
  - `ErrorServiceImpl` never sets `method`.

## Where Tessera or its documentation fell short

- **Error-recovered input.** There is no guidance for migrating from an engine whose pages relied on browser error recovery: a full document nested in the body by an include, content before the doctype, mid-body `<body class>`. You have to work out the browser DOM yourself; I used jsoup. A `tessera-migrate` command that prints the parsed DOM of a recorded page would help.
- **Broken old markup.** There is no way to reproduce junk attributes the old page emitted (`quote ' in a tag or attribute name`). The guide says broken markup the old engine passed through is an accepted difference, but `compare` still fails those pages, so a compare exit code of 0 is unreachable for them.
- **Recipes change the page.** The SVG recipe and the script-declaration rule necessarily change the DOM, so those pages can never be `same`. `compare` has no way to mark an accepted, explained difference other than `--ignore`, which would hide too much.
- **No FreeMarker tool.** `tessera-migrate` only rewrites Thymeleaf; FreeMarker had to be converted by hand. The FreeMarker notes (4.10) don't mention three things:
  - Spring's FreeMarker view resolves missing names from request and session attributes. That is how `categories` and `_csrf` were found.
  - FreeMarker's locale-formatted `${number}` default (`90000` prints `90,000`), which needs a custom function.
  - `spring.showErrors` reuses the last `spring.bind`, so errors repeat after later fields.
- **`compare` and `--ignore`:**
  - It is not documented that `compare` rewrites today's date to `TODAY` before `--ignore` runs, so patterns written for the raw text silently don't match.
  - It is not documented which text `--ignore` sees. A pattern copied from the printed diff (`const currentGroupId = [0-9]+;`) did not match.
  - The error-page timestamp contains U+202F, which made a regex fail silently.
  - `record` rejects `--ignore` and only prints the usage.
- **Compiler messages:**
  - A duplicated `<html>` start tag inside `<head>` was reported as "element `<html>` is not closed" at the first `<html>`, not at the duplicate.
  - The guide doesn't say whether `t:field` on a password input writes `value`.
  - It doesn't say which output context `<source type>` belongs to.
- **Path variables.** The guide mentions in passing that `@PathVariable`s fill same-named `t:params`; several pages rely on it.
- **Workflow.** Nothing warns that a `compare` run mutates the new app's data, so the app has to be restarted from fresh data before each compare.
