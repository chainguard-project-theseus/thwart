# Security architecture: from patching contexts to safe by construction

Status: **accepted** (Oct 24, 2026). The principle changes (§6) are in
[AGENTS.md](../AGENTS.md) and the process changes (§7) are in [handoff.md](handoff.md). The rest of
this doc describes the target and the plan, not the current code. Where it and
[mvp-design.md](mvp-design.md) disagree, mvp-design describes what ships today.

**Amended Oct 25, 2026:** a build-time verifier (§11) replaces the profile as the security
boundary for markup contexts (families A and B), enforced since #367. The pinned spec HTML parser
checks every template inside the annotation processor. Simplified the same day (#404): it is to
accept only error-free parses outside three coarse exclusions (#406). §11.7 says which parts of
§5 are now friendly errors or exclusions.

**Amended Oct 30, 2026:** §12 closes the areas the verifier doesn't cover by class (#544).

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

Since then §11 made a spec parser the boundary for families A and B, and §12 (Oct 30) applies the
same rule to everything else that kept producing bugs: attribute values, scripts, frameworks,
runtime content checks, name folding and CSRF each become an allow-list or a closed grammar, and a
hostile-value sweep looks for what is left.

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
| C. JS position | **Yes, by restriction** | Holes only as the whole value of a declaration in a closed prelude at the top of the script (§12.3). Don't try to lex all of JS |
| E. URL/policy | **Mostly** | One URL-shape function used everywhere (resource, htmx, refresh, CSRF), and attributes that are not exactly one URL take only literals or a `TrustedUrl` (§12.2) |
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
  in it, so the `{{` break comes apart. Values go in `td`, `th` and `caption`. The build-time
  verifier (§11) enforces this on the composed parse, through `t:insert`/`t:replace` and fragments:
  a value read as table text is `TEXT_IN_TABLE`, `SafeHtml` or a `Fragment` value outside a cell is
  `RAW_NOT_FLOW`, and a misplaced table part or `<form>` (a `<tr>` that ends a `<td>`) is a parse
  error, so foster parenting never happens. The verifier composes a `Fragment` argument only where
  the callee inserts the parameter by itself, so the compiler rejects a template-built `Fragment`
  value that starts with a table part (TODO #561). The oracle (§5.7) watches the rest of this claim.

### 5.4 Scripts: the script prelude rule

Don't lex JavaScript. Fix the code around every hole with a closed grammar instead (#540,
replacing #332's data-prelude lexer and the #509 expression-position checks):

- In a JavaScript `<script>` (no type, a JavaScript type or `module`), each hole is the whole value
  of a declaration `const|let|var NAME = [[...]];` (at least one space or tab after the keyword; an
  ASCII identifier that is not a reserved word or `location`, since `var location = v` in a classic script calls the navigating `window.location` setter; one hole per declaration), and these declarations
  form a prelude at the top of the script: before, between and after them, up to the first other
  code, only ASCII spaces, tabs, line breaks and `//` comments (a hole in a comment ends the
  prelude). There is no form without a keyword (`x = [[...]];`). Every hole after that is a build
  error with a fix-it: move the value into a declaration at the top, or use
  `<script type="application/json">` and `JSON.parse`. The code after the prelude is static and may
  use anything (`/`, template literals, `-->`): no value follows it. `tessera-migrate` hoists
  values into the prelude (#541).
- `t:text` and `t:utext` on a JavaScript `<script>` are build errors with the same fix-it: the whole
  body would be one value with no code around it.
- Every value in a JavaScript script is written in parentheses, `(json)` (`Escapers.javaScript`, and
  the generated bean serializers), so it is one primary expression whatever its type. The code next
  to it is always ` = ` and `;`.
- In a JSON script (`application/json`, `application/ld+json`), which a browser never runs, values
  stay plain JSON. Each hole must be outside a `"..."` string and the text has no `/` outside
  strings; JSON's tokens are context-free, so this is exact.
- Numbers are written from their text only for the JDK classes themselves: a `BigDecimal` or
  `BigInteger` subclass (whose `toString()` an application can override) fails the render, in
  scripts, bindings, messages and styles.
- No holes in `<svg><script>`: browsers decode character references and parse markup there.
- JSON strings also escape `{` as `{` (#303's cheap part), so petite-vue can't interpolate
  a value in script text.

In the latest migration-bench runs, 33 of the 36 JavaScript scripts with holes need their values
hoisted into declarations at the top; the hoist is mechanical (#541).

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
break. A computed `<title>` or `<textarea>` gets `v-pre` from the framework table (#549), which
Vue 3 and petite-vue honour; attributes, and RCDATA under AngularJS, stay documented limitations
(guide §7), bounded by `ng-non-bindable` and
CSP. I don't recommend a per-template "client framework mode". Mount points are chosen at runtime
and often in a layout, so a per-template flag is a knob that's easy to get wrong. AngularJS is not supported
(EOL): its directive names are denied by one coarse superset rule (ignoring `- _ :`, a name starting
with `ng`, `xng` or `datang`), and nothing of its algorithm is modelled.

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

`HtmlParserDifferentialTest` against attoparser was deleted once the oracle landed (#325). It tested
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

Amended Oct 25, 2026 (§11): principle 5's "checks this against a spec parser in CI" now reads
"checks this at build time, for every template, against two spec HTML parsers". The keep-it-simple
line now sends parser-differential and composition fixes to the verifier. `tessera-compiler` may
shade the two pinned validator.nu htmlparser versions (§11.4), and nothing else.

Simplified Oct 25, 2026 (#404): the shade exception names one pinned parser, and keep-it-simple
says the verifier rejects error-recovery parsing rather than model it, with the divergence policy
of §11.4. Principle 5 now reads "checks this at build time, for every template, against the spec
HTML parser, and accepts only parses without errors, outside the constructs where browsers are
known to split".

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
| **3. Scripts** | Replace #290's regex/division heuristic with the closed prelude grammar (§12.3); `{` in JSON; no holes in SVG `<script>` | Family C by construction | JS oracle green; corpus: no new rejections beyond the quoted ones #287 rewrites |
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
- **Oracle drift.** Settled by §11.4: one pinned engine, error-free parses only, and a coarse
  exclusion for every split the nightly detector (#399) finds between it and parse5, Chromium,
  Firefox or WebKit.
- **Node in CI.** Keep `mvn verify` Java-only (validator.nu, Rhino). Node-based checks (parse5,
  jsdom frameworks, real browsers) run nightly and before releases.
- **Scope creep.** This plan is phased so phase 0 stands alone. If we stop after phase 1, we still
  have a gate that finds these bugs before reviewers do.

## 11. The build-time context verifier (decided Oct 25, 2026)

This section supersedes §4 (b) ("no in main code"), and it supersedes §5 as the security argument
for families A and B. §11.7 says what happens to each part of §5. Tracker: #364. The research
memo and prototype are not published.

**Simplified Oct 25, 2026 (#404, tracker #405).** An independent architecture review found that
the verifier tried to accept every spec-safe template, including markup that parses only through
error recovery. That forced us to model engine and browser splits forever (#398, #401). No
corpus template reaches those splits, and only 9% of the accepted ones use error recovery at
all, most of them one layout idiom. @dlorenc decided:

- one engine;
- error-free parses only;
- three coarse exclusions where browsers are known to split;
- a divergence the nightly detector finds becomes an exclusion, never a modelled algorithm.

§11.4 states the boundary; this section describes the target. The tracker's wave plan takes the
code there. Until #406 lands, `main` has no error check: it accepts some markup that parses only
through error recovery (101 of the 1,120 corpus templates it accepts) and checks it by modelling
recovery, which has the known gaps #398 and #401. So on that markup `main` is weaker than this
section, not stricter.

### 11.1 Decision

A spec HTML parser decides contexts at build time, for every template. Translation validation
done as abstract interpretation:

1. **Record the skeleton.** For each template, the processor records the skeleton (static text,
   holes, control flow, fragment insertions) from the exact strings the generator writes (#366).
2. **Run the parser.** It runs the skeleton through the pinned spec HTML parser (§11.4), feeding
   static bytes as they are. At each hole it reads the parser's real state.
3. **Accept only error-free parses.** On every path, with scripting on and off, the parser must
   report no parse error, outside a short allow-list. No path may reach an excluded construct
   (§11.4).
4. **Accept only inert holes.** A hole is accepted only if its escaper's output can't change the
   parse in that state (§11.2).
5. **Otherwise, a build error.** Anything else is a build error with file, line, column and a
   fix-it (#367, #368). Fail closed.

Output bytes don't change: the verifier only accepts or rejects. No parser runs at render time,
nothing uses reflection, and the runtime stays dependency-free. So principles 1 and 4 hold.

The compiler used to guess contexts with a hand-written model of HTML (`HtmlParser`,
`MarkupTokenizer`, `Contexts`), and nothing checked the guess. With the verifier, the algorithm
browsers implement decides, exactly, once per template. That model stays only to build Tessera's
tree and give early, readable errors (#411). When it disagrees with a browser, the result is a
build error, never XSS.

### 11.2 Soundness argument

The verifier doesn't sample values. The argument has three parts.

**Escaper output is inert in the observed tokenizer state.** From `Escapers`:

| Escaper | Output alphabet | Accepted states | Why it's inert |
|---|---|---|---|
| `text` | no `< > " ' &`, NUL or lone surrogates, except the complete references `&amp; &lt; &gt; &quot; &#39;` and the complete comment `<!---->` | data state (in the modes below); RCDATA; RAWTEXT only in never-executed elements (`noscript` with scripting on, `iframe`, `xmp`, `noembed`, `noframes`) | Data state reacts only to `<`, `&` and NUL. A complete reference or comment returns to data state with nothing pending. RCDATA and RAWTEXT react only to `</` + name |
| `rcdata` | as `text`, without the comment | RCDATA | as above |
| `attr`, `url`, `resourceUrl`, `refresh`, `jsonAttr` | no `"`; `&` only in complete references | attribute value (double-quoted), with the parser's current attribute name equal to the hole's | That state reacts only to `"`, `&` and NUL |
| `json` | no `< > & '`, backtick, `$`, U+2028/U+2029 or controls | script data, with an HTML `script` as the current node | Script data reacts only to `<` |

Every other state is rejected: tag open, character reference, comment, CDATA, escaped script
data, `text` in script data or `style`, `json` anywhere but HTML script data (including SVG
`<script>`). These rules cover #295-#299, #303's `&name`, #338 and #353 without naming any of
them.

**The tree builder treats the value uniformly.**

- The static markup around the value parses without errors. So nothing is foster-parented,
  no misnested formatting element is reopened, no element is closed by an end tag that isn't
  its own or an implied end tag the spec allows, and the adoption agency only pops the element
  its end tag names. The Noah's Ark clause and reconstruction never change anything. Neither
  does `frameset-ok`: a `<frameset>` start tag in body is always a parse error, and the verifier
  rejects `<frameset>` anyway.
- In data state the value produces only character tokens, and for `text` empty comments.
- The modes in body, in cell, in caption, in select (and in select in table), in template,
  foreign content and text handle every character token the same way: append.
- The value itself isn't fed to the parser, so the error check can't see what it does. The
  hole-state rules cover that. They reject text holes in every other mode (table, table body,
  row, column group, head and the rest), where non-whitespace text would be foster-parented or
  dropped.
- Dynamic `SafeHtml` and `Fragment` values are covered by the `FLOW` predicate (§11.3 item 2).

So after the value the parser is in the same state as before, for every value. The rest of the
document parses identically, and the value is in the node the parser reported.

**Control flow is exact up to state equivalence.**

- **The state signature.** Tokenizer state, current attribute name, insertion and original mode,
  the open-element stack as (namespace, name), template modes, form and head pointers, quirks and
  the `annotation-xml` integration-point flag.
- **Branches** take the union of their exit states. Unions add infeasible combinations, so they
  can only add false positives. The error check runs on every branch.
- **Loops** iterate to a fixed point (Go `html/template`'s idea, with the real parser as the
  transfer function). A loop that doesn't converge in 4 rounds is rejected. An error in any round
  is an error.
- **Static fragment insertions** are analysed at each call site, memoised per (fragment, entry
  state, bound fragment arguments). Recursion is a fixed point too. Recursion that doesn't
  converge, or that reaches a bad state only through data, is "markup structure depends on data".

### 11.3 Limits of the argument

1. **Skeleton fidelity.** The skeleton must be the bytes the generated code writes, or it
   becomes a new differential. #366 records it at the single emission path and checks every
   render in the test suite against it. #324 checks every hostile render.
2. **Dynamic `SafeHtml` and `Fragment` values.** These aren't known at build time, and the error
   check can't see them. The argument assumes their exit state equals their entry state. That
   holds only if the producer emits balanced content that implies no end tags in that state.
   Content kinds are therefore predicates on the insertion state (`FLOW`, including
   `ContentKinds.closer`), plus a check on producers (#329). `SafeHtml.unchecked` stays the
   caller's responsibility.
3. **One parser.** The guarantee is relative to the WHATWG algorithm as the pinned engine
   implements it, on error-free parses, outside the exclusions. The supported browsers are tested
   to agree there, not proved to. The nightly detector (#399, not yet on `main`) is to compare the
   engine's trees with parse5, Chromium, Firefox and WebKit on the cases it exports that the
   verifier accepts (seeds, generator cases, fixtures). Until it lands, the Node job compares
   renders across browsers only. A split it hasn't found yet is a gap until it does.
4. **The allow-list.** Errors on the allow-list are accepted. It holds only the missing-doctype
   messages, which say nothing about the tree. Widening it needs a measurement and an argument in
   the PR.
5. **Entry context of partials.** A partial (a view with no doctype or `<html>` start) is parsed
   as a fragment in the context its root element implies, and errors count only there. A
   partial rendered in some other context isn't covered.
6. **The inertness lemma itself.** It's argued here and tested by #324 (state unchanged across
   every escaped hostile value), not proved. A Lean proof is optional work under #312.

### 11.4 The boundary: one parser, error-free parses, three exclusions

**One parser.**

| Engine | Source | Why |
|---|---|---|
| Current | upstream htmlparser from the pinned validator 26.10.12 sources release (`nu.validator:validator:26.10.12:sources`), the Java source Gecko's parser is translated from | current spec, 99.7% of html5lib tree-construction tests |

Until #408 a second engine ran, the htmlparser 1.4.16 snapshot, and a template needed both. It was
there to bracket two known splits: classic and customizable `<select>` parsing, and the old
fragment-case foreign-content breakout. Exclusion (c) covers the first. The second is a parse
error. The measurement found no template, seed, probe or generator case that only the 1.4.16
engine rejects and the new rule accepts, and #408 removed it with no verdict change.

**Error-free parses only** (#406).

- On every path, with scripting on and off, the parser reports no tokenizer or tree-builder
  error.
- The allow-list is the missing-doctype messages only. Most views are partials.
- A partial counts errors only in its implied fragment context (`tr`: `tbody`; `td`, `th`: `tr`;
  `option`, `optgroup`: `select`; `li`: `ul`; otherwise `body`), not as a document.
- A fragment no template calls is checked on its own (#461): declared in HTML content, in the
  fragment contexts its root implies, as above (as a document only if it is a page); declared in
  `<svg>`, `<math>` or `<select>` content, in that element. Declared where no fragment fits (raw
  text, mixed `<svg>`/`<math>`, `t:remove="tag"`), it is rejected (`UNCALLED_FRAGMENT_FITS_NOWHERE`).
- The error is reported at the static that holds it, with a fix-it for the common classes: a
  second `<body>`, a block element in `<p>`, content between `</head>` and `<body>`, content
  directly in a table, a missing space between attributes.

**Coarse exclusions**, checked after every tag ((d): at every CDATA section), where browsers are known to split:

| | Rejected | Why | Issue |
|---|---|---|---|
| (a) | an `svg` or `math` element open with `td`, `th`, `caption`, `tr`, `tbody`, `thead`, `tfoot`, `table` or `template` below it on the stack (the fragment context counts) | vnu and Firefox reset the insertion mode to "in body" at the foreign element, while the spec, Chromium and WebKit go on to the cell | #398 |
| (b) | an HTML table part directly in `<template>`, or a table mode in the template-mode stack | vnu and Firefox handle `</table>` there with `findLast`, not table scope, and close the outer table | #401 |
| (c) | any element other than `option`, `optgroup` and `hr` open above `<select>`, in composed output | classic `<select>` parsing (Firefox ESR, parse5) drops what customizable `<select>` (Chromium, current Firefox, WebKit, vnu) keeps | #305, #328 |
| (d) | a CDATA section whose current node is an HTML integration point (SVG `foreignObject`, `desc`, `title`; an HTML `annotation-xml`) or a MathML text integration point (`mi`, `mo`, `mn`, `ms`, `mtext`), with or without a `>` in it | vnu and Firefox read a CDATA section there, as the standard does; parse5 and Chromium read a bogus comment that ends at the first `>`, so the markup after it is live HTML | #296, #441 |

They are coarse on purpose. Exclusion (a) rejects every `<svg>` in a table cell, not only the
ones that reset. A wider exclusion that is obviously right beats a narrow one that models a
browser.

**The divergence policy.** When the nightly detector, a reviewer or a report finds the engine
and a supported browser building different trees for markup the verifier accepts:

1. add the markup as a seed the verifier must reject;
2. add a coarse exclusion (a stack shape or an element in a context) that covers the whole
   class, with a negative test and a positive one;
3. never model either algorithm in the verifier, and never patch the engine.

PR #402 modelled both reset algorithms. #406 replaces it with exclusion (a), and its tests
and its 45,056-variant differential become test cases. Known deviations are listed in
[security-model.md](security-model.md) §7.1.

**Packaging (#365).**

- **Build.** A build-only module fetches the sources jar from Maven Central with a pinned SHA-256
  and compiles it with `--release 8`. Upstream doesn't publish a Java 8 jar; its pom targets 1.8,
  and the source builds that way.
- **Relocation.** It is relocated to `dev.tessera.compiler.internal.html5.current`.
- **Accessor.** One Tessera-owned accessor class reads the parser state and its errors. The
  parser source itself isn't patched.
- **Shading.** `tessera-compiler` shades it into its jar with its MIT notice. Consumers see no
  new dependency, and the processor still runs in a JDK 8 javac (`Jdk8ProcessorTest`).
- **Bumps.** Bumping the pinned validator release is its own PR, with the corpus and seed verdicts
  and the nightly detector's findings diffed.

### 11.5 What the verifier doesn't cover

| Family | Status | Owner |
|---|---|---|
| C. JS lexical position | Not covered. The verifier makes one input exact: a `json` hole is in an HTML script's data | closed prelude grammar (§12.3) |
| D. Client frameworks | Not covered | deny-by-default names (#330), framework table (#333), harness (#334), CSP nonces (#335, PR #361); AngularJS unsupported, with a coarse `ng` deny (§12.4) |
| E. URL and CSRF policy | Not covered. The element and attribute a value lands in become exact | inert-text list and single-URL grammar (§12.2), closed form shape (§12.7) |
| CSS inside `style` | Not covered beyond "the value stays in its attribute" | `CssValue` rules |

§12 converts these areas, and the runtime and name-folding code around them, to allow-lists and
closed grammars.

### 11.6 Cost

Measured with the prototype (memo §5):

- **Seeds.** It flagged 120 of the 121 family A/B seeds live on 0dda2f1. The one miss
  (`pr304-9`) was a prototype lowering artefact. It also found 2 holes the value oracle missed.
- **Corpus.** 1,585 converted templates, 1,372 of which main's parser accepts. The only real
  rejections are 25 (1.8% of 1,372) recursive rich-text fragments. That pattern is data-dependent
  structure, a build error by decision. The fix-it is `html.sanitize` to `SafeHtml` (#369, #262).
  398 more were flagged only because the prototype couldn't resolve an insert, 395 of them layout
  `Fragment` parameters in `<head>`. #367 analyses those at the call site.
- **Build time.** The naive prototype took p95 155 ms per template; the targets with
  memoisation are in #367 (p95 ≤ 50 ms, under 10% of javac time).

The error-free rule, measured on `main` 49060ba (`~/tessera-handoff/errorfree-measurement.md`):

- **Safety.** It accepts nothing today's verifier rejects: 0 of 134 corpus rejections, 0 of 130
  seeds, 0 of 9 probes, 0 of 271 generator cases, and 0 of the 10,837 #398/#401 variants where
  Chromium and Firefox disagree. The error check alone rejects all 10,837.
- **Corpus.** 101 of 1,120 accepted bench templates (9%) become errors. 80 of them are one layout
  idiom, `~{layout :: layout(~{::body})}`, which nests a second `<body>`. #409 teaches
  `tessera-migrate` the rewrite, and #407 checks whether a compiler bug causes some of them. The
  other 21 are broken markup: a block in `<p>`, script/meta/link between `</head>` and `<body>`,
  headings in a table, a missing space between attributes. The exclusions never fire in the
  corpus.
- **Size.** It deletes about 1,000 hand-written lines (the classic engine and its workarounds,
  reconstruction, Noah's Ark, foster and frameset modelling, most of the producer-promise checks)
  and about 21.5k lines of upstream source that are no longer built. It adds about 50.

### 11.7 What happens to §5

| §5 part | Role now |
|---|---|
| §5.1 invariant | Unchanged. The verifier enforces it at build time; the oracle (#324) tests the verifier |
| §5.2 token rules (#327) | **Friendly early errors**, not the boundary. `MarkupTokenizer`'s positive grammar stays for its messages. Further rules only where they give a better message than the verifier |
| §5.3 parse modes and allow-lists (#328) | The verifier's exclusions (§11.4) own the browser splits. `HtmlParser`'s mode rules stay only where their message is better (#411). `plaintext` stays an error. The element allow-list moves to phase 4 as a framework/typo rule |
| §5.4 script prelude rule (#540) | Unchanged |
| §5.5 content kinds (#329) | **Redefined** as one parser-state predicate, `FLOW`, checked by the verifier. `PHRASING` is backlog (#397) |
| §5.6 attribute names, framework table, CSP | Unchanged (family D) |
| §5.7 oracle gate (#324) | **Repurposed.** It tests skeleton fidelity and the inertness lemma, and keeps value checks for C, D and E. Its Node job is the nightly divergence detector (#399) |
| §5.8 what doesn't change | All of it, except the compiler's dependencies: `tessera-compiler` shades the pinned parser (§11.4). The runtime, the starters and generated code stay dependency-free |

A new parser-differential or composition report becomes a verifier test case, not a special case
in `HtmlParser`, `MarkupTokenizer` or `Contexts` (handoff.md). If browsers split on it, it also
becomes a coarse exclusion (§11.4). For families A and B this replaces §7's "fix it by shrinking
the profile". #378 makes the parser state name each hole's escaper: a hole written with another
escaper is rejected, also when only some of its paths reach that state. For element text the
verifier chooses `text` or `rcdata` itself, and the processor sets it in the generated code
between verifying and writing. The raw-text name tables stay: Tessera's tree needs them before a
skeleton exists, and the profile's messages use them.

## 12. Closing the remaining families by class (decided Oct 30, 2026)

Tracker: #544. This section extends §11 to the areas §11.5 lists as not covered. It doesn't
change §11.

### 12.1 Why

§11 closed families A and B. The XSS reports that came after it were all in areas §11.5 lists
as not covered:

| Area (§11.5) | Bugs |
|---|---|
| Attribute values (E) | #414 `ping`, #415 `manifest`/`srcset`, #416 and #421 SVG CSS `url()` attributes, #419 attributes the tree builder reads |
| Scripts (C) | #508 number subclasses, #509 expression position, #511 and #533 negative numbers and integers |
| Frameworks (D) | #529 and #531 AngularJS directive spellings |
| Runtime content checks | #530 and #535 unchecked HTML messages |
| Names and character classes | #506, #507, #519, #534 Unicode case folding |

Each one was a hand-written model that permits by default: a name table with a permissive
fallback, a JavaScript lexer, a copy of AngularJS's `directiveNormalize`, a runtime test for
"looks safe", Java's Unicode folding where browsers fold A-Z. Each fix added a case to the model.
The expression language, which is allow-listed and checked by javac, has had none.

So the decision is the one §0 made for markup, applied to what is left: **convert each area to
an allow-list or a closed grammar, accept the migration build errors, and find what remains
mechanically instead of by review.**

### 12.2 Attribute values (#539)

- **Inert-text list** (PR #552). A computed value is written as plain `ATTRIBUTE` only into an
  attribute on `inert-attributes.txt` (206 names whose spec value type is text, keyword, number,
  boolean, ID refs or tokens). Any other standard attribute without a context of its own is
  `TYPED_ATTRIBUTE`: string literals, numbers and a choice between literals only (#565, PR #566
  removed the safe-type escape hatch). `<meta content>` is inert only with a static, harmless
  `name`. Review found two gaps already on `main`, a computed `<param value>` and
  `<meta name=htmx-config content>`; both are now typed. The harmless names were a three-name
  deny-list until #590 made them an allow-list (`description`, `keywords`, `author`,
  `application-name`, `generator`, `color-scheme`, `theme-color`), with no `property` or
  `itemprop`: `apple-itunes-app`, Open Graph and any name a browser or crawler adds later are typed.
- **Single-URL grammar** (PR #567). The URL escapers apply only to attributes whose spec grammar
  is exactly one URL. Lists and values whose meaning depends on another attribute (`ping`,
  `srcset`, SVG animation `values`/`to`/`from`/`by`) are `TRUSTED_URL`: literals or a
  `TrustedUrl`. `URL_LIST` is deleted.

An attribute nobody classified is now a build error, not a plain attribute.

### 12.3 Scripts (#540, #541)

- **Closed prelude** (PR #553). In a JavaScript `<script>`, `[[...]]` is allowed only as the whole
  value of `(const|let|var) NAME = [[...]];` at the top, with only whitespace and `//` comments
  around. `NAME` is an ASCII identifier, not a reserved word and not `location` (in a classic
  script `var location = ...` navigates; the reviewer found this live on `main`, and the old rule
  allowed it). Every other hole, and `t:text` on a JavaScript script, is a build error with a
  fix-it. `TextInlining`'s JavaScript lexer and adjacency rules are gone (§5.4's "don't lex
  JavaScript").
- **`(json)`.** Every value is written in parentheses, which replaces #511/#533's per-type cases.
- **Exact-class numbers.** A `BigDecimal` or `BigInteger` is written only when its class is
  exactly the JDK class; a subclass throws, in scripts, bindings, messages and styles (#508's
  class).
- **Migrate hoist** (PR #563). `tessera-migrate` moves each value outside the prelude into
  `var _tN = [[...]];` above the first code, using the compiler's grammar, so the compiler's
  checks are unchanged. 33 of 36 bench scripts needed it.

### 12.4 Frameworks (#542)

- **AngularJS unsupported** (PR #551). Tessera no longer models `directiveNormalize`, the model
  behind #529, #531 and #532.
- **Coarse `ng` deny** (PR #551). A name or class token is denied when, ignoring A-Z case and
  `- _ :`, it starts with `ng`, `xng` or `datang`: a superset of every spelling AngularJS reads, in
  the compiler, migrate, the sanitizer and the test oracle. It also covers computed class tokens,
  which had no check before: `Attributes.classes` drops matching tokens at render time, at the
  one place a computed class value is written.
- **Vue in RCDATA** (#549, PR #560): a framework-table entry adds `v-pre` to a `<title>` or
  `<textarea>` with computed content, where a comment can't break `{{`.
- **htmx 1.x sockets** (#562, PR #564): `hx-ws`/`hx-sse` are build errors, instead of parsing
  their `connect:` grammar.

### 12.5 The runtime never classifies content (#535, #537)

The runtime never decides that a value is safe by inspecting it. A message is HTML only if it is
exactly what a build-checked pattern produces; anything else is escaped as text, with `{{`
broken (PR #536). PR #538 fixed the double escaping that followed. A new runtime check of the
form "if it looks harmless, write it raw" is a design error, not a fix.

### 12.6 ASCII-only names and character classes (#545)

Browsers fold A-Z only and split on HTML whitespace. Every HTML, CSS and URL comparison and
character class goes through named ASCII helpers; Java text (expressions, bean names, message
patterns) goes through `JavaStrings`. `AsciiOnlyTest` in the compiler and the runtime fails on
`equalsIgnoreCase`, `toLowerCase`, `trim`, `Character.isWhitespace`, regex `\s` and the like
outside a pinned one-file allow-list (PR #546).

### 12.7 CSRF: one closed form shape (#550)

A `<form>` gets the token only if its method is a static `post` and its action is absent, static
and same-origin by shape, or a computed `@{/literal-path}` link re-checked at render. A computed
`method`, any other computed action on a POST form, and a cross-origin `formaction`,
`<base href>` or static htmx request URL on any element are build errors (PR #555). This replaces
eight case-by-case patches, and closed two live leaks (`hx-post` on the form, and an `hx-ws`
socket around it).

### 12.8 `{{` through one function (#547, #571)

At runtime `Escapers.appendText` is the only function that writes value text into element text
(`t:text`, `SafeHtml.escape`, sanitizer text, HTML message arguments), and it writes every `{` and
`}` of a value between two empty comments, so a value's brace never meets another brace, even
when the value is empty. `Escapers.rawEdges` breaks a `{` at the edges of a `SafeHtml` and of a
checked HTML message. `TextChokePointTest` enforces both (PR #569, PR #577). At build time the rule
is coarse and looks at no neighbours: in element text, CDATA in foreign content included, a static
`{` or `}` is allowed only inside a complete `{{ ... }}` that opens and closes in one literal, and
any other static brace is an error. Checked messages get the same rule as formatted, and a script
`{{` must close before the next hole. #569's per-literal edge rule is deleted. In `<xmp>`,
`<iframe>`, `<noembed>`, `<noframes>` and `<noscript>`, where a comment can't break a brace, a
value is an error. A computed `<title>` or `<textarea>` gets `v-pre` (#549), and any static brace
in one is an error. With AngularJS gone (#551), the sanitizer no longer handles braces in
attributes.

### 12.9 Mechanical discovery (#543)

`HostileSweepTest` (PR #570) runs in `mvn verify` (in the compiler module's default surefire
execution only, not again under `release-8`). On the commits before their fixes, its spike found
10 of 11 historic bugs. It missed #531, in the sanitizer, so the sanitizer is now one of its three
passes:

- **Attributes.** Renders every computed (element, attribute) pair the compiler accepts with
  hostile values, parses the output with validator.nu, and classifies each attribute against a
  sink table written from the specs (`HostileSinks`), not from `Contexts`. This is a ratchet: a hit
  fails the build unless `oracle/hostile-sweep-baseline.txt` lists it with a justification, and a
  listed hit that no longer occurs fails too, until its line is deleted.
- **Sanitizer.** `html.sanitize` under `BASIC`, `RICH` and the widest policy, over every element
  and attribute a policy can allow (and some it never allows), with the same values, the same
  table and the same baseline.
- **Scripts.** The closed prelude grammar (§12.3) and the expression positions it rejects, for
  each value type. For every shape the compiler accepts, Rhino checks that each value is one literal
  and that the code around it doesn't change. There's no baseline: any hit fails.

Confirming hits in browsers in the nightly job is later work.

### 12.10 Policy going forward

- A new XSS in an area not yet converted converts that area to an allow-list or a closed
  grammar. It doesn't add a case. The PR names the class it closes.
- In a converted area, a report means the list or grammar is wrong: fix the entry and add a
  negative test, and add a sweep row so the class stays found.
- Removing support (AngularJS, htmx 1.x sockets, safe types in typed attributes) beats
  modelling it. The cost is a build error with a fix-it, and `tessera-migrate` rewrites
  what it can.
- Parser and composition reports still go to the verifier (§11.4).
