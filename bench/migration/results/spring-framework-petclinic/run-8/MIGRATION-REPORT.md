# Migration report: JSP → Tessera

Spring Framework Petclinic (Spring 7.0, plain Spring MVC with XML configuration, `jakarta.servlet`,
Java 17, war run with `mvn jetty:run-war`). Every HTML page is now rendered by a compiled Tessera
template. JSP, JSTL, the tag files and the JSP view-resolver configuration are gone.

## What changed

### Build (`pom.xml`)
- Removed `tomcat-jasper-el`, `jakarta.servlet.jsp.jstl-api` and `org.glassfish.web:jakarta.servlet.jsp.jstl`
  (and their version properties).
- Added `dev.tessera:tessera-spring-boot-starter:0.1.0-SNAPSHOT` (the `jakarta.servlet` artifact; no
  Spring Boot is used, see below) and `tessera-compiler` as the annotation processor in
  `maven-compiler-plugin`, with `-Atessera.templates`, `-Atessera.package` and `-Atessera.messages`
  (so every `#{key}` is checked against `messages/messages.properties` at build time).
- `src/main/resources` is filtered, so `templates/**` is excluded from resources. The templates are
  compiled into classes and are not needed at runtime.
- Added `org.glassfish.expressly:expressly` (`provided`). `tomcat-jasper-el` was also the
  Expression Language implementation that Hibernate Validator uses to interpolate messages. Without
  it, `ValidatorFactory` can't start in the tests and the JPA, validator and controller tests fail.
  It stays `provided`, as before: the servlet container supplies EL at runtime.
- No Spring or Java version changes. The build still targets Java 17.

### Spring configuration
- `mvc-view-config.xml`: removed the `ContentNegotiatingViewResolver`, whose only default view was
  a `JstlView`, and the `<mvc:jsp>` resolver. Added `<bean class="dev.tessera.spring.TesseraConfiguration"/>`,
  which registers the Tessera view resolver and the `redirect:`/`forward:` resolver. The
  `BeanNameViewResolver` and the JAXB `MarshallingView` are kept.
- Added `web/MissingViewResolver` as the last resolver: a view name with no template gets a 404.
  `VisitController.showVisits` returns `visitList`, which never existed as a JSP; the old app
  answered 404 because Jetty's JSP servlet couldn't find the file. Without this resolver the new app
  would answer 500 ("Could not resolve view"). `VisitControllerTests.testShowVisits` pins the
  controller's view name and model, so the controller is unchanged.
- `mvc-core-config.xml`: the exception resolver is now `web/PetclinicExceptionResolver`, a
  `SimpleMappingExceptionResolver` subclass that also puts `exceptionMessage` (a `String`) in the
  model, because templates can't read an `Exception`. The `exception` attribute is still there,
  so `CrashControllerTests` passes unchanged.

### Java
- `@TemplateView` on the classes the templates read: `Owner`, `Pet`, `PetType`, `Visit`, `Vet`,
  `Specialty`, `Vets`. Getters inherited from the unannotated `@MappedSuperclass`es resolve through
  them.
- `web/FormActionAdvice` (`@ControllerAdvice`): puts `formAction` (the originating request URI plus
  the query string) in the model. Spring's `<form:form>` posted back to the current URL when it had no
  `action`; a Tessera template can't see the request.

### Templates (`src/main/resources/templates`)
| Old | New |
|---|---|
| `tags/layout.tag`, `htmlHeader.tag`, `bodyHeader.tag`, `menu.tag`, `pivotal.tag`, `footer.tag` | `fragments/layout.html` (`layout(String pageName, Fragment content, Fragment customScript)`) |
| `tags/menuItem.tag` | `fragments/menu.html` (`menuItem(...)`) |
| `tags/inputField.tag`, `tags/selectField.tag` | `fragments/fields.html` (`input(...)`, `selectType(...)` with `Field` parameters) |
| `tags/localDate.tag` | `temporals.format(date, pattern)` |
| `jsp/welcome.jsp`, `exception.jsp` | `welcome.html`, `exception.html` |
| `jsp/owners/*.jsp` | `owners/findOwners.html`, `ownersList.html`, `ownerDetails.html`, `createOrUpdateOwnerForm.html` |
| `jsp/pets/*.jsp` | `pets/createOrUpdatePetForm.html`, `createOrUpdateVisitForm.html` |
| `jsp/vets/vetList.jsp` | `vets/vetList.html` |

