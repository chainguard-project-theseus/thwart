# Migration report: JSP/JSTL to Tessera

## Result

- Every page is now rendered by a Tessera template under `src/main/resources/templates`. The
  templates are compiled to `org.springframework.samples.petclinic.templates.*Template` by the
  annotation processor during `javac`.
- All JSPs (`WEB-INF/jsp`) and tag files (`WEB-INF/tags`) are deleted, along with the JSTL
  dependencies and the JSP view resolver / `JstlView` configuration.
- `mvn clean install` passes: 83 tests. That is the 75 existing tests, none of them changed, plus 8
  new tests in `TemplateRenderingTests`.
- No `SafeHtml.unchecked` or `TrustedUrl.unchecked` anywhere, and no security or escaping
  setting changed. The application has no Spring Security, so there is no `permitAll` or CSRF
  setting involved.

## How the output was checked

I built the original WAR and the migrated WAR and ran each in embedded Tomcat 11 (a temporary
harness, since deleted). I sent the same sequence of 30 requests to both and compared the
responses with a normalizer that ignores attribute order and whitespace.

The requests covered every page, every form with validation errors (owner, pet, visit, the "not
found" search), a type-mismatch date, user data containing `<script>`, quotes and `&`, the
exception page, the 404 cases, and the redirects after a successful POST. I also compared:

- the `de` and `es` locales;
- `Accept: application/json` on HTML pages (406 on both);
- a form whose URL has a query string.

All status codes, redirect targets and content types match. All pages are identical except two
expected differences:

1. **`/owners/new` with a non-numeric telephone.** The JSP printed validation messages without
   escaping, so `numeric value out of bounds (<10 digits>.<0 digits> expected)` went out as raw
   markup. Tessera escapes it (`&lt;10 digits&gt;`). The text a browser shows is the same; only the
   escaping is now correct. The same applies to labels and `${exception.message}`, which the JSPs
   also printed unescaped.
2. **`/owners/*/pets/{id}/visits`.** The controller returns view `visitList`, for which no view
   ever existed. Before, this was a 404 from forwarding to a missing JSP; it is still a 404 (see
   below). Only the text of Tomcat's default error page differs, because it used to name the
   missing JSP.

Character references also differ (`&#039;` and `&#034;` are now written by Tessera's own
escaper). They decode to the same characters.

## What changed

**Build (`pom.xml`)**
- Added `tessera-spring-boot-starter` with every `org.springframework.boot` artifact excluded.
  The app is plain Spring MVC with XML config, not Boot.
- Added `tessera-compiler` as an annotation processor, with `tessera.templates`,
  `tessera.package` and `tessera.messages`. Message keys are now checked at build time.
- Removed `jakarta.servlet.jsp.jstl-api` and `org.glassfish.web:jakarta.servlet.jsp.jstl`.
- Kept `tomcat-jasper-el` (provided) and added a comment explaining why. Despite the name, it is
  the Expression Language implementation Hibernate Validator needs to build messages. When I
  removed it, the JPA and controller tests failed with "Unable to build the default
  ValidatorFactory".
- `java.version` 17 → 21, because the Tessera jars are Java 21 bytecode and the guide requires
  21+. The CI matrix (`.github/workflows`) and `.devcontainer` were updated to match.
- Template sources are left out of the packaged resources, since they are never read at runtime.

**Templates**

| Template | Replaces |
|---|---|
| `fragments/layout.html` | `layout.tag`, `htmlHeader.tag`, `bodyHeader.tag`, `menu.tag`, `pivotal.tag`, `footer.tag` |
| `fragments/menuItem.html` | `menuItem.tag` |
| `fragments/fields.html` | `inputField.tag` and `selectField.tag`, as fragments with `Field` parameters |
| `welcome.html`, `exception.html`, `owners/*.html`, `pets/*.html`, `vets/vetList.html` | the JSP pages of the same names |

- `localDate.tag` is now `temporals.format(date, pattern)`.
- `<spring:url>` / `<c:url>` are now `@{...}` links (including the page-relative `{ownerId}/edit`).
- `<form:form>` / `<form:input>` / `<form:select>` / `<form:errors>` are now `t:object`, `t:field`
  and `t:errors`.

**Model:** `@TemplateView` added to `Owner`, `Pet`, `PetType`, `Visit`, `Vet`, `Vets` and
`Specialty`.

**Spring wiring**
- New `web/TemplateViewResolver`, registered in `mvc-view-config.xml` inside the existing
  `ContentNegotiatingViewResolver`, after the bean-name resolver. It:
  - wraps Tessera's `TesseraViewResolver`, using `TemplateRegistry.load` and
    `SpringRenderContexts::create`;
  - handles `redirect:` and `forward:` view names the way `UrlBasedViewResolver` does;
  - adds a `requestUri` model attribute (see below);
  - answers 404 for view names that have no template, as the old JSP resolver did.
- New `web/ExceptionMessageResolver`, a `SimpleMappingExceptionResolver` subclass registered in
  `mvc-core-config.xml`. It adds the exception's message to the model as `message`, because the
  error template cannot read the `exception` object. `CrashControllerTests` still finds it by type.

`readme.md` was updated to point at the templates and to say Java 21.

## What could not be converted directly

Everything was converted. Some JSP behaviour had to be reproduced differently:

- **Implicit form action.** `<form:form>` without an `action` posts back to the current request
  URI (plus the query string). Templates cannot see the request, so `TemplateViewResolver` passes
  that URI to every template as `requestUri`, and the forms use `t:action="requestUri"`.
- **The green "ok" icon.** `inputField.tag` / `selectField.tag` used `status.actualValue` to
  decide whether to show it. A `Field` parameter cannot read the value, so each call passes
  `hasValue` (for example `!strings.isEmpty(owner.firstName)`).
- **First error only.** `${status.errorMessage}` shows only the first error, so the template uses
  `fields.errors(name).first` instead of `t:errors`, which would join all of them.
- **`value=""` for a null id.** `value="${pet.id}"` renders `value=""` when the id is null, but a
  `null` value in Tessera drops the attribute. The templates use
  `t:value="pet.id == null ? '' : '' + pet.id"`.
- **Missing view `visitList`.** This is a pre-existing bug and I kept it as a 404 rather than
  inventing a page.

## Where the guide or Tessera fell short

1. **No plain Spring MVC integration.** The only Spring integration is a Spring Boot starter
   (auto-configuration). The guide says nothing about non-Boot or XML-configured applications. I
   had to:
   - read the starter's classes with `javap`;
   - exclude its Boot dependencies (it also pulls the Spring Boot 4.1.1 BOM into its own POM);
   - build `TesseraViewResolver(TemplateRegistry.load(cl), SpringRenderContexts::create)` myself.

   `RenderContextFactory`, `SpringRenderContexts` and `TesseraView` are not documented.
2. **`redirect:` and `forward:` are not handled by Tessera's resolver.** The guide says they "work
   as usual", which is only true when another `UrlBasedViewResolver` (Thymeleaf's, or JSP's) is
   still registered. Once the old engine is removed, `redirect:/owners/1` reached my fallback, and
   the first run returned 404s on every successful POST until I handled the prefixes.
3. **Unresolved view names.** Tessera's resolver returns `null` for unknown names, which turns into
   a 500 "Could not resolve view". The guide does not discuss what happens when the old catch-all
   resolver (`InternalResourceViewResolver`) is removed.
4. **No guidance for an implicit form action.** The JSP table maps
   `<form:form modelAttribute="owner">` to `<form t:object="owner" method="post">` but does not
   say that the action, implicit in JSP, must now come from somewhere. Without it, forms on
   `/owners/1/edit` and similar URLs would post to the wrong place.
5. **`Field` values cannot be read** (stated in the guide). This forces extra parameters for
   value-dependent markup such as `spring:bind`'s `status.actualValue`. The JSP table does not
   mention `<spring:bind>` at all.
6. **Error pages.** The guide only covers `status` and `message` (Spring Boot's error
   attributes). It says nothing about `SimpleMappingExceptionResolver`, whose `exception`
   attribute is a `Throwable` and never view-safe, so a Java change was needed.
7. **`tessera-migrate` handles Thymeleaf only**, so all 19 JSP and tag files were converted by
   hand.
8. **Undocumented behaviour I relied on and verified by comparing output:**
   - passing `null` for a `Fragment` argument (for pages without a custom script);
   - `t:remove="tag"` applying to an element selected with `~{::main}`, which lets a page pass its
     content without a wrapper element;
   - `.first` on a function result (`fields.errors(name).first`);
   - `t:field` rendering `value=""` for a null property;
   - `<option>` elements of `<select t:field>` being selected by comparing against Spring's
     formatted value (the `PetTypeFormatter` name).
9. **Java 21 requirement** (stated in the guide). It forced a Java baseline change from 17 in a
   project whose CI also tested 17.
10. **The build depends on a local snapshot.** Tessera 0.1.0-SNAPSHOT exists only in the local
    Maven repository, so CI and other machines cannot build the project until Tessera is
    published somewhere.
11. **Maven resource filtering.** This project filters `src/main/resources`, and the guide puts
    templates there. Any `${maven.property}` written in a template would be substituted when the
    resources are copied (the compiler reads the unfiltered files). I avoided `${...}` in the
    templates and left them out of the packaged resources.
12. **Incremental builds** (known limitation): a change to a template alone needs
    `mvn clean compile`.

## Not changed / left as is

- The jib base image `jetty:11.0-jdk17` in `pom.xml` was already incompatible with Spring 7
  (Jetty 11 is Servlet 5) and now also with the Java 21 baseline. It is unrelated to views, so I
  left it alone.
- `jetty-web.xml` and the `vets/vetList.xml` `MarshallingView` bean are unrelated to JSP and were
  kept.
