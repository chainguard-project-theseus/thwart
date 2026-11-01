# Migration report: JSP → Tessera

PetClinic (Spring Framework 7.0.9, XML configuration, no Spring Boot, Jetty 11 via `jetty:run-war`,
Java 17 target) rendered all its pages with JSP, JSTL, Spring's form tags and custom tag files. All
pages are now Tessera templates compiled during `javac`. JSP is gone: its templates, its dependencies
and its view resolver configuration.

## What changed

### Build (`pom.xml`)
- Removed the JSP and JSTL dependencies: `tomcat-jasper-el`, `jakarta.servlet.jsp.jstl-api` and
  `org.glassfish.web:jakarta.servlet.jsp.jstl`, along with their version properties.
- Added `dev.tessera:tessera-spring-boot-starter` (the `jakarta.servlet` artifact). Despite the
  name it needs only `spring-webmvc`; it doesn't pull in Boot.
- Added `tessera-compiler` as the annotation processor, with `-Atessera.templates`,
  `-Atessera.package=org.springframework.samples.petclinic.templates` and `-Atessera.messages` (the
  `messages/messages` bundle, so `#{welcome}` is checked at build time). Also added
  `tessera-maven-plugin` (`check` and `review` goals).
- Resource filtering stays on for `src/main/resources` but now skips `templates/**`.
- Added `org.glassfish.expressly:expressly` (provided scope). Diffing `mvn dependency:list` before
  and after showed that removing JSP also removed the only Jakarta EL on the test classpath.
  Hibernate Validator needs EL to interpolate constraint messages, so without it 23 JPA and
  validator tests failed. The servlet container supplies EL at runtime, as before. No other library
  the app uses came in through JSP.
- Updated the project description and the README table that pointed at the JSP and tag files.

### Spring configuration
- `mvc-view-config.xml`: replaced the `ContentNegotiatingViewResolver`, `JstlView` and the JSP
  `InternalResourceViewResolver` with `dev.tessera.spring.TesseraConfiguration`. That registers the
  Tessera view resolver and the `redirect:`/`forward:` resolver. The `BeanNameViewResolver` and the
  `vets/vetList.xml` marshalling view are kept, and so is the JAXB marshaller.
- New `NotFoundViewResolver` (last in order): any view name with no template answers **404** and
  renders nothing. This keeps the old behaviour for `VisitController.showVisits`, which returns the
  view `visitList` that never existed. JSP answered that with a 404 (missing JSP file). Without this
  resolver it would now be a 500 ("Could not resolve view").
- `mvc-core-config.xml`: the `SimpleMappingExceptionResolver` bean is now
  `ExceptionMessageResolver`, a subclass that also puts `exceptionMessage` (a `String`) in the
  model. The old page printed `${exception.message}`, but a template can't read an `Exception`.
  `CrashControllerTests` still autowires it as a `SimpleMappingExceptionResolver`.

### Java
- `@TemplateView` on the entities the pages read: `Owner`, `Pet`, `PetType`, `Visit`, `Vet`,
  `Vets`, `Specialty`. This is the guide's shortcut. Per-page view records would expose less, but
  the forms bind the entities anyway.
- `VisitController`: the GET mapping is now `/owners/{ownerId}/pets/{petId}/visits/new` (it was
  `/owners/*/...`). Both handlers now declare `@PathVariable("ownerId") String ownerId`, and
  `PetController.processUpdateForm` now declares `@PathVariable("petId") int petId`. The routes are
  unchanged, and a `String` still matches any segment, including the `*` the tests use. The
  parameters are there because a template sees only the path variables its handler declares, and
  the forms need them to post back to their own URL (see the Tessera notes below).

### Templates (`src/main/resources/templates`)

| JSP / tag file | Tessera |
|---|---|
| `tags/layout.tag`, `htmlHeader.tag`, `bodyHeader.tag`, `menu.tag`, `menuItem.tag`, `pivotal.tag`, `footer.tag` | `fragments/layout.html`: `layout(String pageName, Fragment content, Fragment customScript)`. Pages pass `~{::body}` with `t:remove="tag"`; the flatpickr includes are passed as `~{::page-script}`. |
| `tags/inputField.tag`, `tags/selectField.tag` (`spring:bind`) | `fragments/fields.html`: `inputField(String label, Field name)` and `selectField(String label, Field name, Collection<PetType> names, int size)`, using `t:field`, `fields.hasErrors`, `fields.value` and `fields.errors(name).first`. |
| `tags/localDate.tag` | `temporals.format(date, 'yyyy-MM-dd')` / `'yyyy/MM/dd'` |
| `welcome.jsp`, `exception.jsp`, `vets/vetList.jsp`, `owners/*.jsp`, `pets/*.jsp` | The same paths with `.html`, so every view name is unchanged and no controller test changed. |

- `<form:form>` without an action posted back to the current request URI. The templates now write
  that URL with `@{...}` from the path variables.
- `<form:errors path="*"/>` became `<span id="owner.errors" t:errors="*">`.
- `<form:select items>` became `t:each` over `<option>`.
- Template-only comments (`<%-- --%>`) became `<!--/* */-->`.
- `ViewRenderingTests` (new) renders through the real resolvers. It checks that `<script>` and
  markup in owner data come out escaped, that rejected form values are echoed escaped, that the
  error page shows the exception message and that an unknown view gives a 404. The existing
  controller tests use `standaloneSetup` and never render a view.

## Verification
- **Build and tests:** `mvn clean verify` passes on JDK 21 (the installed default; the project
  targets Java 17). 79 tests run: the 75 existing ones, none changed, plus the 4 new ones.
