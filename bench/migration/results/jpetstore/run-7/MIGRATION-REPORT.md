# Migration report: Thymeleaf → Tessera

The JPetStore views (19 Thymeleaf templates using the layout dialect and the Spring Security
dialect) now render through Tessera 0.1.0-SNAPSHOT. Thymeleaf, its layout and security extras, and
its configuration have been removed.

## Result

| Check | Status |
|---|---|
| Every page rendered by Tessera | Yes. All 19 templates were converted. No Thymeleaf template, dependency or configuration is left (`mvn dependency:tree` has no `org.thymeleaf`/`nz.net.ultraq`). |
| Build and tests on the target JDK (8) | `mvn clean verify -Ddependency-check.skip=true` passes on JDK 8: the 6 existing Selenide tests are unchanged and pass, and the 3 new tests pass. See "Build note" below for the OWASP step. |
| Page parity | `tessera-migrate compare` exits 0. All 80 requests in `pages.txt` come out `same`, with the recorded status and final URL. |
| Security | No `SafeHtml.unchecked` or `TrustedUrl.unchecked`, no new `permitAll`, CSRF untouched (Tessera adds the `_csrf` input to every POST form), no tests disabled. |

### Build note: OWASP dependency-check (pre-existing failure)

On the **original, unmodified** project, `mvn verify` already fails in
`dependency-check-maven:3.2.1:check`, after all tests pass. The plugin tries to download the NVD
2.0 XML feeds (`nvdcve-2.0-*.xml.gz`), and NVD has retired them ("Unable to download the NVD CVE
data"). The failure has nothing to do with the view layer. I did not disable or reconfigure that
security check. Run the build with `-Ddependency-check.skip=true`, or upgrade the plugin, which is
outside the scope of this migration.

The `license-maven-plugin` `format` goal runs at `initialize` and rewrites the copyright year in
every file header. That is how the build already behaved. I reverted that churn so the diff
contains only real changes.

## What changed

### Build (`pom.xml`)
- Removed `spring-boot-starter-thymeleaf`, `thymeleaf-extras-springsecurity4` and
  `thymeleaf-layout-dialect`.
- Added `dev.tessera:tessera-spring-boot-starter-javax` (Boot 2.0 uses `javax.servlet`).
- Configured `maven-compiler-plugin`:
  - `annotationProcessorPaths` lists Lombok **first**, then `tessera-compiler`.
  - `-Atessera.templates`, `-Atessera.package=com.kazuki43zoo.jpetstore.templates` and
    `-Atessera.messages` are set, so message keys are checked at build time.
  - `<proc>none</proc>` is set for `testCompile`.
- Updated the project name and description, and the README stack list.
- Excluded `pages.txt`, `baseline/**` and `.tessera/**` from `license-maven-plugin`. Its
  `format` goal otherwise prepends a licence header to `pages.txt`, which then no longer parses.

### Configuration
- `WebMvcConfig`: removed the `SpringTemplateEngine` SpEL-compiler tweak.
- New `config/TesseraTemplateAvailabilityProvider`, registered in `META-INF/spring.factories`.
  It tells Boot's `DefaultErrorViewResolver` that `error/400`, `error/404` and `error/500` exist
  as Tessera templates, so error pages resolve exactly as they did with Thymeleaf. Statuses
  without a template still fall back to Boot's default.

### Model and view types (what the old templates took from outside the model)
- `@TemplateView` on the classes the templates read: `Category`, `Item`, `Order`, `OrderLine`,
  `Product`, `Cart`, `CartItem`, `Favourite`, `AccountForm`, `OrderForm`, `CartUpdateForm`,
  `Messages` and `Messages.Message`. `Account` is deliberately not view-safe, because it carries
  the password hash.
- New `ui/LayoutModelAdvice` (`@ControllerAdvice`) adds a `layout` model attribute
  (`ui/view/LayoutView`) to every page, including Boot's error pages. It replaces these Thymeleaf
  lookups:
  - `#request.getAttribute('javax.servlet.error.status_code')` becomes `layout.errorResponse`.
  - `@productSearchCriteria.keywords` becomes `layout.keywords`. It is read lazily through a
    `Supplier`, because the search controller sets the keywords after `@ModelAttribute` methods
    have run.
  - `sec:authentication="principal.account.firstName/username"` becomes `layout.firstName` and
    `layout.username`.
  - `#authentication.principal.account.bannerOption/bannerName` becomes `layout.banner`.
- `sec:authorize="isAuthenticated()"` becomes the starter's `auth.isAuthenticated()`.
- Spring beans that the templates read through `@bean` are now model attributes:
  - `@clLanguages` and `@clCategories` in `AccountController` and `MyAccountController`.
  - `@clCreditCardTypes` in `MyOrderController`.
  - `@cart` and `@favourite` in `CartController`.
- `CatalogController` and `MyOrderController` add `productList`, `itemList` and `orderList` by
  explicit name. `Model.addAttribute(Object)` silently drops empty collections, which was
  harmless with Thymeleaf's null-tolerant `#lists.isEmpty`. In Tessera the attribute becomes
  `null`, and "Products not found." returned a 500.
- New `ui/view/ViewFunctions` (`@TemplateFunctions("view")`):
  - `view.price(BigDecimal)` reproduces `#numbers.formatDecimal(x, 1, 'COMMA', 2, 'POINT')`.
    Tessera's `numbers.formatDecimal` has no grouping.
  - `view.imageText(String)`: see the next section.

### Raw HTML from the database (`th:utext`)
`product.description` and the account's `bannerName` are stored as markup, for example
`<image src="/images/fish1.gif">Salt Water fish from Australia`. Thymeleaf printed them with
`th:utext`. Tessera would need `SafeHtml.unchecked` for that, which is not allowed. Instead,
`ImageText.parse` recognises exactly this shape (an image whose `src` is under `/images/`, then
text). The template renders `<img t:src=…>` followed by the text, escaped. Any value that does not
match is shown escaped as plain text. All seeded data matches, so the pages come out `same`.
Arbitrary HTML in those columns is now escaped instead of injected, which is intended.

### Forms
- `t:object`, `t:field` and `t:errors` replace the `th:` equivalents. The common account fields
  (`account/flagments :: commonForm`) are a typed fragment
  `commonForm(AccountForm accountForm, List<String> clLanguages, List<String> clCategories)` with
  its own `t:object="accountForm"`. The old `th:include` became `t:replace`, which gives the same
  DOM.
- Checkbox ids: Thymeleaf generated `listOption1`, `bannerOption1` and
  `shippingAddressRequired1`, and the label used `#ids.prev(...)`. These ids are now written
  literally. Tessera keeps an explicit `id`.
- Cart quantities are bound to indexed paths (`lines[0].quantity`), which Tessera's forms do not
  support. `CartController` builds `CartLineView`s from the `BindingResult`, holding the display
  value (the rejected input after a failed bind) and the field's error messages resolved through
  the `MessageSource`. The template writes `id` and `name` itself and joins errors with `<br />`,
  as `th:errors` did. The cart form is still a `method="post"` form, so it gets the CSRF input.

### Templates
- The layout dialect became a `layout(Fragment content, LayoutView layout, Messages messages)`
  fragment in `layout.html`. Each page does
  `t:replace="~{layout :: layout(~{::section}, layout, messages)}"`. `<title>` was moved before
  `<meta charset>` to match what the layout dialect produced.
- Valueless query parameters (`/cart?add&itemId=…`, `?remove&…`, `?form`) are written as
  `(add=null, …)`. Tessera renders a `null` query parameter as `?name`.

### Tests
- `JpetstoreApplicationTests` (Selenide) is unchanged.
- New `ViewRenderingTests` renders through the real resolvers with MockMvc. It checks that
  `<script>` in the search keywords comes out escaped, that the parsed product image renders, and
  that the error views are known to Boot's error view resolver.

### Parity check artefacts
- `pages.txt` holds 80 requests. They cover:
  - anonymous browsing, search (results, empty, none, XSS)
  - 404 / 400 / 500 error pages, logged out and logged in
  - cart (empty, add, invalid and valid update, remove)
  - login (form, error, bad credentials)
  - registration (empty, invalid, valid)
  - account update (form, errors, success)
  - the checkout flow with and without a shipping address, with errors at each step
  - orders list (empty and full) and order detail, and logout
- `baseline/` is the recording of the original app.
- Compare with
  `compare <url> pages.txt baseline/ --ignore '\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}'`. The pattern
  masks only the order timestamp, which changes on every run.

## Not converted / left as is
- `static/index.html` still says "… Spring Security + Thymeleaf" in its welcome text. It is a
  static file, not a view, and changing it would break parity of `GET /`.
- Builds on JDK 9+ are not covered here. The project targets Java 8: Lombok 1.16.22 and Spring 5.0
  on newer JDKs are pre-existing limitations, independent of Tessera.

## Where Tessera or its documentation fell short
1. **Annotation processor order with Lombok.** With `tessera-compiler` listed before Lombok in
   `annotationProcessorPaths`, every Lombok getter was rejected:
   `'username' is not a property of …AccountForm`. Listing Lombok first fixes it. The guide only
   says to list every processor "next to Tessera", and mentions Lombok `getIsX()` but not this
   ordering requirement.
2. **Boot error views.** The starter registers no `TemplateAvailabilityProvider`, so Boot's
   conventional `templates/error/404.html` (and `4xx`/`5xx`) are not found. Only a single
   `templates/error.html` works, and the guide documents only that. I wrote a small provider over
   `TemplateRegistry`.
3. **No indexed form paths** (`lines[0].quantity`). This is documented as a limitation, but no
   workaround is given. The value, the rejected input and the messages have to be reassembled in
   the controller from `BindingResult` and `MessageSource`.
4. **No supported way to render trusted markup from data** other than `SafeHtml.unchecked`. There
   is no sanitizer or allow-list API, so I wrote a narrow parser in Java.
5. **`numbers.formatDecimal` has no grouping and no locale**, so I needed a custom function to
   reproduce `'COMMA'` grouping.
6. **Null collections.** Spring's `Model.addAttribute(Object)` drops empty collections, and
   Thymeleaf hid that. In Tessera it leads to a `NullPointerException` and a 500. The guide's "a
   missing model attribute is null" is true, but this common Spring trap deserves a mention.
7. **Model values computed in `@ModelAttribute` methods run before the handler**, unlike
   Thymeleaf's render-time bean access (`@productSearchCriteria`). I needed a lazy getter. The
   guide recommends `@ControllerAdvice` for values every page needs without this caveat.
8. **Generated ids.** Thymeleaf's checkbox ids (`field1`) and `#ids.prev/seq` have no equivalent,
   and the guide does not say which `id` Tessera generates for checkboxes. I hard-coded the ids.
9. **Valueless query parameters** (`?form`, `?add&…`) are only reachable through a quirk listed
   under "known limitations" (a `null` parameter renders `?name`). If that is ever "fixed", these
   links break.
10. **`tessera-migrate` (Thymeleaf rewrite).** It treated the valueless link parameters `form`,
    `add` and `remove` as variables and stubbed them as `Object` template parameters. It also gives
    no help with the layout dialect, `sec:` or `th:include`. I wrote the templates by hand, using
    its output only as a reference.
11. **Fragments with their own form object.** A fragment that takes the form object as a typed
    parameter and declares `t:object` on it binds correctly, because the parameter name is used as
    the model attribute name. That works but is undocumented: the guide only describes `Field`
    parameters for reusable form parts.
12. **Docs and environment.** The skill's setup command (`mvn dependency:copy`) is what the guide
    suggests. I copied the jar from the local repository instead, as the guide allows. Template
    changes require `mvn clean compile` (no incremental build or hot reload), as documented.
