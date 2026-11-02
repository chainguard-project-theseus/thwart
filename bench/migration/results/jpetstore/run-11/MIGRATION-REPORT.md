# Migration report: Thymeleaf → Tessera

## Result

- All 19 templates under `src/main/resources/templates` are Tessera templates, compiled by the
  annotation processor during `javac` (`com.kazuki43zoo.jpetstore.templates.*`). The startup log
  shows `Found 19 compiled Tessera templates`. There is no `another template engine is still
  configured` warning.
- Thymeleaf is gone: `spring-boot-starter-thymeleaf`, `thymeleaf-extras-springsecurity4` and
  `thymeleaf-layout-dialect` were removed from the pom, and so was the `SpringTemplateEngine`
  hook in `WebMvcConfig`. Comparing `mvn dependency:list` before and after shows only the
  Thymeleaf stack removed (thymeleaf, thymeleaf-spring5, extras, layout dialect, ognl, javassist,
  attoparser, unbescape) and the two Tessera artifacts added. The application used none of the
  removed libraries directly.
- Build on the target JDK 8: `./mvnw clean package` (and `mvn clean package`) passes, with all 6
  existing tests passing. No test was changed.
- Page parity: I recorded the original app with `tessera-migrate record` over a 77-request
  `pages.txt` and compared the new app with `tessera-migrate compare`. All 77 pages come out
  `same`, with the recorded status and final URL, and `compare` exits 0. Both apps were started
  the same way on a fresh in-memory HSQLDB. The list covers:
  - anonymous catalog pages, including an empty search and a search with no keywords
  - the cart: empty, with items, after quantity validation errors, after update and remove
  - the login page, plain and with a login error
  - registration: empty, with validation errors, a password mismatch, success with a flash
    message
  - My Account: the form, with validation errors, success
  - logged-in pages with favourites and banner
  - the whole order flow: basic form with errors, shipping form with and without errors,
    confirm, the order page, the orders list
  - error pages 400 / 403 / 404 / 500, anonymous and logged in
  - an HTML-bearing user value (`lastName=<b>MyBatis</b>`)

  `--ignore` masks two things, both narrowly: the order timestamp (`yyyy/MM/dd HH:mm:ss`) and the
  `created` timestamp on Boot's whitelabel 403 page.
- Security: no new `permitAll`, CSRF still on, escaping not turned off, no `TrustedUrl`, no tests
  disabled. `WebSecurityConfig` is unchanged. The POST forms get their CSRF token from Tessera
  automatically. Every POST in the page list succeeded, so the tokens are there.

## What changed

### Build
- `pom.xml`:
  - added `tessera-spring-boot-starter-javax` (Boot 2.0 / `javax.servlet`)
  - configured `maven-compiler-plugin` with `annotationProcessorPaths` (Lombok first, then
    `tessera-compiler`), `-Atessera.templates`, `-Atessera.package` and `-Atessera.messages`
  - added `tessera-maven-plugin:check`, so an edit to only a template or `messages.properties`
    recompiles
  - added a `<tessera.messages>` property
  - excluded `.tessera/**` (local migration tooling, git-ignored) from `license-maven-plugin`,
    because its `format` goal was rewriting the recorded pages
- `.mvn/wrapper/maven-wrapper.properties`: Maven 3.5.2 → 3.9.9, because
  `tessera-maven-plugin` needs Maven 3.6.3+.
- `.gitignore`: `.tessera/`.

### Java
- Added `@TemplateView` to the classes the templates read: `Account`, `Category`, `Product`,
  `Item`, `Order`, `OrderLine`, `Cart`, `CartItem`, `Favourite`, `ProductSearchCriteria`,
  `AccountForm`, `OrderForm`, `CartUpdateForm` (and `Line`), `Messages` (and `Message`).
- New `ui/controller/LayoutModelAdvice` (`@ControllerAdvice`). It puts the two values the layout
  read from outside the model into every model, error pages included:
  - `currentAccount`: the principal's `Account`, which replaces `#authentication.principal.account`
    and `sec:authentication="principal.account.*"`
  - `productSearchCriteria`: the session bean, which replaces `@productSearchCriteria`
- Controllers now add what the templates used to read as beans:
  - `CartController`: `cart` and `favourite` (were `@cart` and `@favourite`)
  - `AccountController` and `MyAccountController`: `clLanguages` and `clCategories`
  - `MyOrderController`: `clCreditCardTypes`
- `CatalogController` and `MyOrderController` add the lists under explicit names
  (`productList`, `itemList`, `orderList`). `Model.addAttribute(Object)` silently skips an
  **empty** collection. Thymeleaf tolerated the missing attribute (`#lists.isEmpty(null)`), but
  in Tessera the parameter was `null` and the page failed with a 500 (empty search, a user with
  no orders).
- New `ui/StoredMarkup` (`@TemplateFunctions("storedMarkup")`): `image(markup)` and
  `text(markup)`. See the stored HTML item below.

### Templates
- `layout.html` is now the fragment
  `layout(Fragment content, boolean errorResponse, Messages messages, Account currentAccount, ProductSearchCriteria productSearchCriteria)`.
  Every page replaces its `<html>` with a call to it and passes `~{::section}`. This replaces
  `layout:decorate` and `layout:fragment`. The page's `<section>` replaces the layout's, as the
  layout dialect did.
