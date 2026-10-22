# Porting guide: Thymeleaf, FreeMarker, JSP and Velocity to Tessera

This guide is everything you need to move a server-rendered Java application to Tessera. It is
written for a coding agent working from this document alone. Read sections 1 to 4 once. After
that, use the mapping table for your source engine (section 13), the checklist (section 14) and
the diagnostic catalogue (section 16) as you port.

Examples marked in the source of this file with `<!-- compile: ... -->` are compiled and rendered
by `dev.tessera.compiler.guide.GuideExamplesTest`, so they are known to work.

## Contents

1. [What changes](#1-what-changes)
2. [Build setup](#2-build-setup)
3. [Templates and parameters](#3-templates-and-parameters)
4. [View types](#4-view-types)
5. [Expressions](#5-expressions)
6. [Attributes](#6-attributes)
7. [Escaping and output contexts](#7-escaping-and-output-contexts)
8. [Messages and links](#8-messages-and-links)
9. [Functions](#9-functions)
10. [Forms](#10-forms)
11. [Fragments and layouts](#11-fragments-and-layouts)
12. [Spring integration and rendering](#12-spring-integration-and-rendering)
13. [Mapping tables](#13-mapping-tables)
14. [Migration checklist](#14-migration-checklist)
15. [Worked example: a gtvg page](#15-worked-example-a-gtvg-page)
16. [Diagnostic catalogue](#16-diagnostic-catalogue)
17. [Known limitations](#17-known-limitations)

## 1. What changes

Tessera templates are HTML files with `t:` attributes, close to Thymeleaf's `th:` syntax. Unlike
every engine you are leaving, they are compiled to Java classes by an annotation processor during
`javac`. A template error is a build error with `file:line:col`. There is no template parser at
runtime.

Six rules shape every port. When the compiler rejects something, one of them is the reason:

1. **Templates are code.** No template source is ever read or evaluated at runtime. No
   `?eval`, `#evaluate`, `__${...}__` preprocessing or string-built template names.
2. **No ambient authority.** A template sees only its declared, typed `t:params`. There is no
   request, session, application scope, `#ctx`, `pageContext` or bean access. The controller
   puts what the page needs in the model.
3. **Allow-list everything callable.** Templates read record components and `getX()`/`isX()`
   getters of view-safe types, plus a few built-ins. Other methods cannot be called. Functions
   exist only if registered with `@TemplateFunction`.
4. **No reflection at runtime.** Every property access is plain generated Java.
5. **Escaping is contextual and automatic.** The compiler picks the escaper from where the
   output goes. Raw HTML needs a `SafeHtml` value. There is no flag that turns escaping off.
6. **Familiar before novel.** Where it is safe, syntax and behaviour follow Thymeleaf.

## 2. Build setup

Tessera needs Java 21+ and Maven. Add the runtime (or the Spring Boot starter) as a dependency
and the compiler as an annotation processor:

```xml
<dependencies>
  <!-- Spring Boot web apps: the starter (it brings tessera-runtime). -->
  <dependency>
    <groupId>dev.tessera</groupId>
    <artifactId>tessera-spring-boot-starter</artifactId>
    <version>${tessera.version}</version>
  </dependency>
  <!-- Without Spring use tessera-runtime instead. -->
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <configuration>
        <annotationProcessorPaths>
          <path>
            <groupId>dev.tessera</groupId>
            <artifactId>tessera-compiler</artifactId>
            <version>${tessera.version}</version>
          </path>
        </annotationProcessorPaths>
        <compilerArgs combine.children="append">
          <arg>-Atessera.templates=${project.basedir}/src/main/resources/templates</arg>
          <arg>-Atessera.package=com.example.templates</arg>
          <arg>-Atessera.messages=${project.basedir}/src/main/resources/messages</arg>
        </compilerArgs>
      </configuration>
    </plugin>
  </plugins>
</build>
```

| Option | Required | Meaning |
|---|---|---|
| `tessera.templates` | yes | Absolute path of the templates root. Every `**/*.html` below it is a template. |
| `tessera.package` | no | Package of the generated classes (default `tessera.generated`). |
| `tessera.messages` | no | Base path of the default message bundle, without `.properties` (`.../messages` reads `messages.properties`). Every `#{key}` is then checked at build time. Without it you get one warning per template that uses messages. |

Use absolute paths (`${project.basedir}/...`) so the build does not depend on javac's working
directory. The processor runs inside your normal `javac`; there is no Maven plugin to add and
nothing to configure at runtime. If you use `annotationProcessorPaths` for other processors
(Lombok, MapStruct), list Tessera next to them.

Spring Boot apps also need, as usual for `@PathVariable`/`@RequestParam` binding by name,
`-parameters` (the Boot parent POM sets it; add it if you set `compilerArgs` yourself).

## 3. Templates and parameters

A template is an HTML file. Its view name is its path under the templates root without `.html`
(`owners/list.html` is `owners/list`). The first element carries the header:

<!-- compile: template guide/hello.html -->
```html
<!DOCTYPE html>
<html xmlns:t="https://tessera.dev"
      t:import="com.example.guide.*"
      t:params="Customer customer, List<Order> orders, boolean admin">
  <body>
    <h1 t:text="|Hello, ${customer.name}!|">Hello, Ann!</h1>
    <p t:if="orders.isEmpty()">No orders yet.</p>
    <ul t:unless="orders.isEmpty()">
      <li t:each="o, stat : orders" t:classappend="stat.odd ? 'odd'">
        [[${stat.count}]]. <span t:text="o.total">9.99</span>
      </li>
    </ul>
    <a t:if="admin" t:href="@{/admin/customers/{id}(id=customer.id)}">Edit</a>
  </body>
</html>
```

<!-- compile: java -->
```java
package com.example.guide;

public record Customer(long id, String name) {}
```

<!-- compile: java -->
```java
package com.example.guide;

import java.math.BigDecimal;

public record Order(String number, BigDecimal total) {}
```

- `xmlns:t="https://tessera.dev"` and the header attributes are removed from the output.
- `t:params` lists the parameters with Java types: `Type name, Type name`. Generics and arrays
  work. A template without `t:params` takes no parameters. Header attributes are only allowed on
  the first element.
- `t:import` is a comma-separated list of classes (`com.example.Owner`) or packages
  (`com.example.*`). `java.lang`, `java.util`, `java.time`, `java.math` and `dev.tessera.runtime`
  are always imported.
- With Spring, each parameter is read from the model attribute of the same name (section 12).
  A missing attribute is `null` (a primitive parameter must be present).
- The generated class is `<package>.<path>.<Name>Template`: `guide/hello.html` becomes
  `com.example.templates.guide.HelloTemplate`, with `of(Customer, List<Order>, boolean)`.

The file stays a valid HTML page: static attribute values and element text are prototypes that
`t:` attributes replace, as in Thymeleaf. `<t:block>` is an element that renders only its
content.

## 4. View types

Templates may read only **view-safe** types. Everything the template reads, including every
intermediate value of a property chain, is checked at build time.

**View-safe:** primitives and their boxes, `String`/`CharSequence`, enums, records, `java.time`
values, `BigDecimal`, `BigInteger`, `Optional`, `Iterable`, `Collection`, `List`, `Set`, `Map`,
`Map.Entry` and arrays of view-safe types, `LoopStatus`, `SafeHtml`, `TrustedUrl`, and classes
annotated `@TemplateView`. `Fragment` and `Field` are allowed as fragment parameters.

**Never view-safe**, even if annotated or registered: `Object` and its methods (`getClass`,
`hashCode`, `toString`, `wait`, ...), `Class`, `ClassLoader`, `java.lang.reflect.*`,
`java.lang.invoke.*`, `Thread`, `Runtime`, `Process`, `ProcessBuilder`, `System`, `Module`,
`java.io.File`, `java.nio.file.*`, `javax.script.*`, servlet types, and Spring `context`,
`beans`, `core.env`, `core.io`, `web.context` and `expression` packages.

What a template can read on a view-safe type:

- record components: `owner.name` calls `owner.name()`;
- public no-argument getters: `owner.name`, `owner.name()` and `owner.getName()` all call
  `getName()`; `isX()` for booleans;
- built-ins: `size()`/`isEmpty()` on collections and maps, `length` on arrays, `length()` and
  `isEmpty()` on strings, `first`/`last` on lists;
- index access: `list[0]`, `array[i]`, `map['key']`.

Nothing else: no methods with arguments, no setters, no static members.

**Porting the model.** Records need nothing. For JPA entities and other classes, add
`@TemplateView`:

<!-- compile: java -->
```java
package com.example.guide;

import dev.tessera.runtime.TemplateView;
import java.util.List;

@TemplateView
public class Account {
  private final String email;
  private final boolean active;
  private final List<String> roles;

  public Account(String email, boolean active, List<String> roles) {
    this.email = email;
    this.active = active;
    this.roles = roles;
  }

  public String getEmail() { return email; }
  public boolean isActive() { return active; }
  public List<String> getRoles() { return roles; }
  public void deactivate() { } // not reachable from templates: not a getter
}
```

Getters inherited from unannotated superclasses (JPA `@MappedSuperclass`es) resolve through the
annotated subclass. If a page needs computed data, add a getter or a record built by the
controller rather than calling methods from the template. `java.util.Date` and `Calendar` are not
view-safe: convert them to `java.time` types.

## 5. Expressions

The expression language is a safe subset of Thymeleaf's Standard Expression syntax.

| Construct | Example | Notes |
|---|---|---|
| Variable, property | `owner.address.city` | `${...}` around an expression is accepted and ignored: `${owner.name}` = `owner.name`. |
| Safe navigation | `owner?.address?.city` | `null` if any step is null. |
| Index | `items[0]`, `prices['apple']` | List, array, Map. |
| Literals | `'text'`, `'it\'s'`, `42`, `42L`, `1.5`, `true`, `false`, `null` | |
| Arithmetic | `a + b`, `-`, `*`, `/`, `%` | `+` with a String operand concatenates. `div` and `mod` are not supported. |
| Comparison | `==`, `!=`, `<`, `>`, `<=`, `>=`, `eq ne lt gt le ge` | `==` is `Objects.equals`; an enum compared with a string literal compares the constant name (and the constant must exist). |
| Logic | `&&`, `\|\|`, `!`, `and`, `or`, `not` | |
| Conditional | `c ? a : b`, `c ? a`, `value ?: 'default'` | `c ? a` without else is `null`. |
| Literal substitution | `\|Hello, ${name}!\|` | |
| Selection | `*{firstName}` | Inside `t:object` only; a single property path. |
| Message | `#{welcome(user.name)}` | Key must be a literal. |
| Link | `@{/owners/{id}(id=o.id, q=term)}` | Path must be a literal. |
| Function | `strings.abbreviate(s, 20)` or `#strings.abbreviate(s, 20)` | Registered functions only. |
| Fragment | `~{fragments/layout :: layout(~{::body}, 'home')}` | `t:insert`/`t:replace` and fragment arguments only. |

Attribute values are HTML-decoded first, so `&lt;` and `&amp;&amp;` work. Truthiness in
`t:if`/`t:unless` follows Thymeleaf: `null` and `0` are false, a String is false only for
`"false"`, `"off"` and `"no"`, anything else non-null is true. Iterating `null` gives zero
iterations; `null` text renders empty and a `null` attribute value omits the attribute.

**Rejected on purpose** (each has a specific diagnostic, section 16): `T(...)`, `new`,
`@bean`, `__...__` preprocessing, assignment `=`, lambdas `->`, collection selection `.?[...]`
and projection `.![...]`, dynamic message keys and link paths, bare calls `fn(x)`, and any
method call that is not a getter or built-in. Utility objects other than functions (`#ctx`,
`#vars`, `#request`, `#session`, `#execInfo`, `#locale`, `#dates`, `#calendars`, ...) do not
exist.

## 6. Attributes

Attributes on one element run in this fixed order (as in Thymeleaf):
`t:fragment` → `t:insert`/`t:replace` → `t:each` → `t:if`/`t:unless`/`t:switch`/`t:case` →
`t:object`/`t:with` → `t:attr`, `t:<name>`, `t:field`, `t:classappend`, `t:errorclass` →
`t:text`/`t:utext`/`t:errors` → `t:remove`.

| Attribute | Example | Effect |
|---|---|---|
| `t:text` | `t:text="owner.name"` | Replaces the body with the escaped value. |
| `t:utext` | `t:utext="bio"` | Replaces the body with raw HTML; the value must be a `SafeHtml`. |
| `[[...]]`, `[(...)]` | `Hello [[${name}]]` | Inline output in text; `[(...)]` needs `SafeHtml`. |
| `t:if`, `t:unless` | `t:if="owner.pets.isEmpty()"` | Renders the element only if the condition holds (or does not). |
| `t:switch`, `t:case` | `t:switch="status"` with children `t:case="'ACTIVE'"`, `t:case="*"` | First matching case renders; `*` is the default. |
| `t:each` | `t:each="pet, stat : owner.pets"` | Repeats the element. Iterates Iterables, arrays and Maps (`e.key`, `e.value`). Status: `index`, `count`, `size`, `first`, `last`, `even`, `odd`; implicit name `petStat`. |
| `t:with` | `t:with="total=order.total, n=order.lines.size()"` | Local variables for the element; later ones see earlier ones. |
| `t:remove` | `t:remove="all"` | `all`, `body`, `tag`, `all-but-first` or `none` (literal). |
| `t:<name>` | `t:href`, `t:src`, `t:value`, `t:id`, `t:title`, `t:class`, `t:data-id` | Sets that attribute, replacing a static one. `null` omits it. |
| boolean attributes | `t:disabled="!owner.active"` | `checked`, `selected`, `disabled`, `readonly`, `required`, `multiple`, `hidden`, ... render `name="name"` or nothing. |
| `t:attr` | `t:attr="title=o.name, data-id=o.id"` | Several attributes at once. |
| `t:classappend` | `t:classappend="active ? 'on'"` | Appends to the static or computed `class`. |
| `t:object`, `t:field`, `t:errors`, `t:errorclass` | | Forms, section 10. |
| `t:fragment`, `t:insert`, `t:replace` | | Fragments, section 11. |
| `<t:block>` | `<t:block t:each="x : xs">...</t:block>` | Renders only its content. |

`t:text` with `t:utext`, and `t:insert` with `t:replace`, cannot share an element. Any `t:` name
not listed above is a generic attribute (`t:aria-label`, `t:min`, `t:for`). Thymeleaf-only
attributes with no Tessera equivalent: `th:attrappend`, `th:attrprepend`, `th:alt-title`,
`th:lang-xmllang`, `th:inline`, `th:include`, `th:assert`; write the plain attribute or use
`t:attr` instead.

## 7. Escaping and output contexts

The compiler decides the context from the element and attribute name. You cannot override it.

| Context | Where | What happens |
|---|---|---|
| Text | element text, `<title>`, `<textarea>`, `t:text`, `[[...]]` | `& < > " '` escaped. |
| Raw HTML | `t:utext`, `[(...)]` | Only `SafeHtml` compiles. |
| Attribute | any other attribute value | Escaped, always double-quoted. |
| URL | `href`, `src`, `action`, `formaction`, `cite`, `poster`, ... | Only `http`, `https`, `mailto`, `tel` and relative URLs pass; anything else becomes `about:invalid#tessera`. `TrustedUrl` passes as is. |
| Resource URL | `script[src]`, `iframe[src]`, `embed[src]`, `object[data]`, `base[href]`, `link[href]` | Only a literal-path `@{...}` link or a `TrustedUrl` compiles. |
| Script | text in `<script>` (no type, JavaScript, module or JSON) | `[[...]]` writes JSON (strings, numbers, booleans, enums, `java.time`, collections, String-keyed maps). |
| Style | `<style>` text and `style` attribute | Expressions are a build error; use `t:class`/`t:classappend`. |
| Event handler | `on*` attributes | Build error; put data in `data-*` attributes and read them from a script. |
| Forbidden | `srcdoc`, `content` of `<meta http-equiv="refresh">`, non-JS `<script type>` | Build error. `<script type>` and `<meta http-equiv>` cannot be computed. |

To output trusted markup, build a `SafeHtml` in Java: `SafeHtml.escape(text)`,
`SafeHtml.of(template, ctx)` (another template's output) or `SafeHtml.unchecked(html)` for
markup you have sanitized (every call is a review point). `TrustedUrl.unchecked(url)` does the
same for URLs.

<!-- compile: template guide/contexts.html -->
```html
<div xmlns:t="https://tessera.dev" t:import="com.example.guide.Account"
     t:params="Account account, SafeHtml bio, String homepage">
  <a t:href="homepage" t:text="account.email">me@example.com</a>
  <section t:utext="bio">Bio</section>
  <button t:data-email="account.email" class="js-copy">Copy</button>
  <script>
    const roles = [[${account.roles}]];
  </script>
  <script t:src="@{/js/app.js}"></script>
</div>
```

## 8. Messages and links

**Messages.** `#{key}` and `#{key(arg1, arg2)}` render through the application's messages (Spring
`MessageSource` with the starter; `MessageFormat` placeholders `{0}`). The key is a literal; for
a computed choice, use a conditional: `active ? #{status.active} : #{status.inactive}`. A message
is plain text and is escaped like any other value. A missing key renders `??key_locale??` at
runtime; with `-Atessera.messages` it is a build error instead.

**Links.** `@{...}` takes a literal path with `{var}` placeholders and parameters in
parentheses:

| Link | Renders (context path `/app`) |
|---|---|
| `@{/owners}` | `/app/owners` |
| `@{/owners/{id}(id=owner.id)}` | `/app/owners/7` (path variable, percent-encoded) |
| `@{/owners(page=2, q=term)}` | `/app/owners?page=2&q=...` (other parameters become query parameters) |
| `@{{id}/edit(id=owner.id)}` | `7/edit` (page-relative) |
| `@{https://example.com/x}` | unchanged; only `http`/`https` absolute links are allowed |

A `null` path variable fails the render; a `null` query parameter renders its bare name. Links
are built through `RenderContext.links()`, which applies the context path and (with Spring)
`response.encodeURL`.

## 9. Functions

Templates call functions as `namespace.fn(args)` or, Thymeleaf style, `#namespace.fn(args)`.

| Namespace | Functions |
|---|---|
| `strings` | `isEmpty`, `isBlank`, `defaultString(s, def)`, `abbreviate(s, max)`, `toUpperCase`, `toLowerCase`, `capitalize`, `trim`, `contains(s, part)`, `startsWith`, `endsWith`, `substring(s, start[, end])`, `replace(s, before, after)`, `join(iterable, sep)` |
| `numbers` | `formatInteger(n, minDigits)`, `formatDecimal(n, minIntDigits, decimals)`, `sequence(from, to)` (a `List<Integer>`, for pagination) |
| `temporals` | `format(temporal, pattern)`, `formatIso(temporal)` |
| `lists` | `size`, `isEmpty`, `contains(list, x)` (null-tolerant) |
| `auth` (Spring starter) | `isAuthenticated()`, `hasRole('ADMIN')`, `hasAnyRole('A', 'B')`, `name()` |
| `fields` (inside `t:object`) | `hasErrors('path')`, `hasErrors('*')`, `errors('path')`, section 10 |

All built-ins are null-tolerant like Thymeleaf's utility objects. Add your own namespace with a
class in your application:

<!-- compile: java -->
```java
package com.example.guide;

import dev.tessera.runtime.TemplateFunction;
import dev.tessera.runtime.TemplateFunctions;
import java.math.BigDecimal;
import java.math.RoundingMode;

@TemplateFunctions("money")
public final class Money {
  private Money() {}

  @TemplateFunction
  public static String euros(BigDecimal amount) {
    return amount == null ? "" : "€" + amount.setScale(2, RoundingMode.HALF_UP);
  }
}
```

<!-- compile: template guide/functions.html -->
```html
<p xmlns:t="https://tessera.dev" t:import="com.example.guide.Order" t:params="Order order">
  Total: <b t:text="money.euros(order.total)">€9.99</b>
  (<span t:text="#strings.abbreviate(order.number, 8)">ORD-1</span>)
</p>
```

Rules: the class is annotated `@TemplateFunctions("ns")` and public; each method is `public
static`, annotated `@TemplateFunction`, and returns a value. Parameter and return types must not
be never-view-safe (`Object` is allowed as a parameter type). The class must be compiled in the
same `javac` run as the templates. `fields` is reserved.

## 10. Forms

`t:object` names the form object; it must be a variable (a `t:param` or local), and its name is
the Spring model attribute name used for binding and errors.

<!-- compile: template guide/signup.html -->
```html
<form xmlns:t="https://tessera.dev" t:import="com.example.guide.*" t:params="Signup signup, List<Plan> plans"
      t:object="signup" t:action="@{/signup}" method="post">
  <div t:classappend="fields.hasErrors('email') ? 'has-error'">
    <label for="email">Email</label>
    <input type="text" t:field="*{email}" />
    <span class="help" t:errors="*{email}">Invalid email</span>
  </div>
  <select t:field="*{plan}">
    <option t:each="p : plans" t:value="p" t:text="p">BASIC</option>
  </select>
  <input type="checkbox" t:field="*{newsletter}" />
  <textarea t:field="*{address.street}"></textarea>
  <ul t:if="fields.hasErrors('*')">
    <li t:each="e : fields.errors('*')" t:text="e">error</li>
  </ul>
  <button type="submit">Sign up</button>
</form>
```

<!-- compile: java -->
```java
package com.example.guide;

public record Signup(String email, Plan plan, boolean newsletter, Address address) {}
```

<!-- compile: java -->
```java
package com.example.guide;

public enum Plan { BASIC, PRO }
```

<!-- compile: java -->
```java
package com.example.guide;

public record Address(String street) {}
```

- `t:field="email"` or `t:field="*{email}"` works on `<input>`, `<select>` and `<textarea>`. The
  path is checked against the `t:object` type at build time; nested paths work. It writes `id`
  (unless present), `name` and the bound value (after a failed bind, the rejected value).
  Checkboxes and radios get `checked`; options of a `<select t:field>` get `selected`. Password
  inputs never echo the value.
- `t:errors="*{email}"` renders the field's messages (escaped, joined by `<br />`) or removes the
  element when there are none. `t:errors="*"` shows all errors.
- `t:errorclass="error"` on an element with `t:field` appends that class when the field has
  errors.
- `fields.hasErrors('path')`, `fields.hasErrors('*')` and `fields.errors('path')` work inside
  `t:object`. The argument is a string literal.
- A `<form method="post">` gets the CSRF hidden input automatically when Spring Security provides
  a token.
- Dynamic field names (`*{__${name}__}`) are not supported. For a reusable field fragment,
  declare a `Field` parameter (section 11).

## 11. Fragments and layouts

A fragment is an element with `t:fragment`. Parameters are typed, and **a fragment sees only its
parameters**, never the caller's variables.

<!-- compile: template guide/fragments/cards.html -->
```html
<div xmlns:t="https://tessera.dev" t:import="com.example.guide.Customer">
  <section t:fragment="card(String title, Fragment content)" class="card">
    <h3 t:text="title">Title</h3>
    <div t:replace="content">content</div>
  </section>
  <span t:fragment="badge(Customer customer)" t:text="customer.name">Ann</span>
  <footer t:fragment="copyright">© Example</footer>
</div>
```

Calls use `t:insert` (keep the host element, fragment becomes its body) or `t:replace` (the
fragment replaces the host):

<!-- compile: template guide/cards-page.html -->
```html
<main xmlns:t="https://tessera.dev" t:import="com.example.guide.Customer" t:params="Customer customer">
  <div t:replace="~{guide/fragments/cards :: card('Profile', ~{::dl})}">
    <dl><dt>Name</dt><dd t:text="customer.name">Ann</dd></dl>
  </div>
  <p t:insert="~{guide/fragments/cards :: badge(customer=customer)}"></p>
  <div t:replace="guide/fragments/cards :: copyright"></div>
</main>
```

- References: `~{path :: name(args)}`, `path :: name(args)`, `~{:: name}` or `~{this :: name}`
  (same file), or an expression of type `Fragment`. Paths are relative to the templates root,
  without `.html`, `.`/`..` or a leading `/`.
- Arguments are positional or all named, and type-checked against the signature.
- `~{::name}` as an argument is a `Fragment` over the same-file fragment `name` (which, like
  every fragment, sees only its own parameters), or else over the first element whose tag is
  `name` (`~{::dl}`, `~{::body}`). A selected element renders with the variables visible at the
  call site. Put it inside the `t:replace` host, as above, so it does not also render in place.
- A parameterized fragment is skipped when its template renders as a page; a fragment without
  parameters renders in place.
- Markup selectors other than fragment names and tag names (`~{::#id}`, `~{:: .class}`,
  `~{::div[@x]}`) are not supported.

**Layouts.** The layout is a fragment taking `Fragment` parameters; each page replaces itself
with the layout and passes its own `<body>`:

<!-- compile: template guide/fragments/layout.html -->
```html
<!DOCTYPE html>
<html xmlns:t="https://tessera.dev" t:fragment="layout(Fragment content, String title)">
<head>
  <title t:text="title">Example</title>
  <link rel="stylesheet" t:href="@{/css/app.css}" />
</head>
<body>
  <nav><a t:href="@{/}">Home</a></nav>
  <div class="container" t:insert="content">content</div>
</body>
</html>
```

<!-- compile: template guide/home.html -->
```html
<!DOCTYPE html>
<html xmlns:t="https://tessera.dev" t:import="com.example.guide.Customer" t:params="Customer customer"
      t:replace="~{guide/fragments/layout :: layout(~{::body}, 'Home')}">
<body>
  <h1 t:text="|Welcome, ${customer.name}|">Welcome</h1>
</body>
</html>
```

The layout dialect (`layout:decorate`, `layout:fragment`) is not supported; this pattern replaces
it. If the layout needs a value from the page (the active menu entry, a title), pass it as a
parameter.

**Reusable form fields.** A `Field` parameter takes a field path that the compiler checks
against the caller's `t:object`:

<!-- compile: template guide/fragments/fields.html -->
```html
<div xmlns:t="https://tessera.dev">
  <div t:fragment="input(String label, Field name, String type)" class="form-group"
       t:classappend="fields.hasErrors(name) ? 'has-error'">
    <label t:for="name" t:text="label">Label</label>
    <input t:type="type" t:field="name" />
    <span t:errors="name">Error</span>
  </div>
</div>
```

<!-- compile: template guide/profile.html -->
```html
<form xmlns:t="https://tessera.dev" t:import="com.example.guide.Signup" t:params="Signup signup"
      t:object="signup" method="post">
  <div t:replace="~{guide/fragments/fields :: input('Email', 'email', 'text')}"></div>
  <div t:replace="~{guide/fragments/fields :: input('Street', 'address.street', 'text')}"></div>
</form>
```

The argument for a `Field` parameter is a string literal (or a `Field` parameter passed on), and
the call must be inside `t:object`. Inside the fragment, `t:field`, `t:errors`, `t:errorclass`,
`fields.hasErrors(name)` and `fields.errors(name)` accept it, and printing it (`t:for="name"`)
writes the path. Templates cannot read the field's value. `Field` cannot be a `t:params` type.

## 12. Spring integration and rendering

With `tessera-spring-boot-starter` on the classpath:

- Controllers keep returning view names. `return "owners/list";` renders
  `templates/owners/list.html`, reading each `t:params` entry from the model attribute of the
  same name. A model value of the wrong type fails with `TemplateModelException` naming the
  parameter and both types. Names that are not templates fall through to other view resolvers,
  and `redirect:`/`forward:` work as usual.
- Or return the template itself, typed and checked by `javac`:
  `return ListTemplate.of(owners, page);` (any method returning a `dev.tessera.runtime.Template`).
- Messages come from Spring's `MessageSource` (`spring.messages.basename`), the locale from the
  `LocaleResolver`, links get the context path and `response.encodeURL`, `t:field`/`t:errors`
  read the `BindingResult`, and the CSRF token comes from Spring Security.
- Remove `spring-boot-starter-thymeleaf` (or keep it while you migrate page by page: Tessera's
  resolver runs first and falls through for names it does not know).
- `@WebMvcTest` slices load Tessera's auto-configuration, so MockMvc tests render pages.

Without Spring, render a template directly:

<!-- compile: java -->
```java
package com.example.guide;

import com.example.templates.guide.FunctionsTemplate;
import dev.tessera.runtime.RenderContext;
import java.math.BigDecimal;

public final class RenderExample {
  private RenderExample() {}

  public static String render() {
    Order order = new Order("ORD-12345678", new BigDecimal("9.5"));
    return FunctionsTemplate.of(order).renderToString(RenderContext.defaults());
  }
}
```

`RenderContext(locale, messages, links, forms)` is the entire ambient surface: build one with your
own `Messages` (for example `ResourceBundleMessages`) and `DefaultLinks(contextPath)`.
`TemplateRegistry.load(classLoader)` finds every compiled template by view name if you need
name-based lookup (`registry.find("owners/list")`). A template without parameters is rendered
with `XTemplate.of().render(ctx, out)`.

## 13. Mapping tables

### From Thymeleaf

Start with `tessera-migrate` (section 14); it does the mechanical part of this table.

| Thymeleaf | Tessera |
|---|---|
| `xmlns:th="http://www.thymeleaf.org"` | `xmlns:t="https://tessera.dev"` plus `t:params`/`t:import` on the root |
| `th:text`, `th:utext` | `t:text`, `t:utext` (value must be `SafeHtml`; for messages use `t:text`) |
| `th:if`, `th:unless`, `th:switch`, `th:case`, `th:each`, `th:with`, `th:remove`, `th:block` | `t:` with the same name; `th:block` → `<t:block>` |
| `th:href`, `th:src`, `th:action`, `th:value`, `th:id`, `th:title`, `th:class`, ... | `t:href`, ... (same name) |
| `th:attr="a=${x}"` | `t:attr="a=x"` |
| `th:classappend` | `t:classappend` |
| `th:styleappend`, `th:style` | build error: use classes (`t:classappend`) |
| `th:onclick` and other `th:on*` | build error: `t:data-*` attribute + script |
| `th:inline="javascript"` with `/*[[${x}]]*/` | `[[${x}]]` directly in `<script>` (JSON-encoded); no `th:inline` |
| `th:object`, `*{...}`, `th:field`, `th:errors`, `th:errorclass` | same with `t:`; `th:object` must name a variable |
| `th:fragment="f(a, b)"` | `t:fragment="f(Type a, Type b)"`; `Fragment` for markup params |
| `th:insert`, `th:replace` | same; `th:include` → `t:insert` (`t:insert` keeps the host, inserts the whole fragment element) |
| `layout:decorate`, `layout:fragment` | layout fragment with `Fragment` params (section 11) |
| `sec:authorize="hasRole('ADMIN')"` | `t:if="auth.hasRole('ADMIN')"` |
| `${x.y}`, `${x?.y}`, `${x} ?: 'd'` | same (wrapper optional) |
| `#{key(${a})}` | `#{key(a)}` |
| `@{/p/{id}(id=${x})}` | same |
| `@{/p/__${x}__}`, `@{'/p?k=' + ${x}}` | `@{/p/{x}(x=x)}`, `@{/p(k=x)}` |
| `*{__${name}__}` | a `Field` fragment parameter (section 11) |
| `${#lists.isEmpty(x)}`, `${#lists.size(x)}` | `x.isEmpty()`, `x.size()` |
| `${#strings.*}`, `${#numbers.*}`, `${#temporals.*}` | `strings.*`, `numbers.*`, `temporals.*` (same names; `listJoin` → `join`) |
| `${#dates.format(d, p)}`, `${#calendars.format(c, p)}` | convert to `java.time`, then `temporals.format(d, p)` |
| `#fields.hasErrors('x')`, `#fields.hasAnyErrors()`, `#fields.allErrors()` | `fields.hasErrors('x')`, `fields.hasErrors('*')`, `fields.errors('*')` |
| `${x['new']}` (SpEL bean property) | `x.isNew()` |
| `${s.toUpperCase()}` and other method calls | `strings.toUpperCase(s)` or a `@TemplateFunction` |
| `th:class="nav-item"` (literal token) | `class="nav-item"` or `t:class="'nav-item'"` |
| `${a div b}`, `${a mod b}` | `a / b`, `a % b` |
| `${session.x}`, `${param.x}`, `${#request...}`, `${@bean.m()}`, `${T(C).m()}` | put the value in the model and declare it in `t:params` |
| `${list.?[cond]}`, `${list.![prop]}` | compute in the controller or a `@TemplateFunction` |

### From FreeMarker

| FreeMarker | Tessera |
|---|---|
| `${x}` | `[[${x}]]` in text, or `t:text="x"` on an element |
| `${x?no_esc}`, `<#noautoesc>` | `t:utext="x"` with `x` a `SafeHtml` |
| `${x!"default"}`, `x??` | `x ?: 'default'`, `x != null` |
| `<#if c>…<#elseif d>…<#else>…</#if>` | `t:if="c"`, `t:if="!c and d"`, `t:unless="c or d"`; or `t:switch`/`t:case` on a value |
| `<#list xs as x>…<#sep>, </#list>` | `<t:block t:each="x, s : xs">…<t:block t:unless="s.last">, </t:block></t:block>` |
| `x?index`, `x?counter`, `x?has_next`, `x?is_first` | `s.index`, `s.count`, `!s.last`, `s.first` |
| `<#list 1..n as i>` | `t:each="i : numbers.sequence(1, n)"` |
| `<#assign x = e>`, `<#local>` | `t:with="x=e"` on the enclosing element |
| `<#include "header.ftl">` | `t:insert="~{header :: header}"` (declare `t:fragment="header"` in `header.html`) |
| `<#macro card title><div>${title}<#nested></div></#macro>` | `t:fragment="card(String title, Fragment body)"`, `t:replace="body"` where `<#nested>` was |
| `<@lib.card title="T">…</@lib.card>` | `t:replace="~{lib :: card('T', ~{::cardBody})}"` with the body in a same-file `t:fragment="cardBody"` |
| `x?size`, `x?has_content` | `x.size()`, `!lists.isEmpty(x)` or `!strings.isEmpty(x)` |
| `x?upper_case`, `x?lower_case`, `x?cap_first`, `x?trim`, `x?join(", ")` | `strings.toUpperCase(x)`, `strings.toLowerCase(x)`, `strings.capitalize(x)`, `strings.trim(x)`, `strings.join(x, ', ')` |
| `n?string("0.00")`, `d?string("yyyy-MM-dd")` | `numbers.formatDecimal(n, 1, 2)`, `temporals.format(d, 'yyyy-MM-dd')` |
| `<@spring.message "k"/>`, `<@spring.url "/x"/>` | `#{k}`, `@{/x}` |
| `<@spring.formInput "owner.name"/>`, `<@spring.showErrors "<br>"/>` | `<input t:field="*{name}">` inside `t:object="owner"`, `<span t:errors="*{name}">` |
| `?eval`, `?interpret`, `?new`, `?api`, `<#attempt>` | not supported (never); compute in Java |
| hash access `user.name`, `map["k"]` | `user.name`, `map['k']` (the type must be view-safe) |

### From JSP and JSTL

| JSP/JSTL | Tessera |
|---|---|
| `${x}` (EL, **not escaped**), `<c:out value="${x}"/>` | `[[${x}]]` or `t:text="x"` (always escaped) |
| `<c:if test="${c}">` | `t:if="c"` on the content (or on a `<t:block>`) |
| `<c:choose><c:when test="${a}">…<c:otherwise>` | `t:if="a"` / `t:unless="a"`; several branches: `t:switch`/`t:case` on a value |
| `<c:forEach items="${xs}" var="x" varStatus="s">` | `t:each="x, s : xs"` (`s.index`, `s.count`, `s.first`, `s.last`) |
| `<c:forEach begin="1" end="${n}" var="i">` | `t:each="i : numbers.sequence(1, n)"` |
| `<c:set var="x" value="${e}"/>` | `t:with="x=e"` |
| `<c:url value="/o"><c:param name="id" value="${id}"/></c:url>` | `@{/o(id=id)}` |
| `<fmt:message key="k"><fmt:param value="${a}"/></fmt:message>`, `<spring:message code="k"/>` | `#{k(a)}`, `#{k}` |
| `<fmt:formatDate value="${d}" pattern="p"/>`, `<fmt:formatNumber>` | `temporals.format(d, 'p')` (`java.time` only), `numbers.formatDecimal(...)` |
| `<jsp:include page="h.jsp"/>`, `<%@ include file="h.jsp" %>` | `t:insert="~{h :: h}"` |
| tag file with `<jsp:doBody/>` | fragment with a `Fragment` parameter |
| `<form:form modelAttribute="owner">` | `<form t:object="owner" method="post">` |
| `<form:input path="name"/>`, `<form:errors path="name"/>` | `<input t:field="*{name}">`, `<span t:errors="*{name}">` |
| `<form:select path="type" items="${types}"/>` | `<select t:field="*{type}"><option t:each="t : types" t:value="t" t:text="t">` |
| `<sec:authorize access="hasRole('ADMIN')">` | `t:if="auth.hasRole('ADMIN')"` |
| `${empty x}`, `${not empty x}` | `lists.isEmpty(x)` or `strings.isEmpty(x)`, with `!` for not |
| `${fn:length(x)}`, `${fn:toUpperCase(s)}`, `${fn:contains(s, p)}` | `x.size()`, `strings.toUpperCase(s)`, `strings.contains(s, p)` |
| `${param.x}`, `${sessionScope.x}`, `${requestScope.x}`, `${pageContext...}`, `${cookie.x}` | add to the model in the controller and declare in `t:params` |
| scriptlets `<% %>`, `<%= %>`, `<%! %>` | move the code to the controller or a `@TemplateFunction` |

### From Velocity

| Velocity | Tessera |
|---|---|
| `$x`, `${x}`, `$!x` | `[[${x}]]` or `t:text="x"` (`null` renders empty) |
| `$esc.html($x)` | not needed: output is escaped |
| `#if($c)…#elseif($d)…#else…#end` | `t:if`/`t:unless` (or `t:switch`/`t:case`) |
| `#foreach($x in $xs)…#end`, `$foreach.index`, `$foreach.count`, `$foreach.hasNext`, `$velocityCount` | `t:each="x, s : xs"`, `s.index`, `s.count`, `!s.last`, `s.count` |
| `#set($x = $e)` | `t:with="x=e"` |
| `#parse("h.vm")`, `#include("h.vm")` | `t:insert="~{h :: h}"` |
| `#macro(card $title)…$bodyContent…#end`, `#@card("T")…#end` | `t:fragment="card(String title, Fragment body)"`, called with `~{::...}` |
| `$x.method($arg)`, `$x.getClass()` | getters only; other calls via a `@TemplateFunction` |
| `$date.format('yyyy-MM-dd', $d)`, `$number.format(...)` | `temporals.format(d, 'yyyy-MM-dd')`, `numbers.formatDecimal(...)` |
| `#springMessage("k")`, `#springUrl("/x")`, `#springBind("owner.name")` | `#{k}`, `@{/x}`, `t:field="*{name}"` inside `t:object="owner"` |
| `#evaluate`, `#define` | `#evaluate` is not supported (never); `#define` → a fragment |

## 14. Migration checklist

Work one template at a time; the build tells you what is left.

1. **Build setup.** Add the dependency and processor block (section 2). Put templates under
   `src/main/resources/templates` with an `.html` extension (rename `.ftl`, `.jsp`, `.vm`).
2. **Convert syntax.** For Thymeleaf, run the migrator:
   ```sh
   java -jar tessera-migrate/target/tessera-migrate-<version>.jar \
     src/main/resources/thymeleaf-templates src/main/resources/templates --report migrate-report.md
   ```
   It rewrites `th:` to `t:`, drops redundant `${}`, maps utility objects to function namespaces,
   rewrites common string-built links, inserts a `t:params` stub and types fragment parameters as
   `Object`, and lists everything it could not convert in the report (`file:line`). For other
   engines, rewrite by hand with section 13.
3. **Annotate the model.** Add `@TemplateView` to every class the templates read (or turn it into
   a record). Convert `Date`/`Calendar` to `java.time`.
4. **Type the parameters.** Replace each `Object` in `t:params` with what the controller puts in
   the model (look at `model.addAttribute`, `@ModelAttribute` methods and the return value of
   `ModelAndView`). Add `t:import`. Flash attributes and error-page attributes (`status`,
   `message`) are ordinary parameters.
5. **Type the fragments.** Give every fragment parameter its type; markup arguments
   (`~{::body}`) are `Fragment`, dynamic form fields are `Field`.
6. **Remove ambient access.** Anything read from the request, session, beans, static classes or
   the caller's variables inside a fragment becomes a parameter.
7. **Rewrite the rest** with the mapping table: links built from strings, `__${...}__`, method
   calls, `th:style`/`th:on*`, raw output (`SafeHtml`), unsupported markup selectors.
8. **Check messages at build time.** Set `-Atessera.messages`.
9. **Compile** (`mvn clean compile`). Fix each diagnostic with section 16 until the build is
   green.
10. **Test the pages.** MockMvc tests that assert on rendered HTML, including one that puts
    `<script>` in user data and checks that it comes out escaped. Compare each page with the old
    engine's output for the same data; expect attribute order and whitespace differences only.
11. **Remove the old engine** dependency and its configuration.

## 15. Worked example: a gtvg page

The product list from Thymeleaf's *Good Thymes Virtual Grocery* sample, `product/list.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
  <head>
    <title>Good Thymes Virtual Grocery</title>
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <link rel="stylesheet" type="text/css" media="all"
          href="../../../css/gtvg.css" th:href="@{/css/gtvg.css}" />
  </head>
  <body>
    <h1>Product list</h1>
    <table>
      <tr>
        <th>NAME</th>
        <th>PRICE</th>
        <th>IN STOCK</th>
        <th>COMMENTS</th>
      </tr>
      <tr th:each="prod : ${prods}" th:class="${prodStat.odd}? 'odd'">
        <td th:text="${prod.name}">Onions</td>
        <td th:text="${prod.price}">2.41</td>
        <td th:text="${prod.inStock}? #{true} : #{false}">yes</td>
        <td>
          <span th:text="${#lists.size(prod.comments)}">2</span> comment/s
          <a href="comments.html"
             th:href="@{/product/comments(prodId=${prod.id})}"
             th:unless="${#lists.isEmpty(prod.comments)}">view</a>
        </td>
      </tr>
    </table>
    <p>
      <a href="../home.html" th:href="@{/}">Return to home</a>
    </p>
  </body>
</html>
```

**Step 1: syntax.** `th:` becomes `t:`, the namespace changes, `${...}` wrappers can go, and the
two `#lists` calls become built-ins.

**Step 2: model.** gtvg's `Product` and `Comment` are plain classes with getters; annotate them:

<!-- compile: java -->
```java
package com.example.gtvg;

import dev.tessera.runtime.TemplateView;
import java.math.BigDecimal;
import java.util.List;

@TemplateView
public class Product {
  private final Integer id;
  private final String name;
  private final BigDecimal price;
  private final boolean inStock;
  private final List<Comment> comments;

  public Product(Integer id, String name, BigDecimal price, boolean inStock, List<Comment> comments) {
    this.id = id;
    this.name = name;
    this.price = price;
    this.inStock = inStock;
    this.comments = comments;
  }

  public Integer getId() { return id; }
  public String getName() { return name; }
  public BigDecimal getPrice() { return price; }
  public boolean isInStock() { return inStock; }
  public List<Comment> getComments() { return comments; }
}
```

<!-- compile: java -->
```java
package com.example.gtvg;

import dev.tessera.runtime.TemplateView;

@TemplateView
public class Comment {
  private final String text;

  public Comment(String text) { this.text = text; }

  public String getText() { return text; }
}
```

**Step 3: parameters.** The gtvg controller puts `prods` (a `List<Product>`) in the context, so
the header is `t:import="com.example.gtvg.Product" t:params="List<Product> prods"`.

**Result**, `src/main/resources/templates/product/list.html`:

<!-- compile: template product/list.html -->
```html
<!DOCTYPE html>
<html xmlns:t="https://tessera.dev" t:import="com.example.gtvg.Product" t:params="List<Product> prods">
  <head>
    <title>Good Thymes Virtual Grocery</title>
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <link rel="stylesheet" type="text/css" media="all"
          href="../../../css/gtvg.css" t:href="@{/css/gtvg.css}" />
  </head>
  <body>
    <h1>Product list</h1>
    <table>
      <tr>
        <th>NAME</th>
        <th>PRICE</th>
        <th>IN STOCK</th>
        <th>COMMENTS</th>
      </tr>
      <tr t:each="prod : prods" t:class="prodStat.odd ? 'odd'">
        <td t:text="prod.name">Onions</td>
        <td t:text="prod.price">2.41</td>
        <td t:text="prod.inStock ? #{true} : #{false}">yes</td>
        <td>
          <span t:text="prod.comments.size()">2</span> comment/s
          <a href="comments.html"
             t:href="@{/product/comments(prodId=prod.id)}"
             t:unless="prod.comments.isEmpty()">view</a>
        </td>
      </tr>
    </table>
    <p>
      <a href="../home.html" t:href="@{/}">Return to home</a>
    </p>
  </body>
</html>
```

<!-- compile: messages -->
```properties
true=yes
false=no
```

The page compiles to `com.example.templates.product.ListTemplate`. Render it from a controller
(`return "product/list";` with `prods` in the model, or `return ListTemplate.of(prods);`) or
directly with `ListTemplate.of(prods).renderToString(ctx)`. For gtvg's `home.html`, note two more
changes: `th:utext="#{home.welcome(${session.user.name})}"` becomes
`t:text="#{home.welcome(user.name)}"` with `User user` as a parameter (a message is text, and the
session is not reachable), and `${#calendars.format(today,'dd MMMM yyyy')}` becomes
`temporals.format(today, 'dd MMMM yyyy')` with `LocalDate today`.

## 16. Diagnostic catalogue

Every compiler diagnostic, with its fix. Diagnostics print as
`templates/path.html:line:col: error: message`, with the position of the attribute, expression or
element at fault. In the messages below, `<angle brackets>` stand for the parts that vary. A
template with any error generates no code, and other templates still report their errors.

### Build configuration

These come from the annotation processor itself, without a template position.

- `Tessera: the annotation processor needs -Atessera.templates=<absolute templates dir>, e.g. ${project.basedir}/src/main/resources/templates`:
  add the `-Atessera.templates` compiler argument (section 2).
- `Tessera: templates directory does not exist: <path>`: fix the path; use
  `${project.basedir}/...`.
- `Tessera: message bundle does not exist: <file> (-Atessera.messages is the bundle base path without .properties)`:
  point the option at the bundle without the `.properties` suffix.
- `Tessera: internal compiler error: <exception>`: a compiler bug. Report it with the template
  that triggers it.
- `generated class name <class> collides with the one generated for <other template>; rename one of the templates`:
  two paths map to the same class name (for example `a-b.html` and `a_b.html`, or names that differ
  only in case). Rename one file.

### Markup

- `malformed markup: <parser message>`: the HTML could not be parsed at that point; fix the
  markup (unclosed quote, stray `<`).
- `malformed markup: invalid element name '<name>': element names start with a letter and use letters, digits, '-', '_', ':' and '.'`:
  fix the tag name, or escape a literal `<` in text as `&lt;`.
- `malformed markup: invalid attribute name '<name>': attribute names cannot contain <, quotes or =`:
  usually a missing quote or space before the attribute.
- `duplicate attribute '<name>' on <<tag>>: remove one of them`: keep one; names are
  case-insensitive (`t:text` and `T:TEXT` are the same).
- `malformed markup: content of <<tag>> contains '</<tag>' before its end tag: escape it (in a script string, write '<\/<tag>')`:
  a browser would end the element early; split the string.
- `<<tag>/> is not self-closing in HTML: the browser treats everything after it as <tag>; write <<tag> ...></<tag>>`:
  reported for `<script/>` and `<style/>`; a browser ignores the `/` and treats the rest of the
  page as script or CSS. Write the end tag, e.g. `<script src="a.js"></script>`.
- `end tag </<tag>> does not match any open element: remove it or add the matching <<tag>>`:
  common with `<a ... />` followed by `</a>`; write `<a ...></a>`.
- `element <<tag>> is not closed (found </<other>> at <line>:<col>): add </<tag>>` and
  `element <<tag>> is not closed: add </<tag>>`: close the element (Tessera needs well-formed
  nesting for non-void elements).
- `malformed markup: '<!--' inside <script> is not supported: remove it (it is not needed in HTML5)`:
  delete the `<!--`/`-->` wrapper.

### Template structure

- `<t:attr> is only allowed on the root element (the first element of the template)`: move
  `t:params`/`t:import` to the first element.
- `t:<a> and t:<b> cannot be used on the same element; keep one of them`: `t:text` with
  `t:utext`, or `t:insert` with `t:replace`; wrap in a `<t:block>` if you need both.
- `<t:attr> needs a value: write an expression, e.g. <t:attr>="owner.name"`: the attribute is
  empty; give it an expression.
- `an expression cannot be used in attribute <name> (<CONTEXT> context): write a literal value`:
  the attribute's context (style, event handler, forbidden) does not allow expressions in
  inlined text; see section 7.
- `duplicate parameter '<name>': rename or remove one of them`: in `t:params` or a fragment
  signature.
- `parameter '<name>' has type Field, which only fragment parameters may have: declare it in a t:fragment signature`:
  `Field` is for fragment parameters; a page uses `t:object` instead.
- `t:fragment needs a name, e.g. t:fragment="card(OwnerView owner)"`: give the fragment a name.
- `duplicate fragment '<name>' in this template: rename one of them`.

### Expressions (syntax)

- `expected <what> but <found>` (for example `expected ')' but reached the end`,
  `expected '}' to close '${' but reached the end`): add the missing token at the position shown.
- `unexpected '<c>': expected the end of the expression (check for a missing operator or an unbalanced bracket or quote)`:
  two expressions without an operator between them, or an extra `)`.
- `expected an expression but reached the end`, `expected an expression but found '<c>'`,
  `expected an expression but found operator '<op>'`: an operand is missing.
- `expression is nested more than <n> levels deep: simplify it or compute the value in Java`.
- `preprocessing (__...__) is not supported: expressions are never built from strings`: replace
  `__${x}__` with a link parameter (`@{/p/{x}(x=x)}`), a `Field` fragment parameter, or a
  conditional.
- `assignment (=) is not supported; use == to compare`.
- `lambdas (->) are not supported: compute the value in Java and pass it as a parameter`.
- `collection projection (.![...]) is not supported: compute the list in Java and pass it as a parameter`.
- `collection selection (.?[...]) is not supported: filter the list in Java and pass it as a parameter`.
- `expected '.' after '#<ns>' but <found>`, `expected '(' after '#<ns>.<fn>' but <found>`:
  `#ns` must be followed by `.fn(...)`; utility objects that are not function namespaces do not
  exist.
- `bean references (@name) are not supported; pass values in as template parameters`.
- `object creation (new) is not supported: pass the value in as a template parameter`.
- `type references (T(...)) are not supported: call a @TemplateFunction (e.g. numbers.sequence(1, n)) or pass the value as a parameter`.
- `'<fn>(...)' is not a known call; call functions as 'ns.<fn>(...)' or '#ns.<fn>(...)'`: add
  the namespace.
- `unterminated string literal: expected a closing ' (write \' for a quote inside the string)`.
- `number literal out of range: <digits> (int, or long with an L suffix)`.
- `invalid number literal: expected a digit, '.' or 'L' but found '<c>'`.
- `expected a message key after '#{' but <found>`.
- `dynamic message keys are not supported; the key must be a literal such as #{owner.name}`:
  choose between literal keys with a conditional.
- `invalid character '<c>' in message key '<key>': keys use letters, digits, '.', '-' and '_'`.
- `expected a link path after '@{' but <found>`.
- `dynamic link paths are not supported; the path must be a literal such as @{/owners/{id}(id=owner.id)}`:
  move the variable parts into `{placeholders}` or query parameters. A link computed in Java is a
  `String` (URL checked) or `TrustedUrl` value in `t:href`.
- `expected '=' after link parameter '<name>' but <found>`: parameters are `name=value`.
- `link path must not contain whitespace: percent-encode it (%20) or pass the value as a parameter, e.g. @{/search(q=term)}`.
- `invalid path variable '{<name>}': write a name such as {id}`.
- `unterminated literal substitution: expected a closing '|' (e.g. |Hello, ${name}!|)`.
- `dynamic template names are not supported; the template must be a literal such as ~{fragments/layout :: main}`:
  pick between literal references with `t:if`, or pass a `Fragment`.
- `expected '::' in fragment reference (e.g. ~{template :: name}) but <found>`.
- `invalid character '<c>' in template name '<name>': names use letters, digits, '/', '-', '_' and '.'`.
- `expected a fragment name after '::' (markup selectors are not supported) but <found>`: select
  by fragment or tag name only.
- `fragment arguments must be all named or all positional, e.g. f(a, b) or f(x=a, y=b)`.
- `expected an attribute or variable name but <found>`: in `t:with`/`t:attr` assignments.
- `wildcard types are not supported: write the element type, e.g. List<Owner>`.
- `expected a parameter name after type '<type>' but <found> (parameters are declared as 'Type name', e.g. 'OwnerView owner')`:
  migrated stubs such as `f(a, b)` need types: `f(String a, Fragment b)`.

### Names and types

- `unknown identifier '<name>': a template sees only its t:params and local variables`: declare
  it in `t:params` (and add it to the model), or in a fragment's signature. Inside a fragment the
  caller's variables are not visible: pass them as arguments.
- `unknown identifier or function namespace '<name>': declare it in t:params, or register a class annotated @TemplateFunctions("<name>")`.
- `'<ns>' is a function namespace, not a value: call a function, e.g. <ns>.fn(...)`.
- `'<ns>.<fn>' is a function: call it as <ns>.<fn>(...)`.
- `unknown type '<name>': add it to t:import or use its qualified name`.
- `unknown type '<name>': check the package name and that the class is on the compile classpath`
  and `unknown type '<name>' in t:import: check the package name and that the class is on the compile classpath`.
- `type '<name>' is ambiguous: <a> and <b>; import one explicitly`: two wildcard imports
  provide it; import the class by name.
- `<type> cannot have type arguments`, `<type> needs <n> type argument(s), e.g. <type><String>`,
  `<type> takes <n> type argument(s), got <m>`: fix the generics.
- `type arguments must be reference types: use <Boxed>, not <primitive>`: `List<Integer>`, not
  `List<int>`.
- `parameter type <type> is not allowed: <reason>`: a never-view-safe type (section 4). Pass the
  data the page needs instead (a record or view type).
- `parameter type <type> is not view-safe: use a record, an enum, a JDK value type, a collection of those, or a class annotated @TemplateView`:
  annotate the class `@TemplateView` or pass a record.
- Reasons inserted into the messages above and below: `java.lang.Object is never view-safe: declare a specific type`,
  `java.lang.Object (unbounded ?) is never view-safe` (use a concrete type argument),
  `<type> is never view-safe`, `<type> is never view-safe (it is a <denied type>)`.

### Properties, calls and operators

- `'<name>' is not a property of <type>: templates can read record components, public getX()/isX() getters and built-ins (size, isEmpty, length, first, last)`:
  check the spelling; add a getter if the data is missing.
- `method call '<name>(...)' is not allowed: templates can read record components, getX()/isX() getters and built-ins, and call @TemplateFunction functions`:
  replace the call with a function (`strings.toUpperCase(s)`) or a getter.
- `'<name>' is a java.lang.Object method and can never be called from a template`.
- `cannot read '<name>': <type> is not view-safe (make it a record or annotate it @TemplateView)`.
- `cannot read '<name>': <reason>` and `cannot read '<name>' of <type>: it returns <type>, and <reason>`:
  the property leads to a never-view-safe type.
- `cannot read '<name>' of null: the expression is the literal null`.
- `cannot read '<name>' of a <primitive> value: primitives have no properties`.
- `cannot read '<name>' of raw type <type>: declare its type arguments`, `cannot iterate over raw type <type>: declare its type arguments`,
  `cannot index raw type <type>: declare its type arguments`: use `List<Owner>`, not `List`.
- `cannot iterate over <type>: t:each needs an Iterable, an array or a Map`, `cannot iterate over <type>: <reason>`.
- `array index must be an int, got <type>`, `list index must be an int, got <type>`,
  `map key must be a <type>, got <type>`.
- `cannot index <type>: [...] works on a List, an array or a Map`, `cannot index <type>: <reason>`.
- `cannot compare <a> with <b>: == on primitives needs two numbers or two booleans`,
  `cannot compare <a> and <b> with <op>`: compare values of compatible types.
- `'<value>' is not a constant of enum <type> (constants: <list>)`: fix the literal in the
  comparison or `t:case`.
- `unary - needs a number, got <type>`, `operator <op> needs numbers (or a String for +), got <a> and <b>`.
- `the branches of ?: have incompatible types <a> and <b>: make both branches the same type`.
- `the operands of ?: have incompatible types <a> and <b>: make the default the same type as the value`.
- `cannot output void: the expression has no value`.
- `cannot output a value of type <type>: it is not view-safe`, `cannot output a value of type <type>: <reason>`:
  output a property of it instead, or make the type view-safe.
- `a fragment expression ~{...} is only allowed in t:insert/t:replace or as a fragment argument`.
- `unsupported literal <value>`: a compiler bug; report it.

### Functions

- `unknown function namespace '<ns>': register a class annotated @TemplateFunctions("<ns>") (built-ins: strings, numbers, temporals, lists; auth with the Spring starter)`.
- `unknown function <ns>.<fn>: only public static @TemplateFunction methods of <class> can be called`.
- `no single applicable <ns>.<fn> for arguments (<types>): check the argument types against the @TemplateFunction overloads of <class>`.
- `<ns>.<fn> returns <type>: <reason>`.
- `invalid function namespace '<ns>' on <class>: must be a Java identifier`.
- `function namespace 'fields' on <class> is reserved for form errors`: choose another name.
- `duplicate function namespace '<ns>': declared by <class> and <class>`.
- `@TemplateFunctions class <class> must be public`.
- `@TemplateFunction <method> must be public static`, `@TemplateFunction <method> must return a value`.
- `@TemplateFunction <method> returns <type>: <reason>`, `@TemplateFunction <method> parameter <name> has type <type>: <reason>`:
  never-view-safe types cannot cross into templates; `Object` is allowed only as a parameter type.

### Text output and escaping

- `<t:text> is not allowed inside <style>: CSS cannot be escaped safely; compute a class name instead`
  (also for `[[...]]`).
- `<form> is not allowed inside a <script> whose type is not JavaScript or JSON (or is set by t:type)`:
  use a JavaScript or JSON script, or move the data to a `data-*` attribute.
- `<form> is not allowed in <CONTEXT> context: move the expression into element text or a t: attribute`.
- `t:utext is only allowed on elements whose content is HTML text, not <script> or <style>`.
- `[(...)] is only allowed in element text; inside <script> use [[...]], which outputs JSON`.
- `cannot output a value of type <type> inside <script>: only strings, numbers, booleans, enums, java.time values, and Optionals, collections, arrays and String-keyed maps of those can be serialized as JSON`:
  output the specific properties the script needs.
- `<form> needs a value of type dev.tessera.runtime.SafeHtml, but a message #{...} is a String: use t:text or [[...]] (escaped), or pass trusted HTML as a SafeHtml parameter`.
- `<form> needs a value of type dev.tessera.runtime.SafeHtml, but the expression has type <type>: use t:text or [[...]] (escaped), or build a SafeHtml in trusted Java code`.

### Attributes

- `<label> is not allowed: event-handler attributes cannot contain expressions; put the value in a data-* attribute and read it from a script`.
- `<label> is not allowed: style attributes cannot contain expressions; use t:class or t:classappend instead`.
- `<label> is not allowed: srcdoc is a whole HTML document and cannot contain expressions; use src with a URL`.
- `<label> is not allowed: the content of <meta http-equiv="refresh"> navigates the page and cannot contain expressions; redirect from the controller instead`.
- `<label> is not allowed: attribute name '<name>' is not allowed; names must match [A-Za-z_][A-Za-z0-9_.:-]*`.
- `<label>: the type of a <script> cannot be computed; its content type must be known at build time, so write a static type attribute`.
- `<label>: the http-equiv of a <meta> cannot be computed; it decides whether content is a refresh URL, so write a static http-equiv attribute`.
- `<label> on <<tag>> loads code or a document: use a literal-path link such as @{/js/app.js} or a TrustedUrl value, not a <type>`.
- `t:attr needs a value, e.g. t:attr="title=owner.name, data-id=owner.id"`.
- `t:attr cannot set t:<name>; write the t: attribute directly`.

`<label>` is the attribute as written (`t:onclick`, `t:attr style`).

### Control flow

- `t:each needs a value, e.g. t:each="item : items"`.
- `t:each loop variable and status variable must have different names, e.g. t:each="item, itemStat : items"`.
- `t:with needs a value, e.g. t:with="total=order.total"`.
- `t:with variable name '<name>' must be an identifier ([A-Za-z_][A-Za-z0-9_]*)`.
- `t:case must be inside an element with t:switch (on an enclosing element, not the same one)`.
- `t:remove must be one of all, body, tag, all-but-first, none (got '<value>')`.

### Messages and links

- `unknown message key '<key>': it is not in <bundle file>`: add the key to the bundle or fix the
  spelling.
- `message keys in this template are not checked: set -Atessera.messages=<bundle base path> to check #{...} keys at build time`
  (warning): set the option (section 2).
- `duplicate link parameter '<name>' in @{<path>}`.
- `link path variable {<name>} in @{<path>} has no parameter: add (<name>=...)`.
- `link @{<path>} has scheme '<scheme>:': only http and https absolute links are allowed; use a relative path, or pass a TrustedUrl instead of a link`.
- `link @{<path>} has a path variable in its host: write the host literally`.

### Forms

- `t:object must name a variable (a t:param or local variable), e.g. t:object="owner"`: for
  `th:object="${owner.address}"`, use `t:with="address=owner.address"` on an enclosing element
  and `t:object="address"`; the variable name is the model attribute name used for binding.
- `t:field works on <input>, <select> and <textarea>, not <<tag>>`.
- `t:field on a radio button or a non-boolean checkbox needs a value attribute (or t:value)`.
- `<t:field> needs a field path, e.g. <t:field>="*{lastName}"`.
- `<t:field> can only be used inside t:object (on this element or an ancestor), or with a Field fragment parameter`.
- `<t:field> needs a field path such as lastName or *{address.city}, not an expression`.
- `field '<path>' can only be used inside t:object: put t:object="..." on this element or an enclosing one`.
- `'<path>' is not a field path: use names like lastName or address.city`.
- `*{<name>} can only be used inside t:object: put t:object="..." on this element or an enclosing one, or write the full path (owner.<name>)`.
- `t:errors can only be used inside t:object (on this element or an ancestor)`.
- `t:errorclass needs literal class names (letters, digits, '-', '_'), e.g. t:errorclass="error"`.
- `t:errorclass needs t:field on the same element`.
- `unknown function fields.<fn>: the fields namespace has hasErrors('path') and errors('path')`:
  `#fields.hasAnyErrors()` → `fields.hasErrors('*')`, `#fields.allErrors()` → `fields.errors('*')`.
- `fields.<fn>(...) can only be used inside t:object, or with a Field parameter`.
- `fields.<fn> takes one string literal (a field path such as 'lastName', or '*' for all errors) or a Field parameter`.

### Fragments

- `<t:insert> needs a fragment reference, e.g. ~{fragments/layout :: header}`.
- `<t:insert> needs a fragment reference (~{template :: name}) or a value of type Fragment, but the expression has type <type>`.
- `template name '<name>' must be a path relative to the templates root, without a leading '/' or empty, '.' or '..' segments`.
- `unknown template '<name>' in ~{<name> :: <fragment>}: template names are paths relative to the templates root, without .html`.
- `template '<name>' has no fragment '<fragment>'; its fragments are: <signatures>`.
- `wrong number of arguments for fragment <signature>: expected <n> but got <m>`.
- `unknown argument '<name>' for fragment <signature>`, `duplicate argument '<name>' for fragment <signature>`,
  `missing argument '<name>' for fragment <signature>`.
- `argument <n> ('<param>') of fragment <signature> has type <type>, but a fragment ~{...} was passed`.
- `argument <n> ('<param>') of fragment <signature> has type <type>, but the argument has type <type>`.
- `argument <n> ('<param>') of fragment <signature> is a form field, so the call must be inside t:object (on this element or an ancestor)`.
- `argument <n> ('<param>') of fragment <signature> has type Field: pass a field path as a string literal, e.g. 'lastName' (checked against the enclosing t:object), or a Field parameter`.
- `~{::<name>}: this template has no fragment or element named '<name>'`.
- `~{::<name>} selects an element, which takes no arguments`.
- `~{::<name>} selects an element that contains this t:insert/t:replace, so it would include itself`:
  select a sibling (`~{::body}` from `<html>` works because `<html>` is the caller).
- `~{::<name>} selects an element inside this element's t:object, but t:insert/t:replace runs before t:object, so the selection would render without it: move t:object to an enclosing element`.
- `~{::<name>} and the selections inside it include each other, so rendering would never end: no selected element may pass ~{::...} of an element that selects it`.

## 17. Known limitations

- **Incremental builds.** Maven's incremental compile does not notice template-only changes. Run
  `mvn clean compile` (or touch a Java file) after editing only templates. There is no hot
  reload: restart the application to see a template change.
- **No dynamic templates.** View names, fragment references, message keys and link paths are
  literals. Choose between literals with conditionals.
- **No ambient context.** Request parameters, session attributes, beans and static methods must
  be passed in.
- **Not supported:** the layout dialect, `th:inline` modes and `/*[[...]]*/` natural-template
  comments, markup selectors other than fragment and tag names, collection
  selection/projection, `th:style`/`th:on*` with expressions (by design), `th:include`,
  `th:attrappend`, Kotlin, Gradle, and byte-array output.
- **Output differences from Thymeleaf** that are expected: attributes set by `t:` attributes may
  move within the tag; `<td >` (after a removed attribute) renders as `<td>`; a `null` attribute
  value omits the attribute (Thymeleaf writes `value=""`); a `null` link path variable fails the
  render.
- **Errors at runtime** are limited to model type mismatches (`TemplateModelException`), missing
  primitive parameters and `null` link path variables; everything else is a build error.
