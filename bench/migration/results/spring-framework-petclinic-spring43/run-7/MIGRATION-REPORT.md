# Migration report: JSP → Tessera

Spring PetClinic (plain Spring MVC 4.3.13, XML config, WAR on Tomcat 7) used to render its pages
with JSP, JSTL and Spring's form tags and tag files. Every page is now rendered by a compiled
Tessera template.

## Result

| Check | Status |
|---|---|
| Every page rendered by Tessera; JSPs, tag files, JSP/JSTL dependencies and the JSP view resolver gone | done |
| `mvn clean verify` on JDK 8 (`JAVA_HOME=/usr/lib/jvm/java-1.8-openjdk`) | passes: 67 tests, 0 failures (the 62 existing tests, unchanged, plus 5 new ones) |
| `tessera-migrate compare` on `pages.txt` (54 requests, app started with `mvn tomcat7:run-war`) | 52 `same`; 2 `differs`, explained below |
| Security | no `SafeHtml.unchecked`/`TrustedUrl.unchecked`, no `t:utext`, no new `permitAll`, nothing turned off. The app has no Spring Security, so it had no CSRF protection before and has none now. All output is escaped by context. |

## What changed

**Build (`pom.xml`)**
- `java.version` 1.7 → 1.8. Tessera's runtime, starter and generated code are Java 8 class files.
  JDK 8 was already the JDK the app built on (see `.travis.yml`), and the original build couldn't
  run on JDK 21 anyway (it needs `-source 1.7`).
- Removed `javax.servlet.jsp-api`, `tomcat-jasper-el`, `javax.servlet.jsp.jstl-api` and
  `taglibs-standard-jstlel`. Added `dev.tessera:tessera-spring-boot-starter-javax`.
- `maven-compiler-plugin` 3.0 → 3.8.1, because 3.0 doesn't support `annotationProcessorPaths`.
  Added `tessera-compiler` as the annotation processor, plus `-Atessera.templates`,
  `-Atessera.package` and `-Atessera.messages` (message keys are checked at build time),
  `<parameters>true</parameters>`, and `<proc>none</proc>` for test compilation. The old `<Xlint/>`
  compiler argument is now `-Xlint` in `compilerArgs`.
- `src/main/resources/templates/**` is excluded from resource filtering.
- Added `org.glassfish.web:javax.el:2.2.6` with `provided` scope. Hibernate Validator needs a
  Unified EL implementation to interpolate messages. Before, it came on the test classpath with
  the JSP dependency `tomcat-jasper-el`. Without it, Bean Validation silently fails to initialize
  in MVC tests and 3 existing tests fail. At runtime Tomcat provides EL, as before.

**Templates** (`src/main/resources/templates`), one per old JSP, plus two fragment files that
replace the 9 tag files:
- `fragments/layout.html` replaces `layout.tag`, `htmlHeader.tag`, `bodyHeader.tag`,
  `menu.tag`, `menuItem.tag`, `pivotal.tag` and `footer.tag`. It is a fragment
  `layout(String menu, Fragment content, Fragment script)`, where `script` replaces the
  `customScript` JSP fragment attribute.
- `fragments/fields.html` replaces `inputField.tag` and `selectField.tag` with fragments that take
  `Field` parameters.
- `welcome`, `exception`, `vets/vetList`, `owners/{findOwners,ownersList,ownerDetails,createOrUpdateOwnerForm}`,
  `pets/{createOrUpdatePetForm,createOrUpdateVisitForm}`.

**Java**
- `@TemplateView` on the model classes the templates read: `Owner`, `Pet`, `PetType`, `Visit`,
  `Vet`, `Vets`, `Specialty`. This adds no getters, so JSON/XML output and JPA mapping are
  unchanged.
- `web/TesseraViewConfiguration`: the `TemplateRegistry` and `RenderContextFactory` beans.
- `mvc-view-config.xml`: the `ContentNegotiatingViewResolver` stays. It now wraps the
  `TesseraViewResolver` (as an inner bean), the `BeanNameViewResolver` (XML marshalling view), a
  `UrlBasedViewResolver` for `redirect:`/`forward:`, and `NotFoundViewResolver`. The JSP resolver
  and the `JstlView` default view are gone.
- `web/NotFoundViewResolver`: a view name with no template returns 404. Before, the JSP resolver
  forwarded to a missing JSP, which also gave 404. This affects `VisitController.showVisits`,
  which returns `visitList`; that view never existed.
- `web/CurrentRequest` + `web/CurrentRequestAdvice` (`@ControllerAdvice`): put the current request
  URI in the model, because `<form:form>` posted back to the current URL by default. It is a
  `@TemplateView` object, not a `String`, so `RedirectView` never appends it to redirect URLs.
- `web/DateFunctions` (`@TemplateFunctions("dates")`): `dates.format(Date, pattern)` replaces
  `<fmt:formatDate>`. The model uses `java.util.Date`, and `temporals.*` only takes `java.time`.
