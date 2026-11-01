# Migration report: JSP (+ Thymeleaf mail templates) → Tessera

## Result

- **Every page is rendered by Tessera.** There are 65 compiled templates under
  `src/main/resources/templates`, and their view names are the old JSP paths, so controllers and
  tests still return and assert the same names. The three Thymeleaf e-mail templates
  (`newSubmission`, `submissionContent`, `ticket-message`) are Tessera templates too. They are
  rendered with `XTemplate.of(...).renderToString(RenderContext.defaults())` in
  `AbstractCfpController` and `TicketService`.
- **The old engines are gone:**
  - All JSPs and tag files (`src/main/webapp`) are deleted.
  - These dependencies are removed: `tomcat-embed-jasper`, `spring-boot-starter-thymeleaf`, the
    JSTL API and implementation, and `spring-security-taglibs`.
  - The `InternalResourceViewResolver`/`JstlView` bean in `WebConfig` is removed.
  - `spring.mvc.view.prefix/suffix` and `spring.thymeleaf.enabled` are removed from both
    `application.properties` files.
  - The startup log now says "Found 65 compiled Tessera templates", with no "another template
    engine is still configured" warning.
- **`mvn clean verify` passes: 208 tests, 0 failures**, on the default JDK, with the build still
  targeting Java 17. No test was modified, disabled or deleted. The tests are full `MockMvc`
  `@SpringBootTest`s, so they now actually render the templates.
- **Security:**
  - There is no `SafeHtml.unchecked` or `TrustedUrl.unchecked` anywhere. `tessera-maven-plugin`
    runs `review` with `failOnUnchecked=true`, and `target/tessera-review.txt` lists 0 unchecked
    calls and 6 small `@TemplateFunctions`.
  - `SecurityConfig` is unchanged, so there is no new `permitAll`. CSRF stays disabled, which is
    the app's own existing setting. Escaping is on everywhere.

## Build changes (`pom.xml`)

- Added `dev.tessera:tessera-spring-boot-starter`, `tessera-compiler` as an annotation processor
  (`-Atessera.templates`, `-Atessera.package=site.templates`, `-Atessera.messages`), and
  `tessera-maven-plugin` (`check` + `review`).
