# Migration report: JSP → Tessera

Spring Petclinic (plain Spring MVC 4.3, XML configuration, WAR on Tomcat 7, built and tested with JDK 8)
now renders every HTML page with Tessera 0.1.0-SNAPSHOT compiled templates. JSP, JSTL, the Spring JSP tag
libraries and the tag files are gone.

## Result

| Check | Result |
|---|---|
| `mvn clean verify` on JDK 8 (the JDK this app targets: `.travis.yml` uses `oraclejdk8`) | passes, **70 tests**: the 62 existing tests, unchanged, plus 8 new rendering tests |
| `tessera-migrate compare` on `pages.txt` (52 requests) | **49 same**; 3 differ, each with the recorded status and final URL (explained below) |
| Old view technology | JSPs (`WEB-INF/jsp`), tag files (`WEB-INF/tags`), the JSP/JSTL/Jasper dependencies, the `<mvc:jsp>` resolver and the `JstlView` default view are all removed |
| Security | no `SafeHtml.unchecked` / `TrustedUrl.unchecked`, no `t:utext`, no `permitAll`, no escaping turned off (the app has no Spring Security, so there is no CSRF to keep), no tests disabled or changed |

## What changed

**Build (`pom.xml`)**
- `java.version` 1.7 → 1.8 (Tessera needs Java 8). `maven-compiler-plugin` 3.0 → 3.8.1 (needed for
  `annotationProcessorPaths`), with `tessera-compiler` as the annotation processor and
  `-Atessera.templates`, `-Atessera.package=org.springframework.samples.petclinic.templates`, and
  `-Atessera.messages` (so every `#{...}` key is checked at build time).
- Added `tessera-spring-boot-starter-javax` (the app uses `javax.servlet`).
- Removed `javax.servlet.jsp-api`, `tomcat-jasper-el`, `javax.servlet.jsp.jstl-api`, `taglibs-standard-jstlel`.
- Added `org.glassfish:javax.el` in `provided` scope. Hibernate Validator needs an EL implementation to
  interpolate constraint messages. `tomcat-jasper-el` used to supply one on the test classpath, and without
  it every Bean Validation test failed. At runtime Tomcat provides EL, as before.
- `src/main/resources/templates/**` is left out of resource filtering and copying. Templates are compiled
  to classes and nothing reads them at runtime.

**Templates (`src/main/resources/templates`)**: one template per old JSP, with the same view names:
`welcome`, `exception`, `owners/{createOrUpdateOwnerForm,findOwners,ownerDetails,ownersList}`,
`pets/{createOrUpdatePetForm,createOrUpdateVisitForm}`, `vets/vetList`.
- `fragments/layout.html`: replaces `layout.tag`, `htmlHeader.tag`, `bodyHeader.tag`, `menu.tag`,
  `pivotal.tag` and `footer.tag`. It takes `layout(String pageName, Fragment content, Fragment customScript)`.
  The page-specific datepicker `<script>` (was `<jsp:attribute name="customScript">`) is passed as
  `~{::script}`.
- `fragments/menu.html`: `menuItem(boolean active, String url, String title, Fragment body)` (was `menuItem.tag`).
- `fragments/fields.html`: `inputField(String label, Field name, boolean filled)` (was `inputField.tag`).
  The one `selectField.tag` use (pet type) is written inline in the pet form.

**Java**
- `@TemplateView` on the entities that templates read: `Owner`, `Pet`, `PetType`, `Visit`, `Vet`, `Vets`,
  `Specialty`. The annotation doesn't change JPA, JAXB or Jackson behaviour.
- `web/DateFunctions` (`@TemplateFunctions("dates")`): `dates.format(Date, pattern)` replaces
  `<fmt:formatDate>`, because `java.util.Date` cannot be printed by a template.
