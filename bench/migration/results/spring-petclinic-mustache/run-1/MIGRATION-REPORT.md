# Migration report: Mustache (JMustache) → Tessera 0.1.0-SNAPSHOT

The application was a Spring Boot 3.2 / **JMustache** PetClinic. The Tessera guide covers
Thymeleaf, FreeMarker, JSP and Velocity, not Mustache, and `tessera-migrate` only reads
Thymeleaf. So all twelve templates were converted by hand.

## Result

- All 12 Mustache templates are replaced by Tessera templates (`src/main/resources/templates/**/*.html`).
  `spring-boot-starter-mustache` and `jmustache` are removed. No Mustache files, dependencies or
  configuration are left.
- `./mvnw package` passes: the 42 existing tests plus one new test (see below). No existing test
  was changed.
- **HTML parity check.** Before changing anything I captured 34 responses from the running Mustache
  application (`TestRestTemplate`, real server, H2 data). They cover every page, the pagination
  pages, the 404 and 500 error pages, every form with validation errors (empty fields, bad
  telephone, bad date, duplicate pet name), redirects, and data containing
  `<script>`, `<b>`, `&`, `"` and `'`. I captured the same 34 responses from the Tessera build and
  compared them as normalised token streams (whitespace, attribute order, entity spelling and
  `selected` versus `selected="selected"` ignored). Status codes and content types also match.
  **31 of 34 responses are identical.** The other three are the intended differences listed under
  "Differences in output" below.
- Security: there is no `SafeHtml.unchecked`/`TrustedUrl.unchecked`, no `t:utext`, no new
  `permitAll`, and escaping and CSRF are not turned off. The app has no Spring Security. All output
  is contextually escaped.

## What changed

### Build (`pom.xml`, CI)
- Added `dev.tessera:tessera-spring-boot-starter` and set up `tessera-compiler` as an annotation
  processor with `-Atessera.templates`, `-Atessera.package=org.springframework.samples.petclinic.templates`
  and `-Atessera.messages` (all absolute, `${project.basedir}`).
- **Excluded `org.springframework.boot:spring-boot-webmvc`** from the starter (see shortcomings).
- `spring-boot-configuration-processor` is now listed in `annotationProcessorPaths` too. Once
  `annotationProcessorPaths` is set, javac stops discovering processors on the classpath, so
  without this the `@ConfigurationProperties` metadata would no longer be generated.
- `java.version` 17 → **21**, because Tessera's jars are Java 21 class files (major 65). The GitHub
  workflow now uses JDK 21.
- JaCoCo 0.8.7 → 0.8.15. 0.8.7 cannot read Java 21 class files.
- The artifactId was renamed from `spring-petclinic-mustache` to `spring-petclinic-tessera`.

### Java
- `@TemplateView` added to the entities the templates read: `Owner`, `Pet`, `PetType`, `Visit`,
  `Vet`, `Specialty`, plus `Application.Menu`. Getters on the `@MappedSuperclass`es resolve
  through these.
- `InputField` / `SelectField`: their public fields became private fields with getters, and both
  classes are now `@TemplateView`. Mustache read the fields; Tessera only reads getters.
- `OwnerController.OwnerForm` and `PetController.PetForm` moved out to top-level public
  `@TemplateView` classes `OwnerForm` and `PetForm`. Their getters are now public, and `PetForm`'s
  `nameField()`-style accessors became `getNameField()` etc. The generated template classes live
  in another package and can only call public getters.
- The pagination model was `List<Map<String,Object>>`, which is not view-safe. It is now
  `List<PageLink>`, where `PageLink(int number, boolean current)` is a new record. This changed in
  `OwnerController` and `VetController`.
- `LayoutAdvice` (menus, `errors`, binding status) is unchanged. The templates take its model
  attributes as typed `t:params`.

### Templates
- `fragments/layout.html` is a `layout(Fragment content, List<Menu> menus)` fragment. Each page
  is `<html t:replace="~{fragments/layout :: layout(~{::body}, menus)}">` with
  `<body t:remove="tag">`. The `t:remove="tag"` is there so only the page's content goes into the
  layout's container, as Mustache's `{{$body}}` did.
- `fragments/inputField.html` / `selectField.html` are fragments typed with `InputField` /
  `SelectField`. I did not use `t:field`/`t:errors`: `t:field` adds an `id` attribute the old
  pages did not have, so the existing field objects (which carry the `BindStatus`) keep the markup
  identical.
- Mustache sections became `t:if`/`t:unless`/`t:each`. `{{#x.new}}` became `x.isNew()`, because
  `new` is reserved in Tessera expressions. Values Mustache would crash on when null (`owner.lastName`,
  `pet.type`) are null-safe (`?.`, `?:`).

### Tests
- New `src/test/resources/META-INF/spring/org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc.imports`
  makes `@WebMvcTest` slices load `TesseraAutoConfiguration` on Boot 3 (see shortcomings).
  Without it, slice tests never render a page.
