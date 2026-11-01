# Migration report: Thymeleaf → Tessera

## Result

| Check | Status |
|---|---|
| Every page rendered by Tessera | All 11 templates (`welcome`, `error`, owners ×4, pets ×2, `vets/vetList`, the layout and the form-field fragments) are Tessera templates. At startup the app logs `Found 11 compiled Tessera templates` and there is no `another template engine is still configured` warning. |
| Old engine removed | `spring-boot-starter-thymeleaf`, `spring-boot-starter-thymeleaf-test` and `spring.thymeleaf.mode` are removed. Diffing `mvn dependency:list` before and after: the only removals are `thymeleaf`, `thymeleaf-spring6`, `spring-boot-thymeleaf`, `attoparser` and `unbescape`. The app doesn't use any of them directly, so nothing needed to be re-declared. |
| `mvn clean verify` (JDK 21 building `java.version` 17, as before) | Passes: 80 tests, 0 failures. That is the 79 existing tests plus one new one. The 2 skipped tests are the MySQL and Postgres Testcontainers tests (`disabledWithoutDocker`), which also skip without Docker on the original app. |
| Page parity | `tessera-migrate compare` exits 0 on `pages.txt` (79 requests). 78 pages are `same` and 1 is `reordered` (details below). |
| Security | No `SafeHtml.unchecked` or `TrustedUrl.unchecked` (`target/tessera-review.txt` lists 0 calls and 0 `@TemplateFunctions`). No `permitAll`; the app has no Spring Security. No escaping turned off, and no tests disabled. |

## What changed

### Build (`pom.xml`)
- Replaced `spring-boot-starter-thymeleaf` with `dev.tessera:tessera-spring-boot-starter:0.1.0-SNAPSHOT` (jakarta, for Boot 4.1).
- Removed the test dependency `spring-boot-starter-thymeleaf-test`.
- `maven-compiler-plugin` now runs `tessera-compiler` as an annotation processor, with these settings:
  - `tessera.templates=src/main/resources/templates`
  - `tessera.package=org.springframework.samples.petclinic.templates`
  - `tessera.messages=src/main/resources/messages/messages`, so every `#{key}` is checked at build time.
- Added `tessera-maven-plugin` (`check` and `review`), so a change to only a template or a message still triggers a recompile.
- `<release>` is left unset, so the Boot parent still derives it from `java.version` 17.

### Gradle (`build.gradle`)
- Made the same changes: the starter, `annotationProcessor`, the compiler args, the template and message inputs, and `mavenLocal()`.
- I could not run the Gradle build here (see below).

### Configuration
- Removed `spring.thymeleaf.mode=HTML` from `application.properties`.

### View types
- Added `@TemplateView` to the entities the templates read: `Owner`, `Pet`, `PetType`, `Visit`, `Vet` and `Specialty`. I chose this shortcut over per-page view records to keep the controllers unchanged.
- Lazy loading during rendering is not a concern here: all associations are `EAGER`, and `open-in-view=false` was already set.

### Templates (converted with `tessera-migrate`, then finished by hand)

**Layout (`fragments/layout.html`)**
- `layout(Fragment template, String menu)`.
- Each page passes its `<body>` with `t:remove="tag"`.
- `menuItem` used `@{__${link}__}` and read the caller's `menu` variable. It now takes the link as `@{...}` and the active state as a `boolean` computed at the call site, because fragments can't see the caller's variables.

**Form fields (`fragments/inputField.html`)**
- The `input` and `select` fragments use typed `Field` parameters instead of `*{__${name}__}`.
- The `select` fragment, formerly `selectField.html`, now lives in this file.
- The date input's `th:min="${minVisitDate}"` read a variable from the caller's scope. It is now an explicit `LocalDate min` argument: the visit form passes `minVisitDate`, and the other forms pass `null`, which omits the attribute, just as before.
- The error spans no longer carry `th:text="#{error}"` together with `th:errors`. Thymeleaf's `th:errors` always overrode it.

