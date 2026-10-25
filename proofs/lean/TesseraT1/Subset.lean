/-
The template profile (docs/security-architecture.md §5.2-5.3), slice 1: body text only.

A slice-1 template is element text in the HTML namespace with no tags,
comments or declarations: static spans and TEXT holes. Later slices add tags,
attributes, raw-text elements and foreign content.

Each predicate names the side condition it formalizes and the compiler check
that enforces it. The compiler-side facts ("an accepted template satisfies P")
are hypotheses (the rule contracts of the OpenJML proofs kept on a fork,
github.com/tcnghia/harder, branch proofs), not proved here.
-/
namespace TesseraT1

/-- ASCII letter, as the WHATWG tokenizer's "ASCII alpha". -/
def isAsciiAlpha (c : Char) : Bool :=
  ('a' ≤ c && c ≤ 'z') || ('A' ≤ c && c ≤ 'Z')

/-- A piece of a slice-1 template, in output order. -/
inductive Piece where
  /-- Static text, copied verbatim (a maximal run of a `Text` node's raw text). -/
  | static (s : List Char)
  /-- A `[[...]]` or `t:text` hole in element text (OutputContext.TEXT, `Escapers.text`). -/
  | holeText
  deriving Repr, DecidableEq

abbrev Template := List Piece

/--
The `<` at index `i` of static span `s` is plain text to a browser: it is the last
char of the span, or the next char is not an ASCII letter, `!`, `/` or `?` (the
tag-open state then emits `<` and reconsumes in the data state).
-/
def ltIsText (s : List Char) (i : Nat) : Prop :=
  match s[i + 1]? with
  | none => True
  | some c => isAsciiAlpha c = false ∧ c ≠ '!' ∧ c ≠ '/' ∧ c ≠ '?'

/--
Every `<` in a static span is text (slice 1's lexical side condition).

Enforced by the combination of:
* `MarkupTokenizer.kind` (main :138): `<` followed by an ASCII letter starts a start tag,
  `</` + letter an end tag, `<!--` a comment, `<?`/`<!` a declaration, so none of these
  stays in a text run;
* S1 (`HtmlParser.java:42`): a start tag whose name does not start with an ASCII letter
  is an error, so `<` + a digit or other name char never survives as text either;
* S6d (`HtmlParser.bogusComment`, fix-cdata-in-html :92): a text run with `<!`, `<?`, or
  `</` not followed by an ASCII letter or `>` is an error.
Rule-contract hypothesis: `accepted_staticText`.
-/
def StaticText (s : List Char) : Prop :=
  ∀ i, s[i]? = some '<' → ltIsText s i

/--
S12 restricted to slice 1: the static text right before a hole does not end with `<`.
Given `StaticText`, the other S12 endings (`<!`, `<!-`, `<?`, `</`+letters) cannot occur,
since their `<` would not be text.

Enforced by `TextInlining.markupBefore` (fix-hole-after-lt :132, applied at :68).
Rule-contract hypothesis: `accepted_noLtBeforeHole`.
-/
def NoLtBeforeHole (s : List Char) : Prop :=
  s.getLast? ≠ some '<'

/-- No two adjacent static pieces: a text node's static text between holes is one span. -/
def Normal : Template → Prop
  | [] => True
  | [_] => True
  | .static _ :: .static _ :: _ => False
  | _ :: p :: r => Normal (p :: r)

/-- Slice 1 membership: the template is in the safe subset. -/
def InSubset (T : Template) : Prop :=
  Normal T ∧
  (∀ s, Piece.static s ∈ T → StaticText s) ∧
  (∀ i s, T[i]? = some (.static s) → T[i + 1]? = some .holeText → NoLtBeforeHole s)

/-- Number of holes. -/
def holes : Template → Nat
  | [] => 0
  | .static _ :: r => holes r
  | .holeText :: r => holes r + 1

/--
The rendered output: static spans verbatim, each hole replaced by the next value.
A missing value renders as empty (the generated code writes nothing for `null`).
-/
def render : Template → List (List Char) → List Char
  | [], _ => []
  | .static s :: r, vs => s ++ render r vs
  | .holeText :: r, [] => render r []
  | .holeText :: r, v :: vs => v ++ render r vs

/-! ## Slice 2: tags and attribute holes

Slice 2 adds start and end tags. A start tag carries static attributes as written
(double-quoted, single-quoted or unquoted) and at most the attribute holes the
generator emits. The generator writes an attribute hole as ` name="` + E(value) + `"`,
always double-quoted, where E is the escaper for `Contexts.attribute(element, name)`
(`TemplateGenerator.dynamicAttr`, `TemplateGenerator.java:451`,`:474`). -/

/-- Output context of an attribute hole (docs/context-model.md, section 6.1). -/
inductive AttrCtx where
  | attribute | url | resourceUrl | refresh | scriptAttr | style
  deriving Repr, DecidableEq

/-- The `Escapers` method an attribute context uses; all attribute holes are double-quoted. -/
def AttrCtx.escaper : AttrCtx → String
  | .attribute => "attr"
  | .url => "url"
  | .resourceUrl => "resourceUrl"
  | .refresh => "refresh"
  | .scriptAttr => "jsonAttr"
  | .style => "attr"   -- a style attribute is attribute-escaped (TemplateGenerator.java:459)

/-- A static attribute as written in the source. -/
inductive StaticAttr where
  | dquote (name : List Char) (value : List Char)
  | squote (name : List Char) (value : List Char)
  | unquoted (name : List Char) (value : List Char)
  | bare (name : List Char)    -- value-less, e.g. `disabled`
  deriving Repr, DecidableEq

/-- One item inside a start tag: a static attribute, or an attribute hole. -/
inductive TagItem where
  | attr (a : StaticAttr)
  | hole (name : List Char) (ctx : AttrCtx)
  deriving Repr, DecidableEq

/-- Name of a tag item. -/
def TagItem.name : TagItem → List Char
  | .attr (.dquote n _) => n
  | .attr (.squote n _) => n
  | .attr (.unquoted n _) => n
  | .attr (.bare n) => n
  | .hole n _ => n

/-- An attribute/element name char per S1/S2: `[A-Za-z0-9_.:-]` (first char checked separately). -/
def isNameChar (c : Char) : Bool :=
  isAsciiAlpha c || ('0' ≤ c && c ≤ '9') || c = '_' || c = '.' || c = ':' || c = '-'

/-- S2 for an attribute-hole name: `[A-Za-z_][A-Za-z0-9_.:-]*` (else the context is FORBIDDEN). -/
def validHoleName : List Char → Bool
  | [] => false
  | c :: r => (isAsciiAlpha c || c = '_') && r.all isNameChar

/--
A start tag is well-formed for slice 2: every attribute-hole name is a valid plain
name (S2 / S16), and no two items share a name (S3, case-insensitive left to the
compiler; here exact). Static attribute values are checked by their own quoting rule
in the tokenizer (S5), modelled on the tag side in `Whatwg.lean`.
-/
def StartTagOK (items : List TagItem) : Prop :=
  (∀ it ∈ items, (∃ n ctx, it = .hole n ctx) → validHoleName it.name = true) ∧
  (items.map TagItem.name).Nodup

/-! ### Rendering tags (TemplateGenerator on origin/main) -/

/--
`Attribute.sourceValue()` after the name: `=` + quote + raw value + quote as written,
`=` + raw value when unquoted, nothing when value-less. Whitespace the author wrote
around `=` is not kept.
-/
def StaticAttr.render : StaticAttr → List Char
  | .dquote n v => n ++ ['=', '"'] ++ v ++ ['"']
  | .squote n v => n ++ ['=', '\''] ++ v ++ ['\'']
  | .unquoted n v => n ++ ['='] ++ v
  | .bare n => n

/-! ### Start tags as written in the source

The verbatim form of a start tag, as structure rather than bytes. The rule-contract
for `MarkupTokenizer.tagEnd` (C3) is what says Tessera's delimited source tag parses
into this structure; `SrcTagOK` is the side condition on it. -/

/-- ASCII whitespace as the WHATWG tokenizer reads it: TAB, LF, FF, CR, SPACE. -/
def isAsciiWs (c : Char) : Bool :=
  c = ' ' || c = '\t' || c = '\n' || c = '\x0C' || c = '\r'

/-- An attribute value as written. -/
inductive SrcValue where
  | dquote (v : List Char)
  | squote (v : List Char)
  | unquoted (v : List Char)
  deriving Repr, DecidableEq

def SrcValue.render : SrcValue → List Char
  | .dquote v => ['"'] ++ v ++ ['"']
  | .squote v => ['\''] ++ v ++ ['\'']
  | .unquoted v => v

def SrcValue.quoted : SrcValue → Bool
  | .unquoted _ => false
  | _ => true

/--
An attribute as written: its name and, if it has one, the whitespace before `=`,
the whitespace after it, and the value.
-/
structure SrcAttr where
  name : List Char
  value : Option (List Char × List Char × SrcValue)
  deriving Repr, DecidableEq

def SrcAttr.render (a : SrcAttr) : List Char :=
  a.name ++ match a.value with
    | none => []
    | some (w1, w2, v) => w1 ++ ['='] ++ w2 ++ v.render

/--
A start tag as written: name, each attribute with the whitespace before it, the
whitespace before the end, and whether it ends in `/>`.
-/
structure SrcTag where
  name : List Char
  items : List (List Char × SrcAttr)
  trailWs : List Char
  selfClosing : Bool
  deriving Repr, DecidableEq

def SrcTag.render (t : SrcTag) : List Char :=
  ['<'] ++ t.name ++ (t.items.flatMap fun (w, a) => w ++ a.render) ++ t.trailWs ++
    (if t.selfClosing then ['/', '>'] else ['>'])

/-- S1: `[A-Za-z][A-Za-z0-9_.:-]*` (`HtmlParser.java:42`). -/
def validElementName : List Char → Bool
  | [] => false
  | c :: r => isAsciiAlpha c && r.all isNameChar

/-- An attribute name the browser reads as one name (S2, `HtmlParser.java:284`). -/
def validSrcAttrName (n : List Char) : Bool :=
  !n.isEmpty && n.head? != some '=' &&
  n.all fun c => !isAsciiWs c && c != '/' && c != '>' && c != '=' && c != '"' && c != '\'' &&
    c != '<' && c != '\x00'

def SrcValue.ok : SrcValue → Bool
  | .dquote v => v.all (· != '"')
  | .squote v => v.all (· != '\'')
  | .unquoted v => !v.isEmpty &&
      v.all fun c => !isAsciiWs c && c != '>' && c != '"' && c != '\''

def SrcAttr.ok (a : SrcAttr) : Bool :=
  validSrcAttrName a.name && match a.value with
    | none => true
    | some (w1, w2, v) => w1.all isAsciiWs && w2.all isAsciiWs && v.ok

/-- The attribute ends in a quoted value, so the next one may follow without whitespace. -/
def SrcAttr.endsQuoted (a : SrcAttr) : Bool :=
  match a.value with
  | some (_, _, v) => v.quoted
  | none => false

/-- The attributes of a verbatim tag in order. `q`: the previous attribute ended in a
quoted value, so this one may follow without whitespace. -/
def SrcChain : Bool → List (List Char × SrcAttr) → Prop
  | _, [] => True
  | q, (w, a) :: r =>
    w.all isAsciiWs = true ∧ a.ok = true ∧ '\x00' ∉ a.render ∧ (w ≠ [] ∨ q = true) ∧
      SrcChain a.endsQuoted r

/--
The side condition on a verbatim start tag.
* Every separator is ASCII whitespace (G6; NOT ENFORCED until the tokenizer fix lands).
* An attribute is preceded by whitespace, except right after a quoted value (the
  browser starts a new attribute there too).
* Names follow S1/S2; quoted values contain no closing quote; unquoted values are
  non-empty with no whitespace, `>` or quote (S5).
* An unquoted value is not followed directly by `/>`: the browser would read the `/`
  as part of the value. Assumed, not enforced.
* No NUL anywhere in the tag (the browser turns it into U+FFFD). Assumed, not enforced.
-/
def SrcTagOK (t : SrcTag) : Prop :=
  validElementName t.name = true ∧
  t.trailWs.all isAsciiWs = true ∧
  SrcChain false t.items ∧
  (t.selfClosing = true → t.trailWs = [] →
    ∀ w a, t.items.getLast? = some (w, a) → a.endsQuoted = true ∨ a.value = none)

/--
A start tag as the generator sees it. `source` is the tag as written; `startTag` copies
it verbatim when the element has no `t:` attributes and its plan is unchanged
(`TemplateGenerator.java:404`). `none` means the tag is rebuilt.
`spaceBeforeSlash` is whether the source wrote whitespace before `/>`.
-/
structure StartTag where
  name : List Char
  items : List TagItem
  source : Option SrcTag
  selfClosing : Bool
  spaceBeforeSlash : Bool
  deriving Repr, DecidableEq

/--
`<!DOCTYPE html>` as written: `<!` + keyword + whitespace + name + whitespace + `>`.
The keyword is `DOCTYPE` and the name `html`, both ASCII case-insensitive (`DoctypeOK`).
Other DOCTYPEs that S6c lets through (PUBLIC/SYSTEM identifiers) are outside slice 2.
-/
structure Doctype where
  kw : List Char
  ws1 : List Char
  name : List Char
  ws2 : List Char
  deriving Repr, DecidableEq

def Doctype.render (d : Doctype) : List Char :=
  ['<', '!'] ++ d.kw ++ d.ws1 ++ d.name ++ d.ws2 ++ ['>']

/-- A slice-2 template piece. -/
inductive Piece2 where
  | static (s : List Char)
  | holeText
  | startTag (t : StartTag)
  /-- An end tag, always rebuilt as `</` + name + `>` (`TemplateGenerator.java:391`). -/
  | endTag (name : List Char)
  /-- A comment, copied verbatim as `<!--` + body + `-->`. Its side condition,
      `CommentBodyOK`, is in `Comment.lean`. -/
  | comment (b : List Char)
  /-- A DOCTYPE, copied verbatim. -/
  | doctype (d : Doctype)
  deriving Repr, DecidableEq

/-- A static attribute, as opposed to an attribute hole. -/
def TagItem.isAttr : TagItem → Bool
  | .attr _ => true
  | .hole _ _ => false

/--
One rebuilt tag item, consuming hole values in order. A hole whose value is `none`
(a `null` value) writes nothing (`dynamicAttr`, `:472`); a `scriptAttr` hole always
has a value, because `jsonAttr` writes `null` as JSON. Each hole writes exactly
` ` + name + `="` + value + `"` (`:474`).
-/
def renderItems : List TagItem → List (Option (List Char)) → List Char × List (Option (List Char))
  | [], vs => ([], vs)
  | .attr a :: r, vs =>
    let (o, vs') := renderItems r vs
    ([' '] ++ a.render ++ o, vs')
  | .hole _ _ :: r, [] =>
    renderItems r []
  | .hole n _ :: r, v :: vs =>
    let (o, vs') := renderItems r vs
    ((match v with
      | none => []
      | some x => [' '] ++ n ++ ['=', '"'] ++ x ++ ['"']) ++ o, vs')

/--
A start tag: verbatim if `source` is given and the tag has no holes, else rebuilt as
`<` + name + one space before each item + `>`, or `/>` (` />` if the source had a
space there) for a self-closing element (`TemplateGenerator.startTag`, `:401`–`:423`).
-/
def StartTag.render (t : StartTag) (vs : List (Option (List Char))) :
    List Char × List (Option (List Char)) :=
  match t.source, t.items.all TagItem.isAttr with
  | some src, true => (src.render, vs)
  | _, _ =>
    let (o, vs') := renderItems t.items vs
    let close := if t.selfClosing then (if t.spaceBeforeSlash then " />".toList else "/>".toList)
      else ['>']
    (['<'] ++ t.name ++ o ++ close, vs')

/-- Slice-2 render. Text holes and attribute holes take values from one list, in output order. -/
def render2 : List Piece2 → List (Option (List Char)) → List Char
  | [], _ => []
  | .static s :: r, vs => s ++ render2 r vs
  | .holeText :: r, [] => render2 r []
  | .holeText :: r, v :: vs => v.getD [] ++ render2 r vs
  | .startTag t :: r, vs =>
    let (o, vs') := t.render vs
    o ++ render2 r vs'
  | .endTag n :: r, vs => ['<', '/'] ++ n ++ ['>'] ++ render2 r vs
  | .comment b :: r, vs => "<!--".toList ++ b ++ "-->".toList ++ render2 r vs
  | .doctype d :: r, vs => d.render ++ render2 r vs
termination_by ps => ps.length

/-! ### Slice-2 membership -/

/-- ASCII lowercase, as the browser folds tag and attribute names. Lean's `Char.toLower`
changes only `A`–`Z`. -/
def asciiLowerChar (c : Char) : Char := c.toLower

def asciiLower (s : List Char) : List Char := s.map asciiLowerChar

/--
Elements that switch the tokenizer (raw text, RCDATA, script, PLAINTEXT), start
foreign content, or run their content in an insertion mode that ignores start tags.
Slice 2 excludes them; slice 3 adds a stateful tree stub for them.
-/
def slice3Names : List (List Char) :=
  ["script", "style", "title", "textarea", "xmp", "iframe", "noembed", "noframes", "noscript",
    "plaintext", "svg", "math", "select", "frameset", "template"].map String.toList

/-- A tag name inside slice 2's scope: S1, and not one of `slice3Names`. -/
def Slice2Name (n : List Char) : Prop :=
  validElementName n = true ∧ asciiLower n ∉ slice3Names

/-- A rebuilt static attribute follows the same rules as one written verbatim. -/
def StaticAttr.ok : StaticAttr → Bool
  | .dquote n v => validSrcAttrName n && (SrcValue.dquote v).ok && v.all (· != '\x00')
  | .squote n v => validSrcAttrName n && (SrcValue.squote v).ok && v.all (· != '\x00')
  | .unquoted n v => validSrcAttrName n && (SrcValue.unquoted v).ok && v.all (· != '\x00')
  | .bare n => validSrcAttrName n

/-- Whether `StartTag.render` rebuilds the tag (it copies it verbatim only with a source and no holes). -/
def StartTag.rebuilt (t : StartTag) : Bool :=
  match t.source, t.items.all TagItem.isAttr with
  | some _, true => false
  | _, _ => true

/--
The conditions on a start tag's attributes and verbatim form, independent of the tag
name. Shared by `StartTag2OK` (slice 2) and `ScriptOK` (slice 3, whose name is
`script`, outside `Slice2Name`).
* S2/S16 on hole names and S5/G8 on static names and values.
* S3: no two attributes with the same ASCII-lowercased name, in the rebuilt items and,
  for a verbatim tag, in the source items. The compiler compares `toLowerCase(Locale.ROOT)`
  names (`HtmlParser.java:288`); names equal after ASCII lowercasing are equal after that
  too, so an accepted tag satisfies this.
* A rebuilt tag with `/>` and no space has no unquoted static value (G8): a later
  attribute hole whose value is `null` writes nothing, so an unquoted value could end up
  directly before `/>`.
* A verbatim tag (`source = some src`, no holes): `SrcTagOK src`, its attribute names are
  `Nodup`, `src.name = t.name`, and `src.selfClosing = t.selfClosing`.
-/
def TagAttrsOK (t : StartTag) : Prop :=
  (∀ it ∈ t.items, match it with
    | .attr a => a.ok = true
    | .hole n _ => validHoleName n = true) ∧
  ((t.items.map fun it => asciiLower it.name).Nodup) ∧
  (t.rebuilt = true → t.selfClosing = true → t.spaceBeforeSlash = false →
    ∀ n v, TagItem.attr (.unquoted n v) ∉ t.items) ∧
  (∀ src, t.source = some src →
    SrcTagOK src ∧ (src.items.map fun x => asciiLower x.2.name).Nodup ∧
    src.name = t.name ∧ src.selfClosing = t.selfClosing)

/-- A start tag in slice 2: the slice-2 name scope, and the shared attribute conditions. -/
def StartTag2OK (t : StartTag) : Prop :=
  Slice2Name t.name ∧ TagAttrsOK t

/-- `<!DOCTYPE html>`, ASCII case-insensitive, with ASCII whitespace (S6c, G6). -/
def DoctypeOK (d : Doctype) : Prop :=
  asciiLower d.kw = "doctype".toList ∧ d.ws1 ≠ [] ∧ d.ws1.all isAsciiWs = true ∧
  asciiLower d.name = "html".toList ∧ d.ws2.all isAsciiWs = true

/--
What the compiler lets through for a comment body `b`, as Tessera reads
`"<!--" ++ b ++ "-->"`:
* S6a (`HtmlParser.earlyCommentEnd`, fix-comment-end `:149`): the comment does not
  start `<!-->` or `<!--->`, and has no `--!>` from index 4 on;
* `MarkupTokenizer.delimitedEnd(start, "<!---->", "--")`: the comment ends at the first
  `>` at index 6 or later that follows `--`, so no earlier one is in it;
* no NUL (assumed, G8: a browser turns NUL into U+FFFD).
Rule-contract hypotheses: C4 (`earlyCommentEnd`) and the comment case of C3.
The bridge `CommentTessera b → CommentBodyOK b` is proved where both are in scope.
-/
def CommentTessera (b : List Char) : Prop :=
  let full := "<!--".toList ++ b ++ "-->".toList
  b.take 1 ≠ ['>'] ∧ b.take 2 ≠ ['-', '>'] ∧
  (∀ k, 4 ≤ k → (full.drop k).take 4 ≠ "--!>".toList) ∧
  (∀ p, 6 ≤ p → p + 1 < full.length → full[p]? = some '>' →
    ¬ (full[p - 1]? = some '-' ∧ full[p - 2]? = some '-')) ∧
  '\x00' ∉ b

/-- One piece's own condition in slice 2. -/
def Piece2.ok : Piece2 → Prop
  | .static s => StaticText s
  | .holeText => True
  | .startTag t => StartTag2OK t
  | .endTag n => Slice2Name n
  | .comment b => CommentTessera b
  | .doctype d => DoctypeOK d

/-- No two adjacent static spans. -/
def Normal2 : List Piece2 → Prop
  | [] => True
  | [_] => True
  | .static _ :: .static _ :: _ => False
  | _ :: p :: r => Normal2 (p :: r)

/--
Slice-2 membership: no adjacent static spans, every piece satisfies its own
condition, and the static text right before a TEXT hole does not end in `<` (S12).
-/
def InSubset2 (T : List Piece2) : Prop :=
  Normal2 T ∧
  (∀ p ∈ T, p.ok) ∧
  (∀ i s, T[i]? = some (.static s) → T[i + 1]? = some .holeText → NoLtBeforeHole s)

/-! ## Slice 3: `<script>` elements with JSON holes

A JavaScript `<script>` holds script data, not markup. Its content is static text and
`[[...]]` holes written with `Escapers.json` (SCRIPT context). The tokenizer reads the
content as raw text up to the first `</script` followed by a delimiter; the compiler
must agree where that is (S8), must reject `<!--` (S6b), and must reject a hole right
after `<!-` (S12 SCRIPT case). -/

/-- A piece of `<script>` content. -/
inductive ScriptPart where
  | static (s : List Char)
  /-- A JSON hole (`Escapers.json`); its value satisfies `jsonScriptSafe`. -/
  | hole
  deriving Repr, DecidableEq

/-- The browser's script-data end-tag delimiters after `</script`. -/
def scriptEndDelim (c : Char) : Bool :=
  isAsciiWs c || c = '/' || c = '>'

/--
A prefix `Q` of script content ends where a browser leaves script data: `Q` ends with
`<!--` (S6b), or with `</` + a six-letter run ASCII-lowercasing to `script` + a
delimiter (S8, ASCII case). Mirrors the tokenizer model's `DangerEnd`, over the same
characters, without importing it.
-/
def ScriptDangerEnd (Q : List Char) : Prop :=
  (∃ P, Q = P ++ ['<', '!', '-', '-']) ∨
  (∃ P L d, Q = P ++ ['<', '/'] ++ L ++ [d] ∧ asciiLower L = "script".toList ∧ scriptEndDelim d = true)

/--
A static script part is safe: no NUL (assumption, G8), and no prefix ends a browser's
script data early (`ScriptDangerEnd`). So it contains no `<!--` (S6b) and no browser
script-data end tag (S8).
-/
def NoScriptDanger (s : List Char) : Prop :=
  '\x00' ∉ s ∧ ∀ i, ¬ ScriptDangerEnd (s.take i)

/-- S12 for SCRIPT: the static text before a hole does not end in `<!-` (it would make
`<!--` with a value). -/
def NoScriptLtBeforeHole (s : List Char) : Prop :=
  ¬ (s.getLast? = some '-' ∧ (s.dropLast).getLast? = some '!' ∧
     (s.dropLast.dropLast).getLast? = some '<')

/-- No two adjacent static parts (one text node between holes). -/
def NormalScript : List ScriptPart → Prop
  | [] => True
  | [_] => True
  | .static _ :: .static _ :: _ => False
  | _ :: p :: r => NormalScript (p :: r)

/-- A `<script>` element: a start tag and its content. -/
structure ScriptEl where
  tag : StartTag
  body : List ScriptPart
  deriving Repr, DecidableEq

/--
A `<script>` element is in the slice-3 subset:
* its tag's ASCII-lowercased name is `script` and it is not self-closing (S7);
* the tag's attributes satisfy the slice-2 rules (`StartTag2OK` minus the name scope,
  which `script` is outside of by design — a JS `<script>` is allowed);
* each static part is `NoScriptDanger`;
* a static part just before a hole is `NoScriptLtBeforeHole`;
* no two adjacent static parts.
-/
def ScriptOK (e : ScriptEl) : Prop :=
  asciiLower e.tag.name = "script".toList ∧ e.tag.selfClosing = false ∧
  TagAttrsOK e.tag ∧
  (∀ p ∈ e.body, match p with | .static s => NoScriptDanger s | .hole => True) ∧
  NormalScript e.body ∧
  (∀ i s, e.body[i]? = some (.static s) → e.body[i+1]? = some .hole → NoScriptLtBeforeHole s)

/-- Render a script body: static parts verbatim, each hole a `jsonScriptSafe` value. -/
def renderScript : List ScriptPart → List (Option (List Char)) → List Char × List (Option (List Char))
  | [], vs => ([], vs)
  | .static s :: r, vs =>
    let (o, vs') := renderScript r vs
    (s ++ o, vs')
  | .hole :: r, [] =>
    renderScript r []
  | .hole :: r, v :: vs =>
    let (o, vs') := renderScript r vs
    (v.getD [] ++ o, vs')

/-- Render a `<script>` element: the start tag, the body, and the rebuilt end tag. -/
def ScriptEl.render (e : ScriptEl) (vs : List (Option (List Char))) :
    List Char × List (Option (List Char)) :=
  let (tagOut, vs1) := e.tag.render vs
  let (bodyOut, vs2) := renderScript e.body vs1
  (tagOut ++ bodyOut ++ ['<', '/'] ++ e.tag.name ++ ['>'], vs2)

/-! ## Slice 4: RCDATA and RAWTEXT elements (docs/security-architecture.md §5.3)

`title` and `textarea` hold RCDATA: static text and `[[...]]`/`t:text` holes written with
`Escapers.rcdata` (OutputContext.RCDATA, postcondition `htmlSafe`). `style` holds RAWTEXT
with no holes (S16: a hole in `<style>` content is a build error). `iframe` is RAWTEXT in
the browser and must be empty (§5.3). `xmp`, `plaintext`, `noembed`, `noframes`, `frameset`
and `frame` are not pieces: the profile rejects them. `noscript` is slice 4b. -/

/-- A raw-text element: its start tag and its body, static text and holes (as `ScriptPart`;
every hole here is an RCDATA hole). The end tag is rebuilt as `</` + name + `>`. -/
structure RawEl where
  tag : StartTag
  body : List ScriptPart
  deriving Repr, DecidableEq

/-- The RCDATA elements of the profile (§5.3), lowercased. -/
def rcdataNames : List (List Char) := ["title", "textarea"].map String.toList

/--
A prefix `Q` of raw-text content ends where a browser leaves RCDATA/RAWTEXT: `</` + a run
ASCII-lowercasing to the element's lowercased name `n` + whitespace, `/` or `>` (S8,
`HtmlParser.browserEndTag`, which folds Unicode case and so only ever ends earlier, G9).
-/
def RawDangerEnd (n Q : List Char) : Prop :=
  ∃ P L d, Q = P ++ ['<', '/'] ++ L ++ [d] ∧ asciiLower L = n ∧ scriptEndDelim d = true

/-- Static raw-text content is safe: no NUL (assumption, G8) and no browser end tag inside
(S8: `HtmlParser.endTag` reports `</name` before the element's end tag). -/
def NoRawDanger (n s : List Char) : Prop :=
  '\x00' ∉ s ∧ ∀ i, ¬ RawDangerEnd n (s.take i)

/-- S12 for RCDATA (`TextInlining.markupBefore`, origin/main): the static text before a hole
does not end in `<`, nor in `</` followed by letters only, so no value can complete a tag. -/
def NoRawLtBeforeHole (s : List Char) : Prop :=
  (∀ P', s ≠ P' ++ ['<']) ∧
  (∀ P' buf, (∀ c ∈ buf, isAsciiAlpha c = true) → s ≠ P' ++ ['<', '/'] ++ buf)

/-- The content model of a raw-text element, by its ASCII-lowercased name (§5.3): RCDATA
with holes for `title`/`textarea`; RAWTEXT without holes for `style`; empty for `iframe`. -/
def RawKindOK (n : List Char) (body : List ScriptPart) : Prop :=
  n ∈ rcdataNames ∨
  (n = "style".toList ∧ ∀ p ∈ body, p ≠ .hole) ∨
  (n = "iframe".toList ∧ body = [])

/--
A raw-text element is in the profile:
* its name is one of the four (`RawKindOK`), with that content model;
* it is not self-closing (§5.2: no `/>` on a non-void element; S9b);
* its attributes satisfy the slice-2 rules;
* each static part has no NUL and no browser end tag (S8);
* a static part just before a hole is `NoRawLtBeforeHole` (S12);
* no two adjacent static parts.
-/
def RawOK (e : RawEl) : Prop :=
  RawKindOK (asciiLower e.tag.name) e.body ∧ e.tag.selfClosing = false ∧
  TagAttrsOK e.tag ∧
  (∀ p ∈ e.body, match p with
    | .static s => NoRawDanger (asciiLower e.tag.name) s
    | .hole => True) ∧
  NormalScript e.body ∧
  (∀ i s, e.body[i]? = some (.static s) → e.body[i+1]? = some .hole → NoRawLtBeforeHole s)

/-- Render a raw-text element: the start tag, the body, and the rebuilt end tag. -/
def RawEl.render (e : RawEl) (vs : List (Option (List Char))) :
    List Char × List (Option (List Char)) :=
  let (tagOut, vs1) := e.tag.render vs
  let (bodyOut, vs2) := renderScript e.body vs1
  (tagOut ++ bodyOut ++ ['<', '/'] ++ e.tag.name ++ ['>'], vs2)

/-! ## Slice 5: `noscript` (docs/security-architecture.md §5.3)

With scripting enabled a browser reads `noscript` content as RAWTEXT up to `</noscript`
+ delimiter. Tessera parses it as markup. The profile keeps both readings consistent by
allowing inside `noscript` only text and comments without `<`, TEXT holes, and tags whose
static attribute values have no `<`, so that no `</noscript` can appear before the end
tag. CHOICE: §5.3 forbids the literal `</noscript`; forbidding every `<` is simpler. -/

/-- No `<` in a static attribute value. -/
def StaticAttr.noLt : StaticAttr → Prop
  | .dquote _ v => '<' ∉ v
  | .squote _ v => '<' ∉ v
  | .unquoted _ v => '<' ∉ v
  | .bare _ => True

def SrcAttr.noLt (a : SrcAttr) : Prop :=
  match a.value with
  | none => True
  | some (_, _, v) => '<' ∉ v.render

/-- A start tag inside `noscript`: a valid name, the slice-2 attribute rules, and no `<`
in a static attribute value. -/
def NoscriptTagOK (t : StartTag) : Prop :=
  validElementName t.name = true ∧ TagAttrsOK t ∧
  (∀ it ∈ t.items, match it with | .attr a => a.noLt | .hole _ _ => True) ∧
  (∀ src, t.source = some src → ∀ x ∈ src.items, x.2.noLt)

end TesseraT1
