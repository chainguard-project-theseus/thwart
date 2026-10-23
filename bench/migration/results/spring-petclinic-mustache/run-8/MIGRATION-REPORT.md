# Migration report: Mustache (JMustache) → Tessera

## Result

| Check | Status |
|---|---|
| `mvn clean verify` (JDK 21, `java.version` 17) | passes, 44 tests (42 existing + 2 new) |
| Old engine removed | `spring-boot-starter-mustache` and `jmustache` deps and all 12 `*.mustache` templates are gone. There was no Mustache-specific configuration in `application.properties`. |
| Page parity (`pages.txt`, 45 requests) | 39 `same` (the empty-owner POST sometimes comes out `reordered`), 6 `differs`. All 6 come from one cause, the `<span">` typo explained below. |
| Security | no `SafeHtml.unchecked` / `TrustedUrl.unchecked`, no raw output of any kind, no `permitAll`, CSRF/escaping untouched (the app has no Spring Security), no test disabled or changed |

## What changed

**Build (`pom.xml`)**
- Replaced `jmustache` + `spring-boot-starter-mustache` with `dev.tessera:tessera-spring-boot-starter`
  (jakarta, Boot 3.2 is unchanged).
- `maven-compiler-plugin`: `annotationProcessorPaths` has `tessera-compiler` and
  `spring-boot-configuration-processor`. I listed the second one because `annotationProcessorPaths`
  turns off processor discovery. Also added `-Atessera.templates`, `-Atessera.package`,
  `-Atessera.messages` and `<parameters>true</parameters>`.
- I kept the artifactId `spring-petclinic-mustache` so the jar name, and any scripts that start
  the app, stay the same. It is only a name.

**Templates** (`src/main/resources/templates/**/*.html`, one for each old template)
- `fragments/layout.html`: a `layout(List<Application.Menu> menus, Fragment body)` fragment,
  plus a parameterless `head` fragment. This replaces the Mustache parent `{{<fragments/layout}}`
  with its `{{$body}}` block. Each page does
  `t:replace="~{fragments/layout :: layout(menus, ~{::body})}"` with `<body t:remove="tag">`.
- `fragments/navbar.html`: the menu bar as `navbar(menus)`. The layout and the error page both
  use it.
- `fragments/inputField.html` and `fragments/selectField.html`: fragments that take the existing
  `InputField` / `SelectField` view models. They replace the Mustache partials, which got their
  data implicitly from the context stack.
- `error.html`, `welcome.html`, `owners/*`, `pets/*`, `vets/vetList.html`: the same markup with
  typed `t:params`. Relative links (`3/edit`, `3/pets/7/visits/new`) are page-relative `@{...}`
  links. Pagination links are `@{/owners(page=...)}`.

**Java**
- `@TemplateView` on `Owner`, `Pet`, `PetType`, `Visit`, `Vet`, `Specialty`, `Application.Menu`,
  `InputField`, `SelectField`, `OwnerForm` and `PetForm`.
- `OwnerForm` / `PetForm` were package-private nested classes of the controllers with
  package-private accessors. They are now public top-level classes in `owner` with public getters,
  because the generated template classes live in another package. They work as before.
- `InputField` / `SelectField` got public getters (Mustache read their public fields).
- Pagination: the `List<Map<String,Object>>` of pages, which is not view-safe (`Object`), is now
  `List<PageLink>`. `PageLink` is a new record `(int number, boolean current)`.
- `LayoutAdvice` (the interceptor that adds `menus`, the field `errors` and the form bind status)
  is unchanged. It is application code, not engine configuration, and it feeds Tessera's typed
  parameters the same way it fed Mustache.

**Tests**
- No existing test was changed. They assert only view names and model attributes, and pass
  unchanged.
- Added `OwnerFormRenderingIntegrationTests`. It renders through the real resolvers and checks
  that `<script>` in a rejected form value comes out escaped, and that the owner page renders with
  its relative links and active menu.

## Parity: the remaining differences and why

`pages.txt` (in the repo root) covers: home; `/oups` (500); unknown URL (404); a missing owner
(500); `/vets` JSON with Accept html (406); vets list pages 1 and 2; find owners (empty, not found,
a single match that redirects, several matches, all owners on pages 1 and 2); owner details;
create and edit owner (empty, with validation errors, HTML in input, success); create and edit pet
(500 on empty birth date, type mismatch, unknown type, duplicate name, success); visit form (empty
description, bad date, `<script>` in description, success, and the result shown after). `baseline/`
holds the recording of the original app.

