# Petclinic migration friction log

Friction points from porting petclinic to Tessera (#18), with how each was resolved. The steps and
output deviations are in [PORTING.md](PORTING.md).

| # | Kind | Friction | Resolution |
|---|---|---|---|
| 1 | Tooling | `tessera-migrate` jar has no `Main-Class` and no dependency classpath; `java -jar` fails | Ran `java -cp <jar>:<attoparser> dev.tessera.migrate.Main`. Filed #42 |
| 2 | Migrator | `th:class="nav-item"` (Thymeleaf literal token) became the expression `nav - item`; the stub declared `Object nav, Object item` | Static `class="nav-item"`. Filed #42 |
| 3 | Migrator | `#strings.listJoin` mapped to a non-existent `strings.listJoin` | `strings.join`. Filed #42 |
| 4 | Migrator | `#fields.hasAnyErrors()` / `allErrors()` left as is | `fields.hasErrors('*')` / `fields.errors('*')`. Filed #42 |
| 5 | Migrator | `x['new']` left as an index expression | `x.isNew()`. Filed #42 |
| 6 | Construct | `*{__${name}__}` field fragments (`inputField`, `selectField`) cannot be expressed: no dynamic field paths, by design | Inlined at all 10 call sites (about 140 lines of duplicated markup). Filed #43 for a typed, safe alternative |
| 7 | Construct | `menuItem` read the layout's `menu` from the caller's context | Caller passes `menu == 'owners'` as a `boolean` parameter (principle 2) |
| 8 | Construct | `@{__${link}__}` with the link passed as a fragment argument | Caller passes a built link (`@{/owners/find}` is a `String` expression); fragment uses `t:href="link"` |
| 9 | Construct | `@{'/owners?page=' + ${i}}` string-built links | `@{/owners(page=i)}` |
| 10 | Construct | `*{firstName + ' ' + lastName}`: `*{}` takes only a path | `*{firstName} + ' ' + *{lastName}` |
| 11 | Construct | `th:errors` together with `th:text` on one span | Dropped `t:text`; Thymeleaf showed the errors anyway |
| 12 | Parser | `<a ... /></a>` (stray end tag) is a build error; Thymeleaf tolerates it | Wrote `<a ...></a>`. Diagnostic was clear (`end tag </a> does not match any open element`, file:line:col) |
| 13 | Compiler bug | `<td t:text="x" />` rendered `<td />x` | Fixed in `TemplateGenerator`: a self-closed element whose body is replaced gets a start and end tag |
| 14 | Starter gap | `@WebMvcTest` slices did not load `TesseraAutoConfiguration`, so no view rendered and bodies were empty | Added the starter's `AutoConfigureWebMvc.imports` entry |
| 15 | Runtime rule | A null link path variable throws; upstream test data listed an owner without an id | Test fixture gives the owner an id (`OwnerControllerTests.savedOwner`) |
| 16 | Cosmetic | `t:each` drops the whitespace Thymeleaf repeats between iterations | Left as is; #40 |

What went smoothly: once the model was annotated and the stubs were typed, the templates compiled
after the one parser error above. That covers layout with `~{::body}`, `t:object`/`t:field`/`t:errors`,
`<select t:field>` with `t:each` options, `t:switch` on an `Integer` status, `temporals.format`,
`numbers.sequence`, inlined `[[${i}]]`, and build-time checks of every `#{key}`. Every page then
rendered the same content as upstream for the sample data, including the validation-error states.
