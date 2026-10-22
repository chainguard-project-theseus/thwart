# Tessera MVP design

Oct 22, 2026. What we build first. The long-term design is [tessera-vision.md](tessera-vision.md);
the issue breakdown is [issues.md](issues.md). If this doc and the code disagree, fix one of them
in your PR.

## Scope

**In:** `t:params`/`t:import`, `t:text`, `t:utext` (SafeHtml only), `[[...]]`/`[(...)]` inlining,
`t:if`/`t:unless`, `t:switch`/`t:case`, `t:each` with a status variable, `t:with`, `t:block`,
`t:remove`, `t:attr` and generic `t:<attr>` (`t:href`, `t:src`, `t:value`, `t:class`, `t:id`,
`t:data-*`, boolean attributes), `t:classappend`, `t:fragment`/`t:insert`/`t:replace` (typed
params, same-file and cross-file, Fragment-valued args), `t:object`/`t:field`/`t:errors`/`t:errorclass`,
CSRF in POST forms, `#{key(args)}`, `@{/path/{id}(id=x, q=y)}`, `@TemplateFunction` namespaces,
built-in `strings`/`numbers`/`temporals`/`lists` and Spring `auth` namespaces, view-safe types,
contextual escaping, `${...}`/`*{...}` wrappers, the Spring Boot starter, `tessera-migrate`,
and a petclinic port.

**Out:** sandbox, devtools/hot reload, LSP, Kotlin, Gradle, Maven plugin, the layout dialect,
`th:inline` modes and JS natural-template comments (`/*[[...]]*/`), markup selectors other than
fragment names and `~{::tag}`, `__...__` preprocessing (never), `T(...)`/`new`/`@bean` (never),
collection selection/projection, `th:style`/`th:onclick` (build errors by design), byte-array output.

## Modules and ownership

One Maven reactor, `groupId dev.tessera`, `--release 21`, JUnit 5.

| Module | Packages | Contents | Deps |
|---|---|---|---|
| `tessera-runtime` | `dev.tessera.runtime` | `Template`, `RenderContext`, `Output`, `Escapers`, `SafeHtml`, `TrustedUrl`, `Messages`, `Links`/`DefaultLinks`/`UrlEncoding`, `Forms`, `Fragment`, `LoopStatus`, `Conditions`, `Values`, `TemplateRegistry`/`TemplateEntry`, annotations | none |
| | `dev.tessera.runtime.fn` | built-in function namespaces | |
| `tessera-compiler` | `dev.tessera.compiler` | pipeline (`TemplateCompiler`), `Diagnostic`, `SourcePos`, `CompilerEnv` | runtime, attoparser |
| | `.model` | template node model, `OutputContext` | |
| | `.html` | `HtmlParser` (attoparser), `Contexts` (escaping-context table) | |
| | `.expr` | `Expr` AST, `ExpressionParser` | |
| | `.types` | `ExpressionCompiler`, `ViewSafety`, `FunctionRegistry`, `Scope` | |
| | `.messages`, `.links`, `.forms` | `#{}`, `@{}` and `fields.*` compilation, `MessageBundle` | |
| | `.codegen` | `TemplateGenerator`, `ElementPlan`, `AttributeProcessors`, `TemplateIndex`, `RegistryGenerator` | |
| | `.codegen.attr` | one processor class per attribute family + hooks | |
| | `.processor` | `TesseraProcessor` (javac entry point), options, discovery | |
| `tessera-spring-boot-starter` | `dev.tessera.spring{,.view,.context,.fn}` | auto-config, view resolver, return values, request `RenderContext`, `auth` | Spring Boot 4.1 |
| `tessera-migrate` | `dev.tessera.migrate` | Thymeleaf → Tessera CLI | attoparser |
| `examples/petclinic` | `org.springframework.samples.petclinic` | spring-petclinic on Tessera | starter |

The annotation processor lives in `tessera-compiler` (`META-INF/services/javax.annotation.processing.Processor`);
a separate module would only add a jar. Exact per-issue ownership is in [issues.md](issues.md).

## Pipeline

```
templates/**/*.html ─ TemplateDiscovery ─▶ TemplateSource(viewName, path, content)
   ─ HtmlParser ─▶ Document (Element/Text/Comment/DocType with SourcePos, OutputContext on text)
   ─ TemplateIndex ─▶ class names, resolved t:params, fragment signatures (all templates)
   ─ TemplateGenerator ─▶ per element: ElementPlan ◀─ AttributeProcessors (precedence order)
         └ ExpressionParser ─▶ Expr ─ ExpressionCompiler (ViewSafety, FunctionRegistry,
                                       MessageCompiler, LinkCompiler) ─▶ CompiledExpr(code, type)
   ─▶ one Java class per template + TesseraRegistry + META-INF/services entry ─ Filer ─▶ javac
```

Every stage reports to a `DiagnosticSink` and continues; errors print as
`owners/ownerDetails.html:12:7: error: <message>`. If any error exists nothing is written.
Stages are concrete classes; the only interface with several implementations is `AttributeProcessor`.

## Key interfaces (all in code; read the javadoc)

