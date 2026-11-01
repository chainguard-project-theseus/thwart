# Migration report: Thymeleaf → Tessera

## Outcome

- All 12 templates (10 pages, plus the `layout`, `inputField` and `selectField` fragment files) are
  Tessera templates now, compiled by the `tessera-compiler` annotation processor into
  `org.springframework.samples.petclinic.templates`. Thymeleaf is gone: the starter, its test
  starter, `spring.thymeleaf.*` configuration and every `th:` template. Nothing in the
  dependency tree or the packaged jar mentions Thymeleaf, and the startup warning
  `Tessera: another template engine is still configured` doesn't appear.
- `mvn clean verify` (JDK 21; the project targets Java 17) passes with 79 tests, 0 failures and
  2 skipped. The 2 skipped are the MySQL Testcontainers tests, which skip because there is no
  Docker here, the same as before the migration.
- Page parity: I recorded the original app with `tessera-migrate record` and compared the
  migrated app against it, each started fresh from the same seed data. There were two request
  lists (kept locally in `.tessera/`, which git ignores) with 88 requests in total. They cover
  every page, empty forms and forms with validation errors (blank fields, a bad telephone,
  overlong fields, bad dates, past or future dates, an unknown pet type, duplicate pet names),
  successful posts with flash messages, paging including out-of-range redirects, searches with
  none, one or several results, 400, 404 and 500 error pages, markup in user data, and the
  de/es/ko/fa locales. Result: 87 `same`, 1 `reordered`, 0 `differ`. `compare` exits 0 for both
  lists.
  - The `reordered` page is the German owner form posted empty. The telephone field fails two
    constraints (`@NotBlank` and `@Pattern`), and Hibernate Validator returns the two messages in
    no fixed order. That is harmless and not caused by the migration.
- Security: there are no `SafeHtml.unchecked` or `TrustedUrl.unchecked` calls, no `t:utext`, and
  no `@TemplateFunction`s. `target/tessera-review.txt` is empty. The application has no Spring
  Security, so no `permitAll` or CSRF settings were touched. No tests were disabled.

## What changed

### Build
- `pom.xml`:
  - Replaced `spring-boot-starter-thymeleaf` with `dev.tessera:tessera-spring-boot-starter`.
  - Removed `spring-boot-starter-thymeleaf-test`.
  - Configured `maven-compiler-plugin` with `tessera-compiler` as an annotation processor (the
    build had no other processors) and the `tessera.templates`, `tessera.package` and
    `tessera.messages` options.
  - Added `tessera-maven-plugin` (`check` and `review` goals).
  - Added the `tessera.version` and `tessera.messages` properties.
- `build.gradle`: the same changes for the Gradle build: the starter, the annotation processor,
  the compiler arguments and the template/message inputs. I also added `mavenLocal()`, because
  Tessera exists only in the local Maven repository. I didn't run the Gradle build; Maven is the
  build that was checked.
- `application.properties`: removed `spring.thymeleaf.mode=HTML`.

### Java
- Added `@TemplateView` to the entities the templates read: `Owner`, `Pet`, `PetType`, `Visit`,
  `Vet` and `Specialty`. Getters from their unannotated `BaseEntity`, `NamedEntity` and `Person`
  superclasses resolve.
- No controller changes were needed. Every value the templates read was already in the model.
  `minVisitDate` was read implicitly by the `inputField` fragment; it is now passed to the
  fragment explicitly.

### Templates
- `fragments/layout.html`:
  - `layout(Fragment template, String menu)`.
  - The `menuItem` fragment now takes a link built with `@{...}` at each call site and a
    `boolean active`, instead of a path assembled with `__${link}__`.
- `fragments/inputField.html` and `fragments/selectField.html`:
  - The `*{__${name}__}` field paths became typed `Field` parameters.
  - `inputField` takes an extra `LocalDate min` argument. It replaces the implicit read of
    `minVisitDate`; callers pass `null` where the old page had no `minVisitDate` in its model.
  - Dropped the redundant `th:text="#{error}"` next to `th:errors`. Thymeleaf's `th:errors`
    already overrode it, so the output is the same.