All JSP files under `WEB-INF/jsp` and `WEB-INF/tags` are deleted. `readme.md` now points to the
templates instead of the deleted JSP files.

### Tests
- No existing test was changed, disabled or deleted.
- Added `web/ViewRenderingTests`, which renders through the real view resolvers (the existing
  controller tests use `standaloneSetup` and never render). It checks that `<script>` in an owner
  name comes out escaped, that page-relative links and the form's post-back action are right, that
  validation errors render, that the exception page shows the message, and that a view with no
  template gives 404.
- `mvn clean verify` passes on JDK 21, the default: 14 test classes, 79 tests, 0 failures. I couldn't run
  JDK 17, which the project targets and CI also uses, because it isn't installed here. Tessera's
  generated code compiles to Java 8 class files, so it isn't expected to matter.

## Page parity

`pages.txt` has 48 requests in order, starting from the seeded H2 database: home, vets, the
`/oups` exception page, the find form (empty, not found, one result → redirect, many results), all
owners, several owner details pages, a missing owner (exception page), 400 and 404 container
errors, the missing `visitList` view, and the owner, pet and visit forms. Each form is covered
empty, with validation errors (empty fields, a bad number, a bad date, a duplicate pet name, a
missing type), and after success and redirect. The data includes `<script>`, quotes and `&` to
check escaping. The baseline recorded from the original app is in `baseline/`.

A second, ad-hoc list (not committed) recorded the original app from a separate checkout. It
covered: missing owners in the edit and new-pet URLs, a pet form missing its type, a telephone that
is too long, a visit with an empty date, `/vets.json`, `/vets.xml`, owner searches with partial and
`<script>` names, and edits to existing pets. All 20 of those pages came out `same`. With
`Accept-Language: de`/`es`, the welcome heading and the validation messages were identical in both
apps. Response headers also match (`Content-Type: text/html;charset=utf-8`, `Content-Language`).

Final `compare` run on `pages.txt`: 45 `same`, 3 `differs`. Each difference is explained below.

1. **`GET /owners/1/pets/1/visits` (17): status matches (404), but the body differs.** The old 404
   page is Jetty's error page from the JSP servlet ("JSP file [/WEB-INF/jsp/visitList.jsp] not
   found", `SERVLET: jsp`). Only a forward into Jetty's JSP servlet can reproduce that text, and that
   would keep the JSP machinery alive. The new app answers with Jetty's standard 404 page ("Not
   Found", `SERVLET: dispatcher`). I think that's the right result for a page that never existed.
2. **`POST /owners/new` with all fields empty (19) and `POST /owners/11/edit` with an empty
   telephone (25): the telephone's error message is random in the old app too.** An empty telephone
   fails both `@NotEmpty` and `@Digits`. Hibernate Validator returns the violations in a `HashSet`
   whose order depends on the bean's identity hash code, so the order changes on every request. The
   old `inputField.tag` printed the first message (`status.errorMessage`), and the new fragment does
   the same (`fields.errors(name).first`). Sending the same POST 12 times to the *original* app gave
   "must not be empty" 6 times and "numeric value out of bounds…" 6 times. Depending on the run,
   these pages come out `same` (both were `same` in my first comparison) or `differs`. Every other
   part of these pages is identical. I didn't change the validation code to force an order: that is
   outside the views, and it still wouldn't match the old app's random choice more than half the
   time.

