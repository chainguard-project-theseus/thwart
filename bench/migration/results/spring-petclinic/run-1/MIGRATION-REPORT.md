# Migration report: Thymeleaf → Tessera

## Result

- All 12 templates under `src/main/resources/templates` are now Tessera templates, compiled to
  Java classes (`org.springframework.samples.petclinic.templates.*`) by the annotation processor.
  No Thymeleaf template, dependency or property is left.
- `mvn clean package` passes: 80 tests, 0 failures. That is the 79 existing tests plus one new
  escaping test. 2 tests are skipped, as before the migration: the MySQL Testcontainers tests,
  which need Docker.
- Output check. Before changing anything I built the Thymeleaf version and captured 36 responses
  with curl against the running jar:
  - every page
  - `?lang=de`
  - every form, both empty and with validation errors (including a type-conversion error)
  - the flash-message redirects
  - 404, 500 (`/oups`) and the IllegalArgumentException error page
  - an owner whose name contains `<script>` and quotes
  - pagination, including the out-of-range redirect

  I captured the same requests against the Tessera build and compared them after parsing: tags,
  sorted attributes and text with whitespace collapsed, with the random `jsessionid` masked. The
  only remaining difference is the stray `</a>` described below.

## What changed

### Build
- `pom.xml`:
  - `spring-boot-starter-thymeleaf` and `spring-boot-starter-thymeleaf-test` are replaced by
    `dev.tessera:tessera-spring-boot-starter` (`${tessera.version}` = `0.1.0-SNAPSHOT`).
  - `maven-compiler-plugin` now runs `tessera-compiler` as an annotation processor, with
    `-Atessera.templates`, `-Atessera.package` and `-Atessera.messages`, so message keys are
    checked at build time. `-parameters` is also set.
- `build.gradle`: the same change (starter, `annotationProcessor`, compiler args, `mavenLocal()`
  for the snapshot). The guide says Gradle is not supported. I checked that `./gradlew
  compileJava` generates the templates, but only after temporarily switching the toolchain to 21:
  this machine has no JDK 17, which the Gradle build asks for, so as committed it cannot run here
  (that was already true before the migration).
- `application.properties`: removed `spring.thymeleaf.mode=HTML`.

### Java
- `@TemplateView` is added to the JPA entities the pages read: `Owner`, `Pet`, `Visit`,
  `PetType`, `Vet`, `Specialty`. Getters from the `@MappedSuperclass`es resolve through them.
- `WebConfiguration` now registers Spring's `ResourceUrlEncodingFilter` itself.
  - Before, Spring Boot's Thymeleaf auto-configuration registered it. That filter is what turns
    `@{/webjars/bootstrap/dist/js/...}` into the versioned `/webjars/bootstrap/5.3.8/dist/js/...`.
  - Without it, every page's font-awesome and bootstrap URLs changed.
  - Tessera links go through `response.encodeURL`, so the filter works with them unchanged.
- No controller changes were needed. Every page's data was already in the model under the names
  the templates use.

### Templates (started with `tessera-migrate`, then finished by hand)
- **Typed parameters** on every page. The migrator missed `Visit visit` and
  `LocalDate minVisitDate` on the visit form. `error.html` takes `Integer status, String message`.
- **Layout** (`fragments/layout.html`):
  - `layout(Fragment template, String menu)`.
  - `menuItem` used `@{__${link}__}` and read the caller's `menu`. It now takes
    `String link, ..., String menu`, and callers pass a literal link expression (`@{/owners/find}`)
    plus `menu`.
  - `th:class="nav-item"` became a plain `class="nav-item"`.
- **Form field fragments** (`inputField`, `selectField`):
  - `*{__${name}__}` became a `Field name` parameter.
  - `inputField` read the page's `minVisitDate` ambiently. It is now a fourth parameter,
    `LocalDate minVisitDate`: the visit form passes it, and the other forms pass `null`, so the
    `min` attribute is omitted as before.
  - `th:errors` + `th:text="#{error}"` on one span became just `t:errors`. Thymeleaf's
    `th:errors` overwrote the text anyway.
  - The select options use `item.name` for value and text, which is the same string that
    `PetTypeFormatter` and `toString()` produced.
- **Links**: `@{__${owner.id}__/edit}`-style links became path variables
  (`@{{ownerId}/edit(ownerId=owner.id)}`). String-concatenated pagination links became
  `@{/owners(page=i)}` and `@{/vets.html(page=currentPage + 1)}`.
- **SpEL / utility objects**:
  - `x['new']` became `x.isNew()`.
  - `#fields.hasAnyErrors()` / `allErrors()` became `fields.hasErrors('*')` /
    `fields.errors('*')`.
  - `#strings.listJoin` became `strings.join`.
  - `#temporals.format` became `temporals.format`.
  - In `ownerDetails`, the `th:object="${owner}"` on a table, which used multi-property `*{...}`,
    became plain `owner.x` expressions.
- **Thymeleaf-compatible output kept on purpose**:
  - New pet form, hidden `id` input: Tessera omits an attribute whose value is `null`, but
    Thymeleaf wrote `value=""`. It now uses `t:value="pet.isNew() ? '' : '' + pet.id"`, so the
    output stays `value=""`.
  - Owners list: Thymeleaf rendered `@{/owners/__${owner.id}__}` with a null id as `/owners/null`,
    and `OwnerControllerTests` renders an `Owner` without an id. Tessera fails the render on a null
    path variable, so the link uses `(ownerId='' + owner.id)`, which gives the same output.
