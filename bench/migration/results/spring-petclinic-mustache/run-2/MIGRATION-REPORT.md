# Migration report: Mustache (JMustache) → Tessera 0.1.0-SNAPSHOT

## Summary

This app's views were **Mustache** templates (JMustache via `spring-boot-starter-mustache`), not
Thymeleaf. Tessera now renders every page: the 7 pages, the error page and the layout and form-field
fragments. The Mustache templates, both Mustache dependencies and every Mustache reference are gone.
`mvn clean verify` passes: 44 tests, which are the 42 existing tests, all unchanged, plus 2 new
rendering tests.

The migrator (`tessera-migrate`) only handles Thymeleaf, so I did not use it. I converted every
template by hand.

## How "same HTML" was checked

1. I built the original application and ran it on the default H2 data. I captured 27 requests with
   `curl`: every GET page, every form with validation errors (owner create/edit, pet create, duplicate
   pet name, a bad date, visit), both pages of each paginated list, `/oups`, a 404, and an owner whose
   fields contain `<script>`, quotes, `&` and `'`, saved and then shown.
2. I captured the same requests from the Tessera build.
3. A script tokenised both outputs with Python's `html.parser`, collapsed whitespace and sorted the
   attributes, then diffed them.

Result: 22 of 27 responses are identical. The other 5 differ only by the item in "Remaining
differences" below. I also ran the comparison with `server.error.include-message=always`, because the
old error page was truncated by default (see below).

## What changed

### Build (`pom.xml`)
- Removed `com.samskivert:jmustache` and `spring-boot-starter-mustache`.
- Added `dev.tessera:tessera-spring-boot-starter`. It excludes `org.springframework.boot:spring-boot-webmvc`
  (see "Where Tessera fell short", item 1).
- Configured `maven-compiler-plugin`:
  - the `tessera-compiler` annotation processor, with `-Atessera.templates`, `-Atessera.package=org.springframework.samples.petclinic.templates`
    and `-Atessera.messages` (so message keys are checked at build time);
  - `-parameters`;
  - `spring-boot-configuration-processor`, listed explicitly. Setting `annotationProcessorPaths` turns
    off processor discovery, and this processor was previously picked up from the classpath.
- `java.version` 17 → 21. Tessera's jars are Java 21 class files (major version 65).
- `jacoco.version` 0.8.7 → 0.8.11. 0.8.7 cannot read Java 21 class files, so `verify` failed in
  `jacoco:report`.
- CI workflow and readme: JDK 17 → 21.

### Templates (`src/main/resources/templates/**/*.html`, replacing the `*.mustache` files)
- `fragments/layout.html`: the layout is a `t:fragment="layout(Fragment body, List<Application.Menu> menus)"`.
  Each page replaces its `<html>` with it, passing `~{::section}`. The page's `<section>` has
  `t:remove="tag"`, so only its contents are output, the same as Mustache's `{{$body}}` block.
- `fragments/inputField.html` and `fragments/selectField.html` are typed fragments taking an `InputField`
  or `SelectField`.
- `welcome`, `error`, `owners/{findOwners,ownersList,ownerDetails,createOrUpdateOwnerForm}`,
  `pets/{createOrUpdatePetForm,createOrUpdateVisitForm}` and `vets/vetList` each declare typed
  `t:params`, matching what the controllers and `LayoutAdvice` put in the model.
- `{{#x}}…{{/x}}` sections became `t:if`/`t:unless`/`t:each`. `{{x}}` became `t:text`, `[[${…}]]` or
  `t:<attr>`. Concatenated hrefs became `@{…}` links (for example `@{/owners(page=…)}` and the
  page-relative `@{{id}/edit(id=owner.id)}`). With no context path these produce the same strings.
- `<option … selected>`: a `t:selected` boolean attribute renders `selected="selected"`. To keep the
  exact old markup, each option is written twice, once with a static `selected` and once without,
  chosen with `t:if`/`t:unless`.
- Mustache comment wrappers (`{{! <html>… }}`) and the unused `{{$menu}}` blocks were dropped. They
  produced no output.

