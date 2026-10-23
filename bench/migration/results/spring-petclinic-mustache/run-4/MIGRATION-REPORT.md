# Migration report: Mustache (JMustache) to Tessera

## Result

- All 12 views (`welcome`, `error`, `owners/*`, `pets/*`, `vets/vetList` and the three shared
  fragments) are Tessera templates under `src/main/resources/templates/**/*.html`. They are compiled by
  the annotation processor into `org.springframework.samples.petclinic.templates`.
- JMustache, `spring-boot-starter-mustache` and every `*.mustache` template are gone. The old app had
  no Mustache-specific properties, so there was no configuration to remove.
- `mvn clean verify` passes: all 42 existing tests run unchanged, plus 2 new ones (44 total).
- Page parity: `pages.txt` has 47 requests covering every view and form state: the owner/pet/visit
  forms empty, with validation errors (including a type-mismatch date and a duplicate pet name) and after
  success; search with no hits, one hit (redirect) and many hits with pagination; HTML/script in user
  data; the 500 page from `/oups`, a 500 from a missing owner and from an empty pet birth date; and the
  404 page. Against the recording of the original app, 41 pages are `same`, 1 is `reordered` and
  5 `differ`. All 5 differences have the same cause, a typo in the old markup that Tessera cannot
  reproduce (explained below).
- Security: there is no `SafeHtml.unchecked` or `TrustedUrl.unchecked`, no `t:utext`, nothing that
  turns escaping off, no security configuration changes, and no test was disabled or weakened.

## What changed

**Build (`pom.xml`).** I replaced `jmustache` and `spring-boot-starter-mustache` with
`dev.tessera:tessera-spring-boot-starter:0.1.0-SNAPSHOT` (the app is Boot 3.2 on jakarta). I configured
`maven-compiler-plugin` with `tessera-compiler` in `annotationProcessorPaths`. Because that list turns
off processor discovery, `spring-boot-configuration-processor` is listed there too. The plugin also gets
`-Atessera.templates`, `-Atessera.package` and `-Atessera.messages` (`messages/messages`), and
`<proc>none</proc>` for `default-testCompile` (#118).

**Templates.** Each Mustache parent/partial became a fragment call:
- `fragments/layout.html` has the fragment `layout(List<Application.Menu> menus, Fragment body,
  boolean footer)`. Each page replaces itself with the layout and passes `~{::body}`.
- `fragments/inputField.html` has `inputField(InputField field)` and `fragments/selectField.html` has
  `selectField(SelectField field)`. These replace the `{{<fragments/inputField}}` partials, which
  rendered in the context of the field object.
- Mustache's context-stack lookups were made explicit. For example, `{{owner.id}}` inside
  `{{#pets}}` became `owner.id` of the enclosing loop. `{{#new}}` became `pet.isNew()`, and so on.
- Values that were `null` and printed as `""` by Mustache, but would now drop the attribute, use
  `?: ''`. One example is `value` on the find-owners input.
- A `selected` option is written with a static `selected` attribute in a `t:if` branch, so the markup
  matches the old `{{#selected}} selected{{/selected}}` exactly.

**Java.**
- `@TemplateView` is on the classes the templates read: `Owner`, `Pet`, `PetType`, `Visit`, `Vet`,
  `Specialty`, `Vets`, `Application.Menu`, `InputField`, `SelectField`, `OwnerForm` and `PetForm`.
  Getters inherited from the `@MappedSuperclass` entities resolve through them.
- `InputField`/`SelectField` exposed public fields. They now also have getters, because Tessera reads
  only getters.
- `OwnerForm`/`PetForm` were package-private classes nested in package-private controllers. Mustache
  reached them by reflection. Generated template code lives in another package, so they became public
  top-level classes (`owner/OwnerForm.java`, `owner/PetForm.java`) with public getters.
  `PetForm.nameField()/birthDate()/type()` became `getNameField()/getBirthDate()/getType()`.
- The pagination model was a `List<Map<String, Object>>`, which is not view-safe. It is now a
  `List<PageLink>`, where `system/PageLink` is a record `(int number, boolean current)`. This is
  used in `OwnerController` and `VetController`.
- `LayoutAdvice` (which adds `menus`, `errors` and fills the form fields from the `BindingResult`) is
  unchanged. It runs in `postHandle`, before the view renders, so the templates simply declare `menus`
  and `errors` as parameters.

**Tests.** I added `TesseraRenderingTests`, a `@SpringBootTest` with MockMvc that renders through the
real Tessera view resolver. It checks the owner page and that `<script>` in a rejected form value comes
out escaped. It sits in the root test package: in the `system` package, `@SpringBootTest` picked up the
nested `@SpringBootApplication` of `CrashControllerIntegrationTests` and got a context without
controllers.

## What could not be converted 1:1, and why

1. **`<span">` in the pagination blocks** (`owners/ownersList`, `vets/vetList`; pages 03, 04, 05, 43
   and 44 in `pages.txt`). The old templates contain the typo `<span">`. A browser parses this as an
   unknown element named `span"`. Its `</span>` does not close it, so the rest of the pagination block
   nests inside it. Tessera's parser rejects the markup (`malformed markup: Incomplete structure`), and
   no Tessera construct can emit an element with that name. The only way would be raw markup through
   `SafeHtml.unchecked`, which is not acceptable. The new templates write `<span>`. The only difference
   in those 5 pages is this element and the nesting it caused. The text, links and order are
   identical. This is the "broken markup the old engine passed through" case from the guide, and the
   difference is correct.
2. **Bugs in the old app kept on purpose, for parity.** I reproduced these faithfully rather than
   fixed them, because the parity check is against the original output:
   - `vets/vetList` looped over `{{#listVets}}`, which no controller provides, so the vet table was
     always empty. The new template renders the same empty `<tbody>`.
   - `pets/createOrUpdateVisitForm` wrapped its date/description inputs in `{{#form}}`, which the
     visit controller never sets, so the visit form showed no input fields. The new template has the
     same (empty) form.
   - The error page printed `{{message}}`, which Boot leaves out of the model by default
     (`server.error.include-message=never`). JMustache threw at that point and the response was cut off
     after `<p>`: no footer and no bootstrap script. The new `error.html` renders an empty
     `<p t:text="message">` and calls the layout with `footer=false`, which gives the same page as a
     browser builds from the truncated response, with the same status codes.
   - `POST /owners/{id}/pets/new` with an empty birth date still returns a 500, from an NPE in
     `PetForm`.
   - The static `th:text`/`th:value` attributes, copied from Thymeleaf, were output literally before
     and still are.
3. **`reordered`, page 12.** The two telephone validation messages come from a `HashSet`, so their
   order changes from run to run under either engine. This is harmless.

## Where the guide or Tessera fell short

- **Mustache is barely covered.** The guide's migration tooling and most of its advice target
  Thymeleaf. There is no help for Mustache sections, inverted sections, context-stack name resolution
  or partials rendered in a section's context. The guide says only "partials ... become fragment
  calls". The context-stack semantics (for example `{{owner.firstName}}` inside `{{#owner}}` falling back
  to `pet.owner`) had to be worked out by hand.
