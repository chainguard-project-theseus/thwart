# Migration report: JSP → Tessera

## Summary

The jPrime site rendered its pages with JSP (JSTL, Spring form tags, Spring Security
taglib, tag files under `WEB-INF/tags`) through an `InternalResourceViewResolver`
(`JstlView`, prefix `/`, suffix `.jsp`). It also used Thymeleaf (view resolver disabled
with `spring.thymeleaf.enabled=false`) only to render three e-mail bodies.

All pages and those e-mails now render with Tessera 0.1.0-SNAPSHOT. JSP, JSTL, the Spring
Security taglib, Jasper and Thymeleaf are gone, along with their templates and configuration.

## What changed

### Build (`pom.xml`)
- Added `dev.tessera:tessera-spring-boot-starter` (jakarta, Boot 3.5) and the
  `tessera-compiler` annotation processor (`annotationProcessorPaths`, `<parameters>true`,
  `-Atessera.templates=${project.basedir}/src/main/resources/views`,
  `-Atessera.package=site.views`). The project uses no other annotation processors.
- Removed `tomcat-embed-jasper` (replaced by `spring-boot-starter-tomcat`, `provided`, so the
  embedded/WAR Tomcat stays exactly as before), `jakarta.servlet.jsp.jstl-api`,
  `org.glassfish.web:jakarta.servlet.jsp.jstl`, `spring-security-taglibs` and
  `spring-boot-starter-thymeleaf`.
- Java level is still 17 (`source`/`target` 17), which is what CI uses.

### Configuration
- `WebConfig`: removed the `InternalResourceViewResolver`/`JstlView` bean and the
  `configureViewResolvers` override. The Tessera starter's auto-configuration registers the
  view resolver.
- `application.properties`: removed `spring.mvc.view.prefix=/`, `spring.mvc.view.suffix=.jsp`
  and `spring.thymeleaf.enabled=false` (also from the test `application.properties`).

### Templates
- `src/main/webapp/**` (all JSPs, `WEB-INF/tags/**`) deleted. `src/main/resources/templates/**`
  (Thymeleaf mail templates) deleted.
- New templates root `src/main/resources/views` (a new directory, so no Thymeleaf leftovers can be
  picked up). View names are unchanged, so controllers still return the same strings, e.g.
  `views/index.html` or `views/admin/tag/view.html`.
- The tag files became fragments:
  - `views/fragments/user.html`: `themeColors` (was `theme-colors.jsp`, included statically),
    `css` (was `user:pageJavaScriptAndCss`), `header(String page, List<Tag> tags)` (was
    `user:header`) and `footer` (was `user:footer`).
    The header worked out the active menu item from `pageContext.request.requestURI`, the
    forwarded JSP path such as `/index.jsp`. A template can't read the request, so each page
    now passes its own name as a literal (`header('index', tags)`). This gives the same output,
    including the entries that could never match before (`tickets-epay-register`, `agenda`).
  - `views/fragments/admin.html`: `css`, `menu`.
  - `views/fragments/cfp.html`: the CFP form and the speaker sub-form (was `user:cfp`,
    `user:speaker`).
- Mail: `views/mail/newSubmission.html`, `views/mail/submissionContent.html`,
  `views/mail/ticket-message.html`. `AbstractCfpController` and `TicketService` render them with
  `XxxTemplate.of(...).renderToString(RenderContext.defaults())` and no longer use Thymeleaf's
  `TemplateEngine`.

### Java
- `@TemplateView` on the entities and form classes that templates read (`site.model.*`,
  `BranchForm`, `TicketPriceForm`, `InvoiceData`, `InvoiceDetail`).
- `site.app.PageView`: a view-safe record snapshot of a Spring Data `Page`, because `Page`
  isn't view-safe.
- Controller changes per page (see below).

### Tests
- `site.view.HtmlJspValidationTest` scanned `src/main/webapp` for `.jsp`/`.tag` files. With the
  JSPs gone it would have passed without checking anything, so it now scans the Tessera
  templates (`src/main/resources/views`, fragments in `views/fragments`). Its checks are
  unchanged.
- New `site.views.MailTemplatesTest` renders a mail template and checks that user data is
  escaped.
- No other test was changed, disabled or deleted.

### Controller and helper changes (Java)
Existing model attributes are kept (tests assert on them). Where a page needed a value a
template can read, a new attribute was added next to the old one:
- `SpeakerController`: `speakersPage` (a `PageView`). `NavController`: `articlesPage`.
  `AdminUserController`: `users` is now a `PageView`.
