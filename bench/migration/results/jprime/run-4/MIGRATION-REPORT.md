# JSP → Tessera migration report

## Result

- Every server-rendered page is now a Tessera template under `src/main/resources/templates`. There are 66 templates, compiled into package `site.templates`, and message keys are checked against `messages.properties`.
- The three e-mail bodies that used Thymeleaf are Tessera templates too (`templates/mail/*`, rendered with `XTemplate.of(..).renderToString(RenderContext.defaults())`), so Thymeleaf is gone as well.
- **Removed:** `src/main/webapp` (all JSPs and tag files), `tomcat-embed-jasper`, the JSTL API and implementation, `spring-security-taglibs`, `spring-boot-starter-thymeleaf`, the `InternalResourceViewResolver`/`JstlView` bean in `WebConfig`, and the `spring.mvc.view.*` and `spring.thymeleaf.*` properties.
- **Build:** `mvn clean package` passes, with all 211 tests green. That includes the existing ones, which now render real templates through MockMvc.
- **Parity:** `pages.txt` (360 requests) gives 346 `same`, 9 `reordered` and 5 `differ` against a recording of the original app. The 5 differences are explained below.

## What changed

- **pom.xml**
  - Added `tessera-spring-boot-starter`.
  - Added `tessera-compiler` as an annotation processor, with `release 21` and `-parameters`. Test compilation runs with `proc none`, as the guide requires.
  - Added `tomcat-embed-core` with scope `provided`. The embedded Tomcat used to arrive through `tomcat-embed-jasper`; the web starter excludes `spring-boot-starter-tomcat`.
- **Model**
  - All `site.model` classes, `InvoiceData`, `BranchForm` and `TicketPriceForm` are annotated `@TemplateView`.
  - `BranchForm`'s `java.util.Date` fields became `LocalDate`, with the same `dd.MM.yyyy` format.
- **Shared layout:** `fragments/user.html` holds `head`, `themeColors`, `header(tags, active)` and `footer`; `fragments/admin.html` holds `head` and `menu`.
  - The old header compared `pageContext.request.requestURI` to mark the active menu item. Each page now passes the name of the item explicitly.
  - Other fragments: `fragments/cfp.html` replaces `cfp.tag`/`speaker.tag`; `index.html` has local fragments.
- **Template functions** for logic the JSPs ran as EL method calls:
  - `AdminViewFunctions` (`adminD`): selected tags, sold-out packages, a speaker's submissions per branch.
  - `CfpTemplateFunctions` (`cfp.enumName`): Spring form tags write enum `name()`, while Tessera prints `toString()`.
  - `TicketsTemplateFunctions`: the first visitor, because Tessera has no indexed form paths.
- **Controllers** now put into the model what the JSPs read from elsewhere or computed inline:
  - `AgendaController` builds the agenda table rows in Java (`AgendaTable`, with a unit test). The JSP did this with `c:set` counters.
  - `SpeakerController` adds `speakerList` and `NavController` adds `articleList`, because Spring's `Page` is not view-safe.
  - `RaffleController` adds `raffleVisitors` (written to the script as JSON with `[[...]]`).
  - `AdminBranchController.current` adds empty list attributes.
  - `AdminSpeakerController` adds `sourcePage` on validation errors.
  - Existing attribute names are unchanged.
- **Tests:**
  - `HtmlJspValidationTest` scanned `src/main/webapp`; it now scans the templates directory. This is the only existing test that changed, because it asserted on where the old view files lived.
  - New: an `AgendaControllerTest` case that renders through the real resolvers and checks that `<script>` in a speaker name comes out escaped, plus `AgendaTableTest`.
- **Security:** unchanged. There is no `SafeHtml.unchecked` or `TrustedUrl.unchecked`, `SecurityConfig` is untouched, and no test is disabled.
  - CSRF was already disabled in the original `SecurityConfig`, so no `_csrf` inputs appear in the old or the new pages.
  - Article texts, bios and descriptions that the JSPs printed raw with `${...}` are now escaped. This is intended.

## Parity

- **How it was run:** both apps were started from an empty in-memory H2 database (Flyway off, `ddl-auto=create-drop`), with `target/test-classes` on the classpath like the tests. The scripts I used are not committed.
- **`pages.txt`**
  - It logs in as admin and creates the 2027 branch.
  - It then creates data through the admin forms and visits every view in its states: empty and full lists, forms empty, with validation errors and after success, edit pages, logged in and logged out, not-found pages, and CFP and tickets open, closed and sold out.
