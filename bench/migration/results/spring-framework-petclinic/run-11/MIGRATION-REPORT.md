# Migration report: JSP → Tessera

The application (Spring Framework Petclinic: Spring MVC 7 with XML configuration, packaged as a war and
run with `./mvnw jetty:run-war`) rendered its pages with JSP, JSTL, Spring's form tags and tag files.
Every page is now a Tessera template compiled during `javac`. The JSPs, tag files, JSTL dependencies and
JSP view-resolver configuration are gone.

## What changed

### Build (`pom.xml`)
- Removed `jakarta.servlet.jsp.jstl-api`, `org.glassfish.web:jakarta.servlet.jsp.jstl` and
  `org.apache.tomcat:tomcat-jasper-el`.
- `tomcat-jasper-el` also supplied the Expression Language implementation that Hibernate Validator uses
  to interpolate messages, and `ValidatorTests` relies on it. It is replaced by
  `org.glassfish.expressly:expressly` 6.0.0, `provided` like the jar it replaces (at runtime the
  container provides EL, as before).
- Added `dev.tessera:tessera-spring-boot-starter` (jakarta), `tessera-compiler` as the annotation
  processor (`-Atessera.templates`, `-Atessera.package=org.springframework.samples.petclinic.templates`,
  `-Atessera.messages=src/main/resources/messages/messages`), and `tessera-maven-plugin:check`.
- `src/main/resources` is filtered by Maven, so `templates/**` is excluded from filtering. Otherwise
  Maven would rewrite `${...}` inside the templates.
- No Spring, JDK or plugin versions changed. The build still targets Java 17.

### Spring configuration
- `mvc-view-config.xml`: removed the `ContentNegotiatingViewResolver` (its default view was a
  `JstlView`) and the `<mvc:jsp>` resolver. Added `dev.tessera.spring.TesseraConfiguration`, which brings
  the Tessera view resolver plus a `redirect:`/`forward:` resolver. Kept the `BeanNameViewResolver` and
  the JAXB `MarshallingView`, which are not JSP, ordered after Tessera.
- `MissingViewResolver` (new, last in the chain): if no template provides a view name, the response is
  404. The JSP resolver behaved the same way, because it resolved every name and the container answered
  404 for a missing file. Without this, `GET /owners/*/pets/{petId}/visits` (whose `visitList` view never
  existed) would change from 404 to a 500 "Could not resolve view".
- `mvc-core-config.xml`: `SimpleMappingExceptionResolver` is replaced by its subclass
  `ExceptionMessageResolver`. It also puts the exception's message in the model as
  `exceptionMessage`, because templates cannot read a `Throwable`.
- `PathVariablesAdvice` (new `@ControllerAdvice`): copies the request's URI template variables
  (`ownerId`, `petId`) into the model. The forms use them to post back to the URL they were rendered
  from, as `<form:form>` without an `action` did (details under "Shortfalls").

### Model
- `@TemplateView` on the entities the pages read: `Owner`, `Pet`, `PetType`, `Specialty`, `Vet`, `Vets`,
  `Visit`. No getters were added or changed.

### Templates (`src/main/resources/templates`)
| JSP / tag file | Tessera |
|---|---|
| `tags/layout.tag`, `htmlHeader.tag`, `bodyHeader.tag`, `menu.tag`, `menuItem.tag`, `pivotal.tag`, `footer.tag` | `fragments/layout.html` as `layout(Fragment content, String pageName, Fragment customScript)`. The four menu items are written out, because their link paths must be literals. |
| `tags/inputField.tag`, `tags/selectField.tag` | `fragments/fields.html` as `inputField(String label, Field name)` and `selectField(String label, Field name, Collection<PetType> names)` |
| `tags/localDate.tag` | `temporals.format(date, pattern)` |
| `welcome.jsp`, `exception.jsp`, `owners/*.jsp`, `pets/*.jsp`, `vets/vetList.jsp` | Same names, `.html` |

