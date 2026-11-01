# Migration report: JSP/JSTL → Tessera

## Result

| Check | Status |
|---|---|
| All pages rendered by Tessera | Yes. 11 templates under `src/main/resources/templates` (Tessera logs "Found 11 compiled Tessera templates" at startup). |
| Old engine removed | Yes. All JSPs (`WEB-INF/jsp`) and tag files (`WEB-INF/tags`) are deleted. The JSP/JSTL dependencies, the `mvc:jsp` / `JstlView` / content-negotiation view config are gone too. There is no "another template engine is still configured" warning. |
| `mvn clean verify` on JDK 8 | Passes: 67 tests, i.e. the 62 existing tests (none changed) plus 5 new rendering tests. |
| Page parity (`tessera-migrate compare`, 49 requests in `pages.txt`) | 48 `same`. The 49th, `GET /owners/1/pets/1/visits`, has the recorded status (404) and final URL, but Tomcat's error-page text differs. See below. |
| Security | No `SafeHtml.unchecked` / `TrustedUrl.unchecked` (`target/tessera-review.txt` lists 0). No `permitAll`, no CSRF or escaping changes (the app has no Spring Security). No tests disabled. |

The baseline was recorded from the unmodified app, started with `mvn tomcat7:run` on JDK 8 (`http://localhost:9966/petclinic`, fresh in-memory HSQLDB). `pages.txt` covers:
- every view;
- the owner, pet and visit forms empty, with validation errors (missing fields, bad numbers, bad dates, unknown pet type, duplicate pet name) and after a successful submit or redirect;
- owner search with no results (form error), one result (redirect) and many results;
- HTML-special characters in user data;
- the exception page, both from `/oups` and from a missing owner (`/owners/999`);
- the 404 views.

The recording is in `baseline/`.

## What changed

**Build (`pom.xml`)**
- `java.version` goes from 1.7 to 1.8, because Tessera needs Java 8+. The build is run with JDK 8. `maven-compiler-plugin` goes from 3.0 to 3.8.1 (for `annotationProcessorPaths` and `<parameters>`).
- Added the `tessera-compiler` annotation processor with `-Atessera.templates`, `-Atessera.package=org.springframework.samples.petclinic.templates` and `-Atessera.messages` (message keys are checked at build time). Also added `tessera-maven-plugin` (`check`, `review`).
- Added `tessera-spring-boot-starter-javax`.
- Removed `javax.servlet.jsp-api`, `javax.servlet.jsp.jstl-api`, `taglibs-standard-jstlel` and `tomcat-jasper-el`.
- From the `dependency:list` diff: `tomcat-jasper-el` had also supplied the Expression Language that Hibernate Validator uses to build its messages ("may not be empty"). It is replaced by `org.glassfish:javax.el` in `provided` scope (Tomcat provides EL at runtime).
- `templates/**` is excluded from resource filtering, so Maven doesn't rewrite `${...}` in templates.

**Spring configuration**
- `mvc-view-config.xml`: the content-negotiating, JSP and `JstlView` resolvers are replaced by `dev.tessera.spring.TesseraConfiguration`, which also resolves `redirect:`/`forward:`. The `BeanNameViewResolver` and the `vets/vetList.xml` `MarshallingView` stay as they were (they are not a template engine).
- New `MissingViewResolver` (last in the chain). A view name with no template now gives a 404 for the requested URL, as the JSP resolver did by forwarding to a missing JSP. Without it the dead `/owners/*/pets/{petId}/visits` handler (view `visitList`, which never existed) became a 500.
- `PetclinicExceptionResolver` (a subclass of `SimpleMappingExceptionResolver`, configured as before) adds the exception's message as `exceptionMessage`. The template can't read the `Exception` object. The `exception` attribute is still present, so `CrashControllerTests` is unchanged.

**Java**
- `@TemplateView` on the entities the templates read and bind: `Owner`, `Pet`, `PetType`, `Visit`, `Vet`, `Vets`, `Specialty`. This is the guide's shortcut. Per-page view records would mean rewriting the forms' binding and the existing controller tests, which assert on the entity model attributes.
- `PetclinicFunctions` (`petclinic.birthDate(pet, pattern)`, `petclinic.visitDate(visit, pattern)`) replaces `fmt:formatDate`, because `java.util.Date` is not view-safe.
- `PetController.processUpdateForm` now declares `@PathVariable("petId")`. Spring's `form:form` posted back to the current URL; the template has to write that URL with `@{...}`, and Tessera only exposes path variables that the handler declares.

