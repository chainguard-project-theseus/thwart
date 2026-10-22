# Thymeleaf reference templates

`templates/` holds the original spring-petclinic Thymeleaf templates, copied unchanged from
upstream commit `500158f732419217507c7656904b8e6aa1bcc0d6`. They are **not** on the classpath and
not under the Tessera templates root (`src/main/resources/templates`).

The Tessera port of these templates lives in `src/main/resources/templates` (#18). See
[`../PORTING.md`](../PORTING.md) for every manual step and deviation, and
[`../MIGRATION-NOTES.md`](../MIGRATION-NOTES.md) for the friction log. `fragments/inputField.html` and
`fragments/selectField.html` have no Tessera counterpart: their markup is inlined at each call site
because Tessera has no dynamic field paths.

The upstream tests that needed rendered views (`PetClinicIntegrationTests.ownerDetails`/`ownerList`,
`PetClinicConcurrencyTests`, `CrashControllerIntegrationTests.triggerExceptionHtml`) are restored.
The `@WebMvcTest` controller tests render the Tessera views and assert on the HTML.

The MySQL and Postgres tests (`MySqlIntegrationTests`, `MysqlTestApplication`,
`PostgresIntegrationTests`) were dropped for good along with those profiles.
