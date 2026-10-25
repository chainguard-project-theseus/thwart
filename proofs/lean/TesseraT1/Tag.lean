/-
Start and end tags in the tokenizer model (13.2.5.6-13.2.5.40): a tag written in
the shape `TagSyn` describes is read as one StartTag token with the attributes as
written (names ASCII-lowercased, first occurrence of a name wins).

`TagSyn` covers both forms the generator writes: a start tag copied from the
source (the author's whitespace) and a rebuilt one (one space before each item, no
whitespace around `=`). An attribute hole ` name="v"` is a double-quoted `AttrSyn`
whose value is the escaped value.
-/
import TesseraT1.Whatwg

namespace TesseraT1

open Whatwg

/-- An attribute value as written. -/
inductive AVal where
  | dq (v : List Char)
  | sq (v : List Char)
  | unq (v : List Char)
  deriving Repr, DecidableEq

def AVal.render : AVal → List Char
  | .dq v => ['"'] ++ v ++ ['"']
  | .sq v => ['\''] ++ v ++ ['\'']
  | .unq v => v

def AVal.value : AVal → List Char
  | .dq v | .sq v | .unq v => v

/-- One attribute as written: leading whitespace, name, and optionally whitespace,
`=`, whitespace and a value. -/
structure AttrSyn where
  ws : List Char
  name : List Char
  val : Option (List Char × List Char × AVal)
  deriving Repr, DecidableEq

def AttrSyn.render (a : AttrSyn) : List Char :=
  a.ws ++ a.name ++
    match a.val with
    | none => []
    | some (w1, w2, v) => w1 ++ ['='] ++ w2 ++ v.render

/-- The attribute as the tokenizer reports it. -/
def AttrSyn.token (a : AttrSyn) : List Char × List Char :=
  (lowerStr a.name, match a.val with | none => [] | some (_, _, v) => v.value)

/-- A start tag as written. -/
structure TagSyn where
  name : List Char
  attrs : List AttrSyn
  trail : List Char
  selfClosing : Bool
  deriving Repr, DecidableEq

def TagSyn.render (t : TagSyn) : List Char :=
  ['<'] ++ t.name ++ (t.attrs.map AttrSyn.render).flatten ++ t.trail ++
    (if t.selfClosing then ['/', '>'] else ['>'])

/-- The StartTag token Tessera reads the tag as. -/
def TagSyn.token (t : TagSyn) : Token :=
  .startTag (lowerStr t.name) (finalAttrs (t.attrs.map AttrSyn.token).reverse) t.selfClosing

/-- Whitespace runs: every character is tokenizer whitespace. -/
def AllWs (w : List Char) : Prop := ∀ c ∈ w, isWs c = true

/-- A tag or attribute name character the tag states append: not whitespace, `/`,
`>`, `=` or NUL. (S1/S2 are stricter.) -/
def NameChar (c : Char) : Prop := isWs c = false ∧ c ≠ '/' ∧ c ≠ '>' ∧ c ≠ '=' ∧ c ≠ '\x00'

def AVal.OK : AVal → Prop
  | .dq v => '"' ∉ v ∧ '\x00' ∉ v
  | .sq v => '\'' ∉ v ∧ '\x00' ∉ v
  | .unq v => v ≠ [] ∧ (∀ c ∈ v, isWs c = false ∧ c ≠ '>' ∧ c ≠ '\x00') ∧
      v.head? ≠ some '"' ∧ v.head? ≠ some '\''

def AttrSyn.OK (a : AttrSyn) : Prop :=
  AllWs a.ws ∧ a.name ≠ [] ∧ (∀ c ∈ a.name, NameChar c) ∧
    match a.val with
    | none => True
    | some (w1, w2, v) => AllWs w1 ∧ AllWs w2 ∧ v.OK

def AttrSyn.isUnq (a : AttrSyn) : Bool :=
  match a.val with
  | some (_, _, .unq _) => true
  | _ => false

def AttrSyn.endsQuoted (a : AttrSyn) : Bool :=
  match a.val with
  | some (_, _, .dq _) | some (_, _, .sq _) => true
  | _ => false

