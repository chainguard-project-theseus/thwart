Migrate this application's server-rendered views to Tessera, a compiled template engine for Java.

- The application is checked out in /work/app (your working directory).
- /work/guide.md is the Tessera porting guide. Read it first; it is the only Tessera documentation.
- Tessera 0.1.0-SNAPSHOT is installed in the local Maven repository
  (~/.m2/repository/dev/tessera): tessera-runtime, tessera-compiler,
  tessera-spring-boot-starter and tessera-migrate (an executable jar). It is not published
  anywhere else.
- JDK 21 and Maven 3.9 are installed. Maven Central is reachable. There is no Tessera source
  code, issue tracker or other documentation, and no human to answer questions: decide for
  yourself and keep going.

Done means:

1. Every page the application renders is rendered by Tessera. The old view technology (its
   templates, dependency and configuration) is gone.
2. The application's normal Maven build passes, and so do all of its existing tests. Do not
   delete, disable or weaken tests; update a test only where it asserts on details of the old
   view technology.
3. For the same data, every page renders the same HTML as before (whitespace and attribute order
   aside), including error pages and forms with validation errors.
4. Security is not weakened: no `SafeHtml.unchecked` or `TrustedUrl.unchecked`, no new
   `permitAll`, no CSRF or escaping turned off.

You have {minutes} minutes. Work in /work/app only. Before you finish, write
/work/app/MIGRATION-REPORT.md: what you changed, anything you could not convert and why, and
every place where the guide or Tessera fell short.