- `AgendaController` + new `AgendaRow` record: the JSP built the two-hall agenda table with
  `c:set` counters and index lookups across lists. That logic is now done in Java (`firstDayRows`,
  `secondDayRows`), and the dates are `LocalDateTime` instead of `java.util.Date`.
- `RaffleController`: `raffleVisitors` (a list of the now `public`, `@TemplateView`
  `RaffleVisitor`). The script gets `[[${raffleVisitors}]]` instead of a JSON string that Jackson
  built and JSP printed raw into `<script>`.
- `AdminArticleController`: `selectedTagIds` for the tag multi-select.
- `TicketsController`: the GET (and the error path when the list is empty) adds one empty
  `Visitor`, because the form binds `visitors[0].*`. Spring's form tags grew the list on their own;
  Tessera's indexed field paths don't.
- `AbstractCfpController`: `sessionTypes` is a `LinkedHashMap`, so options come out in a stable
  order. Before, it was a `HashMap` keyed by enum, whose order depended on identity hash codes.
- Template functions, needed because templates can't call methods:
  - `enums.name(x)` (`EnumNameFunctions`): JSP EL and Spring form tags printed enums with
    `name()`, while Tessera prints `toString()` (`SessionLevel` and others override it).
  - `adminView.name/raw` (`AdminViewFunctions`): `raw` is `SafeHtml.sanitize`, used where the
    admin JSPs printed `${...}` raw.
  - `branchView.*` (`BranchViewFunctions`): `branch.soldOutPackages()`, and formatting of the
    `java.util.Date` form fields of `BranchForm`, which aren't view-safe.
  - `speakerViews.submissions(speaker, branch)` (`SpeakerFunctions`): replaces the JSP's
    `speaker.branchSubmissions(selected_branch)` method call.

## Verification

- `mvn clean verify` on JDK 21, with the project still compiling for Java 17 as CI uses:
  **BUILD SUCCESS, 209 tests, 0 failures, 0 errors, 0 skipped.** That is the original 208 plus
  the new mail test.
