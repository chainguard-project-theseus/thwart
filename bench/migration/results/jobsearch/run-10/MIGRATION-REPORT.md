# Migration report: FreeMarker → Tessera

## Summary

Every page is now rendered by Tessera (49 compiled templates; the starter logs
"Found 49 compiled Tessera templates" and no "another template engine is still configured" warning).
FreeMarker is gone: all 49 `.ftlh` files, `spring-boot-starter-freemarker` and the
`spring.freemarker.*` configuration are removed. `mvn clean verify` passes on JDK 21, which the app
targets (3 tests: the existing `contextLoads`, plus 2 new rendering tests).

## What changed

### Build (`pom.xml`)
- Removed `spring-boot-starter-freemarker`. Added `dev.tessera:tessera-spring-boot-starter`
  (jakarta, Boot 3.4).
- Added `tessera-compiler` to `annotationProcessorPaths` after Lombok and MapStruct, with
  `-Atessera.templates`, `-Atessera.package=kg.attractor.jobsearch.templates` and
  `-Atessera.messages` (`messages.properties`, so every `#{key}` is checked at build time).
- Added `tessera-maven-plugin` (`check` and `review` goals).
- `mvn dependency:list` before and after: the only changes are `org.freemarker:freemarker` and
  `spring-boot-starter-freemarker` out, Tessera in. `spring-context-support`, which mail needs,
  still comes in through `spring-boot-starter-mail`.
- `application.yaml`: removed `spring.freemarker.expose-spring-macro-helpers`.
- `.gitignore`: `.tessera/`, the local copy of the `tessera-migrate` jar.

### Templates (`src/main/resources/templates/**/*.html`)
- **Layout:** `fragments/layout.html`, a `layout(Fragment head, Fragment content, String bodyClass)`
  fragment, replaces `layout.ftlh`.
  - Many old pages wrote a `<link>` or `<style>` before the layout's `<!doctype html>`, so the
    browser had no doctype (quirks mode) and moved those elements to the start of `<head>`.
  - To build the same DOM, the layout writes no doctype. Pages whose old output started with the
    doctype write `<!DOCTYPE html>` themselves. Pages that had something before it pass that
    content as the `head` fragment and have no doctype.
- **Navbar:** `fragments/navbar.html`, a `navbar(UserDto authorizedUser, boolean userMenu)`
  fragment, replaces `layout/navbar.ftlh`.
  - The old navbar called the layout macro a second time from inside the page body, so the
    browser put a second set of `meta`, `link` and `title` elements in the body. The fragment
    writes those elements in the same place.
  - `userMenu` replaces the old `<#if user??>`: the user menu appeared only when the model had a
    `user` attribute.
- **Hand-written `_csrf` inputs:** removed; the Tessera starter adds the token to every same-origin
  POST form.
- **`spring.ftl` macros** were rebuilt in markup so the DOM matches:
  - `formInput` and `formHiddenInput` became `id`/`name` plus `t:value="fields.value(..)"`, so the
    page keeps writing `value=""` as before.
  - `showErrors` became `t:each` over `fields.errors(..)` with `<br>` between messages.
  - `formSingleSelect` became explicit options with `t:selected`.
- **Number formatting:** FreeMarker's default (locale grouping, up to 3 decimals) is reproduced by
  the template function `ftlfmt.number`. `?keep_after_last` is reproduced by `ftlfmt.keepAfterLast`.

### Java
- **DTOs:** every class in `kg.attractor.jobsearch.dto` is annotated `@TemplateView`.
- **Template functions:**
  - `view/FtlFormatFunctions` (`ftlfmt.number`, `ftlfmt.keepAfterLast`).
  - `view/VacancyViewFunctions` (`vacancyfn.favoriteFor`), which replaces a FreeMarker `?filter`.
- **`GlobalControllerAdvice`:** the error page no longer gets the `HttpServletRequest` (`details`).
  It gets an `ErrorPageDetails` record with the strings the page shows. The validation-error page
  gets a `ValidationErrorView` record.