- **Parity:** `pages.txt` holds 64 requests covering:
  - every page: home, vets (plus `.json`/`.xml`), the find form, the list, a search that redirects,
    owner details, the owner/pet/visit forms, the error page, 400 and 404;
  - the form states: empty, with errors, with type-mismatch errors, after success;
  - edge cases: hostile input such as `<script>`, quotes and `&`, empty and full lists, a
    non-existent owner or pet.

  I recorded the original app with `tessera-migrate record` (into `baseline/`), then ran `compare`
  against the migrated app started the same way (`mvn jetty:run-war`, fresh H2).
  **60 of 64 are `same`, and every status code and final URL matches.** The remaining four:

  | # | Request | Why it differs |
  |---|---|---|
  | 15 or 21 | `POST /owners/{1/edit,new}` with every field empty | **Random in the old app too.** An empty telephone breaks both `@NotEmpty` and `@Digits`. Hibernate Validator returns violations in a `HashSet`, and the page shows the first error. Requesting the original app six times showed "must not be empty" and "numeric value out of bounds" alternating. In each run one of these two pages differs; which one varies. |
  | 49 | `POST .../visits/new` with an empty date | **Both apps return 500.** `Pet.getVisits()` sorts by date and throws on the `null` date (an application bug). The status matches; only Jetty's error page text differs, because it quotes the exception chain (`JasperException ... createOrUpdateVisitForm.jsp(58,12)` before, the same `NullPointerException` in Tessera's render now). |
  | 56 | `GET /owners/1/pets/1/visits` (`visitList` was never written) | **Both apps return 404.** Jetty's text was "JSP file [/WEB-INF/jsp/visitList.jsp] not found" from servlet `jsp`; now it's "Not Found" from `dispatcher`. Copying the JSP message would mean faking a JSP that doesn't exist. |
  | 61 | `GET /owners/1/pets/9999/edit` (no such pet) | **Both apps return 500**, because the `pet` model attribute is `null`. Only Jetty's quoted exception text differs (a JSP tag-file excerpt before, a `NullPointerException` now). |

  If the checker's request list includes any of these four, `compare` will report `differs` there,
  for the reasons above.
- **Old engine gone:** no `.jsp`/`.tag` files remain. `mvn dependency:list` shows no JSP, JSTL or
  Jasper artifact. No JSP or `ContentNegotiating` view resolver is configured, and startup logs no
  `another template engine is still configured` warning.
- **Security:** `target/tessera-review.txt` lists 0 `@TemplateFunctions` and 0
  `SafeHtml.unchecked`/`TrustedUrl.unchecked` calls. No `t:utext` is used. Nothing was added to
  `permitAll`, and no tests were disabled. The app has no Spring Security, so there is no CSRF
  token, as before. The old pages printed `${status.errorMessage}` (validation messages such as
  `(<10 digits>.<0 digits> expected)`) and `${exception.message}` raw; both are now escaped. That
  is intended, and the DOM is the same in every recorded case.

## Could not convert / left as is
Every page was converted. Two application bugs are kept so behaviour stays the same:
- a visit posted with an empty date returns 500;
- the `visitList` view doesn't exist.

## Where Tessera or its documentation fell short
1. **No JSP converter.** `tessera-migrate` rewrites only Thymeleaf. For JSP the guide gives a few
   bullet points (section 4.10), and the whole port is manual.
2. **A POST form's action can't choose between two same-origin links.** For example,
   `t:action="id == null ? @{/owners/new} : @{/owners/{id}/edit(id=id)}"` is rejected, although
   both branches are literal-path links. The guide's own `?:`/ternary rules for `Fragment` and
   neutral attributes allow such choices. As a result the owner and pet forms are written twice
   under `t:if`/`t:unless`. JSP's `<form:form>` default (post to the current request URI) has no
   equivalent except omitting `action`, and that changes the markup.
3. **Path variables are visible only when a handler declares them.** A URI template variable that
   is in the mapping but not a handler parameter (`/owners/{ownerId}/pets/{petId}/visits/new`
   before the change) arrives as `null`, which fails a link at render time. The guide says
   "the handler's `@PathVariable` values", but the error ("link path variable 'ownerId' must not be
   null") doesn't point there. I had to add parameters the handler doesn't use. A variable
   declared only on a `@ModelAttribute` method of the same controller did seem to be visible
   (`ownerId` in the pet form), which isn't documented.
4. **No 404 for unknown view names without Boot.** Spring then throws "Could not resolve view"
   (500), where the JSP resolver produced a 404. Section 2 doesn't mention this for apps that
   leave JSP; I wrote a small `NotFoundViewResolver`.
5. **No support statement for plain Spring 6/7 without Boot.** Section 2 lists Boot 1.5–4.1 and
   plain Spring MVC 4.3/5.0 as tested. Plain Spring 7 (this app) worked with
   `TesseraConfiguration` declared as an XML bean.
6. **Exception messages need Java glue.** Nothing in the starter exposes an exception message to
   the `SimpleMappingExceptionResolver` error view, which is a very common JSP pattern. The guide
   covers only Boot's error attributes.
7. **Lost EL implementation.** Section 3 step 6 says to diff the dependency list, but doesn't name
   the most likely casualty for a JSP app: removing Jasper EL breaks Hibernate Validator's message
   interpolation (tests fail with "Unable to build the default ValidatorFactory").
8. **Error-page parity can't be reached.** When the old app failed while rendering a view, the
   container's error page quotes engine-specific exception text, so `compare` can never call those
   pages `same`. It would help if the tool could compare only status and URL for 5xx pages, or
   said so.

## Files for reproducing the check
- `pages.txt`: the request list.
- `baseline/`: the recording of the original app.
- `.tessera/tessera-migrate.jar`: git-ignored, copied from the local Maven repository.
