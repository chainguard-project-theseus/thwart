# Migration report: JSP/JSTL → Tessera

The application is the plain Spring Framework PetClinic: no Spring Boot, XML configuration, packaged as a war.
All its server-rendered views used to be JSP + JSTL + Spring form tags + tag files. Tessera now renders all of them.

## What changed

### Build (`pom.xml`)
- Removed the JSP stack: `jakarta.servlet.jsp.jstl-api`, `org.glassfish.web:jakarta.servlet.jsp.jstl`,
  `tomcat-jasper-el`.
- Added `dev.tessera:tessera-spring-boot-starter:0.1.0-SNAPSHOT`, **excluding all `org.springframework.boot`
  artifacts**. PetClinic is not a Boot app, so the auto-configuration is not used. The view resolver, view and
  render-context classes in the starter only need `spring-webmvc`.
- Added `tessera-compiler` as an annotation processor, with `-Atessera.templates`, `-Atessera.package=org.springframework.samples.petclinic.templates`
  and `-Atessera.messages` (so `#{...}` keys are checked at build time).
- Added `org.apache.tomcat.embed:tomcat-embed-el` (`provided`). `tomcat-jasper-el` had also been supplying the
  Jakarta EL implementation that Hibernate Validator needs to interpolate messages. Without an EL implementation
  every JPA/validator test failed with "Unable to build the default ValidatorFactory".
- `java.version` 17 → **21**. The Tessera jars are Java 21 bytecode (class version 65). CI matrix, devcontainer,
  readme and the jib base image tag were updated to match.
- `src/main/resources/templates/**` is excluded from resource copying. Templates are compiled to classes and never
  read at runtime. Also, the resources are filtered, so Maven would have rewritten `${...}` in the copies.

### Configuration
- `mvc-view-config.xml`: the `<mvc:jsp>` resolver and the `JstlView` default view are gone. Inside the existing
  `ContentNegotiatingViewResolver` there is now a `TesseraViewResolver` (inner bean). There is also a
  `UrlBasedViewResolver` restricted to `redirect:*` / `forward:*` names. The JSP `InternalResourceViewResolver`
  used to handle redirects. Without a replacement, every successful form POST returned 406. I found this through
  the comparison, not the guide (see below).
- `TesseraViewConfig` (new `@Configuration`, picked up by the existing component scan) declares the
  `TemplateRegistry` and `RenderContextFactory` beans that the Boot auto-configuration would otherwise provide.
- `mvc-core-config.xml`: the exception resolver is now `PetclinicExceptionResolver`, a subclass of
  `SimpleMappingExceptionResolver`. It also puts the exception message in the model as the String `message`,
  because an `Exception` is not a view-safe type. The `exception` attribute is still there (the tests check it).

### Java
- `@TemplateView` on the entities the views read: `Owner`, `Pet`, `PetType`, `Vet`, `Vets`, `Visit`, `Specialty`.
- `FormActionAdvice` (`@ControllerAdvice` for the three form controllers) adds a `formAction` model attribute. When
  `<form:form>` has no `action`, it defaults to the current request URI plus query string, passed through
  `response.encodeURL`. Tessera links must have literal paths, and the URI can't be rebuilt from the model: after a
  failed update the bound owner has no id, and the visit URL may contain a literal `*`.

### Templates (`src/main/resources/templates`)
| JSP | Tessera |
|---|---|
| `tags/layout.tag`, `htmlHeader.tag`, `bodyHeader.tag`, `menu.tag`, `menuItem.tag`, `pivotal.tag`, `footer.tag` | `fragments/layout.html`: `layout(Fragment content, String pageName, Fragment customScript)` plus a `menuItem(...)` fragment |
| `tags/inputField.tag`, `selectField.tag` | `fragments/forms.html`: `inputField(String, Field, boolean)` and `selectField(String, Field, Collection<PetType>, boolean)` |
| `tags/localDate.tag` | `temporals.format(date, 'pattern')` |
| `jsp/welcome.jsp`, `exception.jsp`, `owners/*.jsp`, `pets/*.jsp`, `vets/vetList.jsp` | same names, `.html` |

Each page uses `t:replace="~{fragments/layout :: layout(~{::body}, '<menu>', ...)}"` with `<body t:remove="tag">`, so
only the body's children are inserted, like `<jsp:doBody/>`. The pet and visit forms pass their flatpickr block as
a same-file `customScript` fragment. The other pages pass `null`.

### Tests
- Existing tests are unchanged and all pass: 75/75, the same count as before the migration.
- Added `TemplateRenderingTests` (7 tests). The existing controller tests use `standaloneSetup`, so they never
  render a view. The new tests render through the real view resolvers: welcome page, owner details with
  `<script>` in user data (checked escaped), owner form with validation errors, pet form (selected option, bound
  date format), vets, the error page, and a redirect.
- `mvn clean install`: BUILD SUCCESS, 82 tests, 0 failures.