/-- Each attribute is well-formed and starts with whitespace, except right after a
quoted value (13.2.5.39 starts a new attribute there too). `q`: the previous
attribute ended in a quoted value. -/
def AttrsChain : Bool → List AttrSyn → Prop
  | _, [] => True
  | q, a :: r => a.OK ∧ (a.ws ≠ [] ∨ q = true) ∧ AttrsChain a.endsQuoted r

/-- The tag conditions: the name starts with an ASCII letter, every piece is in
its lexical class, and `/>` does not directly follow an unquoted value (a browser
would append the `/` to the value). -/
def TagSyn.OK (t : TagSyn) : Prop :=
  (∃ c r, t.name = c :: r ∧ isAlpha c = true) ∧ (∀ c ∈ t.name, NameChar c) ∧
    AttrsChain false t.attrs ∧ AllWs t.trail ∧
    (t.selfClosing = true → t.trail = [] → ∀ a, t.attrs.getLast? = some a → a.isUnq = false)

/-! ### Runs of one lexical class -/

theorem run_tagName_chars (tr : Tree) (ls : List Char) :
    ∀ (p : List Char) (t : Tag), (∀ c ∈ p, NameChar c) →
      run tr ⟨.tagName t, ls⟩ p = (⟨.tagName { t with name := t.name ++ lowerStr p }, ls⟩, [])
  | [], t, _ => by simp [run, lowerStr]
  | c :: p, t, h => by
    have hc := h c (by simp)
    have e : step tr ⟨.tagName t, ls⟩ c = (⟨.tagName (t.appendName (lower c)), ls⟩, []) := by
      simp [step, stepTagName, hc.1, hc.2.1, hc.2.2.1, hc.2.2.2.2]
    simp only [run, e]
    rw [run_tagName_chars tr ls p _ (fun d hd => h d (by simp [hd]))]
    simp [Tag.appendName, lowerStr]

theorem run_ws_before (tr : Tree) (ls : List Char) (t : Tag) :
    ∀ w, AllWs w → run tr ⟨.beforeAttrName t, ls⟩ w = (⟨.beforeAttrName t, ls⟩, [])
  | [], _ => rfl
  | c :: w, h => by
    have hc : isWs c = true := h c (by simp)
    simp [run, step, stepBeforeAttrName, hc,
      run_ws_before tr ls t w (fun d hd => h d (by simp [hd]))]

theorem run_ws_after (tr : Tree) (ls : List Char) (t : Tag) :
    ∀ w, AllWs w → run tr ⟨.afterAttrName t, ls⟩ w = (⟨.afterAttrName t, ls⟩, [])
  | [], _ => rfl
  | c :: w, h => by
    have hc : isWs c = true := h c (by simp)
    simp [run, step, hc, run_ws_after tr ls t w (fun d hd => h d (by simp [hd]))]

theorem run_ws_beforeValue (tr : Tree) (ls : List Char) (t : Tag) :
    ∀ w, AllWs w → run tr ⟨.beforeAttrValue t, ls⟩ w = (⟨.beforeAttrValue t, ls⟩, [])
  | [], _ => rfl
  | c :: w, h => by
    have hc : isWs c = true := h c (by simp)
    simp [run, step, hc, run_ws_beforeValue tr ls t w (fun d hd => h d (by simp [hd]))]

/-- The tag with a finished attribute `(n, v)` added (attributes are kept reversed). -/
def Whatwg.Tag.withAttr (t : Tag) (n v : List Char) : Tag := { t with attrs := (n, v) :: t.attrs }

theorem run_attrName_chars (tr : Tree) (ls : List Char) :
    ∀ (p : List Char) (t : Tag) (n : List Char), (∀ c ∈ p, NameChar c) →
      run tr ⟨.attrName (t.withAttr n []), ls⟩ p =
        (⟨.attrName (t.withAttr (n ++ lowerStr p) []), ls⟩, [])
  | [], t, n, _ => by simp [run, lowerStr]
  | c :: p, t, n, h => by
    have hc := h c (by simp)
    have e : step tr ⟨.attrName (t.withAttr n []), ls⟩ c =
        (⟨.attrName (t.withAttr (n ++ [lower c]) []), ls⟩, []) := by
      simp [step, stepAttrName, hc.1, hc.2.1, hc.2.2.1, hc.2.2.2.1, hc.2.2.2.2,
        Tag.appendAttrName, Tag.withAttr]
    simp only [run, e]
    rw [run_attrName_chars tr ls p _ _ (fun d hd => h d (by simp [hd]))]
    simp [lowerStr]

