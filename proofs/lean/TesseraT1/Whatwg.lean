/-
A fragment of the WHATWG HTML tokenizer (HTML Living Standard, section 13.2.5
"Tokenization"), restricted to the states the safe subset reaches, plus the one
tree-construction decision the subset depends on: which tokenizer state follows
a start tag (section 13.2.6, "in body" / "in head" rules for raw-text and
RCDATA elements).

Each `St` constructor is one spec state; its step function follows the spec's
state text, in the spec's order, and its doc comment names the spec section.

Modeling choices (each one is also listed in proofs/lean/README.md):

* Unmodeled states go to the sticky sink `St.out`, which absorbs all input and
  emits nothing. A theorem that the run never reaches `out` (`leavesModel ... =
  false`) proves the page stays inside the modeled fragment; nothing is assumed
  unreachable. A theorem that only constrains the emitted tokens does not, since
  `out` emits none, so each T1 slice states both.
  `out` stands for: the DOCTYPE public and system identifier states and the
  bogus DOCTYPE state, `<?` (13.2.5.72-76 processing
  instruction states in the current spec; a bogus comment in older browsers),
  PLAINTEXT, the script-data escaped and double-escaped states, and (in
  `treeStub`) the start tags listed there.
* Character references are transparent: in the data, RCDATA and attribute-value
  states a `&` is treated as an ordinary character of the state that would be
  the character reference's return state. The character reference states
  (13.2.5.77-84) consume only ASCII alphanumerics, `#`, `x`/`X` and `;`, and then
  reconsume the next character in the return state. Each of those characters is
  "anything else" in the data, RCDATA and attribute-value states, so the state
  sequence and every token boundary are the same as in the model. Only the
  decoded code points of Character tokens and attribute values differ, so the
  model's tokens equal the browser's tokens before character-reference decoding.
* Input stream preprocessing (13.2.3.5: CRLF and CR to LF) is not modeled; the
  tokenizer runs on the code points as given and treats CR as whitespace like
  LF. In every modeled state CR, LF, TAB, FF and SPACE take the same transition.
* The encoding is fixed (code points in, no byte decoding or sniffing).
* Duplicate attributes are dropped when the tag is emitted rather than when the
  attribute name is complete (13.2.5.33); the emitted tokens are the same.
-/
namespace TesseraT1.Whatwg

/-- "ASCII alpha" (Infra). -/
def isAlpha (c : Char) : Bool :=
  ('a' ≤ c && c ≤ 'z') || ('A' ≤ c && c ≤ 'Z')

/-- Tokenizer whitespace: TAB, LF, FF, SPACE, and CR (see the preprocessing note). -/
def isWs (c : Char) : Bool :=
  c == '\t' || c == '\n' || c == '\x0C' || c == '\r' || c == ' '

/-- "ASCII lowercase" of one code point. -/
def lower (c : Char) : Char := c.toLower

def lowerStr (s : List Char) : List Char := s.map lower

/-- A token, as the tokenizer emits it to the tree builder. -/
inductive Token where
  | char (c : Char)
  | startTag (name : List Char) (attrs : List (List Char × List Char)) (selfClosing : Bool)
  | endTag (name : List Char)
  | comment (data : List Char)
  | doctype (name : Option (List Char)) (forceQuirks : Bool)
  deriving Repr, DecidableEq, Inhabited

/-- A tag token under construction. `attrs` is in reverse order; its head is the
attribute currently being built. -/
structure Tag where
  isEnd : Bool
  name : List Char
  attrs : List (List Char × List Char)
  selfClosing : Bool
  deriving Repr, DecidableEq, Inhabited

/-- The three text-like content models that end at an "appropriate end tag". -/
inductive Raw where
  | rcdata
  | rawtext
  | script
  deriving Repr, DecidableEq, Inhabited

