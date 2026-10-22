Migrate this application's server-rendered views to Tessera, a compiled template engine for Java.

- The application is checked out in /work/app (your working directory).
- /work/guide.md is the Tessera porting guide. Read it first; it is the only Tessera documentation.
- Tessera 0.1.0-SNAPSHOT is installed in the local Maven repository
  (~/.m2/repository/dev/tessera): tessera-runtime, tessera-compiler,
  tessera-spring-boot-starter, tessera-spring-boot-starter-javax and tessera-migrate (an
  executable jar). It is not published anywhere else. `tessera-migrate record` and `compare`
  check page parity as the guide describes: record the original app's pages before you change
  anything, then compare your app against the recording.
- JDK 21 (the default, and what Tessera's build needs) and JDK 8 (/usr/lib/jvm/java-1.8-openjdk)
  and Maven 3.9 are installed. Maven Central is reachable. There is no Tessera source code, issue
  tracker or other documentation, and no human to answer questions: decide for yourself and keep
  going.

Done means, and your result is checked against:

1. Every page the application renders is rendered by Tessera. The old view technology (its
   templates, dependency and configuration) is gone.
2. The application's normal Maven build passes, and so do all of its existing tests. Do not
   delete, disable or weaken tests; update a test only where it asserts on details of the old
   view technology.
3. Page parity. The check has its own list of requests that covers every page and form state
   (including error pages and forms with validation errors). It starts the original app and
   your app the same way from the same data, records the original with `tessera-migrate record`
   and runs `tessera-migrate compare` against yours: every page must come out `same` or
   `reordered`, with the recorded status and final URL. The list is not given to you; write your
   own `pages.txt` that reaches everything.
4. Security is not weakened: no `SafeHtml.unchecked` or `TrustedUrl.unchecked`, no new
   `permitAll`, no CSRF or escaping turned off, no tests disabled.

You have {minutes} minutes. Work in /work/app only. Before you finish, write
/work/app/MIGRATION-REPORT.md: what you changed, anything you could not convert and why, and
every place where the guide or Tessera fell short.
