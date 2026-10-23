# Tessera

Tessera is a compiled, safe-by-design successor to Thymeleaf for Spring MVC applications. It is
the pilot of Chainguard's secure-by-design library portfolio.

**Why it exists.** Frontier models make finding vulnerabilities cheap. In libraries whose design
invites a bug class, CVEs will arrive faster than anyone can triage and patch them. The defense
that scales is to remove the bug class by design. Thymeleaf's recurring bug class is server-side
template injection: its expressions are SpEL evaluated at runtime, `__${...}__` re-parses data
as code, and its sandbox fixes have been deny-list patches that the next bypass gets around. Tessera keeps Thymeleaf's
natural-HTML `t:` attribute syntax, forms, i18n and fragments. It compiles templates to plain
Java at build time, so there is no runtime interpreter to inject into. More:
[strategy](docs/strategy.md), [vision](docs/tessera-vision.md).

## What it looks like

A template is HTML with a typed parameter header:

```html
<!DOCTYPE html>
<html xmlns:t="https://tessera.dev"
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

The application's `javac` compiles `customers/show.html` to a `ShowTemplate` class. A controller
returns the view name `"customers/show"` with a model, as with Thymeleaf, or returns the typed
`ShowTemplate.of(customer, orders)`. A mistake such as an unknown property, a method that isn't a
getter, a type that isn't view-safe, or `T(java.lang.Runtime)` is a build error with `file:line:col`.

## Design principles

Each principle removes a class of bugs. The full rules are in [AGENTS.md](AGENTS.md).

1. **Templates are code.** They compile at build time. The runtime has no template parser and no
   API that takes template source.
2. **No ambient authority.** A template sees only its declared, typed `t:params`: no request,
   session, beans, class names, `new` or statics.
3. **Allow-list everything callable.** Templates read getters and record components of view-safe
   types and call only registered `@TemplateFunction`s. `Class`, reflection, `Runtime`, servlet
   and Spring context types are never reachable.
4. **No reflection at runtime.** All access is generated Java. No SpEL, OGNL or `ScriptEngine`.
5. **Escaping is contextual and cannot be forgotten.** The output context picks the escaper. Raw
   output needs a `SafeHtml` or `TrustedUrl` built in Java.
6. **Familiar before novel.** The syntax tracks Thymeleaf wherever that is safe, so migration is
   mostly mechanical.

## Requirements

- **Your application:** builds with JDK 8+ javac and Maven, and runs on Java 8+. The runtime, the
  Spring starters and the annotation processor are Java 8 class files, and the generated code
  compiles at release 8.
- **Spring:** Spring Framework 5.0+ or Spring Boot 2.0+. Tested on Boot 2.0, 2.7, 3.5 and 4.1 and
  on plain Spring MVC 5.0.
- **Dependencies:** `tessera-runtime` has none. The starters pass on only `tessera-runtime`;
  Spring, the servlet API and Spring Security come from your application.
- **Building this repo:** JDK 21+ and Maven 3.9+.

Artifacts are `0.1.0-SNAPSHOT` and not published to a remote repository yet. Run
`mvn -B -ntp install` in this repo to put them in your local Maven repository.

## Add it to a project

Add the starter for your servlet API (use exactly one) and run the annotation processor:

| Application | Artifact |
|---|---|
| `jakarta.servlet` (Boot 3+, Spring 6+) | `dev.tessera:tessera-spring-boot-starter` |
| `javax.servlet` (Boot 2.x, Spring 5.x) | `dev.tessera:tessera-spring-boot-starter-javax` |
| No Spring | `dev.tessera:tessera-runtime` |

```xml
<dependency>
  <groupId>dev.tessera</groupId>
  <artifactId>tessera-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>

<plugin>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths>
      <path>
        <groupId>dev.tessera</groupId>
        <artifactId>tessera-compiler</artifactId>
        <version>0.1.0-SNAPSHOT</version>
      </path>
    </annotationProcessorPaths>
    <compilerArgs combine.children="append">
      <arg>-Atessera.templates=${project.basedir}/src/main/resources/templates</arg>
      <arg>-Atessera.package=com.example.templates</arg>
    </compilerArgs>
  </configuration>
