# Migration report: Thymeleaf → Tessera

## Result

| Check | Status |
|---|---|
| Every page rendered by Tessera | Yes. All 12 templates (9 views and the fragment files `layout`, `inputField`, `selectField`) are compiled Tessera templates; the starter logs `Found 12 compiled Tessera templates` and there is no "another template engine is still configured" warning. |
| Thymeleaf removed | `spring-boot-starter-thymeleaf` removed from `pom.xml` and `build.gradle`, `spring.thymeleaf.mode` removed from `application.properties`, no `th:` markup left. `mvn dependency:list` before/after: only `thymeleaf`, `thymeleaf-spring5`, `thymeleaf-extras-java8time`, `attoparser`, `unbescape` and the starter disappeared. The app used none of them directly, so nothing had to be re-declared. |
| `mvn clean verify` on JDK 8 (the app targets `java.version` 1.8) | BUILD SUCCESS, 42 tests, 0 failures. 1 skipped: `CrashControllerTests`, which was already `@Disabled` in the original. No test was changed, disabled or weakened. I added one test. |
| Page parity | `tessera-migrate compare` exits 0 on 54 requests: 53 `same`, 1 `reordered` (see below). |
| Security | No `SafeHtml.unchecked` / `TrustedUrl.unchecked` (`target/tessera-review.txt` lists 0 unchecked calls and 0 template functions), no new `permitAll`, CSRF and escaping untouched. The app has no Spring Security, before or after. |

## What changed

### Build (`pom.xml`)
- Replaced `spring-boot-starter-thymeleaf` with `dev.tessera:tessera-spring-boot-starter-javax` (Boot 2.7 / `javax.servlet`). I kept the app's Boot version.
- `maven-compiler-plugin`: added `tessera-compiler` as the annotation processor and set `-Atessera.templates`, `-Atessera.package=org.springframework.samples.petclinic.templates` and `-Atessera.messages`, so every `#{key}` is checked at build time. The app had no other annotation processors to list.
- Added `tessera-maven-plugin` (`check` + `review` goals), so a change to only a template or only a message file still triggers a recompile.
- nohttp checkstyle: excluded `**/.tessera/**`. That directory holds the migration tool, `pages.txt`, the recorded baseline and logs. It is also in `.gitignore`.
- `build.gradle`: the same change for Gradle (starter, `annotationProcessor`, compiler args, template/message inputs, plus `mavenLocal()` because Tessera is only in the local repository). **I did not run the Gradle build**: the task's build is Maven.

### Java
- `@TemplateView` on the entities the templates read: `Owner`, `Pet`, `PetType`, `Visit`, `Vet`, `Specialty`. This is the guide's "shortcut" approach. I chose it over per-page view records to keep the diff small and controller behaviour identical. Most controller tests assert on the entity model attributes, so moving to view records would have meant changing what they assert. One consequence: open-session-in-view stays on, as it was before.
- No controller changes were needed. Every value the templates read was already in the model. `error.html` gets Boot's `message` error attribute.
- New test `OwnerControllerTests.testShowOwnerEscapesUserData`: it renders `/owners/{id}` through the real view resolver and checks that `<script>` in user data comes out escaped.

### Templates (`src/main/resources/templates`)
I first ran the mechanical rewrite with `tessera-migrate`, then finished each template by hand:
- **Layout** (`fragments/layout.html`): `layout(Fragment template, String menu)`. The `menuItem` fragment used `@{__${link}__}` preprocessing and compared `active==menu` against the caller's variable, which a Tessera fragment can't see. It now takes `String link` (callers pass a literal `@{/...}` link) and `boolean active` (callers pass `menu == 'owners'`, ...). Each page passes its `<body>` with `t:remove="tag"`.
- **Form widgets** (`inputField`, `selectField`): `*{__${name}__}` became a typed `Field name` parameter (guide 4.8). `selectField` takes `Collection<PetType> items` and writes `item.name` for the option value and text, which is what Thymeleaf printed through `toString()`.
- **ownerDetails**: the page-relative links `@{__${owner.id}__/edit}` became `@{{id}/edit(id=owner.id)}` (and the same for the pet links), with the same output (`1/edit`). `${pet.type}` became `pet.type?.name`.
- **ownersList / vetList**: links built by string concatenation became link parameters (`@{/owners(page=i)}`, ...), with the same output.
  - The owner link can meet an unsaved owner with a `null` id (one existing test fixture has one). Thymeleaf rendered `/owners/` for it, but Tessera fails on a `null` path variable. I kept Thymeleaf's output with `owner.id == null ? @{/owners/} : @{/owners/{id}(id=owner.id)}`.
  - I dropped the stray `</a>` after a self-closed `<a/>` and the self-closed `<td/>`/`<span/>`. Browsers ignore them, so the DOM is unchanged.
