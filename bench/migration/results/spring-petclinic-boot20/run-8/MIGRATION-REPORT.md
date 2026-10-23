# Migration report: Thymeleaf → Tessera

## Outcome

| Check | Result |
|---|---|
| Every page rendered by Tessera | Yes: all 10 page templates plus 4 shared fragment templates. Thymeleaf's dependency, templates and `spring.thymeleaf.mode` are gone. |
| `mvn clean verify` on the targeted JDK (8) | Passes. 43 tests run: the 41 existing tests plus 2 new ones. 1 is skipped, `CrashControllerTests`, which was already `@Ignore`d upstream and left untouched. No test was changed. |
| Page parity | `tessera-migrate compare` exits 0 on `pages.txt`: 42 requests, all `same` against the recording of the original app. A second ad-hoc list of 14 more requests (select-field errors, HTML/quote escaping, more 500 pages, an all-empty edit form) also exits 0. In that list one page is `reordered`: two validation messages on one field (`must not be empty`, `numeric value out of bounds`) come out in a different order. Spring collects them in a `HashSet`, so the order was not stable under Thymeleaf either. |
| Security | No `SafeHtml.unchecked`/`TrustedUrl.unchecked`, no `permitAll`, CSRF and escaping untouched, no tests disabled. A new test checks that `<script>` and HTML in user data come out escaped. |

## What changed

- **pom.xml**: replaced `spring-boot-starter-thymeleaf` with `dev.tessera:tessera-spring-boot-starter-javax`, because Boot 2.0.4 uses `javax.servlet`. Configured `maven-compiler-plugin` with the `tessera-compiler` annotation processor, with `-Atessera.templates`, `-Atessera.package=org.springframework.samples.petclinic.templates` and `-Atessera.messages` (message keys are checked at build time). Java 8 source and target are unchanged.
- **application.properties**: removed `spring.thymeleaf.mode=HTML`.
- **Entities** (`Owner`, `Pet`, `PetType`, `Visit`, `Vet`, `Vets`, `Specialty`): annotated `@TemplateView` so templates can read their getters.
- **Templates** (`src/main/resources/templates`), rewritten by hand, using `tessera-migrate`'s mechanical output as a reference:
  - `fragments/layout.html` is now `layout(Fragment template, String menu, String errorPath)`. Pages pass `~{::body}` with `<body t:remove="tag">`.
  - The `menuItem` fragment moved to `fragments/menuItem.html`. It takes the active `menu` as an argument, because fragments can't see the caller's variables. Each path is passed as an `@{...}` link instead of `@{__${path}__}`.
  - `fragments/inputField.html` and `fragments/selectField.html` take a `Field` parameter in place of `*{__${name}__}`. The select takes `List<PetType>` and prints `item.name`, which is what `PetType.toString()` and `PetTypeFormatter` produced.
  - Pages declare typed `t:params`. Old `${x['new']}` is `x.isNew()`, and `#fields.hasAnyErrors()`/`allErrors()` are `fields.hasErrors('*')`/`fields.errors('*')`.
  - Templates now handle nulls the way Thymeleaf did, because the existing `@WebMvcTest`s render pages with sparse mock data (a `Pet` with no id, type or owner; an `Owner` with no id):
    - `pet.type?.name`
    - `pet.id == null ? '' : '' + pet.id` for the hidden inputs
    - the owner link in `ownersList` falls back to `@{/owners/}` when the id is null. Thymeleaf's `@{/owners/__${owner.id}__}` gave `/owners/` there; Tessera fails the render on a null path variable.
- **`system/WebConfiguration.java`** (new): declares `ResourceUrlEncodingFilter` exactly as Boot 2.0.4's Thymeleaf auto-configuration did, for REQUEST dispatches only. See "Where Tessera fell short" below. The Tessera starter backs off for this bean.
- **Test added**: `owner/OwnerViewRenderingTests` renders through the real view resolver. It checks escaping of user data, and checks field binding and errors on a failed POST.
- `pages.txt` and `baseline/` hold the request list and the recording of the original app. `.tessera/` (the migrate jar) is git-ignored.

## Deliberate reproductions of old quirks (for parity)

- **Empty first menu entry.** Thymeleaf rendered the `menuItem` fragment definition in place with every parameter null. That produced an empty `<li>` whose link is `href=""`, with the class `glyphicon-null`. The layout keeps that entry as static markup, with a comment explaining it.
- **That entry's link on error pages.** On error pages the empty link came out as the failed request's path, because `response.encodeURL("")` resolves it during an error dispatch. Tessera rejects an empty `@{}`. So `error.html` passes Boot's `path` error attribute to the layout, and the layout prints it in that `href`.
- **Unversioned webjar links on error pages.** Boot 2.0's Thymeleaf auto-configuration registered `ResourceUrlEncodingFilter` for REQUEST only, so error pages had unversioned `/webjars/...` links. Hence `WebConfiguration` above.

## Not converted / known gaps

- **`/owners/{id}/pets/{unknown}/edit`** returned a 500 page whose message was Thymeleaf's own SpEL error text. The text was `Exception evaluating SpringEL expression: "pet['new']" ...`. Tessera fails differently, because the template reads a property of a null `pet`, so that error page's message text differs. Its status (500) and layout are the same. That message can't be reproduced without Thymeleaf, and it isn't in `pages.txt`.
- **Stray end tags.** The old templates had `<td ... /></td>` markup. Tessera rejects it, so the new templates use well-formed `<td ...></td>`. Browsers build the same DOM from both, and compare reports `same`.
- `readme.md` still says that upstream Petclinic is "based on Spring Boot and Thymeleaf". That describes the canonical upstream repository, so it was left as it is.

## Where Tessera or its documentation fell short

1. **Resource-URL filter on error pages (Boot 2.0).** The guide and the starter's Javadoc say the starter registers `ResourceUrlEncodingFilter` for REQUEST *and* ERROR dispatches "as Boot's Thymeleaf auto-configuration did (2.0+)". On Boot 2.0.4 that is wrong: Thymeleaf's registration covered REQUEST only. As a result, every error page differed (versioned vs unversioned `/webjars/` links). The guide also says "Don't declare the filter yourself", but declaring it was the only way to keep parity.
2. **`tessera-migrate` output for fragment files has no `xmlns:t` declaration.** It converted `th:*` to `t:*` in files that had no `xmlns:th`, such as `fragments/inputField.html`, but added no namespace. It also left `th:include="${template}"` unconverted inside a `<t:block>`.
3. **Contradictory advice on dynamic field paths.** `tessera-migrate` reports `*{__${name}__}` as unsupported, says to "inline one element per field", and points to an issue "#43" that can't be looked up. Guide section 4.8 already documents `Field` fragment parameters, which solve this cleanly.
4. **No empty link.** `@{}` is a compile error ("expected a link path"), and nothing in the guide covers a link that is empty or the current URL.
5. **Null-handling differences.** A null link path variable fails the render where Thymeleaf printed nothing. `a.b.c` on a null `b` fails where SpEL with Thymeleaf often tolerated it. The guide mentions both, but existing `@WebMvcTest`s with sparse mocks hit them right away. A null-safe link variant would help.
6. **Undocumented behaviour of a fragment nested in a layout.** The guide doesn't say whether a parameterized fragment nested inside another fragment (Thymeleaf's in-place `menuItem` inside `layout`) renders when the outer fragment is called. I sidestepped this by moving `menuItem` to its own file.
