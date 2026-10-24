# Rule-function contracts

Each section states one of the compiler's HTML rule functions twice: as the JML contract that
OpenJML proves against the code (`tessera-compiler/src/jml/verify.sh`), and as a plain-math
statement over the template's characters, which the Lean model of T1 (`proofs/lean` on the
`lean-t1` branch) takes as a hypothesis word for word. Every section ends with what the contract
does NOT cover, so a hypothesis is never read as stronger than the proof.

Conventions: `W` is ASCII whitespace, the five characters TAB, LF, FF, CR and SPACE (U+0009,
U+000A, U+000C, U+000D, U+0020). Strings are sequences of UTF-16 code units, compared exactly.
State names are the WHATWG HTML tokenizer's (HTML Living Standard, section 13.2.5).

Each function is also tested against the Validator.nu HTML parser, a WHATWG tokenizer, on random
inputs. That test catches a WHATWG rule transcribed wrongly into the contract, which a proof
cannot.

## 1. Tag end: `MarkupTokenizer.tagEnd`

Stated and proved with the tag-state fix (#297).

## 2. Comments a browser ends earlier: `HtmlParser.earlyCommentEnd`

Whether a browser ends a comment before the `-->` where Tessera's tokenizer ends it. The compiler
calls this for every comment outside raw text, with `c` the comment from `<!--` through that
`-->` (the first `-->` whose `>` is at least 7 characters in).

**Statement.** The comment states, after `<!--`, are CommentStart, CommentStartDash, Comment,
CommentLessThanSign, CommentLessThanSignBang, CommentLessThanSignBangDash,
CommentLessThanSignBangDashDash, CommentEndDash, CommentEnd and CommentEndBang, plus a final
Emit where a browser emits the comment token. The step function `γ(s, x)`, with "reconsume in
state X" followed through (`γ(X, x)` means "as X does on `x`"):

| state `s` | `-` | `>` | `!` | `<` | other |
|---|---|---|---|---|---|
| CommentStart | CommentStartDash | Emit | γ(Comment, x) | γ(Comment, x) | γ(Comment, x) |
| CommentStartDash | CommentEnd | Emit | γ(Comment, x) | γ(Comment, x) | γ(Comment, x) |
| Comment | CommentEndDash | Comment | Comment | CommentLessThanSign | Comment |
| CommentLessThanSign | γ(Comment, x) | γ(Comment, x) | CommentLessThanSignBang | CommentLessThanSign | γ(Comment, x) |
| CommentLessThanSignBang | CommentLessThanSignBangDash | γ(Comment, x) | γ(Comment, x) | γ(Comment, x) | γ(Comment, x) |
| CommentLessThanSignBangDash | CommentLessThanSignBangDashDash | γ(CommentEndDash, x) | γ(CommentEndDash, x) | γ(CommentEndDash, x) | γ(CommentEndDash, x) |
| CommentLessThanSignBangDashDash | γ(CommentEnd, x) | γ(CommentEnd, x) | γ(CommentEnd, x) | γ(CommentEnd, x) | γ(CommentEnd, x) |
| CommentEndDash | CommentEnd | γ(Comment, x) | γ(Comment, x) | γ(Comment, x) | γ(Comment, x) |
| CommentEnd | CommentEnd | Emit | CommentEndBang | γ(Comment, x) | γ(Comment, x) |
| CommentEndBang | CommentEndDash | Emit | γ(Comment, x) | γ(Comment, x) | γ(Comment, x) |

These are HTML 13.2.5.43 to 13.2.5.52. `run(c, 4) = CommentStart`, and for `k > 4`, `run(c, k)`
is Emit if `run(c, k - 1)` is, else `γ(run(c, k - 1), c[k - 1])`.

**Contract.** For `c` starting with `<!--`: if `|c| >= 7`, `earlyCommentEnd(c) = -1` iff
`run(c, j) ≠ Emit` for every `4 < j < |c|`, that is, iff a browser does not end the comment
before its last character. And when `earlyCommentEnd(c) = -1`, `|c| >= 7` and `c` ends in `-->`,
then `run(c, |c|) = Emit`: the browser ends the comment exactly at Tessera's last `>`. Shorter
strings, which the tokenizer never delimits, are read to their end (`4 < j <= |c|`), so `<!-->`
and `<!--->` give 0. Otherwise the result is the offset the error points at: 0 for `<!-->` and
`<!--->`, else the start of the `--!>` that ends it.

The same in the syntactic form the Lean model's `CommentTessera` uses: when
`earlyCommentEnd(c) = -1`,

- (i) if `|c| >= 7`, then `c[4] ≠ '>'` and not (`c[4] = '-'` and `c[5] = '>'`): `c` does not start
  with `<!-->` or `<!--->`;
- (ii) no `p` with `4 <= p` and `p + 4 < |c|` has `c[p..p+4) = "--!>"`. (A `--!>` ending at the
  last character cannot occur: the tokenizer's `c` ends in `-->`.)

And (iii) the tokenizer delimits `c` as claimed: `MarkupTokenizer.delimitedScan(from, before)`
returns -1 or the least `r >= from` with `buf[r] = '>'` and `buf[r-|before|..r) = before`, and no
`from <= i < r` (or `< n` when -1) has both. `delimitedEnd` calls it with `from = start + 6` and
`before = "--"` for a comment at `start` (the two-line glue that throws on -1 is not proved), so
no `>` at comment index `p >= 6` is preceded by `--` before the final one.

So for the comments the tokenizer produces, the compiler accepts a comment exactly when a
WHATWG tokenizer ends it at the same `>` as Tessera.

**Does NOT cover:**

- The offset the error message points at (it is not part of the contract) and the two-line
  glue in `Builder.comment` that reports it.
- Comments inside raw text,
  which a browser reads as text.
- Comments inside `<svg>` or `<math>` CDATA or other foreign-content rules.
- Parsers that deviate from these states. Validator.nu's tokenizer consumes the character after
  a nested `<!--` in CommentLessThanSignBangDashDash instead of reconsuming it, so it ends
  `<!-- <!--x> -->` at the first `>`, where the states above (and the compiler) do not. This is a
  validator.nu artifact (G10): Chromium 156, Firefox 158 and Firefox 140 ESR end that comment at
  the first `-->`, as WHATWG says. The #306 profile forbids `<!--` in comment text (phase 2). The
  #344 oracle, which parses with validator.nu, would report such a comment as a
  STRUCTURE_CHANGED false positive. `CommentEndSpecTest` finds no other difference from
  Validator.nu on every comment `<!--x-->` with `x` up to 6 characters over `-!<>a`.

Proof: `bash tessera-compiler/src/jml/verify.sh HtmlParser` (`earlyCommentEnd` 27.7 s;
`commentStep`, `commentText`, `commentEnd` and the lemmas `commentEndsAtDashDashGt` and
`commentEndsAtDashDashBangGt` 2 to 10 s each; `MarkupTokenizer.delimitedScan`, run with `verify.sh
Tokenizer`, 5 s). `delimitedEnd` is `delimitedScan` plus the throw it did inline, with the same result.
`earlyCommentEnd` is a run of the comment states instead of the earlier `startsWith`/`indexOf`
form, with the same result on every input the tokenizer can pass: `EarlyCommentEndTest` checks it
against the earlier form on every comment `<!--x-->` with `x` up to 7 characters over `-!<>a` that
the tokenizer delimits there, and on every `<!--` followed by up to 3 of those characters. On
strings the tokenizer never passes, with a `-->` before the last character (`<!--a-->b`), it
reports that `-->` where the earlier form returned -1. A mutant `commentStep` in which `--!>` does
not end the comment fails its postcondition (checked on the proof-rule-functions branch).

## 3. Markup a browser ends at an earlier `>`

Notation: a string `s` has length `|s|` and characters `s[0] .. s[|s|-1]`. `letter(c)` means `c` is in `A-Z` or `a-z` (ASCII only). `min ∅ = -1`.

### `HtmlParser.earlyEnd(outer)`

- Input: `outer`, the full text of a CDATA section (`<![CDATA[...]]>`), DOCTYPE (`<!DOCTYPE ...>`), XML declaration or processing instruction (`<?...>`), as `MarkupTokenizer` delimited it. Its last character is the `>` that the tokenizer took as the end.
- Result: `earlyEnd(s) = min { i | 0 <= i < |s| - 1 and s[i] = '>' }`.
- Use: `Builder.cdata` and `Builder.declaration` report a build error when the result is `>= 0`.
- WHATWG states modeled: *bogus comment state* (ends at the first `>`), entered from *markup declaration open state* for `<![CDATA[` when the adjusted current node is not in foreign content (`cdata-in-html-content`); for `<?`, *tag open state* goes to the *processing instruction open state*, and the processing instruction states (13.2.5.72-76) end the token at the first `>`, or turn it into a comment that ends there (a target not starting with an ASCII alpha or `_`, a target with other characters, or the targets `xml` and `xml-stylesheet`); the DOCTYPE states (*DOCTYPE*, *before/after DOCTYPE name*, *DOCTYPE name*, the *public/system identifier* states including the quoted ones, and *bogus DOCTYPE*), each of which emits the token at the first `>`.
- Not covered: inside `<svg>` and `<math>` the *CDATA section state* ends only at `]]>`; the contract rejects any earlier `>` there too, so it over-approximates. That the tokenizer's delimiting (the input) is correct is the `MarkupTokenizer` contracts' job. The glue (`checkEarlyEnd` reports at `start + result`) is checked by tests, not proved.

### `HtmlParser.bogusComment(text)`

- Input: `text`, one maximal run of element text that the tokenizer did not read as markup (`Builder.flushText` passes the buffered text between two markup tokens; the content of raw-text elements never reaches it).
- Define `bogusAt(t, i)` iff `0 <= i`, `i + 1 < |t|`, `t[i] = '<'`, and one of
  - `t[i+1] = '!'`, or `t[i+1] = '?'`, or
  - `t[i+1] = '/'` and not (`i + 2 < |t|` and (`letter(t[i+2])` or `t[i+2] = '>'`)).
- Result: `bogusComment(t) = min { i | bogusAt(t, i) }`. So the result is `-1` iff `t` has no `<!`, no `<?`, and no `</` that is followed by something other than an ASCII letter or `>` (a `</` at the very end of `t` counts as followed by something else).
- Use: `Builder.flushText` reports a build error at `textStart + result` when the result is `>= 0`.
- WHATWG states modeled: *data state* `<` → *tag open state*; there `!` → *markup declaration open state*, which (when not `--`, `DOCTYPE` or `[CDATA[`) goes to *bogus comment state* (`incorrectly-opened-comment`); `?` → *processing instruction open state*, which reads a processing instruction or a comment, both ending at the first `>`; `/` → *end tag open state*, where an ASCII alpha starts a tag name, `>` drops `</>` (`missing-end-tag-name`), and anything else goes to *bogus comment state* (`invalid-first-character-of-tag-name`).
- Not covered: the contract over-approximates in two places: every `<!` counts (a real `<!--`, `<!DOCTYPE` or `<![CDATA[` would have been read as markup by the tokenizer, so it cannot be in `text`), and a `</` at the end of `text` counts (a browser at end of file would emit it as text, but in a template more markup follows). It says nothing about `<` followed by a letter: the tokenizer reads that as a tag, so it is not in `text` (tokenizer contracts). Contract C1 of the Lean model is `bogusComment` together with `MarkupTokenizer.kind`: `</>` passes `bogusComment`, but `kind` reads it as an end tag and `endTag` rejects it (the first check in `MarkupTokenizer.endTag`), so no accepted text run contains it. The glue is checked by tests, not proved.

### `HtmlParser.cdataHole(outer)`

- Input: `outer`, a CDATA section as the tokenizer read it.
- Result: `cdataHole(s) = min { i | 0 <= i, i + 1 < |s|, s[i] = '[' and s[i+1] in {'[', '('} }`, the first `[[` or `[(`.
- Use: `Builder.cdata` reports a build error when the result is `>= 0`, so no `[[...]]` or `[(...)]` expression is ever inlined into a CDATA section.
- WHATWG states modeled: none directly. It closes the gap that a CDATA section is a *bogus comment* outside foreign content and *CDATA section state* text (no character references) inside it, so neither escaper fits a value there.
- Not covered: whether `TextInlining` would see other expression syntaxes; it only recognises `[[` and `[(`.

Proof: `bash tessera-compiler/src/jml/verify.sh HtmlParser` (OpenJML 21.0.28, z3 5.1.0, `--code-math=java`; 5-8 s per method). `earlyEnd` and `cdataHole` were rewritten from `indexOf` to explicit loops with no behaviour change, because OpenJML's bundled `String.indexOf` specs are unspecified; `EarlyEndRulesTest` checks the loops against the `indexOf` forms on every string up to length 8 over `>a"<` and `[(a]`, and `bogusComment` against `bogusAt` on every string up to length 5 over `<!?/>aZ0 `.

Negative checks: dropping `?` from `bogusComment` fails its loop invariant, and stopping `earlyEnd` one character early fails its postcondition (about 9 s each, checked on the proof-rule-functions branch, whose code for both is the same).

## 4. Raw-text end tags: `HtmlParser.browserEndTag`

Where a browser ends the content of a raw-text or RCDATA element. The compiler calls
`browserEndTag(content, name, from)` after the start tag of `<script>` and `<style>`, with `from`
just past its `>` (the result bounds the raw text Tessera escapes for that element), and after the
start tag of the other elements a browser reads as raw text or RCDATA (`<title>`, `<textarea>`
and the rest of `Contexts.browserRawText`), to check that the template's own end tag is the one a
browser uses.

**Statement.** Let `low(x)` lower A-Z only (`x + 32` for `'A' <= x <= 'Z'`, else `x`). For a
string `c` and a name `m`, `endTagAt(c, m, i)` holds iff

- `0 <= i <= |c| - 2 - |m|`, `c[i] = '<'` and `c[i+1] = '/'`,
- `low(c[i+2+k]) = low(m[k])` for every `0 <= k < |m|`, and
- `i + 2 + |m| = |c|`, or `c[i+2+|m|]` is in `W ∪ {'/', '>'}`.

This is HTML 13.2.5.12, 13.2.5.15 and 13.2.5.18 (RCDATA, RAWTEXT and script data end tag name
states) for the appropriate end tag of a start tag named `m`: the tokenizer appends `low` of each
ASCII letter to the temporary buffer, and only whitespace, `/` or `>` after a buffer equal to the
start tag's name ends the element. Anything else (`</scriptx`, `</scr` and a non-letter) is text.

**Contract.** For `0 <= from <= |c|`:

- `browserEndTag(c, m, from) = -1` iff `endTagAt(c, m, i)` fails for every `from <= i < |c|`;
- otherwise the result is the least `i >= from` with `endTagAt(c, m, i)`.

Names compare ASCII case-insensitively only: `</ſcript>` (U+017F) and `</scrİpt>` (U+0130) do not
match, as in a browser. `String.regionMatches(true, ...)` and `equalsIgnoreCase` fold them.

**Does NOT cover:**

- The end of `c` counts as a delimiter. A browser at end of input emits `</script` as text; in a
  template more input follows, so this case does not arise between a start tag and its end tag.
- Script data escaped and double-escaped states (`<!--` and `<script` inside `<script>`), where
  `</script>` can stop ending the element. The compiler rejects `<!--` inside `<script>`
  (`HtmlParser.java`, "'<!--' inside <script> is not supported"); that check is not proved here.
- Foreign content: inside `<svg>` or `<math>`, `<script>`, `<style>` and `<title>` are not raw
  text. The callers' choice of when to call this (`RAW_TEXT_ELEMENTS`, `Contexts.browserRawText`)
  and `matchEndTagCase`, which rewrites the end tag's case for the tokenizer, are tested, not
  proved.
- `m` is the start tag's name as written; the contract compares it with `low`, so its case does
  not matter, but it assumes `m` is the name a browser uses as the last start tag.

Proof: `bash tessera-compiler/src/jml/verify.sh HtmlParser` (`browserEndTag`, its helpers
`asciiMatchesIgnoreCase`, `asciiLower` and `endTagDelimiter`: 8.7 s, 6.4 s, 2.2 s and 2.1 s).
`browserEndTag` moved from `Builder` to a static method taking the content and name as
parameters, matches `<` and `/` exactly and then the name with `asciiMatchesIgnoreCase` (which
now also returns false out of bounds), and its loop guard is written `len <= n - 2 - i` so that
it cannot overflow. None of these changes behaviour for `0 <= from <= |content|`, which every
caller passes: ASCII folding leaves `<` and `/` unchanged. `BrowserEndTagSpecTest` compares it
with Validator.nu on about 20,000 random `<script>`, `<style>`, `<title>` and `<textarea>` bodies
built from near-miss end tags (other case, U+017F and U+0130 folds, longer and shorter names,
whitespace other than CR, `/` and `>`).

## 5. Markup right before an inlined value

### `TextInlining.htmlMarkupStart(before)` and `TextInlining.scriptMarkupStart(before)`

- Input: `before`, the static text of one `Text` node between the previous inlined expression (`[[...]]` or `[(...)]`) of that node, or the node's start, and the next one. `TextInlining.render` passes it; it never sees text from another node or a value.
- Define `letters(t, f)` iff every `t[k]` with `f <= k < |t|` is an ASCII letter, and `endingAt(t, j)` iff `0 <= j < |t|`, `t[j] = '<'`, and `t[j..]` is one of `<`, `<!`, `<!-`, `<?`, or `</` followed by letters only:
  `j + 1 = |t|`, or (`j + 2 = |t|` and `t[j+1] in {'!', '?'}`), or (`j + 3 = |t|` and `t[j+1] = '!'` and `t[j+2] = '-'`), or (`t[j+1] = '/'` and `letters(t, j + 2)`).
- `htmlMarkupStart(t)`: the result is `-1` iff no `j` has `endingAt(t, j)`; otherwise it is a `j` with `endingAt(t, j)`. Such a `j` is unique, because every ending contains exactly one `<` at its start. Equivalently: the result is `>= 0` iff `t` ends with `<`, `<!`, `<!-`, `<?`, or `</` followed by zero or more ASCII letters.
- `scriptMarkupStart(t) = |t| - 3` if `|t| >= 3` and `t` ends with `<!-`, else `-1`.
- Use: `markupBefore(context, t)` is `scriptMarkupStart` in SCRIPT, `htmlMarkupStart` in TEXT and RCDATA, and `-1` in every other context; `null` when that is `-1`, else `t.substring(result)`. `render` reports a build error and emits no code for the expression when `markupBefore` is non-null. That dispatch and the error are glue, checked by `ValueAfterLessThanTest` and `MarkupBeforeRulesTest`, not proved.
- WHATWG states modeled: TEXT: *data state* `<` → *tag open state*, where an ASCII alpha starts a tag name, `!` → *markup declaration open state* (`<!-` + `-` = comment), `?` → *bogus comment*, `/` → *end tag open state*; a value starting with a letter (or `-`, `[`, ...) would continue any of these endings into markup. RCDATA: *RCDATA less-than sign state* → *RCDATA end tag open state* → *RCDATA end tag name state*: `</` + letters + a value could complete the appropriate end tag (`</text` + `area`, or `</textarea` + a value starting with whitespace, `/` or `>`). SCRIPT: *script data less-than sign state* → *script data escape start state*: `<!-` + a negative number gives `<!--`, entering *script data escaped state*, where `<script` hides the next `</script>`.
- Not covered: in SCRIPT the contract checks only `<!-`. The other script endings (`<`, `<!`, `</` + letters) are safe only if a JSON value never starts with `/`, `!`, a letter that would continue a `</script` name with whitespace, `/` or `>` after it, or `--`. That needs the JSON first-character fact (the value starts with `"`, `-` followed by a digit, a digit, `t`, `f`, `n`, `[` or `{`), which belongs to the escaper proofs and is not proved yet (`jsonFirst` in the Lean model). In RCDATA the contract does not say what the static text after the value does; `browserEndTag` covers static end tags.

Proof: `bash tessera-compiler/src/jml/verify.sh TextInlining` (z3; about 4 s for `scriptMarkupStart` and 6 s for `htmlMarkupStart`). `markupBefore` was split into the two index functions with no behaviour change, because OpenJML has no specification for `String.lastIndexOf` or `substring` contents, and does not know that two constants of an enum read from bytecode differ. `MarkupBeforeRulesTest` checks it against the earlier form in every context on every string up to length 6 over `<!-?/aZ1 `. The proof of `htmlMarkupStart`'s `-1` case needs the intermediate assertion that no `<` is left, then that no `endingAt` holds.

Negative checks: dropping `?` from `htmlMarkupStart` and the final `-` from `scriptMarkupStart` each fail a postcondition (about 9 s and 5 s, checked on the proof-rule-functions branch, whose code for both is the same).

## 6. Text runs: `MarkupTokenizer.nextStructure` and `kind`

Where a `<` in the template can be left as text. Together with `bogusComment` (section 3) this is
what the Lean model's `NoTagOpenInText` and `staticText_of_contracts` take for static text.

**Statement.** Let `buf` be the template's characters, `n = |buf|` and `letter(c)` as in
section 3. `noTagStart(buf, j)` holds iff

- not (`j + 1 < n` and `letter(buf[j+1])`), and
- not (`j + 2 < n`, `buf[j+1] = '/'` and (`letter(buf[j+2])` or `buf[j+2] = '>'`)).

**Contract.**

- `kind(i) = NONE` implies `noTagStart(buf, i)`, for `0 <= i < n`.
- For `0 <= from <= n`, `nextStructure(from)` is the least `i >= from` with `buf[i] = '<'` and
  `kind(i) != NONE`, or `-1` if there is none; and every `j` with `from <= j < r` and
  `buf[j] = '<'` has `noTagStart(buf, j)`, where `r` is the result, or `n` when it is `-1`.

`run()` passes the handler each text run outside untokenized `<script>` and `<style>` content as
`[current, nextStructure(current))`, or `[current, n)` when that is `-1`. So for every such run
`[s, e)` and every `s <= j < e` with `buf[j] = '<'`: if `j + 1 < e` then `buf[j+1]` is not an
ASCII letter, and if `j + 2 < e` and `buf[j+1] = '/'` then `buf[j+2]` is neither an ASCII letter
nor `>`. One `Builder` text node is exactly one such run, because `Builder.flushText` runs at
every other token.

With `bogusComment` (no `<!`, no `<?`, and no `</` followed by anything but an ASCII letter or
`>`, the end of the run included) and the first check in `MarkupTokenizer.endTag`, which rejects
`</>`, every `<` in an accepted text node is its last character or is followed by a character
that is not an ASCII letter, `!`, `/` or `?`.

WHATWG states modeled: *data state* `<` goes to *tag open state*, where an ASCII alpha starts a
tag name; `/` goes to *end tag open state*, where an ASCII alpha starts a tag name and `>` drops
`</>`. The contract says the tokenizer reads each of these as markup, never as text.

**Does NOT cover:**

- The glue in `run()` that turns the scan into text runs (read it in `MarkupTokenizer.java`) and
  the `</>` check in `endTag`.
- Text inside untokenized `<script>` and `<style>` content, and the content of `<textarea>`,
  `<title>` and non-JavaScript `<script>`, which the tokenizer tokenizes but `Builder` drops
  (section 4 covers where those end).
- Which markup a `kind` other than `NONE` is, or that it is read correctly (section 1 and the
  tokenizer's other code). A `<` followed by a non-ASCII letter is a start tag to the tokenizer
  and text to a browser; `Builder.startTag` rejects its name.
- `Character.isWhitespace` is trusted (`src/jml/java/lang/Character.jml`): exact on ASCII as the
  JDK documents it, open above.
- `buf` is the buffer as the tokenizer reads it. `Builder.matchEndTagCase` changes the case of an
  end tag's letters ahead of the tokenizer; `noTagStart` does not depend on case.

Proof: `bash tessera-compiler/src/jml/verify.sh Tokenizer` (`kind` 16.3 s; `isElementName`,
`nextStructure`, `startsWith` and `keyword` 4 to 7 s each; `keyword` is proved free of
out-of-bounds reads only). `nextStructure` is the scan that `run()` did inline with `indexOf`
and a `while` over `kind`, as one loop with the same result. `TextRunSpecTest` checks every text
run the tokenizer emits against `noTagStart` on every input up to length 6 over `<!/?aZ>- `
(space included).

Negative checks: `nextStructure` stopping only at a `kind` of `START_TAG` fails its loop
invariant, and `kind` not reading `</q` as an end tag fails its postcondition (about 7 s and
25 s).
