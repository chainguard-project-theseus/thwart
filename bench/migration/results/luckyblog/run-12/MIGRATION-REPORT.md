# Migration report: Velocity → Tessera

## Summary

All 18 server-rendered views of luckyBlog are now Tessera templates. They are compiled into
`com.msun.luckyBlog.templates.*` by the annotation processor during `javac`. Velocity's
templates, starter dependency and `spring.velocity.*` configuration are gone. When the app
starts, Tessera logs `Found 20 compiled Tessera templates`, and the
`another template engine is still configured` warning does not appear.

The build target stays JDK 8. `pom.xml` hard-codes `${java.home}/lib/rt.jar` as the
bootclasspath, as it did before. `JAVA_HOME=/usr/lib/jvm/java-1.8-openjdk mvn clean package`
succeeds. The project sets `maven.test.skip=true`, as before, and its only test class
(`Test.java`) has just an empty `main`. No test was touched.

## What changed

### Build (`pom.xml`)
- Removed `spring-boot-starter-velocity`. Added `dev.tessera:tessera-spring-boot-starter-javax`
  (the app uses Boot 1.4.1 with `javax.servlet`).
- Added `spring-context-support` 4.3.3 explicitly, without `commons-logging`. The Velocity
  starter used to bring it in, and Boot's `EhCacheCacheConfiguration` needs it for the
  app's `@Cacheable` caches. The other jars Velocity brought (commons-lang, -beanutils,
  -collections, -digester) are not used by the app. They are no longer on the classpath.
- `maven-compiler-plugin` 2.3.2 → 3.8.1 (needed for `annotationProcessorPaths` and `parameters`).
  Source/target 1.7 → 1.8. Added `tessera-compiler` as the processor, with
  `-Atessera.templates` and `-Atessera.package=com.msun.luckyBlog.templates`.
- Added `tessera-maven-plugin` `check`, so editing only a template still triggers a recompile.
- `maven-jar-plugin` only packaged `**/*.class`. It now also packages `META-INF/services/**` and
  `META-INF/tessera/**`; without them the packaged app (`target/lucky-blog-*/lib`) finds no
  templates.
- The `copy-resources` step to `conf/` runs with filtering on. It now excludes `templates/**`:
  the templates are compiled into the jar, and filtering would rewrite `${...}`.

### Configuration
- `application.properties`: removed every `spring.velocity.*` key.
- `README.md`: the tech stack line says Tessera instead of velocity.

### Templates (`src/main/resources/templates`)
| Velocity | Tessera |
|---|---|
| `module/header.vm` + `module/footer.vm` (`#parse`/`#include`) | layout fragment `fragments/front.html :: front(Info info, Fragment content)` |
| `module/admin-header.vm` (+ closing markup repeated in each admin page) | layout fragment `fragments/admin.html :: admin(Fragment content, Fragment scripts)` |
| `VM_global_library.vm` macros `#flip`, `#frontFlip`, `#liClass` | fragments `fragments/paging.html :: flip(...)`, `frontFlip(...)`; `#liClass` became a `t:class` on the `<tr>` that picks by `st.count % 4` |
| every page `.vm` | a page `.html` of the same view name with typed `t:params` |

Velocity behaviour that the pages depended on, reproduced on purpose:
- On `/projects` (no page number) Velocity could not evaluate `$current - 1` and printed the
  references literally (`href="/projects/$prev"`). `frontFlip` writes exactly that when
  `current` is null.
- In `login.vm` and `result.vm`, `#set($x = $null)` kept the previous value. `login.html` shows
  the error growl only when `success < 1`. `result.html` treats a null `result` as success and a
  null `targetUrl` as `/`.
- `$!{x}` in a `value=` wrote `value=""`. The `info` form uses `?: ''` to keep that.

### Java
- `@TemplateView` on `Info`, `BlogView`, `Archive` and `Project`, so templates can read them.
- `Project.getDateText()` (`@JsonIgnore`, so `/admin/pro.json` is unchanged) returns the date
  as `Timestamp.toString()`, which is what Velocity printed. `Timestamp` is not view-safe.
- New `support/view/BlogFunctions` (`blog.html(...)`). It sanitizes the stored article and
  resume HTML (editor.md/prettify output) with an `HtmlPolicy` whitelist. Velocity printed
  that HTML raw.
- `CtrlExceptionHandler` now returns `forward:/error` instead of `/error`. Under Velocity,
  `/error` was not a Velocity view name, so `InternalResourceViewResolver` forwarded it to
  Boot's `/error`, which answers 200 with the error page. Tessera strips the leading `/` and
  would render `error.html` directly with the handler's 500 status. The explicit forward keeps
  the recorded behaviour: status 200 and the same page.

### Static JS
`front-end.min.js` (front pages) and `jcala-form.min.js` (login and admin pages) each end with
one delegated click/mouseover listener. It replaces inline handlers that Tessera rejects (see
below). The elements are marked with `data-action="day-night"`, `"home"` or `"none"`.

## Security
- Nothing is printed unescaped any more. Stored HTML goes through the `blog.html` sanitizer;
  everything else is escaped for its context.
- No `permitAll`, CSRF or escaping switch was added or changed. The app has no Spring Security.
  Tessera's starter adds a nonce-based `Content-Security-Policy` header; nothing turns it off.
  The interceptor-based admin login check is unchanged.