- `web/FormActionInterceptor`: puts the current request URL (plus query string, through
  `response.encodeURL`) into the model as `formAction`. `<form:form>` without an `action` used to post back
  to the current URL; templates cannot see the request. It is an interceptor rather than a `@ModelAttribute`
  so that it is never added to `redirect:` views, where `RedirectView` would append a String model attribute
  to the target URL as a query parameter.
- `web/ExceptionMessageResolver` (subclass of `SimpleMappingExceptionResolver`, configured in
  `mvc-core-config.xml`): also exposes `exceptionMessage` (a `String`), because the `Exception` itself is not
  view-safe. `exception` stays in the model, so `CrashControllerTests` is unchanged.
- `web/NotFoundViewResolver`: the last resolver. A view name with no template answers 404, as the
  `InternalResourceViewResolver` did by forwarding to a missing JSP. Without it, `VisitController.showVisits`
  (returns `visitList`, which never existed) would turn from 404 into 500.
- `mvc-view-config.xml`: `<bean class="dev.tessera.spring.TesseraConfiguration"/>`. The existing
  `ContentNegotiatingViewResolver` now delegates to the `BeanNameViewResolver`, `tesseraViewResolver`,
  `tesseraRedirectViewResolver` (for `redirect:`) and `NotFoundViewResolver`.
- New test `TemplateRenderingTests` renders through the real resolvers (the controller tests use
  `standaloneSetup` and never render). It checks that `<script>`, quotes, `&` and tags in user data come out
  escaped, that the form action and validation errors are right, that redirects work, and the exception and
  404 behaviour.
- `readme.md` and a pom comment updated to point at the templates instead of the JSP files.

## Page parity

`pages.txt` lists 52 requests: every page in every state. Find-owner results (none, one with redirect, many,
all), owner details (with and without pets and visits, before and after edits), owner, pet and visit forms
(empty, each kind of validation error, type-conversion errors, duplicate pet name, success with redirect, the
`.html` suffix variants), vets as HTML, JSON and XML, the exception page (`/oups`, and `/owners/999`, which
fails in the data layer), user data with `<`, `'`, `&` and non-ASCII, plus 400 and 404 container pages.
`baseline/` was recorded from the original app (`mvn tomcat7:run-war` on JDK 8, in-memory HSQLDB) before
any change. The new app was started the same way.

The three pages that differ, all with the recorded status and final URL:

1. **`POST /owners/1/edit … telephone=` (page 22): which of two error messages is shown, and it varies
   between runs.** An empty telephone breaks both `@NotEmpty` and `@Digits`. Hibernate Validator returns its
   violations from a `HashSet` that hashes the bean by identity, so their order changes from one JVM run to
   the next. The old `inputField.tag` printed only the first (`status.errorMessage`), and so does the
   template (`fields.errors(name).first`). The original app is itself non-deterministic here: within one
   recording, page 15 showed "may not be empty" and page 22 showed "numeric value out of bounds" for the
   same empty telephone. My first compare run reported page 22 as `same`, the last run as `differs`. Making
   this deterministic would need a change to validation, not to the views, so I left it.
2. **`GET /owners/1/pets/999/edit` (page 50): 500 in both apps.** The controller puts a `null` pet into the
   model and rendering fails. The body is Tomcat's error report, whose message and stack trace named the JSP
   (`JasperException … createOrUpdatePetForm.jsp at line 29`) and now name the template
   (`NullPointerException … CreateOrUpdatePetFormTemplate`). It can't match without the JSP.
3. **`GET /owners/1/pets/1/visits` (page 51): 404 in both apps.** The original 404 came from Tomcat failing
   to find `/WEB-INF/jsp/visitList.jsp`, and its error report printed that path. The new 404 comes from
   `NotFoundViewResolver` without a message. I did not fake the old JSP path.

Escaping changed only where the old output was raw. `${status.errorMessage}` was printed unescaped, so
`(<10 digits>.<0 digits> expected)` is now `&lt;10 digits&gt;…`. The browser shows the same text, and compare
reports `same`.

