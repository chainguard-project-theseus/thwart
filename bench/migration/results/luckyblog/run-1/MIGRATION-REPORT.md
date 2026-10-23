# Migration report: Velocity → Tessera

## Result

| Check | Status |
|---|---|
| All pages rendered by Tessera; Velocity templates, dependency and configuration removed | Done |
| `mvn clean verify` on JDK 8 (the JDK this build targets, see below) | BUILD SUCCESS. The only test, `Test.java`, is an empty `main`, and the pom skips tests (`maven.test.skip=true`, surefire `skipTests=true`). I left that setting alone. With `-Dmaven.test.skip=false` the test sources compile, and the Tessera processor runs on them too. |
| `tessera-migrate compare` | 60 of 61 requests `same`, 1 `differs` (explained below) |
| Security | No `SafeHtml.unchecked` or `TrustedUrl.unchecked`, no `permitAll`, no CSRF or escaping changes, no tests disabled |

## What I changed

### Build (`pom.xml`)
- Removed `spring-boot-starter-velocity`.
- Added `dev.tessera:tessera-spring-boot-starter-javax:0.1.0-SNAPSHOT`, because the app uses `javax.servlet` (Boot 1.4.1 / Spring 4.3.3). I kept the Spring Boot version.
- Added `org.springframework:spring-context-support:4.3.3.RELEASE`. Until now the Velocity starter pulled it in, and `spring.cache` needs it for `EhCacheCacheManager`. Without it Boot falls back to the simple cache, which knows only the cache `ehcache`, so every page failed with "Cannot find cache named 'profileOfInfo'".
- Added `org.jsoup:jsoup:1.17.2`, used to parse stored article and resume HTML (see "Rich text").
- Raised the Java source/target from 1.7 to 1.8, the minimum Tessera supports. `maven-compiler-plugin` went from 2.3.2 to 3.8.1 for `annotationProcessorPaths`, and the plugin now runs `tessera-compiler` with `-Atessera.templates=…/src/main/resources/templates` and `-Atessera.package=com.msun.luckyBlog.templates`. I kept the existing `bootclasspath ${java.home}/lib/rt.jar` argument. It means the build runs on JDK 8, as it did before: JDK 9+ has no `rt.jar`.

### Configuration
- Removed every `spring.velocity.*` property from `application.properties`.

### Templates (`src/main/resources/templates`, all `.vm` files deleted)
- `module/front.html`: layout fragment `front(Info info, Fragment content)`. It replaces the `#parse header.vm` / `#include footer.vm` pair used by `index`, `post`, `projects`, `tags`, `tagView`, `about` and `archives`.
- `module/admin.html`: layout fragment `admin(Fragment content, Fragment scripts)`, which replaces `#include admin-header.vm`. Each admin page passes its `<body>` and a `<scripts>` element, both marked `t:remove="tag"`.
- `fragments/flip.html`: the `#flip` and `#frontFlip` macros from `VM_global_library.vm`, as typed fragments. The `#liClass` macro is now a `t:class` expression on the row (`st.count % 4`).
- `fragments/avatar.html`: the avatar box (see "Limitations").
- `fragments/rich.html`: a recursive fragment that renders a `RichNode` tree (see "Rich text").
- Pages: `index`, `post`, `projects`, `tags`, `tagView`, `about`, `archives`, `login`, `error`, `admin/{blog_add, blog_list, blog_modify, info, monitor, project, result, resume}`.
- Velocity quirks that showed up in the pages are reproduced on purpose:
  - `GET /projects` has no paging data. Velocity's arithmetic on null failed, so it printed the links `/projects/$prev` and `/projects/$next` literally. `frontFlip` does the same.
  - `login.vm`'s `#set($ii = $!{success})` keeps 1 when `success` is null.
  - `result.vm` treats a null `result` as success.
  - A null `$!{...}` value that Velocity wrote as `value=""` keeps the empty attribute (`?: ''`) instead of dropping it.
  - `info.html` drops one stray `</div>`. The browser ignored it, but Tessera requires well-formed markup.

### Java
- `@TemplateView` on `Info`, `BlogView`, `Archive` and `Project`.
- New package `com.msun.luckyBlog.support.view`:
  - `RichNode` (`@TemplateView`) and `RichText.parse(String)`: stored HTML becomes an allow-listed node tree.
  - `ViewFunctions` (`@TemplateFunctions("views")`): `projectDate(Project)`. It prints the `Timestamp` exactly as Velocity did. `java.sql.Timestamp` isn't view-safe, and I didn't want to add a getter to `Project`, because `/admin/pro.json` serializes it with Jackson.
- `FontEndController`: `post` and `about` now put `RichText.parse(...)` in the model instead of the raw HTML string. No other controller changes were needed: every value the templates read was already in the model. Boot's error attributes feed `error.html`, and the `"/error"` view name from `CtrlExceptionHandler` still falls through to Boot's `/error` forward, as it did with Velocity.

### Rich text (article and resume HTML)
Velocity printed `$!{article}` and `$!{resume}` raw. Tessera's `html.sanitize` would remove the `class`, `id` and `name` attributes that Editor.md's output depends on (every code block, heading anchor and line number), so every post page would change. Instead, as the guide's "Rich text" limitation suggests ("build the markup in Java (a template over a parsed model)"), `RichText` parses the HTML with jsoup and keeps an allow-list:
- Elements: a, abbr, b, blockquote, br, caption, center, cite, code, dd, del, div, dl, dt, em, figcaption, figure, font, h1–h6, hr, i, img, ins, kbd, li, mark, ol, p, pre, q, s, samp, small, span, strike, strong, sub, sup, table, tbody, td, tfoot, th, thead, tr, u, ul, var.
- Attributes: id, class, name, title, alt, href, src, target, rel, width, height, align, colspan, rowspan, start, lang, dir. For `style`, only the literals `text-align:left|center|right` (Editor.md table alignment) are kept.
- Removed with their content: script, style, iframe, object, embed, svg, math, form controls and similar. Any other element is unwrapped and keeps its text. Comments are dropped.