/-- Tokenizer states. States carry their buffers: the tag token, the comment
data, the markup-declaration lookahead, or the temporary buffer. -/
inductive St where
  /-- 13.2.5.1 Data state. -/
  | data
  /-- 13.2.5.2 RCDATA, 13.2.5.3 RAWTEXT, 13.2.5.4 Script data state. -/
  | raw (k : Raw)
  /-- 13.2.5.6 Tag open state. -/
  | tagOpen
  /-- 13.2.5.7 End tag open state. -/
  | endTagOpen
  /-- 13.2.5.8 Tag name state. -/
  | tagName (t : Tag)
  /-- 13.2.5.9/12/15 RCDATA, RAWTEXT, script data less-than sign state. -/
  | rawLt (k : Raw)
  /-- 13.2.5.10/13/16 RCDATA, RAWTEXT, script data end tag open state. -/
  | rawEndTagOpen (k : Raw)
  /-- 13.2.5.11/14/17 ... end tag name state; `buf` is the temporary buffer
  (the tag name is its ASCII lowercase). -/
  | rawEndTagName (k : Raw) (buf : List Char)
  /-- 13.2.5.18 Script data escape start state. -/
  | scriptEscapeStart
  /-- 13.2.5.19 Script data escape start dash state. -/
  | scriptEscapeStartDash
  /-- 13.2.5.32 Before attribute name state. -/
  | beforeAttrName (t : Tag)
  /-- 13.2.5.33 Attribute name state. -/
  | attrName (t : Tag)
  /-- 13.2.5.34 After attribute name state. -/
  | afterAttrName (t : Tag)
  /-- 13.2.5.35 Before attribute value state. -/
  | beforeAttrValue (t : Tag)
  /-- 13.2.5.36 Attribute value (double-quoted) state. -/
  | attrValueDQ (t : Tag)
  /-- 13.2.5.37 Attribute value (single-quoted) state. -/
  | attrValueSQ (t : Tag)
  /-- 13.2.5.38 Attribute value (unquoted) state. -/
  | attrValueUnq (t : Tag)
  /-- 13.2.5.39 After attribute value (quoted) state. -/
  | afterAttrValueQ (t : Tag)
  /-- 13.2.5.40 Self-closing start tag state. -/
  | selfClosing (t : Tag)
  /-- 13.2.5.41 Bogus comment state. -/
  | bogusComment (d : List Char)
  /-- 13.2.5.42 Markup declaration open state; `buf` holds the characters
  consumed so far while matching `--`, `DOCTYPE` or `[CDATA[` (the spec's
  "next few characters" lookahead, one character at a time). -/
  | markupDecl (buf : List Char)
  /-- 13.2.5.43 Comment start state. -/
  | commentStart (d : List Char)
  /-- 13.2.5.44 Comment start dash state. -/
  | commentStartDash (d : List Char)
  /-- 13.2.5.45 Comment state. -/
  | comment (d : List Char)
  /-- 13.2.5.46 Comment less-than sign state. -/
  | commentLt (d : List Char)
  /-- 13.2.5.47 Comment less-than sign bang state. -/
  | commentLtBang (d : List Char)
  /-- 13.2.5.48 Comment less-than sign bang dash state. -/
  | commentLtBangDash (d : List Char)
  /-- 13.2.5.49 Comment less-than sign bang dash dash state. -/
  | commentLtBangDashDash (d : List Char)
  /-- 13.2.5.50 Comment end dash state. -/
  | commentEndDash (d : List Char)
  /-- 13.2.5.51 Comment end state. -/
  | commentEnd (d : List Char)
  /-- 13.2.5.52 Comment end bang state. -/
  | commentEndBang (d : List Char)
  /-- 13.2.5.53 DOCTYPE state. -/
  | doctype
  /-- 13.2.5.54 Before DOCTYPE name state. -/
  | beforeDoctypeName
  /-- 13.2.5.55 DOCTYPE name state. -/
  | doctypeName (n : List Char)
  /-- 13.2.5.56 After DOCTYPE name state (only its whitespace and `>` branches;
  `PUBLIC`, `SYSTEM` and the bogus DOCTYPE state are unmodeled). -/
  | afterDoctypeName (n : List Char)
  /-- Any state outside the modeled fragment (sticky). -/
  | out
  deriving Repr, DecidableEq, Inhabited

/-- The tokenizer state plus the name of the last start tag emitted (for the
"appropriate end tag token" test). -/
structure M where
  st : St
  lastStart : List Char
  deriving Repr, DecidableEq, Inhabited

