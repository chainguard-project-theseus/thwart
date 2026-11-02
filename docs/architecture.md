# How the compiler, runtime and starter work

For contributors. The template language and every rejection are in [guide.md](guide.md), the
accepted HTML profile and escaping contexts in [context-model.md](context-model.md), and the
security design in [security-architecture.md](security-architecture.md). If this doc and the code
disagree, fix one of them in your PR.

## Modules

One Maven reactor, `groupId dev.tessera`, JUnit Jupiter 6. What the application uses is Java 8:
`tessera-runtime`, the starter, `tessera-compiler` (the annotation processor, which runs inside
the application's javac) and `tessera-migrate` are built with `--release 8` and the generated
template code compiles at `--release 8`, so the app builds with JDK 8+ and runs on Java 8+.
Everything else (benchmarks, examples, most tests) is `--release 21`, so building this repo needs
JDK 21+. The table names the main classes only. The public API of the runtime and the starter is the sorted `public-api.txt`; `PublicApiTest` fails until a change to it is copied there.

| Module | Packages | Contents | Deps |
|---|---|---|---|
| `tessera-runtime` | `dev.tessera.runtime` | `Template`, `RenderContext`, `Output`, `Escapers`, `SafeHtml`/`HtmlSanitizer`, `CssValue`, `Messages`/`ResourceBundleMessages`, `Links`/`DefaultLinks`, `Forms`, `Field`, `Fragment`, `LoopStatus`, `Conditions`, `Values`, `TemplateRegistry`/`TemplateEntry`, `TemplateModelException`, `StringOutput`, annotations | none |
| | `dev.tessera.runtime.fn` | built-in function namespaces | |
| | `dev.tessera.runtime.internal` | `Checks`: URL and number checks shared with the compiler; public but not API | |
| `tessera-compiler` | `dev.tessera.compiler` | pipeline (`TemplateCompiler`), `Diagnostic`, `SourcePos`, `CompilerEnv` | runtime; shades `tessera-html5-*` |
| | `.model` | template node model, `OutputContext` | |
| | `.html` | `HtmlParser`, `MarkupTokenizer` (its own tokenizer for Tessera's tree), `Contexts` (escaping-context table) | |
| | `.expr` | `Expr` AST, `ExpressionParser` | |
| | `.types` | `ExpressionCompiler`, `ViewSafety`, `FunctionRegistry`, `Scope` | |
| | `.messages`, `.links`, `.forms` | `#{}`, `@{}`, `fields.*` and `ids.*` compilation, `MessageBundle` | |
| | `.codegen` | `TemplateGenerator`, `ElementPlan`, `AttributeProcessors`, `TemplateIndex`, `RegistryGenerator`, `Emitted` (the single output path) | |
| | `.codegen.attr` | one processor class per attribute family + hooks | |
| | `.processor` | `TesseraProcessor` (javac entry point), options, discovery | |
| | `.verify` | the build-time context verifier: `ContextVerifier`, its rule table `Rules`, `ContentKinds`, the rule ids `Problem`, and `Skeleton` (what each generated class writes, recorded by `Emitted`) | |
| | `.verify.engine` | `Vnu`, `Parser`, `ParserState`: the spec HTML parser the verifier runs (security-architecture.md §11.4) | |
| `tessera-html5` | `dev.tessera.compiler.internal.html5.current` | build only: validator.nu htmlparser source from Maven Central (`validator:26.10.12:sources`), pinned by SHA-256, renamed and compiled for Java 8, plus a `TesseraAccess` accessor; shaded into the `tessera-compiler` jar with its MIT notice | none |
| `tessera-spring-boot-starter` | `dev.tessera.spring{,.view,.context,.fn}` | auto-config, plain-Spring config, view resolver, request `RenderContext`, `auth` | runtime; Spring 4.3+ / Boot 1.5+, Servlet 3.0+ from the app (`jakarta.servlet`) |
| `tessera-spring-boot-starter-javax` | same | the starter jar shaded with `jakarta.servlet` relocated to `javax.servlet` | runtime |
| `tessera-spring-tests` | | the starter's test sources run on Boot 3.5, 2.7, 2.0 and 1.5 and on Spring MVC 5.0 and 4.3 without Boot | |
| `tessera-migrate` | `dev.tessera.migrate` | CLI: page record/compare (`Crawler`, `PageNormalizer`, `Parity`) and the Thymeleaf → Tessera converter (`Migrator`); usage in [guide.md](guide.md) | attoparser, jsoup (bundled into the jar) |
| `tessera-maven-plugin` | `dev.tessera.maven` | `CheckMojo` (`tessera:check`, phase `process-sources`): compares the templates with the fingerprint the processor wrote and deletes the module's compile output when they differ, so a template-only change recompiles (#425). Java 8, Maven 3.6.3+ | `maven-plugin-api`, `maven-plugin-annotations` (provided) |
| `tessera-benchmarks` | | JMH, Tessera vs Thymeleaf | Thymeleaf, JMH |
| `examples/petclinic` | `org.springframework.samples.petclinic` | spring-petclinic on Tessera | starter |

The annotation processor lives in `tessera-compiler` (`META-INF/services/javax.annotation.processing.Processor`);
a separate module would only add a jar.

## Pipeline

```
templates/**/*.html ─ TemplateDiscovery ─▶ TemplateSource(viewName, path, content)
   ─ HtmlParser ─▶ Document (Element/Text/Comment/DocType with SourcePos, OutputContext on text)
   ─ TemplateIndex ─▶ class names, resolved t:params, fragment signatures (all templates)
   ─ TemplateGenerator ─▶ per element: ElementPlan ◀─ AttributeProcessors (precedence order)
         └ ExpressionParser ─▶ Expr ─ ExpressionCompiler (ViewSafety, FunctionRegistry,
                                       MessageCompiler, LinkCompiler) ─▶ CompiledExpr(code, type)
   ─▶ one Java class per template + TesseraRegistry + META-INF/services entry ─ Filer ─▶ javac
   ─▶ in memory: one Skeleton per template ─ ContextVerifier (spec HTML parser) ─▶ accept or error
```

Every stage reports to a `DiagnosticSink` and continues; errors print as
`owners/ownerDetails.html:12:7: error: <message>`. If any template has an error, nothing is written:
`TesseraProcessor` is the single gate, checked once after every template has compiled.
Stages are concrete classes; the only interface with several implementations is `AttributeProcessor`.

## Key interfaces (all in code; read the javadoc)

- Runtime: `Template.render(RenderContext, Output)`; `RenderContext(locale, messages, links, forms)`,
  plus an optional CSP nonce written on every `<script>`/`<style>` start tag (#335),
  is the *entire* ambient surface of a template. Escapers: `Escapers.{text,attr,url,resourceUrl,
  refresh,html,htmlMessage,linkedHtmlMessage,javaScript,json}`.
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
  (`<form>`), `CsrfInjector.checkSubmitter` (every element), `CsrfInjector.checkBase` (`<base href>`),
  `FieldProcessor.option` (`<option>`), `FragmentMethods.generate` (once per template).

## Expression grammar

What `ExpressionParser` accepts. Attribute values are entity-decoded before parsing, so `&lt;`
works; `lt gt le ge eq ne and or not` are aliases. A `${...}` wrapper is accepted and ignored.

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
postfix     = primary { step } ;
step        = ( "." | "?." ) ident [ args ] | "[" expr "]" ;
primary     = literal | ident | "(" expr ")" | "${" expr "}" | "*{" selpath "}"
            | "#" ident "." ident args | message | link | interp | fragment ;
selpath     = ident { step } ;                             (* a property of the t:object *)
args        = "(" [ expr { "," expr } ] ")" ;
message     = "#{" key [ args ] "}" ;                      (* key is literal *)
link        = "@{" path [ "(" lparam { "," lparam } ")" ] "}" ;  (* path is literal *)
lparam      = ident [ "=" expr ] ;                         (* no value: ?name *)
interp      = "|" { text | "${" expr "}" | "*{" selpath "}" | message } "|" ;
fragment    = "~{" fragref "}" ;
fragref     = [ tplname | "this" ] "::" selector [ "(" [ fargs ] ")" ] ;
selector    = ident { "-" ident } | "t:block" ;
fargs       = expr { "," expr } | ident "=" expr { "," ident "=" expr } ;  (* all positional or all named *)
literal     = "'" chars "'" | int [ "L" ] | decimal | "true" | "false" | "null" ;
each        = ident [ "," ident ] ":" expr ;
assignments = name "=" expr { "," name "=" expr } ;        (* t:with, t:attr *)
params      = [ type ident { "," type ident } ] ;
type        = qname [ "<" type { "," type } ">" ] { "[]" } ;
```

`t:insert`/`t:replace` also take a bare `fragref`. Rejected with a specific message: `T(`, `new`,
`__`, `@bean`, `=` assignment, `->`, `.![`, `.?[`, dynamic message keys, link paths and template
names, bare calls `fn(x)` (functions are `ns.fn(x)` or `#ns.fn(x)`).

## Types and functions

`ExpressionCompiler` resolves an identifier to a scope variable, else a function namespace, else
an error. `a.b`, `a.b()` and `a.getB()` resolve to a record component or a public no-arg getter on
a **view-safe** type, or a built-in (`size`, `isEmpty`, `length`, `first`/`last`); any other call
is an error.

**View-safe** (`ViewSafety`): primitives and boxes, `String`/`CharSequence`, enums, records,
`java.time` values, `BigDecimal`/`BigInteger`, `Optional`/`Iterable`/`Collection`/`List`/`Set`/
`Map`/`Map.Entry` and arrays of view-safe types (a public `java.util` type that implements one of
them is seen as that interface, #135), `LoopStatus`, `SafeHtml`, `CssValue`, and classes annotated
`@TemplateView`. `Fragment` passes only between templates (#676): Java can't build one. The
never-view-safe list (reflection, class loading, processes, files, servlet and Spring context
types) is in `ViewSafety`'s javadoc and wins over everything.

Function namespaces: classes annotated `@TemplateFunctions("ns")` in the current compilation,
plus `BuiltinNamespaces.CLASSES` if on the classpath; methods are `public static @TemplateFunction`.
A namespace in neither is looked up in the function index the processor writes to the class
output (`META-INF/tessera/functions/<ns>`, #118), so test compilation still finds main classes.
Two names are reserved: `fields` (inside `t:object`: `hasErrors`/`errors`/`value` of a path,
`'*'` or `'global'`; `FieldsCompiler`) and `ids` (`seq`/`next`/`prev`; `IdsCompiler`).

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
Maven's incremental compile does not see template edits, so after a successful compile the
processor writes `META-INF/tessera/templates.fingerprint` (each template's path and SHA-256, sorted,
then each message basename's `.properties` files) to the class output, and `tessera-maven-plugin`'s
`check` goal, before `compile`, deletes the module's compile output when the templates or message
files no longer match it (#425, #447).

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
instance method): call `XTemplate.of().render(ctx, out)`. Every param gets a `$` suffix in generated
code (`TemplateGenerator.javaName`), so names like `java`, `class` or `ctx` cannot clash.
A fragment compiles to a `private static` method in its class and a copy in every class that calls
it; one without a `Fragment` parameter also gets a Java-facing `fragment_<name>` (#600).
The registry also has `fragments()` (#131): an entry named `page :: name` per fragment that
`ContextVerifier.servableFragments` accepts as a response body and that has no `Fragment` or
`Field` param, adapting the model to the fragment's declared params the same way and calling
`fragment_<name>`. `find` finds both kinds; `all()` lists only templates.

## Spring integration

- `TesseraAutoConfiguration`: merged `TemplateRegistry.load(classLoader)`, a `TesseraViewResolver`
  ordered before Thymeleaf's that returns `null` for unknown names (fall-through; never parses a
  name: it drops one leading `/` and makes the spaces around a `::` single, then does one exact
  lookup), one view per registry entry.
  `redirect:`/`forward:` prefixes are left to Spring. While another engine is configured it
  renders every unknown name; the guarantees cover Tessera-rendered views, and a startup warning
  (log only, matched on the class names of the other engines' view resolvers) says so.
- Supported range: Spring 4.3+ / Boot 1.5+, Servlet 3.0+ (`request.getServletContext()`). One
  source tree (`jakarta.servlet`, `--release 8`) compiled against Boot 4.1; `tessera-spring-boot-starter-javax` relocates it to `javax.servlet`.
  An application uses exactly one of the two. To work on every generation the auto-configuration
  is a plain `@Configuration` with untyped `@ConditionalOnWebApplication` and
  `@ConditionalOnClass(DispatcherServlet)`, and the jar
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
  `hasAnyRole(String...)`, `hasAuthority(String)`, `hasAnyAuthority(String...)`, `name()`, via
  `SecurityContextHolder` (optional dependency).

## Test strategy

- Unit: runtime escapers (heavy negative tests), `Contexts` table, parser ASTs and errors.
- End to end: `dev.tessera.compiler.testing.Compilation` (test sources of `tessera-compiler`)
  compiles templates and Java sources in memory with the real processor, then renders through the
  generated registry or asserts on `file:line:col` errors. Type-resolution tests use
  `Compilation.builder().inProcessor(pe -> ...)` with `Compilation.env(pe, sink)`.
- Every security rule has a negative test (fails to build, or output is escaped); the adversarial
  SSTI/XSS suite is in `dev.tessera.compiler.security`, the verifier seeds and hostile sweep in
  `.oracle` and `.verify`.
- `RuntimeArchitectureTest` keeps reflection, script engines and third-party imports out of the runtime.
- Starter: MockMvc tests with hand-written `TemplateRegistry`s, plus an end-to-end smoke test and
  a `@WebMvcTest` slice over templates compiled from `src/test/templates`. The test sources are
  Java 8 and run unchanged on every generation: `tessera-spring-tests` copies them (rewriting
  `jakarta.servlet` for the javax modules and Boot 4's `@WebMvcTest` package for Boot 1.5-3) and
  runs them on Boot 3.5, 2.7, 2.0 and 1.5; its Boot-free `spring50` and `spring43` modules run only
  the plain-Spring tests.
  Petclinic: its own MockMvc suite plus rendered-HTML assertions.
- `mvn -B -ntp verify` must stay green; CI runs exactly that (`.chainguard/ci/maven.yaml`).
