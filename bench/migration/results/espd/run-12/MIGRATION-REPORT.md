# Migration report: Tiles + JSP to Tessera

The ESPD web application (`espd-web`, Spring Boot 1.4.1, Spring 4.3) rendered its pages with Apache Tiles 3
definitions over JSPs (JSTL, Spring form tags and scriptlets). Every page is now a Tessera template under
`espd-web/src/main/resources/templates`, compiled into `eu.europa.ec.grow.espd.templates` during `javac`.

## Build

- `pom.xml`: `java.version` 1.7 → 1.8. Tessera needs Java 8, so the build now targets JDK 8
  (`/usr/lib/jvm/java-1.8-openjdk`). The Tiles version property and its dependency management are gone.
- `espd-web/pom.xml`:
  - Removed `tomcat-embed-jasper` (also from the `non-embedded` profile), `jstl` and `tiles-jsp`.
  - Added `tessera-spring-boot-starter-javax`.
  - Added `commons-beanutils` 1.9.2 and `commons-collections` 3.2.2. Tiles used to bring these in, and the XML
    import/export code still uses them (I compared the old WAR's `WEB-INF/lib`).
  - `maven-compiler-plugin` is pinned to 3.8.1, because Boot 1.4 pins 3.1, which has no `annotationProcessorPaths`.
    It now has `<parameters>`, Lombok and then `tessera-compiler` as processors, and the `tessera.*` compiler
    arguments. `tessera.messages` names `i18n/messages_en` and `i18n/messages`, so every `#{key}` is checked at
    build time.
  - Added `tessera-maven-plugin:check`.
  - `templates/**` is excluded from resource filtering, so Maven doesn't rewrite `${...}`.
  - The enforcer's `requireMavenVersion` went from `[3.0.3,3.5.0]` to `[3.6.3,)`. The old range rejected the
    installed Maven 3.9 (the original build failed at the enforcer), and the Tessera plugin needs Maven 3.6.3+.
    The checked-in `mvnw` cannot run either way, because `.mvn/wrapper/maven-wrapper.jar` is missing.
- Build and test with: `JAVA_HOME=/usr/lib/jvm/java-1.8-openjdk mvn -B verify`. The application must also run on
  JDK 8: Spring 4.3 / Boot 1.4 do not start on JDK 21, and that was already true of the original.

## Old engine removed

- Deleted: `tiles.xml`, every JSP under `src/main/resources/META-INF/resources/WEB-INF/views`, and
  `util/I18NFunc.java`. That class was the JSP-only helper behind `i18n` / `span18n` / `div18n`.
- `WebConfiguration`: removed the `UrlBasedViewResolver`/`TilesView` resolver and the `TilesConfigurer`. Redirects
  are still resolved by Boot's default resolver.
- Removed `spring.mvc.view.prefix/suffix` from `application*.properties`.
- `WEB-INF/web.xml`: the WebLogic 500 error page (`500.jsp`) now points to `/error`, which Boot's error controller
  renders with `templates/error.html`.
- `espd-docs/.../view.adoc`: rewritten for the new view layer.

## How the views are structured now

- **Layout** (`espdTemplate.jsp` + header/breadcrumb/footer) is `templates/layout/espdTemplate.html` +
  `layout/header.html`. Values the JSPs read from beans or the response come from the `layout` model attribute:
  `LayoutModel`, built by `view/ViewModelAdvice` (`@ControllerAdvice`). These were `@espdConfiguration.*` via
  `s:eval`, `pageContext.response.locale`, `Language.VALUES`, and the breadcrumb map.
- **Tiles definitions** become one template per view name, e.g. `templates/request_ca_procedure.html`. Each passes
  the definition's literal `agent`/`flow`, which differ from the URL for `response_ca_*` (those extend
  `response_eo`). Shared Tiles fragments are now typed fragments in `common/wizard.html` (`progress`,
  `viewChangeRole`, `footerButtons`) and `common/selects.html` (`countries`, `currencies`, `groupCurrencies`).
- **Data-driven criteria.** The criteria JSPs walked `CriteriaTemplates` maps at runtime: computed Tiles names,
  form paths `${field}.answer`, message keys, and `data-target-*` selectors. Tessera requires all of those to be
  literals. `tools/tessera/gen_criteria.py` reads `CriteriaTemplates.java` and the criterion enums and writes the
  criteria sections as literal markup under `templates/criteria/`. `tools/tessera/gen_selects.py` does the same for
  the country and currency selects, whose options carry `data-i18n="country_XX"`. Re-run both after changing those
  classes. The generated files say so in a template comment.
