# Migration report: JSP/JSTL → Tessera

## Result

- Every page is now rendered by a Tessera template compiled at build time into
  `org.springframework.samples.petclinic.templates`. The JSPs, tag files, JSP/JSTL dependencies and
  the JSP view resolver configuration have been removed.
- The build is `mvn clean install` on JDK 8 (`java.version` 1.8, Travis `oraclejdk8`). It passes
  with all 62 existing tests, unchanged, plus 6 new tests in `ViewRenderingTests`.
- Page parity: I recorded the original app (started with `mvn tomcat7:run-war`, in-memory HSQLDB)
  with `tessera-migrate record` against a 54-request `pages.txt`, then ran `compare` against the
  migrated app started the same way. The list covers:
  - every view and form state: empty forms, validation errors, type-mismatch errors, duplicate
    pet name, successful posts with redirects;
  - the `.html` URL variants the pages link to;
  - list, single-result and no-result owner searches;
  - the error page, both `/oups` and a missing owner;
  - 400 and 404 responses;
  - HTML special characters in user data.

  52 of the 54 pages always come out `same`. One of four full runs exited 0. The other runs differ
  only on the two "empty owner form" posts, and the original app does the same thing (see below).

## What changed

Build (`pom.xml`, `.mvn/wrapper/maven-wrapper.properties`):
- `java.version` 1.7 → 1.8, because Tessera needs Java 8+. `maven-compiler-plugin` 3.0 → 3.8.1,
  because `annotationProcessorPaths` and `parameters` need it.
- Added `tessera-spring-boot-starter-javax` (Spring 4.3 uses `javax.servlet`), the
  `tessera-compiler` annotation processor with `tessera.templates`, `tessera.package` and
  `tessera.messages`, and `tessera-maven-plugin:check`.
- Annotation processing is switched off for `testCompile` (`<proc>none</proc>`). Otherwise the
  processor generates a second `TesseraRegistry` and service file into `test-classes`.
- `templates/**` is excluded from resource filtering and packaging, because the templates are
  compiled into classes.
- Removed `javax.servlet.jsp-api`, `javax.servlet.jsp.jstl-api` and `taglibs-standard-jstlel`.
- Kept `tomcat-jasper-el` (provided). It is the Unified EL implementation Hibernate Validator uses
  for message interpolation, not part of JSP rendering, and the comment above it now says so.
- Maven wrapper 3.3.3 → 3.9.9, because `tessera-maven-plugin` needs Maven 3.6.3+.

Spring configuration:
- `mvc-view-config.xml`: removed the `ContentNegotiatingViewResolver`, whose default view was a
  `JstlView`, and `<mvc:jsp>`. Added `dev.tessera.spring.TesseraConfiguration`, which brings the
  Tessera view resolver and the `redirect:`/`forward:` resolver. Kept `<mvc:bean-name/>` and the
  JAXB `MarshallingView` bean.
- `mvc-core-config.xml`:
  - `SimpleMappingExceptionResolver` → `PetclinicExceptionResolver`, a subclass that also puts
    `exceptionMessage` (a `String`) in the model, because an `Exception` is not view-safe.
  - Registered `HtmlSuffixInterceptor` (see form actions below).

Templates (`src/main/resources/templates`):
- `fragments/layout.html` replaces `layout.tag`, `htmlHeader.tag`, `bodyHeader.tag`, `menu.tag`,
  `menuItem.tag`, `pivotal.tag` and `footer.tag`. The active menu entry and the page's
  `customScript` are layout parameters.
- `fragments/fields.html` replaces `inputField.tag` and `selectField.tag`, with typed `Field`
  parameters.
- One page template per JSP: `welcome`, `exception`, `owners/*`, `pets/*`, `vets/vetList`.

Java:
- `@TemplateView` on the entities the views read: `Owner`, `Pet`, `PetType`, `Visit`, `Vet`,
  `Vets` and `Specialty`.
- `Pet.getBirthLocalDate()` and `Visit.getLocalDate()` return `LocalDate` for display, because
  `java.util.Date` is not view-safe. JPA uses field access, so these getters are not mapped.
- `PetController.processUpdateForm` now declares the (unused) `@PathVariable petId`, so the view
  can see it.
- `MissingTemplateView`, registered as the bean `visitList`. `VisitController.showVisits` returns
  the view name `visitList`, which never had a JSP. The old setup forwarded to a missing
  `/WEB-INF/jsp/visitList.jsp` and Tomcat answered 404. With no resolver for the name, the request
  would now fail with a 500, so this view sends the same 404, with the same message, to keep
  parity. `VisitControllerTests` still expects that view name, so the controller is unchanged.
- `ViewRenderingTests` (new) renders pages through the real view resolvers. The existing
  controller tests use `standaloneSetup` and never render a template. The new tests cover the
  welcome page, the error page, escaping on owner details, a form with errors, the vet list and the
  `visitList` 404.
- `readme.md` and a `pom.xml` comment now point at the templates instead of the JSP files.

