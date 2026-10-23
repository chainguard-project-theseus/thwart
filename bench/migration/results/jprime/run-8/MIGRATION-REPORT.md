# Migration report: JSP → Tessera

## Summary

All server-rendered pages of the jPrime site now render through Tessera 0.1.0-SNAPSHOT. Before the
migration they were rendered by JSP, JSTL, Spring form tags, Spring Security taglibs and
`WEB-INF/tags` tag files. The 61 JSP files (59 pages plus the `footer.jsp` and `theme-colors.jsp` includes) and 8 tag files are deleted. The JSP dependencies
(`tomcat-embed-jasper`, JSTL API and implementation, `spring-security-taglibs`) are gone, and so is
the JSP configuration (`InternalResourceViewResolver`/`JstlView` in `WebConfig`,
`spring.mvc.view.prefix/suffix`).

- `mvn clean verify` on JDK 21 passes: **209 tests, 0 failures**. That is the 208 existing tests
  plus one new test. No test was changed, disabled or deleted.
- The app compiles at `source/target 17`, the level the project and its CI target.
- `tessera-migrate compare` ran over two request lists (details below), 217 requests in total.
  Every page has its recorded status and final URL. Every page is `same` or `reordered`, except
  pages where the old app printed HTML from the database unescaped.
- Security config is unchanged: no `SafeHtml.unchecked`, no `TrustedUrl.unchecked`, no new
  `permitAll`, no CSRF or escaping changes.

## What changed

### Build (`pom.xml`)
- Added `dev.tessera:tessera-spring-boot-starter` (Boot 3.5 / jakarta). Added `tessera-compiler` as
  an annotation processor with these options:
  - `-Atessera.templates=src/main/resources/views`
  - `-Atessera.package=site.views`
  - `-Atessera.messages=src/main/resources/messages`
- Removed `tomcat-embed-jasper`, `jakarta.servlet.jsp.jstl-api`, `jakarta.servlet.jsp.jstl` and
  `spring-security-taglibs`.
- Jasper had been the only thing pulling Tomcat in, because `spring-boot-starter-tomcat` is
  excluded from `spring-boot-starter-web`. It is replaced by `spring-boot-starter-tomcat` in
  `provided` scope. The packaging is unchanged: an executable war that also deploys to an
  external container.
- Templates live in `src/main/resources/views`, not `templates/`. `templates/` already holds the
  **Thymeleaf e-mail templates** (`ticket-message.html`, `newSubmission.html`,
  `submissionContent.html`). Tessera would try to compile every `*.html` under its root.

### Templates (`src/main/resources/views`)
- Each JSP `X.jsp` became `views/X.html`, so view names and controller return values are
  unchanged.
- The tag files became fragments:
  - `fragments/user.html`: `header(activePage, tags)`, `footer`, `pageJavaScriptAndCss`, and
    `themeColors`, which was the included `theme-colors.jsp`.
  - `fragments/admin.html`: `menu` and `pageJavaScriptAndCss`.
  - `fragments/cfp.html`: the CFP form and speaker sub-form. The speaker fields are passed as
    `Field` parameters.
- The header used to mark the active menu item from `pageContext.request.requestURI`, the
  forwarded JSP path. Each page now passes that literal path, for example `'/index.jsp'`, to
  `header(...)`. The output is identical.
- Spring form-tag output is reproduced where `t:field` writes something different:
  - Spring always writes `value=""`, so those inputs set `id`/`name`/`t:value` by hand.
  - Checkbox ids are numbered (`active1`), as Spring numbers them.
  - Enum option values and labels use `name()`.
  - The Break/TBA and entity-id `selected` logic of the session form is kept.
  - Error spans keep `id="field.errors"`.
- `site.views.fn.ViewFunctions` (namespace `view`) holds the few helpers the templates need:
  - `el` prints values as EL did: enums by `name()`, null as "".
  - `date` and `dateTime` replace `fmt:formatDate` and the app's `LocalDateTime` converter.
  - `containsId`, `visitor`, `soldOutPackages` and `branchSubmissions` wrap model methods that
    take arguments.

