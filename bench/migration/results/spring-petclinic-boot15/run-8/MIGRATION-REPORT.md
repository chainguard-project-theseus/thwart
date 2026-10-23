# Migration report: Thymeleaf → Tessera

## Result

| Check | Status |
|---|---|
| Every page is rendered by Tessera; Thymeleaf templates, dependency and configuration removed | Done |
| `mvn clean verify` on JDK 8 (the JDK the app targets, `java.version` 1.8, Boot 1.5.4) | Passes: 42 tests, 0 failures, 1 skipped (`CrashControllerTests`, already `@Ignore`d before the migration) |
| `tessera-migrate compare` against the recorded original, 39 requests in `pages.txt` | Exit 0: 38 `same`, 1 `reordered` (see below) |
| Security | No `SafeHtml.unchecked`/`TrustedUrl.unchecked`, no `t:utext`, no new `permitAll`, CSRF/escaping not touched, no tests disabled or changed |

The build fails on JDK 21 before and after the migration, for the same reason:
`cobertura-maven-plugin` 2.7 needs `tools.jar`, which only JDK 8 has. Not related to Tessera.

## What changed

### Build (`pom.xml`)
- Removed `spring-boot-starter-thymeleaf` (and its `thymeleaf-layout-dialect` exclusion) and the
  `thymeleaf.version` property. `mvn dependency:tree` has no Thymeleaf artifact left.
- Added `dev.tessera:tessera-spring-boot-starter-javax` (Boot 1.5 is `javax.servlet`).
- `maven-compiler-plugin` 3.8.1 (the Boot 1.5 parent pins 3.1, which has no
  `annotationProcessorPaths`), with `tessera-compiler` as the annotation processor and
  `-Atessera.templates`, `-Atessera.package=org.springframework.samples.petclinic.templates` and
  `-Atessera.messages` (so `#{welcome}` is checked at build time). Spring/Boot versions unchanged.

### Configuration
- `application.properties`: removed `spring.thymeleaf.mode=HTML`.
- New `system/ResourceUrlConfig`: declares the `ResourceUrlEncodingFilter` bean that Boot 1.5's
  Thymeleaf auto-configuration used to declare. As a plain filter bean Boot maps it for REQUEST
  dispatches only, as before. The Tessera starter would register it for REQUEST *and* ERROR, so
  error pages (404/400/500) would get versioned webjar URLs (`/webjars/jquery/2.2.4/...`) where
  the old app printed `/webjars/jquery/...`. Its `@ConditionalOnMissingBean` backs off when this
  bean exists. (Versioned links on error pages would arguably be better, but parity was the goal.)

### Model classes
- `@TemplateView` on `Owner`, `Pet`, `PetType`, `Visit`, `Vet`, `Vets`, `Specialty` (getters
  inherited from `BaseEntity`/`NamedEntity`/`Person` resolve through them).
- `Pet.getBirthDateValue()` and `Visit.getDateValue()`: read-only `LocalDate` views of the
  `java.util.Date` fields, because `Date` is not view-safe and the old templates used
  `#calendars.format(..., 'yyyy-MM-dd')`. The `Date` fields stay as they were. Changing them to
  `LocalDate` would have broken `ClinicServiceTests` (`pet.setBirthDate(new Date())`). It would also
  have changed behaviour: `SimpleDateFormat` accepts `2013-01-01x` and the JSR-310 parser does not.

### Templates (`src/main/resources/templates`, all rewritten in Tessera syntax)
- `fragments/layout.html`: `layout(Fragment template, String menu, String emptyMenuHref)`.
- `fragments/menuItem.html` (new): the menu entry fragment. Its link is passed as a link argument
  (`@{/owners/find}`) in place of `@{__${path}__}`, and `menu` is passed explicitly because
  fragments can't see the caller's variables.
- `fragments/inputField.html` / `selectField.html`: `Field` parameters in place of
  `*{__${name}__}` preprocessing.
- Pages: `welcome`, `error`, `owners/*`, `pets/*`, `vets/vetList`. Each page calls the layout with
  `~{::body}` and `t:remove="tag"`. `owner['new']` became `owner.isNew()`. `${pet.type}` became
  `pet.type?.name`. Stray `</td>` after self-closed `<td/>` were removed (browsers dropped them
  anyway).
- The visit form's "Previous Visits" loop had `th:each` and `th:if` on one element, with the
  loop variable shadowing the `visit` model attribute. The loop variable is now named `previous` so
  the condition reads the same thing it did in Thymeleaf (the loop element, which runs first).
- `error.html` reads Boot's `message` and `path` error attributes.

### Tests
- No existing test changed. Added `OwnerViewRenderingTests`, which renders `/owners/{id}` through
  the real Tessera resolver and checks that `<script>`, `'` and `&` in owner data come out escaped.

