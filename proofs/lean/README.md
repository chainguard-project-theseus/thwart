# Lean model and proof of T1 (token level)

This directory is a Lean 4 model of the WHATWG HTML tokenizer and a proof, over the Tessera HTML
profile, of the per-hole half of the invariant in
[docs/security-architecture.md](../../docs/security-architecture.md) §5.1 (section 1 of
[docs/context-model.md](../../docs/context-model.md)). It touches no Java, is not part of
`mvn verify`, and uses Lean core only (no Mathlib).

## What T1 says

Take a template the compiler accepts, and values for its holes that each satisfy the
postcondition of the escaper the compiler chose for that hole. Render the page. A WHATWG
tokenizer, driven by a tree builder that tracks namespaces, integration points and the raw-text
switches, reads the page as exactly the tokens the compiler assigned:

- every TEXT and RCDATA value is Character tokens of the element the compiler put it in;
- every attribute value is one attribute value of the start tag the compiler put it on;
- every script value is script data of its `<script>`, which ends at its own end tag;
- every value is read in the tokenizer state of its element's content model and in the namespace
  the compiler chose (HTML, SVG, MathML);
- no value or static text opens or closes an element the template does not have, the page ends in
  the data state with every element closed, and the run never leaves the modeled tokenizer.

In §5.1's words: each hole lands in the token, tokenizer state and namespace the compiler chose.
The other half of §5.1, that the benign and hostile trees are equal, is not proved here; the
oracle checks it (§5.7, `STRUCTURE_CHANGED` in context-model.md section 1).

The theorem is `TesseraT1.t1_profile_tree` in `TesseraT1/ProfileT1.lean`:

```lean
theorem t1_profile_tree : ∀ (T : Forest) (vs : List (Option (List Char))), InProfile T → ValsT T vs →
    ∃ ls', runS profileTB [] init (renderF T vs).1 = ((⟨.data, ls'⟩, []), denoteF .html T vs)
```

- `Forest` is the compiler's template tree: static text, holes tagged with the context the
  compiler chose (`HoleCtx`: TEXT, RCDATA, SCRIPT), comments, a DOCTYPE, void elements and
  elements with content (`Profile.lean`).
- `InProfile T` is the profile, one clause per row of §5.2 and §5.3 (`Profile.lean`, `NodeOK`,
  indexed by parse mode: HTML, SVG, MathML, `select`, `noscript`).
- `ValsT T vs` says each value satisfies its escaper's postcondition (`Escapers.lean`).
- `renderF` is what the generated code writes; `denoteF` is the token list the compiler meant.
- `runS profileTB` runs the tokenizer model (`Whatwg.lean`) with `profileTB`, a tree builder
  that keeps a stack of (namespace, name) and picks the tokenizer state after each start tag.
- `t1_profile_tree_inModel` restates the result as: the run never reaches the unmodeled sink.

The slice theorems `t1_slice1` to `t1_slice5` (text; tags, attributes, comments and DOCTYPE;
`<script>` with JSON holes; `title`, `textarea`, `style`, `iframe`; `noscript`) are the
building blocks. [NOTES.md](NOTES.md) describes each slice, the modeling choices and the
questions the proof raised.

## What it does not cover

- **Tree construction.** The claim is about tokens. Which node a token attaches to is out of
  scope: implied end tags, nested `<form>`, the adoption agency and foster parenting can move
  static nodes. That the trees are equal for every value is the oracle's job.
- **CDATA sections in foreign content.** The tokenizer model sends `<![CDATA[` to the
  unmodeled sink, so a template with CDATA is outside `InProfile`.
- **`template` and tables.** `template` is not on the Lean allow-list, and the insertion modes
  of tables (which ignore raw-text start tags under `<template><col>`, and foster-parent text)
  are not modeled.
- **Fragment content kinds** (§5.5): `t:insert`, `t:replace`, `Fragment`, `SafeHtml`,
  `t:utext`, `[(...)]` and HTML messages are not holes in the model.
- **JavaScript.** Script holes rest on the JSON escaper and on the script body containing no
  `<!--` and no early end tag. The data-prelude rule (§5.4) is not modeled.
