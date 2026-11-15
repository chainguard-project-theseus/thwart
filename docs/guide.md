# Porting guide: moving server-rendered Java views to Thwart

This is the reference for moving a Spring application's views (Thymeleaf, JSP, FreeMarker,
Velocity, Pebble, Mustache or similar) to Thwart. It doesn't map your engine's syntax construct by
construct: you know your engine, and once you know Thwart's model the syntax converts on its own.
It covers what you can't find out from the tools: the model (section 1), build setup (section 2),
the tools and the loop (section 3), syntax and output contexts (section 4), the traps of particular
engines (section 4.10) and known limitations (section 5).

The compiler's error messages are the detailed documentation for each rule: they name the file,
line and column, say what is wrong, and name the fix. Examples marked `<!-- compile: ... -->` in the
source of this file are compiled and rendered by Thwart's tests, so they are known to work.

## 1. The model

Thwart templates are HTML files with `t:` attributes, close to Thymeleaf's `th:` syntax. An
annotation processor compiles them to Java classes during the application's normal `javac`, so a
template error is a build error. At runtime nothing parses or reads templates.

**A template sees only its typed parameters.** The first element declares them
(`t:params="Owner owner, List<Pet> pets"`). With Spring each is read from the model attribute of
the same name, or else from the handler's `@PathVariable` of that name. There is no request,
session, application or bean scope, no request parameters, cookies or headers. Engines you are
leaving often fall back to those scopes when a name is not in the model; Thwart doesn't, so **a
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
| puts expressions in `style`, a `data-*` or a framework attribute (`th:hx-get`) | Section 4.4: computed attributes are a closed list, each with a fixed context. |
| has an event handler (`onclick="..."`, any `on*`, static or computed) or a `javascript:` URL | Build errors: a strict CSP blocks inline script. Mark the element (`data-action="save"`) and attach one listener from a `<script>`: `document.addEventListener('click', e => { const el = e.target.closest('[data-action=save]'); if (el) save(el); });`. A `href="javascript:void(0)"` link becomes a `<button type="button">`, or `href="#"` with `e.preventDefault()` in the listener. |
| includes a file, calls a macro or tag with a body, or extends a layout | A fragment call with `Fragment` arguments (section 4.8). |
| binds a form through form tags or macros | `t:object`, `t:field`, `t:errors`, `fields.*` (section 4.7). |
| reads an `Exception`, a `Date` or another non-view-safe value | Put what the page shows (a message `String`, a `LocalDate`, an `Instant`) in the model. |

## 2. Build setup

The build needs JDK 8+ and Maven or Gradle; the application runs on Java 8+ with Spring Framework
4.3+ or Spring Boot 1.5+, on Servlet 3.0+. Keep the application's Spring and Boot versions: don't
upgrade as part of the migration. On Java 8 there are no records, so view types are classes with
getters annotated `@TemplateView`.

Pick the starter by servlet API and use exactly one. It brings only `thwart-runtime`; Spring, the
servlet API and Spring Security come from the application:

| Application | Artifact |
|---|---|
| `jakarta.servlet` (Boot 3+, Spring 6+) | `thwart-spring-boot-starter` |
| `javax.servlet` (Boot 1.5-2.x, Spring 4.3-5.x) | `thwart-spring-boot-starter-javax` |

Maven and Gradle are set up the same way: the starter as a dependency, `thwart-compiler` as an
annotation processor in the application's own javac, and three processor options. There is no
Gradle plugin. Sections 2.1 and 2.2 give the complete setup for each; what they share:

- `thwart.templates` (required) is the absolute templates root; every `**/*.html` below it is a
  template. `thwart.package` is the generated package. `thwart.messages` is the Spring message
  basename as a path without `.properties` (several, comma-separated); with it every `#{key}` is
  checked at build time. If the base file is partial or missing and the full text is in a locale
  file, name that file (`.../i18n/messages_en`).
- The processor path is explicit, so list every processor the build relied on (Lombok,
  MapStruct, ...), Lombok before Thwart. Templates see Lombok's getters.
- Templates compile at the application's release level: keep the build's existing Java level
  rather than setting one for Thwart, or the class files may not load on the runtime JDK. JDK 8's
  javac has no `--release`; use source and target 1.8 there.
- The build tool, not javac, decides when to compile, and it doesn't know javac reads the
  templates and message files. Each needs to be told (Maven with `thwart-maven-plugin`, Gradle
  with declared inputs), or an edited template or message keeps its old generated class and the
  build stays green.
