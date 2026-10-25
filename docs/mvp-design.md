# Tessera MVP design

Oct 22, 2026. What we build first. The long-term design is [tessera-vision.md](tessera-vision.md);
the historical MVP build plan is [issues.md](issues.md). If this doc and the code disagree, fix one
of them in your PR.

## Scope

**In:** `t:params`/`t:import`, `t:text`, `t:utext` (SafeHtml only), `[[...]]`/`[(...)]` inlining,
`t:if`/`t:unless`, `t:switch`/`t:case`, `t:each` with a status variable, `t:with`, `t:block`,
`t:remove`, `t:attr` and generic `t:<attr>` (`t:href`, `t:src`, `t:value`, `t:class`, `t:id`,
`t:data-*`, boolean attributes), `t:classappend`, `t:fragment`/`t:insert`/`t:replace` (typed
params, same-file and cross-file, Fragment-valued args), `t:object`/`t:field`/`t:errors`/`t:errorclass`,
CSRF in same-origin POST forms, `#{key(args)}`, `@{/path/{id}(id=x, q=y)}`, `@TemplateFunction` namespaces,
built-in `strings`/`numbers`/`temporals`/`lists`/`css`/`html` and Spring `auth` namespaces, view-safe types,
contextual escaping, `${...}`/`*{...}` wrappers, the Spring Boot starter, `tessera-migrate`,
and a petclinic port.

**Out:** sandbox, devtools/hot reload, LSP, Kotlin, Gradle, Maven plugin, the layout dialect,
`th:inline` modes and JS natural-template comments (`/*[[...]]*/`), markup selectors other than
fragment names and `~{::tag}`, `__...__` preprocessing (never), `T(...)`/`new`/`@bean` (never),
collection selection/projection, `th:style`/`th:onclick` (build errors by design), byte-array output.

## Modules

