# Thymeleaf reference templates

`templates/` holds the original spring-petclinic Thymeleaf templates, copied unchanged from
upstream commit `500158f732419217507c7656904b8e6aa1bcc0d6`. They are **not** on the classpath and
not under the Tessera templates root (`src/main/resources/templates`). They are the input for
porting the UI to Tessera in #18.

Until #18 lands, the app starts but its pages return 404/500 because no view exists yet for the
view names the controllers return.

## Upstream tests left out because they need rendered views

These upstream tests request a page over HTTP and need its rendered HTML, or a 2xx from a
rendered page. Restore them from upstream in #18:

- `PetClinicIntegrationTests.ownerDetails` (GET `/owners/1`)
- `PetClinicIntegrationTests.ownerList` (GET `/owners?lastName=`)
- `PetClinicConcurrencyTests.testDuplicatePetNameRaceConditionIsBlocked` (whole class: the
  successful POST redirects to `/owners/1`, and the duplicate re-renders the pet form)
- `system.CrashControllerIntegrationTests.triggerExceptionHtml` (renders `error.html`)

The MySQL and Postgres tests (`MySqlIntegrationTests`, `MysqlTestApplication`,
`PostgresIntegrationTests`) were dropped for good along with those profiles.

## Copied tests that will render views once templates exist

The `@WebMvcTest` controller tests were copied whole. No templates exist yet, so the Tessera view
resolver finds no view, MockMvc only records a forward for the returned view name, and these tests
pass on their model and view-name assertions alone. Once #18 adds the Tessera templates, every test that expects a
non-redirect view name renders for real. Add rendered-HTML assertions to them in #18:

- `owner.OwnerControllerTests`: `initCreationForm`, `processCreationFormHasErrors`,
  `processCreationFormRejectsCityLongerThanColumn`,
  `processCreationFormRejectsAddressLongerThanColumn`, `initFindForm`, `processFindFormSuccess`,
  `processFindFormWithWhitespaceOnlyLastNameReturnsAllOwners`, `processFindFormNoOwnersFound`,
  `initUpdateOwnerForm`, `processUpdateOwnerFormHasErrors`, `showOwner`
- `owner.PetControllerTests`: `initCreationForm` and the `ProcessCreationFormHasErrors` and
  `ProcessUpdateFormHasErrors` nested classes (including `initUpdateForm`)
- `owner.VisitControllerTests`: `initNewVisitForm` and the `processNewVisitFormHasErrors*` tests
- `vet.VetControllerTests.showVetListHtml`
- `system.WelcomeControllerTests.welcome`

`system.I18nPropertiesSyncTest.checkNonInternationalizedStrings` scans `src/main/**/*.html` for
literal text that `th:text` or `#{...}` does not cover. It passes trivially today because there
are no templates. In #18 it has to learn the Tessera `t:text` and `t:utext` attributes.
