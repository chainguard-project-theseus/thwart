# Porting guide: moving server-rendered Java views to Tessera

This guide is for a coding agent moving a Spring application's views (Thymeleaf, JSP, FreeMarker,
Mustache or similar) to Tessera, working from this document alone. It does not map your engine's
syntax construct by construct: you know your engine, and once you know how Tessera works the
syntax converts on its own. The hard parts are where Tessera's model differs (where data comes
from, how layouts and forms work) and knowing whether the result is right. Section 4.10 lists the
traps of particular engines (JSP with Tiles or SiteMesh, Velocity, Pebble).

Read section 1 (the target) and section 2 (the build), then follow section 3 (the loop). Sections
4 to 7 are reference: syntax, a worked example, every compiler diagnostic with its fix, and known
limitations.

**Done** means: the build and all existing tests pass; `tessera-migrate compare` reports every page
in your `pages.txt` as `same` or `reordered`, or you can say why a remaining difference is right;
security is not weakened (no `SafeHtml.unchecked`/`TrustedUrl.unchecked` to get around escaping,
no CSRF or escaping turned off); and the old engine's templates, dependency and configuration are
gone, with no `Tessera: another template engine is still configured` warning at startup.

Examples marked in the source of this file with `<!-- compile: ... -->` are compiled and rendered
by `dev.tessera.compiler.guide.GuideExamplesTest`, so they are known to work.

## 1. The target

Tessera templates are HTML files with `t:` attributes, close to Thymeleaf's `th:` syntax. An
annotation processor compiles them to Java classes during the application's normal `javac`, so a
template error is a build error with `file:line:col`. At runtime nothing parses or reads
templates. When the compiler rejects something, one of the rules below is the reason.

**A template sees only its typed parameters.** The first element declares them
(`t:params="Owner owner, List<Pet> pets"`), and with Spring each is read from the model attribute
of the same name, or else from the handler's `@PathVariable` of that name, as Spring's own views
do. Nothing else is visible: no request, session, application or bean scope, no request
parameters, cookies or headers. Engines you are leaving often fall back to
those scopes when a name is not in the model. Tessera doesn't: **a missing model attribute is
`null`**, which prints nothing, and reading a property of it fails the render with a
`NullPointerException` (write `a?.b` where `null` is expected). So before converting a template,
find everything it reads that the controller does not put in the model, and make the controller
provide it. Values every page needs (the current user, the request URI) come from `@ModelAttribute`
methods in a `@ControllerAdvice`. For Thymeleaf, `tessera-migrate` lists the reads of these
scopes that it can see, with where each value should come from (section 3, step 4).

**Templates read data; they don't run code.** A template reads record components and
`getX()`/`isX()` getters of *view-safe* types (records, enums, JDK value types, collections, and
classes annotated `@TemplateView`), plus built-ins such as `size()`. It cannot call other methods,
construct objects or reach static members. Logic lives in Java: a getter, a record the controller
builds, or a static `@TemplateFunction`. The recommended model is a view record per page, built by
the controller from its entities, rather than the entities themselves (section 4.2).

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
`temporals.*` (which follow the render locale) or your own function. `null` text renders empty and a `null` attribute is omitted,
but inside `|...|` and `+` a `null` prints `null`.

### Things Tessera deliberately doesn't do, and what to do instead

| If the old template... | Do this instead |
|---|---|
| reads the request, session, application scope, cookies or headers, or falls back to them | Put the value in the model and declare it in `t:params`. `tessera-migrate` reports the scope reads it can see (section 3), but not a plain name the old engine found in a request attribute. (A `@PathVariable` fills the param of the same name without this.) |
| calls a method with arguments, a static method, a bean or a constructor | A getter, a record built by the controller, or a `@TemplateFunction`. |
| filters, sorts, groups or projects a collection | Do it in the controller. |
| assigns a variable | `t:with="x=..."`: an immutable local for one element. |
| builds a view name, fragment name, message key or link path from strings | Conditionals between literals; link `{placeholders}`; a `Fragment` argument. |
| evaluates a string as a template (`?eval`, `#evaluate`, `__${...}__`) | Not possible. Compute the value in Java. |
| prints a string unescaped | Stored rich text: `t:utext="html.sanitize(article.text)"` (section 4.4). Markup built in Java: a `SafeHtml`. |
| puts expressions in `style`, SVG `fill`/`stroke`/`mask`/... or `on*` | `t:style` (or `t:fill`, ...) with literals, numbers and `css.color(...)`/`css.length(...)`/`css.url(...)`/`css.var(...)` (section 4.4); otherwise `t:classappend`; `t:data-*` attributes read by a script. |
| includes a file, calls a macro or tag with a body, or extends a layout | A fragment call with `Fragment` arguments (section 4.8). |
| binds a form through form tags or macros | `t:object`, `t:field`, `t:errors`, `fields.*` (section 4.7). |
| posts a form back to the current URL by default | Write the `action` with `@{...}`, or put the URL in the model. |
| reads an `Exception`, a `Date` or another non-view-safe value | Put what the page shows (a message `String`, a `LocalDate`) in the model. |

## 2. Build setup

The build needs JDK 8+ and Maven or Gradle, and the application runs on Java 8+. Tessera's CI compiles
applications with JDK 8 and JDK 21 javac; newer JDKs are expected to work but are not run in CI.
The annotation processor
(`tessera-compiler`) runs inside the application's javac, and it, `tessera-runtime` and the Spring
starter are Java 8 class files; the generated template code compiles with `release` 8. On Java 8
there are no records, so write the view types as classes with `getX()` getters and annotate them
`@TemplateView`. JDK 8's
javac has no `--release`: there, set `<source>` and `<target>` 1.8 (a Boot 1.5 or 2.x parent sets
them from `<java.version>`).

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
    <!-- no <release> under a Spring Boot parent: it comes from <java.version> (see below) -->
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
      <!-- the property tessera.messages, e.g. ${project.basedir}/src/main/resources/messages,
           which tessera-maven-plugin reads too -->
      <arg>-Atessera.messages=${tessera.messages}</arg>
    </compilerArgs>
  </configuration>
</plugin>

<plugin>
  <groupId>dev.tessera</groupId>
  <artifactId>tessera-maven-plugin</artifactId>
  <version>${tessera.version}</version>
  <executions>
    <execution>
      <goals><goal>check</goal><goal>review</goal></goals>
    </execution>
  </executions>
  <!-- only when tessera.templates is not the default:
  <configuration><templates>${project.basedir}/src/main/webapp/templates</templates></configuration> -->