### Migration tooling (not part of the app)
- `pages.txt` (39 requests: every page, empty/invalid/valid states of the owner, pet and visit
  forms, find with 0/1/many results, HTML in user data, 400/404/500 error pages) and `baseline/`
  (the original app's recording). `.tessera/` holds the tool jar and is git-excluded.

## Differences left, and why they are right
- `16 reordered` (POST empty owner form): the two telephone messages ("may not be empty",
  "numeric value out of bounds") swap places. Bean Validation returns violations as a `HashSet`,
  so the order changes between runs in the old app too.
- Pages 35/37 (owner 1 after adding visits) sometimes come out `reordered`. Two visits share the
  date 2013-01-01. `Pet.getVisits()` sorts by date, and ties keep Hibernate's `PersistentSet`
  (identity-hash) order, which changes between runs in either app.

## Faithfully reproduced quirks of the old pages
- The old layout rendered the `menuItem` fragment *definition* as an extra menu entry, with null
  arguments: `<li><a href=""><span class="glyphicon  glyphicon-null">`. On error pages Thymeleaf
  resolved the empty link to the request path. The new layout writes that entry as static markup,
  with `emptyMenuHref` ('' on normal pages, `path` on the error page), so pages stay identical.
  This is a bug in the original app that I kept for parity. Deleting that `<li>` (and the
  parameter) is a safe follow-up.
- Owners list links are built as `@{/owners/{id}(id='' + owner.id)}`. Tessera fails the render on
  a null path variable, and the existing `OwnerControllerTests` mock owners without ids (Thymeleaf
  printed `/owners/` + null there). With real data this makes no difference.

## Nothing left unconverted
All 12 Thymeleaf templates were converted. `readme.md` still says the *upstream* canonical
Petclinic is "based on Spring Boot and Thymeleaf"; that sentence is about the upstream project,
so I left it.

## Where Tessera or its documentation fell short
1. **`ResourceUrlEncodingFilter` on ERROR dispatches.** The guide (§2 "Resource links") says the
   starter registers the filter "for REQUEST and ERROR dispatches, as Boot's Thymeleaf
   auto-configuration did", and "Don't declare the filter yourself". On Boot 1.5 that's wrong:
   Thymeleaf's auto-config declared a plain filter bean (REQUEST only), so error pages had
   unversioned webjar links, and following the guide changes every error page. The fix (declare
   the bean yourself) goes against the guide's advice, and the only way to find it was reading the
   starter's bytecode (`@ConditionalOnMissingBean(ResourceUrlEncodingFilter, name=resourceUrlEncodingFilter)`).
2. **`java.util.Date` support.** `Date` is not view-safe and there is no `dates`/`calendars`
   namespace, so every Java 8-era entity with `Date` fields needs extra getters or a custom
   function. Surprisingly, `t:field` on a `Date` property through a `Field` fragment parameter
   compiles and renders correctly (it goes through `BindingResult` and `@DateTimeFormat`).
   That contradicts §7 ("no `t:field` on fields whose type is not view-safe"), so either the
   limitation note or the check is wrong.
3. **Parameterized fragment rendered in place.** Thymeleaf renders a `th:fragment` with
   parameters in place, with null arguments, when its host is rendered. Tessera skips it (§4.8).
   Neither the guide nor the `tessera-migrate` report warns about this difference, though it
   changes output (here: an extra menu entry). Only `compare` caught it.
4. **Empty link on error pages.** Thymeleaf's `@{}` with an empty or null path resolved to the
   request URI on error dispatches. Not documented; the layout needs an extra parameter to match.
5. **Null path variables.** "A null link path variable fails the render" is documented, but
   `tessera-migrate` converts `@{/owners/__${owner.id}__}` without flagging that the old code
   tolerated null, and the failure shows up only at runtime (here, in mock-based controller tests).
6. **Migration report references an issue tracker that doesn't exist** ("a typed alternative is
   tracked in #43"). The `Field` parameter (§4.8) is the answer and already works. The report
   could say so.
7. **Running Boot 1.5 on JDK 21.** The skill's tool notes say the jar runs on JDK 8+. The guide
   mentions `--add-opens` for Spring 4.3 on JDK 16+ tests, but not that the *application itself*
   (Boot 1.5 / Spring 4.3) won't start on JDK 21 at all, so `record` needs JDK 8 for such apps.
8. `t:remove="tag"` on `<body>` vs Thymeleaf's nested `<body>`: both normalize the same in
   `compare`, but the guide could say so explicitly. The old output had a `<body>` inside the
   layout's `<body>`, and it isn't obvious that dropping it isn't a difference.
