# Migration report: Velocity → Tessera

## Result

- Every view is now a Tessera template: 17 pages plus 3 fragment files under
  `src/main/resources/templates`. They compile to `com.msun.luckyBlog.templates.*`. At startup the
  app logs `Found 20 compiled Tessera templates` and no "another template engine" warning.
- Velocity is gone: the `.vm` templates, `VM_global_library.vm`, the `spring-boot-starter-velocity`
  dependency and all `spring.velocity.*` properties have been removed.
- Build: `mvn clean install` passes on JDK 8, the JDK the project targets (the compiler config puts
  `${java.home}/lib/rt.jar` on the bootclasspath, and that file only exists on JDK ≤ 8). The project
  has no real tests: `Test.java` only has a `main`, and the pom sets `maven.test.skip`. I left both
  unchanged.
- Parity: I ran `tessera-migrate compare` on 61 requests covering every page and state I could
  find (see "How it was checked"). 52 are `same`. All 9 that differ are explained below; each one is
  either intended or forced by Tessera's rules.

## What changed

### Build (`pom.xml`)
- Source/target moved from 1.7 to 1.8, because Tessera needs Java 8.
- `maven-compiler-plugin` moved from 2.3.2 to 3.8.1, because `annotationProcessorPaths` needs 3.5+.
  I added `tessera-compiler` as the processor (the build had no other processors) and set
  `-Atessera.templates` and `-Atessera.package`.
- Dependencies: `spring-boot-starter-velocity` is replaced by `tessera-spring-boot-starter-javax`.
  `spring-context-support` 4.3.3 is now declared directly: the Velocity starter used to bring it
  in, and the app's `@EnableCaching`/ehcache setup needs `EhCacheCacheManager`.
- Added `tessera-maven-plugin:check`. I confirmed that a template-only edit now triggers a
  recompile.
- `maven-jar-plugin` only packaged `**/*.class`. It now also packages `META-INF/services/**` and
  `META-INF/tessera/**`; without them the jar contains no templates.
- The `copy-resources` execution (filtering on) now excludes `templates/**`, so nothing rewrites
  `${...}` in the templates.
- I kept Spring Boot at 1.4.1. The guide says Boot 1.5+, but the starter's auto-configuration
  works on 1.4.1 too.

### Templates
- `module/header.vm` and `module/footer.vm` became one layout fragment,
  `module/front.html :: layout(Info info, Fragment content)`.
  - The old header had an inline `<script>` between `</head>` and `<body>`. Browsers move that
    script into `<head>`, so I wrote it there. The DOM is identical.
- `module/admin-header.vm` became `module/admin.html :: layout(Fragment content, Fragment
  scripts)`.
  - The `<div id="wrapper">` that the header opened and each page closed is now opened and closed
    in the layout.
  - `info.vm` had one stray extra `</div>`; browsers ignore it, so I dropped it.
- The `VM_global_library.vm` macros:
  - `flip` and `frontFlip` are now fragments in `module/pager.html`, driven by
    `com.msun.luckyBlog.view.Pager`, which the controllers build.
  - `liClass` is now a `t:class` on the `<tr>`, picked from `stat.count % 4`.
  - Velocity quirk kept on purpose: `/projects` (no page number) has neither `pageNum` nor
    `current`, so the old macro printed both links with the literal text `/projects/$prev` and
    `/projects/$next`. `Pager.front` reproduces this.
- Velocity printed everything raw. Plain values are now escaped text or attributes.
- The stored editor.md HTML (post body and résumé) goes through a custom `HtmlPolicy`
  (`view/ArticleFunctions`, function `editormd.html`). It allows the elements editor.md produces
  and the prettify and editor.md classes.
- `login.vm`: the `#set($ii …)` logic is now `t:if="success != null and success < 1"` on the
  growl `<script>`, with an empty `<script>` otherwise, as before.
- `admin/result.vm`: the `<meta http-equiv="refresh">` target is now a choice between literal
  links, since Tessera's refresh context only takes `@{...}` links.
  - `AdminController.update` also puts a `targetVid` into the model for the
    `/admin/update{vid}` link.
  - The old fallback (`/` when `targetUrl` is null) is kept.
- `admin/monitor.vm`: the memory numbers are now declarations at the top of the script
  (`var freeMemory = [[${freeMemory}]];`), as the script context requires.

### Java
- `@TemplateView` is on `Info`, `BlogView` and `Archive`.
- New `view/ProjectView`: `Project` has a `java.sql.Timestamp` (not view-safe) and is also
  serialised by `/admin/pro.json`, so I left it alone. `ProjectView` prints the date exactly as
  before (`Timestamp.toString()`).
- New `view/Pager`, as described above.
- `CtrlExceptionHandler` now returns `forward:/error` instead of `/error`.
  - In the old app nothing resolved the view name `/error` except `InternalResourceViewResolver`,
    which forwarded to Boot's `BasicErrorController`. That rendered `error` with status 200.
  - Tessera would resolve `/error` directly (it drops the leading slash) and return 500.
  - `forward:/error` keeps the old flow, status included.
