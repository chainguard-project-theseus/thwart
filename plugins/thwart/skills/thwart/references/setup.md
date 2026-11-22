# Build setup

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
Gradle plugin. [Maven](#maven) and [Gradle](#gradle) below give the complete setup for each; what they share:

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

## Maven

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

## Gradle

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

## Spring

**Spring Boot.** The starter's auto-configuration registers the view resolver, the template
registry and a `TemplateAvailabilityProvider`, on Boot 1.5 through 4, including `@WebMvcTest`
slices. Thwart's resolver runs first and falls through for view names it doesn't know, so the old
engine can stay while you migrate. While another engine's view resolver is a bean, the starter logs
`Thwart: another template engine is still configured` naming it. JSP, and resolvers added only
through `ViewResolverRegistry` or inside a `ContentNegotiatingViewResolver`, are not reported.

**Spring MVC without Boot.** Import `dev.chainguard.thwart.spring.ThwartConfiguration` (or declare it as a
bean in XML). It also registers a resolver for `redirect:` and `forward:` names, which the old
engine's resolver often provided: without one, removing the old engine breaks redirects. If your
container doesn't scan for `WebApplicationInitializer`s, register the CSP filter ([Rendering](templates.md#rendering)).

**Security.** Spring Security's CSRF token goes into POST forms automatically ([Forms](templates.md#forms)). Keep
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
