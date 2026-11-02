# Migration report: JSP → Tessera

## Result

- Every page is now a Tessera template under `src/main/tessera` (66 templates, generated package
  `site.templates`). The old JSPs, tag files, the JSP view resolver, its configuration
  (`spring.mvc.view.prefix/suffix`) and the JSP/JSTL dependencies (`tomcat-embed-jasper`, the
  JSTL API and implementation, `spring-security-taglibs`) are removed. `src/main/webapp` no longer
  exists.
- The three e-mail templates were Thymeleaf (`src/main/resources/templates`, rendered by hand
  through `TemplateEngine`). They are now Tessera templates in `src/main/tessera/mail/`, rendered with
  `XTemplate.of(...).renderToString(RenderContext.defaults())`. The Thymeleaf starter and the
  `spring.thymeleaf.enabled` properties are gone, so Tessera is the only template engine. The startup
  warning about another template engine does not appear.
- `mvn clean verify` passes: 208 tests, 0 failures, no test deleted or disabled.
- Page parity: I used a `.tessera/pages.txt` of 278 requests. It logs in as admin, creates the data
  through the admin forms, and covers logged-out, admin, empty and full lists, form errors, two branch
  states and the error page. Results: 253 `same`, 18 `reordered`, 7 `differs`. The 7 differences are
  explained below; the original app shows the same differences between two of its own runs.

## What changed

**Build (`pom.xml`)**
- Added `tessera-spring-boot-starter`, `tessera-compiler` as an annotation processor (`-Atessera.templates`,
  `-Atessera.package=site.templates`, `-Atessera.messages`) and `tessera-maven-plugin` `check`.
- Added a `build.dir` property, defaulting to `target`, so parallel builds could use separate output directories.
- `tomcat-embed-jasper` (provided) was the only thing that brought in embedded Tomcat, since
  `spring-boot-starter-web` excludes `spring-boot-starter-tomcat`. It is replaced by `tomcat-embed-core`
  (provided).
- Jasper also brought in `ecj` 3.33.0, which JasperReports' JDT compiler uses. I declared it explicitly so
  the version doesn't drop to 3.21. I diffed `dependency:list` before and after: otherwise only JSP/JSTL
  artifacts and `spring-security-acl` (unused) are gone.

**Templates**
- Shared fragments:
  - `fragments/user.html`: `themeColors` (was `theme-colors.jsp`), `pageJavaScriptAndCss`,
    `header(active, tags)` and `footer`.
  - `fragments/admin.html`: `pageJavaScriptAndCss` and `menu`.
  - `fragments/cfp.html`: `cfp(...)`, plus `speaker(Field<Speaker>)` (was `speaker.tag`).
  - `fragments/index.html`: a helper for the home page's partner sections.
- The header used to mark the active menu entry by testing `pageContext.request.requestURI`, which was
  the JSP path. Each page now passes that as a literal (`'index'`, `'team'`, ...).
- One template per JSP, with the same view name.

**Java**
- Entities in `site.model` and the form/DTO classes the pages read are annotated `@TemplateView`.
- `java.util.Date` getters aren't view-safe, so dates are formatted from the `LocalDateTime` getters with
  `temporals.format`.
- Model shaping that moved out of the JSPs:
  - `AgendaController` and the new `AgendaRow`: the agenda grid's colspan/rowspan and hall-matching
    logic from `talks.jsp`.
  - `AdminSpeakerController`: `speakerSubmissions`, because the JSP called a method with an argument.
  - `SpeakerController`, `NavController`, `AdminUserController`: list content instead of a Spring
    `Page`, which isn't view-safe.
  - `RaffleController`: a list of view objects instead of a hand-built JSON string; the template writes
    it with `[[...]]`.
  - `TicketsController`: makes sure there is one visitor row. See shortfall 1.
- Template functions in `site.view`: `enums.name` (`EnumFunctions`) and `adminCore.soldOutPackages`.
- `WebConfig`: the JSP view resolver is removed.
- `HtmlJspValidationTest` now points at `src/main/tessera`, because the directory it scanned was removed.
  The assertion itself is unchanged.

