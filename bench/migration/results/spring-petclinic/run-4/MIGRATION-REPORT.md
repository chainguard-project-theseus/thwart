# Migration report: Thymeleaf → Tessera

## Result

- All 12 Thymeleaf templates were rewritten as Tessera templates, plus one new fragment file
  (`fragments/menu.html`). Every page the app serves (welcome, vets, owner search/list/details,
  owner/pet/visit forms in every state, and the Boot error page for 400/404/406/500) is rendered by
  Tessera.
- Thymeleaf is gone: `spring-boot-starter-thymeleaf` and `spring-boot-starter-thymeleaf-test` are
  removed from `pom.xml`, the Gradle dependency is replaced, and `spring.thymeleaf.mode` is removed
  from `application.properties`. No `th:` templates remain.
- `mvn clean verify` passes: 81 tests, 0 failures, 2 skipped. The 2 skipped are the MySQL
  Testcontainers tests, which skip because there is no Docker here; that was already the case before.
- Page parity: `pages.txt` (62 requests, in the repository root) was recorded from the original app
  before anything changed. `tessera-migrate compare` exits 0: 61 pages are `same` and 1 is
  `reordered`. The `reordered` page is #24, the empty owner form post. Its `telephone` field has two
  messages ("must not be blank" and "Telephone must be a 10-digit number"). Bean Validation returns
  them as an unordered set, so their order changes from run to run in either engine.
  - I also checked 9 more edge requests against the original: 400 type mismatches, bad `page`
    values, an over-long name, a visit with an empty description, and a static resource 404. All 9
    were `same`.
- Security: no `SafeHtml.unchecked`/`TrustedUrl.unchecked`, no `t:utext`, and no escaping turned
  off. The app has no Spring Security, so there is no `permitAll` or CSRF setting to touch. The new
  `OwnerViewRenderingTests` renders through the real view resolvers and checks that `<script>` in an
  owner's name is escaped in text and in a re-rendered form `value`.

## What changed

