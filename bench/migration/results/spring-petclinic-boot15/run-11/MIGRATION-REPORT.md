# Migration report: Thymeleaf → Tessera

## Result

- Every view (`welcome`, `error`, `owners/*`, `pets/*`, `vets/vetList`, and the `layout`,
  `inputField` and `selectField` fragments) is now a Tessera template compiled by `javac`
  (12 templates, `org.springframework.samples.petclinic.templates`).
- Thymeleaf is gone: `spring-boot-starter-thymeleaf` (and with it `thymeleaf`,
  `thymeleaf-spring4`, `attoparser`, `unbescape`), the `thymeleaf.version` property and
  `spring.thymeleaf.mode` are removed; no Thymeleaf template remains. `mvn dependency:list` before
  and after differs only in those artifacts and the two Tessera ones, so nothing the app uses was
  lost. The startup log shows `Found 12 compiled Tessera templates` and no
  "another template engine is still configured" warning.
- Build: `./mvnw clean package` on JDK 8 (the app's target, `java.version` 1.8) passes:
  41 tests, 0 failures, 1 skipped (`CrashControllerTests`, already `@Ignore`d before the
  migration). No test was changed.
- Parity: a 67-request `pages.txt` (every page; empty forms, forms with validation errors,
  type-mismatch errors, duplicate pet name, successful submits and redirects, HTML/quote
  characters in data, single/multiple/no search results, owner without pets, 404, 400, 500
  pages, unknown owner/pet ids, static resources) was recorded from the original app on JDK 8 and
  compared against the migrated app started the same way: **66 `same`, 1 `reordered`, exit 0**.
  The reordered page is the owner form with all fields empty: the telephone field's two Bean
  Validation messages come out of a `HashSet` in a different order — not a template difference.
- Security: no Spring Security in this app; nothing was relaxed. No `TrustedUrl.unchecked`, no
  `permitAll`, no raw output (`t:utext`) at all, no test disabled.

## What changed

Build (`pom.xml`, `.mvn/wrapper`):
- `tessera-spring-boot-starter-javax` (Boot 1.5 / javax servlet) replaces the Thymeleaf starter.
- `maven-compiler-plugin` pinned to 3.8.1 (Boot 1.5's parent pins 3.1, too old for
  `annotationProcessorPaths`), with `tessera-compiler` as annotation processor and
  `tessera.templates`, `tessera.package`, `tessera.messages` (`#{welcome}` is checked at build time).
- `tessera-maven-plugin:check` so template- or message-only edits recompile.
- Maven wrapper bumped from 3.3.3 to 3.9.9 (the plugin needs Maven 3.6.3+).
- `.tessera/` (migration tool, baseline, jars) is git-ignored.

Java:
- `@TemplateView` on the entities the views read (`BaseEntity`, `NamedEntity`, `Person`,
  `Owner`, `Pet`, `PetType`, `Visit`, `Vet`, `Specialty`, `Vets`).
- `system/DateFunctions` (`dates.birthDate(pet)`, `dates.visitDate(visit)`): `java.util.Date` is
  not view-safe, so the `#calendars.format(..., 'yyyy-MM-dd')` calls became functions.
- `PetController.initUpdateForm`: for an unknown pet id the old page was a 500 whose message was
  Thymeleaf's own failure text (`Exception evaluating SpringEL expression: "pet['new']" ...`).
  Under Tessera the null pet fails with an NPE without a message ("No message available"). To keep
  the page identical the controller now throws that same message explicitly when the pet is not
  found. This is a deliberate compatibility shim; a cleaner choice later would be a proper 404.

Templates:
- Layout: `layout(Fragment template, String menu, String path)`. Pages pass `~{::body}` with
  `t:remove="tag"`. The `menuItem` fragment used `@{__${path}__}` preprocessing, which Tessera does
  not support; the four menu entries are written out with literal `@{...}` links and an
  `active` class conditional. Thymeleaf also rendered the `menuItem` definition itself in place as
  an extra, empty first menu entry whose link was `""` on normal pages and the request path on
  error pages (it picked up Boot's `path` error attribute); that entry is reproduced explicitly,
  fed by the new `path` layout parameter (`''` from normal pages, `path` from `error.html`).
- `inputField` / `selectField`: `*{__${name}__}` became typed `Field` parameters
  (`input(String label, Field name)`, `select(String label, Field name, Collection<PetType> items)`);
  fragment libraries' roots became `<t:block>`.
- Markup fixes the verifier requires, which don't change the DOM a browser builds: stray `</td>`
  / `</dd>` end tags removed, explicit `<tbody>` around rows written directly in `<table>`
  (`<tbody t:unless="owner.pets.isEmpty()">` on the owner page, because the browser created none
  when the owner has no pets).
- `${pet['new']}` → `pet.isNew()`; the hidden `id` input keeps `value=""` for new pets
  (`pet.id == null ? '' : '' + pet.id`).
- Owner list: `@{/owners/__${owner.id}__}` → `@{/owners/{id}(id=owner.id)}`. Tessera rejects a
  null path variable; an existing test renders an owner without id, for which Thymeleaf wrote
  `/owners/`, so a guarded alternative link keeps that.

## Not converted

Nothing was left unconverted.

## Where Tessera or its documentation fell short

- **Error text of failed renders.** A template reading a property of `null` fails with a bare
  `NullPointerException` without a message, so Boot's error page says "No message available". A
  message naming the template, line and expression (as the compiler's errors do) would be more
  useful and would have avoided the controller shim above.
- **Fragment definitions rendered in place.** Thymeleaf renders a `th:fragment` element where it
  is declared; the guide doesn't say whether Tessera does, and `tessera-migrate` didn't flag the
  layout's in-place `menuItem` definition (and its leakage of the error model's `path`) as a
  behaviour difference. It only showed up by reading the recorded baseline.
- **`tessera-migrate` conversion** left `${#calendars.format(...)}`, `*{__${name}__}` and
  `@{__${path}__}` in its output, and emitted fragment libraries with an `<html>` root without
  `xmlns:t`; fine as a starting point, but each needed hand work. It also doesn't warn that
  Thymeleaf's `th:each` + `th:if` on one element evaluates the `if` against the loop variable
  (relevant in `createOrUpdateVisitForm`, where the loop variable shadows the form object `visit`).
- **Dates.** `java.util.Date`/`Calendar` aren't view-safe and there is no built-in to format them
  (`temporals.*` takes `java.time` only), so JPA entities with `@Temporal Date` fields need
  functions or extra getters. A `dates.format(Date, pattern)` built-in would make this a mechanical
  conversion.
- **Wrapper/version requirements.** The plugin's Maven 3.6.3+ requirement and Boot 1.5's
  `maven-compiler-plugin` 3.1 pin are documented (guide section 2) but not checked: the build
  just doesn't run the processor or fails late. An explicit check with a message would help.
- **Validation message order** (`reordered` above) comes from Hibernate Validator, not Tessera;
  noted only because `compare` reports it.
