# Thwart compared with other Java template engines

This page compares Thwart with two groups of engines. Part 1 covers the engines people migrate
from: Thymeleaf, JSP, FreeMarker, Velocity, Mustache and Pebble. Part 2 covers the other compiled,
typed engines you could move to instead: JTE, Rocker, JStachio and Qute. It says where another
engine is the better fit. The claims about other engines come from their official docs,
repositories and security advisories, checked November 2026 (sources at the end). If one is out of
date, fix it here.

## AI compatible, not API compatible

Thwart is not a drop-in replacement for Thymeleaf or any other engine. Its templates look like
Thymeleaf's (`t:` attributes on HTML), but its model is stricter: typed parameters, no runtime
expression language, no switch that turns escaping off, and a closed HTML profile. Most real
templates need changes to compile.

Instead of API compatibility, Thwart ships what lets a coding agent do that migration reliably and
check the result:

- A Claude Code plugin with a migration skill, and a porting guide with notes for each engine
  ([guide: migrating](guide/migrate.md)).
- Compiler errors written as fixes: each names the template file, line and column and says what to
  write instead.
- `thwart-migrate record` and `compare`: record every page of the old app, then compare the new
  app's pages against the recording. This works whatever the old engine was. The skill also has the
  agent load the pages in a headless browser and compare script errors and CSP violations, which
  markup comparison can't see. `thwart-migrate` also converts Thymeleaf syntax mechanically; it has
  no converter for the other engines.
- A migration test that measures agents migrating real open-source apps with only the plugin.

[AI compatible](ai-compatible.md) describes these parts and the test's results.

## Part 1: the engines you're leaving

### Expression language and injection history

Server-side template injection (SSTI) needs something at render time that evaluates template code.
There are two ways in: data reaching the evaluator (a view name, a preprocessed expression), and an
attacker who can write templates escaping the engine's sandbox. The second matters where users
edit templates (CMSs, notification templates, admin tools).

| | Runtime expression language | Injection history (examples) |
|---|---|---|
| **Thwart** | None. Templates are compiled to Java in the app's javac; the runtime has no template parser and no API that takes template source as a string | None known. Nothing evaluates template code at render time, and a template reaches only its typed parameters, so there is nothing to inject into ([security model §1](security-model.md#1-the-guarantee)). The separate XSS guarantee is relative to the pinned HTML parser and the documented exclusions ([§7](security-model.md#7-assumptions)) |
| **Thymeleaf** | OGNL; SpEL under Spring. `__${...}__` preprocessing evaluates the result of an expression as another expression | Injection through view names and fragment selectors, and repeated bypasses of the restricted expression mode, 2020 to November 2026 (listed below the table) |
| **JSP / JSTL** | Jakarta Expression Language in pages and tags. Spring's `<spring:eval>` evaluates SpEL | CVE-2011-2730: Spring's JSP tags evaluated EL twice on EL-capable containers, so request data could be evaluated as EL (Spring before 2.5.6.SEC03, 2.5.7.SR023, and 3.x before 3.0.6; Red Hat rated it as remote code execution) |
| **FreeMarker** | Its own expression language. Templates call public methods of exposed objects, and `?new` creates Java objects | FreeMarker's docs warn that with `?new` "the template author can create arbitrary Java objects". Its FAQ says that in general you shouldn't let users upload templates "unless those users are application developers, system administrators, or other highly trusted personnel", then lists what to configure if you do. Also CVE-2026-84939 (not injection): path traversal in template loading through a malformed locale, with localized lookup (on by default); 2.2.0 to 2.3.34, CVSS 9.1, fixed in 2.3.35 |
| **Velocity** | Velocity Template Language. Templates call methods, with arguments, on objects in the context; `SecureUberspector` restricts this for untrusted template writers | CVE-2020-13936: an attacker who can modify templates can run arbitrary Java code (Engine up to 2.2, fixed 2.3; the legacy `velocity` 1.7 artifact has no fix) |
| **Mustache** (JMustache) | None: tags are names. A name resolves by reflection to a map key, a method of that name, a getter or a field | No advisories found in GitHub's advisory database |
| **Pebble** | Its own expression language. Templates call methods on objects; the default validator (4.1.3) is a deny-list (`getClass`, `Class`, `Runtime`, `Thread`, `System`, ...) | CVE-2022-37767: bypass of the method access restriction (3.1.5, CVSS 9.8). Not fixed: the maintainers dispute it, saying template input is trusted code. CVE-2025-1686: the `include` tag could read local files (before 4.1.0; fixed in 4.1.0) |

