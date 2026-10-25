/-
The Tessera HTML profile (docs/security-architecture.md §5.2-5.5) as a predicate on a
template tree, and the §5.1 invariant as the statement T1 targets.

The template model here is the compiler's: a tree of elements, static text, holes,
comments and a DOCTYPE, each hole carrying the output context the compiler chose. The
tables of §5.2 (token-level rules) and §5.3 (parse modes and allow-lists) are written
as one predicate per table row so a reviewer can line them up; §5.4 (scripts) reuses
`ScriptOK`; §5.5 (content kinds) is the mode a hole or fragment is written for.

Where the doc leaves a choice, the choice is marked `-- CHOICE` and listed in
proofs/lean/README.md as a question for upstream.
-/
import TesseraT1.Subset
import TesseraT1.RawBridge
import TesseraT1.T1Slice4

namespace TesseraT1

/-- The parse modes of §5.3. `RAWTEXT(script|style)` and `RCDATA(title|textarea)` are
the content of those elements; the profile never nests an element in them. -/
inductive Mode where
  | html | svg | math | select | noscript
  deriving Repr, DecidableEq

/-- The output context the compiler chose for a hole in element text (§5.5: the content
kind of the place it is written for). -/
inductive HoleCtx where
  /-- `Escapers.text` (OutputContext.TEXT): element text in HTML, SVG and MathML. -/
  | text
  /-- `Escapers.rcdata` (OutputContext.RCDATA): `title` and `textarea` content. -/
  | rcdata
  /-- `Escapers.json` (OutputContext.SCRIPT): a JSON value in a `<script>`. -/
  | script
  deriving Repr, DecidableEq

mutual
/-- A node of the template tree, in output order. -/
inductive Node where
  /-- Static text as written. -/
  | text (s : List Char)
  /-- A `[[...]]` or `t:text` hole. -/
  | hole (ctx : HoleCtx)
  | comment (b : List Char)
  | doctype (d : Doctype)
  /-- A start tag with no content and no end tag: a void HTML element, or a self-closing
  foreign element. -/
  | void (tag : StartTag)
  /-- An element with content; the end tag is rebuilt as `</` + name + `>`. -/
  | element (tag : StartTag) (children : Forest)
inductive Forest where
  | nil
  | cons (n : Node) (r : Forest)
end

/-! ## §5.3 allow-lists

HTML: the elements of the WHATWG HTML element index, minus the ones §5.3 rejects
(`xmp`, `plaintext`, `noembed`, `noframes`, `frameset`, `frame`). CHOICE: obsolete
elements browsers still parse (`center`, `font`, `tt`, `big`, `strike`, `marquee`, ...)
are left out; `font` is also an SVG breakout tag. CHOICE: `template` is left out too:
its content runs in insertion modes that can ignore a raw-text start tag
(`<template><col><textarea>`, README "Findings"), and §5.3 gives no rule for it. SVG: the SVG 2 element index. MathML:
MathML Core. None of the SVG or MathML names is a breakout tag (13.2.6.5), which is what
lets foreign content be allow-listed "by construction". -/

def htmlNames : List String :=
  ["a", "abbr", "address", "area", "article", "aside", "audio", "b", "base", "bdi", "bdo",
   "blockquote", "body", "br", "button", "canvas", "caption", "cite", "code", "col", "colgroup",
   "data", "datalist", "dd", "del", "details", "dfn", "dialog", "div", "dl", "dt", "em", "embed",
   "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6",
   "head", "header", "hgroup", "hr", "html", "i", "iframe", "img", "input", "ins", "kbd", "label",
   "legend", "li", "link", "main", "map", "mark", "menu", "meta", "meter", "nav", "noscript",
   "object", "ol", "optgroup", "option", "output", "p", "picture", "pre", "progress", "q", "rp",
   "rt", "ruby", "s", "samp", "script", "search", "section", "select", "slot", "small", "source",
   "span", "strong", "style", "sub", "summary", "sup", "table", "tbody", "td",
   "textarea", "tfoot", "th", "thead", "time", "title", "tr", "track", "u", "ul", "var", "video",
   "wbr"]

