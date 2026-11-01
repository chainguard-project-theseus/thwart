# Migration report: JSP → Tessera

Spring Framework Petclinic (Spring MVC 5.3, XML config, `javax.servlet`, Java 8, packaged as a war)
now renders every HTML page with Tessera 0.1.0-SNAPSHOT templates. Before, it used JSP with JSTL,
Spring form tags and tag files.

## Results

| Check | Result |
|---|---|
| `mvn -B clean verify` on JDK 8 (the target, `java.version` 1.8) | BUILD SUCCESS, 67 tests, 0 failures (62 existing + 5 new) |
| `mvn -B clean verify` on JDK 21 | BUILD SUCCESS, 67 tests, 0 failures |
| `tessera-migrate compare` (`pages.txt`, 56 requests) | 54 `same`, 2 `differs`: Jetty's own error pages, explained below. Every status and final URL matches. |
| `tessera:review` | 0 `@TemplateFunction`s, 0 `SafeHtml.unchecked`/`TrustedUrl.unchecked`. `failOnUnchecked` is now on, so adding one fails the build. |
| Old engine | No JSPs, no tag files, no JSTL/taglibs dependency, no `InternalResourceViewResolver`/`JstlView`. There is no "another template engine is still configured" warning. |

No test was deleted, disabled or weakened, and no existing test needed a change.

## What changed

**Build (`pom.xml`)**
- Added `dev.tessera:tessera-spring-boot-starter-javax`. The app uses `javax.servlet` without Boot.
- Added `tessera-compiler` as an annotation processor (`-Atessera.templates`, `-Atessera.package=org.springframework.samples.petclinic.templates`, `-Atessera.messages` pointing at `messages/messages`, so every `#{...}` key is checked at build time). Also set `<parameters>true</parameters>`.
- Added `tessera-maven-plugin` (`check` + `review`, with `failOnUnchecked=true`).
- Excluded `templates/**` from resource filtering. `src/main/resources` is filtered, and filtering would rewrite `${...}` in the templates.
- Removed `taglibs-standard-jstlel` (JSTL) and `tomcat-jasper-el`.
  - `dependency:list` diff: the JSTL jars, `tomcat-el-api` and `tomcat-jasper-el` are gone.
  - Hibernate Validator still needs an EL implementation to interpolate messages, so `org.glassfish:jakarta.el:3.0.4` replaces it. It uses the same `provided` scope: the container supplies EL, as before.
- `.mvn/wrapper/maven-wrapper.properties`: Maven 3.5.4 → 3.9.9, because `tessera-maven-plugin` needs Maven 3.6.3+.

**Spring configuration**
- `mvc-view-config.xml`:
  - Removed the `ContentNegotiatingViewResolver`, its default `JstlView` and the `<mvc:jsp>` resolver.
  - Registered `dev.tessera.spring.TesseraConfiguration`. It provides the Tessera view resolver and the `redirect:`/`forward:` resolver.
  - Kept the `BeanNameViewResolver` and the existing `MarshallingView` bean.
- `mvc-core-config.xml`: the `SimpleMappingExceptionResolver` bean is now `PetclinicExceptionResolver`. This subclass also puts `message` (a `String`) in the model, because a template cannot read an `Exception`. The `exception` attribute is still there, so `CrashControllerTests` is unchanged.
- New `NotFoundView` bean named `visitList`. `VisitController.showVisits` returns the view `visitList`, which never had a JSP: the old app answered 404 by forwarding to a missing JSP. With JSP gone the name would not resolve at all, which gives a 500. The bean keeps the 404, and `VisitControllerTests` still sees the view name `visitList`.

**Model**: `@TemplateView` on `Owner`, `Pet`, `PetType`, `Visit`, `Vet`, `Specialty` and `Vets`. This is the guide's "shortcut" (section 4.2) rather than one view record per page. The pages are small and read the entities directly, as the JSPs did.

**Templates** (`src/main/resources/templates`). Each JSP or tag file is replaced:

| Old | New |
|---|---|
| `layout.tag`, `htmlHeader.tag`, `bodyHeader.tag`, `menu.tag`, `menuItem.tag`, `pivotal.tag`, `footer.tag` | `fragments/layout.html`: `layout(String pageName, Fragment content, Fragment customScript)` |
| `inputField.tag`, `selectField.tag` | `fragments/fields.html`: `inputField(String, Field)` and `selectField(String, Field, Collection<PetType>)`, using `fields.hasErrors`, `fields.value` and `fields.errors(...).first` in place of `spring:bind`'s `status` |
| `localDate.tag` | `temporals.format(date, pattern)` |
| `welcome.jsp`, `exception.jsp`, `owners/*.jsp`, `pets/*.jsp`, `vets/vetList.jsp` | `.html` templates of the same view names |

