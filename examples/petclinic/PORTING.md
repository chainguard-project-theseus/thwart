# Porting spring-petclinic's templates to Tessera

This records every manual step and deviation from porting the twelve Thymeleaf templates in
`thymeleaf-reference/templates` to `src/main/resources/templates`. The general porting guide is
[docs/guide.md](../../docs/guide.md).

What went smoothly: once the model was annotated and the stubs were typed, the templates compiled
after one parser error (step 9). That covers layout with `~{::body}`, `t:object`/`t:field`/`t:errors`,
`<select t:field>` with `t:each` options, `t:switch` on an `Integer` status, `temporals.format`,
`numbers.sequence`, inlined `[[${i}]]`, and build-time checks of every `#{key}`. Every page then
rendered the same content as upstream for the sample data, including the validation-error states.

## Steps

1. **Run the migrator.**
   ```sh
   mvn -B -q -ntp -DskipTests install   # builds tessera-migrate
   java -jar tessera-migrate/target/tessera-migrate-0.1.0-SNAPSHOT.jar \
     examples/petclinic/thymeleaf-reference/templates \
     examples/petclinic/src/main/resources/templates --report migrate-report.md
   ```
   This converted 12 templates and produced 53 report items: 25 `Object` stubs to type (root variables
   and fragment parameters), 18 `__${...}__` uses, and 10 non-literal link paths.
2. **Make the model view-safe.** Added `@TemplateView` to the entities the templates read: `Owner`,
   `Pet`, `PetType`, `Visit`, `Vet`, `Specialty`. Getters inherited from `BaseEntity`, `NamedEntity` and
   `Person` resolve through the annotated subclass, so the mapped superclasses needed nothing.
   `Owner.getPet(String)` and other methods with arguments stay unreachable. This is the
   shortcut from guide section 4.2, kept so the port stays close to upstream; a new application would
   pass view records built in the controller instead, for the lazy-loading and exposure reasons given
   there.
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
6. **Type the field fragments with `Field`.** `fragments/inputField` and `fragments/selectField`
   used `*{__${name}__}` and `#fields.hasErrors(name)`. Tessera has no preprocessing and no dynamic
   field paths. Instead `name` is declared `Field`: `input (String label, Field name, String type)`
   and `select (String label, Field name, Collection<PetType> items)`. The callers are unchanged
   (`input (#{firstName}, 'firstName', 'text')`); each literal path is checked against the caller's
   `t:object` at build time. Inside, `*{__${name}__}` became `name` (`t:field="name"`,
   `t:errors="name"`, `fields.hasErrors(name)`, `t:for="name"`). Upstream's date input read
   `minVisitDate` from the caller's context; fragments see only their parameters, so
   `inputWithMin (label, name, type, LocalDate min)` takes it and `input` delegates to it with `null`.
   The visit form calls `inputWithMin (#{date}, 'date', 'date', minVisitDate)`. The first port inlined
   the markup at all 10 call sites.
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
    - Added rendered-HTML assertions to every `@WebMvcTest` test that renders a view, plus
      `OwnerControllerTests.ownerDataIsEscapedInEveryPage` (XSS negative test).
    - `I18nPropertiesSyncTest` now treats `t:text`/`t:utext`/`t:errors` like `th:text`.

## Deviations from upstream output

I compared every page against upstream Thymeleaf petclinic running the same sample data (GET pages
and POSTs with validation errors). Text and structure are the same. The remaining differences:

- **Attribute order.** Attributes set by `t:*` are written where Tessera's plan places them, so
  `<a href="1/edit" class="btn">` becomes `<a class="btn" href="1/edit">`.
- **Whitespace.** Thymeleaf writes `<td >` for an element whose attribute was removed. Tessera
  writes `<td>`.
- **Null attribute values.** `<input type="hidden" name="id" t:value="*{id}" />` on a new pet omits
  `value`. Thymeleaf writes `value=""`. Omitting the attribute on `null` is Tessera's documented rule.
- **Null link path variables throw.** `@{/owners/{ownerId}(ownerId=owner.id)}` with a null id fails the
  render. Upstream's `@{/owners/__${owner.id}__}` renders `/owners/`. Saved owners always have an id,
  but `OwnerControllerTests` listed a bare `new Owner()`, so it now uses an owner with id 2.
- **Pet and visit form labels** use `#{name}`, `#{birthDate}`, `#{type}`, `#{date}`, `#{description}`
  where upstream passed English literals to the field fragment. English output is the same. (The
  first port inlined the labels, where `I18nPropertiesSyncTest` flags literal text; the keys stayed.)
- **Label attribute order.** The field fragments write `<label class="..." for="firstName">`;
  upstream writes `for` first (see attribute order above).