### Java
- `@TemplateView` is added to the entities in `site.model`, and to `BranchForm`,
  `TicketPriceForm`, `InvoiceData` and `RaffleController.RaffleVisitor`.
- `BranchForm` gets `get*DateText()` getters. Its dates are `java.util.Date`, which is not
  view-safe.
- Controllers that put a Spring `Page` in the model now also add, or for `AdminUserController`
  instead add, the content `List`, because `Page` is not view-safe:
  - `NavController` adds `articleList`.
  - `SpeakerController` adds `speakerList`.
  - `AdminUserController` now puts the `users` list itself in the model.
- `AgendaController`: the JSP's row-pairing logic moved into Java. It now builds `AgendaRow`
  records: a counter carried over both days, `colspan` and `rowspan`.
- `RaffleController`: it used to put a Jackson JSON string in the model for a `<script>`. It now
  puts the list, and the template writes `[[${visitors}]]`.
- `WebConfig`: the JSP view resolver is removed.
- New test `site.view.TesseraEscapingTest`. It renders `/team` through the real Tessera resolver
  with a tag named `<script>alert(1)</script>` and asserts that the tag comes out escaped.

## How parity was checked
The original war and the new war both run exploded, with H2 in place of MySQL. Both use
`ddl-auto=create` and Flyway off, so each starts empty from the same state.

- **`.tessera/pages.txt`** (125 requests) starts from an empty database. It covers:
  - the error/404 page;
  - login: good, bad, logout;
  - creating a branch, tags, articles, halls, users, registrants, visitors and sessions through
    the admin forms;
  - every admin list and edit page;
  - invalid form posts: the tickets form shows a captcha error, reset password shows its message
    page, and signup, speaker, sponsor and partner come back to the form. The old app shows no
    field errors in those last four recorded states. The signup error spans were checked
    separately by rendering with injected errors;
  - every public page, before and after data exists;
  - CFP open, and tickets sold out;
  - unknown URLs.
- **`.tessera/pages-seed.txt`** (93 requests) runs against the same apps started with a fixed
  SQL seed (`--spring.sql.init.*`, kept outside the app). The seed adds speakers, co-speakers,
  submissions in every status, sessions in all halls including Break/TBA, sponsors, partners,
  articles, registrants with visitors, a JPro visitor and a background job. That makes the
  data-heavy pages reachable: agenda, talk, speaker, admin submission views and edits, session
  edits, invoice, raffle, QR presence forms. It is needed because `tessera-migrate` cannot post
  multipart forms, so speakers, sponsors and submissions cannot be created from a request list.

Remaining non-`same` results, all judged correct:

1. **`reordered`: CFP and submission forms (`/cfp`, `/admin/submission/add`, `/admin/submission/edit/*`).**
   The session-type options come from a `HashMap<SessionType,String>` built in
   `AbstractCfpController`. Its iteration order follows enum identity hash codes, so it changes
   from one JVM run to the next in the old app too. Harmless.
2. **`differs`: data containing HTML markup.** Affected: article title, tag `News & <Events>`,
   submission description, speaker bio and headline. JSP EL `${...}` printed these raw, and the
   seed includes a speaker headline with `<script>alert(1)</script>`, a stored XSS in the old
   app. Tessera escapes them. That is intended and was not undone. Nothing else differs on those
   pages.

Volatile text masked with `--ignore`: only `value="dd.MM.yyyy HH:mm"`. These are the hidden
`createdDate` inputs, which hold the creation time.

## What could not be converted 1:1
- **Admin-authored HTML is now shown escaped.** This affects articles and the speaker bio,
  headline and description on public pages. The old pages printed it as live markup, which was
  also an XSS hole. Tessera can only output raw markup from a `SafeHtml`, and the only way to
  build one from a string is `SafeHtml.unchecked`, which this migration must not use. Tessera has
  no sanitiser-backed constructor. If formatted articles are wanted, the right fix is a vetted HTML
  sanitiser plus a sanctioned `SafeHtml` factory for its output.
- **`<form:errors/>` with no path (object-level errors only) is dropped.** It appeared on the
  tag, branch, article and other edit forms. Tessera has only `fields.errors('*')`, which also
  includes field errors. None of these controllers ever add global errors, so the output is
  unchanged.
