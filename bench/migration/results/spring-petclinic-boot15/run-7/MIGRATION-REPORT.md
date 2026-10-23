# Migration report: Thymeleaf → Tessera

## Result

| Check | Status |
|---|---|
| Every page is rendered by Tessera | Yes. All 12 Thymeleaf templates were rewritten as Tessera templates, plus one new fragment file (`fragments/menu.html`). |
| Old engine removed | Yes. `spring-boot-starter-thymeleaf` and `spring.thymeleaf.mode` are gone. `mvn dependency:tree` lists no Thymeleaf artifact, and no `th:` markup is left. |
| `mvn clean verify` on the target JDK (8) | Passes. There are 43 tests: the original 41 (the same one is skipped as before: `CrashControllerTests` has an `@Ignore` in the original) and 2 new ones. No existing test was changed. |
| Page parity | `tessera-migrate compare` exits 0 on my 45-request list: 44 `same`, 1 `reordered`. A second list of 15 edge cases has 14 `same`/`reordered` and 1 known difference, explained below. |
| Security | No `SafeHtml.unchecked`, `TrustedUrl.unchecked`, `permitAll`, CSRF change or escaping switch. The app has no Spring Security. A new test checks that user data comes out escaped. |

## What changed

**Build (`pom.xml`)**
- `spring-boot-starter-thymeleaf` is replaced by `dev.tessera:tessera-spring-boot-starter-javax:0.1.0-SNAPSHOT`. The app is on `javax.servlet`.
- `maven-compiler-plugin` is pinned to 3.8.1. Boot 1.5's default version is too old for `annotationProcessorPaths` and `<parameters>`. It now runs the `tessera-compiler` processor with `-Atessera.templates`, `-Atessera.package=org.springframework.samples.petclinic.templates` and `-Atessera.messages`, so `#{...}` keys are checked at build time. Test compilation uses `<proc>none</proc>`, as the guide says.
- The `thymeleaf.version` property is removed and `tessera.version` added.

**Configuration**
- `application.properties`: removed `spring.thymeleaf.mode=HTML`.
- New `system/WebConfig.java` registers `ResourceUrlEncodingFilter`. Spring Boot 1.5 registered this filter inside its *Thymeleaf* auto-configuration. Without it, webjar links lose their version (`/webjars/jquery/jquery.min.js` instead of `/webjars/jquery/2.2.4/jquery.min.js`). Tessera links go through `response.encodeURL`, so this filter restores the original URLs.

**Java model**
- `@TemplateView` added to `Owner`, `Pet`, `PetType`, `Visit`, `Vet`, `Vets` and `Specialty`.
- New `system/DateFunctions.java` adds the `dates` namespace (`dates.birthDate(pet)`, `dates.visitDate(visit)`). It replaces `#calendars.format(..., 'yyyy-MM-dd')`, because `java.util.Date` is not view-safe. The entity field types stay `Date`, since tests and JPA mapping depend on them.
- Controllers are unchanged.

**Templates** (all in `src/main/resources/templates`)
- `fragments/layout.html` is now a typed fragment, `layout(Fragment template, String menu, String selfHref)`. Pages pass their `<body>` with `t:remove="tag"`.
- The `menuItem` fragment moved to `fragments/menu.html`. It takes the link as a `String` link argument (`@{/owners/find}`) instead of preprocessing `@{__${path}__}`, and it takes `menu` explicitly, because fragments can't see the caller's variables.
- `fragments/inputField.html` and `fragments/selectField.html` take a `Field` parameter instead of `*{__${name}__}`.
- Pages declare their real `t:params` types. `owner['new']` became `owner.isNew()`. `#fields.hasAnyErrors()` / `allErrors()` became `fields.hasErrors('*')` / `fields.errors('*')`.
- `error.html` reads Boot's `message` and `path` error attributes.
- Parity quirks kept on purpose:
  - **Stray menu item.** The Thymeleaf `menuItem` fragment definition rendered in place, with all arguments null. That produced an empty first `<li>` in the navbar: `href=""`, class `glyphicon-null`. On error pages its href was the failed request's URI, because Thymeleaf passed the empty link through `response.encodeURL("")`. The layout writes this `<li>` statically. Its href comes from `selfHref`: `''` on normal pages and Boot's `path` attribute on `error.html`. It is an old bug, kept only for page parity, and can be deleted along with the parameter.
  - **Null `id` on new pets.** `<input type="hidden" name="id">` keeps `value=""` when the id is null: Thymeleaf wrote `value=""`, while Tessera would omit the attribute.
  - **Null owner ids.** The owners list falls back to `@{/owners/}` when an owner has no id. Tessera fails the render on a null link path variable. Thymeleaf's preprocessing tolerated it, and `OwnerControllerTests.testProcessFindFormSuccess` renders such an owner.

**Tests**
- Added `owner/OwnerViewRenderingTests`. It renders owner pages through the real resolvers (`@WebMvcTest`, so it goes through Tessera) and checks that `<script>` in stored data and in rejected form values comes out escaped.

## Page parity