- Self-closing non-void tags (`<td t:text=... />`, `<span ... />`) are written with explicit end
  tags.

### Tests
- `I18nPropertiesSyncTest`: its regex for "this line has a translated text attribute" only matched
  `th:(u)?text`. It now matches `(th|t):(u)?text`. This is the only test change, and it was there
  only because the test asserted on old view-technology syntax. Nothing else was relaxed.
- The two `t:errors` spans lost their unused prototype text (`Error`), which `t:errors` always
  replaces. Before, the span carried `th:text="#{error}"`, which is why the i18n test accepted it.
- Added `OwnerControllerTests.showOwnerEscapesUserData`. It checks that `<script>` and `<b>` in
  owner and pet names come out escaped, as the guide's checklist recommends.

## What could not be converted exactly

- **Stray `</a>` in `owners/ownersList.html`.** The Thymeleaf template had
  `<a th:href=... th:text=... /></a>`. That is a self-closed `<a/>` followed by an unmatched
  `</a>`, and Thymeleaf echoed the unmatched end tag into the output on every owner row. Tessera
  rejects unmatched end tags, and the only way to emit raw markup is `SafeHtml.unchecked`, which is
  not allowed. The new output is `<a href="/owners/1">George Franklin</a>` without the extra
  `</a>`. Browsers ignore the stray end tag, so the parsed DOM is identical. This is the only
  difference across all 36 compared responses.
- Prototype-only markup (`src="../static/..."`, sample text) is kept as in the original; `t:`
  attributes replace it at render time, as before.

## Where the guide or Tessera fell short

1. **Lost `ResourceUrlEncodingFilter`.** Removing `spring-boot-starter-thymeleaf` also removes
   the `ResourceUrlEncodingFilter` that Boot's Thymeleaf auto-configuration registers. That
   silently changes every versioned webjar/resource URL. Neither the guide (section 12 says "links get the context path and
   `response.encodeURL`") nor the starter mentions or replaces it. I found it only by diffing the
   output.
2. **`tessera-migrate` parameter stubs are incomplete or wrong:**
   - On the visit form it missed `visit`, which is used by `t:object="visit"` and `visit.isNew()`,
     and the loop variable of the same name shadows it.
   - It put `t:params="Object minVisitDate"` on the root of `fragments/inputField.html`, a
     fragment-only file. That is useless, because fragments never see page parameters (section
     11). It should have suggested a fragment parameter.
3. **The migrator report contradicts the guide.** For `*{__${name}__}` it says "not supported …
   inline one element per field (a typed alternative is tracked in #43)". The guide (section 11)
   documents that alternative, `Field` fragment parameters, as available, and there is no issue
   tracker to look up #43. `Field` parameters worked.
4. **Some old link syntax was left unconverted and unreported.** The migrator left
   `@{'/owners?page=1'}` and `@{'/vets.html?page=1'}` (string-literal links) as they were, and
   only reported the vets file with a generic message.
5. **Null semantics differ from Thymeleaf, with no idiom given.**
   - A `null` attribute value omits the attribute, where Thymeleaf wrote `value=""`.
   - A `null` link path variable throws, where Thymeleaf wrote `null`.

   Section 17 lists both differences, but the guide gives no recommended idiom for keeping
   identical output. The obvious `pet.id ?: ''` is rejected because the operands have different
   types (section 16), so the workarounds (`cond ? '' : '' + x`, `'' + x`) were found by trial.
6. **Undocumented behaviour I had to try out:**
   - Whether a link expression `@{...}` may be a fragment argument (it may: it is a String, and it
     is URL-checked again in `t:href`).
   - Whether `null` may be passed for a typed fragment parameter (it may).
   - How `t:errors` and `t:text` interact on one element.
   - How the Boot error view (`error.html` vs. the Whitelabel view) is resolved. Tessera did take
     over `/error`, and the integration test passes.
7. **Java version.** The guide says "Tessera needs Java 21+" without saying whether that means
   the JDK running `javac` or the target `--release`. The app keeps `java.version=17`
   (`--release 17`), compiled on JDK 21, and that worked.
8. **Gradle** is listed as not supported (section 17), but the repository ships a `build.gradle`.
   I converted it in the obvious way (`annotationProcessor` + compiler args). It compiles the
   templates, but that is not covered by the guide.
9. **Template-only changes need a clean build.** Maven's incremental compile does not notice
   template-only changes (section 17). I used `mvn clean ...` throughout. In `build.gradle` I
   declared the templates and messages directories as `compileJava` inputs to avoid this.

## Security checklist

- No `SafeHtml.unchecked` or `TrustedUrl.unchecked`, and no `t:utext` / `[(...)]`.
- No new `permitAll`. The app has no Spring Security, so there is no CSRF configuration to keep or
  lose.
- Escaping is automatic and cannot be turned off. The new test, and the captured
  `<script>alert(1)</script> O'Brien "q"` owner page, show user data escaped exactly as Thymeleaf
  did.