**Expressions and links**
- `owner['new']` / `pet['new']` / `visit['new']` became `isNew()`.
- `#fields.hasAnyErrors()` / `allErrors()` became `fields.hasErrors('*')` / `fields.errors('*')`.
- `@{__${owner.id}__/edit}` and similar became page-relative links with placeholders: `@{{ownerId}/edit(ownerId=owner.id)}`.
- The string-built pagination links became `@{/owners(page=...)}` and `@{/vets.html(page=...)}`.
- `${pet.type}` (printed via `toString()`) became `pet.type?.name`. The options of the pet-type `<select>` use `item.name`, which is what `PetType.toString()` and `PetTypeFormatter` produce.
- The new pet's hidden `id` input is written as `pet.id == null ? '' : '' + pet.id`. Thymeleaf wrote `value=""` for null, and Tessera would otherwise omit the attribute.
- In the visit form the loop variable is now `v`, so it no longer shadows the `visit` form object. The evaluation order (each, then if) is unchanged.

**Owner list: null ids**
- The name link is guarded: if `owner.id` is null it renders `@{/owners/}`, as Thymeleaf's preprocessing did.
- Without the guard, the existing `OwnerControllerTests`, whose fixtures include an unsaved `Owner`, failed with Tessera's "null link path variable" error.

**Markup**
- Fixed markup that Thymeleaf passed through but the build-time HTML verifier rejects: self-closing `<td/>`, `<span/>` and `<a/>`, a stray `</a>`, and `<th:block .../>`.

### Tests
- `I18nPropertiesSyncTest` looks for `th:(u)?text` to decide whether a line's literal text is only a prototype. That is a detail of the old engine, so the pattern now also accepts Tessera's `t:` prefix: `(?:th|t):(u)?text`. Nothing else in the test changed.
- New test `OwnerViewEscapingTests`: a `@WebMvcTest` that renders `owners/ownerDetails` through the real view resolver, and checks that a `<script>` owner name and an `<i>` pet name come out escaped.

### Migration artefacts kept in the repo
- `pages.txt`: the 79 requests, covering every page and form state.
  - Empty, error and success states of every form, including field length limits, bad dates and future dates.
  - Single and multiple search results, no results, and pagination, including out-of-range and overflowing page numbers.
  - The 400, 404, 406 and 500 error pages.
  - The `de` and `es` locales.
  - XSS and `{{...}}` payloads.
- `baseline/`: the recording of the original app.
- `.tessera/`: the migrate tool, git-ignored.

## Remaining differences (judged correct)
- **Request 15 / 51** (owner form posted empty) is `reordered`. The telephone field has two validation messages (`must not be blank` and the 10-digit pattern), and their order comes from Bean Validation's unordered `Set` of violations. Between runs of the *original* app they also swap (request 15 was `same` on one run and `reordered` on another). The content is identical.

## Not converted
- Nothing. Every view the app renders is a Tessera template.

## Where Tessera or its documentation fell short
1. **Stale `tessera-migrate` report text.**
   - It reports `*{__${name}__}` in fragments as "not supported … inline one element per field (a typed alternative is tracked in #43)".
   - Guide section 4.8 documents exactly that typed alternative (`Field` parameters), and it works.
   - The report should point to `Field` instead of suggesting the fields be inlined.
2. **`t:errors` and `t:text` on the same element.** The tool converted `th:errors` together with `th:text` into both `t:` attributes and did not report it. The guide says both are in the same attribute-order group, but not which one wins. I removed the `t:text` to keep Thymeleaf's behaviour, where `th:errors` wins.
3. **Null link path variables.** Rendering an unsaved entity in a list (a pattern the app's own tests use) fails with an exception, where Thymeleaf rendered a link.
   - This is documented, and `tessera-migrate` lists every non-literal link.
   - But the report doesn't separate links whose variable can realistically be null from the rest, so every list page has to be checked by hand.
4. **Fragments can't see the caller's model.** This is by design, but `tessera-migrate` stubbed the old implicit read (`minVisitDate` inside the input fragment) as a *page* `t:params` entry on the fragment file. That stub is wrong: the value has to become a fragment parameter that each caller passes.
5. **Tests that assert on template source.** Neither the guide nor the tool warns that an app's tests may grep template files for `th:` attributes (like `I18nPropertiesSyncTest` here).
6. **Inconsistent installation instructions.** The skill text says to build Tessera from `github.com/wolfi-dev/tessera`, while the environment provides only the locally installed artifacts and no source. It didn't block anything.
7. **No Gradle check.** The guide documents the Gradle wiring, but the `review` and `check` goals have no Gradle equivalent. I could not run the Gradle build here to verify `build.gradle`; the Maven build is the one that was verified.
