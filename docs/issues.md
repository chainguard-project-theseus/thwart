# Tessera MVP issue breakdown

> **Historical.** This is the MVP build plan as it was executed. The file ownership below no
> longer applies; the current module layout is in [mvp-design.md](mvp-design.md) and the
> shared-contract rule is in [AGENTS.md](../AGENTS.md).

Every issue below is a GitHub issue with the same number. Issues within a wave own disjoint files
and can run fully in parallel. Start a wave once the previous wave has merged. Exception: wave-1
issues marked *soft dep* can start right away against the stubs and rebase when the dependency
merges.

Path shorthands: `RT` = `tessera-runtime/src/main/java/dev/tessera/runtime`,
`CC` = `tessera-compiler/src/main/java/dev/tessera/compiler`, `SP` =
`tessera-spring-boot-starter/src/main/java/dev/tessera/spring`. Owning a main-source path also
means owning the matching test path (`src/test/java/...`, same package). Every issue may add new
test files of its own under its packages.

**Shared contracts** are owned by the skeleton (#21): `CC/model/*`, `CC/expr/Expr.java` and the
other `CC/expr` records, `CC/types/{Scope,Variable,CompiledExpr}.java`,
`CC/codegen/{CodegenContext,ElementPlan,AttributeProcessor,AttributeProcessors}.java`, the runtime
API signatures, and `dev.tessera.compiler.testing.Compilation`. Change them only additively and
minimally, and say so in the PR description. Read [AGENTS.md](../AGENTS.md) and
[mvp-design.md](mvp-design.md) first.

| # | Wave | Title | Owns |
|---|---|---|---|
| 1 | 1 | Runtime escapers and safe types | `RT/{Escapers,SafeHtml,TrustedUrl,Output,StringOutput,WriterOutput}.java` |
| 2 | 1 | HTML parser and output-context table | `CC/html/**` |
| 3 | 1 | Expression lexer and parser | `CC/expr/ExpressionParser.java`, new `CC/expr/*` |
| 4 | 1 | Type resolution, view-safe allow-list, expression compiler | `CC/types/**`, `RT/Conditions.java`, new `RT/Values.java` |
| 5 | 1 | Codegen core, annotation processor, registry | `CC/codegen/*.java`, `CC/processor/**`, `CC/{TemplateCompiler,CompilerEnv,GeneratedFile}.java` |
| 6 | 1 | Runtime links, messages and built-in functions | `RT/{DefaultLinks,UrlEncoding,ResourceBundleMessages}.java`, `RT/fn/**` |
| 7 | 1 | Spring view resolver, typed returns, auto-config | `SP/TesseraAutoConfiguration.java`, `SP/view/**`, `META-INF/spring/**` |
| 8 | 1 | Spring request RenderContext and `auth` namespace | `SP/context/**`, `SP/fn/**` |
| 9 | 1 | tessera-migrate: Thymeleaf to Tessera converter | `tessera-migrate/**` |
| 10 | 1 | Import spring-petclinic into examples/petclinic | `examples/petclinic/**` |
| 11 | 2 | Text output: t:text, t:utext, inlining | `CC/codegen/attr/{TextProcessor,UtextProcessor,TextInlining}.java` |
| 12 | 2 | Control flow: if/unless, each, with, switch, remove | `CC/codegen/attr/{IfProcessor,EachProcessor,WithProcessor,SwitchProcessor,RemoveProcessor}.java` |
| 13 | 2 | Attributes: t:attr, t:&lt;attr&gt;, boolean attrs, t:classappend | `CC/codegen/attr/{AttrProcessor,GenericAttrProcessor,ClassAppendProcessor}.java`, new `RT/Attributes.java` |
| 14 | 2 | Fragments: t:fragment, t:insert, t:replace | `CC/codegen/attr/{FragmentProcessor,InsertReplaceProcessor,FragmentMethods}.java` |
| 15 | 2 | Spring forms: t:object, t:field, t:errors, CSRF | `CC/codegen/attr/{ObjectProcessor,FieldProcessor,ErrorsProcessor,ErrorClassProcessor,CsrfInjector}.java`, `CC/forms/**` |
| 16 | 2 | Messages and links at compile time | `CC/messages/**`, `CC/links/**` |
| 17 | 3 | Security test suite (SSTI and XSS) | `tessera-compiler/src/test/java/dev/tessera/compiler/security/**`, `tessera-spring-boot-starter/src/test/java/dev/tessera/spring/security/**` |
| 18 | 3 | Port petclinic templates to Tessera | `examples/petclinic/**` |
| 19 | 3 | Porting guide (docs/guide.md) and diagnostics pass | `docs/guide.md`, diagnostic message text in `CC/**` |
| 20 | 3 | JMH benchmarks vs Thymeleaf | `tessera-benchmarks/**`, its `<module>` line in `pom.xml` |

---

## #1 Runtime escapers and safe types

**Wave 1.** Owns `RT/{Escapers,SafeHtml,TrustedUrl,Output,StringOutput,WriterOutput}.java`.
Depends on: none.

Implement every `Escapers` method exactly as its javadoc says (`text`, `attr`, `url`,
`resourceUrl`, `html`, `json`, `toText`). `url` scheme check: strip leading whitespace and ASCII
control characters, ignore tab/CR/LF inside the scheme (browsers do), compare case-insensitively;
allow `http`, `https`, `mailto`, `tel` and scheme-less values (including `//host` and `#frag`),
replace everything else with `about:invalid#tessera`. `json` must be a complete serializer for
the listed types and must escape `<`, `>`, `&`, `'`, U+2028 and U+2029 as `\uXXXX`.

Acceptance:
- Positive tests for each escaper and type.
- Heavy negative tests: `"><script>`, `' onmouseover=`, `</script>`, `<!--`, `]]>`, `javascript:`,
  `JaVaScRiPt:`, ` javascript:`, `java\tscript:`, `\u0000javascript:`, `vbscript:`, `data:text/html`,
  `&#106;avascript:` (stays inert because `&` is escaped), U+2028/2029, unpaired surrogates, null.
- `json` of nested maps, lists, arrays, enums, `Optional`, `java.time`; unsupported types throw.
- `RuntimeArchitectureTest` still passes.

## #2 HTML parser and output-context table

**Wave 1.** Owns `CC/html/**` (may add files; `CC/model/*` changes additive only). Depends on: none.

Implement `HtmlParser` on attoparser (`org.attoparser`, already a dependency) and `Contexts`
per the escaping table in mvp-design.md. Record positions for elements, attribute names and
attribute values (first character inside the quotes). Decode character references in
`Attribute.value` (numeric, plus `amp lt gt quot apos nbsp`; leave unknown named references as
written) and keep `rawValue` verbatim. Text nodes get their context from `Contexts.text`.
Report: duplicate attributes, unclosed or mismatched non-void elements, malformed markup.

Acceptance:
- Positions are exact, including multi-line attribute values and values after entities.
- Re-serializing a parsed well-formed template reproduces the input (attribute whitespace
  normalized to one space is fine).
- Void elements, self-closing tags, `<script>`/`<style>`/`<textarea>`/`<title>` raw text,
  comments, doctype, CDATA.
- Table tests for `Contexts.attribute` (every row of the design table, case-insensitive,
  `on*`, `style`, `srcdoc`, odd names) and `Contexts.text` (script types).

## #3 Expression lexer and parser

**Wave 1.** Owns `CC/expr/ExpressionParser.java` and any new files in `CC/expr`
(records there are shared: additive changes only). Depends on: none.

Implement every `ExpressionParser` method for the grammar in mvp-design.md, with positions via
`SourcePos.advance`. A `${...}` wrapper is accepted anywhere an expression is expected; `*{...}`
yields `SelectionVar` roots. Errors name what was expected and where.

Acceptance:
- AST tests for every production, precedence and associativity, textual operator aliases,
  literals (escaped quotes in strings), `?.`, `?:`, ternary without else, index, `#ns.fn()`,
  messages with args, links with path variables and query params, `|...|` interpolation,
  fragment refs (`~{a/b :: f(x, y)}`, `a/b :: f(n=x)`, `~{:: f}`, `~{this :: f}`, `~{::body}`).
- `parseEach` (with/without status), `parseAssignments` (attribute names with `-`/`:`),
  `parseParams` with generics and arrays, `parseImports`, `parseFragmentSignature`.
- Each rejected construct has a negative test with a specific message: `T(java.lang.Runtime)`,
  `new java.io.File('x')`, `__${x}__`, `@bean`, `a = b`, `x -> x`, `.![`, `.?[`, `#{${key}}`,
  `@{${url}}`.

## #4 Type resolution, view-safe allow-list, expression compiler

**Wave 1.** Owns `CC/types/**` (shared records additive only), `RT/Conditions.java`, and a new
`RT/Values.java` for runtime helpers the generated code needs (null-safe navigation, numeric
ops). Depends on: none (build `Expr` trees directly in tests; *soft dep* #3 for convenience).

Implement `ExpressionCompiler`, `ViewSafety`, `FunctionRegistry`, `Conditions` per their javadoc
and the "View-safe rules" in mvp-design.md. Route `Expr.Message`/`Expr.Link` to
`MessageCompiler`/`LinkCompiler`, and `fields.*`/`#fields.*` to `FieldsCompiler` (stubs owned by
#16/#15). Generated code must be plain Java: no reflection, no lambdas over `Object`.

Acceptance (all via `Compilation.builder().java(...).inProcessor(...)`):
- Record components, `getX`/`isX` getters (as `a.x`, `a.x()`, `a.getX()`), `@TemplateView`
  classes, built-ins on collections and strings, index on List/array/Map, generics
  (`Map<String, List<Item>>` to `Item`), `?.`, `?:`, ternary typing, string `+`, arithmetic,
  comparisons, `==` (incl. enum vs literal with an error for unknown constants).
- Negative: arbitrary method (`user.delete()`), `getClass()`, `hashCode()`, `wait()`, never-view-safe
  params/results/function signatures (each deny-list entry), non-`@TemplateView` POJO, unknown
  identifier, unknown function, non-static or unannotated function method, duplicate namespace.
- `compileCondition` truthiness, `elementType` for Iterable/array/Map, `resolveType` with
  `t:import`, implicit imports and generics; `Conditions.isTrue` unit tests.

## #5 Codegen core, annotation processor, registry

**Wave 1.** Owns `CC/codegen/*.java` (not `attr/`; shared contracts additive only),
`CC/processor/**`, `CC/{TemplateCompiler,CompilerEnv,GeneratedFile}.java`; maintains the
`testing/Compilation` harness. Depends on: *soft dep* #2 (end-to-end tests), #4 (params).

Implement `TemplateIndex.build`, `ClassNames`, `TemplateGenerator` (implements `CodegenContext`;
emits `ElementPlan`s exactly as documented; calls `TextInlining`, `CsrfInjector`,
`FragmentMethods`; handles `t:params`/`t:import` on the root element only, `<t:block>`, and errors
for misplaced core attributes and exclusive pairs `text`+`utext`, `insert`+`replace`), and
`RegistryGenerator` with the class shape in mvp-design.md. Strip `xmlns:t` and core attributes.

Acceptance:
- A static template renders byte-identical to its source (minus `xmlns:t`), through the
  generated registry.
- `t:params` produce typed `of(...)`/`render(...)` methods; the registry adapter rejects a
  wrong-typed model value with `TemplateModelException` naming param and types, passes null for
  missing ones.
- Class-name rules (keywords, hyphens, digits, nested dirs); services file generated.
- Errors carry `file:line:col`; a template with errors generates nothing.
- Generated sources contain no `java.lang.reflect`, `MethodHandle`, `Class.forName`.

## #6 Runtime links, messages and built-in functions

**Wave 1.** Owns `RT/{DefaultLinks,UrlEncoding,ResourceBundleMessages}.java`, `RT/fn/**`.
Depends on: none.

Implement link expansion per `Links` javadoc, UTF-8 percent-encoding, `ResourceBundleMessages`
(MessageFormat; missing key renders `??key_locale??`), and these `@TemplateFunction`s (static,
null-tolerant like Thymeleaf's utility objects; `Object` only as parameter type):
- `strings`: `isEmpty`, `isBlank`, `defaultString`, `abbreviate`, `toUpperCase`, `toLowerCase`,
  `capitalize`, `trim`, `contains`, `startsWith`, `endsWith`, `substring`, `replace`, `join`.
- `numbers`: `formatInteger(n, minDigits)`, `formatDecimal(n, minIntDigits, decimals)`,
  `sequence(from, to)` (`List<Integer>`, used for pagination).
- `temporals`: `format(TemporalAccessor, pattern)`, `formatIso`.
- `lists`: `size`, `isEmpty`, `contains`.

Acceptance: unit tests incl. path-variable encoding of `/`, `?`, `#`, `..`, spaces and non-ASCII;
query params order and null values; context path; absolute URLs untouched; every function.

## #7 Spring view resolver, typed returns, auto-config

**Wave 1.** Owns `SP/TesseraAutoConfiguration.java`, `SP/view/**`,
`src/main/resources/META-INF/spring/**`. Depends on: none.

`TesseraViewResolver` (ordered before Thymeleaf's, returns null for unknown names so other
resolvers run; never parses, concatenates or normalizes names), `TesseraView`, a return-value
handler for `dev.tessera.runtime.Template`, and the auto-configuration (merged
`TemplateRegistry.load`). Obtain the `RenderContext` through an overridable bean (a functional
interface defaulting to `SpringRenderContexts::create`) so tests don't depend on #8.

Acceptance (MockMvc, hand-written `TemplateRegistry`/`Template` test doubles):
- View name renders with model; `Template` return value renders; content type
  `text/html;charset=UTF-8`.
- Unknown name falls through (404 or other resolver); names like `../x`, `x :: frag`,
  `__${T(java.lang.Runtime)}__`, `x?y` never resolve.
- Model type mismatch fails with the `TemplateModelException` message; `redirect:` works.

## #8 Spring request RenderContext and `auth` namespace

**Wave 1.** Owns `SP/context/**`, `SP/fn/**`. Depends on: *soft dep* #6 (`DefaultLinks`).

Implement `SpringRenderContexts.create`: locale via `RequestContextUtils`, `Messages` over
`MessageSource` (missing key renders `??key_locale??`), links with context path +
`response.encodeURL`, `Forms` over the model's `BindingResult`s (display value via
`BindStatus`, i.e. rejected value after a failed bind; resolved error messages), CSRF token from
request attribute `_csrf` when Spring Security is present. Add `dev.tessera.spring.fn.Auth`
(`@TemplateFunctions("auth")`: `isAuthenticated`, `hasRole`, `hasAnyRole`, `name`) via
`SecurityContextHolder`; must not fail when Spring Security is absent.

Acceptance: tests for each piece with mock request/response and a real `BindingResult`
(rejected value, field and global errors, nested paths), `auth` with and without an
authentication, CSRF present/absent.

## #9 tessera-migrate: Thymeleaf to Tessera converter

**Wave 1.** Owns `tessera-migrate/**`. Depends on: none.

Implement the CLI per "Migration tool" in mvp-design.md: source-preserving rewrite with
attoparser (bytes outside rewritten attributes stay identical), `th:` to `t:`, expression
rewrites, `t:params` stub, and a markdown report with `file:line` per unconverted construct.
Keep the conversion rules in one table-like class so they are easy to extend.

Acceptance: golden-file tests (input, expected output, expected report) covering spring-petclinic
style templates (layout via `th:replace="~{fragments/layout :: layout(~{::body},'owners')}"`,
`th:each`, forms, `#{}`/`@{}`, `__${}__` reported), idempotence (running twice changes nothing),
CLI exit codes and usage.

## #10 Import spring-petclinic into examples/petclinic

**Wave 1.** Owns `examples/petclinic/**` (keep the processor block in `pom.xml`).
Depends on: none.

Copy spring-petclinic (https://github.com/spring-projects/spring-petclinic, Apache-2.0; keep
headers, add a NOTICE) into `examples/petclinic`: Java sources, `messages*.properties`, static
resources, H2 schema/data, `application.properties`. Drop Thymeleaf, MySQL/Postgres profiles,
Testcontainers and Docker files; adapt to Spring Boot 4.1. Put the original templates under
`examples/petclinic/thymeleaf-reference/templates/` (not on the classpath, not under the templates
root) for #18. Keep controllers unchanged (they return view names). Copy upstream tests that do
not render views; leave a list of view-rendering tests for #18 in `thymeleaf-reference/README`.

Acceptance: `mvn -B -ntp verify` passes with the app compiling and its non-view tests green;
`spring-boot:run` starts (pages 404/500 until #18 is fine).

## #11 Text output: t:text, t:utext, inlining

**Wave 2.** Owns `CC/codegen/attr/{TextProcessor,UtextProcessor,TextInlining}.java`.
Depends on: #1–#5.

Acceptance (end-to-end via `Compilation`):
- `t:text` escapes markup; numbers, enums, dates, Optional; `null` renders empty; an `Object`
  or non-view-safe type is a build error.
- `t:utext` with `SafeHtml` renders raw; with `String` or a message it is a build error whose
  message names `SafeHtml`.
- `[[x]]` in text escapes; inside `<script>` it is JSON and `</script>` in data cannot break out;
  inside `<style>` it is a build error. `[(x)]` accepts `SafeHtml` only.

## #12 Control flow: if/unless, each, with, switch, remove

**Wave 2.** Owns `CC/codegen/attr/{IfProcessor,EachProcessor,WithProcessor,SwitchProcessor,RemoveProcessor}.java`.
Depends on: #2–#5.

Acceptance: truthiness as in Thymeleaf; `t:unless`; `t:each` over List, Set, array, Map
(entries with `e.key`/`e.value`), `null` (zero iterations); status variable explicit and implicit
(`itemStat`) with all fields; nested loops; `t:each` + `t:if` on one element (if sees the loop
var); `t:with` chained and shadowing; `t:switch`/`t:case`/`*`; `t:remove` all, body, tag,
all-but-first, none; loop variable not visible after the element (negative test).

## #13 Attributes: t:attr, t:<attr>, boolean attrs, t:classappend

**Wave 2.** Owns `CC/codegen/attr/{AttrProcessor,GenericAttrProcessor,ClassAppendProcessor}.java`
and a new `RT/Attributes.java`. Depends on: #1–#5.

Acceptance: `t:href`/`t:src`/`t:action` use `Escapers.url` (`javascript:` becomes
`about:invalid#tessera`, `TrustedUrl` passes); `t:src` on `<script>` with a String is a build
error, with `@{/js/app.js}` it works; `t:onclick`, `t:style`, `t:srcdoc`, and `t:attr="onclick=..."`
are build errors with a hint; boolean attributes render `name="name"` or nothing; `null` omits the
attribute; `t:class` replaces a static `class`; `t:classappend` appends to static and dynamic
classes; quotes in values are escaped; `t:data-*`.

## #14 Fragments: t:fragment, t:insert, t:replace

**Wave 2.** Owns `CC/codegen/attr/{FragmentProcessor,InsertReplaceProcessor,FragmentMethods}.java`.
Depends on: #2–#5.

Acceptance: same-file and cross-file fragments; named and positional args; argument type
mismatch, unknown template, unknown fragment, wrong arity are build errors at the call site that
name the callee signature; `t:insert` keeps the host tag, `t:replace` drops it; `Fragment` params
with `~{::body}` (the petclinic layout pattern: a page's `<html t:replace="~{fragments/layout ::
layout(~{::body}, 'owners')}">`); parameterized fragment skipped in place; caller variables are
not visible inside a fragment (negative test).

## #15 Spring forms: t:object, t:field, t:errors, CSRF

**Wave 2.** Owns `CC/codegen/attr/{ObjectProcessor,FieldProcessor,ErrorsProcessor,ErrorClassProcessor,CsrfInjector}.java`,
`CC/forms/**`. Depends on: #2–#5.

Acceptance (with a test `Forms` in the `RenderContext`): `t:field` on text/hidden/number inputs
emits `id`, `name`, `value` from `ctx.forms().value(...)`; textarea body; checkbox/radio
`checked`; `<select t:field>` marks the matching option `selected`; nested paths; unknown field and
`t:field` outside `t:object` are build errors naming the type; `t:errors` renders escaped
messages joined by `<br />` or removes the element; `t:errorclass`; `fields.hasErrors('x')` and
`('*')`; CSRF hidden input only in `method="post"` forms and only when a token exists.

## #16 Messages and links at compile time

**Wave 2.** Owns `CC/messages/**`, `CC/links/**`. Depends on: #3–#5, #6.

Acceptance: `#{key}` and `#{key(a, b)}` render through `ctx.messages()`; with
`-Atessera.messages` an unknown key is a build error naming the key and bundle, without it a
warning; `@{/owners/{id}(id=o.id, q=term)}` encodes path vars and query params end to end;
a `{var}` without a parameter, duplicate params and non-http(s) absolute links are build errors.

## #17 Security test suite (SSTI and XSS)

**Wave 3.** Owns `tessera-compiler/src/test/java/dev/tessera/compiler/security/**` and
`tessera-spring-boot-starter/src/test/java/dev/tessera/spring/security/**`. Depends on: waves 1–2.

An adversarial suite that must stay green forever: every Thymeleaf SSTI payload class from
docs/research.md (`T(...)`, `new`, `__${}__`, `#ctx`/`#vars`/`#request`/`#execInfo`, reflection
chains like `x.getClass().forName(...)`, Spring-class gadgets) fails to build; view-name and
fragment-selector injection through the starter never renders or parses anything; XSS vectors in
every output context are escaped or rejected; generated sources contain no reflection; a
randomized test feeds arbitrary strings through every escaper and checks the output cannot leave
its context. File a bug issue for anything that gets through; fix only with a minimal, called-out
change.

## #18 Port petclinic templates to Tessera

**Wave 3.** Owns `examples/petclinic/**`. Depends on: waves 1–2, #10.

Port every template in `thymeleaf-reference/templates` to `src/main/resources/templates`
(start with `tessera-migrate`), make entities/view types view-safe (`@TemplateView` or records),
inline the `__${name}__` field fragments, use `numbers.sequence` for pagination, and restore the
view-rendering MockMvc tests with assertions on rendered HTML. Record every manual step and
deviation in `examples/petclinic/PORTING.md` (input for #19).

Acceptance: `mvn verify` green with all upstream controller tests; each page renders the same
content as upstream Thymeleaf for the sample data; the app runs with `spring-boot:run`.

## #19 Porting guide (docs/guide.md) and diagnostics pass

**Wave 3.** Owns `docs/guide.md` and the wording of diagnostic messages anywhere in `CC/**`.
Depends on: waves 1–2; read #18's `PORTING.md` if merged.

Write a concise, complete guide that lets a coding agent migrate an application from Thymeleaf,
FreeMarker, JSP or Velocity to Tessera using only that document: setup (pom snippet and options),
syntax mapping tables per source engine, view types and `@TemplateView`, functions, forms,
fragments and layouts, Spring integration, a catalogue of every compiler diagnostic with its fix,
known limitations (e.g. incremental builds), and a step-by-step migration checklist. Then make
every diagnostic name the file:line:col, what was found, what was expected, and a mechanical fix
when there is one.

Acceptance: every diagnostic string in `CC/**` appears in the catalogue; examples in the guide
compile (add them as tests under `dev.tessera.compiler.guide`); a reviewer can follow the guide to
port one gtvg template without reading other docs.

## #20 JMH benchmarks vs Thymeleaf

**Wave 3.** Owns `tessera-benchmarks/**` and adding its `<module>` to the root `pom.xml`.
Depends on: waves 1–2.

A JMH module (Thymeleaf as a dependency of this module only) rendering equivalent templates with
Tessera and Thymeleaf: a 100-row owner table with links and conditionals, and a fragment-heavy
layout page. `mvn verify` only compiles it; running is manual
(`java -jar tessera-benchmarks/target/benchmarks.jar`). Commit results with machine details to
`tessera-benchmarks/RESULTS.md`.

Acceptance: both engines produce identical HTML for the benchmark data (checked by a unit test);
benchmarks run; results recorded.
