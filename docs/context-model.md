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
since #367. It runs every template through the pinned spec HTML parser and enforces section 1
per hole, whether or not a row below has its own rule; its rule table is section 9. Once #406
lands it accepts only parses with no parse errors (missing-doctype aside), outside three coarse
exclusions where browsers split (security-architecture.md §11.4). Markup that parses only through
error recovery (foster parenting, misnested formatting, a second `<body>`) is then rejected, not
modelled; until then `main` still models it.
The profile of principle 3 is what the verifier accepts, narrowed by the rows below. The rows in
sections 2, 3 and 5 run first, as friendly early errors (security-architecture.md §11.7). Sections 4 and 6 are unchanged: the verifier doesn't cover script
lexical position, URL policy or the value shapes of names outside HTML (`NEUTRAL`).

## How to keep this document current

- A PR that changes `HtmlParser`, `MarkupTokenizer`, `Contexts`, `TextInlining`, `Escapers` or
  the verifier's rule table (`verify/`) updates this document in the same PR.
- A PR that widens the accepted language says so in its description, and adds the oracle seed
  or generator value that checks the new row.
- Every row names its check, or says `none yet`. A `none yet` row is a gap to close with a seed,
  a generator value or a unit test.

## Columns

| Column | Meaning |
|---|---|
| **Status** | `verifier (#367)`: enforced for every template by the build-time verifier (`verify/ContextVerifier`, rule table `verify/Rules`, section 9), whatever the row's own rule says. `main`: enforced on `main` (f135112). In sections 2 and 3 the status also names the layer that rejects: `parser` is an early error from `html/HtmlParser` or `MarkupTokenizer` (`generator` from `codegen/TemplateGenerator`), reported before the verifier runs; `verifier` is the verifier, by a rule id of section 9. `#N`: enforced in open PR #N, not yet on `main`. `phase N (#M)`: not implemented; issue #M owns it, in that phase of security-architecture.md §8. `not enforced`: no check and no issue. `partial`: the row says which part is on `main` |
| **Checked by** | Unit tests on `main`, plus the oracle: seed IDs `A0`-`A62` and `B0`-`B11` from the #320 seed corpus (#347), and axis values of the template generator (#343). The oracle checks are #344 (tree invariant, merged), #341 (script slots, merged) and #342 (context check, merged). Their unit tests run in `mvn verify`; the oracle runs over the seeds and generated cases only once the gate (#324) lands |
| **Proof** | `Lean`: a predicate in the Lean model of T1 (unpublished, not in this repository). `JML`: an OpenJML contract in an unpublished `docs/rule-contracts.md` (the rule functions on `main` 99e35c5), or on an unpublished branch where the row names one. `Esc`: an escaper postcondition proved on an unpublished branch. `not yet` means no proof states the row |

