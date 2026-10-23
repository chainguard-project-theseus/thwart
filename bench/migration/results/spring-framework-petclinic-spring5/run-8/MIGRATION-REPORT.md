# Migration report: JSP → Tessera

Spring Framework Petclinic (plain Spring MVC 5.3, XML configuration, `javax.servlet`, Java 8 target,
WAR run with `mvn jetty:run-war`) now renders every HTML page with compiled Tessera templates.
JSP, JSTL, the tag files and the JSP view resolvers are gone.

## Result against the acceptance criteria

| Criterion | Status |
|---|---|
| Every page rendered by Tessera, old view technology gone | Done. All 9 JSPs and 10 tag files deleted; `taglibs-standard-jstlel` and `tomcat-jasper-el` removed; `InternalResourceViewResolver`/`ContentNegotiatingViewResolver`+`JstlView` configuration removed. |
| Normal Maven build + all existing tests | `mvn clean verify` passes on JDK 8 (the target, `java.version` 1.8) and on JDK 21: 67 tests, 0 failures. No existing test was changed. One new test class was added (`ViewRenderingTests`). |
| Page parity | `pages.txt` (53 requests, see below) recorded from the original app and compared against the new one: 52 `same`, 1 `differs` (#49, explained below). |
| Security | No `SafeHtml.unchecked`/`TrustedUrl.unchecked`, no `permitAll`, no CSRF or escaping turned off, no tests disabled. Output that JSP printed raw (`${exception.message}`, `${label}`) is now escaped. |

## What changed

### Build (`pom.xml`)
- Added `dev.tessera:tessera-spring-boot-starter-javax:0.1.0-SNAPSHOT` (the app uses `javax.servlet`).
- `maven-compiler-plugin` (already 3.8.1): `tessera-compiler` as the annotation processor, with
  `-Atessera.templates=${project.basedir}/src/main/templates`,
  `-Atessera.package=org.springframework.samples.petclinic.templates` and
  `-Atessera.messages=.../src/main/resources/messages/messages` (message keys are checked at build time),
  plus `<parameters>true</parameters>`. The build had no other annotation processors to list.
- Templates live in `src/main/templates`, **outside** `src/main/resources`. The resources there are
  filtered (`<filtering>true</filtering>`), which would have rewritten `${...}` in the templates, and the
  compiled templates don't need the HTML on the runtime classpath anyway.
- Removed `taglibs-standard-jstlel` (JSTL) and `tomcat-jasper-el` (Tomcat's JSP EL).
- Added `org.glassfish:jakarta.el:3.0.4` with `provided` scope, the same scope `tomcat-jasper-el` had.
  Hibernate Validator needs an EL implementation to interpolate messages, and it had been getting one
  from the JSP EL jar. Without it, validation tests fail with `ValidationException`. At runtime the
  container provides EL, as it did before.

### Spring configuration
- `mvc-view-config.xml`: replaced the `<mvc:content-negotiation>` default `JstlView` and `<mvc:jsp>`
  with `<bean class="dev.tessera.spring.TesseraConfiguration"/>`. That bean is the Tessera view resolver
  plus a `redirect:`/`forward:` resolver. Kept `<mvc:bean-name/>` (for the `vets/vetList.xml`
  marshalling view) and added `MissingViewResolver` last in the chain (see #49).
- `mvc-core-config.xml`: `SimpleMappingExceptionResolver` → `ExceptionMessageResolver` (a subclass,
  see below). Comments now point at the templates instead of JSPs.

### Java
- `@TemplateView` added to the entities that templates read: `Owner`, `Pet`, `PetType`, `Vet`, `Specialty`,
  `Vets`, `Visit`. Getters inherited from the `@MappedSuperclass`es resolve through them.
- `web/CurrentRequest` + `web/CurrentRequestAdvice` (`@ControllerAdvice`): expose the originating
  request URI (+ query string) as model attribute `currentRequest`. JSP's `<form:form>` posted back to
  the current URL by default, and the pet, visit and owner forms rely on that. For example, the visit
  form's GET mapping is `/owners/*/pets/{petId}/visits/new`, so the owner id isn't even a path
  variable, and after a failed POST to `/owners/{id}/edit` the owner's `id` is null (disallowed field).
  `CurrentRequest` is a `@TemplateView` class on purpose, not a `String`: `RedirectView` exposes simple
  model attributes as query parameters, so a String attribute would have leaked into every
  `redirect:` URL.
- `web/ExceptionMessageResolver`: `SimpleMappingExceptionResolver` that also adds
  `exceptionMessage` (a `String`) to the model. The `Exception` itself isn't view-safe. The
  `exception` attribute is still there, so `CrashControllerTests` is unchanged.
- `web/MissingViewResolver`: last view resolver. A view name that nothing resolves answers 404
  (see #49).

### Templates (`src/main/templates`)
| JSP / tag | Tessera |
|---|---|
| `tags/layout.tag`, `htmlHeader.tag`, `bodyHeader.tag`, `menu.tag`, `menuItem.tag`, `pivotal.tag`, `footer.tag` | `fragments/layout.html`: `layout(String pageName, Fragment content, Fragment customScript)`. The active menu entry is a conditional `class`. The page-specific script (`<jsp:attribute name="customScript">`) is a `Fragment`. |
| `tags/inputField.tag`, `selectField.tag` | `fragments/fields.html`: `input(String label, Field name, boolean hasValue)` and `select(String label, Field name, Collection<PetType> names, String size, boolean hasValue)` |
| `tags/localDate.tag` | `temporals.format(date, 'yyyy-MM-dd')` / `'yyyy/MM/dd'` |
| `welcome.jsp`, `exception.jsp`, `vets/vetList.jsp`, `owners/*.jsp`, `pets/*.jsp` | same path with `.html` |

View names are unchanged, so no controller had to change and the existing `view().name(...)`
assertions still hold.

Conversion notes:
- `<form:form>` → `<form t:object=... t:action=... method="post" id=...>`. The default `id` (the model
  attribute name: `pet`, `visit`) is written out, and the default action is `currentRequest.uri`.
- `spring:bind` / `status.error` / `status.errorMessage` / `status.actualValue` →
  `fields.hasErrors(name)` and `fields.errors(name).first` (JSP showed only the first message). The
  "valid" tick depends on the field's current value, which a `Field` fragment can't read, so each call
  site passes `hasValue` (`!strings.isEmpty(owner.city)`, `pet.birthDate != null`, ...).
- `<form:errors path="*"/>` → `<span id="owner.errors" t:errors="*">`.
- `${pet.id}` in hidden inputs: EL printed `""` for null, so it's `pet.id == null ? '' : '' + pet.id`.
  A bare null would have dropped the `value` attribute.
- `${pet.owner.firstName}` after a failed pet POST: the bound `Pet` has no owner, and EL ignored the
  null. This became `pet.owner?.firstName` (the same for `type?.name`).
- `<c:if test="${!visit['new']}">` → `t:unless="previous.isNew()"`.

### Tests
- New `ViewRenderingTests`: renders through the real view resolvers (`webAppContextSetup` over
  `mvc-core-config.xml`), unlike the existing `standaloneSetup` tests, which never render a view. It
  checks the welcome page, that `<script>`/`<b>` in owner data and in rejected form values come out
  escaped, form action/error rendering, the exception page, and that `redirect:` still resolves.

## Page parity

`pages.txt` covers, in order, against a freshly started app (in-memory H2 seed data): home, vets, the
error page (`/oups`), find-owners empty, not found, one result (redirect) and several results (including
`?lastName=` and no parameter), owner details (with and without visits, unknown id → exception page,
non-numeric id → 400), the owner form new/edit empty, with validation errors and after success (including
HTML/quotes in names), the pet form new/edit empty, with each validation error (required, duplicate name,
bad date) and after success, the visit form empty, with errors (empty description, bad date) and after
success (including a `<script>` description), the visit form for a pet with no visits, the
`/owners/*/pets/{id}/visits` route and an unknown URL (404).

Commands (from `/work/app`): app started with `mvn jetty:run-war` (JDK 8) for both runs;
`java -jar .tessera/tessera-migrate.jar record http://localhost:8080 pages.txt baseline/` on the
original commit, `... compare http://localhost:8080 pages.txt baseline/` on the migrated app.

### Remaining differences
- **#49 `GET /owners/6/pets/7/visits`: differs (body only; status 404 and final URL match).**
  `VisitController.showVisits` returns the view name `visitList`, but the original never had a
  `visitList.jsp`. The old app forwarded to the missing `/WEB-INF/jsp/visitList.jsp`, and Jetty's JSP
  servlet answered with its 404 page ("HTTP ERROR 404 /WEB-INF/jsp/visitList.jsp", "SERVLET: jsp").
  With JSP gone, an unresolved view name would have been a 500 (`Could not resolve view with name
  'visitList'`). `MissingViewResolver` turns it back into a 404. The only differences left are in
  Jetty's own error page text: the message ("Not Found" instead of the JSP path) and the servlet name
  ("dispatcher" instead of "jsp"). Copying that page exactly would mean forwarding to a fake JSP path
  through Jetty's JSP servlet, which brings back the JSP configuration this migration removes. I kept
  the view name and model (`VisitControllerTests` asserts on both) and didn't invent a `visitList` page,
  because that would change the status from 404 to 200.
- **#16 `POST /owners/new` with all fields empty: flaky in the original too.** An empty telephone breaks
  both `@NotEmpty` and `@Digits`. Hibernate Validator returns the violations in a `HashSet` whose hash
  codes include identity hashes, so which message comes "first" changes from JVM run to JVM run. JSP
  showed `status.errorMessage` (the first one), and the template shows `fields.errors(...).first`, so
  both apps print the same message given the same order. Across runs this page came out `differs` once
  (the other message) and `same` once. The difference comes from the validator, not the views.

## What I could not convert
Nothing in the views. Everything the JSPs rendered has a Tessera equivalent. The one gap is the
error-page text of the never-implemented `visitList` view (above).

## Where Tessera or its documentation fell short
1. **Non-Boot error views.** The guide covers Boot's `error.html` and its attributes, but not the classic
   `SimpleMappingExceptionResolver`, which exposes an `Exception` (not view-safe). I had to subclass
   the resolver to expose the message as a `String`. A short recipe in section 2 or 7 would help.
2. **Unresolved view names without Boot.** The guide says unknown names "fall through to other
   resolvers". With the JSP `InternalResourceViewResolver` gone, nothing catches them: a missing view
   becomes a 500 `ServletException` instead of the old 404. Nothing warns about this. Tessera knows its
   templates at build time but can't check controller view-name strings.
3. **"Post back to the current URL" has no supported helper.** The guide says to "write the `action`
   with `@{...}`, or put the URL in the model", but doesn't say that a `String` model attribute added by
   a `@ControllerAdvice` gets appended to every `redirect:` URL by `RedirectView` (Spring MVC without
   `ignoreDefaultModelOnRedirect`). I had to wrap it in a `@TemplateView` class. A built-in (for example
   a `request.uri()` in the render context, or `t:action` defaulting to the current URL like Spring's
   form tag) would remove a whole class of mistakes.
4. **`Field` fragments can't read the field's value** (a known limitation). Spring's `status.actualValue`
   pattern (the "valid" tick) needed an extra `boolean` argument at every call site, which duplicates
   the field path.
5. **No "first error" function.** Spring's `status.errorMessage` has no `fields.*` equivalent. The guide
   documents `first` on lists, and `fields.errors(name).first` worked, but the guide never shows using a
   built-in on a function result.
6. **`t:field` and `value`.** Section 4.7 says the value is "omitted when null". Under Spring, the
   `BindingResult` display value of a null property is `""`, so `value=""` is written (which matches the
   JSP form tag). That's right, but the docs suggest otherwise, and I had to confirm it by comparing pages.
7. **`<parameters>true</parameters>`** is in the build snippet without saying whether Tessera needs it.
8. **Hidden dependency of the old stack.** This one isn't Tessera's fault, but it belongs in the guide's JSP
   notes: removing the JSP EL jar (`tomcat-jasper-el`/Jasper) also removes the EL implementation that
   Hibernate Validator needs for message interpolation. Validation then fails at test time only, because
   the servlet container still provides EL at runtime.
9. **Skill/guide pointers.** The skill says Tessera can be built from `github.com/chainguard-dev/harder`.
   There is no source, issue tracker or explanation of that repository here, so I checked behaviour
   against the starter's `-sources.jar` (`SpringForms`, `TesseraConfiguration`) instead.
10. **`tessera-migrate` handles only Thymeleaf mechanically.** For JSP there is no rewrite, so the
    conversion was manual (fine at this size, but the guide only hints at it).

## Files not to commit
`.tessera/` (the migrate jar) and `baseline/` (the recording) are in `.gitignore`. `pages.txt` is kept
so the comparison can be repeated.
