# Migration report: JSP → Tessera

The Spring Framework Petclinic (plain Spring MVC 5.3, XML configuration, WAR on Jetty, Java 8)
rendered its pages with JSP, JSTL, Spring form tags and tag files. Tessera templates now render
every page. JSP, JSTL, the tag files and the JSP view resolvers are gone.

## What changed

### Build (`pom.xml`, `.mvn/wrapper/maven-wrapper.properties`, `.gitignore`)
- Added `dev.tessera:tessera-spring-boot-starter-javax` (javax servlet, Spring 5.3). It is used
  without Boot, through `TesseraConfiguration`.
- Added `tessera-compiler` as the annotation processor in `maven-compiler-plugin` (`<parameters>true`,
  `-Atessera.templates`, `-Atessera.package=org.springframework.samples.petclinic.templates`,
  `-Atessera.messages`). The build adds no other annotation processors.
- Added the `tessera-maven-plugin` `check` goal, so a change to a template or a message file
  recompiles the templates.
- Templates live in `src/main/templates`, outside `src/main/resources`. That directory is
  resource-filtered, which would rewrite `${...}`, and the templates aren't needed at runtime.
- Removed `taglibs-standard-jstlel` (JSTL) and `tomcat-jasper-el` (the JSP EL). Hibernate
  Validator still needs a Unified EL implementation for message interpolation, so
  `org.glassfish:jakarta.el:3.0.4` replaces it with the same `provided` scope (Jetty supplies EL at
  runtime, as before).
- Maven wrapper 3.5.4 → 3.9.9: `tessera-maven-plugin` needs Maven 3.6.3+.
- `.tessera/` (the migrate jar, `pages.txt` and the baseline) is git-ignored.
- The Java level stays at 1.8. `mvn -B package` passes on JDK 8 (the target) and on JDK 21:
  69 tests, 0 failures.

### Spring configuration
- `mvc-view-config.xml`: removed the `ContentNegotiatingViewResolver` with its default
  `JstlView` and the `InternalResourceViewResolver` (`/WEB-INF/jsp/*.jsp`). The file now declares
  `dev.tessera.spring.TesseraConfiguration` (the Tessera view resolver plus `redirect:`/`forward:`
  handling), keeps the `BeanNameViewResolver` with the JAXB `MarshallingView`, and adds
  `MissingViewResolver` (below).
- `mvc-core-config.xml`: `SimpleMappingExceptionResolver` is now `PetclinicExceptionResolver`,
  a subclass that also puts the exception message into the model as a `String`
  (`exceptionMessage`). The template can't read the `Exception` object, which isn't view-safe. The
  `exception` attribute is still there (CrashControllerTests checks for it).

### Java
- `@TemplateView` on the entities the pages read: `Owner`, `Pet`, `PetType`, `Specialty`, `Vet`,
  `Vets`, `Visit`. The templates read only their getters.
- `web/PetclinicExceptionResolver.java`: described above.
- `web/MissingViewResolver.java`: the last resolver in the chain. A view name with no template
  answers 404, as a missing JSP did before. `VisitController.showVisits` returns `visitList`, which
  has never had a view. Without this resolver that request would become a 500 "Could not resolve
  view".

### Templates (`src/main/templates`)
| Old | New |
|---|---|
| `tags/layout.tag`, `htmlHeader.tag`, `bodyHeader.tag`, `menu.tag`, `menuItem.tag`, `pivotal.tag`, `footer.tag` | `fragments/layout.html`: one `layout(String pageName, Fragment content, Fragment customScript)` fragment |
| `tags/inputField.tag`, `tags/selectField.tag` (`spring:bind` + form tags) | `fragments/fields.html`: `inputField(String, Field)` and `selectField(String, Field, Collection<PetType>)` |
| `tags/localDate.tag` | `temporals.format(date, pattern)` |
| `jsp/welcome.jsp`, `exception.jsp` | `welcome.html`, `exception.html` |
| `jsp/owners/*.jsp` | `owners/createOrUpdateOwnerForm.html`, `findOwners.html`, `ownerDetails.html`, `ownersList.html` |
| `jsp/pets/*.jsp` | `pets/createOrUpdatePetForm.html`, `createOrUpdateVisitForm.html` |
| `jsp/vets/vetList.jsp` | `vets/vetList.html` |

Points to note:
- `<form:form>` without an `action` posted back to the request URI. A Tessera POST form's action
  has to be absent or a literal link, so each form chooses between literal links. The owner form
  uses the `ownerId` path variable, not `owner.id`, because `id` is a disallowed field and is `null`
  on a re-rendered edit form. The visit form uses `visit.pet.owner.id`, because the GET mapping is
  `/owners/*/pets/{petId}/...` and has no `ownerId` variable.
- `spring:bind`'s `status.error`, `status.errorMessage` and "not empty `status.actualValue`" map to
  `fields.hasErrors(name)`, `fields.errors(name).first` and
  `!strings.isEmpty(fields.value(name))`.
- `<form:errors path="*"/>` maps to `<span id="owner.errors" t:errors="*">`, with the id written out
  as the guide recommends.
- Rows that sat directly in a `<table>` now have an explicit `<tbody>`. The pets table on the owner
  page writes its `<tbody>` only when the owner has pets, so the DOM matches the old page in both
  cases.
- Values that EL printed as empty when `null` (`${pet.owner.firstName} ...` on a re-rendered pet
  form, where `pet.owner` is `null`) are guarded explicitly.
