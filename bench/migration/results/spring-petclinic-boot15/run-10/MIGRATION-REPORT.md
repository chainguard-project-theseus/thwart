# Migration report: Thymeleaf → Tessera

Spring PetClinic 1.5.1 (Spring Boot 1.5.4, Java 8, `javax.servlet`) now renders every page with
Tessera 0.1.0-SNAPSHOT. Thymeleaf is gone.

## Result

| Check | Status |
|---|---|
| `mvn clean verify` on JDK 8 (the JDK the app targets: `java.version` 1.8, Cobertura 2.7) | passes: 43 tests, 0 failures, 1 skipped (the same test was already skipped before the migration) |
| `tessera-migrate compare` on `pages.txt` (44 requests) | exit 0: every page `same`, or `reordered` for one page (see below) |
| Old engine removed | no Thymeleaf templates, dependency or configuration. `mvn dependency:list` no longer shows `thymeleaf`, `thymeleaf-spring4`, `attoparser`, `unbescape` or `spring-boot-starter-thymeleaf`. At startup the log shows only `Found 13 compiled Tessera templates` and no "another template engine is still configured" warning |
| Security | no `SafeHtml.unchecked` / `TrustedUrl.unchecked` (`target/tessera-review.txt` lists 0 calls and 0 functions), no `t:utext`, no new `permitAll`, CSRF and escaping not touched, no test disabled or weakened |

## What changed

### Build (`pom.xml`)
- Replaced `spring-boot-starter-thymeleaf` with `dev.tessera:tessera-spring-boot-starter-javax`.
  The diff of `mvn dependency:list` before and after shows that only Thymeleaf's own artifacts went
  away. The application used none of them directly.
- `maven-compiler-plugin` raised to 3.8.1 (the Boot 1.5 parent pins 3.1, which ignores
  `annotationProcessorPaths`), with `tessera-compiler` as annotation processor, `-parameters`,
  `tessera.templates`, `tessera.package=org.springframework.samples.petclinic.templates` and
  `tessera.messages` (so every `#{...}` key is checked at build time).
- Added `tessera-maven-plugin` (`check` so that changing only a template triggers a recompile,
  `review` to write the security review list).
- Removed the `thymeleaf.version` property.

### Configuration
- Removed `spring.thymeleaf.mode=HTML` from `application.properties`.

### Templates (`src/main/resources/templates`)
All 12 templates were converted, after a first pass with `tessera-migrate` and then by hand:
- `fragments/layout.html`: typed layout fragment `layout(Fragment template, String menu, String emptyLinkHref)`.
  Pages pass their `<body>` with `t:remove="tag"`.
- The menu items used `@{__${path}__}` preprocessing, which Tessera doesn't support. They are now a
  typed fragment `fragments/menuItem.html :: menuItem(String path, ...)`, and each caller passes
  a literal `@{...}` link.
- Thymeleaf also rendered the `menuItem` fragment's declaration in place, with no arguments: a stray
  first menu entry with `href=""` and `glyphicon-null`. On error pages its empty link resolved to
  the failed request's path. To keep page parity the layout writes this entry statically. Its
  `href` comes from the `emptyLinkHref` argument: `''` on normal pages, Boot's `path` error
  attribute on `error.html`. The entry is a visible defect of the original app. I kept it only for
  parity, and it can be deleted together with the parameter.
- `fragments/inputField.html` and `fragments/selectField.html` used `*{__${name}__}`. They now take a
  typed `Field` parameter (`t:field="name"`, `t:errors="name"`, `fields.hasErrors(name)`).
- Pages declare typed `t:params` (`Owner owner`, `Pet pet, Collection<PetType> types`,
  `Pet pet, Visit visit`, `Collection<Owner> selections`, `Vets vets`,
  `String message, String path`).
- `#calendars.format(date, 'yyyy-MM-dd')` became `temporals.format(..., 'yyyy-MM-dd')` on a
  `LocalDate` (see the Java section).
- `owner['new']` / `pet['new']` became `isNew()`. `th:value="*{id}"` on the pet form's hidden
  input became `pet.id == null ? '' : '' + pet.id`, so that it still writes `value=""` for a new pet.
- `ownersList.html`: `@{/owners/__${owner.id}__}` became `@{/owners/{id}(id=owner.id)}`, guarded
  with `t:if="owner.id != null"`. The existing test `testProcessFindFormSuccess` lists an owner
  without an id, which Thymeleaf rendered and a `null` path variable would fail.
- Fixed markup that Thymeleaf tolerated and a browser repairs (`<td .../></td>`, `<a/></a>`,
  self-closed `<span/>`). The DOM is unchanged, which `compare` confirms.

