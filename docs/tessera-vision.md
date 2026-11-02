# Tessera: A Safe, Compiled Successor to Thymeleaf

Oct 7, 2026 · Dan Lorenc (WIP vision doc; the MVP scope lives in [mvp-design.md](mvp-design.md))

## Summary

Tessera keeps what people love about Thymeleaf (natural HTML templates, attribute syntax, first-class Spring MVC support) and removes the runtime interpreter that makes it injectable. Templates compile at build time to type-checked Java, expressions are a small closed language with no reflection, and output escaping is contextual and on by default.

The bet: safety alone never displaces an incumbent. Tessera has to be a near drop-in for Thymeleaf syntax, faster, and better in the IDE, with safety as the property you get for free. Server-side template injection (SSTI) should be impossible by construction, not mitigated.

"Tessera" is a working name.

## Why Thymeleaf wins, and where it breaks

| What keeps teams on Thymeleaf | Where it is unsafe or weak |
|---|---|
| Natural templates: files are valid HTML that open in a browser | Expressions are SpEL (or OGNL outside Spring): full languages evaluated at runtime |
| Spring Boot auto-configuration, zero setup | Expression preprocessing (`__${...}__`) re-parses data as code |
| Form binding: `th:field`, validation errors, `th:object` | View names returned from controllers can carry user input into template resolution |
| i18n via `#{...}` message keys | Reflection reaches any public method on any object in the model |
| Fragments and layouts; fragment rendering for HTMX | Errors surface at render time, per request, not at build |
| Spring Security dialect (`sec:authorize`) | Interpretive rendering is slower than compiled alternatives |

The CVE pattern follows from the right-hand column: every sandbox is a deny-list over an expression language that can reach the whole JVM.

## Design principles

Each principle removes a whole class of bugs rather than one instance of it.

1. **Templates are code.** They are compiled at build time and shipped as classes. Production has no template parser, so there is nothing to inject into.
2. **No ambient authority.** A template sees only its declared, typed parameters. It cannot name a class, construct an object, or reach static state.
3. **Allow-list everything callable, and everything parseable.** Property access resolves at compile time against an allow-list of types. Functions exist only if the application registers them. Markup outside a closed profile of HTML is a build error.
4. **No reflection at runtime.** All access is generated Java. This also makes Tessera friendly to GraalVM native images and to the JDK module system.
5. **Contexts are proven, not guessed; escaping cannot be forgotten.** The compiler accepts only templates where it knows exactly, as browsers parse them, whether each expression lands in HTML text, an attribute, a URL, JavaScript or CSS. Raw output requires a safe type, never a flag on a string.
6. **Familiar before novel, inside the profile.** Syntax tracks Thymeleaf wherever that is safe, so migration is mostly mechanical. Parity never overrides 5.
7. **The guarantee ends at the HTML parser.** Client frameworks that re-read the DOM as code are supported only through a deny-by-default framework table. See [security-architecture.md](security-architecture.md).

## Template syntax

Templates stay valid HTML with a `t:` attribute namespace that mirrors `th:`. The one addition is a typed parameter header, which is what makes compile-time checking possible.

```html
<!DOCTYPE html>
<html xmlns:t="https://tessera.dev"
      t:params="UserView user, List<OrderView> orders, OrderForm form">
<body>
  <h1 t:text="#{greeting(user.name)}">Hello, Jane</h1>

  <p t:if="orders.isEmpty">No orders yet.</p>
  <table t:unless="orders.isEmpty">
    <tr t:each="o : orders" t:classappend="o.overdue ? 'late'">
      <td t:text="o.number">A-1001</td>
      <td t:text="fmt.money(o.total)">$40.00</td>
      <td><a t:href="@{/orders/{id}(id=o.id)}">View</a></td>
    </tr>
  </table>

  <form t:action="@{/orders}" t:object="form" method="post">
    <input t:field="quantity">
    <span t:errors="quantity">Must be positive</span>
  </form>

  <div t:insert="~{fragments/footer :: footer(user)}"></div>
</body>
</html>
```

What differs from Thymeleaf:

