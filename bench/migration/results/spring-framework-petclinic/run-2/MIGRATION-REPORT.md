# Migration report: JSP/JSTL to Tessera

## Summary

All nine pages of Spring Framework Petclinic (welcome, find owners, owners list, owner details, owner form,
pet form, visit form, vets list, error page) are now Tessera templates compiled by `javac`. The JSP files, the tag
files, the JSTL dependencies and the JSP view resolver are gone. `mvn clean install` passes: the 75 existing tests are
unchanged, and there are 7 new rendering tests (82 in total).

### How output equality was checked

1. Before changing anything, I built the original WAR and deployed it to Tomcat 11.0.18 (downloaded from Maven
   Central). I captured 28 responses twice, at the root context and at the `/pc` context path. The capture includes
   every page, every form with validation errors (owner new/edit, pet new/edit, including a type-mismatch date and an
   invalid pet type, visit), the "owner not found" search, the `/oups` error page, and pages showing user data that
   contains `<script>`, `&`, `'` and `"`.
2. I deployed the migrated WAR the same way, made the same requests, and compared the HTML after normalising
   whitespace, attribute order and entity spelling (`&#039;` and `&#39;` are the same character).
3. Result: at both context paths every response is identical except one, which is intentional (see
   "Differences in output" below).

## What changed

**Build (`pom.xml`)**
- Removed `jakarta.servlet.jsp.jstl-api` and `org.glassfish.web:jakarta.servlet.jsp.jstl`, and their version
  properties.
- Added `dev.tessera:tessera-spring-boot-starter` with all `org.springframework.boot` dependencies excluded (see
  "Where the guide or Tessera fell short", item 1).
- Added `tessera-compiler` as an annotation processor, with `-Atessera.templates`, `-Atessera.package`
  (`org.springframework.samples.petclinic.templates`) and `-Atessera.messages`, so `#{...}` keys are checked at build
  time.
- Turned annotation processing off for `default-testCompile` (item 2).
- Excluded `templates/**` from resource copying. The templates are compiled into classes and nothing reads them at
  runtime. Without the exclusion, the project's resource filtering (`<filtering>true</filtering>`) would also run over
  their `${...}` expressions.
- Kept `tomcat-jasper-el` (provided). It is the Expression Language implementation Hibernate Validator needs for
  message interpolation, not a JSP dependency, and the servlet container supplies it at runtime.

**Spring configuration**
- `mvc-view-config.xml`: the `ContentNegotiatingViewResolver` now delegates to `BeanNameViewResolver` and Tessera's
  `TesseraViewResolver`. Before, it delegated to `InternalResourceViewResolver` (`mvc:jsp`) with a default `JstlView`.
  The `TemplateRegistry` is loaded with `TemplateRegistry.load(classLoader)`.
- `mvc-core-config.xml`: `SimpleMappingExceptionResolver` is replaced by its subclass `ExceptionMessageResolver`, and
  JSP references in comments are updated.

**Java**
- `@TemplateView` added to `Owner`, `Pet`, `PetType`, `Vet`, `Vets`, `Visit` and `Specialty`. Getters inherited from
  the `@MappedSuperclass`es (`BaseEntity`, `NamedEntity`, `Person`) resolve through these subclasses.
- `web/TesseraRenderContextFactory`: a `RenderContextFactory` that delegates to the starter's
  `SpringRenderContexts.create`, so messages, links, form values/errors and CSRF come from Spring.
- `web/FormActionAdvice` (`@ControllerAdvice`): adds a `formAction` model attribute holding the current request URI
  and query string. This is the default action of Spring's `<form:form>`, computed the same way (encoded originating
  URI, `response.encodeURL`). Templates cannot read the request.
- `web/ExceptionMessageResolver`: also puts the exception message in the model as `message`. Exceptions are not
  view-safe, so the error page cannot read `${exception.message}`. The `exception` attribute is still added, so
  `CrashControllerTests` is unchanged.
- `web/TextFunctions` (`@TemplateFunctions("text")`): `text.orEmpty(x)` renders `value=""` for a `null` id in the
  hidden inputs, as JSP EL did. Tessera would otherwise omit the attribute.

**Templates** (`src/main/resources/templates`)

| JSP / tag file | Tessera |
|---|---|
| `tags/layout.tag`, `htmlHeader.tag`, `bodyHeader.tag`, `footer.tag`, `pivotal.tag` | `fragments/layout.html` with `layout(String pageName, Fragment content, Fragment customScript)` |
| `tags/menu.tag`, `menuItem.tag` | `fragments/menu.html`: `menu(String name)` and `menuItem(boolean active, String url, String title, String glyph, String label)` |
| `tags/inputField.tag`, `selectField.tag` (`spring:bind`) | `fragments/fields.html`: `inputField(String label, Field name, boolean hasValue)` and `selectField(String label, Field name, Collection<PetType> names, int size, boolean hasValue)` |
| `tags/localDate.tag` (`fmt:parseDate`/`fmt:formatDate`) | `temporals.format(date, 'yyyy-MM-dd')` |
| `jsp/**/*.jsp` | the same view names under `templates/` (`owners/ownerDetails.html`, ...), so controllers are unchanged |

Each page replaces its `<html>` with the layout. It passes `~{::body}` (a `<body t:remove="tag">`, so only its
content is inserted) and, for the two pages with date pickers, a same-file `customScript` fragment.

**Tests**
- No existing test was modified. They use `MockMvcBuilders.standaloneSetup`, which does not use the configured view
  resolvers, so none of them depends on JSP rendering. `CrashControllerTests` still asserts `forwardedUrl("exception")`,
  which holds because standalone MockMvc uses its own default `InternalResourceViewResolver`.
