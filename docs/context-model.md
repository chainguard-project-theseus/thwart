# Context model: the Thwart HTML profile

This document specifies, as tables, the set of templates the compiler accepts and where each
value may go. It is the detail behind [security-architecture.md](security-architecture.md) §5 and
§11; the threat model is [security-model.md](security-model.md), and the porting recipe for each
rejection is [guide.md](guide.md).

The security boundary for markup is the build-time verifier (`verify/ContextVerifier`, rule table
`verify/Rules`, section 9). It runs every template through the pinned spec HTML parser and
accepts only error-free parses outside four coarse exclusions, with every value in a state where
its escaper is inert. The parser and generator rules in sections 2 and 3 run first, as friendly
early errors with a better message. Sections 4 and 6 hold what the verifier doesn't decide: script
lexical position, URL policy and the value shapes of attribute names.

## How to keep this document current

- A PR that changes `HtmlParser`, `MarkupTokenizer`, `Contexts`, `TextInlining`, `Escapers`,
  `ContentKinds` or the verifier (`verify/`) updates this document in the same PR.
- A PR that widens the accepted language says so in its description and adds a seed
  (`thwart-compiler/src/test/resources/oracle/seeds`) or a test for the new row.
- Every row names its check. **Enforced by** is the layer that rejects: `verifier` (by a rule id
  of section 9), `parser` (`html/HtmlParser`, `MarkupTokenizer`, before a skeleton exists),
  `generator` (`codegen/TemplateGenerator` and the processors), or `runtime`.

## 1. The invariant

For every template T the compiler accepts, every model M, and every supported browser or spec
HTML parser that parses `render(T, M)`:

- each value lands in the node, context and namespace the compiler chose;
- the static structure is the same for every value: the benign and hostile trees are equal.

This is a per-value claim. Implied end tags may move static nodes, the same way for every value.

| Part | Enforced by | Checked by |
|---|---|---|
| Static structure is the same for every value | verifier: error-free parses, state-neutral control flow, inert holes | `ContextVerifierRulesTest`, `SeedVerifierTest`, `ProfileOracleTest` |
| Each value lands in the node and context the compiler chose | verifier: the parser state at each hole | `ContextVerifierRulesTest`, `SeedVerifierTest` |
| No value runs as script, handler or `javascript:` URL, or loads a cross-origin resource | escapers, attribute classes (section 6), the script prelude (section 4) | `HostileSweepTest`, `EscapersTest`, `EscaperFuzzTest` |
| Static structure doesn't depend on the browser | verifier: one engine, error-free parses, the exclusions (section 9) | the Node job (`.chainguard/ci/oracle-node.yaml`: parse5, Chromium, Firefox ESR, WebKit, after every merge) |

## 2. Token-level rules

