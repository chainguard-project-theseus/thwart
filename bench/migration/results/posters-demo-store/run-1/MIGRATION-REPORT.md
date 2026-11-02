# Migration report: Thymeleaf → Tessera

Branch `feat/tessera-migration` moves the Posters storefront, checkout and backoffice views from
Thymeleaf with the Layout Dialect to Tessera 0.1.0-SNAPSHOT.

## Result

| Criterion | Status |
|---|---|
| Every page renders through Tessera; Thymeleaf templates, dependencies and config are gone | Done. All 53 templates are converted. `spring-boot-starter-thymeleaf`, `thymeleaf-layout-dialect` and the `spring.thymeleaf` block in `application.yml` are removed. At startup there is no `another template engine is still configured` warning and no `No compiled Tessera templates` warning. |
| `mvn clean package` on JDK 21 with all tests | Passes: 284 tests, 0 failures, 0 skipped. |
| Page parity (`tessera-migrate compare`) | **Not met.** Status code and final URL match on every request I recorded. Page bodies do not: almost every page that uses a layout comes out `differs`. Each remaining difference comes from a construct that Tessera rejects at build time (see "Differences that remain"). |
| Security | No `permitAll` added. CSRF is not disabled. No `t:utext`, `SafeHtml` or other unescaped output. `SecurityConfig` is unchanged. No test disabled. |

## What changed

### Build (`implementation/pom.xml`)
- Added `dev.tessera:tessera-spring-boot-starter`.
- Added `tessera-compiler` as an annotation processor, with `-Atessera.templates=src/main/resources/templates`, `-Atessera.package=com.xceptance.posters.templates` and `-Atessera.messages=src/main/resources/messages`, so message keys are checked when the build runs.
- Added `tessera-maven-plugin:check`, so an edit to a template or message alone still triggers recompilation.
- Removed Thymeleaf and the Layout Dialect. No application code imports anything they brought in (Thymeleaf, Groovy, attoparser), and the app starts and serves every recorded request without them.

### Templates (`implementation/src/main/resources/templates`)
All 53 templates were rewritten as Tessera templates at the same paths, so view names did not change. I first ran `tessera-migrate` for the mechanical part, then finished each template by hand.

**Layouts.** The three Layout Dialect layouts (`layout/default`, `layout/checkoutLayout`, `backoffice/layout/default`) are now fragment layouts. Each page passes its title, any extra head content, and its body as `Fragment` values. This reproduces what the Layout Dialect did:
- the page's `<title>` replaced the layout's;
- the page's head content was appended to the layout's `<head>`;
- the `layout:fragment` wrapper element was kept.

**Values that used to come from outside the model.** Templates now receive these explicitly:
- **Storefront:** `CommonDataInterceptor` also adds a `storefront` record (`view/StorefrontLayout`) holding the layout data and the CSRF token.
- **Backoffice:** `AbstractBackofficeController` and `BackofficeExceptionHandler` add a `chrome` value (`controller/BackofficeChrome`) holding the user, permitted modules, URI and CSRF token.
- **Checkout:** `CheckoutCsrfModelAdvice` adds the CSRF token.
- **Backoffice login:** `BackofficeController` passes `loginError` and `loggedOut` to the login page instead of the template reading request parameters.
- **Break page:** `BreakController` adds `breakStatus`.

**View types.**
- **Storefront:** `@TemplateView` is on the entities the pages read: `Customer`, `AdminUser`, `Role`, `AuditLogEntry` and `WebShopController.CategoryDto`.
- **Checkout:** new records in `view/checkout/*`.
- **Backoffice customers:** new records `controller/backoffice/Backoffice*View`.
- **Paging:** `BackofficePage` is a view-safe copy of Spring Data's `Page`.

**Logic moved into Java.** Method calls with arguments, arithmetic on `BigDecimal` and string-to-number conversion are now `@TemplateFunctions`: `StorefrontFunctions` (`shop.*`), `CheckoutFunctions` (`checkout.*`) and `BackofficeFunctions` (`bo.*`).

**Tests.**
- New tests that render through the real view resolver:
  - `BackofficeTesseraRenderingTest`, which includes CSRF 200 and 403 cases;
  - `CheckoutTesseraRenderTest`;
  - `CheckoutFunctionsTest`.
- `CustomersModuleControllerTest`: only the asserted fragment view names changed, for example `:: profile-name-form` to `:: profileNameForm`. Tessera fragment names are identifiers and a `-` is a build error (`unexpected '-'`). I checked this myself.
- `src/test/java/.../TestThymeleaf.java` is deleted. It was not a test: it had a `main` method that printed a Thymeleaf rendering, with no `@Test` and no assertion. It only existed to try Thymeleaf syntax, and it would not compile without Thymeleaf.

### Docs
`README.md`, `doc/CSRF_GUIDE.md`, the CSRF section of `AGENTS.md`, `NOTICE.md` and `doc/3rd-party-licenses/tessera/` (Apache-2.0, taken from the `tessera-parent` POM) are updated for Tessera.

## Differences that remain in `compare`, and why

I recorded three request lists against the original jar, each started from a fresh, empty data directory:
- storefront: 85 requests;
- checkout: 48 requests, covering guest and logged-in checkout, validation errors, de-DE, en-GB, ja-JP and sv-SE;
- backoffice: 112 requests, covering login, logout, login errors, access denied, the 404 and 500 pages, the users and roles forms with validation errors, and each role.

The backoffice customers htmx fragments got a separate 49-request list.

Results against the final build: in every list, status and final URL match on every request. Without masking, the page bodies give:

| List | same | differs |
|---|---|---|
| storefront | 12 | 73 |
| checkout | 5 | 43 |
| backoffice | 1 | 111 |