The unpublished JML contracts are stated over the rule functions on `main`. Some proofs
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
| Static structure does not depend on the browser | verifier (#367): error-free parses outside the exclusions (#406), in one engine (#408); real engines checked by the nightly detector (#399) | the htmlparser inside validator 26.10.12 in #344; parse5, headless Chromium, Firefox 140 ESR and WebKit (Playwright's build) in the Node job (#345, #354; on Chainguard Checks after every merge and on request, over a fixture until #324) | not yet |

## 2. Token-level rules (§5.2)

The tokenizer accepts only these forms. Anything else is a build error with file, line and
column. The `parser` rules are friendly early errors; the verifier (#367) is the boundary. A row
marked `verifier for holes` is one where a value next to the construct lands in a parser state the
verifier rejects (a tag, a comment, a character reference, CDATA, raw text after `<`); a row marked
`verifier: PARSE_ERROR` is one where the construct itself is a parse error (#406). #327 is re-scoped
to rules that give a better message than the verifier; the `phase 2 (#327)` rows below are kept
only if they do. #411 took the inventory of `HtmlParser`'s rules: a rule went only where the
verifier rejects every one of its tests with a message naming the same markup and fix, and the
rest stay, each row saying why. #442 gave the verifier's parse errors behind the self-closing, PI,
DOCTYPE and bogus-comment rules a fix-it at the markup's `<`, and those rules went.

| Construct | Accepted | Rejected | Status | Checked by | Proof |
|---|---|---|---|---|---|
| Element name | `[A-Za-z][A-Za-z0-9_.:-]*` | anything else | parser (main): a hole in a name is rejected before there is a skeleton | `HtmlParserTest`; `<scr[[s]]ipt>` in `ValueAfterLessThanTest` | Lean `validElementName` |
| Start tag separators | ASCII whitespace (TAB, LF, FF, CR, SPACE) | U+000B, U+001C-U+001F and other Java-only whitespace in a tag (#338) | parser (main, #297); verifier for holes | `XssContextTest.REJECTED`; generator interference "U+000B/U+001F/U+2028/U+3000 in a tag" | Lean `TagAttrsOK` (assumes ASCII separators); JML `tagEnd` (fork branch `proof-b-tse`) |
| Quotes in tags | `"` or `'` only as the delimiters of a quoted value | a quote in a name, in an unquoted value, or right after a quoted value | parser (main, #297); verifier for holes | seed `A13`; `HtmlParserTest`, `XssContextTest` | JML `tagEnd` (fork branch `proof-b-tse`); Lean `SrcTagOK` |
| Attribute names | names without `=`, `<` or quotes | `=`, `<` or a quote in a name | parser (main) | `HtmlParserTest` | Lean `validSrcAttrName` |
| Unquoted values | `[A-Za-z0-9_.:/#-]`, not ending in `/` before `>` | other characters | verifier for holes; parser: `/` before `>` and quotes (#297), a value starting with `=` (#353); the character class is phase 2 (#327) | generator interference `<textarea x=y/>` | Lean `SrcValue.ok` (weaker: any non-space, non-quote, non-`>`) |
| Attribute values: `&` | only as a complete character reference | an incomplete `&name` (decision 13) | verifier: PARSE_ERROR for a named reference without its `;` (`a &amp b`, #406); the rest phase 2 (#327) | generator interference `&amp` | not yet (a decoding rule; the token-level proof doesn't need it) |
| Duplicate attributes | one attribute per name | two attributes with the same lowercased name (`toLowerCase(Locale.ROOT)` on main; ASCII in the target) | parser (main) | `HtmlParserTest` | Lean `TagAttrsOK` (`Nodup`) |
| `/>` | on void HTML elements and foreign elements | on non-void HTML elements, except where Tessera writes the end tag (`<textarea t:field/>`) | verifier: PARSE_ERROR on every non-void HTML element (#406), with the fix-it (write the end tag; #442, which deleted the parser's rule for `script`, `style`, `select` from #298); generator: `title`, `textarea`, `xmp`, `iframe`, `noembed`, `noframes`, `noscript` (#298); parser: anything but `>` after `/` (#353) | seeds `A11`, `A12`; `BrowserRawTextTest`, `ParseErrorFixItTest` | Lean `NodeOK` (`selfClosing = false` on non-void); `t:field` case not modeled |
| End tag | `</name>` matching the open element | stray or mismatched end tags; `</` + non-letter | parser (main, #296): Tessera builds its tree from matched tags | `HtmlParserTest`; generator interference `</ x>` | Lean slice 2 `run_endTag`; JML §3 `bogusComment` |
| Comment | `<!--` text `-->`; text does not start with `>` or `->` and has no `--!>`, `<!--` or `-->` | early-ending comments | verifier for holes; verifier: PARSE_ERROR for `<!-->` and `<!--->`; parser: `<!-->`, `<!--->`, `--!>` (#295), kept: the pinned engine reports no error at `--!>` (#411); a nested `<!--` check is unpublished hardening, not a browser difference (G10) | seed `A14`; `HtmlParserTest`, `XssContextTest` | JML §2 `earlyCommentEnd`; Lean `CommentTessera` |
| Template comment | `<!--/*` text `*/-->` in markup (Thymeleaf's parser-level comment), ending at the first `*/-->`; dropped with its content before codegen, so it is never output, compiled or verified | unclosed; `<!--/*/` (Thymeleaf's prototype-only comment); `<!--/*` in `script`, `style`, `title` or `textarea` content | parser (#132) | `HtmlParserTest`, `CodegenEndToEndTest` | not modeled (never output) |
| Conditional comments | as written in petclinic (`<!--[if ...]>...<![endif]-->`) | | main | #296 corpus scan | Lean `CommentTessera` |
| Doctype | `<!DOCTYPE html>`, ASCII case-insensitive, first token only | other DOCTYPEs; a DOCTYPE after other content | verifier: PARSE_ERROR at the DOCTYPE's start, with the fix-it (write `<!doctype html>`; #442, which deleted the parser's early-`>` rule from #296); the exact form and position phase 2 (#327) | `ParseErrorFixItTest` | Lean `DoctypeOK`, first node only (`InProfile`) |
| `<?`, `<!` other forms | none | processing instructions, bogus comments | verifier: PARSE_ERROR for every processing instruction and bogus comment (#406), at its `<` and with a fix-it (#442, which deleted the parser's rules for `<?`, `<!x` and `</`+non-letter in text and for an early `>` in `<?...?>`, from #296) | generator interference `<?x>`, `<!x>`; `ParseErrorFixItTest` | JML §3 `earlyEnd`, `bogusComment` |
| CDATA | inside SVG and MathML only, outside the integration points, with no hole and no `>` before `]]>` | CDATA in HTML content, including the HTML descendants of `foreignObject`, where it is a bogus comment (decision 11); CDATA whose current node is an HTML or MathML text integration point (`foreignObject`, `desc`, `title` in SVG, an HTML `annotation-xml`, `mi`, `mo`, `mn`, `ms`, `mtext`), where parse5 and Chromium read a bogus comment (#441) | verifier: a hole (`TEXT_NOT_IN_TEXT`; the parser's rule went in #411, `CdataHoleTest`), CDATA in HTML content (a bogus comment, PARSE_ERROR, #406), and CDATA in an integration point (exclusion (d), `CDATA_IN_INTEGRATION_POINT`, #441); parser: an early `>` (#296), kept: the verifier accepts `<svg><![CDATA[ a > b ]]></svg>` | seeds `A10`, `A54`, `A55` | JML §3 `earlyEnd`, `cdataHole` (main before #411); Lean: not modeled (sink) |
| Text: `<` | only as the start of an accepted tag or comment | a literal `<` that is not a tag (write `&lt;`) | verifier: PARSE_ERROR for a `<` followed by `!`, `?` or `/`+non-letter (see `<?`, `<!` above; the parser's rule went in #442) and for a `<` that starts no tag (`a < b`, #406) | `ValueAfterLessThanTest` (controls) | Lean `TextOK` (`'<' ∉ s`); `StaticText` for main's rule |
| Text: hole after `<` | a hole after any other text | a hole after static `<`, `<!`, `<!-`, `<?`, `</`+letters | verifier for holes; parser (main, #299) | seed `A15`; `ValueAfterLessThanTest` | JML §5 `htmlMarkupStart`, `scriptMarkupStart` (`markupBefore` on main); Lean `NoLtBeforeHole` |
| Text: `&` | only as a complete character reference | an incomplete `&name` (decision 13) | verifier: PARSE_ERROR for a named reference without its `;` (`a &amp b`, #406); the rest phase 2 (#327) | generator interference `&amp` | not yet (a decoding rule; Lean treats references as transparent) |
| Text: hole after `&` | a hole after a complete reference (`&amp;`, `&nbsp;`) or after `&` and a character that starts none (`& `) | a hole after static `&`, `&#`, `&#x` or `&` and letters, which a value such as `#123;` finishes into a reference (`&#123;` is `{`): in text, RCDATA and foreign content, also with the `&` in another node (`<t:block>`, `t:remove="tag"`, a condition or a fragment) | verifier for holes (`TEXT_NOT_IN_TEXT`). Attribute values: a computed attribute is one value through one escaper, so a static `&` in it (`t:href="'&' + ${s}"`) is written `&amp;` | `ValueAfterAmpersandTest` | not yet (Lean treats references as transparent) |
| NUL | no U+0000 | U+0000 anywhere in static markup: text, tags, RCDATA and raw text (decision 8) | phase 2 (#327) | none yet | Lean assumes it (G8) |
| RCDATA and RAWTEXT content | text with no `</name` + whitespace, `/` or `>` before the element's end tag, no hole right after `<` or `</` + letters, and no NUL (decision 10) | the rest | verifier for holes; parser: the end-tag rule (#298), kept: the verifier accepts the hidden end tags of `xmp`, `noscript` and the rest that leave a `<script>` open at the end of a partial, and for `script` the profile's message comes first; the look-alike end tag (`</ſcript>`), kept: the verifier reads it as a browser does, so only Tessera's tree is wrong; a hole after `<` (#299); NUL phase 2 (#327) | seeds `A8`, `A28`-`A30`; `BrowserRawTextTest`, `ValueAfterLessThanTest` | Lean `RawOK` (`NoRawDanger`, `NoRawLtBeforeHole`); JML §4 `browserEndTag`, §5 |
| Holes | `[[...]]`, `[(...)]` in text; `t:*` attributes | holes in comments, tags, attribute names, element names | verifier for holes; parser: in element names (#299), before there is a skeleton; in comments and static attributes `[[...]]` is copied as literal text, not a hole; an error in phase 2 (#327) | `HtmlParserTest`, `TextOutputTest` | Lean `Node.hole` placement |

## 3. Parse modes (§5.3)

The parser keeps a parse mode for every open element. Today `HtmlParser` keeps none: it
decides raw text from the local name (`RAW_TEXT_ELEMENTS`, `Contexts.browserRawText`), and it
has no namespaces. #301 adds namespaces. The verifier (#367) reads the real mode and namespace
from the spec parser, and owns the browser splits as exclusions (#406). #328 is re-scoped to
friendly errors. #411 took the inventory of the `HtmlParser` rules in this section and kept them
all: each row says what the verifier would miss or say less clearly without it. #378 kept the
raw-text name tables too: Tessera's tree needs them before there is a skeleton, and the profile's
messages use them. The verifier names the escaper of element text from the parser state
(section 9), so `Contexts.text` no longer tells `<title>` and `<textarea>` apart. The element allow-list moves to phase 4 (#311).

### 3.1 Modes and what they accept

| Mode | Entered by | Children accepted | Holes | Status | Checked by | Proof |
|---|---|---|---|---|---|---|
| `HTML` | document root; `foreignObject` | allow-listed HTML elements, including `template` (3.2) and the obsolete `center`, `font`, `tt`, `big`, `strike`, `marquee` (decision 6); custom elements (name with `-`) | TEXT | partial: no allow-list on main, any valid name is accepted; a static `is` must name a custom element, so `<component is="script">` is an error (#272, #584); phase 2 (#328) | generator container axis (HTML values) | Lean `NodeOK .html`, `HtmlName` |
| `RAWTEXT(script)` | `<script>` in HTML mode | static script text | SCRIPT (§4) | verifier: JSON only in an HTML script's data; parser (main) | seeds `A1`-`A2`, `A32`-`A36` | Lean slice 3 `ScriptOK`; JML §4 `browserEndTag` |
| `RAWTEXT(style)` | `<style>` in HTML mode | static CSS | none | verifier; parser (main) | seed `A53` | Lean slice 4 `RawKindOK`; JML §4 |
| `RCDATA` | `<title>`, `<textarea>` in HTML mode | text | RCDATA | verifier; parser (main) | seed `A50`; `BrowserRawTextTest` | Lean slice 4 `RawOK`; JML §4 |
| `SVG` | `<svg>` | SVG 2 element names only | TEXT | verifier for holes; verifier: PARSE_ERROR for a breakout tag (#406); parser: the breakout list (#301), kept (see 3.2); allow-list phase 2 (#328) | seeds `A3`-`A7`, `A19`, `A21`, `A25`-`A27`, `A54`-`A56`, `A58`, `B0`, `B7`-`B11` | JML (fork branch `proof-b-301`); Lean `NodeOK .svg`, `SvgName`, `svg_no_breakout`; token-level T1 (`t1_profile_tree`) without CDATA (not yet) |
| `MATHML` | `<math>` | MathML Core element names only | TEXT | verifier for holes; verifier: PARSE_ERROR for a breakout tag (#406); parser: the breakout list (#301), kept (see 3.2); allow-list phase 2 (#328) | seeds `A20`, `A22`, `A40`, `A57` | JML (fork branch `proof-b-301`); Lean `NodeOK .math`, `MathName`, `math_no_breakout`; token-level T1 (`t1_profile_tree`) without CDATA (not yet) |
| `SELECT` | `<select>` | `option`, `optgroup` (not nested, decision 3), `hr`, text | TEXT | verifier for holes, classic and customizable parsing; verifier: exclusion (c) `SELECT_CONTENT` (#406); parser (#305), kept as the in-template fix-it; no nested `optgroup` phase 2 (#328) | seed `A31`; `<select>` wrapper axis | JML `selectContent` (fork branch `proof-b-305`); Lean `NodeOK .select`, `NoOptgroup`; token-level T1 (`t1_profile_tree`) |
| `NOSCRIPT` | `<noscript>` in HTML mode | HTML elements except RCDATA, raw-text, script, `select`, `svg` and `math` elements, at every depth (decision 1); an empty `iframe` | TEXT | verifier for holes, scripting on and off; parser: the end-tag rule (#298), kept (see 3.2); generator: no runtime HTML (#298); the descendant rule phase 2 (#328) | seeds `A9`, `A59`; `BrowserRawTextTest` (GTM `<noscript><iframe>`) | Lean slice 5 `NoscriptOK`, `NoscriptBodyOK` (scripting enabled; empty `iframe` allowed) |

### 3.2 Transitions and element rules

| Rule | Status | Checked by | Proof |
|---|---|---|---|
| `script`, `style` are raw text and `title`, `textarea` are RCDATA only in HTML mode | verifier; parser (#301): the namespace Tessera's tree records | seeds `A5`, `A6`, `A21`-`A23`, `A27`; `<svg title>` wrapper axis | Lean `NodeOK .svg` (SVG `script`/`style`/`title`) |
| A browser ends raw text and RCDATA at the first ASCII-case `</name` + whitespace, `/` or `>`; it must be the element's own end tag | verifier for holes; parser (#298, ASCII case folding), kept: see section 2, RCDATA and RAWTEXT content (#411) | seeds `A8`, `A28`-`A30`; `BrowserRawTextTest` | JML §4 `browserEndTag`; Lean `NoRawDanger`, `NoScriptDanger` |
| `<!--` inside `<script>` is an error | verifier for holes; parser (main), kept: without it Tessera's tokenizer misreads the script and reports an unmatched `</script>` instead, and the verifier accepts a static `<script><!-- a --></script>` (#411) | seed `A36`; `HtmlParserTest` | Lean `NoScriptDanger` (S6b); JML not yet |
| `plaintext` is an error | parser (main), kept (#411) | seed `A62` | Lean: excluded (`htmlNames`) |
| `xmp`, `noembed`, `noframes`, `frameset`, `frame` are errors | verifier: `frameset` is an error (a coarse exclusion after #410); phase 2 (#328); parser: their end tag must match (#298) | seeds `A8`, `A28`, `A29`, `A60` | Lean: excluded (`htmlNames`) |
| `iframe` must be empty | phase 2 (#328); parser: its end tag must match (#298) | seed `A30` | Lean `RawKindOK` (`iframe`, empty body) |
| `noscript`: no `</noscript` before its own end tag, including in comments and static attribute values (decision 2) | parser (#298), kept: the verifier accepts `<noscript><!-- </noscript><script> --></noscript>` in a partial, where the `<script>` it leaves open is no parse error (#411) | seed `A9` | Lean `NoscriptBodyOK` (stricter: no `<` in comments or static attribute values) |
| `noscript`: no `t:insert`, `t:utext`, `[(...)]`; no value, condition or loop at all inside it or `xmp`, `iframe`, `noembed`, `noframes` (#571) | generator (#298, #571) | `BrowserRawTextTest` | Lean: raw holes not modeled |
| `svg`, `math` enter SVG/MathML mode; HTML breakout tags (`p`, `b`, `font` with `color`/`face`/`size`, ...) are errors there | verifier: PARSE_ERROR (#406), whose message names the fix since #442; parser (#301), kept: the verifier accepts a breakout tag that is never written (`<p t:remove="all">`) (#411) | seeds `A25`, `A26`, `B10`; generator interference "breakout tags" | JML (fork branch `proof-b-301`); Lean `svg_no_breakout`, `math_no_breakout` |
| Integration points `title`, `desc` (SVG) and `mi`, `mo`, `mn`, `ms`, `mtext` (MathML) take text and TEXT holes only, with `{{` broken | parser (#301): the TEXT context, and `<` in an SVG or MathML `title` or `textarea`, kept with the same rule for `script` and `style` (next row); verifier: any CDATA section there, where browsers split (#296), exclusion (d) `CDATA_IN_INTEGRATION_POINT` (#441; the parser's rule for a `>` in a CDATA section in an SVG `title` went); text-only content phase 2 (#328) | seeds `A7`, `A21`, `A22`, `A48` | Lean `TextOnly` |
| SVG `script` and `style` take no holes, no `<` and no `&` | verifier: no holes; parser: `<` and `&` (#301), kept: the verifier accepts static text that parses without errors, such as `<svg><style><!-- a{} --></style>` or `<svg><script>if (a && b) {}</script>` (#411) | seeds `A3`, `A4`, `A55`, `A56`, `A58`, `B0`, `B11` | Lean `StaticNoAmp` |
| `foreignObject` holds an HTML subtree under these rules | parser (#301) | seeds `A23`, `B9`; `foreignObject` wrapper axis | Lean `NodeOK .svg` (`foreignobject`) |
| `annotation-xml` with `encoding="text/html"` or `"application/xhtml+xml"` is an HTML integration point | verifier: a computed `encoding` in MathML is an error; parser (#301, static `encoding`); a computed `encoding`: in fragments #304, everywhere phase 2 (#328) (decision 4) | `annotation-xml` wrapper axis | Lean: MathML content only (stricter) |
| `select` takes only `option`, `optgroup`, `hr`, `t:block` and text | parser (#305) in the template's source, kept as the in-template fix-it (#411); verifier: exclusion (c) `SELECT_CONTENT` on composed output, including fragments and unwrapped elements (#406) | seed `A31`; `<select>` wrapper axis | JML `selectContent` (fork branch `proof-b-305`); Lean `NodeOK .select` |
| No nested `optgroup` inside `select` (decision 3) | phase 2 (#328) | none yet | Lean `NoOptgroup` |
| No nested `<form>` | verifier: PARSE_ERROR (#406) | `CsrfFormactionTest` | not modeled (tree level) |
| Tables are allowed; foster parenting is an error | verifier (#367): text holes in a table part, and raw HTML anywhere in a table outside a cell or caption, are errors; static content that would be foster-parented is a parse error (#406); SVG or MathML anywhere inside a table part is exclusion (a) `FOREIGN_IN_TABLE` (#406); generator (#351, not an `HtmlParser` rule): computed text directly in `table`, `tbody`, `thead`, `tfoot`, `tr`, `colgroup` is an error, table parts must sit directly in the part that takes them, and `form` may not sit directly in a table part | seed `A24`; `ClientTemplateInjectionTest`, `XssContextTest`; `table`, `table>tbody>tr` container values | not modeled (tree level, G1) |
| `template` holds flow content under the body profile; `col`, `colgroup`, `caption`, `tbody`, `thead`, `tfoot`, `tr`, `td`, `th` as direct children are errors (decision 5) | verifier exclusion (b) `TABLE_IN_TEMPLATE` (#406); in the profile phase 2 (#328) | seeds `A61`, `B8`; `template` wrapper axis | not yet (Lean leaves `template` out) |

## 4. Scripts: the script prelude rule (§5.4)

Applies to a `<script>` with no `type` or a JavaScript, module or JSON type
(`Contexts.SCRIPT_TYPES`). Holes in other script types are errors (`FORBIDDEN`), and a computed
`type` (`t:type`) is an error.

| Rule | Status | Checked by | Proof |
|---|---|---|---|
| Holes take a JSON value written by `Escapers.javaScript` (`Escapers.json` in a JSON script), which also escapes `<`, `>`, `&`, `'`, `/`, backtick, `$`, U+2028 and U+2029 | main | `ScriptJsonTest`; #341 slot check | `Esc` `jsonScriptSafe` (strings); `JsonFirst` for other values unproved |
| In a JavaScript script every value is written in parentheses, `(json)` (`Escapers.javaScript` and the generated bean serializers), so it is one primary expression whatever its type; JSON scripts (`application/json`, `application/ld+json`) keep plain JSON (`Escapers.json`) | main (#540, replacing #511 and #533) | `ScriptNumberTest`, `ScriptJsonTest`, `EscapersTest` | not yet |
| A `BigDecimal` or `BigInteger` is written only if its class is exactly the JDK class; a subclass fails the render (scripts, bindings, messages, styles) | main (#540, replacing #508's text check) | `BigNumberSubclassTest`, `EscapersTest` | n/a |
| `{` in JSON strings is written as `\u007b` | main (#303) | `ScriptJsonTest` (#303) | not yet (G3) |
| In a JavaScript script each hole is the whole value of a declaration `const\|let\|var NAME = [[...]];` (a space or tab after the keyword; `NAME` an ASCII identifier, not a reserved word or `location`, whose setter `var location = v` would call; one hole per declaration), and the declarations form a prelude at the top: before, between and after them only ASCII spaces, tabs, line breaks and `//` comments. No form without a keyword. Any other hole is an error with a fix-it (a prelude declaration, or a JSON script and `JSON.parse`); `tessera-migrate` hoists values (#541) | main (#540, `TextInlining.checkPrelude`, replacing #332's `checkDataPrelude` lexer and #509's expression-position checks) | `ScriptPreludeTest`, `ValueAfterLessThanTest`; seeds `A2`, `A32`-`A36`, `A51`, `A52`; generator script positions (rejected outside the prelude) | not yet (Lean does not model JavaScript) |
| `t:text` and `t:utext` on a JavaScript `<script>` are errors, with the same fix-it; on a JSON script `t:text` writes one JSON value | main (#540) | `ScriptPreludeTest`, `TextOutputTest` | n/a |
| In a JSON script each hole is outside a `"..."` string, and there is no `/` outside strings | main (#540) | `ScriptPreludeTest` | n/a |
| After the prelude, any static script text (no holes) | main (#540) | #341 aligns the AST after the slot | n/a |
| No holes in SVG `<script>` | main: the verifier's `JSON_NOT_IN_SCRIPT` (#367) | seeds `A55`, `A56`, `A58`, `B0`, `B11` | Lean `StaticNoAmp` |
| An unclosed static `{{` before a hole in the same script is an error | main (#303) | `ClientTemplateInjectionTest` (#303) | not yet |

Rhino, the #341 engine, cannot parse ES modules, classes, `for await` or object spread, and reports
`JS_UNPARSABLE` for an accepted script it can't parse; the Node job (#345) checks those with acorn.
The gate lists none: seeds `A51` and `A52` are rejected, their holes outside the prelude.

## 5. Composition: content kinds (§5.5)

A content kind is a predicate on the spec parser's state at the insertion point, plus a promise
about what the producer emits (#329, redefined Oct 25, 2026; `verify/ContentKinds`). Together
they let the verifier take the state after a value known only at render time to be the state
before it. Statically known fragments need no kind: the verifier analyses them at each call site.
HTML messages need none either: their markup is known at build time (below).

**`FLOW`, the predicate** (`ContentKinds.problem`), with scripting on and off. `closer` stays: a
runtime value is invisible to the error check. Formatting elements waiting to be reopened are
reached only by error recovery, and SVG or MathML above a cell only past exclusion (a), so #410
dropped both clauses. #444 dropped three formatting entries of one name: one more makes the Noah's
Ark clause drop the oldest from the list, but that element stays open, and in an error-free parse
the list changes the tree only through entries that are no longer open.

- data state, between tokens; the current node is HTML; the mode is in body, in cell, in caption
  or in template (`RAW_NOT_FLOW` otherwise);
- and no open element that flow content would close, reopen or adopt (`RAW_CLOSES_ELEMENT`
  otherwise): a `p` in button scope; a heading as the current node; an `a` in the list of active
  formatting elements after the last marker; a `button`, `nobr` or `ruby` in scope; a `select`,
  `option` or `optgroup` anywhere on the stack.

In template mode the first flow start tag switches to "in body"; the verifier then continues
from both states (the second is the state after `<span></span>`).

**`FLOW`, the producer promise:** balanced content (it closes every element it opens); no
`form`; and no top-level `li`, `dd`, `dt`,
table part, `head`, `body`, `html` or `frameset`. That is why an open `li`, `dd`, `dt` or `form`
is not in the predicate: `<li t:utext="${html.sanitize(x)}">` and sanitized text in a form are
fine.

| Producer | Meets the promise | Checked by |
|---|---|---|
| `html.sanitize`, `html.sanitizeRich`, every `HtmlPolicy` | yes: `HtmlSanitizer` places each element where a browser's tree builder keeps it as written (no block in `p`, heading or phrasing; no `a` in `a`, no formatting element in itself; `li` only in `ul`/`ol`; table parts only in `table`, with `tbody`/`tr` written out and no text directly in table, section or row) and closes everything it opens | `FlowProducerTest` (exit state equals entry state at 14 `FLOW` insertion points, scripting on and off; hostile and fuzzed input, five policies); `SanitizerBalanceTest` (the parsed tree equals the one the tags spell); the #329 PR's parse5 run |
| `SafeHtml.escape`, `SafeHtml.concat` of `FLOW` values | yes (text; concatenation of balanced content) | `FlowProducerTest` |
| A rendered partial template (`SafeHtml.of`, `Fragment.of`) | only if the compiler proved it a `FLOW` partial (#396, below); the runtime refuses any other template with an `IllegalArgumentException` naming it | `FlowPartialTest` (the #329 review's partials are refused, each shown to change the state at a position the predicate accepts; ordinary partials are marked); `FlowProducerTest` (sample partials with hostile values); `Jdk8ProcessorTest` |
| A `Fragment` built in Java with `Fragment.of` | yes (above). `Fragment` is a final class, so no lambda or subclass is one (#581). A page's `Fragment` parameter refuses, at render time, a value without the mark `Fragment.of` sets, also a template-built one handed back by Java. A fragment with a `Fragment` parameter has no Java-facing `fragment_<name>` method (#600), so Java reaches a template only through page `t:params` and registry entries | `FragmentFromJavaTest`, `FragmentTest` |
| `Fragment.unverifiedForGeneratedCode`, and a generated `internalUnverified$<name>` method (a fragment with a `Fragment` parameter) called from Java | for generated code only: they are public because generated classes in other packages call them, and they check nothing, so `Fragment.unverifiedForGeneratedCode(lambda)` passed to `internalUnverified$<name>` renders the lambda's markup unchecked. A call outside generated code is a review point, the caller's responsibility like `SafeHtml.unchecked`; the names say so and are greppable (#581, #600) | `FragmentFromJavaTest.uncheckedFragmentEntryPointsAreTheDocumentedReviewPoints` (pins the unchecked methods to this row) |
| `SafeHtml.unchecked`, a hand-written `Template` | the caller's responsibility | none |

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
- and, at every element inserted in the `<body>` probe (`ContentKinds.promiseBreach`), it keeps
  the one clause of the producer promise that isn't a parse error: no `form` outside a
  `template`. A formatting element inside one of its own name is allowed (#448): where more of
  that name are open around the value, the Noah's Ark clause may drop the oldest entry from the
  list of active formatting elements, but that element stays open, and in an error-free parse the
  list changes the tree only through entries that are no longer open (#444).

The rest of the promise needs no check of its own (#410): in the `<body>` probe a stray end tag,
a top-level table part and a `head`, `body`, `html` or `frameset` start tag are parse errors, an
`li` or `dd` outside its own list (inside a `div` too) closes the open `li` or `dd` of the list
probes, and a table part closes a cell or switches a template's mode. So the bench corpus's two call sites pass: budgetmaster's `NotificationLinkTemplate` (an `a`)
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
| `Fragment` parameter or value | `FLOW` | verifier (#367, #329, #561): a template-built value is a closed set of candidates (a `Fragment` parameter, `null`, a `t:with` alias of one, a ternary or Elvis of those; inserted or passed on), each bound argument parsed where it lands; any other `Fragment` expression (also any function or method call, whatever its parameter types), and a `@TemplateFunction` that takes a `Fragment`, is a build error. Built in Java (page parameter, unbound parameter): `FLOW`, and only a `Fragment.of` of a verified `FLOW` partial, which the generated page and fragment methods check at render time (#581); #304 | `FragmentCandidatesTest`, `FragmentFromJavaTest`, `ContentKindsTest`, `FlowProducerTest`, `FragmentParsingContextTest`; composition value `Fragment f` | not yet |
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
escapers below ship today. No name is classified by client framework (#583, #584): a name outside
HTML takes only a framework-neutral value (`NEUTRAL`).

| Class (`OutputContext`) | Where | Escaper | Status | Checked by | Proof |
|---|---|---|---|---|---|
| `ATTRIBUTE` | inert-text attributes (`inert-attributes.txt`: text, keyword, number, boolean, ID and token values, ARIA; on SVG and MathML only text and language attributes), on any element, a custom element included (#584), and `content` of a `<meta>` whose static `name` is on the allow-list (`description`, `keywords`, `author`, `application-name`, `generator`, `color-scheme`, `theme-color`; #590), with no computed `name` and no `http-equiv`, `property` or `itemprop` | `Escapers.attr` | main | seeds `A38`, `A39`, `A49`; hole values `t:title`, `t:value`, `t:class` | `Esc` `htmlSafe`; Lean slice 2 |
| `TYPED_ATTRIBUTE` | every other standard name without a class of its own, on any element: `rel`, `target`, `sandbox`, `allow`, `crossorigin`, `itemtype`, `usemap` (a hash-name reference, not a URL), other `<meta>` `content` (`referrer`, `viewport`, `htmx-config`, a name a browser, OS or crawler acts on such as `apple-itunes-app` or `google-site-verification`, an unknown name, Open Graph `property`, `itemprop`, a computed `name`; #590), SVG and MathML geometry and presentation attributes, ... | `Escapers.attr`, string literals, numbers and a choice between literals only (a condition for a boolean attribute); a plain value or a safe type is a build error (#565) | main (#539) | `InertAttributesTest`, `KnownAttributesTest`, `ContextsTest` | `Esc` `htmlSafe` |
| `NEUTRAL` | every plain name that is not a standard HTML, SVG, MathML or ARIA name (`attributes.txt`): `data-*`, `hx-*`, `x-*`, `v-*`, `v-bind:x`, `ng-*`, `up-*`, a custom element's own attributes, any other name (#583 rule 3) | `Escapers.attr` over a framework-neutral value, by static type and expression shape: a number (`Escapers.neutralNumber`: exact JDK class, plain decimal `-?(0\|[1-9][0-9]*)(\.[0-9]+)?`, no exponent, NaN or Infinity), a boolean (`true`, `false`), a link `@{/path}` whose literal path starts with a single `/`, holds only `A-Z a-z 0-9 . _ ~ / -` and `%XX` and has no query (`Escapers.neutralPath`: percent-encodes every other byte of the built URL and the second `-` of `--`, and checks it starts with a single `/`), or a choice (`c ? a : b`, `a ?: b`) between those and literals; a String, concatenation or any other type is a build error | main (#584) | `NeutralValuesTest`, `ContextsTest`, `EscapersTest`; hostile sweep: every name outside HTML it accepts, with a String, a link, a number and a boolean, renders only values in the neutral grammar (`HostileSweepTest`, `NON_NEUTRAL_VALUE`, #587) | the alphabet is the safety research on #583 (headless Chromium, 20 libraries): a value starting with `/` is read as a regular expression literal by every JavaScript evaluator, `--` was the one write primitive inside it |
| `URL` | the attributes whose grammar is exactly one URL (WHATWG "valid URL potentially surrounded by spaces"): `href src action formaction cite poster xlink:href` and the obsolete `background longdesc manifest` | `Escapers.url` | main; only exactly-one-URL attributes (#539) | seeds `A40`, `A41`; `AttributesEndToEndTest` (`manifest`) | `Esc` `htmlSafe`; scheme check not yet |
| `TRUSTED_URL` | attributes that hold URLs but are not exactly one URL: the lists `ping` (space-separated) and `srcset imagesrcset` (comma-separated candidates), and `values to from by` on SVG `animate` and `set`, whose meaning depends on `attributeName` (not when they animate a `STYLE` attribute) | `Escapers.attr`, literals, numbers, a choice between literals and `TrustedUrl` only; a plain value is a build error | main (#414, #539) | `AttributesEndToEndTest` (`ping`, `srcset`, animation values), `XssContextTest`, `ContextsTest` | `Esc` `htmlSafe` |
| `RESOURCE_URL` | `script[src|href|xlink:href]`, `iframe/frame/embed[src]`, `object[data]`, `base/link[href]` | `Escapers.resourceUrl` | main; protocol-relative `/\` paths rejected for every link (#340); every `<base href>`, static or computed, must be same-origin, main (#357); a link in a same-origin slot is re-checked when rendered, so a context path or custom `Links` cannot make it protocol-relative, main (#358) | seeds `A42`, `A43`, `B1`, `B2`, `B4`; `LinkCompilerTest`, `CsrfBaseTest`, `SameOriginLinkRenderTest` | `Esc` `htmlSafe`; origin fixed by the literal; not proved |
| `REFRESH` | `content` of `<meta http-equiv="refresh">` | `Escapers.refresh` | main | seed `B3` | `Esc` `htmlSafe` |
| `SCRIPT_ATTRIBUTE` | none since #584: Vue/Alpine bindings are `NEUTRAL` (`v-bind:x`, `x-bind:x`) or `FORBIDDEN` (`:x`) | `Escapers.jsonAttr` (unused; TODO(#599): delete) | removed (#584) | n/a | n/a |
| `STYLE` | `style` attribute; SVG `fill stroke clip-path mask filter marker-start marker-mid marker-end marker cursor` (not `fill` on an animation element); `values to from by` of an animation whose static `attributeName` is one of them (the attribute name folds A-Z only, as in a browser: `attrıbuteName` with a dotless i is another attribute) | `Escapers.attr`, literals, numbers and `CssValue` only | main | `StyleAttributeTest`, `ContextsTest` | not yet |
| `EVENT_HANDLER` | `on*` | none: build error | main | `ContextsTest` | n/a |
| `FORBIDDEN` | `srcdoc`, `<script type>`, `<meta http-equiv>`, `attributeName` of an SVG animation element, a computed htmx 1.x `hx-sse` or `hx-ws` (#562), attributes the tree builder reads (`is`, `xmlns`, `xmlns:xlink`; `<template>` `shadowroot*` and `for`; `<meta>` `charset`, and `content` under a static `http-equiv="Content-Type"`), names outside `[A-Za-z_][A-Za-z0-9_.:-]*` (Vue's `:x`, `@x`, `#x`, `.x` included: stricter than rule 3) | none: build error | main | `ContextsTest`, `NeutralValuesTest` | n/a |

### 6.2 Which computed names are accepted

Static attributes are copied verbatim and are not affected by these rules, except a static `is`.

| Name | Target | Today on main | Status | Checked by |
|---|---|---|---|---|
| Standard HTML, SVG, MathML or ARIA attribute | accepted, classified as in 6.1 | accepted, on any element; the names are `tessera-compiler/src/main/resources/dev/tessera/compiler/html/attributes.txt` (WHATWG HTML index and obsolete attributes, SVG 2 attribute and property indexes, MathML Core, WAI-ARIA 1.2; the file names the versions) | main | `ContextsTest`, `KnownAttributesTest` |
| Any other name matching `[A-Za-z_][A-Za-z0-9_.:-]*` (`data-*`, framework names such as `hx-get` or `hx-boost`, a custom element's own attributes) | `NEUTRAL` (#583 rule 3) | `NEUTRAL`; the build error's fix-it: the element text or a standard inert attribute such as `title`, hidden inputs (with `hx-include`), or a `<script type="application/json">` block | main (#584; covers #273's Unpoly and Datastar names and #276's `hx-action`) | `NeutralValuesTest`, `ContextsTest` |
| Any other name | error | error ("names must match") | main | `ContextsTest`, `NeutralValuesTest` |
| Static `is` | a valid custom element name only (HTML 4.13.3), so nothing that reads `is` renders another element than the one classified, such as `<component is="script">` | ASCII `[a-z][a-z0-9._]*-[a-z0-9._-]*`, not a reserved SVG/MathML name; `<component>` is an ordinary element | main (#584, closes #272's element half). A framework's own element switch in static markup (Vue's `:is="'script'"`, `v-bind:is`) is copied verbatim: what client code builds from static markup is outside the guarantee (principle 7, security-model.md) | `NeutralValuesTest`, `ContextsTest` |
| Computed `action` on a `<form>` | same-origin link or `TrustedUrl`, with or without client code that submits the form; a static POST form: same-origin link only | as the target; replaces the `hx-boost` tracking of #258 (#585) | main (#585, #285) | `FormActionTest`, `CsrfActionOriginTest` |
| Computed `formaction` | same-origin link only | same-origin link required on every element, since any form may get the CSRF token | main (#346, #314) | seeds `A45`, `B6`; `CsrfFormactionTest` |
| Computed `href` on `<base>` | same-origin link only, in every template (a static one must be same-origin by shape) | same-origin link required; static `href` checked like a static `action` | main (#357) | `CsrfBaseTest` |

### 6.3 Client code

There is no framework table (#583-#587): client code that evaluates attribute values or DOM text
is outside the guarantee (principle 7), and the compiler constrains the shape of its own output
instead (`NEUTRAL` above, `{{` below). The hostile sweep checks that shape without naming a
framework either (#587). The framework-named code that remains: a computed htmx 1.x `hx-ws` or
`hx-sse` is a build error (#562), and `v-pre` on a computed `<title>` or `<textarea>` (#586).

| `{{` rule | Status | Checked by |
|---|---|---|
| Every `{` and `}` of an element text value between two `<!---->` (#571) | main | hostile value `{{PWN()}}`; `EscapersTest`, `TextChokePointTest`, `ClientTemplateInjectionTest` |
| `{{` broken in SVG/MathML text | #301 (TEXT context) | seed `A7` |
| `{` as `\u007b` in JSON strings | main (#303) | `ScriptJsonTest` (#303) |
| A static `{` or `}` in element text (CDATA in foreign content included) outside a complete `{{ ... }}` in one literal is an error, whatever is next to it; in a script, an unclosed static `{{` before a hole | main (#571, replacing the #336 edge rule; nothing about neighbours is looked at); script main (#303) | seed `A18`; `ClientTemplateInjectionTest` |
| A static `&` that starts a character reference before a hole is an error, so a value can't spell `{` as `&#123;` (`&[[a]]&[[b]]`, a = b = `#123;`); an HTML message the build did not check is escaped as text | verifier (`TEXT_NOT_IN_TEXT`, §2 "Text: hole after `&`"); runtime (`Escapers.htmlMessage`) | `ValueAfterAmpersandTest`, `EscapersTest` |
| RCDATA (`<title>`, `<textarea>`): no lossless break, so a computed one gets the constant attribute `v-pre` (Vue, petite-vue), whatever else is on it; no duplicate `v-pre`. Its static text follows the element-text brace rule above | main (#549, simplified in #586) | `VPreRcdataTest`, `ClientTemplateInjectionTest`; that Vue 3.5.44 and petite-vue 0.4.1 skip it was checked by hand in Chromium (the hostile value runs without `v-pre`, not with it), framework harness rows #334 |
| RAWTEXT (`xmp`, `iframe`, `noembed`, `noframes`, `noscript`): no break at all, so a value, condition or loop inside is an error | verifier for values (`VALUE_IN_RAW_TEXT`, #586); generator early error for values, conditions and loops (#571) | `ContextVerifierRulesTest`, `BrowserRawTextTest` |
| What client code makes of static markup, standard attribute values (class tokens included) and custom delimiters, AngularJS included: outside the guarantee (guide §7) | not modelled; a strict CSP is the backstop (#335, guide §4.11) | seed `A37` |

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
| 15 | ~~Two parsers: pinned current upstream validator.nu htmlparser and the 1.4.16 classic snapshot; a hole is accepted only if both accept~~ Superseded by 19 | Covered the classic and customizable `<select>` split (Firefox ESR); exclusion (c) does now |
| 16 | The parsers are shaded into `tessera-compiler`, relocated and built for Java 8; the runtime and starters stay dependency-free | Upstream ships no Java 8 jar; the processor runs in a JDK 8 javac |
| 17 | Markup structure that depends on data (recursive rich-text fragments) is a build error, with a `SafeHtml` fix-it; `tessera-migrate` reports it | It breaks the per-hole invariant; 25 of 1,585 corpus templates |
| 18 | Scripts (#332), URL policy and client frameworks (phase 4) stay as planned; CSP auto-nonce (#335) moves earlier | The verifier doesn't cover them |
| 19 | One engine: the pinned current validator.nu htmlparser; the classic 1.4.16 engine goes (#408) | No template, seed or generator case needs it once the error-free rule and exclusion (c) apply |
| 20 | Error-free parses only: on every path, scripting on and off, no parse error except the missing-doctype messages; partials only in their implied fragment context (#406) | Error recovery is where engines and browsers split; the corpus barely uses it (101 of 1,120 accepted templates, 80 of those one layout idiom) |
| 21 | Coarse exclusions: SVG/MathML above a table part or `template`; table parts directly in `template`; only `option`/`optgroup`/`hr` above `select` (#406); no CDATA section in an integration point (#441) | The known splits (#398, #401, #305, #296), covered by a stack shape rather than a model |
| 22 | A divergence the nightly detector finds becomes a coarse exclusion plus a test, never a modelled algorithm or an engine patch | Ends the modelling treadmill; #406 replaces PR #402 with exclusion (a) |
| 23 | The hole-state rules and `ContentKinds.closer` stay | Values are invisible to the error check |

## 9. The verifier's rule table (#367)

`verify/Rules` and `verify/ContextVerifier`. Each path through a template holds the parser twice,
scripting on and off; a template is accepted only if both accept it. Retired ids are no longer
reported; their names stay reserved.
The id in brackets ends every error message (`[verifier:ID]`), for #368's fix-its.

The parser state names the one escaper a hole may have (#378): data `text`, RCDATA `rcdata`, the
script data of an HTML `<script>` `json`, a double-quoted attribute value an attribute escaper
for that attribute (which one is policy, `Contexts.attribute`). Raw text names none: no hole is
accepted there (#586). A hole with another escaper is rejected, so a hole whose paths reach states that name
different escapers is rejected (Go's branch rule). For element text the verifier makes the choice:
the compiler writes a `text` hole with its escaper left open, the first state that names one
(`text` or `rcdata`) decides it, and the processor sets it in the generated code before writing
it. Since #461 every fragment's holes are reached. Element text that one path or call writes in
RCDATA and another in data is rejected (`TEXT_IN_RCDATA`, `RCDATA_NOT_IN_RCDATA`).

| Id | Rejects | Accepted instead |
|---|---|---|
| `TEXT_NOT_IN_TEXT` | a `text` or `rcdata` hole inside a tag, comment, character reference, CDATA, doctype or `plaintext` | data state; RCDATA |
| `TEXT_IN_SCRIPT` | a `text` or `rcdata` hole in script data, `style`, or SVG/MathML `script`/`style` | as above |
| `TEXT_IN_TABLE` | a `text` hole in a table, table body, row or column group (foster-parented table text; an element foster-parented out of the table is a parse error) | in a cell or caption |
| `TEXT_OUTSIDE_BODY` | a `text` hole before `<html>`, in or after the head, after the body, in a frameset | in body, cell, caption, `select`, `template`; in foreign content |
| `TEXT_RECONSTRUCTS` | retired (#410): a `text` hole where formatting elements closed out of order would be reopened first. The misnesting is a parse error | |
| `RCDATA_NOT_IN_RCDATA` | an `rcdata` hole (no `{{` breaking) a browser reads as ordinary text; element text written in RCDATA first and then in data | RCDATA |
| `TEXT_IN_RCDATA` (#378) | element text written in data first (with the comments that break `{{`) and then in the RCDATA of `<title>` or `<textarea>`, where the comments would show | one escaper for every path and call |
| `ATTRIBUTE_NOT_IN_VALUE`, `ATTRIBUTE_WRONG_NAME` | an attribute hole outside a double-quoted value, or in another attribute's value (or a dropped duplicate) | the double-quoted value of the attribute the compiler wrote, compared case-insensitively |
| `ATTRIBUTE_VALUE_CHANGES_PARSE` | a computed `encoding` in MathML (an `<input>` in a table mode is a parse error with any `type`) | static values |
| `JSON_NOT_IN_SCRIPT` | a `json` hole outside the script data of an HTML `<script>` (SVG `script`, escaped script data, CDATA) | script data, also right after `<` (no JSON starts with `/` or `!`) |
| `RAW_NOT_FLOW` | `SafeHtml`, an unbound or Java-built `Fragment` outside data state, HTML namespace, or body/cell/caption/template mode | flow content (section 5) |
| `RAW_CLOSES_ELEMENT` | the same where flow content would close, reopen or adopt an open element: `p` in button scope, a current heading, an active `a`, `button`/`nobr`/`ruby` in scope, `select`/`option`/`optgroup` | flow content (section 5) |
| `MESSAGE_CHANGES_STATE` | an HTML message whose markup (any locale, either form) leaves the parser in another state than it found it | a message that closes what it opens, where its markup fits |
| `FRAMESET` | any `frameset` element the parser inserts, a coarse exclusion (#410); where a `<frameset>` start tag doesn't apply, it is a parse error | |
| `NOAHS_ARK` | retired (#444): three or more identical formatting elements, one of them with a computed attribute. The value decides which entry the Noah's Ark clause drops from the list of active formatting elements, but the element stays open, and in an error-free parse the list changes the tree only through entries that are no longer open. Misnested, they are a parse error | |
| `PARSE_ERROR` (#406) | any tokenizer or tree-builder error on any path, scripting on or off, except the missing-doctype messages; a partial only in its implied fragment context. The current engine reports them; each parser reports each rule once per path, at its first occurrence. validator.nu's "Duplicate ID" isn't a parse error and is allowed (it is checked only within one chunk of input) | markup that parses without errors |
| `FOREIGN_IN_TABLE` (#406) | an `svg` or `math` element open with `td`, `th`, `caption`, `tr`, `tbody`, `thead`, `tfoot`, `table` or `template` below it (the fragment context counts) | SVG and MathML outside tables; a table inside `foreignObject` outside any table |
| `TABLE_IN_TEMPLATE` (#406) | an HTML table part directly in `template`, or a table mode in the template-mode stack | flow content in `template` |
| `SELECT_CONTENT` (#406) | any element other than `option`, `optgroup` or `hr` open above `select`, in composed output (void elements too) | option, optgroup, hr and text |
| `CDATA_IN_INTEGRATION_POINT` (#441) | a CDATA section the parser starts while the current node is an HTML integration point or a MathML text integration point, exclusion (d) | CDATA in other SVG and MathML elements (`style`, `script`, `text`, `g`, `mrow`) |
| `UNCALLED_FRAGMENT_FITS_NOWHERE` (#461) | a fragment no template calls, declared where no fragment fits (raw text, mixed `svg`/`math`, `t:remove="tag"` content). Every other fragment no template calls is checked on its own: declared in HTML content, in the fragment contexts its root elements imply (as a document only if it is a page); in `svg`, `math` or `select` content, in that element | a fragment some template calls, or one declared in HTML, `svg`, `math` or `select` content |
| `LOOP_NO_FIXED_POINT`, `STRUCTURE_DEPENDS_ON_DATA` | a loop, or a fragment calling itself, that reaches new parser states after 4 rounds (or more than 24 states for one recursive fragment); anything reached only through recursion | loops and recursion that settle |
| `TOO_MANY_STATES` | more than 24 distinct parser states at a join | |
| `VALUE_IN_RAW_TEXT` (#586) | any hole, whatever its escaper, in the raw text of `noscript` (scripting on), `iframe`, `xmp`, `noembed` or `noframes`, where an empty comment is text and nothing breaks a value's `{{` (the CSRF input of a `<form>` inside `<noscript>` included) | static content; the value outside the element |
| `ENGINE_FAILURE`, `UNKNOWN_NODE` | a parser that throws or reports an unnamed state; a fragment the skeleton doesn't define | |

A computed attribute (`[ name="..."]`) inside a start tag is one path: writing it or not leaves the
tokenizer in states that parse what follows alike, except for `type`, `encoding`, `color`, `face`
and `size`, which stay two paths. A partial (a view whose output doesn't start with a doctype or
`<html>`) is also checked by fragment parsing in the context its first element implies (`tr`:
`tbody`; `td`, `th`: `tr`; `option`, `optgroup`: `select`; `li`: `ul`; otherwise `body`).