- New `OwnerViewEscapingTests`: puts `<script>`, `<b>`, `"` and `&` in owner data and checks that
  they come out escaped.

## Differences in output

1. **Error pages (`/oups`, 404) now render completely.** The old `error.mustache` read
   `{{message}}`. Boot leaves `message` out of the model by default
   (`server.error.include-message=never`), so JMustache threw `No method or field with name 'message'`
   and the response was cut off right after `<p>`. Tessera treats the missing attribute as
   `null`, so the page renders as written: `<p></p>`, then the rest of the layout. With
   `include-message=always` the message is shown, as `CrashControllerIntegrationTests` checks.
   I chose the complete page over reproducing a crash.
2. **`<span">` → `<span>`** in the pagination of `owners/ownersList` and `vets/vetList`. The
   Mustache source had a stray quote: a browser parses it as an attribute literally named `"`.
   Tessera's parser rejects that attribute name, as it should, and no Tessera attribute can
   produce it. The pages look the same; only that bogus attribute is gone.

## Pre-existing bugs kept on purpose (same HTML as before)
- `vets/vetList`: the template loops over `listVets`, which no controller ever puts in the model.
  So the vets table has always been empty. The Tessera template keeps this as a
  `List<Vet> listVets` parameter, so the output is still an empty table. Adding
  `model.addAttribute("listVets", paginated.getContent())` in `VetController` would fill it.
- `pets/createOrUpdateVisitForm`: the template wraps the date/description inputs in `{{#form}}`,
  but `VisitController` never adds `form`. So the visit form has always rendered without input
  fields. The Tessera template renders the same empty `form-group`. Fixing this needs a
  `VisitForm` like `OwnerForm`.
- Static leftovers such as `th:text="#{welcome}"` and `th:value="${pet.id}"` were plain text
  under Mustache. They are still emitted verbatim.
- The owner form shows "Add Owner" when an edit fails validation, because `id` is a disallowed
  bind field, so the bound owner looks new. Same as before.

## Where the guide or Tessera fell short

1. **No Mustache coverage.** The guide and `tessera-migrate` only handle Thymeleaf, FreeMarker,
   JSP and Velocity. There is no mapping for Mustache sections, inverted sections, context-stack
   lookup, partials or `{{< parent}}{{$block}}` inheritance.
2. **Spring Boot version is not stated.** The starter is built against **Spring Boot 4.1.1**: its
   POM depends on `spring-boot-webmvc`, a Boot 4 module, and its `@WebMvcTest` import file uses
   the Boot 4 package `org.springframework.boot.webmvc.test.autoconfigure`. The guide never says
   this.
   - On Boot 3.x, `spring-boot-webmvc` 4.1.1 comes in transitively and has to be excluded by hand.
   - Section 12's promise that "`@WebMvcTest` slices load Tessera's auto-configuration" does not
     hold on Boot 3. Without the extra imports file, slice tests fall back to
     `InternalResourceView` and render nothing, yet they still pass silently.
   - The starter's classes only use APIs that also exist in Spring 6.1, so it works on Boot 3.2
     once these two workarounds are in place.
3. **Java 21 requirement.** Section 2 says "Java 21+". It does not mention the knock-on effects:
   older JaCoCo versions break, and adding `annotationProcessorPaths` silently turns off
   processors discovered on the classpath (such as `spring-boot-configuration-processor`).
4. **Error pages.** The guide does not cover how Boot's `BasicErrorController` and the whitelabel
   `error` view interact with Tessera. In practice `TesseraViewResolver` (order `LOWEST_PRECEDENCE - 20`)
   runs before Boot's `BeanNameViewResolver`, so `templates/error.html` wins. I had to find that
   by decompiling the starter.
5. **Layouts that insert only the body's content.** The guide's layout example inserts the page's
   whole `<body>` element into the layout. For a layout whose own `<body>` wraps a container, it
   gives no way to pass only the children. `t:remove="tag"` on the selected element works, but it
   is undocumented. So is whether `~{::t:block}` can be selected.
6. **Output formatting of values is unspecified.** Examples: how `LocalDate` and `Integer` print
   (it turned out to be `toString()`), whether `t:value=""` writes an empty attribute (it does),
   how booleans render (`selected="selected"`), and which entities are used for `'`. I confirmed
   each by comparing output.
7. **Nested class names in `t:params`/`t:import`** (`Application.Menu`) work but are not documented.
8. **`new` as a property name.** `x.new` (from `isNew()`) is only covered indirectly, by the
   Thymeleaf row `${x['new']}` → `x.isNew()`. A Mustache or JSP user writing `x.new` would hit
   the "object creation" diagnostic.
9. **Malformed source markup.** Mustache never parsed the HTML, so errors like `<span">` were
   invisible. Tessera rejects them, and the result cannot be reproduced byte-for-byte.
   (Expected, but worth a note in the guide's "output differences" section.)