Not in `pages.txt`: `GET /owners/1/pets/999/edit`. In the old app this is a Jetty 500 page that dumps
the JSP stack trace from `inputField.tag`, because the pet is `null`. The new app also fails with
500, from a `NullPointerException` in the template, but the error text differs. Matching it would
mean reproducing a JSP compiler error page.

## Security
- No `SafeHtml.unchecked` or `TrustedUrl.unchecked`, no new `permitAll`. The application has no
  Spring Security, so CSRF is unchanged (none before, none now), and no escaping is turned off.
- Values the JSPs printed raw are now escaped: `${label}` and `${status.errorMessage}` in the tag
  files (for example, `<10 digits>` in the Digits message), and `${exception.message}` on the error
  page. The DOM is unchanged for current data; for hostile data it's now safe.

## Where Tessera or its documentation fell short
- **Missing views without Boot.** The guide says unknown view names "fall through to other
  resolvers". It doesn't say what happens in plain Spring MVC when nothing else resolves them: a 500
  where the JSP setup gave a 404. I had to write `MissingViewResolver`.
- **Exception pages without Boot.** Section 2 covers error pages only under Boot
  (`templates/error.html` with Boot's error attributes). For `SimpleMappingExceptionResolver`,
  which puts an `Exception` in the model, nothing is provided. I subclassed the resolver to expose
  the message as a `String`.
- **Default form action.** Spring's form tag posted back to the current URL. Tessera needs the URL in
  the model, so every app ends up writing the same `@ControllerAdvice` (`UrlPathHelper` originating
  URI plus query string). The guide's table names the problem but gives no ready-made helper; a
  built-in `formAction`/request-URI value from the starter would help.
- **`Field` fragments can't read the field's value.** The old tag showed an "ok" icon when the value
  was non-empty (`status.actualValue`). Every call site now passes a separate `filled` boolean
  (`!strings.isEmpty(owner.firstName)`, `pet.birthDate != null`), which is repetitive and easy to get
  wrong.
- **First error only.** `t:errors` joins all messages with `<br />`. Showing only the first (as
  `status.errorMessage` did) took `fields.errors(name).first`, which works but isn't documented in
  section 4.7. The guide also doesn't warn that error order from Bean Validation is not
  deterministic, which makes `compare` results for multi-constraint fields flaky.
- **Selecting a `<t:block>` fragment.** Section 7 says `~{::tag}` can't select `t:block`, but a
  `<t:block t:fragment="customScript">` selected by its fragment name worked, which is what I used
  for the page-specific scripts. The documentation is ambiguous here.
- **Side effects of dropping JSP.** Removing the JSP dependencies also removed the EL
  implementation Hibernate Validator needs (`tomcat-jasper-el`). The guide's "remove the old engine"
  step doesn't mention that view dependencies can carry other runtime roles.
- **Tested versions.** The guide lists plain Spring MVC 4.3 and 5.0 and Boot up to 4.1 as tested.
  This app is plain Spring MVC **7.0** with XML configuration, which isn't listed. It worked unchanged
  with `TesseraConfiguration`, but the support matrix doesn't say so.
- **Error pages from the container.** `tessera-migrate compare` can't tell the app's pages from the
  servlet container's own error pages. Differences there, like the JSP servlet's 404 above, can't
  be masked narrowly; `--ignore` would hide the whole message.
- **Install pointer.** The skill says to install Tessera from `github.com/chainguard-dev/harder` if
  it's missing. It was already in the local repository here, so I didn't need that path.

## How to reproduce
```sh
mvn clean verify
mvn jetty:run-war    # http://localhost:8080
mvn -q dependency:copy -Dartifact=dev.tessera:tessera-migrate:0.1.0-SNAPSHOT -Dmdep.stripVersion=true -DoutputDirectory=.tessera
java -jar .tessera/tessera-migrate.jar compare http://localhost:8080 pages.txt baseline/
```
