# Migration report: JSP → Tessera

Spring Framework Petclinic (Spring 7, XML configuration, no Spring Boot, no Spring Security, run
with `mvn jetty:run-war`). Every HTML page used to be a JSP with custom tag files. Every page is now
a compiled Tessera template.

## Result

- `mvn clean install`: build passes and all 75 existing tests pass, none of them changed. I added
  `ViewRenderingTests` (4 tests). It renders pages through the real view resolvers and checks
  that `<script>` and markup in user data and in rejected form values come out escaped.
- Parity: I recorded the original app with `tessera-migrate record` before changing anything, using
  `pages.txt` (47 requests: every page, empty/error/success form states, lists, redirects, error
  pages, and HTML in user input). The recording is in `migration/baseline/`. Against a freshly
  started new app, `tessera-migrate compare` gives **45 `same`, 2 `differs`**. Both differences
  are explained below and are not template defects.
- Security: no `SafeHtml.unchecked`/`TrustedUrl.unchecked`, no `permitAll`, nothing turned off.
  The app has no Spring Security or CSRF, before or after. Values that the JSPs printed raw
  (`${exception.message}`, `${status.errorMessage}`, `${label}`) are now escaped. That made no
  visible difference on any recorded page.

## What changed

**Build (`pom.xml`)**
- Removed the JSP/JSTL dependencies (`jakarta.servlet.jsp.jstl-api`, glassfish
  `jakarta.servlet.jsp.jstl`) and `tomcat-jasper-el`.
- `tomcat-jasper-el` was there only as the Expression Language implementation that Hibernate
  Validator needs to interpolate messages. I replaced it with the standalone EL implementation
  `org.glassfish.expressly:expressly` (provided scope, as before) so nothing Jasper/JSP-branded
  stays.
- Added `dev.tessera:tessera-spring-boot-starter` (jakarta flavour). Plain Spring MVC is wired by
  hand, see below.
- Added `tessera-compiler` as the annotation processor, with `-Atessera.templates`,
  `-Atessera.package=org.springframework.samples.petclinic.templates` and `-Atessera.messages`
  (all `#{...}` keys are checked at build time).
- Turned off annotation processing for `default-testCompile`.
- Excluded `templates/**` from resource filtering: resources are filtered for the database
  properties, which would otherwise rewrite `${...}` inside templates.

**Spring configuration**
- `mvc-view-config.xml`: removed the `ContentNegotiatingViewResolver`, the default `JstlView` and
  the JSP `InternalResourceViewResolver`. Added `<bean class="dev.tessera.spring.TesseraConfiguration"/>`,
  which brings the Tessera view resolver plus a `redirect:`/`forward:` resolver. Kept the
  `BeanNameViewResolver`, the `vets/vetList.xml` `MarshallingView` and the JAXB marshaller: that
  is XML output, not the old view technology.
- `mvc-core-config.xml`: the `SimpleMappingExceptionResolver` bean is now
  `web.ExceptionMessageResolver`, a small subclass. It also puts the exception message into the
  model as a `String` (`exceptionMessage`), because an `Exception` is not view-safe. The
  `exception` attribute is still there; `CrashControllerTests` checks for it.

**Java**
- Model classes (`BaseEntity`, `NamedEntity`, `Person`, `Owner`, `Pet`, `PetType`, `Specialty`,
  `Vet`, `Vets`, `Visit`) are annotated `@TemplateView`. That is the only change to them.
- `web.ViewModelAdvice` (`@ControllerAdvice`) adds `formAction`, the request URI plus query string.
  JSP's `<form:form>` posted back to the current URL by default, and the edit forms cannot rebuild
  that URL from the model: after a failed POST the owner's `id` is a disallowed field and is null.

**Templates** (`src/main/resources/templates`, view names unchanged so controllers and tests are
untouched)
- `fragments/layout.html`: `layout(Fragment content, String pageName, Fragment customScript)`.
  Replaces `layout.tag`, `htmlHeader.tag`, `bodyHeader.tag`, `menu.tag`, `pivotal.tag` and
  `footer.tag`. The flatpickr `customScript` block is passed in from a same-file `t:fragment`, or
  `null`.
- `fragments/menu.html`: `menuItem(...)`, replaces `menuItem.tag`.
- `fragments/fields.html`: `inputField(String label, Field name, boolean hasValue)` and
  `petTypeSelectField(...)`, replacing `inputField.tag` and `selectField.tag`. The "ok" icon
  needs the field's value, which a `Field` fragment cannot read, so the caller passes
  `hasValue`. `status.errorMessage` (the first error) became `fields.errors(name).first`.