With the differences below masked, the backoffice list has 55 pages `same`, and the remaining storefront and checkout differences are all from this list. Every difference comes from one of these causes:

1. **`<meta name="_csrf" th:content="${_csrf.token}">` (every layout page).**
   - Tessera does not allow a computed `content` on a named `<meta>`.
   - A computed `data-*` accepts only numbers, booleans and links.
   - So the token can't go into any attribute. Each layout now declares it at the top of its htmx setup script (`const postersCsrfToken = "...";`, and the same in the other two layouts), and the htmx `configRequest` listener reads that constant.
   - `compare` masks the token inside the meta tag but not inside a script, so this one change makes every layout page `differs`.
   - htmx requests still send `X-CSRF-TOKEN`; a test checks this.
2. **Inline `onclick` handlers** (scroll-to-top on every storefront and checkout page, password toggles, "same as shipping", "disable all" on `/break`). Tessera rejects `on*` attributes, even static ones. They became `data-action` or an `id`, with a listener attached from a script.
3. **htmx attributes.**
   - A computed `hx-get` or `hx-post` is a build error, so these became `data-hx-get` and `data-hx-post`, which htmx also reads.
   - A neutral `data-*` value can't carry a query string, so query parameters (`cartProductId`, `productId`, `hx-vals`) became hidden inputs sent with `hx-include`, and paginated `hx-get` links became `hx-boost`.
   - A localized `hx-confirm` (a `#{}` in a neutral attribute is an error) became a key plus a hidden localized text that the confirm handler reads.
   - In category filter links the slug is now percent-encoded.
4. **Bootstrap modal targets** (`data-bs-target="#deleteUserModal-N"`). A computed string isn't allowed in `data-*`, so these became `href="#deleteUserModal-N"`, which Bootstrap also reads.
5. **Script data is now JSON.** `[[...]]` must be a top-level declaration, so `window.CC_MESSAGES = {...}` is built from declared constants. `shippingData` is written as JSON, so `<` appears as `<` and `{0}` as `{0}`. The values JavaScript sees are the same.
6. **Hidden `_csrf` inputs** are added to the backoffice htmx modal forms that have no `action`. This is Tessera's automatic CSRF injection.
7. **Error pages where Thymeleaf failed partway.** Examples are `/backoffice/security/users/999/edit` and three customer-module error responses. The original returned an empty or truncated body with status 500, because the template crashed on a null `currentUri`. The new app renders the whole error page with the same status, as the Tessera guide requires.
8. **Values that change on every run:** order numbers, timestamps and random customer UUIDs.

One original bug is kept deliberately for parity. The roles list shows raw module ids, because the old SpEL `moduleDisplayNames[moduleId]` looked up the literal key `"moduleId"` and always fell back to the id.

None of these differences changes text content, form values, selected options, escaping, locale formatting, status codes or redirects. Causes 1 to 4 have no Tessera form that produces the same markup. Getting the pages `same` would need a hack: patching vendored htmx or Bootstrap, or moving the CSRF token into a cookie set by a filter. I didn't do that, because it would add workarounds to the security setup only to satisfy a diff.

## Where Tessera or its documentation fell short

- **No way to give scripts the CSRF token.** `<meta name="_csrf" content>` is the standard Spring Security pattern for htmx and fetch, and Tessera has no form for it. The guide has no recipe. The `tessera-migrate` report suggests "a `<meta>` for JavaScript", which the compiler then rejects. `compare` masks the meta token but not a token in a script, so the one workaround that works shows up as a difference on every page.
- **Static `onclick` is a build error,** so no page that has one can ever come out `same` in `compare`.
- **htmx support is thin.**
  - `data-hx-*` can't take a query string or a URL built in Java.
  - There is no recipe for a localized `hx-confirm`.
  - `compare` doesn't treat `hx-get` and `data-hx-get` as the same, so a correct port can't reach exit code 0.
- **No recipe for `data-bs-target="#id-N"`** (Bootstrap modals and collapses).
- **Fragment names can't contain `-`.** Neither the guide nor `tessera-migrate` says so, and controllers returning `view :: some-fragment` (and their tests) had to change.
- **The Layout Dialect's merge rules aren't documented.** Title replacement, head appending, the kept `layout:fragment` wrapper and attribute merging all had to be worked out from the recorded output. Guide 4.8 doesn't describe what the dialect did.
- **Missing expression features.**
  - No `BigDecimal` arithmetic (`rate * 100`).
  - No string-to-number conversion: `numbers.formatCurrency` doesn't accept a `String`, which Thymeleaf converted silently.
  - No `strings.substringAfter` or `substringBefore`.
  - No `set.contains(x)` or `enum.name()`.
  - Comparing a `String` with an enum *variable* is silently `false`; only string literals compare by name. This made a `selected` option disappear with no warning.
- **Gaps in the `tessera-migrate` conversion.**
  - It leaves string-concatenated `@{'/' + x}` links, `#messages.msg('a' + x)`, `not x`, `th:attr`, `th:attrappend` and `th:inline` for hand conversion.
  - It rewrites `/*[[...]]*/` inside object literals into `[[...]]` in place, which the compiler then rejects.
  - It puts `t:params` on fragment roots.
  - It does not flag leftover `th:*` attributes.
- **`record`/`compare` assume deterministic data.** There is no help for seed data with random UUIDs, beyond masking with `--ignore`. The guide's "wait until it answers HTTP" also isn't enough for this app: its catalog import finishes after Tomcat is up, so you have to wait for the `Data import complete` log line.
- **Thymeleaf's `th:value=""` for null and its empty-segment links** need a manual decision at every use. The report lists them all (413 items for 53 templates), but most turned out not to matter.

## Not converted

Nothing. Every view is rendered by Tessera.
