# Security model

Tessera is a template engine for Java web applications. It is built to remove whole classes of
template vulnerability by design, not to patch them one at a time. This page summarises the threat
model: what Tessera trusts, what it defends against, where its guarantees stop, and what is out of
scope. The full version is in [docs/security-model.md](docs/security-model.md) (threat model and
trust boundaries) and [docs/context-model.md](docs/context-model.md) (the HTML profile the compiler
accepts, rule by rule).

## Reporting a vulnerability

Don't open a public issue. Follow the wolfi-dev
[security policy](https://github.com/wolfi-dev/.github/blob/main/SECURITY.md).

## Trusted and untrusted

Tessera draws the line between **build time** and **render time**.

| Input | Trust | Why |
|---|---|---|
| Template source | Trusted, but fallible | Templates are code. They come from the application's repository and are compiled by the application's `javac`. Tessera assumes authors don't attack their own templates. It doesn't assume they understand every corner of HTML parsing, so it rejects any template whose meaning it can't pin down. |
| Message bundles (`messages*.properties`) | Trusted at build time | The compiler checks the HTML messages it sees. At render time, a message is written as HTML only if it matches a pattern checked at build time; otherwise it is escaped as text. |
| Application Java code: controllers, `@TemplateFunction`s, model types | Trusted | It decides what goes into the model and what it marks as safe. |
| The build: javac, the annotation processor, Tessera's pinned dependencies | Trusted | Out of scope. |
| **Model values**: every `t:params` value and its properties | **Untrusted** | Assume an attacker controls every value completely: content, length, characters, `null`, collection sizes, and which branches render as a result. |
| **The request**: URL, headers, form fields, session | **Untrusted** | It reaches a template only as model values. |

## Bug classes removed by design

- **Server-side template injection and code execution.** Templates compile to plain Java at build
  time. The runtime has no template parser, no API that accepts template source, and no expression
  evaluator: no SpEL, OGNL, `ScriptEngine` or reflection. Nothing at render time can turn data into
  template code, so there is no equivalent of Thymeleaf's `__${...}__` preprocessing, and no
  sandbox to bypass.
- **Ambient authority.** A template sees only its declared, typed `t:params`. It can't name classes,
  call `new`, read statics, or reach the request, session or Spring beans. Property access is checked
  at compile time against an allow-list of view-safe types. Functions exist only if registered with
  `@TemplateFunction`. `Class`, `ClassLoader`, reflection, `Thread`, `Runtime`, `ProcessBuilder`
  and servlet or Spring context types can never be reached, even if registered. Getting any of this
  wrong is a build error, not a runtime failure.
- **Forgotten or wrong escaping (XSS).** The compiler works out the context of every output position
  and picks the escaper from it. Template authors don't choose escapers, so they can't forget one.

## Contextual escaping: what is guaranteed

For every template the compiler accepts and every model, Tessera guarantees what a supported
browser's HTML parser and built-in sinks do with the rendered page:

- Each value lands in the node, context and namespace the compiler chose at build time, and the page's
  static structure is the same whatever the values are.
- A value can land only in text, an attribute value, a checked URL slot or a JSON expression slot. It
  never runs as a script, an event handler or a `javascript:` URL, and never makes the page load a
  script, frame or base URL from another origin.
- Markup outside a closed profile of HTML is a build error. At build time, a pinned spec HTML
  parser (validator.nu htmlparser) checks every template. It accepts only parses without errors,
  outside a few constructs where browsers are known to disagree. The supported browsers are current
  Chromium, Firefox, Firefox ESR and WebKit. They are checked against the parser by tests, not
  proven to agree with it.

A value in a JavaScript `<script>` is only the whole value of a declaration at the top of the
script. `<style>` can't contain holes. A `style` attribute takes only literals, numbers and
`CssValue`. The request's CSP nonce goes on every script and style that the compiler writes.

## Where the guarantees end: escape hatches

Raw output always needs a typed value built in Java, never a flag on a string:

- **`SafeHtml`** for `t:utext` and `[(...)]`. Values from `SafeHtml.escape`, `sanitize`, or a
  compiled `Template` that the compiler proved safe to insert are trusted by construction.
- **`TrustedUrl`** skips the URL scheme and origin checks.
- **`Fragment`**, or a hand-written `Fragment` lambda or `Template`.

**`SafeHtml.unchecked`** and **`TrustedUrl.unchecked`** vouch for a string with no check at all.
Whatever an `unchecked` call lets through is the caller's responsibility, not a Tessera bug. Every
call site should be reviewed. `mvn tessera:review` lists every `unchecked` call and every registered
`@TemplateFunction`, and `failOnUnchecked` turns an `unchecked` call into a build error.

## Out of scope

- **Client-side code** that re-reads the DOM as code: Vue in-DOM templates, Alpine, AngularJS, htmx
  and similar. Tessera models no client-side framework. It only shapes its own output: outside
  standard HTML attributes a computed value is framework-neutral (a number, a boolean or a
  same-origin path), and computed text never contains `{{` (a computed `<title>` or `<textarea>`
  gets `v-pre` instead). A computed value that breaks either rule is in scope; anything else
  client code evaluates isn't ([the guide](docs/guide.md), section 5). Send a strict CSP without
  `'unsafe-eval'` and with `connect-src 'self'` as defence in depth.
- **What page JavaScript does** with values it reads from the DOM.
- **Views Tessera doesn't render.** While another engine such as Thymeleaf or JSP is still
  configured during a migration, its views get none of these guarantees. The Spring starter logs a
  warning at startup while that is the case.
- **Browsers outside the supported set.** A difference in another browser is a request to widen the
  set, not a vulnerability.
- **Denial of service, timing, clickjacking and response headers.** These belong to the application
  and the server, not to the template engine.
- **A compromised build**: the template sources, message bundles, compiler or dependencies.
- **Tooling inputs.** `tessera-migrate`, the benchmarks and the example apps are developer tools and
  samples, not production components.

## What counts as a Tessera vulnerability

Severity is judged against the compiler, not the template author. If the compiler accepts a template
and a supported browser parses its rendered output differently from the compiler's model, that is
a bug, however contrived the markup. So is any way for a model value to reach code, or to leave the
context the compiler chose. A template the compiler rejects although it would have been safe is a
false positive, not a vulnerability. Report it as an ordinary issue.