- `isErrorResponse`, which used to be read from the request attribute
  `javax.servlet.error.status_code`, is now a literal argument: `true` from the `error/*` pages
  and `false` from all others. Only Boot's error dispatch renders `error/*`.
- `sec:authorize` became `t:if="auth.isAuthenticated()"`, and `sec:authentication` became
  `currentAccount.*`.
- `account/flagments.html :: commonForm` is a `<t:block>` fragment that takes the form object
  and the option lists, and carries its own `t:object`. The pages `t:insert` it into the same
  `<div>`, which matches `th:include`.
- In the cart, `*{lines[__${cartItemStat.index}__].quantity}` became
  `*{lines[cartItemStat.index].quantity}`.
- Tables whose rows come from `t:each`/`t:if` now have an explicit `<tbody>`, which the browser
  inserted anyway. The cart's unclosed `<td>` is now closed. Both changes leave the DOM the same.
- The license headers on the templates are kept, because the build's `license-maven-plugin`
  would add them back.

## Things not converted one-to-one, and why

- **Stored HTML printed raw (`th:utext`)**:
  - Which values: product descriptions (`catalog/products`, `catalog/item`) and the banner
    (`layout`).
  - What they hold: markup from the database, `<image src="/images/x.gif">Some text`, printed
    unescaped, which was an injection point.
  - Why neither sanitizer works: `html.sanitize`/`sanitizeRich` drop the non-standard `<image>`
    element (browsers treat it as `<img>`), so the images vanished. On `catalog/products` the
    description also sits inside an `<a>`, where the compiler rejects any `SafeHtml`
    (`RAW_CLOSES_ELEMENT`).
  - What I did: the templates now render `<img t:src="storedMarkup.image(x)">` and the escaped
    `storedMarkup.text(x)`. The image path goes through Tessera's URL check. All seed data has
    this shape, so the pages are the same.
  - Behaviour change: any other markup someone stores in these columns now shows as plain text
    instead of running as HTML. That is the intended security fix.
- `th:if="${true}"` on the "My Account" link was dropped (no-op).
- `static/index.html` still says "Thymeleaf" in its welcome text. It is a static file, not a
  view, and changing it would break page parity on `/`.

## Pre-existing, not caused by the migration
- `mvn verify` fails in `dependency-check-maven` (OWASP) because the NVD XML feeds it downloads
  are no longer served (`nvdcve-2.0-2006.xml.gz` returns non-200). This happens the same way with
  the original, untouched project. Compile, tests and packaging pass, and `package` is green.
- The project targets Java 8 (`java.version` 1.8). Spring Boot 2.0 and Lombok 1.16 do not build
  on JDK 21. That was true before the migration, so builds use `/usr/lib/jvm/java-1.8-openjdk`.
- The build's `license-maven-plugin` (git mode) updates the copyright year in every file it sees
  as modified, so edited files now say `2016-2026`.

## Where Tessera, its tools or its documentation fell short

1. **`tessera-migrate` doesn't convert the layout or Spring Security dialects.** Every
   `layout:decorate`, `layout:fragment` and `sec:*` attribute is reported as "port it by hand".
   The guide's Thymeleaf notes say "Layout dialect pages become fragment calls", which reads as
   if the tool did it. All 18 pages had to be rewrapped by hand.
2. **The sanitizers silently drop `<image>`.** Browsers parse it as `<img>`, and it is common in
   legacy stored data (such as JPetStore's seed data). Neither the guide nor the policy builder
   mentions it, and nothing maps it to `img`. The difference only showed up in `compare`.
3. **No `SafeHtml` inside a link, whatever the policy.** `RAW_CLOSES_ELEMENT` rejects any
   `SafeHtml` inside `<a>`, even from a policy that cannot emit links. There is no way to declare
   a link-free policy, so the only fix the compiler suggests (move it into a `<div>` outside the
   link) changes the page. I had to split the stored markup in Java, which the guide's rich-text
   section discourages in general terms.
4. **Spring drops empty collections from the model.** `model.addAttribute(list)` doesn't add an
   empty collection. The guide covers "a missing model attribute is null" in general but not
   this common Spring trap. A typed `List<...>` parameter could warn about it, or the guide could
   recommend named attributes. The build doesn't catch it: it was a runtime 500 that only
   `compare` found.
5. **Some things the port relied on are undocumented.** All of them worked, but I could only
   find out by trying:
   - whether a `@TemplateView` class that implements `Iterable` can be the target of `t:each`
     (`Messages`)
   - whether `t:object` may sit on a `<t:block>` inside a fragment
   - whether valueless link parameters such as `@{/cart(remove,itemId=...)}` and
     `@{/accounts/create(form)}` are supported
   - whether `@ControllerAdvice` `@ModelAttribute` values reach Boot's error pages
6. **The doc says the error-page attributes depend on a setting, but not the advice values.**
   Section 2 says Boot's `message` error attribute depends on
   `server.error.include-message`, but nothing tells you whether model values from advice are
   present during error rendering. For a layout that shows user data on error pages, that is
   what you need to know.
7. **The Maven plugin needs a newer Maven than older wrappers ship.** `tessera-maven-plugin`
   needs Maven 3.6.3+, so older projects have to upgrade their `mvnw` wrapper. The guide says
   so, but the wrapper upgrade is a change to the build that a migration would rather avoid.
