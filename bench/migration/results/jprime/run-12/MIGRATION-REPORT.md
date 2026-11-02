# Migration report: JSP (and Thymeleaf mail templates) to Tessera

## Result

- Every view the application renders is a Tessera template under `src/main/resources/views`
  (69 page templates plus shared fragments). The three Thymeleaf mail templates are also Tessera
  templates now (`views/mail/*`), rendered with `XTemplate.of(...).renderToString(...)`.
- The JSP engine is gone: all `src/main/webapp/**/*.jsp` and `WEB-INF/tags/**` were deleted,
  together with the `tomcat-embed-jasper`, JSTL (`jakarta.servlet.jsp.jstl-api`,
  `org.glassfish.web:jakarta.servlet.jsp.jstl`) and `spring-security-taglibs` dependencies, the
  `InternalResourceViewResolver`/`JstlView` bean in `WebConfig`, and `spring.mvc.view.prefix/suffix`.
  Thymeleaf is gone as well (`spring-boot-starter-thymeleaf`, `src/main/resources/templates`,
  `spring.thymeleaf.enabled`). The startup log has no `another template engine is still
  configured` warning and no `No compiled Tessera templates` warning.
- `mvn clean verify` passes: 208 tests, 0 failures, 0 errors, 0 skipped. That's the same count
  as before the migration. The build targets Java 17 (`source`/`target` 17) and ran on JDK 21,
  the default here. JDK 17 isn't installed in this environment, so the build has not been run
  on 17.
- Security: CSRF stays as the app had it (already disabled in `SecurityConfig`, untouched). No
  `permitAll` was added, no escaping was turned off, and no test was disabled or weakened.

## Build changes (`pom.xml`)

- Added `dev.tessera:tessera-spring-boot-starter` (Jakarta) and `tessera-compiler` as the
  annotation processor. Arguments: `tessera.templates=src/main/resources/views`,
  `tessera.package=site.views` and `tessera.messages=src/main/resources/messages`. Also turned
  on `<parameters>true</parameters>` and added `tessera-maven-plugin:check`.
- The templates root is `views/`, not `templates/`, because `templates/` held the Thymeleaf mail
  templates while both engines were present.
- Removing `tomcat-embed-jasper` also removed embedded Tomcat itself, because the web starter
  excludes `spring-boot-starter-tomcat`. Replaced it with `spring-boot-starter-tomcat` in
  `provided` scope (the usual layout for a deployable war).
- `tomcat-embed-jasper` also supplied `org.eclipse.jdt:ecj` 3.33.0, which JasperReports uses.
  Without jasper it fell back to 3.21.0, so I pinned 3.33.0 explicitly. Apart from that,
  `mvn dependency:list` before and after differs only by the JSP/JSTL/taglib jars and their
  `jakarta.el-api`/`spring-security-acl` transitive dependencies. EL is still supplied by
  `tomcat-embed-el` through the validation starter.

## Java changes

- All `site.model` entity classes: annotated `@TemplateView`. So are the form objects
  `BranchForm`, `TicketPriceForm` and `InvoiceData`, and `RaffleController.RaffleVisitor`, which
  is now public.
- Model attributes added next to the existing ones, which are unchanged because the tests
  assert on them:
  - `Page` isn't view-safe, so these controllers also put the page content in the model:
    `NavController.articleList`, `SpeakerController.speakerList`, `AdminUserController.userList`.
  - `AdminBranchController.branchRows`: sold-out packages, because `soldOutPackages()` isn't a
    getter.
  - `AdminSpeakerController.speakerSubmissions`: replaces `speaker.branchSubmissions(branch)`,
    a method with an argument.
  - `AgendaController.firstDayRows`/`secondDayRows` (new record `AgendaRow`) and
    `firstDayStart`/`secondDayStart`: the slot matching, colspan and workshop counter that
    `talks.jsp` computed with `c:set`.
  - `RaffleController.visitorList`: the visitors as a list, written into the script as JSON
    instead of the JSON string printed raw.