- Pages:
  - Each page calls the layout with `~{::body}`, and its `<body>` has `t:remove="tag"`. The
    original produced a nested `<body>`, which browsers discard, so parity holds.
  - Links that used `__${...}__` preprocessing became `@{...}` links with placeholders. Page-relative
    links, such as `{id}/edit` on the owner page, stay relative, as before.
  - `owner['new']` became `owner.isNew()`. `#fields.hasAnyErrors()` and `#fields.allErrors()`
    became `fields.hasErrors('*')` and `fields.errors('*')`.
  - `#strings.listJoin` became `strings.join`.
  - The visit form's loop variable `visit`, which shadowed the form object, was renamed to `v`.
  - The pet form's hidden `id` input keeps `value=""` for a new pet.
- `owners/ownersList.html`: an owner whose `id` is `null` now gets a static `href="/owners/"`,
  which is what Thymeleaf wrote, instead of failing the render. Tessera rejects `null` link path
  variables. `OwnerControllerTests` hits this case with an unsaved `new Owner()`.
- Removed a stray `</a>` in `ownersList.html`. The old markup had `<a ... /></a>`, and browsers
  drop the extra end tag, so the page is the same.

### Tests
- `I18nPropertiesSyncTest.checkNonInternationalizedStrings` treats a line as translated if it
  contains `th:text`/`th:utext`. That is a Thymeleaf attribute-name detail, so I widened the
  pattern to also accept Tessera's `t:text`, `t:utext` and `t:errors`. The test still flags
  untranslated literal text. No other test changed.

## Not converted

Nothing. Every page and fragment was converted, and no page needed an escaping change or a
section 4.4 recipe.

## Where Tessera or its documentation fell short

1. **Garbled escape table in the guide.** Section 4.10, "Scripts", says `/`, `&`, `<`, `'` and
   `{` are escaped as `\/`, `&`, `<`, `'` and `{`. All but the first appear unescaped, so the
   sentence says nothing. The `\uXXXX` forms seem to have been decoded when the document was
   generated.
2. **References to an issue tracker that isn't available.** The `tessera-migrate` report says "a
   typed alternative is tracked in #43", and `tessera-review.txt` starts with "(#428)". With no
   tracker or source available, these references are dead ends. The typed alternative for
   `*{__${name}__}` in fragments is in fact the documented `Field` parameter (guide 4.8). The
   report should point there.
3. **`tessera-migrate` left string-built query links in place.** In `ownersList.html` and
   `vetList.html` it kept `@{'/owners?page=1'}` and `@{'/vets.html?page=1'}` but reported only
   one of the non-literal links per file. I rewrote these by hand as `@{/owners(page=1)}`.
4. **The converter didn't catch `<a ... /></a>`.** Thymeleaf's HTML mode passed this markup
   through, and the build rejected it only at compile time ("end tag </a> does not match any
   open element"). The compiler message was clear, but a converter that already rewrites `<body>`
   for this kind of parse rule could fix or report it.
5. **No guidance on matching Thymeleaf's output for `null` path variables.** The guide says to
   guard such links with `t:if`, but the old engine wrote an empty segment (`/owners/`). Only the
   application's tests found this, because the seed data never has a `null` id and `compare`
   can't see it. A `compare` run can't reach this state, so a converter warning on path
   variables that come from nullable getters would help.
6. **Small packaging oddity.** `META-INF/tessera/templates.fingerprint` is packaged at the root of
   the Spring Boot jar, not under `BOOT-INF/classes`. It works, but the guide says only that
   `META-INF/tessera/**` "must be packaged" and doesn't say where it should end up in a Boot fat
   jar.
7. **Typed-attribute rules are unclear for `min`.** The guide's output-context table lists "most
   standard attributes" as typed (literals only), but `t:min` with a `LocalDate` compiled and
   rendered correctly. The rule for which standard attributes are inert text and which are typed
   isn't documented. I had to find out by compiling.