| Construct | Accepted | Rejected | Enforced by | Checked by |
|---|---|---|---|---|
| Element name | `[A-Za-z][A-Za-z0-9_.:-]*` | anything else; a value in a name | parser | `HtmlParserTest`, `ValueAfterLessThanTest` |
| Tag separators | ASCII whitespace (TAB, LF, FF, CR, SPACE) | U+000B, U+001C-U+001F and other Java-only whitespace in a tag | parser; verifier for values | `XssContextTest` |
| Quotes in tags | `"` or `'` only as delimiters of a quoted value | a quote in a name, in an unquoted value, or right after a quoted value | parser; verifier for values | `HtmlParserTest`, `XssContextTest` |
| Attribute names | no `=`, `<` or quotes | the rest | parser | `HtmlParserTest` |
| Duplicate attributes | one per name | two with the same ASCII-lowercased name | parser | `HtmlParserTest` |
| `&` in text and attribute values | a complete character reference | a named reference without its `;` | verifier: `PARSE_ERROR` | `ErrorFreeTest` |
| Value after `&` | after a complete reference, or `&` and a character that starts none | after static `&`, `&#`, `&#x` or `&` + letters, which a value could finish into a reference | verifier: `TEXT_NOT_IN_TEXT` | `ValueAfterAmpersandTest` |
| `/>` | on void HTML elements | on non-void HTML elements, except where Thwart writes the end tag (`<textarea t:field/>`) | verifier: `PARSE_ERROR` with a fix-it | `ParseErrorFixItTest`, `BrowserRawTextTest` |
| End tag | `</name>` matching the open element | stray or mismatched end tags | parser (Thwart's tree is built from matched tags) | `HtmlParserTest` |
| Comment | `<!--` text `-->` | `<!-->`, `<!--->`, `--!>` | parser; verifier: `PARSE_ERROR`; values in comments: `TEXT_NOT_IN_TEXT` | `HtmlParserTest`, `XssContextTest` |
| Template comment | `<!--/*` text `*/-->`, dropped before codegen | unclosed; `<!--/*/`; inside `script`, `style`, `title`, `textarea` | parser | `HtmlParserTest` |
| Doctype | `<!DOCTYPE html>`, first token only | other forms or positions | verifier: `PARSE_ERROR` with a fix-it | `ParseErrorFixItTest` |
| `<?`, other `<!` forms | none | processing instructions, bogus comments | verifier: `PARSE_ERROR` with a fix-it | `ParseErrorFixItTest` |
| CDATA section | none | anywhere | parser; verifier: `CDATA_SECTION` (exclusion (d)) | `ContextVerifierRulesTest`, seeds |
| `<` in text | the start of a tag or comment | a literal `<` that starts nothing (write `&lt;`) | verifier: `PARSE_ERROR` | `ValueAfterLessThanTest` |
| Value after `<` | after any other text | after static `<`, `<!`, `<!-`, `<?`, `</`+letters | parser; verifier | `ValueAfterLessThanTest` |
| RCDATA and raw-text content | no `</name` + whitespace, `/` or `>` before the element's own end tag | the rest | parser; verifier for values | `BrowserRawTextTest` |
| Value positions | `[[...]]`, `[(...)]` in text; `t:*` attributes | in comments, tags, attribute or element names (`[[...]]` in a comment or static attribute is literal text) | parser; verifier | `HtmlParserTest`, `TextOutputTest` |

## 3. Elements and parse modes

### 3.1 Modes and what they accept

| Mode | Entered by | Content | Values | Enforced by | Checked by |
|---|---|---|---|---|---|
| HTML | document root | HTML elements, custom elements (name with `-`); a static `is` must be a valid custom element name | text | verifier; generator (`is`) | `NeutralValuesTest`, seeds |
| Script | `<script>` | static script text | the script prelude (section 4) | verifier: `JSON_NOT_IN_SCRIPT`, `TEXT_IN_SCRIPT`; generator | `ScriptPreludeTest`, seeds |
| Style | `<style>` | static CSS | none | verifier: `TEXT_IN_SCRIPT`; generator | seeds |
| RCDATA | `<title>`, `<textarea>` | text | text | verifier | `BrowserRawTextTest` |
| SVG, MathML | `<svg>`, `<math>` | static markup only | none: no computed attribute, `[[...]]`, `t:*` or fragment inside (control flow on the `<svg>`/`<math>` element itself is fine) | generator; verifier: `VALUE_IN_FOREIGN` | `ContextVerifierRulesTest`, `ContextVerifierTest` |
| Select | `<select>` | `option`, `optgroup`, `hr`, `t:block`, text | text | parser; verifier: `SELECT_CONTENT` | seeds |
| `noscript`, `iframe` | `<noscript>`, `<iframe>` | static content only | none | generator; verifier: `VALUE_IN_RAW_TEXT` | `BrowserRawTextTest`, `ContextVerifierRulesTest` |

### 3.2 Element rules

| Rule | Enforced by | Checked by |
|---|---|---|
| `xmp`, `noembed`, `noframes`, `frameset`, `plaintext` are errors | generator (`plaintext`: parser); verifier: `PARSE_ERROR` for any `frameset` | `UnmodelledMarkupTest`, seeds |
| A browser ends raw text and RCDATA at the first ASCII-case `</name` + whitespace, `/` or `>`; it must be the element's own end tag | parser | `BrowserRawTextTest` |
| `<!--` inside `<script>` is an error | parser | `HtmlParserTest` |
| `noscript`: no `</noscript` before its own end tag, in comments and attribute values too | parser | `BrowserRawTextTest` |
| No `t:insert`, `t:utext`, `[(...)]`, value, condition or loop inside `noscript`, `iframe` | generator; verifier: `VALUE_IN_RAW_TEXT` | `BrowserRawTextTest` |
| Inside `svg`/`math`, HTML breakout tags (`p`, `b`, `font` with `color`/`face`/`size`, ...) are errors | verifier: `PARSE_ERROR` (the parser marks everything under `svg`/`math` foreign, by tag) | seeds, `UnmodelledMarkupTest` |
| `svg`/`math` inside a table part or `template` | verifier: `FOREIGN_IN_TABLE` (exclusion (a)) | seeds, `ErrorFreeTest` |
| `template` holds flow content; table parts as direct children are errors | verifier: `TABLE_IN_TEMPLATE` (exclusion (b)) | seeds |
| No nested `<form>` | verifier: `PARSE_ERROR` | `CsrfFormactionTest` |
| Tables: values go in cells and captions; content that would be foster-parented is a parse error | verifier: `TEXT_IN_TABLE`, `RAW_NOT_FLOW`, `PARSE_ERROR`; generator: computed text directly in a table part, misplaced table parts | `ClientTemplateInjectionTest`, `XssContextTest` |

## 4. Scripts: the script prelude rule

Applies to a `<script>` with no `type` or a JavaScript, module or JSON type
(`Contexts.SCRIPT_TYPES`); the type is the first static `type`. Values in other script types are
errors (`FORBIDDEN`), and a computed `type` is an error.

| Rule | Enforced by | Checked by |
|---|---|---|
| In a JavaScript script each value is the whole value of `const\|let\|var NAME = [[...]];` (`NAME` an ASCII identifier, not a reserved word or `location`; one value per declaration), and the declarations form a prelude at the top, with only whitespace and `//` comments between them. Any other value is an error with a fix-it | generator (`TextInlining.checkPrelude`) | `ScriptPreludeTest`; `HostileSweepTest` (script pass) |
| The value is JSON in parentheses (`Escapers.javaScript`), so it is one primary expression; a JSON script takes plain JSON (`Escapers.json`) outside `"..."` strings, with no `/` outside strings | generator; runtime | `ScriptJsonTest`, `ScriptNumberTest`, `ScriptPreludeTest` |
| The JSON escapes `<`, `>`, `&`, `'`, `/`, backtick, `$`, `{`, U+2028, U+2029 and every code point the parser reports as an error (as `\uXXXX`) | runtime | `ScriptJsonTest`, `EscapersTest` |
| A `BigDecimal` or `BigInteger` subclass fails the render (scripts, neutral values, messages, styles) | runtime | `BigNumberSubclassTest` |
| `t:text`, `t:utext` on a JavaScript script are errors; on a JSON script `t:text` writes one JSON value | generator | `ScriptPreludeTest`, `TextOutputTest` |
| After the prelude, static script text only | generator | `HostileSweepTest` |

## 5. Composition: content kinds

A value known only at render time is invisible to the parser's error check, so it gets a content
kind: a predicate on the parser state at the insertion point, plus a promise about what its
producer emits (`verify/ContentKinds`). Statically known fragments need no kind: the verifier
analyses them at each call site.

**`FLOW`, the predicate**, scripting on and off:

- data state between tokens, an HTML current node, and in body, cell, caption or template mode
  (`RAW_NOT_FLOW` otherwise);
- no open element that flow content would close, reopen or adopt (`RAW_CLOSES_ELEMENT`): a `p` in
  button scope; a heading as the current node; an active `a`; a `button`, `nobr` or `ruby` in
  scope; a `select`, `option` or `optgroup` anywhere on the stack.

**`FLOW`, the promise:** balanced content without parse errors; no `form`; no top-level `li`,
`dd`, `dt`, table part, `head`, `body`, `html` or `frameset`.

**`PHRASING`** is for HTML messages only: inline elements and links, so only an open
`select`/`option`/`optgroup` and, for a message with links, an active `a` are
`RAW_CLOSES_ELEMENT`.

| Producer | Meets the promise | Checked by |
|---|---|---|
| `html.sanitize`, `html.sanitizeRich`, every `HtmlPolicy` | yes: a closed content model (below), every element closed, unfit tags unwrapped, forbidden code points as U+FFFD | `FlowProducerTest`, `SanitizerBalanceTest` |
| `SafeHtml.escape`, `SafeHtml.concat` of `FLOW` values | yes | `FlowProducerTest`, `EscapersTest` |
| HTML message (`#{...}` in `t:utext`, `[(...)]`) | `PHRASING`: formatted with escaped arguments, then sanitized by a fixed inline policy (`b i em strong u s sub sup small mark code kbd span br`, `title`, and `a` with a checked `href`, `rel="noopener noreferrer"` and `target=_blank` kept, for a key whose bundle values have an `<a`) | `HtmlMessageTest`, `FlowProducerTest` |
| A `Fragment` argument | only between compiled templates: Java can't build one, and no page or `@TemplateFunction` takes one; checked where it lands | `FragmentFromJavaTest`, `FragmentCandidatesTest` |

**The sanitizer's closed content model.** The output is built from a stack; every non-void
element gets an explicit end tag, written only while its element is the current node. P (phrasing)
is `a b i em strong u s del ins sub sup small mark code kbd span br img`.

| Element | Children | Never inside | Attributes |
|---|---|---|---|
| insertion point, `div`, `blockquote`, `li` | text, P, `p h1-h6 pre blockquote ul ol div hr` | | `li`: `value` |
| `p h1-h6 pre` | text, P | | |
| `ul ol` | `li` | | `ol`: `start`, `reversed` |
| `a` | text, P | `a` | checked `href`, `rel`, `target=_blank` |
| `b i em strong u s small code` | text, P | itself | |
| `del ins sub sup mark kbd span` | text, P | | |
| `br hr img` | void | | `img`: `src` (required, same-origin), `alt`, `width`, `height` |

`id`, `class` and `title` go on any element the policy allows them on; ids get the prefix
`user-content-`. Input that doesn't fit is unwrapped; raw-text, foreign, `template`, `select` and
`object` elements are dropped with their content. Nothing is implied, closed early or moved.

| Value or position | Kind | Enforced by | Checked by |
|---|---|---|---|
| Fragment (`t:fragment`) inserted by name | none: analysed at each call site | verifier | `FragmentParsingContextTest` |
| `Fragment` parameter or value | `FLOW` when unbound; each bound argument parsed where it lands; any other `Fragment` expression is an error | verifier; generator | `FragmentCandidatesTest`, `ContentKindsTest` |
| `SafeHtml` (`t:utext`, `[(...)]`) | `FLOW` | verifier | `ContentKindsTest`, `FlowProducerTest` |
| HTML message | `PHRASING` | verifier; runtime (`Escapers.htmlMessage`, a `SafeInlineHtml`; `Escapers.linkedHtmlMessage` with links) | `ContentKindsTest`, `HtmlMessageTest` |
| `t:insert`, `t:utext`, `[(...)]` into RCDATA or raw text | error | generator; verifier | `BrowserRawTextTest` |
| `t:replace` with a whole `<title>` fragment | allowed | | `FragmentsTest` |
| `t:remove="tag"` on raw-text, RCDATA or foreign elements | error | generator; verifier | `ControlFlowTest`, `FragmentsTest` |
| `t:fragment`, or a `~{::selector}` target, declared outside HTML content (`svg`, `math`, `select`, raw text, `t:remove="tag"`) | error | generator | `FragmentParsingContextTest` |

## 6. Attributes

### 6.1 Attribute classes

`Contexts.attribute(element, name)` picks the class of a computed attribute, in this order (`Contexts.forbidden` runs first and gives each `FORBIDDEN` reason; `OutputContext.attributeEscaper()` names each class's escaper). No
name is classified by client framework.

| Class (`OutputContext`) | Where | Accepts | Checked by |
|---|---|---|---|
| `FORBIDDEN` | names outside `[A-Za-z_][A-Za-z0-9_.:-]*` (Vue's `:x`, `@x` included); every attribute of an SVG or MathML element; `srcdoc`; URL lists `ping`, `srcset`, `imagesrcset`; attributes the tree builder reads: `is`, `xmlns*`, `encoding`, `type` on `<input>` and `<script>`, `color`/`face`/`size` on `<font>`, `<template>` `shadowroot*` and `for`, `<meta>` `charset` and `http-equiv`, and `content` under a static `http-equiv="Content-Type"` ; and every name off the closed allow-list: any name but `data-*`, `aria-*` and the names below, `content` other than a refresh included | nothing: build error | `ContextsTest`, `NeutralValuesTest`, `AttributesEndToEndTest` |
| `EVENT_HANDLER` | `on*` | nothing: build error | `ContextsTest` |
| `STYLE` | `style` | literals, numbers and `CssValue` (`Escapers.attr`) | `StyleAttributeTest` |
| `REFRESH` | `content` of `<meta http-equiv="refresh">` | a literal delay and a same-origin link (`Escapers.refresh`) | seed `B3`, `SameOriginLinkRenderTest` |
| `RESOURCE_URL` | `script[src\|href]`, `iframe/frame/embed[src]`, `object[data]`, `base/link[href]` | a same-origin literal-path `@{...}` link with no query parameters: no scheme or `//` origin, data only in percent-encoded path variables, and a `/` path re-checked when rendered (`Escapers.sameOriginLink`); a `<base href>` must also start with a single `/` | `LinkCompilerTest`, `AttributesEndToEndTest`, `CsrfBaseTest` |
| `URL` | `href src action formaction cite poster` | any value; only `http`, `https`, `mailto`, `tel` and relative URLs pass, anything else becomes `about:invalid#thwart` (`Escapers.url`) | `AttributesEndToEndTest` |
| `TYPED_ATTRIBUTE` | `target`; `value` other than on `button data input li meter option progress select`; `for` other than on `label` and `output` | string literals, numbers, a choice between literals (`Escapers.attr`) | `InertAttributesTest`, `ContextsTest` |
| `ATTRIBUTE` | the listed inert names (`Contexts.COMPUTED`: text `abbr alt download id label name pattern placeholder title value`, keywords, numbers, booleans, ID references, `accept class hreflang lang`, `role` and the WAI-ARIA 1.2 names) | any value (`Escapers.attr`) | `ContextsTest` |
| `NEUTRAL` | `data-*`, and an `aria-*` name outside WAI-ARIA 1.2 | a framework-neutral value by static type and shape: an exact-class number in plain decimal (`Escapers.neutralNumber`), a boolean, a link `@{/path}` with a literal path in `A-Z a-z 0-9 . _ ~ / -` and `%XX` and no query (`Escapers.neutralPath`), or a choice between those and literals | `NeutralValuesTest`, `HostileSweepTest` |

### 6.2 Which computed names are accepted

Static attributes are copied verbatim, except a static `is`. A static `on*` handler, or a static
`javascript:` URL in a URL attribute, is a build error: a strict nonce CSP blocks both.

| Name | Accepted | Checked by |
|---|---|---|
| `data-*`, `aria-*`, or a name on the closed allow-list (`Contexts.COMPUTED`, guide §4.4) on an HTML element | classified as in 6.1 | `ContextsTest` |
| Any other name | error, with a fix-it naming a static value, `t:if`, or a `data-*` attribute | `ContextsTest`, `NeutralValuesTest`, `InertAttributesTest` |
| Static `is` | a valid custom element name only (ASCII, with `-`, not a reserved SVG/MathML name) | `NeutralValuesTest` |
| Computed `action` on a `<form>` | a same-origin link, or a choice whose every branch is one | `FormActionTest`, `CsrfActionOriginTest` |
| Computed `formaction` | a same-origin link, or a choice whose every branch is one | `CsrfFormactionTest` |
| Computed `href` on `<base>` | a same-origin link, or a choice whose every branch is one; a static one must be same-origin by shape | `CsrfBaseTest` |

### 6.3 Client code

There is no framework table: client code that evaluates attribute values or DOM text is outside
the guarantee (principle 7), and the compiler constrains its own output only in `data-*` names
(`NEUTRAL`); every other name outside HTML is off the allow-list. Client frameworks that compile the server's DOM as a template are
unsupported; a strict CSP is the backstop (6.4, guide §4.9).

| Rule | Enforced by | Checked by |
|---|---|---|
| Element text, RCDATA and attribute values use one escaper (`& < > " '`); `{{` in a value renders as `{{` | runtime | `EscapersTest`, `EscaperFuzzTest`, `ClientTemplateInjectionTest` |
| `{` is written `\u007b` in JSON strings | runtime | `ScriptJsonTest` |
| No value, condition or loop in `noscript`, `iframe` (raw text decodes no reference) | generator; verifier: `VALUE_IN_RAW_TEXT` | `ContextVerifierRulesTest`, `BrowserRawTextTest` |
| What client code makes of static markup, text and standard attribute values | outside the guarantee | seed `A37` |

### 6.4 The CSP nonce

Defence in depth beyond the guarantee: with a nonce-based `script-src`, which the application
sends (guide §4.9), only the scripts the compiler wrote run. The nonce comes from
`RenderContext.cspNonce()`; the Spring starter sets a fresh one per request.

| Rule | Checked by |
|---|---|
| Every `<script>` and `<style>` start tag the compiler writes gets ` nonce="..."` after its other attributes (`Escapers.attr`); nothing when the context has no nonce | `CspNonceTest` |
| Not inside `<svg>`/`<math>` or an element a browser reads as text; a mistake here only removes a nonce, never adds one | `CspNonceTest` |
| A `nonce` on `<script>` or `<style>` in the template (static or `t:nonce`) is an error | `CspNonceTest` |
| Markup the compiler did not write gets no nonce: the sanitizer drops `script` and `style` with their content | `CspNonceTest`, `HtmlSanitizerTest` |

## 7. Profile decisions

| # | Decision | Reason |
|---|---|---|
| 1 | Fail closed: reject what can't be proven simply, and shrink the profile rather than model more | security-architecture.md §4 |
| 2 | `<noscript>` and `<iframe>` hold only static content; `xmp`, `noembed`, `noframes`, `frameset`, `plaintext` are errors | Raw text decodes no reference, so no escaper fits |
| 3 | `<svg>` and `<math>` are static; CDATA sections are errors | Foreign content, its integration points and CDATA are where browsers and sanitizers split; Thwart doesn't model them |
| 4 | Attributes the tree builder reads can't be computed | They decide how the rest of the page parses |
| 5 | `template` holds flow content only | `<template><col>` ignores raw-text start tags |
| 6 | `center`, `font`, `tt`, `big`, `strike`, `marquee` are accepted HTML | Migration parity |
| 7 | Supported browsers: Chromium, Firefox, Firefox ESR and Safari/WebKit (current); WebKit is checked through Playwright's build in the Node job | Rules must hold in every engine users run |
| 8 | The invariant is per value: each value lands where the compiler chose, and benign and hostile trees are equal | Implied end tags make a full-tree claim false |
| 9 | No `{{` breaking and no `v-pre` (`ClientTemplateInjectionTest`) | Client frameworks that compile server DOM are unsupported; strict CSP is the backstop |

## 8. Verifier decisions

| # | Decision | Reason |
|---|---|---|
| 10 | The build-time verifier is the boundary for markup contexts, in every build | The hand-written model kept failing; the spec algorithm decides instead |
| 11 | One engine, the pinned validator.nu htmlparser, shaded into `thwart-compiler` and built for Java 8; the runtime and starters stay dependency-free | Upstream ships no Java 8 jar; the processor runs in a JDK 8 javac |
| 12 | Error-free parses only: on every path, scripting on and off, no parse error except missing doctype and "Duplicate ID"; partials in their implied fragment context | Error recovery is where engines and browsers split |
| 13 | Four coarse exclusions: SVG/MathML above a table part or `template`; table parts directly in `template`; only `option`/`optgroup`/`hr` above `select`; any CDATA section | The known splits, covered by a shape rather than a model |
| 14 | A divergence found later becomes a coarse exclusion plus a test, never a modelled algorithm or an engine patch | Ends the modelling treadmill |
| 15 | Control flow is state-neutral: each body leaves the parser as it found it | One path is then exact; no joins, fixed points or state caps |
| 16 | Markup structure that depends on data (recursive rich-text fragments) is a build error, with a `SafeHtml` fix-it | It breaks the per-value invariant |

## 9. The verifier's rule table

`verify/Rules` and `verify/ContextVerifier`. Each path through a template holds one parser per
scripting mode (off only if the template mentions `<noscript>`); a template is accepted only if
every parser accepts it. The id in brackets ends every error message (`[verifier:ID]`); guide
section 5 gives the fix for each.

The parser state names the one escaper a value may have: `text` in data state (in body, cell,
caption or template mode) and RCDATA; `json`/`javaScript` in the script data of an HTML
`<script>`; an attribute escaper in the double-quoted value of the attribute the compiler wrote.
A value with another escaper is rejected, so a value whose paths reach states that name different
escapers is rejected.

| Id | Rejects | Accepted instead |
|---|---|---|
| `TEXT_NOT_IN_TEXT` | a `text` value inside a tag, comment, character reference, doctype or `plaintext` | data state; RCDATA |
| `TEXT_IN_SCRIPT` | a `text` value in script data or `style` | as above |
| `TEXT_IN_TABLE` | a `text` value in a table, table body, row or column group | in a cell or caption |
| `TEXT_OUTSIDE_BODY` | a `text` value before `<html>`, in or after the head, after the body | in body, cell, caption, `template` |
| `ATTRIBUTE_NOT_IN_VALUE` | an attribute value outside a double-quoted value, or in another attribute's value (or a dropped duplicate) | the double-quoted value of the attribute the compiler wrote |
| `JSON_NOT_IN_SCRIPT` | a script value outside the script data of an HTML `<script>` | script data |
| `RAW_NOT_FLOW` | `SafeHtml`, an HTML message or an unbound `Fragment` outside data state, HTML namespace, or body/cell/caption/template mode | flow content (section 5) |
| `RAW_CLOSES_ELEMENT` | the same where its markup would close, reopen or adopt an open element | flow content (section 5) |
| `PARSE_ERROR` | any tokenizer or tree-builder error on any path, except missing doctype and validator.nu's "Duplicate ID" (security-architecture.md §11.3); a partial only in its implied fragment context; and any `frameset` element, which applies only if no body content came before it | markup that parses without errors; an ordinary layout |
| `FOREIGN_IN_TABLE` | exclusion (a): an `svg` or `math` element open with a table part or `template` below it (the fragment context counts) | SVG and MathML outside tables |
| `TABLE_IN_TEMPLATE` | exclusion (b): an HTML table part directly in `template`, or a table mode in the template-mode stack | flow content in `template` |
| `SELECT_CONTENT` | exclusion (c): any element other than `option`, `optgroup` or `hr` open above `select`, in composed output | option, optgroup, hr and text |
| `CDATA_SECTION` | exclusion (d): any CDATA section | text with `&lt;` and `&amp;` |
| `VALUE_IN_RAW_TEXT` | any value in the raw text of `noscript` (scripting on), `iframe`, `xmp`, `noembed` or `noframes` | static content; the value outside the element |
| `VALUE_IN_FOREIGN` | any value while the current node is SVG or MathML | static SVG/MathML; the change outside it |
| `UNBALANCED` | a `t:if`, `t:unless`, `t:case`, `t:each` (one pass, or its separator), computed attribute or fragment insertion whose body leaves the parser in another state than it found it in, or a partial that does as a whole. Allowed: in template mode, the switch a flow start tag makes; a fragment inserted at the start of a document that ends it (a layout on `<html>`) | bodies that close what they open and leave open what they find open |
| `STRUCTURE_DEPENDS_ON_DATA` | a fragment that calls itself in ever new parser states, or reaches a bad state only through recursion | recursion into the state its call started in |
| `UNCALLED_FRAGMENT_FITS_NOWHERE` | a fragment no template calls, declared where no fragment fits (raw text, `t:remove="tag"` content); verifier-only, since a fragment declared outside HTML content is a build error first | a fragment declared in HTML content |

A parser that throws, or a skeleton the verifier can't interpret (a fragment it doesn't define, markup
right after a computed attribute that isn't whitespace, `/` or `>`), has no id: it is an internal
compiler error, a compiler bug, and the build fails.

A partial (a view whose output doesn't start with a doctype or `<html>`) is checked by fragment
parsing in the context its first element implies (`tr`: `tbody`; `td`, `th`: `tr`; `option`,
`optgroup`: `select`; `li`: `ul`; otherwise `body`), where it must also end in the state it
starts in.
