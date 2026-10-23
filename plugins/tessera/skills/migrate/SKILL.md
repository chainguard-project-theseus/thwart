---
name: migrate
description: Migrate a Java web application's server-rendered views (Thymeleaf, JSP, FreeMarker, Velocity, Mustache, or similar) to Tessera, the compiled, safe-by-design template engine, and check every page against the old app with tessera-migrate record/compare. Use when asked to port, convert or move an app's templates or views to Tessera, or to replace its template engine with Tessera.
---

# Migrate server-rendered Java views to Tessera

The full porting guide is [references/guide.md](references/guide.md). Read its sections 1 (the
target), 2 (build setup) and 3 (the loop) before you change anything. Use sections 4 to 7
(syntax, a worked example, every compiler diagnostic with its fix, known limitations) when you
need them. It doesn't map your engine's syntax construct by construct. You know the old engine,
and once you know how Tessera works the syntax converts on its own.

## The target, in brief

Templates are HTML with `t:` attributes, close to Thymeleaf. The application's `javac` compiles
them into Java classes, so a template mistake is a build error with `file:line:col`. A template
sees only its typed `t:params`, which are read from the model. It can't see the request, the
session, beans or statics, and a missing model attribute is `null`. Templates read getters and
record components of view-safe types and call only registered `@TemplateFunction`s. The escaper
comes from the output context, and raw output needs a `SafeHtml` built in Java. Most of the work
is in the places where this model differs from the old engine: where the data comes from, layouts,
and forms.

## The tool

`tessera-migrate` records the old app's pages and compares the new app's pages against them. For
Thymeleaf it also does the mechanical rewrite of a whole template directory. It runs on JDK 8+,
and the build needs a JDK and Maven, so you already have both. Get the jar into `.tessera/` in the application's
directory (outside `target/`, so `mvn clean` leaves it; don't commit it):

```sh
mvn -q dependency:copy -Dartifact=dev.tessera:tessera-migrate:0.1.0-SNAPSHOT -Dmdep.stripVersion=true -DoutputDirectory=.tessera
java -jar .tessera/tessera-migrate.jar --help
```

Run the command as written (don't add `-o`: Maven may need to fetch the dependency plugin
itself). The Tessera artifacts aren't published to a remote repository yet, so it resolves only
where Tessera has been built with `mvn install` (from `github.com/chainguard-dev/harder`). If it
fails, read the error. If the missing artifact is `dev.tessera:tessera-migrate`, stop and say that
Tessera needs to be installed into the local Maven repository first. Any other failure (plugin
resolution, network) is a Maven problem, not a missing Tessera. Either way, if the jar is in the
local repository (`~/.m2/repository/dev/tessera/tessera-migrate/<version>/tessera-migrate-<version>.jar`,
with the version above), copying it to `.tessera/tessera-migrate.jar` works too. The application's
build resolves `tessera-compiler` and the starter from the same local repository.

## The process

The old application is the specification. Work the way an expert would, keeping the loop short:

1. **List every page** in a `pages.txt`, in every state that renders differently: forms empty,
   with errors and after success; each role, logged out and logged in; empty and full lists; error
   pages. Start the app from the same data on every run.
2. **Record** the old app before changing anything:
   `java -jar .tessera/tessera-migrate.jar record <base-url> pages.txt baseline/`
3. **Set up the build** (guide section 2): the app builds with JDK 8+ and runs on Java 8+ with
   Spring 5.0+ or Boot 2.0+, and the starter is `tessera-spring-boot-starter` for
   `jakarta.servlet` or `tessera-spring-boot-starter-javax` for `javax.servlet`. Tessera can sit
   next to the old engine while you migrate.
4. **Convert one template at a time**, shared fragments and layouts first. For each one, find
   every value it reads and make the controller provide it. Declare the real parameter types,
   rewrite the markup, then run `mvn clean compile` and fix each error using the diagnostic
   catalogue (guide section 6). For Thymeleaf, run
   `java -jar .tessera/tessera-migrate.jar <old-templates-dir> <new-templates-dir> --report migrate-report.md`
   first, then finish what it reports.
5. **Compare** the new app, started from the same data, after each template or small group:
   `java -jar .tessera/tessera-migrate.jar compare <base-url> pages.txt baseline/`
   Judge each difference: fix it, or decide that it is right and write down why. A `reordered`
   page passes but prints its diff, so check that what moved is harmless. Text that changes on
   every run (times, random ids) can be masked with `--ignore REGEX`; keep it narrow, since what it
   matches is never compared. Few differences are right. An empty value or a 500 nearly always
   means a value the old template took from outside the model.
6. **Remove the old engine**: its templates, its dependency and its configuration. Then build,
   test and compare once more.

## What "done" is checked against

The migration is checked against these facts. Work towards them, and report against each one:

- `mvn clean verify` passes, including all the existing tests.
- `compare` exits 0 on your `pages.txt`, so every page is `same` or `reordered` with its recorded
  status and final URL. Otherwise, explain why each remaining difference is right.
- Security isn't weakened. Don't use `SafeHtml.unchecked` or `TrustedUrl.unchecked` to get around
  escaping, and don't turn off CSRF or escaping. Output that the old engine printed raw and that
  Tessera now escapes is intended.
- The old engine is gone: no templates, dependency or configuration remain.

A `pages.txt` that skips pages or states makes `compare` pass without proving anything, so list
every page.
