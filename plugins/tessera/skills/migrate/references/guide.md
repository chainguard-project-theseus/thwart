# Porting guide: moving server-rendered Java views to Tessera

This guide is for a coding agent moving a Spring application's views (Thymeleaf, JSP, FreeMarker,
Mustache or similar) to Tessera, working from this document alone. It does not map your engine's
syntax construct by construct: you know your engine, and once you know how Tessera works the
syntax converts on its own. The hard parts are where Tessera's model differs (where data comes
from, how layouts and forms work) and knowing whether the result is right.

Read section 1 (the target) and section 2 (the build), then follow section 3 (the loop). Sections
4 to 7 are reference: syntax, a worked example, every compiler diagnostic with its fix, and known
limitations.

**Done** means: the build and all existing tests pass; `tessera-migrate compare` reports every page
in your `pages.txt` as `same` or `reordered`, or you can say why a remaining difference is right;
security is not weakened (no `SafeHtml.unchecked`/`TrustedUrl.unchecked` to get around escaping,
no CSRF or escaping turned off); and the old engine's templates, dependency and configuration are
gone.

Examples marked in the source of this file with `<!-- compile: ... -->` are compiled and rendered
by `dev.tessera.compiler.guide.GuideExamplesTest`, so they are known to work.

## 1. The target

Tessera templates are HTML files with `t:` attributes, close to Thymeleaf's `th:` syntax. An
annotation processor compiles them to Java classes during the application's normal `javac`, so a
template error is a build error with `file:line:col`. At runtime nothing parses or reads
templates. When the compiler rejects something, one of the rules below is the reason.

**A template sees only its typed parameters.** The first element declares them
(`t:params="Owner owner, List<Pet> pets"`), and with Spring each is read from the model attribute
of the same name. Nothing else is visible: no request, session, application or bean scope, no
request parameters, cookies, headers or path variables. Engines you are leaving often fall back to
those scopes when a name is not in the model. Tessera doesn't: **a missing model attribute is
`null`**, which prints nothing, and reading a property of it fails the render with a
`NullPointerException` (write `a?.b` where `null` is expected). So before converting a template,
find everything it reads that the controller does not put in the model, and make the controller
provide it. Values every page needs (the current user, the request URI) come from `@ModelAttribute`
methods in a `@ControllerAdvice`.

**Templates read data; they don't run code.** A template reads record components and
`getX()`/`isX()` getters of *view-safe* types (records, enums, JDK value types, collections, and
classes annotated `@TemplateView`), plus built-ins such as `size()`. It cannot call other methods,
construct objects or reach static members. Logic lives in Java: a getter, a record the controller
builds, or a static `@TemplateFunction`.

**The output context picks the escaper.** Text, attribute values, URLs and `<script>` blocks are
each escaped for where they appear, with no switch to turn it off. Raw markup must be a `SafeHtml`
built in Java. Expressions in `on*` attributes are rejected, and a `style` attribute takes only
literals, numbers and validated `css.*` values. Values that the old engine
printed raw (JSP EL, FreeMarker without auto-escaping, Mustache `{{{x}}}`) now come out escaped.
That is intended, not a difference to undo.

**Forms bind through Spring's `BindingResult`.** `t:object="owner"` names the form-backing model
attribute, and `t:field`, `t:errors` and `fields.*` read its `BindingResult`, as Spring's form tags
and macros do: formatters and `@DateTimeFormat` apply, and a failed bind echoes the rejected value.

**Fragments see only their parameters.** A fragment (`t:fragment="card(String title, Fragment
body)"`) is a typed function that cannot see the caller's variables. Markup is passed as a
`Fragment`. Includes, macros with bodies, tag files, partials and layouts all become fragment
calls.

**Names are literals.** View names, fragment references, message keys and link paths are literals
checked at build time. Choose between literals with a conditional.

**Values print with `String.valueOf`**, with no locale-aware formatting: use `numbers.*`,
`temporals.*` or your own function. `null` text renders empty and a `null` attribute is omitted,
but inside `|...|` and `+` a `null` prints `null`.

### Things Tessera deliberately doesn't do, and what to do instead

| If the old template... | Do this instead |
|---|---|
| reads the request, session, application scope, cookies, headers or path variables, or falls back to them | Put the value in the model and declare it in `t:params`. Nothing warns you when you miss one. |
| calls a method with arguments, a static method, a bean or a constructor | A getter, a record built by the controller, or a `@TemplateFunction`. |
| filters, sorts, groups or projects a collection | Do it in the controller. |
| assigns a variable | `t:with="x=..."`: an immutable local for one element. |
| builds a view name, fragment name, message key or link path from strings | Conditionals between literals; link `{placeholders}`; a `Fragment` argument. |
| evaluates a string as a template (`?eval`, `#evaluate`, `__${...}__`) | Not possible. Compute the value in Java. |
| prints a string unescaped | Stored rich text: `t:utext="html.sanitize(article.text)"` (section 4.4). Markup built in Java: a `SafeHtml`. |
| puts expressions in `style` or `on*` | `t:style` with literals, numbers and `css.color(...)`/`css.length(...)`/`css.url(...)` (section 4.4); otherwise `t:classappend`; `t:data-*` attributes read by a script. |
| includes a file, calls a macro or tag with a body, or extends a layout | A fragment call with `Fragment` arguments (section 4.8). |
| binds a form through form tags or macros | `t:object`, `t:field`, `t:errors`, `fields.*` (section 4.7). |
| posts a form back to the current URL by default | Write the `action` with `@{...}`, or put the URL in the model. |
| reads an `Exception`, a `Date` or another non-view-safe value | Put what the page shows (a message `String`, a `LocalDate`) in the model. |

## 2. Build setup

