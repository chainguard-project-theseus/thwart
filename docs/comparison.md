# Tessera compared with JTE, Rocker, JStachio and Qute

Tessera isn't the only compiled, typed Java template engine. This page compares it with four
others, on the axes people ask about, and says where another engine is the better fit. The claims
about the other engines come from their official docs and repositories as of October 2026
(sources at the end). If one is out of date, fix it here.

## Summary

| | Tessera | JTE | Rocker | JStachio | Qute |
|---|---|---|---|---|---|
| **Templates** | Compiled to Java by an annotation processor in the app's javac. The runtime has no template parser | Compiled to Java classes. Precompiled by the Maven or Gradle plugin, or compiled at runtime in development mode (hot reload) | Compiled to Java by a Maven or Gradle plugin. Optional hot reload recompiles at runtime | Compiled to Java by an annotation processor | Parsed by the Qute engine at runtime. In Quarkus, templates are validated at build time |
| **Syntax** | Thymeleaf attributes (`t:text`, `t:each`, `@{...}`, `#{...}`) on HTML | Its own `@`/`${}` syntax with Java (or Kotlin) expressions | Its own `@` syntax with Java expressions | Mustache (logic-less) | Its own `{...}` syntax |
| **Type checking** | Every expression is resolved at build time against declared `t:params` and a view-safe type allow-list | Java expressions, checked by javac | Java expressions, checked by javac | Bindings are checked against the model at build time | Only for type-safe templates (`@CheckedTemplate`) and type-safe expressions. Other expressions are resolved at render time, and a strict-rendering error is thrown then |
| **Auto-escaping** | Contextual: text, attribute, typed attribute (literals only), URL (scheme-checked), resource URL (same-origin literal link), framework-neutral values in non-standard attributes (`data-*`, `hx-*`, ...), `<script>` (JSON in a closed prelude), style (`css.*` values only) and `<meta http-equiv="refresh">`, each with its own escaper or check. Expressions in `on*` attributes and `srcdoc` are rejected | Contextual with `ContentType.Html`: tag body, attribute, `on*` JavaScript attribute and `<script>`, each with its own escaper | By content type: an `.html` template HTML-escapes every value, the same way everywhere | By content type: HTML escaping (Mustache), not contextual. Off in zero-dependency mode | By content type: HTML/XML templates escape `' " < > &` everywhere. Not contextual |
| **Checked against a spec HTML parser** | Yes. At build time, each template is run through a pinned validator.nu HTML parser and rejected on a parse error or a construct where browsers build different trees. Tests also compare it with parse5, Chromium, Firefox and WebKit (run after merges) | No such check documented | No | No | No |
| **Raw output** | Only a typed value: `SafeHtml` built in Java by `sanitize` or `escape`, or a `Fragment` passed between templates. No template syntax switches escaping off | `$unsafe{...}`, `@raw`, a custom `gg.jte.Content` | `@raw(...)` | `{{{name}}}` or `{{&name}}` (Mustache) | `\|raw`, `\|safe`, `RawString` |
| **Reflection at runtime** | None. Generated code calls getters directly | Not documented as using reflection for template code (compiled Java) | "No reflection used" | Generated renderers use none. Locating a template from a model falls back to reflection unless you register a `JStacheCatalog` | A reflection-based value resolver is the fallback in JVM mode. Type-safe templates and `@TemplateData` generate resolvers |
| **Thymeleaf migration** | `tessera-migrate` converts templates and records/compares the old app's pages. A porting guide covers Thymeleaf, JSP, FreeMarker and Mustache | Manual rewrite | Manual rewrite | Manual rewrite (or from Mustache) | Manual rewrite |
| **Spring** | Starter for Spring MVC (servlet), Spring Framework 4.3+ / Boot 1.5+, with Spring form binding (`t:object`, `t:field`, `t:errors`), messages and Spring Security helpers. No WebFlux | Starters for Boot 2, 3 and 4 (MVC and WebFlux). Listed on start.spring.io | No integration in its docs | Spring and Boot modules for Web MVC and WebFlux | Built for Quarkus. Usable standalone, without build-time validation. No Spring integration |
| **Java floor** | Java 8 (apps built with JDK 8+ javac) | Java 17 for jte 3. jte 2 still gets security fixes for older Java | Java 8 | Java 17 | Java 17 (Quarkus 3) |
| **Build tools** | Maven and Gradle (processor wiring, no Gradle plugin). No hot reload | Maven and Gradle plugins, hot reload | Maven and Gradle, hot reload | Any build that runs annotation processors | Quarkus (Maven or Gradle), or standalone |
| **Maturity** | Prototype. `0.1.0-SNAPSHOT`, not published to any repository. One vendor, no outside users, no IDE support | Apache-2.0, on Maven Central (3.2.4, April 2026). About 1.1k GitHub stars. IntelliJ plugin, Kotlin support | Apache-2.0, on Maven Central (v2.4.0, November 2025). About 780 stars | BSD-3-Clause, on Maven Central (1.3.7, March 2025). About 340 stars | Apache-2.0, part of Quarkus (about 16k stars) with Red Hat backing |