</plugin>
```

`tessera.templates` (required) is the absolute templates root; every `**/*.html` below it is a
template. `tessera.package` is the generated package (default `tessera.generated`).
`tessera.messages` is the Spring message basename as a path without `.properties`; with it, every
`#{key}` is checked at build time. List several basenames with commas
(`.../msgs/TranslationMessages_de,.../ValidationMessages`). When the base file is partial or missing
and the full text is in a locale file (`messages_en.properties`), name that file instead
(`.../i18n/messages_en`): it means basename `messages` with default locale `en`. Use the locale
Spring falls back to (the system locale unless the app sets one). Facts about the build today:

- Templates compile in the application's own javac run, at its release level, so set the level
  in one place. Under a Spring Boot parent, leave `<release>` off the plugin and set
  `<java.version>`: the Boot 3+ parent passes it as `maven.compiler.release`, and the Boot 1.5 and
  2.x parents as `maven.compiler.source` and `target`. A `<release>` on the plugin overrides the
  parent, so `<release>21</release>` in an app with `<java.version>17</java.version>` builds class
  files, the templates included, that a Java 17 runtime cannot load. Without a Boot parent, set
  `<release>` (or the property `maven.compiler.release`) to the Java version the application runs
  on. The Boot 2+ parent already sets `<parameters>true</parameters>`.
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
- Check that the processor ran: after `mvn clean compile`, `target/generated-sources/annotations`
  holds a `*Template.java` per template under the `tessera.package` directory. If it is empty,
  `annotationProcessorPaths` was ignored, usually because the plugin is older than 3.5.
- The processor's generated resources, `META-INF/services/dev.tessera.runtime.TemplateRegistry`
  and `META-INF/tessera/functions/*`, must be packaged in the jar: a build that copies only
  `**/*.class` leaves them out, and every view then falls through (the starter warns "No compiled
  Tessera templates on the classpath" at startup instead of logging the template count).
- Static analysis that runs on compiled classes or all source roots (SpotBugs, PMD, Checkstyle,
  Javadoc) also sees the generated templates and flags them (redundant null checks, dead stores).
  Exclude the `tessera.package` package: a SpotBugs `excludeFilterFile` with
  `<Match><Package name="~com\.example\.templates(\..*)?"/></Match>`, PMD
  `<excludeRoots><excludeRoot>target/generated-sources/annotations</excludeRoot></excludeRoots>`,
  Checkstyle `<excludes>com/example/templates/**</excludes>`, Javadoc
  `<excludePackageNames>com.example.templates</excludePackageNames>`. Don't change the
  generated code or lower the tools' thresholds for the application's own code.
- The processor also runs in test compilation and regenerates the templates there. It finds
  `@TemplateFunctions` classes compiled earlier (the main classes, or an incremental compile that
  skips them) through the index it writes to `META-INF/tessera/functions/`, so no `<proc>none</proc>`
  is needed.
- `tessera-maven-plugin` makes a template-only or message-only change recompile. Maven runs javac,
  and so the processor, only when Java sources or the classpath change, so without it an edited
  template or message keeps its old generated class and its old build-time checks, and the build
  stays green. The processor records the templates and message files it read
  (`META-INF/tessera/templates.fingerprint` in the class output). The `check` goal runs before
  `compile` (phase `process-sources`), and when one of them was added, edited or deleted it deletes
  the module's compile output (`target/classes`, `target/test-classes`, the generated sources and
  the compiler plugin's incremental state), so `compile` builds the module again. Its `templates`
  parameter is the `tessera.templates` directory, by default
  `${project.basedir}/src/main/resources/templates`. Its `messages` parameter is the
  `tessera.messages` value, read from the property of that name, so set the property and pass it
  to the compiler as in the snippet. If the two differ, the goal warns and every build recompiles
  the module. It needs Maven 3.6.3+ and runs on Java 8+.
- The `review` goal lists the code Tessera trusts but cannot check, for a security review (#428).
  It runs after `compile` (phase `process-classes`, so `mvn package` runs it and `mvn compile`
  does not), reads the bytecode in `target/classes` and writes `target/tessera-review.txt`: every
  `@TemplateFunction` with its signature and source line, marked when it returns a safe type
  (`SafeHtml`, `TrustedUrl`, `Fragment`, `CssValue`), and every `SafeHtml.unchecked` and
  `TrustedUrl.unchecked` call at its source line, however the source writes it (qualified, static
  import or method reference). With `<configuration><failOnUnchecked>true</failOnUnchecked></configuration>`
  (or `-Dtessera.failOnUnchecked=true`) an `unchecked` call fails the build, naming its file and
  line. A hand-written `Fragment` lambda or `Template` is not listed. Gradle has no equivalent yet.
- Templates under `src/main/resources` are copied into the jar as resources, so resource
  filtering rewrites `${...}` in them unless `templates/**` is excluded.

**Gradle.** The same processor runs under Gradle, with no plugin. `examples/gradle` is a minimal
build that CI runs with Gradle 9 (`.chainguard/ci/gradle.yaml`). The wiring, in the Groovy DSL:

```groovy
dependencies {
  implementation "dev.tessera:tessera-spring-boot-starter:$tesseraVersion" // javax: -javax; without Spring: tessera-runtime
  annotationProcessor "dev.tessera:tessera-compiler:$tesseraVersion" // after Lombok and the other processors
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

- The `inputs` lines do what `tessera-maven-plugin` does under Maven. javac reads the templates
  and message files, but Gradle doesn't know that: without them, a template-only or message-only
  edit leaves `compileJava` UP-TO-DATE and the old generated classes stay in the build. With them,
  any edit reruns `compileJava`. Declare every message file `tessera.messages` names (with a
  locale file such as `messages_en`, that file).
- `annotationProcessor` is the processor path, like `annotationProcessorPaths`, and javac runs the
  processors in the order they are declared: declare Lombok, MapStruct and the others there too,
  Lombok before Tessera (the Lombok bullet above says why).
- Tessera does not declare itself an incremental processor to Gradle (no
  `META-INF/gradle/incremental.annotation.processors`), so Gradle recompiles the whole source set
  when a source changes (`--info` says "Full recompilation is required because ... TesseraProcessor
  is not incremental"). That is on purpose: the generated classes depend on template files and on
  every type the templates read, not on one annotated element (an isolating processor) or on the
  set of annotated elements (an aggregating one), so either declaration would let Gradle skip a
  regeneration that is needed.
- Gradle runs processors on test sources only if `testAnnotationProcessor` lists them; tests see
  the main generated classes without it. Templates are copied into the jar by `processResources`,
  unfiltered unless the build adds `expand` or `filter`.
- The release level is the build's own (`options.release` or the Java toolchain), and the
  templates compile at it.

**Spring Boot.** The starter's auto-configuration registers the view resolver, the `Template`
return-value handler and the template registry. Tessera's resolver runs before Thymeleaf's and
falls through for names it does not know, so the old engine can stay while you migrate. While it
stays, every view name Tessera doesn't know is rendered by it, with its injection risks: Tessera's
guarantees (no template injection, contextual escaping, CSRF token only in same-origin POST forms)
cover only the views Tessera renders, within the limits in the security model
(`docs/security-model.md` section 1). Removing the old engine (section 3, step 6) is what stops
unknown view names from reaching an engine that can be injected. While the other engine's view
resolver is still configured, the starter logs a warning at startup naming it (Thymeleaf,
FreeMarker, Groovy markup, JSP, Mustache, Pebble, script templates, Tiles and Velocity are
recognized; other engines, such as JTE, are not). There is no setting to silence the warning;
removing the engine does. JSP is reported by its `InternalResourceViewResolver`, except the one
Boot registers in every web app (`defaultViewResolver`) while `spring.mvc.view.prefix` and
`spring.mvc.view.suffix` are unset; set either and it serves JSPs, so it is reported too. A JSP
application that renders through Tiles is also reported by its `TilesViewResolver`. Never build a
view name from request data, whichever engine renders it. The jar registers itself for every
Boot generation, including `@WebMvcTest` slices.

**Spring MVC without Boot.** Import `dev.tessera.spring.TesseraConfiguration`. It needs only
`spring-webmvc`, and logs the same startup warning while another engine is configured:

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
`ResourceUrlEncodingFilter` for the dispatches Boot's Thymeleaf auto-configuration of the same Boot
version used, so `@{/webjars/...}` and other resource links stay versioned as before: on Boot 2.0.6
and later for REQUEST and ERROR dispatches (normal and error pages), on Boot 1.5 and 2.0.0-2.0.5
for REQUEST only (error pages keep unversioned links, as they had with Thymeleaf). Don't declare
the filter yourself; remove a hand-written one. Without Boot, map the filter in `web.xml`
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
- Boot 1.4 (Spring 4.3) isn't in the test suite, but apps on it have migrated with the javax
  starter unchanged; don't upgrade Boot. Its parent pins the compiler plugin's version directly,
  so the property above has no effect: write `<version>3.8.1</version>` on the
  `maven-compiler-plugin` itself, and check that the processor ran.
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
the model (`Integer status`, `String error`, `String message`, `String path`). Boot's `timestamp`
is a `java.util.Date`, which is not view-safe; declare it `Instant timestamp` or
`ZonedDateTime timestamp` and the starter passes it converted (a `Date` model attribute fills any
`Instant` or `ZonedDateTime` param; the zone is the JVM default, as for `Date.toString()`).
Thymeleaf's `${timestamp}` is `t:text="temporals.format(timestamp, 'EEE MMM dd HH:mm:ss zzz yyyy')"`
on a `ZonedDateTime`.

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

**3. Set up the build** (section 2). Give the templates view types: preferably a record per page
that the controller builds, or, as a shortcut, `@TemplateView` on the classes they already read
(section 4.2).

**4. Convert one template at a time.** Shared fragments and layouts first, since pages depend on
them. For each template: find every value it reads and make sure the model provides it; declare
the parameters with their real types; rewrite the markup (section 4); run `mvn clean compile` and
fix each error with the catalogue (section 6).

For Thymeleaf, `tessera-migrate` does the mechanical rewrite of a whole directory first:
`java -jar $MIGRATE <old-templates-dir> <new-templates-dir> --report migrate-report.md`. It
converts `th:` attributes, utility objects and common string-built links, splits a selection
over several properties (`*{firstName + ' ' + lastName}` becomes
`*{firstName} + ' ' + *{lastName}`), stubs `t:params` and fragment parameter types as `Object`
(which fails to compile until you write the real types),
fixes layout markup that nests a second `<body>` (section 4.8, Layouts), and lists what it could
not convert by `file:line`. In a JavaScript or JSON `<script>` it drops the
quotes around a `"..."` or `'...'` string that is only an inline (`"[[${x}]]"` becomes `[[${x}]]`,
which writes JSON with its own quotes) and reports any other `[[...]]` inside a string, template
literal or comment. In a `th:inline="javascript"` script it rewrites a natural-template comment
followed by one literal, `/*[[${x}]]*/ 'd'`, to `[[${x}]]`, as Thymeleaf rendered it, and reports
one followed by anything else (`/*[[${x}]]*/ a + b`). It reports a script whose code before the
last `[[...]]` the data-prelude rule rejects (a `/`, a backtick, ..., section 6), because moving the data
above the code is not mechanical. An element with `th:inline="none"` (Thymeleaf's way to leave `[[` alone) is not touched.
It reports a computed name that is not a known attribute (section 6), and rewrites the htmx
ones that htmx reads the same with `data-` (`th:hx-target` becomes `t:data-hx-target`; also
`hx-swap`, `hx-select`, `hx-confirm` and the other htmx selector, swap and text attributes),
in `th:attr` too. It adds a missing space between attributes (`href="/a"class="b"`).
It also reports each `th:value`, `th:href`, `th:src`, `th:action`, `th:name` and `th:type` whose
value may be `null`: Thymeleaf wrote the attribute empty, and Tessera omits it (section 4.3).
It reports each read of a scope the template cannot see, at its line, with the fix-it for the
controller, and leaves the read in place: `session.x` (`@SessionAttribute`), `param.x`
(`@RequestParam`), `application.x`, `_csrf` (the starter already adds the `_csrf` input to
same-origin POST forms), `#request`, `#httpServletRequest`, `#response`, `#session`,
`#servletContext`, `#ctx`, `#vars`, `#root`, `#execInfo`, `@bean` and `T(...)`. Each value moves
into the model and `t:params`; one that every page needs, such as the current user or the request
URI, comes from a `@ModelAttribute` method in a `@ControllerAdvice`. A `session`, `param` or
`application` root does not get an `Object` stub, and isn't reported where the template declares
the name itself (`t:params`, `th:with`, `th:each`, a fragment parameter). The list is the
controller-side checklist, but not a complete one: a plain name that Thymeleaf found in request
attributes rather than the model looks like any other parameter, and shows up in compare as an
empty value or a 500.

**Nulls are where a converted page most often fails at runtime.** A `null` link path variable
fails the render (a 500), where Thymeleaf wrote an empty segment: for an owner whose `id` is
`null`, `@{/owners/{ownerId}(ownerId=owner.id)}` rendered `/owners/` and now throws. Where a path
variable can be `null`, guard the link with `t:if`; `?: ''` is no fix, because an empty value is
rejected too (section 4.5). `tessera-migrate` reports every link whose path variable is not a
literal. Reading a property of `null` (`a.b` with a `null` `a`) fails as it did in Thymeleaf, so a
new `NullPointerException` there means `a` is `null` now and wasn't before, nearly always a value
the old template got from outside the model (section 1). Write `a?.b` where `null` is expected; a
`null` result prints nothing. Tests catch these only if a fixture holds the `null`, such as a list
entry that was never saved and so has no `id`: add one.

**5. Compare** against the new app, started from the same data:

```sh
java -jar $MIGRATE compare http://localhost:8080 pages.txt baseline/
```

It compares the status, the final URL after redirects, and the page as a browser would build it,
not the bytes: whitespace runs, attribute and class order, optional end tags, comments, CSRF
tokens and whitespace between JSON tokens in scripts (`[ "a" ]` against `["a"]`, but not
inside strings) don't count. Each page prints one line, `NN same|reordered|differs REQUEST`. `reordered`
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

**6. Remove the old engine.** This is what completes the security guarantee, not cleanup: until
its resolver is gone, any view name Tessera doesn't know, including one built from request data,
still reaches it, and the startup warning `Tessera: another template engine is still configured`
names it. Remove its templates, dependency and configuration. Build, test and compare once more.
The old engine's starter or tag library often brought in libraries the application still uses
(`spring-context-support`, with Spring's Ehcache, JCache and mail support, came with Boot's
Velocity starter; `commons-beanutils` and `commons-collections` with Tiles), and losing them may
show only at runtime. Save `mvn dependency:list` before removing it, diff it afterwards, and
declare what the application still imports or configures.

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
- The template comment is Thymeleaf's parser-level comment `<!--/* ... */-->`: the compiler drops
  it with everything inside, up to the first `*/-->`, so nothing in it is compiled or written. It
  may span lines and wrap markup. `<!-- /* ... */ -->` (with a space) is an ordinary comment. Inside
  `<script>`, `<style>`, `<title>` and `<textarea>`, where a browser reads it as text, `<!--/*` is
  an error. Thymeleaf's prototype-only comment `<!--/*/ ... /*/-->`, whose content Thymeleaf
  renders, is an error too: remove its markers (`tessera-migrate` does).

### 4.2 View types

Templates may read only **view-safe** types, checked at build time for every step of a property
chain.

**View-safe:** primitives and their boxes, `String`/`CharSequence`, enums, records, `java.time`
values, `BigDecimal`, `BigInteger`, `Optional`, `Iterable`, `Collection`, `List`, `Set`, `Map`,
`Map.Entry` and arrays of view-safe types, `LoopStatus`, `SafeHtml`, `TrustedUrl`, and classes
annotated `@TemplateView`. `Fragment` and `Field` are allowed as fragment parameters. A public
`java.util` type that implements `List`, `Set`, `Map`, `Map.Entry` or `Collection` (`ArrayList`,
`LinkedHashMap`, `TreeMap`, `SortedMap`, `TreeSet`, `ArrayDeque`, ...) is accepted as that
interface: a template reads it exactly as it reads a `List` or `Map`, and the class's own methods
(`ensureCapacity`, `pollFirstEntry`, `ArrayDeque.getFirst()`) are not reachable. Other packages'
collections (`java.util.concurrent`, Guava) and your own subclasses of them are not view-safe:
declare the getter's return type as the interface.

A view type must be **public**, and so must any class it is nested in and its type arguments:
templates compile to classes in their own packages, which can't reach a package-private record,
a `private` nested record or the `Hidden` in `Gen<Hidden>`. A public record nested in a public
class (`Outer.Row`) is fine.

**Never view-safe**, even if annotated: `Object` and its methods, `Class`, reflection, threads,
processes, `System`, files, scripting, servlet types, and Spring context, bean, environment and
expression types.

On a view-safe type a template can read:

- record components: `owner.name` calls `owner.name()`;
- public no-argument getters: `owner.name`, `owner.name()` and `owner.getName()` all call
  `getName()`; `isX()` for booleans (`new` is a keyword: write `pet.isNew()`). As in SpEL, the
  property `skill.isApproved` calls `getIsApproved()` (what Lombok generates for a `Boolean
  isApproved` field) if there is one, else `isApproved()`;
- built-ins: `size()`/`isEmpty()` on collections and maps, `length` on arrays, `length()` and
  `isEmpty()` on strings, `first`/`last` on lists;
- index access: `list[0]`, `array[i]`, `map['key']`.

Nothing else.

**Recommended: a view record per page.** The controller builds a record that holds exactly what
the page shows and passes that, not the entity:

<!-- compile: java -->
```java
package com.example.guide;

import java.util.List;

public record OwnerPage(long id, String name, String city, List<PetRow> pets) {}
```

<!-- compile: java -->
```java
package com.example.guide;

public record PetRow(String name, String type) {}
```

<!-- compile: template guide/owner.html -->
```html
<!DOCTYPE html>
<html xmlns:t="https://tessera.dev" t:import="com.example.guide.*" t:params="OwnerPage owner">
  <body>
    <h1 t:text="owner.name">Ann Lee</h1>
    <ul>
      <li t:each="p : owner.pets" t:text="|${p.name} (${p.type})|">Leo (cat)</li>
    </ul>
  </body>
</html>
```

```java
@GetMapping("/owners/{ownerId}")
String show(@PathVariable int ownerId, Model model) {
  Owner o = owners.findById(ownerId).orElseThrow();
  List<PetRow> pets = o.getPets().stream()
      .map(p -> new PetRow(p.getName(), p.getType().getName()))
      .toList();
  model.addAttribute("owner", new OwnerPage(o.getId(), o.getFirstName() + " " + o.getLastName(), o.getCity(), pets));
  return "guide/owner";
}
```

Why a record rather than the entity:

- **Lazy loading happens in the controller.** Spring Boot leaves open-session-in-view on by
  default, so a template that walks a lazy association runs a query while rendering, one per row
  inside a `t:each` (N+1); with it off, the same read throws `LazyInitializationException`
  halfway through the page. Building the record runs those reads where the query is visible and
  can be fetched in one go.
- **The template sees only what the record lists.** An annotated entity exposes every public
  getter to every template that receives it, including fields and associations added later
  (`getPasswordHash()`, `getOrders()`). A record's components are the whole surface.
- **Shaping lives in Java.** Formatting a name, flattening `p.getType().getName()` or picking the
  rows to show is plain, testable Java, which is where Tessera puts logic anyway.

On Java 8, where there are no records, write the view type as a small class with getters and
annotate it `@TemplateView`.

**Shortcut: `@TemplateView` on existing classes.** For a large port it can be quicker to annotate
the entities and domain classes the templates already read. That works, with the caveats above:
lazy associations load during rendering, every public getter is readable, and every class the
template reaches through a getter must be annotated too. Getters inherited from an unannotated
superclass (a JPA `@MappedSuperclass`) resolve through the annotated class:

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
  property path: Thymeleaf's `*{firstName + ' ' + lastName}` is `*{firstName} + ' ' + *{lastName}`.
  Utility objects are function namespaces (section 4.6); `#ctx`, `#request`, `#dates` and the rest
  don't exist.
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
  `t:remove` takes a literal (`all`, `body`, `tag`, `all-but-first`, `none`). `tag` is not allowed on
  `<script>`, `<style>`, `<title>` or `<textarea>`, whose content is text only inside their own
  tags, nor on `<svg>`, `<math>` or any element inside them, whose content a browser parses
  differently without the enclosing tag. The other values keep the tag, so they are allowed on these elements.
- Any `t:<name>` sets that attribute; `null` omits it, where Thymeleaf's `th:value`, `th:href`,
  `th:src`, `th:action`, `th:name` and `th:type` wrote it empty (`value=""`). Where the empty
  attribute matters, write `x ?: ''` for a `String` and `x == null ? '' : '' + x` otherwise.
  Boolean attributes (`t:checked`, `t:disabled`, ...) render `name="name"` or nothing. `t:utext`
  and `[(...)]` need a `SafeHtml` or a checked bundle message (section 4.5).
- Not supported: `th:attrappend`/`attrprepend`, `th:alt-title`, `th:lang-xmllang`, `th:assert`,
  `th:include` (use `t:insert`) and `th:inline` (in a `<script>`, `[[...]]` writes JSON; drop the
  quotes a Thymeleaf default-mode page put around it: `"[[${x}]]"` becomes `[[${x}]]`, and a
  natural-template comment with a default, `var a = /*[[${x}]]*/ 'd';`, becomes `var a = [[${x}]];`).

### 4.4 Escaping and output contexts

The compiler decides the context from the element and attribute name. You cannot override it.

| Context | Where | What happens |
|---|---|---|
| Text | element text, `t:text`, `[[...]]` | `& < > " '` escaped, and `{{` broken with an empty comment (below). |
| Title and textarea | text in `<title>` and `<textarea>` (also `t:field`), outside `<svg>` and `<math>` (there they are Text) | `& < > " '` escaped; `{{` is left as is. |
| Raw HTML | `t:utext`, `[(...)]` | Only `SafeHtml`, or a bundle message checked at build time (section 4.5), compiles. Not inside `<xmp>`, `<iframe>`, `<noembed>`, `<noframes>`, `<noscript>`, `<textarea>` or `<title>`, which a browser reads as text up to their end tag (nor are `t:insert` and `t:replace`). |
| Attribute | any other standard HTML, SVG, MathML or ARIA attribute, `data-*`, or a plain prop on a custom element | Escaped, always double-quoted. Any other computed name is a build error (below). |
| URL | `href`, `src`, `action`, `formaction`, `cite`, `poster`, `manifest`, `hx-push-url`, `hx-replace-url`, `values`, `to`, `from` and `by` of SVG `<animate>`, `to` of SVG `<set>` (`<set>` ignores `values`, `from` and `by`, but they are checked the same way; not when they animate a Style attribute, below), ... | Only `http`, `https`, `mailto`, `tel` and relative URLs pass; anything else becomes `about:invalid#tessera`. `TrustedUrl` passes as is. `values` of `<animate>` is a `;`-separated list and every item is checked; one bad item replaces the whole list with `about:invalid#tessera`. A `TrustedUrl` there vouches for every item. A computed `srcset` or `imagesrcset` holds a comma-separated list of image candidates, and a computed `ping` a whitespace-separated list of URLs. Tessera does not check these one by one, so they compile only as a `TrustedUrl`. |
| Resource URL | `script[src]`, `iframe[src]`, `embed[src]`, `object[data]`, `base[href]`, `link[href]` | Only a literal-path `@{...}` link or a `TrustedUrl` compiles. A `<base href>` must be same-origin (section 4.7). |
| htmx request URL | `hx-get`, `hx-post`, `hx-put`, `hx-patch`, `hx-delete`, `sse-connect`, `ws-connect` (also with `data-`) | Only a link whose literal path starts with a single `/` compiles, as for a refresh: htmx swaps the response into the page as HTML, and htmx 1.x also requests other origins. Static values are copied verbatim. htmx 1.x's `hx-sse` and `hx-ws` (`connect:<url>`) take no expression at all. |
| htmx form URL | `action` of a `<form>` inside `hx-boost`; `formaction` inside `hx-boost` or inside a `<form>` with an htmx request attribute | Same rule as an htmx request URL: htmx submits the form with AJAX and swaps the response in. `hx-boost="false"` on the form opts out. `hx-boost` itself cannot be computed. Links (`href`) are not affected. |
| Script | text in `<script>` (no type, JavaScript, module or JSON) | `[[...]]` writes JSON (strings, numbers, booleans, enums, `java.time`, records and `@TemplateView` beans, collections, String-keyed maps). Don't quote it: `var a = [[${x}]];`, not `"[[${x}]]"`: the value brings its own quotes, and an inline inside a JavaScript string (`"..."` or `'...'`) or comment is a build error (section 6): `[[...]]` goes only where a JavaScript expression goes. Before the last `[[...]]` the code may not use `/`, template literals or a few rare tokens (the data-prelude rule, section 6): declare the data first. |
| Style | `<style>` text, the `style` attribute, and the SVG attributes that take a CSS `url()`: `fill`, `stroke`, `clip-path`, `mask`, `filter`, `marker-start`, `marker-mid`, `marker-end`, `marker` and `cursor` (not `fill` on `<animate>`, `<set>`, `<animateMotion>`, `<animateTransform>` or `<animateColor>`, which is timing); `values`, `to`, `from` and `by` of an animation whose `attributeName` is one of them | In `<style>`, expressions are a build error. A `style` attribute or one of those SVG attributes takes only string literals, numbers and `CssValue`s, combined with `+`, `\|...\|`, `?:` and `? :`; anything else (a `String` value) is a build error. Write `t:fill="${css.color(c)}"` or `t:mask="${css.url(u)}"`. The `attributeName` of an animation element cannot be computed. |
| Refresh | `content` of `<meta http-equiv="refresh">` | Only `'<seconds>;url=' + @{/path...}` compiles: a literal delay and a link whose literal path starts with a single `/`, so the refresh stays on the same origin. Path variables and parameters are encoded as usual, and `; , ' "`, `\` and whitespace in the URL are percent-encoded. Rendered as `<seconds>;url=<url>`. |
| Vue/Alpine binding | `:x`, `v-bind:x`, `x-bind:x` whose target `x` is an ordinary attribute (below) | Only a value `[[...]]` accepts in a script compiles; it is written as the same JSON, then attribute-escaped. `null` is written as `null`. |
| Event handler | `on*` attributes, and framework directives (below) | Build error; put data in `data-*` attributes and read them from a script. |
| Forbidden | `srcdoc`, non-JS `<script type>` | Build error. `<script type>`, `<meta http-equiv>` and the `attributeName` of an SVG animation element cannot be computed. |

A browser drops a line feed right after a `<pre>`, `<listing>` or `<textarea>` start tag. When an
attribute such as `t:text`, `t:utext`, `t:field` or `t:insert` sets the element's content, or the
content starts with `[[...]]`, a `t:if` or a `t:each`, Tessera writes one line feed after the start
tag, which the browser drops: the value is shown exactly, a value's own leading line feed
included, and so is a line feed written after the expression, also when the value is empty.
`<textarea t:field="*{bio}">` renders as `<textarea id="bio" name="bio">` + line feed + the value,
so a test that matches the rendered bytes (`">Main St</textarea>"`) needs the line feed
(`">\nMain St</textarea>"`). Thymeleaf's `th:field` on a `<textarea>` adds a line feed only when the
value starts with one, and `th:text` and `[[...]]` add none, so there the browser drops a value's
leading line feed, or the template's line feed after an empty value or a loop with no items.
Content that starts with static text is written as is.

Client-side frameworks run some attribute values as JavaScript once they mount the element, so
attribute escaping does not protect them and an expression there is a build error, as for `on*`:
Vue's `v-*` (every directive, `v-html` included) and its `:x`, `@x`, `#x` and `.x` shorthands;
Alpine's `x-*`; htmx's `hx-on*`, `hx-vals`, `hx-vars`, `hx-headers`, `hx-request` and `hx-trigger`
(also with `data-`); _hyperscript's `_`, `script` and `data-script`; AngularJS's `ng-*`, `ng:*`
and `ng_*` (also after `x` or `data` followed by `-`, `:` or `_`, and after leading `-`, `:` or `_`, as in `data:ng-click`, `x_ng-click`, `data--ng-click` or `_ng-click`); and Knockout's `data-bind` and `params` on a custom element. Static directives are copied verbatim, shorthand names included: write
`<api-entry :security="security" @click="open">` as in the old page.

The one exception is a binding that hands a value to a component or attribute: Vue's
`v-bind:x` and `:x`, and Alpine's `x-bind:x`. `t::security="${api.securities}"` compiles when the
value is one a script `[[...]]` accepts (strings, numbers, booleans, enums, `java.time`, records
and `@TemplateView` beans, collections, arrays, Optionals, String-keyed maps). It is written as the
same JSON as in a script, then attribute-escaped, so a string is always a string literal and the
framework gets the value, never code: `:security="[{&quot;name&quot;:&quot;a&quot;}]"`. A Pebble or
JSP `:prop='{{ obj | json | raw }}'` (or a Java-built JSON string) becomes `t::prop="${obj}"`; pass
the object, not a JSON string, which would arrive as a string. Any other type is a build error.
Only these bindings take JSON; anything else stays a build error:

- a target `x` that is not an ordinary attribute: `on*`, `style`, URLs (`:href`, `:src`,
  `:action`, ...), resource URLs, `srcdoc`, another directive (`:v-html`, `:x-data`, `:hx-get`),
  `innerHTML`, `outerHTML` and `is`, and the parts of a link's URL (`protocol`, `host`,
  `hostname`, `port`, `pathname`, `search`, `hash`, `username`, `password`), which Vue sets as
  properties;
- modifiers and dynamic names: `v-bind:x.prop`, `:x.camel`, `.x`, `:[key]`;
- `v-bind="obj"` and `x-bind="obj"` with no target;
- any binding on `<script>`, `<style>` or `<meta>`.

Otherwise, to hand server data to a component, put it in a `data-*` attribute or a `<script>`
with `[[${...}]]` and read it from there. Other
`data-*` attributes are ordinary attributes, and so are htmx attributes that hold selectors or text
when written with `data-` (`t:data-hx-target`, `t:data-hx-swap`, `t:data-hx-select`,
`t:data-hx-confirm`), which htmx reads like `hx-target`. Port `th:hx-get="@{/items/{id}(id=${id})}"`
to the same `t:hx-get`; a URL computed in Java (`t:hx-get="${url}"`) has to become a link.

A computed attribute (`t:<name>` or a `t:attr` entry) must have a name the compiler knows: a
standard HTML, SVG, MathML or ARIA attribute, a `data-*` attribute, a plain `[a-z][a-z0-9]*` prop on
a custom element (an element whose name has a `-`), or one of the framework attributes above. Any
other name is a build error, because a client library may run its value as code (Unpoly's
`up-on-loaded`) or fetch it (htmx 4's `hx-action`), and the compiler cannot know which. Static
attributes are not checked and are copied verbatim. If the value is data for your script, use
`t:data-<name>`; a computed `t:hx-target` becomes `t:data-hx-target`.

Inside `hx-boost` htmx submits every form with AJAX to its `action` and swaps the response into
the page as HTML, without checking the origin (htmx 1.x, or 2.x with `selfRequestsOnly` off), and a
submit button's `formaction` replaces that URL in any form htmx submits, `hx-post` and the like
included. So where the template itself shows the boost (a static `hx-boost` or `data-hx-boost` on
the form or an ancestor in the same template, nearest wins), a computed `t:action`, and a computed
`t:formaction` there or inside a `<form>` with `hx-get`, `hx-post`, ..., takes only a link whose
literal path starts with a single `/`: `t:action="@{/owners/{id}/edit(id=${owner.id})}"`. To post a
boosted page's form somewhere else, put `hx-boost="false"` on the form; it then submits natively
and takes any URL. `hx-boost` cannot be computed: write `true` or `false`. Links need nothing:
htmx boosts an `<a>` only when its hostname is the page's, so `t:href="${user.website}"` in a boosted
nav stays an ordinary URL.

Vue (2 and 3), petite-vue and AngularJS compile the page's HTML as a template when they mount
it, so a `{{ ... }}` in printed user data would run as an expression, escaped or not. Element
text therefore never contains `{{` from a value: Tessera writes an empty comment between two
braces, before a value's first `{` and after its last (`{{x}}` renders as
`<!---->{<!---->{x}}`). Nothing visible changes: the page, `textContent` and copied text are the
same, and `tessera-migrate compare` ignores comments. This happens in every template, whether or
not it shows a mount point, since the app is often mounted from JavaScript or in a layout. The
same applies to `SafeHtml.escape`, `html.sanitize` (which also percent-encodes `{` in the links it
keeps) and the arguments of an HTML message. An expression inside a static `{{ ... }}` in the
template is a build error, since the framework would run the value as code: pass it in a
`data-*` attribute or a `<script>` with `[[...]]`. A static `{{` with no `}}` after it in the
same element is a build error too, since a value written after it, here or where a template
inserts the element as a fragment, would be inside it: write a literal `{{` as `{<!---->{`. The `}}`
may be in a child element (`<p>{{ <b>x</b> }}</p>`), but `<b>{{</b> }}` is an error; a `}}` that
may not render where the `{{` does, such as one inside `t:if`, does not close it. A `<script>` with `[[...]]` stays a safe place for a
value: its JSON writes `{` in a string as `\u007b`, so petite-vue, which interpolates script text,
never sees `{{` there (Vue and AngularJS do not interpolate script text). A value after a `{{` with
no `}}` between them in the same script is a build error. Attribute values and `<title>`/`<textarea>` text are not changed (section 7).

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
and all other attributes (`on*`, `style`, `class`, `id`, `target`, ...).

For editor or Markdown output that needs more, write `html.sanitizeRich(article.text)`
(`HtmlPolicy.RICH.sanitize(text)` in Java). It also keeps `del ins kbd`; images (`img` with a `src`
that is a same-origin path, `alt`, `title`, `width`, `height`); tables (`table caption thead tbody
tfoot tr th td` with `colspan`, `rowspan`, `align`); `ol start reversed` and `li value`; `title`;
`language-*` class tokens (code highlighting); and `id`, written as `user-content-<id>` so stored
content can't shadow `window` or `document` properties or the page's own ids, with `href="#<id>"`
links rewritten to match. Links keep `target="_blank"`. An image `src` must start with a single
`/` (not `//` or `/\`), so that showing stored content never makes the reader's browser contact
another host; other images are dropped. An attribute value containing `{{` is dropped.

For any other mix, build a policy once in Java with `HtmlPolicy.builder()` and expose it through
your own function namespace (section 4.6):

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
          .allowElements("p", "br", "b", "a", "table", "tr", "th", "td")
          .allowAttributes("colspan", "rowspan")
          .allowClasses("status-*")
          .linkRel() // the application writes these notices itself: add no rel
          .build();

  private NoticeFunctions() {}

  @TemplateFunction
  public static SafeHtml html(Object text) {
    return SafeHtml.sanitize(text == null ? null : text.toString(), POLICY);
  }
}
```

Then `t:utext="notices.html(notice.body)"`. The builder accepts only names from a closed set
(`HtmlPolicy.ELEMENTS`, `HtmlPolicy.ATTRIBUTES`, `REL_TOKENS`) and throws for anything else, so
`style`, `on*`, framework directives and script-capable elements can't be switched on. Its other
options are `allowClasses` (exact names, `prefix*` or `*`; only letters, digits, `-` and `_`, and
never a name AngularJS reads as a directive: `ng-*`, `ng_*` or `ngX`, also after one `x` or `data` prefix followed by `-` or `_` and after a run of `-` and `_`, as in `data-ng-bind`, `data--ng-bind` or `_ng-bind`),
`idPrefix` (a letter first, ending in `-`), `allowLinkTarget` (`target="_blank"` with `noopener`),
`allowRemoteImages` (any `img src` that passes the URL check, not only same-origin paths, so
every reader's browser fetches from hosts the content's author picks, who can then track them) and
`allowDataImages` (base64 PNG, GIF, JPEG and WebP `data:` URLs in `img src`). Allow the classes
the content needs by name: with `*`, content can use any of the page's own CSS classes, to cover
the page or dress up as its controls.

Under every policy the output is rebuilt, never copied through, and balanced: every element is
closed, and placed only where a browser's parser keeps it as written. A block element closes an
open `p`, heading or inline element first; `a` never nests in `a`; `li` is kept only in `ul`/`ol`;
table parts only inside a `table`, with the `tbody` and `tr` a browser would add written out, and
no text directly in a table, section or row. A tag that fits nowhere is dropped and its text kept.
So the output works as flow content wherever a `div` could hold it. Text is re-escaped
(`{{` broken), and character references are decoded (`&oacute;` stays `ó`).

In Java, `SafeHtml.escape(text)` escapes text, `SafeHtml.of(template, ctx)` wraps another template's
output, and `SafeHtml.concat(a, b, ...)` joins parts (`concat(escape(a), sanitize("<br>"),
escape(b))`). `SafeHtml.unchecked(html)` is a review point, and `TrustedUrl.unchecked(url)` is the
same for URLs. The `tessera:review` goal lists every call of either, and can fail the build on one
(section 2).

`SafeHtml.of(template, ctx)`, and `Fragment.of(template)` for a `Fragment` value, accept only a
template the compiler verified as a flow partial: one whose output can't close or reopen anything
around the place it is inserted. A `div`, `section`, `span` or `a` partial, text, lists and
tables inside it are fine. Not accepted: a whole page; a partial whose root is an `li`, `dd`,
`dt` or table row or cell, or that has one outside its own `ul`, `dl` or `table` (even inside a
`div`); and a partial with a `form`. Anything else throws an `IllegalArgumentException` naming
the template. The fix
is to wrap the root in a `<div>` (list items in their own `<ul>`, rows in their own `<table>`),
move the `form` out, or render the page through a view. For markup used as data rather than
inserted into a page (mailed, or written into an attribute), `template.renderToString(ctx)` gives
a `String`, which a template escapes. A hand-written `Fragment` lambda is a review point, like
`unchecked`.

CSS has no escaping that makes an arbitrary string safe, so a `style` attribute is built only from
pieces that cannot end a declaration or call an unchecked `url(...)`: literals, numbers and `CssValue`s.
`css.color(s)` and `css.length(s)` (or `CssValue.color`/`CssValue.length` in Java) validate a
string and return `null` for anything else, so user data such as a category colour goes through
them: `t:style="|background-color: ${css.color(category.color)}|"`. An invalid colour prints
`background-color: null`, which the browser ignores as an invalid declaration; a `t:style` that is
only a `null` value is omitted. Choices between literals work as before:
`t:style="${soldOut} ? 'opacity:.5' : 'opacity:1'"`. Besides hex and named colours, `css.color`
accepts `rgb`, `rgba`, `hsl` and `hsla` with three or four numbers, each with an optional `%`,
`deg`, `rad`, `grad` or `turn`, separated by commas (`rgb(0, 128, 255)`,
`hsla(120deg, 50%, 50%, .5)`) or by spaces with the alpha after `/` (`rgb(0 128 255 / 50%)`). It
writes them lower-cased, rebuilt from the numbers. `css.var(name)` (`CssValue.var`) takes a custom
property name, `--` and an identifier of ASCII letters, digits and `-` not starting with a digit,
and writes `var(--name)`: `t:style="|color: ${css.var(theme.accent)}|"`. Nothing else is
accepted: no `calc(...)`, nested functions, keywords such as `none`, `var` fallbacks, comments,
escapes, quotes, `;`, braces, `<`, `>` or `@`. With numbers in the model, write the function as a
literal around them instead: `t:style="|color: rgb(${r}, ${g}, ${b})|"`. For anything else,
compute a hex colour in Java or use a class. For a background image such as an
uploaded avatar, `css.url(s)` (`CssValue.url`) writes `url("...")`:
`t:style="|background: ${css.url(user.avatar)} no-repeat center|"`. The URL must be relative,
protocol-relative (`//cdn/a.png`) or `http`/`https`; `data:`, `javascript:`, every other scheme,
control characters (newlines included) and URLs over 4096 characters give `null`. Quotes,
backslashes, parentheses, `*`, spaces, a stray `%` and non-ASCII are percent-encoded, so the value
cannot leave the quoted URL.

The SVG attributes that take a CSS value with a `url()` follow the same rule: `fill`, `stroke`,
`clip-path`, `mask`, `filter`, `marker-start`, `marker-mid`, `marker-end`, `marker` and `cursor` on an SVG
element. Write `t:fill="${css.color(series.color)}"` or `t:mask="${css.url(m)}"`; `t:fill="${color}"`
with a `String` is a build error. `fill` on `<animate>`, `<set>`, `<animateMotion>`, `<animateTransform>`
and `<animateColor>` is timing (`freeze`), not CSS, and stays an ordinary attribute.
An animation of one of them sets that CSS, so its `values`, `to`, `from` and `by` follow the same
rule: `<animate attributeName="fill" t:values="|red;${css.color(c)}|"/>` compiles, `t:values="${s}"`
with a `String` does not. The `attributeName` of `<animate>`, `<set>`, `<animateMotion>`,
`<animateTransform>` and `<animateColor>` cannot be computed: it decides whether those values are
CSS or a URL.

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
it is a build error instead. A key is known when it is in a basename's base file or its default
locale's files (section 2; for `pt_BR`, `messages_pt_BR.properties` and then
`messages_pt.properties`); other locale files (`messages_fr.properties`) may leave keys out, and
Spring resolves those as it did before the port. That fallback is what
`ReloadableResourceBundleMessageSource` does. Spring Boot's default `ResourceBundleMessageSource`
falls back to the default locale only for a locale that has no file of its own, so for a key that
is not in the base file the build warns, naming the locale files that lack it. A locale file is
named as Spring names it: `messages_fr`, `messages_pt_BR`, `messages_zh_Hant_TW`,
`messages_en__POSIX` (a variant after an empty country), with a known language code, so
`messages_faq.properties` is not a locale file. A key computed from data
(`'criterion.' + c.code`) has no template form: resolve those messages in Java with Spring's
`MessageSource` and the request `Locale` (in the controller or a `@ModelAttribute` method) and pass
the strings in the model. A
`@TemplateFunction` can't see Spring beans, and sees the locale only as a leading `Locale`
parameter (section 4.6); don't reach them through `RequestContextHolder` or other static state.

**Messages with markup.** A bundle is part of the application, so a message that holds markup
(Thymeleaf `th:utext="#{key(args)}"`, JSP `<spring:message>` without escaping) can be printed as
HTML: `t:utext="#{key(args)}"` or `[(#{key(args)})]`. It needs `-Atessera.messages`; the compiler
then checks the key's value in the base file and in every locale file next to it
(`messages_fr.properties`, `messages_pt_BR.properties`, ...) of every basename and fails the build,
naming the file and key, unless the value is markup from this allow-list:

- elements `p br hr h1`-`h6 b i em strong u s sub sup small mark code pre blockquote ul ol li dl dt
  dd span div a abbr cite q kbd del ins`; no comments, `script`, `style` or anything else;
- attributes `class id title lang dir`, plus `href target rel hreflang` on `a`, with quoted
  values; an `href` must be relative or `http`/`https`/`mailto`/`tel`, with no `&` other than
  `&amp;` and no whitespace or control characters inside it; spaces, tabs and newlines at either
  end are allowed (`href=" https://..."`), because browsers strip them, and the scheme is checked
  without them; no `style` and no `on*`;
- every end tag matches, and every element is closed except `p li dt dd`. `<p/>` is a `<p>` and is
  printed as written, as Thymeleaf does, but `/>` on an element that isn't void is a parse error,
  which the build rejects (section 7): write `<br>`.
- the message does not end with `<` (write `&lt;`): the template text after it would continue it.

Arguments are HTML-escaped before they are formatted into the message (numbers and `Date`s are
formatted by `MessageFormat` as usual), so `{0}` is always text. Placeholders must be `{n}`,
`{n,number[,integer|currency|percent]}` or `{n,date|time[,short|medium|long|full]}`, and may not
appear inside a tag. The value is checked both as written and as `MessageFormat` formats it, where
`'` quotes text: use double quotes around attribute values and `''` for an apostrophe. At
runtime the message is printed as HTML only if it contains no `<` and every `&` in it starts
`&amp;`, `&lt;`, `&gt;`, `&quot;` or `&#39;` (a bare `&` and an argument `#123;` would make `{`),
or is exactly what one of the checked values produces; a message from a source the build did not check (another directory or
jar, a database-backed `MessageSource`, a file changed after the build) is escaped as text. Where a message's markup does not fit
the allow-list, move the markup into the template and keep the text in the bundle.

<!-- compile: messages -->
```properties
phaseout.notice=This version is being phased out: see <a href="/phaseout" class="alert-link">the plan</a>.<br>Save your work.
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
links must be `http`/`https`. Links get the context path and `response.encodeURL`. As in
Thymeleaf, a `null` query value renders `name=`, a parameter with no value renders the bare name
(`@{/cart(add, itemId=i.id)}` is `/cart?add&itemId=...`), and a collection repeats the parameter
per element (`?t=a&t=b`; a `null` element is the bare `t`). A `null` path variable fails the
render, where Thymeleaf wrote an empty segment (`/owners//edit`); so does an empty one, `.` or
`..`. Guard a link whose path variable can be `null` with `t:if`. A URL computed in
Java goes in `t:href` as a `String` (scheme-checked) or `TrustedUrl`, without the context path. A context path of `/` or one ending in `/` loses the
trailing `/`, so `@{/a}` never renders as the protocol-relative `//a`. Where a link must be
same-origin (a `<base href>`, an htmx request URL, a refresh URL, a boosted or htmx form's
`action`/`formaction`, a `formaction` that may receive the CSRF token; sections 4.4 and 4.7), the built URL
is checked again when rendering: if a custom `Links` makes it anything but a path starting with a
single `/`, the render fails.

### 4.6 Functions

Templates call functions as `namespace.fn(args)` or `#namespace.fn(args)`.

| Namespace | Functions |
|---|---|
| `strings` | `isEmpty`, `isBlank`, `defaultString(s, def)`, `abbreviate(s, max)`, `toUpperCase`, `toLowerCase`, `capitalize`, `trim`, `contains(s, part)`, `startsWith`, `endsWith`, `substring(s, start[, end])`, `replace(s, before, after)`, `join(iterable, sep)` |
| `numbers` | `formatInteger(n, minDigits[, thousandsPoint])`, `formatDecimal(n, minIntDigits, decimals[, decimalPoint])`, `formatDecimal(n, minIntDigits, thousandsPoint, decimals, decimalPoint)`, `formatPercent(n, minIntDigits, decimals)`, `formatCurrency(n)`, `sequence(from, to)` (a `List<Integer>`); see below |
| `temporals` | `format(temporal, pattern)` (month and day names in the render locale), `formatIso(temporal)` |
| `lists` | `size`, `isEmpty`, `contains(list, x)` |
| `css` | `color(s)` (`#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`, a CSS named colour), `length(s)` (a number with optional `px`, `em`, `rem`, `%`, `vh`, `vw`), `url(s)` (`url("...")` for a relative or `http`/`https` URL, percent-encoded): a `CssValue`, or `null` for anything else |
| `html` | `sanitize(s)`: stored rich text as `SafeHtml` for `t:utext`; `sanitizeRich(s)`: the same with same-origin images, tables, `language-*` classes and `id` (section 4.4) |
| `auth` (Spring starter) | `isAuthenticated()`, `hasRole('ADMIN')`, `hasAnyRole('A', 'B')` (`ROLE_` is added unless present), `hasAuthority('PERM_X')`, `hasAnyAuthority('A', 'B')` (compared exactly), `name()` |
| `fields` (inside `t:object`) | `hasErrors('path')`, `hasErrors('*')`, `hasErrors('global')`, `errors('path')`, section 4.7 |

All built-ins are null-tolerant. `numbers.*` and `temporals.format` format in the render locale
(`RenderContext.locale()`, which the Spring starter takes from the `LocaleResolver`), as
Thymeleaf's `#numbers` and `#temporals` do; `RenderContext.empty()` has `Locale.ROOT`, which
formats numbers with `.` as the decimal point and `,` for grouping, and month names in English.
Without a point type `formatInteger` and `formatDecimal` don't group and use the locale's decimal
point. A point type is one of the literals `'POINT'` (`.`), `'COMMA'` (`,`), `'WHITESPACE'` (a
space) or `'DEFAULT'` (the locale's), and for thousands also `'NONE'` (no grouping):
`numbers.formatInteger(n, 1, 'COMMA')` prints `-2,550`, and
`numbers.formatDecimal(n, 1, 'POINT', 2, 'COMMA')` prints `1.234,50` in any locale. Another
string fails the render. `formatPercent(0.125, 1, 1)` is `12.5%` in English, and
`formatCurrency` uses the locale's currency, so it needs a locale with a country (`en_US`, not
`en`). Unlike Thymeleaf, `numbers.*` rounds half up.

Add your own namespace with a class in your application:

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
value whose type is not never-view-safe (`Object` is allowed for parameters). A leading
`java.util.Locale` parameter is not a template argument: the compiler passes the render locale, so
`public static String day(Locale locale, LocalDate d)` is called as `fmt.day(d)`. The class must be in
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
  `<select>` and `<textarea>`. It writes `id` (unless present), `name` and the
  `BindingResult`'s display value (omitted when null; never on passwords). Checkboxes and radios
  get `checked`, options of a `<select t:field>` get `selected` when their value matches (none
  when the bound value is `null`, so a placeholder `<option value="">` stays unselected). Bound to
  a collection, array or map (`Set<String> roles`, `<select multiple>`), every input or option
  whose value is an element (key) matches, as with Thymeleaf's `th:field`; an enum matches by name
  or `toString()`. Every checkbox that is not `disabled` is followed by Spring's `_name` hidden
  marker, so unchecking all boxes binds `false` or an empty collection, and every
  `<select multiple>` that is not `disabled` is preceded by one
  (`<input type="hidden" name="_roles" value="1" />`), so deselecting every option binds an empty
  collection, as in Thymeleaf. A static `selected` on such an option is replaced, as in Thymeleaf;
  a `t:selected` *replaces* the bound-value match for that option: the option is selected exactly
  when the expression is true, whatever the bound value. To keep the match and add a default,
  write the match into the expression, for example
  `t:selected="${curr == form.currency || (form.currency == null && curr == defaultCurrency)}"`.
  Options without a `t:selected` are still matched. (Thymeleaf does the opposite: its `th:field`
  match overrides a `th:selected` on the option, so a `th:selected` there has no effect; drop it
  when porting such a select.)
- A field whose type is not view-safe, such as a `MultipartFile` or `List<MultipartFile>`, can
  be bound wherever its value is never read: `t:field` on `<input type="file">` (which writes
  only `id` and `name`, as Thymeleaf's does) or `type="password"`, `t:errors`, `t:errorclass` and
  `fields.hasErrors(...)`/`fields.errors(...)`. The path is still checked, and every step before
  the last must be view-safe (`'file.originalFilename'` is rejected). Anything that reads the
  value is rejected: `t:field` on any other input, a `<select>` or a `<textarea>`, `t:value`,
  `[[*{file}]]`, or passing the field to a `Field` parameter.
- Checkboxes and radios get a numbered `id`, as in Thymeleaf and Spring's JSP tags: the field
  name (without `[` and `]`) and a counter per name that starts at 1 for every render, so the
  radios of `t:field="*{roles}"` are `roles1`, `roles2`, … in page order (a boolean checkbox
  too: `active1`). An input with its own `id` (or `t:id`) keeps it and takes no number. Point a
  label at one with `ids` (Thymeleaf's `#ids`), which shares the counter: `ids.prev('roles')` is
  the id the last input got (a label after its input, `<label t:for="${ids.prev('roles')}">`;
  before any input the render fails, as in Thymeleaf), `ids.next('roles')` the id the next input
  will get, without counting it (a label before its input), and `ids.seq('roles')` counts and
  returns a new one. The argument is the field name without `[` and `]`.
- `t:errors` renders the field's messages escaped and joined by `<br />`, or removes the element
  if there are none (`*` for all). For other markup, loop over `fields.errors('email')`;
  `fields.errors('email').first` is the first message (JSP `spring:bind`'s
  `status.errorMessage`); `first` fails on an empty list, so guard it with
  `t:if="fields.hasErrors('email')"`.
- `fields.value('email')` is the field's display value, the String `t:field` writes: after a
  failed bind the rejected value, otherwise the value formatted through the field's property
  editor or conversion service (`@DateTimeFormat`), and `''` for null. This is JSP `spring:bind`'s
  `status.value` (Spring's `BindStatus.getDisplayValue()`) and Thymeleaf's double-brace
  `*{{email}}` inside `th:object`, which `tessera-migrate` rewrites to it. It is a String, so it
  is escaped as usual. The field's type must not be on the deny-list, as for `t:field`, and `'*'` and `'global'`
  have no value.
- `global` instead of a path means the form object's own errors (`BindingResult.reject(...)`,
  a class-level constraint), without the fields' errors: `t:errors="*{global}"` (or
  `t:errors="global"`), `fields.hasErrors('global')`, `fields.errors('global')`. These are
  Thymeleaf's `th:errors="*{global}"`, `#fields.hasGlobalErrors()` and `#fields.globalErrors()`.
  As in Thymeleaf, `'global'` always means this, even if the form object has a property named
  `global`.
- `t:errorclass="error"` next to `t:field` appends the class when the field has errors.
- `fields.*` arguments are string literal paths (or a field expression, below). A
  `<form method="post">` gets the CSRF input automatically when Spring Security provides a token,
  and so does a form whose computed method (`t:method`, `t:attr="method=..."`) is `post` (any
  case) at render time, as in Thymeleaf. Computing the method does not opt a form out of the token.
  The method must be exactly `post` as a browser reads it: `method=" post"` submits as GET and gets
  no token, so the token never ends up in a URL. The token also goes only into a form that posts to
  the page's origin: no `action`, or a relative one (`@{/owners}`, `/owners`, `?page=2`), decided
  at render time when the action is computed. A form that posts elsewhere
  (`action="https://pay.example/..."`, or a computed URL with a scheme or starting with `//`) gets
  no token, so the session's token is never sent to another site. An absolute URL to the
  application's own host counts as elsewhere, and Spring Security then rejects the POST: write it
  as `@{/...}`. A submit button sends the form, token included, to its `formaction`, so a button
  that may submit a form with the token (inside a POST form that may get it, with a `form`
  attribute, or in a fragment, which can be inserted into such a form) takes only a same-origin
  `formaction`: a computed one must be a link with a literal path starting with a single `/`
  (`t:formaction="@{/items/{id}/delete(id=${item.id})}"`), a static one must be same-origin like a
  static `action`, and `formmethod` must be a static `post` or `dialog` (anything else submits as
  GET with the token in the URL). Anything else is a build error: use a link, or move the button
  into its own form. Inside a GET form, or a form with a static cross-origin `action`, which gets
  no token, `formaction` and `formmethod` are unchanged, except in a fragment (a browser drops the
  start tag of a form inserted into another). A form with `t:remove="tag"` gets no token. A static `hx-post` is
  not checked (section 7); a `<form>` nested in another is a build error (`PARSE_ERROR`). Every relative URL on the page, a form's `action` and a button's
  `formaction` included, resolves against `<base href>`, so a `<base>` must be same-origin, in any
  template (a layout's `<base>` applies to the forms its fragments insert): a static `href` like a
  static `action` (`/app/`, `app/`), a computed one a link with a literal path starting with a
  single `/` (`t:href="@{/app/}"`). Anything else, a `TrustedUrl` included, is a build error;
  where another origin is meant, drop `<base>` and write absolute URLs.
  A form that writes its own token input keeps it and gets no second one: a direct child
  `<input type="hidden">` whose literal `name` equals the token's parameter name at render time
  (`_csrf` by default), as FreeMarker and JSP pages write it to read the token by id
  (`<input type="hidden" name="_csrf" id="token" t:value="${token}" />`, with the token's value
  passed as a parameter). It counts only if it is always written and submitted with the form: a
  static `type="hidden"` and `name`, no `t:` attribute other than `t:value` and `t:id`, no
  `disabled` or `form` attribute, and the form's body not replaced (`t:text`, `t:remove="body"`).
  Any other input (another name, `t:name`, under `t:if`, inside a `<div>`) leaves the automatic
  one in place, so a POST form never ends up without a token unless it writes one. Thymeleaf has
  no such rule (`th:action` adds its input next to a hand-written one); prefer dropping the
  hand-written input when nothing reads it.
- **Lists of beans.** A path may index a `List` or an array with an int literal or an int
  variable: `*{items[0].name}`, `*{items[itemStat.index].name}`, `*{items[${i}].name}`. This
  replaces Thymeleaf's `*{items[__${itemStat.index}__].name}` and FreeMarker's
  `"cart.items[${index}].name"`. The element type is checked, `name` is Spring's `items[1].name`
  and `id` is `items1.name`, and values and errors come from the `BindingResult` for that path, so
  `@DateTimeFormat` and converters apply. In `fields.*` write a literal `'items[0].name'` or, for a
  variable index, the path itself: `fields.hasErrors(*{items[itemStat.index].name})`.
- **Maps.** A path may also index a `Map` by key: `*{prices[e.key]}` (iterating
  `t:each="e : form.prices"`), `*{prices[key]}`, `*{prices['x']}`, `*{byId[e.key].name}`. As with
  Spring's `BeanWrapper` the key is written unquoted, so `name` is `prices[x]`, and `id` drops the
  brackets as Thymeleaf's `th:field` does, `pricesx`; this replaces Thymeleaf's
  `*{prices[__${key}__]}`. The value type comes from the Map's type arguments and is checked like
  any other field (view-safe where the value is read). A key is a literal or a variable of type
  `String`, `int`, `long` or an enum (written as its `name()`, which Spring converts back), and
  must match the Map's key type. Spring's paths have no escape, so a `String` key must be
  non-empty and contain no `[`, `]`, `'` or `"`: a literal key that does is a build error, and a
  variable one throws `IllegalArgumentException` when the page renders. In `fields.*` and `Field`
  arguments a literal path takes identifier keys, `'prices[x]'`.

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
  tag `name` (`~{::dl}`, `~{::my-card}`), rendered with the call site's variables. Put it inside
  the host so it does not also render in place. `~{::t:block}` (Thymeleaf's `~{::th:block}`)
  selects the first `<t:block>`, which leaves no tag. Thymeleaf selects every match; Tessera only
  the first. Other selectors (`#id`, `.class`) are not supported.
- A `null` `Fragment` renders nothing, so a `Fragment` parameter can be optional.
- A fragment goes only where a browser parses markup the way it does where the fragment is
  declared: in HTML content, or inside `<svg>`, `<math>` or `<select>`. Inside `<svg>`, `<title>`
  and `<textarea>` hold markup and `<![CDATA[` is text, so an HTML fragment could run a value as
  script there. For `<svg t:insert>`, declare the icon inside an `<svg>` in its template
  (`<svg><g t:fragment="check">...</g></svg>`), or make it a whole `<svg t:fragment="check">`,
  which fits in HTML and `<svg>` content. `Fragment` values (`~{::tag}` arguments, `Fragment`
  parameters) are HTML content, and so are `t:utext` and `[(...)]` values. No fragment fits inside a
  raw-text element such as `<title>`, `<textarea>` or `<noscript>` (the raw-text error in section 6):
  use `t:text`.
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

A page has one `<html>`, one `<head>` and one `<body>`. A browser reads a second one by error
recovery (it ignores the tag and merges its attributes into the first), and the build rejects it
(`PARSE_ERROR`, section 7). Thymeleaf layouts often pass the page's whole `<body>`:

```html
<html th:replace="~{fragments/layout :: layout(~{::body}, 'home')}">
<body>
  <h2>Welcome</h2>
</body>
</html>
```

Mark the passed element `t:remove="tag"`, as in `guide/home.html` above, and pass a `<head>` the
same way. `t:remove="tag"` drops the element's own attributes, so move them to the layout's element
first (a `String` parameter for a class, say). Delete the tags of any other `<html>`, `<head>` or
`<body>` inside the page's body, and of those a fragment writes when it holds a whole document
(an include copied from another engine); move what such a `<head>` needs into the layout's
`<head>`. Nothing may come before the layout call either: an element there is written before the
layout's doctype, so put it in the `<head>` the page passes.

`tessera-migrate` does the mechanical part and reports each change. It adds `t:remove="tag"` where
the layout inserts the passed `<body>` (or `<head>`, `<html>`) inside its own element of that name,
deletes the tags of a nested `<body>` or `<html>`, and unwraps a fragment that writes a whole
document into page bodies. It changes a tag only if a browser would merge nothing new from it:
the tag has no attributes, or the element it merges into already has them. Otherwise it reports
the element with the fix, as it does for an element before the layout call and a `<head>` inside a
`<body>`.

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
and `fields.*` accept it; printing it writes the field's id, the path without brackets as
`t:field` writes it (`items1.name` for `'items[1].name'`), so `<label t:for="name">` points at the
input. Thymeleaf's `th:for="${name}"` with a string writes the bracketed path instead. The fragment reads the field's display
value with `fields.value(name)` and its first error with `fields.errors(name).first` (under a
`fields.hasErrors(name)` guard), as a JSP tag file does with `spring:bind`'s `status.value` and `status.errorMessage`: petclinic's "valid" icon
is `t:if="${!fields.hasErrors(name) and !fields.value(name).isEmpty}"`.

**Binding properties of a `Field`.** Declare the field's type, `Field<Criterion> c`, and the
fragment can bind its properties: `t:field="c.answer"` binds `<c's path>.answer`, as JSP's
`<form:input path="${field}.answer"/>` or Thymeleaf's `*{__${field}__.answer}` do. The property is
checked against `Criterion` when the fragment compiles, and the caller's path against
`Criterion` at each call. Name, id, value and errors are Spring's for the full path
(`criteria[1].answer`, id `criteria1.answer`). `t:errors`, `t:errorclass`, `fields.*` and fragment
arguments accept the sub-path too; an index works on a `Field<List<...>>` (`cs[i].text`). A plain
`Field` has unknown type and no sub-paths.

<!-- compile: template guide/fragments/criterion.html -->
```html
<div xmlns:t="https://tessera.dev" t:import="com.example.guide.Criterion">
  <fieldset t:fragment="criterion(Field<Criterion> c)" t:classappend="fields.hasErrors(c.answer) ? 'has-error'">
    <input type="radio" t:field="c.answer" value="true" /> Yes
    <input type="radio" t:field="c.answer" value="false" /> No
    <textarea t:field="c.description"></textarea>
    <span t:errors="c.description">Error</span>
  </fieldset>
</div>
```

<!-- compile: template guide/criteria.html -->
```html
<form xmlns:t="https://tessera.dev" t:import="com.example.guide.*" t:params="Espd espd"
      t:object="espd" method="post">
  <div t:replace="~{guide/fragments/criterion :: criterion('exclusion')}"></div>
  <div t:replace="~{guide/fragments/criterion :: criterion('selection[0]')}"></div>
</form>
```

<!-- compile: java -->
```java
package com.example.guide;

public record Criterion(Boolean answer, String description) {}
```

<!-- compile: java -->
```java
package com.example.guide;

import java.util.List;

public record Espd(Criterion exclusion, List<Criterion> selection) {}
```

### 4.9 Rendering

With Spring (Boot's auto-configuration, or `TesseraConfiguration` without Boot), `return
"owners/list";` renders `templates/owners/list.html` from the model. The name
must match exactly, except that one leading `/` is dropped (`"/owners/list"`, which Thymeleaf and
FreeMarker also accept); other names fall through to other resolvers (section 7). As with Spring's `AbstractView`
and Thymeleaf, the handler's `@PathVariable` values are added under the model (a model attribute of
the same name wins), so `@GetMapping("/owners/{ownerId}")` fills `t:params="Long ownerId"`; only
declared params are read, and an undeclared path variable is never visible. A path variable holds
the handler argument's type (`Long` for `@PathVariable Long ownerId`). A model value of the wrong type
fails with `TemplateModelException`. A controller can also return `ListTemplate.of(owners)`, checked
by `javac`. Without Spring, call `FunctionsTemplate.of(order).renderToString(ctx)` with a
`RenderContext(locale, messages, links, forms)`, the whole ambient surface (`RenderContext.defaults()`,
`ResourceBundleMessages`, `DefaultLinks(contextPath)`), plus an optional CSP nonce (section 4.11). The generated class is
`<tessera.package>.<path>Template` with `of(...)` taking the `t:params` in order (section 4.1).
This is how code outside a request (a command-line tool, a mail job) renders a template that the
old app rendered with its engine's API. `TemplateRegistry.load(classLoader)` finds templates by
view name.

**Fragment view names.** `return "owners/list :: rows";`, as for an htmx or Turbo partial
response, renders only the fragment `rows` of `templates/owners/list.html`. Its declared parameters
are filled from the model and the path variables exactly as a page's `t:params` are, so
`<tr t:fragment="rows(List<OwnerView> owners)" t:each="o : owners">` reads the model attribute
`owners` and nothing else. Spaces around `::` are optional, as in Thymeleaf (`"owners/list::rows"`),
and one leading `/` is dropped. The part after `::` must be a fragment name. Thymeleaf's other forms
fall through to other resolvers, because Tessera looks the name up and never parses it: arguments
(`page :: rows(x=${x})`; put the values in the model instead), selectors (`page :: #id`,
`page :: .class`, `page :: tr`) and `~{...}`. A name built from request input can select only a
fragment that has an entry, as `"prefix" + input` can select only a compiled page.

A fragment has an entry only if the compiler verified it as a response body, in the context its
root element implies (a `<tr>` in a `<tbody>`, an `<li>` in a `<ul>`, an `<option>` in a
`<select>`, anything else in a `<body>`), and only if a model can supply all of its parameters.
Fragments without an entry fall through:

- fragments with a `Fragment` or `Field` parameter (layouts and form widgets), which only a fragment
  call can supply;
- fragments whose root needs a context Tessera doesn't infer, such as `<head>`, `<body>`, `<tbody>`,
  `<thead>` or `<caption>`, which the verifier checks only at the calls that insert them: serve
  their rows (`<tr t:fragment>`) or a fragment that wraps them (`<table>`) instead.

`TemplateRegistry.fragments()` lists the fragment entries. They are not in `all()`.

### 4.10 Notes for specific engines

The rest of this guide applies to every engine. These notes list what is easy to get wrong when
leaving a particular one. Read the part for your engine before converting.

**Every engine.**

- **Comments.** Write template-only comments (`{{! }}`, `<%-- --%>`, `<#-- -->`, `##`, `#* *#`,
  `{# #}`) as `<!--/* ... */-->`, which is never written (section 4.1). An HTML comment
  `<!-- -->` is written to the page, so don't put internals in one.
- **Raw output.** Velocity prints everything raw, JSP prints EL `${x}` raw outside `<c:out>` and
  the form tags, and Pebble's `| raw` turns escaping off. Treat each raw output in the old
  templates as a review point, and replace it according to where the value comes from:
  - plain data: an escaped `t:text` or attribute. If the page now shows `&lt;b&gt;` where the old
    one rendered `<b>`, the old page was injectable. That difference is the fix: keep it and say so.
  - stored rich text: keep it a `String` and write `t:utext="html.sanitize(article.text)"`;
    `html.sanitizeRich` for editor or Markdown output (images, tables, code-block classes); or a
    policy of your own for any other mix (section 4.4). Don't parse it in Java into a tree of
    records and rebuild it with a recursive fragment: see Rich text below.
  - markup the application builds in Java (a `StringBuilder` of HTML): build records instead and
    let the template render them; for a few pieces, `SafeHtml.concat`/`SafeHtml.escape`.
  - constant HTML: write it in the template; a bundle message with markup: `t:utext="#{key}"`
    (section 4.5).
  - JSON for a script: pass the object to `[[...]]` inside the `<script>`. JSON in a `data-*`
    attribute: build the JSON string in Java and write it as a normal attribute value, which is
    escaped. JSON for a Vue `:prop` or Alpine `x-bind:prop`: pass the object, `t::prop="${obj}"`,
    which writes it as JSON (section 4.4). Other framework directives (`v-on`, `@x`, `x-data`, ...)
    can't contain an expression: put the value in a `data-*` attribute or a `<script>` with
    `[[...]]` and read it from there.

  Never `SafeHtml.unchecked`.
- **Scripts.** `[[...]]` in a `<script>` writes JSON with, among others, `/`, `&`, `<`, `'` and `{`
  escaped as `\/`, `\u0026`, `\u003c`, `\u0027` and `\u007b`. The JavaScript value is the same, so a
  difference that compare shows there against a page that printed the value raw or HTML-escaped is
  expected (an `&amp;` inside an old `<script>` was a bug: the string contained `&amp;`). Remove
  the quotes a default-mode page put around an inline: `var a = "[[${x}]]";` becomes
  `var a = [[${x}]];` (section 6). A natural-template comment with a default,
  `var a = /*[[${x}]]*/ 'd';`, becomes `var a = [[${x}]];`: inside the comment the value would be
  ignored and the page would use `'d'` (section 6).
- **Conditions** are Thymeleaf's: `t:if` is false for `null`, `false`, `0` and the strings
  `"false"`, `"off"` and `"no"`, and true for everything else, including `""` and empty
  collections. Write what the old condition meant: `x != null`, `!list.isEmpty()`.
- `numbers.sequence(1, n)` counts down when `n` is below 1 (`sequence(1, 0)` is `[1, 0]`): guard the
  loop where the old one ran zero times. Whitespace between `t:case` siblings and HTML comments are
  output; that matters only in whitespace-sensitive markup (`<pre>`, inline text).

**Rich text.** Stored HTML goes through a sanitizer policy into a `SafeHtml`. Don't rebuild it
in the template instead, with a fragment that inserts itself, one `t:case` per tag name, over a
tree parsed in Java. There the data decides which element nests in which (a `p` inside a `p`,
text directly inside a `table`), so the compiler can't check what the browser will build from
the page. Don't write such a fragment: the compiler's build-time context verifier rejects it
(`STRUCTURE_DEPENDS_ON_DATA`, section 7). `tessera-migrate` reports every `th:utext` and
`[(...)]` it converts, and every fragment of this shape with its call sites.

An example from a real port: fredbet's info pages print HTML written in a Summernote editor.
The Thymeleaf template had `<div th:remove="tag" th:utext="${textContent}"></div>`. A port that
parsed the HTML into records rendered it like this, with `RichTextParser.parse(content)` in the
controller and `<t:block t:replace="~{fragments/rich_html :: nodes(textContent)}"></t:block>` on
each page:

```html
<t:block t:fragment="nodes(List<HtmlNode> nodes)"><t:block t:each="n : nodes" t:switch="n.tag"><t:block
    t:case="'#text'" t:text="n.text"></t:block><p t:case="'p'" t:class="n.attrs['class']"><t:block
    t:replace="~{fragments/rich_html :: nodes(n.children)}"></t:block></p><img t:case="'img'"
    t:class="n.attrs['class']" t:src="n.attrs['src']" t:alt="n.attrs['alt']" /><!-- and 36 more cases --></t:block></t:block>
```

Delete the fragment, the node records and the parser, and pass the HTML string. The editor
stores images inline as `data:` URLs, and the controller adds `class="img-responsive"` to them.
`html.sanitizeRich` would drop both, so the application exposes its own policy:

<!-- compile: java -->
```java
package com.example.guide;

import dev.tessera.runtime.HtmlPolicy;
import dev.tessera.runtime.SafeHtml;
import dev.tessera.runtime.TemplateFunction;
import dev.tessera.runtime.TemplateFunctions;

@TemplateFunctions("info")
public final class InfoFunctions {
  // RICH's elements and attributes, plus one class and inline images.
  private static final HtmlPolicy POLICY =
      HtmlPolicy.builder()
          .allowElements(HtmlPolicy.ELEMENTS.toArray(new String[0]))
          .allowAttributes(HtmlPolicy.ATTRIBUTES.toArray(new String[0]))
          .allowClasses("language-*", "img-responsive")
          .allowLinkTarget()
          .allowDataImages()
          .build();

  private InfoFunctions() {}

  @TemplateFunction
  public static SafeHtml html(Object text) {
    return SafeHtml.sanitize(text == null ? null : text.toString(), POLICY);
  }
}
```

<!-- compile: template guide/info.html -->
```html
<div class="col-12" t:params="String textContent" t:utext="info.html(textContent)"></div>
```

Without those two needs, `t:utext="html.sanitizeRich(textContent)"` is all it takes. If trusted
markup needs more than any policy allows (a `style`, an `iframe`), build that piece in Java as a
`SafeHtml` from escaped parts (`SafeHtml.concat`, section 4.4), never with `SafeHtml.unchecked`
over a stored string.

**JSP, JSTL and Spring form tags, with Tiles or SiteMesh.**

- `<form:form modelAttribute="x">` is `t:object="x"`; `<form:input>`, `<form:select>`,
  `<form:checkbox>`, `<form:radiobutton>` and `<form:textarea path="p">` are the HTML element with
  `t:field="*{p}"` (write `<form:options>` as a `t:each` over `<option>`); `<form:errors path="p">` is
  `t:errors="*{p}"`, `path="*"` is `t:errors="*"`, and `<form:errors/>` with no path (the form
  object's own errors only) is `t:errors="*{global}"`. `t:field` writes Spring's ids, names, checkbox `_name` markers and `selected`/`checked`.
- **Computed paths.** A tag file or Tiles fragment that binds `path="${field}.answer"` becomes a
  fragment with a `Field<T>` parameter that binds `t:field="c.answer"` (section 4.8, Binding
  properties of a `Field`). The caller can't yet pass a `Field` indexed by its own loop variable
  (`*{criteria[i]}`): pass the list both as a value to loop over and as a `Field<List<T>>`,
  and bind `cs[st.index].answer` inside the fragment's loop; or keep those controls at the call
  site, where `*{criteria[st.index].answer}` works, and pass them in as a `Fragment`
  (`~{::controls}` renders with the call site's variables).
  Don't hand-write the form tags' markup: what you miss (selected options, ids, markers) shows up
  only in compare.
- **Tiles definitions.** Each definition becomes a page template that calls a layout fragment with
  the definition's attributes as arguments: `<tiles:insertAttribute>` is a `Fragment` parameter,
  `<tiles:getAsString>` a `String`. An `extends` chain and `put-attribute` defaults are resolved by
  the caller: each page passes the inherited values itself, and `cascade` attributes are passed on
  to the nested fragments that use them. A definition chosen at runtime becomes `t:if`/`t:switch`
  between literal fragment calls, or the controller returns one of several literal view names.
  SiteMesh decorators are layouts the same way (section 4.8).
- `<jsp:include>`, `<%@ include %>` and tag files are fragments: tag attributes become typed
  parameters and `<jsp:doBody/>` a `Fragment` parameter.
- **Scriptlets and state changes during render** (`<% %>`, `<jsp:useBean>`, `<c:set
  scope="session">`, filling in a form object's lists) move to the controller, a `@ModelAttribute`
  method or an interceptor. `<c:set var>` of a value is `t:with`; `<c:set var>` that captures markup
  to repeat it is a fragment. Markup needed as data (written into a hidden field, mailed) is a
  second template rendered in Java: `SafeHtml.of(XTemplate.of(...), ctx)` for a flow partial
  inserted into a page, or `XTemplate.of(...).renderToString(ctx)` for a `String` (section 4.4),
  with `ctx` from `SpringRenderContexts.create(request, response, model)` in a controller.
- **Spring Security.** Thymeleaf's `sec:authorize="hasRole('A')"` and JSP's
  `<sec:authorize access="hasRole('A')">` become `t:if="auth.hasRole('A')"`; likewise
  `hasAnyRole`, `hasAuthority('PERM_X')`, `hasAnyAuthority` and `isAuthenticated()`, and
  `sec:authentication="name"` is `t:text="auth.name()"` (section 4.6).
- EL implicit objects (`param`, `sessionScope`, `header`, `cookie`,
  `pageContext.request.contextPath`) are model values (section 1); a context-path prefix is `@{...}`.

**Velocity (and Velocity Tools).**

- `#foreach` is `t:each="x, st : xs"`: `$velocityCount` is `st.count` (1-based), `$foreach.index`
  `st.index`, `$foreach.hasNext` `!st.last`. `#set` is `t:with`, for one element. `#macro` and
  `VM_global_library.vm` macros are fragments; `#parse`/`#include` of a header and footer become one
  layout fragment (section 4.8). Of Velocity Tools, `$esc.html(...)` goes (escaping is
  automatic), `$date` and `$number` become `temporals.*`, `numbers.*` or a function, and `$link`
  an `@{...}` link.
- **Nulls.** `$!{x}` prints nothing for `null`, and `${x}` prints its own source text; Tessera prints
  nothing for both. A `null` attribute is omitted where `value="$!{x}"` kept `value=""`: write
  `x ?: ''` for a `String`, and `x == null ? '' : '' + x` for anything else
  (`x == null ? '' : x` doesn't compile, because the branches' types differ).
- **Tolerance.** Velocity prints the source of a reference it can't evaluate (arithmetic on `null`
  leaves `/projects/$prev` in the page), `#set($a = $b)` with a null `$b` keeps the old `$a`, and
  `#if($x)` is true for anything but `null` and `false`. If the pages depend on that, reproduce it
  on purpose, in Java, and check it with compare.
- Templates split across `#parse` files often leave tags unbalanced (the header opens a `<div>` the
  footer closes). Tessera needs each template well formed: open and close in one layout fragment.

**Pebble.**

- `{% for %}`'s `loop.index` is `st.index` (0-based), `loop.first`/`loop.last`/`loop.length` are
  `st.first`/`st.last`/`st.size`. `{% set %}` is `t:with`, `{% macro %}` a typed fragment,
  `{% include %}` a fragment call, and `{% extends %}` with `{% block %}`s a layout whose `Fragment`
  parameters are the blocks.
- **Extends chains.** A layout that extends another layout is a fragment that replaces itself with
  the outer layout and passes its own markup as a selection: a `<t:block>` (`~{::t:block}`, the
  first in the file), or an element with a name you invent (`~{::page-tools}`) and `t:remove="tag"`:

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

The page puts its selected elements outside `<body>`, or they would also render inside it.

- **Filters and functions.** Built-in filters map to functions (`upper`, `abbreviate`, `date`) or
  `?:` (`default`). Custom filters and functions (a Pebble `Extension`) become a
  `@TemplateFunctions` class or a value computed in Java. `| raw`: see raw output above.
  `strictVariables` is what typed `t:params` already give you: delete the setting with Pebble.
- **Attribute access.** Pebble reads public fields, `Map` keys with `.`, and methods with arguments
  (`x.getOther('a')`, `opt.get()`). Tessera reads getters and record components only: add getters
  or build a record. `map['key']` works. An `Optional` has `isPresent()` (or `present`) and prints
  its value or nothing, but `get()` can't be called. Printing a `@TemplateView` object prints its
  `toString()`.

### 4.11 Content Security Policy

**Where the guarantee ends.** Tessera guarantees what the browser's HTML parser does with the page:
every value lands in text, an attribute value, a checked URL or a JSON value in a script, so no
value runs as a script, an event handler or a `javascript:` URL, or loads a script from another
origin. It does not see what the page's JavaScript does with the DOM afterwards. Client frameworks
that read the DOM as code (Vue in-DOM templates, petite-vue, Alpine, Knockout, htmx `hx-on`,
AngularJS) are covered only by the build errors and `{{` breaking in section 4.4, with the gaps in
section 7. A strict Content Security Policy is the control that reaches past that.

**The nonce.** When the `RenderContext` has a CSP nonce, every `<script>` and `<style>` start tag
the template writes gets `nonce="..."`: inline or `src`, and in fragments wherever they are
inserted. The exception is a script or style a browser does not read as HTML: inside `<svg>` or
`<math>` (also when a fragment is inserted there), or inside `<noscript>`, `<xmp>`, `<iframe>`,
`<noembed>` or `<noframes>`. Those get no nonce, so a nonce policy blocks them; move scripts out of
SVG. The content of `<iframe srcdoc>` inherits the page's policy but gets no nonce, so its
scripts don't run either. With no nonce, nothing is added. Markup the compiler didn't write never gets one:
`html.sanitize` removes scripts and styles, and a script in a `SafeHtml.unchecked` value does not
run under a nonce policy. (`SafeHtml.of(template, ctx)` is another template's output, stamped with
the nonce of the `ctx` it was rendered with.) A nonce in the template is a build error, static or computed, so delete
Thymeleaf's `th:nonce="${nonce}"` and `th:attr="nonce=${nonce}"` when you port.

The Spring starter puts a fresh nonce (128 random bits, base64) in every request's
`RenderContext`. `CspNonce.get(request)` (`dev.tessera.spring.CspNonce`) returns the same value,
for your policy header; it is also the request attribute `CspNonce.ATTRIBUTE`. The starter sends
no policy header itself: a strict policy blocks `on*` attributes and `javascript:` URLs in your
templates, so turning it on is your decision. Without Spring, pass the nonce to
`new RenderContext(locale, messages, links, forms, nonce)` or call `ctx.withCspNonce(nonce)`, with
a new value for every response. Because the nonce changes on every response, an ETag computed from the
body (Spring's `ShallowEtagHeaderFilter`) never matches for a page with a script or style.

**The policy.** Send this header, with the request's nonce, on every page:

```text
Content-Security-Policy: script-src 'nonce-<nonce>' 'strict-dynamic'; object-src 'none'; base-uri 'self'
```

Don't add `'unsafe-eval'` or `'unsafe-inline'`. Then a script runs only if the page's own markup
or one of its scripts loaded it: an injected `<script>`, an `on*` handler and a `javascript:` URL
don't run, and `eval` and `new Function` throw. That turns Vue in-DOM templates, petite-vue,
Alpine, Knockout and htmx `hx-on` from code execution into, at worst, access to the component's
state: they compile expressions with `new Function`, so under this policy those expressions don't
run at all, and their CSP builds (Alpine's `@alpinejs/csp`, Vue with precompiled templates) only
read and call what the component exposes. Set `htmx.config.allowEval = false` too. AngularJS is
not bounded this way: its CSP mode still evaluates expressions that can reach `window`, so keep
`ng-non-bindable` (section 7). Before you turn the policy on, move inline handlers into scripts
(`addEventListener`) and send the same policy as `Content-Security-Policy-Report-Only` for a while
to find what it would block. The policy above covers scripts only; `style-src 'nonce-...'` would
also block every `style` attribute.

With Spring Security, add a header writer that reads the nonce. It works on Spring Security 4.2
(Spring 4.3, Boot 1.5) and later, whether headers are written before or after the view:

```java
import dev.tessera.spring.CspNonce;
import org.springframework.security.web.header.HeaderWriter;

HeaderWriter csp = (request, response) -> response.setHeader("Content-Security-Policy",
    "script-src 'nonce-" + CspNonce.get(request) + "' 'strict-dynamic'; object-src 'none'; base-uri 'self'");

http.headers(headers -> headers.addHeaderWriter(csp)); // Spring Security 5.2 and later
http.headers().addHeaderWriter(csp);                   // Spring Security 4.2 to 6.x
```

`contentSecurityPolicy("...")` takes a fixed string, so it cannot carry the nonce. Without Spring
Security, a servlet `Filter` that sets the header from `CspNonce.get(request)` before
`chain.doFilter` does the same.

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
- `Tessera: message bundle does not exist: <file> (-Atessera.messages is a comma-separated list of bundle base paths without .properties)`:
  point the option at the bundle without the `.properties` suffix. With a locale suffix
  (`messages_en`) the locale's file must exist. When the base file is missing but locale files
  exist, the message lists them: name the default locale's file, e.g. `.../messages_en`.
- `Tessera: message file <file> is not loaded: its name does not end in a locale (_fr, _pt_BR, ...), so its keys are not checked and its HTML messages are escaped`
  (warning): a file beside a basename such as `messages_en-US.properties`,
  `messages_backup.properties`, `messages_faq.properties` or `messages_de_formal.properties`.
  Rename a locale file to Java's form (`messages_en_US.properties`, and `messages_de__formal` for a
  variant without a country); list another bundle in `-Atessera.messages`; ignore the warning for
  other files.
- `Tessera: <file> was generated before another annotation processor (Lombok?) finished adding members to the classes its templates read; list that processor before Tessera in annotationProcessorPaths (Gradle: annotationProcessor)`:
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
- `malformed markup: invalid element name '<name>' in end tag </<name>>: element names start with a letter and use letters, digits, '-', '_', ':' and '.'`:
  the same rule for end tags. A browser folds ASCII letters only, so `</ſpan>` (with U+017F) does
  not close a `<span>` there; fix the tag name.
- `malformed markup: invalid attribute name '<name>': attribute names cannot contain <, quotes or =`:
  usually a missing quote or space before the attribute.
- `malformed markup: quote <quote> in an unquoted attribute value: quote the value and write <reference> in it`:
  a quote inside a value written without quotes, as in `title=a"b`. A browser keeps it as a
  character of the value and ends the tag at the next `>`, where Tessera would not. Quote the
  value and write the quote as `&quot;` or `&#39;` (`title="a&quot;b"`).
- `malformed markup: quote <quote> in a tag or attribute name: remove it (names cannot contain quotes)`:
  a quote that does not open or close a quoted value, as in `<p"x">`, `title="a"b"` or
  `</p ">`. A browser reads it as part of a name and ends the tag at the next `>`, where Tessera
  would not. Remove it; write a quote inside a value as `&quot;` (`title="a&quot;b"`), and
  remove anything after the name of an end tag.
- `malformed markup: U+<hex> in a tag is not HTML whitespace: replace it with a space`:
  a character that Java counts as whitespace but HTML does not (U+000B, U+001C to U+001F,
  U+2028, U+3000 and the other Unicode spaces) in a tag, outside a quoted value. A browser keeps
  it as part of the tag name, attribute name or value, so `<textarea` followed by U+000B is an
  unknown element whose content is live markup. Only space, tab, newline, carriage return and
  form feed separate the parts of a tag.
- `malformed markup: '/' before '>' belongs to the unquoted value, not to '/>': quote the value`:
  as in `<textarea x=y/>` or `<img src=a/>`. A browser reads the `/` as the last character of
  the value, so the tag is an open tag, not a self-closing one. Quote the value
  (`<img src="a"/>`).
- `malformed markup: a name after '/' in a tag starts a new attribute in a browser: remove the '/'`:
  as in `<p title="x"/t:text="s">` or `<img/src=x>`. A browser ends a name at a `/` and reads
  the name after it as a new attribute, where Tessera would not. Remove the `/`.
- `malformed markup: '/' in a tag must be followed by '>': remove the '/' or what follows it`:
  as in `<br / >`, `<br //>` or `<script type/ type="application/json">`. A browser ignores a
  `/` that is not right before `>`, while Tessera would read it as an attribute named `/`; where
  Tessera rebuilds the tag, that attribute comes out as ` />`, which closes an svg or math
  element early. Write `<br/>` or `<br />`, or remove the `/`.
- `malformed markup: '=' starting an unquoted value is part of it in a browser: quote the value`:
  as in `href==x` or `title= =x`. A browser reads the value as `=x`, while Tessera would skip the
  extra `=`, so a tag Tessera rebuilds could carry a different value than the browser read.
  Remove the extra `=`, or quote the value if it starts with `=` (`title="=x"`).
- `duplicate attribute '<name>' on <<tag>>: remove one of them`: keep one; names are
  case-insensitive (`t:text` and `T:TEXT` are the same).
- `malformed markup: content of <<tag>> contains '</<tag>' before its end tag: escape it (in a script string, write '<\/<tag>')`:
  a browser would end the element early; split the string.
- `malformed markup: content of <<tag>> contains '</<tag>' before its end tag: a browser ends the element there, so the markup after it would be read differently; remove it or move it outside the element`:
  reported for `<xmp>`, `<iframe>`, `<noembed>`, `<noframes>` and `<noscript>`. Tessera parses
  their content as markup, but a browser (for `<noscript>`, with scripting on) reads it as text up
  to the first `</<tag>`. That must be the element's own end tag, not one inside a nested
  `<textarea>`, `<title>` or `<script>`, a comment, an attribute value or a nested `<noscript>`.
  (Inside `<svg>` or `<math>` these are ordinary elements to a browser, but the check still applies.)
  Tessera still parses a `t:remove="all"` subtree, so the check also applies there: remove the
  markup even though it is not rendered.
- `malformed markup: '</<name>' inside <<tag>> looks like its end tag but a browser doesn't end the element there: remove it`:
  in `<script>`, `<style>`, `<title>` and `<textarea>` a browser matches the end tag with ASCII
  letters only, so `</ſcript>` (with U+017F) or `</tıtle>` (with U+0131) is text to it, not the end
  tag. Remove the look-alike. Tessera still parses a `t:remove="all"` subtree, so the check also
  applies there.
- `<<tag>/> is not self-closing in HTML: the browser treats everything after it as <tag>; write <<tag> ...></<tag>>`:
  reported for `<textarea/>`, `<title/>`, `<xmp/>`, `<iframe/>`,
  `<noembed/>`, `<noframes/>` and `<noscript/>` when Tessera writes them as they are: a browser
  reads the rest of the page as their text. With content from `t:text`, `t:field` or another
  attribute (`<textarea t:field="*{bio}"/>`) Tessera writes the end tag, and they compile.
  `<script/>`, `<style/>`, `<select/>` and any other non-void element written with `/>` are the
  verifier's `PARSE_ERROR` (section 7): `a browser ignores the '/' of <script/>, ...; write the end
  tag: <script ...></script>`.
- `<title/> is not supported inside <svg> or <math>: Tessera cannot tell an SVG title from an HTML one (in <foreignObject> or after <b>), after which a browser reads the rest of the page as title text; write <title></title>`:
  a real SVG `<title/>` is self-closing, but Tessera does not track where a browser leaves SVG
  (`<foreignObject>`, `<mtext>`, a `<b>` or `<p>` inside `<svg>`), so it rejects them all.
- `<plaintext> is not supported: a browser treats the rest of the page as plain text; use <pre>`:
  a browser never ends `<plaintext>`. Use `<pre>`.
- `end tag </<tag>> does not match any open element: remove it or add the matching <<tag>>`:
  common with `<a ... />` followed by `</a>`; write `<a ...></a>`.
- `element <<tag>> is not closed (found </<other>> at <line>:<col>): add </<tag>>` and
  `element <<tag>> is not closed: add </<tag>>`: close the element (Tessera needs well-formed
  nesting for non-void elements).
- `malformed markup: '<!--' inside <script> is not supported: remove it (it is not needed in HTML5)`:
  delete the `<!--`/`-->` wrapper.
- `malformed markup: a comment cannot start with '<!-->' or '<!--->' (a browser ends it there): put a space after '<!--'`
  and `malformed markup: '--!>' ends a comment in a browser: end the comment with '-->' or remove the '!'`:
  a browser ends the comment earlier than Tessera would, so the markup after it would be live. Write
  `<!-- -->`-style comments that end only at `-->`.
- `malformed markup: '<!--/*' starts a template comment that is not closed: end it with '*/-->'`:
  a template comment (section 4.1) runs to the first `*/-->`; add it, or write `<!-- -->` for an
  HTML comment.
- `malformed markup: '<!--/*' inside <<tag>> is text, not a template comment: move the comment outside <<tag>>`:
  in `<style>`, `<title>` and `<textarea>` a browser reads it as text, so Tessera would write it
  where Thymeleaf drops it. In `<script>` it is the `'<!--' inside <script>` error above.
- `Thymeleaf prototype-only comments '<!--/*/ ... /*/-->' are not supported: remove the '<!--/*/' and '/*/-->' markers to make the content part of the template`:
  Thymeleaf renders the content of these blocks, so remove the markers and keep the content.
  `tessera-migrate` does this.
- `malformed markup: '>' inside a CDATA section is not supported, even in <svg> and <math>: elsewhere a browser ends it at the first '>'; write the text without <![CDATA[, with '>' as &gt;`:
  Tessera ends a CDATA section at `]]>`, but in HTML a browser ends it at the first `>`, so the
  markup after it would be read differently. Inside `<svg>` and `<math>` a browser ends a CDATA
  section at `]]>` too; Tessera does not track those namespaces and rejects the `>` there as well.
  A CDATA section inside `<script>` or `<style>` is script or CSS text and is not checked. A `>`
  inside a `<?...?>` or a DOCTYPE, and `'<!'`, `'<?'` or `'</'` not followed by a letter in text
  (`a </ b`, `<? x ?>`, `<![if !IE]>`), are the verifier's `PARSE_ERROR` (section 7), reported at
  the `<` with the fix.
- `malformed markup: '<' inside <<tag>> in <svg> or <math> starts markup in a browser, not text: write &lt; for a literal '<'`:
  inside `<svg>` and `<math>`, a browser parses the content of `<script>`, `<style>`, `<title>` and
  `<textarea>` as markup (SVG `<title>` and `<desc>` as HTML), so `<svg><title><script>` is a real
  script. Write text only; a `<![CDATA[ ... ]]>` section is allowed. Inside `<foreignObject>`,
  MathML `<mi>`, `<mo>`, `<mn>`, `<ms>` and `<mtext>`, and `<annotation-xml encoding="text/html">`,
  these elements are HTML again. `<t:block>` and `t:remove="tag"` elements do not count as parents,
  since their tags are not rendered.
- `malformed markup: '&' inside <<tag>> in <svg> or <math> is decoded by a browser: write the character itself, or use a CDATA section`:
  inside `<svg>` and `<math>`, a browser decodes character references in `<script>` and `<style>`
  before the script or style sees the text, so `<svg><script>var a = &quot;[[${s}]]&quot;;` puts
  the value outside the string. Write `"` instead of `&quot;`, or put the code in a
  `<![CDATA[ ... ]]>` section, where nothing is decoded. Any `&` outside a CDATA section is an error,
  including `&&`.
- `malformed markup: <<tag>> inside <svg> or <math> is HTML in a browser and ends the <svg> or <math> there: close it first, or put HTML inside <foreignObject>`:
  a browser closes the `<svg>` or `<math>` at `<p>`, `<div>`, `<span>`, `<b>`, `<br>`, `<img>`,
  `<table>` and the other HTML "breakout" tags (and `<font>` with `color`, `face` or `size`), so the
  markup after it is not where the template puts it.
- `<<tag>> inside <select> is not supported: browsers with the classic select parser ignore most tags there and parse their content as markup; move the <<tag>> out of the <select> (a <select> holds only <option>, <optgroup>, <hr> and <t:block>)`:
  reported for any other start tag inside a `<select>`, also inside its `<option>` or `<optgroup>`,
  and also for `<svg>` and `<math>` and the elements in them. Firefox 140 and other browsers
  without customizable select ignore those start tags in a select and parse what is inside them
  as markup, so a `<script>` in a `<title>`, a `<style>` or an svg `<![CDATA[` runs, and later
  holes can land in it. A `t:remove` on the select does not change this. Move the element out of
  the `<select>`; wrap repeated options in `<t:block t:each="...">` or put `t:each` on the `<option>`.

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
- `expected a fragment or tag name after '::' (markup selectors such as #id and .class are not supported) but <found>`:
  select by fragment or tag name only.
- `'<name>' after '::': the only tag name with a prefix that can be selected is t:block`, ending in
  ` (Tessera's name for th:block)` for `th:block`: write `~{::t:block}` for Thymeleaf's `~{::th:block}`.
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
- When a container (`List`, `Map`, `Map.Entry`, `Optional`, ...) is not view-safe because of a
  type argument, the messages above and below end with `: its type argument <type> <why>` instead
  of advice about the container. `<type>` is the first offending argument, however deeply nested;
  `<why>` is `is not a record or annotated @TemplateView`, `is not public, not a record and not
  annotated @TemplateView`, `is raw: declare its type arguments`, or a never-view-safe reason. Fix
  that type (make it a public record or annotate it `@TemplateView`), not the container.
- `<type> is not public: make it public, and any class it is nested in (templates compile to classes in their own packages)`:
  the type, or a type argument of it at any depth, is otherwise view-safe but the generated code
  can't name it (section 4.2). It ends the messages above, `cannot read '<name>': ...` and
  `cannot output ...`, and `'<name>' of <type> has type <result>, which is not view-safe: ...`
  (reading a property) or `<ns>.<fn>(...) has type <result>, which is not view-safe: ...`
  (calling a function) when the value's type holds such a class.

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
- `function namespace 'ids' on <class> is reserved for element ids`: choose another name.
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
- `<t:attr> inside <<tag>> is not supported: a browser reads the content of <<tag>> as text up to the first '</<tag>', and inserted markup could contain one; move it outside the element`:
  reported for `t:utext`, `[(...)]`, `t:insert` and `t:replace` on or inside `<xmp>`, `<iframe>`,
  `<noembed>`, `<noframes>`, `<noscript>`, `<textarea>` and `<title>`. A `SafeHtml` or a rendered fragment could contain
  `</noscript>` and end the element early. `t:replace` on the element itself is allowed, as it
  replaces the whole element: `<noscript t:replace="~{fragments :: gtm}"></noscript>`.
- `[(...)] is only allowed in element text; inside <script> use [[...]], which outputs JSON`.
- `<form> with a SafeHtml value or HTML message is only allowed in HTML content, not in <context> content, where a browser parses its markup differently: write the markup in the template, or move the <form> into HTML content`:
  `t:utext` and `[(...)]` inside `<svg>`, `<math>` or `<select>` (contexts as in the fragment
  messages below; inside raw text, the raw-text error above applies). Inside `<svg>`, a `<p>` in the value
  ends the `<svg>`, and a `<title>` holds HTML, where a `<script>` runs.
- `[[...]] is inside a quoted JavaScript string (<q>...<q>), but it writes a complete JSON value with its own quotes, which would close a "..." string and run as code, or end up quoted twice; remove the quotes, e.g. var a = [[...]]; or "Hello " + [[...]]`:
  `<q>` is `"` or `'`. A Thymeleaf default-mode page wrote `var a = "[[${x}]]";`; write
  `var a = [[x]];` (a string renders `"Alice"` as before; a number renders `42`, not `"42"`). For
  `"Hello [[${x}]]"` write `"Hello " + [[x]]`. An inline inside a comment is an error too (next
  entry), and so is one in a template literal or a regular expression, at its backtick or `/` (the
  data-prelude entry below).
- `[[...]] is inside a JavaScript comment (<c>), so its value is written inside the comment and the page never uses it; move it out of the comment: Thymeleaf's natural-template var a = /*[[${x}]]*/ 'd'; becomes var a = [[${x}]];`:
  `<c>` is `//` or `/* */`. Thymeleaf's JavaScript natural templates put the value in a comment
  and a default literal after it; Tessera writes the value where the inline is, so the page would
  run with the default (#315). Drop the comment markers and the default. `tessera-migrate` does
  this in a `th:inline="javascript"` script when the default is a string, number, `true`, `false`,
  `null`, array or object (or missing), and reports any other text after the comment. Thymeleaf
  dropped that text up to the next `;`, `,`, `)`, `}`, `]`, `//` or line end, so check what the
  code meant.
- `<token> before the last [[...]] of a <script>: the compiler reads the code before the last value only with a lexer that has no ambiguous tokens, so it can prove where each value goes: move the [[...]] above the code (declare the data first, var data = [[...]];), or write the value into a <script type="application/json"> and read it with JSON.parse`:
  the data-prelude rule (#332). To know that each `[[...]]` is outside every string and comment,
  the compiler reads the script text before the last `[[...]]` with a lexer that needs no
  context: identifiers, numbers, punctuators, `"..."` and `'...'` strings, `//` and `/* */`
  comments, and whitespace. `<token>` is what it can't read without guessing: a `/` (a division
  or a regular expression?), a backtick (a template literal, `${...}` included), `-->`, `#!` or
  `<!` (HTML-like and hashbang comments; a negative value after `<!-` would make `<!--`), a
  backslash line continuation in a string, or U+2028/U+2029. After the last `[[...]]` anything
  goes except `-->` (entry after next), but each `[[...]]` must be in expression position (next entry). The usual shape,
  data first and code after it, compiles unchanged: `var chart = [[${chart}]]; $(function () { ... })`.
  Otherwise move the declarations up, or put the data in `<script type="application/json" id="data">[[${data}]]</script>` and read it with
  `JSON.parse(document.getElementById('data').textContent)`. A pattern built from a value
  (`/[[${x}]]/`) becomes `var p = [[${x}]];` first and `new RegExp(p)` after it. `tessera-migrate`
  reports these scripts.
- `[[...]] in a <script> must be in expression position, <why>: its value would change what the code around it means. Declare the value first (var v = [[...]];) and use v`:
  the value of a `[[...]]` must be an operand of the code around it (security-architecture.md
  §5.4). `<why>` names the problem:
  - The token before it, skipping whitespace, comments and line breaks, must be one after which
    an operand starts: `(`, `[`, `,`, `?`, `:`, an assignment or binary operator, `+ - ! ~`,
    `...`, `return`, `typeof`, `void`, `delete`, `new`, `in`, `instanceof`, `case`, `throw`,
    `else`, `do`, or `of` as the keyword of a `for (...)` head (after `]`, `}` or a binding name).
    After an identifier, a number, a string, another `[[...]]`, `)`, `]`, `}`, `.`, `?.` or `#`
    the value would continue that expression:
    `globalThis[[${l}]]([[${s}]])` with `l = ["eval"]` renders `globalThis["eval"](...)`, and
    `x[[${n}]]` renders the name `x5`. A line break doesn't help: JavaScript inserts no `;`
    before a `[`. After `{` or `=>` a map would be a block, and after `yield`, `await` or any other
    `of` (also identifiers, as in `for (x of a instanceof of [[${l}]])`) the value could index a
    variable.
  - A word or `-` right before it needs a space: `return[[${n}]]` renders `return5`, and
    `-[[${n}]]` renders `--1` for `-1`.
  - A word and then a line break before it, in whitespace or a comment: a reserved word such as
    `in` is also a class field name, and the line break ends the field, so `class A { in
    [[${l}]] = f }` would name a computed field after the value. Put them on one line.
  - It may not be followed directly by a letter, digit, `_`, `$` or `\` (`[[${n}]]e3` renders
    `5e3`) or by a `.` that doesn't start a property name (`[[${n}]].5`), nor by `(` or a backtick
    (a call or a method name), nor by `?.` and then `(` or a backtick (`[[${l}]]?.(...)`), nor by
    `:` after a `,` (an object key, `{a: 1, [[${k}]]: 2}`).
  - At the start of the script or after `;`, a `[[...]]` that starts a statement may be followed
    only by a `;`: a string there is a directive, and `"use strict"` changes how the code after
    it runs; in a class body a list is a computed field name.

  `var v = [[...]];` above the code, then `v`, always compiles: `globalThis[v](...)`,
  `o[v] = ...`, `v.forEach(...)`.
- `'-->' after a [[...]] of a <script>: at the start of a line it is an HTML-like comment, which can hide the code next to a value from the compiler's checks; remove it, or write '--\x3e' in a string`:
  a script with a `[[...]]` has `-->` after its last `[[...]]` (before it, the data-prelude entry
  above reports it). At the start of a line JavaScript reads `-->` as a comment, so in
  `{a: 1, [[${k}]]\n-->\n: f}` the `:` that makes the value an object key is hidden from the
  expression-position check, and the compiler doesn't lex the code after the last value to tell.
  Every `-->` there is an error, also in a string or a comment: an old `//-->` comment end can go,
  since HTML5 needs no comment around script code.
- `[[...]] is inside a client-side {{ ... }} interpolation: Vue or AngularJS would run the value as code, whatever the escaping. Pass the value in a data-* attribute or a <script> with [[...]] and read it from there`
  (also `[(...)]`): the text has a `{{` with no `}}` before the expression, in element text,
  `<title>`, `<textarea>` or a `<script>`. Character references for braces count outside scripts,
  as the framework decodes them.
- `a client-side {{ is never closed inside <p>: Vue or AngularJS would run a value written after it as code, also where the element is inserted as a fragment. Close it with }} inside the same element, or write a literal {{ as {<!---->{`:
  element text, `<title>` or `<textarea>` has a static `{{` with no `}}` after it in the same
  element (`<p>{{</p>`, `<p><b>{{</b> }}</p>`, `<t:block t:fragment="f">{{</t:block>}}`), since a
  fragment or a selector inserts the element without what follows it. Character references for
  braces count. A `}}` inside an element that may not render where the `{{` does (`t:if`, `t:unless`, `t:each`, `t:switch`, `t:case`,
  `t:field`, `t:remove` other than `"tag"` and `"none"`) does not close it, and the body of an element
  that `t:text`, `t:utext`, `t:insert`, `t:replace`, `t:errors` or `t:remove="all"`/`"body"` replaces or
  drops is not read, except for the elements in it, which a template can still insert.
- `[[...]] directly inside <tr> is not supported: a browser moves the text in front of the table but leaves the comments that break {{ in the value inside it, so Vue or AngularJS could run the value as code; put the value in a <td>, <th> or <caption>`
  (also `[(...)]`, `t:text`, `t:utext` and `t:errors`, and `<table>`, `<tbody>`, `<thead>`, `<tfoot>`
  and `<colgroup>`): a value directly in a table, also through `<t:block>` or `t:remove="tag"`.
  Whitespace and static text are allowed. Move the value into a cell (`<tr><td>[[...]]</td></tr>`),
  or put `t:each` rows on the `<tr>` itself.
- `<tr> inside <td> is not supported: a browser would close elements to place it (a <tr> ends a <td>, a <form> in a table is closed at once), and then move text after it in front of the table, leaving the comments that break {{ in a value behind, so Vue or AngularJS could run the value as code; write table parts only directly in the part that takes them (<tr> in <tbody>, <td> in <tr>)`
  (any of `<caption>`, `<colgroup>`, `<col>`, `<tbody>`, `<thead>`, `<tfoot>`, `<tr>`, `<td>` and
  `<th>` not directly in the part that takes it, also through other elements as in `<td><p><tr>`,
  and `<form>` directly in a table; `here` for one at the top of a fragment). Also
  `t:insert of a fragment that writes <tr> inside <td> is not supported: ...`: the same check on
  the top level of what `t:insert`/`t:replace` writes, looking through `<t:block>` and
  `t:remove="tag"` fragment roots.
- `t:insert writes computed text or a Fragment value directly inside <tbody>, which is not supported: a browser moves the text in front of the table but leaves the comments that break {{ in the value inside it, so Vue or AngularJS could run the value as code; put the value in a <td>, <th> or <caption>, or insert a fragment whose top element is a table row or cell`
  (also `t:replace`, and any table part that holds no text): a fragment with a value at its top
  level, or a `Fragment` value, inserted directly in a table.
- `t:insert inside a table must insert a fragment Tessera can follow (~{template :: name})` (also
  `t:replace`): the fragment reference cannot be resolved, or nests too deeply, so Tessera cannot
  check what it writes in the table.
- `~{:: row} writes <tr>, which is not supported in a Fragment value: inserted in a <td> or a <div> a browser would close elements to place it and move text after it out of the table; insert the fragment with t:insert/t:replace directly in the table part that takes it`:
  a fragment passed as a `Fragment` argument may have no table part other than `<table>` at its top.
- `[[...]] may not follow '<MARKUP>' directly: the browser would read the value as markup; write &lt; for a literal '<'`
  (also `[(...)]`), where `<MARKUP>` is `<`, `<!`, `<!-`, `<?`, or `</` and any letters, at the end
  of the static text before the expression, in element text, `<title>` or `<textarea>`. In element
  text a browser reads `<` and a value starting with a letter as a tag (`<p><[[s]]></p>` with
  `s = "img src=x onerror=..."`). In `<title>` and `<textarea>` only an end tag matters: `</text`
  and a value `area`, or `</textarea` and an empty value, end the element early. The other endings
  are rejected there too, to fail closed. Write `&lt;[[s]]`.
- `cannot output a value of type <type> inside <script>: only strings, numbers, booleans, enums, java.time values, records and @TemplateView classes of those, and Optionals, collections, arrays and String-keyed maps of those can be serialized as JSON`:
  when a property of a record or bean is the problem, the message ends with
  `; property '<name>' of <type> has type <type>` (or `; <type> contains itself with different type arguments`).
  Output the specific properties the script needs, or make the property's type serializable.
- `<form> with a message #{...} needs -Atessera.messages=<bundle base path>, so the message can be checked as HTML at build time; or use t:text or [[...]] (escaped)`:
  set the option (section 2), or print the message as text.
- `message '<key>' in <bundle file> cannot be rendered as HTML by <form>: <reason>`: the value does
  not pass the allow-list in section 4.5. The reason names the element, attribute, URL or
  placeholder at fault (`<script> is not allowed`, `attribute onclick is not allowed on <b>`,
  `a placeholder may not be inside a tag`, `<div> is never closed`, ...); a reason that ends `(in
  the message as MessageFormat formats it, ...)` is about `'` quoting. `a message may not end with
  '<'` means the template text after the message would continue the `<` into a tag. Fix the
  bundle, or move the markup into the template.
- `<form> needs a value of type dev.tessera.runtime.SafeHtml, but the expression has type <type>: use t:text or [[...]] (escaped), or build a SafeHtml in trusted Java code`.

### Attributes

- `<label> is not allowed: event-handler attributes cannot contain expressions; put the value in a data-* attribute and read it from a script`.
- `<label> is not allowed: '<name>' is a Vue, Alpine, htmx, _hyperscript, AngularJS or Knockout directive whose value is run as JavaScript, so it cannot contain expressions; write it as a static attribute, or put the value in a data-* attribute or a <script> with [[...]] and read it from there`. See [4.4](#44-escaping-and-output-contexts) for the list of directives. A Vue or Alpine binding of an ordinary attribute (`t::prop`, `t:v-bind:prop`, `t:x-bind:prop`) takes a JSON value instead.
- `<label> is not allowed: '<name>' is a Vue or Alpine binding whose value is run as JavaScript; an expression (written as JSON) is only allowed in :x, v-bind:x or x-bind:x whose target x is an ordinary attribute, with no modifiers, not on* / style / a URL or URL part / innerHTML / is, and not on <script>, <style> or <meta>; otherwise write it as a static attribute`. For `:href`, `:style`, `v-bind:x.prop`, `v-bind="obj"`, ... (section 4.4).
- `cannot output a value of type <type> in <label>: only strings, numbers, booleans, enums, java.time values, records and @TemplateView classes of those, and Optionals, collections, arrays and String-keyed maps of those can be serialized as JSON`. A Vue or Alpine binding takes the same types as a script `[[...]]`.
- `<label> is not allowed: a style value can only combine string literals, numbers and CssValues such as css.color(c), css.length(l) or css.url(u), not a <type>; use t:class or t:classappend instead`.
- `<label> is not allowed: srcdoc is a whole HTML document and cannot contain expressions; use src with a URL`.
- `<label> is not allowed: the content of <meta http-equiv="refresh"> navigates the page; write a delay and a same-origin link such as '3;url=' + @{/items/{id}(id=${item.id})}, or redirect from the controller`. Port `content="1;url=/admin/update${vid}"` to `t:content="'1;url=' + @{/admin/update{vid}(vid=${vid})}"`.
- `<label> is not allowed: a refresh URL must be a same-origin path starting with a single '/', not @{<path>}`. Absolute, protocol-relative and page-relative links cannot be refresh targets; redirect from the controller instead.
- `<label> is not allowed: htmx requests this URL and swaps the response into the page as HTML; use a same-origin link such as @{/items/{id}(id=${item.id})}`. On `hx-get`, `hx-post`, `hx-put`, `hx-patch`, `hx-delete`, `sse-connect` and `ws-connect`; build the URL with a link, not a `String`.
- `<label> is not allowed: an htmx request URL must be a same-origin path starting with a single '/', not @{<path>}`. Absolute, protocol-relative and page-relative links are rejected; write a cross-origin endpoint as a static attribute.
- `<label> is not allowed: htmx 1.x '<name>' holds connect:<url>, a URL htmx connects to and swaps into the page as HTML, so it cannot be computed; write it as a static attribute, or use the sse/ws extension's sse-connect/ws-connect with a same-origin link such as @{/events}`. On `hx-sse` and `hx-ws` (also with `data-`).
- `<label> is not allowed: inside hx-boost or an htmx form, htmx submits to this URL and swaps the response into the page as HTML; use a same-origin link such as @{/items/{id}(id=${item.id})}, or put hx-boost="false" on the form to submit it natively`. On `action` of a boosted `<form>` and `formaction` in a boosted region or an htmx form (section 4.4). Other links are reported as `an htmx request URL must be a same-origin path ...`.
- `<label> is not allowed: this button may submit a POST form that gets the CSRF token, which then goes to the button's formaction; use a same-origin link such as @{/items/{id}(id=${item.id})}, or move the button into its own form`. On a computed `formaction` (`t:formaction`, `t:attr="formaction=..."`) of a button that may submit a form with the CSRF token (section 4.7). A fragment that takes its `formaction` as a `String` parameter cannot pass this check: write the link inside the fragment, or drop the parameter if every caller passes `''`. A link that is not same-origin is reported as `a formaction that may receive the CSRF token must be a same-origin path starting with a single '/', not @{<path>}`.
- `<label> is not allowed: every relative URL on the page resolves against <base href>, so a POST form with a relative action would send the CSRF token there; use a same-origin link such as @{/items/{id}(id=${item.id})}`. On a computed `href` of a `<base>` (`t:href`, `t:attr="href=..."`) that is not a link (section 4.7). A link that is not same-origin is reported as `a <base href> must be a same-origin path starting with a single '/', not @{<path>}`.
- `href="<url>" on <base> is not allowed: every relative URL on the page resolves against <base href>, so a POST form with a relative action would send the CSRF token there, and this URL is not same-origin; use a same-origin path such as /app/, or remove <base> and write absolute URLs where another origin is meant`. A static `<base href>` with a scheme or starting with `//` or `/\`.
- `formaction="<url>" is not allowed here: this button may submit a POST form that gets the CSRF token, which then goes to the button's formaction, and this URL is not same-origin; use a same-origin URL such as /items or @{/items/{id}(id=${item.id})}, or move the button into its own form`. A static `formaction` with a scheme or starting with `//` or `/\` in such a form.
- `formmethod="<value>" is not allowed here: this button may submit a POST form that gets the CSRF token, and a formmethod other than post submits as GET with the token in the URL; remove formmethod, or move the button into its own GET form`. Also `<label> is not allowed: ...` for a computed `formmethod` there; only a static `post` or `dialog` is allowed.
- `<form/> is not self-closing in HTML: the browser treats everything after it as inside the form; write <form ...></form>`. A browser drops the start tag of every later form, so their buttons and CSRF token inputs land in it.
- `<label> is not allowed: '<name>' is not a known attribute; a computed attribute must be a standard HTML, SVG, MathML or ARIA attribute, a data-* attribute, or a plain [a-z][a-z0-9]* prop on a custom element. If it is data for your script, use data-<name>; if a framework reads it, write it as a static attribute`. On a computed name that is not a standard attribute, `data-*`, a custom element's prop or a framework attribute Tessera classifies (section 4.4), such as `t:up-on-loaded`, `t:hx-action`, `t:hx-target` or `t:attr="foo=..."`. htmx reads every
  `hx-*` attribute the same with a `data-` prefix, so `t:hx-target` becomes `t:data-hx-target`;
  `tessera-migrate` does this for htmx's selector, swap and text attributes and reports the rest.
- `<label> is not allowed: '<name>' cannot be computed; it decides whether htmx submits the forms inside with AJAX and swaps the response into the page, so write <name>="true" or <name>="false" statically`. On `hx-boost` and `data-hx-boost`.
- `<label> is not allowed: attribute name '<name>' is not allowed; names must match [A-Za-z_][A-Za-z0-9_.:-]*`.
- `<label>: the type of a <script> cannot be computed; its content type must be known at build time, so write a static type attribute`.
- `<label>: the http-equiv of a <meta> cannot be computed; it decides whether content is a refresh URL, so write a static http-equiv attribute`.
- `<label>: the attributeName of an SVG animation cannot be computed; it decides whether values, to, from and by are CSS or a URL, so write a static attributeName attribute`. On `<animate>`, `<set>`, `<animateMotion>`, `<animateTransform>` and `<animateColor>`.
- `<label> on <<tag>> loads code or a document: use a literal-path link such as @{/js/app.js} or a TrustedUrl value, not a <type>`.
- `<label> on <<tag>> is a list of URLs that Tessera does not check one by one: use a TrustedUrl value or a static attribute, not a <type>`. On a computed `ping`, `srcset` or `imagesrcset`.
- `a nonce on <script> is not supported: Tessera writes the render's CSP nonce (RenderContext.cspNonce, set per request by the Spring starter) on every <script> and <style>; remove the nonce attribute (static, t:nonce or t:attr nonce=...)`. Also on `<style>`. Delete Thymeleaf's `th:nonce`; the nonce comes from the `RenderContext` (section 4.11).
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
- `t:remove="tag" is not allowed on <<tag>>: it is <svg> or <math> or inside one, and without the tag a browser would parse its content as HTML, not as SVG or MathML (or the reverse); keep the tag, or use t:remove="all" to drop the element`:
  on `<svg>`, `<math>` and every element inside them, matched by name. Without the `<svg>` tag,
  for example, a browser reads an SVG `<![CDATA[ ... ]]>` section as a comment that ends at the
  first `>`, so markup Tessera compiled as text would be live.
- `t:remove="tag" is not allowed on <<tag>>: its content is text only inside <<tag>>, and without the tags a browser would parse it as HTML; keep the tag, or use t:remove="all" to drop the element`:
  on `<script>`, `<style>`, `<title>` and `<textarea>`, whose content Tessera reads (and escapes)
  as text.

### Messages and links

- `unknown message key '<key>': it is not in <bundle files>`: add the key to the bundle or fix the
  spelling. When it ends `; it is only in <files>, which other locales do not fall back to (name
  the default locale in -Atessera.messages, e.g. <base>_en, or add the key to the base file)`, the
  key is only in a locale file: name the default locale in the option (`.../messages_en`, section
  2) or add the key to the base file.
- `message key '<key>' is only in the default locale's files, not in the base file or in <files> (or the files they fall back to): Spring Boot's default ResourceBundleMessageSource falls back to the default locale only for a locale with no file of its own, so it does not find the key in those locales (ReloadableResourceBundleMessageSource does); add the key to them or to the base file`
  (warning, once per key): with Boot's default message source a request in `fr` reads
  `messages_fr.properties` and then the base file, so the key renders as missing there. Add it to
  the files named or to the base file. With `ReloadableResourceBundleMessageSource` (and the default
  locale named in `-Atessera.messages`) the key resolves and the warning can be ignored.
- `message keys in this template are not checked: set -Atessera.messages=<bundle base path> to check #{...} keys at build time`
  (warning): set the option (section 2).
- `duplicate link parameter '<name>' in @{<path>}`.
- `link path variable {<name>} in @{<path>} has no parameter: add (<name>=...)`.
- `link @{<path>} has scheme '<scheme>:': only http and https absolute links are allowed; use a relative path, or pass a TrustedUrl instead of a link`.
- `link @{<path>} has a path variable in its host: write the host literally`.
- `link @{<path>} starts like a protocol-relative URL (a browser reads '\' as '/'), so it would load from another origin: write @{/...} for a path on this site, or @{//host/...} if another origin is meant`:
  a browser reads `/\host`, `\/host` and `\\host` as `//host`. Write `@{//host/...}` when another
  origin is meant; it stays allowed where protocol-relative links are (not in htmx, refresh or
  boosted form URLs).
- `link @{<path>} starts with '\', which a browser reads as '/': write '/' instead`.

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
- `a map key in a field path must be a literal or a variable such as key or entry.key, not an expression`.
- `a map key in a field path must be an int, a long, a String or an enum, got <type>`.
- `map key '<key>' cannot be bound in a field path: a key must be non-empty and contain no '[', ']' or quotes, which Spring's property paths cannot escape`.
- `cannot index <type> in a field path: [...] works on a List, an array or a Map`.
- `field '<path>' can only be used inside t:object: put t:object="..." on this element or an enclosing one`.
- `'<path>' is not a field path: use names like lastName, address.city or items[0].name`.
- `*{<name>} can only be used inside t:object: put t:object="..." on this element or an enclosing one, or write the full path (owner.<name>)`.
- `t:errors can only be used inside t:object (on this element or an ancestor)`.
- `t:errorclass needs literal class names (letters, digits, '-', '_'), e.g. t:errorclass="error"`.
- `t:errorclass needs t:field on the same element`.
- `unknown function fields.<fn>: the fields namespace has hasErrors('path'), errors('path') and value('path')`:
  `#fields.hasAnyErrors()` → `fields.hasErrors('*')`, `#fields.allErrors()` → `fields.errors('*')`,
  `#fields.hasGlobalErrors()` → `fields.hasErrors('global')`, `#fields.globalErrors()` → `fields.errors('global')`,
  `*{{p}}` → `fields.value('p')`.
- `fields.value takes a field path such as 'lastName', not '<keyword>': only a field has a value`:
  `'*'` and `'global'` name errors, not a field.
- `fields.<fn>(...) can only be used inside t:object, or with a Field parameter`.
- `fields.<fn> takes one string literal (a field path such as 'lastName', '*' for all errors or 'global' for the form object's own), a field expression such as *{items[i].name}, or a Field parameter`.
- `unknown function ids.<fn>: the ids namespace has seq('name'), next('name') and prev('name')`.
- `ids.<fn> takes one argument, the id (a field name such as 'roles')`.

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
- `argument <n> ('<param>') of fragment <signature> has type Field: pass a field path as a string literal, e.g. 'lastName' (checked against the enclosing t:object), a Field parameter, or a property of one, e.g. name.answer`.
- `argument <n> ('<param>') of fragment <signature> has type Field<T>, but the field has type <type>`
  (or `but '<name>' is a Field<?>`): the path must name a `T`; a plain `Field` can't be passed on
  as a `Field<T>`.
- `'<name>' is a Field of unknown type, so its properties cannot be bound: declare its type in the fragment signature, e.g. Field<Criterion> <name>`.
- `~{::<name>}: this template has no fragment or element named '<name>'`.
- `~{::<name>} selects an element, which takes no arguments`.
- `~{::<name>} selects an element that contains this t:insert/t:replace, so it would include itself`:
  select a sibling (`~{::body}` from `<html>` works because `<html>` is the caller).
- `~{::<name>} selects an element inside this element's t:object, but t:insert/t:replace runs before t:object, so the selection would render without it: move t:object to an enclosing element`.
- `~{::<name>} and the selections inside it include each other, so rendering would never end: no selected element may pass ~{::...} of an element that selects it`.
- `fragment '<template> :: <name>' is declared in <context> content but <t:insert> puts it in <context> content, where a browser parses its markup differently: insert it in <context> content, or declare the fragment inside <element> in its template`
  (`or declare the fragment outside <element>` when it is put in HTML content). A context is
  `HTML`, `<svg>`, `<math>`, `<annotation-xml>`, `<select>` or a raw-text element such as
  `<noscript>`; SVG `foreignObject`, `desc` and `title`, MathML `mi mo mn ms mtext` and
  `<annotation-xml encoding="text/html">` hold HTML. Contexts follow the tags as written, so a
  fragment inside `<svg t:replace>` is declared in `<svg>` content. `<svg t:replace>` puts the
  fragment where the `<svg>` is, `<svg t:insert>` inside it. A whole `<svg t:fragment>` fits in
  `<svg>` content too. Nothing fits in a
  `mixed <svg>/<math> (one holding an HTML element such as <p>)`, where the message ends
  `no fragment fits in mixed content: move the HTML element out of the <svg> or <math> or into a <foreignObject>, or write the <annotation-xml> encoding as a static attribute`.
  - A fragment declared in raw text fits nowhere: the message ends `declare the fragment outside <noscript>`.
    Inside raw text itself, only the raw-text error above is reported.
  - Under `t:remove="tag"` on an element whose tag changes the context (such as `<select>`; on
    `<svg>`, `<math>` and raw-text elements it is an error of its own), the context is `t:remove="tag" (on an element whose tag changes how its content is parsed)`
    and the message ends `no fragment fits there: drop the t:remove="tag"`.
  - In `<select>` content, an HTML fragment fits only without `<title>`, `<style>`, `<svg>`,
    `<math>`, `t:insert`, `t:replace`, `t:utext` and `[(...)]`: `insert it in HTML content, or keep <title>, <style>, <svg>, <math>, t:insert, t:replace, t:utext and [(...)] out of the fragment, since some browsers ignore those tags in a <select> and parse their content as markup`.
- `fragment ~{...} is in or holds an <svg> or <math> with an HTML element such as <p> in it, where a browser leaves foreign content early, so it fits nowhere: move the HTML element out of the <svg> or <math>, or into a <foreignObject>`.
  An `<annotation-xml>` whose `encoding` is computed (`t:encoding`, or a `t:attr` that names `encoding`), and MathML
  `mglyph` and `malignmark`, and a `<noscript>`, `<iframe>`, `<xmp>`, `<noembed>` or `<noframes>` inside
  `<svg>` or `<math>`, count the same way.
- `fragment ~{...} is in or is an element with t:remove="tag" whose tag changes how its content is parsed, so it is compiled for one context and rendered in another and fits nowhere: drop the t:remove="tag", or move the fragment out of that element`.
- `<t:insert> with a Fragment value is only allowed in HTML content, not in <context> content, where a browser parses the fragment's markup differently: insert a ~{template :: name} declared inside <context>`
  (under `t:remove="tag"` and in mixed content, the message ends as above).
- `fragment argument ~{...} is declared in <context> content, but a Fragment value is inserted in HTML content, where a browser parses its markup differently: pass a fragment or element declared outside <context>`.

## 7. Known limitations

Gaps in Tessera today, each with what to do until it is fixed.

- **Versions.** Spring 4.3+ / Boot 1.5+ on Servlet 3.0+; anything older is untested. Upgrade to
  Spring 4.3 first, or wire the classes by hand at your own risk.
- **Build.** Maven and Gradle (section 2); there is no Gradle plugin, only the processor wiring.
  Under Maven, a template-only or message-only change is recompiled only with
  `tessera-maven-plugin` (section 2). Under Gradle, the `compileJava` inputs cover both. There is no hot reload.
- **Messages.** `-Atessera.messages` checks the basenames it lists, read from the file system. Keys
  that only a jar or a database-backed `MessageSource` provides fail the build; give those pages a
  `@TemplateFunction` that looks them up, and accept that they are not checked.
- **View resolution.** Names are matched exactly, less one leading `/` and the spaces around a
  `::`. A `page :: fragment` view name (an htmx partial response) renders the fragment if it has an
  entry (section 4.9). `page :: name(args)`, selectors (`page :: #id`, `page :: .class`) and
  `~{...}` fall through to other resolvers, and so does a fragment with a `Fragment` or `Field`
  parameter or a `<head>`, `<body>` or table-section root: put arguments in the model, and give the
  response its own fragment or template.
- **Mixed engines.** During a migration the old engine renders every view Tessera doesn't know
  (section 2), so an app part-way through is protected only on the ported views; finish by
  removing the engine (section 3, step 6).
- **Types.** Collections outside `java.util` (`java.util.concurrent`, Guava, your own subclasses)
  are rejected as parameter and property types; declare the interface (`List`/`Map`, section 4.2).
  `java.util.Date`/`Calendar` are not view-safe; declare `Instant` or `ZonedDateTime` (section 2).
- **Forms.** No `t:field` that writes the value of a field
  whose type is not view-safe (a file input is fine, section 4.7). A `Field` fragment reads the
  value only as the display String (`fields.value(name)`).
- **CSRF and other ways a form posts elsewhere.** The CSRF input is left out of a form whose
  `action` is on another origin, a submit button's `formaction` must be same-origin where it
  may submit a form with the token, and so must every `<base href>` (section 4.7). Not seen: a static htmx request attribute such
  as `hx-post="https://other.example/"` on the form or a button in it. Give an external target its
  own form.
- **Scripts.** In a `<script>` with `[[...]]`, the code before the last `[[...]]` can't use `/`
  (division, regular expressions), template literals, `-->`, `#!`, `<!`, a backslash line
  continuation or U+2028/U+2029 (section 6): the compiler proves where each value goes without a
  JavaScript parser. Declare the data at the top of the script, or use a
  `<script type="application/json">` and `JSON.parse`.
- **Output.** `[[...]]` in a script serializes only the properties of the static type: a subclass's
  extra getters are not written, and Jackson annotations are ignored.
- **Rich text.** Stored HTML is printed through a sanitizer policy: `html.sanitize`,
  `html.sanitizeRich`, or one built with `HtmlPolicy.builder()` (section 4.4). The builder takes
  names only from a closed set, so `style`, `iframe`, `video`, `svg` and forms are never kept,
  and images from other hosts only with `allowRemoteImages()`, which lets whoever wrote the
  content see each reader's visit. Editor output that depends on them
  loses them: map what you need to classes in Java before sanitizing, or accept the loss.
  Rebuilding the HTML in a template from a tree parsed in Java is a build error
  (`STRUCTURE_DEPENDS_ON_DATA` below), and `SafeHtml.unchecked` is not a way around it
  (section 4.10, Rich text).
- **Framework attribute names.** Only the default names of client-side framework attributes are
  known (section 4.4). An Alpine prefix (`Alpine.prefix('data-x-')`), a Knockout binding provider
  or a _hyperscript `attributes` setting that reads other names is not detected, so expressions
  there are escaped as ordinary attributes and run as code. Keep the default names, or never
  compute those attributes.
- **htmx boosting from elsewhere.** Form URLs are checked only where `hx-boost` is visible around
  the form where it is written (section 4.4). Boosting from a layout or a fragment caller
  (`<body hx-boost="true">` in a layout that receives the page's content, or a boosted element of
  the same template that inserts the fragment), set from JavaScript, and a submit button outside
  its form (`form="id"`) are not seen (#498). On htmx 1.9.5 and later set
  `htmx.config.selfRequestsOnly = true` (the default from 2.0) so htmx requests only the page's
  origin; older htmx 1.x has no such setting, so upgrade.
- **Client-side templates (`{{ }}`).** Tessera breaks `{{` only in element text (section 4.4).
  AngularJS also interpolates attribute values (`title`, `value`, `alt`, `href`, ...), and Vue,
  petite-vue and AngularJS interpolate `<title>` and `<textarea>` text, where a comment would
  show or change the value. Inside a mounted app, put `ng-non-bindable` (AngularJS; it also
  covers the element's own attributes) or `v-pre` (Vue) on elements whose attributes or
  `<title>`/`<textarea>` hold user data, or keep server-rendered user data out of the mounted
  region. Custom delimiters (Vue `delimiters`, AngularJS `$interpolateProvider`) are not
  broken, so keep the default `{{ }}` or mark the region. The check for an expression inside a
  static `{{ }}` only sees one text: a `{{` before a tag or comment and an expression after it
  are not reported when a `}}` follows later in the element (#336). `SafeHtml.unchecked` text is
  not changed.
- **Security functions.** `auth` has `isAuthenticated`, `hasRole`/`hasAnyRole` and
  `hasAuthority`/`hasAnyAuthority`. They check the granted authorities as given, without a role
  hierarchy. Compute any other Spring Security expression in the controller.
- **Selections.** `~{::tag}` selects fragment names and tag names (hyphenated ones and `t:block`
  too), and only the first match: not ids, classes or every match as in Thymeleaf. Give the
  element a `t:fragment` name instead.
- **Not supported, by design:** runtime template loading, `__${...}__` preprocessing, dynamic
  names, the layout dialect, `th:inline` modes and `/*[[...]]*/` natural-template comments,
  collection selection/projection, expressions in `<style>` and `on*`, `String` values in `style`
  attributes, and Kotlin.
- **Expected output differences from Thymeleaf:** attributes set by `t:` attributes may move within
  the tag; `<td >` (after a removed attribute) renders as `<td>`; a `null` attribute value omits
  the attribute (Thymeleaf writes `value=""`); a `null` link path variable fails the render (section 3, step 4);
  element text from a value with `{{`, or starting or ending with `{`, gets empty comments next
  to those braces (section 4.4); with the Spring starter every `<script>` and `<style>` has a
  `nonce` attribute (section 4.11), which `tessera-migrate compare` ignores.
- **Runtime errors** are model type mismatches (`TemplateModelException`), missing primitive
  parameters, reading a property of `null`, `null` link path variables, `first`/`last` of an empty
  list, `ids.prev` before any input (section 4.7) and a variable form-path map key Spring can't
  bind (section 4.7). Everything else is a build error.

### Verifier errors

After its other checks, the compiler runs every template through a spec HTML parser (current
validator.nu). It accepts a template only if the parser reads it without a parse error (a missing
doctype aside) and outside three constructs where browsers are known to build different trees,
and it checks that each value lands where its escaping keeps it inert. It follows every `t:if`
branch and `t:each` pass, and checks a fragment at each place it is inserted. A partial (a
template that doesn't start with a doctype or `<html>`) is parsed where its first element goes:
a `<tr>` in a `<tbody>`, a `<td>` or `<th>` in a `<tr>`, an `<option>` or `<optgroup>` in a
`<select>`, an `<li>` in a `<ul>`, anything else in a `<body>`. Most templates a browser would
misread fail an earlier, more specific check from section 6 first. These errors catch the rest,
often markup a browser parses differently from how it looks: `<textarea>` or `<title>` inside
`<svg>`, `<![CDATA[` outside `<svg>` and `<math>`.

Markup a browser reads only by error recovery is an error even where browsers agree on it: a
second `<body>`, a `<div>` inside a `<p>`, an `<h3>` directly in a `<table>`, a stray end tag. The
most common one comes from a layout call: `t:replace="~{layout :: layout(~{::body})}"` passes
the page's whole `<body>` element, so the layout writes a `<body>` inside its own. Mark the page's
`<body>` `t:remove="tag"` so that only its children are passed (section 4.8, Layouts).

An error ends with `[verifier:RULE]`. For an expression inside a fragment, the error is at the
call site and the message names the fragment and the expression's position in it, and each call
on the way when fragments insert each other. When markup before the value put a browser where it
reads it, the message also names that element and where its start tag is: the raw-text element
(often a `<script>` the template doesn't show), the `<svg>` or `<math>`, or the table part. "Only with
scripting on" or "only with scripting off" means browsers differ there; fix the template all the
same.

| Rule | What a browser does | Fix |
|---|---|---|
| `TEXT_NOT_IN_TEXT` | Reads the value inside markup: a tag after a literal `<` or `</`, a character reference after `&`, a comment, a CDATA section, the rest of the page after `<plaintext>` | Write a literal `<` as `&lt;` and `&` as `&amp;`; move the expression out of the comment or CDATA section; use `<pre>`, not `<plaintext>` |
| `TEXT_IN_SCRIPT` | Runs the value as script code, or reads it as CSS, where escaping for text doesn't help: a `<script>` starts in markup where the template shows none, or the script is an SVG `<script>` | Move a `<script>` out of a `<title>` inside `<svg>`, `<math>` or `<select>`, and write a literal `<` in attribute values, comments and CDATA sections as `&lt;`; write an SVG script as an HTML `<script>` outside `<svg>` and `<math>`, with data as `[[${...}]]`; remove a `<!--` from a script; set a class, not CSS |
| `TEXT_IN_TABLE` | Reads text directly in a `<table>`, `<tbody>`, `<tr>` or `<colgroup>` and moves it in front of the table | Put it in a `<td>` or `<th>` |
| `TEXT_OUTSIDE_BODY` | Reads text in the `<head>` (which ends the head there) or after `</body>` (which it moves into the body) | Put it in `<title>`, `<meta content>` or an element in the body |
| `RCDATA_NOT_IN_RCDATA` | Reads text that another use of the same fragment writes in a `<title>` or `<textarea>` as ordinary text, where a `{{` in it could run in Vue or AngularJS | Use one fragment for the `<title>` or `<textarea>` and another for ordinary content, or insert it outside `<svg>` and `<math>` |
| `TEXT_IN_RCDATA` | Reads text that another use of the same fragment writes as ordinary content as the text of a `<title>` or `<textarea>`, where the comments Tessera writes to break `{{` would show | Use one fragment for the `<title>` or `<textarea>` and another for ordinary content |
| `ATTRIBUTE_NOT_IN_VALUE` | Reads an attribute's value outside the attribute: earlier markup ended the tag or started raw text | Fix the markup before the tag |
| `ATTRIBUTE_WRONG_NAME` | Reads the value as another attribute's, or drops a duplicate attribute | Write each attribute once |
| `ATTRIBUTE_VALUE_CHANGES_PARSE` | Decides from the value how the content parses: `encoding` on MathML `<annotation-xml>` | Write the attribute as a static value |
| `JSON_NOT_IN_SCRIPT` | Reads `[[...]]` JSON outside the code of an HTML `<script>`, for example in an SVG `<script>` | Write the script as an HTML `<script>` outside `<svg>` and `<math>` |
| `RAW_CLOSES_ELEMENT` | Lets the markup of `SafeHtml`, a `Fragment` value or an HTML message close an element open around it (an open `<p>`, `<a>`, heading or `<form>`), so the rest of the page parses differently | Insert it in a `<div>` instead, outside the open element |
| `MESSAGE_CHANGES_STATE` | Parses an HTML message's markup so that it closes an element open around it (a `<p>` in a `<p>`, an `<a>` in an `<a>`) or leaves one of its own open | Close every element the message opens, and insert it where its markup fits (a message with a `<p>` in a `<div>`, not in a `<p>`) |
| `RAW_NOT_FLOW` | Parses `SafeHtml`, a `Fragment` value or an HTML message outside body content: in the head, a table, a `<select>`, `<svg>`, `<math>`, or raw text such as `<noscript>` | Insert it in an element such as `<div>` in the body, or in a `<td>` |
| `FRAMESET` | Uses a `<frameset>` only if no body content came before it | Use an `<iframe>` or an ordinary layout |
| `LOOP_NO_FIXED_POINT` | Has more elements open after each `t:each` pass | Close inside the loop every element it opens |
| `STRUCTURE_DEPENDS_ON_DATA` | Nests deeper at each call of a fragment that inserts itself, directly or through other fragments (the message names each call in the cycle), so the page's structure depends on data | For stored rich text, `t:utext="html.sanitize(...)"`, `html.sanitizeRich` or an application `HtmlPolicy` (section 4.10, Rich text), and delete the fragment; for other trees, render a fixed depth or flatten the tree in Java |
| `TOO_MANY_STATES` | Can be in more than 24 different states (different elements open) after the branches and loops before this point | Close inside each `t:if`, `t:switch` and `t:each` the elements it opens, or split the template into fragments |
| `PARSE_ERROR` | Reads the markup only by error recovery: a second `<body>`, `<head>` or `<html>` (often from `layout(~{::body})`), a block element that closes an open `<p>`, a `<script>`, `<meta>`, `<link>` or `<title>` between `</head>` and `<body>`, content directly in a `<table>`, attributes without a space between them, a stray end tag, `/>` on an element that isn't void, an HTML element such as `<p>` inside `<svg>` or `<math>`, a `<?...?>`, a malformed DOCTYPE (a `>` inside it), `'<!'` or `'</'` not followed by a letter in text (a comment up to the next `>`), `<![CDATA[` outside `<svg>` and `<math>` | Mark a `<body>` passed to a layout `t:remove="tag"`; use a `<div>` instead of the `<p>`; move the element into the head or the body; put table content in a `<td>` or `<th>`; write a space between attributes; write the end tag (`<script ...></script>`); close the `<svg>` or `<math>` first, or put the HTML in a `<foreignObject>`; remove the `<?...?>`; write `<!doctype html>`; write `&lt;` for a literal `<`. For other errors the message quotes the parser: fix the markup it names |
| `FOREIGN_IN_TABLE` | Builds different trees for a nested table, `<template>` or `<select>` that ends inside `<svg>` or `<math>` in a table (Firefox goes on as if outside the table, Chrome and Safari go back to the cell); Tessera rejects any `<svg>` or `<math>` in a table part or `<template>` | Move it out of the table, or show the image with `<img src="....svg">` |
| `TABLE_IN_TEMPLATE` | Handles a `</table>` inside a `<template>` that holds table parts directly differently (Firefox closes a table around the `<template>`) | Put a whole `<table>` in the `<template>` |
| `SELECT_CONTENT` | Drops everything in a `<select>` but `<option>`, `<optgroup>` and `<hr>` in classic `<select>` parsing (Firefox ESR), and keeps it in newer browsers | Insert only those into a `<select>`, also from fragments, `t:remove="tag"` and `<template>` |
| `CDATA_IN_INTEGRATION_POINT` | Reads a `<![CDATA[` section in SVG `<title>`, `<desc>` or `<foreignObject>`, or in MathML `<mi>`, `<mo>`, `<mn>`, `<ms>`, `<mtext>` or an HTML `<annotation-xml>`, as a comment that ends at the first `>` (Chrome), where Firefox reads a CDATA section, so the markup after it differs | Write the text without `<![CDATA[` and `]]>`, with `<` as `&lt;` and `>` as `&gt;` |
| `UNCALLED_FRAGMENT_FITS_NOWHERE` | (Tessera can't check it) A fragment that no template inserts is checked on its own, in the context it is declared in; declared in raw text (`<noscript>`, `<title>`, ...), in an `<svg>` or `<math>` holding an HTML element, or under `t:remove="tag"`, there is no such context | Declare the fragment in HTML, `<svg>` or `<math>` content, or delete it |
| `ENGINE_FAILURE`, `UNKNOWN_NODE` | (Tessera couldn't check the template) | A compiler bug: report it with the template |