The build needs JDK 8+ and Maven, and the application runs on Java 8+. The annotation processor
(`tessera-compiler`) runs inside the application's javac, and it, `tessera-runtime` and the Spring
starter are Java 8 class files; the generated template code compiles with `release` 8. On Java 8
there are no records, so give the model classes `getX()` getters and `@TemplateView`. JDK 8's
javac has no `--release`: there, set `<source>` and `<target>` 1.8 instead of `<release>` below.

The Spring integration supports **Spring Framework 4.3+ and Spring Boot 1.5+**, on Servlet 3.0+
(Tomcat 7+). The test suite runs on Boot 1.5, 2.0, 2.7, 3.5 and 4.1 and on plain Spring MVC 4.3
and 5.0. Pick the artifact by servlet API and use exactly one of them; they contain the same
classes:

| Application | Artifact |
|---|---|
| `jakarta.servlet` (Boot 3+, Spring 6+) | `tessera-spring-boot-starter` |
| `javax.servlet` (Boot 1.5-2.x, Spring 4.3-5.x) | `tessera-spring-boot-starter-javax` (the same jar, relocated to `javax.servlet`) |

The starter brings only `tessera-runtime`. Spring, Boot, the servlet API and Spring Security come
from the application. Below Spring 4.3 nothing is tested: wiring the classes by hand is at your
own risk. Don't upgrade Spring as part of the migration: an application on Boot 1.5 or Spring 4.3
keeps its version.

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
    <release>21</release>
    <parameters>true</parameters>
    <annotationProcessorPaths>
      <path>
        <groupId>dev.tessera</groupId>
        <artifactId>tessera-compiler</artifactId>
        <version>${tessera.version}</version>
      </path>
      <!-- and every other processor the build uses -->
    </annotationProcessorPaths>
    <compilerArgs combine.children="append">
      <arg>-Atessera.templates=${project.basedir}/src/main/resources/templates</arg>
      <arg>-Atessera.package=com.example.templates</arg>
      <arg>-Atessera.messages=${project.basedir}/src/main/resources/messages</arg>
    </compilerArgs>
  </configuration>