### Java
- `@TemplateView` on the classes the templates read: `Owner`, `Pet`, `PetType`, `Visit`, `Vet`,
  `Vets`, `Specialty` (the guide's shortcut for an existing app, instead of a view class per page).
- `java.util.Date` is not view-safe, so `Pet.getBirthLocalDate()` and `Visit.getLocalDate()`
  return the dates as `LocalDate` for display. The persistent `Date` fields and form binding
  (`@DateTimeFormat`) are unchanged.
- No controller changes were needed: every value the templates read was already in the model.

### Tests
- No existing test was changed. The existing `@WebMvcTest` tests render through the real
  resolver, and they caught the `null`-id link case above.
- Added `OwnerViewRenderingTests`, which renders owner pages through Tessera and checks that
  `<script>` and `&` in user data, including a rejected form value that is echoed back, come out
  escaped.

### Page parity
- `pages.txt` (in the repository root) holds 44 requests: home, vets (HTML and JSON), error pages
  (500 from `/oups`, 404, 400 type mismatch, 500 for a missing owner), find owners (list, single
  result redirect, not found error, empty search), owner details (with and without pets and
  visits), create and edit owner (empty form, validation errors, HTML in input, success), new and
  edit pet (empty, required errors, duplicate name, invalid date, success, HTML in the name), and
  visits (empty form, errors, invalid date, success, previous visits).
- A further 19 edge requests (missing owner or pet for each form, a 405, `/vets.xml`, `/error`
  directly, an unknown pet type, an actuator 406, and others) were recorded from the original and
  compared too. All match except the one below.
- Run against the original app 4 times, `pages.txt` #17 (`POST /owners/new` with an empty
  telephone) came out `reordered` 3 times against a recording of the original itself. The order of
  "may not be empty" and "numeric value out of bounds" changes from one run to the next because
  Hibernate Validator returns its constraint violations as a `HashSet`. This isn't something the
  migration introduced. `compare` reports it as `reordered`, which passes.

## What could not be converted exactly

- **`GET /owners/{id}/pets/{unknown}/edit`** (a pet that does not exist). Both apps return 500 with
  the error page, but the original's message is Thymeleaf's own exception text
  (`Exception evaluating SpringEL expression: "pet['new']" (template: ...)`). Tessera fails on the
  same `null` with a `NullPointerException`, which has no message on Java 8, so Boot shows
  "No message available". I didn't fake the old engine's internal error text. The status and URL
  match. Only the message, which names the removed engine, differs.
- The application doesn't start on JDK 21, before or after the migration: Spring 4.3 / Hibernate
  5.0 need `--add-opens` there. It targets and runs on JDK 8, so the build and all checks above
  used JDK 8.

## Where Tessera or its documentation fell short

1. **`#calendars` / `java.util.Date`.** `tessera-migrate` reports `#calendars` as having no
   equivalent, and `temporals.*` accepts only `java.time` types. An app whose JPA entities use
   `java.util.Date` (very common on Boot 1.5 / Hibernate 5.0) has to add `LocalDate` getters or
   view classes. A built-in way to format a `Date` would make this mechanical. Only Boot's error
   `timestamp` gets an automatic conversion.
2. **Form binding of a `Date` field contradicts the docs.** Guide section 7 says "No `t:field` that
   writes the value of a field whose type is not view-safe", and it lists `Date` as not view-safe.
   Yet `t:field` through a `Field` parameter on `Pet.birthDate` / `Visit.date` (`java.util.Date`)
   compiles and renders the `@DateTimeFormat`-formatted value correctly. Either the docs are
   stale or the compiler misses a check that the docs promise. I relied on the observed
   behaviour, which matches the original output.
3. **Quirks the guide doesn't cover.** The guide says nothing about Thymeleaf rendering a
   parameterized `th:fragment` in place, with null arguments, when it sits inside the page's
   markup. It also says nothing about how an empty link resolves on error pages. `tessera-migrate`
   didn't report either, and only `compare` found them.
4. **Self-closing non-void elements** (`<td th:text="..."/></td>`), which Thymeleaf repairs, are
   not in `tessera-migrate`'s report. The compiler does reject them, with a clear message
   (`end tag </td> does not match any open element`), so I fixed them by hand. Listing them in the
   report would save a compile round.
5. **The skill text says to fetch `tessera-migrate` with `mvn dependency:copy`.** That works, but
   copying the jar from `~/.m2` (the documented fallback) was simpler offline.
6. **Doctype.** Thymeleaf wrote no doctype for pages whose own file had none, even though the
   layout file has one. Tessera takes the doctype from the page too. The guide doesn't say so, and
   I first added doctypes to pages that had none, which `compare` flagged.
7. **`compare` and state.** The guide says to start from the same data, but `compare` doesn't warn
   when the app has already served requests that changed the data. Running two lists one after the
   other on the same instance gives misleading differences, so restart between runs.