- The two pet/visit form pages pass their datepicker `<script>` to the layout as a `Fragment`
  (`~{::page-script}`), where the old pages used the `customScript` JSP fragment attribute.

### Tests
- Every existing test is unchanged and passes. No test asserted on JSP details that changed: the
  `forwardedUrl("exception")` in `CrashControllerTests` comes from MockMvc's standalone
  `InternalResourceViewResolver`, not from the application's resolvers.
- Added `web/ViewRenderingTests`. The controller tests use `standaloneSetup` and never render a view.
  The new tests render through the real Tessera resolver: welcome page, owner page (escaping, date,
  links), pet form binding, validation errors and form action, vet list, exception page, and the 404
  for a view with no template.

## Page parity

I recorded the original app (`mvn jetty:run-war`, H2 seed data) with
`.tessera/pages.txt`: 39 requests. They cover every view, every form (empty, with validation
errors, with type-mismatch errors, with an unknown pet type, a duplicate name, and success with the
redirect that follows), single- and multiple-result searches, a not-found search, owners with and
without pets, HTML-special characters in submitted data, `/oups`, an unknown owner (the exception
page), a 404 and a missing view.

Result: all pages that the application itself renders are `same`, with the recorded status and
final URL. The remaining differences:

1. **`GET /owners/1/pets/9999/edit`** (500 both before and after). This is a pre-existing bug: the
   pet is `null`. The old JSP failed part-way through, and Jetty's error page printed the
   `JasperException` together with a snippet of the JSP source. The new app fails with a
   `NullPointerException` in the template, and Jetty's page names that exception instead. Status and
   URL match; the body is Jetty's stack-trace text, which can't be reproduced and shouldn't be.
2. **`GET /owners/*/pets/{petId}/visits`** (404 both before and after). The `visitList` view has never
   existed. Jetty's 404 page used to name `/WEB-INF/jsp/visitList.jsp` and the `jsp` servlet; it now
   says "Not Found" from the `dispatcher` servlet. Status and URL match.
3. **`POST /owners/new` with an empty telephone** is nondeterministic in both apps. The field fails
   both `@NotEmpty` and `@Digits`, and the page (old and new) shows only the first error. Hibernate
   Validator returns the violations as a `HashSet`, ordered by identity hash, so which message comes
   first changes from one JVM run to the next. Across my runs this page came out `same` in one and
   `differs` in another. That is not a migration difference.

Escaping: the old pages escaped every data value (`c:out`, form tags), so no output changed from
raw to escaped. The one raw EL output, `${exception.message}` on the error page, is now escaped
text.

## Security
- No `permitAll`. The application has no Spring Security, so there is no CSRF setting to change. No
  test is disabled or weakened. No `TrustedUrl.unchecked`, no `SafeHtml`, no `t:utext`.
- The JSP resolver is gone, so every HTML view is a compiled Tessera template. A view name with no
  template gets a 404; it can't reach another engine.
- The startup warning `Tessera: another template engine is still configured` doesn't appear (the
  log reports `Found 11 compiled Tessera templates`).

## What I couldn't convert
Nothing. Every page and fragment was converted. The two error-page bodies above are Jetty's
container pages, not application views.

## Where Tessera or its documentation fell short
- **Non-Boot, XML-configured Spring MVC is covered in one sentence** ("declare it as a bean in
  XML"). It doesn't say that `TesseraConfiguration` is a `@Configuration` class whose `@Bean`
  methods are processed only when annotation config is active in that context (here through
  `<context:component-scan>`).
- **Unknown view names.** The guide says Tessera's resolver "falls through" for names it doesn't
  know. In a non-Boot app that removes JSP, nothing is left to fall through to: once any
  `ViewResolver` bean exists, `DispatcherServlet` no longer registers its default
  `InternalResourceViewResolver`, so a missing view becomes a 500 instead of the old 404. I had to
  write a small last-resort resolver. A documented option, or a built-in "missing view → 404"
  resolver in `TesseraConfiguration`, would help.
- **Error pages without Boot.** The guide covers Boot's `error.html` attributes only. It has no
  recipe for `SimpleMappingExceptionResolver`, which puts the `Exception` itself in the model, and
  that type isn't view-safe. I needed a resolver subclass to expose the message.
- **JSP-specific notes leave out `spring:bind`.** Section 4.10 maps form tags, but not `status.error`,
  `status.errorMessage` (the first message only) or `status.actualValue`. I found the equivalents
  (`fields.hasErrors`, `fields.errors(...).first`, `fields.value`) by reading 4.6/4.7. It also
  doesn't say directly that `first` works on the list a function returns.
- **No JSP converter.** `tessera-migrate`'s mechanical rewrite and report exist only for Thymeleaf.
  JSP pages, and the list of values they read from outside the model, had to be converted and
  audited by hand.
- **The old-engine warning doesn't detect JSP** (documented, but it means the warning gives no
  safety net in exactly this migration).
- **The Maven wrapper must be 3.6.3+** for `tessera-maven-plugin`. This app shipped 3.5.4, so
  the migration has to touch the wrapper, which is outside the view layer.
- **JaCoCo.** With the jacoco agent, `prepare-package` warns "Execution data for class
  ...templates.* does not match" when stale `jacoco.exec` data exists. This is harmless, but section
  2 doesn't list JaCoCo among the tools that see generated classes.