## Where Tessera is weaker

- **Maturity and ecosystem.** Tessera is a prototype with no published artifacts, no releases, no
  community, no IDE plugin and no Stack Overflow answers. Every other engine here ships on Maven
  Central, and JTE and Qute have active communities and IDE support.
- **Features.** No hot reload: a template change needs a recompile, and under Maven
  `tessera-maven-plugin` to notice it. No Gradle plugin, no Kotlin, no WebFlux, no streaming
  or async rendering API. The closed HTML profile rejects markup that browsers accept, and the
  `<script>` rules restrict which JavaScript can come before an inlined value (guide section 4.4).
- **Expressiveness.** Templates read getters and call only registered `@TemplateFunction`s. JTE
  and Rocker let a template call any Java. That is a deliberate trade, but it means more code in
  controllers.
- **Security beyond SSTI.** JTE, Rocker and JStachio are also compiled and have no runtime
  expression language, so server-side template injection is impossible in them too. Tessera's
  security differences are narrower: more output contexts, checking against a spec parser,
  rejection of unclassifiable markup, and no template-level switch for raw output.

## When to pick which

- **Pick Tessera** to move an existing Thymeleaf (or JSP, FreeMarker, Mustache) app on Spring MVC
  off a runtime expression language while keeping Thymeleaf-shaped templates and Spring form
  binding, with a mechanical, checked migration. Also pick it if you are still on Java 8 or
  Boot 1.5-2.x.
- **Pick JTE** for a new Spring Boot 3/4 app, or a Kotlin app, that wants a mature, open,
  compiled engine with contextual escaping, Gradle support, hot reload and an IntelliJ plugin, and
  doesn't need Thymeleaf syntax. Our migration tooling could target JTE as well. It doesn't today.
- **Pick Rocker** for a non-Spring Java 8+ app that wants plain Java in templates (and its "near zero-copy"
  rendering), and can accept content-type-only escaping.
- **Pick JStachio** if you already use Mustache templates, or want logic-less templates checked
  at compile time, on Java 17+.
- **Pick Qute** on Quarkus.

## Performance

No performance comparison with these engines has been measured. The repo's JMH benchmarks
(`tessera-benchmarks`) compare Tessera only with Thymeleaf, on two petclinic-style pages
rendered to a `String`, with Thymeleaf's template cache on. Method and results are in
[tessera-benchmarks/RESULTS.md](../tessera-benchmarks/RESULTS.md).

## Sources

Checked October 2026.

- JTE: [jte.gg](https://jte.gg/), [HTML rendering and escaping](https://jte.gg/html-rendering/),
  [precompiling](https://jte.gg/pre-compiling/), [Spring Boot starter](https://jte.gg/spring-boot-starter-3/),
  [jte 3.0.0 release notes (Java 17)](https://github.com/casid/jte/releases/tag/3.0.0),
  [start.spring.io](https://start.spring.io/), [GitHub](https://github.com/casid/jte).
- Rocker: [README](https://github.com/fizzed/rocker), [syntax and escaping](https://github.com/fizzed/rocker/blob/master/docs/SYNTAX.md).
- JStachio: [documentation](https://jstach.io/doc/jstachio/current/apidocs/), [GitHub](https://github.com/jstachio/jstachio),
  [Mustache manual](https://mustache.github.io/mustache.5.html) (unescaped variables).
- Qute: [reference guide](https://quarkus.io/guides/qute-reference), [Quarkus getting started (JDK 17+)](https://quarkus.io/guides/getting-started).
- Tessera: [README](../README.md), [guide](guide.md) (section 5, known limitations), [security model](security-model.md).