- **6 `differs`: `/vets.html`, `/vets.html?page=2`, `/owners`, `/owners?page=2`,
  `/owners?lastName=&page=2`, `/owners?lastName=`.** The old pagination markup had a typo,
  `<span">`. A browser parses that as an unknown element named `span"`, and because the matching
  `</span>` doesn't close it, the rest of the pagination bar nests inside it. Tessera's parser
  rejects the typo at build time (`malformed markup: Incomplete structure: "<span"`; I tried it).
  The only ways to emit it anyway are raw-output escape hatches: `SafeHtml.unchecked`, or a
  hand-written `Fragment`/`Template` that writes to `Output` (which `SafeHtml.of` would wrap). That
  would be an escaping bypass, so I didn't do it. The new pages render the intended valid
  `<span>`. Text, links and order are identical. Only the bogus element name and its nesting
  differ. This is the "broken markup the old engine passed through" case.
- **1 `reordered`: `POST /owners/new` with all fields empty.** The two telephone messages
  (`must not be empty`, `numeric value out of bounds`) come from Bean Validation in no fixed order.
  The order changes between runs of the old app too.

### Old behaviour kept on purpose (to match the old pages)
- **Error page.** The old page threw while rendering `{{message}}` (Boot 3 leaves `message` out
  of the error attributes by default). The response was cut off after `<p>`, so there was no footer
  and no Bootstrap script. `error.html` reproduces what users saw: layout head and navbar, the
  heading, the message if Boot includes it (`server.error.include-message`), and no footer. It no
  longer throws.
- **Vets list table is empty.** The old template iterated `listVets`, which the controller never
  puts in the model (it adds `vets`), so the table body was always empty. The Tessera template
  keeps reading `listVets` so the page matches. Fixing this is a one-line controller change
  (`model.addAttribute("listVets", vets.getVetList())`), but it changes the page, so I left it as a
  known pre-existing bug.
- Literal leftover attributes from the app's Thymeleaf past (`th:text="#{welcome}"`,
  `th:value="*{id}"`, `th:value="${pet.id}"`) were printed as-is by Mustache. They are kept as
  static attributes so the pages match. They have no effect.
- The pet form still throws (500) on an empty birth date (`PetForm` calls `toString()` on null),
  as before.

## Where Tessera or its documentation fell short

1. **Broken legacy markup can't be reproduced.** The parser rejects malformed tags such as
   `<span">`, and there is no sanctioned "verbatim static markup" construct. For parity the only
   options are a raw-output bypass or accepting the difference. The guide calls such differences
   "right", but `compare` still fails them and has no way to record that a difference is accepted,
   apart from `--ignore`, which deletes text and can't express a DOM-structure difference.
2. **`Fragment` and `Template` are public interfaces that write straight to `Output`.** Any Java
   class implementing them, and `SafeHtml.of(customTemplate, ctx)`, gives unchecked raw HTML
   without calling `SafeHtml.unchecked`. The guide presents `unchecked` as the one review point.
   This second route isn't mentioned, and a grep for `unchecked` won't find it.
3. **A parameterized fragment nested in another fragment is not rendered in place.** I defined
   `<nav t:fragment="navbar(menus)">` inside the layout fragment. The layout then rendered without
   the navbar, with no warning. The guide only says a parameterized fragment "is skipped when its
   template renders as a page". The fix was to move it to its own file and call it.
4. **Template-only changes don't rebuild.** `mvn package` after editing only a template silently
   reuses the old generated classes. The guide mentions this once, but it is easy to miss in a
   compare loop: always use `mvn clean ...`.
5. **No Mustache guidance.** The guide covers Thymeleaf in detail (and `tessera-migrate` converts
   only Thymeleaf). Mustache's context-stack lookup (`{{#owner}}{{firstName}}`, `{{owner.id}}`
   resolving from an outer section, `{{^new}}` on `isNew()`) and parent/partial templates had to
   be mapped by hand. In particular, nothing warns that Mustache silently printed nothing for
   missing keys (`listVets`), which Tessera surfaces only as `null`.
6. `t:field` writes an `id` attribute unless one is present, which changes markup for apps
   that never had ids on inputs. The guide doesn't say how to suppress it. I kept the app's existing bind-status view
   models (`InputField`/`SelectField`) instead of `t:object`/`t:field`, which also kept the exact
   old error-message and value behaviour.
7. Minor: the guide's build snippet has no example for Spring Boot's own configuration processor.
   `${spring-boot.version}` is not defined under the Boot 3.2 parent, so I used
   `${project.parent.version}`.