/-- The tree builder's effect on the tokenizer: the state the tokenizer is in
after the tree builder handles a start tag with this (lowercase) name. -/
abbrev Tree := List Char → St

/-- The html5lib tokenizer tests run without a tree builder. -/
def noTree : Tree := fun _ => .data

/--
The tree-construction decisions the subset relies on (13.2.6.4.4 "in head",
13.2.6.4.7 "in body"): `script` switches to script data, `title`/`textarea` to
RCDATA, and `style xmp iframe noembed noframes noscript` to RAWTEXT ("generic
raw text element parsing algorithm"; `noscript` only because the model fixes
scripting mode to enabled: with scripting disabled, `noscript` content is markup).
`plaintext`, `svg`, `math`, `select`, `frameset` and `template` lead out of the
model: PLAINTEXT is unmodeled; foreign content changes how later start tags and
CDATA are handled; browsers that still have the classic "in select" mode ignore
some of the tags above inside `<select>` (the current spec parses `select` in
body); the frameset modes ignore `script`; and in a template whose content is a
column group (`<template><col>`), the "in column group" mode ignores every start
tag but `html`, `col` and `template` (13.2.6.4.12 "Anything else"), so `script`
or `textarea` there does not switch the tokenizer.

The stub sees only the tag name. It is exact for the start tags it does not send
to `out` as long as no `template` or `select` is open and nothing is foreign,
which `out` guarantees for a page that never reaches it.
-/
def treeStub : Tree := fun n =>
  if n = "script".toList then .raw .script
  else if n = "title".toList ∨ n = "textarea".toList then .raw .rcdata
  else if n = "style".toList ∨ n = "xmp".toList ∨ n = "iframe".toList ∨
      n = "noembed".toList ∨ n = "noframes".toList ∨ n = "noscript".toList then .raw .rawtext
  else if n = "plaintext".toList ∨ n = "svg".toList ∨ n = "math".toList ∨
      n = "select".toList ∨ n = "frameset".toList ∨ n = "template".toList then .out
  else .data

/-- The attributes of an emitted tag: in source order, first occurrence wins. -/
def finalAttrs (rev : List (List Char × List Char)) : List (List Char × List Char) :=
  rev.reverse.foldl (fun acc a => if acc.any (·.1 == a.1) then acc else acc ++ [a]) []

def Tag.appendName (t : Tag) (c : Char) : Tag := { t with name := t.name ++ [c] }

def Tag.newAttr (t : Tag) : Tag := { t with attrs := ([], []) :: t.attrs }

def Tag.appendAttrName (t : Tag) (c : Char) : Tag :=
  match t.attrs with
  | (n, v) :: r => { t with attrs := (n ++ [c], v) :: r }
  | [] => { t with attrs := [([c], [])] }

def Tag.appendAttrValue (t : Tag) (c : Char) : Tag :=
  match t.attrs with
  | (n, v) :: r => { t with attrs := (n, v ++ [c]) :: r }
  | [] => t

/-- One step's result: the next machine and the tokens emitted. -/
abbrev R := M × List Token

/-- Emit the current tag token. An end tag returns to the data state; a start
tag records `lastStart` and lets the tree builder pick the next state. -/
def emitTag (tr : Tree) (ls : List Char) (t : Tag) : R :=
  if t.isEnd then (⟨.data, ls⟩, [.endTag t.name])
  else (⟨tr t.name, t.name⟩, [.startTag t.name (finalAttrs t.attrs) t.selfClosing])

/-- The replacement character, for NUL in names, values and comments. -/
def fffd : Char := '�'

/-- 13.2.5.1 Data state. `&` is transparent (see the header). NUL is emitted as is. -/
def stepData (ls : List Char) (c : Char) : R :=
  if c = '<' then (⟨.tagOpen, ls⟩, [])
  else (⟨.data, ls⟩, [.char c])

/-- 13.2.5.2-4 RCDATA, RAWTEXT, script data. -/
def stepRaw (k : Raw) (ls : List Char) (c : Char) : R :=
  if c = '<' then (⟨.rawLt k, ls⟩, [])
  else if c = '\x00' then (⟨.raw k, ls⟩, [.char fffd])
  else (⟨.raw k, ls⟩, [.char c])

