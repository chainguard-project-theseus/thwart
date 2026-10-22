# Migration report: Thymeleaf → Tessera 0.1.0-SNAPSHOT

## Result

- All 12 templates under `src/main/resources/templates` are now Tessera templates, compiled to
  `org.springframework.samples.petclinic.templates.*Template` classes during `javac`. Thymeleaf is
  gone: no `spring-boot-starter-thymeleaf` / `spring-boot-starter-thymeleaf-test` dependency, no
  `spring.thymeleaf.*` property, and no `th:` attributes are left.
- `./mvnw clean verify` passes: 79 tests, 0 failures. The 2 skipped tests are the MySQL
  Testcontainers tests, which skip because there is no Docker here (they were skipped before the
  migration too).
- **Checking that the HTML is the same.** Before changing anything I built the Thymeleaf version,
  started it, and saved the HTML of 45 requests. After the migration I ran the same requests
  against the Tessera build and compared the two after normalising whitespace and attribute order.
  The requests covered:
  - every page, including paged owner and vet lists;
  - the error page for 404, for an exception (`/oups`) and for a missing owner;
  - every form with validation errors: owner create/edit, pet create/edit (blank fields, duplicate
    name, future birth date, unparseable date), visit (past date, blank description), and owner
    search with no match;
  - flash messages after create and update;
  - the German locale;
  - user data containing HTML, which is escaped the same way as before.

  The only differences left are:
  1. **`ownersList`**: the old template had `<a th:href=… th:text=… /></a>`, and Thymeleaf sent the
     stray `</a>` to the browser. Tessera rejects mismatched end tags, so the new template has one
     `<a>…</a>`. Browsers ignore a stray end tag, so the page looks and behaves the same.
  2. **Order of several errors on one field** (for example, a blank telephone fails both
     `@NotBlank` and `@Pattern`). Hibernate Validator returns these in a different order from one
     request to the next, with Thymeleaf as well as with Tessera, so this is not caused by the
     migration.
  3. **`jsessionid`** values in URLs differ because they are random.

## What changed

### Build (`pom.xml`, `build.gradle`)
- Replaced `spring-boot-starter-thymeleaf` with `dev.tessera:tessera-spring-boot-starter`, and
  removed `spring-boot-starter-thymeleaf-test`.
- Added `tessera-compiler` as an annotation processor, with `-Atessera.templates`,
  `-Atessera.package=org.springframework.samples.petclinic.templates` and `-Atessera.messages`. The
  last one means every `#{key}` is checked against `messages.properties` at build time. Also added
  `-parameters`.
- Raised `java.version` from 17 to **21**, because Tessera requires Java 21 or later.
- `build.gradle`: made the same changes (Tessera starter, compiler as `annotationProcessor`, the
  `-A` options, Java 21) and added `mavenLocal()`, the only place the snapshot is installed. The
  guide says Gradle is not supported; `./gradlew compileJava` did compile all templates, but I did
  not run the Gradle tests.
- `application.properties`: removed `spring.thymeleaf.mode=HTML`.

### Java
- Added `@TemplateView` to the JPA entities the templates read: `Owner`, `Pet`, `Visit`,
  `PetType`, `Vet` and `Specialty`. Getters inherited from the `@MappedSuperclass`es (`BaseEntity`,
  `Person`, `NamedEntity`) are found through them.
- `WebConfiguration`: registered a `ResourceUrlEncodingFilter`. Spring Boot only registers it
  automatically when Thymeleaf or FreeMarker is present. Without it, links to webjars lost their
  version (`/webjars/bootstrap/dist/...` instead of `/webjars/bootstrap/5.3.8/dist/...`). See
  "Where the guide or Tessera fell short", item 1.
- No controller changes were needed. Every template reads exactly the model attributes the
  controllers already set, and the view names are unchanged.

### Templates
I ran `tessera-migrate` on a copy of the templates, then finished the conversion by hand:
- **`fragments/layout.html`**
  - `layout(Fragment template, String menu)`.
  - `menuItem` gets `menu` as an explicit parameter, because a fragment cannot see its caller's
    variables.
  - `th:href="@{__${link}__}"` was preprocessing, which Tessera does not support. Callers now
    pass a link expression instead (`menuItem(@{/owners/find}, …)`), so the context path and
    `encodeURL` still apply.
  - `th:class="nav-item"` became a static `class`.
- **`fragments/inputField.html` and `fragments/selectField.html`**
  - `*{__${name}__}` became a typed `Field name` parameter.
  - `minVisitDate` was read from the caller's model. It is now a `LocalDate` parameter: the visit
    form passes it, and the other forms pass `null`, which leaves out `min`, as before.
  - In the select, options use `item.name`, which is the same text that the old `toString()` and
    `PetTypeFormatter` produced.
  - The old span had both `th:errors` and `th:text="#{error}"`, and `th:errors` won. In Tessera
    `t:errors` also wins, so the span keeps both attributes. The `t:text` stays so that the
    `I18nPropertiesSyncTest` regex still passes.
- **`owners/ownerDetails.html`**
  - `@{__${owner.id}__/edit}` and similar became page-relative links with path variables
    (`@{{ownerId}/edit(ownerId=owner.id)}`).
  - `*{firstName + ' ' + lastName}` became `owner.firstName + ' ' + owner.lastName`, because
    Tessera's `*{}` allows only a single property path.
  - `message` and `error` (flash attributes) are now `String` parameters.