/-- Void elements (13.1.2): no content, no end tag; `/>` is allowed on them. -/
def voidNames : List String :=
  ["area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track",
   "wbr"]

def svgNames : List String :=
  ["a", "animate", "animateMotion", "animateTransform", "circle", "clipPath", "defs", "desc",
   "discard", "ellipse", "feBlend", "feColorMatrix", "feComponentTransfer", "feComposite",
   "feConvolveMatrix", "feDiffuseLighting", "feDisplacementMap", "feDistantLight", "feDropShadow",
   "feFlood", "feFuncA", "feFuncB", "feFuncG", "feFuncR", "feGaussianBlur", "feImage", "feMerge",
   "feMergeNode", "feMorphology", "feOffset", "fePointLight", "feSpecularLighting", "feSpotLight",
   "feTile", "feTurbulence", "filter", "foreignObject", "g", "image", "line", "linearGradient",
   "marker", "mask", "metadata", "mpath", "path", "pattern", "polygon", "polyline",
   "radialGradient", "rect", "script", "set", "stop", "style", "svg", "switch", "symbol", "text",
   "textPath", "title", "tspan", "use", "view"]

def mathNames : List String :=
  ["math", "maction", "annotation", "annotation-xml", "menclose", "merror", "mfrac", "mi",
   "mmultiscripts", "mn", "mo", "mover", "mpadded", "mphantom", "mprescripts", "mroot", "mrow",
   "ms", "mspace", "msqrt", "mstyle", "msub", "msubsup", "msup", "mtable", "mtd", "mtext", "mtr",
   "munder", "munderover", "semantics"]

/-- The HTML breakout start tags of 13.2.6.5 ("in foreign content"), plus `font` (which
breaks out with a `color`, `face` or `size` attribute). Not part of the profile; used to
state that the allow-lists exclude them. -/
def breakoutNames : List String :=
  ["b", "big", "blockquote", "body", "br", "center", "code", "dd", "div", "dl", "dt", "em",
   "embed", "h1", "h2", "h3", "h4", "h5", "h6", "head", "hr", "i", "img", "li", "listing", "menu",
   "meta", "nobr", "ol", "p", "pre", "ruby", "s", "small", "span", "strong", "strike", "sub", "sup",
   "table", "tt", "u", "ul", "var", "font"]

theorem svg_no_breakout : svgNames.all (fun n => !breakoutNames.contains n) = true := by decide
theorem math_no_breakout : mathNames.all (fun n => !breakoutNames.contains n) = true := by decide

/-- Names are compared ASCII-lowercased: the tokenizer lowercases every tag name, and the
tree builder restores the SVG spelling from a fixed table (`foreignobject` ->
`foreignObject`, 13.2.6.5 "adjust SVG tag names"). §5.2: names are ASCII (`validElementName`,
S1); a custom element is a name with a `-`. -/
def HtmlName (n : List Char) : Prop :=
  validElementName n = true ∧
  (asciiLower n ∈ htmlNames.map String.toList ∨ '-' ∈ n)

def lowerNames (l : List String) : List (List Char) := l.map fun s => asciiLower s.toList

def SvgName (n : List Char) : Prop := validElementName n = true ∧ asciiLower n ∈ lowerNames svgNames
def MathName (n : List Char) : Prop := validElementName n = true ∧ asciiLower n ∈ lowerNames mathNames
def IsVoid (n : List Char) : Prop := asciiLower n ∈ voidNames.map String.toList

/-! ## §5.2 token-level rules -/

/-- §5.2 "Text": characters other than `<` (a literal `<` is an error: write `&lt;`), and no
NUL. CHOICE: "`&` only as a complete character reference" is not modeled: character
references are transparent to the tokenizer model (README), and an incomplete `&name`
before a hole is #303's rule. -/
def TextOK (s : List Char) : Prop := '<' ∉ s ∧ '\x00' ∉ s

