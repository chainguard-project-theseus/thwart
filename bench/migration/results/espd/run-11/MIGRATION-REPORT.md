# Migration of the ESPD views from JSP/Apache Tiles to Tessera

## Result

* Every page the application renders is a compiled Tessera template under
  `espd-web/src/main/resources/templates` (generated package `eu.europa.ec.grow.espd.templates`).
  The JSPs (`META-INF/resources/WEB-INF/views/**`), `tiles.xml`, the Tiles view resolver and
  `TilesConfigurer`, the `tiles-jsp`, `jstl` and `tomcat-embed-jasper` dependencies, the JSP
  helper `I18NFunc` and the `spring.mvc.view.prefix/suffix` properties are gone. The starter no
  longer logs `Tessera: another template engine is still configured`.
* `mvn clean package` (root, JDK 8, which the project targets) passes with all 931 existing tests,
  plus a new `TemplatesTest` that renders pages through the compiled templates. No test was
  changed, disabled or deleted.
* `tessera-migrate compare` against a recording of the original app (`.tessera/pages.txt`, 46
  requests, plus 23 more in `.tessera/pages2.txt`: 404, 405, 400 and 500 error pages, forms
  re-rendered with binding errors, escaping of user input, other locales, every wizard step for
  `request|response` x `ca|eo`, imports of a CE request) gives the recorded status and final URL
  for every page. Apart from the differences listed below, every page's DOM is identical
  (whitespace aside). See "Remaining differences" for why `compare` still reports `differs`.
* No `SafeHtml.unchecked`/`TrustedUrl.unchecked` (`target/tessera-review.txt` lists 0), no
  security configuration was added or changed (the app has no Spring Security; nothing was
  turned off), escaping is contextual everywhere.

## What changed

### Build (`pom.xml`, `espd-web/pom.xml`, `.mvn/jvm.config`)
* `tessera-spring-boot-starter-javax` replaces Tiles/JSTL/Jasper; `tessera-compiler` runs as an
  annotation processor after Lombok; `tessera-maven-plugin` (`check`, `review`).
  `-Atessera.messages` points to `i18n/messages_en` (the base `messages.properties` is partial).
* `java.version` 1.7 -> 1.8 (Tessera needs Java 8), `maven-compiler-plugin` 3.8.1.
* `commons-beanutils` 1.9.2 and `commons-collections` 3.2.2 are now declared: the application
  code uses them and they used to arrive transitively through Tiles.
* `templates/**` is excluded from resource filtering (it would rewrite `${...}`).
* The enforcer's `requireMavenVersion` `[3.0.3,3.5.0]` -> `[3.6.3,)`: the Tessera Maven plugin
  needs 3.6.3+, and the installed Maven is 3.9 (the original build only passed with
  `-Denforcer.skip`). The plugin repository URL `http://repo1.maven.org` -> `https://` (Maven
  3.8+ blocks plain http repositories).
* `.mvn/jvm.config` gives Maven a 2 GB heap: compiling the ~13k lines of generated criteria
  templates needs more than the default in the 4 GB container.