/-- 13.2.5.41 Bogus comment state. -/
def stepBogus (ls d : List Char) (c : Char) : R :=
  if c = '>' then (⟨.data, ls⟩, [.comment d])
  else if c = '\x00' then (⟨.bogusComment (d ++ [fffd]), ls⟩, [])
  else (⟨.bogusComment (d ++ [c]), ls⟩, [])

/-- 13.2.5.8 Tag name state. -/
def stepTagName (tr : Tree) (ls : List Char) (t : Tag) (c : Char) : R :=
  if isWs c then (⟨.beforeAttrName t, ls⟩, [])
  else if c = '/' then (⟨.selfClosing t, ls⟩, [])
  else if c = '>' then emitTag tr ls t
  else if c = '\x00' then (⟨.tagName (t.appendName fffd), ls⟩, [])
  else (⟨.tagName (t.appendName (lower c)), ls⟩, [])

/-- 13.2.5.33 Attribute name state. On whitespace, `/` and `>` the spec
reconsumes in the after attribute name state; the three branches below are that
state's transitions for those characters. -/
def stepAttrName (tr : Tree) (ls : List Char) (t : Tag) (c : Char) : R :=
  if isWs c then (⟨.afterAttrName t, ls⟩, [])
  else if c = '/' then (⟨.selfClosing t, ls⟩, [])
  else if c = '>' then emitTag tr ls t
  else if c = '=' then (⟨.beforeAttrValue t, ls⟩, [])
  else if c = '\x00' then (⟨.attrName (t.appendAttrName fffd), ls⟩, [])
  else (⟨.attrName (t.appendAttrName (lower c)), ls⟩, [])

/-- 13.2.5.32 Before attribute name state. `/` and `>` reconsume in the after
attribute name state, whose transitions for them are inlined. -/
def stepBeforeAttrName (tr : Tree) (ls : List Char) (t : Tag) (c : Char) : R :=
  if isWs c then (⟨.beforeAttrName t, ls⟩, [])
  else if c = '/' then (⟨.selfClosing t, ls⟩, [])
  else if c = '>' then emitTag tr ls t
  else if c = '=' then (⟨.attrName (t.newAttr.appendAttrName '='), ls⟩, [])
  else stepAttrName tr ls t.newAttr c

/-- 13.2.5.38 Attribute value (unquoted) state. -/
def stepAttrValueUnq (tr : Tree) (ls : List Char) (t : Tag) (c : Char) : R :=
  if isWs c then (⟨.beforeAttrName t, ls⟩, [])
  else if c = '>' then emitTag tr ls t
  else if c = '\x00' then (⟨.attrValueUnq (t.appendAttrValue fffd), ls⟩, [])
  else (⟨.attrValueUnq (t.appendAttrValue c), ls⟩, [])

/-- 13.2.5.45 Comment state. -/
def stepComment (ls d : List Char) (c : Char) : R :=
  if c = '<' then (⟨.commentLt (d ++ ['<']), ls⟩, [])
  else if c = '-' then (⟨.commentEndDash d, ls⟩, [])
  else if c = '\x00' then (⟨.comment (d ++ [fffd]), ls⟩, [])
  else (⟨.comment (d ++ [c]), ls⟩, [])

/-- 13.2.5.50 Comment end dash state. -/
def stepCommentEndDash (ls d : List Char) (c : Char) : R :=
  if c = '-' then (⟨.commentEnd d, ls⟩, [])
  else stepComment ls (d ++ ['-']) c

/-- 13.2.5.51 Comment end state. -/
def stepCommentEnd (ls d : List Char) (c : Char) : R :=
  if c = '>' then (⟨.data, ls⟩, [.comment d])
  else if c = '!' then (⟨.commentEndBang d, ls⟩, [])
  else if c = '-' then (⟨.commentEnd (d ++ ['-']), ls⟩, [])
  else stepComment ls (d ++ ['-', '-']) c

