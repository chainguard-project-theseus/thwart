# Security architecture: from patching contexts to safe by construction

Status: **accepted** (Oct 24, 2026). The principle changes (§6) are in
[AGENTS.md](../AGENTS.md) and the process changes (§7) are in [handoff.md](handoff.md). The rest of
this doc describes the target and the plan, not the current code. Where it and
[mvp-design.md](mvp-design.md) disagree, mvp-design describes what ships today.

**Amended Oct 25, 2026:** a build-time verifier (§11) replaces the profile as the security
boundary for markup contexts (families A and B), enforced once #367 lands. Two spec HTML parsers
check every template, inside the annotation processor. §11.7 says which parts of §5 are now
friendly errors or browser-divergence exclusions.

## 0. Summary

The core claim held. No reviewer has found SSTI or code execution: templates are compiled, calls
are allow-listed, and nothing is reflective at runtime. Principles 1-4 work.

XSS-class bugs keep coming: roughly 30 reports since #235 was opened on Oct 23, in five families (§1). Every one of them is in
**context selection**. None is in an escaper: `Escapers` has done exactly what it was asked
every time. It was asked the wrong question.

The root cause in one sentence: **we allow-listed the expression language and deny-listed the
markup language.** Expressions have a closed grammar, unknown names are errors, and javac (an
exact oracle) resolves them. Markup has an open grammar: whatever `MarkupTokenizer` accepts goes
through. Unknown elements and attributes get a permissive default context, and that context comes
from a hand-written guess at how browsers parse, keyed on names. Every browser behaviour we didn't
model is a bug, and there are many.

The recommendation, in three parts:

1. Define a **Tessera HTML profile**: a small, positive grammar of templates whose parse we can
   state exactly and which every supported browser parses the same way. Reject everything outside
   it at build time.
2. Prove the compiler's model against a **spec-conformant parser in test scope**, as a CI gate.
3. Admit that client-side frameworks are **outside what a server compiler can prove**. Bound them
   with deny-by-default names, a versioned framework table, and CSP, rather than chase them.

Principle 5 changes from "the compiler picks the escaper" to "the compiler accepts only what it
can classify exactly". Principle 6 is subordinated to it explicitly.

## 1. What happened

| Family | Issues / PRs | What went wrong |
|---|---|---|
| A. Parser differentials (compiler's tree ≠ browser's tree) | #277, #283, #289, #291, #295-#301, #305; CDATA, `<textarea/>`, markup inserted into `<title>`/`<textarea>`, `<svg><script>` with a quoted `<img title>` (all found in review) | The tokenizer models four raw-text names and nothing else: no namespaces, no insertion modes, no bogus comments, no `select`/`table` rules |
| B. Composition (context decided per file, output assembled at render time) | #300, #304; `t:remove="tag"` on raw text; HTML fragments in SVG; `SafeHtml` in `<title>` | `Fragment` and `SafeHtml` carry no context kind, so a fragment compiled for body text can be inserted anywhere |
| C. JavaScript position | #281, #282, #287, #290, #294; 4 regex/division bypasses in #290; `&quot;` in `<svg><script>` | `SCRIPT` is one context. JSON is safe only in expression position, and the compiler never knew where the hole was |
| D. Second-order interpreters (client frameworks) | #235/#243, #246/#270, #248/#275, #249/#257, #258/#274, #272, #273, #276, #303 | The browser was fine, but page JavaScript re-reads the DOM as code. Each fix added names to a deny-list |
| E. URL and policy | #284/#288, #285/#292, `@{/\evil}` in `t:src`, #286/#293 | Single-value checks applied to lists; origin checks only on some paths |

