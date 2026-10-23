# Migration report: JSP → Tessera

Spring Framework Petclinic (Spring MVC 5.3, XML config, `javax.servlet`, Java 8 target, run with
`mvn jetty:run-war`) now renders every page with Tessera 0.1.0-SNAPSHOT
(`tessera-spring-boot-starter-javax`, wired with `TesseraConfiguration` because the app doesn't use Boot).

## Results against the "done" criteria

| Criterion | Status |
|---|---|
| All pages rendered by Tessera; JSP gone | Done. All 9 JSPs and 10 tag files are deleted. `tomcat-jasper-el`, `taglibs-standard-jstlel`, the `ContentNegotiatingViewResolver`/`JstlView`/`InternalResourceViewResolver` config and every JSP reference in comments, pom and readme are gone. |
| `mvn clean verify` on the target JDK | Passes on JDK 8 (`/usr/lib/jvm/java-1.8-openjdk`), 66 tests (the 62 existing tests plus 4 new ones). `mvn clean package` also passes on JDK 21. No existing test was changed. |
| Page parity | `pages.txt` (51 requests) was recorded from the original app and compared against the new one: 49 `same`, 1 `reordered` (harmless, see below), 1 `differs` (the dead `visitList` endpoint, see below). A second set of 17 edge-case requests was recorded from the original running in a separate worktree: 15 `same`, and 2 that differ for reasons that are not template differences (see below). |
| Security | No `SafeHtml.unchecked` or `TrustedUrl.unchecked`, no `permitAll`, no escaping turned off, no tests disabled or weakened. The app has no Spring Security, so it never had CSRF. Every value is now escaped for its context. The JSP printed some values raw (`${exception.message}`, `${status.errorMessage}`, `${label}`), and Tessera escapes those now. |

## What changed

**Build (`pom.xml`)**
- Added `dev.tessera:tessera-spring-boot-starter-javax` and set up `maven-compiler-plugin` with
  `tessera-compiler` as the annotation processor and `-Atessera.templates`, `-Atessera.package`
  (`org.springframework.samples.petclinic.templates`) and `-Atessera.messages` (so every `#{...}` key
  is checked at build time). Test compilation runs with `<proc>none</proc>`, as the guide says to.
- Resource filtering is on for `src/main/resources`, so `templates/**` is excluded from it. Templates are compiled to
  classes and are not copied into the war.
- Removed `tomcat-jasper-el` and `taglibs-standard-jstlel`. **Side effect found by the tests:**
  `tomcat-jasper-el` was also the Unified EL implementation that Hibernate Validator needs for message
  interpolation, so `ValidatorTests` and three controller tests failed without it. I replaced it with
  `org.glassfish:jakarta.el:3.0.4`, `provided` like before (Jetty supplies EL at runtime).

**Spring configuration**
- `mvc-view-config.xml`: replaced the `mvc:view-resolvers` block (content negotiation + `JstlView` +
  JSP resolver) with `<bean class="dev.tessera.spring.TesseraConfiguration"/>` (template resolver plus
  its `redirect:`/`forward:` resolver). I kept a plain `BeanNameViewResolver` for the existing
  `vets/vetList.xml` marshalling view bean, and added `MissingViewResolver` last (see below).
- `mvc-core-config.xml`: the exception resolver is now `ExceptionMessageResolver`, a
  `SimpleMappingExceptionResolver` subclass that also puts `exceptionMessage` (a `String`) in the
  model. A template can't read an `Exception`. The `exception` attribute is still there, so
  `CrashControllerTests` is unchanged.

**Java**
- `@TemplateView` on the model classes the templates read: `Owner`, `Pet`, `PetType`, `Specialty`, `Vet`,
  `Vets`, `Visit`. Getters from the `@MappedSuperclass`es resolve through these.
- `CurrentRequest` (`@TemplateView`) and `CurrentRequestAdvice` (`@ControllerAdvice`): expose the
  originating request URI as `currentRequest`. Spring's `<form:form>` posted back to the current URL by
  default, and several form URLs depend on path variables that aren't in the model (for example
  `/owners/{ownerId}/edit` after a failed bind, where `owner.id` is null because `id` is a disallowed
  field). `CurrentRequest` is deliberately not a simple type, so `RedirectView` never adds it to redirect URLs as a
  query parameter.
- `MissingViewResolver`: answers 404 for a view name that nothing resolves. Before, a missing JSP
  made the container answer 404. Without this resolver the dispatcher would throw "Could not resolve view" (500).
  `VisitController.showVisits` returns `visitList`, which never had a JSP.

**Templates** (`src/main/resources/templates`)
- `fragments/layout.html`: `layout(Fragment content, String pageName, Fragment customScript)`. It
  replaces `layout.tag`, `htmlHeader.tag`, `bodyHeader.tag`, `menu.tag`, `menuItem.tag`, `pivotal.tag` and `footer.tag`.
  The active menu entry comes from `pageName`, and pages pass their datepicker `<script>` as `customScript`.
- `fragments/fields.html`: `inputField(...)` and `petTypeField(...)` replace `inputField.tag` and
  `selectField.tag`. They use `Field` parameters, `fields.hasErrors`, and the first message of
  `fields.errors` (the JSP printed `status.errorMessage`, the first error only). A `Field` fragment can't read the
  value, so the caller passes a `filled` flag for the "valid" icon (JSP: `not error and not empty actualValue`).
