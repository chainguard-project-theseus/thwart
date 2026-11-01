# Migration report: Mustache (JMustache) → Tessera

## Result

| Check | Status |
|---|---|
| Every page rendered by Tessera | Yes. 12 compiled templates; no other view resolver is configured, and there is no "another template engine is still configured" warning at startup. |
| Old engine removed | `spring-boot-starter-mustache` and `com.samskivert:jmustache` are gone from `pom.xml`, all 12 `*.mustache` templates are deleted, and the Mustache-only Java support classes (`Form`, `FormField`, `InputField`, `SelectField`, `SelectValue`) are deleted. `mvn dependency:list` before/after: the only removals are those two artifacts (the only additions are `tessera-runtime` and `tessera-spring-boot-starter`), so the app lost no libraries it still uses. |
| `mvn clean verify` (JDK 21, `java.version` 17) | Passes: 43 tests (the 42 existing tests unchanged, plus 1 new test), checkstyle/nohttp and spring-javaformat both pass. |
| Security | No `SafeHtml.unchecked` / `TrustedUrl.unchecked` (`target/tessera-review.txt` lists 0 calls and 0 `@TemplateFunctions`). No `t:utext`. No security config, CSRF or escaping changes. No tests disabled. |
| Page parity (`tessera-migrate compare`, 40 requests in `pages.txt`) | 34 `same`, 1 `reordered`, **5 `differs`**. All 5 come from one malformed tag in the original templates that Tessera cannot emit (see below). |

## What changed

**Build (`pom.xml`)**
- Removed `spring-boot-starter-mustache` and `jmustache`. Added `tessera-spring-boot-starter`.
- Added `maven-compiler-plugin` config with `tessera-compiler` on `annotationProcessorPaths`. I also listed `spring-boot-configuration-processor` there, because `annotationProcessorPaths` turns off processor discovery. Added `-Atessera.templates`, `-Atessera.package=org.springframework.samples.petclinic.templates` and `-Atessera.messages`.
- Added `tessera-maven-plugin` (`check` and `review` goals).
- The artifactId `spring-petclinic-mustache` is unchanged, so the jar name and the way the app is started stay the same.

**Templates** (`src/main/resources/templates/**/*.html`, one for each old `.mustache` file)
- `fragments/layout.html`: the layout fragment `layout(List<MenuItem> menus, Fragment body, boolean footer)`. Pages call it with `t:replace` and pass `~{::body}` with `<body t:remove="tag">`.
- `fragments/inputField.html` and `fragments/selectField.html`: fragments that take a `Field` parameter. They read the value, errors and has-error state from the `BindingResult` through `fields.value/hasErrors/errors`. I did not use `t:field`, because it adds an `id` attribute the old pages did not have.
- `owners/*`, `pets/*`, `vets/vetList.html`, `welcome.html`, `error.html`: the remaining pages.

**Java**
- `@TemplateView` on the entities the templates read: `Owner`, `Pet`, `PetType`, `Visit`, `Vet`, `Specialty`. This is the guide's shortcut. A view record per page would expose less, but it would mean rewriting every controller and test fixture.
- `LayoutAdvice` (an interceptor): it still picks the active menu from the model (`owner`/`owners` → Owners, `vets` → Vets, otherwise Home). It now adds an immutable `List<MenuItem>` built for each request, where it used to mutate the shared singleton `Application.Menu` objects (a race between concurrent requests). The `RequestContext`/`Form` binding code and the `errors` model attribute are gone; templates read the `BindingResult` directly. `Application.Menu.active` was removed.
- New records: `MenuItem` (menu entry) and `PageLink` (pagination entry). `PageLink` replaces the `Map<String, Object>` page models, because `Object` is not view-safe.
- `OwnerController`: removed the `OwnerForm` model attribute (`form`).
- `PetController`: removed `PetForm`. The pet types are now a `@ModelAttribute("types")`.
- `PetController.initCreationForm` now has `Assert.state(pet.getBirthDate() != null, ...)`. The old `PetForm` called `pet.getBirthDate().toString()`, so a pet form submitted with an empty birth date answered 500. This line keeps that behaviour, so `POST /owners/1/pets/new` with `birthDate=` is still a 500 error page.
- New test `OwnerControllerTests.testShowOwnerEscapesUserData`. It renders the owner page through the real Tessera view resolver and checks that `<script>` in user data comes out escaped.