- **`owners/ownersList.html`, `vets/vetList.html`**
  - Links built from strings became link parameters (`@{/owners(page=currentPage + 1)}`).
  - `#strings.listJoin` became `strings.join`.
  - Self-closed `<td/>` and `<span/>` became explicit pairs.
  - The owner link is
    `owner.id != null ? @{/owners/{ownerId}(ownerId=owner.id)} : @{/owners/null}`. Thymeleaf wrote
    `/owners/null` for an unsaved owner, while Tessera stops the whole render on a null path
    variable. `OwnerControllerTests.processFindFormSuccess` renders an owner without an id.
- **`pets/createOrUpdatePetForm.html`**
  - `${pet['new']}` became `pet.isNew()`.
  - The hidden id input is `t:value="pet.id != null ? '' + pet.id : ''"`, so that a new pet still
    renders `value=""` as Thymeleaf did. A plain `t:value="*{id}"` leaves out the attribute.
- **`pets/createOrUpdateVisitForm.html`, `error.html`, `findOwners.html`, the owner form,
  `welcome.html`**
  - Parameters are typed: `Visit visit, Pet pet, Owner owner, LocalDate minVisitDate`;
    `Integer status, String message`; and so on.
  - `#fields.hasAnyErrors()` / `#fields.allErrors()` became `fields.hasErrors('*')` /
    `fields.errors('*')`.
  - `${pet.type}` (printed with `toString()`) became `pet.type?.name`, which gives the same text.

### Tests
- `I18nPropertiesSyncTest`: the one test change. Its regex looked for Thymeleaf's attribute name
  `th:(u)?text`, and now looks for `\bt:(u)?text`. Nothing else in the test changed, and no test
  was deleted or disabled.

### Security
- No `SafeHtml.unchecked` or `TrustedUrl.unchecked` (there is no `t:utext` at all). No Spring
  Security config was touched, so there is no new `permitAll`. Escaping was not turned off.
- The app has no Spring Security, so it has no CSRF tokens, before or after.
- I checked that user data containing HTML is escaped exactly as before (`<b>x</b>` in a form
  value, `O'Brien<x>` in a name, `<i>check</i>` in a visit description).

## Could not convert exactly
- The stray `</a>` in `ownersList` (see "Result", item 1). It is invalid markup and Tessera rightly
  rejects it.

Everything else renders the same.

## Where the guide or Tessera fell short
1. **Versioned webjar URLs broke without warning.** Section 12 says links go through
   `response.encodeURL`, but does not say that Spring's `ResourceUrlEncodingFilter`, which makes
   that call return versioned resource URLs, is only auto-registered by Boot's Thymeleaf and
   FreeMarker auto-configuration. The starter does not register it either. After removing
   Thymeleaf every page linked to `/webjars/bootstrap/dist/...` instead of
   `/webjars/bootstrap/5.3.8/dist/...`. I had to register the filter myself.
2. **`tessera-migrate` left gaps**:
   - It left out `visit` from the `t:params` stub of `pets/createOrUpdateVisitForm.html`, although
     the template uses it in `t:object` and `t:if`.
   - It rewrote string-built links that contain a variable, but left `@{'/owners?page=1'}` and
     `@{'/vets.html?page=1'}` (no variable at all) as dynamic links. It did report them, but only
     as "link with a non-literal path", when the fix is a mechanical `@{/owners(page=1)}`.
   - It turned `th:text=" ${visit.description}"` into `t:text=" visit.description"`. That works,
     but only by luck.
   - Its report points to "issue #43" for a typed field alternative, but the guide already
     documents `Field` parameters, and there is no issue tracker.
3. **`t:text` together with `t:errors`** on one element compiles without any diagnostic, and
   `t:text` is silently ignored. That matches Thymeleaf here, but the guide only lists
   `t:text`+`t:utext` and `t:insert`+`t:replace` as conflicting, and says nothing about which of
   `t:text` and `t:errors` wins.
4. **A null attribute value removes the static fallback too.** `value="" t:value="*{id}"` renders
   no `value` attribute when the id is null. The guide lists "null omits the attribute" as an
   expected difference, but offers no way to keep Thymeleaf's `value=""`. I needed a
   `'' + …` string conditional for byte-identical output.
5. **A null link path variable stops the whole render.** This is documented, but it is a
   behaviour change: Thymeleaf's `__${…}__` rendered `null`. It broke an existing test until the
   template guarded against null.
6. **Undocumented but working**:
   - link expressions as fragment arguments (`~{::menuItem(@{/}, …)}`);
   - link expressions as branches of `?:`;
   - `null` passed for a typed fragment parameter.

   I relied on all three.
7. **`strings.join` calls `toString()`** on the elements of a non-String iterable (here
   `List<Pet>`, which renders the names through `NamedEntity.toString()`). The guide does not say
   what `join` does with non-String elements, and it is the one place a template reaches an
   entity's `toString()`.
8. **Java version.** The guide says Java 21+, so the project's `java.version` had to go from 17 to
   21. Anyone on Java 17 can no longer build the project.
9. **Gradle**: section 17 says it is not supported, yet `annotationProcessor` plus `-A` arguments
   compiled every template. The guide could say what "unsupported" means here. Template-only
   changes do not invalidate Gradle's compile task either, so I declared the template and message
   directories as inputs.
10. **Error pages**: the guide does not say whether Boot's `/error` handling (`status`, `message`
    and so on) works with the starter. It does: the `error` view is rendered by Tessera rather than
    the whitelabel page, and `status` binds to `Integer`. The guide should say so, since the
    starter provides no `TemplateAvailabilityProvider`.
11. **Incremental builds** (section 17): after editing only a template you have to run
    `mvn clean`. That is documented, but easy to trip over.