- **Pet and visit forms**:
  - `pet['new']` became `pet.isNew()`.
  - The hidden `id`/`petId` inputs use `x == null ? '' : '' + x` so they keep `value=""` as Thymeleaf wrote it.
  - In the visit form's "previous visits" loop, the loop variable (`visit`) shadowed the form object, so I renamed it `v`. The `t:if="!v.isNew()"` on the row keeps the original filtering: the new, unsaved visit is not listed.
- **error.html**: `t:params="String message"`.

## Parity check

`.tessera/pages.txt` has 54 requests, run in order from fresh H2 data. They cover:
- every view: welcome, find owners, owner list with pages 1, 2 and out of range, search by last name with several matches, one match (redirect) and no match (`notFound` error), owner details, owner new/edit, pet new/edit, visit new, vet list pages 1–3;
- the forms empty, with validation errors (empty fields, non-numeric or too-long telephone, duplicate pet name, unparseable dates, missing type, empty description) and after success (redirects);
- HTML/script in user input (escaped the same way as before);
- error pages: 500 from `/oups`, 404, a 500 for a missing owner, a 400 for a bad id, a 500 for a missing pet and a 500 for a missing owner on the pet form.

I recorded it from the original jar and compared it against the migrated jar, both started the same way (`java -jar … --server.port=8080`). The result is 53 `same` and 1 `reordered`: request 11, the empty owner form, where the two telephone messages ("must not be empty" and "numeric value out of bounds…") are listed in the other order. Bean Validation reports violations from a `HashSet`, so their order is not deterministic in the original app either. This is harmless.

## What I could not convert

Nothing: every template and every page state I listed renders through Tessera with the same output.

## Where Tessera or its documentation fell short

1. **The `tessera-migrate` report contradicts the guide about dynamic form-field paths.** It says `*{__${name}__}` "is not supported (preprocessing); inline one element per field (a typed alternative is tracked in #43)". Guide section 4.8 documents exactly that typed alternative: `Field` fragment parameters, which work and which I used. Someone who follows the report would needlessly inline every field of petclinic's three forms.
2. **The converted fragment-only files have no `xmlns:t` declaration.** The tool added `xmlns:t="https://tessera.dev"` to the page templates, but not to `fragments/inputField.html`, `layout.html` or `selectField.html`. I added it by hand. Neither the tool nor the guide says whether fragment files need it.
3. **The tool converts links inconsistently.** It rewrote `@{'/owners?page=' + ${i}}` to `@{/owners(page=i)}`, but left the fully literal `@{'/owners?page=1'}` unchanged and reported it as "link with a non-literal path". The fix is simply `@{/owners(page=1)}`.
4. **The `__${link}__` report gives no recipe.** For the menu item it only says "inline the dynamic part". The idiomatic fix (pass the link as a `String` argument built with `@{...}`, which guide 4.8 says is allowed) has to be pieced together from the guide.
5. **Null path variables are under-documented.** The guide says to "guard with `t:if`", but that changes the output where Thymeleaf wrote `/owners/`. It doesn't mention that a conditional between two literal links in `t:href` (`c ? @{/a/} : @{/a/{id}(id=x)}`) is accepted, which is the only way I found to keep the exact old output. I found that by trying it.
6. **The docs point to resources that aren't there.** The skill and the guide refer to issue numbers (#43, #428, #562, #602), `docs/security-model.md`, `github.com/wolfi-dev/tessera` and `GuideExamplesTest`, none of which are available. The section 2 snippet uses `${tessera.messages}` without saying that the property has to be defined. It's obvious, but the snippet doesn't build as pasted.
7. **Gradle can't resolve Tessera without a repository setting.** Tessera is only in the local Maven repository, so the Gradle wiring in guide section 2 doesn't resolve without `mavenLocal()`, and the guide doesn't mention this.
8. **The tooling has no way to start and stop the app.** `record`/`compare` take a URL only, so starting and stopping the old and new apps on the same port is left to the user. I lost one compare run to a stale process on port 8080. It showed up as many `differs` from mutated data, not as a clear "wrong server" error.
