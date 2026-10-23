# Migration report: Thymeleaf → Tessera

Spring PetClinic (Spring Boot 4.1, `jakarta.servlet`, Java 17 target) has been moved from Thymeleaf
to Tessera 0.1.0-SNAPSHOT. Every page is now rendered by a compiled Tessera template. Thymeleaf is
gone: its starter, its test starter, the `spring.thymeleaf.mode` property and every `th:` template.

## Result against the acceptance criteria

| Criterion | Status |
|---|---|
| All pages rendered by Tessera, old engine removed | Done. `mvn dependency:tree` shows no `thymeleaf` artifact. No `th:` attribute and no Thymeleaf configuration is left. The 12 templates compile to `org.springframework.samples.petclinic.templates.*Template` classes. |
| `mvn clean verify` on the targeted JDK | Passes: build, spring-javaformat, nohttp checkstyle and all tests. 81 tests run, 0 failures. The 2 MySQL Testcontainers tests are skipped because there is no Docker here, the same as on the original app. No test was deleted, disabled or changed. One new test was added. |
| Page parity | `tessera-migrate compare` exits 0 on `pages.txt` (67 requests). Every page has its recorded status and final URL. 66 are `same` and 1 is `reordered` (explained below). |
| Security | No `SafeHtml.unchecked` or `TrustedUrl.unchecked` (no raw output at all), no `permitAll`, no CSRF or escaping change. The app has no Spring Security, before or after. |

### The one `reordered` page

`56 POST /owners/new` (German locale, all fields empty) lists the telephone field's two messages,
`darf nicht leer sein` and `Telefonnummer muss aus 10 Ziffern bestehen`, in the opposite order.
Bean Validation returns violations as an unordered set, so the order changes from run to run. The
same page was `same` on the first compare run. This is harmless.

## What changed

### Build (`pom.xml`)
- `spring-boot-starter-thymeleaf` was replaced by `dev.tessera:tessera-spring-boot-starter`
  (`${tessera.version}` = `0.1.0-SNAPSHOT`), and `spring-boot-starter-thymeleaf-test` was removed.
- `maven-compiler-plugin` now has `tessera-compiler` as its annotation processor, plus
  `<parameters>true</parameters>` and these options:
  - `-Atessera.templates` set to `src/main/resources/templates`
  - `-Atessera.package=org.springframework.samples.petclinic.templates`
  - `-Atessera.messages` set to `src/main/resources/messages/messages`, so every `#{key}` is checked
    at build time
- The build used no other annotation processors, so none had to be listed.

### Gradle build (`build.gradle`, secondary)
- The Thymeleaf starter was swapped for the Tessera starter, `annotationProcessor` and
  `testAnnotationProcessor` were added, and the same `-A` options are passed to every `JavaCompile`.
  The build now reads `mavenLocal()`, because Tessera is only in the local Maven repository.
  **Not verified.** `./gradlew compileJava` stops before compiling because the build asks for a
  JDK 17 toolchain and only JDKs 21 and 8 are installed. It can't download one either. This is an
  environment limit and is unrelated to the migration. Maven is the build this project's checks
  use.

### Configuration
- The `spring.thymeleaf.mode=HTML` line was removed from `application.properties`. Tessera's Boot
  auto-configuration needs no properties.

### Java
- `@TemplateView` was added to the entities the templates read: `Owner`, `Pet`, `Visit`, `PetType`,
  `Vet`, `Specialty`. Getters inherited from the `@MappedSuperclass` classes (`BaseEntity`,
  `NamedEntity`, `Person`) resolve through these.
- No controller changes were needed. Every value the old templates read was already a model
  attribute: the flash attributes `message` and `error`, `minVisitDate`, `types`, the pagination
  values, and Boot's `status` and `message` on the error page.

### Templates
- **Layout** (`fragments/layout.html`): `layout(Fragment template, String menu)`. Each page passes
  `~{::body}` with `t:remove="tag"` on its `<body>`. `menuItem` used to build its link with
  `@{__${link}__}` and read the caller's `menu` variable. It now takes the link as a `String`
  argument, written as a literal `@{/...}` at each call site, and takes `menu` as an explicit
  parameter.
- **Form fields** (`fragments/inputField.html`, `fragments/selectField.html`): the field name
  passed through `*{__${name}__}` became a typed `Field` parameter. `inputField` used to read the
  caller's `minVisitDate` implicitly; it now has an explicit `LocalDate min` parameter, which is
  `null` everywhere except the visit form. `selectField` takes `Collection<PetType> items`.
- **Links**: preprocessed links such as `@{__${owner.id}__/edit}` and `'/vets.html?page=__${i}__'`
  became `{placeholder}` links: page-relative `@{{id}/edit(id=owner.id)}` and query parameters
  `@{/vets.html(page=i)}`.