/-- §5.2 "Start tag" and "Holes": the attribute rules of slice 2 (`TagAttrsOK`: names,
quoting, no duplicates, holes only as whole attribute values). -/
def TagOK (t : StartTag) : Prop := TagAttrsOK t

/-- §5.2 "Comment": `CommentTessera` (no `<!-->`, `<!--->`, `--!>`, or an earlier `-->`). -/
def CommentOK (b : List Char) : Prop := CommentTessera b

/-! ## §5.3 tree-level rules -/

def rawNames : List (List Char) := ["title", "textarea", "style", "iframe"].map String.toList

/-- Content of an RCDATA/RAWTEXT element as body parts: text and RCDATA holes only. -/
def rawBody : Forest → Option (List ScriptPart)
  | .nil => some []
  | .cons (.text s) r => (rawBody r).map (.static s :: ·)
  | .cons (.hole .rcdata) r => (rawBody r).map (.hole :: ·)
  | .cons _ _ => none

/-- Content of a `<script>` as body parts: text and JSON holes only. -/
def scriptBody : Forest → Option (List ScriptPart)
  | .nil => some []
  | .cons (.text s) r => (scriptBody r).map (.static s :: ·)
  | .cons (.hole .script) r => (scriptBody r).map (.hole :: ·)
  | .cons _ _ => none

/-- §5.3 "Raw text and RCDATA" for an element whose lowercased name is in `rawNames`:
`RawOK` (the content model per name, S8 end-tag rule, S12, no `/>`). CHOICE: §5.3 gives
no token rules for RCDATA/RAWTEXT content; S8 and S12 are what the proof needs. -/
def RawElOK (t : StartTag) (ch : Forest) : Prop :=
  ∃ body, rawBody ch = some body ∧ RawOK ⟨t, body⟩

/-- §5.4 for a `<script>`: slice 3's `ScriptOK` (JSON holes, no `<!--`, S8, S12).
CHOICE: the data-prelude rule (holes in expression position) is a JavaScript-level
condition that the HTML tokenizer proof does not see; it is not modeled. -/
def ScriptElOK (t : StartTag) (ch : Forest) : Prop :=
  ∃ body, scriptBody ch = some body ∧ ScriptOK ⟨t, body⟩

/-- Text nodes are maximal: the parser merges adjacent text (`HtmlParser.flushText`). -/
def NotTwoTexts : Node → Forest → Prop
  | .text _, .cons (.text _) _ => False
  | _, _ => True

/-- A text node or a TEXT hole. -/
def TextNode : Node → Prop
  | .text s => TextOK s
  | .hole .text => True
  | _ => False

/-- Text-and-holes content (integration points, `option`, SVG `title`/`desc`). -/
def TextOnly : Forest → Prop
  | .nil => True
  | .cons n r => TextNode n ∧ NotTwoTexts n r ∧ TextOnly r

/-- No `optgroup` element among a forest's nodes. -/
def NoOptgroup : Forest → Prop
  | .nil => True
  | .cons (.element t _) r => asciiLower t.name ≠ "optgroup".toList ∧ NoOptgroup r
  | .cons _ r => NoOptgroup r

/-- A static text node with no `<` or `&`. -/
def StaticNode : Node → Prop
  | .text s => TextOK s ∧ '&' ∉ s
  | _ => False

/-- Static text only, with no `<` or `&` (§5.3: SVG `script` and `style`). -/
def StaticNoAmp : Forest → Prop
  | .nil => True
  | .cons n r => StaticNode n ∧ NotTwoTexts n r ∧ StaticNoAmp r

