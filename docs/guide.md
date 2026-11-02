# Porting guide: moving server-rendered Java views to Tessera

This is the reference for moving a Spring application's views (Thymeleaf, JSP, FreeMarker,
Velocity, Pebble, Mustache or similar) to Tessera. It doesn't map your engine's syntax construct by
construct: you know your engine, and once you know Tessera's model the syntax converts on its own.
It covers what you can't find out from the tools: the model (section 1), build setup (section 2),
the tools and the loop (section 3), syntax and output contexts (section 4), the traps of particular
engines (section 4.10) and known limitations (section 5).

The compiler's error messages are the detailed documentation for each rule: they name the file,
line and column, say what is wrong, and name the fix. Examples marked `<!-- compile: ... -->` in the
source of this file are compiled and rendered by Tessera's tests, so they are known to work.

## 1. The model

Tessera templates are HTML files with `t:` attributes, close to Thymeleaf's `th:` syntax. An
annotation processor compiles them to Java classes during the application's normal `javac`, so a
template error is a build error. At runtime nothing parses or reads templates.

**A template sees only its typed parameters.** The first element declares them
(`t:params="Owner owner, List<Pet> pets"`). With Spring each is read from the model attribute of
the same name, or else from the handler's `@PathVariable` of that name. There is no request,
session, application or bean scope, no request parameters, cookies or headers. Engines you are
leaving often fall back to those scopes when a name is not in the model; Tessera doesn't, so **a
missing model attribute is `null`**, which prints nothing, and reading a property of it fails the
render with a `NullPointerException` (write `a?.b` where `null` is expected). Before converting a
template, find everything it reads that the controller doesn't put in the model, and make the
controller provide it. Values every page needs (the current user, the request URI) come from
`@ModelAttribute` methods in a `@ControllerAdvice`.

**Templates read data; they don't run code.** A template reads record components and
`getX()`/`isX()` getters of view-safe types (section 4.2), plus built-ins such as `size()`. It
cannot call other methods, construct objects or reach static members. Logic lives in Java: a
getter, a record the controller builds, or a static `@TemplateFunction` (section 4.6).

**The output context picks the escaper.** Text, attribute values, URLs and `<script>` values are
each escaped for where they appear, with no switch to turn it off (section 4.4). Raw markup must be
a `SafeHtml` built in Java, and stored HTML goes through `html.sanitize(...)`. Values the old
engine printed raw now come out escaped; that is intended, not a difference to undo.

**Forms bind through Spring's `BindingResult`**, as Spring's form tags and macros do (section 4.7).

**Fragments see only their parameters.** A fragment is a typed function that cannot see the
caller's variables. Includes, macros with bodies, tag files, partials and layouts all become
fragment calls, with markup passed as a `Fragment` (section 4.8).

**Names are literals.** View names, fragment references, message keys and link paths are checked
at build time. Choose between literals with a conditional.

**Values print with `String.valueOf`**, with no locale-aware formatting: use `numbers.*`,
`temporals.*` or your own function. `null` text renders empty and a `null` attribute is omitted, but
inside `|...|` and `+` a `null` prints `null`.

| If the old template... | Do this instead |
|---|---|
| reads the request, session, application scope, cookies or headers, or falls back to them | Put the value in the model and declare it in `t:params`. |
| calls a method with arguments, a static method, a bean or a constructor | A getter, a record built by the controller, or a `@TemplateFunction`. |
| filters, sorts, groups or projects a collection | Do it in the controller. |
| assigns a variable | `t:with="x=..."`: an immutable local for one element. |
| builds a view name, fragment name, message key or link path from strings | Conditionals between literals; link `{placeholders}`; a `Fragment` argument. A message key computed from data is resolved in Java and passed as a string. |
| evaluates a string as a template (`?eval`, `#evaluate`, `__${...}__`) | Compute the value in Java. |
| prints a string unescaped | Stored rich text: `t:utext="html.sanitize(article.text)"`. Markup built in Java: a `SafeHtml`. A bundle message with markup: `t:utext="#{key}"` (section 4.5). |
| puts expressions in `style` or a `data-*`/framework attribute | Section 4.4: `t:style` takes literals, numbers and `css.*` values; other non-standard attributes take only framework-neutral values. |
| has an event handler (`onclick="..."`, any `on*`, static or computed) or a `javascript:` URL | Build errors: a strict CSP blocks inline script. Mark the element (`data-action="save"`) and attach one listener from a `<script>`: `document.addEventListener('click', e => { const el = e.target.closest('[data-action=save]'); if (el) save(el); });`. A `href="javascript:void(0)"` link becomes a `<button type="button">`, or `href="#"` with `e.preventDefault()` in the listener. `tessera-migrate` flags both. |
| includes a file, calls a macro or tag with a body, or extends a layout | A fragment call with `Fragment` arguments (section 4.8). |
| binds a form through form tags or macros | `t:object`, `t:field`, `t:errors`, `fields.*` (section 4.7). |
| reads an `Exception`, a `Date` or another non-view-safe value | Put what the page shows (a message `String`, a `LocalDate`, an `Instant`) in the model. |

## 2. Build setup

The build needs JDK 8+ and Maven or Gradle; the application runs on Java 8+ with Spring Framework
4.3+ or Spring Boot 1.5+, on Servlet 3.0+. Keep the application's Spring and Boot versions: don't
upgrade as part of the migration. On Java 8 there are no records, so view types are classes with
getters annotated `@TemplateView`.

Pick the starter by servlet API and use exactly one; it brings only `tessera-runtime`:

| Application | Artifact |
|---|---|
| `jakarta.servlet` (Boot 3+, Spring 6+) | `tessera-spring-boot-starter` |
| `javax.servlet` (Boot 1.5-2.x, Spring 4.3-5.x) | `tessera-spring-boot-starter-javax` |

```xml
<dependency>
  <groupId>dev.tessera</groupId>
  <artifactId>tessera-spring-boot-starter</artifactId> <!-- javax: -javax; without Spring: tessera-runtime -->
  <version>${tessera.version}</version>
</dependency>

<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <parameters>true</parameters>
    <annotationProcessorPaths>
      <!-- every processor the build already uses (Lombok first), then: -->
      <path>
        <groupId>dev.tessera</groupId>
        <artifactId>tessera-compiler</artifactId>
        <version>${tessera.version}</version>
      </path>
    </annotationProcessorPaths>
    <compilerArgs combine.children="append">
      <arg>-Atessera.templates=${project.basedir}/src/main/resources/templates</arg>
      <arg>-Atessera.package=com.example.templates</arg>
      <arg>-Atessera.messages=${tessera.messages}</arg>
    </compilerArgs>
  </configuration>
</plugin>

<plugin>
  <groupId>dev.tessera</groupId>
  <artifactId>tessera-maven-plugin</artifactId>
  <version>${tessera.version}</version>
  <executions><execution><goals><goal>check</goal></goals></execution></executions>
  <!-- if tessera.templates is not the default:
  <configuration><templates>${project.basedir}/src/main/webapp/templates</templates></configuration> -->
</plugin>
```

- `tessera.templates` (required) is the absolute templates root; every `**/*.html` below it is a
  template. `tessera.package` is the generated package. `tessera.messages` is the Spring message
  basename as a path without `.properties` (several, comma-separated); with it every `#{key}` is
  checked at build time. If the base file is partial or missing and the full text is in a locale
  file, name that file (`.../i18n/messages_en`).
- `annotationProcessorPaths` turns off processor discovery, so list every processor the build
  relied on (Lombok, MapStruct, ...), Lombok before Tessera. Templates see Lombok's getters.
- After `mvn clean compile`, `target/generated-sources/annotations` holds a `*Template.java` per
  template. If it is empty, the processor didn't run: `annotationProcessorPaths` needs
  `maven-compiler-plugin` 3.5+ (`<parameters>` 3.6.2+). Boot 1.5's parent pins 3.1 and defaults
  `java.version` to 1.6: set `<java.version>1.8</java.version>` and
  `<maven-compiler-plugin.version>3.8.1</maven-compiler-plugin.version>` (Boot 1.4 pins the
  version on the plugin itself, so write `<version>` there).
