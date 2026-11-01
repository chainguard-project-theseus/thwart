# Migration report: Thymeleaf → Tessera

All 19 server-rendered views of jpetstore (18 pages and error pages, plus one shared fragment file) are
now compiled Tessera templates. Thymeleaf, the layout dialect and the Spring Security dialect are gone.

## Result against the acceptance criteria

| Criterion | Status |
|---|---|
| Every page rendered by Tessera, old engine gone | Done. `mvn dependency:list` has no `thymeleaf*`, `thymeleaf-layout-dialect`, `ognl`, `attoparser` or `unbescape`. No Thymeleaf configuration is left, no `th:`/`sec:`/`layout:` attribute is left, and there is no "another template engine is still configured" warning at startup. The startup log says "Found 19 compiled Tessera templates". |
| `mvn clean verify` on the target JDK (1.8) | Passes on JDK 8 (`/usr/lib/jvm/java-1.8-openjdk`): 8 tests, 0 failures. That is the 6 existing Selenide tests, unchanged, plus 2 new tests. See the note on OWASP dependency-check below. |
| Page parity | `pages.txt` has 68 requests covering every page and form state (see below). `tessera-migrate compare` gives **68/68 `same`**, with the recorded status and final URL, using only `--ignore '\d\d:\d\d:\d\d'` (the order time). A second list of 26 edge requests (405, 400 from bad params, ACID user, order flow with a different card type, empty quantity, account update) also came out the same, except one intended difference (see "Intended differences"). |
| Security | No `SafeHtml.unchecked` or `TrustedUrl.unchecked` (`target/tessera-review.txt` lists 0, and `tessera-maven-plugin` runs with `failOnUnchecked=true`). No new `permitAll`: `WebSecurityConfig` is unchanged. CSRF is still on: every POST form gets Tessera's automatic `_csrf` input. Escaping is not turned off anywhere. No test was disabled or changed. |

## What changed

### Build (`pom.xml`)
- Removed `spring-boot-starter-thymeleaf`, `thymeleaf-extras-springsecurity4` and `thymeleaf-layout-dialect`.
  I diffed `mvn dependency:list` before and after. Only Thymeleaf's own libraries disappeared (thymeleaf,
  thymeleaf-spring5, the java8time and springsecurity4 extras, the layout dialect with its expression processor, ognl,
  attoparser, unbescape and javassist). The app uses none of them directly, and MyBatis ships its own shaded javassist.
- Added `dev.tessera:tessera-spring-boot-starter-javax` (the app is Boot 2.0.6 on `javax.servlet`).
- `maven-compiler-plugin`: `annotationProcessorPaths` lists Lombok first, then `tessera-compiler`.
  Also `-Atessera.templates`, `-Atessera.package=com.kazuki43zoo.jpetstore.templates` and `-Atessera.messages`.
  The Boot version and `java.version` 1.8 are unchanged.
- Added `tessera-maven-plugin` (`check` and `review`, with `failOnUnchecked=true`).
- license-maven-plugin: excluded the migration artefacts (`.tessera/**`, `baseline/**`, `pages.txt`, this file)
  so it doesn't stamp headers into them.
- OWASP `dependency-check-maven` 3.2.1: added `<failOnError>false</failOnError>`. This plugin version
  downloads the NVD 2.0 XML feeds, which NVD has retired (every request returns non-200). So `mvn verify` failed
  at that step whatever the code, including on the original commit. With this flag the scan still runs
  and `failBuildOnCVSS=7` still fails the build whenever NVD data is available. Only "no data could be
  downloaded" no longer fails it. Revert it if you upgrade the plugin to one that uses the NVD API.

### Java
- `@TemplateView` on the classes the templates read: `Category`, `Item`, `Product`, `Order`, `OrderLine`,
  `CartItem`, `AccountForm`, `OrderForm`, `CartUpdateForm` (+ `Line`) and `Messages` (+ `Message`). I took the
  guide's "shortcut" (annotating the existing classes) rather than writing view records, to keep the change small.
  `Account` is not exposed: the layout gets a small `ui/CurrentUser` view (username, first name, banner option
  and banner name) instead of the principal, so templates never see the password hash.
- `WebMvcConfig`: removed the `SpringTemplateEngine` customisation. The existing `@ControllerAdvice` now also adds
  `currentUser` (from `@AuthenticationPrincipal`, replacing `#authentication`/`sec:authentication`) and
  `searchKeywords` (replacing `@productSearchCriteria`) to every model. `CatalogController.searchProducts`
  overrides `searchKeywords` after it updates the session bean, because Thymeleaf read the bean at render time.
