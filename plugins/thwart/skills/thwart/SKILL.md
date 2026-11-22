---
name: thwart
description: Thwart, the compiled, safe-by-design template engine for Spring MVC and successor to Thymeleaf (HTML templates with t attributes, compiled to Java by an annotation processor at build time). Use when adding Thwart to a Java web application (the Maven or Gradle build, the Spring starter); when writing, editing or fixing Thwart templates or their build errors in an application that already uses Thwart (templates declaring xmlns:t="https://thwart.chainguard.dev" and t:params, the thwart-compiler processor or a thwart-spring-boot-starter dependency); or when migrating an application's server-rendered views from Thymeleaf, JSP, FreeMarker, Velocity, Mustache, Pebble or similar to Thwart (porting, converting or moving its templates, or replacing its template engine), checking every page against the old app with thwart-migrate record/compare.
---

# Thwart

## What Thwart is

Thwart templates are HTML with `t:` attributes, close to Thymeleaf's `th:` syntax. An annotation
processor compiles them into Java classes during the application's own `javac`, so a template
mistake is a build error with `file:line:col`, and nothing parses templates at runtime. The model
is stricter than other template engines, and that is where the work is:

- A template sees only its typed `t:params`, filled from the model (or a `@PathVariable` of the same
  name). There is no request, session, bean or static scope to fall back to, so a value the page
  finds anywhere else is `null`: the controller has to provide it.
- Templates read getters and record components of view-safe types and call registered
  `@TemplateFunction`s. Logic, filtering and formatting move into Java.
- Escaping comes from the output context and can't be turned off. Raw markup is a `SafeHtml`
  built in Java, and stored HTML goes through `html.sanitize(...)`.
- Includes, macros, tag files and layouts become fragments: typed functions that see only their
  parameters, with markup passed as `Fragment` values. View names, fragment references, message
  keys and link paths are literals.

## The compiler is your feedback loop

- **The compiler.** Each error names the file, line and column, says what is wrong and how to fix
  it. Those messages are the documentation for each rule; `mvn compile` (Gradle:
  `gradle compileJava`) is the fastest check you have. An error ending in `[verifier:RULE]` has a
  row with the fix in [limitations.md](references/limitations.md). Some constructs are rejected
  rather than supported, each with a recipe in the guide: values inside `<svg>`, `<math>`,
  `<noscript>` or `<iframe>`, CDATA, `<xmp>`/`<frameset>`, a computed input `type`, `srcset` or
  `<font color>`, unbalanced `t:if`/`t:each` bodies
  ([Output contexts](references/templates.md#output-contexts),
  [Fragments and layouts](references/templates.md#fragments-and-layouts)), and block markup in HTML
  messages ([Messages and links](references/templates.md#messages-and-links)). Client frameworks
  that compile the server's DOM as a template are unsupported
  ([limitations.md](references/limitations.md)). The generated Java is in
  `target/generated-sources/annotations` (Gradle:
  `build/generated/sources/annotationProcessor/java/main`) if you want to see what a template
  became.
- **`thwart-maven-plugin`**: `check` makes Maven recompile after a template-only or message-only
  edit. Gradle has no plugin: declared `inputs` on `compileJava` do the same
  ([setup.md](references/setup.md#gradle)). Without either, an edited template keeps its old
  generated class and the build stays green.

Fix each error where it points and compile again. Don't work around a rejection with raw output,
string-built names or a second template engine: the guide has the recipe that keeps the page safe.

## Which task are you on

- **Adding Thwart to an application.** Follow [setup.md](references/setup.md): one starter for the
  application's servlet API, `thwart-compiler` as an annotation processor in its own javac, the
  processor options, and `thwart-maven-plugin` (Maven) or declared inputs (Gradle). Keep the
  application's Java, Spring and Boot versions. Then read
  [The model](references/templates.md#the-model) and write templates as below.
  [Hot reload](references/migrate.md#hot-reload) gives a save-to-browser loop under Spring Boot.
- **Writing or editing templates** in an application that already uses Thwart. Read
  [The model](references/templates.md#the-model) once, then look up what the template needs in
  [templates.md](references/templates.md): templates and parameters, view types, expressions,
  output contexts, messages and links, functions, forms, fragments and layouts, rendering. Give
  the controller the values the template reads, as typed `t:params`.
- **Migrating views** from Thymeleaf, JSP, FreeMarker, Velocity, Mustache, Pebble or a similar
  engine. Start with [migrate.md](references/migrate.md): the `thwart-migrate` tool (it records
  the old app's pages and compares the new app against them, and rewrites Thymeleaf mechanically),
  how to work, and what "done" is checked against. Before you start, read
  [The model](references/templates.md#the-model), [setup.md](references/setup.md) and your engine's
  part of [Notes for specific engines](references/migrate.md#notes-for-specific-engines); look up
  the rest when you need it.

Whatever the task, [limitations.md](references/limitations.md) lists what Thwart doesn't do and the
fix for each markup rejection.

## References

| File | What it covers |
|---|---|
| [setup.md](references/setup.md) | Build setup: picking the starter, Maven, Gradle, Spring (Boot, MVC without Boot, security, error pages) |
| [templates.md](references/templates.md) | The model; templates and parameters, view types, expressions and attributes, output contexts, messages and links, functions, forms, fragments and layouts, rendering and the CSP |
| [migrate.md](references/migrate.md) | Migrating views: your tools, how to work, what "done" is checked against, the record/convert/compare loop, hot reload, notes for specific engines |
| [limitations.md](references/limitations.md) | Known limitations, and the rule table for `[verifier:RULE]` errors |