- Expressions drop the `${...}` wrapper inside `t:` attributes. `${...}` is still accepted for migration and means the same thing.
- `t:params` is required. Each parameter is a Java type the compiler resolves on the classpath.
- Fragment references are checked at build time, including their argument types.
- There is no `__...__` preprocessing, no `T(...)` class access, and no `th:utext` on strings.

## Expression language

A small, closed grammar, not an embedded general-purpose language. Everything compiles to a direct Java call or a constant.

| Construct | Allowed | Example |
|---|---|---|
| Parameter and loop variables | Yes | `user`, `o` |
| Property access | Yes, on view-safe types only | `user.name`, `o.total.amount` |
| Null-safe access, Elvis | Yes | `user?.address?.city ?: 'Unknown'` |
| Literals, comparison, boolean, arithmetic, ternary | Yes | `o.qty > 0 && !o.cancelled` |
| Collection built-ins | Fixed set | `orders.isEmpty`, `orders.size`, `list.first` |
| Registered functions | Only ones the app declares | `fmt.money(o.total)` |
| Message keys | Yes, checked at build | `#{greeting(user.name)}` |
| Link expressions | Yes, path variables encoded | `@{/orders/{id}(id=o.id)}` |
| Arbitrary method calls | No | `user.delete()` |
| Class or static access, `new` | No | `T(java.lang.Runtime)` |
| Assignment, lambdas, reflection | No | |

**View-safe types** are the allow-list behind property access. By default: records, enums, strings, primitives and boxes, `java.time` types, `BigDecimal`, `Optional`, and collections of view-safe types. Other classes opt in with `@TemplateView` or a package-level registration. On a view-safe type only record components and no-argument JavaBean getters are visible.

A set of types is **never** view-safe, even if registered: `Object` methods, `Class`, `ClassLoader`, anything in `java.lang.reflect`, `java.lang.invoke`, `Thread`, `Runtime`, `ProcessBuilder`, and servlet or Spring context objects. The compiler enforces this, so `getClass().getClassLoader()` fails to build instead of failing a sandbox check.

**Registered functions** are static methods annotated `@TemplateFunction`, grouped under a namespace (`fmt`, `auth`, `dates`). They are the only extension point, and the security review surface is exactly that list.

## Compilation pipeline

A Maven/Gradle plugin (backed by a javac annotation processor) turns each template into one Java class before the application compiles.

1. **Parse.** An HTML5 tokenizer reads the template and records the context of every expression: element text, attribute value, URL attribute, `<script>`, `<style>`, or event handler.
2. **Resolve.** Parameter types are resolved against the compile classpath. Each property access is checked against the view-safe allow-list and bound to a concrete accessor.
3. **Type-check.** Conditions must be boolean, `t:each` needs an iterable, fragment calls must match their declared parameters, and `t:utext` must receive a safe type.
4. **Check resources.** Every `#{...}` key must exist in the message bundles, and every fragment reference must resolve.
5. **Generate.** Emit a Java class with a typed render method. Static HTML is pre-encoded to UTF-8 byte arrays. Each expression becomes a direct accessor call wrapped in the escaper for its context.

```java
public final class OrdersPage implements Template {
  private static final byte[] S0 = "<!DOCTYPE html>\n<html>\n<body>\n  <h1>".getBytes(UTF_8);
  public static void render(UserView user, List<OrderView> orders, OrderForm form,
                            RenderContext ctx, Output out) {
    out.write(S0);
    Html.text(out, ctx.messages().get("greeting", user.name()));
    for (OrderView o : orders) {
      out.write(S7);
      Html.text(out, o.number());
      out.write(S8);
      Html.text(out, Fmt.money(o.total()));
      out.write(S9);
      Html.urlAttr(out, ctx.links().build("/orders/{id}", "id", o.id()));
    }
  }
}
```

Applications call it through a generated, typed facade, `Templates.ordersPage(user, orders, form)`. There is no API that accepts template source as a String.

## Contextual autoescaping and safe types