- `FontEndController` and `AdminController` add the `pager` model attribute and wrap project lists
  in `ProjectView`.

### Security
- No `TrustedUrl.unchecked`, no `permitAll`. The app has no Spring Security, so there is no CSRF
  setting to change.
- Escaping is never turned off. The only raw HTML is the stored article and résumé HTML, and that
  goes through the sanitizer.

## Differences that remain in `compare`, and why

1. **`/post/23` (the stored editor.md article): 2 requests.**
   - Tessera's sanitizer always prefixes ids with `user-content-`, which can't be configured.
   - Its attribute allow-list has no `name`, so `<a class="reference-link" name="…">` loses
     `name`.
   - The prettify line-number lists (`<pre><ol class="linenums"><li>…`) are flattened into
     newline-separated `<code>` lines, to work around the sanitizer bug below.
   - Everything else in the article is identical: elements, classes, text and structure.
2. **`/admin/` (monitor): 1 request.** The Morris data values became script-top declarations,
   which Tessera requires. The value itself is free memory and changes on every request anyway.
3. **Project named `Proj <b>2</b>` (my own injection test): 6 requests.** It is now shown as text
   instead of bold markup. That is the intended escaping fix.

## Tessera / documentation shortcomings found

1. **Sanitizer bug.** `SafeHtml.sanitize` breaks the structure when block elements sit inside
   `<pre>`. It drops the inner `<ol>`/`<li>` start tags but keeps their end tags, which then close
   the *enclosing* list early. Content that followed the code block falls out of the list.
   - Reproduce: `<ol><li><pre><ol><li><code>x</code></li></ol></pre><h6>after</h6></li><li>next</li></ol>`
     → `<ol><li><pre><code>x</code></pre></li></ol><h6>after</h6>next`.
   - editor.md / prettify output (`<pre class="linenums"><ol class="linenums">`) triggers this.
   - Workaround: `ArticleFunctions.flattenCodeLines` rewrites those lists before sanitizing.
2. **`HtmlPolicy` can't keep `name`, can't keep ids unprefixed, and has no table elements.** The
   closed `ATTRIBUTES` set is `id, title, alt, width, height, start, reversed, value`. So stored
   HTML that relies on `<a name>` anchors or exact ids can't reach page parity. The guide mentions
   the id prefix but not the missing `name`.
3. **`css.url` always writes a quoted `url("…")`.** Old pages wrote `url(/img/avatar.jpg)`; the
   result is the same CSS but a different `style` string, so `compare` reports it.
   - Workaround (about, login, admin info): the stored default avatar path `/img/avatar.jpg` is
     written as a literal style value, and any other value goes through `css.url`.
4. **Script values must be declarations at the top of the script.** Inline values (Morris data on
   the monitor page) can't keep their markup, so such pages can never be `same` in `compare`.
5. **Supported versions.** The guide says Spring Boot 1.5+. This app is on Boot 1.4.1 and it
   worked, but the docs don't say whether 1.4 is supported.
6. **Function namespace vs. parameter name.** A `@TemplateFunctions("article")` namespace is
   shadowed by a `t:params` variable named `article`. The error says `method call 'html(...)' is
   not allowed` instead of pointing at the name clash. I renamed the namespace to `editormd`.
7. **The view-name rule for a leading `/` silently changed behaviour.** `"/error"` used to fall
   through to `InternalResourceViewResolver` (a forward). Tessera drops the slash and renders
   directly, which changed the status code (500 instead of 200). The guide mentions the slash rule
   but not this consequence.
8. **`tessera-migrate` only rewrites Thymeleaf.** There is no Velocity conversion, so the whole
   migration was done by hand.

## How it was checked

- **Database.** There's no MySQL here, so I ran both apps on in-memory H2 2.2 (MySQL mode). The
  schema and the converted seed data (`data.sql` with MySQL escapes rewritten) are in
  `.tessera/h2-init.sql`.
  - The H2 jar is added only on the command line.
  - Overrides passed only at launch: datasource, port, `pic.home`, access log.
  - Nothing in the app itself was changed for this.
- **The requests** are in `.tessera/pages.txt` (61):
  - Every public page.
  - Login: failure, success, and the redirect when not logged in.
  - Every admin page.
  - Blog create, update and delete, including the failed create and failed update result pages.
  - Project create, update and delete.
  - Résumé and info updates.
  - 404 and exception error pages, and `/admin/update999`.
  - Empty and filled lists, and pagination edge cases.
- **Running it.**
  - `.tessera/scripts/run.sh` starts an app.
  - `.tessera/scripts/cycle.sh` rebuilds, restarts from fresh data and runs `compare`.
  - The baseline was recorded from the original build (`.tessera/orig`).
- **Not covered:** avatar upload (`/admin/file/uplAva.action`), because it needs a multipart POST,
  which `pages.txt` can't express. It renders the same `admin/info` template with the same model,
  and that template was compared in several states.
- `.tessera/` is git-ignored.