## Page parity

`pages.txt` (in the repo root) has 59 requests. They cover every view: front pages with
empty, out-of-range and missing page numbers; unknown tags; missing and malformed post ids,
which go through the error handler; the 404 page; admin while logged out; a failed and a
successful login; every admin page; every admin form, both its success and its failure
result page; and the pages again after the data changed.

I recorded the original app (Velocity build) and compared the migrated one with
`tessera-migrate`. Both ran from the same data with the same startup arguments. The
datasource was H2 in MySQL mode instead of MySQL, since no MySQL server is available here.
Result: 25 pages `same`, 34 `differs`, with every status and final URL matching. I checked
each `differs` page line by line. They contain only the differences below, and all of them
are forced by Tessera's rules:

1. **Inline handlers and `javascript:` URLs (every front page, login, admin project list).**
   `<a href="javascript:void(0)" onclick="day_night()">`, the login avatar's
   `onclick`/`onmouseover` and the project list's `href='javascript:void(0)'` are build errors
   in Tessera (a strict CSP blocks them). They became `href="#"` plus `data-action`, handled
   by the listener added to the existing JS files. The page DOM differs by those attributes.
2. **`style="background: url(...)"` with a value from data (login, about, admin info).**
   `t:style` accepts only `CssValue`s. `css.url(avatar)` writes `url("/img/avatar.jpg")`
   (quoted) where Velocity wrote `url(/img/avatar.jpg)`. The CSS means the same.
3. **Stored article/resume HTML (post, about).** It now goes through `SafeHtml.sanitize`. ids
   get the fixed `user-content-` prefix. `name` on `<a>` is not in `HtmlPolicy.ATTRIBUTES`,
   so it is dropped. `<ol>/<li>` inside `<pre>` (prettify's line numbering) is flattened. The
   text content is identical (checked by stripping tags from both pages).
4. **Admin monitor `<script>`.** `[[...]]` must be a declaration at the top of the script, so
   the script now starts with `const freeMemory = ..; const used = ..;` and the
   `Morris.Donut` call uses those names. The free-memory value comes from the OS and changes
   between any two runs anyway, even of the original app.

## What could not be converted 1:1, and why
- The four difference classes above cannot be made `same` without bringing back what
  Tessera forbids (inline handlers, raw HTML, unquoted computed CSS). I kept Tessera's
  behaviour and wrote down each difference.
- `pages.txt` does not include an avatar upload (`multipart/form-data`): `tessera-migrate`
  only sends URL-encoded forms. A non-multipart POST to `/admin/file/uplAva.action` is
  included, and it renders the same info page.

## Where Tessera or its documentation fell short
- **Boot 1.4.** The guide supports only Boot 1.5+ and warns that older versions are untested.
  The app is on Boot 1.4.1 / Spring 4.3.3. It worked: the auto-configuration, CSP filter and
  view resolver all registered.
- **Compare cannot tell forced changes from regressions.** The guide prescribes the
  `onclick`/`javascript:` recipe, but `compare` still reports the resulting attribute changes
  as `differs`. Nothing marks them as expected, so a migration that follows the recipe can
  never reach exit 0 on pages that had inline handlers. Only `--ignore` is offered, and it
  would have to mask real markup.
- **`css.url` quotes its output**, so every computed `url(...)` differs from the original
  textually. The guide does not mention this.
- **The sanitizer's structural rules are undocumented.** The guide does not say that
  `<ol>/<li>` inside `<pre>` are flattened, or that `rel="nofollow noopener"` is added to
  links. I found both by experiment with `jshell`. The guide's allowed-attribute list is
  incomplete too: it does not say that `name` (e.g. `<a name>` anchors from Markdown
  editors) can never be allowed. `allowAttributes("name")` throws
  `IllegalArgumentException` when the class initializes, which surfaced only at runtime as
  an `ExceptionInInitializerError` (a 500 on the first post page), not at build time.
- **Class prefix syntax.** The guide says `allowClasses` takes "class names or prefixes" but
  not the syntax. It is `"lang-*"`; a bare `"lang-"` silently matches nothing.
- **Leading-slash view names.** The guide says one leading `/` is dropped. It does not warn
  that this changes behaviour for apps whose old resolver ignored `/error` and let Spring
  forward to Boot's error controller: the status code changes (200 → 500).
- **The `jar` plugin include pattern.** The guide says `META-INF/services/...` and
  `META-INF/tessera/**` must be packaged. A jar plugin restricted to `**/*.class`, as here,
  silently drops them. Only the startup warning would show it.
- `tessera-migrate`'s automatic rewrite only handles Thymeleaf. The Velocity conversion was
  done by hand.
- `mvn dependency:copy -DoutputDirectory=...`, the command the skill gives for fetching the
  tool, is overridden by this project's own `maven-dependency-plugin` `<outputDirectory>`, so
  the jar ended up elsewhere. I copied it from `~/.m2` instead.

## How to reproduce the check locally
The local helpers live in `.tessera/`, which is git-ignored: an H2 schema and the converted
seed data, `run.sh <appdir> <port>`, and `cycle.sh`, which rebuilds, restarts and compares.
The baseline was recorded from the original build with
`java -jar .tessera/tessera-migrate.jar record http://localhost:8080 pages.txt .tessera/baseline`.