## Original behaviour kept on purpose (for page parity)

The old app is the specification, so these quirks were reproduced. They are all candidates for a follow-up fix:
- **Empty vets table.** `vetList.mustache` iterated `listVets`, but the controller only provides `vets`, so the table was always empty. The template still declares `List<Vet> listVets`, which the model never fills.
- **Visit form without inputs.** `createOrUpdateVisitForm.mustache` rendered its fields from a `form` attribute that `VisitController` never set, so the page has no date or description inputs.
- **Error page cut off.** In the original, `{{message}}` was missing from Boot's error model (`server.error.include-message=never`), so JMustache threw and the response stopped after `<p>`: no footer and no script. The layout's `footer` parameter is `false` only for `error.html`, which reproduces the same document.
- **Leftover `th:` attributes.** The static `th:text="#{welcome}"`, `th:value="*{id}"` and `th:value="${pet.id}"` attributes (Thymeleaf leftovers in the Mustache templates) are still written as static text, as before.
- **Relative links.** `href="1/edit"`, `href="resources/images/..."` and the other relative links are unchanged.

## What could not be converted: 5 `differs` pages

`GET /owners`, `/owners?page=2`, `/owners?lastName=`, `/vets.html` and `/vets.html?page=2` (every list with more than one page).

The pagination markup in `ownersList.mustache` and `vetList.mustache` contained a typo, `<span">`. A browser reads that as an element literally named `span"`, and its `</span>` end tags then nest the rest of the pager inside it. Tessera's parser rejects it at build time: `malformed markup: quote " in a tag or attribute name`. No safe Tessera construct can produce an element with that name. Raw output would need `SafeHtml.unchecked`, which is not allowed and would be wrong anyway. So the templates write the intended `<span>`.

The compare diffs for these pages differ only in those tags and their nesting: same text, same links, same classes. This is "broken markup the old engine passed through", and I consider the difference correct.

## Other differences
- `POST /owners/new` with empty fields comes out `reordered`: the two Bean Validation messages on `telephone` swap order. Their order comes from a `HashSet` and also varies between runs of the original app, so this is harmless.

## Where Tessera or its documentation fell short
1. **No way to keep the old engine's broken markup.** Pages whose original markup was malformed can never be `same` in `compare`. That is a reasonable design, but the guide does not say what to expect for markup the old engine passed through that Tessera's verifier rejects: in which cases `compare` will always differ, and how to record that as accepted (an allow-list or annotation in `compare` would help).
2. **Mustache has no section in 4.10.** The guide names Mustache among the engines but has no notes for it. Missing points:
   - Context-stack lookup: `{{owner.id}}` inside `{{#pets}}` falls back to outer scopes, while Tessera fragments and loops see only declared names.
   - JMustache throws on a missing variable after the response is committed, so the recorded error pages are truncated documents that the new app has to reproduce.
   - Partials-with-blocks (`{{<layout}}{{$body}}`) map to `Fragment` parameters.
   - `tessera-migrate` has no rewrite for Mustache, only for Thymeleaf.
3. **`t:field` always writes `id`.** The guide documents this. Matching a page that had no ids meant giving up `t:field` and using `t:name` + `fields.value(...)` on a `Field` parameter. An option to suppress the generated `id` would help parity-driven ports.
4. **`Field` printed as an attribute value.** Printing it writes the field's id, which equals the name only for paths without indexes. The guide says so only in passing; a `fields.name(field)` would be clearer.
5. **Layout parameters have no defaults.** Adding one layout parameter (`footer`) meant editing every page's layout call. Optional or defaulted fragment parameters would avoid that.
6. **Tooling noise (not Tessera's fault).** `[ERROR] Failed to perform fetch` comes from the existing `git-commit-id-plugin`; it was already there before the migration and does not fail the build.

## Files for re-checking
- `pages.txt`: 40 requests covering every view and form state: empty, with validation errors (including type mismatches) and after success; lists with 0, 1 and several results and two pages; error pages 400, 404 and 500.
- `baseline/`: the recording of the original app (untracked).
- `.tessera/tessera-migrate.jar`: the migration tool. It is git-ignored.

Re-run: start the app on fresh H2 data, then `java -jar .tessera/tessera-migrate.jar compare http://localhost:8080 pages.txt baseline/`.
