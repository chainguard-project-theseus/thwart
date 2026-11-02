# Migration report: Thymeleaf → Tessera

## Result

- All 19 Thymeleaf templates (layout, 15 pages, 3 error pages, one fragment file) are Tessera
  templates now. They compile to Java during `javac`. Thymeleaf, `thymeleaf-extras-springsecurity4`,
  `thymeleaf-layout-dialect` and the Thymeleaf engine configuration are gone. The startup log shows
  `Found 19 compiled Tessera templates` and no `another template engine is still configured` warning.
- Build on the target JDK (1.8): `mvn clean package` passes, with all 6 existing Selenide/HtmlUnit tests
  (they render through the real Tessera view resolver) plus 3 new unit tests for the HTML sanitizer.
  No test was changed.
- Page parity: `tessera-migrate compare` on 95 requests gives **95 `same`**, 0 reordered, 0 differ (exit 0).
  The only mask is `--ignore '\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}'`, for the order timestamp that changes
  on every run. The requests cover: every catalog page, including 404s for unknown
  category/product/item; search with hits, no hits, an empty query and no query; the empty and full
  cart, cart updates with type-mismatch and `@Min` errors, a successful update and removal, and the
  400 for a missing parameter; login (form, `?error`, bad credentials); registration (empty, invalid,
  success with flash message, and a duplicate user giving the 500 page); account update (form,
  validation errors, success with flash); the whole order flow (basic form with errors, shipping
  form with errors, confirm, submit, order detail, order list empty and full, another user's
  order → 404, non-numeric id → 400, the broken `?from` redirect → 400 page with a flash message);
  `GET /error` directly; unknown URL → 404; logged-out, `j2ee` (banner + favourites), `ACID` and a
  newly registered user. The list and recordings are in `.tessera/` (not committed).
- `mvn verify` fails only at `dependency-check-maven:3.2.1`. That plugin downloads the NVD 2.0 XML
  feeds, which NIST retired, so the check can't run here. The unmodified original fails at the same
  step (checked in a separate worktree). This has nothing to do with the migration, and I left it
  alone.

## What changed

**Build (`pom.xml`, `.mvn/wrapper`)**
- The Thymeleaf starter and the two dialect dependencies are replaced by `tessera-spring-boot-starter-javax`
  (Boot 2.0 uses `javax.servlet`).
- `maven-compiler-plugin` now has `parameters`, `annotationProcessorPaths` (Lombok first, then
  `tessera-compiler`), and `-Atessera.templates/package/messages`. `tessera-maven-plugin:check`
  makes a template-only edit trigger a recompile.
- The Maven wrapper moves from 3.5.2 to 3.9.9, because the Tessera plugin needs Maven 3.6.3+.
- `.tessera/**` is excluded from `license-maven-plugin`, which otherwise writes license headers into
  the migration work files.

**Templates**
- `layout.html` is now a fragment, `layout(Fragment content, boolean errorResponse, Messages messages,
  String searchKeywords, Account loginAccount)`. Each page replaces itself with it and passes
  `~{::body}`. This replaces `layout:decorate`/`layout:fragment`. `<title>` comes before
  `<meta charset>` because that is the order the layout dialect produced.
- `sec:authorize` became `auth.isAuthenticated()`. `sec:authentication="principal.account.x"` and
  `#authentication.principal.account.x` became `loginAccount.x`.
- `account/flagments.html :: commonForm` (`th:include`) became a typed fragment
  `commonForm(AccountForm accountForm, List<String> languages, List<String> categories)` on a
  `<t:block>` with its own `t:object`. It is inserted into the host `<div>`, so the markup is
  identical.
- `cart.html`: `*{lines[__${cartItemStat.index}__].quantity}` became
  `*{lines[cartItemStat.index].quantity}`. A `<td>` the browser closed implicitly is now closed
  explicitly (same DOM).
- `#lists.isEmpty(x)` became `x.isEmpty()`, and `le`/`gt` became `<=`/`>`. `#numbers.formatDecimal`,
  `#temporals.format` and `#ids.prev` carry over as the `numbers`/`temporals`/`ids` functions.

**Java**
- `@TemplateView` added to the types templates read: `Account`, `Category`, `Item`, `Product`, `Order`,
  `OrderLine`, `CartItem`, `AccountForm`, `OrderForm`, `CartUpdateForm(.Line)`, `Messages(.Message)`.
- New `ui/LayoutModelAdvice` (`@ControllerAdvice`) supplies what the old layout read from outside
  the model:
  - `searchKeywords`, from the session bean `@productSearchCriteria`;
  - `loginAccount`, from the security principal;
  - `errorResponse`, which replaces `#request.getAttribute('javax.servlet.error.status_code') ge 400`.
    It comes from the request, so a direct `GET /error` still shows the menu, as before.