- **Raffle JSON may now include `null` properties.** It used to go through the app's Jackson
  `ObjectMapper`. Tessera's `[[...]]` serialiser ignores
  `spring.jackson.default-property-inclusion=non_null`, so a visitor with no company would now get
  `"company":null`. The raffle script only reads names, so this is harmless.
- **Thymeleaf stays, for e-mail bodies only.** `TicketService` and `AbstractCfpController`
  render e-mail bodies from `templates/*.html`, with `spring.thymeleaf.enabled=false` for MVC.
  These are not pages the application serves, so they are out of scope.
- **Leftover JSP names.** Constants named `*_JSP` in controllers are kept, because the existing
  tests import them. `HtmlJspValidationTest` and the `pr-validation.yml` step that runs it still
  scan `src/main/webapp`, which no longer exists, so the test passes vacuously. I left it alone
  because tests must not be changed.
- **Markup the browser repairs is now written out repaired.** Tessera rejects unbalanced markup,
  so pages that relied on browser error recovery now spell out the tree the browser built:
  - `index.jsp` had stray `</section>` tags that made later sections nest;
  - `resetPassword.jsp` had unclosed `<strong>` tags;
  - several pages had unclosed `<p>` and duplicate attributes.

  The DOM is the same, but the source now carries some odd nesting with comments explaining it.

## Where Tessera or its documentation fell short
1. **No way to build `SafeHtml` from sanitised input** other than `unchecked`, and no bundled
   sanitiser. Any app with rich-text content loses formatting or has to use `unchecked`.
2. **`t:field` value semantics are underdocumented and differ from Spring's form tags.**
   - The guide says a null value omits `value`, but agents saw `value=""` in some cases (null
     `String`) and no `value` in others.
   - A static `value` is silently replaced.
   - Checkboxes get Spring's hidden `_field` input but not Spring's numbered `id`.
   - Enum options and values use `toString()`, where Spring and EL use `name()`.

   None of this is in the guide; we found it by rendering.
3. **Multi-selects bound to a collection are unsupported.** They get no `selected` and no `_field`
   input. `<select t:field>` options also can't be matched by an entity id, so they had to be
   hand-written.
4. **No object-level-only error selector**, so `<form:errors/>` can't be reproduced.
5. **Parameterless fragments can't see page variables**, so a plain `~{::name}` selection is
   useless once its content reads model data. You need dummy parameters. `~{::name}` of a
   `<div t:fragment>` also includes the `div` itself, which is easy to get wrong; use
   `<t:block t:fragment>`.
6. **No conditional static attributes.** Expressions are banned in `style`, so a computed inline
   style must be written as two alternative elements via fragments.
7. **Spring `Page<T>` and `java.util.Date` are not view-safe, not even through a function.** The
   controller has to unwrap them.
8. **`[[...]]` JSON ignores the application's Jackson configuration** (`non_null`). The guide only
   mentions annotations.
9. **The guide doesn't say what `t:each` does with a `null` collection.** It renders nothing; we
   learned that from tests.
10. **`tessera-migrate` can't send multipart POSTs.** Forms with file uploads (speaker, sponsor,
    partner, CFP) can't create data from a `pages.txt`, so their success states can't be recorded
    and data-heavy pages need an external seed.
11. **The guide's JSP coverage is thin.** It is written around Thymeleaf. Nothing covers EL
    coercion (enum → `name()`, null → ""), the `pageContext.request.requestURI` idiom, or how JSP
    tag files with attributes map to fragments.
12. **No single-template preview or render harness.** Checking one template means booting the app
    or a MockMvc context with seeded data.

## Reproducing the checks
```sh
mvn clean verify
# old/new apps: run the exploded war with H2 (see the commands in this report), then
java -jar .tessera/tessera-migrate.jar compare http://localhost:8082 .tessera/pages.txt .tessera/baseline \
     --ignore 'value="\d\d\.\d\d\.\d{4} \d\d:\d\d"'
```
(`.tessera/` isn't committed. It holds the tool jar, both request lists and both baselines.)
