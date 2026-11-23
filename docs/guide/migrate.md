# Migrating views to Thwart

This is the reference for moving a Spring application's views (Thymeleaf, JSP, FreeMarker,
Velocity, Pebble, Mustache or similar) to Thwart. It doesn't map your engine's syntax construct by
construct: you know your engine, and once you know Thwart's model the syntax converts on its own.
It covers what you can't find out from the tools: the tools, how to work and what "done" is checked
against, the loop step by step, hot reload, and the traps of particular engines. The model, the
syntax and the output contexts are in [templates.md](templates.md), build setup in
[setup.md](setup.md), and known limitations in [limitations.md](limitations.md). Read the model,
the build setup and your engine's notes before you start; look up the rest when you need it.

## Your tools

The compiler comes first: each error names the file, line and column, says what is wrong and how
to fix it, and `mvn compile` (Gradle: `gradle compileJava`) is the fastest check you have. For a
migration you also have:

- **`thwart-migrate`**, a jar that runs on JDK 8+. Fetch it into `.thwart/` (outside `target/`;
  don't commit it):

  ```sh
  mvn -q dependency:copy -Dartifact=dev.chainguard.thwart:thwart-migrate:0.1.0-SNAPSHOT -Dmdep.stripVersion=true -DoutputDirectory=.thwart
  java -jar .thwart/thwart-migrate.jar --help
  ```

  The command needs no `pom.xml`, so it works the same in a Gradle application. Thwart isn't
  published to a remote repository: it resolves from the local Maven repository, where it was
  installed with `mvn install` (a Gradle build reads it through `mavenLocal()`). If the copy
  fails, the jar may still be there to copy by hand
  (`~/.m2/repository/dev/chainguard/thwart/thwart-migrate/<version>/`). If `dev.chainguard.thwart` is missing from
  the local repository, say that Thwart has to be installed first.
  - `record` and `compare` replay a `pages.txt` against the old app and then the new one and
    compare the pages as a browser builds them. This is how you know a page is right.
  - For Thymeleaf, `thwart-migrate <old-templates-dir> <new-templates-dir> --report report.md`
    does the mechanical rewrite and reports, by `file:line`, what it couldn't convert and every
    value the templates read from outside the model.
- **The application's own tooling.** Its build, tests, scripts, README and seed data are yours to
  find and use.

## How to work

The old application is the specification. Record it before you change anything, with a
`pages.txt` that reaches every page in every state that renders differently. Set up the build, then
convert shared layouts and fragments first, then pages, compiling and comparing as you go. Keep
templates that share a layout or form fragments in one context: split across subagents, they
drift apart. Run
`compare` early and often: an empty value or a 500 almost always means a value the old template
took from outside the model. Remove the old engine last: its templates, dependency and
configuration. Until it is gone, it still renders every view Thwart doesn't know, with its own
injection risks, so removing it is what completes the security guarantee.

You can't feel how much time has passed. If you were given a time budget, note `date` when you
start and run it again before deciding time is short.

## What "done" is checked against

- The application's normal Maven or Gradle build passes on the JDK it targets, with all existing
  tests. Don't delete, disable or weaken tests; update a test only where it asserts on the old
  engine's details.
- Page parity: `compare` exits 0 on your `pages.txt`, so every page is `same` or `reordered` with
  its recorded status and final URL, or you can say why a remaining difference is right. A
  `pages.txt` that skips pages or states proves nothing.
- Page scripts still run, which `compare` can't see: load the `pages.txt` pages in a headless
  browser on both apps and diff the console. Check each CSP violation or uncaught exception the old
  app doesn't have: a blocked inline script or `on*` handler is the table in
  [The model](templates.md#the-model); a blocked `eval` is "A third-party script that needs `eval`" in
  [Rendering](templates.md#rendering). A report that breaks nothing
  (no exception, nothing missing on the page) can be noted and left.
- Security isn't weakened: no new `permitAll`,
  no CSRF or escaping turned off. Output the old engine printed raw and Thwart now escapes is the
  intended difference.
- The old engine is gone, and the startup warning `Thwart: another template engine is still
  configured` no longer appears.

## The tools and the loop

`thwart-migrate` is one jar with three commands; `--help` prints the usage. Fetch it as
[Your tools](#your-tools) says, or use the jar in the local Maven repository.

**1. List every page.** Write a `pages.txt` that reaches every view in every state that renders
differently: forms empty, with validation errors and after success; logged out and as each role;
empty and full lists; error pages.

```text
# One request per line, run in order with one cookie jar.
GET /owners/new
POST /owners/new firstName=&lastName=
POST /owners/new firstName=Ann&lastName=Lee&city=Paris&telephone=0123456789
GET /owners/1
POST /login username=admin&password=admin
```

A `POST` sends the `_csrf` hidden input of the previous page, so `GET` a form before posting it.
Write paths and form values raw or percent-encoded (a space is `%20` in a path, `+` in a form
value). Start the app from the same data on every run, and let the list create the data it needs.

**2. Record the old app** before changing anything:
`java -jar thwart-migrate.jar record http://localhost:8080 pages.txt baseline/`. It saves each
response's body, status and final URL. `compare` refuses a `pages.txt` that doesn't match the
recording, so record again after changing it.

**3. Set up the build** ([setup.md](setup.md)) and give the templates view types
([View types](templates.md#view-types)).

**4. Convert one template at a time**, shared layouts and fragments first, since pages depend on
them. For each template, find every value it reads and make sure the model provides it, declare the
parameters with their real types, rewrite the markup, and run `mvn clean compile` (Gradle:
`gradle compileJava`).

For Thymeleaf, `thwart-migrate <old-templates-dir> <new-templates-dir> --report report.md` does the
mechanical rewrite of a whole directory first: `th:` to `t:`, utility objects, common string-built
links and `__${a.b}__` path segments, `sec:authorize` calls with an `auth.` twin, `th:attr` of
`data-*`/`aria-*` pairs (and a `content` pair on a `<meta>`), `th:errorclass` beside a `th:field`, layouts built from `th:replace`
fragment calls, script inlines; Layout Dialect pages (`layout:decorate`) are reported and ported by
hand ([Fragments and layouts](templates.md#fragments-and-layouts), Layouts). It stubs parameter types as `Object`, which fails to compile until you
write the real types: one error per stub, since the compiler checks neither a template's body nor
the calls into its fragments until its parameter types resolve. Its report lists what it couldn't
convert and every read of a scope the template can't see (`session.x`, `param.x`, `#request`, `@bean`,
`T(...)`, ...) with where the value should come from instead: that is the controller-side
checklist. Forms the compiler rejects with the fix (event handlers such as `th:onclick`, a computed
`th:style`, raw output, `th:remove="body"`) are converted without a report: the build error names
the fix. It can't see a plain name the old engine found in a request attribute; those show up in
`compare` as an empty value or a 500.

**Nulls are where a converted page most often fails at runtime.** A `null` link path variable
fails the render where the old engine wrote an empty segment, and an empty one is rejected too:
guard such a link with `t:if`. A new `NullPointerException` reading `a.b` means `a` is `null` now
and wasn't before, nearly always a value the old template got from outside the model. Tests catch
these only if a fixture holds the `null`.

**5. Compare** the new app, started from the same data:
`java -jar thwart-migrate.jar compare http://localhost:8080 pages.txt baseline/`. It compares
status, final URL and the page as a browser builds it, ignoring whitespace, attribute order,
comments, CSRF tokens and JSON formatting in scripts. Each page prints `same`, `reordered`,
`forced` or `differs`, with a diff for all but `same`. `reordered` passes but its diff is printed:
check that what moved is harmless (validation messages from a `HashSet`, yes; table rows that lost
their sort order, no). `forced` means the page differs only in what Thwart makes you change:
`on*` handlers, `href="javascript:…"` and `href="#"`, `data-*` attributes (added, removed or
changed, as the recipes in [Output contexts](templates.md#output-contexts) do), `<script>` and
`<style>` elements, `hx-X` written as `data-hx-X` with the same value, the query of a path in an
attribute whose name has a `-` (other than `aria-*`: `hx-delete="/posts?ids=5"` became
`hx-delete="/posts"` with a `data-*` attribute and a script that adds the query), and, inside a
table or `<template>`, an `<svg>` or `<math>` replaced by an `<img>` or by an element with no
content and only `class`, `role`, `title` and `aria-*` attributes. Scripts, styles and `data-*`
values aren't compared in that form, so a wrong value in any of them is `forced` too. `forced`
knows only these changes, so some changes Thwart requires still make a page `differs`: a
computed `hx-target` or an `hx-vals` moved to hidden inputs, a URL encoded differently (a space
written `%20`), `hx-confirm` text moved into a hidden element, a removed `hx-on::…` attribute
(Thwart copies it as written, so the CSP forced the change, and compare knows no framework's
attribute names: only `on*` attributes count as handlers), a computed `<style>` width moved to a
class (a `data-*` attribute that a script applies compares `forced`), and the `_csrf` hidden input
Thwart adds to a `method="post"` form. Check those pages by hand, and
write down the recipe behind each. Fix the other `differs` pages first, then for each `forced` page
check the values in its diff and load it in a browser to check that its scripts work. It exits 0
only if every page is `same` or `reordered` with its recorded status and final URL.

For each difference, fix it or decide it is right and write down why. Few are right: output now
escaped where the old engine printed it raw, broken markup the old engine passed through, and the
expected changes of a recipe in [Output contexts](templates.md#output-contexts). Text that changes on every run (times, random ids,
content-hashed asset names) can be masked with `--ignore REGEX`; keep it narrow, since what it
matches is never compared. Compare after each template or small group, and run the tests. Controller
tests that use `standaloneSetup` never render a view, so add one that renders through the real
resolver.

**6. Remove the old engine.** This completes the security guarantee: until its resolver is gone,
any view name Thwart doesn't know still reaches it. Remove its templates, dependency and
configuration, then build, test and compare once more. The old engine's starter often brought in
libraries the application still uses (`spring-context-support` with Boot's Velocity starter,
`commons-beanutils` with Tiles), and losing them may show only at runtime: diff
`mvn dependency:list` (Gradle: `gradle dependencies --configuration runtimeClasspath`) from
before and after, and declare what the application still uses.

### Hot reload

Under Spring Boot, a saved template or message file reaches the browser on the next refresh, a few
seconds later. The build recompiles the module with the same processor and the same checks, then
Spring Boot DevTools restarts the application with the new classes. Nothing compiles templates
inside the application, and Boot leaves DevTools out of the jar it packages. Add DevTools:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-devtools</artifactId>
  <optional>true</optional>
</dependency>
```

- **Maven:** run `mvn spring-boot:run` in one terminal and `mvn thwart:watch` in another, both in
  the application module's directory. The watch goal checks the templates and the
  `thwart.messages` files every half second and runs `mvn compile` on the module after each
  change, with the watch's own `-o`, `-P` and `-D` options (pass `-s` in `.mvn/maven.config`, not
  on the command line). It needs the `check` goal and its configuration from [setup.md](setup.md#maven). In a
  multi-module build, `install` the modules the application depends on first.
- **Gradle:** declare `developmentOnly 'org.springframework.boot:spring-boot-devtools'` instead,
  then run `gradle bootRun` in one terminal and `gradle -t classes` in another. The `inputs` lines
  from [setup.md](setup.md#gradle) make each save rerun the processor.
- **IDE:** to run the application from your IDE, keep the watch goal or the continuous build
  running next to it. We haven't tested whether an IDE's own automatic build reruns the processor
  after a template-only save, so don't rely on it.
- **Errors:** a template that fails to compile prints its `file:line:col` error in the watch
  terminal. By then the old classes are gone, so the restarted application fails to start until
  you save a fix. It then comes back by itself.
- **Timing:** in a small Boot app, about 4 s from save to page under Gradle and 5 s under Maven,
  most of it the build. The restart itself takes half a second.
- The Maven watch goal reacts only to templates and messages. Java edits are compiled by your IDE
  or by `mvn compile`, and DevTools restarts as usual. Spring MVC without Boot has no DevTools, so
  it has no hot reload.

## Notes for specific engines

What is easy to get wrong when leaving a particular engine. Read the part for yours.

**Every engine.**

- **Raw output.** Velocity prints everything raw, JSP prints EL `${x}` raw outside `<c:out>` and
  the form tags, FreeMarker prints raw without an HTML output format (and with `?no_esc`), Pebble
  with `| raw`, Mustache with `{{{x}}}`. Treat each raw output as a review point and replace it by
  where the value comes from: plain data becomes an escaped `t:text` (if the page now shows
  `&lt;b&gt;`, the old page was injectable, and that difference is the fix); stored rich text goes
  through a sanitizer ([Output contexts](templates.md#output-contexts), Rich text; in a `<p>`, heading or link, Inline HTML); markup built in Java becomes records the template
  renders; a bundle message with markup is `t:utext="#{key}"` ([Messages and links](templates.md#messages-and-links)); JSON for a script is
  `[[...]]` ([Output contexts](templates.md#output-contexts)).
- **Scripts.** `[[...]]` writes JSON with `/` as `\/`, and `<`, `>`, `&`, `'`, backtick, `$`, `{`,
  U+2028 and U+2029 as `\uXXXX` (`&` is `\u0026`); the JavaScript value is the same, so that
  difference in compare is expected.
- **Spring Security tags.** `sec:authorize="hasRole('A')"` and
  `<sec:authorize access="hasRole('A')">` become `t:if="auth.hasRole('A')"`, and
  `sec:authentication="name"` is `t:text="auth.name()"`. Any other expression is computed in the
  controller. `auth` reads `SecurityContextHolder` and compares the user's own authorities, with
  `ROLE_` as the role prefix. It ignores a `RoleHierarchy` (an `ADMIN` who implies `USER` fails
  `auth.hasRole('USER')`), a `GrantedAuthorityDefaults` prefix and a `SecurityContextHolderStrategy`
  bean, which `sec:authorize` honours. If the app uses one, compute the flag in the controller and
  pass it as a `boolean` param, for example `roleHierarchy.getReachableGrantedAuthorities(
  authentication.getAuthorities())` contains `ROLE_USER`.
- **Whitespace and comments.** Write template-only comments (`{{! }}`, `<%-- --%>`, `<#-- -->`,
  `##`, `{# #}`) as `<!--/* ... */-->`. Whitespace between `t:case` siblings is output.

**Thymeleaf.** Run `thwart-migrate` first ([The tools and the loop](#the-tools-and-the-loop)). Natural-template script comments
(`/*[[${x}]]*/ 'd'`) and quoted inlines (`"[[${x}]]"`) become `[[${x}]]`; move each value that
isn't then a declaration at the top of the script into one ([Output contexts](templates.md#output-contexts)). Layout dialect pages
become fragment calls ([Fragments and layouts](templates.md#fragments-and-layouts)).

**JSP, JSTL and Spring form tags, with Tiles or SiteMesh.**

- `<form:form modelAttribute="x">` is `t:object="x"`; `<form:input>`, `<form:select>`,
  `<form:checkbox>`, `<form:radiobutton>` and `<form:textarea path="p">` are the HTML element with
  `t:field="*{p}"`; `<form:options>` is a `t:each` over `<option>`. `<form:errors path="p">` is
  `t:errors="*{p}"`, `path="*"` is `t:errors="*"`, and with no path `t:errors="*{global}"`. Don't
  hand-write the form tags' markup: what you miss (selected options, ids, markers) shows up only in
  compare.
- The form tags write things `t:` attributes don't: `<form:errors>` writes an `id` (`p.errors`,
  or `<form>.errors` for `*`), so write it statically; `<form:form>` without an `action` posts to
  the request URI with its query string, which omitting the action does too, while a
  `t:action="@{...}"` must pass the query parameters itself.

<!-- compile: template guide/jsp-form-defaults.html -->
```html
<form xmlns:t="https://thwart.chainguard.dev" t:import="com.example.guide.*" t:params="Signup signup"
      t:object="signup" method="post">
  <span id="signup.errors" class="alert" t:errors="*">errors</span>
  <input type="text" t:field="*{email}" /> <span id="email.errors" t:errors="*{email}">error</span>
</form>
```

- Tiles definitions become page templates that call a layout fragment with the definition's
  attributes: `<tiles:insertAttribute>` is a `Fragment` parameter, `<tiles:getAsString>` a
  `String`. Inherited `extends` values and `cascade` attributes are passed explicitly by each page.
  A definition chosen at runtime becomes a choice between literal fragment calls or view names.
- `<jsp:include>`, `<%@ include %>` and tag files are fragments: tag attributes become typed
  parameters and `<jsp:doBody/>` a `Fragment` parameter. A tag file that binds
  `path="${field}.answer"` takes a `Field<T>` ([Fragments and layouts](templates.md#fragments-and-layouts)).
- Scriptlets and state changes during render (`<% %>`, `<jsp:useBean>`,
  `<c:set scope="session">`) move to the controller, a `@ModelAttribute` method or an interceptor.
  `<c:set var>` of a value is `t:with`; one that captures markup to repeat is a fragment.
- EL implicit objects (`param`, `sessionScope`, `header`, `pageContext.request.contextPath`) are
  model values; a context-path prefix is `@{...}`.
- With the JSP resolver gone, a view name no template provides is a 500 (`Could not resolve
  view`), where `InternalResourceViewResolver` let the container answer 404; if that matters, add
  a last view resolver that answers 404. Removing Jasper's `tomcat-jasper-el` can also remove the
  EL implementation Bean Validation needs: declare one (`org.glassfish:javax.el` for `javax`,
  `org.glassfish.expressly:expressly` for Jakarta).

**FreeMarker.**

- Macros are typed fragments, `<#include>` a fragment call, `<#nested>` a `Fragment` parameter.
  Fragment parameters have no defaults: where a call relied on a macro's default, pass that value.
- `x!d` is `x ?: d`; `(a.b)!d` is `a?.b ?: d`; `${x!}` is `t:text="x"`; `x??` is `x != null`;
  `c?then(a, b)` is `c ? a : b`.
- `spring.ftl` macros take paths that start with the form object's name (`"signup.email"`): put
  `t:object="signup"` on the form and bind `*{email}`. `formInput` and its siblings are the element
  with `t:field` and their attribute argument written statically; `formSingleSelect` is a
  `<select t:field>` over `<option>`s; `spring.status.value`, `.errorMessages` and `.error` are
  `fields.value`, `fields.errors` and `fields.hasErrors`. `showErrors` wraps each message in a
  `<span>` with the separator between: loop over `fields.errors` where the markup matters.
- `<@spring.message>` is raw from Spring 5: a message with markup is `t:utext="#{key}"`.

**Velocity (and Velocity Tools).**

- `#foreach` is `t:each="x, st : xs"`: `$velocityCount` is `st.count`, `$foreach.index`
  `st.index`, `$foreach.hasNext` `!st.last`. `#set` is `t:with`. Macros and `#parse`d headers and
  footers become fragments and a layout. `$esc.html` goes, `$date`/`$number` become `temporals.*`,
  `numbers.*` or a function, and `$link` an `@{...}` link.
- `$!{x}` and `${x}` both print nothing for `null` in Thwart. A `null` attribute is omitted where
  `value="$!{x}"` wrote `value=""` ([Expressions and attributes](templates.md#expressions-and-attributes)).
- Velocity prints the source of a reference it can't evaluate, keeps the old value on
  `#set($a = $b)` with a null `$b`, and treats only `null` and `false` as false. If the pages depend
  on that, reproduce it on purpose in Java and check it with compare.
- Templates split across `#parse` files often leave tags unbalanced (the header opens a `<div>`
  the footer closes). Each Thwart template is well formed: open and close in one layout.

**Pebble.** `loop.index` is `st.index`, `loop.length` is `st.size`. `{% set %}` is `t:with`,
`{% macro %}` a typed fragment, `{% include %}` a fragment call, and `{% extends %}` with
`{% block %}`s a layout whose `Fragment` parameters are the blocks (an extends chain:
[Fragments and layouts](templates.md#fragments-and-layouts)). Pebble reads public fields, `Map` keys with `.` and methods with arguments; Thwart reads
getters and record components only (`map['key']` works), so add getters or build a record.
Custom filters become `@TemplateFunctions` or values computed in Java. `strictVariables` is what
typed `t:params` already give you.

**Mustache.** JMustache fails at a missing value, cutting the page off mid-render; Thwart prints
nothing for `null`. Render the whole page, and compare against an original that has the value.
