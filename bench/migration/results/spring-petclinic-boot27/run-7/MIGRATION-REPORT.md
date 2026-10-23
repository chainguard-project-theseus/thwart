# Migration report: Thymeleaf → Tessera

## Result

| Check | Status |
|---|---|
| Every page is rendered by Tessera; Thymeleaf's templates, dependency and config are gone | Done. `spring-boot-starter-thymeleaf` is removed from `pom.xml` and `build.gradle`, `spring.thymeleaf.mode` is removed from `application.properties`, and all 12 templates are rewritten in Tessera syntax. No `th:` attribute and no Thymeleaf jar is left (checked in the packaged jar). |
| `mvn clean verify` | Passes on JDK 8 (the target, `java.version=1.8`) and on JDK 21. 42 tests run, 0 failures. The one skipped test, `CrashControllerTests`, was already `@Disabled` before the migration. |
| Page parity | `tessera-migrate compare` exits 0 on `pages.txt` (60 requests): all 60 pages `same` on the final run. |
| Security | No `SafeHtml.unchecked` or `TrustedUrl.unchecked`, no `t:utext`. No security config changed. Escaping is on everywhere. |

## What changed

- **Build (`pom.xml`)**: added `dev.tessera:tessera-spring-boot-starter-javax` (Boot 2.7 uses `javax.servlet`). Configured `maven-compiler-plugin` with:
  - `tessera-compiler` as the annotation processor;
  - `-Atessera.templates`, `-Atessera.package=org.springframework.samples.petclinic.templates` and `-Atessera.messages=…/messages/messages`, so `#{welcome}` is checked at build time;
  - `<proc>none</proc>` for `testCompile`, as the guide asks (#118).

  The build had no other annotation processors.
- **`build.gradle`**: swapped the Thymeleaf starter for the Tessera starter, added `mavenLocal()`, the `annotationProcessor` and the same compiler arguments. This is best effort: the guide says Gradle is untested, and I didn't run it.
- **Model**: added `@TemplateView` to `Owner`, `Pet`, `PetType`, `Visit`, `Vet` and `Specialty`.
- **Templates** (started from the `tessera-migrate` mechanical rewrite, then finished by hand):
  - **Layout**: `layout(Fragment template, String menu)`. Each page replaces itself with the layout and passes `~{::body}` marked `t:remove="tag"`. Thymeleaf used to insert a second `<body>` inside the container, which browsers drop anyway.
  - **Menu item**: moved to its own template, `fragments/menu.html`. It takes the link as a `String` built with `@{…}` at the call site, replacing `@{__${link}__}`, and takes the active `menu` as an explicit argument, because fragments can't see the caller's variables.
  - **`inputField` / `selectField`**: take a typed `Field` parameter in place of `*{__${name}__}` preprocessing. Select options use `item.name` for the value and text, which is what `PetType.toString()` / `PetTypeFormatter` produced before.
  - **Links**: string-built and preprocessed links became `@{/owners(page=i)}`, `@{{id}/edit(id=owner.id)}` and similar.
  - **Visit form**: the "Previous visits" loop variable is renamed from `visit` to `v`, so it no longer shadows the `visit` form object.
  - **Hidden pet `id`**: written as `pet.isNew() ? '' : '' + pet.id`. Tessera leaves out an attribute whose value is null, but Thymeleaf wrote `value=""`.
  - **Malformed markup**: the old templates' `<a …/></a>` and self-closed `<td/>`/`<span/>` are now written as normal start and end tags.
- **Tests**: added `PetClinicIntegrationTests.testViewsRenderWithTesseraAndEscapeUserInput`. It renders a page through the real view resolver and checks that `<script>` in user input comes out escaped. No existing test was changed.
- **`pages.txt`**: the request list used for record/compare. It covers:
  - home and the vet list on every page, plus an out-of-range page and a bad page parameter (400);
  - the `/vets` JSON endpoint with an HTML Accept header (406 error page);
  - `/oups` (500), a 404, a 400 on `/owners/abc` and a 500 on `/owners/999`;
  - owner search: all owners, several pages, several results, a single result (redirect) and not found;
  - the owner, pet and visit forms: empty, with each kind of validation and conversion error, after success, and in edit mode;
  - HTML and quotes in user data.

  `baseline/` and `.tessera/` are git-ignored.

## Differences found and why they are acceptable

- **Owner list, unsaved owner**: `OwnerControllerTests.testProcessFindFormSuccess` lists an `Owner` with a null id. Thymeleaf rendered `href="/owners/null"`. Tessera fails the render on a null link path variable. The template now leaves out the `href` for an owner that hasn't been saved: `owner.isNew() ? null : @{/owners/{id}(id=owner.id)}`. Real data never contains unsaved owners in this list, so no recorded page changes.
- **Validation message order** (owner form posted empty): the two telephone messages ("must not be empty" and "numeric value out of bounds") can appear in either order. This happens in both engines, because Bean Validation returns constraint violations from a `HashSet`. `compare` reported it once as `reordered` and once as `same`.

## Not converted

Nothing: every view now renders through Tessera. `readme.md` still says the upstream "canonical" Petclinic is based on Thymeleaf. That sentence is about the upstream project, not this checkout, so I left it.

## Where Tessera, its tools or its documentation fell short

1. **Null link path variables.** A null path variable fails the whole render with `IllegalArgumentException`, where Thymeleaf printed `null`. The guide mentions this under "Runtime errors", but the compiler gives no warning when a path variable's type is nullable (`Integer id`). Only an existing test that used an unsaved entity caught it. The guide also doesn't document the fix I used (a conditional between `null` and a link, `cond ? null : @{…}`). It compiles and works, but I had to find that out by trying it.
2. **`tessera-migrate` report contradicts the guide.** For `*{__${name}__}` the report says "inline one element per field (a typed alternative is tracked in #43)". The guide, section 4.8, documents exactly that typed alternative, `Field` fragment parameters, and it works. Following the report would have duplicated every form field.
3. **`tessera-migrate` left constructs unconverted without reporting them.** It left `t:href="@{'/owners?page=1'}"` and `@{'/vets.html?page=1'}` (a quoted literal link) unconverted, without reporting them. I rewrote them as `@{/owners(page=1)}` before compiling, so I didn't check whether the compiler accepts the quoted form. It also copied `<a …/></a>` and self-closed `<td …/>` unchanged and didn't report them; I rewrote those too.
4. **Loop variable shadowing.** The tool converted `th:if="${!visit['new']}" th:each="visit : …"`, where the loop variable shadows a parameter, without a warning. The guide doesn't say whether shadowing is allowed, so I renamed the variable.
5. **Null attribute values.** Tessera leaves out an attribute whose value is null, but Thymeleaf wrote `value=""`. This is in "Expected output differences", but the converter doesn't flag the places where it happens, such as `t:value="*{id}"`.
6. **Installation instructions.** The skill says Tessera is built from `github.com/chainguard-dev/harder`. That repository isn't available here; the artifacts were already in `~/.m2`, so nothing was blocked.
7. **Template-only changes need `mvn clean compile`** (the guide notes this). There is no hot reload, which slows down the convert-and-compare loop.