- **Unbounded requirement groups.** `DynamicRequirementGroup` is a `Map<String,Object>`, which Tessera can't bind
  (`Object` is never view-safe). Those inputs are written with names from `GroupFunctions`
  (`x.unboundedGroups[0]["amount"]`, exactly as Spring's tags wrote them). Their display values come from the
  `groupValues` model map, which `view/GroupValuesInterceptor` builds: the bound value formatted as before, or the
  rejected value after a failed bind.
- **Scriptlets that mutated the model** now run in `EspdController` before the view is rendered: the "at least one
  representative" and "at least one unbounded group" additions.
- **Print / overview page.** `print.jsp` captured its printed part into a variable, printed it, and posted it back
  in the hidden `html` input for the PDF export. Tessera can't capture template output, so the printed part is the
  fragment `print/content :: content`. `view/PrintHtmlInterceptor` renders it to a string (`print/html`, via
  `SpringRenderContexts`) for the hidden input. The JSP left a `<div>` open in the captured HTML and
  `EspdController.addHtmlHeader` closed it. The fragment is balanced now, so `addHtmlHeader` no longer appends
  `</div>`.
- **Messages.** The JSPs printed every message raw. They now go through `t:utext="#{key}"` (Tessera's message
  policy). Messages with block markup (`<p>`) go through `i18n.blocks(...)` (`view/MessageFunctions`), a fixed
  `HtmlPolicy` that allows `p`, lists, inline tags, links and the `alert-link` class.

## Differences from the old pages (intended or unavoidable)

I recorded the original with `tessera-migrate record` (`.tessera/pages.txt`, 52 requests). It covers every page, every
role/flow combination, the add/remove buttons, re-renders with binding errors, the 404 error page and the
session-expired page. Every request returns the recorded status and final URL.

Raw `compare` reports all 52 pages as `differs`, because of item 1 below. With only items 1–3 and 6 masked
(`--ignore` on the head script, the breadcrumb block, the build timestamp and `data-target-*`), **32 of 52 pages are
`same`**. The other 20 differ only by:

- items 4 and 3b below: `rel="nofollow noopener"` on the filter page's message links, and `href` instead of
  `data-target` on the representative panels (procedure and overview pages);
- the overview pages' hidden `html` input, which holds a copy of the page, so it differs wherever the page does;
- one tooltip, `crit_selection_suitability_article_tooltip` (CA selection page). The message bundle contains a stray
  U+0081 control character, which Tessera's attribute escaper writes as U+FFFD.

Each difference is explained below.

1. **The inline `<script>` in `<head>`, on every page.** The JSP spliced values into the middle of the code
   (`"${locale}".toLowerCase()`, `validator(..., "${i18n['validator_required']}")`,
   `EcertisHandler("${url}", "${country}")`). In Tessera a script value can only be a JSON declaration at the top
   of the script, so the values are now `var validatorRequired = "...";` declarations and the code uses the names.
   The JavaScript does the same thing, but the script text differs, and `compare` compares script text. Every page
   therefore shows as `differs`. There is no way around this in Tessera without hard-coding each locale's text,
   which the guide forbids.
2. **Breadcrumb `data-i18n`.** The keys come from configuration (`espd.page.breadcrumb`), and Tessera's `data-*`
   attributes take no computed strings. Following the guide's recipe, each link now has
   `data-i18n-crumb="<index>"`, and a JSON block `#breadcrumb-i18n` lists the codes. `static/js/init.js`
   (`language()`) reads them, so the language switch still translates the breadcrumb.
3. **Selectors containing a loop index** in `data-target-show` / `data-target-hide` (unbounded groups) are written
   as the static prefix plus `data-target-index="N"`. The layout's `dataShow`/`dataHide` append it, following the
   guide's "the number; the script adds the prefix" recipe. The representative panels' Bootstrap
   `data-target="#...N"` became `href="#...N"` on the heading, which Bootstrap 3 collapse also reads.
4. **Messages.** Tessera's sanitizer adds `rel="noopener noreferrer"` to links inside messages and drops attributes
   it doesn't allow.
   - `expire_reminder_message` was printed raw inside a `<p>` (a browser closes it at the message's own `<p>`). It
     is rebuilt as the same DOM with `i18n.lead`/`i18n.rest`, and the first part is now plain text.
   - Messages using `<p/>` are normalised to `<p>`, which is how a browser reads them.
5. **The error page prints `trace` when there is one.** `error.jsp`'s test
   `IncludeStacktrace.ALWAYS eq errorProperties.includeStacktrace` read two names that were in no scope, so it was
   always true, and `server.error.include-stacktrace=always` is still configured. Behaviour is unchanged. Showing
   stack traces to users is a pre-existing problem worth fixing separately.
6. **Footer "last update" / about "Last built on"** show the build timestamp, which differs between two builds.
7. **No `action` on the wizard forms.** `<form:form>` without an action posted to the request URI, and omitting the
   action does the same.

## Where Tessera or its documentation fell short

- **Version support.** The guide supports Boot 1.5+ only. This app is Boot 1.4.1, and the starter's
  auto-configuration (`spring.factories`) worked on it unchanged. The guide says "older is untested".
- **`Map<String,Object>` form values can't be bound with `t:field`, even just to display them.** Real applications
  bind map entries routinely, and Spring's tags handle them. The workaround (hand-written `name`/`id` plus a
  precomputed value map) re-implements part of `BindStatus`, and every such field is a place where escaping and
  rejected-value handling are now the application's job.
- **`data-*` attributes reject every computed string,** including message keys from a fixed bundle and selectors
  built from a field name known at the call site. For this app (about 100 criteria rendered by generic fragments)
  that forced a code generator rather than typed fragments. A fragment parameter that is a compile-time literal at
  every call site can't flow into a `data-*` attribute either. The guide's recipes cover one-off values, not a
  generic fragment library.
- **No way to capture a fragment's output as a string inside a template.** The print page needed it, which led to a
  Java interceptor with `renderToString`. The guide (4.9) covers `renderToString` only in a sentence. That a
  standalone template can be just `<t:block t:params t:replace="~{...}">`, and that `t:object` works on
  `<t:block>`, I learned by trying.
- **Inline scripts.** The script rule (values only as top-of-script declarations) means no port of an inline script
  that splices values can ever come out `same` in `compare`. The guide presents `compare` passing as the goal but
  says nothing about this. Telling the two apart needs either a JS-aware compare or an explicit statement that such
  pages will differ.
- **The `RAW_CLOSES_ELEMENT` / `RAW_NOT_FLOW` verifier** rejects any `SafeHtml` inside a `<p>`, even one built with
  an inline-only `HtmlPolicy`. The compiler can't see the policy, so the API leaves no way to say "inline only".
- **`tessera.messages` is a file-system path list;** the guide never says how keys are looked up across several
  files. Listing both `messages_en` and the base `messages` file worked; the currency names exist only in the base
  file.
- **The `[(...)]` error for `<option>` content** was clear. The fix (`[[...]]`, text) changes nothing here, because
  the messages are plain text.
- **Stale templates.** Even with `tessera-maven-plugin:check` bound, some template-only edits did not reach the
  compiled classes until a Java file was touched or `clean` ran. I could not tell whether this was the plugin or the
  pinned compiler-plugin version, and the guide gives no way to check that the goal is effective.
- **No warning when the old engine's JSP view resolver remains** (the guide says JSP isn't reported). Removal was
  checked by hand.

