# Porting spring-petclinic's templates to Tessera

This records every manual step and deviation from porting the twelve Thymeleaf templates in
`thymeleaf-reference/templates` to `src/main/resources/templates` (#18). It is input for the porting
guide (#19). Friction points (compiler gaps, migrator misses) are in [MIGRATION-NOTES.md](MIGRATION-NOTES.md).

## Steps

1. **Run the migrator.**
   ```sh
   mvn -B -q -ntp -DskipTests install   # builds tessera-migrate
   java -cp tessera-migrate/target/tessera-migrate-0.1.0-SNAPSHOT.jar:<attoparser-2.0.9.jar> \
     dev.tessera.migrate.Main examples/petclinic/thymeleaf-reference/templates \
     examples/petclinic/src/main/resources/templates --report migrate-report.md
   ```
   This converted 12 templates and produced 53 report items: 25 `Object` stubs to type (root variables
   and fragment parameters), 18 `__${...}__` uses, and 10 non-literal link paths.
2. **Make the model view-safe.** Added `@TemplateView` to the entities the templates read: `Owner`,
   `Pet`, `PetType`, `Visit`, `Vet`, `Specialty`. Getters inherited from `BaseEntity`, `NamedEntity` and
   `Person` resolve through the annotated subclass, so the mapped superclasses needed nothing.
   `Owner.getPet(String)` and other methods with arguments stay unreachable.
3. **Type the `t:params` stubs.** Each page imports `org.springframework.samples.petclinic.owner.*` (or
   `vet.Vet`) and declares what its controller puts in the model:

   | Template | `t:params` |
   |---|---|
   | `owners/createOrUpdateOwnerForm`, `owners/findOwners` | `Owner owner` |
   | `owners/ownerDetails` | `String message, String error, Owner owner` (flash attributes) |
   | `owners/ownersList` | `List<Owner> listOwners, int totalPages, int currentPage` |
   | `pets/createOrUpdatePetForm` | `Pet pet, Owner owner, Collection<PetType> types` |
   | `pets/createOrUpdateVisitForm` | `Visit visit, Pet pet, Owner owner, LocalDate minVisitDate` |
   | `vets/vetList` | `List<Vet> listVets, int totalPages, int currentPage` |
   | `error` | `Integer status, String message` (Spring Boot's error attributes) |
   | `welcome` | none |

4. **Type the fragments.** `layout(Fragment template, String menu)` and
   `menuItem(String link, boolean active, String title, String glyph, String text)`.
5. **Rewrite the layout's menu.** Upstream `menuItem` compares `active==menu`, reading `menu` from the
   caller's context. Tessera fragments see only their parameters, so the caller passes the comparison:
   `~{::menuItem(@{/owners/find}, menu == 'owners', 'find owners', 'search', #{findOwners})}`. The link
   was `@{__${link}__}`. Now the caller passes a built link (`@{/owners/find}`, type `String`) and
   the fragment writes `t:href="link"`. `t:class="nav-item"` (a Thymeleaf literal token) became a
   static `class="nav-item"`.
6. **Inline the field fragments.** `fragments/inputField` and `fragments/selectField` used
   `*{__${name}__}` and `#fields.hasErrors(name)`. Tessera has no preprocessing and no dynamic field
   paths, so each of the 10 call sites now contains the fragment's markup with a literal path
   (`t:field="*{firstName}"`, `fields.hasErrors('firstName')`, `t:errors="*{firstName}"`). The two
   fragment templates were deleted. The `th:switch` on `type` is gone: each copy holds the one
   `<input>` it needs, still wrapped in the `<div>` the switch rendered.
7. **Rewrite links with `__${...}__`** to path variables or query parameters:
   - `@{__${owner.id}__/edit}` → `@{{ownerId}/edit(ownerId=owner.id)}` (still page-relative)
   - `@{/owners/__${owner.id}__}` → `@{/owners/{ownerId}(ownerId=owner.id)}`
   - `@{'/owners?page=' + ${i}}`, `@{'/owners?page=__${currentPage - 1}__'}` →
     `@{/owners(page=i)}`, `@{/owners(page=currentPage - 1)}` (same for `/vets.html`)
8. **Pagination** keeps `numbers.sequence(1, totalPages)`, from the migrator's `#numbers.` rewrite.
9. **Smaller expression rewrites:**
   - `owner['new']`, `pet['new']`, `visit['new']` → `owner.isNew()` etc.
   - `*{firstName + ' ' + lastName}` → `*{firstName} + ' ' + *{lastName}` (`*{...}` takes a path)
   - `#strings.listJoin(owner.pets, ', ')` → `strings.join(owner.pets, ', ')`
   - `#fields.hasAnyErrors()` / `#fields.allErrors()` → `fields.hasErrors('*')` / `fields.errors('*')`
   - `<a th:href=... th:text=... /></a>` (self-closed `<a>` plus a stray end tag) → `<a ...></a>`. Tessera's
     parser rejects the unmatched `</a>`.
10. **`t:errors` with `t:text`.** Upstream's error span has both `th:errors` and `th:text="#{error}"`.
    Thymeleaf shows the errors. Tessera keeps one body-setting attribute, so `t:text` was dropped.
11. **Build-time message checks.** `pom.xml` passes
    `-Atessera.messages=${project.basedir}/src/main/resources/messages/messages`, so every `#{key}` is
    checked against `messages.properties` at compile time.
12. **Tests.**
    - Restored the upstream view-rendering tests: `PetClinicIntegrationTests.ownerDetails`/`ownerList`,
      `PetClinicConcurrencyTests`, `CrashControllerIntegrationTests.triggerExceptionHtml`.
    - Added rendered-HTML assertions to every `@WebMvcTest` test that renders a view (the list in
      `thymeleaf-reference/README.md`), plus `OwnerControllerTests.ownerDataIsEscapedInEveryPage`
      (XSS negative test).
    - `I18nPropertiesSyncTest` now treats `t:text`/`t:utext`/`t:errors` like `th:text`.

## Changes outside `examples/petclinic`

- **Self-closed elements with a replaced body** (`tessera-compiler`, `TemplateGenerator`):
  `<td t:text="x" />` rendered `<td />x`. It now renders `<td>x</td>`, like Thymeleaf. Upstream's
  `ownersList.html` and `vetList.html` use this form. Test:
  `CodegenEndToEndTest.selfClosingElementWithReplacedBodyGetsAnEndTag`.
- **`@WebMvcTest` slices** (`tessera-spring-boot-starter`): added
  `META-INF/spring/org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureWebMvc.imports`
  listing `TesseraAutoConfiguration`. Without it, MockMvc slice tests never render a Tessera view:
  the forward is recorded and the body is empty. Boot's Thymeleaf module registers itself the same way.

## Deviations from upstream output

I compared every page against upstream Thymeleaf petclinic running the same sample data (GET pages
and POSTs with validation errors). Text and structure are the same. The remaining differences:

- **Attribute order.** Attributes set by `t:*` are written where Tessera's plan places them, so
  `<a href="1/edit" class="btn">` becomes `<a class="btn" href="1/edit">`.
- **Whitespace.** Thymeleaf writes `<td >` for an element whose attribute was removed. Tessera
  writes `<td>`. `t:each` does not repeat the whitespace before an iterated element (#40).
- **Webjars URLs.** Thymeleaf's links pass through Boot's `ResourceUrlEncodingFilter`, which
  Boot registers only with Thymeleaf, so upstream renders versioned webjar paths
  (`/webjars/bootstrap/5.3.8/...`). Tessera renders `/webjars/bootstrap/dist/...`, which
  `webjars-locator-lite` serves just as well.
- **Null attribute values.** `<input type="hidden" name="id" t:value="*{id}" />` on a new pet omits
  `value`. Thymeleaf writes `value=""`. Omitting the attribute on `null` is Tessera's documented rule.
- **Null link path variables throw.** `@{/owners/{ownerId}(ownerId=owner.id)}` with a null id fails the
  render. Upstream's `@{/owners/__${owner.id}__}` renders `/owners/`. Saved owners always have an id,
  but `OwnerControllerTests` listed a bare `new Owner()`, so it now uses an owner with id 2.
- **Pet and visit form labels** use `#{name}`, `#{birthDate}`, `#{type}`, `#{date}`, `#{description}`
  where upstream passed English literals to the field fragment. English output is the same.
  `I18nPropertiesSyncTest` flags literal label text now that the labels are inline.
