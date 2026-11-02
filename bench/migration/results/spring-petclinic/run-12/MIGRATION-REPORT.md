# Migration report: Thymeleaf → Tessera

## Result

- All 12 templates (10 pages, error page, layout, 2 form-field fragments) are now Tessera
  templates compiled at build time into `org.springframework.samples.petclinic.templates`.
  The startup log shows `Found 12 compiled Tessera templates`. The warning
  `another template engine is still configured` does not appear.
- Thymeleaf is gone. `spring-boot-starter-thymeleaf` and `spring-boot-starter-thymeleaf-test`
  are no longer in `pom.xml`, `spring.thymeleaf.mode` is no longer in `application.properties`,
  and no `th:` markup is left. Thymeleaf does not appear in `mvn dependency:tree` or in the
  packaged jar.
- `mvn -B clean verify` (the CI command) passes on JDK 21: 79 tests, 0 failures, 2 skipped.
  The 2 skips are the Docker/Testcontainers MySQL tests, which the original build skips too.
- Page parity: I recorded the original app with `tessera-migrate record` and compared the new
  app against it.
  - Main list, 62 requests: every page, the forms empty, with validation errors and after a
    successful submit, flash messages, pagination and out-of-range redirects, the search with
    0, 1 and many results, the 404 and 500 error pages, the German locale, and HTML-like input.
    Result: 61 `same`, 1 `reordered`.
  - Second list, 30 requests: 400 errors, a pet that belongs to another owner (500), an
    unknown pet type, a missing type, duplicate names, `ja`/`es` locales and more. Result:
    30 `same`.
  - The one `reordered` page is the owner form with every field left empty, in German. The two
    messages on `telephone` (`@NotBlank` and `@Pattern`) come out in a different order because
    Bean Validation keeps them in a `HashSet`. The order is not stable in the original app
    either, so this difference is harmless.
- Security: there is no `TrustedUrl.unchecked`, no `permitAll`, no CSRF change, no raw output
  and no disabled tests. The app has no Spring Security.

## What changed

- **`pom.xml`**
  - Added `tessera-spring-boot-starter`.
  - Added `tessera-compiler` as the only annotation processor (the build had none before).
  - Added the `-Atessera.templates`, `-Atessera.package` and `-Atessera.messages` compiler
    arguments. Messages are checked against `messages/messages.properties`.
  - Added `tessera-maven-plugin:check`, so a change to only a template or only the messages
    still triggers a recompile.
- **`build.gradle`**: made the same change for the Gradle build: the starter, the annotation
  processor, the compiler arguments, `inputs` for templates and messages, and `mavenLocal()`
  because Tessera is installed only locally. **I did not run the Gradle build.**
- **Entities**: added `@TemplateView` to `Owner`, `Pet`, `PetType`, `Visit`, `Vet` and
  `Specialty`, so the templates can read them. This is the guide's shortcut. No controllers
  changed: every value the templates read was already in the model (including the flash
  attributes and `minVisitDate`).
- **`fragments/layout.html`**
  - It is now `layout(Fragment template, String menu)`.
  - The `menuItem` fragment built its link with `@{__${link}__}`. Tessera has no
    preprocessing, so I wrote the four menu items out with literal links. The active item is
    chosen with `menu == '...'`.
- **`fragments/inputField.html` and `fragments/selectField.html`**
  - The field name is now a typed `Field` parameter. This replaces `*{__${name}__}`.
  - The input fragment takes an extra `LocalDate minDate`. The old fragment read
    `minVisitDate` from the caller's scope, which a Tessera fragment can't see. The visit form
    passes `minVisitDate`; the other forms pass `null`, so `min` is omitted as before.
  - I dropped the redundant `th:text="#{error}"` next to `th:errors`. `th:errors` already won
    in Thymeleaf.
- **Pages**
  - Pages pass `~{::body}` with `t:remove="tag"`.
  - Links built from strings became link expressions: `@{{id}/edit(id=owner.id)}` and
    `@{/owners(page=i)}`.
  - `#fields.hasAnyErrors()`/`allErrors()` became `fields.hasErrors('*')`/`fields.errors('*')`.
  - `#strings.listJoin` became `strings.join`.
  - `obj['new']` became `obj.isNew()`.
  - Elements that were self-closed but are not void elements (`<td/>`, `<span/>`, `<a/></a>`)
    are now written out in full.
  - Rows directly inside a table now sit in an explicit `<tbody>`.
- **`ownerDetails.html`**: the pets `<tbody>` is only written when the owner has pets. A
  browser builds no `<tbody>` for an empty table, and the original page had none.
- **`ownersList.html`**: an owner without an id (only possible in `OwnerControllerTests`
  fixtures) is rendered as a link without `href`. Thymeleaf wrote `/owners/null`; Tessera fails
  the render on a `null` link path variable.
- **`pets/createOrUpdateVisitForm.html`**
  - The "previous visits" loop variable was named `visit`, which shadowed the page's `visit`.
    I renamed it to `previous`.
  - The `t:if` sits on the row inside a `<t:block t:each>`, so the filter on new visits works
    as before.
- **`I18nPropertiesSyncTest`**: this is the only test change. Its regex recognised only
  Thymeleaf's `th:text`/`th:utext` as an internationalised text attribute. It now also accepts
  `t:text`/`t:utext` (`th?:`). Nothing else in the test changed; it still flags hard-coded
  text.
- **`.gitignore`**: now ignores `.tessera/`, which holds the migration tool, `pages.txt` and
  the recordings.

## Not converted

Everything was converted.

## Where Tessera or its documentation fell short

1. **No null-safe links.**
   - A `null` path variable fails the whole render (`IllegalArgumentException`) where
     Thymeleaf wrote `null`.
   - The guide does say to guard such links with `t:if`. But there is no compact form such as
     `cond ? @{...} : null` documented, so each guard needs two elements.
   - Existing `@WebMvcTest` fixtures with unsaved entities hit this.
2. **No preprocessing for computed link paths.** The menu fragment passed the link path as a
   string. The guide documents no way to give a fragment a link: a `String` in `t:href` skips
   the context path and URL encoding, and a link value can't be passed. I had to write out the
   fragment calls.
3. **Self-closed non-void elements.** Thymeleaf treats `<span .../>` as closed; a browser
   doesn't. The converter (`tessera-migrate`) leaves them as they are and doesn't report
   them, so `<td t:text=".."/>` changes meaning without any warning.
4. **`tessera-migrate` didn't flag every problem.**
   - It kept `t:errors` together with `t:text` on one element without comment.
   - It left the `visit` loop variable shadowing the page parameter of the same name, which
     the guide doesn't mention either.
   - It left `minVisitDate` as a template parameter of the fragment library, where it can't
     reach a fragment. The fix (an extra fragment parameter) isn't described.
5. **The empty-table `<tbody>` difference.** The guide's UNBALANCED advice ("write the
   `<tbody>`") changes the DOM when the loop is empty, because browsers create no `<tbody>`
   for an empty table. The guide doesn't say to make the `<tbody>` conditional to keep parity.
6. **The guide's examples aren't checked by `I18nPropertiesSyncTest`-style tests.** This is
   minor: tests that grep templates for `th:` syntax have to be adapted, and the guide doesn't
   mention that.