mutual
/--
The profile, by parse mode. One clause per §5.3 bullet.
-/
def NodeOK : Mode → Node → Prop
  | _, .text s => TextOK s
  | .html, .hole ctx => ctx = .text
  | .svg, .hole ctx => ctx = .text
  | .math, .hole ctx => ctx = .text
  | .select, .hole ctx => ctx = .text
  | .noscript, .hole ctx => ctx = .text
  | .noscript, .comment b => CommentOK b ∧ '<' ∉ b
  | _, .comment b => CommentOK b
  -- §5.2: the DOCTYPE is the first thing only (`InProfile`); a browser ignores a later one.
  | _, .doctype _ => False
  -- HTML mode: the allow-list plus custom elements; void elements have no content.
  | .html, .void t => TagOK t ∧ IsVoid t.name ∧ HtmlName t.name
  | .html, .element t ch =>
      TagOK t ∧ t.selfClosing = false ∧ HtmlName t.name ∧ ¬ IsVoid t.name ∧
      (if asciiLower t.name = "script".toList then ScriptElOK t ch
       else if asciiLower t.name ∈ rawNames then RawElOK t ch
       else if asciiLower t.name = "svg".toList then ForestOK .svg ch
       else if asciiLower t.name = "math".toList then ForestOK .math ch
       else if asciiLower t.name = "select".toList then ForestOK .select ch
       else if asciiLower t.name = "noscript".toList then ForestOK .noscript ch
       else ForestOK .html ch)
  -- Foreign content: SVG names only; integration points hold text and holes;
  -- `script`/`style` hold static text without `<` or `&`; `foreignObject` holds HTML.
  | .svg, .void t => TagOK t ∧ t.selfClosing = true ∧ SvgName t.name
  | .svg, .element t ch =>
      TagOK t ∧ t.selfClosing = false ∧ SvgName t.name ∧
      (if asciiLower t.name = "title".toList ∨ asciiLower t.name = "desc".toList then TextOnly ch
       else if asciiLower t.name = "script".toList ∨ asciiLower t.name = "style".toList then
         StaticNoAmp ch
       else if asciiLower t.name = "foreignobject".toList then ForestOK .html ch
       else ForestOK .svg ch)
  | .math, .void t => TagOK t ∧ t.selfClosing = true ∧ MathName t.name
  | .math, .element t ch =>
      TagOK t ∧ t.selfClosing = false ∧ MathName t.name ∧
      (if asciiLower t.name ∈ ["mi", "mo", "mn", "ms", "mtext"].map String.toList then TextOnly ch
       -- CHOICE: `annotation-xml` is an HTML integration point only with
       -- `encoding="text/html"` or `"application/xhtml+xml"`; the profile takes it as
       -- MathML content and leaves HTML inside it out.
       else ForestOK .math ch)
  -- `select`: only `option`, `optgroup`, `hr` and text (§5.3), the subset on which classic
  -- and new select parsing agree.
  | .select, .void t => TagOK t ∧ HtmlName t.name ∧ asciiLower t.name = "hr".toList
  | .select, .element t ch =>
      TagOK t ∧ t.selfClosing = false ∧ HtmlName t.name ∧
      ((asciiLower t.name = "option".toList ∧ TextOnly ch) ∨
       -- §5.1: an `optgroup` start tag closes an open `optgroup`, so none is nested.
       (asciiLower t.name = "optgroup".toList ∧ ForestOK .select ch ∧ NoOptgroup ch))
  -- `noscript`: markup children under the HTML rules, but no RCDATA/raw-text/script
  -- element (§5.3), and no `<` in static attribute values or comments (`NoscriptTagOK`),
  -- so no `</noscript` can appear before the end tag.
  | .noscript, .void t => NoscriptTagOK t ∧ IsVoid t.name ∧ HtmlName t.name
  | .noscript, .element t ch =>
      NoscriptTagOK t ∧ t.selfClosing = false ∧ HtmlName t.name ∧ ¬ IsVoid t.name ∧
      ((asciiLower t.name ∉ "script".toList :: "noscript".toList :: rawNames ∧
        ForestOK .noscript ch) ∨
       -- GTM's `<noscript><iframe ...></iframe>`: an empty iframe is safe in both readings.
       (asciiLower t.name = "iframe".toList ∧ ch = .nil))
def ForestOK : Mode → Forest → Prop
  | _, .nil => True
  | m, .cons n r => NodeOK m n ∧ NotTwoTexts n r ∧ ForestOK m r
end

/-- A whole template: an optional `<!DOCTYPE html>` first (§5.2), then a forest in HTML mode. -/
def InProfile : Forest → Prop
  | .cons (.doctype d) r => DoctypeOK d ∧ ForestOK .html r
  | T => ForestOK .html T

