# Notes on the Lean model of T1

Details behind [README.md](README.md): what each slice theorem states, the modeling choices in
`Whatwg.lean`, and what stating the profile in Lean found. Section numbers (§5.x) are those of
[docs/security-architecture.md](../../docs/security-architecture.md).

## What is proved (slice 1)

Slice 1 is element text in the HTML namespace with TEXT holes and no tags: a template is a list
of static text spans and `[[...]]`/`t:text` holes (`Subset.lean`, `Piece`).

`TesseraT1.t1_slice1` (`T1.lean`): for every template `T` with `InSubset T`, and every list of
values `vs` each satisfying `htmlTextSafe`, and every tree-builder stub `tr`,

```
tokenize tr (render T vs) = denote T vs
```

`tokenize` runs the tokenizer model from the data state to the end of input. `denote` is
Tessera's reading: each static character is a Character token, and each value is Character
tokens except that each `<!---->` in it is an empty Comment. So every hole's characters land in
character data, no value opens a tag or a non-empty comment, and every static span produces
exactly its own characters (before character-reference decoding: a browser reads a static
`&amp;` as `&`). `t1_slice1_inModel` proves the run never reaches the `out` sink, and
`t1_slice1_tokens` restates the equality as: every token of the page is a Character token or
an empty Comment.

`#print axioms` for each of the three theorems lists only `propext`, `Classical.choice` and `Quot.sound`.
There is no `sorry`.

## What is proved (slice 2)