The `customScript` JSP fragment attribute (datepicker script) is passed as `~{::custom-script}`.

- New test: `TemplateRenderingTests`. It renders through the real view resolvers (`webAppContextSetup`) and checks that user data in pages and in form values comes out escaped. It also covers form actions, validation errors, the error page and messages. The existing controller tests use `standaloneSetup` and never render a view.
- `pages.txt` and `baseline/`: the recorded page list (all pages, empty and invalid form states, success redirects, HTML in user input, not-found and error pages). `.tessera/` is in `.gitignore`.

## Differences that remain, and why

1. **53 `GET /owners/6/pets/7/visits` (404 both times).**
   - Before, Jetty's error page named the missing JSP ("HTTP ERROR 404 /WEB-INF/jsp/visitList.jsp", servlet `jsp`).
   - Now it is Jetty's generic "404 Not Found" from the dispatcher.
   - The status and URL match. The old text only described JSP internals, and that file no longer exists.
2. **56 `GET /owners/1/pets/9999/edit` (500 both times).**
   - The pet doesn't exist, so the form object is `null` and rendering fails in both versions.
   - Before, Jetty's error page showed a Jasper exception with JSP source lines. Now it shows a `NullPointerException`.
   - The status and URL match. Copying JSP's error text would be meaningless, and the controller behaviour was left as it was.

Output that the JSPs printed raw is now escaped: `${exception.message}`, and `${status.errorMessage}` in the tag files. For the data in the page list the bytes are the same. Where they would differ, the old page was injectable.

## Things worth knowing about the conversion

- `<form:form>` posts back to the current request URI by default. Tessera requires an explicit, same-origin, literal-path `t:action` on a POST form, and **rejects a choice between two such links** (`cond ? @{/a} : @{/b}`).
  - For the owner form (`/owners/new` vs `/owners/{id}/edit`) and the pet form (`.../pets/new` vs `.../pets/{id}/edit`), the form body became a same-file fragment carrying `t:object`. Two `<form>`s under `t:if`/`t:unless` insert it, each with its own literal link.
- The owner form chooses its action from the `ownerId` path variable, not from `owner.isNew()`. A POST to `/owners/1/edit` with errors re-renders an owner whose `id` was not bound, so it shows "New Owner" but posts to `/owners/1/edit`, exactly as before.
- The visit form's action is built from `visit.pet.owner.id`, because the GET mapping `/owners/*/pets/{petId}/visits/new` has no `ownerId` variable. That equals the request URL whenever the URL names the pet's real owner.

## Where Tessera or its documentation fell short

1. **A choice between two literal links in a POST form's `t:action` is a build error.** Both branches are same-origin links with literal paths, so this looks safe. The error message suggests "omit the action", which would change the markup. The guide doesn't show how to post back to one of two URLs; the workaround is the duplicated `<form>` plus a fragment described above.
2. **`~{::tagname}` selection works only as a `Fragment` argument.** In `t:insert="~{::owner-form-body}"` it is looked up as a fragment name ("has no fragment 'owner-form-body'"). The guide (section 4.8) presents `~{::name}` generally as "the first element with tag `name`".
3. **A same-file fragment doesn't inherit the caller's `t:object`.** That is consistent with "fragments see only their parameters", but the guide gives no example of putting `t:object` on the fragment itself (`<t:block t:fragment="body(Owner owner)" t:object="owner">`). That works, and binding still uses the `owner` model attribute's `BindingResult`.
4. **Path variables fill `t:params` only when the handler method declares them.** `PetController.processUpdateForm` maps `/owners/{ownerId}/pets/{petId}/edit` but doesn't declare `petId`, so the template's `petId` was silently `null` on that request. Compare caught it as a wrong form action. The guide says this in section 4.9, but a missing declared value gives no warning.
5. **Fragment argument types are invariant.** `List<PetType>` won't accept a `Collection<PetType>` model value. The message is clear; changing the parameter type fixed it.
6. **`tessera-maven-plugin` needs Maven 3.6.3+.** The project's Maven wrapper pinned 3.5.4, so the wrapper had to be upgraded. The guide mentions the requirement but not wrappers.
7. **Without Boot, the guide doesn't cover `<mvc:view-resolvers>`/`ContentNegotiatingViewResolver`.** The CNVR with `use-not-acceptable="true"` only sees the resolvers declared inside it, so leaving it in place next to `TesseraConfiguration` would have turned HTML pages into 406s. I removed it and kept only the bean-name resolver.
8. **`Exception` and `Date` model values are not view-safe.** The guide says so. The usual `SimpleMappingExceptionResolver` setup needs a small subclass to expose the message; a documented recipe for this common Spring MVC pattern would help.

Nothing was left unconverted.