- **Scripting disabled.** `noscript` is read as a browser with scripting enabled reads it.
- **Character references, input preprocessing and byte decoding.** Tokens are compared before
  character-reference decoding; CR/LF normalization and encoding sniffing are not modeled.
- **Template to tree.** The step from a template file to `Forest`, including the runtime
  unrolling of `t:each`, `t:if` and `t:insert`, is not formalized.

## Trusted base

- `#print axioms` for `t1_profile_tree`, `t1_profile_tree_inModel`, `t1_profile` and
  `t1_slice1` to `t1_slice5` lists only `propext`, `Classical.choice` and `Quot.sound`, the
  standard axioms of Lean's logic.
- There is no `sorry` and no `native_decide`, so the kernel checks every proof, including the
  `decide` facts about the allow-lists.
- The model itself is trusted: `Whatwg.lean` transcribes the tokenizer states the profile can
  reach, and `profileTB` transcribes the tree builder's state switches. The differential checks
  below are the evidence that both match the spec's test suites.
- The hypotheses below are the Java side's job.

## What the Java side must guarantee

Each row is a hypothesis of `t1_profile_tree`. Status is for this repo's `main` unless the row
says otherwise. "Proved by OpenJML" means a JML contract proved in the OpenJML proofs kept on a
fork (github.com/tcnghia/harder, branch proofs), not in this repo.