- `TicketsController`: adds an empty first visitor before rendering the ticket form. Spring's
  JSP form tags grew the empty list when binding `visitors[0].*`; Tessera fails on the missing
  index instead.
- New template functions:
  - `EnumViewFunctions` (`enums.name`): JSP EL printed enums with `name()`, while Tessera prints
    `toString()`, and several enums override `toString()`.
  - `InlineHtmlFunctions` (`inlineHtml.text`): talk descriptions and bios that the JSPs printed
    raw inside a `<p>`.
  - `SinglePostFunctions`: the article body that `single-post.jsp` printed raw inside a `<p>`.
- `AbstractCfpController` and `TicketService`: the mail bodies are rendered by Tessera templates
  instead of the Thymeleaf `TemplateEngine`.
- `HtmlJspValidationTest`: its quote-balance check walked `src/main/webapp`, which no longer
  exists, so it would have passed on nothing. It now also scans `src/main/resources/views`. That
  is the only test change; no assertion was relaxed.
- `src/test/resources/application.properties`: removed `spring.thymeleaf.enabled=false`
  (configuration for the removed engine).

## Templates

- Shared fragments (`views/fragments/`):
  - `user.html`: theme colours, CSS, header and footer. The header takes the page key so it can
    reproduce the "active" nav item, which the JSP computed from
    `pageContext.request.requestURI`.
  - `admin.html`: admin CSS/JS and menu.
  - `cfp.html`: the CFP form and speaker block (was `cfp.tag` and `speaker.tag`).
  - `cards.html`: speaker social icons and agenda names.
  - `index-partners.html`: the partner sections of the index page.
- Raw `${...}` output became escaped `t:text`. Stored HTML (articles, bios, abstracts,
  descriptions) goes through `html.sanitize` or the inline sanitizers above.
- Markup that browsers only read through error recovery was rewritten as the tree a browser
  builds, so the DOM is unchanged. Examples: `div`s closed with `</section>` in `index.jsp`,
  nested or stray `<p>`s, stray `</div>`/`</dl>`, `<strong>` tags left open in
  `resetPassword.jsp`, and table rows without a `<tbody>`.

## Page parity

The list is `.tessera/pages.txt`, 180 requests, not committed. It starts from an empty H2
database and creates its data through the app's own forms. It covers:

- logged out and as admin;
- every admin list (empty, full and paginated), add form and edit form;
- validation-error states;
- public pages in the CFP open/closed, sold-out and agenda published/unpublished states;
- login error/logout, 404 and `/error`.

`tessera-migrate compare` against the original's recording gives 170 `same`, 6 `reordered` and
4 `differs`. Statuses and final URLs all match. The `reordered` pages and the masked timestamps
are harmless:

- The `_tags` hidden marker is now written before the multi-select (article form).
- The session-type `<option>` order comes from a `HashMap`, so it also varies between runs of
  the original.
- Masked with `--ignore`: the current-time `createdDate` hidden value and the invoice date
  (`value="dd.MM.yyyy[ HH:mm]"`), and the printed `HashSet` of sold-out packages, whose order
  changes between JVM starts in the original too.

The 4 `differs` are intended:

- **049/050:** a tag named `Java & <JVM>`. The JSP printed it raw (HTML injection); Tessera
  escapes it. The same happens to every other value the JSPs printed raw.
- **097/098 (`/raffle/view`, `/raffle/view/jpro`):** Tessera only accepts a value in a script as
  a declaration at the top of the script. So `var visitors = [...]` now comes before
  `var shuffling = false;`. The script behaves the same.

A further 48 requests ran against both apps started with a SQL seed of speakers, submissions,
sponsors and partners (`.tessera/seed.sql` with `seedpages.txt` and `seedadmin.txt`). They cover the
index with sponsors and partners, the speakers, speaker and talk pages, the agenda, and the admin
speaker, submission, partner and session lists and edit forms. All are `same`.

## Not converted or not verified

