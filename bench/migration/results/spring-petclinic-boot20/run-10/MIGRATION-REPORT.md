# Migration report: Thymeleaf → Tessera

Spring PetClinic (Spring Boot 2.0.4, `javax.servlet`, Java 8). Every view is now a compiled
Tessera template, and Thymeleaf is gone.

## Result

| Check | Status |
|---|---|
| `mvn clean verify` on JDK 8 (the target, `java.version` 1.8) | passes: 45 tests, 0 failures, 1 skipped (`CrashControllerTests` was already `@Ignore`d before the migration) |
| Existing tests | none changed, deleted or disabled |
| `tessera-migrate compare` (50 requests in `pages.txt`) | 49 `same`, 1 `differs` (see "Remaining difference" below) |
| Old engine removed | no Thymeleaf templates, dependency or configuration; `mvn dependency:list` shows no `thymeleaf`, `attoparser` or `unbescape`; no "another template engine is still configured" warning at startup ("Found 13 compiled Tessera templates") |
| Security | no `SafeHtml.unchecked` / `TrustedUrl.unchecked` (`target/tessera-review.txt` lists none), no `t:utext`, no `@TemplateFunction`s, no `permitAll`, and CSRF and escaping are untouched (the app has no Spring Security) |

## What changed

**Build (`pom.xml`)**
- `spring-boot-starter-thymeleaf` is replaced by `dev.tessera:tessera-spring-boot-starter-javax`
  (Boot 2.0 uses `javax.servlet`).
- `maven-compiler-plugin` runs the `tessera-compiler` annotation processor with
  `tessera.templates`, `tessera.package=org.springframework.samples.petclinic.templates` and
  `tessera.messages` (`messages/messages`, so `#{welcome}` is checked at build time).
- `tessera-maven-plugin` (`check` and `review` goals) is added, so a change to a template or a
  message file alone still triggers a recompile.
- The dependency diff shows that Thymeleaf brought in only `thymeleaf`, `thymeleaf-spring5`,
  `thymeleaf-extras-java8time`, `attoparser` and `unbescape`. The app uses none of them directly,
  so it needs no new declarations.

**Configuration**: removed `spring.thymeleaf.mode=HTML` from `application.properties`.

**Java**: added `@TemplateView` to the classes the templates read: `Owner`, `Pet`, `PetType`,
`Visit`, `Vet`, `Specialty` and `Vets`. I used the guide's "shortcut" (annotating the entities)
instead of view records per page. The controllers and their tests work with the entities in the
model (`model().attributeHasFieldErrors("owner", ...)`, form binding to `Owner` and `Pet`), so
this keeps the controllers and all their tests unchanged. Controller code is unchanged.

**Templates** (all 12 converted, plus one new file):
- `fragments/layout.html` is now a `layout(Fragment template, String menu, String selfLink)`
  fragment. Pages pass their `<body>` with `t:remove="tag"`.
- `fragments/menuItem.html` (new) holds the `menuItem` fragment. The Thymeleaf version built its
  link with `@{__${path}__}` preprocessing; callers now pass a link literal (`@{/owners/find}`)
  as a `String`, plus a `boolean active`.
- `fragments/inputField.html` and `fragments/selectField.html` take a `Field` parameter in place
  of the `*{__${name}__}` preprocessing. `fields.hasErrors(name)` and `t:errors="name"` work as
  before. Options print `item.name`, which is what `PetTypeFormatter` produced for Thymeleaf.
- Pages: `t:params` with real types, `owner['new']` becomes `owner.isNew()`, `#temporals` becomes
  `temporals`, and `#fields.allErrors()` becomes `fields.errors('*')`.
- `ownersList.html`: `@{/owners/__${owner.id}__}` became a link with a path variable. An owner
  with a `null` id (`OwnerControllerTests.testProcessFindFormSuccess` uses one) used to render
  `/owners/`. A `null` path variable is a render error in Tessera, so the template now chooses
  `@{/owners/}` in that case, which keeps the old output.
- The pet form's hidden `id` writes `value=""` for a new pet, as Thymeleaf did. Tessera would
  otherwise leave the attribute out.
- The old templates had self-closed non-void elements followed by a stray end tag
  (`<td th:text="..."/></td>`, also on `dd`, `a` and `span`). Tessera rejects these, so they are
  now normal elements. Browsers already ignored the stray end tag, so the parsed page is the same.