The request lists are in `.tessera/` (git-excluded, along with the baselines and the `tessera-migrate` jar):
- **`pages.txt`** (45 requests) covers:
  - every page: home, vets, find owners, owner list, owner details, and the owner/pet/visit forms;
  - each form empty, with validation errors (empty fields, a non-numeric or too-long phone, an invalid date, a duplicate pet name) and after a successful post-redirect;
  - search with no match, one match (redirect) and several matches;
  - HTML in user data;
  - the error pages: 500 from `/oups` and from a missing owner, 404, and 400 for a non-numeric id;
  - `vets.json`.
- **`pages2.txt`** (15 more edge cases) covers an unknown pet type, a missing type, empty edits, `vets.xml`, 405 and missing entities.

Results, with apps started the same way from the seeded HSQLDB:
- `pages.txt`: exit 0. Page 13 (`POST /owners/new` with every field empty) is `reordered`. The telephone field's two messages ("is required", "numeric value out of bounds") swap order, because Hibernate Validator reports constraint violations in an unordered set. That is harmless.
- `pages2.txt`: one difference. `GET /owners/1/pets/999/edit` (a pet id that doesn't exist) returns 500 in both apps, with the same final URL. But the old error page showed Thymeleaf's own exception text (`Exception evaluating SpringEL expression: "pet['new']" ...`), while the new one shows Boot's `No message available`, because Tessera fails with a `NullPointerException` that has no message. I left it: reproducing another engine's internal error text would mean faking it in the controller. The app has no link that leads to this URL.

## What I couldn't convert, and why

Nothing was left unconverted. Two things are not as clean as they could be:
- The stray navbar `<li>` and the `selfHref` layout parameter exist only to reproduce an old Thymeleaf rendering bug byte-for-byte (see above).
- The app is still Spring Boot 1.5.4 (Spring 4.3). Tessera documents Spring 5.0+ / Boot 2.0+ as the minimum. I checked the javax starter's sources: it only uses APIs that exist in Spring 4.3 (`ObjectProvider`, `BeanPostProcessor`, `RequestContext`, Boot's `@ConditionalOn*`). Its `META-INF/spring.factories` entries for `EnableAutoConfiguration` and `AutoConfigureWebMvc` are also read by Boot 1.5. So I kept the Boot version rather than do a framework upgrade that wasn't asked for. Everything works on 1.5: running the app, the `@WebMvcTest` slices, form binding, messages and error pages. But it is outside Tessera's tested range.

The build targets Java 8 (`java.version=1.8`). It passes on JDK 8. On JDK 21 it fails *before and after* the migration in the same way: `cobertura-maven-plugin` needs `tools.jar`. That is unrelated to the migration.

## Where Tessera or its documentation fell short

1. **Supported versions.** The guide says Spring 5.0+ / Boot 2.0+ and that anything older is "at your own risk". It doesn't say what actually breaks on older versions. In practice nothing did on Boot 1.5 / Spring 4.3. A compatibility note, or a test on Boot 1.5, would have saved the source-reading.
2. **`ResourceUrlEncodingFilter` isn't mentioned.** On Boot 1.x the filter that versions webjar and resource links belongs to Thymeleaf's auto-configuration. Removing Thymeleaf silently changes every `<script src>` and `<link href>`. The guide's "Remove the old engine" step should warn about this and give the one-bean fix.
3. **`t:field` on `java.util.Date` fields.** Section 7 says there is "no `t:field` on fields whose type is not view-safe", and that `Date` is not view-safe. Yet `t:field="birthDate"` (a `Date` with `@DateTimeFormat`) compiled and rendered correctly, including echoing rejected values. Either the documentation is wrong or the check is missing. The limitation as written made me plan a workaround I didn't need.
4. **No link for the empty or current URL.** `@{}` is rejected ("expected a link path"), so there is no way to reproduce Thymeleaf's `response.encodeURL("")`. I passed the value in from the model instead.
5. **Null link path variables.** A null path variable fails the whole render with `IllegalArgumentException`. Thymeleaf-era code (and this app's tests) relied on it being tolerated. The guide mentions it, but the migration tool's report doesn't flag the `@{/owners/__${owner.id}__}` → `{id}` conversion as a place where null now throws.
6. **The migration tool and fragment definitions rendered in place.** Thymeleaf renders a parameterized `th:fragment` element in place when the page itself renders, with null arguments. Tessera skips it. That changes output (here, a navbar item), and neither the migrate report nor the guide's "Expected output differences from Thymeleaf" mentions it.
7. **Thymeleaf's empty `th:class`.** Thymeleaf drops the attribute when an expression yields `''`; Tessera writes `class=""` unless the expression yields `null`. Section 7 only covers the opposite case (`null` attribute omitted vs Thymeleaf's `value=""`).
8. **`tessera-migrate record` and query strings.** It can't send a request whose query contains characters such as `<` or `>` ("Illegal character in query"). It records status 0 instead of percent-encoding the URL, so pages.txt has to be encoded by hand. `pages.txt` documents no way to set headers either (for example `Accept-Language` to cover the German message bundle).
9. **Messages.** `-Atessera.messages` checks only one bundle (`messages.properties`). The `_de` / `_en` variants aren't checked. That's documented, but means the German page text is unverified at build time.