One Maven reactor, `groupId dev.tessera`, JUnit Jupiter 6. What the application uses is Java 8:
`tessera-runtime`, the starter, `tessera-compiler` (the annotation processor, which runs inside
the application's javac) and `tessera-migrate` are built with `--release 8` and the generated
template code compiles at `--release 8`, so the app builds with JDK 8+ and runs on Java 8+.
Everything else (benchmarks, examples, most tests) is `--release 21`, so building this repo needs
JDK 21+. The table names the main classes only.

| Module | Packages | Contents | Deps |
|---|---|---|---|
| `tessera-runtime` | `dev.tessera.runtime` | `Template`, `RenderContext`, `Output`, `Escapers`, `SafeHtml`, `TrustedUrl`, `Messages`/`ResourceBundleMessages`, `Links`/`DefaultLinks`, `Forms`, `Field`, `Fragment`, `LoopStatus`, `Conditions`, `Values`, `TemplateRegistry`/`TemplateEntry`, `TemplateModelException`, `StringOutput`/`WriterOutput`, annotations | none |
| | `dev.tessera.runtime.fn` | built-in function namespaces | |
| `tessera-compiler` | `dev.tessera.compiler` | pipeline (`TemplateCompiler`), `Diagnostic`, `SourcePos`, `CompilerEnv` | runtime; shades `tessera-html5-*` |
| | `.model` | template node model, `OutputContext` | |
| | `.html` | `HtmlParser`, `MarkupTokenizer` (its own tokenizer for Tessera's tree), `Contexts` (escaping-context table) | |
| | `.expr` | `Expr` AST, `ExpressionParser` | |
| | `.types` | `ExpressionCompiler`, `ViewSafety`, `FunctionRegistry`, `Scope` | |
| | `.messages`, `.links`, `.forms` | `#{}`, `@{}` and `fields.*` compilation, `MessageBundle` | |
| | `.codegen` | `TemplateGenerator`, `ElementPlan`, `AttributeProcessors`, `TemplateIndex`, `RegistryGenerator`, `Emitted` (the single output path) | |
| | `.codegen.attr` | one processor class per attribute family + hooks | |
| | `.processor` | `TesseraProcessor` (javac entry point), options, discovery | |
| | `.verify` | `Skeleton`: what each generated class writes, recorded by `Emitted` | |
| | `.verify.engine` | `Engine`, `Parser`, `ParserState`: the two spec HTML parsers the build-time verifier runs (security-architecture.md §11.4) | |
| `tessera-html5/current`, `tessera-html5/classic` | `dev.tessera.compiler.internal.html5.{current,classic}` | build only: validator.nu htmlparser source from Maven Central (`validator:26.10.12:sources`, `htmlparser:1.4.16:sources`), pinned by SHA-256, renamed and compiled for Java 8, plus a `TesseraAccess` accessor; shaded into the `tessera-compiler` jar with their MIT notices | none |
| `tessera-spring-boot-starter` | `dev.tessera.spring{,.view,.context,.fn}` | auto-config, plain-Spring config, view resolver, return values, request `RenderContext`, `auth` | runtime; Spring 4.3+ / Boot 1.5+, Servlet 3.0+ from the app (`jakarta.servlet`) |
| `tessera-spring-boot-starter-javax` | same | the starter jar shaded with `jakarta.servlet` relocated to `javax.servlet` | runtime |
| `tessera-spring-tests` | | the starter's test sources run on Boot 3.5, 2.7, 2.0 and 1.5 and on Spring MVC 5.0 and 4.3 without Boot | |
| `tessera-migrate` | `dev.tessera.migrate` | CLI: page record/compare (`Crawler`, `PageNormalizer`, `Parity`) and the Thymeleaf → Tessera converter (`Migrator`) | attoparser, jsoup (bundled into the jar) |
| `tessera-benchmarks` | | JMH, Tessera vs Thymeleaf | Thymeleaf, JMH |
| `examples/petclinic` | `org.springframework.samples.petclinic` | spring-petclinic on Tessera | starter |

The annotation processor lives in `tessera-compiler` (`META-INF/services/javax.annotation.processing.Processor`);
a separate module would only add a jar. The per-issue file ownership in [issues.md](issues.md) is
historical and no longer applies.

## Pipeline

```
templates/**/*.html ─ TemplateDiscovery ─▶ TemplateSource(viewName, path, content)
   ─ HtmlParser ─▶ Document (Element/Text/Comment/DocType with SourcePos, OutputContext on text)
   ─ TemplateIndex ─▶ class names, resolved t:params, fragment signatures (all templates)
   ─ TemplateGenerator ─▶ per element: ElementPlan ◀─ AttributeProcessors (precedence order)
         └ ExpressionParser ─▶ Expr ─ ExpressionCompiler (ViewSafety, FunctionRegistry,
                                       MessageCompiler, LinkCompiler) ─▶ CompiledExpr(code, type)
   ─▶ one Java class per template + TesseraRegistry + META-INF/services entry ─ Filer ─▶ javac
   ─▶ in memory: one Skeleton per template (static text, holes, control flow, fragment calls)
```

Every stage reports to a `DiagnosticSink` and continues; errors print as
`owners/ownerDetails.html:12:7: error: <message>`. If any template has an error, nothing is written:
`TesseraProcessor` is the single gate, checked once after every template has compiled.
Stages are concrete classes; the only interface with several implementations is `AttributeProcessor`.

## Key interfaces (all in code; read the javadoc)

- Runtime: `Template.render(RenderContext, Output)`; `RenderContext(locale, messages, links, forms)`,
  plus an optional CSP nonce written on every `<script>`/`<style>` start tag (#335),
  is the *entire* ambient surface of a template; `Escapers.{text,attr,url,resourceUrl,html,json}`.
- `ExpressionParser.parseExpression/parseEach/parseAssignments/parseParams/parseImports/parseFragmentSignature/parseFragmentRef`.
- `ExpressionCompiler.compile/compileCondition/elementType/resolveType/checkOutputType` → `CompiledExpr(code, type, pos)`.
- `AttributeProcessor.process(CodegenContext, Attribute, ElementPlan)`. A processor adds wrappers
  (loops/ifs/locals), sets/removes attributes, replaces the body or element, or removes/omits the
  tag. It never writes output directly; `TemplateGenerator` emits the plan. Every write in generated
  code (static text, escaper calls, raw HTML, fragment calls, and the conditions and loops around
  them) is built with `Emitted`, which records the template's `Skeleton` in the same call (#366).
  `EmissionGuardTest` fails on output written any other way, and the `Compilation` test harness
  checks every render against its skeleton.
- Hooks called by the generator: `TextInlining.render` (text nodes), `CsrfInjector.apply`
  (`<form>`), `FieldProcessor.option` (`<option>`), `FragmentMethods.generate` (once per template).

## Template syntax

Root element carries the header; it is stripped from the output, as is `xmlns:t`.

```html
<html xmlns:t="https://tessera.dev"
      t:import="com.example.owner.*, com.example.vet.VetView"
      t:params="OwnerView owner, List<PetView> pets">
```

Types resolve against `t:import`, then `java.lang`, `java.util`, `java.time`, `java.math`,
`dev.tessera.runtime`. A template without `t:params` takes no parameters. Inside fragments only the
fragment's declared parameters are visible (no ambient caller context, principle 2).

**Attribute precedence** (fixed; `AttributeProcessors.ORDER`): `fragment` → `insert`/`replace` →
`each` → `if`/`unless`/`switch`/`case` → `object`/`with` → `attr`, generic `t:<attr>`, `field`,
`classappend`, `errorclass` → `text`/`utext`/`errors` → `remove`. `t:text` + `t:utext` on one
element, or `t:insert` + `t:replace`, is a build error. Unknown `t:` names are generic attributes.

**Fragments.** `t:fragment="card(OwnerView owner, Fragment body)"` generates
`fragment_card(OwnerView, Fragment, RenderContext, Output)`. `t:insert`/`t:replace` take
`~{tpl :: name(args)}`, `tpl :: name(args)`, `~{:: name}` (same file), or an expression of type
`Fragment`. Arguments are positional or all named, type-checked against the signature.
`~{::name}` as an *argument* is a `Fragment` lambda over the same-file fragment `name`, or else the
first element whose tag is `name` (petclinic's `~{::body}` layout pattern). A selected element renders in the
scope of the `t:insert`/`t:replace` that passes it. Template names in `~{...}` are paths under the
templates root; `.`, `..`, empty segments and a leading `/` are build errors. When a template renders
as a whole, a parameterized fragment element is skipped; a parameterless one renders in place.

**Forms.** `t:object` must name a variable; that name is the form object name passed to `Forms`
(the Spring model attribute name). `t:field="lastName"` or `"*{lastName}"` checks the path at build
time. Dynamic field names (`*{__${name}__}`) are not supported. For a reusable form-field
fragment, declare a parameter of type `Field`: `t:fragment="input(String label, Field name, String type)"`,
called as `~{fragments/inputField :: input(#{firstName}, 'firstName', 'text')}` inside `t:object`.
The argument must be a string literal (or a `Field` variable passed on); the path is checked
against the caller's `t:object` type at build time and the generated code passes
`new Field(objectName, path, currentValue)`. Inside the fragment, `t:field="name"`,
`t:errors="name"`, `t:errorclass` and `fields.hasErrors(name)`/`fields.errors(name)` accept it, and
outputting it (`t:for="name"`) writes the path. Templates cannot read its value. `Field` is a
fragment parameter type only (not allowed in `t:params`). A checkbox bound through a `Field` is
checked by its value attribute, since the field's static type is not known inside the fragment.

## Expression grammar

Attribute values are entity-decoded before parsing, so `&lt;` works; `lt gt le ge eq ne and or not`
are aliases. A `${...}` wrapper is accepted and ignored.

```ebnf
expr        = ternary ;
ternary     = elvis [ "?" expr [ ":" expr ] ] ;          (* no else: null *)
elvis       = or [ "?:" elvis ] ;
or          = and { ( "||" | "or" ) and } ;
and         = equality { ( "&&" | "and" ) equality } ;
equality    = relational { ( "==" | "!=" | "eq" | "ne" ) relational } ;
relational  = additive [ ( "<" | ">" | "<=" | ">=" | "lt" | "gt" | "le" | "ge" ) additive ] ;
additive    = mult { ( "+" | "-" ) mult } ;                (* + with a String operand concatenates *)
mult        = unary { ( "*" | "/" | "%" ) unary } ;
unary       = ( "!" | "not" | "-" ) unary | postfix ;
postfix     = primary { ( "." | "?." ) ident [ args ] | "[" expr "]" } ;
primary     = literal | ident | "(" expr ")" | "${" expr "}" | "*{" selpath "}"
            | "#" ident "." ident args | message | link | interp | fragment ;
args        = "(" [ expr { "," expr } ] ")" ;
message     = "#{" key [ "(" expr { "," expr } ")" ] "}" ;            (* key is literal *)
link        = "@{" path [ "(" ident "=" expr { "," ident "=" expr } ")" ] "}" ;  (* path literal *)
interp      = "|" { text | "${" expr "}" } "|" ;
fragment    = "~{" [ ( tplname | "this" ) ] "::" ident [ "(" fargs ")" ] "}" ;
literal     = "'" chars "'" | int [ "L" ] | decimal | "true" | "false" | "null" ;
each        = ident [ "," ident ] ":" expr ;
assignments = name "=" expr { "," name "=" expr } ;                   (* t:with, t:attr *)
params      = [ type ident { "," type ident } ] ;
type        = qname [ "<" type { "," type } ">" ] { "[]" } ;
```

Rejected with a specific message: `T(`, `new`, `__`, `@bean`, `=` assignment, `->`, `.![`, `.?[`,
dynamic message keys and link paths, bare calls `fn(x)` (functions are `ns.fn(x)` or `#ns.fn(x)`).

## View-safe rules

Resolution (in `ExpressionCompiler`): an identifier is a scope variable, else a function namespace,
else an error. `a.b`, `a.b()`, `a.getB()` resolve to a record component `b()` or a public no-arg
`getB()`/`isB()` on a **view-safe** type, or a built-in (`size`, `isEmpty`, `length` on String,
`first`/`last`/`getFirst`/`getLast` on List). Any other method call is an error. `ns.fn(x)` and
`#ns.fn(x)` resolve only to `@TemplateFunction` methods. `==` is `Objects.equals` (numeric
promotion for primitives; enum vs string literal compares `name()` and checks the constant exists).

**View-safe:** primitives and boxes, `String`/`CharSequence`, enums, records, `java.time` values,
`BigDecimal`/`BigInteger`, `Optional`/`Iterable`/`Collection`/`List`/`Set`/`Map`/`Map.Entry` and arrays of
view-safe types, `LoopStatus`, `SafeHtml`, `TrustedUrl`, `Fragment` (params only), classes annotated
`@TemplateView`.

**Never view-safe** (even if annotated or registered; checked on params, every intermediate result,
function signatures): `Object` and its methods (`getClass`, `hashCode`, `wait`, ...), `Class`,
`ClassLoader`, `java.lang.reflect.*`, `java.lang.invoke.*`, `Thread`, `ThreadGroup`, `Runtime`,
`Process`, `ProcessBuilder`, `ProcessHandle`, `System`, `SecurityManager`, `Module`, `ModuleLayer`, `java.io.File`, `java.nio.file.*`, `javax.script.*`,
`jakarta.servlet.*`, `javax.servlet.*`, Spring `context`, `beans`, `core.env`, `core.io`,
`web.context`, `expression` packages. The list is in `ViewSafety`. `Object` is allowed only as a
`@TemplateFunction` parameter type (input only).

Function namespaces: classes annotated `@TemplateFunctions("ns")` in the current compilation, plus
`BuiltinNamespaces.CLASSES` if on the classpath. Methods must be `public static @TemplateFunction`.
`fields` is reserved: inside `t:object`, `fields.hasErrors('path'|'*')` and `fields.errors('path')`
compile via `FieldsCompiler` to `ctx.forms()` calls. Iterating `null` yields zero iterations. When a loop's status variable is provably unused, a plain
`Iterable` can stream directly: iterator advancement and rendering the body are interleaved. When
status is used (or fragment selection makes its use uncertain), a plain `Iterable` is consumed into
a list first so `size` and `last` are available. Stateful iterators and body getters/functions can
observe this ordering; templates should not depend on an eager snapshot when status is unused.

## Escaping contexts

`Contexts` decides the context from element and attribute names; the template author cannot. This
table is what ships today. The target profile, with the status of each rule, is in
[context-model.md](context-model.md), and the threat model is in [security-model.md](security-model.md).

| Context | Where | Escaper | Notes |
|---|---|---|---|
| `TEXT` | element text, `t:text`, `[[...]]` | `Escapers.text` | `& < > " '`; `{{` broken with an empty comment, also before a leading and after a trailing `{` (#248) |
| `RCDATA` | text in `<title>`, `<textarea>` (outside `<svg>`/`<math>`, #277) | `Escapers.rcdata` | `& < > " '`; no comment, it would show (#248) |
| — | `t:utext`, `[(...)]` | `Escapers.html(SafeHtml)` | non-`SafeHtml` is a build error |
| `ATTRIBUTE` | any other attribute value | `Escapers.attr` | output always double-quoted |
| `URL` | `href src action formaction cite poster background longdesc usemap ping` (`xlink:href`), `to from by` on SVG `animate`/`set` | `Escapers.url` | http/https/mailto/tel/relative, else `about:invalid#tessera`; `TrustedUrl` passes |
| `URL_LIST` | `values` on SVG `animate`/`set` | `Escapers.urlList` | a `;`-separated list and every item is checked as for `URL`; one bad item replaces the whole list with `about:invalid#tessera` (#284); `TrustedUrl` passes |
| `RESOURCE_URL` | `script[src] script[href] script[xlink:href] iframe[src] frame[src] embed[src] object[data] base[href] link[href]` | `Escapers.resourceUrl` | only a literal-path `@{}` link or `TrustedUrl`; else build error |
| `SCRIPT` | text in `<script>` (no type or JS/module/JSON) | `Escapers.json` | `[[...]]` only; JSON-able types only; also escapes `/`, backtick and `$` so a value cannot leave a JS comment, regex or template literal; `[[...]]` inside a JS string literal (also template-literal text), comment or regex literal is a build error (#281, #315) |
| `REFRESH` | `content` of `<meta http-equiv="refresh">` | `Escapers.refresh` | only `'<seconds>;url=' + @{/path}` with a literal path starting with a single `/` (#237); else build error |
| `STYLE` | `<style>`, `style` attribute | — | build error |
| `EVENT_HANDLER` | `on*` attributes | — | build error; use `data-*` + script |
| `FORBIDDEN` | `srcdoc`, non-JS `<script type>`, odd attribute names | — | build error; `http-equiv` on `<meta>` and `type` on `<script>` cannot be computed |

## Discovery and processor configuration

The processor runs inside the application's normal `javac`. Every app adds:

```xml
<plugin>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths>
      <path><groupId>dev.tessera</groupId><artifactId>tessera-compiler</artifactId><version>${tessera.version}</version></path>
    </annotationProcessorPaths>
    <compilerArgs>
      <arg>-Atessera.templates=${project.basedir}/src/main/resources/templates</arg>  <!-- required -->
      <arg>-Atessera.package=com.example.templates</arg>        <!-- default tessera.generated -->
      <arg>-Atessera.messages=${project.basedir}/src/main/resources/messages</arg>  <!-- optional -->
    </compilerArgs>
  </configuration>
</plugin>
```

An absolute path avoids depending on javac's working directory and on resources being copied
before compilation. Every `**/*.html` under the root is a template; the view name is its relative
path without `.html`. The processor supports `*`, never claims annotations, and compiles the
templates in the first round; if that has errors it retries in each later round and reports the
last round's errors, so getters another processor (Lombok) adds are visible whatever the order. A
successful early compile is repeated in the last round and the build fails if the output changed
(script JSON of a Lombok bean with Tessera listed first). With `tessera.messages` set, `#{}` keys are checked against `<base>.properties`.
**Known limitation:** Maven's incremental compile does not see template edits; run
`mvn clean compile` (or touch a Java file) after changing only templates.

## Generated code

For `owners/ownerDetails.html` with `t:params="OwnerView owner"` and base `com.example.templates`:

```java
package com.example.templates.owners;

public final class OwnerDetailsTemplate implements dev.tessera.runtime.Template {
  private static final String S$0 = "<!DOCTYPE html>\n<html>\n<body>\n<h2>";
  private final com.example.OwnerView owner;
  private OwnerDetailsTemplate(com.example.OwnerView owner) { this.owner = owner; }
  public static OwnerDetailsTemplate of(com.example.OwnerView owner) { return new OwnerDetailsTemplate(owner); }
  @Override public String templateName() { return "owners/ownerDetails"; }
  @Override public void render(dev.tessera.runtime.RenderContext ctx, dev.tessera.runtime.Output out) { render(owner, ctx, out); }
  public static void render(com.example.OwnerView owner, dev.tessera.runtime.RenderContext ctx, dev.tessera.runtime.Output out) {
    out.write(S$0);
    dev.tessera.runtime.Escapers.text(out, owner.getFirstName());
    ...
  }
  public static void fragment_card(..., dev.tessera.runtime.RenderContext ctx, dev.tessera.runtime.Output out) { ... }
}
```

A template the verifier proves a `FLOW` partial (#396; this page isn't one) also gets
`@Override public boolean verifiedFlowPartial() { return true; }`, which `SafeHtml.of` and
`Fragment.of` require.

Plus `com.example.templates.TesseraRegistry implements TemplateRegistry`, whose entries adapt a
`Map<String, ?>` model: `instanceof` check per param (`TemplateModelException` on mismatch; missing
means `null`, except for a primitive param, which needs a value; generic arguments unchecked), then
the static `render`. Registered via `META-INF/services/dev.tessera.runtime.TemplateRegistry`. No
reflection anywhere. A template without `t:params` has no static `render` (it would clash with the
instance method): call `XTemplate.of().render(ctx, out)`. Every param gets a `$` suffix in generated
code (`TemplateGenerator.javaName`), so names like `java`, `class` or `ctx` cannot clash.

## Spring integration

- `TesseraAutoConfiguration`: merged `TemplateRegistry.load(classLoader)`, a `TesseraViewResolver`
  ordered before Thymeleaf's that returns `null` for unknown names (fall-through; never parses a
  name), and a return-value handler so controllers can `return OwnerDetailsTemplate.of(owner)`.
  `redirect:`/`forward:` prefixes are left to Spring. While another engine is configured it
  renders every unknown name; the guarantees cover Tessera-rendered views, and a startup warning
  (log only, matched on the class names of the other engines' view resolvers) says so.
- Supported range: Spring 4.3+ / Boot 1.5+, Servlet 3.0+ (`request.getServletContext()`). One
  source tree (`jakarta.servlet`, `--release 8`) compiled against Boot 4.1; `tessera-spring-boot-starter-javax` relocates it to `javax.servlet`.
  An application uses exactly one of the two. To work on every generation the auto-configuration
  is a plain `@Configuration` with untyped `@ConditionalOnWebApplication` and
  `@ConditionalOnClass(DispatcherServlet)`, the return-value handler is added by a
  `BeanPostProcessor` on `RequestMappingHandlerAdapter` (not a `WebMvcConfigurer`), and the jar
  carries every registration file: `AutoConfiguration.imports` (Boot 2.7+), `spring.factories`
  (`EnableAutoConfiguration` and `AutoConfigureWebMvc` keys, Boot 1.5-2.6), and the Boot 2.7/3
  and Boot 4 `AutoConfigureWebMvc.imports` for `@WebMvcTest`. The shared tests use their own
  `SpringTestContext` JUnit extension (Spring's `SpringExtension` needs 5.0) and build contexts by
  hand instead of `ApplicationContextRunner` (Boot 2.0+). Below Spring 4.3: untested.
- `TesseraConfiguration`: the same beans without Boot (`@Import` it, or declare it in XML), plus a
  `UrlBasedViewResolver` for `redirect:`/`forward:` names only, which Boot otherwise provides.
- `TesseraView` renders the registry entry with the model and `SpringRenderContexts.create(...)`:
  locale (`LocaleResolver`), `MessageSource` messages, `DefaultLinks(contextPath)` +
  `response.encodeURL`, `Forms` over `BindingResult`/`BindStatus`, CSRF token from the request
  attribute `_csrf`, and a per-request CSP nonce (`CspNonce.get(request)`, 128 bits from
  `SecureRandom`; no policy header is sent). Content type `text/html;charset=UTF-8`.
- `auth` namespace (`dev.tessera.spring.fn.Auth`): `isAuthenticated()`, `hasRole(String)`,
  `hasAnyRole(String...)`, `name()`, via `SecurityContextHolder` (optional dependency).

## Migration tool

`tessera-migrate record|compare <base-url> <pages.txt> <dir> [--ignore REGEX]...` is the page-parity
check behind the migration loop in [guide.md](guide.md): `record` fetches each request in
`pages.txt` from the old app (one cookie jar, CSRF token carried over, redirects followed like a
browser) and saves the pages; `compare` fetches them from the new app and reports each page
`same`, `reordered` or `differs`, comparing status, final URL and the page as parsed by jsoup.

`tessera-migrate <in> <out> [--report r.md]` (attoparser, source-preserving): `th:` → `t:`,
`xmlns:th` → `xmlns:t`, drop redundant `${}`, `#lists.isEmpty(x)` → `x.isEmpty()`,
`#strings.`/`#numbers.`/`#temporals.` → namespace calls, `th:fragment="f(a,b)"` → `f(Object a, Object b)`
plus a report item to type it, `"[[${x}]]"`/`'[[${x}]]'` in a JavaScript or JSON `<script>` (not under `th:inline="none"`) →
`[[${x}]]` plus a report item naming the non-string value change (other `[[...]]` inside a JavaScript string, template literal or comment: reported, #282), `__${x}__`, `T(...)`, `th:inline`, `th:style`, `th:on*`, `layout:*`,
`sec:*`, unknown dialects and selection/projection → left in place and reported with file:line.
It inserts a `t:params` stub listing every root variable it saw, with `Object` types and a report
item each. Exit code 0 even with report items (the report is the audit).

## Test strategy

- Unit: runtime escapers (heavy negative tests), `Contexts` table, parser ASTs and errors.
- End to end: `dev.tessera.compiler.testing.Compilation` (test sources of `tessera-compiler`)
  compiles templates and Java sources in memory with the real processor, then renders through the
  generated registry or asserts on `file:line:col` errors. Type-resolution tests use
  `Compilation.builder().inProcessor(pe -> ...)` with `Compilation.env(pe, sink)`.
- Every security rule has a negative test (fails to build, or output is escaped). Issue #17 adds
  an adversarial SSTI/XSS suite in `dev.tessera.compiler.security`.
- `RuntimeArchitectureTest` keeps reflection, script engines and third-party imports out of the runtime.
- Starter: MockMvc tests with hand-written `TemplateRegistry`s, plus an end-to-end smoke test and
  a `@WebMvcTest` slice over templates compiled from `src/test/templates`. The test sources are
  Java 8 and run unchanged on every generation: `tessera-spring-tests` copies them (rewriting
  `jakarta.servlet` for the javax modules and Boot 4's `@WebMvcTest` package for Boot 1.5-3) and
  runs them on Boot 3.5, 2.7, 2.0 and 1.5; its Boot-free `spring50` and `spring43` modules run only
  the plain-Spring tests.
  Petclinic: its own MockMvc suite plus rendered-HTML assertions.
- `mvn -B -ntp verify` must stay green; CI runs exactly that (`.chainguard/ci/maven.yaml`).
