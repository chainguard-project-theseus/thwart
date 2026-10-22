# Migration report: Thymeleaf to Tessera

## Result

- All 12 templates (10 pages plus 3 fragment files, including `error.html`) are now Tessera
  templates. They compile to `org.springframework.samples.petclinic.templates.*Template` during
  `javac`.
- Thymeleaf is gone: `spring-boot-starter-thymeleaf`, `spring-boot-starter-thymeleaf-test`,
  `spring.thymeleaf.mode` and every `th:` attribute are removed.
- `mvn clean verify` passes: 80 tests, 0 failures, 2 skipped. The 2 skipped are
  `MySqlIntegrationTests`, which need Docker; they were skipped before the migration too.
- Rendered HTML was compared with the Thymeleaf version, page by page. Both apps (the original
  built from `git archive HEAD`, and the migrated one) ran against the same H2 data, and the same
  44 requests went to each:
  - every GET page, in English and German;
  - every form after failed validation: owner, pet (missing fields, a future date, an unparseable
    date) and visit;
  - flash messages after create and update;
  - pagination;
  - the "not found" search;
  - the 404 page, the 500 page (`/oups`) and the page for an unknown owner;
  - user data containing `<script>`, `"`, `'` and `&`, both stored and echoed back after a failed
    bind.

  The output was normalised (whitespace, attribute order, `jsessionid` values) and diffed. Every
  response matches, with these exceptions:
  1. `owners/ownersList`: the old template had `<a ... /></a>`. Thymeleaf wrote out the stray
     `</a>` as is; Tessera rejects unmatched end tags, so the stray tag is gone. Browsers ignore a
     stray `</a>`, so the parsed page is the same.
  2. When one field has two errors (for example `telephone` blank and not 10 digits), they can
     come out in either order. This is not caused by the migration: the order of Bean Validation
     violations changes between JVM runs, for the old app as well as the new one (checked by
     restarting each app three times).
- Status codes and redirects are the same for every request.

## What changed

### Build

- `pom.xml`:
  - replaced the Thymeleaf starter with `dev.tessera:tessera-spring-boot-starter`
    (`${tessera.version}` = `0.1.0-SNAPSHOT`);
  - added `tessera-compiler` as an annotation processor, with `-Atessera.templates`,
    `-Atessera.package` and `-Atessera.messages`, so every `#{key}` is checked at build time;
  - added `-parameters`;
  - removed `spring-boot-starter-thymeleaf-test`;
  - raised `java.version` from 17 to 21, because Tessera needs Java 21.
- `build.gradle`: made the same changes (starter, `annotationProcessor`, compiler arguments,
  `mavenLocal()` because the snapshot exists only in `~/.m2`, Java 21). This is untested: the
  guide says Gradle is not supported, and the task asks for the Maven build only.
- `application.properties`: removed `spring.thymeleaf.mode=HTML`.

### Java

- `Owner`, `Pet`, `Visit`, `PetType`, `Vet` and `Specialty` are annotated `@TemplateView`. This
  makes them view-safe. The getters they inherit from the `@MappedSuperclass`es (`BaseEntity`,
  `Person`, `NamedEntity`) resolve through them.
- `WebConfiguration` now registers `ResourceUrlEncodingFilter` for the REQUEST and ERROR
  dispatches. Without it, the WebJars links lost their version
  (`/webjars/bootstrap/dist/...` instead of `/webjars/bootstrap/5.3.8/dist/...`), which changed
  the HTML of every page. The filter was registered by Spring Boot's Thymeleaf auto-configuration,
  so it disappeared with Thymeleaf; see the shortfalls below.

### Templates

I started from the output of `tessera-migrate` and finished each template by hand:

- **Layout** (`fragments/layout.html`):
  - `layout(Fragment template, String menu)`.
  - `menuItem` used to read the caller's `menu` variable and built its link with
    `@{__${link}__}`. Tessera fragments only see their parameters and link paths must be literal.
    `menuItem` now takes `menu` as a parameter, and each call passes an already-built link
    (`@{/owners/find}`) as a `String`. The rendered `href` is the same.
  - `th:class="nav-item"` became a plain `class="nav-item"`.
- **Form fields** (`fragments/inputField.html`, `fragments/selectField.html`):
  - the dynamic `*{__${name}__}` field paths became typed `Field` parameters.
  - The date input used to read the page's `minVisitDate` ambiently. It now takes an explicit
    `LocalDate min` parameter: the visit form passes `minVisitDate` and the other forms pass
    `null`, so their output is unchanged.
  - `select` takes `Collection<PetType>` and renders `item.name`. Thymeleaf rendered the same
    string through `PetTypeFormatter` / `toString()`.
  - The old error span had both `th:errors` and a redundant `th:text="#{error}"`. It now has only
    `t:errors`, with an empty prototype body.
- **Links with `__${...}__`**:
  - `ownerDetails` uses page-relative `@{{id}/edit(id=owner.id)}` links and similar ones;
  - pagination uses `@{/owners(page=...)}` and `@{/vets.html(page=...)}`.
  - The owner link in `ownersList` falls back to `@{/owners/}` when the id is `null`. Thymeleaf
    rendered `/owners/` for a null id, but a `null` Tessera path variable fails the render. The
    existing `OwnerControllerTests` exercise this case.
- **`${x['new']}`** became `x.isNew()`.
- **`*{firstName + ' ' + lastName}`** became `owner.firstName + ' ' + owner.lastName`. Tessera
  selection expressions take a single property path only.