theorem run_dq_chars (tr : Tree) (ls : List Char) :
    ∀ (p : List Char) (t : Tag) (n v : List Char), '"' ∉ p → '\x00' ∉ p →
      run tr ⟨.attrValueDQ (t.withAttr n v), ls⟩ p =
        (⟨.attrValueDQ (t.withAttr n (v ++ p)), ls⟩, [])
  | [], t, n, v, _, _ => by simp [run]
  | c :: p, t, n, v, h1, h2 => by
    simp only [List.mem_cons, not_or] at h1 h2
    have e : step tr ⟨.attrValueDQ (t.withAttr n v), ls⟩ c =
        (⟨.attrValueDQ (t.withAttr n (v ++ [c])), ls⟩, []) := by
      simp [step, Ne.symm h1.1, Ne.symm h2.1, Tag.appendAttrValue, Tag.withAttr]
    simp only [run, e]
    rw [run_dq_chars tr ls p t n _ h1.2 h2.2]
    simp

theorem run_sq_chars (tr : Tree) (ls : List Char) :
    ∀ (p : List Char) (t : Tag) (n v : List Char), '\'' ∉ p → '\x00' ∉ p →
      run tr ⟨.attrValueSQ (t.withAttr n v), ls⟩ p =
        (⟨.attrValueSQ (t.withAttr n (v ++ p)), ls⟩, [])
  | [], t, n, v, _, _ => by simp [run]
  | c :: p, t, n, v, h1, h2 => by
    simp only [List.mem_cons, not_or] at h1 h2
    have e : step tr ⟨.attrValueSQ (t.withAttr n v), ls⟩ c =
        (⟨.attrValueSQ (t.withAttr n (v ++ [c])), ls⟩, []) := by
      simp [step, Ne.symm h1.1, Ne.symm h2.1, Tag.appendAttrValue, Tag.withAttr]
    simp only [run, e]
    rw [run_sq_chars tr ls p t n _ h1.2 h2.2]
    simp

theorem run_unq_chars (tr : Tree) (ls : List Char) :
    ∀ (p : List Char) (t : Tag) (n v : List Char),
      (∀ c ∈ p, isWs c = false ∧ c ≠ '>' ∧ c ≠ '\x00') →
      run tr ⟨.attrValueUnq (t.withAttr n v), ls⟩ p =
        (⟨.attrValueUnq (t.withAttr n (v ++ p)), ls⟩, [])
  | [], t, n, v, _ => by simp [run]
  | c :: p, t, n, v, h => by
    have hc := h c (by simp)
    have e : step tr ⟨.attrValueUnq (t.withAttr n v), ls⟩ c =
        (⟨.attrValueUnq (t.withAttr n (v ++ [c])), ls⟩, []) := by
      simp [step, stepAttrValueUnq, hc.1, hc.2.1, hc.2.2, Tag.appendAttrValue, Tag.withAttr]
    simp only [run, e]
    rw [run_unq_chars tr ls p t n _ (fun d hd => h d (by simp [hd]))]
    simp

/-! ### One attribute -/

/-- Where the tokenizer can start the next attribute: between attributes, or right
after a quoted value (13.2.5.39 reconsumes a name character in 13.2.5.32). -/
inductive Ready : St → Tag → Prop where
  | before (t : Tag) : Ready (.beforeAttrName t) t
  | after (t : Tag) : Ready (.afterAttrName t) t
  | quoted (t : Tag) : Ready (.afterAttrValueQ t) t

/-- Where the tokenizer is right after an attribute's last character. -/
inductive Done : St → Tag → Bool → Prop where
  | name (t : Tag) : Done (.tagName t) t false
  | bare (t : Tag) : Done (.attrName t) t false
  | quoted (t : Tag) : Done (.afterAttrValueQ t) t false
  | unq (t : Tag) : Done (.attrValueUnq t) t true

