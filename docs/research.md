# Tessera research memo

Date: 2026-10-22. I checked facts against public advisories and docs where noted. Lines marked **(unverified)** come from memory or secondary sources.

## 1. Thymeleaf CVE / SSTI history

| ID / issue | Affected | Root bug class | Notes |
|---|---|---|---|
| "Spring View Manipulation" (Veracode/Acunetix 2019-20, no CVE) | thymeleaf-spring 3.0.x | **Controller-returned view name / fragment selector treated as an expression.** `__${...}__` preprocessing plus `~{}` fragment syntax in `return "user/" + lang + "/welcome"` | The original SSTI pattern. A void controller method whose URI becomes the view name is also exploitable. |
| CVE-2021-43466 | thymeleaf-spring5 <= 3.0.12 (fixed 3.0.13) | **Expression injection/RCE (CWE-94)** through attacker-influenced template/view names. The 3.0.12 check for `T(` / `new ` in view names could be bypassed | CVSS 9.8. Public bypasses relied on whitespace and syntax variants of `T (`, the kind of thing a string-match blocklist misses **(unverified detail)**. |
| CVE-2022-46166 (adjacent) | spring-boot-admin < 2.6.10 / 2.7.8 | **SpEL injection** in notifier templates through the writable `/env` actuator | This is SpEL, not Thymeleaf. It shows that "templates" configurable at runtime end up evaluating code. |
| CVE-2023-38286 | Thymeleaf <= 3.1.1 (as used by SBA <= 3.1.1) | **Sandbox (restricted-mode) bypass through a deny-list gap.** The payload reached `org.springframework.util.*` statics | Thymeleaf 3.1.2 added `org.springframework.util` to the blocked packages. The vendor says the vulnerability belongs to SBA. |
| CVE-2026-40477 | <= 3.1.3 (fixed 3.1.4) | **Incomplete object-scope restriction.** `#ctx`, `#vars`, `#root`, `#execInfo` etc. stayed reachable in restricted mode | CVSS ~9.0 (GHSA-r4v4-5mwr-2fwr). |
| CVE-2026-40478 | <= 3.1.3 (fixed 3.1.4) | **Lexical pre-check bypass plus type deny-list gap.** `containsSpELInstantiationOrStaticOrParam` matched only `new` followed by ASCII 0x20, so `new\t` passed. The type blocklist covered only `java.*`, so Spring classes like `FileSystemResource` were open. The view-name check missed cookies, headers, `__..__` and `\|..\|` | Endor Labs write-up. The fix normalizes whitespace and extends the deny-list. **It is still a deny-list.** |
| CVE-2026-41901 | < 3.1.5 | **Incomplete patch for 40478.** Some constructs still run in restricted contexts | Endor Labs, April 2026. Few public details. |

**Lesson for Tessera:** every one of these bugs comes from evaluating a Turing-complete reflective EL (SpEL/OGNL) at runtime on strings that may be partly attacker-controlled, and then patching with string or type deny-lists. Tessera removes the class structurally:
- no runtime parsing of expressions;
- no view-name-as-expression;
- no preprocessing (`__${}__`);
- no `T()`/`new`/`@bean`;
- a closed grammar resolved against static types at build time.

## 2. Thymeleaf migration surface (ranked by estimated real-world frequency)

Ranking is my estimate from tutorials, petclinic/CAS/RuoYi/Broadleaf templates and Stack Overflow tag volume. It is not measured; a grep over the corpus in section 6 should confirm it.

**Tier 1 (nearly every app):**
- `th:text`
- `th:href="@{/path/{id}(id=${x.id},q=${q})}"`
- `th:each="x, stat : ${list}"`
- `th:if` / `th:unless`
- `th:src`
- `th:action` + `th:object` + `th:field="*{name}"` (Spring form binding: id/name/value/checked plus the hidden `_field` for checkboxes)
- `th:value`
- `th:replace` / `th:insert` (`~{layout :: header}`)
- `th:fragment`
- `th:class` / `th:classappend`
- `#{msg.key(${arg})}`

**Tier 2:**
- `th:utext` (raw HTML; a major XSS source)
- `th:attr="data-x=${..}"`
- `th:selected` / `th:checked` / `th:disabled` (boolean attrs)
- `th:with="a=${..}"`
- `th:switch` / `th:case` (`*` default)
- `th:block`
- `th:errors` / `#fields.hasErrors('*')` / `th:errorclass`
- `th:inline="javascript"` (`[[${x}]]` JS-escaped)
- `[[...]]` / `[(...)]` text inlining
- `th:remove="all|tag|body|all-but-first"`
- `sec:authorize` (thymeleaf-extras-springsecurity)
- `layout:decorate` / `layout:fragment` (Ultraq layout dialect; very common in older apps)

