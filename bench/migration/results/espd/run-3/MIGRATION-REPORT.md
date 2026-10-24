# Migration report: JSP + Apache Tiles → Tessera

## Result in short

| Check | State |
|---|---|
| Every page rendered by Tessera; JSP, JSTL, Jasper and Tiles gone | Done: no `.jsp`, no `tiles.xml`, no `tiles-jsp`/`jstl`/`tomcat-embed-jasper` dependency, no Tiles view resolver or `spring.mvc.view.prefix/suffix` |
| `mvn clean verify` on JDK 8 (`/usr/lib/jvm/java-1.8-openjdk`) | Passes. 931 tests, 0 failures, 0 errors, 0 skipped. No test was changed, disabled or deleted. |
| Page parity (`tessera-migrate compare`, my `pages.txt`, 51 requests) | 45 of 51 `same`. The 6 overview pages differ only inside the value of the hidden `html` input, plus the unavoidable script-escaping and timestamp differences below |
| Security | No `SafeHtml.unchecked` / `TrustedUrl.unchecked`, no `permitAll`, CSRF and escaping untouched; output that the JSPs printed raw (EL `${...}`) is now escaped |

## What I changed

### Build (`pom.xml`, `espd-web/pom.xml`)
* `java.version` 1.7 → 1.8. Tessera's runtime and generated code are Java 8 class files. JDK 7 isn't installed, so JDK 8 is the
  JDK the build now targets.
* `maven-compiler-plugin` pinned to 3.8.1 on the plugin itself, because the Boot 1.4 parent pins 3.1, which ignores
  `annotationProcessorPaths`. Lombok and `tessera-compiler` are listed as processors, with `-Atessera.templates`,
  `-Atessera.package=eu.europa.ec.grow.espd.templates` and `-Atessera.messages=.../i18n/messages_en` (the base
  `messages.properties` is partial; Spring falls back to English).
* `tessera-spring-boot-starter-javax` added (Boot 1.4 / Spring 4.3, `javax.servlet`).
* `src/main/resources/templates/**` is copied without resource filtering, because filtering would rewrite `${...}`.
* Removed `tiles-jsp`, `jstl`, `tomcat-embed-jasper` (also from the `non-embedded` profile) and the `tiles.version`
  property. Per the guide I diffed `mvn dependency:list` before and after. `commons-beanutils` 1.9.2 and
  `commons-collections` 3.2.2 had come in through Tiles and the application imports them, so they are now declared.