`fragments/rich.html` renders the tree. Text and attributes go through Tessera's normal escaping, `href`/`src` through its URL check (`javascript:` becomes `about:invalid#tessera`), and `style` only takes the literals above. Nothing bypasses escaping.

## Verification
- Local data: there is no MySQL in this environment. I ran both apps on H2 2.1.214 in MySQL mode, which is only on the runtime classpath for testing and not a project dependency. `src/main/resources/data.sql` was converted for H2 (MySQL string escapes), with an equivalent schema. Everything lives in `.tessera/run/` (git-ignored).
- `.tessera/pages.txt` has 61 requests in order:
  - every front page, empty and full;
  - the post with and without prev/next, and a missing post (error page);
  - 404;
  - login, failed login, the admin redirect while logged out, then login;
  - every admin page;
  - adding a blog (success and failure), updating a blog (success, failure with a numeric id, failure without an id);
  - updating info, adding and updating projects, updating the resume, deleting a blog and a project;
  - logout, then the admin redirect again.

  I recorded the original build (compiled before any change) and compared against the new build, both started from the same seed data.
- `--ignore 'value: [0-9]+'` masks only the free-memory percentage on the monitor page. It is read live from the OS, so it changes on every request.

## What could not be converted, and remaining differences
1. **`admin/result` after a failed blog update with a numeric id** (`POST /admin/update.action vid=999…`). The old page wrote `<meta http-equiv="refresh" content="1;url=/admin/update999">`. Tessera rejects any expression in a refresh `<meta>`'s `content` ("redirect from the controller instead"), and I found no way to build that markup that isn't `SafeHtml.unchecked`.
   - `result.html` chooses between literals for every fixed target the controllers use: `/admin/info`, `/admin/blog_add`, `/admin/blogList/1`, and the old app's own `/admin/updatenull` (a failed update without `vid`).
   - For a numeric id it refreshes to `/admin/blogList/1` (the list that links to the editor). This is the one `differs` page.
2. **Avatar background.** `style="background: url($!{avatar}) …"` (login, about, admin info) can't be expressed: a `style` attribute takes only literals and `CssValue`s, and there is no `CssValue` for `url(...)`. The default avatar `/img/avatar.jpg` (the seed data and the column default) renders identically, as a literal. An uploaded avatar renders the box without the background image.
3. **Intended differences, not in my page list because they are security fixes:**
   - Blog titles, tags, project fields and user info containing markup or entities are now escaped. Velocity printed them raw, which was stored XSS.
   - Article and resume HTML loses scripts, event handlers, `javascript:` URLs, arbitrary `style` attributes, and elements and attributes outside the allow-list.
   - Velocity also wrote `${blog.Title}` and other values unescaped inside `value="…"` attributes, so a `"` broke the attribute. Tessera quotes them correctly.

## Where Tessera or its documentation fell short
- **Boot 1.4.** The docs support only Boot 1.5+. The app is on Boot 1.4.1 (Spring 4.3.3), and the guide says not to upgrade Spring. The starter's auto-configuration loaded and worked on 1.4.1, but nothing in the docs says whether that is expected.
- **The old starter's transitive dependencies.** Removing `spring-boot-starter-velocity` silently removed `spring-context-support`, which broke caching at runtime with no build error. The guide's "remove the old engine" step could warn about dependencies that only the old starter brought in.
- **Rich text.** `html.sanitize` has a fixed policy that strips `class`/`id`, which makes it unusable for typical Markdown-editor output. "Build the markup in Java (a template over a parsed model)" is the only route, and I had to write it myself: a parser plus a ~50-case recursive `t:switch` fragment with one `t:` attribute per allowed name. A configurable sanitizer policy, or a documented built-in node-tree renderer, would avoid this.
- **No way to compute attribute names.** `t:attr` names must be literals, so the generic node renderer must list every allowed attribute on every element. The `t:style` restriction meant special-casing even harmless values like `text-align:center`.
- **Refresh `<meta>`.** Tessera has no safe way to render a refresh target computed in Java (for example a `TrustedUrl` or a link `@{/admin/update{id}(id=…)}`). Even a path built by `@{}` from a literal path and an integer is rejected, so this page can't be ported faithfully.
- **No `url()` CSS value.** There is nothing like `css.url(TrustedUrl)`, so `background: url(...)` with a model value can't be written. The guide doesn't say what to do with it.
- **Comments.** There is no template-only comment syntax: all HTML comments are copied to the output. I had to delete an explanatory comment from `admin/result.html` so it wouldn't be served to users.
- **Ternaries with mixed types.** `?:` with a `String` and an `Integer` branch is a build error (`x == null ? '' : x`). The guide does document the `'' + x` workaround, but this pattern comes up constantly when porting Velocity's `$!{x}`.
- **Whitespace in the generated renderer.** Whitespace between `t:case` siblings is emitted as text, so the recursive renderer has to be written with no whitespace between tags (line breaks only inside tags). The guide doesn't mention this.
- **`numbers.sequence(1, 0)`.** The guide doesn't say what it returns. I guarded the call with `pageNum > 0`.
- **Recording and comparing.** `tessera-migrate record` doesn't URL-encode request paths (`GET /tags/new<i>` fails with "Illegal character in path"). In POST bodies, a raw `&` inside a value splits the parameter, so values have to be pre-encoded (`%26`). Neither is documented.