/-- From `Ready`, the first name character starts a new attribute. -/
theorem ready_name_start (tr : Tree) (ls : List Char) {st : St} {t : Tag} (h : Ready st t)
    {c : Char} (hc : NameChar c) :
    step tr ⟨st, ls⟩ c = (⟨.attrName (t.withAttr [lower c] []), ls⟩, []) := by
  obtain ⟨h1, h2, h3, h4, h5⟩ := hc
  have e : stepAttrName tr ls t.newAttr c = (⟨.attrName (t.withAttr [lower c] []), ls⟩, []) := by
    simp [stepAttrName, h1, h2, h3, h4, h5, Tag.newAttr, Tag.appendAttrName, Tag.withAttr]
  have eb : stepBeforeAttrName tr ls t c = (⟨.attrName (t.withAttr [lower c] []), ls⟩, []) := by
    simp [stepBeforeAttrName, h1, h2, h3, h4, e]
  cases h with
  | before => simpa [step] using eb
  | after => simp [step, h1, h2, h3, h4, e]
  | quoted => simp [step, h1, h2, h3, eb]

theorem ready_ws (tr : Tree) (ls : List Char) {st : St} {t : Tag} (h : Ready st t)
    {c : Char} (hc : isWs c = true) :
    ∃ st', step tr ⟨st, ls⟩ c = (⟨st', ls⟩, []) ∧ Ready st' t := by
  cases h with
  | before => exact ⟨_, by simp [step, stepBeforeAttrName, hc], .before t⟩
  | after => exact ⟨_, by simp [step, hc], .after t⟩
  | quoted => exact ⟨_, by simp [step, hc], .before t⟩

theorem done_ws (tr : Tree) (ls : List Char) {st : St} {t : Tag} {u : Bool} (h : Done st t u)
    {c : Char} (hc : isWs c = true) :
    ∃ st', step tr ⟨st, ls⟩ c = (⟨st', ls⟩, []) ∧ Ready st' t := by
  cases h with
  | name => exact ⟨_, by simp [step, stepTagName, hc], .before t⟩
  | bare => exact ⟨_, by simp [step, stepAttrName, hc], .after t⟩
  | quoted => exact ⟨_, by simp [step, hc], .before t⟩
  | unq => exact ⟨_, by simp [step, stepAttrValueUnq, hc], .before t⟩

theorem run_ready_ws (tr : Tree) (ls : List Char) :
    ∀ (w : List Char) {st : St} {t : Tag}, Ready st t → AllWs w →
      ∃ st', run tr ⟨st, ls⟩ w = (⟨st', ls⟩, []) ∧ Ready st' t
  | [], st, _, h, _ => ⟨st, rfl, h⟩
  | c :: w, _, _, h, hw => by
    obtain ⟨s1, e1, h1⟩ := ready_ws tr ls h (hw c (by simp))
    obtain ⟨s2, e2, h2⟩ := run_ready_ws tr ls w h1 (fun d hd => hw d (by simp [hd]))
    exact ⟨s2, by simp [run, e1, e2], h2⟩

/-- Where an attribute may start: `Ready`, or `Done` when whitespace follows. -/
def CanStart (st : St) (t : Tag) (ws : List Char) : Prop :=
  Ready st t ∨ (ws ≠ [] ∧ ∃ u, Done st t u) ∨ (∃ t', st = .afterAttrValueQ t' ∧ t' = t)

theorem run_lead_ws (tr : Tree) (ls : List Char) {st : St} {t : Tag} {w : List Char}
    (h : CanStart st t w) (hw : AllWs w) :
    ∃ st', run tr ⟨st, ls⟩ w = (⟨st', ls⟩, []) ∧ Ready st' t := by
  rcases h with h | ⟨hne, u, h⟩ | ⟨t', rfl, rfl⟩
  · exact run_ready_ws tr ls w h hw
  · match w, hne with
    | c :: w, _ =>
      obtain ⟨s1, e1, h1⟩ := done_ws tr ls h (hw c (by simp))
      obtain ⟨s2, e2, h2⟩ := run_ready_ws tr ls w h1 (fun d hd => hw d (by simp [hd]))
      exact ⟨s2, by simp [run, e1, e2], h2⟩
  · exact run_ready_ws tr ls w (.quoted _) hw

