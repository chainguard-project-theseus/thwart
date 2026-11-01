# Migration report: Velocity → Tessera

## Result

| Check | Status |
|---|---|
| Every page rendered by Tessera; Velocity templates, dependency and configuration removed | Done |
| `mvn clean verify` on the JDK the app targets (JDK 8) | Passes (the build has `maven.test.skip=true`, and `src/test` has no test cases; nothing was disabled) |
| No `SafeHtml.unchecked` / `TrustedUrl.unchecked`, no CSRF/escaping/permitAll change | `target/tessera-review.txt`: 0 unchecked calls; one `@TemplateFunction` (`blog.html`, a sanitizer) |
| Page parity (`tessera-migrate compare`) | 68 requests in `pages.txt`. On the shipped seed data (`data.sql`), all are `same` except the 3 explained below, plus one timestamp the app writes from `now()` |

## What changed

**Build (`pom.xml`)**
- Removed `spring-boot-starter-velocity`. Added `dev.tessera:tessera-spring-boot-starter-javax` (Boot 1.4.1 / Spring 4.3 / `javax.servlet`). Boot was not upgraded.
- Declared `org.springframework:spring-context-support` (4.3.3.RELEASE). It used to come with the Velocity starter, and the app needs it for `EhCacheCacheManager` (`spring.cache.ehcache.config`). The `dependency:list` diff otherwise only drops velocity, commons-beanutils, commons-collections, commons-digester and commons-lang, none of which the app uses.
- `maven-compiler-plugin` went from 2.3.2 to 3.8.1, which `annotationProcessorPaths` needs. Source/target moved from 1.7 to 1.8 because Tessera needs Java 8. The build already required JDK 8 (`<bootclasspath>${java.home}/lib/rt.jar`). Added `<parameters>`, the `tessera-compiler` processor, `-Atessera.templates` and `-Atessera.package=com.msun.luckyBlog.templates`.
- Added `tessera-maven-plugin` (`check` and `review` goals).
- `maven-jar-plugin` only packaged `**/*.class`. It now also includes `META-INF/services/dev.tessera.runtime.TemplateRegistry` and `META-INF/tessera/**`; without them the packaged app would have no templates.

**Configuration:** removed all `spring.velocity.*` properties. There is no "another template engine is still configured" warning at startup.

**Templates:** 18 `.vm` files plus `VM_global_library.vm` became 17 `.html` pages plus 3 fragment files:
- `fragments/front.html`: the layout replacing `module/header.vm` + `module/footer.vm` (`#parse`/`#include`).
- `fragments/admin.html`: the layout replacing `module/admin-header.vm`. Each admin page passes its body and its own scripts.
- `fragments/flip.html`: the `#flip` and `#frontFlip` macros. `#liClass` became a `t:class` on `<tr>`.

**Java**
- `@TemplateView` on `Info`, `BlogView` and `Archive`.
- New `view/ProjectRow`: `Project.date` is a `java.sql.Timestamp`, which isn't view-safe. The row carries its `toString()` text, as Velocity printed it. I didn't add a getter to `Project`, because that would change the `/admin/pro.json` JSON.
- New `view/BlogFunctions` (`blog.html(...)`): an `HtmlPolicy` for the stored editor.md HTML of articles and the résumé. It is RICH plus the classes that editor.md and prettify write, and adds no `rel`.
- `AdminController`/`FontEndController` pass `ProjectRow`s. When an update fails, they also pass `targetVid` for the result page's refresh link.
- `CtrlExceptionHandler` returned the view name `"/error"`. Velocity didn't match it, so Boot's `InternalResourceViewResolver` forwarded to `/error`. Tessera strips the leading `/` and would render `error.html` directly, with a different status. The handler now returns `"forward:/error"`, which is exactly the old behaviour (status 200 for `/post/999` and the like, as recorded).