Build (`pom.xml`):
- Added `dev.tessera:tessera-spring-boot-starter` (Jakarta / Boot 4.1).
- Configured `maven-compiler-plugin` with the `tessera-compiler` annotation processor and these
  options: `-Atessera.templates`, `-Atessera.package=org.springframework.samples.petclinic.templates`
  and `-Atessera.messages` (so every `#{key}` is checked at build time). Test compilation uses
  `<proc>none</proc>`, as the guide says (#118). No other annotation processors were in use.

Java:
- Added `@TemplateView` to the entities the templates read: `Owner`, `Pet`, `PetType`, `Visit`,
  `Vet` and `Specialty`. Inherited `@MappedSuperclass` getters resolve through them.
- `WebConfiguration`: registers `ResourceUrlEncodingFilter`. Thymeleaf's Boot auto-configuration
  used to register it, and it is what turns `/webjars/bootstrap/...` into
  `/webjars/bootstrap/5.3.8/...`. Without it, every page's webjar URLs differed and pointed at
  unversioned paths.
- No controller changes were needed. Every template value was already a model attribute,
  including the flash attributes `message`/`error` and Boot's error attributes `status`/`message`.

Templates:
- `fragments/layout.html`: `layout(Fragment template, String menu)`. Pages pass `~{::body}` with
  `t:remove="tag"`. The old layout put a nested `<body>` inside the container, which browsers drop,
  so the DOM is the same.
- `fragments/menu.html`: `menuItem(String link, String active, String menu, ...)`. The old version
  was an inline fragment inside a `th:remove="all"` list, built its link with `@{__${link}__}`, and
  read the caller's `menu` variable. Now the caller passes `@{/...}` links as `String` arguments and
  passes `menu` explicitly.
- `fragments/inputField.html` / `selectField.html`: the `*{__${name}__}` preprocessing became a
  `Field` parameter. The visit form's `th:min="${minVisitDate}"`, which read a caller variable,
  became a `LocalDate min` argument (`null` everywhere else, which drops the attribute, as before).
  The `<option>` values use `item.name`. Before, Thymeleaf converted the `PetType` through the
  formatter, which gave the same string.
- Pages: `t:params` with real types, and `${x['new']}` → `x.isNew()`.
  `#strings.listJoin` → `strings.join`, `#fields.hasAnyErrors()/allErrors()` →
  `fields.hasErrors('*')/errors('*')`, and `#temporals.format` → `temporals.format`. String-built
  links (`@{'/owners?page=' + ${i}}`, `@{__${owner.id}__/edit}`) became `@{/owners(page=i)}` and the
  page-relative `@{{id}/edit(id=owner.id)}`. `owner?.firstName + ' ' + ...` became
  `|${owner.firstName} ${owner.lastName}|`, and `${pet.type}` (which relied on `toString()`) became
  `pet.type?.name`.
- `ownersList.html`: Thymeleaf rendered an owner with a `null` id as `/owners/`, and
  `OwnerControllerTests` relies on that: it lists a `new Owner()`. Tessera fails the render on a
  `null` path variable, so the link is
  `owner.id == null ? @{/owners/} : @{/owners/{id}(id=owner.id)}`.
- Malformed markup the old engine passed through was fixed: `<a ... /></a>`, `<td ... />` and
  `<span ... />`. The parsed DOM is the same.

Tests:
- `I18nPropertiesSyncTest`: its regex for "this line is translated" matched Thymeleaf's attribute
  name `th:(u)?text`. It now matches `t:(u)?text`; that is the only change. To keep the check
  strict, the `t:errors` spans lost their placeholder text "Error". The old version escaped the
  check only through a `th:text="#{error}"` that `th:errors` overrode anyway.
- Added `OwnerViewRenderingTests` (escaping through the real resolvers).

## What could not be converted / known gaps

- Nothing is left unconverted.
- Gradle: I updated `build.gradle` (Tessera dependency, `annotationProcessor` and the compiler
  args), but the guide says Tessera supports Maven only, and I did not run the Gradle build. Treat it
  as unverified.
- `README.md` still mentions Thymeleaf in the history of the upstream project. That describes the
  project's origin, not this build, so I left it.

## Where the guide or Tessera fell short

1. **`ResourceUrlEncodingFilter`.** The guide says links go through `response.encodeURL`, but not
   that Thymeleaf's Boot auto-configuration is what registers the filter that makes `encodeURL`
   rewrite resource URLs. Removing Thymeleaf silently drops the webjar version from every
   `<link>`/`<script>` URL. Every page differed until I registered the filter myself. This belongs
   under "Remove the old engine".
2. **`null` path variables.** Thymeleaf's `@{/x/__${id}__}` turns `null` into an empty segment.
   Tessera throws. The guide mentions this under limitations, but existing controller tests with
   unsaved entities are a common way to hit it. Tessera has no null-tolerant link form, so I needed
   a conditional between two links.
3. **Where fragments can live.** The guide does not say whether a parameterized fragment defined
   inside another fragment's element (the old `menuItem`, nested in the `layout` fragment) is
   skipped when the outer fragment renders. I moved it to its own file to avoid relying on
   undefined behaviour.
4. **`th:remove="all"` holders.** Nothing in the guide covers the Thymeleaf idiom of keeping
   fragment definitions inside a removed element.
5. **`t:errors` together with `t:text`.** The guide lists both in the attribute order but does not
   say whether they can share an element (Thymeleaf lets `th:errors` win). I avoided the
   combination.
6. **`t:field` on a `null` value.** The guide says the value is "omitted when null", so I expected
   `value=""` to disappear, as Thymeleaf's would not. In practice Tessera wrote `value=""` for
   null text fields, matching Thymeleaf. The guide's wording is misleading here.
7. **Restarting between runs.** `record`/`compare` hit whatever answers on the port. If the old app
   is still shutting down when the new one starts, `compare` silently runs against the old app. Make
   sure the port is free before starting the new app; the guide does not warn about this.