Slice 2 adds start tags, end tags, comments and `<!DOCTYPE html>` (`Subset.lean`, `Piece2`). A start
tag is copied from the source (`SrcTag`, the author's whitespace) or rebuilt by the generator
(one space before each item; static attributes as written; each attribute hole as ` name="v"`,
omitted when the value is null). Its lowercased name is not one of `slice3Names` (`script`, the
RCDATA and RAWTEXT elements, `plaintext`, `svg`, `math`, `select`, `frameset`, `template`), so in
every insertion mode the tree builder leaves the tokenizer in the data state.

`TesseraT1.t1_slice2` (`T1Slice2.lean`): for every `T` with `InSubset2 T` and every value list
`vs` with `ValsOK2 T vs` (each TEXT value `htmlTextSafe`, each attribute-hole value `htmlSafe`),

```
tokenize treeStub (render2 T vs) = denote2 T vs
```

`denote2` reads static text and TEXT values as in slice 1, each start tag as one StartTag token
with the lowercased name and the attributes as written (static values and hole values, names
lowercased; the duplicate-attribute rule makes the browser's first-occurrence rule a no-op, `token_eq_tessera`), each end tag as an EndTag with the lowercased name,
each comment as one Comment with its body, and the DOCTYPE as `html`. So an attribute hole's value
is exactly one attribute value of the tag Tessera assigned it to. `t1_slice2_inModel` proves the
run never reaches `out`. Building blocks: `run_startTag` and `run_endTag` (`Tag.lean`),
`run_comment` (`Comment.lean`), `run_doctype` (`T1Slice2.lean`); the spec side proved the bridges
`StartTag.toSyn_*` (`TagBridge.lean`) and `commentBodyOK_of_tessera` (`CommentBridge.lean`).

`#print axioms TesseraT1.t1_slice2` lists only `propext`, `Classical.choice` and `Quot.sound`.
There is no `sorry` in the project.

## What is proved (slice 3)

Slice 3 adds JavaScript `<script>` elements (`ScriptEl`: a start tag whose name ASCII-lowercases to
`script`, a body of static script text and JSON holes, and the rebuilt end tag `</` + name + `>`).

`TesseraT1.t1_slice3` (`T1Slice3.lean`): for every `T` with `InSubset3 T` (slice-2 membership plus
`ScriptOK` for each script element) and every value list with `ValsOK3 T vs` (slice-2 values, and
every script hole a present value with `jsonScriptSafe` and `JsonFirst`),

```
tokenize treeStub (render3 T vs) = denote3 T vs
```

`denote3` reads a script element as its StartTag, the body's characters (static script text and
the JSON values as written), and one EndTag `script`. So no JSON value and no static script text
ever ends the script early, opens `<!--` (the escaped states), or starts a tag. `t1_slice3_inModel`
proves the run never reaches `out`. The script-data lemmas are in `Script.lean`: `good_run`
(static text), `good_value` (a JSON value after static text not ending in `<!-`), `good_close`
(Tessera's end tag).

## What is proved (slice 4)

Slice 4 adds the raw-text elements of §5.3 (`RawEl`): `title` and `textarea` (RCDATA, with
`Escapers.rcdata` holes), `style` (RAWTEXT, no holes) and `iframe` (RAWTEXT, empty).

`TesseraT1.t1_slice4` (`T1Slice4.lean`): for every `T` with `InSubset4 T` (slice-3 membership plus
`RawOK` per raw element) and every value list with `ValsOK4 T vs` (slice-3 values, each RCDATA
value `htmlSafe`),

```
tokenize treeStub (render4 T vs) = denote4 T vs
```

`denote4` reads a raw-text element as its StartTag, its content (static text and RCDATA values) as
Character tokens, and one EndTag with its name. `RawOK` is: the name is one of the four with that
content model (`RawKindOK`), the tag is not self-closing (§5.2: `<title/>` is an open RCDATA
element), the slice-2 attribute rules, each static part has no NUL and no `</name` + whitespace,
`/` or `>` (the raw-text end-tag rule, `NoRawDanger`, ASCII case), a static part before a hole does not end in `<` or `</`
+ letters (the hole-after-`<` rule for RCDATA, `NoRawLtBeforeHole`, `TextInlining.markupBefore`), and no two adjacent
static parts. The RCDATA/RAWTEXT lemmas are in `RawText.lean` (`goodR_run`, `goodR_close`,
`raw_plain_run`, `raw_chunks_run`); `t1_slice4_inModel` proves the run never reaches `out`.

## What is proved (slice 5)

Slice 5 adds `noscript` (`NoscriptEl`), read as a browser with scripting enabled reads it: RAWTEXT
up to `</noscript` + delimiter. Tessera parses the content as markup (slice-4 pieces). `NoscriptOK`
(`T1Slice5.lean`) allows inside only static text and comments without `<`, TEXT holes, start tags
with no `<` in static attribute values (`NoscriptTagOK`, Subset.lean), end tags of other elements,
and an empty `iframe` (GTM's snippet), so no `</noscript` can form before the end tag.

`TesseraT1.t1_slice5`: `tokenize treeStub (render5 T vs) = denote5 T vs`, where a `noscript`
element reads as its StartTag, its whole rendered content as Character tokens, and one EndTag
`noscript` (`run_noscriptBody`, `run_noscript`). With scripting disabled the content is markup,
which is Tessera's reading and slice 4 on the body; §5.1 cannot hold for both readings at once (doc
question 3 below).

## The profile: `InProfile` and the tree-level theorem

`Profile.lean` states the profile on the compiler's template tree (`Node`/`Forest`: static text,
holes with the context the compiler chose (`HoleCtx`: TEXT, RCDATA, SCRIPT), comments, a DOCTYPE,
void elements, elements with content). `NodeOK m n` has one clause per §5.3 bullet, indexed by the
parse mode `m` (`html`, `svg`, `math`, `select`, `noscript`): HTML names from the WHATWG index plus
custom elements (`HtmlName`), raw text and script elements (`RawElOK`, `ScriptElOK`), SVG and
MathML allow-lists with integration points (`SvgName`, `MathName`; `svg_no_breakout` and
`math_no_breakout` check by `decide` that no allow-listed name is a breakout tag), `select` with
`option`/`optgroup`/`hr`/text, `noscript` as above. `InProfile T` is an optional `<!DOCTYPE html>`
first, then a forest in HTML mode. Every place the doc leaves a choice is marked `CHOICE` and listed
below.

Two theorems state §5.1's token-level reading over it:

- `t1_profile` (`Profile.lean`): the slice-4 fragment (`Html4F`: no `svg`, `math`, `select`,
  `noscript`), through `flatten` to slice-4 pieces and `treeStub`.
- `t1_profile_tree` (`ProfileT1.lean`): the whole profile, with a **stateful tree builder**
  (`Modes.lean`). `TreeSM` is a tree builder whose state every tag token updates; `runS` runs the
  tokenizer with it; `runS_run` proves that a run whose tokens have at most one tag before any start
  tag (`OneStart`) reads as `run tr` with the state fixed at its start, because the tokenizer
  consults the tree builder only in `emitTag` (`step_indep`). `profileTB` is the profile's tree
  builder: a stack of (namespace, lowercased name); start tags dispatch to the foreign-content
  rules when the current node is a non-integration-point SVG or MathML element
  (`foreignDispatch`); in HTML dispatch the tokenizer switch is `treeStub`'s table (`htmlNext`); void
  HTML elements and self-closing foreign elements are not pushed; end tags pop. The theorem: for
  `InProfile T` and values `ValsT T vs` satisfying each hole's escaper postcondition,

  ```
  ∃ ls', runS profileTB [] init (renderF T vs).1 = ((⟨.data, ls'⟩, []), denoteF .html T vs)
  ```

  so the page is read as exactly the tokens the template assigns (`denoteF`: raw-text content as
  characters, markup recursively in its child mode), ends in the data state with every element
  closed, and never reaches `out` (`t1_profile_tree_inModel`).

## Modeling choices in `Whatwg.lean`

Each is also in the file header.

- **Unmodeled states are a sink.** Every state or transition outside the fragment goes to
  `St.out`, which absorbs all input and emits nothing. Since `out` emits nothing, a statement
  about tokens alone does not rule it out, so each slice also proves `leavesModel ... = false`.
  `out` covers the DOCTYPE public and system identifier and bogus DOCTYPE states, the CDATA
  section states, `<?` (the processing-instruction states of
  the current spec, 13.2.5.72-76; older browsers open a bogus comment), PLAINTEXT, the
  script-data escaped states, and, in `treeStub`, `svg`, `math`, `select`, `frameset` and
  `template` start tags.
- **Character references are transparent.** `&` is an ordinary character of its return state.
  The character-reference states consume only ASCII alphanumerics, `#`, `x`/`X` and `;`, then
  reconsume in the return state, and each of those is "anything else" in the data, RCDATA and
  attribute-value states. So states and token boundaries are as modeled, and the model's tokens
  equal the browser's before decoding. Not proved in Lean.
- **No input preprocessing.** CRLF/CR-to-LF normalization is not modeled; CR is tokenizer
  whitespace, like LF, and takes the same transition as LF in every modeled state. A second
  whitespace character never changes the state the first one produced, so collapsing CRLF changes
  only character data: a browser's Character tokens have LF where the model's have CR.
- **Code points in.** No byte decoding or encoding sniffing.
- **Scripting enabled.** `treeStub` reads `noscript` as RAWTEXT. With scripting disabled
  (DOMParser, sandboxed iframes, JavaScript off) `noscript` content is markup. Decision 1 of
  context-model.md asks for rules that hold under both readings; the model checks only the first.
- **Tree construction** appears only as `Tree`, the next tokenizer state after a start tag, as
  a function of the tag name. `treeStub` switches to script data, RCDATA or RAWTEXT for the
  elements the "in head" and "in body" rules name, and sends start tags whose effect depends on
  insertion mode or the open elements to `out` (see its doc comment). Slice 1 emits no start
  tags, so it holds for every stub. Slices 2 and 3 exclude every element whose effect depends on
  the insertion mode, so the name-only stub is exact for them; `wpt-tree-diff` checks it. A slice
  with `select`, `template` or foreign content needs a stub with state.

## Findings

- **`<template><col>` defeats raw-text switching.** In a template whose content is a column
  group, the "in column group" mode ignores `script`, `style`, `title`, `textarea`, `xmp`,
  `iframe`, `noembed`, `noframes` and `noscript` start tags (13.2.6.4.12, "Anything else"), so
  their content is tokenized as data. In `<template><col><textarea>[[x]]</textarea></template>`
  Tessera reads x as RCDATA and a browser as data. Holes stay inert, because every raw-text
  escaper postcondition (`htmlSafe`, `htmlTextSafe`, `jsonScriptSafe`) excludes `<`, but static
  tokens differ. This is a tree-level effect, outside T1. Found by spec reading, not run in a browser.
- **Nested comments are a validator.nu artifact.** validator.nu ends `<!-- <!--x> -->` at
  the first `>`. Chromium 156, Firefox 158 and Firefox 140 do not: in headless runs of four such
  pages (one k8s microvm per browser, a logging Go server), each ended the comment at the first
  `-->`, as the spec and the model do. The pending `nestedCommentStart` check is fail-closed
  hardening, not a fix for a live divergence.
- **Unicode case folding in `browserEndTag`** made Tessera end a script at `</ſcript>`, where
  a browser would not; a source/output difference, not an injection. Closed: main folds ASCII only
  (#298's fix, proved by OpenJML), which is the raw-text end-tag condition in Lean.
- **`<?`** opens the processing-instruction states in the current spec and in html5lib-tests
  c777c408, and a bogus comment in older browsers. Text never contains `<?` (#296).

## Questions the proof raised, and how #349 settled them

Stating the profile in Lean raised these questions about §5. The decisions in
[docs/context-model.md](../../docs/context-model.md) section 7 settle most of them.

| Question | Outcome |
|---|---|
| §5.1 claimed the tree the compiler recorded; implied end tags (`<p><div>`, `<li><li>`, nested `optgroup`, a late DOCTYPE, nested `<form>`), foster parenting and the adoption agency make that false even when every hole keeps its context | Decision 9: §5.1 is a per-hole claim, the half T1 proves. `InProfile` keeps DOCTYPE first and forbids nested `optgroup` |
| RCDATA and RAWTEXT content had no token rules: the proof needs the end-tag rule, no hole after `<` or `</` + letters, and no NUL (`RawOK`) | Decision 10 adopts exactly these |
| `noscript` read with scripting on is one text node; the compiler records markup children | Decision 1: one rule for both readings. The proof covers scripting enabled; disabled is slice 4 on the body |
| `<template><col>` ignores raw-text start tags | Decision 5: `template` takes flow content only. `InProfile` still leaves `template` out |
| Obsolete elements (`center`, `font`, `tt`, `big`, `strike`, `marquee`) | Decision 6 accepts them. `InProfile` still leaves them out |
| `annotation-xml` with an HTML `encoding` is an HTML integration point | Decision 4 follows #301. `InProfile` takes `annotation-xml` as MathML only, which is stricter |
| CDATA under the HTML descendants of `foreignObject` is a bogus comment | Decision 11 makes it an error. CDATA is not modeled |
| Nested `optgroup`, and `hr` inside `optgroup`, change the tree | Decision 3: nested `optgroup` is an error |
| `&` as a complete character reference | Decision 13: a decoding rule in the text and attribute rows. The model treats `&` as transparent and the proof never needs it |
| A browser drops a LF right after `<textarea>`, `<pre>` and `<listing>`, so a value starting with LF loses its first character | Open. Fidelity, not security; the generator could emit a LF after those start tags |
| SVG and MathML names: the tokenizer lowercases them and the tree builder restores `foreignObject` | The allow-list check must be ASCII-case-insensitive (`SvgName` is), and `font` stays off the SVG list |

## Next slices

1. CDATA sections in foreign content (three tokenizer states and a `Tree`-side flag for the
   adjusted current node's namespace).
2. `template` and tables at the tree level: the insertion modes that ignore raw-text start
   tags, foster parenting.
3. OpenJML contracts for `RawOK`, `NoscriptOK` and the allow-lists once phase 2 lands.
