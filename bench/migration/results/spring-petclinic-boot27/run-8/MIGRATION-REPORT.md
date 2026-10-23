# Migration report: Thymeleaf → Tessera

## Result

| Check | Status |
|---|---|
| Every page rendered by Tessera | Yes. All 12 templates (2 pages, 3 fragment files, 7 owner/pet/vet views) plus `error.html` for 400/404/500 are Tessera templates. |
| Old engine removed | `spring-boot-starter-thymeleaf` removed from `pom.xml` and `build.gradle`, `spring.thymeleaf.mode` removed from `application.properties`, no `th:` template is left, and `mvn dependency:tree` has no Thymeleaf artifact. |
| `mvn clean verify` | Passes on JDK 8 (`/usr/lib/jvm/java-1.8-openjdk`, the JDK the app targets with `java.version` 1.8) and on JDK 21. 43 tests: 42 pass and 1 is skipped. The skipped one is `CrashControllerTests`, which was already `@Disabled` upstream. No existing test was changed. |
| Page parity | `tessera-migrate compare` exits 0 on 50 requests. 49 are `same` and 1 is `reordered` (see below). |
| Security | No `SafeHtml.unchecked`, no `TrustedUrl.unchecked`, no `t:utext`, and no new `permitAll`. The app has no Spring Security, so CSRF did not change. Escaping is on everywhere. |

## What changed

- **`pom.xml`**
  - Replaced the Thymeleaf starter with `dev.tessera:tessera-spring-boot-starter-javax` (Boot 2.7 uses `javax.servlet`). Kept the Boot version.
  - Configured `maven-compiler-plugin` with the `tessera-compiler` annotation processor, `-Atessera.templates`, `-Atessera.package=org.springframework.samples.petclinic.templates` and `-Atessera.messages`, so `#{welcome}` is checked at build time.
  - Added `**/.tessera/**` to the nohttp checkstyle excludes. This is my local migration workspace, which is git-ignored.
- **`build.gradle`**: made the same dependency change and added `mavenLocal()` and the processor options. **This is not verified**: there is no Gradle run in this environment, and Tessera documents no Gradle support.
- **`application.properties`**: removed `spring.thymeleaf.mode`.
- **Entities**: added `@TemplateView` to `Owner`, `Pet`, `Visit`, `PetType`, `Vet` and `Specialty`. Without it, templates can't read their getters. No other code changes and no controller changes were needed.
- **Templates** (rewritten in place, same file names and view names):
  - `fragments/layout.html`
    - It is now `layout(Fragment template, String menu)`.
    - `menuItem` used `@{__${link}__}` (preprocessing). It now takes the link as a `String` built by a literal `@{/...}` at each call site, and receives `menu` explicitly because a fragment can't see its caller's variables.
  - `fragments/inputField.html` and `fragments/selectField.html`: the dynamic `*{__${name}__}` field paths became a typed `Field name` parameter. Select options use `item.name`.
  - Pages: replaced `th:` with `t:` and declared typed `t:params`.
    - `owner['new']` became `owner.isNew()`.
    - `pet.type` became `pet.type?.name`.
    - `#strings.listJoin` became `strings.join`.
    - String-built links (`@{'/owners?page=__${i}__'}`, `@{__${owner.id}__/edit}`) became `@{/owners(page=i)}` and `@{{ownerId}/edit(ownerId=...)}`.
    - In the visit form, the loop variable is renamed from `visit` to `previous` so it doesn't shadow the `visit` parameter. Thymeleaf's semantics (`th:each` runs before `th:if`) are preserved.
  - `error.html` reads Boot's `message` error attribute.
- **New test** `OwnerViewRenderingTests`: renders `/owners/{id}` through the real Tessera resolver. It checks that `<script>`, quotes and `&` in owner and pet data come out escaped, and that the id-based relative links are right. The existing controller tests (`@WebMvcTest`) also render every view through Tessera now.

## Parity check

- I recorded the original app (built on JDK 8, fresh H2 data) and compared it against the migrated app started the same way.
- The 50 requests cover:
  - home and the vet list (pages 1 and 2);
  - find-owners: empty search, not found (form error), single hit (redirect) and multiple hits with paging;
  - owner details, including owners with and without pets and visits;
  - the owner form: new and edit, with validation errors and on success. This includes an XSS payload that is then shown on the details page;
  - the pet form: new and edit, with an empty name, a duplicate name, an invalid date and success;
  - the visit form: empty description, invalid date and success;
  - error pages: `/oups` (500), an unknown URL (404), a non-numeric id (400), an unknown owner (500) and a pet that isn't the owner's (500).
- One page is `reordered`: #17, the owner form with all fields empty. The telephone field has two messages, "must not be empty" and "numeric value out of bounds", and they come in another order. Hibernate Validator returns its violations in a `HashSet`, so the order isn't stable in the original app either. This is harmless.

## Not converted / deviations

- Nothing was left unconverted.
- One deliberate workaround: Thymeleaf rendered a `null` id in `@{/owners/__${owner.id}__}` as the text `/owners/null`, and Tessera fails the render on a `null` link path variable. The existing test `OwnerControllerTests.testProcessFindFormSuccess` lists an `Owner` with no id. To keep Thymeleaf's behaviour, the owner and pet id links pass `'' + owner.id` and `'' + pet.id`. For a non-null id the output is the same.

## Where Tessera or its documentation fell short

1. **Null path variables.** Tessera fails the render on a `null` path variable, where Thymeleaf printed `null`. An existing test hit this. The guide mentions it only in "known limitations" and in the expected-differences list. It suggests no idiom to keep the old output, and I had to find the `'' + x` workaround myself.
2. **The guide is wrong about `t:field` and null values.** Section 4.7 says `t:field` writes the display value "omitted when null", and section 7 lists "a `null` attribute value omits the attribute (Thymeleaf writes `value=""`)" as an expected difference. In practice `t:field` writes `value=""` for a null field, the same as Thymeleaf. That's good, but the docs say otherwise. Following the documented rule that a `null` attribute is omitted, I wrote the hidden `id` input in the pet form as `pet.id == null ? '' : '' + pet.id` so it keeps `value=""`. I didn't check whether a plain `t:value` would also have written `value=""`.
3. **`tessera-migrate` contradicts the guide on dynamic field paths.** For `*{__${name}__}`, the converter's report says the path is "not supported … inline one element per field (a typed alternative is tracked in #43)". But the guide (section 4.8) documents exactly that typed alternative, `Field` fragment parameters, and it works. The report also points to an issue number in a tracker that doesn't exist.
4. **The converter's output is a long way from compiling.** It adds no `xmlns:t` to fragment files, leaves every type as `Object`, and leaves `__...__` in place. It also leaves quoted string links such as `@{'/owners?page=1'}` untouched. The report flags most of these, so the files are a starting point rather than a near-finished conversion. I wrote the templates by hand.
5. **Gradle is unsupported and undocumented** (guide section 7). The project ships a `build.gradle`. I updated it by analogy, but it isn't tested.
6. **Two smaller gaps in the guide.** It doesn't say whether a parameterised fragment defined inside a `t:remove="all"` element (Thymeleaf's `menuItem` pattern) can still be called. It can, but I found that out by compiling. It also doesn't say which of Boot's error attributes are present when `server.error.include-message` is off: `message` is then absent or empty, which matched the original.
7. **The skill text points to a GitHub repository for building Tessera**, but the task says Tessera has no published source. That didn't block anything.