Thymeleaf's history in detail:

- 2020: Acunetix and Veracode ("Spring View Manipulation") show that a view name or fragment
  selector built from request data is evaluated as an expression.
- CVE-2021-43466: template injection in thymeleaf-spring5 3.0.12 (CVSS 9.8); fixed in 3.0.13.
- CVE-2023-38286: sandbox bypass in Thymeleaf up to 3.1.1, as used in Spring Boot Admin.
- CVE-2026-40477 and CVE-2026-40478: bypasses of the expression restrictions (CVSS 9.0); fixed in
  3.1.4.
- CVE-2026-41901: another bypass; fixed in 3.1.5.
- November 2026: nine more advisories, including a critical restricted-mode escape
  (GHSA-2wmw-5fmc-m25v); fixed in 3.1.6 and 3.1.7.

### Escaping

| | Default | How it is switched off |
|---|---|---|
| **Thwart** | Contextual: the compiler picks the escaper or check for each output position (text, attribute, URL, script data, style, ...); see the [proof table](security-model.md#8-proof-table) | It can't be. Raw output is a typed `SafeHtml` built in Java by a sanitizer or escaper, or a `Fragment` from another template ([guide: templates](guide/templates.md)) |
| **Thymeleaf** | `th:text` and `[[...]]` HTML-escape. Not context-aware in HTML: JavaScript and CSS escaping only with an explicit `th:inline="javascript"` or `"css"` (or those template modes) | `th:utext`, `[(...)]` |
| **JSP / JSTL** | `${x}` in template text is written as is. `<c:out>` escapes by default; Spring's `form:` tags escape by default; other `spring:` tags (`spring:message`, `spring:eval`) escape only when `htmlEscape` or the `defaultHtmlEscape` context parameter says so | Plain `${x}`, `<c:out escapeXml="false">`, `htmlEscape="false"` |
| **FreeMarker** | Escapes only with an HTML output format: a `.ftlh` template (when `recognize_standard_file_extensions` is on) or a configured format. Otherwise nothing is escaped. Spring Boot's default suffix is `.ftlh` since 2.2 | `?no_esc`, `<#noautoesc>`, or a template without the HTML output format |
| **Velocity** | Nothing is escaped. `$esc.html(...)` (Velocity Tools) escapes one value; most of the escaping event handlers are deprecated since Engine 2.1 | Default |
| **Mustache** (JMustache) | `{{x}}` HTML-escapes. Not contextual | `{{{x}}}`, `{{&x}}`, `escapeHTML(false)` on the compiler |
| **Pebble** | Autoescaping on by default with the `html` strategy; the `js`, `css`, `url_param` and `json` strategies are chosen by the template author, not by context | `\| raw`, `{% autoescape false %}`, `autoEscaping(false)` on the engine builder |

### Spring support, maintenance and versions

| | Latest version | Spring and Spring Boot |
|---|---|---|
| **Thwart** | `0.1.0-SNAPSHOT`, not published to any repository | Starter for Spring MVC (servlet): Framework 4.3+ / Boot 1.5+ on Servlet 3.0+; tested on Boot 1.5, 2.0, 2.7, 3.5 and 4.1 |
| **Thymeleaf** | 3.1.7 (November 2026), Java 8+ | Auto-configured by Spring Boot. Boot 4 uses the `thymeleaf-spring6` module; a Spring 7 module is planned for 3.2.0, which has no date. Spring's docs call it actively developed and maintained |
| **JSP / JSTL** | Jakarta Pages 4.0 and Expression Language 6.0 (Jakarta EE 11), Standard Tag Library 3.0; Tomcat 11.0.x implements Pages 4.0 and EL 6.0 | Supported by Spring MVC. Not auto-configured by Spring Boot, whose docs say "If possible, JSPs should be avoided"; JSPs don't work in an executable jar |
| **FreeMarker** | 2.3.35 (August 2026, with the CVE-2026-84939 fix) | Auto-configured by Spring Boot |
| **Velocity** | Engine 2.4.1 (October 2024), Tools 3.1 (2021) | Deprecated in Spring 4.3, removed in Spring 5.0; Spring Boot removed its support in 1.5 |
| **Mustache** (JMustache) | 1.16 (November 2023); the repository still gets commits | Auto-configured by Spring Boot (`spring-boot-starter-mustache` uses JMustache) |
| **Pebble** | 4.1.3 (November 2026) | Not auto-configured by Spring Boot. Pebble's own `pebble-spring-boot-starter` (Boot 4) and `pebble-legacy-spring-boot-starter` (Boot 3) |

### Coverage in the migration test

The [migration test](ai-compatible.md) runs an agent with only the plugin on real open-source
apps, 18 in all. Held-out apps measure the plugin, and the skill and guide are never changed
because of what a held-out app does.

| Engine | Apps | Held out |
|---|---|---|
| Thymeleaf | 8: spring-petclinic and its Boot 1.5, 2.0 and 2.7 versions, fredbet, jpetstore, posters-demo-store (with htmx), purl-server | purl-server |
| JSP | 5: spring-framework-petclinic in 3 versions (Spring 4.3, 5.3 and current), jprime, espd (with Apache Tiles) | jprime, espd |
| FreeMarker | 2: JobSearch, budgetmaster | budgetmaster |
| Mustache | 1: spring-petclinic-mustache | none |
| Velocity | 1: luckyblog | luckyblog |
| Pebble | 1: ewp-registry | ewp-registry |

Coverage is thin outside Thymeleaf and JSP: one app each for Mustache, Velocity and Pebble, two
for FreeMarker, and one for Apache Tiles. A result for those engines says more about that app than
about the engine. Results by engine are in [AI compatible](ai-compatible.md#by-engine).

## Part 2: compiled alternatives

Thwart isn't the only compiled, typed Java template engine. JTE, Rocker and JStachio are also
compiled and have no runtime expression language, so server-side template injection through data
isn't possible in them either. Qute is different: it parses templates at runtime, and its
`{#eval}` section parses and evaluates a template at render time. None of them is AI compatible
in the sense above: none ships an agent skill or a page-parity checker. JTE has a JSP converter
(also in its IntelliJ plugin); otherwise moving to one is a manual rewrite.

| | Thwart | JTE | Rocker | JStachio | Qute |
|---|---|---|---|---|---|
| **Templates** | Compiled to Java by an annotation processor in the app's javac. The runtime has no template parser | Compiled to classes (`.jte` templates as Java, `.kte` as Kotlin). Precompiled by the Maven or Gradle plugin, or compiled at runtime in development mode (hot reload) | Compiled to Java by a Maven or Gradle plugin. Optional hot reload recompiles at runtime | Compiled to Java by an annotation processor | Parsed by the Qute engine at runtime. In Quarkus, type-safe expressions are validated at build time |
| **Syntax** | Thymeleaf attributes (`t:text`, `t:each`, `@{...}`, `#{...}`) on HTML | Its own `@`/`${}` syntax with Java (or Kotlin) expressions | Its own `@` syntax with Java expressions | Mustache (logic-less) | Its own `{...}` syntax |
| **Type checking** | Every expression is resolved at build time against declared `t:params` and a view-safe type allow-list | Java expressions, checked by javac | Java expressions, checked by javac | Bindings are checked against the model at build time | Only for type-safe templates (`@CheckedTemplate`) and type-safe expressions. Other expressions are resolved at render time, and a strict-rendering error is thrown then |
| **Auto-escaping** | Contextual: text, attributes (typed attributes take literals, numbers or a choice of literals), URLs (scheme-checked), `data-*` values, `<script>` (JSON only) and style (`css.*` values only), each with its own escaper or check ([proof table](security-model.md#8-proof-table)). Computed attributes outside a closed allow-list, and expressions in `on*` and `srcdoc`, are rejected | Contextual with `ContentType.Html`: tag body, attribute, `on*` JavaScript attribute and `<script>`, each with its own escaper. `javascript:` values in `<a href>` are dropped; since 3.2.5 also `javascript:` and `data:` in `href` on `a`, `area` and `base`, `action` on `form`, `formaction` on `button` and `input`, and `src` on `iframe`, and `srcdoc` output is omitted | By content type: an `.html` template HTML-escapes every value, the same way everywhere | By content type: HTML escaping (Mustache), not contextual. Off by default in zero-dependency mode | By content type: HTML/XML templates escape `' " < > &` everywhere. Not contextual |
| **Checked against a spec HTML parser** | Yes. At build time, each template is run through a pinned validator.nu HTML parser and rejected on a parse error or a construct where browsers build different trees. Tests also compare it with parse5, Chromium, Firefox ESR and WebKit (run after merges) | No such check documented | No | No | No |
| **Raw output** | Only a typed value: `SafeHtml` built in Java by `sanitize` or `escape`, or a `Fragment` passed between templates. No template syntax switches escaping off | `$unsafe{...}`, or a custom `gg.jte.Content` in a tag body | `@raw(...)` | `{{{name}}}` or `{{&name}}` (Mustache) | `\|raw`, `\|safe`, `RawString` |
| **Reflection at runtime** | None. Generated code calls getters directly | Template bodies are compiled Java; the engine loads template classes and calls `render` reflectively | "No reflection used" | Generated renderers use none. Locating a template from a model falls back to reflection unless you register a `JStacheCatalog` | A reflection-based value resolver is the fallback in JVM mode. Type-safe templates and `@TemplateData` generate resolvers |
| **Migration** | AI compatible: migration skill and guide for Thymeleaf, JSP, FreeMarker, Velocity, Mustache and Pebble; `thwart-migrate` records and compares the old app's pages and converts Thymeleaf syntax | JSP converter (`jte-jsp-converter`, also in the IntelliJ plugin); otherwise manual | Manual rewrite | Manual rewrite (or from Mustache) | Manual rewrite |
| **Spring** | Starter for Spring MVC (servlet), Spring Framework 4.3+ / Boot 1.5+, with Spring form binding (`t:object`, `t:field`, `t:errors`), messages and Spring Security helpers. No WebFlux | Starters for Boot 2, 3 and 4 (MVC and WebFlux). start.spring.io offers it for Boot 4.0.x only, not 4.1 or 4.2 | No integration in its docs | Spring modules for Web MVC and WebFlux; a Boot starter for Web MVC | Built for Quarkus. Usable standalone, without build-time validation. No Spring integration |
| **Java floor** | Java 8 (apps built with JDK 8+ javac) | Java 17 for jte 3. The last jte 2 release (2.3.2) is from April 2023; later security fixes are 3.x only | Java 8 | Java 17 | Java 17 (Quarkus 3) |
| **Build tools** | Maven and Gradle (processor wiring, no Gradle plugin). Hot reload by DevTools restart after a recompile | Maven and Gradle plugins, hot reload | Maven and Gradle, hot reload | Any build that runs annotation processors | Quarkus (Maven or Gradle), or standalone |
| **Maturity** | Prototype. `0.1.0-SNAPSHOT`, not published to any repository. No outside users, no IDE support | Apache-2.0, on Maven Central (3.2.5, November 2026). About 1.1k GitHub stars. IntelliJ plugin, Kotlin support | Apache-2.0, on Maven Central (2.4.0, November 2025). About 780 stars | BSD-3-Clause, on Maven Central (1.3.7, March 2025). About 340 stars | Apache-2.0, part of Quarkus (about 16k stars) with Red Hat backing |

### Where Thwart is weaker

- **Maturity and ecosystem.** Thwart is a prototype with no published artifacts, no releases, no
  community, no IDE plugin and no Stack Overflow answers. Every other engine here ships on Maven
  Central. JTE has an IntelliJ plugin, and Qute is part of Quarkus.
- **Features.** Hot reload is a recompile and a Spring Boot DevTools restart (`thwart:watch` or
  `gradle -t`), a few seconds per save, and only under Boot ([guide: hot reload](guide/migrate.md#hot-reload)). No
  Gradle plugin, no Kotlin, no WebFlux, no streaming or async rendering API. The closed HTML profile
  rejects markup that browsers accept, and the `<script>` rules restrict which JavaScript can come
  before an inlined value ([guide: output contexts](guide/templates.md#output-contexts); the full list is in
  [known limitations](guide/limitations.md)).
- **Expressiveness.** Templates read getters and call only registered `@TemplateFunction`s. JTE
  and Rocker let a template call any Java. That is a deliberate trade, but it means more code in
  controllers.
- **Security beyond SSTI.** Thwart's security differences from JTE, Rocker and JStachio are
  narrower than from the engines in part 1: more output contexts, checking against a spec parser,
  rejection of unclassifiable markup, and no template-level switch for raw output. The advisories
  published against JTE and JStachio have been escaping bugs (XSS), not injection. Qute's advisories
  include an SSTI fix (CVE-2026-12894: its reflection-based value resolver let someone who controls
  template text run commands; fixed in 3.27.6, 3.33.4, 3.39.2 and 3.40.0) and an `{#eval}`
  escaping bug (CVE-2026-93432: the evaluated template lost the content type, so values were
  written unescaped).

### When to pick which

- **Pick Thwart** to move an existing Thymeleaf (or JSP, FreeMarker, Velocity, Mustache or Pebble)
  app on Spring MVC off a runtime expression language while keeping Thymeleaf-shaped templates and
  Spring form binding, with an agent doing the migration and page comparison checking it. Also pick
  it if you are still on Java 8 or Boot 1.5-2.x.
- **Pick JTE** for a new Spring Boot 3/4 app, or a Kotlin app, that wants a mature, open,
  compiled engine with contextual escaping, Gradle support, hot reload and an IntelliJ plugin, and
  doesn't need Thymeleaf syntax. Our migration tooling could target JTE as well. It doesn't today.
- **Pick Rocker** for a non-Spring Java 8+ app that wants plain Java in templates (and its "near
  zero-copy" rendering), and can accept content-type-only escaping.
- **Pick JStachio** if you already use Mustache templates, or want logic-less templates checked
  at compile time, on Java 17+.
- **Pick Qute** on Quarkus.

## Performance

Only Thwart and Thymeleaf have been measured. The repo's JMH benchmarks (`thwart-benchmarks`)
render two petclinic-style pages to a `String`: Thwart was 19x faster on a 100-row table page and
42x faster on a fragment-heavy layout page (run of November 2, 2026). That is render only, against a
plain Thymeleaf `TemplateEngine` with OGNL and its template cache on, not the `SpringTemplateEngine`
with SpEL that Spring apps use, and it leaves out the request path. Method and results are in
[thwart-benchmarks/RESULTS.md](../thwart-benchmarks/RESULTS.md).

Rendering speed against the other engines on this page hasn't been measured. Much of the gap with
Thymeleaf comes from not interpreting templates at runtime, which other compiled engines such as
JTE share, so expect them in the same range.

## Sources

Checked November 2026.

- Thymeleaf: [download page (3.1.7)](https://www.thymeleaf.org/download.html),
  [Using Thymeleaf (OGNL, escaping, inlining)](https://www.thymeleaf.org/doc/tutorials/3.1/usingthymeleaf.html),
  [Thymeleaf + Spring (SpEL)](https://www.thymeleaf.org/doc/tutorials/3.1/thymeleafspring.html),
  [security advisories](https://github.com/thymeleaf/thymeleaf/security/advisories),
  [CVE-2021-43466 (GHSA-qcj6-jqrg-4wp2)](https://github.com/advisories/GHSA-qcj6-jqrg-4wp2),
  [CVE-2023-38286](https://nvd.nist.gov/vuln/detail/CVE-2023-38286),
  [CVE-2026-40477 (GHSA-r4v4-5mwr-2fwr)](https://github.com/thymeleaf/thymeleaf/security/advisories/GHSA-r4v4-5mwr-2fwr),
  [CVE-2026-40478 (GHSA-xjw8-8c5c-9r79)](https://github.com/thymeleaf/thymeleaf/security/advisories/GHSA-xjw8-8c5c-9r79),
  [CVE-2026-41901 (GHSA-c9ph-gxww-7744)](https://github.com/thymeleaf/thymeleaf/security/advisories/GHSA-c9ph-gxww-7744),
  [GHSA-2wmw-5fmc-m25v](https://github.com/thymeleaf/thymeleaf/security/advisories/GHSA-2wmw-5fmc-m25v),
  [Acunetix: Exploiting SSTI in Thymeleaf](https://www.acunetix.com/blog/web-security-zone/exploiting-ssti-in-thymeleaf/),
  [Veracode: Spring View Manipulation](https://github.com/veracode-research/spring-view-manipulation),
  [Spring 7 support issue](https://github.com/thymeleaf/thymeleaf/issues/1065),
  [Spring Framework: Thymeleaf](https://docs.spring.io/spring-framework/reference/web/webmvc-view/mvc-thymeleaf.html).
- JSP, JSTL and Spring's JSP tags: [Jakarta Pages](https://jakarta.ee/specifications/pages/) and
  [the 4.0 spec, section 2.2 (EL in template text)](https://jakarta.ee/specifications/pages/4.0/jakarta-server-pages-spec-4.0),
  [Jakarta Standard Tag Library](https://jakarta.ee/specifications/tags/),
  [`<c:out>`](https://jakarta.ee/specifications/tags/3.0/tagdocs/c/out),
  [Jakarta Expression Language](https://jakarta.ee/specifications/expression-language/),
  [Tomcat versions](https://tomcat.apache.org/whichversion.html),
  [`EvalTag` (SpEL)](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/tags/EvalTag.html),
  [`HtmlEscapingAwareTag`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/tags/HtmlEscapingAwareTag.html),
  [`AbstractFormTag`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/tags/form/AbstractFormTag.html),
  [CVE-2011-2730](https://nvd.nist.gov/vuln/detail/CVE-2011-2730),
  [Red Hat on CVE-2011-2730](https://access.redhat.com/security/cve/CVE-2011-2730).
- FreeMarker: [download (2.3.35)](https://freemarker.apache.org/freemarkerdownload.html),
  [expert built-ins (`?new`, `?api`)](https://freemarker.apache.org/docs/ref_builtins_expert.html),
  [FAQ (untrusted templates)](https://freemarker.apache.org/docs/app_faq.html),
  [auto-escaping](https://freemarker.apache.org/docs/dgui_misc_autoescaping.html),
  [output formats and `.ftlh`](https://freemarker.apache.org/docs/pgui_config_outputformatsautoesc.html),
  [CVE-2026-84939 (GHSA-27j2-h3m2-8237)](https://github.com/advisories/GHSA-27j2-h3m2-8237),
  [Spring Boot 2.2 release notes (`.ftlh`)](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-2.2-Release-Notes).
- Velocity: [news (Engine 2.4.1, CVE-2020-13936, CVE-2020-13959)](https://velocity.apache.org/news.html),
  [user guide (methods)](https://velocity.apache.org/engine/2.4/user-guide.html),
  [developer guide (`SecureUberspector`, escaping handlers)](https://velocity.apache.org/engine/2.4/developer-guide.html),
  [CVE-2020-13936 (GHSA-59j4-wjwp-mw9m)](https://github.com/advisories/GHSA-59j4-wjwp-mw9m),
  [Velocity Tools on Maven Central](https://repo1.maven.org/maven2/org/apache/velocity/tools/velocity-tools-generic/),
  [Spring 4.3 deprecation](https://docs.spring.io/spring-framework/docs/4.3.x/javadoc-api/org/springframework/web/servlet/view/velocity/VelocityConfigurer.html),
  [Spring Framework 5.0 release notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-5.0-Release-Notes),
  [Spring Boot 1.5 release notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-1.5-Release-Notes).
- JMustache: [README (name resolution, escaping)](https://github.com/samskivert/jmustache),
  [Maven Central](https://repo1.maven.org/maven2/com/samskivert/jmustache/),
  [GitHub advisory search](https://github.com/advisories?query=jmustache),
  [Mustache manual](https://mustache.github.io/mustache.5.html) (unescaped variables).
- Pebble: [escaping](https://pebbletemplates.io/wiki/guide/escaping/),
  [Spring Boot integration](https://pebbletemplates.io/wiki/guide/spring-boot-integration/),
  [releases](https://github.com/PebbleTemplates/pebble/releases),
  [Maven Central](https://repo1.maven.org/maven2/io/pebbletemplates/pebble/),
  [`BlacklistMethodAccessValidator`](https://github.com/PebbleTemplates/pebble/blob/v4.1.3/pebble/src/main/java/io/pebbletemplates/pebble/attributes/methodaccess/BlacklistMethodAccessValidator.java),
  [CVE-2022-37767](https://nvd.nist.gov/vuln/detail/CVE-2022-37767) and
  [issue #625](https://github.com/PebbleTemplates/pebble/issues/625),
  [CVE-2025-1686](https://nvd.nist.gov/vuln/detail/CVE-2025-1686),
  [escape strategies (`EscapeFilter`)](https://github.com/PebbleTemplates/pebble/blob/v4.1.3/pebble/src/main/java/io/pebbletemplates/pebble/extension/escaper/EscapeFilter.java).
- Spring Boot: [servlet web applications, template engines and JSP limitations](https://docs.spring.io/spring-boot/reference/web/servlet.html),
  [dependency versions](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html),
  [application properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html).
- JTE: [jte.gg](https://jte.gg/), [HTML rendering and escaping](https://jte.gg/html-rendering/),
  [precompiling](https://jte.gg/pre-compiling/), [Spring Boot starter](https://jte.gg/spring-boot-starter-3/),
  [jte 3.0.0 release notes (Java 17)](https://github.com/casid/jte/releases/tag/3.0.0),
  [start.spring.io metadata](https://start.spring.io/metadata/client), [GitHub](https://github.com/casid/jte),
  [security advisories](https://github.com/casid/jte/security/advisories),
  [JSP converter](https://jte.gg/jsp-converter/), [migrating to jte](https://jte.gg/migrating-to-jte/),
  [`Template.java` (reflective `render`)](https://github.com/casid/jte/blob/main/jte-runtime/src/main/java/gg/jte/runtime/Template.java),
  [Kotlin templates](https://jte.gg/kotlin/), [jte versions on Maven Central](https://repo1.maven.org/maven2/gg/jte/jte/).
- Rocker: [README](https://github.com/fizzed/rocker), [syntax and escaping](https://github.com/fizzed/rocker/blob/master/docs/SYNTAX.md).
- JStachio: [documentation](https://jstach.io/doc/jstachio/current/apidocs/), [GitHub](https://github.com/jstachio/jstachio),
  [security advisories](https://github.com/jstachio/jstachio/security/advisories).
- Qute: [reference guide](https://quarkus.io/guides/qute-reference),
  [CVE-2026-12894 (GHSA-prf4-p7fp-fr79)](https://github.com/quarkusio/quarkus/security/advisories/GHSA-prf4-p7fp-fr79),
  [CVE-2026-93432 (GHSA-9qp9-6p5x-rfh4)](https://github.com/quarkusio/quarkus/security/advisories/GHSA-9qp9-6p5x-rfh4).
- Thwart: [README](../README.md), [guide](guide/README.md) ([known limitations](guide/limitations.md)),
  [security model](security-model.md), [migration test](../bench/migration/README.md).