## Differences that remain, and why they are right

- **`createdDate` hidden input** on article, hall and session edit pages (4 pages): it holds the time the
  record was created, so it changes on every run.
- **Sold-out sponsor packages column** on the branch pages (3 pages): it prints a `Set` of enums whose
  iteration order changes from one JVM run to the next. The original app's two baseline recordings
  differ in the same way.
- **`reordered` pages** (passing):
  - `t:field` writes a checkbox's or multi-select's `_name` marker input at a different position than
    Spring's form tags did.
  - The session-type `<select>` options come from a `HashMap`, which is also random in the original.
- **Raw output is now escaped.** Speaker bios, talk descriptions, submission titles and similar fields
  were printed raw by EL. Article bodies are stored HTML and now go through `html.sanitizeRich`. This is
  the intended security change.

## Not converted / not verified

- Nothing is left on JSP.
- `tessera-migrate` can only send url-encoded POSTs. Sponsors, partners, speakers and submissions are
  created only through multipart forms (an image is required), so `pages.txt` can't create them. The
  agents checked those pages (speaker, talk, agenda with speakers, submission lists and edit) with a
  separate curl-seeded comparison against the original app. Everything matched except the intended
  escaping.
- `tickets-result` (needs the random captcha) and `createNewPassword` with a valid token (needs the
  token from the e-mail) are covered only by the controller tests.

## Where Tessera or its documentation fell short

1. **Indexed `t:field` paths don't auto-grow lists.** `t:field="*{visitors[0].name}"` on an empty list
   fails at render time with `IndexOutOfBoundsException`, while Spring's tags auto-grew the list. Not
   documented; worked around in the controller.
2. **Enums.** EL and Spring's form tags print `name()`, but Tessera prints `toString()`. This app's
   enums override `toString()`, so values changed silently ("Payed" instead of `PAYED`). `x.name()` is
   rejected ("'name' is not a property of ..."), and there is no built-in for an enum's name, so I
   wrote a function. The JSP notes (4.10) should mention this.
3. **`<option t:value>` inside a `t:field` select** is printed with `String.valueOf`, not through
   Spring's conversion service. Spring printed a `Branch` as its id (`YEAR_2030`); Tessera printed
   `toString()`. I wrote `b.label` by hand. Not documented.
4. **Marker position.** There is no control over where `_name` hidden inputs go relative to the
   element, so the pages show up as `reordered`.
5. **`[[...]]` script declarations** must come before any other code, not even after a literal
   `var x = false;`. Keeping the original order needed `var shuffling = [[${false}]];`.
6. **`t:object` doesn't write the form `id`** that `<form:form>` did. The guide covers the
   `<form:errors>` ids but not this.
7. **Null-tolerance.** EL treated `null` as 0 in comparisons (`number > 0`); Tessera throws a
   `NullPointerException` at render time. That is documented in general but easy to hit in a JSP port.
8. **Misnested legacy markup.** The verifier rejects stray end tags, a `<div>` inside a `<p>`, and
   unclosed `<strong>`. The guide says "write the markup a browser reads", but gives no help rebuilding
   the tree a browser builds from badly nested markup. One example: `index.jsp` closed `<div>`s with
   `</section>`, so its partner sections really contain the rest of the page, and reproducing that took
   `Fragment` parameters. We inspected browser trees with jsoup to work these out.
9. **`|...|` literal substitution** has no documented way to include a literal `|`.
10. **`<meta name="description" t:content>`** accepts computed text, while 4.4 says "most of
    `<meta content>`" is literals-only. Which names count as text isn't documented.
11. **Tooling.**
    - `compare --ignore` runs on the raw HTML, but the diff it prints is of the normalized DOM. A regex
      written against the diff can break the markup and make many pages differ, so I couldn't use it.
    - `record`/`compare` can't send multipart POSTs.
12. **Guide wording.** Section 4.9 says `Fragment` exists only between templates. Mail templates
    therefore render through `renderToString(RenderContext.defaults())`. That works, but the guide gives
    no full example for a non-HTTP context (links, messages).