- `CartController` adds `cartItems`, `cartSubTotal` and `favouriteProducts`, replacing `@cart` and `@favourite`.
  `AccountController` and `MyAccountController` add `clLanguages` and `clCategories`, and `MyOrderController` adds
  `clCreditCardTypes`. All of these replace the `${@bean}` reads, using the same beans from `ApplicationConfig`.
- `ui/template/MarkupFunctions` (`@TemplateFunctions("markup")`):
  - `markup.html(x)` sanitises the HTML stored in the database (product descriptions and banners,
    which Thymeleaf printed with `th:utext`). Its `HtmlPolicy` allows only `<img>`, with a same-origin
    `src` and `alt`. Before sanitising it renames `<image` to `<img`, which is exactly what the HTML parser of every
    browser does, because the stored data uses `<image src=...>` (see "Shortcomings").
  - `markup.leadingImage(x)` and `markup.text(x)` serve the product search results, where the description sits
    inside an `<a>` (see "Shortcomings"). They split the stored description into its leading image path
    (printed through `t:src`, so it is URL-checked) and its text without tags (printed escaped).
- `config/TesseraTemplateAvailabilityProvider` is registered in `META-INF/spring.factories`, so that Boot's
  `DefaultErrorViewResolver` finds `error/400`, `error/404` and `error/500` as it did with Thymeleaf
  (see "Shortcomings").
- `CartUpdateForm.Line.getQuantity()` is written by hand instead of by Lombok, to work around a Tessera code-generation
  bug (see "Shortcomings"). Its behaviour is unchanged.

### Templates
- The layout dialect's `layout:decorate` became a `layout(Fragment content, boolean errorResponse,
  String searchKeywords, Messages messages, CurrentUser currentUser)` fragment. Each page replaces its `<html>`
  with a call that passes its `<section>` (`~{::section}`). This gives the same DOM the layout dialect produced:
  the page's own `<div id="Content">` wrapper is dropped, and its `<section>` is put inside the layout's.
- `isErrorResponse`, which read `javax.servlet.error.status_code` from `#request`, is now the literal `true`
  passed by the three error templates and `false` passed by every other page.
- `sec:authorize="isAuthenticated()"` became `t:if="auth.isAuthenticated()"`, and `sec:authentication="principal.account.*"`
  became `currentUser.*`.
- `th:include="account/flagments :: commonForm"` became `t:insert` of a `<t:block>` fragment
  `commonForm(AccountForm accountForm, List<String> clLanguages, List<String> clCategories)` with its own
  `t:object="accountForm"`. That is what `th:include` produced: the host `<div>` and the fragment's children.
- `*{lines[__${cartItemStat.index}__].quantity}` became `*{lines[cartItemStat.index].quantity}`,
  `#ids.prev(...)` became `ids.prev(...)`, and `#numbers`, `#temporals` and `#lists` became the built-in namespaces.
- The search box's `th:value` became `searchKeywords ?: ''`, keeping Thymeleaf's `value=""` for a null value.
- `{description unavailable}` in `order.html` is written as `[['{']]description unavailable[['}']]`, because a static
  brace is a build error.
- In `cart.html` I added the `</td>` that the original left out before `<td>&nbsp;</td>`. The browser DOM is the same,
  and Tessera rejects markup that only parses by error recovery. In `layout.html` the `<title>` now comes before
  `<meta charset>`, which is the order the layout dialect wrote.

### Tests
- The existing `JpetstoreApplicationTests` is unchanged and passes.
- New `ViewRenderingTests` renders pages through the real Tessera view resolver (MockMvc + full context).
  It checks that a `<script>` in the search keywords comes out escaped, and that a stored description keeps
  only its image.

### Verification artefacts
- `pages.txt` holds the 68 requests, and `baseline/` holds the original app recorded with `tessera-migrate record`
  (fresh `~/db`, `java -jar` on JDK 8). The list covers: the catalog, category, product, item, product search
  (hit, empty, no result, `<script>`), 404s for an unknown category, product, item, order and URL, a 400
  (non-numeric order id), a 500 (cart update of an unknown item), the cart (empty, add, re-add, remove,
  update with type and range errors, successful update), the login page (plain, `?error`, bad
  credentials, success), the registration form (empty, all-empty errors, mixed errors with markup
  input, success → login with flash message), My Account (redirect when logged out, form,
  errors, success with flash), favourites in the cart, the checkout flow (empty-cart redirect, basic form,
  errors, shipping form, shipping errors, confirmation, submit → order page with flash), My Orders (empty and
  with an order), and logged-in error pages showing the banner. The helper scripts are in `.tessera/` (git-ignored).

## Intended differences (not converted on purpose)