- Nothing was left unconverted.
- `tessera-migrate` only sends URL-encoded POSTs, so it can't verify forms that need a file
  upload: CFP submission, speaker/sponsor/partner add and edit, and the CSV visitor upload.
  - Their GET states were compared.
  - Their POST error states were posted as multipart with curl to both apps and diffed. They
    matched apart from `selected` vs `selected="selected"`, which is the same in the DOM.
- Not compared on a running app:
  - Successful ticket purchase (`tickets-result`) and the CFP speaker errors: they need the
    captcha answer, which only exists as an image. `tickets-result` is rendered by
    `TicketsEpayRegisterControllerTest`.
  - `createNewPassword` with a valid token, which is only sent by mail.
- Behaviour of the original kept on purpose:
  - Branch and hall POSTs with validation errors return 405 in both apps: `BindingResult` is
    in the wrong position in `AdminBranchController`, and `hall/edit` reads `hall` while the
    bound object is `venueHall`.
  - The registrant error form shows no payment types, because the controller doesn't put them
    in the model on POST.
  - `login.jsp` read `${error}`/`${msg}`, which nothing ever sets; those blocks were dropped.
- Small, intended mail changes:
  - In `newSubmission`, the co-speaker `<div>` sat inside a `<p>` and is now written after it.
  - `submissionContent` now closes its trailing `<p>`.
- `admin/submission/edit.html` has its own inline copy of the CFP form rather than calling
  `fragments/cfp.html` with `admin=true`. Both match their originals, but they could be merged
  later.

## Where Tessera or its documentation fell short

1. **Enums.** The guide doesn't say that JSP EL and `<form:select items>` print enums with
   `name()` while Tessera uses `toString()`. There is no built-in way to get `name()`, so every
   JSP port needs its own function. Spring also renders entity `<option>` values through the
   conversion service, which `t:value` does not.
2. **Indexed form paths.** Spring's form tags grow an empty list for `visitors[0].name`; Tessera
   throws. This isn't documented.
3. **Checkbox and multi-select markers.** `t:field` writes the hidden `_name` marker in a
   different position from Spring's form tags. This isn't documented.
4. **Script values.** A value must be the first declaration of a script, even after a harmless
   literal like `var shuffling = false;`. This forces a visible reorder.
5. **Raw HTML inside a `<p>`.** There is no faithful port. `SafeHtml` is rejected there
   (`RAW_CLOSES_ELEMENT`) and `SafeInlineHtml` forbids links. Also, the guide doesn't mention
   that `html.sanitize` adds `rel="nofollow noopener"` to links.
6. **Broken markup.** Pages whose markup a browser repairs must be rewritten as the repaired tree
   by hand. `tessera-migrate` can't print the tree a browser builds, so we used the jsoup copy
   inside the jar.
7. **`tessera-migrate` and uploads.** It can't send multipart requests, so upload forms can't be
   recorded or compared.
8. **`|...|` literals** can't contain `|`, so titles like `jPrime | Blog` need concatenation.
9. **`on*` attributes.** A conditional `onclick` must be split into one element per case. This
   is by design, but it's awkward for JSP ports.
10. **Smaller gaps.** The guide doesn't say what `t:each` does over a `null` collection (it
    renders nothing). It also doesn't say whether a fragment nobody calls is fully checked.
11. **The JSP migration path.** The guide's notes are good, but there is no mechanical converter
    for JSP like the Thymeleaf one, so ~7,000 lines of JSP/tag files were converted by hand.

## Notes for reproducing the checks

- Scratch tooling lives in `.tessera/` (git-excluded, not committed).
- `run.sh` starts an app on H2 with `ddl-auto=create-drop`.
- `cmp.sh` restarts this app and compares it with `baseline/` using the masks above.
- `seedcmp.sh` runs the seeded comparison.
- The pristine original that `seedcmp.sh` records against was a `git archive HEAD` export into
  `.tessera/orig`. It was deleted afterwards because it contained the old JSPs. Re-create it with
  `mkdir .tessera/orig && git archive HEAD | tar -x -C .tessera/orig`, then run `mvn package` there.