- Templates compile at the application's release level. Under a Boot parent, set
  `<java.version>` and leave `<release>` off the plugin, or the class files may not load on the
  runtime JDK. JDK 8's javac has no `--release`: use `<source>`/`<target>` 1.8.
- Maven runs javac only when Java sources change, so without `tessera-maven-plugin`'s `check`
  goal an edited template or message keeps its old generated class and the build stays green. It
  reads `tessera.messages` from the property of that name, so set the property. The plugin needs
  Maven 3.6.3+; upgrade an older `mvnw` wrapper.
- The generated `META-INF/services/dev.tessera.runtime.TemplateRegistry` and
  `META-INF/tessera/**` must be packaged; otherwise the starter warns "No compiled Tessera
  templates on the classpath" at startup.
- Static analysis that runs on compiled classes (SpotBugs, PMD, Checkstyle, Javadoc) sees the
  generated templates: exclude the `tessera.package` package rather than loosening the rules.
- Resource filtering rewrites `${...}` in templates unless `templates/**` is excluded from it.
- Spring 4.3 and 5.0 need `--add-opens java.base/java.lang=ALL-UNNAMED` on JDK 16+, including the
  surefire `argLine`.

**Gradle.** The same processor, with no plugin:

```groovy
dependencies {
  implementation "dev.tessera:tessera-spring-boot-starter:$tesseraVersion" // javax: -javax
  annotationProcessor "dev.tessera:tessera-compiler:$tesseraVersion" // after Lombok and the others
}

def templates = file('src/main/resources/templates')
def messages = file('src/main/resources/messages')

tasks.named('compileJava') {
  options.compilerArgs += [
    "-Atessera.templates=$templates",
    '-Atessera.package=com.example.templates',
    "-Atessera.messages=$messages",
  ]
  inputs.dir(templates).withPropertyName('tesseraTemplates').withPathSensitivity(PathSensitivity.RELATIVE)
  inputs.file("${messages}.properties").withPropertyName('tesseraMessages').withPathSensitivity(PathSensitivity.RELATIVE)
}
```

The `inputs` lines make a template-only or message-only edit rerun `compileJava`; declare every
message file `tessera.messages` names.

**Spring Boot.** The starter's auto-configuration registers the view resolver, the template
registry and a `TemplateAvailabilityProvider`, on Boot 1.5 through 4, including `@WebMvcTest`
slices. Tessera's resolver runs first and falls through for view names it doesn't know, so the old
engine can stay while you migrate. While another engine's view resolver is a bean, the starter logs
`Tessera: another template engine is still configured` naming it. JSP, and resolvers added only
through `ViewResolverRegistry` or inside a `ContentNegotiatingViewResolver`, are not reported.

**Spring MVC without Boot.** Import `dev.tessera.spring.TesseraConfiguration` (or declare it as a
bean in XML). It also registers a resolver for `redirect:` and `forward:` names, which the old
engine's resolver often provided: without one, removing the old engine breaks redirects. If your
container doesn't scan for `WebApplicationInitializer`s, register the CSP filter (section 4.9).

**Security.** Spring Security's CSRF token goes into POST forms automatically (section 4.7). Keep
the application's own security setup; Boot 1.5's default security has CSRF off, and that stays so.

**Resource links.** With the resource chain enabled, the starter registers
`ResourceUrlEncodingFilter` as Boot's Thymeleaf auto-configuration did, so `@{/webjars/...}` links
stay versioned. Remove a hand-written one.

**Error pages.** Under Boot, `templates/error.html` (or `error/404.html`, `error/4xx.html`,
`error/5xx.html`) renders with Boot's error attributes: `Integer status`, `String error`,
`String message`, `String path`, and `timestamp`, which you declare as `Instant` or
`ZonedDateTime`. Since Boot 2.3, `message` is present only with
`server.error.include-message=always`, so it may be `null` or `''`: test the value
(`t:unless="strings.isEmpty(message)"`), and record the old app's error pages with the same
setting. Always render the whole error page, even where the old engine failed part-way through it.

## 3. The tools and the loop

`tessera-migrate` is one jar with three commands; `--help` prints the usage. Fetch it as the skill
says, or use the jar in the local Maven repository.

**1. List every page.** Write a `pages.txt` that reaches every view in every state that renders
differently: forms empty, with validation errors and after success; logged out and as each role;
empty and full lists; error pages.

```text
# One request per line, run in order with one cookie jar.
GET /owners/new
POST /owners/new firstName=&lastName=
POST /owners/new firstName=Ann&lastName=Lee&city=Paris&telephone=0123456789
GET /owners/1
POST /login username=admin&password=admin
```

A `POST` sends the `_csrf` hidden input of the previous page, so `GET` a form before posting it.
Write paths and form values raw or percent-encoded (a space is `%20` in a path, `+` in a form
value). Start the app from the same data on every run, and let the list create the data it needs.

**2. Record the old app** before changing anything:
`java -jar tessera-migrate.jar record http://localhost:8080 pages.txt baseline/`. It saves each
response's body, status and final URL. `compare` refuses a `pages.txt` that doesn't match the
recording, so record again after changing it.

**3. Set up the build** (section 2) and give the templates view types (section 4.2).

**4. Convert one template at a time**, shared layouts and fragments first, since pages depend on
them. For each template, find every value it reads and make sure the model provides it, declare the
parameters with their real types, rewrite the markup, and run `mvn clean compile`.

For Thymeleaf, `tessera-migrate <old-templates-dir> <new-templates-dir> --report report.md` does the
mechanical rewrite of a whole directory first: `th:` to `t:`, utility objects, common string-built
links, layout markup, script inlines. It stubs parameter types as `Object`, which fails to compile
until you write the real types. Its report lists what it couldn't convert and every read of a
scope the template can't see (`session.x`, `param.x`, `#request`, `@bean`, `T(...)`, ...) with
where the value should come from instead: that is the controller-side checklist. It can't see a
plain name the old engine found in a request attribute; those show up in `compare` as an empty
value or a 500.

**Nulls are where a converted page most often fails at runtime.** A `null` link path variable
fails the render where the old engine wrote an empty segment, and an empty one is rejected too:
guard such a link with `t:if`. A new `NullPointerException` reading `a.b` means `a` is `null` now
and wasn't before, nearly always a value the old template got from outside the model. Tests catch
these only if a fixture holds the `null`.

**5. Compare** the new app, started from the same data:
`java -jar tessera-migrate.jar compare http://localhost:8080 pages.txt baseline/`. It compares
status, final URL and the page as a browser builds it, ignoring whitespace, attribute order,
comments, CSRF tokens and JSON formatting in scripts. Each page prints `same`, `reordered` or
`differs`, with a diff for the last two. `reordered` passes but its diff is printed: check that
what moved is harmless (validation messages from a `HashSet`, yes; table rows that lost their sort
order, no). It exits 0 only if every page is `same` or `reordered` with its recorded status and
final URL.

For each difference, fix it or decide it is right and write down why. Few are right: output now
escaped where the old engine printed it raw, broken markup the old engine passed through, and the
expected changes of a section 4.4 recipe. Text that changes on every run (times, random ids,
content-hashed asset names) can be masked with `--ignore REGEX`; keep it narrow, since what it
matches is never compared. Compare after each template or small group, and run the tests. Controller
tests that use `standaloneSetup` never render a view, so add one that renders through the real
resolver.

**6. Remove the old engine.** This completes the security guarantee: until its resolver is gone,
any view name Tessera doesn't know still reaches it. Remove its templates, dependency and
configuration, then build, test and compare once more. The old engine's starter often brought in
libraries the application still uses (`spring-context-support` with Boot's Velocity starter,
`commons-beanutils` with Tiles), and losing them may show only at runtime: diff
`mvn dependency:list` from before and after, and declare what the application still uses.