- **Pages**: `t:params` on every page declare the real types, for example
  `List<Owner> listOwners, int totalPages, int currentPage`. `visit['new']` and `pet['new']` became
  `isNew()`. `th:if="${message}"` became `message != null`. `#strings.listJoin` became
  `strings.join`. `#fields.hasAnyErrors()` and `allErrors()` became `fields.hasErrors('*')` and
  `fields.errors('*')`.
- **Invalid markup**: the old templates had markup the browser silently repaired: a stray `</a>`,
  self-closing `<td/>` and `<span/>`. It was written as well-formed HTML. Because `compare` checks
  the DOM, the pages still match.
- **Placeholder text**: the prototype text inside elements whose content is replaced anyway
  (`t:text` and `t:errors`) was dropped in the fragments and in a few pages. The existing
  `I18nPropertiesSyncTest` treats any literal text on a line without `th:text` or `#{` as
  hard-coded, so it would otherwise flag these lines. Removing the placeholder text was preferred
  over editing the test.
- **Null ids**: `ownersList.html` uses `owner.id == null ? @{/owners/} : @{/owners/{id}(...)}`.
  Thymeleaf turned a null id into `/owners/`, but a Tessera link fails the render on a null path
  variable. `OwnerControllerTests` renders a list with an unsaved `new Owner()` in it, which hit
  this case.
- **Hidden `id` input** on the pet form: `t:value="pet.id == null ? '' : '' + pet.id"`. Thymeleaf
  writes `value=""` for null, while Tessera omits a null attribute.

### Tests
- New: `OwnerViewRenderingTests` renders `owners/ownerDetails` and the owner form through the real
  Tessera view resolver in a `@WebMvcTest`. It checks that `<script>` and `<b>` in user data come
  out escaped and that relative links render. The existing controller tests check only view names.

### Migration artefacts
- `pages.txt` is the parity request list: 67 requests, in order, from freshly seeded H2 data. It
  covers every view, every form empty, with validation errors (blank, malformed, too long,
  duplicate, future and past dates, a bad date format) and after success; HTML-special characters
  in user data; pagination, including out-of-range redirects; single-result redirects; 400, 404
  and 500 error pages; and the de/es/ko/ru/ja/tr/pt/fa/hi locales.
- `baseline/` holds the recording of the original app. `.tessera/` holds the tool jar, logs and
  jars of the original and new apps. Both are in `.gitignore`.

## What could not be converted

Nothing. Every template was converted.

## Where Tessera, its tool or its documentation fell short

1. **The migrate report points to a tracker that doesn't exist and contradicts the guide.** For
   `*{__${name}__}` the report says "inline one element per field (a typed alternative is tracked
   in #43)". There is no issue tracker to look at. Guide §4.8 already documents the typed
   alternative, the `Field` fragment parameter, and it worked on the first try. The advice to
   inline every field is wrong for this case.
2. **The migrate tool keeps markup that Tessera's rules reject, without reporting it.** It kept
   the stray `</a></a>` (guide §6: "end tag ... does not match any open element" is a build error)
   and the self-closing `<td ... />`, `<span ... />`, and `t:errors` together with `t:text` on one
   element. None of these appear in its report. I rewrote them by hand, so I didn't confirm which
   of them would actually fail the build. It does report the string-concatenated links such as
   `@{'/owners?page=1'}`, but it leaves them unconverted even though they are literal.
3. **`t:field` with a null value: the guide disagrees with what happens.** Guide §4.7 says the
   value is "omitted when null". For a new `Owner` whose `firstName` is null, the output was
   `value=""`, as in Thymeleaf, presumably because Spring's display value for null is `""`. The
   result is right, but the guide made me expect a parity difference.
4. **Unspecified behaviour I had to test.** The guide doesn't say:
   - whether Thymeleaf's default case `t:case="*"` exists (it does);
   - whether a link `@{...}` can be a branch of `?:` (it can);
   - whether an `int` model value compares with `==`/`!=` against a `numbers.sequence` element
     (`Integer`) (it does);
   - whether a `@TemplateView` entity can be printed directly through its `toString()`. I avoided
     relying on that and printed `.name`.
5. **A null link path variable.** It is documented (§7). Thymeleaf's preprocessing quietly printed
   an empty segment where Tessera throws, so I added explicit null guards in the template expressions.
   Nothing in the build or the migrate tool warns that a path variable may be null, so this showed
   up only as a failing existing unit test.
6. **The skill text names a repository** (`github.com/chainguard-dev/harder`) to build Tessera
   from, but no such source was available. The local-repository fallback it describes worked.
7. **Gradle is undocumented** (§7 says so). The Gradle configuration here was written from the
   javac options in §2.
8. **Template-only changes need `mvn clean`.** As documented, Maven doesn't recompile when only a
   template changes, so every template edit needs `mvn clean compile`.
