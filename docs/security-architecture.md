# Security architecture

This document describes how Tessera makes template injection and XSS impossible by construction,
as the code does it today. The threat model is [security-model.md](security-model.md), the markup
rules as tables are [context-model.md](context-model.md), and the porting recipe for every
rejection is [guide.md](guide.md). How the design got here is in the appendix and in git history.

The north star (#673): Tessera compiles the templates our target Spring apps actually use into
fast, plain Java, and makes template injection and XSS impossible by construction. When a feature
can't be proven safe simply, reject it and let migration absorb the cost; never model browsers,
client frameworks or one app's idiom.

Code comments cite sections 5.3, 5.4 and 11-11.5 here, and context-model.md 5, 6.1, 6.2 and 9; keep
those numbers stable (add new sections after them, as §5.7 was).

## 1. Summary

- **No template injection.** Templates are compiled at build time into Java classes. The runtime
  has no template parser and no API that takes template source. A template sees only its typed
  `t:params`; properties and functions are allow-listed and resolved by javac; nothing is
  reflective at runtime (AGENTS.md principles 1-4).
- **No XSS in markup.** Every template is run through a pinned spec HTML parser inside the
  annotation processor (§11). A template is accepted only if it parses without errors, outside
  four coarse exclusions, and every value lands in a parser state where its escaper's output
  can't change the parse. Anything else is a build error with file, line, column and a fix-it.
- **Everything the parser can't decide is an allow-list or a closed grammar** (§5-§9):
  attribute classes, single-URL attributes, framework-neutral values, a closed script prelude,
  one CSRF form shape, ASCII-only name handling, and a runtime that never classifies content.
- **Client frameworks are outside the guarantee.** Tessera guarantees what the browser's HTML
  parser and built-in sinks do with the output. Client frameworks that compile the server's DOM as
  a template (Vue in-DOM templates, petite-vue, AngularJS) are unsupported; a strict CSP is the
  backstop (§5.6).

## 2. Threat families

Every XSS report before the current design was in one of these families, all in **context
selection**, none in an escaper:

| Family | What went wrong | Closed by |
|---|---|---|
| A. Parser differentials | The compiler's model of HTML disagreed with the browser's | the verifier (§11) |
| B. Composition | A fragment or `SafeHtml` inserted somewhere it parses differently | content kinds checked by the verifier (§5.5) |
| C. JavaScript position | A value in a string, comment or regex of a script | the closed script prelude (§5.4) |
| D. Client frameworks | Page JavaScript re-reading the DOM as code | out of scope; neutral values and CSP bound it (§5.6) |
| E. URL and policy | Single-value checks on lists; origin checks on some paths only | single-URL grammar, same-origin links, one CSRF shape (§5.3, §9) |

The root cause was that the expression language was allow-listed and the markup language
deny-listed: unknown names got a permissive default, and contexts came from a hand-written guess
at how browsers parse. The design rule since: **accept a closed language, classify it exactly,
and reject the rest.**

## 3. Pipeline

1. **Parse.** `MarkupTokenizer` and `HtmlParser` build Tessera's own tree and give early,
   readable errors (an unsupported element, a CDATA section, a literal `<`). They are not the
   security boundary.
2. **Generate.** `TemplateGenerator` picks each value's context from the element and attribute
   (`Contexts`, context-model.md §6) and emits Java through one output path (`Emitted`).
3. **Record the skeleton.** The same path records the skeleton: static bytes exactly as written,
   holes with their escaper, control flow and fragment insertions.
4. **Verify.** `ContextVerifier` runs the skeleton through the pinned parser (§11). The
   verifier only accepts or rejects; output bytes don't change.
5. **Render.** Generated code calls exactly one `Escapers` method per value. No parser runs at
   render time.

## 4. Principles this rests on

The principles are in [AGENTS.md](../AGENTS.md). The one rule this design adds is **reject,
don't model**: if browsers split, add a coarse exclusion; never model a browser's algorithm or a
framework, and never patch the parser.

## 5. The profile

### 5.1 The invariant

For every accepted template T, every model M and every supported browser parsing `render(T, M)`:
each value lands in the node, context and namespace the compiler chose, and the static structure
is the same for every value. context-model.md §1 states it with its checks.

### 5.2 Markup

The verifier accepts error-free parses only (§11.4). On top of that the compiler rejects, with
its own message, what it doesn't model:

- **Foreign content.** No value inside `<svg>` or `<math>`: no computed attribute, `[[...]]`,
  `t:*` or fragment (`VALUE_IN_FOREIGN`). Control flow on the `<svg>` element itself is fine.
- **CDATA sections**, anywhere (`CDATA_SECTION`, exclusion (d)).
- **Raw-text elements** other than `script`, `style`, `title`, `textarea`: `xmp`, `noembed`,
  `noframes`, `frameset`, `plaintext` are errors; `noscript` and `iframe` hold only static text
  (`VALUE_IN_RAW_TEXT`).
- **Attributes the tree builder reads** can't be computed: `type` on `<input>` and `<script>`,
  `<font color/face/size>`, `is`, `xmlns*`, `encoding`, `<meta charset/http-equiv>`, `<template>`
  `shadowroot*` and `for`.

### 5.3 Attributes

`Contexts.attribute` classifies each computed attribute (context-model.md §6.1). Computed names
are one closed allow-list (#761): `data-*`, `aria-*` and a fixed set of standard names, each with
one class. Every other computed name is a build error with a fix-it.

- **Inert text** (the listed text, keyword, number, boolean, ID, token and ARIA names): any value,
  escaped.
- **Typed**: `target`, a `value` outside form controls and `<data> <li> <meter> <progress>`, and
  a `for` outside `<label>` and `<output>` take string literals, numbers and a choice between
  literals only.
- **Single URL**: `href src action formaction cite poster` take a URL; the scheme is checked at
  render time.
- **Resource URL**: `script[src|href]`, `iframe/frame/embed[src]`, `object[data]` and
  `base/link[href]` take only a same-origin literal-path `@{...}` link with no query parameters: no
  scheme or `//` origin, data only in percent-encoded path variables, and a `/` path re-checked
  as same-origin when rendered. A `<base href>` must also be same-origin (§9).
- **Neutral**: `data-*`, and an `aria-*` name outside WAI-ARIA 1.2, take only a framework-neutral
  value: an exact-class number, a boolean, a same-origin `@{/path}` without a query, or a choice of
  those and literals.
- **Forbidden** (`Contexts.forbidden`, which also gives the fix-it): names that aren't plain
  (`:x`, `@x`), `srcdoc`, URL lists (`srcset`, `imagesrcset`, `ping`), the tree-builder inputs of
  §5.2, every attribute of an SVG or MathML element, and every name off the list (`hx-*`, `x-*`,
  `v-*`, custom element attributes, `rel`, `type`, `nonce`, named `<meta>` content, ...). `on*` is
  a build error too.

`OutputContext.attributeEscaper()` gives each class its escaper, a `Skeleton.Escaper` naming one
`Escapers` method (§6).

### 5.4 Scripts: the script prelude rule

In a JavaScript `<script>`, a value is allowed only as the whole value of
`(const|let|var) NAME = [[...]];` at the top of the script, with only whitespace and `//`
comments around (`TextInlining.checkPrelude`). `NAME` is an ASCII identifier, not a reserved
word and not `location`. The value is written by `Escapers.javaScript` as JSON in parentheses,
so it is one primary expression whatever its type. A JSON script (`application/json`,
`application/ld+json`) takes plain JSON outside strings. Tessera never lexes JavaScript: every
other position is a build error with a fix-it. `BigDecimal`/`BigInteger` subclasses throw, so an
overridden `toString()` can't inject.

### 5.5 Composition: content kinds

A value known only at render time is invisible to the error check, so it gets a content kind, a
predicate on the parser state where it is inserted plus a promise about what its producer emits
(`verify/ContentKinds`, context-model.md §5):

- `SafeHtml` and unbound `Fragment` values are `FLOW`: data state, HTML current node, a body-like
  mode (`RAW_NOT_FLOW`), and no open element that flow content would close, reopen or adopt
  (`RAW_CLOSES_ELEMENT`). Producers (`html.sanitize`, `HtmlPolicy`, `SafeHtml.escape`/`concat`)
  emit balanced content that parses without errors.
- HTML messages (`t:utext="#{...}"`) and `SafeInlineHtml` values are `PHRASING`: at render time
  the formatted message is sanitized by a fixed inline policy (`Escapers.htmlMessage`, which returns a `SafeInlineHtml`, or `linkedHtmlMessage` for a key with links,
  `HtmlMessageTest`), and a `SafeInlineHtml` is escaped text or sanitized in inline mode, with a
  policy limited to `HtmlPolicy.INLINE_ELEMENTS` (#397, `FlowProducerTest`).
- `Fragment` is compile-time only (`FragmentFromJavaTest`): Java can't build one, and no page or function takes
  one, so every `Fragment` value comes from a `~{...}` the verifier checks where it lands.
  Statically known fragments need no kind: the verifier analyses them at each call site.

### 5.6 Client frameworks and CSP

Tessera models no client framework, and no rule names one. Computed text and attribute values
share one HTML escaper (`& < > " '`), so `{{` in a value renders as `{{`. Client frameworks that
compile the server's DOM as a template are unsupported, and what client code does with static
markup, text and standard attribute values is outside the guarantee. In `data-*` attribute
names the compiler constrains its own output to framework-neutral values, and computes no other name outside HTML (§5.3).

The backstop is a strict CSP: the Spring starter puts the request's nonce on every `<script>`
and `<style>` the compiler writes (context-model.md §6.4), and sends a strict nonce-based
policy for a Tessera page that commits without one, so a policy from Spring Security or the
application wins (guide §4.9).

### 5.7 Expressions

The expression language is closed and resolved by the compiler against static types; javac
compiles the result. Nothing is evaluated reflectively at render time.

- **Rejected by the parser** (`expr/ExpressionParser`, `ExpressionParserTest`, `SstiPayloadTest`):
  `T(...)`, `new`, `__...__` preprocessing, `@bean` references, assignment, lambdas,
  projection and selection (`.![]`, `.?[]`), dynamic message keys, link paths and template names
  (`~{...}` takes a literal), and a bare `fn(x)` without a namespace.
- **View-safe types** (`types/ViewSafety`, the source of truth; `ViewSafetyTest`): properties
  resolve only on primitives and boxes, strings, enums, records, `java.time` values,
  `BigDecimal`/`BigInteger`, the `java.util` containers and arrays of view-safe types,
  `LoopStatus`, `SafeHtml`, `CssValue` and `@TemplateView` classes, all public. Never view-safe,
  even if annotated or registered: `Object`, `Class`, `ClassLoader`, reflection and
  `java.lang.invoke`, `Thread`, `Runtime`, `Process*`, `System`, files, `javax.script`, servlet
  and Spring context, bean, environment, resource and expression types, and any type with such a
  supertype or type argument. `Object` is allowed only as a function parameter type, where nothing
  can be called on it.
- **Internal names.** A variable whose name contains `$` is never looked up
  (`ExpressionCompiler`, `FormPaths.fieldVariable`), so the compiler's own variables can't be
  reached from a template.
- **Path-only fields.** `t:errors`, `fields.*` and `t:field` on a file input resolve a form path
  for its type only (`ExpressionCompiler.pathType`): a step may have a never-view-safe type such as
  `MultipartFile`, but properties still resolve only on view-safe types and no code is emitted,
  so the value can't be read (`NameOnlyFieldsTest`).
- **Functions** (`types/FunctionRegistry`, `FunctionRegistryTest`): `public static` methods
  annotated `@TemplateFunction` in a class annotated `@TemplateFunctions("ns")`, called as
  `ns.fn(...)`. A signature with a never-view-safe type or a `Fragment` parameter, a duplicate or
  invalid namespace, or a reserved one (`fields`, `ids`) is a build error. Namespaces come from the current
  compilation, `BuiltinNamespaces` (Tessera's runtime functions), and the classpath index
  `META-INF/tessera/functions/<ns>`, which any jar can provide; an indexed class must still
  carry `@TemplateFunctions` with that namespace and passes the same checks.

## 6. Escapers

`dev.tessera.runtime.internal.Escapers` has one method per context: `text` (element text and RCDATA),
`attr`, `url`, `resourceUrl`, `refresh`, `json`, `javaScript`, plus the neutral-value checks.
The verifier's rule table (§11.2) names which escaper each parser state accepts. `EscapersTest`
and `EscaperFuzzTest` test them; `TextChokePointTest` checks that value text reaches element text
only through `Escapers`.

## 7. The runtime never classifies content

The runtime never decides a value is safe by inspecting it. `SafeHtml` comes only from factories
that check (`escape`, `concat`, `sanitize`); there is no unchecked constructor. An HTML message is
sanitized by a fixed policy whatever the bundle holds. A new runtime check of the form "if it looks
harmless, write it raw" is a design error, not a fix.

## 8. ASCII-only names and character classes

Browsers fold A-Z only and split on HTML whitespace. Every HTML, CSS and URL comparison goes
through named ASCII helpers (`dev.tessera.runtime.internal.Ascii` at render time, `HtmlParser.asciiLower`
and its siblings in the compiler); Java text goes through `JavaStrings`. `AsciiOnlyTest` fails on
`equalsIgnoreCase`, `toLowerCase`, `trim`, `Character.isWhitespace`, regex `\s` and the like
outside a pinned allow-list.

## 9. CSRF: one closed form shape

A `<form>` gets the token only if its method is a static `post` and its action is absent, static
and same-origin by shape, or a computed same-origin `@{/...}` link re-checked by
`Escapers.sameOriginLink` at render time. A
computed `method`, any other computed action, and a cross-origin `formaction` or `<base href>` are
build errors. Every computed form `action` is a same-origin link, since client code may submit
any form.

## 10. Tests that hold the line

| Test | What it holds |
|---|---|
| `SeedVerifierTest` | the seeds (`oracle/seeds`): each historic bypass or probe keeps its verdict |
| `ErrorFreeTest`, `ContextVerifierRulesTest` | the error-free rule, the exclusions and the hole-state table |
| `HostileSweepTest` | every computed (element, attribute) pair and the sanitizer, rendered with hostile values and classified against a sink table written from the specs (`HostileSinks`); the script prelude shapes, checked with Rhino |
| `SstiPayloadTest` | template-injection payloads stay inert |
| `EscapersTest`, `EscaperFuzzTest`, `TextChokePointTest`, `AsciiOnlyTest` | the escapers, the single text path and ASCII-only folding |
| `FlowProducerTest`, `SanitizerBalanceTest` | `FLOW` producers emit balanced, error-free content |
| `.chainguard/ci/oracle-node.yaml` | after every merge: parse5, Chromium, Firefox ESR and WebKit compared with the pinned engine's trees |

`hack/quick.sh tier1` runs the security core.

## 11. The build-time context verifier

### 11.1 Decision

A spec HTML parser decides contexts at build time, for every template:

1. **Record the skeleton** from the exact bytes the generated code writes.
2. **Run the parser** over it, feeding static bytes as they are, with scripting on and, if the
   template mentions `<noscript>`, off. At each hole read the parser's real state.
3. **Accept only error-free parses**, outside the exclusions (§11.4).
4. **Accept only inert holes**: a hole is accepted only if its escaper's output can't change the
   parse in that state (§11.2).
5. **Otherwise, a build error** ending in `[verifier:RULE]` (`verify/Problem`; guide §5 lists
   each rule and its fix). Fail closed.

The hand-written model (`HtmlParser`, `MarkupTokenizer`, `Contexts`) stays only to build
Tessera's tree and give early errors. When it disagrees with a browser, the result is a build
error, never XSS.

### 11.2 Soundness argument

**Escaper output is inert in the observed state** (`verify/Rules`):

| Escaper | Output alphabet | Accepted states |
|---|---|---|
| `text` | no `< > " ' &`, NUL or lone surrogates, except complete references | data state in body, cell, caption or template mode; RCDATA |
| `attr`, `url`, `resourceUrl`, `refresh` | no `"`; `&` only in complete references | a double-quoted attribute value whose name is the hole's attribute |
| `json`, `javaScript` | no `< > & '`, backtick, `$`, U+2028/U+2029, controls, noncharacters or lone surrogates | script data, with an HTML `<script>` as the current node |

Every other state is rejected: tag open, character reference, comment, script data for `text`,
table modes, outside the body, raw text (`VALUE_IN_RAW_TEXT`), and any state whose current node is
SVG or MathML (`VALUE_IN_FOREIGN`).

**The tree builder treats the value uniformly.** The static markup parses without errors, so
nothing is foster-parented, reconstructed or adopted. In data state the value produces only
character tokens, and the accepted modes append every character token. So after the value the
parser is in the same state as before, for every value.

**Control flow is state-neutral** (`ContextVerifierRulesTest`). The body of every `t:if`, `t:unless`, `t:case`,
`t:each` (one pass, and the separator), every computed attribute and every fragment insertion
must leave the parser in the state it found it in (`UNBALANCED` otherwise), and a partial must end
in the state it starts in. Then every path is in the same state at each point, so one path is
exact: no unions, fixed points or state caps. Two allowances: in template mode, the switch the
first flow start tag makes; and a fragment inserted at the start of a document that ends it (a
layout on `<html>`). A recursive fragment call in the state its call in progress started in is
neutral by induction; recursion into ever new states is `STRUCTURE_DEPENDS_ON_DATA`.

### 11.3 Limits of the argument

1. **Skeleton fidelity.** The skeleton must be the bytes the generated code writes. It is recorded
   at the single emission path, and `ProfileOracleTest` checks renders against it.
2. **Dynamic values.** `SafeHtml`, HTML messages and unbound `Fragment` values are covered by
   their content kind (§5.5), which relies on the producers.
3. **One parser.** The guarantee is relative to the WHATWG algorithm as the pinned engine
   implements it, on error-free parses, outside the exclusions. Browsers are tested to agree there
   (the Node job), not proved to.
4. **The allowed errors** are two (`Rules.allowedError`): the missing-doctype messages, which
   say nothing about the tree (most views are partials or start at `<html>`), and validator.nu's
   "Duplicate ID", which isn't a parse error in the HTML standard but a conformance check of its
   tree builder, made only among the elements of one chunk of input. Widening the list needs a
   corpus measurement and an argument in the PR.
5. **Partials** are parsed in the fragment context their root implies. A partial rendered in
   another context isn't covered.
6. **The inertness lemma** is argued here and tested by the hostile sweep, not proved.

### 11.4 The boundary: one parser, error-free parses, four exclusions

**One parser:** the upstream htmlparser from the pinned validator.nu sources release, built for
Java 8, relocated to `dev.tessera.compiler.internal.html5.current` and shaded into
`tessera-compiler`. One Tessera-owned accessor reads its state; its source isn't patched.
Bumping it is its own PR, with seed and corpus verdicts diffed. The runtime and starters stay
dependency-free.

**Error-free parses only.** On every path, scripting on and off, the parser reports no tokenizer
or tree-builder error except the two allowed ones of §11.3 item 4, missing doctype and
"Duplicate ID" (`PARSE_ERROR`). A partial counts errors
only in its implied fragment context (`tr`: `tbody`; `td`, `th`: `tr`; `option`, `optgroup`:
`select`; `li`: `ul`; otherwise `body`). A fragment no template calls is checked on its own, or
rejected if it is declared where no fragment fits (`UNCALLED_FRAGMENT_FITS_NOWHERE`). In a build that rule never fires: a
fragment declared outside HTML content is a generator error first (#776).

**Coarse exclusions**, where browsers are known to split:

| | Rejected | Why |
|---|---|---|
| (a) `FOREIGN_IN_TABLE` | an `svg` or `math` element open with a table part or `template` below it | vnu and Firefox reset the insertion mode to "in body" at the foreign element; the spec, Chromium and WebKit go on to the cell |
| (b) `TABLE_IN_TEMPLATE` | an HTML table part directly in `<template>` | vnu and Firefox close an outer table on `</table>` there |
| (c) `SELECT_CONTENT` | anything but `option`, `optgroup` and `hr` above `<select>` | classic and customizable `<select>` parsing differ |
| (d) `CDATA_SECTION` | any CDATA section | browsers read CDATA only in foreign content, and split on it at integration points |

`<frameset>` is rejected too, as a `PARSE_ERROR`: whether its start tag applies depends on what came
before. The exclusions are coarse on purpose: a wider rule that is obviously right beats a narrow
one that models a browser.

**The divergence policy.** When the Node job, a reviewer or a report finds the engine and a
supported browser building different trees for accepted markup: add it as a seed the verifier
must reject, add a coarse exclusion with a negative and a positive test, and never model either
algorithm or patch the engine. Known deviations are in security-model.md §7.1.

### 11.5 What the verifier doesn't cover

Script lexical position (§5.4), URL and CSRF policy (§5.3, §9), the value shapes of non-standard
names (§5.3), CSS inside `style` (literals, numbers and `CssValue` only), and client frameworks
(§5.6).

## 12. Policy going forward

- A parser-differential or composition report becomes a seed and, if browsers split, a coarse
  exclusion. Never a special case in `HtmlParser`, `MarkupTokenizer` or `Contexts`.
- A new XSS outside the verifier converts that area to an allow-list or a closed grammar. It
  doesn't add a case. The PR names the class it closes, with a negative test and a sweep row.
- Removing support beats modelling it. The cost is a build error with a fix-it and a guide
  recipe; `tessera-migrate` rewrites what it can.

## Appendix: history

The design was accepted on Oct 24, 2026 after about 30 XSS reports in families A-E, each fixed by
a new case in a hand-written model. The build-time verifier replaced the profile as the boundary
on Oct 25 (#364, #367), simplified the same day to one engine, error-free parses and coarse
exclusions (#404-#408). Oct 30 closed the remaining areas by class (#544): inert-text and
single-URL attributes, the closed script prelude, AngularJS and htmx 1.x sockets unsupported,
ASCII-only names, one CSRF shape and the hostile sweep. Framework-neutral values replaced the
framework table (#583-#587). The minimal-core rewrite (#673) then removed what couldn't be proven
simply: `{{` breaking and `v-pre` (reversing #248), foreign-content values, CDATA and the raw-text
elements, Java-built `Fragment`s, unsanitized HTML messages; and made control flow state-neutral.
The full earlier text of this document is in git history
(`git log --follow docs/security-architecture.md`).