## How output equivalence was checked
I built the original war and the migrated war and ran each in an embedded Tomcat 11 under the context path
`/petclinic`, so context-path handling in links shows up. I captured 33 scripted requests from both, plus a few one-off checks. They cover every page,
the not-found search, an XSS payload in a search and in form fields, every form with validation errors
(type-mismatch dates, missing type, empty fields, duplicate pet name), a successful POST/redirect on every form
(status and `Location` compared), the pages after those redirects, the `/oups` error page, a missing owner (error
page), a missing pet, an unknown URL, and the `de`/`es` locales.

I compared the HTML as a token stream, ignoring whitespace and attribute order. Status codes, `Location` and
`Content-Type` headers matched, and every page was identical apart from the two items below. The harness was
scratch work and has been removed from the repo.

## Differences I kept on purpose, or could not avoid
1. **Validation messages are now escaped.** `inputField.tag` printed `${status.errorMessage}` with plain EL, which
   JSP does not escape. For example `numeric value out of bounds (<10 digits>.<0 digits> expected)` went out raw.
   Tessera escapes it to `&lt;10 digits&gt;`. The browser shows the same text. Keeping the raw output would need
   `SafeHtml.unchecked`, which is forbidden and would also be an XSS hole. The same applies to `exception.jsp`'s
   `${exception.message}`, though today's message has no special characters, so its output is identical.
2. **`GET /owners/*/pets/{petId}/visits`** (`VisitController.showVisits`) returns the view name `visitList`. That
   template never existed: before the migration, the request forwarded to a missing `/WEB-INF/jsp/visitList.jsp`
   and Tomcat answered **404** with a message naming the JSP. Now no resolver knows the name, and the content
   negotiating resolver (`use-not-acceptable="true"`) answers **406**. I did not invent a page the app never had.
   A 404 could be restored with a fallback view if wanted.
3. Whitespace only: Spring's `<form:errors>` joined messages with `<br/>`, Tessera uses `<br />`. Blank lines from
   JSP directives are gone.

## Where the guide or Tessera fell short
- **No guidance for non-Boot Spring MVC.** The guide only covers the Boot starter's auto-configuration. To find out
  which beans to declare (`TemplateRegistry.load(classLoader)`, `SpringRenderContexts::create`,
  `new TesseraViewResolver(registry, contexts)`), I had to `javap` the starter. I had to exclude Spring Boot from
  the starter by hand. There is no Spring-only artifact.
- **"`redirect:`/`forward:` work as usual" is only true with Boot.** Boot supplies an `InternalResourceViewResolver`
  that handles them. In a plain Spring app that removes its JSP resolver, redirects silently break (406 behind a
  `ContentNegotiatingViewResolver`). The guide should say so.
- **Removing the old engine can remove the EL implementation.** In JSP apps, Bean Validation often gets its EL
  implementation through the JSP/EL dependency. Removing it breaks the validator. The guide's "remove the old
  engine dependency" step doesn't mention this.
- **Java 21 requirement.** Section 2 says "Java 21+" but doesn't say the project's own release level must be 21,
  because the runtime jar is class version 65.
- **No mapping for `<form:form>`'s default `action`** (the current request URI) or its default `id` (the model
  attribute name). Tessera has no ambient request, and link paths must be literals. The JSP mapping table maps
  `<form:form modelAttribute="owner">` to `<form t:object="owner" method="post">`, which silently drops the action.
- **`<jsp:doBody/>`-style layouts:** `~{::body}` inserts the `<body>` element itself, and `<t:block>` cannot be
  selected (`~{::block}` → "no fragment or element named 'block'", `~{::t:block}` → parse error). The guide doesn't
  cover this. `t:remove="tag"` on the selected element works, but I found it by trial.
- **Optional fragment arguments are undocumented.** Passing `null` for a `Fragment` parameter and testing
  `customScript != null` works, but the guide doesn't say so.
- **`|${x}|` renders `null` as the text "null"** (unlike `[[...]]` / `t:text`, which render empty). Section 5 doesn't
  mention it. I used `pet.isNew() ? '' : |${pet.id}|` to reproduce EL's empty output.
- **Spring's `status.actualValue` / `status.errorMessage` have no direct equivalent.** The guide says a `Field`'s
  value can't be read, so the "valid" check mark needs an extra `boolean hasValue` argument that the caller computes
  from the form object. `fields.errors(name).first` works for "first error only", but isn't documented.
- **Incremental builds:** as section 17 says, template-only edits need `mvn clean`. A stale build left deleted
  experiment templates in the registry until I ran `clean`.
- **`tessera-migrate` handles Thymeleaf only**, so I hand-ported all the JSPs using section 13.
- **Unavailable artifacts:** Tessera 0.1.0-SNAPSHOT exists only in the local Maven repository. The GitHub Actions
  workflows (and anyone else building this repo) cannot resolve it until it is published.

## Not converted
Nothing that renders a page. The jetty-maven-plugin (Jetty 11 = Servlet 5) and the jib `jetty:11` image already
couldn't run a Spring 7 / Servlet 6 app before the migration. I left them as they were, apart from the JDK tag.