- **Missing-value behavior of the old engine.** Spring Boot's JMustache setup treats a missing section
  name as false, but throws on a missing `{{variable}}` and returns a truncated page with the error
  status. The guide's advice ("a missing model attribute is `null`") does not cover an old engine that
  fails part-way through a page. I only found this by reading the recorded bodies.
- **Invalid markup.** Tessera rejects markup that browsers accept (`<span">`), and the guide offers no
  option to reproduce it. The parser's message (`Incomplete structure: "<span">x</span>`) also matches
  none of the entries in the diagnostic catalogue (section 6). The closest are "invalid element name"
  and "invalid attribute name".
- **Access to classes.** The guide says templates read "public no-argument getters" of view-safe
  types. It does not say that the *class* must be accessible from the generated package. Public static
  nested classes inside a package-private class are unusable, which forced the `OwnerForm`/`PetForm`
  extraction.
- **`@SpringBootTest` + test-only `@SpringBootApplication`.** This is not Tessera's fault, but the
  guide's advice ("add a test that renders through the real resolvers") runs into it easily.
- **`t:field` cannot be used for parity.** `t:field` always writes an `id` attribute (unless one is
  present). The old markup had no `id`s, so binding through `t:object`/`t:field` would have changed
  every form. The forms keep the existing Java-side `InputField`/`SelectField` view models (filled from
  Spring's `BindStatus` by `LayoutAdvice`) instead. There is no option to suppress the generated `id`.
- **Tooling quirks.** `tessera-migrate` handles only Thymeleaf. `pages.txt` POST bodies must be
  percent-encoded by hand (spaces, `&`, `<`), and the guide does not say so.

## How to re-check

```sh
mvn clean verify
java -jar target/spring-petclinic-mustache-3.0.0-SNAPSHOT.jar &
java -jar ~/.m2/repository/dev/tessera/tessera-migrate/0.1.0-SNAPSHOT/tessera-migrate-0.1.0-SNAPSHOT.jar \
  compare <base-url> pages.txt <baseline-dir>
```

The baseline was recorded from the original app (commit `003a406`) with the same `pages.txt`.