/-- 13.2.5.42 Markup declaration open state, one character at a time. -/
def stepMarkupDecl (ls buf : List Char) (c : Char) : R :=
  let b := buf ++ [c]
  if b = ['-', '-'] then (⟨.commentStart [], ls⟩, [])
  else if b = ['-'] then (⟨.markupDecl b, ls⟩, [])
  else if lowerStr b = "doctype".toList then (⟨.doctype, ls⟩, [])
  else if b.length < 7 ∧ lowerStr b = ("doctype".toList.take b.length) then (⟨.markupDecl b, ls⟩, [])
  -- No adjusted current node in a foreign namespace (the stub leaves the model
  -- at `svg`/`math`), so `[CDATA[` opens a bogus comment with that data.
  else if b = "[CDATA[".toList then (⟨.bogusComment b, ls⟩, [])
  else if b.length < 7 ∧ b = ("[CDATA[".toList.take b.length) then (⟨.markupDecl b, ls⟩, [])
  -- "Anything else": an empty comment, bogus comment state, nothing consumed.
  -- `buf` holds only prefix characters of the three keywords (never `>` or NUL),
  -- so the bogus comment state appends them; `c` is then processed there.
  else stepBogus ls buf c

/-- The appropriate end tag test (13.2.5.11): the temporary buffer, lowercased,
is the last start tag's name. -/
def appropriate (ls buf : List Char) : Bool := lowerStr buf == ls

/-- The end tag token built in an end tag name state. -/
def endTagOf (buf : List Char) : Tag := ⟨true, lowerStr buf, [], false⟩

/-- 13.2.5.11/14/17 RCDATA, RAWTEXT, script data end tag name state. -/
def stepRawEndTagName (tr : Tree) (k : Raw) (ls buf : List Char) (c : Char) : R :=
  if isWs c ∧ appropriate ls buf then (⟨.beforeAttrName (endTagOf buf), ls⟩, [])
  else if c = '/' ∧ appropriate ls buf then (⟨.selfClosing (endTagOf buf), ls⟩, [])
  else if c = '>' ∧ appropriate ls buf then emitTag tr ls (endTagOf buf)
  else if isAlpha c then (⟨.rawEndTagName k (buf ++ [c]), ls⟩, [])
  else
    let r := stepRaw k ls c
    (r.1, [.char '<', .char '/'] ++ buf.map .char ++ r.2)

/-- 13.2.5.54 Before DOCTYPE name state. -/
def stepBeforeDoctypeName (ls : List Char) (c : Char) : R :=
  if isWs c then (⟨.beforeDoctypeName, ls⟩, [])
  else if c = '\x00' then (⟨.doctypeName [fffd], ls⟩, [])
  else if c = '>' then (⟨.data, ls⟩, [.doctype none true])
  else (⟨.doctypeName [lower c], ls⟩, [])