- Added `org.apache.tomcat.embed:tomcat-embed-core` with scope `provided`. The app excludes
  `spring-boot-starter-tomcat`, and its embedded Tomcat used to come only through
  `tomcat-embed-jasper`. Removing JSP removed the web server ("Check your application's
  dependencies for a supported servlet web server").
- `mvn dependency:list`, before vs after:
  - **Removed:** `jakarta.el-api`, the JSTL artifacts, `attoparser`, `unbescape`, `thymeleaf*`,
    `spring-security-taglibs` and `spring-security-acl`. The application uses none of these.
  - **Changed:** `ecj` drops from 3.33 (via jasper) to 3.21 (via jasperreports). Reports are
    compiled at build time by the jasperreports plugin; the app never compiles reports at runtime.

## Java changes

- `@TemplateView` on every entity in `site.model`. This is the guide's "shortcut" for a large
  port; per-page view records would be the cleaner follow-up. Also on `BranchForm`,
  `TicketPriceForm`, `InvoiceData` and `RaffleController.RaffleVisitor` (now `public`).
- Model attributes added where the JSP read something the model didn't hold, or a type that
  isn't view-safe:
  - `NavController`: `articleList` (the content of the Spring `Page`).
  - `SpeakerController`: `speakerList` (the same).
  - `AdminUserController`: `userList` (the same).
  - `AdminSpeakerController`: `speakerSubmissions`, replacing `speaker.branchSubmissions(branch)`,
    a method with an argument.
  - `AgendaController`: `agendaView`. The `AgendaView` record precomputes the agenda grid that
    `talks.jsp` computed in JSTL. It also adds `talkLevel`.
  - `RaffleController`: passes the visitor list as data instead of a JSON string built with
    Jackson; the template writes it as JSON with `[[...]]`.
- New template functions:
  - `adminView.formatDate`, matching `fmt:formatDate`.
  - `adminView.soldOutPackages`.
  - `adminView.enumName` and `enums.name`, which print an enum's `name()` as EL did. These two
    duplicate each other because two agents wrote them independently; this is a cleanup candidate.
  - `richtext.isPlain` and `richtext.startsWithBlock` (see below).
- JSP tag files became fragments:
  - `fragments/user.html`: `css`, `themeColors`, `header(tags, page)` and `footer`.
  - `fragments/admin.html`: `css` and `menu`.
  - `fragments/cfp.html`: the CFP form, plus the speaker sub-form as a `Field<Speaker>` fragment.
  - The header's active-menu logic compared `pageContext.request.requestURI` with `/xxx.jsp`.
    Each page now passes its old JSP path to the header as a literal.
- Stored text that the JSPs printed raw is now `t:utext="html.sanitize(x)"` wherever Tessera
  allows it, so benign markup keeps rendering and scripts are stripped. Elsewhere it is escaped
  (see below).

## Parity

`pages.txt` has 206 requests, and the original's responses are recorded in `baseline/`:
- logged out, both with an empty database and with data;
- data created through the admin forms;
- every admin list and add/edit form, empty, with validation errors and after success;
- error pages: `/404`, `/error`, unknown URLs and ids that don't exist;
- other branch states: CFP closed and sold out.

Both apps run the same way, from `.tessera/run.sh <war> <port>`: H2 in memory, empty at start.
The last run gave **180 same, 26 differ**.

Masks used. Each one covers text that changes on every run of the *original* app too:
- `value="dd.MM.yyyy[ HH:mm]"`: creation timestamps and today's invoice date;
- the session-type `<select>` on `/cfp`: built from a `HashMap`, so its order is random;
- the sold-out packages `[...]` cell: printed from a `HashSet`, so its order is random.

Remaining differences, which I believe are correct:

1. **A tag name containing `"`** (on 24 public pages, through the shared nav header). The JSP
   wrote `href="/nav/${tag.name}"` raw. The quote broke out of the attribute and injected a new
   attribute (`q""=""`). Tessera escapes it. This is an injection fix, and it can't be reproduced
   safely.
2. **Article titles with markup inside `<a>` and headings** (`/nav`, `/nav/Java`,
   `/nav/article/2`; also admin session titles inside links). Tessera's verifier rejects
   sanitized HTML inside `<a>`, `<p>`, `<h*>` and `<strong>` (`RAW_CLOSES_ELEMENT`), so those
   values are escaped.
3. **`/raffle/view` and `/raffle/view/jpro`.** In a script, Tessera only allows `[[...]]` as the
   first declaration (the script prelude rule). So `var visitors = (...)` now comes before
   `var shuffling = false`, the value is in parentheses, and `<`/`&` are written as `<` and
   `&`. The JavaScript values are identical.

Fixed during the port: the original's broken markup was reproduced as the browser parsed it, so
those pages compare `same`:
- `index.jsp` "closed" divs with `</section>`;
- `resetPassword.jsp` left two `<strong>` tags open, and the browser bolded the rest of the page,
  footer included;
- `<p>` elements were left unclosed before block elements.

Not covered by `pages.txt`, because `tessera-migrate` can only send urlencoded forms:
- pages whose data comes only from multipart uploads (speakers, sponsors, partners, submissions);
- the CFP and ticket success paths, which need the random captcha.

The agents checked these separately, seeding the data with curl and a multipart proxy that
reads the captcha answer from the debug log; those pages came out same or reordered. Pages that
need e-mail (a valid reset token) or a CSV upload (jPro presence) were compared only in their
reachable states.

Several error states return 405 in the original because of controller bugs, and they still do:
invalid branch or hall form, invoice form errors, a logged-out multipart CFP post, and a
successful signup (the welcome e-mail fails).

## Where Tessera or its documentation fell short

- **Enum printing.** EL and Spring form tags print an enum's `name()`; Tessera prints
  `toString()`. Templates can't call `name()` either ("'name' is not a property"). The guide's JSP
  notes don't mention this, and every JSP app with an enum that overrides `toString()` needs a
  helper function.
- **Raw output in inline contexts.** `RAW_CLOSES_ELEMENT` rejects `t:utext="html.sanitize(x)"`
  inside `<p>`, `<a>`, `<h*>` and `<strong>`. It does the same with a custom inline-only
  `HtmlPolicy`, whose allowed elements apparently aren't taken into account. The guide (4.10,
  "Raw output") recommends `html.sanitize` for JSP raw output without mentioning this. So the
  common JSP pattern `<p>${richText}</p>` can't be ported with the same DOM.
- **`t:errors` doesn't write Spring's `id="path.errors"`**, which the JSP `<form:errors>` tag
  wrote. It had to be added by hand.
- **`t:field` on a list element out of range** (`*{visitors[0].name}` with an empty list) throws
  `IndexOutOfBoundsException`. Spring's tags auto-grow the list.
- **Order of hidden markers.** `t:field` puts the `_name` marker of a checkbox before the label,
  and the `_tags` marker of a `<select multiple>` before the select. Spring's JSP tags put both
  after. Compare reports this as `differs`, not `reordered`, so those controls were hand-written.
- **A POST form's `t:action` can't be a choice between two same-origin links**
  (`admin ? @{/a} : @{/b}`). The workaround was two forms. Also, `t:replace="~{::fieldset}"`
  fails with a confusing "template has no fragment 'fieldset'": element selectors only work as
  fragment arguments.
- **There is no computed `t:remove`.** Reproducing a conditional wrapper div (index.jsp) needed a
  chain of fragments.
- **`java.util.Date` form fields** work with `t:field`, but `Date` isn't in the guide's view-safe
  list.
- **`tessera-migrate record/compare` can't send multipart requests** and has no hook for
  captchas. For apps like this one (image upload on most forms), that means seeding outside the
  tool.
- **The guide doesn't warn that removing `tomcat-embed-jasper` removes the embedded server** for
  apps that exclude `spring-boot-starter-tomcat` (a common setup for war-deployable JSP apps).

## Housekeeping

- `.tessera/` (the tool jar, the original war, the run script) and `baseline/` are git-ignored.
  `pages.txt` is kept.
- `HtmlJspValidationTest` still passes, but it now scans an empty `src/main/webapp`. It was left
  unchanged because it asserts nothing Tessera-specific.
