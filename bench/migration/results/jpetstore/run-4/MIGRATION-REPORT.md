# Migration report: Thymeleaf → Tessera

## Result

- Every page the application renders now comes from a compiled Tessera template (`src/main/resources/templates`,
  generated into `com.kazuki43zoo.jpetstore.templates`). That covers the catalog, cart, account, login, order and error pages.
- Thymeleaf is gone: `spring-boot-starter-thymeleaf`, `thymeleaf-extras-springsecurity4`,
  `thymeleaf-layout-dialect`, the `SpringTemplateEngine` customization in `WebMvcConfig`, and all
  `th:`/`sec:`/`layout:` markup.
- Build: `mvn verify` (JDK 21) passes: compile, the 6 existing Selenide tests (unchanged) and 2 new
  rendering tests. The OWASP `dependency-check` goal fails exactly as it did before the migration, because it cannot
  download the NVD feeds (`nvd.nist.gov` returns non-200, and the old 3.2.1 plugin uses the retired
  XML feeds). I used `-Ddependency-check.skip=true` locally and left the plugin unchanged.
- Parity: `pages.txt` has 67 requests. They cover every view, empty and full lists, every form with validation errors
  and after success, logged out and logged in (existing user and a newly registered one with
  favourites and banner), login errors, flash messages, and the error pages (404 ×3, 400 ×3, 500). Recorded from
  the original app (JDK 8, fresh `~/db`) and compared against the new app:
  **67 same, 0 reordered, 0 differ**. That holds with the new jar on JDK 8 and on JDK 21, using
  `--ignore '[0-9]{2}:[0-9]{2}:[0-9]{2}'`. Without the ignore, only the time of day of the two orders
  created during the run differs (4 pages). The order time is a fresh timestamp on every run, whichever engine renders it.
- Security: no `SafeHtml.unchecked`/`TrustedUrl.unchecked`, no `t:utext` at all, the security config is
  untouched (no new `permitAll`), CSRF is still on (Tessera adds the `_csrf` input to every POST form), and no tests are disabled.

## What changed

### Build (`pom.xml`)
- Added `tessera-spring-boot-starter-javax` (Boot 2.0 / `javax.servlet`). `maven-compiler-plugin` now uses
  `annotationProcessorPaths` (Lombok + `tessera-compiler`) and `-Atessera.templates/package/messages`.
  Test compilation runs with `<proc>none</proc>`, as the guide prescribes.
- `<release>8</release>`: the app still runs on Java 8 (the original only runs on JDK 8). The build itself needs
  JDK 21 because of Tessera, which forced three tool upgrades:
  - Lombok 1.16.22 → 1.18.36, because 1.16 crashes javac 21.
  - JaCoCo 0.8.2 → 0.8.13, because the 0.8.2 agent aborts the JDK 21 test JVM.
  - surefire `argLine` gets `--add-opens java.base/java.lang=ALL-UNNAMED` (Spring 5.0 CGLIB on JDK 21).
- Jar manifest `Add-Opens: java.base/java.lang`, so `java -jar` also works on JDK 16+ (JDK 8 ignores it).
- The license plugin's `format` goal rewrites every file it knows, so I excluded `pages.txt` and this report from it.
  Otherwise it prepends a header to `pages.txt` that `tessera-migrate` cannot parse.
- The project name and description now say Tessera, and so does the README. `static/index.html` still says "Thymeleaf" in its
  welcome text: it is a static page that is part of the parity check, so I left it as is.

### Model: what the templates used to read from outside the model
- `WebMvcConfig.WebMvcControllerAdvice` now adds the following to every model:
  - `site` (`ui.view.SiteView`), replacing the old template's reads of:
    - `#request.getAttribute('javax.servlet.error.status_code')` (the `errorResponse` flag);
    - the session bean `@productSearchCriteria.keywords`, read lazily at render time, because the search controller
      updates it after the `@ModelAttribute` methods run;
    - `#authentication.principal.account` (welcome name, user id, banner).
  - `clCreditCardTypes`, `clCategories` and `clLanguages`, previously read as beans with `${@clXxx}`.
- `CartController.viewCart` adds `cartRows`, `cartSubTotal` and `favouriteProducts`, previously read from the
  session beans `@cart` and `@favourite`.
- `@TemplateView` is on the domain classes, `CartItem`, the three form classes and `Messages`/`Message`.

### Templates
- Layout: the `layout:decorate` decorator became the fragment `layout(Fragment content, SiteView site, Messages messages)`
  in `layout.html`. Each page does `t:replace="~{layout :: layout(~{::section}, site, messages)}"`, and the layout
  `t:replace`s its `<section>` with the page's.