### Java
- `@TemplateView` was added to everything templates read:
  - `Owner`, `Pet`, `PetType`, `Visit`, `Vet`, `Specialty` (getters inherited from the
    `@MappedSuperclass`es resolve as the guide says);
  - `Application.Menu`, `InputField`, `SelectField`.
- `InputField`/`SelectField`: Tessera cannot read public fields, so I added getters (`getLabel()`,
  `isValid()`, `getErrors()`, …).
- `OwnerController.OwnerForm` and `PetController.PetForm` were package-private nested classes. They
  are now public top-level `@TemplateView` classes, `owner/OwnerForm.java` and `owner/PetForm.java`, so
  the generated template classes (in another package) can use them.
  - `OwnerForm`'s getters became public.
  - `PetForm`'s `nameField()`/`birthDate()`/`type()` became `getNameField()`/`getBirthDate()`/`getType()`.
  - Behaviour is unchanged. `LayoutAdvice` still fills them from the `BindingResult`, and `Form` /
    `FormField` / `LayoutAdvice` are unchanged.
- Pagination: the `pages` model entry was a `List<Map<String,Object>>`, which is not view-safe. It is
  now a `List<PageLink>`, using the new `record PageLink(int number, boolean current)`, in
  `OwnerController` and `VetController`.

### Tests
- No existing test was changed, deleted or disabled.
- Added `src/test/resources/META-INF/spring/org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc.imports`,
  so that `@WebMvcTest` slices load `TesseraAutoConfiguration` on Spring Boot 3 (see "Where Tessera
  fell short", item 2). Without it the slices would not resolve any view.
- Added `owner/OwnerViewRenderingTests`. It is a MockMvc test that checks the slices really render the
  Tessera pages, and that `<script>`, quotes and `&` in user data, and in rejected form values, come
  out escaped.

### Security
- The app has no Spring Security, so there was no CSRF or `permitAll` configuration to keep or weaken.
  Tessera's automatic CSRF field is simply inactive.
- There is no `SafeHtml.unchecked`, `TrustedUrl.unchecked` or `t:utext` anywhere. All output is
  escaped by context, the same way Mustache's `{{…}}` escaped it. The XSS captures are identical
  between old and new.

## Remaining differences and things I could not convert

1. **Malformed `<span">` in pagination (`owners/ownersList`, `vets/vetList`).** The Mustache
   templates contained the typo `<span">`, which Mustache output verbatim. Tessera rejects it at build
   time (`malformed markup: Incomplete structure`), and no template construct can emit it. The new
   output is `<span>`. A browser parses the old markup as a `<span>` with a junk attribute named `"`,
   so the DOM differs only by that attribute. This is the only difference in the 27 captured pages.
2. **Error page with default settings.** With the default `server.error.include-message=never`,
   Spring Boot leaves `message` out of the model. JMustache then threw
   `No method or field with name 'message'` part-way through rendering, so the old error page and 404
   page were **truncated after `<p>`** and the error was logged. Tessera treats the missing parameter
   as `null` and renders the complete page with an empty `<p></p>`. With `include-message=always`,
   which `CrashControllerIntegrationTests` uses, old and new are identical. I did not reproduce the
   truncation.
3. **Pre-existing template bugs, ported faithfully (same HTML for the same data):**
   - `vets/vetList` loops over `listVets`, but `VetController` only puts `vets` (a `Vets` object) in
     the model, so the vet table body has always been empty. The Tessera template keeps the loop over
     a `List<Vet> listVets` parameter, which is `null` and so gives zero rows. Adding
     `model.addAttribute("listVets", paginated.getContent())` would fix it, but that would change the
     page, so I left it.
   - `pets/createOrUpdateVisitForm` wrapped its Date/Description inputs in `{{#form}}`, but
     `VisitController` never adds a `form`, so the visit form has always rendered with no input
     fields. There is no Java form model to type a parameter with, so the Tessera template keeps the
     empty `<div class="form-group has-feedback">` and omits the dead block.
   - Literal leftover Thymeleaf attributes (`th:text="#{welcome}"`, `th:value="*{id}"`,
     `th:value="${pet.id}"`) were in the old output, and they are kept as static attributes.
4. **Whitespace** differs in places (blank lines where Mustache sections were, and indentation),
   which the requirement allows.

## Where the guide or Tessera fell short

1. **The starter targets Spring Boot 4.1.1, and the guide does not say so.** The guide never states
   which Spring Boot (or Spring Framework) version the starter needs. I found out from the POMs in
   `~/.m2`:
   - `tessera-parent` sets `spring-boot.version` 4.1.1;
   - `tessera-spring-boot-starter` depends on `spring-boot-webmvc`, a Boot 4-only module.

   This app is on Boot 3.2.0. Upgrading to Boot 4 would have meant rewriting unrelated test code
   (moved `@WebMvcTest` packages, removed `@MockBean`, Jackson 3), which the task forbids. I checked
   with `javap` that the starter's classes only reference APIs that also exist in Spring Framework
   6.1 and Boot 3.2. I then excluded `spring-boot-webmvc` and kept Boot 3.2. This works, but the
   combination is not supported or documented.
2. **`@WebMvcTest` integration is Boot 4-only.** The guide says "`@WebMvcTest` slices load Tessera's
   auto-configuration". The starter only registers
   `META-INF/spring/org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureWebMvc.imports`,
   which is the Boot 4 name. On Boot 3 the file is
   `org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc.imports`, so I had to
   add it in test resources.
3. **Java 21 is required, with knock-on effects.** The guide does say "Java 21+", but the jars being
   Java 21 bytecode forces the application's `release` to 21 as well. That in turn broke the old
   JaCoCo plugin. The guide does not mention either effect.
4. **No guidance for Mustache.** Section 13 covers Thymeleaf, FreeMarker, JSP and Velocity only.
   Mustache-specific points had to be worked out:
   - sections over objects or lists versus booleans;
   - inverted sections;
   - context-stack lookup (for example `{{owner.id}}` inside `{{#pets}}`);
   - parent templates with `{{$block}}`;
   - Spring Boot's Mustache environment fallback for missing keys.
5. **Malformed markup cannot be preserved.** Tessera's strict parser rejects markup that browsers
   accept (`<span">`). The guide's promise of the same output ("expect attribute order and whitespace
   differences only") therefore cannot hold for such templates. Its diagnostic catalogue lists the
   message as `malformed markup: <parser message>`, but the parser message
   ("Incomplete structure") does not point at the stray quote.
6. **No template comment syntax.** There is no Tessera comment that is dropped from the output, the
   equivalent of Mustache `{{! }}` or Thymeleaf `<!--/* */-->`. HTML comments are passed through, so
   explanatory notes inside templates would change the output.
7. **Boolean attributes always render as `name="name"`.** There is no way to get the bare form
   (`selected`), so I duplicated the `<option>` with `t:if`/`t:unless` to keep the output identical.
8. **Undocumented behaviours I had to test empirically:**
   - whether the doctype's case is preserved (it is);
   - whether conditional comments survive (they do);
   - whether unknown `th:*` attributes pass through untouched (they do);
   - whether `t:remove="tag"` applies to an element passed as `~{::section}` (it does);
   - whether nested types such as `Application.Menu` work in `t:params` (they do);
   - whether `TesseraViewResolver` outranks Boot's whitelabel `error` view bean (it does: order
     `MAX-20` against `MAX-10`).

   Boot's `ErrorTemplateMissingCondition` does not know about Tessera, so the whitelabel view bean
   is still registered, just never chosen. The guide says nothing about error pages beyond "error-page
   attributes are ordinary parameters".
9. **Incremental compile.** As the guide warns, a template-only edit followed by `mvn compile` says
   "Nothing to compile", so `clean` is required after template edits.
10. **Not published anywhere.** The Tessera artifacts exist only in the local `~/.m2`. The GitHub
    Actions workflow, or any other machine, cannot resolve them until they are published.