- `localDate.tag` became `temporals.format(date, pattern)`.
- Pages: `welcome`, `exception`, `vets/vetList`, `owners/{findOwners,ownersList,ownerDetails,createOrUpdateOwnerForm}`,
  `pets/{createOrUpdatePetForm,createOrUpdateVisitForm}`.
- Removed `src/main/webapp/WEB-INF/jsp/**` and `src/main/webapp/WEB-INF/tags/**`. Updated the
  readme's file pointers.

## Remaining differences (and why they are acceptable)

1. **`GET /owners/*/pets/{id}/visits`: recorded 404, now 500.** `VisitController.showVisits` returns
   the view `visitList`, which has never existed. The old 404 was Jetty's "JSP file
   [/WEB-INF/jsp/visitList.jsp] not found" page. Without JSP, Spring reports the unresolvable view
   as a 500. Either way it is a broken endpoint; neither engine ever rendered a page there. I did
   not invent a page for it, and I did not touch the controller or its test (which asserts the
   view name).
2. **`GET /owners/1/pets/999/edit`: 500 before and after.** A pet that doesn't exist puts
   `pet=null` in the model. The old app failed inside `inputField.tag` (JasperException) and the
   new one fails in the template (NullPointerException). The status matches; only the stack-trace
   text in Jetty's error page differs.
3. **A flaky page, not a template defect.** For `POST /owners/new` with an empty telephone, the
   field has two errors (`@NotEmpty`, `@Digits`). Both the JSP and the template show the first
   one, but Hibernate Validator returns violations from a `HashSet` whose order changes between
   JVM runs. Running compare repeatedly against the same app made that page alternate between
   `same` and `differs`; it reproduces the original's own nondeterminism. Its line stays in
   `pages.txt`.

Not kept: `GET /vets` with `Accept: application/xml` used to go through the
`ContentNegotiatingViewResolver` to the `vets/vetList.xml` `MarshallingView`. Tessera's resolver
matches names exactly and the CNVR was removed with the JSP configuration. `/vets.xml` and
`/vets.json` (the `@ResponseBody` endpoints) are unchanged.

## Where the guide or Tessera fell short

- **Without Boot, the guide is thin on XML-configured apps.** It shows the
  `TesseraConfiguration` bean but not how it interacts with an existing `<mvc:view-resolvers>` or
  `ContentNegotiatingViewResolver`. I read the starter's sources jar to confirm the resolver order
  (`LOWEST_PRECEDENCE - 20`, redirects at `-19`) and that `<mvc:bean-name/>` still runs after it.
- **There is no guidance for JSP/EL.** `tessera-migrate` only converts Thymeleaf, so all JSP and
  tag-file conversion was manual. The guide says JSP layout tags become fragments, but has nothing
  on `<jsp:attribute fragment="true">` (I used a named `t:fragment` passed as a `Fragment`) or
  `<spring:bind>`'s `status.*`.
- **`Field` fragments cannot read the field's value**, so tag files that branch on
  `status.actualValue` need an extra argument computed by every caller.
- **There is no "first error" helper.** `t:errors` joins all messages. To reproduce
  `status.errorMessage` I used `fields.errors(name).first`, which works but is not documented as
  valid on `fields.errors(...)`.
- **The `form:form` default action is only mentioned in one table row.** Edit forms whose id is a
  disallowed field can't rebuild the URL with `@{...}`, so a request-derived model attribute is the
  only option, and the guide doesn't say that.
- **Error pages without Boot are not covered.** The guide covers only Boot's `error.html`. For
  `SimpleMappingExceptionResolver` the exception isn't view-safe, and getting at its message takes
  Java code (a resolver subclass here).
- **A `null` model object fails the render with an NPE.** In the pet-not-found case the error
  page changed text. The guide documents this, but there is no way to guard a whole `t:object`
  form other than wrapping it in `t:if`, which would change behaviour.
- **Several syntax points were unclear.** It wasn't obvious whether `!`/`and` are accepted and
  whether `t:block` can carry `t:fragment` (the guide says `t:block` cannot be selected by tag
  name). Both worked.
- **The compare procedure has a trap.** The guide does say to start from the same data, but
  re-running `compare` against an already-used app gives many false `differs`, and that should be
  more prominent.
- **The parity check cannot help with JSP-specific error pages** (container 404/500 bodies with
  JSP compiler output). They can never be `same`.