</plugin>
```

`tessera.templates` (required) is the absolute templates root; every `**/*.html` below it is a
template. `tessera.package` is the generated package (default `tessera.generated`).
`tessera.messages` is the message bundle's path without `.properties`; with it, every `#{key}` is
checked at build time. Facts about the build today:

- `annotationProcessorPaths` needs `maven-compiler-plugin` 3.5+ (`<parameters>` needs 3.6.2+).
  Older parents pin older versions (Boot 1.5: 3.1); override it, for example with the property
  `<maven-compiler-plugin.version>3.8.1</maven-compiler-plugin.version>` under a Boot parent, or
  a `<version>` on the plugin.
- `annotationProcessorPaths` turns off processor discovery, so list every processor the build
  relied on (Lombok, MapStruct, ...) next to Tessera. Templates see the getters Lombok generates
  (`@Getter`, `@Data`, `@Value`) on `@TemplateView` classes, with Lombok 1.16 and 1.18 alike, so
  there is no need to write them by hand. List Lombok before Tessera: property reads work in
  either order, but a Lombok bean serialized as JSON in a `<script>` needs Lombok first, and the
  build fails and says so otherwise. A property that is really missing is still a build error at
  its template position.
- The processor also runs in test compilation and regenerates the templates there. It finds
  `@TemplateFunctions` classes compiled earlier (the main classes, or an incremental compile that
  skips them) through the index it writes to `META-INF/tessera/functions/`, so no `<proc>none</proc>`
  is needed. Maven doesn't recompile when only a template changes: use `mvn clean compile`.
- Templates under `src/main/resources` are copied into the jar as resources, so resource
  filtering rewrites `${...}` in them unless `templates/**` is excluded.

**Spring Boot.** The starter's auto-configuration registers the view resolver, the `Template`
return-value handler and the template registry. Tessera's resolver runs before Thymeleaf's and
falls through for names it does not know, so the old engine can stay while you migrate. The jar
registers itself for every Boot generation, including `@WebMvcTest` slices.

**Spring MVC without Boot.** Import `dev.tessera.spring.TesseraConfiguration`. It needs only
`spring-webmvc`:

```java
@Configuration
@EnableWebMvc
@Import(TesseraConfiguration.class)
public class WebConfig {}
```

In XML, declare it as a bean next to `<context:annotation-config/>` and `<mvc:annotation-driven/>`:
`<bean class="dev.tessera.spring.TesseraConfiguration"/>`. It registers the same beans as the
auto-configuration, plus a resolver for `redirect:` and `forward:` names. Boot provides that
resolver; without Boot the old engine's resolver usually did, so removing it without a replacement
breaks redirects (behind a `ContentNegotiatingViewResolver`, as a 406).

**Resource links.** With the resource chain enabled, the starter registers
`ResourceUrlEncodingFilter` for REQUEST and ERROR dispatches, as Boot's Thymeleaf auto-configuration
did, so `@{/webjars/...}` and other resource links stay versioned on normal and error pages. Don't
declare the filter yourself; remove a hand-written one. Without Boot, map the filter in `web.xml`
or a `WebApplicationInitializer` as the old application did.

**Older Boot.** Checked on Boot 2.0 (Spring 5.0, Security 5.0) and Boot 1.5 (Spring 4.3,
Security 4.2):

- The Boot 2.x parent's `maven-compiler-plugin` (3.7.0 in Boot 2.0) supports
  `annotationProcessorPaths`, and its `-source`/`-target 1.8` build Java 8 class files. Nothing
  to change.
- The Boot 1.5 parent pins `maven-compiler-plugin` 3.1 and defaults `java.version` to 1.6. Set
  the properties `<java.version>1.8</java.version>` and
  `<maven-compiler-plugin.version>3.8.1</maven-compiler-plugin.version>`.
  The starter's `spring.factories` registers the auto-configuration and the `@WebMvcTest` slice
  for Boot 1.5 too.
- Boot 2.0's default security (CSRF on, token in the session) works unchanged: a
  `<form method="post">` gets the `_csrf` input.
- Boot 1.5's default security (`spring-boot-starter-security` and no security configuration of
  your own) is HTTP Basic with CSRF **off** (`security.enable-csrf=false`): no `_csrf` request
  attribute, so a POST form gets no `_csrf` input, as with Thymeleaf. With your own
  `WebSecurityConfigurerAdapter`, Security 4.2 turns CSRF on (token in the session) and the input
  renders. Either way, keep the application's setting; `auth.*` works in both.
- Spring 4.3 and 5.0 need `--add-opens java.base/java.lang=ALL-UNNAMED` on JDK 16+, including
  for tests (surefire `argLine`). Spring 5.3 (Boot 2.7) does not.

**Error pages.** Under Boot, `templates/error.html` renders errors with Boot's error attributes as
the model (`Integer status`, `String error`, `String message`, `String path`; `timestamp` is a
`Date` and not view-safe).

## 3. The migration loop

The old application is the specification. Record it, then convert and check one template at a
time.

**1. List every page.** Write a `pages.txt` that reaches every view in every state that renders
differently: each form empty, with validation errors and after success; logged out and logged in
as each role; empty and full lists; error pages.

```text
# One request per line, run in order with one cookie jar.
GET /owners/new
POST /owners/new firstName=&lastName=
POST /owners/new firstName=Ann&lastName=Lee&city=Paris&telephone=0123456789
GET /owners/1
POST /login username=admin&password=admin
```

A `POST` sends the `_csrf` hidden input of the previous page, so `GET` a form before posting it.
Write paths and form values raw or percent-encoded: `GET /tags/new<i>` requests
`/tags/new%3Ci%3E`, as a browser would. A space ends the path, so write it `%20` there. In a form,
`&` separates fields and `+` is a space, so a value holding them needs `%26` and `%2B`.
Start the app from the same data for every run (fixed seed data in an in-memory database, for
example), and let the list create the data it needs.

**2. Record the old app** before changing anything:

```sh
MIGRATE=~/.m2/repository/dev/tessera/tessera-migrate/<version>/tessera-migrate-<version>.jar
java -jar $MIGRATE record http://localhost:8080 pages.txt baseline/
```

This saves each response in `baseline/` (the body as `NN.html`; status, final URL and request in
`index.tsv`). Today's date is masked when comparing, but a page that shows a random value, the
time or the host name will differ on every run: fix the data, drop the request, or pass
`compare --ignore REGEX` (repeatable) to delete that text from both pages. Keep the pattern
narrow, because whatever it matches is never compared. `compare`
refuses a `pages.txt` that doesn't match the recording, so if you change it, record again.

**3. Set up the build** (section 2). Add `@TemplateView` to the classes templates read, or make
them records.

**4. Convert one template at a time.** Shared fragments and layouts first, since pages depend on
them. For each template: find every value it reads and make sure the model provides it; declare
the parameters with their real types; rewrite the markup (section 4); run `mvn clean compile` and
fix each error with the catalogue (section 6).

For Thymeleaf, `tessera-migrate` does the mechanical rewrite of a whole directory first:
`java -jar $MIGRATE <old-templates-dir> <new-templates-dir> --report migrate-report.md`. It
converts `th:` attributes, utility objects and common string-built links, stubs `t:params` and
fragment parameter types as `Object` (which fails to compile until you write the real types), and
lists what it could not convert by `file:line`.

**5. Compare** against the new app, started from the same data:

```sh
java -jar $MIGRATE compare http://localhost:8080 pages.txt baseline/
```

It compares the status, the final URL after redirects, and the page as a browser would build it,
not the bytes: whitespace runs, attribute and class order, optional end tags, comments and CSRF
tokens don't count. Each page prints one line, `NN same|reordered|differs REQUEST`. `reordered`
means the same elements with siblings in another order; it passes, but its diff is printed so you
can check that what moved is harmless (validation messages from a `HashSet`, yes; table rows that
lost their sort order, no). A `differs` page lists a changed status or final URL and a unified
diff of the normalized page. It exits 0 only if every page is `same` or `reordered` with its
recorded status and final URL. For each difference, fix it, or decide it is right and write down
why. Few are right: output now escaped where the old engine printed it raw, or broken
markup the old engine passed through. An empty value, or a 500 from a `NullPointerException`, is
nearly always a value the old template got from outside the model. A 302 that became 406 or 500 is
view-resolver wiring (section 2).

Compare after each template or small group, not only at the end, and run the tests. Controller
tests that use `standaloneSetup` never render a view, so add one that renders through the real
resolvers and checks that `<script>` in user data comes out escaped.

**6. Remove the old engine**: its templates, dependency and configuration. Build, test and compare
once more.

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

- The namespace and header attributes (first element only) are removed from the output.
- `t:params` is `Type name, ...` with Java types; generics, arrays and nested classes
  (`Outer.Inner`) work. A primitive parameter must be present in the model.
- `t:import` lists classes or packages (`com.example.*`). `java.lang`, `java.util`, `java.time`,
  `java.math` and `dev.tessera.runtime` are always imported.
- `guide/hello.html` compiles to `com.example.templates.guide.HelloTemplate` with
  `of(Customer, List<Order>, boolean)`.
- Static attribute values and text are prototypes that `t:` attributes replace. `<t:block>`
  renders only its content. HTML comments are copied to the output.

### 4.2 View types

Templates may read only **view-safe** types, checked at build time for every step of a property
chain.

**View-safe:** primitives and their boxes, `String`/`CharSequence`, enums, records, `java.time`
values, `BigDecimal`, `BigInteger`, `Optional`, `Iterable`, `Collection`, `List`, `Set`, `Map`,
`Map.Entry` and arrays of view-safe types, `LoopStatus`, `SafeHtml`, `TrustedUrl`, and classes
annotated `@TemplateView`. `Fragment` and `Field` are allowed as fragment parameters.

**Never view-safe**, even if annotated: `Object` and its methods, `Class`, reflection, threads,
processes, `System`, files, scripting, servlet types, and Spring context, bean, environment and
expression types.

On a view-safe type a template can read:

- record components: `owner.name` calls `owner.name()`;
- public no-argument getters: `owner.name`, `owner.name()` and `owner.getName()` all call
  `getName()`; `isX()` for booleans (`new` is a keyword: write `pet.isNew()`);
- built-ins: `size()`/`isEmpty()` on collections and maps, `length` on arrays, `length()` and
  `isEmpty()` on strings, `first`/`last` on lists;
- index access: `list[0]`, `array[i]`, `map['key']`.

Nothing else. For entities and other classes, add `@TemplateView`; getters inherited from an
unannotated superclass (a JPA `@MappedSuperclass`) resolve through the annotated class:

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
  public void deactivate() { } // not a getter: templates cannot call it
}
```

### 4.3 Expressions and attributes

Expressions and attributes are Thymeleaf's Standard dialect with `t:` for `th:`, minus everything
that runs code. What differs:

- `${...}` is optional (`t:text="owner.name"`). `*{...}` works only inside `t:object`, for one
  property path. Utility objects are function namespaces (section 4.6); `#ctx`, `#request`,
  `#dates` and the rest don't exist.
- Message keys, link paths and fragment references are literals. Not supported: `T(...)`, `new`,
  `@bean`, `__...__`, assignment, lambdas, `.?[...]`/`.![...]`, `div`/`mod` (use `/`, `%`).
- `==` is `Objects.equals`; an enum compared with a string literal compares the constant name, and
  the constant must exist. `c ? a` without else is `null`. Both sides of `?:` and of `c ? a : b`
  need compatible types.
- `|...|` and `+` are Java string concatenation, so a `null` operand prints `null`. To print
  nothing for a null non-String, as EL does, write `x == null ? '' : '' + x` (`x ?: ''` works only
  when `x` is a String).
- Attributes on one element run in Thymeleaf's order: `t:fragment` → `t:insert`/`t:replace` →
  `t:each` → `t:if`/`t:unless`/`t:switch`/`t:case` → `t:object`/`t:with` → other attributes →
  `t:text`/`t:utext`/`t:errors` → `t:remove`. So `t:if` does not guard a `t:replace` on the same
  element: put it on an enclosing element or a `<t:block>` (Thymeleaf's `th:block`).
- `t:each` iterates an Iterable, array or Map (`e.key`, `e.value`); status `index`, `count`,
  `size`, `first`, `last`, `even`, `odd`, implicit name `xStat`. `t:with` locals are immutable.
  `t:remove` takes a literal (`all`, `body`, `tag`, `all-but-first`, `none`).
- Any `t:<name>` sets that attribute; `null` omits it, and boolean attributes (`t:checked`,
  `t:disabled`, ...) render `name="name"` or nothing. `t:utext` and `[(...)]` need a `SafeHtml`.
- Not supported: `th:attrappend`/`attrprepend`, `th:alt-title`, `th:lang-xmllang`, `th:assert`,
  `th:include` (use `t:insert`) and `th:inline` (in a `<script>`, `[[...]]` writes JSON).

### 4.4 Escaping and output contexts

The compiler decides the context from the element and attribute name. You cannot override it.

| Context | Where | What happens |
|---|---|---|
| Text | element text, `<title>`, `<textarea>`, `t:text`, `[[...]]` | `& < > " '` escaped. |
| Raw HTML | `t:utext`, `[(...)]` | Only `SafeHtml` compiles. |
| Attribute | any other attribute value | Escaped, always double-quoted. |
| URL | `href`, `src`, `action`, `formaction`, `cite`, `poster`, ... | Only `http`, `https`, `mailto`, `tel` and relative URLs pass; anything else becomes `about:invalid#tessera`. `TrustedUrl` passes as is. |
| Resource URL | `script[src]`, `iframe[src]`, `embed[src]`, `object[data]`, `base[href]`, `link[href]` | Only a literal-path `@{...}` link or a `TrustedUrl` compiles. |
| Script | text in `<script>` (no type, JavaScript, module or JSON) | `[[...]]` writes JSON (strings, numbers, booleans, enums, `java.time`, records and `@TemplateView` beans, collections, String-keyed maps). |
| Style | `<style>` text and `style` attribute | In `<style>`, expressions are a build error. A `style` attribute takes only string literals, numbers and `CssValue`s, combined with `+`, `\|...\|`, `?:` and `? :`; anything else (a `String` value) is a build error. |
| Event handler | `on*` attributes, and framework directives (below) | Build error; put data in `data-*` attributes and read them from a script. |
| Forbidden | `srcdoc`, `content` of `<meta http-equiv="refresh">`, non-JS `<script type>` | Build error. `<script type>` and `<meta http-equiv>` cannot be computed. |

Client-side frameworks run some attribute values as JavaScript once they mount the element, so
attribute escaping does not protect them and an expression there is a build error, as for `on*`:
Vue's `v-*` (every directive, `v-html` included) and its `:x`, `@x`, `#x` and `.x` shorthands;
Alpine's `x-*`; htmx's `hx-on*`, `hx-vals`, `hx-vars`, `hx-headers`, `hx-request` and `hx-trigger`
(also with `data-`); AngularJS's `ng-*`, `ng:*` and `ng_*` (also with `data-` or `x-`); and
Knockout's `data-bind` and `params` on a custom element. Static directives are copied verbatim, shorthand names included: write
`<api-entry :security="security" @click="open">` as in the old page. To hand server data to a
component, put it in a `data-*` attribute or a `<script>` with `[[${...}]]` and read it from
there; a Pebble or JSP `:prop='{{ json | raw }}'` becomes a `[[${object}]]` in a script. Other
`data-*` attributes and htmx attributes that hold URLs, selectors or text (`hx-get`, `hx-target`,
`hx-swap`, `hx-confirm`) are ordinary attributes.

In a script, a record or `@TemplateView` bean becomes a JSON object of the properties a template
can read, as Thymeleaf's Jackson output does: `var chart = [[${chart}]];` works unchanged. Record
components keep their order; a bean's properties come in field order, then getter-only ones, named
like Jackson (`getURL()` is `url`). Getters returning a never-view-safe type are left out, and any
other property that cannot be serialized (a `SafeHtml`, a class that isn't view-safe) is a build
error. Jackson annotations (`@JsonIgnore`, `@JsonProperty`) are not read. Nesting deeper than 64
levels, or a cycle, fails the render. Where the Thymeleaf page parsed a JSON string built in Java
(`JSON.parse([[${json}]])`), pass the object instead and write `[[${object}]]`. Each property is
serialized before the next getter runs; getters should not mutate previously returned values.
The complete JSON value is buffered before output, so a serialization failure writes none of it.

Trusted markup is a `SafeHtml`. For stored HTML (articles, bios, rich-text pages) that the old
page printed with `th:utext` or `${...}` in JSP, write `t:utext="html.sanitize(article.text)"`, or
call `SafeHtml.sanitize(text)` in Java. It keeps `p br hr h1`-`h6 b i em strong u s sub sup small
mark code pre blockquote ul ol li span div` without attributes, and `a` with an `href` that passes
the URL check above, plus `rel="nofollow noopener"`. It removes `script`, `style`, `iframe`, `svg`,
`math`, `object` and similar elements with their content, every other tag (but not its text), comments
and all other attributes (`on*`, `style`, `class`, `id`, `target`, ...). The policy is fixed. In
Java, `SafeHtml.escape(text)` escapes text, `SafeHtml.of(template, ctx)` wraps another template's
output, and `SafeHtml.concat(a, b, ...)` joins parts (`concat(escape(a), sanitize("<br>"),
escape(b))`). `SafeHtml.unchecked(html)` is a review point, and `TrustedUrl.unchecked(url)` is the
same for URLs.

CSS has no escaping that makes an arbitrary string safe, so a `style` attribute is built only from
pieces that cannot end a declaration or call an unchecked `url(...)`: literals, numbers and `CssValue`s.
`css.color(s)` and `css.length(s)` (or `CssValue.color`/`CssValue.length` in Java) validate a
string and return `null` for anything else, so user data such as a category colour goes through
them: `t:style="|background-color: ${css.color(category.color)}|"`. An invalid colour prints
`background-color: null`, which the browser ignores as an invalid declaration; a `t:style` that is
only a `null` value is omitted. Choices between literals work as before:
`t:style="${soldOut} ? 'opacity:.5' : 'opacity:1'"`. `rgb(...)`, `var(...)` and other functions are
not accepted; compute a hex colour in Java or use a class. For a background image such as an
uploaded avatar, `css.url(s)` (`CssValue.url`) writes `url("...")`:
`t:style="|background: ${css.url(user.avatar)} no-repeat center|"`. The URL must be relative,
protocol-relative (`//cdn/a.png`) or `http`/`https`; `data:`, `javascript:`, every other scheme,
control characters (newlines included) and URLs over 4096 characters give `null`. Quotes,
backslashes, parentheses, `*`, spaces, a stray `%` and non-ASCII are percent-encoded, so the value
cannot leave the quoted URL.

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

### 4.5 Messages and links

**Messages.** `#{key}` and `#{key(arg1, arg2)}` render through the application's messages (Spring
`MessageSource` with the starter; `MessageFormat` placeholders `{0}`). The key is a literal; for a
computed choice, use a conditional: `active ? #{status.active} : #{status.inactive}`. A message is
text and is escaped. A missing key renders `??key_locale??` at runtime; with `-Atessera.messages`
it is a build error instead.

**Links** are Thymeleaf's: `@{/owners/{id}(id=owner.id, q=term)}` fills `{id}` (percent-encoded)
and turns other parameters into a query string; `@{{id}/edit(id=x)}` is page-relative; absolute
links must be `http`/`https`. Links get the context path and `response.encodeURL`. As in
Thymeleaf, a `null` query value renders `name=`, a parameter with no value renders the bare name
(`@{/cart(add, itemId=i.id)}` is `/cart?add&itemId=...`), and a collection repeats the parameter
per element (`?t=a&t=b`; a `null` element is the bare `t`). A `null` path variable fails the
render. A URL computed in Java goes in `t:href` as a `String` (scheme-checked)
or `TrustedUrl`, without the context path.

### 4.6 Functions

Templates call functions as `namespace.fn(args)` or `#namespace.fn(args)`.

| Namespace | Functions |
|---|---|
| `strings` | `isEmpty`, `isBlank`, `defaultString(s, def)`, `abbreviate(s, max)`, `toUpperCase`, `toLowerCase`, `capitalize`, `trim`, `contains(s, part)`, `startsWith`, `endsWith`, `substring(s, start[, end])`, `replace(s, before, after)`, `join(iterable, sep)` |
| `numbers` | `formatInteger(n, minDigits)`, `formatDecimal(n, minIntDigits, decimals)` (no grouping, `.` as decimal point), `sequence(from, to)` (a `List<Integer>`) |
| `temporals` | `format(temporal, pattern)` (English month and day names), `formatIso(temporal)` |
| `lists` | `size`, `isEmpty`, `contains(list, x)` |
| `css` | `color(s)` (`#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`, a CSS named colour), `length(s)` (a number with optional `px`, `em`, `rem`, `%`, `vh`, `vw`), `url(s)` (`url("...")` for a relative or `http`/`https` URL, percent-encoded): a `CssValue`, or `null` for anything else |
| `html` | `sanitize(s)`: stored rich text as `SafeHtml` for `t:utext` (section 4.4) |
| `auth` (Spring starter) | `isAuthenticated()`, `hasRole('ADMIN')`, `hasAnyRole('A', 'B')`, `name()` |
| `fields` (inside `t:object`) | `hasErrors('path')`, `hasErrors('*')`, `errors('path')`, section 4.7 |

All built-ins are null-tolerant. Add your own namespace with a class in your application:

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

The class is public; each method is `public static`, annotated `@TemplateFunction`, and returns a
value whose type is not never-view-safe (`Object` is allowed for parameters). The class must be in
the same `javac` run as the templates (section 2). `fields` is reserved.

### 4.7 Forms

`t:object` names the form object: a variable whose name is the model attribute that Spring binds.

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

- `t:field` (`email` or `*{email}`, checked against the `t:object` type) works on `<input>`,
  `<select>` and `<textarea>`. It writes `id` (unless present; not on radios), `name` and the
  `BindingResult`'s display value (omitted when null; never on passwords). Checkboxes and radios
  get `checked`, options of a `<select t:field>` get `selected` when their value matches (none
  when the bound value is `null`, so a placeholder `<option value="">` stays unselected). A static
  `selected` on such an option is replaced, as in Thymeleaf; a `t:selected` is kept.
- `t:errors` renders the field's messages escaped and joined by `<br />`, or removes the element
  if there are none (`*` for all). For other markup, loop over `fields.errors('email')`.
- `t:errorclass="error"` next to `t:field` appends the class when the field has errors.
- `fields.*` arguments are string literal paths (or a field expression, below). A
  `<form method="post">` gets the CSRF input automatically when Spring Security provides a token,
  and so does a form whose computed method (`t:method`, `t:attr="method=..."`) is `post` (any
  case) at render time, as in Thymeleaf. Computing the method does not opt a form out of the token.
  The method must be exactly `post` as a browser reads it: `method=" post"` submits as GET and gets
  no token, so the token never ends up in a URL.
- **Lists of beans.** A path may index a `List` or an array with an int literal or an int
  variable: `*{items[0].name}`, `*{items[itemStat.index].name}`, `*{items[${i}].name}`. This
  replaces Thymeleaf's `*{items[__${itemStat.index}__].name}` and FreeMarker's
  `"cart.items[${index}].name"`. The element type is checked, `name` is Spring's `items[1].name`
  and `id` is `items1.name`, and values and errors come from the `BindingResult` for that path, so
  `@DateTimeFormat` and converters apply. In `fields.*` write a literal `'items[0].name'` or, for a
  variable index, the path itself: `fields.hasErrors(*{items[itemStat.index].name})`. Map keys
  (`map[key]`) are not supported.

<!-- compile: template guide/cart.html -->
```html
<form xmlns:t="https://tessera.dev" t:import="com.example.guide.*" t:params="Cart cart"
      t:object="cart" t:action="@{/cart}" method="post">
  <div t:each="line : cart.lines" t:classappend="fields.hasErrors(*{lines[lineStat.index].qty}) ? 'has-error'">
    <input type="text" t:field="*{lines[lineStat.index].product}" />
    <input type="number" t:field="*{lines[lineStat.index].qty}" />
    <span t:errors="*{lines[lineStat.index].qty}">Invalid quantity</span>
  </div>
</form>
```

<!-- compile: java -->
```java
package com.example.guide;

import java.util.List;

public record Cart(List<CartLine> lines) {}
```

<!-- compile: java -->
```java
package com.example.guide;

public record CartLine(String product, int qty) {}
```

### 4.8 Fragments and layouts

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
  `Fragment` value. Paths are relative to the templates root, without `.html`.
- Arguments are positional or all named, and type-checked. A `String` argument can be a link
  `@{...}` or a message.
- `~{::name}` is a `Fragment` over the same-file fragment `name`, or else the first element with
  tag `name` (`~{::dl}`), rendered with the call site's variables. Put it inside the host so it
  does not also render in place. Other selectors (`#id`, `.class`) are not supported.
- A `null` `Fragment` renders nothing, so a `Fragment` parameter can be optional.
- A parameterized fragment is skipped when its template renders as a page; one without parameters
  renders in place. Fragments may call themselves.

**Layouts.** The layout is a fragment with `Fragment` parameters. Each page replaces itself with
the layout and passes its `<body>`, marked `t:remove="tag"` so that only the body's children are
inserted (without it you get a `<body>` inside the layout's `<body>`):

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

This replaces decorator layouts (`layout:decorate`, Mustache parents, JSP layout tags). What a
decorator merged, the layout takes as parameters: the title (and its pattern), page-specific
`<head>` content as a `Fragment` from a same-file fragment or `null`, the active menu entry.

**Reusable form fields.** A `Field` parameter takes a field path that the compiler checks against
the caller's `t:object`:

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

The argument is a string literal (or a `Field` passed on) inside `t:object`. The form attributes
and `fields.*` accept it; printing it writes the path. The fragment cannot read the field's value:
pass it as another argument if needed.

### 4.9 Rendering

With Spring (Boot's auto-configuration, or `TesseraConfiguration` without Boot), `return
"owners/list";` renders `templates/owners/list.html` from the model. The name
must match exactly; other names fall through to other resolvers. A model value of the wrong type
fails with `TemplateModelException`. A controller can also return `ListTemplate.of(owners)`, checked
by `javac`. Without Spring, call `FunctionsTemplate.of(order).renderToString(ctx)` with a
`RenderContext(locale, messages, links, forms)`, the whole ambient surface (`RenderContext.defaults()`,
`ResourceBundleMessages`, `DefaultLinks(contextPath)`). `TemplateRegistry.load(classLoader)` finds
templates by view name.

## 5. Worked example

The product list and home page of Thymeleaf's *Good Thymes Virtual Grocery* (gtvg), through the
loop. `pages.txt` is `GET /product/list` and `GET /`, recorded from the running old app.

gtvg's `Product` and `Comment` are classes with getters, which get `@TemplateView` (records here,
for brevity):

<!-- compile: java -->
```java
package com.example.gtvg;

import java.math.BigDecimal;
import java.util.List;

public record Product(Integer id, String name, BigDecimal price, boolean inStock, List<Comment> comments) {}
```

<!-- compile: java -->
```java
package com.example.gtvg;

public record Comment(String text) {}
```

The controller puts `prods` in the model, so the template declares `List<Product> prods`. The rest
is `th:` to `t:`, dropping `${...}`, and `#lists.size(x)`/`#lists.isEmpty(x)` becoming
`x.size()`/`x.isEmpty()`. (Without the annotation, `mvn compile` reports
`parameter type java.util.List<com.example.gtvg.Product> is not view-safe: ...` at `t:params`.)

<!-- compile: template product/list.html -->
```html
<!DOCTYPE html>
<html xmlns:t="https://tessera.dev" t:import="com.example.gtvg.Product" t:params="List<Product> prods">
  <head>
    <link rel="stylesheet" type="text/css" media="all"
          href="../../../css/gtvg.css" t:href="@{/css/gtvg.css}" />
  </head>
  <body>
    <table>
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

The home page has `th:utext="#{home.welcome(${session.user.name})}"`. A message is text, so it
becomes `t:text="#{home.welcome(user.name)}"` with `User user` in `t:params`, and it compiles.
`compare` then reports:

```text
01 same      GET /product/list
02 differs   GET /
   status: recorded 200, got 500
--- recorded 02.html
+++ now
...
```

The log shows a `NullPointerException` reading `user.name`: the old page read `user` from the
session, and nothing puts it in the model. A `@ModelAttribute("user")` method that returns the
session's user fixes it, and both pages are `same`. Then Thymeleaf and the old templates go, and a
last build, test and compare confirm it.

## 6. Diagnostic catalogue

Every compiler diagnostic, with its fix. Diagnostics print as
`templates/path.html:line:col: error: message`, with the position of the attribute, expression or
element at fault. In the messages below, `<angle brackets>` stand for the parts that vary. Every
template reports its errors in the same build, but any error fails the javac run, so no template
classes are generated until all are fixed.

### Build configuration

These come from the annotation processor itself, without a template position.

- `Tessera: the annotation processor needs -Atessera.templates=<absolute templates dir>, e.g. ${project.basedir}/src/main/resources/templates`:
  add the `-Atessera.templates` compiler argument (section 2).
- `Tessera: templates directory does not exist: <path>`: fix the path; use
  `${project.basedir}/...`.
- `Tessera: message bundle does not exist: <file> (-Atessera.messages is the bundle base path without .properties)`:
  point the option at the bundle without the `.properties` suffix.
- `Tessera: <file> was generated before another annotation processor (Lombok?) finished adding members to the classes its templates read; list that processor before Tessera in annotationProcessorPaths`:
  move Lombok ahead of Tessera in `annotationProcessorPaths` (section 2), then `mvn clean compile`.
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
  inlined text; see section 4.4.
- `duplicate parameter '<name>': rename or remove one of them`: in `t:params` or a fragment
  signature.
- `parameter '<name>' has type Field, which only fragment parameters may have: declare it in a t:fragment signature`:
  `Field` is for fragment parameters; a page uses `t:object` instead.
- `t:params needs a value, e.g. t:params="String name, int count"` and
  `t:import needs a value, e.g. t:import="java.util.List"`: a bare `t:params` or `t:import` on
  the root element; declare the parameters or imports, or remove the attribute.
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
  conditional. Only `__` outside a literal, or `__` directly followed by `${`, `*{`, `#{`, `@{` or
  `~{` and closed by a later `__`, is rejected: `__` elsewhere inside a `'...'` or `|...|` literal
  is plain text, so BEM class names such as `'card__item card__item--' + s` work as written
  (Thymeleaf's `\_\_` escape is not needed).
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
- `expected '=', ',' or ')' after link parameter '<name>' but <found>`: parameters are
  `name=value`, or a bare `name` for a parameter without a value.
- `link parameter '<name>' fills path variable {<name>} in @{<path>} and needs a value: write
  (<name>=...)`: a path variable needs a value.
- `link path must not contain whitespace: percent-encode it (%20) or pass the value as a parameter, e.g. @{/search(q=term)}`.
- `invalid path variable '{<name>}': write a name such as {id}`.
- `nested '{' in link path: write a path variable such as {id}`.
- `unmatched '}' in link path: write a path variable such as {id}`.
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
- `parameter type <type> is not allowed: <reason>`: a never-view-safe type (section 4.2). Pass the
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
- `cannot output a value of type <type> inside <script>: only strings, numbers, booleans, enums, java.time values, records and @TemplateView classes of those, and Optionals, collections, arrays and String-keyed maps of those can be serialized as JSON`:
  when a property of a record or bean is the problem, the message ends with
  `; property '<name>' of <type> has type <type>` (or `; <type> contains itself with different type arguments`).
  Output the specific properties the script needs, or make the property's type serializable.
- `<form> needs a value of type dev.tessera.runtime.SafeHtml, but a message #{...} is a String: use t:text or [[...]] (escaped), or pass trusted HTML as a SafeHtml parameter`.
- `<form> needs a value of type dev.tessera.runtime.SafeHtml, but the expression has type <type>: use t:text or [[...]] (escaped), or build a SafeHtml in trusted Java code`.

### Attributes

- `<label> is not allowed: event-handler attributes cannot contain expressions; put the value in a data-* attribute and read it from a script`.
- `<label> is not allowed: '<name>' is a Vue, Alpine, htmx, AngularJS or Knockout directive whose value is run as JavaScript, so it cannot contain expressions; write it as a static attribute, or put the value in a data-* attribute or a <script> with [[...]] and read it from there`. See [4.4](#44-escaping-and-output-contexts) for the list of directives.
- `<label> is not allowed: a style value can only combine string literals, numbers and CssValues such as css.color(c), css.length(l) or css.url(u), not a <type>; use t:class or t:classappend instead`.
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
- `<t:field> needs a field path such as lastName, *{address.city} or *{items[i].name}, not an expression`.
- `a field path index must be an int literal or an int variable such as i or iterStat.index, not an expression`:
  compute the index with `t:with="i=..."` first.
- `a field path index must be an int, got <type>`.
- `cannot index <type> in a field path: [...] works on a List or an array`.
- `field '<path>' can only be used inside t:object: put t:object="..." on this element or an enclosing one`.
- `'<path>' is not a field path: use names like lastName, address.city or items[0].name`.
- `*{<name>} can only be used inside t:object: put t:object="..." on this element or an enclosing one, or write the full path (owner.<name>)`.
- `t:errors can only be used inside t:object (on this element or an ancestor)`.
- `t:errorclass needs literal class names (letters, digits, '-', '_'), e.g. t:errorclass="error"`.
- `t:errorclass needs t:field on the same element`.
- `unknown function fields.<fn>: the fields namespace has hasErrors('path') and errors('path')`:
  `#fields.hasAnyErrors()` → `fields.hasErrors('*')`, `#fields.allErrors()` → `fields.errors('*')`.
- `fields.<fn>(...) can only be used inside t:object, or with a Field parameter`.
- `fields.<fn> takes one string literal (a field path such as 'lastName', or '*' for all errors), a field expression such as *{items[i].name}, or a Field parameter`.

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

## 7. Known limitations

Gaps in Tessera today, each with what to do until it is fixed.

- **Versions.** Spring 4.3+ / Boot 1.5+ on Servlet 3.0+; anything older is untested. Upgrade to
  Spring 4.3 first, or wire the classes by hand at your own risk.
- **Build.** Tested with Maven only. Gradle is untested and has no docs or plugin: the processor
  needs only javac options (section 2), so it should work, but it isn't verified. Template-only
  changes need `mvn clean compile`; there is no hot reload.
- **Messages.** `-Atessera.messages` checks one bundle. Keys from other bundles fail the build;
  give those pages a `@TemplateFunction` that looks them up, and accept that they are not checked.
- **View resolution.** Names are matched exactly: a leading `/` or a `page :: fragment` view name
  falls through to other resolvers. Return the plain template name (make the fragment its own
  template if needed). Path variables are not in the model unless the controller adds them.
- **Types.** Concrete collection types (`ArrayList`, `TreeMap`, `SortedMap`) are rejected as
  parameter and property types; declare `List`/`Map`. A `Boolean` getter named `getIsX()` (Lombok
  on a `Boolean isX` field) is reachable only as `x.getIsX()`. `java.util.Date`/`Calendar` are not
  view-safe.
- **Forms.** No map keys in paths (`map[key]`), no `checked` for checkboxes bound to a collection,
  no global (object-level) errors apart from `fields.errors('*')`, no `t:field` on fields whose
  type is not view-safe (`MultipartFile`: write the `name` attribute by hand), and `Field`
  fragments cannot read the value. Radios get no `id`.
- **Output.** `[[...]]` in a script serializes only the properties of the static type: a subclass's
  extra getters are not written, and Jackson annotations are ignored. `numbers.*` has no grouping
  or locale; `temporals.format` uses English names. Thymeleaf parser-level comments
  `<!--/* ... */-->` are emitted like any comment; delete them.
- **Rich text.** `html.sanitize` has one fixed policy: no images, tables, `class` or `style`, and
  links don't keep `target`. If the stored HTML needs more, build the markup in Java (a template
  over a parsed model, or `SafeHtml.concat`) rather than widening it with `SafeHtml.unchecked`.
- **Security functions.** `auth` has `hasRole`/`hasAnyRole`, not `hasAuthority`; compute anything
  else in the controller.
- **Selections.** `~{::tag}` selects fragment names and plain tag names only: not hyphenated tags,
  `t:block`, ids or classes. Give the element a `t:fragment` name instead.
- **Not supported, by design:** runtime template loading, `__${...}__` preprocessing, dynamic
  names, the layout dialect, `th:inline` modes and `/*[[...]]*/` natural-template comments,
  collection selection/projection, expressions in `<style>` and `on*`, `String` values in `style`
  attributes, and Kotlin.
- **Expected output differences from Thymeleaf:** attributes set by `t:` attributes may move within
  the tag; `<td >` (after a removed attribute) renders as `<td>`; a `null` attribute value omits
  the attribute (Thymeleaf writes `value=""`); a `null` link path variable fails the render.
- **Runtime errors** are model type mismatches (`TemplateModelException`), missing primitive
  parameters, reading a property of `null`, and `null` link path variables. Everything else is a
  build error.