- The generated `META-INF/services/dev.chainguard.thwart.runtime.TemplateRegistry` and
  `META-INF/thwart/**` must be packaged; otherwise the starter warns "No compiled Thwart
  templates on the classpath" at startup.
- Static analysis that runs on compiled classes (SpotBugs, PMD, Checkstyle, Javadoc) sees the
  generated templates: exclude the `thwart.package` package rather than loosening the rules.
- Resource filtering (Maven's `<filtering>`, Gradle's `expand` or `filter` in `processResources`)
  rewrites `${...}` in templates unless `templates/**` is excluded from it.
- Spring 4.3 and 5.0 need `--add-opens java.base/java.lang=ALL-UNNAMED` on JDK 16+, including in
  tests (surefire's `argLine`, Gradle's `test { jvmArgs ... }`).

### 2.1 Maven

```xml
<dependency>
  <groupId>dev.chainguard.thwart</groupId>
  <artifactId>thwart-spring-boot-starter</artifactId> <!-- javax: -javax; without Spring: thwart-runtime -->
  <version>${thwart.version}</version>
</dependency>

<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <parameters>true</parameters>
    <annotationProcessorPaths>
      <!-- every processor the build already uses (Lombok first), then: -->
      <path>
        <groupId>dev.chainguard.thwart</groupId>
        <artifactId>thwart-compiler</artifactId>
        <version>${thwart.version}</version>
      </path>
    </annotationProcessorPaths>
    <compilerArgs combine.children="append">
      <arg>-Athwart.templates=${project.basedir}/src/main/resources/templates</arg>
      <arg>-Athwart.package=com.example.templates</arg>
      <arg>-Athwart.messages=${thwart.messages}</arg>
    </compilerArgs>
  </configuration>
</plugin>

<plugin>
  <groupId>dev.chainguard.thwart</groupId>
  <artifactId>thwart-maven-plugin</artifactId>
  <version>${thwart.version}</version>
  <executions><execution><goals><goal>check</goal></goals></execution></executions>
  <!-- if thwart.templates is not the default:
  <configuration><templates>${project.basedir}/src/main/webapp/templates</templates></configuration> -->
</plugin>
```

- `annotationProcessorPaths` turns off processor discovery, which is why every other processor
  must be listed there.
- After `mvn clean compile`, `target/generated-sources/annotations` holds a `*Template.java` per
  template. If it is empty, the processor didn't run: `annotationProcessorPaths` needs
  `maven-compiler-plugin` 3.5+ (`<parameters>` 3.6.2+). Boot 1.5's parent pins 3.1 and defaults
  `java.version` to 1.6: set `<java.version>1.8</java.version>` and
  `<maven-compiler-plugin.version>3.8.1</maven-compiler-plugin.version>` (Boot 1.4 pins the
  version on the plugin itself, so write `<version>` there).
- Under a Boot parent, set `<java.version>` and leave `<release>` off the plugin. On JDK 8 use
  `<source>`/`<target>` 1.8.
- Maven runs javac only when Java sources change: `thwart-maven-plugin`'s `check` goal makes it
  recompile after a template-only or message-only edit. It reads `thwart.messages` from the
  property of that name, so set the property. The plugin needs Maven 3.6.3+; upgrade an older
  `mvnw` wrapper.

### 2.2 Gradle

Keep the build's plugins (`org.springframework.boot`, `io.spring.dependency-management` or a
platform, `java` or `war`) and add the following (Groovy DSL; `examples/gradle` in the Thwart
repository is a complete Boot app, built in CI):

```groovy
dependencies {
  implementation "dev.chainguard.thwart:thwart-spring-boot-starter:$thwartVersion" // javax: -javax; without Spring: thwart-runtime
  // every processor the build already uses (Lombok first), then:
  annotationProcessor "dev.chainguard.thwart:thwart-compiler:$thwartVersion"
}

def templates = file('src/main/resources/templates')
def messages = file('src/main/resources/messages')

tasks.named('compileJava') {
  options.compilerArgs += [
    "-Athwart.templates=$templates",
    '-Athwart.package=com.example.templates',
    "-Athwart.messages=$messages",
  ]
  // javac reads these, not Gradle: without them a template- or message-only edit leaves
  // compileJava UP-TO-DATE with the old generated classes.
  inputs.dir(templates).withPropertyName('thwartTemplates').withPathSensitivity(PathSensitivity.RELATIVE)
  inputs.file("${messages}.properties").withPropertyName('thwartMessages').withPathSensitivity(PathSensitivity.RELATIVE)
}
```

- The `annotationProcessor` configuration is the processor path (Gradle ignores processors on
  the compile classpath), so every other processor goes there too, Lombok before Thwart. Lombok
  also stays on `compileOnly`.
