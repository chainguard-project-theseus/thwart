# Security model: threat model and trust boundaries

This document states who Tessera defends against, what it trusts, and where its guarantee
ends. It is the threat model from §7 of
[security-architecture.md](security-architecture.md). The markup rules that enforce it are in
[context-model.md](context-model.md). Use this document to judge a report: whether it is a bug,
and which layer owns it.

## 1. The guarantee

Tessera guarantees what the browser's HTML parser and its built-in sinks do with a rendered
page. For every template the compiler accepts and every model:

- each hole's value lands in the node, context and namespace the compiler chose, and the static
  structure is the same for every value (context-model.md §1);
- the only contexts a value can land in are text, an attribute value, a checked URL slot and a
  JSON expression slot;
- so no value runs as a script, an event handler or a `javascript:` URL, and no value makes the
  page load a cross-origin script, frame or base URL;
- a template cannot reach code or state beyond its declared, typed `t:params` (principles 1-4).

It does not guarantee what page JavaScript or a client framework does with the DOM afterwards
(principle 7). There is no framework table (#584): the compiler only constrains the shape of its
own output where client code commonly reads it, a framework-neutral value in every non-HTML
attribute (context-model.md §6.1, `NEUTRAL`) and `{{` broken in text (§6.3). Past that boundary,
the defence in depth is a strict CSP: Tessera puts the request's nonce on every script and style
the compiler writes (context-model.md §6.4), and the application sends the policy (guide §4.11).

The guarantee holds for the profile as context-model.md specifies it. Rows marked `phase 2` or
with an open PR there are gaps on `main` today. The build-time verifier (#367) makes the first
point hold for every template the compiler accepts, whatever the profile's rules say. Precisely,
once #406 lands (until then `main` checks error-recovery markup by modelling it, with the known
gaps #398 and #401; security-architecture.md §11):

- It holds relative to the WHATWG parsing algorithm as the pinned parser (validator.nu
  htmlparser, security-architecture.md §11.4) implements it.
- It holds only on parses with no parse errors (missing-doctype aside), outside the coarse
  exclusions: SVG or MathML above a table part, table parts directly in `<template>`,
  anything but `option`, `optgroup` and `hr` in a `<select>` (#406), and a CDATA section in an
  integration point (#441).
- On that markup the supported browsers are tested to agree with the parser, not proved to.
  The nightly detector (#399, not yet on `main`) is to compare the parser's trees with parse5,
  Chromium, Firefox and WebKit on the cases it exports; until then the Node job compares renders
  across browsers only. Known deviations are in §7.1.
- It holds within the limits in security-architecture.md §11.3: skeleton fidelity, dynamic
  `SafeHtml` and `Fragment` kinds, the one parser, the allow-list, the entry context of partials,
  and the inertness lemma, which is argued, not proved.

Once #406 lands, Tessera doesn't verify markup that parses only through error recovery (foster
parenting, misnested formatting, a second `<body>`). It rejects it, even where every browser would
agree.

## 2. Actors and trust

| Actor or input | Trust | What Tessera assumes | What enforces it |
|---|---|---|---|
| **Model values** (every `t:params` value, including strings, numbers, collections, nulls and their properties) | Untrusted | The attacker controls all of them, completely, including length, characters and `null` | Escapers chosen by context; URL checks; JSON for scripts |
| **Template authors** | Trusted, but fallible | Authors don't attack their own templates, but they write markup whose meaning they don't know: a quote in an unquoted value, a comment a browser ends early, `<svg><title>` | The build-time verifier (#367); the profile's rules give earlier messages. A template whose meaning the compiler can't pin down is rejected, whoever wrote it |
| **Message bundles** (`messages*.properties`) | Trusted at build time | The bundles the compiler sees are the ones that ship | HTML messages are checked at build time (`MessageCompiler`). At render time, `Escapers.htmlMessage` returns HTML only if the message is exactly what a checked pattern produces; it never classifies a message as safe by inspecting it, and anything else is formatted with its arguments as plain text and escaped once as text, `{{` broken |
| **Application Java code** (controllers, `@TemplateFunction`s, view-safe types) | Trusted | It decides what goes into the model, and what it marks as safe | Principles 2-4: allow-listed functions and properties, no reflection |
| **`SafeHtml.unchecked`, `TrustedUrl.unchecked`** (and a hand-written `Template`; from Java, `Fragment.unverifiedForGeneratedCode` and a generated class's `internalUnverified$<name>` methods, #581, #600) | Review points | The caller vouches for the content. Every call site is a security review point; the names are greppable. `Fragment` is final, so a lambda or subclass isn't one, but `Fragment.unverifiedForGeneratedCode(body)` and `internalUnverified$<name>` are public for generated code in other packages and check nothing: outside generated code, each call is a review point (context-model.md §5). `tessera:review` lists every `unchecked` call site and every registered `@TemplateFunction`, marking those that return a safe type; `failOnUnchecked` turns an `unchecked` call into a build error (#428) | Nothing at render time. `t:utext` and `[(...)]` accept only `SafeHtml`; a `TrustedUrl` skips the URL checks |
| **Other `SafeHtml` sources** (`escape`, `sanitize`, a rendered `Template`, `concat`) | Trusted by construction | `HtmlSanitizer` copies no input through, so its output is safe however a browser parsed the original. A rendered template is accepted only if the compiler proved it a `FLOW` partial | The verifier (#367, #329): `SafeHtml` is inserted only where the parser state meets its kind's predicate, and producers are checked to emit balanced content (`FlowProducerTest`). `SafeHtml.of` and `Fragment.of` refuse a template whose generated class doesn't carry the verifier's `FLOW` mark: a page, a list-item or table-part partial, one with a `form` or nested formatting elements of one name (#396, context-model.md §5) |
| **The build** (javac, the annotation processor, the compiler's dependencies) | Trusted | Template sources and bundles come from the application's repository. The shaded validator.nu htmlparser is pinned by release and SHA-256 (#365) | Out of scope |
| **Browsers** | The supported set | Chromium (current), Firefox (current), Firefox ESR and Safari/WebKit (current) (context-model.md §7, decision 7). Safari is checked through Playwright's WebKit build (the WPE port: WebKit's HTML parser and script engine, not Safari's shell) (#354). A rule must hold in all of them; where they differ, the verifier excludes the construct (security-architecture.md §11.4: classic and customizable `<select>`, SVG/MathML in tables, table parts in `<template>`) | At build time, the verifier runs the pinned current htmlparser (#367); from #406 it accepts only error-free parses outside the exclusions. In tests, validator 26.10.12 (#344); parse5, headless Chromium, Firefox 140 ESR and WebKit in the Node job (#345, #354; on Chainguard Checks after every merge and on request, over a fixture until #324) |
| **Page JavaScript and client frameworks** | Outside (principle 7) | Code that re-reads the DOM as code (Vue in-DOM templates, Alpine, htmx) can turn safe output into code execution. AngularJS is not supported (guide §7); only the coarse strip of `ng`-like class tokens remains (#586) | Framework-neutral values in every non-HTML attribute (#584), `{{` breaking, strict CSP with nonces (#335: the nonce on compiler-written scripts; the policy header is the application's) |
| **The request** (headers, URL, session) | Untrusted | It reaches a template only through model values or `RenderContext` | As model values; CSRF tokens only on same-origin POST forms |
| **The CSP nonce** (`RenderContext.cspNonce`) | Trusted, but escaped | The application (or the starter, from `SecureRandom`) supplies a fresh, unguessable value per response and the same value in its policy | Written only on compiler-written `<script>` and `<style>` start tags a browser reads as HTML (not in SVG, MathML or raw text), with `Escapers.attr`; a nonce in the template is a build error |

## 3. Trust boundaries

Each row is a point where data crosses from one trust level to another, and the check at that
point.

| Boundary | Crossing | Check | Where |
|---|---|---|---|
| Template source to compiled class | Author markup becomes a fixed tree and a set of holes | The template parses without errors and outside the exclusions, and each hole's escaper output is inert in the state the spec parser reaches there (security-architecture.md §11); the profile's early errors; expression resolution against the view-safe allow-list | The verifier (#367), `MarkupTokenizer`, `HtmlParser`, `Contexts`, the expression compiler |
| Model value to output | An untrusted value is written into a hole | The escaper for the hole's context, chosen at build time | `Escapers` (`text`, `rcdata`, `attr`, `url`, `resourceUrl`, `refresh`, `json`, `jsonAttr`) |
| Model value to URL | An untrusted value becomes a link or a resource | Scheme allow-list for `URL`, which is only the attributes whose grammar is exactly one URL; literals, numbers and `TrustedUrl` only (`TRUSTED_URL`) for the URL lists `ping`, `srcset`, `imagesrcset` and an SVG animation's `values to from by`, whose meaning depends on `attributeName` (#539); literal-path link or `TrustedUrl` for `RESOURCE_URL` (the author's literal fixes the origin; `/\` rejected for every link, #340); same-origin path for htmx request and refresh URLs and every `formaction`; for the action of any form a same-origin link or a `TrustedUrl` (a static POST form: link only), context-model.md §6.2 | `Escapers.url`; same-origin and CSRF: `Escapers.isSameOriginUrl` |
| Model value to script | An untrusted value is written into a `<script>` | A complete JSON value in parentheses, only as the value of a declaration at the top of a JavaScript script, or a JSON value in a JSON script (context-model.md §4) | `Escapers.javaScript`, `Escapers.json`, `TextInlining` |
| Fragment, `SafeHtml` or message to an insertion point | Markup compiled for one context is placed in another | Static fragments: checked at each call site. Dynamic values: the insertion state meets the kind's predicate (context-model.md §5) | The verifier (#367); #329 |
| `unchecked` call to output | Application code asserts that a string is safe | Code review | Call sites of `SafeHtml.unchecked`, `TrustedUrl.unchecked` |
| Rendered page to browser parser | The browser parses the page | The invariant: each hole is in the node, context and namespace the compiler chose, and the benign and hostile trees are equal | The verifier at build time (#367); the oracle gate (#324) tests the verifier |
| Browser DOM to client framework | Page JavaScript reads attributes and text as code | Framework table entries; `{{` breaking; CSP | Beyond the guarantee except as bounded here |
| Rendered page to the application's CSP | The browser decides which scripts and styles run | The nonce is on exactly the start tags the compiler writes, never on `SafeHtml` content (context-model.md §6.4) | `TemplateGenerator.startTag`; `CspNonceTest` |

## 4. What the attacker can and cannot do

| The attacker can | The attacker cannot |
|---|---|
| Choose every model value: any string, including markup, quotes, `{{`, `javascript:`, `//evil.example`, `\evil.example`, U+2028 and NUL | Change template source, bundles or compiled classes |
| Choose values for every hole in one render, so values can combine | Choose which escaper a hole uses: the compiler fixes it at build time |
| Choose collection sizes and `null`s, which change which `t:each`, `t:if`, `t:insert` and `t:replace` branches render | Reach a class, method or property that isn't on the allow-list (principles 2-4) |
| Pick the browser, among the supported set | Supply template source at runtime: the runtime has no template parser (principle 1) |
| Rely on client frameworks the page loads | |

The hostile value set used in tests is `HostileStrings.VECTORS` plus the #320 values (`PWN()`,
`"+PWN()+"`, `\nPWN()//`, ` onerror=PWN() `, `{{PWN()}}`, `javascript:PWN()`, `\evil.example`,
`/evil.example`, `//evil.example`, `img src=x onerror=PWN()`).

## 5. In scope and out of scope

| Class | In scope? | Notes |
|---|---|---|
| Server-side template injection, code execution | Yes | Closed by principles 1-4. No reported case |
| XSS through a parser differential (the compiler's tree is not the browser's) | Yes | Family A; closed by the build-time verifier (#367), relative to the pinned parser, on error-free parses outside the exclusions (§1); the profile (context-model.md §2-§3) gives earlier errors |
| XSS through composition (fragments, `SafeHtml`, messages in the wrong context) | Yes | Family B; once #367 and #329 land, static composition by the verifier at each call site, dynamic values by content-kind predicates (context-model.md §5) |
| XSS through a script hole in the wrong lexical position | Yes | Family C; script prelude rule (context-model.md §4) |
| A URL or resource from another origin | Yes | Family E; URL classes (context-model.md §6.1) |
| Client-side template injection through a computed value | Bounded | Family D. Framework-neutral values in every non-HTML attribute (#584) and `{{` breaking; where `{{` can't be broken (`<title>`, `<textarea>`), `v-pre` for Vue 3 and petite-vue (#549). Gaps are listed in guide §7 |
| What a client framework does with the author's static markup (a static directive, Vue's `:is`, custom delimiters) | No | Outside the guarantee (principle 7). A strict CSP without `'unsafe-eval'` limits the damage (#335, guide §4.11) |
| What page JavaScript does with values it reads from the DOM | No | Principle 7 |
| Content an `unchecked` call vouched for | No | The call site owns it |
| CSS injection | Yes, by restriction | No holes in `<style>`; a `style` attribute, and an SVG attribute that takes a CSS `url()` (`fill`, `stroke`, `mask`, ...) or an animation of one, takes only literals, numbers and `CssValue` |
| Denial of service, timing, clickjacking, response headers | No | Application and server concerns |
| Browsers outside the supported set | No | A difference there is a request to widen the supported set, not a bug |

## 6. Severity

Severity is about the compiler, not the author.

- If the compiler accepts a template and a supported browser parses its render differently
  from the compiler's tree, that's a bug, however contrived the markup.
- For a markup context (families A and B), add the template as a seed the verifier must
  reject, and fix the verifier or its rule table if it accepted it (security-architecture.md
  §11). Otherwise, ask which profile rule admits the template, narrow that rule and add a seed or
  generator value that reproduces it (context-model.md). Never add a special case for the
  reported template.
- If the parser and a supported browser build different trees, add a coarse exclusion that
  covers the class, with a test (security-architecture.md §11.4). Never model the browser's
  algorithm, and never patch the parser.
- If a rule has to guess (regex or division, which namespace), the guess leads to a build error.
- A rejected template that a spec parser shows would have been safe is a false positive. Review
  it, but it isn't a security bug.

## 7. Assumptions the proofs make

The Lean model of T1 and the OpenJML proofs (of the rule functions and the escapers) are not
published in this repository.
Where they are cited in context-model.md, they rely on these assumptions, which are part of this
threat model:

| Assumption | Why it matters |
|---|---|
| Browsers follow the WHATWG tokenizer for the states the profile reaches | Lean checks its tokenizer model against the html5lib tokenizer tests and the WPT tree tests; real browsers are checked only by the PRs' manual runs and the Node job (#345), which runs over a fixture until #324 |
| The escapers meet their postconditions (`htmlTextSafe`, `htmlSafe`, `jsonScriptSafe`) | Proved for strings (unpublished); JSON numbers, booleans and structures are assumed |
| The generated code calls the escaper the context table names | Tested by a generated-code checker (unpublished), not proved (G7) |
| Scripting is enabled, for the `noscript` proof | With scripting disabled, `noscript` content is markup; the profile's rules are argued safe for both readings, but only one is proved |
| Tree construction (implied end tags, nested `form`) doesn't move a hole into another context | Not modeled in Lean; checked per template by the verifier (#367). Error recovery (foster parenting, reconstruction, misnesting) is rejected, not checked (#406). Watched by the oracle (#344) |
| The pinned parser (current upstream htmlparser) reads error-free markup outside the exclusions as browsers do | The verifier's guarantee is relative to it. The exclusions cover the known splits (§7.1); the nightly detector (#399, not yet on `main`) is to compare it with parse5, Chromium, Firefox and WebKit on the accepted cases it exports |
| Escaper output is inert in the states the verifier accepts (the inertness lemma) | Argued in security-architecture.md §11.2; tested on every hostile render by #324; not proved |

### 7.1 Known parser deviations

Places where the pinned parser and a supported browser (or parse5, which the tests use) build
different trees. Each one is handled by a coarse exclusion or is outside what the verifier
accepts. None is modelled.

| Deviation | Who does what | Handling |
|---|---|---|
| "Reset the insertion mode" stops at an SVG or MathML element and picks "in body"; the spec walks on to the `td`, `th`, `caption`, table part or `template` | validator.nu (both versions) and Firefox (generated from the same Java) stop; the spec, parse5, Chromium and WebKit walk on. Not fixed upstream as of validator/htmlparser f5c4147 (Sep 2026) | exclusion (a): no SVG/MathML above a table part or `template` (#398, #406) |
| `</table>` in a table mode inside `template` contents uses `findLast("table")`, not table scope, and closes an outer table | validator.nu and Firefox close it; the spec, parse5, Chromium and WebKit ignore the end tag | exclusion (b): no table part directly in `template` (#401, #406); the measurement's repros also report parse errors |
| Classic and customizable `<select>` parsing | Firefox ESR and parse5 drop elements other than `option`, `optgroup` and `hr`; the current parser, Chromium, current Firefox and WebKit keep them | exclusion (c), on composed output (#305, #406) |
| parse5: the end tag of an SVG/MathML integration point while an HTML formatting element is open (`<div><svg><desc><b></desc></svg>X`) | parse5 differs from validator.nu and all three browsers | a parse5 bug, not a browser one. The detector reports parse5-only drift without failing (#399). The error check rejects it (#406, `ErrorFreeTest`) |