- **`SkillController`:** the "back to the resume/vacancy form" handlers now add `categories`.
  FreeMarker had quietly read it from the HTTP session (`@SessionAttributes`), which a Tessera
  template can't see.
- **`ProfileServiceImpl`:** the model key `pageHolder` held a page of resumes or a page of
  vacancies depending on the role. It is now `resumePageHolder` / `vacancyPageHolder`, because a
  Tessera parameter has a single type.
- **`RespondApplicationController`, `FavoritesController`, `GroupsMessagesController`:** put
  message strings in the model instead of `FieldError`/`ObjectError`/`BindingResult` objects.
- **`VideoController`:** puts `mediaType` in the model as a String.
- **`ChannelController`:** passes `ChannelAccess` values as a `List` on the error path too.

### Tests
- `contextLoads` is unchanged.
- New `TesseraViewsTests` renders the login error page through the real resolvers, checking the
  automatic CSRF input. It also checks that a script tag sent as a request parameter is not
  echoed raw.
  - That second check is weak. I did not confirm that the dynamic-search page echoes the
    parameter at all, so the test may pass without testing any escaping.

## Security
- No `SafeHtml.unchecked`, `TrustedUrl.unchecked` or `Fragment.unverifiedForGeneratedCode`;
  `target/tessera-review.txt` lists 0 unchecked calls.
- No `permitAll` added, CSRF and escaping left on, no tests disabled or weakened.
- Event-handler attributes that interpolated data (group-list rows, the "leave group" and
  "remove member" confirmations) now read their values from `data-*` attributes and `title`/`value`
  instead of having data inserted into JavaScript.

## Page parity (`tessera-migrate compare`)

`pages.txt` (94 requests: anonymous, job seeker, employer; forms empty, with errors and after
submit; error pages) recorded from the original app into `baseline/`. Compare run with
`--ignore` for error-page timestamps, Tomcat request ids and seed-data creation times. Those
values change on every run of the original app too.

Result: **74 same, 2 reordered, 18 differ.** The reorders are validation messages whose order
Hibernate Validator doesn't fix. The 18 that differ:

| Pages | Cause | Why it isn't a template defect |
|---|---|---|
| 08–11, 27, 34, 76 (vacancy lists) | Vacancies come back in a different order on each run. The seeded rows share `created` timestamps, so ties are unordered. On logged-in pages, also the `currentUser` script line. | The original app compared with its own recording differs the same way. With `?size=100` the lists are `same`. The script line: Tessera allows a value in a `<script>` only as a declaration at the top, so `window.currentUser = "x";` became `const currentUserEmail = ("x"); window.currentUser = currentUserEmail;`. It behaves the same, but the text can't be identical. Anonymous pages keep the exact static `window.currentUser = null;`. |
| 30, 32 (update profile) | The hidden password field echoes the bcrypt hash. | A new random salt every run, in the original app as well. |
| 42 (resume form with errors) | The original app crashed halfway through rendering (`resume.contactInfos` null), so no response was recorded. | The new page renders the form with its validation errors. Reproducing a crash would be wrong. |
| 50–53, 60–63 (new group, new channel forms) | The old `spring.formInput` call got its attributes as one literal string, so the page printed FreeMarker source as attribute *names* (`'')`, `(spring.status.error?then('`, `+`, `is-invalid',`). | Tessera rejects attribute names that contain quotes, which is malformed markup. Only `class="form-control"` is kept; everything else on these pages matches. Page 61 also has a validation-message reorder. |

Pages and states beyond `pages.txt` were recorded and compared per area during the work, with the
same results:
- logged-in employer and job seeker (seeded users);
- resume, vacancy, profile, group, channel and video forms with errors and after success;
- the skills pages, chat, members and invite link.

Uploads (group avatars, chat files, videos) were created with curl, because `tessera-migrate`
can't send multipart.

## What could not be converted identically
- **`video/video`:** the old page always crashed partway through rendering. It printed a `List`
  (`${likeQuantity!0}`) and a `LikeDto` with `${}`. The new page renders the like count and uses
  the like's id in the delete link.