- **Stored XSS in the first name and user name is fixed.** The Spring Security dialect's
  `sec:authentication="principal.account.firstName"` (catalog welcome) and `...username` (My Account) printed the
  value **unescaped**. With an account whose first name is `<i>A</i>`, the old page rendered an `<i>` element. The new
  page shows the text `<i>A</i>`. My extra edge-case list shows this as the only `differs` page, and I'm keeping it.
  The 68-request `pages.txt` doesn't contain such an account, so it isn't affected.
- Stored HTML (product descriptions, banners) now passes through a sanitiser that keeps only same-origin images.
  On the seeded data the output is identical, because the browser turns the stored `<image>` into `<img>` as well.

## Where Tessera or its documentation fell short

1. **Generated code doesn't compile with type-use annotations (compiler bug).** `CartUpdateForm.Line.quantity`
   is `@NotNull @Min(0) Integer`, and Bean Validation 2.0 constraints are `TYPE_USE` annotations. On JDK 8
   javac, `t:field="*{lines[cartItemStat.index].quantity}"` generated a cast that prints the annotated type
   (`((@javax.validation.constraints.NotNull,@javax.validation.constraints.Min(0L) :: java.lang.Integer)) ...`)
   into `CartTemplate.java`, which is invalid Java. javac then reported syntax errors in the generated file,
   plus a misleading follow-up error, "generated before another annotation processor (Lombok?) finished",
   although Lombok was listed first. Workaround: a hand-written getter, whose return type has no annotations.
   The guide doesn't mention this, and the error doesn't point at the template.
2. **The sanitiser drops `<image>`.** Browsers parse `<image ...>` as `<img ...>` (an HTML spec rename), but
   `HtmlPolicy` treats it as an unknown tag and drops it. Stored legacy markup therefore loses its images. Workaround:
   rename the tag in Java before sanitising.
3. **`HtmlPolicy.builder().allowAttributes("src")` throws.** `src` is not in `HtmlPolicy.ATTRIBUTES`. Allowing
   `img` implies its same-origin `src`, but the guide's builder section doesn't say so. I found out at runtime
   (an `IllegalArgumentException` while initialising the class, which failed every page using it), not at build time.
4. **`RAW_CLOSES_ELEMENT` ignores the policy.** A `SafeHtml` inside an `<a>` is rejected even when it comes
   from a policy that allows only `<img>` and so can't contain a link or block element. The legacy page puts the
   product description inside a link. The only fix the guide offers is "insert it in a `<div>` outside the open
   element", which changes the page. I split the description into image path and text in Java instead (a fixed,
   non-recursive shape). Typed `SafeHtml` (an "inline/phrasing-only" sanitiser result) would avoid this.
5. **No `TemplateAvailabilityProvider` for Boot error pages.** The starter doesn't register one, so Boot's
   `DefaultErrorViewResolver` can't see `templates/error/404.html`, `error/500.html` and so on, and those
   templates would be silently skipped for the whitelabel page. The guide only documents a single
   `templates/error.html`. Per-status error templates are a very common Thymeleaf convention, and the
   guide's error-pages section doesn't mention that they stop working. I added a 10-line provider.
6. **`tessera-migrate` does little for layout-dialect apps.** It renames `th:` to `t:` but leaves
   `layout:decorate`/`layout:fragment`, `sec:*`, `th:include` and every `@bean` read for manual work
   (93 report items for 19 small templates). The guide calls the layout dialect "not supported, by design", but
   gives no recipe for reproducing the DOM that `layout:decorate` actually produces. That DOM differs from a plain
   `~{::body}` layout call: the page's elements outside the `layout:fragment` are discarded, and `<title>` is moved
   before `<meta charset>`. I found both only through `compare`.
7. **`sec:authentication` was unescaped in the old engine**, and neither the migrate report nor the guide warns
   about it. The guide's raw-output advice covers `th:utext`, JSP, Velocity and Pebble, but not the Spring
   Security dialect.
8. **Name clash with `dev.tessera.runtime.*`.** The always-imported `dev.tessera.runtime` package contains
   `Messages`, which clashes with this app's `component.message.Messages`. An explicit `t:import` of the class worked,
   but the guide doesn't say whether single-type imports win over the implicit package import.
9. **Minor:** `tessera-migrate` in the guide's instructions resolves through `mvn dependency:copy`. With the jar
   only in the local repository I copied it by hand, as the skill text allows. `compare` sends the previous page's
   `_csrf`, so a POST that follows an error page (which has no form) is a 403, both when recording and when comparing.
   That is easy to get wrong in `pages.txt`, and only the recorded status reveals it.