/-! ## What is proved against this statement

`Html4` marks the fragment of the profile that slices 1-4 cover: HTML mode with raw-text
and script elements, no `svg`, `math`, `select`, `noscript` or `template` element
(`template` content runs in insertion modes that can ignore a raw-text start tag,
README "Findings"). `flatten` maps it to slice-4 pieces; `t1_profile` is T1 for it. -/

def excludedNames : List (List Char) := ["svg", "math", "select", "noscript", "template"].map String.toList

mutual
def Html4 : Node → Prop
  | .element t ch => asciiLower t.name ∉ excludedNames ∧ Html4F ch
  | _ => True
def Html4F : Forest → Prop
  | .nil => True
  | .cons n r => Html4 n ∧ Html4F r
end

mutual
def flatten : Node → List Piece4
  | .text s => [.static s]
  | .hole _ => [.holeText]
  | .comment b => [.comment b]
  | .doctype d => [.doctype d]
  | .void t => [.startTag t]
  | .element t ch =>
      if asciiLower t.name = "script".toList then [.script ⟨t, (scriptBody ch).getD []⟩]
      else if asciiLower t.name ∈ rawNames then [.raw ⟨t, (rawBody ch).getD []⟩]
      else .startTag t :: flattenF ch ++ [.endTag t.name]
def flattenF : Forest → List Piece4
  | .nil => []
  | .cons n r => flatten n ++ flattenF r
end

/-! ## The profile fragment of slices 1-4 is in the slice-4 subset -/

theorem textOK_static {s : List Char} (h : TextOK s) : StaticText s :=
  fun _ hi => absurd (List.mem_of_getElem? hi) h.1

theorem textOK_last {s : List Char} (h : TextOK s) : s.getLast? ≠ some '<' :=
  fun hl => h.1 (List.mem_of_getLast? hl)

/-- `Ok4` is closed under concatenation when no static span of the first part ends in `<`. -/
theorem ok4_append : ∀ (a b : List Piece4), Ok4 a → Ok4 b →
    (∀ s, Piece4.static s ∈ a → s.getLast? ≠ some '<') → Ok4 (a ++ b)
  | [], _, _, hb, _ => hb
  | .static s :: r, b, ⟨hs, _, hr⟩, hb, hl =>
    ⟨hs, fun h => absurd h (hl s (by simp)),
      ok4_append r b hr hb (fun s' h' => hl s' (List.mem_cons_of_mem _ h'))⟩
  | .holeText :: r, b, hr, hb, hl => ok4_append r b hr hb (fun s' h' => hl s' (List.mem_cons_of_mem _ h'))
  | .startTag _ :: r, b, ⟨ht, hr⟩, hb, hl =>
    ⟨ht, ok4_append r b hr hb (fun s' h' => hl s' (List.mem_cons_of_mem _ h'))⟩
  | .endTag _ :: r, b, ⟨ht, hr⟩, hb, hl =>
    ⟨ht, ok4_append r b hr hb (fun s' h' => hl s' (List.mem_cons_of_mem _ h'))⟩
  | .comment _ :: r, b, ⟨ht, hr⟩, hb, hl =>
    ⟨ht, ok4_append r b hr hb (fun s' h' => hl s' (List.mem_cons_of_mem _ h'))⟩
  | .doctype _ :: r, b, ⟨ht, hr⟩, hb, hl =>
    ⟨ht, ok4_append r b hr hb (fun s' h' => hl s' (List.mem_cons_of_mem _ h'))⟩
  | .script _ :: r, b, ⟨ht, hr⟩, hb, hl =>
    ⟨ht, ok4_append r b hr hb (fun s' h' => hl s' (List.mem_cons_of_mem _ h'))⟩
  | .raw _ :: r, b, ⟨ht, hr⟩, hb, hl =>
    ⟨ht, ok4_append r b hr hb (fun s' h' => hl s' (List.mem_cons_of_mem _ h'))⟩

