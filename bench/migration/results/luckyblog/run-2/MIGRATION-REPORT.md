# Migration report: Velocity → Tessera

## Result

| Check | Status |
|---|---|
| Every page rendered by Tessera | Yes. All 18 views (`index`, `archives`, `projects`, `tags`, `tagView`, `post`, `about`, `login`, `error`, `admin/*`) are Tessera templates. Velocity's templates (`*.vm`, `VM_global_library.vm`, `module/*.vm`), its starter and every `spring.velocity.*` property are gone. |
| Build | `mvn clean verify` passes on JDK 8 (`/usr/lib/jvm/java-1.8-openjdk`) and on JDK 21. The project disables tests in its own pom (`maven.test.skip=true`, `skipTests=true`) and its only test class (`Test.java`) is an empty `main`. I left both as they were. |
| Page parity | `tessera-migrate compare` on my `pages.txt` (77 requests): **68 `same`, 9 `differs`**. All 9 differences come from the 10 requests at the end of `pages.txt` that put markup into user data. The old pages printed that markup raw and Tessera escapes it (details below). Every page has its recorded status and final URL. |
| Security | No `SafeHtml.unchecked` or `TrustedUrl.unchecked`, no `permitAll`, nothing turned off. The app has no Spring Security and no CSRF of its own, and that hasn't changed. |

## What changed

**Build (`pom.xml`)**
- Removed `spring-boot-starter-velocity`. Added `dev.tessera:tessera-spring-boot-starter-javax` (Boot 1.4.1 / Spring 4.3 / `javax.servlet`). Boot stays at 1.4.1, as the guide says.
- Added `spring-context-support` explicitly. It used to come in with the Velocity starter, and `EhCacheCacheManager` (`spring.cache.ehcache.config`) needs it. I diffed `mvn dependency:list`: the other libraries that left with Velocity (`commons-beanutils`, `commons-collections`, `commons-digester`, `commons-lang`) aren't used by the app.
- Added `org.jsoup:jsoup` to parse stored article/resume HTML (see "Raw HTML" below).
- `maven-compiler-plugin` 2.3.2 → 3.8.1, Java source/target 1.7 → 1.8 (Tessera needs Java 8). Added `annotationProcessorPaths` for `tessera-compiler`, plus `-Atessera.templates` and `-Atessera.package=com.msun.luckyBlog.templates`.
- `maven-jar-plugin` packaged only `**/*.class`. It now also includes `META-INF/services/**` and `META-INF/tessera/**`. Without them the generated template registry is missing from the jar, Tessera's resolver knows no views, and every page falls through to `InternalResourceView` and returns 404.

**Java**
- `@TemplateView` on `Info`, `Archive`, `BlogView` and `Project`.
- `support/view/RichNode` + `RichText`: parse stored HTML into an allow-listed tree (see below).
- `support/view/BlogFunctions` (`blog.projectDate(p)`): `Project.date` is a `java.sql.Timestamp`, which isn't view-safe. The function prints it as `Timestamp.toString()`, as Velocity did.
- `FontEndController`: `article` (post) and `resume` (about) are passed as `List<RichNode>`.
- `AdminController.update` (failure case): the model now carries `targetUrl="/admin/update"` plus `targetVid`, because a `<meta refresh>` link path must be a literal with placeholders.

**Templates** (`src/main/resources/templates`)
- `layout/front.html`: replaces `module/header.vm` + `module/footer.vm` as one layout fragment `front(Info info, Fragment content)`.
- `layout/admin.html`: replaces `module/admin-header.vm`, whose tags were left open for each page to close, as `admin(Fragment content, Fragment scripts)`.
- `layout/macros.html`: the `#flip` and `#frontFlip` macros as typed fragments. `#liClass` became a `t:class` on the row.
- `layout/richtext.html`: a recursive fragment that renders `RichNode` trees.
- One page template per old view, same view names, so the controllers didn't change.

## Decisions and differences worth knowing

- **Raw HTML (articles, resume).** Velocity printed `$!{article}` and `$!{resume}` raw. That stored editor.md HTML needs `class`, `id` and `name` (code highlighting, anchors), which `html.sanitize` drops. So `RichText` parses it with jsoup into an allow-listed tree:
  - **Tags kept:** p, br, hr, h1–h6, a, span, code, pre, ol, ul, li, blockquote, strong, em, b, i, u, s, del, sup, sub, kbd, table, thead, tbody, tr, th, td, img, dl, dt, dd, div.
  - **Attributes kept:** id, class, name, title, href, src, alt.
  - `layout/richtext.html` renders the tree, so every value is escaped, and `href`/`src` go through Tessera's URL check (`javascript:` becomes `about:invalid#tessera`).
  - `script`, `style`, `iframe` and similar elements are dropped with their content. Other unknown tags are unwrapped. `style`, `on*` and `target` are dropped.
  - All existing seed articles render identically.