- `welcome`, `exception`, `vets/vetList`, `owners/{findOwners,ownersList,ownerDetails,createOrUpdateOwnerForm}`,
  `pets/{createOrUpdatePetForm,createOrUpdateVisitForm}`. Same view names, so controllers and tests
  are unchanged. `localDate.tag` became `temporals.format(date, pattern)`.

**Tests**: added `ViewRenderingTests`. All other controller tests use `standaloneSetup` and never render a view.
The new tests render through the real resolvers and check that `<script>` and quotes in user data come out escaped,
that a form with errors posts back to `/owners/1/edit` and echoes the rejected value escaped, the exception page,
and the 404 for an unknown view.

## Remaining page differences, and why they are acceptable

1. **`GET /owners/{id}/pets/{petId}/visits`** (`differs`, status and URL match: 404). The controller
   returns view `visitList`, which never existed. Before, Jetty's JSP servlet answered
   `HTTP ERROR 404 /WEB-INF/jsp/visitList.jsp` (servlet `jsp`), and now `MissingViewResolver` answers
   `HTTP ERROR 404 Not Found` (servlet `dispatcher`). Only a forward to a `/WEB-INF/jsp/...jsp` path would
   reproduce that body byte for byte, and that would mean keeping the old JSP view-resolver configuration. That is
   what criterion 1 asks to remove, so I accepted the different container error text.
2. **`POST /owners/new ... telephone=`** (extra set; on another run the same can happen to any request where
   one field fails two constraints). An empty telephone fails both `@NotEmpty` and `@Digits`. The
   page shows the first message, as the JSP did. Hibernate Validator returns the violations as a `HashSet`, and
   their hash includes the bean's identity hash, so the order changes on every run **in the original app too**:
   baseline page 16 shows "numeric value out of bounds" first, and the extra baseline shows "must not be empty".
   No template can make that deterministic without changing what the old app showed.
3. **`GET /owners/1/pets/9999/edit`** (extra set, 500 in both). This is a bug in the app (the pet doesn't exist,
   so the model has `pet = null`). Both apps answer 500 with Jetty's error page, but the stack trace in the body
   names the engine that failed (Jasper `JspTagException` vs `NullPointerException` in the generated template).
   It's the same failure.
4. **`reordered`** pages (`/owners/1` after renaming pet 1 to "Leo" while a second "Leo" exists). `Owner.getPets()`
   sorts by name, and pets with the same name keep `HashSet` order. The original's order varies in the same way.
   Harmless.

## Tessera / documentation shortcomings encountered

- **Values and errors of a `Field` fragment.** The guide says a `Field` fragment can't read the value. Porting
  a JSP tag that shows a "valid" icon when the field has a value means passing a separate
  `boolean filled` argument at every call site and repeating the field name in it
  (`inputField('City', 'city', !strings.isEmpty(owner.city))`). Something like `fields.value(name)` would remove
  that duplication.
- **No default `action` for forms.** Spring's form tag posts to the current URL by default. Tessera doesn't,
  and templates can't see the request, so every app that relied on the default needs its own
  `@ControllerAdvice`. The guide mentions "put the URL in the model" but doesn't say that a `String` model
  attribute added by a `@ControllerAdvice` leaks into every `redirect:` URL as a query parameter
  (`RedirectView` exposes simple model attributes, and plain Spring MVC defaults
  `ignoreDefaultModelOnRedirect` to false). I wrapped it in a `@TemplateView` class to avoid that. A
  built-in like `request.uri` or a documented recipe would help.
- **Unresolvable view names without Boot.** The guide covers the `redirect:`/`forward:` resolver, but not
  what happens to a view name that no resolver knows. With the old `InternalResourceViewResolver` that was a
  container 404. With Tessera it becomes a 500 ("Could not resolve view"), and I had to write a fallback resolver.
- **Removing the JSP dependencies removes the EL implementation.** It isn't Tessera's fault, but a JSP
  migration guide should warn that `tomcat-jasper-el`/`jasper` often doubles as Hibernate Validator's EL
  provider. Removing it breaks validation at runtime and in tests.
- **Exceptions in error views.** `SimpleMappingExceptionResolver` puts an `Exception` in the model, which is not
  view-safe. The guide only covers Boot's `error.html` attributes, so non-Boot apps need a resolver subclass
  like `ExceptionMessageResolver`.
- **Error-message ordering.** There's no way to sort `fields.errors(...)` in the template, and the guide doesn't
  mention that Spring/Hibernate error order can be nondeterministic, which affects parity checks.
- **Tooling details.** `tessera-migrate` has no JSP converter (only Thymeleaf), so I wrote every template by hand.
  The guide's dependency snippet uses `<release>`, which doesn't work for a Java 8 build. The guide does say to use
  source/target instead, and that worked: the existing `maven.compiler.source/target` 1.8 were enough.
- What worked well: compiling all templates at build time with typed parameters and message-key checks meant
  the first deployed build had no template errors at runtime. Form binding (rejected-value echo, `@DateTimeFormat`, the `PetTypeFormatter` select
  value and `selected`) matched Spring's form tags exactly.

## How to re-run the parity check

```sh
mvn -q dependency:copy -Dartifact=dev.tessera:tessera-migrate:0.1.0-SNAPSHOT -Dmdep.stripVersion=true -DoutputDirectory=.tessera
# original app (git history before this change): mvn jetty:run-war, then
java -jar .tessera/tessera-migrate.jar record http://localhost:8080 pages.txt baseline/
# this app: mvn clean jetty:run-war, then
java -jar .tessera/tessera-migrate.jar compare http://localhost:8080 pages.txt baseline/
```
`.tessera/` and `baseline/` are git-ignored.
