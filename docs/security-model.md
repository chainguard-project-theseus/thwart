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

It does not guarantee what page JavaScript does with the DOM afterwards, except for the
frameworks in the framework table (context-model.md §6.3), and there only by the table's
deny-by-default rules.

The guarantee holds for the profile as context-model.md specifies it. Rows marked `phase 2` or
with an open PR there are gaps on `main` today.

## 2. Actors and trust

| Actor or input | Trust | What Tessera assumes | What enforces it |
|---|---|---|---|
| **Model values** (every `t:params` value, including strings, numbers, collections, nulls and their properties) | Untrusted | The attacker controls all of them, completely, including length, characters and `null` | Escapers chosen by context; URL checks; JSON for scripts |
| **Template authors** | Trusted, but fallible | Authors don't attack their own templates, but they write markup whose meaning they don't know: a quote in an unquoted value, a comment a browser ends early, `<svg><title>` | The profile. A template whose meaning the compiler can't pin down is rejected, whoever wrote it |
| **Message bundles** (`messages*.properties`) | Trusted at build time | The bundles the compiler sees are the ones that ship | HTML messages are checked at build time (`MessageCompiler`). At render time, `Escapers.htmlMessage` returns HTML only if the message has no `<` or matches a checked pattern; anything else is escaped as text |
| **Application Java code** (controllers, `@TemplateFunction`s, view-safe types) | Trusted | It decides what goes into the model, and what it marks as safe | Principles 2-4: allow-listed functions and properties, no reflection |
| **`SafeHtml.unchecked`, `TrustedUrl.unchecked`** | Review points | The caller vouches for the content. Every call site is a security review point; the names are greppable | Nothing at render time. `t:utext` and `[(...)]` accept only `SafeHtml`; a `TrustedUrl` skips the URL checks |
| **Other `SafeHtml` sources** (`escape`, `sanitize`, a rendered `Template`, `concat`) | Trusted by construction | `HtmlSanitizer` copies no input through, so its output is safe however a browser parsed the original | `SafeHtml` has kind `HTML_FLOW` and will be inserted only where HTML flow content is legal (#304, open; #329) |
| **The build** (javac, the annotation processor, the compiler's dependencies) | Trusted | Template sources and bundles come from the application's repository | Out of scope |
| **Browsers** | The supported set | Chromium (current), Firefox (current), Firefox ESR and Safari/WebKit (current) (context-model.md §7, decision 7). Safari is supported but not yet verified: WebKit is not in the oracle yet (#354). A rule must hold in all of them; where they differ (classic and new `<select>` parsing), the profile accepts only the intersection | validator.nu htmlparser 1.4.16 and validator 26.10.12 in `mvn verify` (#344); parse5, headless Chromium and Firefox 140 ESR in the Node job (#345; on Chainguard Checks after every merge and on request, over a fixture until #324) |
| **Page JavaScript and client frameworks** | Outside, except the framework table | Code that re-reads the DOM as code (Vue in-DOM templates, Alpine, htmx, AngularJS) can turn safe output into code execution | Deny-by-default names (#330), the framework table (#333), `{{` breaking, strict CSP with nonces (#335) |
| **The request** (headers, URL, session) | Untrusted | It reaches a template only through model values or `RenderContext` | As model values; CSRF tokens only on same-origin POST forms |

## 3. Trust boundaries

Each row is a point where data crosses from one trust level to another, and the check at that
point.

| Boundary | Crossing | Check | Where |
|---|---|---|---|
| Template source to compiled class | Author markup becomes a fixed tree and a set of holes | The profile (context-model.md §2-§5); expression resolution against the view-safe allow-list | `MarkupTokenizer`, `HtmlParser`, `Contexts`, the expression compiler |
| Model value to output | An untrusted value is written into a hole | The escaper for the hole's context, chosen at build time | `Escapers` (`text`, `rcdata`, `attr`, `url`, `urlList`, `resourceUrl`, `refresh`, `json`, `jsonAttr`) |
| Model value to URL | An untrusted value becomes a link or a resource | Scheme allow-list for `URL`; literal-path link or `TrustedUrl` for `RESOURCE_URL` (the author's literal fixes the origin; `/\` rejected for every link, #340); same-origin path for htmx, refresh and boosted form URLs | `Escapers.url`, `LinkCompiler.isSameOriginPath`; CSRF: `Escapers.isSameOriginUrl` |
| Model value to script | An untrusted value is written into a `<script>` | A complete JSON value in expression position (context-model.md §4) | `Escapers.json`, `TextInlining` |
| Fragment, `SafeHtml` or message to an insertion point | Markup compiled for one context is placed in another | Content kinds must match (context-model.md §5) | #304 (open); #329 |
| `unchecked` call to output | Application code asserts that a string is safe | Code review | Call sites of `SafeHtml.unchecked`, `TrustedUrl.unchecked` |
| Rendered page to browser parser | The browser parses the page | The invariant: each hole is in the node, context and namespace the compiler chose, and the benign and hostile trees are equal | Oracle gate (#324) |
| Browser DOM to client framework | Page JavaScript reads attributes and text as code | Framework table entries; `{{` breaking; CSP | Beyond the guarantee except as bounded here |

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
| XSS through a parser differential (the compiler's tree is not the browser's) | Yes | Family A; closed by the profile (context-model.md §2-§3) |
| XSS through composition (fragments, `SafeHtml`, messages in the wrong context) | Yes | Family B; content kinds (context-model.md §5) |
| XSS through a script hole in the wrong lexical position | Yes | Family C; data-prelude rule (context-model.md §4) |
| A URL or resource from another origin | Yes | Family E; URL classes (context-model.md §6.1) |
| Client-side template injection in a listed framework | Bounded | Family D. Deny-by-default names, the framework table and `{{` breaking. Gaps are listed in guide §7 |
| Client frameworks not in the table, or configured to read other names | No | Outside the guarantee. Strict CSP (#335) limits the damage |
| What page JavaScript does with values it reads from the DOM | No | Principle 7 |
| Content an `unchecked` call vouched for | No | The call site owns it |
| CSS injection | Yes, by restriction | No holes in `<style>`; a `style` attribute takes only literals, numbers and `CssValue` |
| Denial of service, timing, clickjacking, response headers | No | Application and server concerns |
| Browsers outside the supported set | No | A difference there is a request to widen the supported set, not a bug |

## 6. Severity

Severity is about the compiler, not the author.

- If the compiler accepts a template and a supported browser parses its render differently
  from the compiler's tree, that's a bug, however contrived the markup.
- Fix it by asking which profile rule admits the template, then narrowing that rule and adding
  a seed or generator value that reproduces it (context-model.md). Don't add a special case for
  the reported template.
- If a rule has to guess (regex or division, which namespace), the guess leads to a build error.
- A rejected template that a spec parser shows would have been safe is a false positive. Review
  it, but it isn't a security bug.

## 7. Assumptions the proofs make

The Lean model of T1 (tcnghia/harder#16) and the OpenJML proofs (rule functions on
tcnghia/harder#18 and fork branches; escapers on tcnghia/harder#11) are on forks, not upstream.
Where they are cited in context-model.md, they rely on these assumptions, which are part of this
threat model:

| Assumption | Why it matters |
|---|---|
| Browsers follow the WHATWG tokenizer for the states the profile reaches | Lean checks its tokenizer model against the html5lib tokenizer tests and the WPT tree tests; real browsers are checked only by the PRs' manual runs and the Node job (#345), which runs over a fixture until #324 |
| The escapers meet their postconditions (`htmlTextSafe`, `htmlSafe`, `jsonScriptSafe`) | Proved for strings on tcnghia/harder#11; JSON numbers, booleans and structures are assumed |
| The generated code calls the escaper the context table names | Tested by the generated-code checker (tcnghia/harder#15), not proved (G7) |
| Scripting is enabled, for the `noscript` proof | With scripting disabled, `noscript` content is markup; the profile's rules are argued safe for both readings, but only one is proved |
| Tree construction (implied end tags, foster parenting, nested `form`) doesn't move a hole into another context | Not modeled in Lean; watched by the oracle (#344) |