**Tier 3:**
- `th:style`, `th:styleappend`, `th:id`, `th:name`, `th:title`, `th:alt`, `th:placeholder`
- `th:onclick` (restricted in 3.1)
- `th:assert`, `th:object` on non-form elements, `th:ref`
- `th:attrappend` / `th:attrprepend`
- `th:data-*` (3.x generic `th:<any>`)
- fragment expressions passed as params (`~{::title}`, `~{::link}`)
- the no-op token `_`
- `th:replace="${cond} ? ~{a} : ~{}"`

**Expression forms:**

| Form | Meaning |
|---|---|
| `${}` | Variable or SpEL. Covers property paths, method calls, ternary, Elvis `?:`, safe-navigation `?.`, collection selection `.?[]` and projection `.![]`. |
| `*{}` | Selection relative to `th:object` |
| `#{}` | i18n message |
| `@{}` | Link: context-relative, path vars `{id}`, query params, URL encoding |
| `~{}` | Fragment: `template :: selector`, `this`, `::#id`, params `frag(a=${x})` |
| Literals | `'str'`, `\|lit ${sub}\|`, numbers, `true`/`false`/`null` |

**Utility objects (most to least common):**
- `#strings` (isEmpty, abbreviate, toUpperCase, contains, substring)
- `#temporals` (format; extras-java8time)
- `#dates`
- `#lists` / `#sets` / `#maps` (size, isEmpty, contains)
- `#numbers` (formatDecimal, formatCurrency, sequence)
- `#fields`, `#authentication` / `#authorization`
- `#request` / `#session` / `#httpServletRequest` (removed or restricted in 3.1)
- `#bools`, `#arrays`, `#objects` (nullSafe), `#aggregates`, `#messages`, `#uris`, `#ids`, `#calendars`, `#conversions`, `#execInfo`

**Status variable** (`stat`, or the implicit `xStat`): `index`, `count`, `size`, `current`, `even` / `odd`, `first` / `last`.

**Fragment semantics:**
- `insert` keeps the host tag and puts the fragment inside it.
- `replace` substitutes the host with the fragment.
- `include` is deprecated.
- Parameterized fragments take named or positional args. `th:fragment="f(a,b)"` defines the signature.
- A fragment can be selected by markup selector (`::#id`, `::div.cls`) without `th:fragment`. Tessera should support only named fragments, and maybe `#id`.
- Precedence order (insert/replace > each > if > ... > text > fragment > remove) affects semantics when several attributes sit on one element. Tessera must document a fixed order and ideally reject ambiguous combinations.

## 3. Prior art

| Project | Compilation | Typing | Escaping | Copy this |
|---|---|---|---|---|
| **JTE** (gg.jte) | `jte-maven-plugin` / Gradle plugin, goals `generate` (Java sources) or `precompile` (classes). Also runtime compilation with hot reload in dev | `@param Type name` header, `@import`. Expressions are plain Java, so typing comes from javac | `ContentType.Html` uses `OwaspHtmlTemplateOutput`, which is context-aware: tag body, attribute, `on*` JS attribute, `<script>`. `HtmlPolicy` enables compile-time checks such as `PreventInlineEventHandlers`. `$unsafe{}` opts out. "Smart attributes" omit null/false and handle booleans | The typed param header, the dev hot-reload / prod precompile split, the HtmlPolicy idea, and the Spring Boot starter design. It is the closest prior art. |
| **Rocker** (fizzed) | `rocker-maven-plugin` generates Java at build time, with an optional hot-reload mode | `@args (String title, List<User> users)` header. Expressions are Java | Escapes by content type (HTML), **not contextual** | Generated classes expose a typed static `template(args)` API, plus near-zero-copy byte output (pre-encoded static chunks). |
| **JStachio** | **Annotation processor** (`@JStache` on a record or class) generates a renderer at javac time | Mustache tags resolved against `javax.lang.model` types of the model, with build errors for missing properties. No reflection | Pluggable `Escaper`, HTML by default, not contextual | Shows that annotation processing plus `javax.lang.model` works for template type-checking. It has Spring integration (`jstachio-spring`) and also emits a zero-dependency mode. |
| **Quarkus Qute** | Build-time validation in the Quarkus extension. Rendering is still interpreted, but uses **generated value resolvers** (no reflection) | `{@org.acme.Item item}` param declarations, `@CheckedTemplate` static native methods, `TemplateInstance` | Auto-escapes for `text/html` and `xml`. `RawString` / `{x.raw}` opt out. Not contextual | Typed `@CheckedTemplate` API, the "template extension methods" model (a closed set of helpers instead of arbitrary calls), and validation error messages with template line numbers. |
| **Closure Templates / Soy** | `SoyFileSet` compiles to JS, Python and Java. `jbcsrc` emits JVM bytecode | `{@param name: type}`, a real type system (records, unions, protos) | **Strict contextual autoescaping.** Content kinds `html`, `attributes`, `uri`, `trusted_resource_uri`, `css`, `js`, `text`. Typed sanitized-content values interoperate with **safe-html-types** (`SafeHtml`, `SafeUrl`, `TrustedResourceUrl`, `SafeStyle`, `SafeScript`; built by builders and sanitizers, unwrapped only by trusted code) | Content kinds per template and per param, an HTML-aware parse that fails the build on unbalanced or ambiguous tags, safe types as the only way to bypass escaping, and `TrustedResourceUrl` for `<script src>`. |
| **Go html/template** | Runtime parse, with escaping inferred once per template | Untyped | A context-propagation state machine (HTML text, tag, attr name, quoted and unquoted attr value, URL part, JS string/regex, CSS) inserts escaper pipelines. Errors on ambiguous context (branches ending in different contexts). Typed `template.HTML`, `URL`, `JS` and similar opt-outs | The context lattice and its "ambiguous branch = error" rule, URL scheme filtering (`#ZgotmplZ`), and the attribute-type classification table (`attrType`). Port it nearly as-is to Tessera's compile pass. |