## Not converted / open items

- Everything the old app rendered is converted. `500.jsp` was only reachable through `web.xml` on WebLogic; that
  path now uses the shared error template instead of a separate port.
- I could not exercise the multipart upload actions of `/filter` (import, merge, reuse, review) with
  `tessera-migrate`, which sends only url-encoded forms. While porting, I checked their error re-render (the
  `attachments` error message) by hand.
- XML download works. PDF download fails in this environment with `URI is not absolute` (FOP `defaultBaseUri`
  configuration), and fails identically in the original app, so it is not caused by the migration. The posted HTML
  is now balanced (see Print / overview page).
- `EspdController` gained a `@ModelAttribute("ecertisCountryName")` method. e-Certis' "information for country"
  message takes a translated country name, which is a message key computed from data, so it is resolved in Java.
  The method reproduces the JSP's output, including the `? country_` placeholder and `s:message` cutting the
  argument at a comma.
- A few non-English messages put `<p>` inside a `<p>`, or block markup inside a `<label>`. The message policy keeps
  only their text. The English pages are unaffected.

## Notes for running and building

- The original app could not be built with the installed Maven until the enforcer was skipped
  (`-Denforcer.skip`); I only did that to record it. It has to run on JDK 8.
- The sandbox's 4 GB memory cgroup OOM-killed Maven and app JVMs when several ran at once. Building with
  `JAVA_TOOL_OPTIONS=-Xmx700m` avoided it. This was an environment problem, not an application one.
- After an edit to a template only, plain `mvn compile` sometimes kept the stale generated class, even with the
  `tessera-maven-plugin:check` goal configured. `mvn clean` (or touching a Java source) always worked. This is
  listed with the shortcomings below.