* The enforcer rule `requireMavenVersion [3.0.3,3.5.0]` failed the build of the **original** application with the
  installed Maven 3.9 (the `mvnw` script isn't executable here). I widened it to `[3.0.3,)`. Nothing else in the
  enforcer changed.

### Spring configuration
* `WebConfiguration`: removed the Tiles `UrlBasedViewResolver` and `TilesConfigurer`. Boot's own
  `InternalResourceViewResolver` handles `redirect:`. The application's `ResourceUrlEncodingFilter` stays, because the
  resource chain isn't enabled, so the starter doesn't register one, and Tessera links call `encodeURL`.
* `application*.properties`: removed `spring.mvc.view.prefix/suffix`.
* `web.xml`: the external-container `500` error page pointed at `/WEB-INF/views/500.jsp`. It now points at
  `/serverError`, a new `ServerErrorController` that renders the Tessera template `serverError.html` (the same page).

### What the JSPs read from outside the model now comes from Java
* `web/LayoutAdvice` (`@ControllerAdvice`) adds one model attribute, `layout` (`web/LayoutModel`, `@TemplateView`). It
  replaces everything the JSPs took from the request scope, Spring beans (`<s:eval expression="@espdConfiguration...">`)
  and scriptlets (the `I18NFunc` maps, `Language.VALUES`, `Country.VALUES`, `Currency.VALUES`):
  * the messages for computed keys (`layout.i18n[key]`, same "? key" fallback as `I18NFunc`);
  * the response locale;
  * the configuration flags (footer, piwik, eCertis URL, build date, breadcrumb, environment banner);
  * the language columns of the welcome page;
  * the form `action`: request URI plus query string, as `<form:form>` wrote it;
  * the ESPD of the session, for pages such as error pages that `EspdController` doesn't render.
* `web/EspdFunctions` (`@TemplateFunctions("espdfn")`):
  * the display value of `DynamicRequirementGroup` map entries, formatted as Spring's form tags displayed them (dates `dd-MM-yyyy`);
  * the country message key;
  * the comma split that `<spring:message arguments>` did;
  * index ranges.
* `controller/WizardPages`: the JSP pages **mutated the session ESPD while rendering**. Scriptlets in
  `procedure.jsp`, `exclusionEO.jsp`, `selectionCA.jsp` and `selectionEO.jsp` added empty groups or representatives,
  and Spring's form tags grew the yearly-turnover group lists to 5 by binding indexes 0–4. `WizardPages.page()` does the
  same before rendering and is called wherever `EspdController` returns a wizard view. For the overview it also renders
  the printed ESPD once with `SafeHtml.of(PrintContentTemplate…)`. That markup is shown on the page and posted back in the
  hidden `html` input from which the PDF is built, as `<c:set var="htmlToPrint">` did. The handlers that render a view
  got `Model`/`HttpServletRequest`/`HttpServletResponse` parameters for that.
* `@TemplateView` was added to the domain classes the templates read. `EspdInitializationParameters` became `public`
  so that it can be a template parameter.

### Templates (`espd-web/src/main/resources/templates`)
* `fragments/layout.html`, `fragments/header.html`: the Tiles `espdTemplate` with header, breadcrumb and footer, as a
  layout fragment.
* One page template per Tiles definition: `welcome`, `contact`, `about`, `filter`, `error`, `sessionExpired` and the 20
  wizard views `request|response_ca|eo_procedure|exclusion|selection|finish|overview`. Each passes the Tiles `agent`
  value and the URL path variables, which the old JSPs read from the request scope, explicitly. Several JSPs depended
  on that difference: for example, `response_ca_*` has Tiles agent `eo` but URL agent `ca`.
* `wizard/pages.html`, `wizard/forms.html`, `wizard/common.html`, `wizard/printContent.html`,
  `wizard/printFinish.html`: the wizard bodies, the procedure/finish forms, progress bar, footer buttons and overview.
* `criteria/gen.html` is **generated** by `tools/tessera/gen_criteria.py` from a JSON dump of
  `CriteriaTemplates` (`tools/tessera/criteria.json`, produced by `tools/tessera/DumpCriteriaTemplates.java`). The old
  criteria rendering was a Tiles machine driven at runtime by maps of field paths, message keys and flags. Tessera
  needs literal field paths and message keys, so the script partially evaluates those Tiles definitions:
  static flags are resolved, and one fragment per criterion is written with literal `*{field.path}` bindings and
  literal `#{key}` messages. Only conditions on the document remain in the templates. Re-run the script when
  `CriteriaTemplates` changes.
* `criteria/common.html`: the eCertis information block.
* `fragments/msg.html` is generated by `tools/tessera/gen_msg.py`. It prints a message whose key is computed at runtime
  as HTML, by switching between literal `t:utext="#{key}"` cases for the 17 keys that hold markup in some locale. It
  isn't used by the current pages any more, because the generator made every key literal. I left it as the documented
  way to do this. Delete it if you prefer.
* Messages that the JSPs printed raw (`${span18n[...]}`, `<s:message>`) use `t:utext="#{key}"`, so the build checks
  their markup against Tessera's allow-list in every locale. Every other value is escaped.
* `i18n/messages*.properties`: `phase_out_text` had `href=" https://..."` (leading space) in all 25 locales, which
  Tessera's allow-list rejects. I removed the space, which is the only bundle change.

### Removed
`WEB-INF/views/**` (50 JSPs), `tiles.xml`, `util/I18NFunc.java` (JSP-only helper), the Tiles/JSP dependencies and
configuration listed above.

## Page parity

`pages.txt` (51 requests, in the repository) covers:
* every static page, the 404 and 500 error pages, and the session-expired page;
* the filter page, empty and pre-filled through `/initialization`;
* the whole CA request flow and every `request|response × ca|eo × step` view;
* the EO response flow with add/remove of repeatable groups, posts with binding errors (they produce Boot's
  400 page in this application), the overview, and the French locale.

It was recorded from the original app (built from `HEAD`, run on JDK 8) and compared against the migrated app started the same way.

Final run: **45 same, 0 reordered, 6 differ.** The 6 are the overview pages: `POST …/finish overview=true` and
`GET …/overview`, in the CA, request-EO, response-CA and EO flows. The visible page is identical. The difference is the
value of the hidden `<input name="html">`, which holds the ESPD markup as one 515 KB string (input of the PDF export).
compare compares that string textually, apart from whitespace. Tessera writes the same elements with a different
attribute order than Spring's form tags (for example `<textarea rows class id name>` against
`<textarea id name class rows>`), escapes `'` as `&#39;`, doesn't use named entities such as `&ndash;`, and
doesn't keep the JSP's whitespace inside `<option>` bodies. The parsed markup is the same, so the PDF built from it is
the same. I tried rewriting the hidden copy into Spring's attribute order in Java. It got close but not equal within the
time, so I removed it rather than leave a half-working string rewriter in production code.