| Lean hypothesis | Java check | Status |
|---|---|---|
| `htmlTextSafe` for TEXT values | `Escapers.text` | proved by OpenJML |
| `htmlSafe` for attribute and RCDATA values | `Escapers.attr`, `rcdata`, `url`, `resourceUrl`, `refresh`, the attribute half of `jsonAttr` | proved by OpenJML |
| `jsonScriptSafe` for script values | `Escapers.json` | proved by OpenJML for strings |
| `JsonFirst`: a script value starts like a JSON token | `Escapers.json` for numbers, booleans, `null` and structures | not yet |
| No `<` in text except as a tag or comment (`StaticText`) | `HtmlParser.bogusComment`, `MarkupTokenizer.kind` | enforced on main (#296); `bogusComment` proved by OpenJML; the `kind` text-run fact not yet |
| No hole right after static `<`, `</` + letters, `<!-` (`NoLtBeforeHole` and its RCDATA and script forms) | `TextInlining.markupBefore` | enforced on main (#299); proved by OpenJML |
| Comments end where Tessera ends them (`CommentTessera`) | `HtmlParser.earlyCommentEnd` | enforced on main (#295); proved by OpenJML |
| Tag syntax: ASCII whitespace, quotes only as delimiters, `/` only before `>` (`SrcTagOK`, `TagAttrsOK`) | `MarkupTokenizer.tagEnd`, `HtmlParser.attribute` | enforced on main (#297, #353); `tagEnd` proved by OpenJML; the attribute splitter checked, not proved |
| The generator's bytes around an attribute hole (`StartTag.render`) | `TemplateGenerator.dynamicAttr` | not yet in this repo (a generated-code checker on the fork checks it on every compiled template) |
| No `</name` + delimiter inside raw text, ASCII case (`NoScriptDanger`, `NoRawDanger`) | `HtmlParser.browserEndTag` | enforced on main (#298); proved by OpenJML |
| No `<!--` in script, script ends at its own end tag (`ScriptOK`) | `HtmlParser.endRaw`, `selfClosedTagEnd` | enforced on main; checked, not proved |
| No `/>` on `title` and `textarea` (`RawOK`) | `HtmlParser` | enforced on main (#298) |
| `iframe` is empty (`RawKindOK`) | none | not yet (#328) |
| No static NUL | none | not yet (#327) |
| No `</noscript` before its end tag | `HtmlParser` | enforced on main (#298) |
| `noscript` content: no `<` in its comments and static attribute values, no raw-text, `select` or foreign children (`NoscriptOK`) | none | not yet (#328) |
| SVG and MathML: allow-listed names only, no breakout tags (`SvgName`, `MathName`) | `HtmlParser.namespace`, `breaksOut` | breakout deny-list in open PR #301; allow-lists not yet (#328) |
| `select` takes `option`, `optgroup`, `hr` and text (`NodeOK .select`) | `HtmlParser` | in open PR #305; no nested `optgroup` not yet (#328) |
| HTML element names on the allow-list (`HtmlName`) | none | not yet (#328) |
| `<!DOCTYPE html>` only as the first node (`DoctypeOK`) | `HtmlParser` | partial: early `>` rejected on main (#296); position not yet (#327) |
| Elements with content are not void; end tags match (`¬IsVoid`, balanced `Forest`) | `MarkupTokenizer`, `HtmlParser` | enforced on main |

context-model.md carries the same assumptions in its Proof column, row by row.

## Build and check

From this directory, with the toolchain in `lean-toolchain` (Lean 4.35.0, which `elan` installs
on first use):

```
lake build                                    # checks every proof
lake build html5lib-diff wpt-tree-diff wpt-tree-diff-s
.lake/build/bin/html5lib-diff test/html5lib/*.test
.lake/build/bin/wpt-tree-diff test/wpt-tree/*.dat
.lake/build/bin/wpt-tree-diff-s test/wpt-tree/*.dat
```

A clean `lake build` takes about 15 s on 4 CPUs once the toolchain is installed. It prints
linter warnings (unused `simp` arguments, deprecated names) and no errors.

The three executables check the model against the spec's own test suites (vendored in `test/`,
see [test/README.md](test/README.md) for sources and licenses):

- **`html5lib-diff`** runs the tokenizer model over the html5lib tokenizer tests and compares
  tokens, coalescing Character tokens and ignoring parse errors. It skips inputs with `&`
  (decoding is not modeled), `doubleEscaped` tests (lone surrogates are not Lean `Char`s), runs
  that reach the unmodeled sink (mostly DOCTYPEs with public or system identifiers) and initial
  states other than Data, RCDATA, RAWTEXT and Script data.
  Result: `pass 1439, fail 0; skipped: & in input 109, doubleEscaped 6, reaches unmodeled state 753,
  unmodeled initial state 100`.
- **`wpt-tree-diff`** runs `treeStub`, the name-only tree builder of slices 1-5, over the WPT
  tree-construction tests, and compares the text the model reads after each raw-text start tag
  with the text of that element in the expected tree. It skips fragment, `#script-off` and
  scripted tests, inputs with `&` or with `template`, `select`, `svg`, `math`, `frameset` or
  `plaintext`, and runs that reach the sink.
  Result: `pass 783 (164 with raw-text elements), fail 0, skipped 1190`.
- **`wpt-tree-diff-s`** runs the same check with `profileTB`, including `svg`, `math` and
  `select` inputs; only `template`, `frameset` and `plaintext` are excluded.
  Result: `pass 1047 (167 with raw-text elements), fail 0, skipped 926`.

Mutations are caught: making the comment-end-bang state append `-!` instead of `--!` fails 3
html5lib tests, and dropping `textarea` from `treeStub`'s RCDATA case fails 8 WPT tests.

## Files

| File | Contents |
|---|---|
| `TesseraT1/Whatwg.lean` | Tokenizer model, `treeStub` |
| `TesseraT1/Escapers.lean`, `TextChunks.lean` | Escaper postconditions as predicates |
| `TesseraT1/Subset.lean`, `Check.lean`, `Contracts.lean` | Slice templates, side conditions, decision procedures, rule contracts |
| `TesseraT1/T1.lean`, `T1Slice2.lean` ... `T1Slice5.lean` | The slice theorems |
| `TesseraT1/Tag.lean`, `Comment.lean`, `Script.lean`, `RawText.lean` | Tokenizer lemmas per construct |
| `TesseraT1/TagBridge.lean`, `CommentBridge.lean`, `ScriptBridge.lean`, `RawBridge.lean` | From the compiler's side conditions to the lemmas |
| `TesseraT1/Profile.lean` | `InProfile`, `t1_profile` (the profile without foreign content) |
| `TesseraT1/Modes.lean` | Stateful tree builder `TreeSM`, `runS`, `runS_run` |
| `TesseraT1/ProfileT1.lean` | `profileTB`, `renderF`, `denoteF`, `t1_profile_tree` |
| `Diff/Main.lean`, `Diff/Tree.lean`, `Diff/TreeS.lean` | The three differential checks |
| `NOTES.md` | Slice-by-slice notes, modeling choices, findings |
