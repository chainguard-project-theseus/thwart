# Security model: threat model and trust boundaries

This document states who Thwart defends against, what it trusts, and where its guarantee
ends. How the guarantee is built is in [security-architecture.md](security-architecture.md), and
the markup rules that enforce it are in [context-model.md](context-model.md). Use this document to
judge a report: whether it is a bug, and which layer owns it.

## 1. The guarantee

Thwart guarantees what the browser's HTML parser and its built-in sinks do with a rendered
page. For every template the compiler accepts and every model:

- each value lands in the node, context and namespace the compiler chose, and the static
  structure is the same for every value (context-model.md §1);
- the only contexts a value can land in are text, an attribute value, a checked URL slot and a
  JSON expression slot;
- so no value runs as a script, an event handler or a `javascript:` URL, and no value makes the
  page load a cross-origin script, frame or base URL;
- a template cannot reach code or state beyond its declared, typed `t:params` (principles 1-4;
  security-architecture.md §5.7).

It does not guarantee what page JavaScript or a client framework does with the DOM afterwards
(principle 7). There is no framework table: the compiler only constrains the shape of its own
output in attribute names outside HTML, a framework-neutral value (context-model.md §6.1,
`NEUTRAL`). Computed text is plain-escaped, so client frameworks that compile the server's DOM as
a template are unsupported. Past that boundary, the defence in depth is a strict CSP: Thwart puts
the request's nonce on every script and style the compiler writes (context-model.md §6.4), and the
starter sends a strict nonce policy for a Thwart page that commits without one, so a policy from
Spring Security or the application wins ([guide](guide/templates.md#rendering)).

The build-time verifier makes the first point hold for every template the compiler accepts:

- relative to the WHATWG parsing algorithm as the pinned parser (validator.nu htmlparser,
  security-architecture.md §11.4) implements it;
- only on parses with no parse errors (the allowed errors of security-architecture.md §11.3
  aside), outside the four coarse exclusions: SVG or MathML above a table part or `template`,
  table parts directly in `<template>`, anything but `option`, `optgroup` and `hr` in a
  `<select>`, and any CDATA section;
- with no value inside SVG or MathML, or in raw text;
- with the supported browsers tested to agree with the parser on that markup, not proved to (the
  Node job compares parse5, Chromium, Firefox ESR and WebKit with the pinned engine after every
  merge). Known deviations are in §7.1;
- within the limits in security-architecture.md §11.3: skeleton fidelity, the content kinds of
  dynamic values, the one parser, the allowed errors, the entry context of partials, and the
  inertness lemma, which is argued, not proved.

Thwart doesn't verify markup that parses only through error recovery (foster parenting,
misnested formatting, a second `<body>`). It rejects it, even where every browser would agree.

## 2. Actors and trust

This is the one trust table; SECURITY.md links here.

| Actor or input | Trust | What Thwart assumes | What enforces it |
|---|---|---|---|
| **Model values** (every `t:params` value, including strings, numbers, collections, nulls and their properties) | Untrusted | The attacker controls all of them, completely, including length, characters and `null` | Escapers chosen by context; URL checks; JSON for scripts |
| **Template authors** | Trusted, but fallible | Authors don't attack their own templates, but they write markup whose meaning they don't know: a quote in an unquoted value, a comment a browser ends early, `<svg><title>` | The build-time verifier; the profile's rules give earlier messages. A template whose meaning the compiler can't pin down is rejected, whoever wrote it |
| **Message bundles** (`messages*.properties`) | Trusted for text, not for markup | Spring may serve a bundle the build never saw (another jar, a database, a changed file) | An HTML message is formatted with its arguments escaped as text and then always sanitized at render time by a fixed policy (`Escapers.htmlMessage` with `Escapers.MESSAGE_POLICY_NO_LINKS`, a `SafeInlineHtml`, or `Escapers.linkedHtmlMessage` with `Escapers.MESSAGE_POLICY`; `HtmlMessageTest`): inline elements and checked links only, whatever the bundle holds. No message is written raw |
| **Application Java code** (controllers, `@TemplateFunction`s, view-safe types) | Trusted | It decides what goes into the model, and what it marks as safe | Principles 2-4: allow-listed functions and properties, no reflection (security-architecture.md §5.7) |
| **A hand-written `Template`** | Its own code | It writes what its Java writes, like any other response code; nothing at build time vouches for it, and no API wraps its output as `SafeHtml` or `Fragment` (`FragmentFromJavaTest`) | Nothing at render time. `t:utext` and `[(...)]` accept only `SafeHtml`; no type skips the URL checks |
| **Other `SafeHtml` sources** (`escape`, `sanitize`, `concat`) | Trusted by construction | `HtmlSanitizer` copies no input through, so its output is safe however a browser parsed the original. | The verifier: `SafeHtml` is inserted only where the parser state meets its kind's predicate, and producers are checked to emit balanced content (`FlowProducerTest`). |
| **The build** (javac, the annotation processor, the compiler's dependencies) | Trusted | Template sources and bundles come from the application's repository. The shaded validator.nu htmlparser is pinned by release and SHA-256 (`thwart-html5/pom.xml`) | Out of scope |
| **Browsers** | The supported set | Chromium (current), Firefox (current), Firefox ESR and Safari/WebKit (current) (context-model.md §7, decision 7). Safari is checked through Playwright's WebKit build (the WPE port: WebKit's HTML parser and script engine, not Safari's shell). A rule must hold in all of them; where they differ, the verifier excludes the construct (security-architecture.md §11.4: classic and customizable `<select>`, SVG/MathML in tables, table parts in `<template>`, CDATA) | At build time, the verifier runs the pinned htmlparser and accepts only error-free parses outside the exclusions. In tests, parse5, headless Chromium, Firefox 140 ESR and WebKit in the Node job (on Chainguard Checks after every merge and on request) |
| **Page JavaScript and client frameworks** | Outside (principle 7) | Code that re-reads the DOM as code (Vue in-DOM templates, Alpine, htmx) can turn safe output into code execution. AngularJS is not supported ([guide](guide/limitations.md)) | Framework-neutral values in every non-HTML attribute (`NeutralValuesTest`); frameworks that compile the server's DOM are unsupported (`ClientTemplateInjectionTest`); strict CSP with nonces (`CspNonceTest`: the nonce on compiler-written scripts; `CspFilterTest`, `CspTest`: the starter's default policy header, never replacing one already set) |
| **The request** (headers, URL, session) | Untrusted | It reaches a template only through model values or `RenderContext` | As model values; CSRF tokens only on same-origin POST forms |
| **The CSP nonce** (`RenderContext.cspNonce`) | Trusted, but escaped | The application (or the starter, from `SecureRandom`) supplies a fresh, unguessable value per response, used in the policy | Written only on compiler-written `<script>` and `<style>` start tags a browser reads as HTML (not in SVG, MathML or raw text), with `Escapers.attr`; a nonce in the template is a build error; the starter's default policy header takes only a base64 nonce |

## 3. Trust boundaries

Each row is a point where data crosses from one trust level to another, and the check at that
point.

| Boundary | Crossing | Check | Where |
|---|---|---|---|
| Template source to compiled class | Author markup becomes a fixed tree and a set of holes | The template parses without errors and outside the exclusions, and each hole's escaper output is inert in the state the spec parser reaches there (security-architecture.md §11); the profile's early errors; expression resolution against the view-safe allow-list ([security-architecture.md §5.7](security-architecture.md#57-expressions)) | The verifier, `MarkupTokenizer`, `HtmlParser`, `Contexts`, `ExpressionParser`, `ExpressionCompiler` |
| Model value to output | An untrusted value is written into a hole | The escaper for the hole's context, chosen at build time | `Escapers` (`text`, `attr`, `url`, `resourceUrl`, `refresh`, `json`, `javaScript`) |
| Model value to URL | An untrusted value becomes a link | Scheme allow-list (`http`, `https`, `mailto`, `tel`, relative), only on attributes whose grammar is exactly one URL; the URL lists `ping`, `srcset`, `imagesrcset` can't be computed (`FORBIDDEN`) | `Escapers.url`; `AttributesEndToEndTest` |
| Model value to resource URL | An untrusted value picks a script, frame, stylesheet or object | A same-origin literal-path `@{...}` link with no query parameters; anything else, a scheme or `//` origin included, is a build error, and another origin is a static attribute. Data reaches the URL only through path variables, which are percent-encoded (an empty one fails the render). A path starting with `/` is re-checked as same-origin when rendered, so a context path or custom `Links` cannot make it protocol-relative | `Escapers.sameOriginLink`, `Escapers.resourceUrl`; `LinkCompilerTest`, `AttributesEndToEndTest` |
| Model value to form action, `formaction`, `<base href>` or refresh URL | An untrusted value picks where a form submits, what relative URLs resolve against or where a page redirects | A same-origin link, or a choice whose every branch is one (context-model.md §6.2), re-checked as same-origin when rendered | `Checks.isSameOriginUrl`, `Escapers.refresh`; `FormActionTest`, `CsrfFormactionTest`, `CsrfBaseTest`, `SameOriginLinkRenderTest` |
| Model value to script | An untrusted value is written into a `<script>` | A complete JSON value in parentheses, only as the value of a declaration at the top of a JavaScript script, or a JSON value in a JSON script (context-model.md §4) | `Escapers.javaScript`, `Escapers.json`, `TextInlining` |
| Fragment, `SafeHtml` or message to an insertion point | Markup compiled for one context is placed in another | Static fragments: checked at each call site. Dynamic values: the insertion state meets the kind's predicate (context-model.md §5) | The verifier; `ContentKindsTest`, `FlowProducerTest` |
| Rendered page to browser parser | The browser parses the page | The invariant: each hole is in the node, context and namespace the compiler chose, and the benign and hostile trees are equal | The verifier at build time; `SeedVerifierTest`, `HostileSweepTest` and the Node job test it |
| Browser DOM to client framework | Page JavaScript reads attributes and text as code | Framework-neutral values outside HTML attributes; CSP | Beyond the guarantee except as bounded here |
| Rendered page to the CSP | The browser decides which scripts and styles run | The nonce is on exactly the start tags the compiler writes, never on `SafeHtml` content (context-model.md §6.4) | `TemplateGenerator.startTag`; `CspNonceTest` |

## 4. What the attacker can and cannot do

| The attacker can | The attacker cannot |
|---|---|
| Choose every model value: any string, including markup, quotes, `{{`, `javascript:`, `//evil.example`, `\evil.example`, U+2028 and NUL | Change template source, bundles or compiled classes |
| Choose values for every hole in one render, so values can combine | Choose which escaper a hole uses: the compiler fixes it at build time |
| Choose collection sizes and `null`s, which change which `t:each`, `t:if`, `t:insert` and `t:replace` branches render | Reach a class, method or property that isn't on the allow-list (principles 2-4) |
| Pick the browser, among the supported set | Supply template source at runtime: the runtime has no template parser (principle 1) |
| Rely on client frameworks the page loads | |

The hostile value set used in tests is `HostileStrings.VECTORS` plus
`ProfileOracleTest.SEED_VALUES` (`PWN()`, `"+PWN()+"`, `\nPWN()//`, ` onerror=PWN() `,
`{{PWN()}}`, `javascript:PWN()`, `\evil.example`, `/evil.example`, `//evil.example`,
`img src=x onerror=PWN()`).

## 5. In scope and out of scope

Families A-E are the threat families of
[security-architecture.md §2](security-architecture.md#2-threat-families): A parser
differentials, B composition, C JavaScript position, D client frameworks, E URL and policy.

| Class | In scope? | Notes |
|---|---|---|
| Server-side template injection, code execution | Yes | Closed by principles 1-4 and the expression rules (security-architecture.md §5.7). No reported case |
| XSS through a parser differential (the compiler's tree is not the browser's) | Yes | Family A; closed by the build-time verifier, relative to the pinned parser, on error-free parses outside the exclusions (§1); the profile (context-model.md §2-§3) gives earlier errors |
| XSS through composition (fragments, `SafeHtml`, messages in the wrong context) | Yes | Family B; static composition by the verifier at each call site, dynamic values by content-kind predicates (context-model.md §5) |
| XSS through a script hole in the wrong lexical position | Yes | Family C; script prelude rule (context-model.md §4) |
| A URL or resource from another origin | Yes | Family E; URL classes (context-model.md §6.1) |
| Client-side template injection through a computed value | Outside | Family D. Framework-neutral values in every non-HTML attribute (`NeutralValuesTest`); text is plain-escaped and frameworks that compile the server's DOM are unsupported (`ClientTemplateInjectionTest`); a strict CSP is the backstop. A computed non-HTML attribute value that isn't neutral is in scope. See [guide](guide/limitations.md) |
| What a client framework does with the author's static markup (a static directive, Vue's `:is`, custom delimiters) | No | Outside the guarantee (principle 7). A strict CSP without `'unsafe-eval'` and with `connect-src 'self'` limits the damage ([guide](guide/templates.md#rendering)) |
| What page JavaScript does with values it reads from the DOM | No | Principle 7 |
| A hand-written `Template`'s own output | No | The Java that writes it owns it, like any other response code |
| Views Thwart doesn't render (Thymeleaf or JSP still configured during a migration) | No | They get none of these guarantees; the Spring starter logs a warning at startup while that is the case |
| CSS injection | Yes, by restriction | No values in `<style>`; a `style` attribute takes only literals, numbers and `CssValue`; nothing in SVG is computed |
| Denial of service, timing, clickjacking, response headers | No | Application and server concerns |
| Browsers outside the supported set | No | A difference there is a request to widen the supported set, not a bug |
| A compromised build (template sources, bundles, compiler, dependencies) | No | Trusted (§2) |
| Tooling inputs (`thwart-migrate`, the benchmarks, the example apps) | No | Developer tools and samples, not production components |

## 6. Severity

Severity is about the compiler, not the author.

- If the compiler accepts a template and a supported browser parses its render differently
  from the compiler's tree, that's a bug, however contrived the markup.
- For a markup context (families A and B), add the template as a seed the verifier must
  reject, and fix the verifier or its rule table if it accepted it (security-architecture.md
  §11). Otherwise, ask which profile rule admits the template, narrow that rule and add a seed or
  test that reproduces it (context-model.md). Never add a special case for the
  reported template.
- If the parser and a supported browser build different trees, add a coarse exclusion that
  covers the class, with a test (security-architecture.md §11.4). Never model the browser's
  algorithm, and never patch the parser.
- If a rule has to guess (regex or division, which namespace), the guess leads to a build error.
- A rejected template that a spec parser shows would have been safe is a false positive. Review
  it, but it isn't a security bug.

## 7. Assumptions

| Assumption | Why it matters |
|---|---|
| The pinned parser reads error-free markup outside the exclusions as the supported browsers do | The verifier's guarantee is relative to it. The exclusions cover the known splits (§7.1); the Node job compares it with parse5, Chromium, Firefox ESR and WebKit |
| Escaper output is inert in the states the verifier accepts (the inertness lemma) | Argued in security-architecture.md §11.2; tested by the hostile sweep and `EscaperFuzzTest`; not proved |
| The generated code calls the escaper the skeleton records | Both come from the single emission path; `ProfileOracleTest` checks renders against the skeleton |
| `FLOW` producers emit balanced content without parse errors | `FlowProducerTest`, `SanitizerBalanceTest` |
| Scripting may be on or off | The verifier checks both readings of a template that mentions `<noscript>` |

### 7.1 Known parser deviations

Places where the pinned parser and a supported browser (or parse5, which the tests use) build
different trees. Each one is handled by a coarse exclusion or is outside what the verifier
accepts. None is modelled.

| Deviation | Who does what | Handling |
|---|---|---|
| "Reset the insertion mode" stops at an SVG or MathML element and picks "in body"; the spec walks on to the `td`, `th`, `caption`, table part or `template` | validator.nu and Firefox (generated from the same Java) stop; the spec, parse5, Chromium and WebKit walk on. Not fixed upstream as of validator/htmlparser f5c4147 (Sep 2026) | exclusion (a): no SVG/MathML above a table part or `template` (`FOREIGN_IN_TABLE`; seeds) |
| `</table>` in a table mode inside `template` contents uses `findLast("table")`, not table scope, and closes an outer table | validator.nu and Firefox close it; the spec, parse5, Chromium and WebKit ignore the end tag | exclusion (b): no table part directly in `template` (`TABLE_IN_TEMPLATE`; seeds); the repros also report parse errors |
| Classic and customizable `<select>` parsing | Firefox ESR and parse5 drop elements other than `option`, `optgroup` and `hr`; the pinned parser, Chromium, current Firefox and WebKit keep them | exclusion (c), on composed output (`SELECT_CONTENT`; seeds) |
| parse5: the end tag of an SVG/MathML integration point while an HTML formatting element is open (`<div><svg><desc><b></desc></svg>X`) | parse5 differs from validator.nu and all three browsers | a parse5 bug, not a browser one. The Node job reports parse5-only drift without failing. The error check rejects it (`ErrorFreeTest`) |

## 8. Proof table

Each context a computed value can reach, what makes it safe, and the test that pins it. Anything
not in this table is a build error (context-model.md §9; the [guide's limitations](guide/limitations.md) table gives each fix).
`ProofTableTest` checks that every test named here exists.

| Accepted context | Escaper or rule | Pinned by |
|---|---|---|
| Element text, `t:text`, `[[...]]` | `Escapers.text`; the one text choke point | `EscapersTest`, `TextChokePointTest`, `HostileSweepTest` |
| Attribute value on the closed list (`Contexts.COMPUTED`, `aria-*`) | `Escapers.attr` in a quoted value; any other computed name is `FORBIDDEN` | `ContextsTest`, `InertAttributesTest`, `AttributesEndToEndTest` |
| Typed attribute (`target`, some `value` and `for`) | Literals, numbers or a choice of literals, checked at build time | `ContextsTest`, `AttributesEndToEndTest` |
| `data-*` and non-WAI-ARIA `aria-*` | Neutral values only: a number, a boolean or a literal-path same-origin link | `NeutralValuesTest`, `ClientTemplateInjectionTest` |
| URL (`href`, `src`, `action`, `formaction`, `cite`, `poster`) | `Escapers.url`: `http`, `https`, `mailto`, `tel` or relative, else `about:invalid#thwart` | `EscapersTest`, `AttributesEndToEndTest` |
| Resource URL (`script[src]`, `iframe[src]`, `link[href]`, ...) | A same-origin literal-path `@{...}` with no query (`Escapers.sameOriginLink`, `Escapers.resourceUrl`) | `LinkCompilerTest`, `SameOriginLinkRenderTest` |
| Form action, `formaction`, `<base href>`, refresh URL | Same-origin link re-checked at render (`Checks.isSameOriginUrl`, `Escapers.refresh`) | `FormActionTest`, `CsrfFormactionTest`, `CsrfBaseTest` |
| CSRF hidden input | Added only to POST forms that provably post to the page's origin | `CsrfActionOriginTest`, `CsrfOwnInputTest` |
| JavaScript `<script>` | A JSON value in parentheses as a top-of-script declaration (`Escapers.javaScript`) | `ScriptPreludeTest`, `EscaperFuzzTest` |
| JSON `<script>` | `Escapers.json` | `ScriptJsonTest` |
| `style` attribute | Literals, numbers and `CssValue`; nothing in `<style>` | `StyleAttributeTest`, `CssValueTest` |
| Raw HTML (`t:utext`, `[(...)]`) | `SafeHtml` (flow) or `SafeInlineHtml` (phrasing), inserted only where the parser state meets its kind; producers emit balanced content | `ContentKindsTest`, `FlowProducerTest`, `SanitizerBalanceTest` |
| HTML message | Arguments escaped, then sanitized at render by a fixed policy | `HtmlMessageTest` |
| CSP nonce | On compiler-written `<script>`/`<style>` only; the starter's default policy | `CspNonceTest`, `CspFilterTest`, `CspTest` |
| Every hole, structurally | The verifier: benign and hostile trees equal, error-free parse, every exit state | `SeedVerifierTest`, `ExitStateTest`, `ErrorFreeTest` |
| Expressions | Allow-listed properties and functions, no runtime evaluator | `SstiPayloadTest` |

Static author markup holds no untrusted data. X1's static rule rejects `on*` handlers and
`javascript:` in HTML URL attributes (`AttributesEndToEndTest`); the default CSP covers the rest.

## 9. Known gaps

These, and only these, are known ways the guarantee falls short:

1. **Client frameworks over server DOM.** Code that compiles or evaluates the rendered DOM can turn
   safe output into script. Out of scope (§2, principle 7); the strict CSP mitigates it.
2. **Same-origin `img` GETs from sanitized HTML.** A policy that keeps `img` lets user content make
   the browser send a same-origin GET with the user's cookies. Don't serve state-changing GETs.
3. **Tier1's parser oracle shares the verifier's engine.** `SeedVerifierTest` and
   `HostileSweepTest` parse with the same pinned htmlparser the verifier uses; the Node postsubmit
   (parse5, Chromium, Firefox ESR, WebKit) is the independent check.
4. **DOM clobbering through computed `id`, `name` and `form`.** These take any value
   ([guide](guide/templates.md#output-contexts)), so a model value can name an element after a
   page global (`<img t:name>` or `<form t:name>` as `cookie` shadows `document.cookie`; any `t:id` adds a `window` property) or move a
   control into another form on the page with `t:form`. No script runs, but page JavaScript that
   reads those globals sees the value (principle 7). Real templates compute all three (form and
   row ids, `t:form` on inputs outside their form), and every attacker string is a valid name, so
   the compiler doesn't restrict them: compute them from the application's own keys or a prefixed
   id, not raw user input. `HtmlPolicy` prefixes ids in sanitized HTML (`user-content-`).