/-- One tokenizer step. -/
def step (tr : Tree) (m : M) (c : Char) : R :=
  let ls := m.lastStart
  match m.st with
  | .data => stepData ls c
  | .raw k => stepRaw k ls c
  | .tagOpen =>
    if c = '!' then (⟨.markupDecl [], ls⟩, [])
    else if c = '/' then (⟨.endTagOpen, ls⟩, [])
    else if isAlpha c then (⟨.tagName ⟨false, [lower c], [], false⟩, ls⟩, [])
    -- `<?`: the processing instruction open state (13.2.5.72) in the current spec;
    -- older browsers open a bogus comment. Both are outside the model.
    else if c = '?' then (⟨.out, ls⟩, [])
    else
      let r := stepData ls c
      (r.1, .char '<' :: r.2)
  | .endTagOpen =>
    if isAlpha c then (⟨.tagName ⟨true, [lower c], [], false⟩, ls⟩, [])
    else if c = '>' then (⟨.data, ls⟩, [])
    else stepBogus ls [] c
  | .tagName t => stepTagName tr ls t c
  | .rawLt k =>
    if c = '/' then (⟨.rawEndTagOpen k, ls⟩, [])
    else if k = .script ∧ c = '!' then (⟨.scriptEscapeStart, ls⟩, [.char '<', .char '!'])
    else
      let r := stepRaw k ls c
      (r.1, .char '<' :: r.2)
  | .rawEndTagOpen k =>
    if isAlpha c then (⟨.rawEndTagName k [c], ls⟩, [])
    else
      let r := stepRaw k ls c
      (r.1, [.char '<', .char '/'] ++ r.2)
  | .rawEndTagName k buf => stepRawEndTagName tr k ls buf c
  | .scriptEscapeStart =>
    if c = '-' then (⟨.scriptEscapeStartDash, ls⟩, [.char '-'])
    else stepRaw .script ls c
  | .scriptEscapeStartDash =>
    -- `-` leads to 13.2.5.22 script data escaped dash dash state: unmodeled.
    if c = '-' then (⟨.out, ls⟩, [.char '-'])
    else stepRaw .script ls c
  | .beforeAttrName t => stepBeforeAttrName tr ls t c
  | .attrName t => stepAttrName tr ls t c
  | .afterAttrName t =>
    if isWs c then (⟨.afterAttrName t, ls⟩, [])
    else if c = '/' then (⟨.selfClosing t, ls⟩, [])
    else if c = '=' then (⟨.beforeAttrValue t, ls⟩, [])
    else if c = '>' then emitTag tr ls t
    else stepAttrName tr ls t.newAttr c
  | .beforeAttrValue t =>
    if isWs c then (⟨.beforeAttrValue t, ls⟩, [])
    else if c = '"' then (⟨.attrValueDQ t, ls⟩, [])
    else if c = '\'' then (⟨.attrValueSQ t, ls⟩, [])
    else if c = '>' then emitTag tr ls t
    else stepAttrValueUnq tr ls t c
  | .attrValueDQ t =>
    if c = '"' then (⟨.afterAttrValueQ t, ls⟩, [])
    else if c = '\x00' then (⟨.attrValueDQ (t.appendAttrValue fffd), ls⟩, [])
    else (⟨.attrValueDQ (t.appendAttrValue c), ls⟩, [])
  | .attrValueSQ t =>
    if c = '\'' then (⟨.afterAttrValueQ t, ls⟩, [])
    else if c = '\x00' then (⟨.attrValueSQ (t.appendAttrValue fffd), ls⟩, [])
    else (⟨.attrValueSQ (t.appendAttrValue c), ls⟩, [])
  | .attrValueUnq t => stepAttrValueUnq tr ls t c
  | .afterAttrValueQ t =>
    if isWs c then (⟨.beforeAttrName t, ls⟩, [])
    else if c = '/' then (⟨.selfClosing t, ls⟩, [])
    else if c = '>' then emitTag tr ls t
    else stepBeforeAttrName tr ls t c
  | .selfClosing t =>
    if c = '>' then emitTag tr ls { t with selfClosing := true }
    else stepBeforeAttrName tr ls t c
  | .bogusComment d => stepBogus ls d c
  | .markupDecl buf => stepMarkupDecl ls buf c
  | .commentStart d =>
    if c = '-' then (⟨.commentStartDash d, ls⟩, [])
    else if c = '>' then (⟨.data, ls⟩, [.comment d])
    else stepComment ls d c
  | .commentStartDash d =>
    if c = '-' then (⟨.commentEnd d, ls⟩, [])
    else if c = '>' then (⟨.data, ls⟩, [.comment d])
    else stepComment ls (d ++ ['-']) c
  | .comment d => stepComment ls d c
  | .commentLt d =>
    if c = '!' then (⟨.commentLtBang (d ++ ['!']), ls⟩, [])
    else if c = '<' then (⟨.commentLt (d ++ ['<']), ls⟩, [])
    else stepComment ls d c
  | .commentLtBang d =>
    if c = '-' then (⟨.commentLtBangDash d, ls⟩, [])
    else stepComment ls d c
  | .commentLtBangDash d =>
    if c = '-' then (⟨.commentLtBangDashDash d, ls⟩, [])
    else stepCommentEndDash ls d c
  | .commentLtBangDashDash d => stepCommentEnd ls d c
  | .commentEndDash d => stepCommentEndDash ls d c
  | .commentEnd d => stepCommentEnd ls d c
  | .commentEndBang d =>
    if c = '-' then (⟨.commentEndDash (d ++ ['-', '-', '!']), ls⟩, [])
    else if c = '>' then (⟨.data, ls⟩, [.comment d])
    else stepComment ls (d ++ ['-', '-', '!']) c
  | .doctype =>
    if isWs c then (⟨.beforeDoctypeName, ls⟩, [])
    else stepBeforeDoctypeName ls c
  | .beforeDoctypeName => stepBeforeDoctypeName ls c
  | .doctypeName n =>
    if isWs c then (⟨.afterDoctypeName n, ls⟩, [])
    else if c = '>' then (⟨.data, ls⟩, [.doctype (some n) false])
    else if c = '\x00' then (⟨.doctypeName (n ++ [fffd]), ls⟩, [])
    else (⟨.doctypeName (n ++ [lower c]), ls⟩, [])
  | .afterDoctypeName n =>
    if isWs c then (⟨.afterDoctypeName n, ls⟩, [])
    else if c = '>' then (⟨.data, ls⟩, [.doctype (some n) false])
    -- `PUBLIC`, `SYSTEM` or a bogus DOCTYPE: unmodeled.
    else (⟨.out, ls⟩, [])
  | .out => (⟨.out, ls⟩, [])