- Runtime: `Template.render(RenderContext, Output)`; `RenderContext(locale, messages, links, forms)`
  is the *entire* ambient surface of a template; `Escapers.{text,attr,url,resourceUrl,html,json}`.
- `ExpressionParser.parseExpression/parseEach/parseAssignments/parseParams/parseImports/parseFragmentSignature/parseFragmentRef`.
- `ExpressionCompiler.compile/compileCondition/elementType/resolveType/checkOutputType` → `CompiledExpr(code, type, pos)`.
- `AttributeProcessor.process(CodegenContext, Attribute, ElementPlan)`. A processor adds wrappers
  (loops/ifs/locals), sets/removes attributes, replaces the body or element, or removes/omits the
  tag. It never writes output directly; `TemplateGenerator` emits the plan.
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
time. Dynamic field names (`*{__${name}__}`) are not supported; inline such fragments.

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
compile via `FieldsCompiler` to `ctx.forms()` calls. Iterating `null` yields zero iterations.

## Escaping contexts

`Contexts` decides the context from element and attribute names; the template author cannot.

| Context | Where | Escaper | Notes |
|---|---|---|---|
| `TEXT` | element text, `<title>`, `<textarea>`, `t:text`, `[[...]]` | `Escapers.text` | `& < > " '` |
| — | `t:utext`, `[(...)]` | `Escapers.html(SafeHtml)` | non-`SafeHtml` is a build error |
| `ATTRIBUTE` | any other attribute value | `Escapers.attr` | output always double-quoted |
| `URL` | `href src action formaction cite poster background longdesc usemap ping` (`xlink:href`), `values to from by` on SVG `animate`/`set` | `Escapers.url` | http/https/mailto/tel/relative, else `about:invalid#tessera`; `TrustedUrl` passes |
| `RESOURCE_URL` | `script[src] iframe[src] frame[src] embed[src] object[data] base[href] link[href]` | `Escapers.resourceUrl` | only a literal-path `@{}` link or `TrustedUrl`; else build error |
| `SCRIPT` | text in `<script>` (no type or JS/module/JSON) | `Escapers.json` | `[[...]]` only; JSON-able types only; also escapes `/`, backtick and `$` so a value cannot leave a JS comment, regex or template literal |
| `STYLE` | `<style>`, `style` attribute | — | build error |
| `EVENT_HANDLER` | `on*` attributes | — | build error; use `data-*` + script |
| `FORBIDDEN` | `srcdoc`, non-JS `<script type>`, odd attribute names | — | build error |

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
path without `.html`. The processor supports `*`, runs once in the first round, never claims
annotations. With `tessera.messages` set, `#{}` keys are checked against `<base>.properties`.
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

Plus `com.example.templates.TesseraRegistry implements TemplateRegistry`, whose entries adapt a
`Map<String, ?>` model: `instanceof` check per param (`TemplateModelException` on mismatch; missing
means `null`, except for a primitive param, which needs a value; generic arguments unchecked), then
the static `render`. Registered via `META-INF/services/dev.tessera.runtime.TemplateRegistry`. No
reflection anywhere. A template without `t:params` has no static `render` (it would clash with the
instance method): call `XTemplate.of().render(ctx, out)`. A param named like a Java keyword or
`ctx`/`out` gets a `$` suffix in generated code (`TemplateGenerator.javaName`).

## Spring integration

- `TesseraAutoConfiguration`: merged `TemplateRegistry.load(classLoader)`, a `TesseraViewResolver`
  ordered before Thymeleaf's that returns `null` for unknown names (fall-through; never parses a
  name), and a return-value handler so controllers can `return OwnerDetailsTemplate.of(owner)`.
  `redirect:`/`forward:` prefixes are left to Spring.
- `TesseraView` renders the registry entry with the model and `SpringRenderContexts.create(...)`:
  locale (`LocaleResolver`), `MessageSource` messages, `DefaultLinks(contextPath)` +
  `response.encodeURL`, `Forms` over `BindingResult`/`BindStatus`, CSRF token from the request
  attribute `_csrf`. Content type `text/html;charset=UTF-8`.
- `auth` namespace (`dev.tessera.spring.fn.Auth`): `isAuthenticated()`, `hasRole(String)`,
  `hasAnyRole(String...)`, `name()`, via `SecurityContextHolder` (optional dependency).

## Migration tool

`tessera-migrate <in> <out> [--report r.md]` (attoparser, source-preserving): `th:` → `t:`,
`xmlns:th` → `xmlns:t`, drop redundant `${}`, `#lists.isEmpty(x)` → `x.isEmpty()`,
`#strings.`/`#numbers.`/`#temporals.` → namespace calls, `th:fragment="f(a,b)"` → `f(Object a, Object b)`
plus a report item to type it, `__${x}__`, `T(...)`, `th:inline`, `th:style`, `th:on*`, `layout:*`,
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
- Starter: MockMvc tests with hand-written `TemplateRegistry`s. Petclinic: its own MockMvc suite
  plus rendered-HTML assertions.
- `mvn -B -ntp verify` must stay green; CI runs exactly that (`.chainguard/ci/maven.yaml`).