### Java
* `view/PageModelInterceptor` + `view/PageInfo`: everything the layout read from Spring beans
  (`<s:eval @espdConfiguration...>`), the session (`espd` on pages of other controllers), the
  servlet context (`applicationScope.deploymentEnvironment`) and the response
  (`pageContext.response.locale`) is put in the model as `page` after the handler ran (so it sees
  the final model, including Boot's error pages). It also renders the printable part of the
  overview into `htmlToPrint`, the hidden `html` field that the PDF export posts back.
* `controller/WizardViews`: the Tiles definitions `flow_agent_step` become literal template names
  (`wizard/procedure`, `wizard/exclusionCA`, ...), plus the Tiles `agent`/`flow` attributes the
  layout and some bodies used (`layoutAgent`, `layoutFlow`; e.g. `response_ca_*` and the overviews
  extended `response_eo`). The scriptlets that added empty requirement groups/representatives
  while rendering, and the list growth the form tags caused by reading indexed paths, moved here.
* `EspdController` returns those view names (the `previous`/`overview` handlers got a `Model`).
* Domain classes and `EspdInitializationParameters` (now public) are `@TemplateView`.
* `view/GroupFunctions` (`groups.*`): values, names and ids of the dynamic requirement groups
  (`field.unboundedGroups[i]["key"]` is a `Map<String,Object>`, which templates cannot read).
* `view/MessageParagraphs` (`i18n.*`) and `view/MessageMarkup`: messages whose markup a browser
  restructures (see below).

### Templates
* `fragments/layout.html` (espdTemplate.jsp + header/breadcrumb/footer), simple pages
  (`welcome`, `contact`, `about`, `error`, `sessionExpired`, `filter`), wizard pages and their
  includes (`wizard/*.html`), shared fragments (`fragments/wizard.html`: progress, footer buttons,
  session timer; `fragments/satisfiesAll.html`).
* `criteria/*.html`, `fragments/countries.html`, `fragments/currencies.html` are **generated** by
  `tools/tessera/generate_templates.py` from a JSON dump of `CriteriaTemplates`/`Country`/`Currency`
  (`tools/tessera/DumpCriteriaTemplates.java`). The JSPs inserted Tiles definitions chosen from
  those maps at runtime and built field paths, message keys and `data-*` values from them; Tessera
  needs those as literals, so the expansion now happens once at development time (re-run the
  script when `CriteriaTemplates` changes). `tools/tessera/jsp2tessera.py` and
  `balance_messages.py` were the mechanical first pass/a post-processing step.
* `static/js/init.js`: the show/hide handler for the inputs of repeated groups (see below).

## Remaining differences (and why they are right or unavoidable)

1. **eCertis URL in the layout script (every page).** The JSP wrote
   `EcertisHandler("https://...?countryFilter=[country]&lang=[lang]", "BE")`. Tessera only allows
   values in a script as a top-of-script declaration, written as JSON
   (`var ecertisCriterionURL = "https:\/\/...&lang=[lang]"`). `compare` expands the prelude
   but compares the JSON text literally, so `\/` and `&` versus `/` and `&` makes every page
   `differs`, although the JavaScript value is identical. The guide calls this difference expected;
   the tool does not normalize it. The URL is per-profile configuration, so it cannot be a literal.
   All other values in that script (locale, validator messages, country) compare equal.
2. **Indexed `data-target-*` values.** The self-cleaning radios of each conviction
   (`data-target-show="#fraud-self-cleaning-0"`) and the representative panels
   (`data-target="#createeo_info_respresent_div0"`) are built from a loop index. Tessera rejects
   computed strings in `data-*` attributes and the only literal workaround (a choice listing
   `-0`, `-1`, ...) is explicitly forbidden. Per the guide's recipe the radios now carry a literal
   prefix and the numeric index (`data-target-show-prefix` / `data-target-show-index`, handled in
   `init.js`), and the representative heading uses `href="#createeo_info_respresent_divN"`, which
   Bootstrap's collapse reads as well. Affected: exclusion pages of the economic operator, the
   procedure pages with Part II, the overviews.
3. **The hidden `html` field of the overview** holds the HTML text of the printable part. It is now
   Tessera's rendering of the same fragments (attribute order, whitespace and CSP nonces differ from
   the JSP's text), so the attribute value differs. The visible overview is identical. The field
   still ends inside the open last section, as print.jsp's did, because the controller appends the
   closing `</div>` when it builds the PDF.
4. **`crit_selection_suitability_article_tooltip`** contains a C1 control character (mis-encoded
   "Section α"); the JSP printed it raw, Tessera's attribute escaper writes U+FFFD. Only the CA
   selection pages.

Without differences 1-4 every recorded page is `same`.

## Decisions worth reviewing
* **Messages with block markup inside `<p>`.** Several messages contain `<p>`/`<p/>` and were
  printed inside a `<p>` or a `<span>`; browsers recover by closing paragraphs, and Tessera's
  verifier refuses such markup. `i18n.lead`/`i18n.paragraphs` (inside `<p>`) and `i18n.balanced`
  (elsewhere) produce exactly the tree the browser built, sanitized with a fixed policy
  (`MessageMarkup`). They read the bundle with `ResourceBundle` (UTF-8, same fallback as Spring's
  `ResourceBundleMessageSource`) because template functions cannot reach the `MessageSource`.
  The expire-reminder popup does the same in `PageModelInterceptor`.
* **Breadcrumb `data-i18n`.** The keys come from `espd.page.breadcrumb`. The template uses a choice
  between the literal keys of the shipped configuration (`ec`, `tools`, `espd`); another configured
  key renders its text but no `data-i18n` (so it is not re-translated client-side when the user
  switches language).
* The CE/CA exclusion definition is chosen with two literal fragment calls; `footerButtons` takes
  a boolean for the `export`/`next` label instead of a message key.
* Template functions are listed in `target/tessera-review.txt` for review; two return `SafeHtml`
  (sanitized message markup).

## Where Tessera or its documentation fell short
* **Spring Boot 1.4** is below the documented minimum (1.5). It worked here (starter
  auto-configuration, view resolution, error pages), but I could not verify that beforehand.
* **`compare` and JSON in scripts:** the guide says the escaped `/`, `&` are an expected
  difference, but `compare` reports them as `differs`, so a page with any URL in a script can never
  pass, although it is equivalent.
* **No way to write data-driven `data-*` values**, even when they are built from literals passed to
  a fragment (`'#' + field + '-form'` with `field` a fragment parameter that is always a literal at
  the call site). Apps that drive Bootstrap/jQuery from `data-target` need either code generation
  (what I did) or markup changes. The guide does not mention code generation as an option.
* **Large templates:** one fragment compiles to one Java method; a big generated fragment failed with
  javac's "code too large" (64 KB limit) instead of a template-level error, and compiling ~17k lines
  needed more heap than Maven's default in a 4 GB container (the build was OOM-killed without a
  message). Splitting into one fragment per criterion fixed both.
* **The verifier's error recovery rules reach into message bundles:** a message with `<p/>` makes
  every page that prints it fail to compile, in every locale; `t:utext` of a `SafeHtml` is never
  allowed inside `<p>`. The sanitizer (`SafeHtml.sanitize`) is not a spec parser (it drops `<p/>`
  and stray `</p>`), so reproducing the browser's tree needed hand-written code.
* **Messages:** `-Atessera.messages` has to name `messages_en` because the base file is partial;
  the error message explains this well. Message keys computed from data (country names, breadcrumb
  labels) have to be resolved in Java.
* **`t:if` and `t:replace` on one element**: the documented order is easy to trip over (`t:if` does
  not guard `t:replace`); it is silent at compile time.
* `form:radiobutton`/`form:option` details (empty dynamic attributes omitted, `label` as text)
  only showed up in `compare`, as the guide warns.

## Not converted / not done
* All pages are converted. `500.jsp` was not referenced anywhere (no error-page mapping) and was
  removed with the other JSPs; Boot's error view (`error.html`) renders all errors.
* `espd-docs/src/main/asciidoc/view.adoc` still describes the Tiles/JSP view layer; I did not
  rewrite the documentation.
* Multipart uploads (the import/merge actions of the filter page) cannot be expressed in
  `pages.txt`; their error states re-render the same `filter` template, whose upload error span is
  `t:errors="*{attachments}"`.