/-- The tokens emitted at end of input in each state (without the EOF token). -/
def eofTokens (m : M) : List Token :=
  match m.st with
  | .tagOpen => [.char '<']
  | .endTagOpen => [.char '<', .char '/']
  | .rawLt _ => [.char '<']
  | .rawEndTagOpen _ => [.char '<', .char '/']
  | .rawEndTagName _ buf => [.char '<', .char '/'] ++ buf.map .char
  | .markupDecl buf => [.comment buf]
  | .bogusComment d | .commentStart d | .commentStartDash d | .comment d
  | .commentLt d | .commentLtBang d | .commentLtBangDash d | .commentLtBangDashDash d
  | .commentEndDash d | .commentEnd d | .commentEndBang d => [.comment d]
  | .doctype | .beforeDoctypeName => [.doctype none true]
  | .doctypeName n | .afterDoctypeName n => [.doctype (some n) true]
  | _ => []

/-- Run the tokenizer over an input. -/
def run (tr : Tree) : M → List Char → R
  | m, [] => (m, [])
  | m, c :: cs =>
    let r1 := step tr m c
    let r2 := run tr r1.1 cs
    (r2.1, r1.2 ++ r2.2)

/-- All tokens for an input read to its end, starting from `m`. -/
def tokensFrom (tr : Tree) (m : M) (s : List Char) : List Token :=
  let r := run tr m s
  r.2 ++ eofTokens r.1

/-- The initial machine: data state, no start tag seen. -/
def init : M := ⟨.data, []⟩

/-- Tokenize a whole page. -/
def tokenize (tr : Tree) (s : List Char) : List Token := tokensFrom tr init s

/-- Whether the run ever reaches `out`. Once there it stays, so the final state suffices. -/
def leavesModel (tr : Tree) (m : M) (s : List Char) : Bool := (run tr m s).1.st == .out

theorem run_append (tr : Tree) (m : M) (a b : List Char) :
    run tr m (a ++ b) = ((run tr (run tr m a).1 b).1, (run tr m a).2 ++ (run tr (run tr m a).1 b).2) := by
  induction a generalizing m with
  | nil => simp [run]
  | cons c a ih => simp [run, ih, List.append_assoc]

theorem run_append_of {tr : Tree} {m m1 m2 : M} {a b : List Char} {t1 t2 : List Token}
    (h1 : run tr m a = (m1, t1)) (h2 : run tr m1 b = (m2, t2)) :
    run tr m (a ++ b) = (m2, t1 ++ t2) := by
  rw [run_append, h1, h2]

theorem tokensFrom_append (tr : Tree) (m : M) (a b : List Char) :
    tokensFrom tr m (a ++ b) = (run tr m a).2 ++ tokensFrom tr (run tr m a).1 b := by
  simp [tokensFrom, run_append, List.append_assoc]

theorem out_sticky (tr : Tree) (ls : List Char) (s : List Char) :
    run tr ⟨.out, ls⟩ s = (⟨.out, ls⟩, []) := by
  induction s with
  | nil => rfl
  | cons c s ih => simp [run, step, ih]

end TesseraT1.Whatwg