- Controllers now put into the model what templates used to take from beans:
  - `CartController`: `cartItems`, `subTotal`, `favouriteProducts` (was `@cart`, `@favourite`);
  - account controllers: `languages`, `categories` (was `@clLanguages`, `@clCategories`);
  - `MyOrderController`: `creditCardTypes` (was `@clCreditCardTypes`).

  `CatalogController.searchProducts` sets `searchKeywords` after it updates the criteria, because
  advice methods run before the handler.
- List model attributes now have explicit names (`model.addAttribute("productList", list)`).
  Spring's `addAttribute(Object)` silently skips empty collections. Thymeleaf hid that because
  `#lists.isEmpty(null)` is true; in Tessera a missing list caused a `NullPointerException` (500) on
  "no products" and "no orders" pages.
- New `ui/MarkupFunctions` (`markup.inline`): stored HTML (product descriptions, account banners)
  that Thymeleaf printed raw with `th:utext` now goes through a `SafeInlineHtml` policy that allows
  only inline elements plus `<img>`. Rendered output is unchanged for the seed data; scripts, event
  handlers and off-origin images are dropped.
- `WebMvcConfig`: the `SpringTemplateEngine` SpEL-compiler tweak is removed.
- README: the stack list now names Tessera. `static/index.html` still says "Thymeleaf" in its heading.
  It is a static file, not a view, and changing it would break parity for `GET /`.

## Security

- No change to `WebSecurityConfig`: no new `permitAll`, and CSRF is still on. Tessera adds the token to
  every POST form; all POSTs in the parity run went through with it.
- No escaping bypass. The two raw outputs (`th:utext` for the product description and the banner) are
  sanitized now.
- No test was disabled or weakened.

## What could not be converted 1:1

Nothing visible to users. Two behaviours are now explicit rather than accidental:
- The stored HTML is sanitized, so markup beyond inline elements and `<img>` in the database would
  now be stripped (the intended security difference).
- The `?from` typo in `MyOrderController` (redirect to `/my/orders/create?from`, which gives a 400) is
  original behaviour, kept on purpose and covered by the parity run.

## Where Tessera or its documentation fell short

1. **The sanitizer doesn't treat `<image>` as `<img>`.** Browsers (and the HTML spec) rename an
   `<image>` start tag to `<img>`, and Thymeleaf output relied on that. `SafeHtml.sanitize` /
   `SafeInlineHtml.sanitize` drop `<image>` entirely, even with `img` allowed, so every product image
   vanished. I worked around it with a regex rewrite `<image` → `<img` in `MarkupFunctions` before
   sanitizing. The guide's rich-text section doesn't mention this. The sanitizer's parser should
   apply the same renaming the verifier's spec parser does.
2. **`HtmlPolicy.inlineBuilder()` image attributes are undocumented.** `HtmlPolicy.ATTRIBUTES` doesn't
   list `src`, and it isn't clear that `src` is kept implicitly for `img` (it is, if same-origin).
   I found that out by experiment in jshell. The guide says "images stay same-origin paths" but not
   that no attribute needs to be allowed for it, or that relative paths like `../images/x.gif` are
   dropped.
3. **Empty collections and `model.addAttribute(Object)`.** The guide stresses "a missing model
   attribute is `null`" but doesn't call out Spring's most common way to lose one: `addAttribute(list)`
   ignores empty collections. The migrator report didn't flag these calls either. They showed up only
   as 500s in `compare`.
4. **Advice timing.** The guide says to put per-page values in a `@ControllerAdvice`. It doesn't say
   that those run *before* the handler, so a value the handler changes (here the search keywords) is
   stale. Thymeleaf's `@bean` reads happened at render time.
5. **Error pages and the layout.** The guide covers Boot's error attributes but not how to port a layout
   that checks `javax.servlet.error.status_code`. It also doesn't say that flash attributes reach the
   error view's model (they do, and the old page showed them). I had to work both out with compare.
6. **`tessera-migrate` (converter) output.** Its output for layout-dialect pages was little use: it keeps
   the `layout:` and `sec:` attributes and stubs `Object` params. I wrote the pages by hand with a small
   script (`.tessera/scripts/`). It also doesn't report `@bean` reads inside `th:object`, or the
   `th:value` empty-attribute semantics for `@productSearchCriteria.keywords`, as a model-supply task.
7. **The license plugin and `.tessera/`.** The skill says to keep tool files in `.tessera/` inside the
   project. Builds that run `license-maven-plugin:format` (common in older Spring samples) rewrite
   `pages.txt` with a license header, and `compare` then fails with a parse error on line 1. A note in
   the skill, or `#`-comment tolerance in the header area, would help.
8. **`mvnw` version.** `tessera-maven-plugin` needs Maven 3.6.3+, so the project's 3.5.2 wrapper had to
   be bumped (the guide does say so). I didn't test how the plugin fails on an older Maven.