## Things I could not convert one-to-one

Everything renders. These are the places where the behaviour is reproduced rather than translated:
- `<form:form>`'s implicit action and auto `id`: an explicit `t:action="formAction"` (from the
  interceptor) and a literal `id="pet"` / `id="visit"`.
- The "valid" check mark in `inputField.tag` read `status.actualValue`. A Tessera `Field` fragment parameter
  cannot read the value, so callers pass a `boolean filled` computed from the model object.
- `<fmt:formatDate>` became the `dates.format` template function (see above).
- The 404 for a view name that has no template (`NotFoundViewResolver`, see above).

## Where Tessera or its documentation fell short

1. **XML wiring under `<mvc:view-resolvers>` / `ContentNegotiatingViewResolver` is undocumented.** The
   guide gives `<bean class="dev.tessera.spring.TesseraConfiguration"/>` but not the bean names to reference
   from an existing `<mvc:view-resolvers>` (`tesseraViewResolver`, `tesseraRedirectViewResolver`). I found
   them only by reading the starter's `-sources.jar`. An `<mvc:ref>` element doesn't exist (it is the beans
   namespace's `<ref>`), which cost a failed start.
2. **No fallback for view names without a template.** JSP's `InternalResourceViewResolver` resolved any
   name, so a missing view was a 404. With Tessera it is "Could not resolve view" (500), or a 406 behind a
   `ContentNegotiatingViewResolver` with `use-not-acceptable`. The guide doesn't mention this. I wrote a
   resolver for it.
3. **`java.util.Date`**: the guide says it is not view-safe but offers no built-in to format one.
   `temporals.*` only takes `java.time`, so every app with legacy `Date` entities has to write a function.
   It is also inconsistent: the guide's limitations list suggests `t:field` doesn't work on non-view-safe
   types, but `t:field="*{birthDate}"` on a `Date` compiles and binds correctly (through the
   `@DateTimeFormat` formatter).
4. **The guide's "values every page needs come from `@ModelAttribute` methods in a `@ControllerAdvice`"
   advice is a trap for String values** in apps that don't set `ignoreDefaultModelOnRedirect`:
   `RedirectView` appends simple-typed model attributes to the redirect URL. That breaks the final URL of
   every redirect, which `compare` checks. A warning in section 3 or 4.7 next to "put the URL in the model"
   would help.
5. **Forms have no equivalent of `<form:form>`'s defaults** (action = current URL, `id` = model attribute
   name). The guide mentions only the action, and doesn't say how to obtain the current URL when templates
   cannot see the request.
6. **`Field` fragment parameters cannot read the bound value or `BindStatus.actualValue`** (documented), so
   a reusable field fragment with "has value" styling needs an extra argument at every call site.
7. **Exceptions in error views**: `SimpleMappingExceptionResolver` puts an `Exception` in the model, which
   templates can't read. The guide covers Boot's error attributes but not this plain Spring MVC pattern.
8. **Constraint-violation order**: `compare` has no way to treat a set of alternative messages as
   equivalent. `--ignore` would hide the whole message, so the flaky page above stays `differs`.
9. **Minor**: the generated-template class names and the `META-INF/tessera/functions` index are
   undocumented beyond a mention. The skill says the starter brings only `tessera-runtime`, but it doesn't say
   that the EL implementation previously pulled in by JSP dependencies may be needed by Hibernate Validator.
   That isn't Tessera's fault, but it is a common JSP-migration trap worth a line.

## How to reproduce

```sh
# baseline (original app, before the migration)
mvn tomcat7:run-war            # JDK 8, http://localhost:9966/petclinic
java -jar tessera-migrate.jar record  http://localhost:9966/petclinic pages.txt baseline/
# migrated app, started the same way
java -jar tessera-migrate.jar compare http://localhost:9966/petclinic pages.txt baseline/
```
