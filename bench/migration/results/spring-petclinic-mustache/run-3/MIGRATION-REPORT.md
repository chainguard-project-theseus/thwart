# Migration report: JMustache → Tessera

Every server-rendered page of this petclinic now goes through Tessera 0.1.0-SNAPSHOT. The pages are the
welcome page, owner search, owner list, owner details, the owner/pet/visit forms (including validation
errors), the vet list and the error page. The Mustache templates, `spring-boot-starter-mustache` and
`jmustache` are gone.

## How I checked "same HTML"

Before changing anything, I wrote a throwaway `@SpringBootTest(RANDOM_PORT)` capture test. It made
real HTTP requests against the H2 data and saved 41 page states, covering:

- every GET page, including pagination pages 1–3 and an owner search that finds nothing;
- every form, both blank and after a failed POST with validation errors (duplicate pet name, bad
  date, bad telephone);
- the error pages: `/oups`, a 404, an unknown owner and a 500 from a form;
- an owner, pet and visit whose data contains `<script>`, quotes, `&`, `` ` `` and `=`.

I ran the capture twice on the original code: once with the defaults and once with
`server.error.include-message=always`. Then I ran it again on the migrated code. A Python script
compared each pair as token streams, ignoring whitespace, attribute order and how entities are spelled.
The capture test is not part of the final tree.

Result: 35 of 41 pages are identical. The other 6 are the owner and vet list pages, and differ only in the
malformed `<span">` described below. Raw diffs show nothing beyond whitespace, attribute order and the
entity spelling noted below.

## What changed

**Build (`pom.xml`)**
- Removed `spring-boot-starter-mustache` and `com.samskivert:jmustache`.
- Added `dev.tessera:tessera-spring-boot-starter`. It excludes `org.springframework.boot:spring-boot-webmvc`,
  for the reason given under "Where the guide or Tessera fell short".
- Added `tessera-compiler` as an annotation processor, with `-Atessera.templates`, `-Atessera.package`
  and `-Atessera.messages` (all message keys are checked at build time) and `-parameters`.
  - Setting `annotationProcessorPaths` turns off javac's classpath discovery of processors. I therefore
    listed `spring-boot-configuration-processor` there too, so `spring-configuration-metadata.json` is
    still generated.
- `java.version` 17 → 21. Tessera's jars are Java 21 bytecode (class version 65).
- jacoco 0.8.7 → 0.8.11. 0.8.7 cannot read Java 21 class files, so `jacoco:report` would fail.
- The CI workflow and the readme now say JDK 21.

**Templates** (`src/main/resources/templates/**/*.html`, replacing the `.mustache` files)
- `fragments/layout.html`: `layout(List<Application.Menu> menus, Fragment body)`.
  - Every page is `<html t:replace="~{fragments/layout :: layout(menus, ~{::body})}">` with a
    `<body t:remove="tag">`. This replaces Mustache's `{{<fragments/layout}}{{$body}}` inheritance.
- `fragments/inputField.html` and `fragments/selectField.html` are typed fragments that take an
  `InputField` or `SelectField`. They replace the Mustache partials.
- The pages are `welcome`, `error`, `owners/*`, `pets/*` and `vets/vetList`. Each has typed `t:params`
  matching the model attributes the controllers and `LayoutAdvice` provide.
- Dynamic links now use `@{...}`. Their output is unchanged because the context path is empty.

**Java**
- `@TemplateView` on `Owner`, `Pet`, `PetType`, `Vet`, `Specialty`, `Visit`, `Application.Menu`,
  `InputField`, `SelectField`, `OwnerForm` and `PetForm`.
- `InputField` and `SelectField`: added public getters. Tessera reads getters, not public fields.
- `OwnerController.OwnerForm` and `PetController.PetForm` became public top-level classes
  (`owner/OwnerForm.java`, `owner/PetForm.java`):
  - the generated template classes live in another package and could not reach package-private nested
    classes;
  - `PetForm`'s `nameField()`, `birthDate()` and `type()` became `get…()` getters.
- The pagination model used `Map<String, Object>`, which is not view-safe. It is now a
  `record PageLink(int number, boolean current)` in `OwnerController` and `VetController`.
- `LayoutAdvice` (menus and `errors`) and the `Form`/`FormField` binding mechanism are unchanged.
  - I did not use `t:object`/`t:field`, because `t:field` always writes an `id` attribute that the old
    pages never had. That would have changed the HTML.

**Tests**
- No existing test was changed, deleted or disabled. All 42 still pass.
- Added `src/test/resources/META-INF/spring/org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc.imports`,
  so that `@WebMvcTest` slices load `TesseraAutoConfiguration` on Boot 3 and really render pages.
- Added `OwnerPagesRenderingTests` (2 tests). They check the rendered owner details and owner form
  HTML, escaping of `<script>` and other user data, and validation error markup.
- `mvn clean verify` passes: 44 tests, javaformat and nohttp checkstyle included.

