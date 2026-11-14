Migrate this application's server-rendered views to Thwart, a compiled template engine for Java.

- The application is checked out in /work/app (your working directory).
- Thwart 0.1.0-SNAPSHOT is installed in the local Maven repository
  (~/.m2/repository/dev/chainguard/thwart): thwart-runtime, thwart-compiler,
  thwart-spring-boot-starter, thwart-spring-boot-starter-javax and thwart-migrate (an
  executable jar). It is not published anywhere else.
- JDK 21 (the default), JDK 8 (/usr/lib/jvm/java-1.8-openjdk) and Maven 3.9 are installed. Maven
  Central is reachable. There is no Thwart source code or issue tracker, and no human to answer
  questions: decide for yourself and keep going.

Done means, and your result is checked against:

1. Every page the application renders is rendered by Thwart. The old view technology (its
   templates, dependency and configuration) is gone.
2. The application's normal Maven build passes on the JDK it targets, and so do all of its
   existing tests. Do not delete, disable or weaken tests; update a test only where it asserts on
   details of the old view technology.
3. Page parity. The check has its own list of requests that covers every page and form state
   (including error pages and forms with validation errors). It starts the original app and
   your app the same way from the same data, records the original with `thwart-migrate record`
   and runs `thwart-migrate compare` against yours: every page must come out `same` or
   `reordered`, with the recorded status and final URL. The list is not given to you.
4. Security is not weakened: no escaping bypass, no new
   `permitAll`, no CSRF or escaping turned off, no tests disabled.

You have {minutes} minutes. Work in /work/app only. Before you finish, write
/work/app/MIGRATION-REPORT.md: what you changed, anything you could not convert and why, and
every place where Thwart or its documentation fell short.