**Synthesis:**
- Take JTE/Rocker-style codegen.
- Use a JStachio-style annotation processor for type resolution.
- Take Soy/Go-style contextual escaping, computed at compile time, so each output site gets a fixed escaper.
- Take Qute-style closed helper sets.
- Use safe-html-types (`com.google.common.html.types`) as the trusted-value API.

## 4. HTML parsing for a build-time compiler

| Parser | Model | Positions | Preserves source fidelity? | Notes |
|---|---|---|---|---|
| **attoparser** (Thymeleaf's) | Event-based (`IMarkupHandler`), non-validating, **non-normalizing** | line/col on every event (element start, attribute, text, comment) | **Yes.** Attribute name, operator (`=`) and value are reported with their own line/col, and the value has "outer" (with quotes) and "inner" offsets, so quote style and whitespace survive | HTML-aware for void elements, autoclose and `<script>`/`<style>` raw text, with no tree rewriting. Latest release 2.0.7.RELEASE (used by Thymeleaf 3.1.x) **(version unverified)**. Lightly maintained, Apache-2.0. |
| **jsoup** | HTML5-spec tree builder (DOM) | `Parser.setTrackPosition(true)` gives `Node.sourceRange()`, `Element.endSourceRange()`, and `Attribute.sourceRange()` since 1.17.1. Current 1.23.x | **No.** It does spec-mandated tree fixups (implicit `tbody`, foster parenting, moving misnested tags), lower-cases names, and normalizes output quoting | Great API, actively maintained. Tree rewriting breaks the "what you wrote is what gets emitted" promise and fragment semantics. |
| **nu.validator htmlparser** | Spec-conformant HTML5 tokenizer and tree builder (SAX/DOM) | SAX `Locator` line/col | Tokenizer-level only. Quote info is lost and the tree builder rewrites | Heavy, slow-moving releases. Good for a **lint** pass (does this template produce valid HTML5?) but a poor fit as the primary parser. |

**Recommendation:** use **attoparser** as the primary parser.
- It is the same lexer Thymeleaf uses, so migrated templates parse identically.
- It gives exact positions for diagnostics.
- It preserves quoting, which source-preserving codemods need.
- It never rewrites the tree.

Wrap it in Tessera's own small AST builder that tracks the escaping context. If dependency risk is a concern, fork or vendor it; it is small. Optionally run jsoup or nu.validator in a separate verify step on rendered sample output.

## 5. MVP implementation recommendations

**Codegen: use an annotation processor as the core, with a thin Maven/Gradle wrapper later.**
- The processor runs inside javac, so build tools, IDEs and incremental builds just work.
- It has `javax.lang.model` access to the *compile classpath* types: `Elements.getTypeElement(fqcn)`, `Types.isAssignable`, `ElementFilter.methodsIn(getAllMembers(...))`. That lets it resolve `user.name` to `getName()` / record component `name()` and check generics (`List<Item>` to `Item`).
- It emits sources through `Filer.createSourceFile`, and reports errors through `Messager`, mapped to template line/col (attach a `.tess` path:line to the message).
- Trigger: a marker annotation such as `@TesseraTemplates("templates/")` on a package-info or config class. Read templates through `Filer.getResource(StandardLocation.CLASS_PATH / SOURCE_PATH, ...)`. Gotcha: build tools copy `src/main/resources` after compile, so also accept an `-Atessera.templateDir=` processor option. Templates are not "originating elements", so mark the processor `ISOLATING` / aggregating for Gradle incremental builds.
- Caveat: a template param type that is itself being generated in the same round must be deferred to a later round, following the JStachio approach (`RoundEnvironment`, then retry).

**A Maven plugin alone** would need its own classpath type resolution (ASM or ClassGraph over compiled classes plus parsing sources). That is a worse fit, but a plugin is useful later for a `tessera:check` / migrate goal and for dev-mode hot reload. Add a **runtime dev mode** (interpret the same AST, or recompile with the in-memory javac API) only after the MVP.

**Module layout:**
- `tessera-runtime`: tiny, no deps besides safe-html-types. Contains the `Template` interface, `HtmlOutput` (Appendable/byte sink), escapers (HTML text, attr, URL, JS string, CSS string), the `Loop` status record, and the closed helper library (`strings`, `temporals`, `numbers`, `lists`) as static methods.
- `tessera-compiler`: attoparser front end, then an AST, then a type checker (`javax.lang.model`), then a context-escaping pass, then JavaPoet codegen. It is a pure library, so it can be unit-tested without javac.
- `tessera-processor`: the annotation processor that wires the compiler to `Filer`/`Messager` (`META-INF/services/javax.annotation.processing.Processor`).
- `tessera-spring-boot-starter`: a `ViewResolver` / `View` adapter mapping view names to **generated classes only**, so the view name is never evaluated. It also binds `Model` to typed params (fail fast on missing or mistyped attributes), plus `MessageSource` for `#{}`, CSRF hidden-input injection, `@{}` via `ServletUriComponentsBuilder` / context path, and `BindingResult` for `t:field` / `t:errors`.
- Later: `tessera-maven-plugin` (check, migrate) and `tessera-migrate` (th:-to-t: codemod using attoparser's source-preserving events).

**Spec decisions for the MVP:**
- `t:params` header, parsed as Java type syntax resolved with imports.
- Expressions: paths, safe-nav, comparisons, boolean logic, ternary/Elvis, literals, and `|...|` interpolation, plus calls only to the whitelisted helper namespace and zero-arg getters.
- No arbitrary method calls in v1. Consider an opt-in `@TemplateMethod` later.
- `t:utext` accepts only `SafeHtml`.
- `t:href` / `t:src` take `@{}`, `SafeUrl` or `TrustedResourceUrl`.
- Disallow expressions in `on*`/`style`/`<script>` unless the type is the matching safe type, or use JS-string escaping as in `th:inline=javascript`.

## 6. Candidate legacy apps for migration benchmarks

Counts come from the GitHub tree API (2026-10-22) and cover files only; template-like files outside the matched patterns are missed.

| Repo | Engine | Template count | Notes |
|---|---|---|---|
| https://github.com/spring-projects/spring-petclinic | Thymeleaf | 12 `.html` | Canonical baseline. Layout via fragments, forms, `th:each`. |
| https://github.com/spring-petclinic/spring-framework-petclinic | JSP (+ tag files) | 9 `.jsp` (+ `.tag`) | Same app as above: a JSP-to-Tessera vs Thymeleaf-to-Tessera control. |
| https://github.com/mybatis/jpetstore-6 | JSP (Stripes) | 20 `.jsp` | Small classic, non-Spring MVC. |
| https://github.com/Raysmond/SpringBlog | Thymeleaf | 10 | Small, but abandoned and old Spring Boot. |
| https://github.com/spring-io/sagan (archived) | Thymeleaf | ~56 | Former spring.io site. Real-world fragments and layouts. |
| https://github.com/halo-dev/halo | Thymeleaf (themes, server pages) | ~36 in core | Active, large. Themes add many more. |
| https://github.com/yangzongzhuan/RuoYi | Thymeleaf (+ Velocity codegen) | ~147 `.html`, 14 `.vm` | Very popular enterprise admin app. Heavy `th:` plus `shiro:` dialect and inline JS. A hard, realistic case. |
| https://github.com/BroadleafCommerce/BroadleafCommerce | Thymeleaf (custom `blc:` dialect) | ~131 `.html` | Large e-commerce with custom processors. A stress test. |
| https://github.com/apereo/cas | Thymeleaf | ~160 `.html` | Security-sensitive login UI. Layout dialect, i18n heavy. |
| https://github.com/apache/roller | JSP + Velocity | 92 `.jsp`, 83 `.vm` | Legacy multi-engine blog server. |
| https://github.com/sanluan/PublicCMS | FreeMarker | 133 `.ftl` (+ 355 `.html` static/templates) | FreeMarker-to-Tessera at scale. |

Also worth noting:
- `thymeleaf/thymeleafexamples-gtvg` has 8 templates. It is the official Thymeleaf tutorial app and a good syntax-coverage test.
- OpenMRS core has only 19 `.vm` files; the legacy UI JSPs live in a separate module.
- Shopizer's JSP storefront moved out of the main repo.

Suggested ladder: gtvg, then petclinic (both variants), SpringBlog, sagan, CAS, RuoYi, then Broadleaf.