| Output context | Treatment |
|---|---|
| Element text | HTML entity encoding |
| Quoted attribute value | Attribute encoding; unquoted attributes are a build error |
| URL attribute (`href`, `src`, `action`, ...) | Scheme allow-list (http, https, mailto, relative); `javascript:` and `data:` replaced with `about:invalid`; no type skips the check (#668) |
| `<script>` block | Values serialized as JSON, with `</` and line separators escaped |
| Event handler attribute (`onclick`, ...) | Expressions are a build error; use `data-*` attributes plus script |
| `<style>` block or `style` attribute | Allow-listed CSS values only; anything else is a build error |

Raw HTML goes through types, not flags. `t:utext` accepts only `SafeHtml`, which comes only from the escaper or a sanitizer; nothing wraps a string or a rendered template unchecked (#664, #676). Same pattern for `SafeStyle`.

Spring forms get CSRF tokens inserted automatically into every `method="post"` form.

## Spring integration (`tessera-spring-boot-starter`)

- **String view names, safely.** Names are looked up in a build-time registry of compiled templates. Unknown name is a 404, never a template parse. Model attributes are matched to `t:params` by name and type, failing fast on mismatch.
- **Forms.** `t:object`, `t:field`, `t:errors`, `t:errorclass` read from `BindingResult` as Thymeleaf does; field names checked at build time.
- **i18n.** `#{...}` resolves through Spring's `MessageSource`. Missing default-locale keys are build errors.
- **Security.** An `auth` function namespace (`auth.hasRole('ADMIN')`, `auth.isAuthenticated`, `auth.name`) replaces the `sec:` dialect.
- **Fragments for HTMX.** Each `t:fragment` gets its own generated render method.
- **Native images.** No reflection means no reflection config.

## Developer experience

- **Hot reload** in a separate `tessera-devtools` artifact, using the same compiler as production.
- **Errors at the template line**, with expected and actual types; runtime stack traces remapped to template lines.
- **IDE support** via a language server (typed parameters make it tractable).
- **Designer workflow**: templates remain valid HTML with placeholder text.

## Sandbox mode for user-authored templates (`tessera-sandbox`)

Shares syntax and escapers, none of the trust. Data is an immutable tree of values (maps, lists, strings, numbers, booleans, dates), never Java objects. Interpreted over values only, with step/loop/recursion/output/time budgets. Optional GraalVM isolate. The main engine never parses templates at runtime and the sandbox never sees live objects.

## Threat model

| Threat | Status | Why |
|---|---|---|
| SSTI leading to RCE | Eliminated (main engine) | No runtime parser, no String template API, no reflection |
| View-name injection | Eliminated | Names resolve against a build-time registry |
| Sandbox escape via reflection chains | Eliminated | Unsafe types rejected by the compiler |
| XSS from a forgotten escape | Eliminated by default | Contextual escaping is automatic; raw output needs a safe type |
| XSS from an unchecked door (a hand-written `Template`) | Residual | Its own Java code; no API wraps its output as `SafeHtml` or `Fragment` (#676); `SafeHtml.unchecked` and `TrustedUrl` are gone (#664, #668) |
| Unsafe registered functions | Residual, reviewable | The function list (`@TemplateFunction`) is the whole extension surface |
| Information disclosure | Reduced | Only declared parameters and allow-listed accessors visible |
| DoS from template logic | Reduced (main), bounded (sandbox) | |
| Dev reloader in production | Residual, packaging | Separate artifact; refuses production profile |
| Supply chain in the engine | Reduced | Small runtime; compiler runs only at build time |

Out of scope: business logic bugs.

## Migration and adoption

- **`tessera migrate`** rewrites `th:` to `t:`, infers `t:params`, converts common SpEL forms, and lists what it cannot convert with file and line (the report doubles as an audit).
- **Side-by-side running.** The starter's view resolver runs ahead of Thymeleaf's and falls through for unknown names.
- **Benchmarks** against Thymeleaf, JTE and FreeMarker, measured before claimed.
- **Security story** mapping the threat model to OWASP.

## Trade-offs and open questions

- No templates from a database in the main engine (use the sandbox).
- Build-time cost; incremental compilation matters.
- Generic type resolution (`map.values.first.items`) is the hardest part of the compiler; reuse javac's model via the annotation processor.
- Kotlin support: generate Kotlin or only Java?
- JTE already offers compiled typed templates; Tessera differentiates on Thymeleaf compatibility, migration and Spring forms.
- Name is a placeholder.