## 4. Reference

### 4.1 Templates and parameters

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

- `t:params` is `Type name, ...` with Java types (generics, arrays, `Outer.Inner`). A primitive
  parameter must be present in the model. `t:import` lists classes or packages; `java.lang`,
  `java.util`, `java.time`, `java.math` and `dev.tessera.runtime` are always imported.
- The template compiles to `<tessera.package>.guide.HelloTemplate` with
  `of(Customer, List<Order>, boolean)`.
- Static attribute values and text are prototypes that `t:` attributes replace. `<t:block>`
  renders only its content. HTML comments are written to the page; the template comment
  `<!--/* ... */-->` is dropped with everything inside it. Use it for other engines'
  template-only comments.

### 4.2 View types

Templates may read only **view-safe** types, checked at build time for every step of a property
chain: primitives and their boxes, `String`, enums, records, `java.time` values, `BigDecimal`,
`BigInteger`, `Optional`, `java.util` collections, maps and arrays of view-safe types, `SafeHtml`,
and public classes annotated `@TemplateView`. Declare collection getters as the
`java.util` interface: `java.util.concurrent`, Guava and your own collection subclasses aren't
view-safe. A view type, any class it is nested in, and its type arguments must be public. `Object`,
`Class`, reflection, `System`, servlet types and Spring context types are never view-safe.

On a view-safe type a template can read record components (`owner.name()`), public no-argument
getters (`owner.name`, `owner.getName()`, `isX()` for booleans; `new` is a keyword, so write
`pet.isNew()`), `size()`/`isEmpty()`/`first`/`last` on collections, `length` on arrays, and index
access (`list[0]`, `map['key']`). Nothing else.

**Recommended: a view record per page**, built by the controller from its entities. Lazy loading
then happens in the controller, the template sees only what the record lists, and shaping is plain
Java. On Java 8, write it as a small class with getters annotated `@TemplateView`.

**Shortcut: `@TemplateView` on existing classes.** For a large port it can be quicker to annotate
the entities the templates already read. Then every public getter is readable, lazy associations
load during rendering, and every class reached through a getter needs the annotation too. Getters
inherited from an unannotated superclass resolve.

<!-- compile: java -->
```java
package com.example.guide;

import dev.tessera.runtime.TemplateView;
import java.util.List;

@TemplateView
public class Account {
  private final String email;
  private final List<String> roles;

  public Account(String email, List<String> roles) { this.email = email; this.roles = roles; }

  public String getEmail() { return email; }
  public List<String> getRoles() { return roles; }
}
```

### 4.3 Expressions and attributes

Expressions and attributes are Thymeleaf's Standard dialect with `t:` for `th:`, minus everything
that runs code. What differs:

- `${...}` is optional (`t:text="owner.name"`). `*{...}` works only inside `t:object`, for one
  property path: `*{firstName} + ' ' + *{lastName}`. Utility objects are function namespaces
  (section 4.6); `#ctx`, `#request`, `#dates` and the rest don't exist.
- Not supported: `T(...)`, `new`, `@bean`, `__...__` preprocessing, assignment, lambdas,
  selection and projection (`.?[...]`, `.![...]`), `div`/`mod` (use `/`, `%`).
- `==` is `Objects.equals`; an enum compared with a string literal compares the constant's name.
  `c ? a` without else is `null`. Both branches of `?:` and `c ? a : b` need compatible types.
- `|...|` and `+` are Java string concatenation, so a `null` prints `null`. To print nothing,
  write `x ?: ''` for a `String` and `x == null ? '' : '' + x` otherwise.
- `t:if` is false for `null`, `false`, `0` and the strings `"false"`, `"off"` and `"no"`, and true
  for everything else, including `""` and empty collections. Write what the old condition meant:
  `x != null`, `!list.isEmpty()`.
- Attributes on one element run in Thymeleaf's order: `t:fragment` → `t:insert`/`t:replace` →
  `t:each` → `t:if`/`t:unless`/`t:switch`/`t:case` → `t:object`/`t:with` → other attributes →
  `t:text`/`t:utext`/`t:errors` → `t:remove`. So a `t:if` doesn't guard a `t:replace` on the same
  element: put it on an enclosing element or a `<t:block>`.
- `t:each` iterates an `Iterable`, array or `Map` (`e.key`, `e.value`), with status `index`,
  `count`, `size`, `first`, `last`, `even`, `odd` (implicit name `xStat`). `t:with` locals are
  immutable. `t:remove` takes a literal (`all`, `body`, `tag`, `all-but-first`, `none`).
- Any `t:<name>` sets that attribute, and `null` omits it, where Thymeleaf's `th:value`,
  `th:href`, `th:src`, `th:action`, `th:name` and `th:type` wrote it empty. Where the empty
  attribute matters, write `x ?: ''`. Boolean attributes (`t:checked`, ...) take a condition.
- Not supported: `th:attrappend`/`attrprepend`, `th:alt-title`, `th:lang-xmllang`, `th:assert`,
  `th:include` (use `t:insert`) and `th:inline`.

### 4.4 Output contexts

The compiler decides the context from the element and attribute name; you can't override it. What
each one accepts:

