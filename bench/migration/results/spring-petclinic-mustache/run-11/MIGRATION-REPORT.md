# Migration report: Mustache (JMustache) → Tessera

## What changed

**Build (`pom.xml`)**
- Removed `spring-boot-starter-mustache` and `com.samskivert:jmustache`.
- Added `dev.tessera:tessera-spring-boot-starter` (Boot 3 / jakarta), the `tessera-compiler`
  annotation processor (listed after `spring-boot-configuration-processor`, which the build
  previously found by discovery), `-Atessera.templates/package/messages`, and the
  `tessera-maven-plugin` `check` goal so template-only edits recompile.
- No Mustache configuration existed in `application.properties`, so nothing to remove there.
  `mvn -B package` passes on JDK 21 with `java.version` 17 kept: 42 tests, 0 failures, plus
  spring-javaformat and nohttp checkstyle.

**Templates**: all 12 `.mustache` files deleted and replaced by `.html` Tessera templates at the
same view names (`welcome`, `error`, `owners/*`, `pets/*`, `vets/vetList`). The Mustache parent
layout `fragments/layout` is now a layout fragment `layout(Fragment content, List<Menu> menus)`.
The `inputField`/`selectField` partials are typed fragments that take an `InputField` / `SelectField`.

**Java**
- `@TemplateView` on the entities the templates read (`Owner`, `Pet`, `PetType`, `Visit`, `Vet`,
  `Specialty`), on `Application.Menu`, `InputField` and `SelectField`. `InputField` and
  `SelectField` got getters (Mustache read their public fields).
- `OwnerController.OwnerForm` and `PetController.PetForm` moved to public top-level classes
  `OwnerForm` / `PetForm` in the same package, because templates need public types. `PetForm`'s
  `nameField()`/`birthDate()`/`type()` became `getNameField()` and so on.
- Pagination entries were `Map<String,Object>` (not view-safe). They are now a `PageLink(int number,
  boolean current)` record in `OwnerController` and `VetController`.
- Controller and interceptor logic (`LayoutAdvice` menus, the bind-status-driven form fields,
  `errors`) is unchanged, so view names, models and redirects stay the same.

## Page parity (`tessera-migrate compare`)

My `pages.txt` has 47 requests. It covers every view, every form empty / with validation errors /
after a successful redirect, paginated and unpaginated lists, single-result redirect, not-found
search, an HTML-injection attempt, and the 404 and 500 error pages.

- Most pages come out `same`. The owner form with several errors on `telephone` comes out
  `reordered`, because Spring returns those messages in no fixed order.
- **Error pages (`/oups`, 404, `/owners/999`, the 500 from posting a pet with an empty birth date).**
  Under Boot's default `server.error.include-message=never` there is no `message` attribute.
  JMustache throws on the missing value, so the original page was **cut off after `<p>`**, with no
  footer or scripts. The Tessera page renders completely, with an empty `<p>`, as guide section 2
  requires ("Always render the whole error page"). With `--server.error.include-message=always`
  on both apps, all four error pages are `same`. So under default settings these four pages differ
  only in the part the original never rendered.
- **Paginated lists (`/owners` with more than 5 results, `/vets.html`): they differ and can't be
  made identical.** The original templates contain the typo `<span">`. A browser reads `span"` as
  the tag name, and the `</span>` tags never close it, so each page link and everything after it
  nests inside the previous one. Tessera rejects a quote in a tag name ("names cannot contain
  quotes"). The only other route is an unchecked `SafeHtml`, which is forbidden. The new pages use
  a normal `<span>` per page link. Text, links and order are identical; only the element nesting
  and tag name differ. This is broken markup the old engine passed through.
- Original behaviour I kept on purpose, because the pages are specified by the old app:
  - The vet table body is empty: the template reads `listVets`, but the controller provides `vets`.
  - The visit form has no input fields: the template read a `form` attribute that
    `VisitController` never provides.
  - Literal `th:text` / `th:value` attributes left over in the welcome, pet and visit markup are
    still output.
  - Menu highlighting is still decided by `LayoutAdvice`.
  - A 500 on an empty pet birth date: `PetForm` calls `toString()` on a null date.

  These are bugs in the original, and fixing them would break parity. They are worth fixing
  separately.

## Security
No `TrustedUrl.unchecked`, no raw output, no security config touched (the app has no Spring
Security). All values are escaped by context. The old Mustache `{{...}}` escaped as well, so the
`<i>Ann</i>` and `<b>x</b>` test inputs render as identical text. Mustache's view resolver is gone,
and the startup log has no "another template engine is still configured" warning.

## Not converted / left as is
- The Maven `artifactId` is still `spring-petclinic-mustache`. It's the project's name, not a
  dependency, and renaming it would change the jar name that scripts and CI start.

## Where Tessera or its documentation fell short
1. **Broken markup can't be reproduced.** A page whose browser DOM depends on a malformed tag
   (`<span">`) has no Tessera equivalent. The guide calls such differences acceptable, but
   `compare` still exits non-zero, and there is no way to mark a known difference as accepted
   except a `--ignore` regex. That doesn't help with a structural difference.
2. **Error pages vs. JMustache truncation.** The guide's Mustache note says to compare against an
   original that has the value. But under Boot ≥ 2.3's defaults the original never has `message`,
   so the default-config recording can't be matched. I had to record a second baseline with
   `server.error.include-message=always` to check the error page.
3. **Annotation processor paths.** The build example doesn't say that processors the build found
   by discovery (here `spring-boot-configuration-processor`) must be listed explicitly with a
   version. `${spring-boot.version}` isn't defined under `spring-boot-starter-parent`, which gives
   the unhelpful Maven error "version can neither be null, empty nor blank".
   `${project.parent.version}` works.
4. Nested model classes are rejected with a clear message, but only through the
   containing-class rule: Spring controllers are usually package-private, so per-page form
   classes nested in controllers have to be moved out. A note in section 4.2 would save a
   compile round.
5. The guide doesn't say whether arbitrary static namespaced attributes (`th:value="*{id}"`) are
   copied through. They are, but I had to find out by compiling.