/-- The value part `=` + value, from the attribute name state or after it. -/
theorem run_value (tr : Tree) (ls : List Char) (t : Tag) (n : List Char) {w1 w2 : List Char}
    {v : AVal} (h1 : AllWs w1) (h2 : AllWs w2) (hv : v.OK) :
    ∃ st', run tr ⟨.attrName (t.withAttr n []), ls⟩ (w1 ++ ['='] ++ w2 ++ v.render) =
      (⟨st', ls⟩, []) ∧ Done st' (t.withAttr n v.value) (match v with | .unq _ => true | _ => false) ∧
      ((match v with | .unq _ => false | _ => true) = true → st' = .afterAttrValueQ (t.withAttr n v.value)) := by
  -- w1 then `=`: the before attribute value state.
  have hw1 : run tr ⟨.attrName (t.withAttr n []), ls⟩ (w1 ++ ['=']) =
      (⟨.beforeAttrValue (t.withAttr n []), ls⟩, []) := by
    match w1, h1 with
    | [], _ => simp [run, step, stepAttrName, isWs]
    | c :: w, h1 =>
      have hc : isWs c = true := h1 c (by simp)
      rw [List.cons_append]
      simp only [run]
      rw [show step tr ⟨.attrName (t.withAttr n []), ls⟩ c =
          (⟨.afterAttrName (t.withAttr n []), ls⟩, []) by simp [step, stepAttrName, hc]]
      rw [run_append, run_ws_after tr ls _ w (fun d hd => h1 d (by simp [hd]))]
      simp [run, step, isWs]
  rw [List.append_assoc, List.append_assoc, ← List.append_assoc w1, run_append, hw1,
    run_append, run_ws_beforeValue tr ls _ w2 h2]
  simp only [List.nil_append]
  cases v with
  | dq p =>
    obtain ⟨hq, hn⟩ := hv
    refine ⟨.afterAttrValueQ (t.withAttr n p), ?_, .quoted _, fun _ => rfl⟩
    simp only [AVal.render, List.append_assoc, List.cons_append, List.nil_append]
    simp only [run]
    rw [show step tr ⟨.beforeAttrValue (t.withAttr n []), ls⟩ '"' =
        (⟨.attrValueDQ (t.withAttr n []), ls⟩, []) by simp [step, isWs]]
    rw [run_append, run_dq_chars tr ls p t n [] hq hn]
    simp [run, step]
  | sq p =>
    obtain ⟨hq, hn⟩ := hv
    refine ⟨.afterAttrValueQ (t.withAttr n p), ?_, .quoted _, fun _ => rfl⟩
    simp only [AVal.render, List.append_assoc, List.cons_append, List.nil_append]
    simp only [run]
    rw [show step tr ⟨.beforeAttrValue (t.withAttr n []), ls⟩ '\'' =
        (⟨.attrValueSQ (t.withAttr n []), ls⟩, []) by simp [step, isWs]]
    rw [run_append, run_sq_chars tr ls p t n [] hq hn]
    simp [run, step]
  | unq p =>
    obtain ⟨hne, hcs, hd, hs⟩ := hv
    refine ⟨.attrValueUnq (t.withAttr n p), ?_, .unq _, fun h => by simp at h⟩
    match p, hne, hcs, hd, hs with
    | c :: p, _, hcs, hd, hs =>
      have hc := hcs c (by simp)
      have hc1 : c ≠ '"' := fun e => hd (by simp [e])
      have hc2 : c ≠ '\'' := fun e => hs (by simp [e])
      simp only [AVal.render, run]
      rw [show step tr ⟨.beforeAttrValue (t.withAttr n []), ls⟩ c =
          (⟨.attrValueUnq (t.withAttr n [c]), ls⟩, []) by
        simp [step, stepAttrValueUnq, hc.1, hc.2.1, hc.2.2, hc1, hc2, Tag.appendAttrValue,
          Tag.withAttr]]
      rw [run_unq_chars tr ls p t n [c] (fun d hd => hcs d (by simp [hd]))]
      simp

/-- One attribute, read from where an attribute may start. -/
theorem run_attr (tr : Tree) (ls : List Char) {st : St} {t : Tag} {a : AttrSyn}
    (h : CanStart st t a.ws) (ha : a.OK) :
    ∃ st', run tr ⟨st, ls⟩ a.render = (⟨st', ls⟩, []) ∧
      Done st' (t.withAttr a.token.1 a.token.2) a.isUnq ∧
      (a.endsQuoted = true → st' = .afterAttrValueQ (t.withAttr a.token.1 a.token.2)) := by
  obtain ⟨hwsw, hne, hnc, hv⟩ := ha
  obtain ⟨s1, e1, g1⟩ := run_lead_ws tr ls h hwsw
  obtain ⟨ws, name, val⟩ := a
  simp only at hne hnc hv e1 g1 ⊢
  obtain ⟨c, n, rfl⟩ : ∃ c n, name = c :: n := by
    cases name with
    | nil => exact absurd rfl hne
    | cons c n => exact ⟨c, n, rfl⟩
  have hc := hnc c (by simp)
  have en : run tr ⟨s1, ls⟩ (c :: n) =
      (⟨.attrName (t.withAttr (lowerStr (c :: n)) []), ls⟩, []) := by
    simp only [run]
    rw [ready_name_start tr ls g1 hc]
    rw [run_attrName_chars tr ls n t [lower c] (fun d hd => hnc d (by simp [hd]))]
    simp [lowerStr]
  simp only [AttrSyn.render, AttrSyn.token, AttrSyn.isUnq]
  rw [run_append, run_append, e1, en]
  cases val with
  | none => exact ⟨_, by simp [run], .bare _, fun h => by simp [AttrSyn.endsQuoted] at h⟩
  | some x =>
    obtain ⟨w1, w2, v⟩ := x
    obtain ⟨h1, h2, h3⟩ := hv
    obtain ⟨st', e, hd, hq⟩ := run_value tr ls t (lowerStr (c :: n)) h1 h2 h3
    refine ⟨st', ?_, ?_, ?_⟩
    · simp only at e ⊢
      rw [e]; simp
    · cases v <;> simpa using hd
    · intro h; cases v <;> simp_all [AttrSyn.endsQuoted]

/-! ### Whole tags -/

/-- The tag with the finished attributes `as` added, in order. -/
def Whatwg.Tag.withAttrs (t : Tag) (as : List AttrSyn) : Tag :=
  { t with attrs := (as.map AttrSyn.token).reverse ++ t.attrs }

theorem withAttrs_cons (t : Tag) (a : AttrSyn) (as : List AttrSyn) :
    t.withAttrs (a :: as) = (t.withAttr a.token.1 a.token.2).withAttrs as := by
  simp [Tag.withAttrs, Tag.withAttr]

/-- A run of attributes, from the tag name or a finished attribute. `q` says the
state is right after a quoted value. -/
theorem run_attrs (tr : Tree) (ls : List Char) :
    ∀ (as : List AttrSyn) {st : St} {t : Tag} {u q : Bool}, Done st t u →
      (q = true → st = .afterAttrValueQ t) → AttrsChain q as →
      ∃ st' u', run tr ⟨st, ls⟩ (as.map AttrSyn.render).flatten = (⟨st', ls⟩, []) ∧
        Done st' (t.withAttrs as) u' ∧ u' = ((as.getLast?.map AttrSyn.isUnq).getD u)
  | [], st, t, u, _, h, _, _ => ⟨st, u, rfl, by simpa [Tag.withAttrs] using h, by simp⟩
  | a :: as, st, t, u, q, h, hq, ⟨ha, hws, hr⟩ => by
    have hc : CanStart st t a.ws := by
      rcases hws with hws | hq'
      · exact .inr (.inl ⟨hws, u, h⟩)
      · exact .inr (.inr ⟨t, hq hq', rfl⟩)
    obtain ⟨s1, e1, d1, q1⟩ := run_attr tr ls hc ha
    obtain ⟨s2, u2, e2, d2, hu2⟩ := run_attrs tr ls as d1 q1 hr
    refine ⟨s2, u2, ?_, by rwa [withAttrs_cons], ?_⟩
    · simp only [List.map_cons, List.flatten_cons]
      rw [run_append, e1, e2]; simp
    · rw [hu2]; cases as <;> simp [List.getLast?_cons]

/-- From a finished attribute or the tag name, `>` emits the tag. -/
theorem done_gt (tr : Tree) (ls : List Char) {st : St} {t : Tag} {u : Bool} (h : Done st t u) :
    step tr ⟨st, ls⟩ '>' = emitTag tr ls t := by
  cases h <;> simp [step, stepTagName, stepAttrName, stepAttrValueUnq, isWs]

theorem ready_gt (tr : Tree) (ls : List Char) {st : St} {t : Tag} (h : Ready st t) :
    step tr ⟨st, ls⟩ '>' = emitTag tr ls t := by
  cases h <;> simp [step, stepBeforeAttrName, isWs]

/-- `/` from a finished attribute that is not an unquoted value, or from a gap,
reaches the self-closing start tag state. -/
theorem done_slash (tr : Tree) (ls : List Char) {st : St} {t : Tag} (h : Done st t false) :
    step tr ⟨st, ls⟩ '/' = (⟨.selfClosing t, ls⟩, []) := by
  cases h <;> simp [step, stepTagName, stepAttrName, isWs]

theorem ready_slash (tr : Tree) (ls : List Char) {st : St} {t : Tag} (h : Ready st t) :
    step tr ⟨st, ls⟩ '/' = (⟨.selfClosing t, ls⟩, []) := by
  cases h <;> simp [step, stepBeforeAttrName, isWs]

/-- The end of a start tag: whitespace, then `>` or `/>`. -/
theorem run_close (tr : Tree) (ls : List Char) {st : St} {t : Tag} {u : Bool} (h : Done st t u)
    (ht : t.selfClosing = false) {w : List Char} (hw : AllWs w) (sc : Bool)
    (hsc : sc = true → w = [] → u = false) :
    run tr ⟨st, ls⟩ (w ++ (if sc then ['/', '>'] else ['>'])) =
      emitTag tr ls { t with selfClosing := sc } := by
  have ht' : { t with selfClosing := false } = t := by cases t; simp_all
  have hend : ∀ {s : St}, Ready s t →
      run tr ⟨s, ls⟩ (if sc then ['/', '>'] else ['>']) = emitTag tr ls { t with selfClosing := sc } := by
    intro s hs
    cases sc with
    | false => simp [run, ready_gt tr ls hs, ht']
    | true =>
      show run tr ⟨s, ls⟩ ['/', '>'] = _
      simp only [run]; rw [ready_slash tr ls hs]; simp [run, step]
  match w, hw with
  | [], _ =>
    cases sc with
    | false => simp [run, done_gt tr ls h, ht']
    | true =>
      have hu : u = false := hsc rfl rfl
      subst hu
      show run tr ⟨st, ls⟩ ['/', '>'] = _
      simp only [run]; rw [done_slash tr ls h]; simp [run, step]
  | c :: w, hw =>
    obtain ⟨s1, e1, r1⟩ := done_ws tr ls h (hw c (by simp))
    obtain ⟨s2, e2, r2⟩ := run_ready_ws tr ls w r1 (fun d hd => hw d (by simp [hd]))
    rw [List.cons_append]
    simp only [run]
    rw [e1]
    simp only
    rw [run_append, e2]
    simp [hend r2]

/-- The emitted start tag is Tessera's token for it. -/
theorem emit_token (tr : Tree) (ls : List Char) (s : TagSyn) :
    emitTag tr ls { ({ isEnd := false, name := lowerStr s.name, attrs := [], selfClosing := false } :
        Tag).withAttrs s.attrs with selfClosing := s.selfClosing } =
      (⟨tr (lowerStr s.name), lowerStr s.name⟩, [s.token]) := by
  simp [emitTag, Tag.withAttrs, TagSyn.token]

/-- **Start tags.** From the data state, a well-formed start tag is one StartTag
token, after which the tree builder picks the tokenizer state. -/
theorem run_startTag (tr : Tree) (ls : List Char) {s : TagSyn} (h : s.OK) :
    run tr ⟨.data, ls⟩ s.render = (⟨tr (lowerStr s.name), lowerStr s.name⟩, [s.token]) := by
  obtain ⟨⟨c, r, hn, ha⟩, hnc, hchain, htrail, hsc⟩ := h
  have e0 : run tr ⟨.data, ls⟩ (['<'] ++ s.name) =
      (⟨.tagName ⟨false, lowerStr s.name, [], false⟩, ls⟩, []) := by
    rw [hn]
    have hc := hnc c (by simp [hn])
    simp only [List.singleton_append, run]
    rw [show step tr ⟨.data, ls⟩ '<' = (⟨.tagOpen, ls⟩, []) by simp [step, stepData]]
    simp only
    rw [show step tr ⟨.tagOpen, ls⟩ c = (⟨.tagName ⟨false, [lower c], [], false⟩, ls⟩, []) by
      have h1 : c ≠ '!' := by intro e; subst e; simp [isAlpha] at ha
      have h2 : c ≠ '/' := hc.2.1
      simp [step, h1, h2, ha]]
    simp only
    rw [run_tagName_chars tr ls r _ (fun d hd => hnc d (by simp [hn, hd]))]
    simp [lowerStr]
  obtain ⟨st, u, e1, d1, hu⟩ :=
    run_attrs tr ls s.attrs (Done.name ⟨false, lowerStr s.name, [], false⟩) (by simp) hchain
  unfold TagSyn.render
  rw [run_append, run_append, run_append, e0]
  simp only [List.nil_append]
  rw [e1]
  simp only [List.nil_append]
  rw [← run_append]
  rw [run_close tr ls d1 (by simp [Tag.withAttrs]) htrail s.selfClosing (fun h1 h2 => by
    rw [hu]
    cases hl : s.attrs.getLast? with
    | none => simp
    | some a => simpa using hsc h1 h2 a hl)]
  exact emit_token tr ls s

/-- **End tags.** `</` + name + `>` is one EndTag token. -/
theorem run_endTag (tr : Tree) (ls : List Char) {n : List Char}
    (hn : ∃ c r, n = c :: r ∧ isAlpha c = true) (hnc : ∀ c ∈ n, NameChar c) :
    run tr ⟨.data, ls⟩ (['<', '/'] ++ n ++ ['>']) = (⟨.data, ls⟩, [.endTag (lowerStr n)]) := by
  obtain ⟨c, r, rfl, ha⟩ := hn
  have hc := hnc c (by simp)
  simp only [List.cons_append, List.nil_append, run]
  rw [show step tr ⟨.data, ls⟩ '<' = (⟨.tagOpen, ls⟩, []) by simp [step, stepData]]
  simp only
  rw [show step tr ⟨.tagOpen, ls⟩ '/' = (⟨.endTagOpen, ls⟩, []) by simp [step]]
  simp only
  rw [show step tr ⟨.endTagOpen, ls⟩ c = (⟨.tagName ⟨true, [lower c], [], false⟩, ls⟩, []) by
    simp [step, ha]]
  simp only
  rw [run_append, run_tagName_chars tr ls r _ (fun d hd => hnc d (by simp [hd]))]
  simp [run, step, stepTagName, isWs, emitTag, lowerStr]

/-! ### Tessera's reading of a tag -/

/-- The StartTag token as Tessera reads the tag: every attribute as written. -/
def TagSyn.tessera (t : TagSyn) : Token :=
  .startTag (lowerStr t.name) (t.attrs.map AttrSyn.token) t.selfClosing

theorem foldl_dedupe :
    ∀ (l acc : List (List Char × List Char)), ((acc ++ l).map Prod.fst).Nodup →
      l.foldl (fun acc a => if acc.any (·.1 == a.1) then acc else acc ++ [a]) acc = acc ++ l
  | [], acc, _ => by simp
  | a :: l, acc, h => by
    have hn : (acc.any (·.1 == a.1)) = false := by
      rw [List.map_append, List.nodup_append] at h
      simp only [List.any_eq_false, beq_iff_eq]
      intro x hx hxa
      exact h.2.2 x.1 (List.mem_map_of_mem hx) a.1 (by simp) hxa
    simp only [List.foldl_cons, hn, Bool.false_eq_true, if_false]
    rw [foldl_dedupe l (acc ++ [a]) (by simpa using h)]
    simp

/-- With no duplicate (lowercased) attribute names (S3), the browser's
first-occurrence rule changes nothing. -/
theorem token_eq_tessera {t : TagSyn} (h : ((t.attrs.map AttrSyn.token).map Prod.fst).Nodup) :
    t.token = t.tessera := by
  simp only [TagSyn.token, TagSyn.tessera, finalAttrs, List.reverse_reverse]
  rw [foldl_dedupe _ [] (by simpa using h)]
  simp

end TesseraT1