- **Hidden `id` in the pet form**: Thymeleaf wrote `value=""` for a null `*{id}`, but a null
  Tessera attribute is omitted. The form now uses `t:value="pet.id != null ? '' + pet.id : ''"`
  to keep `value=""`.
- **Pet type names**: `${pet.type}` (which relied on `toString()`) became `pet.type?.name`.
- **Self-closing tags**: `<span ... />` with `t:text` was rewritten as `<span></span>` in the pet
  form.
- **Parameter types**:
  - `error.html`: `Integer status, String message`;
  - `ownerDetails`: `String message, String error, Owner owner` (the first two are flash
    attributes);
  - lists and pagination: `List<Owner>` / `List<Vet>`, `int totalPages`, `int currentPage`;
  - forms: `Owner`, `Pet`, `Visit`, `Collection<PetType> types`, `LocalDate minVisitDate`.

### Tests

- `I18nPropertiesSyncTest.checkNonInternationalizedStrings`: the regex that recognises
  "this element's text is replaced by a translated value" matched `th:(u)?text` only. It now
  matches `(th|t):(u)?text`. This is the only change to an existing test, and it is there because
  the regex encoded the old engine's attribute name. The rest of the check is unchanged and still
  runs against every template.
- Added `OwnerControllerTests.showOwnerEscapesUserData`. It renders an owner whose name contains
  `<script>` and checks that the name comes out escaped, as the guide's checklist recommends.

### Security

- There is no `SafeHtml.unchecked`, `TrustedUrl.unchecked` or `t:utext` anywhere.
- No security configuration was touched. The app has no Spring Security, so there is no CSRF to
  keep; Tessera adds the token automatically if Security is added later.
- All output goes through Tessera's contextual escaping. The comparison above confirmed it: user
  data with `<script>`, `<img onerror>` and quotes is escaped exactly as before, in text and in
  `value` attributes.

## Not converted / known differences

- The stray `</a>` in `ownersList` (see above). It cannot be expressed in Tessera, and it was
  invalid markup anyway.
- Nothing else was left unconverted.
- `README.md` still says the upstream project is "based on Spring Boot and Thymeleaf". That
  sentence describes the canonical upstream project, not this build, so I left it.

## Where the guide or Tessera fell short

1. **The `ResourceUrlEncodingFilter` disappears with Thymeleaf.**
   - What happened: Spring Boot's Thymeleaf auto-configuration registers this filter (REQUEST and
     ERROR dispatch) whenever the resource chain is enabled, which is the case with
     webjars-locator. Removing the starter silently drops it, so every versioned WebJars link
     changes.
   - Why the guide missed it: section 12 only says links go through `response.encodeURL` and
     that you should "remove `spring-boot-starter-thymeleaf`". It doesn't warn that the starter
     also registered this filter, and the Tessera starter doesn't register it.
   - Workaround: I registered the filter in `WebConfiguration`.
2. **`tessera-migrate` output needed substantial fixing.**
   - It left `@{'/owners?page=1'}` (a quoted literal path) unconverted, without reporting it.
   - It reported page-relative `@{__${owner.id}__/edit}` only as "inline the dynamic part", and
     didn't suggest `@{{id}/edit(id=...)}`, which the guide documents.
   - It put `t:params="Object minVisitDate"` on the root of `fragments/inputField.html`. That is
     useless, because a fragment never sees page parameters; it should have flagged the ambient
     read instead.
   - It left `visit` out of the `t:params` stub of `createOrUpdateVisitForm.html`, even though
     the template reads `visit.isNew()` and uses `t:object="visit"`.
     I added `Visit visit` explicitly.
   - Its report points to "a typed alternative is tracked in #43". There is no issue tracker;
     the alternative is the `Field` parameter from section 11.
3. **The guide's migrator command (section 14) uses `tessera-migrate/target/...jar`**, a
   source-tree path. The jar only exists in `~/.m2/repository/dev/tessera/tessera-migrate/`.
4. **Some combinations the guide doesn't cover**, which I found by trying them; all three
   compiled and rendered correctly:
   - a link `@{...}` as a fragment argument;
   - a link as a branch of a `?:` conditional;
   - a loop variable shadowing a template parameter (`t:each="visit : pet.visits"` next to the
     `Visit visit` parameter, with `t:if` reading the loop variable).
5. **Behaviour differences from Thymeleaf that needed workarounds:**
   - A `null` link path variable throws, where Thymeleaf rendered an empty segment. This is
     documented, but there is no null-safe link form, so a conditional is needed.
   - A `null` attribute value is omitted, where Thymeleaf wrote `value=""`.
   - Neither is fixed for you by the migrator or flagged in its report.
6. **Unspecified behaviour:**
   - The guide doesn't say what `t:text` does with a `@TemplateView` object (for example a JPA
     entity whose `toString()` is meaningful), or what `strings.join` does with a list of them.
     I avoided relying on it for `pet.type`; for `strings.join(owner.pets, ', ')` the output
     matched Thymeleaf's `#strings.listJoin`.
   - It doesn't say whether `t:errors` and `t:text` may share an element (Thymeleaf allowed it).
     I removed the redundant `t:text`.
7. **Gradle is listed as unsupported**, but the repository ships a `build.gradle`. I updated it
   by analogy and did not test it.
8. **Stray end tags can't be reproduced.** Markup that Thymeleaf passed through unchanged (a
   stray `</a>`) can't be kept byte-for-byte, so pages using it can't be identical in raw HTML.