What the fixes look like: 17 open PRs (#287-#305) add about **7,200 lines**. About 5,500 of them
are parser and context rules, against 1,438 lines in `Contexts` + `HtmlParser` + `MarkupTokenizer`
today. Fixes interact. #291 *reopened* an XSS that main blocked, because two partial models
disagreed: SVG `<title>` was parsed as markup while SVG `<style>`/`<script>` stayed raw text. Three
pairs of PRs (#290/#294, #289/#298, #291/#301) are duplicates with different partial models of the
same browser behaviour. This is whack-a-mole, and it is getting worse, not converging.

### Evidence from a quick experiment

I wrote 74 probe templates, rendered them through the `Compilation` harness on main (0dda2f1) with
10 hostile values each, and parsed the output with parse5 (spec HTML parser, scripting on). A
~150-line oracle flagged a live script, a live handler or interpolation. The results:

- **25 templates give live XSS on main.** Most reproduce the issues and reviews above. Some are
  variants nobody had reported: `<table><caption><svg><title><script>`, `<template><svg><title>`,
  `<svg><foreignObject><svg><title>`, and `<svg><script>/*</svg><img src=x title=*/[[s]]//>`. In the
  last, the JSON value lands in an unquoted attribute and `" onerror=PWN() "` becomes a handler.
- An ordinary-looking `<svg><script>// <p>` comment ends the SVG in the browser and moves a hole
  from SCRIPT into HTML text. That isn't XSS, because JSON is HTML-safe, but the compiler escaped
  the value for a context it wasn't in.
- `<script t:src="@{/\evil.example/a.js}">` and `<base t:href="@{/\evil.example/}">` compile: the
  resource-URL rule doesn't apply the `/\` rule that htmx and refresh URLs already use.
- 6 probes were rejected by existing checks.

The point isn't the count. A generator plus a spec parser finds these mechanically. Today we find
them through human adversarial review, one at a time.

## 2. Root cause

### 2.1 The architectural decisions that produce the bugs

1. **Context is a function of names, and the default is permit.** `Contexts.attribute` returns
   `ATTRIBUTE` for any name it doesn't recognise, and `Contexts.text` returns `TEXT` for any
   element it doesn't recognise. That is why `hx-action` (#276), `up-on-*` and Datastar (#273), and
   `<component is>` (#272) are bugs: each needs someone to know the name and add it. Compare the
   expression side, where an unknown name is an error.
2. **A tokenizer that approximates HTML5, with no tree construction.** `HtmlParser` has four
   raw-text names (`script style textarea title`), decided by local name alone. HTML parsing has
   about 80 tokenizer states and over 20 insertion modes. Context depends on namespace (SVG `title`
   is not RCDATA, #277), on integration points (`foreignObject`, `desc`, `annotation-xml`), on
   breakout tags (`<p>` or `<font color>` inside `<svg>` leaves it), on the scripting flag
   (`noscript`), on `select` (#305, and it differs by browser version), and on bogus comments
   (CDATA, `<?`, `</ `, #296). Each of those is a separate bug under the current design.
3. **Static markup is copied verbatim.** The compiler emits the template's source bytes and
   escapes only the holes. So any static construct the compiler misreads (an unquoted value with a
   quote in it, #297; a comment the browser ends early, #295) changes the context of the holes
   that follow. Our own `HtmlSanitizer` is safe for the opposite reason: "No input is copied
   through, so the result is safe however a browser would have parsed the original." The compiler
   never applied that rule to itself.
4. **Context is decided per file at parse time, but output is composed at render time.**
   `t:insert`, `t:replace`, `Fragment` values, `t:remove="tag"`, `SafeHtml` and HTML messages move
   markup into another parse context. `SafeHtml` means "HTML" without saying *which* HTML (body,
   SVG, `select`, RCDATA), and `Fragment` has no kind at all.
5. **No lexical state for embedded languages.** `TextInlining` writes JSON for any `[[...]]`
   anywhere in a `<script>`. JSON is safe as an expression and unsafe in a string, template
   literal, regex or comment (#281). A hand-written JS lexer (#290, #294) has to tell a regex from
   a division, which depends on the parse, not only the tokens. It will always be approximate, and
   review keeps finding the gap (spread, `for await`, `export default`, `extends`).
6. **Second-order interpreters are invisible to the compiler.** Vue, AngularJS, Alpine, htmx and
   the rest pick mount points, prefixes and delimiters in JavaScript at runtime. No server-side
   analysis can see them. We treated each one as a context to add.
7. **Our tests verify escapers, never contexts.** `ContextOracle` checks that each slot's output
   is safe *for the context the compiler chose*. `HtmlParserDifferentialTest` compares
   `HtmlParser` with **attoparser**, Thymeleaf's own non-conforming parser, so we test for
   agreement with the model we replaced, not with browsers. Nothing in CI can detect a wrong
   context. Every bug in families A-C passes every test we have.
8. **Permit, then patch.** Compatibility features widened the accepted language first and
   deny-listed the dangers afterwards. #270 allowed JSON in any `:x` whose target is "ordinary",
   which is defined by exclusion, and review then found `:protocol`/`:host`. #274 allowed form
   URLs under a static boost and review found 3 bypasses. Each widening needs an adversarial
   review to find its exceptions, and the exceptions never end.

### 2.2 What can be fixed by construction, and what can't

| Family | By construction? | How |
|---|---|---|
| A. Parser differentials | **Yes** | Accept only a profile where the compiler's model is exact and stable across browsers (§5.2-5.3), verified against a spec parser (§5.7) |
| B. Composition | **Yes** | Content kinds on fragments, `SafeHtml` and insertion points, checked at build time (§5.5) |
| C. JS position | **Yes, by restriction** | Holes only in a script prefix whose tokenization is unambiguous: no `/` operator, no template literal before the last hole (§5.4). Don't try to lex all of JS |
| E. URL/policy | **Mostly** | One URL-shape function used everywhere (resource, htmx, refresh, CSRF), and list attributes typed as lists |
| D. Client frameworks | **No** | The interpreter, its configuration and its mount points are chosen at runtime. We can *bound* it (§5.6), not prove it. Go `html/template`, Soy and React don't claim this either |

The honest boundary: **Tessera guarantees what the browser's HTML parser and its built-in sinks do
with the output.** That means the tree is the one the compiler expected, and values land only in
text, attribute values, checked URL slots and JSON expression slots, so the browser never executes
a value as a script, handler, `javascript:` URL or cross-origin resource. It does not guarantee
what page JavaScript later does with the DOM. For a closed, versioned list of frameworks it adds
deny-by-default checks on top, with that boundary documented.

## 3. Prior art, and what it teaches

| System | What it does | Lesson for us |
|---|---|---|
| Go `html/template` | Context state machine over HTML, CSS, JS and URL; the context is decided by lexing the template | The best-known autoescaper still had **6 escaper CVEs in 2023**: CVE-2023-24538 (template literals), -24539 (CSS), -24540 (JS whitespace), -29400 (empty unquoted attributes), -39318 (`<!--` in script), -39319 (`<script` in JS literals). Its first fix for template literals was to *refuse* actions in them until it had a lexer state. Lexing JS is a CVE generator; refusing is the cheap fix |
| Closure Templates (Soy), strict mode | Every template and every `SanitizedContent` declares a **content kind** (`html`, `attributes`, `uri`, `trusted_resource_uri`, `js`, `css`, `text`); a call in the wrong context is a compile error; templates whose branches end in different contexts are rejected; it adds CSP nonces to scripts automatically | Our missing piece for family B is content kinds: `SafeHtml` and `Fragment` need them. Rejecting ambiguous templates is normal and acceptable. Auto-nonces make strict CSP the default |
| Trusted Types / CSP | The browser enforces types at sinks (`TrustedHTML`, `TrustedScriptURL`); a strict CSP without `'unsafe-eval'` blocks string-to-code | The only control that reaches past the HTML parser. Vue's in-DOM compiler, Alpine's standard build, petite-vue, Knockout and htmx `hx-on` evaluate with `Function`, so strict CSP cuts family D down to at most access to component state |
| React / JSX | Markup is a tree built with an API, never a string; attribute and child positions are structural; `dangerouslySetInnerHTML` is the one raw path | Parse differentials can't happen when the input is a tree and the serializer is ours. We can't change the template syntax (principle 6), but we can insist that the source parses to one unambiguous tree and reject it otherwise |
| lit-html | Lets the browser's own parser place markers in a `<template>`, and refuses bindings where it can't support them (raw-text elements, tag and attribute names) | Restricting where holes may go is the accepted answer, even in a client library with a real parser available |
| HTML sanitizers (DOMPurify, the Sanitizer API) | DOMPurify's mXSS history is namespace confusion (`<math><mtext><table><mglyph><style>`, `<svg><style>`, `noscript`, nested `form`). Fixes added namespace checks and banned elements that change parsing. The Sanitizer API sanitizes the tree in context so nothing is re-parsed | These are exactly the elements in our family A. Use the real parser or restrict to a subset where re-parsing is stable; never approximate. Our `HtmlSanitizer` already does the second |
| Thymeleaf | attoparser (non-conforming); `th:inline` modes; the default mode HTML-escapes in scripts | We inherited attoparser's model of HTML and are still testing against it (#170). Thymeleaf has the same quoted-inline bug (#281), so "track Thymeleaf" can't be the safety bar |

What the systems that converged on safety have in common: **a closed accepted language, exact
classification, typed composition, and a parser oracle.** We have the first three for
expressions and none of them for markup.

## 4. Options

| Option | What it buys | Costs and risks | Verdict |
|---|---|---|---|
| (a) **Restricted template grammar (profile)** | The model is small enough to be obviously right; whole families close; the failure mode is a build error with a fix-it, never XSS; output bytes are unchanged (we reject, we don't rewrite) | Some legal HTML is rejected; needs a written spec; migration needs fix-its | **Yes: the core** |
| (b) **Spec-conformant parser in the compiler** | An exact tree for one spec version | A port of about 10k lines, in Java 8 with zero deps, to maintain; it still doesn't solve composition (B) or JS (C); browsers in the field don't all match one spec version (classic vs new `<select>` parsing, #305 needs Firefox 140 ESR); it makes *more* HTML acceptable, which is the wrong direction | **No in main code. Yes in test scope, as the oracle.** Reversed on Oct 25, 2026: the parser itself, not a port, runs at build time as the boundary, shaded into the processor (§11) |
| (c) **Differential testing as a gate** | Turns "a reviewer found a bypass" into "CI found a counterexample"; makes the profile's claims checkable; catches regressions like #291 | Test-scope deps (validator.nu htmlparser is Java, the Gecko lineage; parse5/acorn need Node); generator design takes care; it shows bugs but doesn't prove absence | **Yes: the gate** |
| (d) **Output-side verification** | A runtime check of rendered output catches anything | A parser in the runtime (breaks zero-deps and costs per render); unclear failure behaviour in production | **Runtime: no. Test time: yes**, as part of (c), plus an optional kit for app tests later |
| (e) **Framework policy** | Bounds family D | Can't be complete | **Yes: deny-by-default names, a framework table, unconditional `{{` breaking where it's lossless, and CSP guidance** |

**Combination:** (a) + (c) + (e), with (b) used only as the oracle in (c). (a) without (c) is a
new set of hand-written claims. (c) without (a) is a faster whack-a-mole. Together, the compiler
accepts a small language, CI proves the compiler's model of that language matches a real parser,
and the boundary we can't prove is stated and bounded.

## 5. Recommendation: the Tessera HTML profile

Since Oct 25, 2026 the profile is no longer the security argument for families A and B; the
build-time verifier is (§11). §11.7 lists what each part below is now.

### 5.1 The invariant

For every template T the compiler accepts, and every model M:

> When a spec HTML parser (or any supported browser) parses `render(T, M)`, each hole's value
> lands in the node, context and namespace the compiler chose, and the static structure is the
> same for every value: the trees for benign and hostile values are equal.

Static structure must not depend on values, and must not depend on which browser parses it. It
doesn't claim that the parsed tree is the one the compiler recorded: implied end tags, foster
parenting and the adoption agency can move static nodes, the same way for every value, without
moving a hole out of its context (decided on #349). The
profile is the set of templates for which we can guarantee this with simple local rules. Anything
else is a build error naming file, line and column, with a fix-it.

This is stronger than "the escaper matches the context". Today we escape correctly for a context
that may be wrong.

### 5.2 Token-level rules (positive grammar)

The tokenizer accepts only these forms; anything else is a build error.

| Construct | Accepted | Rejected (today's PRs in brackets) |
|---|---|---|
| Start tag | `<name` + attributes + `>` or `/>`; names ASCII; values double- or single-quoted, or unquoted from `[A-Za-z0-9_.:/#-]` | quotes or `<` outside quoted values, `=` in names (#297); `/>` on non-void HTML elements (`<textarea/>`, `<title/>`, `<div/>`), except where Tessera writes the end tag itself (`<textarea t:field/>`) |
| End tag | `</name>` matching the open element | stray or mismatched end tags (already); `</` + non-letter (#296) |
| Comment | `<!--` text `-->` where the text doesn't start with `>` or `->` and contains no `--!>`, `<!--` or `-->` | early-ending comments (#295); conditional comments are allowed as written in petclinic |
| Doctype | `<!DOCTYPE html>` (case-insensitive), first thing only | other `<!` forms, `<?`, CDATA outside SVG/MathML (#296) |
| Text | characters other than `<` and `&`; `&` only as a complete character reference; `<` only as the start of an accepted tag or comment | a literal `<` that isn't a tag (write `&lt;`); a hole after a static `<`, `</`, `<!` (#299); an ambiguous `&name` before a hole (#303) |
| Holes | `[[...]]` and `[(...)]` in text; `t:*` attributes | holes inside comments, tags, attribute names or element names (already) |

The tokenizer is a few hundred lines because it accepts so little. It replaces, rather than adds
to, the special cases in #295-#299.

### 5.3 Tree-level rules

The parser keeps an explicit **parse mode** for every open element: `HTML`, `SVG`, `MATHML`,
`RAWTEXT(script|style)`, `RCDATA(title|textarea)`, `SELECT`, `NOSCRIPT`. Mode transitions are
allow-listed:

- **HTML elements.** An allow-list of HTML element names plus custom elements (a name with `-`).
  Unknown names without `-` (including Vue's `<component>`, #272) are errors unless the framework
  table lists them, with rules.
- **Raw text and RCDATA.** Only `script` and `style` (raw text) and `title` and `textarea`
  (RCDATA), in HTML mode. `xmp`, `plaintext`, `noembed`, `noframes`, `frameset` and `frame` are
  errors. `iframe` must be empty: its content is never shown, and it's raw text in the browser.
  `noscript` keeps markup children (GTM's `<noscript><iframe>`, `<noscript><img>`), but no
  RCDATA/raw-text children, no `t:insert`/`t:utext`/`[(...)]`, and no literal `</noscript`
  anywhere except its own end tag (#283, #289/#298).
- **Foreign content.** `<svg>` and `<math>` enter SVG/MathML mode, where only element names from
  the SVG/MathML allow-lists are accepted. That rejects every breakout tag (`<p>`, `<b>`, `<font
  color>`, ...) by construction, without listing them. `title`, `desc` and MathML token elements
  (integration points) take text and holes only, escaped as TEXT, with `{{` broken (#277). SVG
  `script` and `style` take no holes and no `<` or `&` (#301). `foreignObject` holds an HTML
  subtree, under the same rules recursively. CDATA is accepted only in foreign mode.
- **`select`.** Only `option`, `optgroup`, `hr` and text, the subset where classic and new select
  parsing agree (#305).
- **Forms.** No nested `<form>`. The browser drops the inner start tag (CSRF, guide §7).
- **Tables.** Allowed, with no computed text directly in `table`, `tbody`, `thead`, `tfoot`, `tr`
  or `colgroup` (#348). Foster parenting moves the text in front of the table but leaves comments
  in it, so the `{{` break comes apart. Values go in `td`, `th` and `caption`. Table parts must sit
  directly in the part that takes them, and `<form>` not directly in a table, or the browser closes
  elements (a `<tr>` ends a `<td>`) and reads later text in a table mode. `t:insert`/`t:replace`
  get the same checks on the fragment's top level; `Fragment` values never start with a table part.
  The oracle (§5.7) watches the rest of this claim.

### 5.4 Scripts: the data-prelude rule

Don't lex JavaScript in general. Restrict where holes may go, so that the lexical state at each
hole is decidable without a JS parser:

- In a `<script>` (no type, JS, module or JSON) that contains a hole, the static text **before
  the last hole** must tokenize with a lexer that has **no ambiguous tokens**: identifiers,
  numbers, punctuators other than `/`, `"..."` and `'...'` strings, `//` and `/* */` comments, and
  whitespace. A `/` operator or regex, a backtick, `<!--`, `-->`, a backslash line continuation
  and U+2028/U+2029 are build errors before the last hole. Without `/` and template literals, JS
  tokenization is context-free, so this lexer is exact, not heuristic.
- Each hole must be in **expression position**: not inside a string or comment token. `"[[x]]"`
  is an error with a fix-it, and `tessera-migrate` unquotes it (#287).
- After the last hole, anything goes. The value is a complete JSON token sequence, so code after
  it can't change how it parses.
- No holes in `<svg><script>`: browsers decode character references and parse markup there.
- JSON strings also escape `{` as `{` (#303's cheap part), so petite-vue can't interpolate
  a value in script text.

Migration cost looks low. In the 1,215 converted templates of the latest migration-bench runs, all
48 scripts with holes declare their data before any division, regex or template literal (crude
scan; the gate in §8 measures it properly). The usual Thymeleaf shape, `var chart = [[${chart}]];
$(function () { ... })`, is unchanged. The fix-it for the rare failure is to move the hole above
the code, or to use `<script type="application/json">` and `JSON.parse`.

### 5.5 Composition: content kinds

Give every fragment, fragment parameter, `Fragment` value, `SafeHtml` and insertion point a
**content kind**: the parse mode of the place it's written for (`HTML_FLOW`, `SVG`, `MATHML`,
`SELECT_OPTIONS`). An insertion is legal only when the kinds match. A fragment's kind comes from
its host element. `SafeHtml` is `HTML_FLOW`. #304 implements most of this.

- `t:insert`, `t:utext` and `[(...)]` into RCDATA or raw text are errors (#298, #300).
  `t:replace` with a whole `<title>` fragment is the fix-it.
- `t:remove="tag"` on raw-text, RCDATA or foreign elements is an error (#300).
- HTML messages (`#{...}` as HTML) are `HTML_FLOW` and are checked when they're used, like
  `SafeHtml`.

This is Soy's model applied to our types, and it's additive: existing code is `HTML_FLOW`.

### 5.6 Attributes and client frameworks

**Deny-by-default names for computed attributes.** A `t:` attribute (or a `t:attr` entry) is
accepted only if its name is:

- a standard HTML, SVG, MathML or ARIA attribute (an allow-list, classified as today: text, URL,
  resource URL, ...);
- `data-*`, except names the framework table marks as code;
- a plain name (`[a-z][a-z0-9]*`) on a custom element (component props);
- listed in the framework table.

Anything else is an error: "`t:up-on-loaded` is not a known attribute; if it is data for your
script, use `data-*`". This closes #273's Unpoly half and #276's `hx-action` without anyone knowing
those frameworks, and it closes the next framework too. Static attributes are unaffected and copied
verbatim, so migration of static directives doesn't change. In the bench corpus, the computed
non-standard names are `stroke-dasharray` (SVG, on the allow-list), custom-element props and
`<font t:color>`. The last is the SVG breakout tag, which §5.3 already rejects inside SVG.

**The framework table** is data, not code: one entry per supported framework and version range.
Each entry lists the attributes that are code (rejected), the URLs it fetches and swaps in
(same-origin links only, as in #257 and #274), the ones that take JSON (#270), the elements it
adds (`component`), and the delimiters it interpolates. Adding a framework means a table entry plus
rows in the framework harness (§5.7), nothing else. Vue `:x` JSON bindings become an **allow-list
of targets** on native elements (`class`, `title`, `id`, `alt`, `value`, `disabled`, ...), plus any
plain name on custom elements. That replaces `BIND_DENIED_TARGETS`, which is a deny-list of DOM
properties, an open set.

**`{{` breaking** stays unconditional wherever it's lossless: element text (as today), SVG/MathML
text (with §5.3), and JSON strings (`{`). For attributes and RCDATA there is no lossless
break, so they stay documented limitations (guide §7), bounded by `v-pre`/`ng-non-bindable` and
CSP. I don't recommend a per-template "client framework mode". Mount points are chosen at runtime
and often in a layout, so a per-template flag is a knob that's easy to get wrong. Revisit if the
bench meets an AngularJS app.

**Bounding with CSP.** The starter should make strict CSP easy: a per-request nonce in
`RenderContext`, added by the compiler to every `<script>` it emits (Soy does this), and a guide
section on a nonce-based `script-src` without `'unsafe-eval'`. That turns Vue in-DOM, Alpine,
petite-vue, Knockout and htmx `hx-on` from code execution into, at worst, access to the
component's state. It is the one control that reaches past what a compiler can see.

### 5.7 The oracle gate (test scope only)

A `ProfileOracleTest` in `tessera-compiler/src/test`:

1. **Templates.** The corpus (repo templates and the bench templates), plus templates generated
   from a grammar of contexts: element nesting across the modes in §5.3, × hole positions (text,
   each attribute class, script prefix positions), × composition (fragments inserted into each
   kind), plus mutations of all of these.
2. **Values.** The hostile set (`HostileStrings.VECTORS`) plus seeded random strings, each tagged
   with a unique marker.
3. **Check.** Parse `render(T, M)` with **validator.nu htmlparser** (Java, the parser Gecko's
   is translated from; test scope only, like attoparser today) and assert the invariant in §5.1:
   same static tree as the benign render, each marker in the recorded node and context, no
   element or attribute whose name or value came from a marker, no executable script or handler
   containing a marker. For script slots, tokenize with a JS tokenizer (Rhino in test scope, or
   acorn via Node) and assert that each marker's value is exactly one literal token sequence in
   expression position.
4. **Rejected side.** Every probe the profile rejects is checked too: if the spec parser shows it
   would have been safe, that's a false positive to review, not a failure.

**What it doesn't cover:** browser-version differences (classic select), which a nightly job
handles by running parse5 and headless Chromium/Firefox ESR over the same generated set, as
tcnghia's PRs already did by hand. Framework re-interpretation is covered by a **framework
harness**, also nightly: jsdom with Vue 2/3, petite-vue, AngularJS, Alpine and htmx 1/2/4, mounted
over generated pages. Neither is in `mvn verify`, because both need Node.

`HtmlParserDifferentialTest` against attoparser should be deleted once the oracle lands. It tests
agreement with the wrong reference.

### 5.8 What doesn't change

Principles 1-4, the expression language, `Escapers` (no escaper bug appears in this history),
zero runtime and compiler dependencies, the Java 8 floor, and output bytes for templates that are
in the profile. Most real templates are: the corpus scans in #296, #297, #301 and #304 found almost
nothing outside it.

## 6. Principle changes

Text now in AGENTS.md:

> **3. Allow-list everything callable, and everything parseable.** (append) Markup is held to the
> same rule as expressions: the compiler accepts a closed profile of HTML (docs/context-model.md),
> and any element, attribute name, or construct outside it is a build error.
>
> **5. Contexts are proven, not guessed; escaping cannot be forgotten.** The compiler accepts only
> templates in which it can classify every output position exactly as every supported browser
> parses it, and checks this against a spec parser in CI. Within that, it picks the escaper from
> the context. Raw output needs a safe type of the right content kind (`SafeHtml`, `TrustedUrl`,
> `Fragment`), never a flag on a string.
>
> **6. Familiar before novel, inside the profile.** Track Thymeleaf syntax and behaviour where it
> is safe. Where Thymeleaf accepts something outside the profile, Tessera rejects it with a fix-it
> and `tessera-migrate` rewrites it where it can. Parity never overrides 5.
>
> **7. The guarantee ends at the HTML parser.** Tessera guarantees what the browser's HTML parser
> and built-in sinks do with the output. Client-side code that re-reads the DOM as code is
> supported only for frameworks in the framework table, deny-by-default, with the remaining gaps
> documented. New framework support is a table entry plus harness rows, never a special case.

And under "Keep it simple": *a security fix that adds a special case to the parser or context
table is a smell. Prefer shrinking the profile.*

Amended Oct 25, 2026 (§11): the keep-it-simple line now sends parser-differential and composition
fixes to the verifier. `tessera-compiler` may shade the two pinned validator.nu htmlparser
versions (§11.4), and nothing else.

## 7. Process: where we went wrong and what changes

The working pattern was: build compatibility features fast, have adversarial reviewers find
bypasses, patch each one, repeat. Review did its job. It found real bugs every round, which shows
the design was generating them. Parallel contributors then patched the same model three different
ways.

| Change | Why |
|---|---|
| **docs/security-model.md**: threat model and trust boundaries. Attacker: controls every model value. Template authors: trusted but fallible, so a template whose meaning the compiler can't pin down is rejected, whoever wrote it. Messages: checked at build time. `SafeHtml`/`TrustedUrl`: review points. Browsers: the supported set, including ESR. Page JavaScript: outside, except the framework table | Today every reviewer reconstructs the threat model, and severity arguments ("contrived template") recur |
| **docs/context-model.md**: the profile (§5.2-5.6) as tables. **Every change to `HtmlParser`, `MarkupTokenizer`, `Contexts`, `TextInlining`, `Escapers` or the framework table updates it in the same PR** | One written model instead of one per PR |
| **The oracle gate in CI** (§5.7). A new context, mode, or framework entry lands with oracle or harness rows. A PR that widens the accepted language must say so in its description | Turns review findings into CI failures; would have caught #291's regression |
| **Deny-by-default for client frameworks.** No computed value in an unknown attribute namespace. Framework support is a table entry with a version range and harness rows | Ends the chase in family D |
| **One owner per security area, and design before code.** Claim an issue before starting; for parser, context or escaper changes, post a design comment (as #248 and #258 did) and get agreement before opening a PR. No second PR for a claimed issue; offer tests to the first one instead | Avoids #290/#294, #289/#298, #291/#301 |
| **Reject over rewrite, and fail closed when unsure.** If a check has to guess (regex vs division, namespace), the guess must lead to a build error, not to accepting | Most bypasses in #290 and #291 came from guesses that failed open |
| **Severity is about the compiler, not the author.** A template the compiler misclassifies is a bug even if the markup is contrived; fix it by shrinking the profile | Stops the "contrived, low severity" debate, while keeping the fix small |
| **Release audit on the oracle's terms.** The strategy's "adversarial model audit" runs against the profile document and reports profile gaps, not single bypasses | Matches the strategy's "verification evidence" offer |

## 8. Plan

Smallest first. Each phase lists what it closes and the gate that shows it's done. The phases are
tracked as GitHub issues. Since Oct 25, 2026 the build-time verifier (#364, §11) comes before the
rest of phase 2, and phase 2 shrinks as §11.7 says. CSP nonces (#335) move earlier, out of
phase 4.

| Phase | Work | Closes | Gate |
|---|---|---|---|
| **0. Now (days)** | Land the ready fail-closed fixes in a fixed order, one integrator, and close duplicates (§9). Apply the `/\` rule to resource URLs (`@{/\evil}` in `t:src`/`base`) | Live XSS in families A, B, C; #281 (high severity) | Each PR's repros in `XssContextTest.REJECTED`; `mvn verify` green |
| **1. Oracle (1-2 weeks)** | `ProfileOracleTest` with validator.nu in test scope; generator v1 (the §1 probes and the open PRs' repros as seeds); JS token check; delete the attoparser differential; nightly parse5 + browser job | Nothing directly. It **finds** what's left, and it's the gate for phases 2-3 | It reproduces the 25 live cases on 0dda2f1 and passes on main after phase 0; known false negatives are listed |
| **2. Profile (2-3 weeks)** | Write docs/context-model.md and docs/security-model.md; positive-grammar tokenizer (§5.2); parse modes and allow-lists (§5.3); content kinds (§5.5); deny-by-default attribute names (§5.6). Fold in the phase-0 rules, keeping their tests | Families A and B by construction; #272, #273 (Unpoly), #276 | Oracle green on the generated set; profile false positives on the 1,513-template corpus reported in the PR (target: a handful, each with a fix-it); lines in `html/` + `Contexts` go down, not up |
| **3. Scripts** | Replace #290's regex/division heuristic with the data-prelude rule (§5.4); `{` in JSON; no holes in SVG `<script>` | Family C by construction | JS oracle green; corpus: no new rejections beyond the quoted ones #287 rewrites |
| **4. Frameworks** | Framework table as data; Vue bind targets as an allow-list; nightly framework harness; CSP nonces in the starter; guide section on the boundary | Bounds family D; #273 (Datastar), #303's cross-node `{{` in its smaller form | Harness green nightly; guide states the boundary |
| **5. Optional** | A test kit so apps can run the oracle over their own MockMvc pages; OpenJML proofs (#302) | Evidence for customers | n/a |

A new parser-differential or composition report becomes a verifier test case (§11.7). Other
reports are handled by asking "which profile rule admits this?" and narrowing that rule plus
adding the generator case. Neither is ever handled by adding a special case.

## 9. Open PRs and issues: decisions

Decided Oct 24, 2026. Ordering principle: land small fail-closed fixes for live XSS now, because
phase 2 will keep their tests and may replace their code. Close duplicates. Don't land anything
that widens the accepted language or adds guessing. Closing or combining a teammate's PR is the
author's call; the actions below are what we recommend to them.

| PR / issue | Action | Notes |
|---|---|---|
| **#290** JS string check (#281) | **Land** after the review's 4 fixes, changed to **reject** wherever the lexer can't tell regex from division (after `)`, `}`, keywords, `++`/`--`) instead of guessing | High severity, reachable from ordinary migrated templates. The "reject when unsure" change ends this bypass class now, and phase 3 replaces the lexer with the smaller exact rule. Port #294's `wordAfterDot` |
| **#294** (same issue) | **Close** | Duplicate of #290; fails open in more places (review) |
| **#287** migrate unquoting (#282) | **Land** after the review fixes (`th:inline="none"`, non-string wording) | Independent of the design; needed by #290 |
| **#289** / **#298** (#283) | **Combine and land one**: #289's messages and ASCII fold plus #298's title/textarea, `<textarea/>`, `<title/>` and insertion coverage | In the profile, `xmp`/`noembed`/`noframes`/`plaintext` simply become errors and `iframe` must be empty, so the code shrinks later |
| **#291** SVG title as markup (#277) | **Close** | Reopens an XSS that main blocks (review); port its namespace and integration-point test cases to #301 |
| **#301** foreign raw text | **Land** first, then #304, then #305 | Matches §5.3 (keep raw text, reject `<` in foreign script/style/title/textarea; breakout list). Becomes the parse-mode core of phase 2 |
| **#304** fragment parse contexts | **Land after #301** | This is §5.5 (content kinds) in all but name. Check overlap with #298/#300 on `t:insert` into title/textarea: one PR owns that rule |
| **#305** select | **Land after #304** | Small; Firefox 140 ESR is a supported browser |
| **#295** comments, **#296** CDATA/PI/bogus, **#297** quotes in tags, **#299** `<` before a hole | **Land in this order, rebasing each** | Token-level rules of §5.2; low false positives per their corpus scans. Phase 2 rewrites them as a positive grammar and keeps the tests |
| **#300** `t:remove="tag"` / insert into RCDATA | **Land**, minus anything #298/#304 already covers | Part of §5.5 |
| **#303** cross-node `{{` (855 lines) | **Split.** Land the `{` JSON escape and "unclosed `{{` is an error" now. Rework the rendered-output tracking into a source-order rule in phase 4: between a static `{{` and its `}}`, no hole, no `t:` attribute, and no end tag of a `t:` element | Family D can't be proven; keep its machinery small |
| **#302** OpenJML proofs | **Defer** (keep as a draft) | No bug in this history is in an escaper. Proving the part that works is low value now, and the loop restructuring adds churn to the file every phase touches. Revisit in phase 5 |
| **#293** old-engine warning (#286) | **Land** after Tiles/Pebble | Orthogonal; already reviewed |
| **#272** `<component is>` | Phase 2/4: `component` only through the framework table, static `is` naming a plain HTML element, classified as that element | Deny-by-default element names |
| **#273** Unpoly/Datastar | Unpoly closed by deny-by-default names (phase 2); Datastar's `data-*` names go in the framework table (phase 4) | |
| **#276** htmx 4 | Closed by deny-by-default names; add htmx 4 to the table when it ships | |
| `&quot;` in `<svg><script>` | Closed by "no holes in SVG `<script>`" (§5.4); in phase 0, add it to #301's rule | |
| `@{/\evil}` resource URLs | Phase 0: one shared same-origin path check for resource, htmx, refresh and CSRF | Family E: one function, used everywhere |
| CSRF via `formaction` (guide §7) | Phase 2: inside a POST form, a computed `formaction` takes only a same-origin link (the #274 rule), or the token is withheld | |

## 10. Risks and open questions

- **False positives.** The profile rejects legal HTML. Mitigation: measure on the 1,513-template
  corpus before each rule lands, give every error a fix-it, and teach `tessera-migrate` the common
  rewrites. If a rule costs real templates, widen it only with oracle coverage.
- **Allow-lists go stale.** New HTML or SVG elements and attributes appear slowly; when one is
  missing the result is a build error, not XSS. Keep the lists in one generated file with a source
  (WHATWG/SVG2 indexes).
- **Oracle drift.** Settled by §11.4: current upstream and 1.4.16 both run at build time, and a
  hole must pass both. The Node job (#322) and WebKit (#354) check that they bracket real engines.
- **Node in CI.** Keep `mvn verify` Java-only (validator.nu, Rhino). Node-based checks (parse5,
  jsdom frameworks, real browsers) run nightly and before releases.
- **Scope creep.** This plan is phased so phase 0 stands alone. If we stop after phase 1, we still
  have a gate that finds these bugs before reviewers do.

## 11. The build-time context verifier (decided Oct 25, 2026)

This section supersedes §4 (b) ("no in main code"), and it supersedes §5 as the security argument
for families A and B. §11.7 says what happens to each part of §5. Tracker: #364. The research
memo and prototype are on local branch `research-systemic` (456680e, 05c4d7f).

### 11.1 Decision

A spec HTML parser decides contexts at build time, for every template. Translation validation
done as abstract interpretation:

1. **Record the skeleton.** For each template, the processor records the skeleton (static text,
   holes, control flow, fragment insertions) from the exact strings the generator writes (#366).
2. **Run two parsers.** It runs the skeleton through two spec HTML parsers (§11.4), feeding static
   bytes as they are. At each hole it reads the parser's real state.
3. **Accept only inert holes.** A hole is accepted only if its escaper's output can't change the
   parse in that state (§11.2), in both parsers, with scripting on and off.
4. **Otherwise, a build error.** Anything else is a build error with file, line, column and a
   fix-it (#367, #368). Fail closed.

Output bytes don't change: the verifier only accepts or rejects. No parser runs at render time,
nothing uses reflection, and the runtime stays dependency-free. So principles 1 and 4 hold.

Today the compiler guesses contexts with a hand-written model of HTML (`HtmlParser`,
`MarkupTokenizer`, `Contexts`), and nothing checks the guess. With the verifier, the algorithm
browsers implement decides, exactly, once per template. That model stays only to build
Tessera's tree and give early, readable errors. When it disagrees with a browser, the result is
a build error, never XSS.

### 11.2 Soundness argument

The verifier doesn't sample values. The argument has three parts.

**Escaper output is inert in the observed tokenizer state.** From `Escapers`:

| Escaper | Output alphabet | Accepted states | Why it's inert |
|---|---|---|---|
| `text` | no `< > " ' &`, NUL or lone surrogates, except the complete references `&amp; &lt; &gt; &quot; &#39;` and the complete comment `<!---->` | data state (in the modes below); RCDATA; RAWTEXT only in never-executed elements (`noscript` with scripting on, `iframe`, `xmp`, `noembed`, `noframes`) | Data state reacts only to `<`, `&` and NUL. A complete reference or comment returns to data state with nothing pending. RCDATA and RAWTEXT react only to `</` + name |
| `rcdata` | as `text`, without the comment | RCDATA | as above |
| `attr`, `url`, `urlList`, `resourceUrl`, `refresh`, `jsonAttr` | no `"`; `&` only in complete references | attribute value (double-quoted), with the parser's current attribute name equal to the hole's | That state reacts only to `"`, `&` and NUL |
| `json` | no `< > & '`, backtick, `$`, U+2028/U+2029 or controls | script data, with an HTML `script` as the current node | Script data reacts only to `<` |

Every other state is rejected: tag open, character reference, comment, CDATA, escaped script
data, `text` in script data or `style`, `json` anywhere but HTML script data (including SVG
`<script>`). These rules cover #295-#299, #303's `&name`, #338 and #353 without naming any of
them.

**The tree builder treats the value uniformly.**

- In data state the value produces only character tokens, and for `text` empty comments.
- The modes in body, in cell, in caption, in select (and in select in table), in template,
  foreign content and text handle every character token the same way: reconstruct the active
  formatting elements, then append.
- The verifier rejects text holes in every other mode (table, table body, row, column group,
  head and the rest). It also rejects them when reconstruction wouldn't be a no-op, and when
  three or more identical formatting elements carry holes in their attributes (the Noah's Ark
  clause).
- `<frameset>` is rejected outright, because a character changes `frameset-ok`.

So after the value the parser is in the same state as before, for every value. The rest of the
document parses identically, and the value is in the node the parser reported.

**Control flow is exact up to state equivalence.**

- **The state signature.** Tokenizer state, current attribute name, insertion and original mode,
  the open-element stack as (namespace, name), active formatting elements, template modes, form
  and head pointers, quirks, `frameset-ok`, and the `annotation-xml` integration-point flag.
- **Branches** take the union of their exit states. Unions add infeasible combinations, so they
  can only add false positives.
- **Loops** iterate to a fixed point (Go `html/template`'s idea, with the real parser as the
  transfer function). A loop that doesn't converge in 4 rounds is rejected.
- **Static fragment insertions** are analysed at each call site, memoised per (fragment, entry
  state, bound fragment arguments). Recursion is a fixed point too. Recursion that doesn't
  converge, or that reaches a bad state only through data, is "markup structure depends on data".

### 11.3 Limits of the argument

1. **Skeleton fidelity.** The skeleton must be the bytes the generated code writes, or it
   becomes a new differential. #366 records it at the single emission path and checks every
   render in the test suite against it. #324 checks every hostile render.
2. **Dynamic `SafeHtml` and `Fragment` values.** These aren't known at build time. The argument
   assumes their exit state equals their entry state. That holds only if the producer emits
   balanced content that implies no end tags in that state. Content kinds are therefore
   predicates on the insertion state, plus a check on producers (#329). `SafeHtml.unchecked`
   stays the caller's responsibility.
3. **Parser version.** The guarantee is relative to the WHATWG algorithm as the two embedded
   parsers implement it. They bracket the known splits (classic and customizable `<select>`, the
   fragment-case foreign-content breakout), not unknown ones. The Node job (#322, #345) checks
   Chromium, Firefox ESR and parse5; WebKit isn't checked yet (#354).
4. **Entry context of partials.** A fragment rendered on its own (htmx, `innerHTML`) is checked
   by fragment parsing in the context its root element implies, and by document parsing.
5. **The inertness lemma itself.** It's argued here and tested by #324 (state unchanged across
   every escaped hostile value), not proved. A Lean proof is optional work under #312.

### 11.4 Two parsers, accepted only if both accept

| Engine | Source | Why |
|---|---|---|
| Current | upstream `validator/htmlparser` at a pinned commit, the Java source Gecko's parser is translated from | current spec, 99.7% of html5lib tree-construction tests |
| Classic | `nu.validator:htmlparser:1.4.16` | classic `<select>` parsing, as in Firefox ESR, and the old fragment-case foreign-content breakout |

Accepting only what both accept covers the supported-browser intersection for the known
version splits by construction. jsoup, NekoHTML and attoparser were measured and aren't used in
the compiler (memo §10).

**Packaging (#365).**

- **Build.** A build-only module per version fetches the source with a pinned SHA-256 and
  compiles it with `--release 8`. Upstream doesn't publish a Java 8 jar; its pom targets 1.8, and
  the source builds that way.
- **Relocation.** Each version is relocated to `dev.tessera.compiler.internal.html5.current` and
  `dev.tessera.compiler.internal.html5.classic`.
- **Accessor.** One Tessera-owned accessor class per version reads the parser state. The parser
  source itself isn't patched.
- **Shading.** `tessera-compiler` shades both into its jar with their MIT notices. Consumers see
  no new dependency, and the processor still runs in a JDK 8 javac (`Jdk8ProcessorTest`).
- **Bumps.** Bumping the upstream commit is its own PR, with the corpus and seed verdicts diffed.

### 11.5 What the verifier doesn't cover

| Family | Status | Owner |
|---|---|---|
| C. JS lexical position | Not covered. The verifier makes one input exact: a `json` hole is in an HTML script's data | data-prelude rule, #332 |
| D. Client frameworks | Not covered | deny-by-default names (#330), framework table (#333), harness (#334), CSP nonces (#335, PR #361) |
| E. URL and CSRF policy | Not covered. The element and attribute a value lands in become exact | URL classes, `LinkCompiler`, `CsrfInjector` |
| CSS inside `style` | Not covered beyond "the value stays in its attribute" | `CssValue` rules |

### 11.6 Cost

Measured with the prototype (memo §5):

- **Seeds.** It flagged 120 of the 121 family A/B seeds live on 0dda2f1. The one miss
  (`pr304-9`) was a prototype lowering artefact. It also found 2 holes the value oracle missed.
- **Corpus.** 1,585 converted templates, 1,372 of which main's parser accepts. The only real
  rejections are 25 (1.8% of 1,372) recursive rich-text fragments. That pattern is data-dependent
  structure, a build error by decision. The fix-it is `html.sanitize` to `SafeHtml` (#369, #262).
  398 more were flagged only because the prototype couldn't resolve an insert, 395 of them layout
  `Fragment` parameters in `<head>`. #367 analyses those at the call site; if it can't, they are
  false positives to fix before it is enforced.
- **Build time.** The naive prototype took p95 155 ms per template; the targets with
  memoisation are in #367 (p95 ≤ 50 ms, under 10% of javac time).
- **Size.** About 21k lines of parser source per version, outside our code. The checker is
  about 1k lines.

### 11.7 What happens to §5

| §5 part | Role now |
|---|---|
| §5.1 invariant | Unchanged. The verifier enforces it at build time; the oracle (#324) tests the verifier |
| §5.2 token rules (#327) | **Friendly early errors**, not the boundary. The rules on main stay. Further positive-grammar work only where it gives a better message than the verifier |
| §5.3 parse modes and allow-lists (#328) | **Browser-divergence exclusions** stay (`select` content, nested `optgroup`, `frameset`, `plaintext`). Mode rules on main stay as friendly errors. The element allow-list moves to phase 4 as a framework/typo rule |
| §5.4 data-prelude rule (#332) | Unchanged |
| §5.5 content kinds (#329) | **Redefined** as parser-state predicates (`FLOW` first; `PHRASING`, `HEAD`, `TABLE_ROWS` only if needed), checked by the verifier |
| §5.6 attribute names, framework table, CSP | Unchanged (family D) |
| §5.7 oracle gate (#324) | **Repurposed.** It tests skeleton fidelity and the inertness lemma, and keeps value checks for C, D and E |
| §5.8 what doesn't change | All of it, except the compiler's dependencies: `tessera-compiler` shades the two parsers (§11.4). The runtime, the starters and generated code stay dependency-free |

A new parser-differential or composition report becomes a verifier test case, not a special
case in `HtmlParser`, `MarkupTokenizer` or `Contexts` (handoff.md). For families A and B this
replaces §7's "fix it by shrinking the profile". Once the verifier has run on main without
false-positive reports for a while, #378 chooses escapers from the parser state and deletes the
name-based context tables.