- `web/ExceptionMessageResolver` extends `SimpleMappingExceptionResolver`. It also puts
  `exceptionMessage` (a `String`) in the model, since a template can't read the `Exception`.
  `mvc-core-config.xml` uses it in place of the plain resolver; the `exception` attribute and the
  view name are unchanged, so `CrashControllerTests` still passes.
- New test `web/TesseraViewRenderingTests` renders through the real view resolvers. The existing
  controller tests use `standaloneSetup` and never render. It checks the escaping of
  `<script>`/markup in user data and in rejected form values, the exception page, validation
  errors, and the 404 for a missing view.
- `readme.md`: the links to JSP/tag files now point to the templates.

**Verification artifacts:** `pages.txt` (54 requests: every page, empty/invalid/valid states of
every form, XSS data, missing entities, 400/404 error pages) and `baseline/` (recorded from the
original app). `.tessera/` holds the tool jar and is git-ignored.

## Remaining differences (compare)

1. `GET /owners/1/pets/1/visits` returns 404 in both apps, with the same Tomcat error page. Before,
   the message read `/petclinic/WEB-INF/jsp/visitList.jsp`, the path of a JSP that never existed.
   Now it is the request URI, which is Tomcat's normal 404 message. Repeating a path to a
   now-deleted JSP technology in the new code would be misleading, so I left this difference in.
2. `POST /owners/new` with every field empty: this differs in some runs and not in others. The
   telephone field breaks two constraints (`@NotEmpty`, `@Digits`). The old tag and the new
   fragment both show the *first* error (`status.errorMessage` / `fields.errors(name)[0]`).
   Hibernate Validator returns its violations in a `HashSet` keyed partly on identity hash codes,
   so which one comes first changes on every request. I measured 12 vs 8 out of 20 POSTs on the
   new app. This page was `same` on my first compare run. The old app behaves the same way, so
   no change to the templates can make this stable.

Nothing could not be converted.

## Where Tessera or its documentation fell short

1. **Spring 4.3 is unsupported and undocumented.** The guide says Spring 5.0+, and "below 5.0
   nothing is tested". I didn't upgrade (Spring 5 would have meant a different Hibernate, Servlet
   3.1, and leaving Tomcat 7). The starter's classes work on Spring 4.3.13 when wired by hand. That
   compatibility is luck, not documented.
2. **`TesseraConfiguration` doesn't fit a `ContentNegotiatingViewResolver`.** It registers
   `TesseraViewResolver` and the redirect resolver as top-level beans with fixed orders. The
   `DispatcherServlet` then consults them directly, outside content negotiation. With
   `use-not-acceptable`, a CNVR without a redirect-capable resolver inside it returns 406 for
   `redirect:` names (the guide mentions the 406 but not how to fix it under a CNVR). I declared
   the resolver as an inner bean myself instead. `ReturnValueHandlerRegistrar` (for controllers
   that return `Template`) is package-private, so it can't be reused without `TesseraConfiguration`.
   I didn't need it.
3. **The docs about `java.util.Date` are inaccurate.** Section 7 says "no `t:field` on fields whose
   type is not view-safe" and that `Date` isn't view-safe. Yet `t:field="*{birthDate}"` on a `Date`
   field compiles and renders the formatted `BindingResult` value, `pet.birthDate != null`
   compiles, and a `@TemplateFunction` can take a `Date` parameter. All of this works, but none of
   it is documented: I only found it out with a probe template.
4. **No date formatting for `java.util.Date`.** `temporals.*` takes only `java.time`, so I wrote
   `dates.format`. That's common in legacy apps; a built-in would help.
5. **`Field` fragments can't read the bound value.** The old tag showed an "ok" icon when the field
   had a value and no error (`status.actualValue`). Every call site now passes an extra
   `boolean filled` argument computed from the model.
6. **No "first error" helper.** `t:errors` joins all messages. For Spring's
   `status.errorMessage` behaviour I had to use `fields.errors(name)[0]` guarded by `hasErrors`.
   The guide doesn't say whether indexing a `fields.errors(...)` result is allowed (it is).
7. **`<select t:field>` doesn't document how `selected` is decided.** It matches on the formatted
   display value (so the `PetTypeFormatter` output `cat` matches `t:value="item.name"`). I found
   that by comparing pages. There is no equivalent of `form:select items=...`; options are written
   by hand.
8. **No fallback for view names without a template.** Tessera falls through, which is correct.
   But apps whose old resolver always produced a view (JSP's `InternalResourceViewResolver`) then
   get a 500 or 406 instead of a 404. The guide doesn't mention this; I added `NotFoundViewResolver`.
9. **Build setup.** The guide doesn't state the minimum `maven-compiler-plugin` version
   (`annotationProcessorPaths` needs 3.5+; this app had 3.0). It also doesn't warn that dropping
   JSP removes the EL implementation that Hibernate Validator relies on on the test classpath.
   That isn't Tessera's fault, but it is a likely trap in any JSP migration.
10. Small things: there is no request/exception access, so the form action and the exception
    message each needed Java glue (expected by design, and the guide covers it in general terms).
    `<form:errors path="*"/>` rendered `<span id="owner.errors">`, and that id has to be written
    by hand.