- **5 `differ`** (`/admin/article/edit/{1,2}`, `/admin/hall/edit/1`, `/admin/session/edit/{1,2}`):
  - The only difference is the hidden `createdDate` input. It prints the record's creation time to the minute, so it changes whenever recording and comparing fall in different minutes. The original app does the same, so this is not a rendering difference.
  - `compare --ignore 'value="[0-9]{2}\.[0-9]{2}\.[0-9]{4} [0-9]{2}:[0-9]{2}"'` makes these pages pass.
- **9 `reordered`:**
  - `/cfp` and `/admin/submission/add`: the session-type `<option>`s come from a `HashMap` keyed by enum, whose order also changes between runs of the original.
  - `/tickets`: Tessera writes the checkbox `_student`/`_company` hidden markers right after the checkbox, where Spring put them after the label.
- **States the tool cannot reach**, because it only sends url-encoded POSTs:
  - The sponsor, partner, speaker, submission and JPro forms need a multipart `file` part, and `/cfp` and `/tickets` also check a session captcha.
  - So these pages were only compared without such data: lists of sponsors, partners and speakers, a real `/speaker/{id}`, submission edit pages, and a successful CFP or ticket purchase (`tickets-result`).
  - Partial checks done instead: group A seeded sponsors, partners and featured speakers with `curl -F` and compared `/` and `/team` (`same`). Controller tests render the others.

## Could not convert one-to-one

- **Broken markup that browsers repair.** Tessera requires well-formed nesting, so the DOM the browser builds was written out explicitly:
  - In `index.jsp`, several divs were opened but closed with `</section>`; this is now a recursive fragment that reproduces the nesting.
  - `resetPassword.jsp` had unclosed `<strong>` elements.
  - Stray `<p>`/`<dd>` tags became `<p></p>`.
- **Expressions in `style`** (sold-out pricing boxes) and **conditional `onclick`**: replaced by choosing between literal elements.
- **`<form:errors/>` without a path** (object-level errors only) has no Tessera equivalent. These forms never produce object errors, so it was dropped.

## Where the guide or Tessera fell short

1. **Two layers of view technology.** The guide assumes a single view technology. This app also used Thymeleaf for e-mail bodies, and those files sat under `templates/`, which the Tessera processor compiles. One of them was not well-formed, which broke the build until all three were converted.
2. **Removing Jasper removes Tomcat.** The guide's build section doesn't mention that dropping `tomcat-embed-jasper` can take the servlet container with it ("no ServletWebServerFactory").
3. **Undocumented `t:field` behaviour:**
   - Boolean checkboxes write their own `_field` hidden input; adding one by hand gives duplicates.
   - A null value omits `value` (Spring wrote `value=""`). Compare happens to treat these as equal.
   - Checkbox ids are not numbered like Spring's (`featured1`).
   - A `<select multiple>` bound to a collection gets no `selected`.
4. **Enums print with `toString()`, not `name()`**, unlike Spring's form tags and JSP option values, and there is no built-in for `name()`.
5. **Spring Data `Page` is not view-safe.** It works if declared as `Iterable<T>`, which the guide doesn't mention.
6. **Fragments as arguments.** Passing a parameterized `~{::frag(args)}` as a `Fragment` argument works, but is undocumented. `t:insert` runs before `t:each` on the same element (the guide only says this for `t:if`).
7. **Small expression gaps:**
   - `|...|` cannot contain a literal `|` (titles like `jPrime | Home`).
   - `x == null ? '' : x` needs `'' + x` for non-Strings.
   - Expressions are banned in `style` even when choosing between two literal values.
8. **`tessera-migrate` limits:**
   - It cannot send multipart POSTs.
   - It has no hook for seeding data.
   - Its `--ignore` is the only way to deal with time-of-day output.
   - Its output doesn't say that `value=""` versus a missing attribute is normalized away.
9. **No guidance for repaired markup.** The guide has no strategy for broken legacy markup where the browser-repaired DOM must be preserved.

## Leftovers

Some Java constants are still named `*_JSP` (for example `ADMIN_BRANCH_VIEW_JSP`). They hold plain view names; I didn't rename them, to keep the controller diffs small.