**Security.** There is no `SafeHtml.unchecked` or `TrustedUrl.unchecked`, and no `t:utext`. No security
configuration changed (the app has no Spring Security). Escaping is Tessera's default everywhere.

## Deliberate output differences and things I could not convert

1. **`<span">` in the pagination bars** of `owners/ownersList` and `vets/vetList`. The original
   templates had a typo (`<span">`), which JMustache copied out as-is. Tessera rejects malformed markup
   (`invalid attribute name`), and the only way to emit it would be `SafeHtml.unchecked`, which is not
   allowed. Tessera now renders `<span>`. A browser used to see a span with a junk attribute named
   `"`; now it sees a plain span.
2. **Error pages with the default `server.error.include-message=never`.** Spring Boot then leaves
   `message` out of the error model. JMustache failed on the missing `{{message}}` partway through and
   sent a truncated page that ended at `<p>`. Tessera treats a missing parameter as `null`, so the full
   page renders with an empty `<p></p>`. With `include-message=always` (as in
   `CrashControllerIntegrationTests`), the old and new error pages are identical.
3. **Entity spelling.** JMustache also escapes `` ` `` as `&#x60;` and `=` as `&#x3D;`. Tessera
   escapes only `& < > " '`. Inside double-quoted attributes and text the two decode to the same
   characters, so the page content and the escaping are the same.
4. **Bugs I kept on purpose, so the HTML would match:**
   - The vet list template reads `listVets`, which `VetController` never puts in the model (it adds
     `vets`). The vets table body is therefore empty, as it was before. I kept a typed
     `List<Vet> listVets` parameter and the row markup, so the page works once a controller provides it.
   - The visit form template rendered `{{#form}}` inputs, but `VisitController` never adds a `form`.
     The old page had no date or description inputs; the new one has the same empty
     `form-group has-feedback` div.
   - `th:text="#{welcome}"`, `th:value="*{id}"` and `th:value="${pet.id}"` were Thymeleaf leftovers that
     Mustache printed literally. Tessera passes them through as static attributes, so they are unchanged.
5. The Maven `artifactId` is still `spring-petclinic-mustache`. It is the project's identity, not a
   dependency, and renaming it would change the build output name.

## Where the guide or Tessera fell short

- **Spring Boot version.** Nothing in the guide says which Boot version the starter needs. It is built
  against Spring Boot 4.1.1 / Spring Framework 7:
  - It pulls `org.springframework.boot:spring-boot-webmvc:4.1.1` and, through it,
    `spring-boot-servlet` and `spring-boot-http-converter` 4.1.1. Mixed with Boot 3.2, the application
    context fails to start (`EndpointCondition must be used on @Bean methods…` from Boot 4's
    `ServletMappingsAutoConfiguration`). I had to exclude `spring-boot-webmvc`. The starter's bytecode
    only uses core Spring Framework APIs, and those work on Spring 6.1.
  - It only registers `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureWebMvc.imports`,
    Boot 4's name for that file. On Boot 3 the guide's claim that "`@WebMvcTest` slices load Tessera's
    auto-configuration" is false. MockMvc tests silently fell back to `InternalResourceView` forwards
    with empty bodies, until I added the Boot 3 imports file in `src/test/resources`.
- **Java version.** The guide says "Java 21+", but not that the artifacts are class version 65. That
  forces projects on 17 to move to 21 and breaks older jacoco versions.
- **Annotation processor paths.** The guide's `annotationProcessorPaths` snippet does not mention that it
  disables processor discovery on the classpath, which silently drops `spring-boot-configuration-processor`.
- **Incremental compile.** Adding templates without a clean build left them out of the registry:
  the view resolver fell through and the requests 400'd. Section 17 warns about this, but there is no
  build-time or startup signal when a view name is missing.
- **Mustache.** The guide has no mapping table for Mustache (only Thymeleaf, FreeMarker, JSP and
  Velocity), and `tessera-migrate` only handles Thymeleaf. I converted everything by hand. Mustache
  section semantics (`{{#x}}` as both "if truthy" and "push context") map to
  `t:if="x != null"` / `t:each`, and template inheritance (`{{<parent}}{{$block}}`) maps to the
  `Fragment`-parameter layout.
- **Undocumented behaviour I had to find out by experiment:**
  - static attributes that look like expressions (`th:value="${pet.id}"`, `pattern="…{2}…"`) are left
    alone;
  - HTML comments, including IE conditional comments, are passed through;
  - a doctype before the root element is emitted, while the layout file's own doctype is not;
  - a bare static `selected` attribute stays bare (boolean `t:selected` would render
    `selected="selected"`, so I used two `t:if` branches);
  - `LocalDate` prints in ISO format;
  - `isNew()` can be called as a getter, but the property form `x.new` is risky because `new` is a
    rejected keyword (the guide only shows `x.isNew()` in the mapping table);
  - nested types work in `t:params` as `Application.Menu`.
- **Whitespace.** Attribute and whitespace order differ from the source template: Tessera re-serializes
  attributes onto one line and keeps the whitespace between `t:each` iterations. This is harmless, but
  the guide only mentions attributes moving within the tag.