| Context | Where | Accepts |
|---|---|---|
| Text | element text, `t:text`, `[[...]]` | Any value, escaped. |
| Raw HTML | `t:utext`, `[(...)]` | A `SafeHtml`, or a bundle message, sanitized by the message policy (section 4.5). |
| Attribute | inert-text attributes: `title`, `alt`, `class`, `id`, `value`, `name`, `placeholder`, `aria-*`, ... | Any value, escaped. |
| Typed attribute | other standard attributes: `rel`, `target`, `sandbox`, most of `<meta content>`, ... | Literals, numbers, and a choice between literals (`${newWindow} ? '_blank' : '_self'`). |
| URL | `href`, `src`, `action`, `formaction`, `cite`, `poster`, ... | Any value; only `http`, `https`, `mailto`, `tel` and relative URLs pass, anything else becomes `about:invalid#tessera`. Forms: section 4.7. |
| Resource URL | `script[src]`, `iframe[src]`, `frame[src]`, `embed[src]`, `object[data]`, `link[href]`, `base[href]` | A same-origin `@{...}` link without query parameters, such as `@{/js/{v}/app.js(v=${v})}`. Load another origin with a static attribute. |
| Neutral | every other name: `data-*`, `hx-*`, `x-*`, `v-*`, `ng-*`, custom elements' attributes | A framework-neutral value (below). |
| Script | `<script>` text | `[[...]]` writes JSON (below). |
| Style | the `style` attribute | Literals, numbers and `CssValue`s (`css.color(c)`, `css.length(l)`, `css.url(u)`, `css.var(name)`), combined with `+`, `\|...\|` and conditionals. No expressions in `<style>`. |
| Refresh | `content` of `<meta http-equiv="refresh">` | `'3;url=' + @{/path...}`: a literal delay and a same-origin link. |
| Forbidden | `on*`, `srcdoc`, URL lists (`srcset`, `imagesrcset`, `ping`), and what the parser reads: `type` on `<script>` and `<input>`, `<meta http-equiv>` and `charset`, `<meta content>` under a static `http-equiv="Content-Type"`, `<template shadowroot*>` and `for`, `encoding`, `is`, `xmlns*`, `<font color/face/size>`, names outside `[A-Za-z_][A-Za-z0-9_.:-]*` (Vue's `:x`, `@x`) | Nothing computed. |

**SVG, MathML and markup Tessera doesn't model.** An `<svg>` or `<math>` must be static: no
`t:*` attribute, `[[...]]` or fragment inside it, and no computed attribute on it (control flow on the
element itself, `<svg t:if="...">` or `t:replace`, is fine). Set what changes outside it: a `t:class` on
an HTML parent with CSS (`fill: currentColor`, or a CSS variable such as
`<div t:style="|--progress: ${n}|">` read by `stroke-dasharray: var(--progress) 100` in the stylesheet),
one static `<svg>` per case under `t:if`/`t:switch`, or an `<img t:src="@{/icons/...svg}">`. For a
computed `srcset`, write one `src` (or a static `srcset`); for `<font color>` from data, write a `<span>`
with a `t:class`; for a computed `<input type>`, write one `<input>` per type under `t:if` or
`t:switch` (the `fields` example in 4.8). CDATA sections, `<xmp>`, `<plaintext>`, `<noembed>`, `<noframes>` and `<frameset>` are
build errors: write the text escaped (`&lt;`) in a `<pre>`, or use `<noscript>`, an ordinary layout or an
`<iframe>`. Inside `<noscript>` and `<iframe>`, only static text.

**Scripts.** In a JavaScript `<script>`, each `[[...]]` must be the whole value of a declaration at
the top of the script, before any other code: `const roles = [[${account.roles}]];`. The value is
written as JSON in parentheses, and the code below uses the name. That is how the compiler knows
where every value lands without a JavaScript parser. So a value inside a string (`"[[${x}]]"`), a
comment (Thymeleaf's `/*[[${x}]]*/ 'd'`), a template literal or an expression is an error: move it
into a declaration (`var x = [[${x}]];` at the top, or `const x0 = [[${x}]]; x = x0;`). Records and
`@TemplateView` beans become JSON objects of their readable properties, as Thymeleaf's Jackson
output does, so `var chart = [[${chart}]];` works unchanged; pass the object, not a JSON string
built in Java. Data can also go in a `<script type="application/json" id="data">[[${data}]]</script>`
read with `JSON.parse`; inside it, write `{"name": [[${x}]]}` without quotes around the value.

<!-- compile: template guide/contexts.html -->
```html
<div xmlns:t="https://tessera.dev" t:import="com.example.guide.Account"
     t:params="Account account, SafeHtml bio, String homepage">
  <a t:href="homepage" t:text="account.email">me@example.com</a>
  <section t:utext="bio">Bio</section>
  <button t:title="account.email" class="js-copy">Copy</button>
  <script>
    const roles = [[${account.roles}]];
  </script>
  <script t:src="@{/js/app.js}"></script>
</div>
```

**Framework-neutral values.** Many client-side frameworks run attribute values as code (Vue's
`v-html`, Alpine's `x-data`, htmx's `hx-on`, Knockout's `data-bind`), and in several `data-*` is the
code channel. Tessera models none of them, so a computed attribute whose name isn't a standard
HTML, SVG, MathML or ARIA attribute takes only a value no framework can run: a number, a boolean, a
same-origin link `@{/items/{id}(id=${item.id})}` (literal path, every parameter a path variable, no
query), or a choice between those and literals. `t:data-id="${item.id}"` compiles;
`t:data-name="${item.name}"` doesn't. Static attributes are copied as written, so
`<api-entry :security="security" @click="open">` stays as it was.

**Migration recipes.** Port a computed string in such an attribute by what it is for:

| The string is | Recipe |
|---|---|
| a request value: `hx-vals`, `hx-headers`, a query in `hx-get` | Hidden inputs and `hx-include` (below). |
| text for people: a tooltip, a label, a status | `title` or element text. Bootstrap's tooltip reads `title`. |
| data your own script reads (`el.dataset.x`) | A JSON block or a hidden input the script reads instead. |
| Alpine `x-data`, Vue props, other component state | A named component (`Alpine.data`, `createApp(App, props)`) that reads a JSON block. |
| options a third-party plugin reads from `data-*` | A number on each element, the strings in a JSON block, and an init script that passes them through the plugin's API (below). If the plugin's only text option is HTML, escape the text first. |
| a key a plugin only compares (a gallery group name) | A neutral value that is equal where the strings were: a number or a link. |
| an id with a prefix (`"hint-" + id`) | The number; the script adds the prefix. |
| a URL held in a variable | A link with a literal path, `@{/items/{id}(id=${id})}`. |

These workarounds compile but break the page or bring back what the rule prevents, so don't use
them: a choice of literals that lists the values of an open-ended range (`row-0`, `row-1`, ...), a
message hard-coded per locale, or a script that copies a value from a JSON block back into a
`data-*` attribute so an unchanged plugin finds it. A `#{key}` in a neutral attribute is an error.
A recipe changes the page, and `compare` shows it: write down which recipe caused each difference.
If it edits a content-hashed asset, mask the hash narrowly.

<!-- compile: template guide/recipes/hidden-inputs.html -->
```html
<ul xmlns:t="https://tessera.dev" t:params="List<String> skus">
  <li t:each="sku : skus">
    <input type="hidden" name="sku" t:value="sku">
    <button hx-post="/cart" hx-include="previous input">Add</button>
  </li>
</ul>
```

<!-- compile: template guide/recipes/plugin.html -->
```html
<div xmlns:t="https://tessera.dev" t:params="List<String> tips">
  <script type="application/json" id="tips-data">[[${tips}]]</script>
  <span t:each="tip, stat : tips" class="hint" t:data-tip="stat.index">?</span>
  <script t:src="@{/js/tips.js}"></script>
</div>
```

```js
// /js/tips.js
const tips = JSON.parse(document.getElementById('tips-data').textContent);
document.querySelectorAll('.hint').forEach((el) => {
  new bootstrap.Tooltip(el, { title: tips[el.dataset.tip] }); // text, not HTML
});
```

If the page re-initializes the plugin after swapping in new HTML, call the same init function on
the swapped element.

**Rich text and `SafeHtml`.** For stored HTML (articles, bios) that the old page printed raw, write
`t:utext="html.sanitize(article.text)"`: it keeps basic formatting and checked links and drops
everything else. `html.sanitizeRich` also keeps same-origin images, `language-*` classes, ids and
`target="_blank"` links, for editor or Markdown output. Neither keeps tables, `style`, `iframe` or
`svg`. For another mix, build a policy with `HtmlPolicy.builder()` (`allowElements`,
`allowAttributes`, `allowClasses`, `linkRel`, `allowLinkTarget`) and expose it as a function:

<!-- compile: java -->
```java
package com.example.guide;

import dev.tessera.runtime.HtmlPolicy;
import dev.tessera.runtime.SafeHtml;
import dev.tessera.runtime.TemplateFunction;
import dev.tessera.runtime.TemplateFunctions;

@TemplateFunctions("notices")
public final class NoticeFunctions {
  private static final HtmlPolicy POLICY =
      HtmlPolicy.builder()
          .allowElements("p", "br", "b", "a", "ul", "li")
          .allowClasses("status-*")
          .build();

  private NoticeFunctions() {}

  @TemplateFunction
  public static SafeHtml html(Object text) {
    return SafeHtml.sanitize(text == null ? null : text.toString(), POLICY);
  }
}
```

The builder accepts only names from a closed set, `HtmlPolicy.ELEMENTS` and
`HtmlPolicy.ATTRIBUTES`, and class names or prefixes, never `"*"`. Whatever the policy, images stay
same-origin paths and ids get the prefix `user-content-`.
Don't parse stored HTML in Java into records and
rebuild it with a fragment that inserts itself: the compiler rejects it, because the page's
structure would depend on data.

**Inline HTML in a paragraph, heading, link or button.** A `SafeHtml` may hold blocks, so the
compiler rejects it inside an open `<p>`, heading, `<a>` or `<button>` (`RAW_CLOSES_ELEMENT`): a
`<p>` or `<div>` in the value would close the element around it. For a value with inline markup
only (a bio with `<b>`, lines joined with `<br>`), return a `SafeInlineHtml` instead: it is
accepted wherever an HTML message is. Its policy comes from `HtmlPolicy.inlineBuilder()`, which
allows only names from `HtmlPolicy.INLINE_ELEMENTS` (`b i em strong small s u sub sup code kbd
mark abbr cite q dfn time span br wbr img`), so no links and no blocks. Allowing `img` keeps its
`src` without an `allowAttributes` call (`src` isn't in `HtmlPolicy.ATTRIBUTES`), but only a
same-origin path starting with a single `/`: an image whose `src` is a relative path such as
`../images/x.gif`, another host or a `data:` URL is dropped. `alt`, `title`, `width` and `height`
still need `allowAttributes`. Join lines with `concat` and `escape`:

<!-- compile: java -->
```java
package com.example.guide;

import dev.tessera.runtime.HtmlPolicy;
import dev.tessera.runtime.SafeInlineHtml;
import dev.tessera.runtime.TemplateFunction;
import dev.tessera.runtime.TemplateFunctions;

@TemplateFunctions("inline")
public final class InlineFunctions {
  private static final HtmlPolicy POLICY = HtmlPolicy.inlineBuilder().allowElements("b", "i", "span", "br").build();
  private static final SafeInlineHtml BR = SafeInlineHtml.sanitize("<br>", POLICY);

  private InlineFunctions() {}

  @TemplateFunction
  public static SafeInlineHtml bio(Object text) {
    return SafeInlineHtml.sanitize(text == null ? null : text.toString(), POLICY);
  }

  @TemplateFunction
  public static SafeInlineHtml twoLines(Object first, Object second) {
    return SafeInlineHtml.concat(SafeInlineHtml.escape(String.valueOf(first)), BR, SafeInlineHtml.escape(String.valueOf(second)));
  }
}
```

Then `<p t:utext="inline.bio(speaker.bio)">bio</p>` compiles, and so does an `<a>` or `<h3>` around
it. Content with links or blocks stays a `SafeHtml`, inserted in a `<div>`.

In Java, `SafeHtml.escape`, `SafeHtml.concat` and `SafeHtml.sanitize` build markup from checked
parts. Nothing wraps a string or a template's output unchecked: insert the other template with
`t:insert` instead.

**CSS.** A `style` value is built from literals, numbers and `CssValue`s:
`t:style="|background-color: ${css.color(category.color)}|"`; `css.var('--brand')` writes
`var(--brand)`. `css.color`, `css.length`, `css.url` and `css.var` return `null` for anything they
don't accept. Otherwise use `t:class`/`t:classappend`.

### 4.5 Messages and links

**Messages.** `#{key}` and `#{key(arg1, arg2)}` render through the application's `MessageSource`
(`{0}` placeholders). The key is a literal; choose with a conditional:
`active ? #{status.active} : #{status.inactive}`. A message is text and is escaped. With
`-Atessera.messages`, a missing key is a build error; without it, it renders `??key_locale??`. A
key computed from data has no template form: resolve it in Java with `MessageSource` and the
request `Locale` and pass the string in the model.

**Messages with markup.** A message whose value holds markup (Thymeleaf `th:utext="#{...}"`, JSP
`<spring:message>` without escaping) prints as HTML with `t:utext="#{key(args)}"`. The arguments
are escaped, and at render time the formatted message is sanitized with a fixed policy: inline
markup (`b i em strong u s sub sup small mark code kbd span br`, a `title`) and links (`a` with an
`http`, `https`, `mailto`, `tel` or relative `href`, given `rel="noopener noreferrer"`;
`target="_blank"` is kept). Anything else is dropped and its text kept: a `<p>` or `<div>`, a
`class` or `style`. A message is inline content, so it fits in a `<p>` or heading; one with a link
can't go inside another link (the build says so), a key whose values have no `<a>` can. For block
markup, keep the text in the bundle and the markup in the template.

<!-- compile: messages -->
```properties
phaseout.notice=This version is being phased out: see <a href="/phaseout">the plan</a>.<br>Save your work.
welcome.html=Hello <b>{0}</b>!
```

<!-- compile: template guide/messages-html.html -->
```html
<div xmlns:t="https://tessera.dev" t:params="String name">
  <p class="alert" t:utext="#{phaseout.notice}">notice</p>
  <p t:utext="#{welcome.html(name)}">Hello <b>Ann</b></p>
</div>
```

**Links** are Thymeleaf's: `@{/owners/{id}(id=owner.id, q=term)}` fills `{id}` (percent-encoded)
and turns other parameters into a query string; `@{{id}/edit(id=x)}` is page-relative; absolute
links must be `http`/`https`. Links get the context path and `response.encodeURL`. A `null`, empty,
`.` or `..` path variable fails the render: guard the link with `t:if`. A URL computed in Java goes
in `t:href` as a `String` (scheme-checked); a URL with another scheme (`sms:`, `data:`, ...) must be
a static attribute.

### 4.6 Functions

Templates call functions as `namespace.fn(args)` or `#namespace.fn(args)`. All built-ins are
null-tolerant.

| Namespace | Functions |
|---|---|
| `strings` | `isEmpty`, `isBlank`, `defaultString(s, def)`, `abbreviate(s, max)`, `toUpperCase`, `toLowerCase`, `capitalize`, `trim`, `contains`, `startsWith`, `endsWith`, `substring(s, start[, end])`, `replace(s, before, after)`, `join(iterable, sep)` |
| `numbers` | `formatInteger(n, minDigits[, thousandsPoint])`, `formatDecimal(n, minIntDigits, decimals[, decimalPoint])`, `formatDecimal(n, minIntDigits, thousandsPoint, decimals, decimalPoint)`, `formatPercent(n, minIntDigits, decimals)`, `formatCurrency(n)`, `sequence(from, to)` |
| `temporals` | `format(temporal, pattern)`, `formatIso(temporal)` |
| `lists` | `size`, `isEmpty`, `contains(list, x)` |
| `css` | `color(s)`, `length(s)`, `url(s)`, `var(name)` |
| `html` | `sanitize(s)`, `sanitizeRich(s)` |
| `auth` (Spring starter) | `isAuthenticated()`, `hasRole('ADMIN')`, `hasAnyRole(...)`, `hasAuthority('PERM_X')`, `hasAnyAuthority(...)`, `name()` |
| `fields` (inside `t:object`) | section 4.7 |
| `ids` | `prev(name)`, `next(name)`, `seq(name)`: Thymeleaf's `#ids` (section 4.7) |

`numbers.*` and `temporals.format` use the render locale, as Thymeleaf's do (a point type is
`'POINT'`, `'COMMA'`, `'WHITESPACE'`, `'DEFAULT'` or `'NONE'`); `numbers.*` rounds half up.
`numbers.sequence(1, n)` counts down when `n` is below 1, so guard a loop the old engine ran zero
times. Add your own namespace:

<!-- compile: java -->
```java
package com.example.guide;

import dev.tessera.runtime.TemplateFunction;
import dev.tessera.runtime.TemplateFunctions;
import java.math.BigDecimal;
import java.math.RoundingMode;

@TemplateFunctions("money")
public final class Money {
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

Methods are `public static`, annotated `@TemplateFunction`, in a class compiled in the same `javac`
run as the templates. A leading `java.util.Locale` parameter receives the render locale and is not
a template argument. Functions can't see Spring beans or the request; don't reach them through
static state.

### 4.7 Forms

`t:object` names the form object, the model attribute Spring binds.

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

- `t:field` on `<input>`, `<select>` and `<textarea>` writes Spring's `id`, `name` and display
  value (formatters and `@DateTimeFormat` apply; a failed bind echoes the rejected value), plus
  `checked`/`selected` and the `_name` markers of checkboxes and multi-selects, as Thymeleaf's
  `th:field` does. Checkboxes and radios get numbered ids (`roles1`, `roles2`); point labels at
  them with `ids.prev('roles')`/`ids.next('roles')`. A `t:selected` on an option replaces the
  bound-value match for that option.
- `t:errors` writes the field's messages joined by `<br />`, or removes the element if there are
  none; `*` is all errors and `global` the form object's own. `t:errorclass` appends a class when
  the field has errors. For other markup, loop over `fields.errors('email')`.
- `fields.hasErrors(path)`, `fields.errors(path)` and `fields.value(path)` (the display value,
  JSP `spring:bind`'s `status.value`, Thymeleaf's `*{{x}}`) take a literal path, a `Field`
  parameter, or `*{...}` for an indexed path (`fields.hasErrors(*{items[itemStat.index].name})`).
- Paths index lists and maps: `*{items[itemStat.index].name}`, `*{prices[e.key]}`. This replaces
  `__${...}__` preprocessing.
- A field whose type isn't view-safe (a `MultipartFile`) can be bound where its value is never
  read: a file or password input, `t:errors`, `fields.hasErrors`.

**CSRF.** A `<form method="post">` gets Spring Security's token input automatically, but only
where the compiler can see the form posts to the page's own origin. So in a POST form the `action`
is absent (it posts to the page's own URL, query string included), a static same-origin URL, or a
link with a literal path starting with `/` (or a choice of such links); `method` is static; every
`formaction`, `formmethod` and `<base href>` on the page is held to the same rule; and forms don't
nest. Any other form's computed `action` is held to the link rule too, since client code may submit
it and swap in the response. A static action to another origin is allowed and gets no token. The
compiler's error names the fix in each case. A form that writes its own `_csrf` hidden input as a
direct, unconditional child keeps it and gets no second one. htmx requests are outside the
guarantee: use htmx 2 or set `htmx.config.selfRequestsOnly = true`.

### 4.8 Fragments and layouts

A fragment is an element with `t:fragment`. Parameters are typed, and a fragment sees only its
parameters.

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

`t:insert` keeps the host element and makes the fragment its body; `t:replace` replaces the host:

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

- References: `~{path :: name(args)}`, `path :: name(args)`, `~{::name}` (same file), or a
  `Fragment` value. Paths are relative to the templates root, without `.html`. Arguments are
  positional or all named, and type-checked; every call passes every argument (no defaults).
- `~{::name}` is a `Fragment` over the same-file fragment `name`, or else the first element with
  tag `name` (`~{::dl}`, `~{::my-card}`, `~{::t:block}`), rendered with the call site's variables.
  Put it inside the host so it doesn't also render in place. Only the first match; no `#id` or
  `.class` selectors.
- A `null` `Fragment` renders nothing, so a `Fragment` parameter can be optional.
- The compiler checks each fragment where it is inserted, so a `~{::tr}` goes into a `<tbody>`
  but not a `<div>`. An SVG icon fragment is a whole, static `<svg>`: nothing inside an `<svg>`
  is computed (4.4). Nothing goes inside `<title>`, `<textarea>` or `<noscript>`: use `t:text`.
- Every `t:if`, `t:unless`, `t:case` and `t:each` body, and every fragment, must close what it
  opens and leave open what it finds open, so what follows parses the same however often it is
  written (`[verifier:UNBALANCED]`). Two shapes need a change when porting:
  - Rows directly in a `<table>`: a browser opens a `<tbody>` before the first row only. Write the
    `<tbody>` (`<table><tbody><tr t:each="...">`), or put the `t:each` on the `<tbody>`.
  - A fragment library wrapped in `<html>`, with a fragment call or `t:if` before its `<body>`:
    that content makes a browser open the `<body>` (or `<head>`). Make the library's root a
    `<t:block>` instead of `<html>`, or move the fragments into the `<body>`.
  - `<head t:replace="~{fragments/head :: head('Home')}">` or `<body t:replace="...">`: the
    fragment brings its own `<head>` or `<body>` where a browser has already opened one. Keep
    the page's `<head>` and replace a `<t:block>` inside it with a fragment of the head's content
    (below), or put a layout on `<html>` (Layouts).

  A layout call on the page's `<html>` (below) is fine: it ends the document.

<!-- compile: template guide/fragments/head.html -->
```html
<t:block xmlns:t="https://tessera.dev">
  <t:block t:fragment="common(String title)">
    <title t:text="|${title} - Example|">Example</title>
    <link rel="stylesheet" t:href="@{/css/app.css}" />
  </t:block>
</t:block>
```

<!-- compile: template guide/head-page.html -->
```html
<!DOCTYPE html>
<html xmlns:t="https://tessera.dev">
<head>
  <t:block t:replace="~{guide/fragments/head :: common('Home')}"></t:block>
</head>
<body><h1>Home</h1></body>
</html>
```

**Layouts.** A layout is a fragment with `Fragment` parameters. Each page replaces itself with the
layout and passes its `<body>`, marked `t:remove="tag"` so that only the body's children are
inserted:

<!-- compile: template guide/fragments/layout.html -->
```html
<!DOCTYPE html>
<html xmlns:t="https://tessera.dev" t:fragment="layout(Fragment content, String title, Fragment head)">
<head>
  <title t:text="|${title} - Example|">Example</title>
  <link rel="stylesheet" t:href="@{/css/app.css}" />
  <t:block t:replace="head"></t:block>
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
      t:replace="~{guide/fragments/layout :: layout(~{::body}, 'Home', null)}">
<body t:remove="tag">
  <h1 t:text="|Welcome, ${customer.name}|">Welcome</h1>
</body>
</html>
```

This replaces decorator layouts (`layout:decorate`, SiteMesh, Tiles, Mustache parents). What a
decorator merged, the layout takes as parameters: the title, page-specific `<head>` content as a
`Fragment` (or `null`), the active menu entry. A page has one `<html>`, `<head>` and `<body>`: a
second one, often from passing `~{::body}` without `t:remove="tag"`, is a build error.
`t:remove="tag"` drops the element's own attributes, so move a `<body class>` to a layout
parameter. Nothing may come before the layout call. A layout that extends another layout replaces
itself with the outer one and passes its own markup:

<!-- compile: template guide/fragments/toolbar-layout.html -->
```html
<html xmlns:t="https://tessera.dev" t:fragment="toolbarLayout(String title, Fragment tools, Fragment content)"
      t:replace="~{guide/fragments/layout :: layout(~{::t:block}, title, null)}">
<t:block>
  <div class="toolbar" t:insert="tools">tools</div>
  <div t:replace="content">content</div>
</t:block>
</html>
```

<!-- compile: template guide/report.html -->
```html
<!DOCTYPE html>
<html xmlns:t="https://tessera.dev" t:params="String name"
      t:replace="~{guide/fragments/toolbar-layout :: toolbarLayout('Report', ~{::page-tools}, ~{::body})}">
<page-tools t:remove="tag"><a t:href="@{/report.csv}">CSV</a></page-tools>
<body t:remove="tag">
  <h1 t:text="name">Report</h1>
</body>
</html>
```

**Reusable form fields.** A `Field` parameter takes a field path that the compiler checks against
the caller's `t:object`. `Field<T>` also binds the type's properties (`t:field="c.answer"`), which
replaces JSP's `path="${field}.answer"` and Thymeleaf's `*{__${field}__.answer}`:

<!-- compile: template guide/fragments/fields.html -->
```html
<div xmlns:t="https://tessera.dev">
  <div t:fragment="input(String label, Field name, String type)" class="form-group"
       t:classappend="fields.hasErrors(name) ? 'has-error'">
    <label t:for="name" t:text="label">Label</label>
    <t:block t:switch="type">
      <input t:case="'email'" type="email" t:field="name" />
      <input t:case="*" type="text" t:field="name" />
    </t:block>
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

Printing a `Field` writes the field's id, so `<label t:for="name">` points at the input. A caller
can't pass a `Field` indexed by its own loop variable: keep those controls at the call site and pass
them in as a `Fragment`, or pass the list as a `Field<List<T>>` and index it inside.

### 4.9 Rendering

With Spring, `return "owners/list";` renders `templates/owners/list.html` from the model (one
leading `/` is dropped; other names fall through to other resolvers). Path variables fill
parameters of the same name. A model value of the wrong type fails with `TemplateModelException`.
`return "owners/list :: rows";` renders only the fragment `rows`, its parameters filled from the
model, for an htmx or Turbo partial response; arguments and selectors in the view name aren't
supported. Don't return a `Template` from a controller: no handler takes it, so Spring stores it as a
model attribute and renders a view name derived from the URL. Return the view name.

Outside a request (a mail job, a command-line tool), call
`XTemplate.of(...).renderToString(ctx)` with a `RenderContext` (`RenderContext.defaults()`,
`ResourceBundleMessages`, `DefaultLinks(contextPath)`), or in a controller with
`SpringRenderContexts.create(request, response, model)`. `Fragment` exists only between
templates: Java can't build one, and a page (`t:params`) or a function can't take one. A page that
took one becomes a fragment its callers pass `~{...}`.

**Content Security Policy.** With the Spring starter every `<script>` and `<style>` the template
writes gets the request's nonce, and the page is sent with `Content-Security-Policy: script-src
'nonce-N' 'strict-dynamic'; object-src 'none'; base-uri 'none'`, with or without Spring Security.
A policy the application sets wins: Spring Security's `contentSecurityPolicy(...)` or your own
filter's header (use `CspNonce.get(request)` for the nonce). The starter's filter,
`dev.tessera.spring.view.CspFilter`, runs first and adds the default only when the response commits
without one. Boot registers it; a servlet container finds it as a `WebApplicationInitializer`.
Where neither happens (an embedded server you start yourself, a `metadata-complete` `web.xml`, or
a Boot app importing `TesseraConfiguration`), register it yourself, first, for REQUEST, ASYNC and
ERROR dispatches; until then the starter logs `CspFilter is not in the filter chain`.
`tessera.csp.enabled=false` turns the default off. Delete Thymeleaf's `th:nonce`; a nonce
attribute in a template is an error.

### 4.10 Notes for specific engines

What is easy to get wrong when leaving a particular engine. Read the part for yours.

**Every engine.**

- **Raw output.** Velocity prints everything raw, JSP prints EL `${x}` raw outside `<c:out>` and
  the form tags, FreeMarker prints raw without an HTML output format (and with `?no_esc`), Pebble
  with `| raw`, Mustache with `{{{x}}}`. Treat each raw output as a review point and replace it by
  where the value comes from: plain data becomes an escaped `t:text` (if the page now shows
  `&lt;b&gt;`, the old page was injectable, and that difference is the fix); stored rich text goes
  through a sanitizer (section 4.4, Rich text; in a `<p>`, heading or link, Inline HTML); markup built in Java becomes records the template
  renders; a bundle message with markup is `t:utext="#{key}"` (section 4.5); JSON for a script is
  `[[...]]` (section 4.4).
- **Scripts.** `[[...]]` writes JSON with `/` as `\/`, and `<`, `>`, `&`, `'`, backtick, `$`, `{`,
  U+2028 and U+2029 as `\uXXXX` (`&` is `\u0026`); the JavaScript value is the same, so that
  difference in compare is expected.
- **Spring Security tags.** `sec:authorize="hasRole('A')"` and
  `<sec:authorize access="hasRole('A')">` become `t:if="auth.hasRole('A')"`, and
  `sec:authentication="name"` is `t:text="auth.name()"`. Any other expression is computed in the
  controller.
- **Whitespace and comments.** Write template-only comments (`{{! }}`, `<%-- --%>`, `<#-- -->`,
  `##`, `{# #}`) as `<!--/* ... */-->`. Whitespace between `t:case` siblings is output.

**Thymeleaf.** Run `tessera-migrate` first (section 3). Natural-template script comments
(`/*[[${x}]]*/ 'd'`) and quoted inlines (`"[[${x}]]"`) become `[[${x}]]`; move each value that
isn't then a declaration at the top of the script into one (section 4.4). Layout dialect pages
become fragment calls (section 4.8).

**JSP, JSTL and Spring form tags, with Tiles or SiteMesh.**

- `<form:form modelAttribute="x">` is `t:object="x"`; `<form:input>`, `<form:select>`,
  `<form:checkbox>`, `<form:radiobutton>` and `<form:textarea path="p">` are the HTML element with
  `t:field="*{p}"`; `<form:options>` is a `t:each` over `<option>`. `<form:errors path="p">` is
  `t:errors="*{p}"`, `path="*"` is `t:errors="*"`, and with no path `t:errors="*{global}"`. Don't
  hand-write the form tags' markup: what you miss (selected options, ids, markers) shows up only in
  compare.
- The form tags write things `t:` attributes don't: `<form:errors>` writes an `id` (`p.errors`,
  or `<form>.errors` for `*`), so write it statically; `<form:form>` without an `action` posts to
  the request URI with its query string, which omitting the action does too, while a
  `t:action="@{...}"` must pass the query parameters itself.

<!-- compile: template guide/jsp-form-defaults.html -->
```html
<form xmlns:t="https://tessera.dev" t:import="com.example.guide.*" t:params="Signup signup"
      t:object="signup" method="post">
  <span id="signup.errors" class="alert" t:errors="*">errors</span>
  <input type="text" t:field="*{email}" /> <span id="email.errors" t:errors="*{email}">error</span>
</form>
```

- Tiles definitions become page templates that call a layout fragment with the definition's
  attributes: `<tiles:insertAttribute>` is a `Fragment` parameter, `<tiles:getAsString>` a
  `String`. Inherited `extends` values and `cascade` attributes are passed explicitly by each page.
  A definition chosen at runtime becomes a choice between literal fragment calls or view names.
- `<jsp:include>`, `<%@ include %>` and tag files are fragments: tag attributes become typed
  parameters and `<jsp:doBody/>` a `Fragment` parameter. A tag file that binds
  `path="${field}.answer"` takes a `Field<T>` (section 4.8).
- Scriptlets and state changes during render (`<% %>`, `<jsp:useBean>`,
  `<c:set scope="session">`) move to the controller, a `@ModelAttribute` method or an interceptor.
  `<c:set var>` of a value is `t:with`; one that captures markup to repeat is a fragment.
- EL implicit objects (`param`, `sessionScope`, `header`, `pageContext.request.contextPath`) are
  model values; a context-path prefix is `@{...}`.

**FreeMarker.**

- Macros are typed fragments, `<#include>` a fragment call, `<#nested>` a `Fragment` parameter.
  Fragment parameters have no defaults: where a call relied on a macro's default, pass that value.
- `x!d` is `x ?: d`; `(a.b)!d` is `a?.b ?: d`; `${x!}` is `t:text="x"`; `x??` is `x != null`;
  `c?then(a, b)` is `c ? a : b`.
- `spring.ftl` macros take paths that start with the form object's name (`"signup.email"`): put
  `t:object="signup"` on the form and bind `*{email}`. `formInput` and its siblings are the element
  with `t:field` and their attribute argument written statically; `formSingleSelect` is a
  `<select t:field>` over `<option>`s; `spring.status.value`, `.errorMessages` and `.error` are
  `fields.value`, `fields.errors` and `fields.hasErrors`. `showErrors` wraps each message in a
  `<span>` with the separator between: loop over `fields.errors` where the markup matters.
- `<@spring.message>` is raw from Spring 5: a message with markup is `t:utext="#{key}"`.

**Velocity (and Velocity Tools).**

- `#foreach` is `t:each="x, st : xs"`: `$velocityCount` is `st.count`, `$foreach.index`
  `st.index`, `$foreach.hasNext` `!st.last`. `#set` is `t:with`. Macros and `#parse`d headers and
  footers become fragments and a layout. `$esc.html` goes, `$date`/`$number` become `temporals.*`,
  `numbers.*` or a function, and `$link` an `@{...}` link.
- `$!{x}` and `${x}` both print nothing for `null` in Tessera. A `null` attribute is omitted where
  `value="$!{x}"` wrote `value=""` (section 4.3).
- Velocity prints the source of a reference it can't evaluate, keeps the old value on
  `#set($a = $b)` with a null `$b`, and treats only `null` and `false` as false. If the pages depend
  on that, reproduce it on purpose in Java and check it with compare.
- Templates split across `#parse` files often leave tags unbalanced (the header opens a `<div>`
  the footer closes). Each Tessera template is well formed: open and close in one layout.

**Pebble.** `loop.index` is `st.index`, `loop.length` is `st.size`. `{% set %}` is `t:with`,
`{% macro %}` a typed fragment, `{% include %}` a fragment call, and `{% extends %}` with
`{% block %}`s a layout whose `Fragment` parameters are the blocks (an extends chain: section
4.8). Pebble reads public fields, `Map` keys with `.` and methods with arguments; Tessera reads
getters and record components only (`map['key']` works), so add getters or build a record.
Custom filters become `@TemplateFunctions` or values computed in Java. `strictVariables` is what
typed `t:params` already give you.

**Mustache.** JMustache fails at a missing value, cutting the page off mid-render; Tessera prints
nothing for `null`. Render the whole page, and compare against an original that has the value.

## 5. Known limitations

Gaps in Tessera today, with what to do instead.

- **Versions and build.** Spring 4.3+ / Boot 1.5+ on Servlet 3.0+; anything older is untested.
  Maven and Gradle, with no hot reload.
- **Messages.** `-Atessera.messages` reads bundles from the file system. Keys that only a jar or a
  database-backed `MessageSource` provides fail the build: give those pages a function that looks
  them up, unchecked.
- **View resolution.** Names match exactly, less one leading `/`. `page :: fragment` renders a
  fragment whose parameters a model can fill and whose root fits a response body; arguments and
  selectors in view names, and fragments with `Fragment` or `Field` parameters, fall through.
- **Mixed engines.** While the old engine is configured it renders every view Tessera doesn't
  know, so an app part-way through is protected only on the ported views.
- **Types.** `java.util.Date` and `Calendar` aren't view-safe: declare `Instant`, `ZonedDateTime`
  or `LocalDate`. `[[...]]` serializes only the properties of the static type and ignores Jackson
  annotations.
- **Client-side code.** Tessera guarantees what the browser's HTML parser does with the page. What
  page JavaScript or a client framework later does with the DOM, static markup and standard
  attribute values is outside that guarantee. Computed text is plain-escaped, so a `{{` in a value
  renders as `{{`: client frameworks that compile the server's DOM as a template (Vue in-DOM
  templates, petite-vue, AngularJS) are unsupported, and a strict CSP without `'unsafe-eval'` is
  the backstop. Pass data to scripts through `[[...]]` declarations or JSON blocks, use htmx 2,
  and don't mount a client template compiler over server-rendered user data.
- **Not supported, by design:** runtime template loading, `__${...}__` preprocessing, dynamic
  names, the layout dialect, `th:inline`, collection selection and projection, expressions in
  `<style>` and `on*`, and Kotlin.
- **Expected output differences:** attributes set by `t:` may move within the tag; a `null`
  attribute value omits the attribute; `<script>` and `<style>` get a CSP
  nonce. `compare` ignores these.
- **Runtime errors** are model type mismatches, missing primitive parameters, reading a property
  of `null`, `null` link path variables, `first`/`last` of an empty list and `ids.prev` before any
  input. Everything else is a build error.
- **Verifier errors** end with `[verifier:RULE]`. After its other checks, the compiler runs every
  template through a spec HTML parser and rejects markup that a browser reads only by error
  recovery (a second `<body>`, a `<div>` inside a `<p>`, a stray end tag) or where browsers build
  different trees. The message says what a browser would do and the smallest fix. For an
  expression inside a fragment, the error is at the call site and names the fragment. Each rule
  and its fix:

| Rule | What it means | Fix |
|---|---|---|
| `PARSE_ERROR` | The markup parses only by error recovery: a block in a `<p>`, a second `<body>`, content between `</head>` and `<body>`, content directly in a table, a stray end tag, a missing space between attributes, a literal `<` or `&` | Write the markup a browser reads without recovery: close the `<p>` first, write `&lt;` and `&amp;`, pass `~{::body}` with `t:remove="tag"` (4.8) |
| `UNBALANCED` | A `t:if`, `t:unless`, `t:case`, `t:each`, computed attribute or fragment leaves an element open or closed, or implies a `<tbody>`, `<head>` or `<body>` | Close inside the body what it opens; write the `<tbody>` around rows; make a fragment library's root a `<t:block>` (4.8) |
| `TEXT_IN_TABLE` | A value directly in a `<table>`, `<tbody>` or `<tr>`, where a browser moves it out of the table | Put it in a `<td>`, `<th>` or `<caption>` |
| `TEXT_OUTSIDE_BODY` | A value in `<head>` or after `</body>` | Move it into `<title>` or the body |
| `TEXT_IN_SCRIPT`, `JSON_NOT_IN_SCRIPT` | A value in a script or style position the compiler doesn't accept | A prelude declaration or a JSON block (4.4, Scripts); no expressions in `<style>` (4.4, CSS) |
| `TEXT_NOT_IN_TEXT` | A value inside a comment, a tag, or right after `&` or `<` | Move it into element text or a `t:` attribute; finish the character reference |
| `ATTRIBUTE_NOT_IN_VALUE`, `ATTRIBUTE_WRONG_NAME` | Static markup around a computed attribute changes which attribute it lands in (an unclosed quote, a duplicate) | Fix the static tag; one attribute per name |
| `RAW_NOT_FLOW`, `RAW_CLOSES_ELEMENT` | A `SafeHtml`, HTML message or `Fragment` where its markup would leave its place: in a table part, in a `<p>` or heading, inside a link (for a message with links), in a `<select>` | Inline-only HTML in a `<p>`, heading, link or button: a `SafeInlineHtml` (4.4, Inline HTML); otherwise a `<div>` or a cell; split the message (4.5) |
| `VALUE_IN_FOREIGN` | A value, condition, loop or fragment inside `<svg>` or `<math>` | Static SVG; set what changes outside it with a `t:class` or a CSS variable, one `<svg>` per case, or an `<img>` (4.4) |
| `CDATA_SECTION` | A `<![CDATA[` section | Write the text without it, with `&lt;` and `&amp;` |
| `VALUE_IN_RAW_TEXT` | A value inside `<noscript>`, `<iframe>`, `<xmp>`, `<noembed>` or `<noframes>` | Static content; move the value outside the element |
| `FOREIGN_IN_TABLE` | An `<svg>` or `<math>` inside a table or `<template>` | An `<img>` with the SVG file, or the SVG outside the table |
| `TABLE_IN_TEMPLATE` | A table part directly in `<template>` | Put the whole `<table>` in the `<template>` |
| `SELECT_CONTENT` | Anything but `<option>`, `<optgroup>` and `<hr>` in a `<select>` | Move it outside the `<select>` |
| `STRUCTURE_DEPENDS_ON_DATA` | A fragment that calls itself ever deeper (a recursive tree renderer) | A flat loop with depth classes, or `html.sanitize` to `SafeHtml` (4.4, Rich text) |
| `UNCALLED_FRAGMENT_FITS_NOWHERE` | Verifier-only: a fragment declared outside HTML content is the build error in the next row first | Declare it in HTML content |
| (build error) `t:fragment in <svg> content is not supported`, `~{::a} selects an element in <svg> content` | A `t:fragment`, or an element `~{::selector}` picks, inside `<svg>`, `<math>`, `<select>`, raw text (`<noscript>`, `<title>`, ...) or `t:remove="tag"` content | Declare the fragment outside it (directly under `<body>` or in its own file) and insert it there |
| `UNKNOWN_NODE`, `ENGINE_FAILURE` | A fragment the compiler can't resolve, static markup right after a computed attribute that isn't whitespace, `/` or `>`, or an internal failure | Fix the reference, or put a space after the attribute; report an `ENGINE_FAILURE` as a bug |