Differences on every page that I masked with narrow `--ignore` patterns in my own runs. Neither can be avoided with
Tessera:
1. `EcertisHandler("…", "…")` in the layout's `<script>`: Tessera's `[[...]]` writes JSON (`https:\/\/…&lang`),
   where the JSP printed `https://…&amp;lang` raw. The JavaScript string value is now correct; the old one contained a
   literal `&amp;`. The same applies to `validator(defaultValidators, "...", "...")` messages that contain an apostrophe
   (French page). Tessera has no way to print a value into a script except as JSON.
2. `Last Update: TODAY HH:MM`: the build timestamp. It differs between any two builds.

## What I couldn't convert, and why
* **Upload errors on the filter page** (`<form:errors path="attachments"/>`): `attachments` is a
  `List<MultipartFile>`, which is never view-safe. Tessera rejects `t:errors`, `fields.hasErrors` and
  `fields.errors` on that path at build time. In the old application the state is unreachable anyway: the upload
  handlers declare `BindingResult` after `Model`, so Spring throws before the `rejectValue` branch. The span was
  dropped.
* **Byte parity of the overview's hidden `html` value**: see above.
* `500.jsp` used `<tiles:insertAttribute>` outside a Tiles definition, which would have failed when rendered. The new
  `serverError` page includes the breadcrumb fragment instead.

## Where Tessera or its documentation fell short
1. **No way to write a value into `<script>` other than JSON.** Every page whose script embeds a URL or text with `/`,
   `&` or `'` differs from the original in `compare`. The guide calls this "expected", while the done criteria demand
   `same`. compare doesn't normalise JSON string escapes inside scripts.
2. **Map keys in field paths aren't supported** (`unboundedGroups[0]["reason"]`). ESPD binds about 20 map-backed
   inputs that way. I had to write `id`/`name`/`value` by hand and add a function for the display value, so Spring's
   editors and rejected values no longer apply to those inputs.
3. **Radios get no `id`** (documented), and Spring's ids carry a per-page counter (`answer1`, `answer2`). Every radio
   needed a hand-written `id`.
4. **Lombok `Boolean isX` fields** (`isSmallSizedEnterprise`) can't be bound with `t:field` at all. The guide only says
   they are readable as `x.getIsX()`, so those radios are hand-written too.
5. **`<select t:field>` leaves the `null` option unselected**, where Spring selected `<option value="">` for a
   `null` value. Every country/currency select needed an explicit `t:selected`. The interaction between `t:selected`
   and the bound value isn't specified ("is kept": does it replace the match or add to it?).
6. **`t:field` omits `value` when the bound value is `null`**, where Spring wrote `value=""`. It didn't matter for the
   DOM comparison here, but attribute presence changes.
7. **Attribute order isn't controllable.** Computed attributes are written after static ones, so markup that's
   compared textually (the hidden `html` copy) can't reproduce the old order without making every attribute computed.
8. **Fragments without parameters render in place**, so a library of parameterless message fragments isn't possible
   in a page template.
9. **`t:if` doesn't guard a `t:replace` on the same element** (documented but easy to miss); every conditional fragment
   call needs a wrapper `<t:block>`.
10. **Iterating fixed counts:** `numbers.sequence(1, 0)` counts down, so JSP `c:forEach begin/end` and loops over a
    possibly empty list needed a helper (`espdfn.indexes`).
11. **The message allow-list rejects `href` values with a leading space**, which browsers accept. The bundle had to be
    edited in 25 locale files.
12. **Guide gaps:**
    * nothing on JSP request-scope fallback to Spring's exposed path variables (`${agent}`/`${flow}`), which this
      application relied on;
    * nothing on rendering side effects of the old form tags (auto-growing lists and nested paths);
    * the installed `tessera-migrate` has no multipart support, so file-upload forms can't be recorded or compared;
    * `compare` takes several minutes per run when a page holds a large attribute, because it prints the whole
      differing line.
13. Boot 1.4 isn't in Tessera's tested matrix. It worked with the javax starter and compiler plugin 3.8.1 pinned on the
    plugin, as the guide predicts.

## How to rebuild the generated templates
```sh
# after changing CriteriaTemplates: dump it (classpath = espd-web/target/classes + the war's WEB-INF/lib)
java -cp <classpath> DumpCriteriaTemplates > tools/tessera/criteria.json
python3 tools/tessera/gen_criteria.py tools/tessera/criteria.json espd-web/src/main/resources/i18n \
    espd-web/src/main/resources/templates/criteria/gen.html 63adb07d-db1b-4ef0-a14e-a99785cf8cf6
# after changing which messages hold markup
python3 tools/tessera/gen_msg.py espd-web/src/main/resources/i18n espd-web/src/main/resources/templates/fragments/msg.html
```
(The last argument of `gen_criteria.py` is the UUID of `ExclusionCriterion.NATIONAL_EXCLUSION_GROUNDS`.)
