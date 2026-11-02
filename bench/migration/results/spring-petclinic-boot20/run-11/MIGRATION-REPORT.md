# Migration report: Thymeleaf → Tessera

## Summary

Every page in Spring PetClinic is now rendered by Tessera 0.1.0-SNAPSHOT (13 compiled templates).
Thymeleaf is gone: its starter dependency, its `spring.thymeleaf.mode` setting and its templates.
The templates under `src/main/resources/templates` were rewritten in place as Tessera templates.
The startup log shows `TesseraViewResolver : Found 13 compiled Tessera templates` and does not
show `another template engine is still configured`.

- Build: `./mvnw clean verify` on JDK 8, the JDK the app targets (`java.version` 1.8), gives
  BUILD SUCCESS with 41 tests run, 0 failures, 0 errors and 1 skipped. The skipped one,
  `CrashControllerTests`, was already `@Ignore`d upstream. No test was changed.
- Parity: I recorded the original app with `tessera-migrate record` using 49 requests and
  compared the migrated app against it. 48 came out `same` and 1 `differs` (explained below).
  The requests cover every view in its empty, validation-error and success states, the
  owner-search outcomes (none, one, many, all), HTML-special characters in stored data, the
  error pages (500 from `/oups`, 404, 400 type mismatch, 406, 500s from missing entities), and
  the German locale (checked by hand with `Accept-Language: de`).
- Security: no `TrustedUrl.unchecked`, no escaping switched off, no security config touched
  (the app has no Spring Security), and no tests disabled.

## What changed

### Build (`pom.xml`, `.mvn/wrapper/maven-wrapper.properties`)
- Replaced `spring-boot-starter-thymeleaf` with `dev.tessera:tessera-spring-boot-starter-javax`,
  because Boot 2.0 uses the `javax` servlet API. `spring-boot-starter-web` 2.0 still brings
  Hibernate Validator, so no other dependency was lost.
- Set up `maven-compiler-plugin` with `<parameters>`, the `tessera-compiler` annotation processor,
  `tessera.templates`, `tessera.package` (`org.springframework.samples.petclinic.templates`) and
  `tessera.messages`. The `tessera.messages` property is defined so `#{welcome}` is checked at
  build time.
- Added the `tessera-maven-plugin` `check` goal, so editing only a template still recompiles.
- Moved the Maven wrapper from Maven 3.3.3 to 3.9.9, because `tessera-maven-plugin` needs Maven
  3.6.3 or later.

### Configuration
- Removed `spring.thymeleaf.mode=HTML` from `application.properties`.

### Java
- Added `@TemplateView` to the entities the templates read: `Owner`, `Pet`, `PetType`, `Visit`,
  `Vet`, `Specialty` and `Vets`. The getters they inherit from `BaseEntity`, `NamedEntity` and
  `Person` resolve without the annotation. No controller had to change: every value the
  templates read was already in the model.

### Templates
- `fragments/layout.html`: now a typed fragment,
  `layout(Fragment template, String menu, String blankHref)`. `th:include="${template}"`
  became `t:insert`.
- `fragments/menu.html` (new): the `menuItem` fragment, moved out of the layout. It now takes
  `boolean active` because a Tessera fragment can't see the caller's `menu` variable. Links are
  passed in as `@{...}` literals in place of `__${path}__` preprocessing.
- In Thymeleaf, the `menuItem` definition `<li>` was also rendered where it was defined, with
  every parameter null: an empty first menu entry with `class="glyphicon  glyphicon-null"`
  and `href=""`. On error pages that empty link resolved to the failing request's path. To keep
  parity I wrote that entry statically in the layout and fill its `href` from the new
  `blankHref` parameter: `''` on normal pages and Boot's `path` attribute on `error.html`. It
  is a useless menu entry, but it was in the original markup. Deleting the `<li>` would remove
  it, at the cost of a parity difference on every page.
- `fragments/inputField.html` and `fragments/selectField.html`: the `*{__${name}__}` preprocessing
  became typed `Field` parameters (`input(String label, Field name, String type)` and
  `select(String label, Field name, Collection<PetType> items)`). The library root is now a
  `<t:block>`, replacing the `<html><body><form>` wrapper.