- Added `web/PageRenderingTests`. It uses `webAppContextSetup` over the real `mvc-core-config.xml`, so pages are
  rendered by Tessera. It covers the welcome page (message and active menu), escaping of `<script>` and `&` in owner
  data, the vets list, the owner form with validation errors and the `formAction`, the pet form with the selected type
  and formatted date, the global errors of the find form, and the error page.

**Docs**: `readme.md` now points to the templates instead of the JSP tag files.

## Differences in output

1. **Validation messages are now escaped.** The owner form with an invalid telephone used to emit
   `numeric value out of bounds (<10 digits>.<0 digits> expected)` as raw markup, because `${status.errorMessage}` is
   unescaped JSP EL. The browser parsed `<10 digits>` as broken markup. Tessera emits
   `(&lt;10 digits&gt;.&lt;0 digits&gt; expected)`, which displays the text the message intends. The same applies to
   the error page's exception message and the field labels, which JSP also printed unescaped. This is required by the
   security rule and cannot be avoided without `SafeHtml.unchecked`.
2. **A view name with no page.** `VisitController.showVisits` returns `visitList`, which had no JSP either. The old
   app answered `GET /owners/*/pets/{id}/visits` with **404** (forward to a missing JSP). It now answers **406**: no
   resolver produces a view, and the content-negotiating resolver is configured with `use-not-acceptable="true"`. Both
   are errors for a page that never existed. I did not invent a page for it. `VisitControllerTests.testShowVisits`
   still passes.
3. HTML comments that were JSP comments (`<%-- ... --%>`) were already absent from the old output and are left out.
   The IE conditional comment, a real HTML comment, is kept.

## Not converted

Nothing was left unconverted. Every JSP construct had a Tessera equivalent or a controller-side replacement (form
action, exception message).

## Where the guide or Tessera fell short

1. **Plain Spring MVC (no Spring Boot) is not covered.** The guide's only Spring path is `tessera-spring-boot-starter`
   with Boot auto-configuration, and the starter pulls in `spring-boot-autoconfigure` and `spring-boot-webmvc`. This
   application is XML-configured Spring Framework 7 without Boot. I inspected the jar with `javap`: only
   `TesseraAutoConfiguration` references Boot. So I excluded every Boot artifact and wired `TemplateRegistry.load(...)`
   and `TesseraViewResolver` in XML myself. The auto-configuration builds `RenderContextFactory` as a lambda, so a
   small adapter class was needed. None of `TesseraViewResolver`, `RenderContextFactory` or `SpringRenderContexts` is
   documented. A plain `tessera-spring` module, or a documented manual-wiring section, is missing.
2. **The annotation processor also runs in `testCompile`.** The guide's build block puts Tessera in
   `annotationProcessorPaths`. Maven applies that to test compilation too, where the processor compiles all templates
   again. It then fails with `unknown identifier or function namespace 'text'`, because the application's
   `@TemplateFunctions` class is not part of that javac run. The guide does not mention this. The fix
   (`<proc>none</proc>` for `default-testCompile`) is not documented, and the error message points the wrong way.
3. **No JSP-specific guidance and no migrator for JSP.** `tessera-migrate` handles Thymeleaf only. The JSP table
   covers single tags but not `spring:bind`/`status.*` in tag files, `jsp:attribute`/`jsp:invoke` named fragments, or
   `form:form`'s implicit action and id.
4. **No equivalent of `<form:form>`'s default action.** Tessera has no request URI and no "post back to the current
   URL" option. I had to add the `formAction` model attribute through a controller advice.
5. **A `Field` fragment cannot read the field value.** The old tags showed an "ok" icon when
   `status.actualValue` was not empty. The fragment cannot read the value, so each caller passes a `hasValue` flag
   computed from the form object (for example `!strings.isEmpty(owner.firstName)`).
6. **`null` attributes are omitted.** The guide states this, but offers no way to render `value=""` for a nullable
   non-String value. `x ?: ''` would mix types, which the guide documents as the "incompatible types" error. A
   one-line `@TemplateFunction` was needed.
7. **Optional `Fragment` parameters are not documented.** The guide does not say whether a `Fragment` argument may be
   `null`. Passing `null` and guarding with `t:if="customScript != null"` around a nested `t:replace` works, but I had
   to find that by trial. The nesting is needed because `t:replace` runs before `t:if`.
8. **The guide's layout example nests `<body>`.** `~{::body}` renders the selected `<body>` element itself, and the
   example inserts it inside `<div class="container">` in the layout's own `<body>`. That produces
   `<body><div><body>...</body></div></body>`. I used `<body t:remove="tag">` on the page. The guide should show this.
9. **Passing a link `@{...}` as a `String` fragment argument** (for the menu items) works but is not documented.
10. **Templates live under `src/main/resources`.** The guide does not warn that resource filtering will rewrite
    `${...}`, or that the templates get packaged although nothing reads them at runtime. I excluded them from
    resources.
11. **No template-only comment syntax** (like `<%-- --%>` or `<!--/* */-->`) is documented. Developer comments
    either appear in the output or must be deleted.
12. **Exceptions in error pages.** The guide says error-page attributes are "ordinary parameters", but Spring's
    `SimpleMappingExceptionResolver` exposes only the `Exception`, which can never be view-safe. It needed a resolver
    subclass. A note or a built-in adapter would help.

## Security

- No `SafeHtml.unchecked` or `TrustedUrl.unchecked` anywhere.
- No `permitAll`; the app has no Spring Security.
- No escaping disabled. Output that JSP left unescaped (EL `${...}` for labels, error messages and the exception
  message) is now escaped.
- CSRF: Tessera would add the token input to `method="post"` forms if Spring Security were present. It is not, as
  before.
