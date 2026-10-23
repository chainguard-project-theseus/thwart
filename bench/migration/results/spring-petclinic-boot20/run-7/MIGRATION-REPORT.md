# Migration report: Thymeleaf → Tessera

## Result

| Check | Status |
|---|---|
| Every page rendered by Tessera; Thymeleaf templates, dependency and config removed | Done. `mvn dependency:tree` shows no `org.thymeleaf` artifact. |
| `mvn clean verify` on JDK 8 (the app targets `java.version` 1.8) | Passes: 43 tests, 0 failures, 1 skipped. The skip is `CrashControllerTests`, which was already `@Ignore`d before the migration. |
| Existing tests unchanged | No existing test was edited, disabled or deleted. One test class was added (see below). |
| `tessera-migrate compare` against a baseline of the original app | Exit 0, 50/50 pages `same`, on several runs, each from a fresh start. |
| Security | No `SafeHtml.unchecked`/`TrustedUrl.unchecked` and no `t:utext`. No security config was added or changed. Escaping is on and can't be turned off. |

## What changed

- **pom.xml**: replaced `spring-boot-starter-thymeleaf` with `dev.tessera:tessera-spring-boot-starter-javax`
  (Boot 2.0 means javax.servlet). Configured `maven-compiler-plugin` with the `tessera-compiler`
  annotation processor and `-Atessera.templates`, `-Atessera.package=org.springframework.samples.petclinic.templates`
  and `-Atessera.messages` (so every `#{...}` key is checked at build time), plus `<proc>none</proc>` for
  test compilation (guide §2, #118). `hibernate-validator` still comes from `spring-boot-starter-web`.
- **application.properties**: removed `spring.thymeleaf.mode=HTML`.
- **Model classes**: added `@TemplateView` to `Owner`, `Pet`, `PetType`, `Visit`, `Vet`, `Specialty` and `Vets`
  so templates can read their getters. Their behaviour is unchanged.
- **`system/WebConfiguration`** (new): registers `ResourceUrlEncodingFilter`. Thymeleaf's
  auto-configuration used to register it, and without it the webjar links lose their version segment
  (`/webjars/jquery/jquery.min.js` instead of `/webjars/jquery/2.2.4/jquery.min.js`). Tessera links go
  through `response.encodeURL`, so the filter works with Tessera unchanged.
- **Templates** (all 11 rewritten in place, plus 1 new):
  - `fragments/layout.html` is now `layout(Fragment template, String menu, String blankLink)`. Pages
    call it with `~{::body}` and mark `<body t:remove="tag">`, which replaces `th:include="${template}"`.
  - `fragments/menuItem.html` (new) is the menu-item fragment. It takes the link as a `String`
    built at the call site with `@{...}`, which replaces `@{__${path}__}`, and takes the active menu
    as an argument because fragments can't see the caller's variables.
  - `fragments/inputField.html` and `selectField.html` take a `Field` parameter instead of
    `*{__${name}__}` preprocessing. `select` takes `Collection<PetType>`, and the options print
    `item.name` (what `PetType.toString()` returned).
  - The pages declare typed `t:params` that match what the controllers put in the model:
    `Owner owner`, `Collection<Owner> selections`, `Pet pet, Collection<PetType> types`,
    `Visit visit, Pet pet`, `Vets vets`, and for `error.html` `String message, String path`
    (from Boot's error attributes).
  - `#fields.hasAnyErrors()`/`allErrors()` became `fields.hasErrors('*')`/`errors('*')`,
    `x['new']` became `x.isNew()`, and `#temporals.format` became `temporals.format`.
  - The Thymeleaf markup quirks (`<td th:text=... /></td>`) are now well-formed.
- **New test** `OwnerViewRenderingTests`: renders owner pages through the real Tessera view resolver
  in a `@WebMvcTest`. It checks that `<script>` and markup in owner data come out escaped, both on the
  details page and when a rejected form value is echoed back.
- **pages.txt**: the 50 requests used for record/compare. They cover every view, the empty and
  failed-validation states of every form plus the state after success, single, multiple and no
  search results, an owner with no pets, user data containing HTML, and the 400, 404 and 500 error
  pages. Run them in order against a freshly started app, because they create data.
- **readme.md**: the stack description says Tessera instead of Thymeleaf.

## Parity details worth knowing (kept on purpose)

- **The phantom first menu item.** The Thymeleaf layout declared the `menuItem` fragment inline, so
  Thymeleaf also rendered the declaration itself as a first, empty `<li>` (`glyphicon-null`, empty
  text). Its `@{}` link came out as `href=""` on normal pages and as the original request URI on
  error pages. To keep the pages identical, the layout writes that `<li>` as static markup, with its
  link taken from the `blankLink` argument: `''` from normal pages, Boot's `path` error attribute from
  `error.html`. It's a bug in the original UI and can be deleted when parity no longer matters.
- **`ownersList` link with a null id.** Tessera fails the render on a `null` link path variable, while
  Thymeleaf's `__${owner.id}__` printed empty. The existing test `testProcessFindFormSuccess` lists an
  `Owner` with no id, so the template now picks `@{/owners/}` when `owner.id` is null.
- **Validation message order.** The telephone field can carry two violations (`@NotEmpty` and
  `@Digits`) whose order comes from a Hibernate Validator `HashSet`, so it changes between runs in
  the original app too. `compare` reports this as `reordered` (pass) when it happens.
- **Hidden pet `id`**: `t:value="pet.id == null ? '' : '' + pet.id"` keeps Thymeleaf's `value=""`,
  because Tessera omits a null attribute.

## Not converted

Nothing. Every view the application renders is a Tessera template.

The templates are still copied into the jar as resources (`src/main/resources/templates`), as the
guide notes. Tessera never reads them at runtime. They do no harm, and I left the resource config
alone.

## Where Tessera or its documentation fell short

1. **The skill text names a source repo and an issue tracker** ("built with `mvn install` from
   `github.com/chainguard-dev/harder`", issues `#118` and `#43`) that aren't available here. Nothing
   could be checked against Tessera source.
2. **The converter output doesn't compile and drops the namespace.** `tessera-migrate`'s Thymeleaf
   rewrite left fragment files without `xmlns:t` and left one `th:include="${template}"` unchanged
   inside a converted `<t:block>`. Its report is useful, but I rewrote every template by hand.
3. **No replacement for `ResourceUrlEncodingFilter`.** The guide says the old engine can be removed
   but doesn't mention that Boot's Thymeleaf auto-configuration also registers this filter. Removing
   Thymeleaf silently changes every webjar URL. Only `compare` caught it.
4. **Null link path variables throw**, unlike Thymeleaf. This is documented (§4.5, §7), but it turns
   into a runtime 500 that the build can't catch. A `?`-style null-tolerant link form would help.
5. **Empty-string attributes.** Thymeleaf drops `class=""` from `th:class`. In Tessera you have to
   return `null`, while `''` keeps the attribute. Not documented as a difference.
6. **Nested fragment declarations.** The guide doesn't say whether a parameterized fragment declared
   inside another fragment renders when the outer one is called (Thymeleaf renders it). I moved
   `menuItem` to its own file to avoid depending on that.
7. **Docs gaps I worked around by trial**: whether a link `@{...}` may be a branch of `?:` (it may),
   and whether `t:field` writes `value=""` for a null bound value (it matches Thymeleaf here; the
   guide says "omitted when null").
8. **JDK note**: the guide's §2 example uses `<release>21</release>`. For this Java 8 app the Boot
   parent's `source`/`target` 1.8 were enough, and the build and app were run on JDK 8. The original
   app (Spring 5.0) doesn't start on JDK 21 without `--add-opens`, which the guide does mention.