- The `inputs` lines are Gradle's equivalent of `thwart:check`: they make a template-only or
  message-only edit rerun `compileJava`, and they make the build cache key cover the templates.
  Declare every message file `thwart.messages` names, and the templates directory even if it is
  outside `src/main/resources`.
- Thwart doesn't register for Gradle's incremental annotation processing: it generates from
  template files, not annotated sources, which Gradle's incremental model can't express, so when
  `compileJava` runs it recompiles the whole source set, which is correct.
- After `gradle clean compileJava`, `build/generated/sources/annotationProcessor/java/main` holds a
  `*Template.java` per template. If it is empty, the processor didn't run: check that
  `thwart-compiler` is on `annotationProcessor`, not `implementation`.
- Keep the build's Java level (`java { toolchain { ... } }`, `sourceCompatibility` or
  `options.release`). Gradle 9 itself runs on JDK 17+: to target Java 8, set
  `options.release = 8`, or compile with a JDK 8 toolchain.
- `thwart-maven-plugin` is Maven-only; nothing replaces it but the `inputs` lines. Thwart isn't
  published yet: install it with `mvn install` and add `mavenLocal()` to `repositories`.

### 2.3 Spring

**Spring Boot.** The starter's auto-configuration registers the view resolver, the template
registry and a `TemplateAvailabilityProvider`, on Boot 1.5 through 4, including `@WebMvcTest`
slices. Thwart's resolver runs first and falls through for view names it doesn't know, so the old
engine can stay while you migrate. While another engine's view resolver is a bean, the starter logs
`Thwart: another template engine is still configured` naming it. JSP, and resolvers added only
through `ViewResolverRegistry` or inside a `ContentNegotiatingViewResolver`, are not reported.

