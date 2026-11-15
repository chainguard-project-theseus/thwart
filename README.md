# Thwart

Thwart is a compiled, safe-by-design successor to Thymeleaf for Spring MVC. It keeps Thymeleaf's
natural-HTML `t:` attributes, forms, i18n and fragments, but compiles templates to plain Java in
the application's own `javac`, which makes template injection and XSS impossible by construction,
relative to the pinned WHATWG parser and the documented exclusions
([security-model.md §7](docs/security-model.md#7-assumptions)).

## Why

Thymeleaf's recurring bug class is server-side template injection: its expressions are SpEL
evaluated at runtime, `__${...}__` re-parses data as code, and its sandbox fixes have been
deny-list patches that the next bypass gets around. Thwart removes the class by design: there is
no runtime interpreter to inject into ([docs/strategy.md](docs/strategy.md)).

## What it looks like

```html
<!DOCTYPE html>
<html xmlns:t="https://thwart.chainguard.dev"
      t:import="com.example.*"
      t:params="Customer customer, List<Order> orders">
  <body>
    <h1 t:text="|Hello, ${customer.name}!|">Hello, Ann!</h1>
    <p t:if="orders.isEmpty()">No orders yet.</p>
    <ul>
      <li t:each="o : orders"><a t:href="@{/orders/{n}(n=o.number)}" t:text="o.total">9.99</a></li>
    </ul>
  </body>
</html>
```

`customers/show.html` compiles to a `ShowTemplate` class; the controller still does
`return "customers/show";` with `customer` and `orders` in the model. Mistakes are build errors.
Add `<button onclick="newOrder()">New order</button>` after "No orders yet." and `javac` prints:

```text
error: customers/show.html:7:55: error: onclick on <button> is not allowed: inline event handlers are not supported: a strict CSP blocks them; remove onclick and attach the listener from a <script> with addEventListener, or mark the element with a data-* attribute and handle it with one delegated listener
```

## What you get, and what it costs

You get:
- No template interpreter at runtime: nothing at render time parses a template or evaluates an expression.
- Escaping chosen for each output context, with no switch to turn it off.
- Typed parameters, checked at build time.
- A strict Content Security Policy by default.
- Speed: about 20× Thymeleaf or more on the [benchmark pages](thwart-benchmarks/RESULTS.md).

It costs:
- It is stricter: some constructs are rejected, each with a recipe in the [guide](docs/guide.md).
- Logic and formatting move to Java: getters, records the controller builds, registered functions.
- Client frameworks that compile the server's DOM as a template are unsupported.
- Hot reload is a recompile and a Spring Boot DevTools restart, a few seconds per save ([guide](docs/guide.md#31-hot-reload)).
- It is not published to any repository yet.

## Status and contributing

Version `0.1.0-SNAPSHOT`: run `mvn -B -ntp install` in this repo to put it in your local Maven repository.

- Java: applications build with JDK 8+ javac and run on Java 8+ (CI compiles them with JDK 8 and 21).
- Spring: Framework 4.3+ or Boot 1.5+ on Servlet 3.0+; tested on Boot 1.5, 2.0, 2.7, 3.5, 4.1 and Spring MVC 4.3, 5.0.

We're building Thwart in the open at Chainguard. We don't publish it to
any package registry; it ships only from Chainguard's own repositories.

We don't accept pull requests from external contributors. If you and your
organization would seriously like to get involved, email
dlorenc@chainguard.dev.

Use of the Thwart and Chainguard names is governed by Chainguard's
trademark policy.

## Where to go next

| Goal | Read |
|---|---|
| Add Thwart to an app | [Quick start](#quick-start), then [guide §2](docs/guide.md#2-build-setup) |
| Migrate an existing app | [The Claude Code skill](#migrating) or the [porting guide](docs/guide.md) |
| Check the security claims | [docs/security-model.md](docs/security-model.md), then [docs/security-architecture.md](docs/security-architecture.md) and [docs/context-model.md](docs/context-model.md) |
| Compare with other engines | [docs/comparison.md](docs/comparison.md) |
| Understand the internals | [docs/architecture.md](docs/architecture.md) |
| Work on this repo | [AGENTS.md](AGENTS.md) |

## Quick start

Depend on one starter, `dev.chainguard.thwart:thwart-spring-boot-starter:0.1.0-SNAPSHOT` for
`jakarta.servlet` (Boot 3+, Spring 6+) or `thwart-spring-boot-starter-javax` for `javax.servlet`
(Boot 1.5-2.x, Spring 4.3-5.x), and run the annotation processor. With Maven:

```xml
<plugin>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths>
      <path>
        <groupId>dev.chainguard.thwart</groupId>
        <artifactId>thwart-compiler</artifactId>
        <version>0.1.0-SNAPSHOT</version>
      </path>
    </annotationProcessorPaths>
    <compilerArgs combine.children="append">
      <arg>-Athwart.templates=${project.basedir}/src/main/resources/templates</arg>
      <arg>-Athwart.package=com.example.templates</arg>
    </compilerArgs>
  </configuration>
</plugin>
```

With Gradle (no plugin; the `inputs` line recompiles when only a template changed):

```groovy
dependencies {
  annotationProcessor 'dev.chainguard.thwart:thwart-compiler:0.1.0-SNAPSHOT'
}

def templates = file('src/main/resources/templates')
tasks.named('compileJava') {
  options.compilerArgs += ["-Athwart.templates=$templates", '-Athwart.package=com.example.templates']
  inputs.dir(templates).withPropertyName('thwartTemplates').withPathSensitivity(PathSensitivity.RELATIVE)
}
```

[Guide §2](docs/guide.md#2-build-setup) has the full setup for both: other annotation processors,
message-key checks, and Maven's `thwart-maven-plugin` `check` goal, which recompiles when only a
template changed. [examples/gradle](examples/gradle/build.gradle) is a Spring Boot app built with
Gradle in CI. Plain Spring
MVC without Boot adds `@Import(dev.chainguard.thwart.spring.ThwartConfiguration.class)`. Thwart's view
resolver runs before the old engine's, so both run side by side; until it is removed, the old
engine still renders every view Thwart doesn't know, outside Thwart's guarantees.

The starter sends `Content-Security-Policy: script-src 'nonce-N' 'strict-dynamic'; object-src
'none'; base-uri 'none'` with Thwart pages unless a CSP header is already set (yours or Spring
Security's wins). Scripts the template writes get the nonce; inline scripts without it, and
libraries that use `eval` or `new Function`, break. Turn it off with `thwart.csp.enabled=false`
([guide §4.9](docs/guide.md#49-rendering)).

## Migrating

```sh
claude plugin marketplace add chainguard-project-theseus/thwart
claude plugin install thwart@thwart
```

Or run `claude --plugin-dir plugins/thwart` from a checkout. Then ask Claude Code to move an app's
views to Thwart, or run `/thwart:migrate`. The skill uses `thwart-migrate` to record the old
app's pages and compare the new app against them, and its Thymeleaf converter for the mechanical
`th:` to `t:` rewrite. Everything else is in the [porting guide](docs/guide.md).

## For contributors

| Module | What it is |
|---|---|
| `thwart-runtime` | Escapers, safe types, links, messages, forms. No dependencies |
| `thwart-compiler` | Annotation processor; shades `thwart-html5`, the verifier's HTML parser |
| `thwart-spring-boot-starter`, `-javax` | Spring MVC integration; `thwart-spring-tests` runs it on older Spring |
| `thwart-migrate`, `thwart-maven-plugin` | Page record/compare and Thymeleaf converter; `thwart:check`, `thwart:watch` |
| `thwart-benchmarks`, `bench/migration` | JMH benchmarks against Thymeleaf; the migration benchmark |
| `examples/`, `plugins/thwart` | PetClinic and Gradle examples; the Claude Code plugin |

Build and test with `mvn -B -ntp verify` (JDK 21+, Maven 3.9+; set `JDK8_HOME` to a JDK 8 to run
`Jdk8ProcessorTest`). `plugins/thwart/skills/migrate/references/guide.md` must stay a copy of
`docs/guide.md` (`MigrationSkillTest`). The [migration benchmark](bench/migration/README.md)
measures whether a coding agent can migrate a real app from the guide alone. Maintainer rules,
for humans and agents, are in [AGENTS.md](AGENTS.md).

## License

Thwart is licensed under the [Apache License, Version 2.0](LICENSE). [NOTICE](NOTICE) lists the
third-party material the repository and its artifacts contain.