**Templates** (one per JSP, same view names)
- `fragments/layout.html` merges `layout.tag`, `htmlHeader.tag`, `bodyHeader.tag`, `menu.tag`, `menuItem.tag`, `pivotal.tag` and `footer.tag`. It takes `layout(String pageName, Fragment content, Fragment customScript)`.
- `fragments/fields.html` replaces `inputField.tag` and `selectField.tag`, using `Field` parameters with `fields.hasErrors`, `fields.value` and `fields.errors(...).first`.
- `welcome`, `exception`, `owners/{findOwners,ownersList,ownerDetails,createOrUpdateOwnerForm}`, `pets/{createOrUpdatePetForm,createOrUpdateVisitForm}` and `vets/vetList` are pages that call the layout.
- Raw JSP EL output is now escaped:
  - `${exception.message}`;
  - the hidden `id` / `petId` values;
  - the tag-file labels.

  None of the recorded pages changed because of it.

**Tests**: no existing test was changed. Added `ViewRenderingTests`. It renders through the real resolvers and checks:
- `<script>` in an owner's name comes out escaped, both in text and in a bound input;
- the post-back action and the relative links;
- the welcome and exception pages;
- a view without a template gives a 404.

**Docs**: `readme.md` and a `pom.xml` comment no longer point at the deleted JSP/tag files.

## What doesn't match, and why

1. **`GET /owners/1/pets/1/visits`: status 404 and final URL match, body text differs.** The old 404 was Tomcat's page for the JSP that was missing, `/petclinic/WEB-INF/jsp/visitList.jsp`. The new 404 is Tomcat's page with the requested URL (`/petclinic/owners/1/pets/1/visits`), which is how Tomcat reports any other missing resource. The handler's view never existed. Making the text identical would mean writing a non-existent JSP path into the new app, so I left it.
2. **`POST /owners/new` with every field empty is not deterministic, in the original app too.** `telephone` breaks two constraints (`@NotEmpty`, `@Digits`), and the page shows the first error (`spring:bind`'s `status.errorMessage`, now `fields.errors(name).first`). Hibernate Validator returns the violations as a `HashSet` whose hash includes the bean's identity hash code, so which one comes first changes from run to run. Across five fresh runs this page came out `same` twice and `differs` three times. When it differed, the only change was "may not be empty" in place of "numeric value out of bounds". The template doesn't choose the order.

## Where Tessera or its documentation fell short

- **No JSP-specific tooling.** `tessera-migrate` rewrites Thymeleaf only. All JSP, JSTL and tag-file conversion was by hand. The guide's JSP section is a short list of mappings.
- **Not documented: `form:form` posts back to the current URL by default.** The guide says to "write the action with `@{...}`". But a computed POST action may not be a conditional between two same-origin links: `cond ? @{/a} : @{/b}` is rejected with the same "t:action is not allowed" error as a non-link. Create-or-update forms therefore need two whole `<form>` elements under `t:if`/`t:unless` with duplicated bodies. A same-file fragment can't share the body either, because the fields need the enclosing `t:object`.
- **Path variables reach the template only if the handler declares them.** That is "the handler's `@PathVariable` values". URI template variables matched by the mapping, or resolved only in an `@ModelAttribute` method, are not visible. On `POST /owners/{ownerId}/pets/{petId}/edit` the param `Integer petId` was silently `null`, and the page rendered the wrong action with no error. I had to add an unused `@PathVariable` to the controller.
- **No fallback for unknown views.** When Tessera falls through and no other resolver remains, Spring answers 500 where JSP setups answered 404. The guide mentions the `redirect:`/`forward:` resolver but not this. I had to write a small last-resort resolver.
- **No `java.util.Date` support for reading values.** It is not view-safe, and there is no `dates.*` namespace. The conversion only applies to model attributes that fill an `Instant`/`ZonedDateTime` param, not to properties of entities. I needed a `@TemplateFunction` taking the entity. On the other hand, binding a `Date` field through a `Field` fragment parameter (`t:field`, `fields.value`) did compile and render correctly (it goes through `@DateTimeFormat`). The guide's text ("No `t:field` that writes the value of a field whose type is not view-safe") suggested it wouldn't.
- **Diagnostic for `t:action`.** It doesn't say *why* a ternary of two same-origin links is not "a same-origin link", or that the fix is two forms.
- **Build output noise.** The project's existing `<verbose>true</verbose>` compiler setting makes the processor print every round's annotation list, and a template error is easy to miss among them. That isn't Tessera's fault, but it would help if Tessera's own errors had a distinct prefix.
- **Exact messages and JSP path text are out of reach.** `compare` diffs container error pages byte for byte, and nothing in the guide covers JSP-era 404s for missing views.

## How to reproduce

```sh
JAVA_HOME=/usr/lib/jvm/java-1.8-openjdk mvn clean verify
JAVA_HOME=/usr/lib/jvm/java-1.8-openjdk mvn tomcat7:run        # http://localhost:9966/petclinic
java -jar .tessera/tessera-migrate.jar compare http://localhost:9966/petclinic pages.txt baseline/
```
