# Migration report: Thymeleaf → Tessera

## Summary

All 12 Thymeleaf templates (9 pages, 3 fragment libraries) are now Tessera templates, compiled into
`org.springframework.samples.petclinic.templates` during `javac`. Thymeleaf is no longer a
dependency, and none of its configuration is left. The Maven build (`./mvnw -B package`) passes on
JDK 8, the JDK the project targets (`java.version` 1.8). All 41 tests pass. The one skipped test,
`CrashControllerTests`, was already `@Disabled` before the migration. No test was changed.

## What changed

**Build**
- `pom.xml`: removed `spring-boot-starter-thymeleaf`. Added `tessera-spring-boot-starter-javax`,
  because this is Boot 2.7 on `javax.servlet`. Added the `tessera-compiler` annotation processor to
  `maven-compiler-plugin`, with `-Atessera.templates`, `-Atessera.package` and
  `-Atessera.messages`, so every `#{key}` is checked at build time against
  `messages/messages.properties`. Added `tessera-maven-plugin:check` so that editing only a
  template or a message triggers a recompile. New properties: `tessera.version` and
  `tessera.messages`.
- `build.gradle`: made the same change for the Gradle build: the starter, the annotation processor,
  the compiler args and the template/message inputs. Also added `mavenLocal()`, because Tessera is
  only in the local repository. The Gradle build was not run.
- `application.properties`: removed `spring.thymeleaf.mode=HTML`.
- `.gitignore`: added `.tessera/`, the scratch directory for the migration tool, its baseline and
  `pages.txt`.

**Java**
- Added `@TemplateView` to the entities the templates read: `Owner`, `Pet`, `Visit`, `PetType`,
  `Vet` and `Specialty`. This is the guide's shortcut for porting quickly. No controller changes
  were needed: every value the templates read was already in the model, apart from Boot's error
  attributes, which the starter supplies.

**Templates**
- `fragments/layout.html`: `layout(Fragment template, String menu)`. Pages pass `~{::body}` with
  `t:remove="tag"`. The old page put a `<body>` inside a `<div>` and browsers dropped it, so the
  DOM is unchanged. The old `menuItem` fragment built its link with `@{__${link}__}`
  (preprocessing), which Tessera does not have. I wrote the four menu items out with literal
  links and kept the `active` class choice.
- `fragments/inputField.html` and `fragments/selectField.html` are now typed fragments that take a
  `Field` instead of the `*{__${name}__}` preprocessing. Each library's root is now a `<t:block>`
  instead of `<html><body><form>`.
- Pages: replaced string-built links (`@{__${owner.id}__/edit}`, `@{'/owners?page=__${i}__'}`)
  with links that use placeholders and query parameters. The `${pet['new']}` checks became
  `pet.isNew()`. Added explicit `<tbody>` elements where rows sat directly in a `<table>`; the one
  on the owner's pets table is conditional, because the browser creates none for an empty table.
  Wrote out the self-closing non-void tags (`<td .../>`, `<span .../>`) and the stray `</a>`
  explicitly, as Thymeleaf's output did. Renamed the loop variable in the visit form so it no
  longer shadows the `visit` form object.
- `owners/ownersList.html`: an owner with a `null` id (one of the test fixtures) made Thymeleaf
  write `href="/owners/"`. Tessera refuses a `null` link variable, so a `t:if`/`t:unless` pair now
  writes the same `/owners/` link in that case.
- The hidden `id` input in the pet form keeps Thymeleaf's `value=""` for a new pet
  (`*{id} == null ? '' : '' + *{id}`).

## Verification

- `tessera-migrate record` ran against the original app (built and run on JDK 8, port 8081, fresh
  H2 data). `compare` ran against the migrated app started the same way. My `pages.txt` (in
  `.tessera/`, 42 requests) covers:
  - every page and the paginated lists (page 1 and page 2, with and without results)
  - the not-found and single-result redirect cases of the owner search
  - every form empty, with validation errors (required fields, bad number, bad date, duplicate
    pet name) and after success
  - HTML-special characters in user data
  - the 404, 400, 406 and 500 error pages

  Result: **42 same, 0 differ**. In one run, the owner-form page with every field empty came out
  `reordered` instead: the two Bean Validation messages on `telephone` (`@NotEmpty` and `@Digits`)
  come from an unordered set, so their order changes from run to run in both engines.
- A second list of edge requests also came out 9/9 `same`. It covers an unknown pet or owner on
  the edit, visit and new-pet pages (the 500 error page), pages past the end of the lists, and
  `/error`.
- At startup the new app logs `Found 12 compiled Tessera templates` and does not log the "another
  template engine is still configured" warning. `mvn dependency:list` no longer contains any
  `thymeleaf` artifact. Thymeleaf's starter brought no library that the application code uses.
- Security: the app has no Spring Security, so there is no CSRF or `permitAll` setup to keep. No
  `TrustedUrl.unchecked`, no raw output (`t:utext`) and no disabled tests. User data is escaped in
  the same way Thymeleaf escaped it, as the escaping test page shows.

## Not converted

Nothing was left unconverted. Two pieces of the old markup were not ported literally, because
Tessera has no equivalent:
- The `menuItem` fragment's link built from a string, `@{__${link}__}`: replaced by the four menu
  items written out (identical output).
- `th:remove="all"` around that fragment's definition: no longer needed and removed.

## Where Tessera or its documentation fell short

- **No link parameter for fragments.** A fragment cannot take a link or path as an argument and
  pass it to `@{...}`, because link paths must be literals. So a reusable nav-item fragment such as
  `menuItem(link, ...)` cannot be ported as a fragment, and I wrote each item out by hand. The guide
  does not show a pattern for "fragment parameterised by a link". A `String` passed to `t:href`
  would work, but it would lose the context path and URL encoding, so I did not use it.
- **`null` link variables are a hard render failure.** Thymeleaf wrote an empty segment. The guide
  documents this (sections 3 and 4.5), but the fix is to write the element twice with
  `t:if`/`t:unless`. There is no inline way to say "this link, or that literal link".
- **The converter's reports are generic.** It flagged `th:value` on `*{id}` as possibly null, which
  was useful. But it did not warn about rows directly in a `<table>`, which become a `<tbody>`
  decision, or about an empty `<tbody>` differing from the browser's implicit one. Only `compare`
  found that.
- **The guide's `<tbody>` advice can itself cause a difference.** Section 4.8 says "write the
  `<tbody>`", but a `<table>` with zero rows from a `t:each` gets no `<tbody>` in the browser. An
  unconditional explicit `<tbody>` therefore changes the DOM for empty lists. The guide could say
  to guard it, or put `t:unless="list.isEmpty()"` on it.
- **Tool ergonomics.** The `record`/`compare` workflow needs the old app restarted from fresh data
  for each recording. The tool cannot reset that state, and recording mutates it, so re-recording
  needs a manual restart. This is documented ("start the app from the same data"), but it is easy
  to get wrong.
- **Mixed error output.** The skill says to fetch `tessera-migrate` with `mvn dependency:copy`. That
  worked, but in this project every Maven run prints a `[ERROR] Failed to perform fetch` line from
  `git-commit-id-plugin`, unrelated to Tessera, which makes it harder to spot real errors.
- No Tessera bugs or `ENGINE_FAILURE`s were hit. All templates compiled on the first attempt after
  the types were filled in, on JDK 8.