The old markup is reproduced exactly: the `id` that `<form:form>` wrote (the model attribute name), the
`id="owner.errors"` of `<form:errors path="*">`, the `class="form-group "` with its trailing space, the
`value=""` of hidden inputs whose value is null, and the null-safe owner name on the pet form (the
owner is null after a failed "new pet" POST). Text that JSP printed raw (`${label}`,
`${status.errorMessage}`, `${exception.message}`) is now escaped.

### Tests
- No existing test was changed. The controller tests use `standaloneSetup`, so they never render a
  view; they don't touch JSP and needed no update.
- Added `ViewRenderingTests`. It renders pages through the real resolver chain: welcome, vets, owner
  details (and checks that HTML is escaped), the owner form with errors and its post-back URL, the pet
  form with the selected type and formatted date, the visit form, the exception page and the missing-view
  404.
- `mvn clean install` on JDK 21: 83 tests, 0 failures. Before the migration there were 75.

## Page parity

`.tessera/pages.txt` (57 requests, not committed) covers every page and state: the home page, the vets
list, the error page (`/oups`), and find-owners empty, not found, single result (redirect) and multiple
results. It covers the owner details page with and without pets, and the owner, pet and visit forms
empty, with validation errors (missing values, bad dates, a duplicate pet name, out-of-bounds digits),
after success and on edit. It also covers HTML in user input, `/owners/abc` (400), `/owners/999`, a
missing pet, the broken `visitList` page, an unknown URL, and `/vets.json`/`/vets.xml`.

Both apps were started with `mvn jetty:run-war` from the same in-memory H2 seed data. The original was
recorded with `tessera-migrate record` and the new app checked with `tessera-migrate compare`: **52
same, 1 reordered, 4 differ**. Every page has its recorded status and final URL. The ones that don't
come out `same`:

| # | Request | Why it remains |
|---|---|---|
| 49 | owner 1 after adding a second pet named "Leo" (`reordered`) | `Owner.getPets()` sorts by name, so two pets with the same name come out in `HashSet` order. The original's order is just as arbitrary. Harmless. |
| 14 | `POST /owners/new` with every field empty | The telephone field has two errors (`@NotEmpty` and `@Digits`). The page shows the first, and Hibernate Validator returns them in random order. The **original** gives either message from one request to the next (checked with 8 identical requests: 5 vs 3). So this page matches about half the time in either app. |
| 32 | `POST .../visits/new` with an empty date | Bug in the original: `Pet.getVisits()` sorts by date and throws an NPE on the new visit's null date. Both apps give **500** from Jetty's error page, but the original's error text names Jasper and the JSP file and line. That text can't be reproduced without JSP. I kept the 500 rather than fix the bug, because the task asks for the recorded status. |
| 50 | `GET /owners/1/pets/999/edit` | Same situation: a null pet gives a 500 in both apps. Only Jetty's error text differs (a JSP `JspTagException` vs an NPE in the template). |
| 35 | `GET /owners/6/pets/7/visits` | The `visitList` view never existed. Both apps return Jetty's **404** page, but the old message was `JSP file [/WEB-INF/jsp/visitList.jsp] not found` from the `jsp` servlet and the new one is `Not Found` from `dispatcher`. I didn't fake the old message. |

The 404 for an unknown URL, the 400 for `/owners/abc`, the exception page for `/owners/999` and the 406
for `/vets.json`/`/vets.xml` with a browser `Accept` header all come out `same`.

## Could not convert / behaviour notes
- Nothing was left on JSP.
- Removing the `ContentNegotiatingViewResolver` (`use-not-acceptable="true"`) changes one case no
  browser triggers: an HTML page such as `/vets` requested with only `Accept: application/json` used to
  answer 406, and now renders the HTML. The JSON and XML endpoints (`/vets.json`, `/vets.xml`) are
  `@ResponseBody` handlers and are unchanged.