## Intended differences

- Output the JSPs printed raw is now escaped: `${exception.message}`, the field labels and
  `status.errorMessage`. For example, the `@Digits` message `(<10 digits>.<0 digits> expected)` is
  now `&lt;10 digits&gt;…`. The browser shows the same text, and compare agrees.

## What I could not make deterministic

- An empty telephone fails both `@NotEmpty` and `@Digits`. Both the JSP and the template show only
  the first error for the field (JSP `status.errorMessage`, now `fields.errors(name).first`).
- Hibernate Validator returns the violations in a `HashSet` keyed partly by identity hash codes, so
  which message comes first changes from run to run in the original app too. In the baseline,
  request 17 shows "numeric value out of bounds" and request 41, which posts the same data, shows
  "may not be empty".
- So `compare` reports `differs` on those two pages in some runs and `same` in others. Making the
  order deterministic would not match a random baseline either, so I left the behaviour as it was.

## Removed from `pages.txt`

- `GET /owners/1/pets/999/edit`. In the original this is a 500 whose body is a Jasper stack trace
  ("Neither BindingResult nor plain target object for bean name 'pet'"). That page only exists
  because JSP rendering crashed, so there is nothing to keep parity with. The new app also answers
  500, but its body is a different stack trace (a `NullPointerException` in the controller).

## Where Tessera or its documentation fell short

1. **Form actions.**
   - Spring's `<form:form>` without an `action` writes `action="<request URI>"`. Guide 4.10 says
     omitting the action "does too", meaning the browser posts to the same place, but `compare`
     reports the missing attribute as `differs`.
   - A computed POST `action` must be a literal-path link, so there is no way to echo the request
     URI.
   - I had to rebuild each action as a choice of literal links filled from path variables. For the
     `.html` URLs the pages link to (`1/edit.html`, `1/pets/new.html`), a `htmlSuffix` flag comes
     from an interceptor. It is not a `@ControllerAdvice`, because then `RedirectView` would append
     it to every redirect as a query parameter.
   - A built-in "post back to the request URI" form, or a compare rule for it, would remove this
     work.
2. **Path variables.**
   - Section 1 says parameters are filled from "the handler's `@PathVariable` of that name". In
     practice only variables bound by a declared `@PathVariable` argument count, of the handler or
     of a `@ModelAttribute` method, through `View.PATH_VARIABLES`.
   - A variable in the mapping but not declared (`processUpdateForm`'s `{petId}`), or a wildcard
     segment (`/owners/*/pets/{petId}/visits/new`), is `null` in the view. The guide does not say
     this.
3. **The processor also runs on test sources.**
   - With `compilerArgs` at plugin level, as section 2 shows, `testCompile` generates a second
     `TesseraRegistry` with the same class name, and a service file, into `test-classes`. That copy
     shadows the main one on the test classpath.
   - The guide doesn't mention this. I turned processing off for `default-testCompile`.
4. **View names with no template.**
   - Once the old resolver is gone, a view name that no template provides fails with a 500 ("Could
     not resolve view"). The old resolver gave a container 404 for a missing JSP.
   - The guide covers this only for the period while the old engine is still configured. It
     doesn't cover what happens after it is removed, or mention dead view names at all.
5. **Error pages without Boot.**
   - Section 2 covers Boot's error attributes only. For `SimpleMappingExceptionResolver`, whose
     model has only the non-view-safe `exception`, there is no recipe. I subclassed the resolver to
     add the message.
6. **The old-engine warning can't confirm JSP removal.**
   - The startup warning `another template engine is still configured` doesn't detect JSP, or
     resolvers inside a `ContentNegotiatingViewResolver`. The guide says so in section 2.
   - So for this app the warning's absence (confirmed in the startup log) proves nothing. I checked
     the removal by hand: no JSP resolver, no `.jsp`/`.tag` files, and no JSP/JSTL jars in the WAR.
7. **`Date` on form fields.**
   - The guide says `java.util.Date` is not view-safe, but not whether `t:field` may bind a `Date`
     property. It can (through `@DateTimeFormat`).
   - Only displaying a `Date` needed a `LocalDate` getter. I learned this by trial; the guide
     should say it.
8. **`compare` and random validation order.**
   - `reordered` handles moved elements, but not a page that shows one item of an unordered set,
     as described above. A way to accept a known set of alternatives would help.
9. **Single-message errors.**
   - The JSP tags showed only the first error message (`status.errorMessage`). The guide maps
     `<form:errors>` to `t:errors`, which writes every message.
   - The first-message case needs `fields.errors(name).first`. That works, but the guide doesn't
     mention it next to `spring:bind`.

## Security

- There is no Spring Security in this app, so there is no CSRF or `permitAll` configuration to
  keep.
- No `TrustedUrl.unchecked`, no `SafeHtml` built from strings, and no `t:utext`. All output goes
  through Tessera's context escaping.
- No tests were disabled or weakened.