**Parity quirk kept on purpose.** Thymeleaf rendered the `menuItem` fragment definition in place
inside the layout, with no arguments: an extra `<li><a href="">…glyphicon-null…</a></li>` at the
top of the menu. On error pages that `href` came out as the failed request's path (for example
`/oups`), not `""`. The layout keeps this item so pages match. It is written statically, and
its `href` is the `selfLink` parameter: `''` from normal pages, and Boot's `path` error attribute
from `error.html`. A comment in `layout.html` explains why. A follow-up could delete this item,
which is a leftover bug in the original UI.

**Tests**: added `system/ViewRenderingTests`. It renders through the real view resolver
(`@SpringBootTest` + MockMvc), because the existing controller tests only check view names. It
checks the owner page, that `<script>` in user input comes out escaped, the validation-error
markup of the form fragments, and the layout's active menu item.

**Repository**: `pages.txt` (the request list used for record and compare) is committed-ready.
`.tessera/` and `baseline/` are added to `.gitignore`.

## Remaining difference (not converted to an identical page)

`GET /owners/1/pets/999/edit` (a pet id that doesn't exist). Both apps return **500** at the same
URL with the same error page. Only the message paragraph differs:

- Thymeleaf: `Exception evaluating SpringEL expression: "pet['new']" (template: "pets/createOrUpdatePetForm" - line 7, col 15)`
- Tessera: `No message available` (the template hits a `NullPointerException` on the `null` pet)

The old text was Thymeleaf's internal diagnostic for a crash in the template. I didn't
reproduce it: that would mean throwing a fake "SpringEL" exception text from the controller, and
it would expose template-engine internals to users for no benefit. The right fix is a 404 for an
unknown pet, but that changes controller behaviour and the status code, which is outside a view
migration.

## Where Tessera or its documentation fell short

1. **`tessera-migrate` and self-closed non-void elements.** The tool converted `<td th:text="x"/></td>`
   unchanged, and the compiler then failed with "end tag `</td>` does not match any open element"
   (13 errors). Thymeleaf (AttoParser) accepted this pattern, and it is common in the PetClinic
   lineage. The tool could rewrite it, since it already fixes other markup such as missing spaces
   between attributes.
2. **`th:include` inside a layout is only reported, not converted.** Because of that, the tool
   didn't add `t:remove="tag"` to the pages' `<body>` either, although its own documentation says
   it does this for layouts. I did both by hand.
3. **Fragment definitions that Thymeleaf rendered in place.** A `th:fragment` declared inside the
   layout's markup also rendered as a broken menu item in every page. Neither the tool report nor
   the guide mentions this Thymeleaf behaviour, and compare only showed it as a difference. I
   found the cause by reading the baseline HTML.
4. **Empty links.** Thymeleaf's `@{}` (from `@{__${null}__}`) rendered `""` on normal requests
   and the original request URI on error dispatches. The guide doesn't cover this, and Tessera has
   no equivalent: I needed an explicit layout parameter fed from Boot's `path` error attribute.
5. **Render-time failure messages differ.** When a template fails on `null` data, the error page
   shows the exception message. Thymeleaf's message named the template and expression, and
   Tessera's generated code throws a bare `NullPointerException`, so Boot shows
   "No message available". A message with the template file and line would be more useful and
   closer to the old behaviour.
6. **Runtime JDK.** The guide notes that Spring 5.0 needs `--add-opens` on JDK 16+. In practice the
   original app doesn't start on JDK 21 at all (CGLIB `InaccessibleObjectException`), so recording
   and comparing had to run on JDK 8. The guide could say this up front for Boot 2.0 apps.
7. **Minor: `tessera-migrate` and `Field` parameters.** The report says dynamic `*{__${name}__}`
   field paths are "not supported … a typed alternative is tracked in #43". The guide's section
   4.8 already documents `Field` parameters, which solved this directly, so the report's wording
   sends readers in the wrong direction.

## How to reproduce the check

```sh
export JAVA_HOME=/usr/lib/jvm/java-1.8-openjdk
mvn clean verify
java -jar target/spring-petclinic-2.0.0.BUILD-SNAPSHOT.jar &      # on JDK 8
java -jar .tessera/tessera-migrate.jar compare http://localhost:8080 pages.txt baseline/
```