- Pages: typed `t:params`, and `t:replace` of the layout with `~{::body}` plus
  `t:remove="tag"`.
  - `${x['new']}` became `x.isNew()`.
  - `/owners/__${owner.id}__` became an `@{/owners/{id}(...)}` link. It is guarded for a `null`
    id, which `OwnerControllerTests.testProcessFindFormSuccess` produces.
  - Self-closed `<td .../></td>` became proper `<td></td>`, and rows written directly in a
    `<table>` got the `<tbody>` the browser used to add implicitly. The owner page writes that
    `<tbody>` only when the owner has pets, as the browser did.
  - Hidden `id`/`petId` inputs print `''` for a null value, because Thymeleaf wrote `value=""`
    and Tessera would otherwise drop the attribute.
  - In the visit form, the "previous visits" loop variable was renamed (`visit` → `previous`) so
    it no longer shadows the `visit` form object. The `t:if` still runs after `t:each`, as in
    Thymeleaf.

## Remaining difference (accepted)

`GET /owners/1/pets/999/edit`, a pet that doesn't exist, is a 500 in both apps, with the same
status and URL and the same error page. The only difference is the message text:

- old: `Exception evaluating SpringEL expression: "pet['new']" (template: "pets/createOrUpdatePetForm" - line 7, col 15)`
- new: `No message available`

The old text was Thymeleaf's internal exception message, which named the old engine and template
internals. Faking it from Java would mean the new app impersonates an engine it no longer has. The
controller's behaviour for a missing pet is unchanged: it still errors as before.

## What I could not convert

Nothing was left unconverted. No page needed a raw-HTML, script or style workaround.

## Where Tessera or its documentation fell short

1. **Fragment definitions rendered in place.** Thymeleaf renders a `th:fragment` element where it
   is defined, here the `menuItem` `<li>` inside the layout's menu. Neither the guide nor the
   `tessera-migrate` report says whether Tessera does the same, or that this
   changes the page. I found it only by reading the recorded HTML, and reproduced it statically.
2. **Empty Thymeleaf links on error dispatch.** `@{}` with an empty value rendered as `href=""`
   on normal pages but as the original request path on error pages. The converter didn't flag
   it, and I had to reproduce it with an extra layout parameter.
3. **`tessera-migrate` conversion output was incomplete.**
   - It left `th:include="${template}"` untouched in its output (`<t:block th:include=...>`).
     It was reported, but the output mixes namespaces.
   - The fragment libraries it produced had no `xmlns:t` declaration.
   - For `pets/createOrUpdateVisitForm.html` it generated `t:params="Object pet"` even though the
     template reads the `visit` form object (`t:object="visit"`, `visit.isNew()`). The stub missed
     a parameter and the report didn't mention it.
   - Its report says dynamic form-field paths have "a typed alternative tracked in #43", but
     `Field` parameters (guide 4.8) already are that alternative and worked. The message is out
     of date and misleading.
4. **Undocumented but working.**
   - Passing a link literal (`@{/owners/find}`) as a `String` fragment argument.
   - A conditional between two links (`c ? @{/a/} : @{/a/{id}(id=x)}`) as a guard for a null
     path variable. The guide says only "guard the link with `t:if`", which would drop the link
     element altogether.
5. **Error message on render failures.** When a template fails with a `NullPointerException`,
   Boot's error page shows "No message available". The guide says to "always render the whole
   error page" but doesn't mention that the message attribute changes when the failure moves
   from the engine to an NPE. That produced the one remaining compare difference.
6. **Build prerequisites are easy to miss.** The plugin's Maven 3.6.3+ requirement is one bullet
   in section 2. Apps like this one still ship an older `mvnw` (3.3.3 here), and the build fails
   only once someone uses the wrapper.

## Environment note (not a Tessera issue)

The sandbox has a 4 GB memory cgroup. With the original and migrated apps running at the same
time for `record`/`compare`, the surefire fork was OOM-killed (exit 137, "forked VM terminated
without properly saying goodbye"). With the apps stopped, `./mvnw clean verify` passes every
time I ran it.