- **`errors/validation_error`:** the old page crashed on `exception.method`, which is always null.
  The new one renders and leaves null values empty.
- **`window.currentUser` script and the `onclick` handlers:** changed as described above,
  because of Tessera's script-prelude and `on*` rules.
- **Malformed attributes** from misused `spring.formInput` calls: dropped.
- **`search_vacancies_dynamic`:** the favourites block depended on a `favorites` attribute that
  no handler sets. It was dead code and was removed.
- **Ids above 999:** FreeMarker printed ids in URLs with grouping (`1,000`), which made broken
  links. Tessera prints `1000`; that bug was not reproduced.
- **`chat.ftlh`:** left a `<div>` unclosed. Tessera requires well-formed templates, so the
  `</div>` sits before `</body>`, which builds the same DOM.

## Where Tessera and its documentation fell short
1. **No FreeMarker section in guide 4.10.** There are notes for JSP, Velocity and Pebble, but
   nothing maps FreeMarker idioms (`x!d`, `??`, `?then`, `?contains`, `?keep_after_last`,
   `?filter`) or the `spring.ftl` macros (`formInput`, `showErrors`, `formSingleSelect`, `bind`).
2. **Implicit scope fallback isn't reported for FreeMarker.** FreeMarker reads names missing from
   the model from request and session attributes (here `categories` from `@SessionAttributes`).
   `tessera-migrate` reports scope reads for Thymeleaf only; for FreeMarker they show up only as
   empty values in compare.
3. **`t:field` and empty values.** The guide says the value is omitted when null. In practice,
   with a `BindingResult` present a null becomes `value=""`, and without one the attribute is
   dropped. A static `value=""` next to `t:field` is silently discarded. Several of us dropped
   `t:field` for `t:value="fields.value(..)"` to get a stable result.
4. **No FreeMarker-style default number format.** `numbers.formatDecimal` needs a fixed number of
   decimals and `formatInteger` rounds, so a custom `@TemplateFunction` was needed. There is also
   no `lastIndexOf`/`keep_after_last` in `strings`.
5. **Script values.** `[[...]]` is allowed only as a top-of-script declaration and always comes
   out in parentheses (`const x = ("a");`). The guide calls the resulting differences expected,
   but `compare` still reports them as `differs` and offers no way to mark them other than
   `--ignore`.
6. **`on*` attributes.** The guide's recipes cover `data-*`, but not handlers that build a URL or
   `confirm()` text from data. Each such page always compares as `differs`.
7. **A `Field` argument can't be a path with a variable index**
   (`frag(*{list[st.index].x})` fails with "pass a field path as a string literal"), so one error
   fragment couldn't be reused inside list loops. The guide doesn't state this limit.
8. **Attribute names with quotes are rejected, while an attribute named `,` is accepted.** That is
   inconsistent, and it makes parity impossible for old pages that relied on browser error
   recovery.
9. **Empty link path variables.** `@{/users/avatar/{a}(a=...)}` fails the render for `""` from
   real data (registration stores an empty avatar). The guide talks about null, and only briefly
   about empty values.
10. **`|...|` literals can't contain `|`.** The error ("unexpected '$'") doesn't point at the
    cause, and neither the guide nor the diagnostics catalogue mentions escaping it.
11. **Doctype placement** before an `<html t:replace=...>` page isn't documented (whether the page's
    or the layout's doctype is written). We found out by testing.
12. **Possible SVG rule mismatch.** A concatenated `t:stroke-dasharray="|${n}, 100|"` on an SVG
    path compiled, although the guide's table says SVG presentation attributes take only literals
    and numbers. Either the table or the compiler is wrong.
13. **`tessera-migrate` gaps:**
    - it can't send multipart requests, so upload states can't be created from `pages.txt`;
    - today's date is replaced with `TODAY` before `--ignore` runs, which is not documented, so a
      full-date regex never matches;
    - validation messages separated by `<br>` that come back in another order count as `differs`
      rather than `reordered`;
    - there is no recipe for nondeterministic data order in the old app.