- `sec:authorize` became `auth.isAuthenticated()`, and `sec:authentication` became `site.account?.…`.
- `th:include="account/flagments :: commonForm"` became a typed fragment
  `commonForm(AccountForm accountForm, List<String> clLanguages, List<String> clCategories)` with
  `t:object="accountForm"`, called with `t:replace`. The output is the same `<div>` + content.
- `#numbers.formatDecimal(x, 1, 'COMMA', 2, 'POINT')` became the custom function `fmt.decimal(x)`
  (`ui.view.FormatFunctions`, `#,##0.00`), because Tessera's `numbers.formatDecimal` has no grouping.
- `@{/cart(add,itemId=…)}` and `@{…(form)}` (Thymeleaf parameters without a value) became
  `@{/cart?add(itemId=…)}` and `@{…?form}`.
- `#ids.prev('shippingAddressRequired')` became a literal id. The checkboxes get explicit `id="xxx1"` to keep
  Thymeleaf's generated ids (`listOption1`, …).
- Password fields get a static `value=""`, because Thymeleaf writes an empty value on passwords and Tessera omits it.
- `cart.html` had an unclosed `<td>` (the browser closed it implicitly). Tessera rejects that, so I added `</td>`.
  The DOM is unchanged.
- Error pages: Boot's `DefaultErrorViewResolver` only finds `error/404`-style views through a
  `TemplateAvailabilityProvider`, and Tessera ships none. Adding `TesseraTemplateAvailabilityProvider`
  (registered in `META-INF/spring.factories`) keeps `error/400|404|500.html` working unchanged.
  Other statuses still fall back to Boot's whitelabel page, as before.

## What could not be converted directly, and how it was handled

1. **Raw HTML from the database (`th:utext`)**: product descriptions and the user banner are stored as markup
   (`<image src="/images/fish1.gif">Salt Water fish…`). Escaping them would break the pages and the existing
   Selenide test, which clicks the link text "Fresh Water fish from China". `SafeHtml.unchecked` is not allowed.
   Instead, `markup.parts(...)` (`ui.view.MarkupFunctions`) splits the snippet into images and text. It only
   recognizes `<image src="/local/path">`, and everything else becomes escaped text. The template renders
   `<img t:src=…>` plus text. The DOM is identical, because browsers parse `<image>` as `<img>`, and there is no raw HTML path.
2. **Indexed form fields (`*{lines[__${i}__].quantity}`)**: Tessera has no indexed paths and no `__…__`
   preprocessing. `CartController` builds `CartRow`s with the field name and id, the display value from the
   `BindingResult` (so a rejected value like `abc` is echoed), and the resolved error messages
   (`MessageSource`). The template writes `id`/`name`/`value` and the errors by hand, joined with `<br />` like `th:errors`.

## Where the guide or Tessera fell short

- **Error views by status**: the guide only mentions a single `templates/error.html`. It does not say that Boot's
  status-specific `error/404.html` lookup stops working, because the starter registers no `TemplateAvailabilityProvider`.
  Nothing fails at build time: the pages silently become Boot's whitelabel page.
- **JDK 21 build of an old Boot 2.0 app**: the guide covers `--add-opens` for Spring 5.0 tests, but not that
  the JDK 21 requirement also breaks older build tooling (Lombok 1.16, JaCoCo 0.8.2 agent) and running the jar on JDK 21.
- **`tessera-migrate`** stubs valueless link parameters (`@{/cart(add,itemId=…)}`, `@{…(form)}`) as template
  parameters `Object add` and `Object form`, instead of converting them to a query string.
- **Links**: the guide documents no syntax for a query parameter without a value. `@{/path?flag(x=…)}` works,
  but I found that by trial.
- **Passwords**: per the guide, `t:field` never writes `value` on a password input, while Thymeleaf writes `value=""`.
  I added a static `value=""` next to `t:field`, and the pages compare `same`. The guide does not say whether static
  attributes next to `t:field` survive.
- **Checkbox ids**: Thymeleaf numbers checkbox ids (`listOption1`) and `#ids.prev` reads them back. Tessera has
  no `#ids`, and the guide says only that `t:field` writes `id` "unless present". So I set the ids explicitly
  to keep the labels and parity.
- **`numbers.formatDecimal`** has no grouping or locale, so a custom function was needed (the guide does say so).
- **Layout dialect**: the guide's layout example uses `t:insert="content"` on the host, which keeps the host
  element. Selecting the page's `<section>` with `~{::section}` therefore calls for `t:replace` in the layout,
  or the sections would nest.
- **Raw markup from data**: the guide offers only `SafeHtml.unchecked` (forbidden here) or "accept the escaped
  output", which would break the pages. Neither fits data like product descriptions, so I wrote a small parser.
- **`pages.txt`**: a POST right after an error page has no `_csrf` to send, so the request fails with 403. The guide says
  to "GET a form before posting it" but does not warn that error pages carry no token.