- Page parity: `pages.txt` (in the repo root) has 115 requests. They cover every public page
  (home, speakers, a single speaker in each state and a missing one, agenda and talks, blog with
  one, several and no articles, published, unpublished and missing articles, team, venue, both
  privacy pages, PWA, QR, CFP pages, 404 and an unknown URL) and every form: CFP, tickets with
  validation errors, presence registration (not found and success, normal and JPro), signup
  (field errors, password mismatch, existing e-mail, success), reset password (invalid e-mail,
  sent), create new password (bad token, mismatch), login (failure, `?error`, success). They
  also cover logged-out access to `/admin` and, logged in as admin, every admin list, add and
  edit page, raffle (both), invoice data and logout.
  I recorded them against the original app and compared against the migrated one. Both were
  started the same way: H2 in memory, seeded from one SQL script that deliberately contains
  markup such as `<script>`, `<b>`, `&` and `<records>` in user-controlled text.
  Result: **102 `same`, 13 `differs`**. Every remaining difference comes from escaping or
  sanitising user data that JSP printed raw:
  - Article text (`/nav*`, `/nav/article*`, `/admin/article/view/1`), talk abstract and speaker
    bio (`/agenda/1`, `/admin/speaker/view`, the four `/admin/submission/view*` lists):
    stored HTML now goes through `html.sanitize`. Formatting tags like `<p>`, `<b>`, `<i>` and
    `<h2>` come out the same, but `<script>` and similar are removed, and text that only looked
    like a tag (`All about <records> & patterns`) is now escaped instead of being swallowed by
    the browser as an unknown element.
  - Tag names in the menu (`about <b>us</b>`) are now escaped text.
  - `/raffle/view`: the JSON in `<script>` writes `<`, `>`, `&` as `\u003c`, `\u0026`. The
    JavaScript values are identical, but the text differs when a name contains those characters.

  These differences are intended (guide section 1: "Values that the old engine printed raw ...
  now come out escaped. That is intended, not a difference to undo"). With data that has no
  markup in those fields, the pages come out `same`. The only way to reproduce the old output
  would be `SafeHtml.unchecked`, which is not allowed and would bring back the stored-XSS
  holes.
- Security: no `SafeHtml.unchecked` or `TrustedUrl.unchecked`, and `SecurityConfig` is
  unchanged. CSRF was already disabled in `SecurityConfig` before the migration; that is
  untouched, and no `permitAll` was added. No tests were disabled.

## What could not be converted 1:1

- **Stored rich HTML.** `html.sanitize` has a fixed policy: no `img`, tables, `class`, `style`
  or link `target`. If real articles or bios use those, they lose them, compared with the old raw
  output. Keeping them safely would need a custom HTML-to-template renderer. Tessera offers no
  configurable sanitizer, and `SafeHtml.unchecked` is ruled out.
- **The CFP form submission** is `multipart/form-data`, so it can't be driven from `pages.txt`
  (the POST is recorded as 405 on both apps). Its error state was checked by hand with a
  multipart `curl` against both apps: same errors and values.
- **Broken markup in the JSPs** had to be written in its well-formed, browser-built form, because
  Tessera needs well-formed nesting. Examples: unclosed `<div>`s on the home page that the
  browser closed at the end of the body, stray `</div>`, `<p><p>`, `<dd><dd>`, unclosed
  `<strong>` on the reset-password page. The DOM is the same, as `compare` confirms.
- **Object-level `<form:errors/>`** without a path (tickets-register, admin speaker edit) was
  dropped. Tessera has no "global errors only"; `fields.errors('*')` would also print field
  errors, and these forms never register object-level errors.
- **Some checkboxes/selects are hand-written** (`name` + `t:checked`/`t:selected` instead of
  `t:field`): `student`, `company`, `featured`, the article tag multi-select, the session
  submission/hall selects, the branch dates and the CSV upload form (`CSVFileModel` isn't a view
  type). Spring's output put the label between the checkbox and its hidden `_x` marker, generated
  ids like `published1`, and bound `java.util.Date`. On a failed POST these hand-written fields
  don't echo a rejected value; for booleans and selects that rebind from the model, they do.

## Where Tessera or its documentation fell short

1. **No JSP converter.** `tessera-migrate` converts Thymeleaf only, so all 61 JSPs and 8 tag
   files were converted by hand.
2. **Enums print with `toString()`, with no built-in for `name()`.** JSP EL and Spring form
   tags use `name()`. This isn't documented, and every enum display or option value needed a
   custom `@TemplateFunction`.
3. **A `t:params` name silently shadows a function namespace of the same name** (`branches`,
   `speakers`). The error is a misleading "method call ... is not allowed" instead of a
   name-clash message. Not documented.
4. **`t:field` details for parity with Spring form tags aren't documented:**
   - A null value leaves out `value` (Spring writes `value=""`).
   - The checkbox's hidden `_field` marker always goes right after the input.
   - Checkbox ids are `path`, not Spring's `path1`.
   - `java.util.Date` fields can't be bound at all.
   - An indexed path into an empty `List` throws at render time instead of growing the list
     like Spring's `BeanWrapper`.
5. **No object-level-only errors** (Spring `<form:errors/>` with an empty path). Listed under
   limitations only as "no global errors apart from `fields.errors('*')`".
6. **A model value that is an array, given to a `List<...>` parameter, fails only at runtime**
   (`TemplateModelException`); `Enum.values()` needs an `X[]` parameter. The guide doesn't say
   so, and the check could happen at build time for controller-returned names.
7. **`style` can't take a server-computed `String`,** even a constant like `opacity: 0.5;` from
   the controller. You need a conditional between literals.
8. **`on*` attributes can't be computed,** even as a choice between two literal handlers. The
   whole element has to be duplicated under `t:if`/`t:unless`.
9. **Well-formedness is required,** so markup that browsers repair (opened-but-not-closed
   containers, conditionally opened wrappers) needs restructuring. On the home page that meant
   chains of same-file fragments.
10. **Null-safe printing of a non-String in text or attributes** needs
    `x == null ? '' : '' + x`, because the two sides of `?:` must have the same type. It is
    documented, but verbose for something EL did by default.
11. **The guide says Tessera's resolver runs "before Thymeleaf's" but says nothing about
    ordering against an application-defined `InternalResourceViewResolver`,** which matches every
    name. During a side-by-side JSP migration that order decides whether Tessera is reached at
    all. I removed the JSP resolver up front.
12. **Programmatic rendering (needed for the e-mails) is documented in one sentence.** The
    generated class names (`404.html` → `_404Template`, `ticket-message.html` →
    `TicketMessageTemplate`) and the `of(...)` factory parameter order (that of `t:params`)
    had to be found by looking at the compiled classes.
13. **Maven doesn't recompile when only a template changes** (documented). Running `mvn clean`
    every time is slow on a 60-template project. There is no incremental or hot-reload mode.

## Files not committed

Nothing was committed. `.tessera/` (the migrate jar, run scripts, seed data, the original-app
copy used for recording) and `baseline/` are local working files, excluded via
`.git/info/exclude`. `pages.txt` is left in the repo root so the comparison can be rerun.
