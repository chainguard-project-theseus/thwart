# Context model: the Tessera HTML profile

This document specifies the HTML profile, the set of templates the compiler accepts, as
tables. It is the written form of §5.2-§5.6 of
[security-architecture.md](security-architecture.md). The threat model it serves is in
[security-model.md](security-model.md).

The tables describe the profile as it will be after phase 2 (#309). Each row also says what
ships today. Where this document and [mvp-design.md](mvp-design.md) disagree about current
behaviour, mvp-design.md describes what ships. This document describes the target and marks
every gap.

**Since Oct 25, 2026 the security boundary for markup contexts is the build-time verifier**
([security-architecture.md](security-architecture.md) §11, tracker #364), enforced in every build
since #367. It runs every template through two spec HTML parsers and enforces section 1 per hole,
whether or not a row below has its own rule; its rule table is section 9. The profile of principle
3 is what the verifier accepts, narrowed by the rows below. The rows in sections 2, 3 and 5 run
first, as friendly early errors or browser-divergence exclusions (security-architecture.md
§11.7). Sections 4 and 6 are unchanged: the verifier doesn't cover script
lexical position, URL policy or client frameworks.

## How to keep this document current

- A PR that changes `HtmlParser`, `MarkupTokenizer`, `Contexts`, `TextInlining`, `Escapers` or
  the framework table updates this document in the same PR.
- A PR that widens the accepted language says so in its description, and adds the oracle seed
  or generator value that checks the new row.
- Every row names its check, or says `none yet`. A `none yet` row is a gap to close with a seed,
  a generator value or a unit test.

## Columns

| Column | Meaning |
|---|---|
| **Status** | `verifier (#367)`: enforced for every template by the build-time verifier (`verify/ContextVerifier`, rule table `verify/Rules`, section 9), whatever the row's own rule says. `main`: enforced on `main` (f135112). `#N`: enforced in open PR #N, not yet on `main`. `phase N (#M)`: not implemented; issue #M owns it, in that phase of security-architecture.md §8. `not enforced`: no check and no issue. `partial`: the row says which part is on `main` |
| **Checked by** | Unit tests on `main`, plus the oracle: seed IDs `A0`-`A62` and `B0`-`B11` from the #320 seed corpus (#347), and axis values of the template generator (#343). The oracle checks are #344 (tree invariant, merged), #341 (script slots, merged) and #342 (context check, merged). Their unit tests run in `mvn verify`; the oracle runs over the seeds and generated cases only once the gate (#324) lands |
| **Proof** | `Lean`: a predicate in the Lean model of T1 (tcnghia/harder#16, not upstream). `JML`: an OpenJML contract in `docs/rule-contracts.md` on tcnghia/harder#18 (the rule functions on `main` 99e35c5), or on a fork branch where the row names one (not upstream). `Esc`: an escaper postcondition proved on tcnghia/harder#11 (not upstream). `not yet` means no proof states the row |

The JML contracts on tcnghia/harder#18 are stated over the rule functions on `main`. Some proofs
rewrote a function without changing its behaviour (`indexOf` to a loop, for example). Where the
name on `main` differs, the row gives both. The tag-end, namespace and `select` contracts are on
fork branches `proof-b-tse`, `proof-b-301` and `proof-b-305`, stacked on #353 (merged), #301 and #305.

The Lean model is token-level. It proves that a WHATWG tokenizer, with the tree builder's
tokenizer switches, reads `render(T, M)` as the tokens the compiler assigned. It does not model
which node a token attaches to: implied end tags, nested `<form>`, the adoption agency and foster
parenting are outside it. Its slices are: 1 text and TEXT holes; 2 tags, comments and DOCTYPE;
3 `<script>` with JSON holes; 4 `title`, `textarea`, `style`, `iframe`; 5 `noscript` (scripting
enabled only). `InProfile` (`Profile.lean`) is the whole profile. `t1_profile_tree`
(`ProfileT1.lean`) states token-level T1 over HTML, raw text, script, `noscript`, SVG, MathML and
`select`, with a tree builder that tracks insertion modes (`profileTB`). It proves that each hole
lands in the token, tokenizer state and namespace the compiler chose, and that every element
closes. That is the per-hole part of section 1. The other part, that the static structure is the
same for every value, is what the oracle checks; no proof states it. `t1_profile_tree` does not
cover CDATA in foreign content, the tree-level effects of tables and `template`, or the content
kinds of inserted fragments (section 5); those rows say `not yet`. The Lean allow-list still
leaves out `template` and the obsolete elements that decisions 5 and 6 accept.
The model is checked against the test suites it can run: `profileTB` passes 1,047 WPT
tree-construction tests (926 skipped as unmodeled), and the tokenizer passes 1,439 html5lib
tokenizer tests (968 skipped), with no failures.

## 1. The invariant

For every template T the compiler accepts, every model M, and every supported browser or spec
HTML parser that parses `render(T, M)`:

- each hole's value lands in the node, context and namespace the compiler chose;
- the static structure is the same for every value: the benign and hostile trees are equal.

This is a per-hole claim (decision 9). It does not say that the parsed tree is the one the
compiler recorded: implied end tags, foster parenting and the adoption agency can move static
nodes, the same way for every value, without moving a hole out of its context.

| Part | Status | Checked by | Proof |
|---|---|---|---|
| Static structure is the same for every value | verifier (#367); the rules below are early errors | `ContextVerifierRulesTest`, `SeedVerifierTest`; #344 `STRUCTURE_CHANGED`, `MARKER_IN_NAME` over every seed and generated case | not proved; the oracle checks it |
| Each value lands in the node, context and namespace the compiler chose | verifier (#367): the parser state at each hole | `ContextVerifierRulesTest`, `SeedVerifierTest`; #342 `CONTEXT_MISMATCH` | Lean token-level T1 (`t1_profile_tree`; not CDATA in foreign content, the tree-level effects of tables and `template`, or fragment insertion) |
| No value runs as script, handler or `javascript:` URL, or loads a cross-origin resource | partial | #344 `LIVE_SCRIPT`, `LIVE_HANDLER`, `JS_URL`, `CROSS_ORIGIN_RESOURCE`; #341 `JS_NOT_LITERAL` | `Esc` for `text`, `attr`, `url`, `json` strings |
| Static structure does not depend on the browser | verifier (#367) for the two embedded parsers (current upstream and 1.4.16, both must accept); real engines checked by the Node job | validator.nu htmlparser 1.4.16 and the htmlparser inside validator 26.10.12 in #344; parse5, headless Chromium, Firefox 140 ESR and WebKit (Playwright's build) in the Node job (#345, #354; on Chainguard Checks after every merge and on request, over a fixture until #324) | not yet |

## 2. Token-level rules (§5.2)

The tokenizer accepts only these forms. Anything else is a build error with file, line and
column. These rules are friendly early errors; the verifier (#367) is the boundary. A row marked
`verifier (#367) for holes` is one where a value next to the construct lands in a parser state the
verifier rejects (a tag, a comment, a character reference, CDATA, raw text after `<`). #327 is re-scoped to rules that give a better message than the verifier; the
`phase 2 (#327)` rows below are kept only if they do.

| Construct | Accepted | Rejected | Status | Checked by | Proof |
|---|---|---|---|---|---|
| Element name | `[A-Za-z][A-Za-z0-9_.:-]*` | anything else | main | `HtmlParserTest`; `<scr[[s]]ipt>` in `ValueAfterLessThanTest` | Lean `validElementName` |
| Start tag separators | ASCII whitespace (TAB, LF, FF, CR, SPACE) | U+000B, U+001C-U+001F and other Java-only whitespace in a tag (#338) | verifier (#367) for holes; main (#297) | `XssContextTest.REJECTED`; generator interference "U+000B/U+001F/U+2028/U+3000 in a tag" | Lean `TagAttrsOK` (assumes ASCII separators); JML `tagEnd` (fork branch `proof-b-tse`) |
| Quotes in tags | `"` or `'` only as the delimiters of a quoted value | a quote in a name, in an unquoted value, or right after a quoted value | verifier (#367) for holes; main (#297) | seed `A13`; `HtmlParserTest`, `XssContextTest` | JML `tagEnd` (fork branch `proof-b-tse`); Lean `SrcTagOK` |
| Attribute names | names without `=`, `<` or quotes | `=`, `<` or a quote in a name | main | `HtmlParserTest` | Lean `validSrcAttrName` |
| Unquoted values | `[A-Za-z0-9_.:/#-]`, not ending in `/` before `>` | other characters | verifier (#367) for holes; partial: `/` before `>` and quotes on main (#297); a value starting with `=` on main (#353); the character class is phase 2 (#327) | generator interference `<textarea x=y/>` | Lean `SrcValue.ok` (weaker: any non-space, non-quote, non-`>`) |
| Attribute values: `&` | only as a complete character reference | an incomplete `&name` (decision 13) | phase 2 (#327) | generator interference `&amp` | not yet (a decoding rule; the token-level proof doesn't need it) |
| Duplicate attributes | one attribute per name | two attributes with the same lowercased name (`toLowerCase(Locale.ROOT)` on main; ASCII in the target) | main | `HtmlParserTest` | Lean `TagAttrsOK` (`Nodup`) |
| `/>` | on void HTML elements and foreign elements | on non-void HTML elements, except where Tessera writes the end tag (`<textarea t:field/>`) | partial: `script`, `style`, `title`, `textarea`, `xmp`, `iframe`, `noembed`, `noframes`, `noscript` on main (#298); `<div/>` and other non-void elements phase 2 (#327); anything but `>` after `/` on main (#353) | seeds `A11`, `A12`; `BrowserRawTextTest` | Lean `NodeOK` (`selfClosing = false` on non-void); `t:field` case not modeled |
| End tag | `</name>` matching the open element | stray or mismatched end tags; `</` + non-letter | main (#296) | `HtmlParserTest`; generator interference `</ x>` | Lean slice 2 `run_endTag`; JML §3 `bogusComment` |
| Comment | `<!--` text `-->`; text does not start with `>` or `->` and has no `--!>`, `<!--` or `-->` | early-ending comments | verifier (#367) for holes; partial: `<!-->`, `<!--->`, `--!>` on main (#295); a nested `<!--` check is fork-only hardening (tcnghia/harder#17), not a browser difference (G10) | seed `A14`; `HtmlParserTest`, `XssContextTest` | JML §2 `earlyCommentEnd`; Lean `CommentTessera` |
| Conditional comments | as written in petclinic (`<!--[if ...]>...<![endif]-->`) | | main | #296 corpus scan | Lean `CommentTessera` |
| Doctype | `<!DOCTYPE html>`, ASCII case-insensitive, first token only | other DOCTYPEs; a DOCTYPE after other content | partial: DOCTYPE with an early `>` rejected on main (#296); the exact form and position phase 2 (#327) | `HtmlParserTest` | Lean `DoctypeOK`, first node only (`InProfile`) |
| `<?`, `<!` other forms | none | processing instructions, bogus comments | partial: `<?`, `<!x` in text and early `>` on main (#296); a whole `<?...?>` with no early `>` still compiles until phase 2 (#327) | generator interference `<?x>`, `<!x>`; `HtmlParserTest` | JML §3 `earlyEnd`, `bogusComment` |
| CDATA | inside SVG and MathML only, with no hole and no `>` before `]]>` | CDATA in HTML content, including the HTML descendants of `foreignObject`, where it is a bogus comment (decision 11) | verifier (#367) for holes; partial: CDATA with an early `>` or a hole rejected on main (#296); CDATA in HTML content phase 2 (#327) | seeds `A10`, `A54`, `A55` | JML §3 `earlyEnd`, `cdataHole`; Lean: not modeled (sink) |
| Text: `<` | only as the start of an accepted tag or comment | a literal `<` that is not a tag (write `&lt;`) | partial: `<` followed by `!`, `?` or `/`+non-letter on main (#296); `a < b` still compiles until phase 2 (#327) | `ValueAfterLessThanTest` (controls) | Lean `TextOK` (`'<' ∉ s`); `StaticText` for main's rule |
| Text: hole after `<` | a hole after any other text | a hole after static `<`, `<!`, `<!-`, `<?`, `</`+letters | verifier (#367) for holes; main (#299) | seed `A15`; `ValueAfterLessThanTest` | JML §5 `htmlMarkupStart`, `scriptMarkupStart` (`markupBefore` on main); Lean `NoLtBeforeHole` |
| Text: `&` | only as a complete character reference | an incomplete `&name` (decision 13) | phase 2 (#327) | generator interference `&amp` | not yet (a decoding rule; Lean treats references as transparent) |
| NUL | no U+0000 | U+0000 anywhere in static markup: text, tags, RCDATA and raw text (decision 8) | phase 2 (#327) | none yet | Lean assumes it (G8) |
| RCDATA and RAWTEXT content | text with no `</name` + whitespace, `/` or `>` before the element's end tag, no hole right after `<` or `</` + letters, and no NUL (decision 10) | the rest | verifier (#367) for holes; partial: end-tag rule on main (#298); hole after `<` on main (#299); NUL phase 2 (#327) | seeds `A8`, `A28`-`A30`; `BrowserRawTextTest`, `ValueAfterLessThanTest` | Lean `RawOK` (`NoRawDanger`, `NoRawLtBeforeHole`); JML §4 `browserEndTag`, §5 |
| Holes | `[[...]]`, `[(...)]` in text; `t:*` attributes | holes in comments, tags, attribute names, element names | verifier (#367) for holes; partial: in element names an error on main (#299); in comments and static attributes `[[...]]` is copied as literal text, not a hole; an error in phase 2 (#327) | `HtmlParserTest`, `TextOutputTest` | Lean `Node.hole` placement |

## 3. Parse modes (§5.3)

The parser keeps a parse mode for every open element. Today `HtmlParser` keeps none: it
decides raw text from the local name (`RAW_TEXT_ELEMENTS`, `Contexts.browserRawText`), and it
has no namespaces. #301 adds namespaces. The verifier (#367) reads the real mode and namespace
from the spec parsers, so #328 is re-scoped to the browser-divergence exclusions (`select`
content, nested `optgroup`, `frameset`, `plaintext`) and friendly errors. The element allow-list
moves to phase 4 (#311).

### 3.1 Modes and what they accept

| Mode | Entered by | Children accepted | Holes | Status | Checked by | Proof |
|---|---|---|---|---|---|---|
| `HTML` | document root; `foreignObject` | allow-listed HTML elements, including `template` (3.2) and the obsolete `center`, `font`, `tt`, `big`, `strike`, `marquee` (decision 6); custom elements (name with `-`); names the framework table lists | TEXT | partial: no allow-list on main, any valid name is accepted (#272); phase 2 (#328) | generator container axis (HTML values) | Lean `NodeOK .html`, `HtmlName` |
| `RAWTEXT(script)` | `<script>` in HTML mode | static script text | SCRIPT (§4) | verifier (#367): JSON only in an HTML script's data; main | seeds `A1`-`A2`, `A32`-`A36` | Lean slice 3 `ScriptOK`; JML §4 `browserEndTag` |
| `RAWTEXT(style)` | `<style>` in HTML mode | static CSS | none | verifier (#367); main | seed `A53` | Lean slice 4 `RawKindOK`; JML §4 |
| `RCDATA` | `<title>`, `<textarea>` in HTML mode | text | RCDATA | verifier (#367); main | seed `A50`; `BrowserRawTextTest` | Lean slice 4 `RawOK`; JML §4 |
| `SVG` | `<svg>` | SVG 2 element names only | TEXT | verifier (#367) for holes; #301 (breakout list); allow-list phase 2 (#328) | seeds `A3`-`A7`, `A19`, `A21`, `A25`-`A27`, `A54`-`A56`, `A58`, `B0`, `B7`-`B11` | JML (fork branch `proof-b-301`); Lean `NodeOK .svg`, `SvgName`, `svg_no_breakout`; token-level T1 (`t1_profile_tree`) without CDATA (not yet) |
| `MATHML` | `<math>` | MathML Core element names only | TEXT | verifier (#367) for holes; #301 (breakout list); allow-list phase 2 (#328) | seeds `A20`, `A22`, `A40`, `A57` | JML (fork branch `proof-b-301`); Lean `NodeOK .math`, `MathName`, `math_no_breakout`; token-level T1 (`t1_profile_tree`) without CDATA (not yet) |
| `SELECT` | `<select>` | `option`, `optgroup` (not nested, decision 3), `hr`, text | TEXT | verifier (#367) for holes, classic and customizable parsing; #305; no nested `optgroup` phase 2 (#328) | seed `A31`; `<select>` wrapper axis | JML `selectContent` (fork branch `proof-b-305`); Lean `NodeOK .select`, `NoOptgroup`; token-level T1 (`t1_profile_tree`) |
| `NOSCRIPT` | `<noscript>` in HTML mode | HTML elements except RCDATA, raw-text, script, `select`, `svg` and `math` elements, at every depth (decision 1); an empty `iframe` | TEXT | verifier (#367) for holes, scripting on and off; partial: end-tag rule and no runtime HTML on main (#298); the descendant rule phase 2 (#328) | seeds `A9`, `A59`; `BrowserRawTextTest` (GTM `<noscript><iframe>`) | Lean slice 5 `NoscriptOK`, `NoscriptBodyOK` (scripting enabled; empty `iframe` allowed) |

### 3.2 Transitions and element rules

| Rule | Status | Checked by | Proof |
|---|---|---|---|
| `script`, `style` are raw text and `title`, `textarea` are RCDATA only in HTML mode | verifier (#367); #301 | seeds `A5`, `A6`, `A21`-`A23`, `A27`; `<svg title>` wrapper axis | Lean `NodeOK .svg` (SVG `script`/`style`/`title`) |
| A browser ends raw text and RCDATA at the first ASCII-case `</name` + whitespace, `/` or `>`; it must be the element's own end tag | verifier (#367) for holes; main (#298, ASCII case folding) | seeds `A8`, `A28`-`A30`; `BrowserRawTextTest` | JML §4 `browserEndTag`; Lean `NoRawDanger`, `NoScriptDanger` |
| `<!--` inside `<script>` is an error | verifier (#367) for holes; main | seed `A36`; `HtmlParserTest` | Lean `NoScriptDanger` (S6b); JML not yet |
| `plaintext` is an error | main | seed `A62` | Lean: excluded (`htmlNames`) |
| `xmp`, `noembed`, `noframes`, `frameset`, `frame` are errors | verifier (#367): `frameset` is an error; phase 2 (#328); on main their end tag must match (#298) | seeds `A8`, `A28`, `A29`, `A60` | Lean: excluded (`htmlNames`) |
| `iframe` must be empty | phase 2 (#328); on main its end tag must match (#298) | seed `A30` | Lean `RawKindOK` (`iframe`, empty body) |
| `noscript`: no `</noscript` before its own end tag, including in comments and static attribute values (decision 2) | main (#298) | seed `A9` | Lean `NoscriptBodyOK` (stricter: no `<` in comments or static attribute values) |
| `noscript`: no `t:insert`, `t:utext`, `[(...)]` | main (#298) | `BrowserRawTextTest` | Lean: raw holes not modeled |
| `svg`, `math` enter SVG/MathML mode; HTML breakout tags (`p`, `b`, `font` with `color`/`face`/`size`, ...) are errors there | verifier (#367) for holes; #301 | seeds `A25`, `A26`, `B10`; generator interference "breakout tags" | JML (fork branch `proof-b-301`); Lean `svg_no_breakout`, `math_no_breakout` |
| Integration points `title`, `desc` (SVG) and `mi`, `mo`, `mn`, `ms`, `mtext` (MathML) take text and TEXT holes only, with `{{` broken | #301 (TEXT context); text-only content phase 2 (#328) | seeds `A7`, `A21`, `A22`, `A48` | Lean `TextOnly` |
| SVG `script` and `style` take no holes, no `<` and no `&` | verifier (#367): no holes; partial: `<` and `&` in #301; no holes phase 3 (#332) | seeds `A3`, `A4`, `A55`, `A56`, `A58`, `B0`, `B11` | Lean `StaticNoAmp` |
| `foreignObject` holds an HTML subtree under these rules | #301 | seeds `A23`, `B9`; `foreignObject` wrapper axis | Lean `NodeOK .svg` (`foreignobject`) |
| `annotation-xml` with `encoding="text/html"` or `"application/xhtml+xml"` is an HTML integration point | verifier (#367): a computed `encoding` in MathML is an error; #301 (static `encoding`); a computed `encoding`: in fragments #304, everywhere phase 2 (#328) (decision 4) | `annotation-xml` wrapper axis | Lean: MathML content only (stricter) |
| `select` takes only `option`, `optgroup`, `hr`, `t:block` and text | #305 | seed `A31`; `<select>` wrapper axis | JML `selectContent` (fork branch `proof-b-305`); Lean `NodeOK .select` |
| No nested `optgroup` inside `select` (decision 3) | phase 2 (#328) | none yet | Lean `NoOptgroup` |
| No nested `<form>` | phase 2 (#328) | none yet | not modeled (tree level) |
| Tables are allowed; foster parenting moves text, not its context | verifier (#367): text holes in a table part, and raw HTML anywhere in a table outside a cell or caption, are errors; main (#351): computed text directly in `table`, `tbody`, `thead`, `tfoot`, `tr`, `colgroup` is an error, table parts must sit directly in the part that takes them, and `form` may not sit directly in a table part | seed `A24`; `ClientTemplateInjectionTest`, `XssContextTest`; `table`, `table>tbody>tr` container values | not modeled (tree level, G1) |
| `template` holds flow content under the body profile; `col`, `colgroup`, `caption`, `tbody`, `thead`, `tfoot`, `tr`, `td`, `th` as direct children are errors (decision 5) | phase 2 (#328); on main `template` content is not checked | seeds `A61`, `B8`; `template` wrapper axis | not yet (Lean leaves `template` out) |

## 4. Scripts: the data-prelude rule (§5.4)

Applies to a `<script>` with no `type` or a JavaScript, module or JSON type
(`Contexts.SCRIPT_TYPES`). Holes in other script types are errors (`FORBIDDEN`), and a computed
`type` (`t:type`) is an error.

| Rule | Status | Checked by | Proof |
|---|---|---|---|
| Holes take a JSON value written by `Escapers.json`, which also escapes `<`, `>`, `&`, `'`, `/`, backtick, `$`, U+2028 and U+2029 | main | `ScriptJsonTest`; #341 slot check | `Esc` `jsonScriptSafe` (strings); `JsonFirst` for other values unproved |
| `{` in JSON strings is written as `\u007b` | #303 | `ScriptJsonTest` (#303) | not yet (G3) |
| Before the last hole, the static text uses only identifiers, numbers, punctuators other than `/`, `"..."` and `'...'` strings, `//` and `/* */` comments, and whitespace | phase 3 (#332); #290 rejects holes in `"..."` and `'...'` strings, and decides regex vs division heuristically (a guess, see security-architecture.md §7) | seeds `A32`, `A33`, `A51`, `A52`; generator script positions "after `a / b;`", "after `x\n/"/.test(y);`" | not yet (Lean does not model JavaScript) |
| A backtick, `<!--`, `-->`, a backslash line continuation, U+2028 or U+2029 before the last hole is an error | phase 3 (#332); `<!--` anywhere is already an error on main | seeds `A34`, `A36` | Lean `NoScriptDanger` (`<!--` only) |
| Each hole is in expression position: not inside a string or comment | phase 3 (#332); `"[[x]]"` in #290; `tessera-migrate` unquotes it in #287 | seeds `A2`, `A35`; generator positions `"[[s]]"`, `'[[s]]'`, `/*[[s]]*/`, `// [[s]]` | not yet |
| A hole after static `<!-` is an error | main (#299) | `ValueAfterLessThanTest` | JML §5 `htmlMarkupStart`, `scriptMarkupStart` (`markupBefore` on main) (SCRIPT); Lean `NoScriptLtBeforeHole` |
| After the last hole, any script text | target | #341 aligns the AST after the slot | n/a |
| No holes in SVG `<script>` | phase 3 (#332) | seeds `A55`, `A56`, `A58`, `B0`, `B11` | Lean `StaticNoAmp` |
| An unclosed static `{{` before a hole in the same script is an error | #303 | `ClientTemplateInjectionTest` (#303) | not yet |

Rhino, the #341 engine, cannot parse ES modules, classes, `for await` or object spread. Seeds
`A51` and `A52` report `JS_UNPARSABLE`, which the gate counts as a known false negative; the Node
job (#345) checks them with acorn.

## 5. Composition: content kinds (§5.5)

A content kind is a predicate on the spec parser's state at the insertion point, plus a promise
about what the producer emits (#329, redefined Oct 25, 2026; `verify/ContentKinds`). Together
they let the verifier take the state after a value known only at render time to be the state
before it. Statically known fragments need no kind: the verifier analyses them at each call site.
HTML messages need none either: their markup is known at build time (below).

**`FLOW`, the predicate** (`ContentKinds.problem`), in all four parsers:

- data state, between tokens; the current node is HTML; the mode is in body, in cell, in caption
  or in template; no formatting element is waiting to be reopened (`RAW_NOT_FLOW` otherwise);
- and no open element that flow content would close, reopen or adopt (`RAW_CLOSES_ELEMENT`
  otherwise): a `p` in button scope; a heading as the current node; an `a` in the list of active
  formatting elements after the last marker; a `button`, `nobr` or `ruby` in scope; a `select`,
  `option` or `optgroup` anywhere on the stack; three formatting entries of one name after the
  last marker (one more would drop the first, the Noah's Ark clause); in cell or caption mode, an
  SVG or MathML element above the cell (a `</table>` in the value resets the mode, and
  validator.nu stops at the foreign element where the standard goes on to the cell).

In template mode the first flow start tag switches to "in body"; the verifier then continues
from both states (the second is the state after `<span></span>`).

**`FLOW`, the producer promise:** balanced content (it closes every element it opens); no
formatting element nested in one of its own name; no `form`; and no top-level `li`, `dd`, `dt`,
table part, `head`, `body`, `html` or `frameset`. That is why an open `li`, `dd`, `dt` or `form`
is not in the predicate: `<li t:utext="${html.sanitize(x)}">` and sanitized text in a form are
fine.

| Producer | Meets the promise | Checked by |
|---|---|---|
| `html.sanitize`, `html.sanitizeRich`, every `HtmlPolicy` | yes: `HtmlSanitizer` places each element where a browser's tree builder keeps it as written (no block in `p`, heading or phrasing; no `a` in `a`, no formatting element in itself; `li` only in `ul`/`ol`; table parts only in `table`, with `tbody`/`tr` written out and no text directly in table, section or row) and closes everything it opens | `FlowProducerTest` (exit state equals entry state at 14 `FLOW` insertion points, both engines, scripting on and off; hostile and fuzzed input, five policies); `SanitizerBalanceTest` (the parsed tree equals the one the tags spell); the #329 PR's parse5 run |
| `SafeHtml.escape`, `SafeHtml.concat` of `FLOW` values | yes (text; concatenation of balanced content) | `FlowProducerTest` |
| A rendered partial template (`SafeHtml.of`, `Fragment.of`) | only if the compiler proved it a `FLOW` partial (#396, below); the runtime refuses any other template with an `IllegalArgumentException` naming it | `FlowPartialTest` (the #329 review's partials are refused, each shown to change the state at a position the predicate accepts; ordinary partials are marked); `FlowProducerTest` (sample partials with hostile values); `Jdk8ProcessorTest` |
| `SafeHtml.unchecked`, a hand-written `Fragment` lambda or `Template` | the caller's responsibility | none |

**Verified `FLOW` partials** (#396, `ContextVerifier.whyNotFlowPartial`). After a build the verifier
accepts, it marks each template whose output it proves `FLOW` content: the generated class
overrides `Template.verifiedFlowPartial()` to return `true`, and `SafeHtml.of(template, ctx)` and
`Fragment.of(template)` throw for any template without the mark. No reflection: the mark is a
generated method. A template is marked if:

- it is a partial: not a page (a doctype or `<html>` start, the same inference as its entry
  contexts), and its root needs no context but `<body>` (not `li`, `tr`, `td`, `th`, `option`,
  `optgroup`);
- inserted at each of `ContentKinds.PARTIAL_PROBES` (after `<body>`, `<ul><li>`, `<dl><dd>`,
  `<table><tr><td>` and `<template>`, all accepted by the predicate) it has no verifier finding
  and leaves all four parsers in the state it found them (in `<template>`, or in the state after
  `<span></span>`). The original insertion mode is ignored outside the "text" mode, the only mode
  that reads it (validator.nu keeps it after a `textarea`, `script` or `template`);
- and, after every tag in the `<body>` probe, it keeps the producer promise: no `form` (the form
  pointer is never set), no formatting element in an entry of its own name after the last marker
  (whatever the attributes, which the Noah's Ark clause compares), no top-level `li`, `dd`, `dt`
  or table part, no `head`, `body`, `html` or `frameset` start tag, and no end tag that closes
  nothing (`</p>` and `</br>` aside, which insert an element). An end tag closes nothing there when
  the parser already closed its element, as `<h2>` closes an open `<h1>`, `<dd>` a `<dt>`, and
  `<button>` an open `<button>` with the `<div>` inside it; inserted inside an `h3`, `dt` or `div`,
  the same end tag closes that one.

The probes catch what the promise forbids where a single `<body>` can't: an `li` or `dd` inside a
`div` still closes an open `li` or `dd`, and a table part closes a cell or switches a template's
mode. So the bench corpus's two call sites pass: budgetmaster's `NotificationLinkTemplate` (an `a`)
is marked, and so is espd run-3's `PrintContentTemplate` once its HTML messages pass the verifier
(they fail `MESSAGE_CHANGES_STATE` since #329, independently of this).

`PHRASING`, `HEAD` and `TABLE_ROWS` are not added. On the bench corpus the `p` clause rejects 6
call sites, `<p t:utext="${...}">` and the like, and nothing needs `HEAD` or `TABLE_ROWS` (the #329
PR has the counts). The fix is a `<div>`. A `PHRASING` kind would need its own safe type, since
the compiler sees only `SafeHtml`.

**HTML messages** (`#{...}` in `t:utext` or `[(...)]`) are recorded with their markup: one
branch per value of the key in the bundle files the build sees, as written and as `MessageFormat`
formats it (each placeholder a `text` hole), plus a `text` hole for a message the build didn't see
(written escaped). The verifier analyses each branch at the insertion point and requires it to
leave the parser where it found it (`MESSAGE_CHANGES_STATE`): a message with a `<p>` inside a
`<p>`, or one that leaves its own `<p>` open, is an error.

| Value or position | Kind | Status | Checked by | Proof |
|---|---|---|---|---|
| Fragment (`t:fragment`) | none: analysed at each call site | verifier (#367); #304 (`TemplateInfo.parsingContexts`) | `FragmentParsingContextTest` (#304); generator composition axis `t:insert`, `t:replace` | not yet |
| `Fragment` parameter or value | `FLOW` | verifier (#367, #329): bound arguments at the call site, otherwise `FLOW`; #304 | `ContentKindsTest`, `FlowProducerTest`, `FragmentParsingContextTest`; composition value `Fragment f` | not yet |
| `SafeHtml` (`t:utext`, `[(...)]`) | `FLOW` | verifier (#367, #329); #304 | `ContentKindsTest`, `FlowProducerTest`, `FragmentParsingContextTest`; hole values `[(h)]`, `t:utext` | not yet |
| HTML message (`#{...}` as HTML) | none: its markup, analysed where used | verifier (#329); #304; messages are checked at build time on main (`MessageCompilerTest`) | `ContentKindsTest`, `HtmlMessageTest`, `MessageMarkupTest` | not yet (G4: the runtime re-check in `Escapers.htmlMessage` is tested, not proved) |
| `t:insert`, `t:utext`, `[(...)]` into RCDATA or raw text | error | verifier (#367); main (#298) | `BrowserRawTextTest`; hole values `[(h)]`, `t:utext` in the `title` and `noscript` wrappers | Lean: raw holes not modeled |
| `t:replace` with a whole `<title>` fragment | allowed (the fix-it) | main | `FragmentsTest` | n/a |
| `t:remove="tag"` on raw-text, RCDATA or foreign elements | error | verifier (#367) for holes; main (#300) | seeds `A16`, `A17`; `ControlFlowTest`, `FragmentsTest`; composition value `t:remove="tag"` | not yet |
| Fragment declared inside `noscript`, `iframe`, `xmp`, `noembed`, `noframes` | fits nowhere | #304 | `FragmentParsingContextTest` | not yet |
| HTML fragment inside `select` | only without `title`, `style`, `svg`, `math`, `t:insert`, `t:replace`, `t:utext`, `[(...)]` | #304 | `FragmentParsingContextTest` | not yet |

#304's name-based checks still run first, as friendly early errors; removing them is left to a
follow-up (#329 PR).

## 6. Attributes (§5.6)

### 6.1 Attribute classes

`Contexts.attribute(element, name)` picks the class of a computed attribute. The classes and
escapers below ship today; [mvp-design.md](mvp-design.md) has a similar table, without `SCRIPT_ATTRIBUTE`.

| Class (`OutputContext`) | Where | Escaper | Status | Checked by | Proof |
|---|---|---|---|---|---|
| `ATTRIBUTE` | ordinary attribute values | `Escapers.attr` | main | seeds `A38`, `A39`, `A49`; hole values `t:title`, `t:data-x`, `t:value`, `t:class` | `Esc` `htmlSafe`; Lean slice 2 |
| `URL` | `href src action formaction cite poster background longdesc usemap ping xlink:href`; `to from by` on SVG `animate`/`set` | `Escapers.url` | main | seeds `A40`, `A41`, `A44`-`A46` | `Esc` `htmlSafe`; scheme check not yet |
| `URL_LIST` | `values` on SVG `animate`/`set` | `Escapers.urlList` | main (#288) | seed `A47` | `Esc` `htmlSafe` |
| `RESOURCE_URL` | `script[src|href|xlink:href]`, `iframe/frame/embed[src]`, `object[data]`, `base/link[href]`, htmx request URLs | `Escapers.resourceUrl` | main; protocol-relative `/\` paths rejected for every link (#340); every `<base href>`, static or computed, must be same-origin, main (#357); a link in a same-origin slot is re-checked when rendered, so a context path or custom `Links` cannot make it protocol-relative, main (#358) | seeds `A42`, `A43`, `B1`, `B2`, `B4`; `LinkCompilerTest`, `CsrfBaseTest`, `SameOriginLinkRenderTest` | `Esc` `htmlSafe`; origin fixed by the literal; not proved |
| `REFRESH` | `content` of `<meta http-equiv="refresh">` | `Escapers.refresh` | main | seed `B3` | `Esc` `htmlSafe` |
| `SCRIPT_ATTRIBUTE` | Vue/Alpine JSON bindings (`:x`, `v-bind:x`, `x-bind:x`) | `Escapers.jsonAttr` | main | `JsonBindingTest` | `Esc` `htmlSafe` (attribute half) |
| `STYLE` | `style` attribute | `Escapers.attr`, literals, numbers and `CssValue` only | main | `StyleAttributeTest` | not yet |
| `EVENT_HANDLER` | `on*`, framework directives | none: build error | main | `FrameworkDirectivesTest` | n/a |
| `FORBIDDEN` | `srcdoc`, `<script type>`, `<meta http-equiv>`, `hx-boost`, `hx-sse`, `hx-ws`, names outside `[A-Za-z_][A-Za-z0-9_.:-]*` | none: build error | main | `ContextsTest`, `HtmxBoostTest` | n/a |

### 6.2 Which computed names are accepted

Static attributes are copied verbatim and are not affected by these rules.

| Name | Target | Today on main | Status | Checked by |
|---|---|---|---|---|
| Standard HTML, SVG, MathML or ARIA attribute | accepted, classified as in 6.1 | accepted | main | `ContextsTest` |
| `data-*` | accepted, except names the framework table marks as code | accepted, except `data-bind`, `data-hx-on*`, `data-ng-*` and the other framework names in 6.3 | main | `FrameworkDirectivesTest` |
| Plain name `[a-z][a-z0-9]*` on a custom element | accepted (component props) | accepted | main | `ContextsTest` |
| Name listed in the framework table | as the table says | as the hard-coded lists in `Contexts` say | phase 4 (#333) | `FrameworkDirectivesTest` |
| Any other name | error ("use `data-*`") | accepted as `ATTRIBUTE` | phase 2 (#330) | none yet; closes #273 (Unpoly) and #276 |
| Computed `formaction` inside a POST form | same-origin link only, or the CSRF token is withheld | same-origin `formaction` required where a button may submit the CSRF token | main (#346, #314) | seeds `A45`, `B6`; `CsrfFormactionTest` |
| Computed `href` on `<base>` | same-origin link only, in every template (a static one must be same-origin by shape) | same-origin link required; static `href` checked like a static `action` | main (#357) | `CsrfBaseTest` |

### 6.3 The framework table

The target is a data file with one entry per framework and version range (#333), checked by the
nightly framework harness (#334). Today the entries are hard-coded in `Contexts`:

| Framework | Code attributes (error) | Fetches and swaps (same-origin link only) | JSON bindings | Delimiters | Elements |
|---|---|---|---|---|---|
| Vue 2/3, petite-vue | every `v-*`; `:x`, `@x`, `#x`, `.x` | none | `:x`, `v-bind:x` with an ordinary target, except `BIND_DENIED_TARGETS` (target: an allow-list, #333) | `{{ }}` | `component` (target: listed, with a static `is`, #272) |
| Alpine.js | every `x-*`; `:x`, `@x` | none | `:x`, `x-bind:x`, as Vue | none | none |
| htmx 1/2 | `hx-on*`, `hx-vals`, `hx-vars`, `hx-headers`, `hx-request`, `hx-trigger` (also `data-hx-`) | `hx-get`, `hx-post`, `hx-put`, `hx-patch`, `hx-delete`, `sse-connect`, `ws-connect`; form actions under `hx-boost` | none | none | none |
| AngularJS | `ng-*`, `ng:*`, `ng_*` (also `data-`, `x-`) | none | none | `{{ }}` | none |
| Knockout | `data-bind`; `params` on a custom element | none | none | none | none |
| _hyperscript | `_`, `script`, `data-script` | none | none | none | none |

| `{{` rule | Status | Checked by |
|---|---|---|
| `{{` broken with `<!---->` in element text values | main | hostile value `{{PWN()}}`; `EscapersTest`, `ClientTemplateInjectionTest` |
| `{{` broken in SVG/MathML text | #301 (TEXT context) | seed `A7` |
| `{` as `\u007b` in JSON strings | #303 | `ScriptJsonTest` (#303) |
| Unclosed static `{{` before a hole is an error | #303 (same text node or script); across nodes #336 | seed `A18`; generator interference `{{..}}` |
| Attributes and RCDATA: no lossless break | documented limitation (guide §7), bounded by `v-pre`, `ng-non-bindable` and CSP (#335) | seed `A37`; #344 CSTI check |

Frameworks configured to read other names (an Alpine prefix, a Knockout binding provider, a
hyperscript `attributes` setting) are not detected (G5). This is outside the guarantee; see
[security-model.md](security-model.md).

### 6.4 The CSP nonce (#335)

Defence in depth beyond the guarantee (security-model.md §1): with a nonce-based `script-src`,
which the application sends (guide §4.11), only the scripts the compiler wrote run. The nonce
comes from `RenderContext.cspNonce()`; the Spring starter sets a fresh one per request.

| Rule | Status | Checked by |
|---|---|---|
| Every `<script>` and `<style>` start tag the compiler writes gets ` nonce="..."` after its other attributes, written with `Escapers.attr`; nothing when the context has no nonce | #361 | `CspNonceTest` (static, `src`, `t:if`/`t:each`, fragments by `t:insert`/`t:replace` and `~{::selector}`, hostile nonce values) |
| Stamped only where a browser reads the element as HTML: not inside `<svg>`/`<math>` (which decode character references in script and style) or inside an element a browser reads as text (`<noscript>`, `<xmp>`, `<iframe>`, `<noembed>`, `<noframes>`), and fragments inserted in `<svg>`/`<math>` render without the nonce. A mistake in this tracking only removes a nonce, so the script is blocked; it never adds one | #361 | `CspNonceTest` (`scriptsABrowserDoesNotReadAsHtmlAreNotStamped`) |
| A `nonce` on `<script>` or `<style>` in the template (static, `t:nonce`, `t:attr`) is an error | #361 | `CspNonceTest` |
| Markup the compiler did not write gets no nonce: `html.sanitize` drops `script` and `style` with their content; `SafeHtml.unchecked` is copied as is | #361 | `CspNonceTest`, `HtmlSanitizerTest` |

## 7. Decisions (Oct 25, 2026)

dlorenc decided the profile questions on #349, following security-architecture.md: fail closed,
and shrink the profile rather than model more. Rows above that the code doesn't do yet carry
their phase issue.

| # | Decision | Reason |
|---|---|---|
| 1 | `noscript` rules apply to all descendants; `select`, `svg` and `math` inside `noscript` are errors | One rule for both scripting readings |
| 2 | `</noscript` is an error anywhere before the element's own end tag, including comments and static attribute values | With scripting on, a browser ends the element there |
| 3 | Nested `optgroup` is an error | The browser closes the outer one |
| 4 | `annotation-xml` with an HTML `encoding` is an HTML integration point, as #301 has it; the `encoding` must be static | Follows the spec; a computed `encoding` would pick the mode at render time |
| 5 | `template` is accepted with flow content only; table-part elements as direct children are errors | `<template><col>` ignores raw-text start tags |
| 6 | `center`, `font`, `tt`, `big`, `strike`, `marquee` are on the HTML allow-list; `font` stays an error inside SVG and MathML | Migration parity; `font` is a breakout tag |
| 7 | Supported browsers: Chromium (current), Firefox (current), Firefox ESR and Safari/WebKit (current). Safari is checked through Playwright's WebKit build in the Node job (#354): the WPE port, so WebKit's HTML parser and JavaScriptCore at a recent WebKit revision, not a shipping Safari release or its app shell | Rules must hold in every engine users run |
| 8 | Static NUL is an error | A browser replaces it, so the source is not what is parsed |
| 9 | Section 1 is a per-hole claim: each hole lands in the node, context and namespace the compiler chose, and the benign and hostile trees are equal | That is what the oracle checks; implied end tags make the tree claim false |
| 10 | RCDATA and RAWTEXT content get the end-tag rule, no markup right before a hole, and no NUL | The proof needs exactly these |
| 11 | CDATA under the HTML descendants of `foreignObject` is an error | A browser reads it as a bogus comment there |
| 12 | `iframe` content must be empty; #298's pinned end tag is the current implementation, and phase 2 tightens it | Its content is never shown |
| 13 | The `&` complete-reference rule is part of the text and attribute value rows | It is a decoding rule, not a tokenizer one |

## 8. Verifier decisions (Oct 25, 2026)

dlorenc decided these on the research memo for #364
([security-architecture.md](security-architecture.md) §11).

| # | Decision | Reason |
|---|---|---|
| 14 | The build-time verifier is the boundary for markup contexts, enforced in every build, not first in test scope | The hand-written model is what kept failing; the spec algorithm decides instead |
| 15 | Two parsers: pinned current upstream validator.nu htmlparser and the 1.4.16 classic snapshot; a hole is accepted only if both accept | Covers the classic and customizable `<select>` split (Firefox ESR) by construction |
| 16 | The parsers are shaded into `tessera-compiler`, relocated and built for Java 8; the runtime and starters stay dependency-free | Upstream ships no Java 8 jar; the processor runs in a JDK 8 javac |
| 17 | Markup structure that depends on data (recursive rich-text fragments) is a build error, with a `SafeHtml` fix-it; `tessera-migrate` reports it | It breaks the per-hole invariant; 25 of 1,585 corpus templates |
| 18 | Scripts (#332), URL policy and client frameworks (phase 4) stay as planned; CSP auto-nonce (#335) moves earlier | The verifier doesn't cover them |

## 9. The verifier's rule table (#367)

`verify/Rules` and `verify/ContextVerifier`. Each path through a template holds four parsers
(current upstream and 1.4.16, scripting on and off); a hole is accepted only if all four accept it.
The id in brackets ends every error message (`[verifier:ID]`), for #368's fix-its.

| Id | Rejects | Accepted instead |
|---|---|---|
| `TEXT_NOT_IN_TEXT` | a `text` or `rcdata` hole inside a tag, comment, character reference, CDATA, doctype or `plaintext` | data state; RCDATA; RAWTEXT of `noscript` (scripting on), `iframe`, `xmp`, `noembed`, `noframes` |
| `TEXT_IN_SCRIPT` | a `text` or `rcdata` hole in script data, `style`, or SVG/MathML `script`/`style` | as above |
| `TEXT_IN_TABLE` | a `text` hole in a table, table body, row or column group whose current node is a table part (foster-parented table text) | in a cell or caption; in an element foster-parented out of the table |
| `TEXT_OUTSIDE_BODY` | a `text` hole before `<html>`, in or after the head, after the body, in a frameset | in body, cell, caption, `select`, `template`; in foreign content |
| `TEXT_RECONSTRUCTS` | a `text` hole where formatting elements closed out of order would be reopened first | |
| `RCDATA_NOT_IN_RCDATA` | an `rcdata` hole (no `{{` breaking) a browser reads as ordinary text | RCDATA, never-run raw text |
| `ATTRIBUTE_NOT_IN_VALUE`, `ATTRIBUTE_WRONG_NAME` | an attribute hole outside a double-quoted value, or in another attribute's value (or a dropped duplicate) | the double-quoted value of the attribute the compiler wrote, compared case-insensitively |
| `ATTRIBUTE_VALUE_CHANGES_PARSE` | a computed `type` in a table mode, a computed `encoding` in MathML | static values |
| `JSON_NOT_IN_SCRIPT` | a `json` hole outside the script data of an HTML `<script>` (SVG `script`, escaped script data, CDATA) | script data, also right after `<` (no JSON starts with `/` or `!`) |
| `RAW_NOT_FLOW` | `SafeHtml`, an unbound or dynamic `Fragment` outside data state, HTML namespace, body/cell/caption/template mode, or with formatting elements to reopen | flow content (section 5) |
| `RAW_CLOSES_ELEMENT` | the same where flow content would close, reopen or adopt an open element: `p` in button scope, a current heading, an active `a`, `button`/`nobr`/`ruby` in scope, `select`/`option`/`optgroup`, three identical formatting entries, SVG or MathML above a cell | flow content (section 5) |
| `MESSAGE_CHANGES_STATE` | an HTML message whose markup (any locale, either form) leaves the parser in another state than it found it | a message that closes what it opens, where its markup fits |
| `FRAMESET` | any `<frameset>` start tag (frameset-ok is not tracked exactly) | |
| `NOAHS_ARK` | three or more identical formatting elements, one of them with a computed attribute | |
| `LOOP_NO_FIXED_POINT`, `STRUCTURE_DEPENDS_ON_DATA` | a loop, or a fragment calling itself, that reaches new parser states after 4 rounds (or more than 24 states for one recursive fragment); anything reached only through recursion | loops and recursion that settle |
| `TOO_MANY_STATES` | more than 24 distinct parser states at a join | |
| `ENGINE_FAILURE`, `UNKNOWN_NODE` | a parser that throws or reports an unnamed state; a fragment the skeleton doesn't define | |

A computed attribute (`[ name="..."]`) inside a start tag is one path: writing it or not leaves the
tokenizer in states that parse what follows alike, except for `type`, `encoding`, `color`, `face`
and `size`, which stay two paths. A partial (a view whose output doesn't start with a doctype or
`<html>`) is also checked by fragment parsing in the context its first element implies (`tr`:
`tbody`; `td`, `th`: `tr`; `option`, `optgroup`: `select`; `li`: `ul`; otherwise `body`).