/-- Every allow-listed HTML name is outside `slice3Names`, is `script`, is a raw-text
name, or is one of the names `Html4` excludes. -/
theorem html_names_scope : (htmlNames.map String.toList).all (fun n =>
    !slice3Names.contains n || n == "script".toList || rawNames.contains n ||
      excludedNames.contains n) = true := by decide

theorem void_names_scope : (voidNames.map String.toList).all (fun n => !slice3Names.contains n) = true := by
  decide

theorem slice3_no_dash : slice3Names.all (fun n => !n.contains '-') = true := by decide

theorem dash_lower {n : List Char} (h : '-' ∈ n) : '-' ∈ asciiLower n := by
  have : asciiLowerChar '-' = '-' := rfl
  exact this ▸ List.mem_map_of_mem h

/-- The name of an ordinary HTML element (not script, raw text, or excluded) is in slice 2's scope. -/
theorem slice2Name_of_html {n : List Char} (h : HtmlName n) (hs : asciiLower n ≠ "script".toList)
    (hr : asciiLower n ∉ rawNames) (hx : asciiLower n ∉ excludedNames) : Slice2Name n := by
  refine ⟨h.1, ?_⟩
  rcases h.2 with hm | hd
  · have := List.all_eq_true.mp html_names_scope _ hm
    simp only [Bool.or_eq_true, Bool.not_eq_true', List.contains_eq_mem, beq_iff_eq,
      decide_eq_false_iff_not, decide_eq_true_eq] at this
    rcases this with ((hn | hn) | hn) | hn
    · exact hn
    · exact absurd hn hs
    · exact absurd hn hr
    · exact absurd hn hx
  · intro hm
    have := List.all_eq_true.mp slice3_no_dash _ hm
    simp only [Bool.not_eq_true', List.contains_eq_mem, decide_eq_false_iff_not] at this
    exact this (dash_lower hd)

theorem slice2Name_of_void {n : List Char} (h : HtmlName n) (hv : IsVoid n) : Slice2Name n := by
  refine ⟨h.1, ?_⟩
  have := List.all_eq_true.mp void_names_scope _ hv
  simp only [Bool.not_eq_true', List.contains_eq_mem, decide_eq_false_iff_not] at this
  exact this

mutual
theorem flatten_ok : ∀ (n : Node), NodeOK .html n → Html4 n →
    Ok4 (flatten n) ∧ ∀ s, Piece4.static s ∈ flatten n → s.getLast? ≠ some '<'
  | .text s, h, _ => ⟨⟨textOK_static h, fun hl => absurd hl (textOK_last h), trivial⟩,
      fun s' hs => by simp [flatten] at hs; subst hs; exact textOK_last h⟩
  | .hole _, _, _ => ⟨trivial, by simp [flatten]⟩
  | .comment b, h, _ => ⟨⟨h, trivial⟩, by simp [flatten]⟩
  | .doctype d, h, _ => h.elim
  | .void t, ⟨ht, hv, hn⟩, _ => ⟨⟨⟨slice2Name_of_void hn hv, ht⟩, trivial⟩, by simp [flatten]⟩
  | .element t ch, ⟨ht, hsc, hn, _, hc⟩, ⟨hx, h4⟩ => by
    by_cases hs : asciiLower t.name = "script".toList
    · simp only [hs, ite_true] at hc
      obtain ⟨body, hb, hok⟩ := hc
      simp only [flatten, hs, ite_true, hb, Option.getD_some]
      exact ⟨⟨hok, trivial⟩, by simp⟩
    by_cases hr : asciiLower t.name ∈ rawNames
    · simp only [hs, hr, ite_true, ite_false] at hc
      obtain ⟨body, hb, hok⟩ := hc
      simp only [flatten, hs, hr, ite_true, ite_false, hb, Option.getD_some]
      exact ⟨⟨hok, trivial⟩, by simp⟩
    have h1 : asciiLower t.name ≠ "svg".toList := fun e => hx (by simp [excludedNames, e])
    have h2 : asciiLower t.name ≠ "math".toList := fun e => hx (by simp [excludedNames, e])
    have h3 : asciiLower t.name ≠ "select".toList := fun e => hx (by simp [excludedNames, e])
    have h5 : asciiLower t.name ≠ "noscript".toList := fun e => hx (by simp [excludedNames, e])
    simp only [hs, hr, h1, h2, h3, h5, ite_false] at hc
    obtain ⟨ih, ihl⟩ := flattenF_ok ch hc h4
    simp only [flatten, hs, hr, ite_false]
    refine ⟨⟨⟨slice2Name_of_html hn hs hr hx, ht⟩, ok4_append _ _ ih ⟨slice2Name_of_html hn hs hr hx, trivial⟩ ihl⟩, ?_⟩
    intro s hsm
    simp only [List.mem_cons, List.mem_append, List.mem_singleton, List.not_mem_nil, reduceCtorEq,
      false_or, or_false] at hsm
    exact ihl s hsm
theorem flattenF_ok : ∀ (F : Forest), ForestOK .html F → Html4F F →
    Ok4 (flattenF F) ∧ ∀ s, Piece4.static s ∈ flattenF F → s.getLast? ≠ some '<'
  | .nil, _, _ => ⟨trivial, by simp [flattenF]⟩
  | .cons n r, ⟨hn, _, hr⟩, ⟨h4, h4r⟩ => by
    obtain ⟨a1, a2⟩ := flatten_ok n hn h4
    obtain ⟨b1, b2⟩ := flattenF_ok r hr h4r
    refine ⟨ok4_append _ _ a1 b1 a2, fun s hs => ?_⟩
    simp only [flattenF, List.mem_append] at hs
    rcases hs with hs | hs
    · exact a2 s hs
    · exact b2 s hs
end

/--
**T1 for the profile fragment of slices 1-4.** For every template in the profile
(`InProfile`) whose elements are HTML, raw-text and script elements (`Html4F`), and
every list of hole values satisfying the escaper postconditions of the contexts the
compiler chose (`ValsOK4`), the WHATWG tokenizer with the tree builder's tokenizer
switches reads the rendered page as exactly the tokens Tessera assigned (`denote4`),
and never leaves the modeled fragment.

This is the token-level half of §5.1: every hole's value is Character tokens, or one
attribute value of one StartTag, between the StartTag and EndTag of the element the
compiler put it in, in the tokenizer state of that element's content model. The
tree-construction half (which node a token attaches to) is not modeled; see README.
-/
theorem inProfile_ok4 : ∀ (T : Forest), InProfile T → Html4F T → Ok4 (flattenF T)
  | .cons (.doctype d) r, ⟨hd, hr⟩, ⟨_, h4⟩ => by
    simp only [flattenF, flatten, List.singleton_append]
    exact ⟨hd, (flattenF_ok r hr h4).1⟩
  | .nil, h, h4 => (flattenF_ok _ h h4).1
  | .cons (.text _) _, h, h4 => (flattenF_ok _ h h4).1
  | .cons (.hole _) _, h, h4 => (flattenF_ok _ h h4).1
  | .cons (.comment _) _, h, h4 => (flattenF_ok _ h h4).1
  | .cons (.void _) _, h, h4 => (flattenF_ok _ h h4).1
  | .cons (.element _ _) _, h, h4 => (flattenF_ok _ h h4).1

theorem t1_profile (T : Forest) (vs : List (Option (List Char)))
    (hT : InProfile T) (h4 : Html4F T) (hv : ValsOK4 (flattenF T) vs) :
    Whatwg.tokenize Whatwg.treeStub (render4 (flattenF T) vs) = denote4 (flattenF T) vs ∧
    Whatwg.leavesModel Whatwg.treeStub Whatwg.init (render4 (flattenF T) vs) = false := by
  have hok := inProfile_ok4 T hT h4
  refine ⟨t1_ok4 _ vs [] hok hv, ?_⟩
  rcases run_ok4 _ vs [] hok hv with h | h <;> simp [Whatwg.leavesModel, Whatwg.init, h]

end TesseraT1