- Security: no Spring Security in this app, so no `permitAll` or CSRF settings were touched. No
  `TrustedUrl.unchecked`, `SafeHtml` or `t:utext` is used; all output is context-escaped.

## Where Tessera or its documentation fell short
1. **Path variables reach a template only if the handler method declares them.** The guide (section 1)
   says parameters come "from the handler's `@PathVariable` of that name". In practice `TesseraView`
   reads `View.PATH_VARIABLES`, which Spring fills only for `@PathVariable` arguments the method
   actually declares. Two cases miss out: variables of a class-level mapping
   (`@RequestMapping("/owners/{ownerId}")` on `PetController`, whose `initCreationForm` doesn't take
   `ownerId`), and a GET mapped with a wildcard (`/owners/*/pets/{petId}/visits/new`). Their
   parameters are silently `null`. I needed a `@ControllerAdvice` that copies
   `HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE` into the model, as Strings.
2. **No equivalent of `<form:form>`'s default `action`.** The JSP note says omitting `action` is
   equivalent because the browser posts to the page URL. But the DOM then lacks the attribute and
   `compare` reports every form page as `differs`. Reproducing it meant a choice between literal links
   per form (new vs edit), built from the path variables of point 1. A built-in for "the current request
   path" usable in `action` would have avoided this.
3. **`<form:form>` also writes `id="<modelAttribute>"`.** The JSP notes mention the `id` of
   `<form:errors>` but not this one. `compare` caught it.
4. **Missing views behave differently once JSP is gone.** `InternalResourceViewResolver` resolves every
   name, so a view without a JSP file used to be a 404. Without it, Spring throws "Could not resolve
   view" (500). The guide doesn't mention this. I added a last-resort resolver that answers 404.
5. **Error pages without Spring Boot.** The guide covers Boot's `error.html` attributes only. For a
   plain-Spring `SimpleMappingExceptionResolver`, whose model holds a `Throwable` (not view-safe), there
   is no recipe. I had to subclass the resolver to expose the message.
6. **JSP's EL dependency also served Bean Validation.** Removing `tomcat-jasper-el` as "the JSP
   dependency" would break Hibernate Validator's message interpolation (`ValidatorTests`). The guide's
   advice to diff `mvn dependency:list` is general; a JSP-specific warning would help.
7. **No old-engine warning for JSP.** The starter's `Tessera: another template engine is still
   configured` check doesn't detect JSP. This is documented, but it means the startup safety signal
   doesn't exist for JSP applications: only removing the resolver by hand closes the gap.
8. **`tessera-migrate` rewrites Thymeleaf only.** JSP, tag files and Spring form tags were converted by
   hand. There was no report of out-of-model reads (here: `pageContext.request.contextPath`, the
   `exception` attribute, and class-level path variables).
9. **Container error pages can't be compared.** When a render fails (here because of bugs in the
   original), the servlet container's error page includes the old engine's exception chain, so
   `compare` can never report `same` for those pages, even with matching status and URL. A
   `compare` option to check only the status of container error pages would help.
10. Small things: `fields.errors(path)` returns all messages, and the JSP `status.errorMessage` (first
    message) is `fields.errors(name)[0]`. The guide doesn't spell this out. The `[[...]]` and
    `t:text` null rules (`|${a} ${b}|` prints `null`) meant writing `(x?.y ?: '')` where JSP EL was
    null-safe.

## Files
- New: `src/main/resources/templates/**`,
  `web/ExceptionMessageResolver.java`, `web/MissingViewResolver.java`, `web/PathVariablesAdvice.java`,
  `src/test/.../web/ViewRenderingTests.java`.
- Changed: `pom.xml`, `mvc-view-config.xml`, `mvc-core-config.xml`, the seven model classes listed above,
  `readme.md`, `.gitignore` (ignores `.tessera/`).
- Deleted: `src/main/webapp/WEB-INF/jsp/**`, `src/main/webapp/WEB-INF/tags/**`.