</plugin>
```

With Spring Boot, auto-configuration registers the view resolver. It runs before Thymeleaf's and
falls through for names it doesn't know, so both engines can run side by side during a migration.
With plain Spring MVC, import the configuration:

```java
@Configuration
@EnableWebMvc
@Import(dev.tessera.spring.TesseraConfiguration.class)
public class WebConfig {}
```

The details (other processors, `<proc>none</proc>` on test compilation, message-key checking,
XML configuration, older Boot versions) are in [section 2 of the guide](docs/guide.md#2-build-setup).

## Migrate an existing app

[docs/guide.md](docs/guide.md) is the porting guide for Thymeleaf, JSP, FreeMarker, Mustache and
similar engines. It is written so a coding agent can work from it alone. The loop: record every
page of the old app, convert one template at a time, and compare the new app's pages against the
recording.

**Claude Code plugin.** `plugins/tessera` packages the guide and the loop as the `migrate` skill.
Ask Claude Code to move an app's views to Tessera, or run `/tessera:migrate`. Install it from this
repo's marketplace:

```sh
claude plugin marketplace add chainguard-dev/harder   # private repo: needs git access (gh auth setup-git)
claude plugin install tessera@chainguard-harder
```

Or try it from a checkout without installing: `claude --plugin-dir plugins/tessera`. The skill
fetches `tessera-migrate` with Maven, so run `mvn install` here first.

**`tessera-migrate`** is a self-contained jar that runs on Java 8+:

```sh
java -jar tessera-migrate.jar record  http://localhost:8080 pages.txt baseline/   # old app
java -jar tessera-migrate.jar compare http://localhost:8080 pages.txt baseline/   # new app
java -jar tessera-migrate.jar old-templates/ new-templates/ --report report.md    # Thymeleaf converter
```

`pages.txt` lists one request per line (`GET /path` or `POST /path a=1&b=2`), sent in order with
one cookie jar and following redirects like a browser. `compare` prints `same`, `reordered` or
`differs` per page. It compares status, final URL and the page as a browser would parse it, and
`--ignore REGEX` masks volatile text. The converter does the mechanical `th:` to `t:` rewrite of a
Thymeleaf directory and reports, by `file:line`, what it couldn't convert.

## Repository layout

| Module | What it is |
|---|---|
| `tessera-runtime` | Runtime: escapers, safe types, links, messages, forms. No dependencies |
| `tessera-compiler` | Template compiler and annotation processor. No dependencies outside Tessera |
| `tessera-spring-boot-starter` | Spring MVC integration (`jakarta.servlet`): view resolver, typed return values, forms, CSRF, i18n, `auth` functions |
| `tessera-spring-boot-starter-javax` | The same starter relocated to `javax.servlet` |
| `tessera-spring-tests` | Runs the starter's tests on Boot 3.5, 2.7, 2.0 and plain Spring MVC 5.0 |
| `tessera-migrate` | Page record/compare and the Thymeleaf converter (bundles attoparser and jsoup) |
| `tessera-benchmarks` | JMH benchmarks against Thymeleaf ([results](tessera-benchmarks/RESULTS.md)) |
| `examples/petclinic` | Spring PetClinic ported to Tessera ([porting notes](examples/petclinic/PORTING.md)) |
| `plugins/tessera` | The Claude Code migration skill ([marketplace](.claude-plugin/marketplace.json)) |
| `bench/migration` | The migration benchmark |

## Build and test

```sh
mvn -B -ntp verify
```

Needs JDK 21+. Set `JDK8_HOME` to a JDK 8 home to also run `Jdk8ProcessorTest`, which builds a
small app with a real JDK 8 javac; without it that test is skipped locally. Maven doesn't
recompile when only a template changes, so use `mvn clean verify` when in doubt. CI is Chainguard
Checks running the same command ([.chainguard/ci/maven.yaml](.chainguard/ci/maven.yaml)).

The guide's examples are compiled by `GuideExamplesTest` and its diagnostic catalogue is checked
by `DiagnosticCatalogueTest`. `plugins/tessera/skills/migrate/references/guide.md` is a copy of
[docs/guide.md](docs/guide.md): edit the guide, then copy it (`MigrationSkillTest` fails when the
copy is stale).

## Migration benchmark

`bench/migration` measures whether a coding agent, given only the porting guide and a legacy
Spring app, can migrate its views to Tessera on its own without breaking anything. Each run is
scored on the app's build and tests, `tessera-migrate compare` against a recording of the
original app, and a check that security wasn't weakened. See
[bench/migration/README.md](bench/migration/README.md) for how it works and
[bench/migration/RESULTS.md](bench/migration/RESULTS.md) for results.

## Documentation

- [docs/guide.md](docs/guide.md): porting guide and reference (syntax, every compiler diagnostic, known limitations)
- [docs/mvp-design.md](docs/mvp-design.md): how the compiler, runtime and starter work
- [docs/tessera-vision.md](docs/tessera-vision.md): long-term design
- [docs/strategy.md](docs/strategy.md): the secure-by-design library portfolio
- [AGENTS.md](AGENTS.md): rules for contributors, human or agent; [docs/handoff.md](docs/handoff.md): how the work is orchestrated
- [docs/research.md](docs/research.md) (research memo) and [docs/issues.md](docs/issues.md) (MVP build plan): historical
