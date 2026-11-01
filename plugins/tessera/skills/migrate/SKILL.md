---
name: migrate
description: Migrate a Java web application's server-rendered views (Thymeleaf, JSP, FreeMarker, Velocity, Mustache, Pebble or similar) to Tessera, the compiled, safe-by-design template engine, and check every page against the old app with tessera-migrate record/compare. Use when asked to port, convert or move an app's templates or views to Tessera, or to replace its template engine with Tessera.
---

# Migrate server-rendered Java views to Tessera

## What Tessera is

Tessera templates are HTML with `t:` attributes, close to Thymeleaf's `th:` syntax. An annotation
processor compiles them into Java classes during the application's own `javac`, so a template
mistake is a build error with `file:line:col`, and nothing parses templates at runtime. The model
is stricter than the engine you are leaving, and that is where the work is:

- A template sees only its typed `t:params`, filled from the model (or a `@PathVariable` of the same
  name). There is no request, session, bean or static scope to fall back to, so a value the old
  page found elsewhere is `null` now: the controller has to provide it.
- Templates read getters and record components of view-safe types and call registered
  `@TemplateFunction`s. Logic, filtering and formatting move into Java.
- Escaping comes from the output context and can't be turned off. Raw markup is a `SafeHtml`
  built in Java, and stored HTML goes through `html.sanitize(...)`.
- Includes, macros, tag files and layouts become fragments: typed functions that see only their
  parameters, with markup passed as `Fragment` values. View names, fragment references, message
  keys and link paths are literals.

[references/guide.md](references/guide.md) is the reference: the model in full (section 1), build
setup (section 2), the tools (section 3), syntax and output contexts (section 4), notes for each
engine (4.10) and known limitations (section 5). Read sections 1 and 2 and your engine's notes
before you start; look up the rest when you need it.

## Your tools

- **The compiler.** Each error names the file, line and column, says what is wrong and how to fix
  it. Those messages are the documentation for each rule; `mvn compile` is the fastest check you
  have. The generated Java is in `target/generated-sources/annotations` if you want to see what a
  template became.
- **`tessera-migrate`**, a jar that runs on JDK 8+. Fetch it into `.tessera/` (outside `target/`;
  don't commit it):

  ```sh
  mvn -q dependency:copy -Dartifact=dev.tessera:tessera-migrate:0.1.0-SNAPSHOT -Dmdep.stripVersion=true -DoutputDirectory=.tessera
  java -jar .tessera/tessera-migrate.jar --help
  ```

  Tessera isn't published to a remote repository: it resolves from the local Maven repository,
  where it was installed with `mvn install`. If the copy fails, the jar may still be there to copy
  by hand (`~/.m2/repository/dev/tessera/tessera-migrate/<version>/`). If `dev.tessera` is
  missing from the local repository, say that Tessera has to be installed first.
  - `record` and `compare` replay a `pages.txt` against the old app and then the new one and
    compare the pages as a browser builds them. This is how you know a page is right.
  - For Thymeleaf, `tessera-migrate <old-templates-dir> <new-templates-dir> --report report.md`
    does the mechanical rewrite and reports, by `file:line`, what it couldn't convert and every
    value the templates read from outside the model.
- **`tessera-maven-plugin`** (`check` goal) makes Maven recompile after a template-only or
  message-only edit (guide section 2).
- **The application's own tooling.** Its build, tests, scripts, README and seed data are yours to
  find and use.

## How to work

The old application is the specification. Record it before you change anything, with a
`pages.txt` that reaches every page in every state that renders differently. Set up the build, then
convert shared layouts and fragments first, then pages, compiling and comparing as you go. Run
`compare` early and often: an empty value or a 500 almost always means a value the old template
took from outside the model. Remove the old engine last: its templates, dependency and
configuration. Until it is gone, it still renders every view Tessera doesn't know, with its own
injection risks, so removing it is what completes the security guarantee.

You can't feel how much time has passed. If you were given a time budget, note `date` when you
start and run it again before deciding time is short.

## What "done" is checked against

- The application's normal Maven build passes on the JDK it targets, with all existing tests. Don't
  delete, disable or weaken tests; update a test only where it asserts on the old engine's details.
- Page parity: `compare` exits 0 on your `pages.txt`, so every page is `same` or `reordered` with
  its recorded status and final URL, or you can say why a remaining difference is right. A
  `pages.txt` that skips pages or states proves nothing.
- Security isn't weakened: no `SafeHtml.unchecked` or `TrustedUrl.unchecked`, no new `permitAll`,
  no CSRF or escaping turned off. Output the old engine printed raw and Tessera now escapes is the
  intended difference.
- The old engine is gone, and the startup warning `Tessera: another template engine is still
  configured` no longer appears.