**Spring MVC without Boot.** Import `dev.chainguard.thwart.spring.ThwartConfiguration` (or declare it as a
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

`thwart-migrate` is one jar with three commands; `--help` prints the usage. Fetch it as the skill
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
`java -jar thwart-migrate.jar record http://localhost:8080 pages.txt baseline/`. It saves each
response's body, status and final URL. `compare` refuses a `pages.txt` that doesn't match the
recording, so record again after changing it.

**3. Set up the build** (section 2) and give the templates view types (section 4.2).

**4. Convert one template at a time**, shared layouts and fragments first, since pages depend on
them. For each template, find every value it reads and make sure the model provides it, declare the
parameters with their real types, rewrite the markup, and run `mvn clean compile` (Gradle:
`gradle compileJava`).

For Thymeleaf, `thwart-migrate <old-templates-dir> <new-templates-dir> --report report.md` does the
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
`java -jar thwart-migrate.jar compare http://localhost:8080 pages.txt baseline/`. It compares
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
any view name Thwart doesn't know still reaches it. Remove its templates, dependency and
configuration, then build, test and compare once more. The old engine's starter often brought in
libraries the application still uses (`spring-context-support` with Boot's Velocity starter,
`commons-beanutils` with Tiles), and losing them may show only at runtime: diff
`mvn dependency:list` (Gradle: `gradle dependencies --configuration runtimeClasspath`) from
before and after, and declare what the application still uses.

### 3.1 Hot reload

Under Spring Boot, a saved template or message file reaches the browser on the next refresh, a few
seconds later. The build recompiles the module with the same processor and the same checks, then
Spring Boot DevTools restarts the application with the new classes. Nothing compiles templates
inside the application, and Boot leaves DevTools out of the jar it packages. Add DevTools:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-devtools</artifactId>
  <optional>true</optional>
</dependency>
```

- **Maven:** run `mvn spring-boot:run` in one terminal and `mvn thwart:watch` in another, both in
  the application module's directory. The watch goal checks the templates and the
  `thwart.messages` files every half second and runs `mvn compile` on the module after each
  change, with the watch's own `-o`, `-P` and `-D` options (pass `-s` in `.mvn/maven.config`, not
  on the command line). It needs the `check` goal and its configuration from section 2.1. In a
  multi-module build, `install` the modules the application depends on first.
- **Gradle:** declare `developmentOnly 'org.springframework.boot:spring-boot-devtools'` instead,
  then run `gradle bootRun` in one terminal and `gradle -t classes` in another. The `inputs` lines
  from section 2.2 make each save rerun the processor.
- **IDE:** to run the application from your IDE, keep the watch goal or the continuous build
  running next to it. We haven't tested whether an IDE's own automatic build reruns the processor
  after a template-only save, so don't rely on it.
- **Errors:** a template that fails to compile prints its `file:line:col` error in the watch
  terminal. By then the old classes are gone, so the restarted application fails to start until
  you save a fix. It then comes back by itself.
- **Timing:** in `examples/petclinic`, about 4 s from save to page under Gradle and 5 s under Maven,
  most of it the build. The restart itself takes half a second.
- The Maven watch goal reacts only to templates and messages. Java edits are compiled by your IDE
  or by `mvn compile`, and DevTools restarts as usual. Spring MVC without Boot has no DevTools, so
  it has no hot reload.

## 4. Reference

### 4.1 Templates and parameters

A template is an HTML file. Its view name is its path under the templates root without `.html`
(`owners/list.html` is `owners/list`). The first element carries the header:

<!-- compile: template guide/hello.html -->
```html
<!DOCTYPE html>
<html xmlns:t="https://thwart.chainguard.dev"
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
  `java.util`, `java.time`, `java.math` and `dev.chainguard.thwart.runtime` are always imported.
- The template compiles to `<thwart.package>.guide.HelloTemplate` with
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

import dev.chainguard.thwart.runtime.TemplateView;
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
  Comparing types that never share a value (a `String` variable with an enum or a number) is a
  build error: compare the enum with a literal (`status == 'ACTIVE'`), or give both sides one type in Java.
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
  immutable. `t:remove` takes `all` or `tag` (other values: section 5's table).
- Any `t:<name>` sets that attribute, and `null` omits it, where Thymeleaf's `th:value`,
  `th:href`, `th:src`, `th:action`, `th:name` and `th:type` wrote it empty. Where the empty
  attribute matters, write `x ?: ''`. Boolean attributes (`t:checked`, ...) take a condition.
- Not supported: `th:attrappend`/`attrprepend`, `th:alt-title`, `th:lang-xmllang`, `th:assert`,
  `th:include` (use `t:insert`), `th:inline`; `th:attr` and `th:errorclass` (section 5's table).

### 4.4 Output contexts

The compiler decides the context from the element and attribute name; you can't override it. A
computed attribute must be on one closed list: `data-*`, `aria-*`, and the names in the table
below. Any other computed name is a build error (recipes below the table). What each context
accepts:

| Context | Where | Accepts |
|---|---|---|
| Text | element text, `t:text`, `[[...]]` | Any value, escaped. |
| Raw HTML | `t:utext`, `[(...)]` | A `SafeHtml`, or a bundle message, sanitized by the message policy (section 4.5). |
| Attribute | text: `abbr alt download id label name pattern placeholder title value`; keywords: `autocomplete dir enterkeyhint formmethod hidden inputmode method scope spellcheck translate`; numbers: `cols colspan datetime height high low max maxlength min minlength optimum rows rowspan size span start step tabindex width`; booleans (they take a condition): `async autofocus checked defer disabled formnovalidate multiple novalidate open readonly required reversed selected`; `for form headers list accept class hreflang lang role`; the WAI-ARIA 1.2 `aria-*` names | Any value, escaped. |
| Typed attribute | `target`; `value` other than on `button data input li meter option progress select`; `for` other than on `label` and `output` | Literals, numbers, and a choice between literals (`${newWindow} ? '_blank' : '_self'`). |
| URL | `href`, `src`, `action`, `formaction`, `cite`, `poster` | Any value; only `http`, `https`, `mailto`, `tel` and relative URLs pass, anything else becomes `about:invalid#thwart`. Forms: section 4.7. |
| Resource URL | `script[src]`, `script[href]`, `iframe[src]`, `frame[src]`, `embed[src]`, `object[data]`, `link[href]`, `base[href]` | A same-origin `@{...}` link without query parameters, such as `@{/js/{v}/app.js(v=${v})}`. Load another origin with a static attribute. |
| Neutral | `data-*`, and an `aria-*` name outside WAI-ARIA 1.2 | A framework-neutral value (below). |
| Script | `<script>` text | `[[...]]` writes JSON (below). |
| Style | the `style` attribute | Literals, numbers and `CssValue`s (`css.color(c)`, `css.length(l)`, `css.url(u)`, `css.var(name)`), combined with `+`, `\|...\|` and conditionals. No expressions in `<style>`. |
| Refresh | `content` of `<meta http-equiv="refresh">` | `'3;url=' + @{/path...}`: a literal delay and a same-origin link. |
| Forbidden | `on*`, `srcdoc`, URL lists (`srcset`, `imagesrcset`, `ping`), and what the parser reads: `type` on `<script>` and `<input>`, `<meta http-equiv>` and `charset`, `<template shadowroot*>` and `for`, `encoding`, `is`, `xmlns*`, `<font color/face/size>`, names outside `[A-Za-z_][A-Za-z0-9_.:-]*` (Vue's `:x`, `@x`); and every name not on the list above: `content` other than a refresh, `type`, `rel`, `loading`, `nonce`, `property`, `hx-*`, `x-*`, `v-*`, a custom element's own attributes, ... | Nothing computed. |

**A computed name that is not on the list.** Port it by what it is for:

| The attribute is | Recipe |
|---|---|
| read by your script or a client library (`th:hx-get`, `th:attr="blockid=${id}"`, `th:icon`) | A number, boolean or link in a `data-*` attribute: `t:data-hx-get="@{/items/{id}(id=${id})}"` (htmx reads `data-hx-*` too), `t:data-blockid="${id}"`; the script reads `el.dataset`. A string goes in a script declaration or a JSON block (Scripts, below). |
| one of a few values (`th:type="${submit} ? 'submit' : 'button'"`, `th:rel`, `th:loading`, `<input type>`) | A static attribute, or one element per value under `t:if`/`t:switch` (the `fields` example in 4.8). |
| `th:attr="nonce=${nonce}"` on a `<script>` | Delete it: Thwart stamps the CSP nonce itself (section 4.9). |
| `content` of a named `<meta>` (`description`, Open Graph `property`, `_csrf`) | A static `content`, or one `<meta>` per case under `t:if`. Page data for JavaScript, such as the CSRF token, goes in a script declaration (4.7). |
| `srcset`, `<font color>` | One `src` (or a static `srcset`); a `<span>` with a `t:class`. |

SVG, MathML, CDATA and the obsolete raw-text elements are static or rejected: section 5's table.

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
<div xmlns:t="https://thwart.chainguard.dev" t:import="com.example.guide.Account"
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

**Framework-neutral values.** In many client frameworks `data-*` is a code channel (Knockout's
`data-bind`, htmx's `data-hx-on`), and Thwart models none of them. So a computed `data-*` takes only
a number, a boolean, a same-origin link with a literal path and no query, or a choice between those
and literals: `t:data-id="${item.id}"` compiles, `t:data-name="${item.name}"` doesn't. Static
attributes are copied as written. Port a computed string by what it is for:

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

Don't list an open-ended range as literals (`row-0`, `row-1`, ...), hard-code a message per locale,
or copy a JSON value back into a `data-*` attribute for an unchanged plugin. A recipe changes the
page: write down which recipe caused each `compare` difference, and mask a changed asset hash narrowly.

<!-- compile: template guide/recipes/hidden-inputs.html -->
```html
<ul xmlns:t="https://thwart.chainguard.dev" t:params="List<String> skus">
  <li t:each="sku : skus">
    <input type="hidden" name="sku" t:value="sku">
    <button hx-post="/cart" hx-include="previous input">Add</button>
  </li>
</ul>
```

<!-- compile: template guide/recipes/plugin.html -->
```html
<div xmlns:t="https://thwart.chainguard.dev" t:params="List<String> tips">
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

import dev.chainguard.thwart.runtime.HtmlPolicy;
import dev.chainguard.thwart.runtime.SafeHtml;
import dev.chainguard.thwart.runtime.TemplateFunction;
import dev.chainguard.thwart.runtime.TemplateFunctions;

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
same-origin paths and ids get the prefix `user-content-`. A kept `img` makes the browser send a
same-origin GET with the user's cookies, so don't serve state-changing GETs. Don't parse stored
HTML in Java into records and rebuild it with a self-inserting fragment (`STRUCTURE_DEPENDS_ON_DATA`).

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

import dev.chainguard.thwart.runtime.HtmlPolicy;
import dev.chainguard.thwart.runtime.SafeInlineHtml;
import dev.chainguard.thwart.runtime.TemplateFunction;
import dev.chainguard.thwart.runtime.TemplateFunctions;

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
`-Athwart.messages`, a missing key is a build error; without it, it renders `??key_locale??`. A
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
<div xmlns:t="https://thwart.chainguard.dev" t:params="String name">
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

import dev.chainguard.thwart.runtime.TemplateFunction;
import dev.chainguard.thwart.runtime.TemplateFunctions;
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
<p xmlns:t="https://thwart.chainguard.dev" t:import="com.example.guide.Order" t:params="Order order">
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
<form xmlns:t="https://thwart.chainguard.dev" t:import="com.example.guide.*" t:params="Signup signup, List<Plan> plans"
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
  none; `*` is all errors and `global` the form object's own. For other markup, loop over
  `fields.errors('email')`.
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
direct, unconditional child keeps it and gets no second one. For htmx or `fetch`, put the token in
the model (a `@ControllerAdvice` `@ModelAttribute("csrfToken")` reading the request's `CsrfToken`),
declare it at the top of a script, `const csrfToken = [[${csrfToken}]];`, and send it as the
`X-CSRF-TOKEN` header (htmx: in an `htmx:configRequest` listener). Use htmx 2 or set
`htmx.config.selfRequestsOnly = true`.

### 4.8 Fragments and layouts

A fragment is an element with `t:fragment`. Parameters are typed, and a fragment sees only its
parameters.

<!-- compile: template guide/fragments/cards.html -->
```html
<div xmlns:t="https://thwart.chainguard.dev" t:import="com.example.guide.Customer">
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
<main xmlns:t="https://thwart.chainguard.dev" t:import="com.example.guide.Customer" t:params="Customer customer">
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
  Where a Thymeleaf call omitted an optional parameter, pass `null` and test for it in the
  fragment (`t:if="glyph != null"`). If most calls pass `null`, split the fragment in two: a
  shorter one with only the common parameters, and a longer one that takes the extra parameters,
  calls the shorter one and adds its extra markup around that call.
- `~{::name}` is a `Fragment` over the same-file fragment `name`, or else the first element with
  tag `name` (`~{::dl}`, `~{::my-card}`, `~{::t:block}`), rendered with the call site's variables.
  Put it inside the host so it doesn't also render in place. Only the first match; no `#id` or
  `.class` selectors.
- A `null` `Fragment` renders nothing, so a `Fragment` parameter can be optional.
- The compiler checks each fragment where it is inserted, so a `~{::tr}` goes into a `<tbody>`
  but not a `<div>`. Nothing goes inside `<title>`, `<textarea>` or `<noscript>`: use `t:text`.
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
<t:block xmlns:t="https://thwart.chainguard.dev">
  <t:block t:fragment="common(String title)">
    <title t:text="|${title} - Example|">Example</title>
    <link rel="stylesheet" t:href="@{/css/app.css}" />
  </t:block>
</t:block>
```

<!-- compile: template guide/head-page.html -->
```html
<!DOCTYPE html>
<html xmlns:t="https://thwart.chainguard.dev">
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
<html xmlns:t="https://thwart.chainguard.dev" t:fragment="layout(Fragment content, String title, Fragment head)">
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
<html xmlns:t="https://thwart.chainguard.dev" t:import="com.example.guide.Customer" t:params="Customer customer"
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
<html xmlns:t="https://thwart.chainguard.dev" t:fragment="toolbarLayout(String title, Fragment tools, Fragment content)"
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
<html xmlns:t="https://thwart.chainguard.dev" t:params="String name"
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
<div xmlns:t="https://thwart.chainguard.dev">
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
<form xmlns:t="https://thwart.chainguard.dev" t:import="com.example.guide.Signup" t:params="Signup signup"
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
supported. A partial of `<option>`s is declared outside the `<select>`, in its own template
(`<t:block t:fragment="options(List<Team> teams)"><option t:each="...">`); the page's `<select>`
holds `<t:block t:replace="~{teams/options :: options(teams)}">`; the htmx handler returns
`"teams/options :: options"`. A `<select t:field>` can't take inserted options (none would be
selected): the partial is the whole select, in a `t:fragment` that also holds the `t:object`.
Don't return a `Template` from a controller: return the view name.

Outside a request (a mail job, a command-line tool), call
`XTemplate.of(...).renderToString(ctx)` with a `RenderContext` (`RenderContext.defaults()`,
`ResourceBundleMessages`, `DefaultLinks(contextPath)`), or in a controller with
`SpringRenderContexts.create(request, response, model)`. `Fragment` exists only between
templates: Java can't build one, and a page (`t:params`) or a function can't take one. A page that
took one becomes a fragment its callers pass `~{...}`.

**Content Security Policy.** With the Spring starter every `<script>` and `<style>` the template
writes gets the request's nonce, and by default the page is sent with `Content-Security-Policy:
script-src 'nonce-N' 'strict-dynamic'; object-src 'none'; base-uri 'none'`. A policy the
application sets wins (Spring Security's `contentSecurityPolicy(...)`, or your filter's header with
`CspNonce.get(request)`): `dev.chainguard.thwart.spring.view.CspFilter` adds the default only when the
response commits without one. Boot and servlet containers register it; elsewhere (your own embedded
server, a `metadata-complete` `web.xml`, Boot importing `ThwartConfiguration`) register it first,
for REQUEST, ASYNC and ERROR, or the starter logs `CspFilter is not in the filter chain`. The one
off switch is `thwart.csp.enabled=false`. Delete `th:nonce`.

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

**Thymeleaf.** Run `thwart-migrate` first (section 3). Natural-template script comments
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
<form xmlns:t="https://thwart.chainguard.dev" t:import="com.example.guide.*" t:params="Signup signup"
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
- `$!{x}` and `${x}` both print nothing for `null` in Thwart. A `null` attribute is omitted where
  `value="$!{x}"` wrote `value=""` (section 4.3).
- Velocity prints the source of a reference it can't evaluate, keeps the old value on
  `#set($a = $b)` with a null `$b`, and treats only `null` and `false` as false. If the pages depend
  on that, reproduce it on purpose in Java and check it with compare.
- Templates split across `#parse` files often leave tags unbalanced (the header opens a `<div>`
  the footer closes). Each Thwart template is well formed: open and close in one layout.

**Pebble.** `loop.index` is `st.index`, `loop.length` is `st.size`. `{% set %}` is `t:with`,
`{% macro %}` a typed fragment, `{% include %}` a fragment call, and `{% extends %}` with
`{% block %}`s a layout whose `Fragment` parameters are the blocks (an extends chain: section
4.8). Pebble reads public fields, `Map` keys with `.` and methods with arguments; Thwart reads
getters and record components only (`map['key']` works), so add getters or build a record.
Custom filters become `@TemplateFunctions` or values computed in Java. `strictVariables` is what
typed `t:params` already give you.

**Mustache.** JMustache fails at a missing value, cutting the page off mid-render; Thwart prints
nothing for `null`. Render the whole page, and compare against an original that has the value.

## 5. Known limitations

Gaps in Thwart today, with what to do instead.

- **Versions and build.** Spring 4.3+ / Boot 1.5+ on Servlet 3.0+; anything older is untested.
  Maven and Gradle. Hot reload restarts the application through Spring Boot DevTools (section 3.1);
  without Boot there is none.
- **Messages.** `-Athwart.messages` reads bundles from the file system. Keys that only a jar or a
  database-backed `MessageSource` provides fail the build: give those pages a function that looks
  them up, unchecked.
- **View resolution.** Names match exactly, less one leading `/`. `page :: fragment` renders a
  fragment whose parameters a model can fill and whose root fits a response body; arguments and
  selectors in view names, and fragments with `Fragment` or `Field` parameters, fall through.
- **Mixed engines.** While the old engine is configured it renders every view Thwart doesn't
  know, so an app part-way through is protected only on the ported views.
- **Types.** `java.util.Date` and `Calendar` aren't view-safe: declare `Instant`, `ZonedDateTime`
  or `LocalDate`. `[[...]]` serializes only the properties of the static type and ignores Jackson
  annotations.
- **Client-side code.** What page JavaScript or a client framework does with the DOM is outside
  the guarantee (security-model.md §9). Client frameworks that compile the server's DOM as a
  template (Vue in-DOM templates, petite-vue, AngularJS) are unsupported.
- **Not supported, by design:** runtime template loading and Kotlin.
- **Expected output differences:** attributes set by `t:` may move within the tag; a `null`
  attribute value omits the attribute; `<script>` and `<style>` get a CSP
  nonce. `compare` ignores these.
- **Runtime errors** are model type mismatches, missing primitive parameters, reading a property
  of `null`, `null` link path variables, `first`/`last` of an empty list and `ids.prev` before any
  input. Everything else is a build error.
- **Markup rejections** and their fixes are in this table. Verifier errors end with `[verifier:RULE]`:
  a spec HTML parser rejects markup a browser reads only by error recovery or where browsers
  build different trees. For an expression inside a fragment, the error is at the call site.

| Rule | What it means | Fix |
|---|---|---|
| `PARSE_ERROR` | The markup parses only by error recovery: a block in a `<p>`, a second `<body>`, content between `</head>` and `<body>`, content directly in a table, a stray end tag, a missing space between attributes, a literal `<` or `&` | Write the markup a browser reads without recovery: close the `<p>` first, write `&lt;` and `&amp;`, pass `~{::body}` with `t:remove="tag"` (4.8) |
| `UNBALANCED` | A `t:if`, `t:unless`, `t:case`, `t:each`, computed attribute or fragment leaves an element open or closed, or implies a `<tbody>`, `<head>` or `<body>` | Close inside the body what it opens; write the `<tbody>` around rows; make a fragment library's root a `<t:block>` (4.8) |
| `TEXT_IN_TABLE` | A value directly in a `<table>`, `<tbody>` or `<tr>`, where a browser moves it out of the table | Put it in a `<td>`, `<th>` or `<caption>` |
| `TEXT_OUTSIDE_BODY` | A value in `<head>` or after `</body>` | Move it into `<title>` or the body |
| `TEXT_IN_SCRIPT`, `JSON_NOT_IN_SCRIPT` | A value in a script or style position the compiler doesn't accept | A prelude declaration or a JSON block (4.4, Scripts); no expressions in `<style>` (4.4, CSS) |
| `TEXT_NOT_IN_TEXT` | A value inside a comment, a tag, or right after `&` or `<` | Move it into element text or a `t:` attribute; finish the character reference |
| `ATTRIBUTE_NOT_IN_VALUE` | Static markup around a computed attribute changes which attribute it lands in (an unclosed quote, a duplicate) | Fix the static tag; one attribute per name |
| `RAW_NOT_FLOW`, `RAW_CLOSES_ELEMENT` | A `SafeHtml`, HTML message or `Fragment` where its markup would leave its place: in a table part, in a `<p>` or heading, inside a link (for a message with links), in a `<select>` | Inline-only HTML in a `<p>`, heading, link or button: a `SafeInlineHtml` (4.4, Inline HTML); otherwise a `<div>` or a cell; split the message (4.5) |
| `VALUE_IN_FOREIGN` | A value, condition, loop, fragment or computed attribute inside or on `<svg>` or `<math>` (`t:if` or `t:replace` on the element itself is fine) | Static SVG. Set what changes outside it: a `t:class` on an HTML parent (`fill: currentColor`), a CSS variable (`<div t:style="\|--progress: ${n}\|">`), one `<svg>` per case under `t:if`, or `<img t:src="@{/icons/x.svg}">` |
| `CDATA_SECTION` | A `<![CDATA[` section | Write the text without it, with `&lt;` and `&amp;` |
| `VALUE_IN_RAW_TEXT` | A value inside `<noscript>` or `<iframe>` | Static content; move the value outside the element |
| (build error) `<xmp>`, `<plaintext>`, `<noembed>`, `<noframes>`, `<frameset>` | Obsolete elements whose parsing Thwart doesn't model | Escaped text (`&lt;`) in a `<pre>`; `<noscript>`; an ordinary layout or an `<iframe>` |
| `FOREIGN_IN_TABLE` | An `<svg>` or `<math>` inside a table or `<template>` | An `<img>` with the SVG file, or the SVG outside the table |
| `TABLE_IN_TEMPLATE` | A table part directly in `<template>` | Put the whole `<table>` in the `<template>` |
| `SELECT_CONTENT` | Anything but `<option>`, `<optgroup>` and `<hr>` in a `<select>` | Move it outside the `<select>` |
| `STRUCTURE_DEPENDS_ON_DATA` | A fragment that calls itself ever deeper (a recursive tree renderer) | A flat loop with depth classes, or `html.sanitize` to `SafeHtml` (4.4, Rich text) |
| `UNCALLED_FRAGMENT_FITS_NOWHERE` | Verifier-only: a fragment declared outside HTML content is the build error in the next row first | Declare it in HTML content |
| (build error) `t:fragment in <svg> content is not supported`, `~{::a} selects an element in <svg> content` | A `t:fragment`, or an element `~{::selector}` picks, inside `<svg>`, `<math>`, `<select>`, raw text (`<noscript>`, `<title>`, ...) or `t:remove="tag"` content | Declare the fragment outside it (directly under `<body>` or in its own file) and insert it there |
| (build error) `t:insert inside a <select t:field> is not supported` | `t:insert` or `t:replace` in a `<select t:field>`: inserted options would never be selected | Write the options inside the select (a `t:each`); for a partial, see 4.9 |
| (build error) `t:attr is not supported` | Thymeleaf's `th:attr`, which set several attributes from one value | One `t:<name>` per attribute: `t:attr="title=a, href=b"` becomes `t:title="a" t:href="b"` |
| (build error) `t:errorclass is not supported` | Thymeleaf's `th:errorclass`, which added a class when the `t:field` had errors | `t:classappend="fields.hasErrors(*{email}) ? 'has-error'"` with the element's `t:field` path; if the element has a `t:classappend` already, join both conditions into that one |
| (build error) `t:remove="body" is not supported` (also `all-but-first`, `none`) | A `t:remove` value other than `all` and `tag` | Edit the template to the result: delete the content, every child element but the first, or the attribute |