**Velocity quirks reproduced on purpose**
- `/projects` (no page number) printed the unresolved `$prev`/`$next` references literally in its links. The `frontFlip` fragment keeps that.
- `#set($x = $null)` keeping the old value is mirrored in `result.html` (no `result` → success; no `targetUrl` → `/`) and `login.html`.
- `$!{x}` in attributes is written as `x ?: ''`, so the attribute stays present.

## Pages that differ from the old app, and why

Recorded and compared on the seed data with `tessera-migrate record/compare` and `pages.txt`.

1. **`GET /post/23`, the seed article.** The stored HTML is prettify output that puts `<ol class="linenums"><li>` inside `<pre>`, with `<a name=...>` anchors and `id`s. No Tessera `HtmlPolicy` can reproduce it:
   - the closed content model allows only inline content in `pre`, so `ol`/`li` are unwrapped;
   - `name` is not among `HtmlPolicy.ATTRIBUTES`;
   - ids are always prefixed (`user-content-`; `idPrefix` can't be empty).

   The text and code survive, but the line-number list inside code blocks is lost. The only ways to match byte for byte are `SafeHtml.unchecked` (forbidden) or rebuilding the HTML from a parsed tree (which the guide explicitly forbids). I kept the sanitizer.
2. **`GET /admin/` (monitor).** The old script inlined `{value: $freeMemory, ...}`. Tessera only allows `[[...]]` as a whole declaration at the top of a script (the prelude rule), so the script now begins `var freeMemory = (63); var usedMemory = (37);` and uses the names. The chart behaves the same. The value itself is live free memory and differs on every run anyway.
3. **`GET /tags/<x>`.** The old page printed the path variable raw into `<h2>`, a reflected XSS. It is now escaped. This is the intended fix.
4. (`GET /projects/1` after `addPro.action` shows the project's creation time, `now()`, so it differs between any two runs. Compare it with `--ignore 'TODAY [0-9:.]+'`.)

I also ran a second baseline with hostile data (HTML and quotes in titles, tags, project fields, the Twitter URL, a `javascript:` project URL, `<script>`/`onclick` in the article and résumé). Every difference there is the old page printing data raw:
- broken attributes and injected elements are now escaped text;
- `javascript:` becomes `about:invalid#tessera`;
- `<script>` and `onclick` are stripped from the rich text.

All intended.

## Not converted / not done
- Nothing is left on Velocity.
- I didn't add a rendering test. The project has no test dependencies, and the build skips tests (`maven.test.skip=true`); I left that as it was.
- The avatar `style="background: url(...)"`: for the default `/img/avatar.jpg` the template writes the old literal, because `css.url` writes `url("...")` with quotes. Any other avatar goes through `css.url(...)`.

## Where Tessera or its documentation fell short
- **Rich text with code blocks:** `HtmlPolicy`'s fixed content model can't hold `pre > ol > li`, which is what prettify/editor.md (`linenums`) store. There is no option for it, and no option to keep `<a name>` or unprefixed ids. A blog that stores rendered Markdown can't get parity without `unchecked`.
- **Inline script values:** the prelude rule makes exact parity impossible for any old script that interpolated a value mid-expression. The guide says so, but `compare` then always reports the page as `differs`.
- **`css.url` quoting:** it always writes `url("...")`, so any old unquoted `url(x)` differs in compare even though it means the same.
- **View name `"/error"`:** the guide (4.9) says a leading `/` is dropped. It doesn't warn that this changes behaviour for apps where the old resolver didn't match such a name and Spring forwarded to the URL instead. It silently changed the status code here.
- **`maven-jar-plugin` includes:** the guide mentions packaging the generated `META-INF` resources, which helped. The `*.class`-only include in this app would otherwise have shipped a jar with no templates and no build error.
- **Refresh meta:** a `targetUrl` string from the controller can't be used. Each target needs its own literal `@{...}`, which needs a `t:if` per target.
- **Running the app:** not a Tessera issue, but the app is wired to an external MySQL host. For record/compare I ran both apps on H2 in MySQL mode with converted copies of `import.sql`/`data.sql` (under the git-ignored `.tessera/`, not committed).