- **Avatar `style`.** `css.url()` writes `url("…")`, but the old pages wrote `url(/img/avatar.jpg)`, and compare treats that as a difference.
  - The templates print the default avatar `/img/avatar.jpg` as a literal (a choice between literals is allowed), so its output is identical. Every other value (an uploaded avatar) goes through `css.url`.
  - The CSS means the same either way, but an uploaded avatar's `style` attribute will differ textually from the old page.
- **Velocity quirks reproduced on purpose:**
  - `GET /projects` (no page numbers in the model) printed the unevaluated references `/projects/$prev` and `/projects/$next`. `frontFlip` reproduces this when `pageNum`/`current` are null.
  - `login.vm` showed the growl script only when `success` was set and below 1.
  - `result.vm` fell back to `/` and to "success" when `targetUrl`/`result` were missing.
  - Values printed with `$!{…}` in `value="…"` attributes keep `value=""` when null (`x ?: ''`).
- **Error pages.** `CtrlExceptionHandler` returns the view name `"/error"`. Tessera's resolver doesn't match a leading slash, so the request is forwarded to Boot's `/error` as before, which renders `error.html`. Statuses match: 200 for `/post/1`, 404 for unknown URLs, 500 for `/error`.
- **The 9 remaining `differs`** (pages 11 and 69–77 of `pages.txt`) are all old output that was injectable:
  - `<b>` in a tag name, post title, project name or twitter field.
  - A `"` that broke out of `value="…"`/`href="…"`.
  - `</textarea>` in markdown, which ended the editor's `<textarea>`.
  - `<script>`, `onclick`, `style`, `target` and `javascript:` in an article.

  Tessera now escapes or drops these. I kept these differences on purpose.
- The monitor page shows live free memory, so I compared with `--ignore 'value: \d+'`. Nothing in the template can make that page stable from run to run.

## How I ran the check

- The app needs MySQL (`192.168.180.154`), which isn't here. I ran both apps from the built `target/lucky-blog-1.0.0-SNAPSHOT/{conf,lib}` layout (as `bin/start_luckyBlog.sh` does).
- I added H2 2.1.214 (`MODE=MySQL;NON_KEYWORDS=DATE,VALUE`) to the classpath only, and seeded it from `data.sql`, converted to standard SQL string escapes. The setup is in `.tessera/` (git-ignored, not part of the app). The H2 1.4.196 in `~/.m2` lacks `INSERT IGNORE`/`LAST_INSERT_ID`, so posting a blog always failed there.
- `pages.txt` is committed next to the pom. It covers:
  - every front page: empty and full lists, pagination edges, unknown tag, missing post, 404, `/error`;
  - login: empty, failed and successful;
  - every admin page while logged out (redirect) and logged in;
  - every admin form's success and failure result pages: blog add, update, delete, project add/delete, info, password, resume;
  - the injection cases at the end.

## Not converted

Nothing was left unconverted. The avatar upload (multipart POST to `/admin/file/uplAva.action`) renders `admin/info` and is covered by that template, but `tessera-migrate` can't send multipart forms, so that request isn't in `pages.txt`.

## Where Tessera or its documentation fell short

1. **Jar packaging isn't mentioned.** The processor writes `META-INF/services/dev.tessera.runtime.TemplateRegistry` and `META-INF/tessera/functions/*`. A build that packages only `**/*.class` (as this one did) compiles fine and starts fine, but every view silently falls through to other resolvers and returns 404. Nothing is logged at startup (no "0 templates found" warning). The guide should list these resources as required at runtime.
2. **The `dependency:copy` command in the skill doesn't work in this project.** The pom configures `maven-dependency-plugin`'s `outputDirectory`, which overrides `-DoutputDirectory`, so the jar went to `target/…/lib`. I had to copy it from `~/.m2` by hand.
3. **`css.url()` always quotes** (`url("…")`). There's no way to reproduce an unquoted `url(/path)` from data. A page that printed an image URL into `style` can't match exactly without a literal workaround. The guide doesn't mention this output difference.
4. **Boot 1.4 isn't in the test suite.** The guide says 1.4 apps "have migrated unchanged", and that held here: auto-configuration, the view resolver and fall-through for `/error` and `redirect:` all worked.
5. **Rich text.** The guide's advice for stored HTML that needs `class`/`id` is to "parse it in Java into an allow-listed tree of records and render that with a recursive fragment". There's no example, so I had to work these out by trial:
   - The fragment needs one `t:case` per tag, with no whitespace between cases, because whitespace between cases is output.
   - Tag names can't be dynamic, so every allowed tag is spelled out.
   - The order of `t:each` and `t:replace` on one element is unclear, so I nested them.

   A built-in allow-list renderer, or a configurable `html.sanitize` policy, would replace about 150 lines of app code.
6. **Velocity's `#parse("/templates/module/header.vm")` paths and its tolerance of null arithmetic** (printing `$prev`) had to be found by recording. The Velocity notes cover the null cases in general terms. That was enough, but a concrete `frontFlip`-style example would have saved a round of comparing.
7. **`tessera-migrate record`/`compare` can't send multipart forms or custom headers,** so file-upload flows can't be covered.
