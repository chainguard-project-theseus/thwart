# Thwart

Thwart is a compiled, safe-by-design successor to Thymeleaf for Spring MVC. It keeps Thymeleaf's
natural-HTML `t:` attributes, forms, i18n and fragments, but compiles templates to plain Java in
the application's own `javac`. Nothing interprets a template at runtime, so server-side template
injection is impossible by construction ([security model §1](docs/security-model.md#1-the-guarantee)).
XSS is impossible by construction too, relative to the pinned WHATWG parser and the documented
exclusions ([§7](docs/security-model.md#7-assumptions)).

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

## AI compatible

Thwart is not a drop-in replacement for Thymeleaf, and it isn't API or ABI compatible on purpose.
The things that make Thymeleaf unsafe are part of its API: expressions that reach any bean or
static method, escaping a template can turn off, logic in templates, and inline scripts that a
strict Content Security Policy (CSP) blocks. Most real templates need changes to compile.

Thwart is **AI compatible** instead. A coding agent that has never seen Thwart can migrate an app
to it on its own and show that the app still works. Four parts make that possible:

1. **A skill and a guide.** The Claude Code plugin in [plugins/thwart](plugins/thwart) has one
   skill, [thwart](plugins/thwart/skills/thwart/SKILL.md), which works from the
   [guide](docs/guide/README.md): setup, templates, migration and known limitations.
2. **Compiler errors written as fixes.** Each names the file, line and column, says what is wrong,
   and says what to write instead, like the one above.
3. **A page-parity checker.** `thwart-migrate record` saves every page of the old app, and
   `thwart-migrate compare` checks every page of the migrated app against it. The skill also has
   the agent load the pages in a headless browser on both apps and compare the console, which
   catches scripts that throw and CSP violations.
4. **The migration test.** [bench/migration](bench/migration/README.md) gives a fresh agent the
   plugin and a real app, with Claude's web tools off and no human, and scores the result with the
   same tools: the build and tests pass, every page matches, no security check or test is switched
   off, no part of the old engine is left, and the browser shows no new errors.

The results so far (the latest round is run 15, 2026-11-15):

- **PetClinic family** (7 apps, Spring Boot 1.5 to 4.1 and Spring MVC 4.3 to 7, Thymeleaf and
  JSP): a strict pass in **41 of 42 runs** over all rounds. In the latest round each migration
  took 6 to 16 minutes and cost about $2 to $4.50.
- **Larger real-world apps** (10 apps; Thymeleaf with htmx, FreeMarker, JSP with Apache Tiles,
  Velocity, Pebble): every run builds and passes all of the app's tests. In the latest round each
  migration took 10 to 51 minutes and cost $3.50 to $32.
- **The gap: strict page-by-page parity on the larger apps.** In the latest round **0 of 10**
  larger apps passed strictly, 1 of 10 passed under the looser `pass_forced` rule, and a mean of
  about 46% of their pages matched. Every larger-app strict pass so far is from an older round
  (5 of 42 runs on the held-out apps). Some differences are what Thwart requires (inline event
  handlers moved out for the CSP, output the old engine printed raw now escaped). Some are
  mistakes. This is the gap Thwart has to close.

The plugin and guide have changed since run 15, and a fresh round will be run before the release.
[docs/ai-compatible.md](docs/ai-compatible.md) has the method, the results by app and engine, and
the test's limitations.

## What's different

- **No template interpreter at runtime.** Templates are Java classes. The runtime has no template
  parser and no API that takes template source, and a template reaches only its typed `t:params`.
  Thymeleaf's recurring bug class, SpEL evaluated at render time and `__${...}__` re-parsing data
  as code, has nothing to attach to.
- **Contextual escaping, checked at build time.** The compiler picks the escaper or check for each
  output position (text, attribute, URL, script, style, ...), with no switch to turn it off. Each
  template is also checked against a spec HTML parser and rejected where browsers could build a
  different tree.
- **A strict Content Security Policy by default.** Every `<script>` and `<style>` the template
  writes gets the request's nonce, and the starter sends `script-src 'nonce-N' 'strict-dynamic';
  object-src 'none'; base-uri 'none'` unless the app or Spring Security sets a policy. Inline
  scripts without the nonce, and libraries that use `eval` or `new Function`, break. The off
  switch is `thwart.csp.enabled=false` ([Rendering](docs/guide/templates.md#rendering)).
- **Typed parameters**, checked at build time.
- **Speed:** about 20× Thymeleaf's `TemplateEngine` at rendering the [benchmark pages](thwart-benchmarks/RESULTS.md) (render only, not the whole request).

What it costs:

- It is stricter: some constructs are rejected, each with a recipe in the [guide](docs/guide/README.md).
- Logic and formatting move to Java: getters, records the controller builds, registered functions.
- Client frameworks that compile the server's DOM as a template are unsupported.
- Hot reload is a recompile and a Spring Boot DevTools restart, a few seconds per save ([guide](docs/guide/migrate.md#hot-reload)).
- It is not published to any repository yet.

## Where it runs

- **Spring MVC on servlets.** Applications build with JDK 8+ javac and run on Java 8+, with Spring
  Framework 4.3+ or Spring Boot 1.5+ on Servlet 3.0+. Tested on Boot 1.5, 2.0, 2.7, 3.5 and 4.1
  and Spring MVC 4.3 and 5.0; CI compiles applications with JDK 8 and 21.
- **One starter, by servlet API.** `thwart-spring-boot-starter` for `jakarta.servlet` (Boot 3+,
  Spring 6+), `thwart-spring-boot-starter-javax` for `javax.servlet` (Boot 1.5-2.x, Spring 4.3-5.x).
- **Spring MVC without Boot.** Add `@Import(dev.chainguard.thwart.spring.ThwartConfiguration.class)`
  ([setup](docs/guide/setup.md#spring)).
- **Outside a request** (a mail job, a command-line tool), render a compiled template to a string
  with `thwart-runtime` alone: `XTemplate.of(...).renderToString(ctx)`. The templates are still
  compiled by the annotation processor. `RenderContext.defaults()` has no messages, an empty
  context path and no form binding, and there is no response to send a CSP header with
  ([Rendering](docs/guide/templates.md#rendering)).
- **Not supported:** WebFlux, and web frameworks other than Spring MVC.

## Get started

Thwart isn't published yet: first run `mvn -B -ntp install` in this repo to put version
`0.1.0-SNAPSHOT` in your local Maven repository.

**With a coding agent.** Install the Claude Code plugin:

```sh
claude plugin marketplace add chainguard-project-theseus/thwart
claude plugin install thwart@thwart
```

Or run `claude --plugin-dir plugins/thwart` from a checkout. Then ask Claude Code to add Thwart to
an app or to migrate an app's views to it, or run `/thwart:thwart`. Other agents can start at
[plugins/thwart/skills/thwart/SKILL.md](plugins/thwart/skills/thwart/SKILL.md).

**By hand.** Depend on one starter (`dev.chainguard.thwart:thwart-spring-boot-starter:0.1.0-SNAPSHOT`
or `thwart-spring-boot-starter-javax`) and run the annotation processor. With Maven:

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
<!-- check: recompiles when only a template changed; Maven skips javac otherwise -->
<plugin>
  <groupId>dev.chainguard.thwart</groupId>
  <artifactId>thwart-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <executions><execution><goals><goal>check</goal></goals></execution></executions>
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

[docs/guide/setup.md](docs/guide/setup.md) has the full setup for both: other annotation processors,
message-key checks, and the `check` goal's options (it needs Maven 3.6.3+).
[examples/gradle](examples/gradle/build.gradle) is a Spring Boot app built with Gradle in CI.
Thwart's view resolver runs before the old engine's, so both run side by side; until it is
removed, the old engine still renders every view Thwart doesn't know, outside Thwart's guarantees.

## Compared with other engines

Thwart is for moving an existing Spring MVC app off its old engine. JTE is the better pick for a
new Spring Boot 3/4 app that doesn't need Thymeleaf syntax. [docs/comparison.md](docs/comparison.md)
compares Thwart with the engines people migrate from and with other compiled engines, and says
where Thwart is weaker.

## Status and contributing

Version `0.1.0-SNAPSHOT`, a prototype.

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
| Add Thwart to an app | [Get started](#get-started), then [docs/guide/setup.md](docs/guide/setup.md) |
| Migrate an existing app | [The Claude Code plugin](#get-started) or the [migration guide](docs/guide/migrate.md) |
| Write templates | [docs/guide/templates.md](docs/guide/templates.md) and [docs/guide/limitations.md](docs/guide/limitations.md) |
| See how agents do at migrating | [docs/ai-compatible.md](docs/ai-compatible.md) |
| Check the security claims | [docs/security-model.md](docs/security-model.md), then [docs/security-architecture.md](docs/security-architecture.md) and [docs/context-model.md](docs/context-model.md) |
| Compare with other engines | [docs/comparison.md](docs/comparison.md) |
| Understand the internals | [docs/architecture.md](docs/architecture.md) |
| Work on this repo | [AGENTS.md](AGENTS.md) |

## For contributors

| Module | What it is |
|---|---|
| `thwart-runtime` | Escapers, safe types, links, messages, forms. No dependencies |
| `thwart-compiler` | Annotation processor; shades `thwart-html5`, the verifier's HTML parser |
| `thwart-spring-boot-starter`, `-javax` | Spring MVC integration; `thwart-spring-tests` runs it on older Spring |
| `thwart-migrate`, `thwart-maven-plugin` | Page record/compare and Thymeleaf converter; `thwart:check`, `thwart:watch` |
| `thwart-benchmarks`, `bench/migration` | JMH benchmarks against Thymeleaf; the migration test |
| `examples/`, `plugins/thwart` | PetClinic and Gradle examples; the Claude Code plugin |

Build and test with `mvn -B -ntp verify` (JDK 21+, Maven 3.9+; set `JDK8_HOME` to a JDK 8 to run
`Jdk8ProcessorTest`). The files in `plugins/thwart/skills/thwart/references/` must stay copies of
the guide in `docs/guide/` (`ThwartSkillTest`). The [migration test](bench/migration/README.md)
measures whether a coding agent can migrate a real app with only the plugin. Maintainer rules,
for humans and agents, are in [AGENTS.md](AGENTS.md).

## License

Thwart is licensed under the [Apache License, Version 2.0](LICENSE). [NOTICE](NOTICE) lists the
third-party material the repository and its artifacts contain.
