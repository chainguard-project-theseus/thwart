/-
From the generator's start tags (Subset.lean: `SrcTag`, `StartTag`) to the
tokenizer-side shape `TagSyn` (Tag.lean), for which `run_startTag` is proved.

The definitions mirror `SrcTag.render`, `renderItems` and `StartTag.render` piece by
piece; the lemmas say the bytes are the same and the side conditions carry over.
-/
import TesseraT1.Subset
import TesseraT1.Tag
import TesseraT1.Escapers

namespace TesseraT1

open Whatwg

def SrcValue.toAVal : SrcValue → AVal
  | .dquote v => .dq v
  | .squote v => .sq v
  | .unquoted v => .unq v

def SrcAttr.toSyn (w : List Char) (a : SrcAttr) : AttrSyn :=
  ⟨w, a.name, a.value.map fun (w1, w2, v) => (w1, w2, v.toAVal)⟩

def SrcTag.toSyn (t : SrcTag) : TagSyn :=
  ⟨t.name, t.items.map (fun (w, a) => a.toSyn w), t.trailWs, t.selfClosing⟩

/-- A rebuilt static attribute: one space, the name, and `=` + value as written. -/
def StaticAttr.toSyn : StaticAttr → AttrSyn
  | .dquote n v => ⟨[' '], n, some ([], [], .dq v)⟩
  | .squote n v => ⟨[' '], n, some ([], [], .sq v)⟩
  | .unquoted n v => ⟨[' '], n, some ([], [], .unq v)⟩
  | .bare n => ⟨[' '], n, none⟩

/-- Mirrors `renderItems`: the attributes a rebuilt tag writes, and the values left. -/
def itemsSyn : List TagItem → List (Option (List Char)) → List AttrSyn × List (Option (List Char))
  | [], vs => ([], vs)
  | .attr a :: r, vs =>
    let (o, vs') := itemsSyn r vs
    (a.toSyn :: o, vs')
  | .hole _ _ :: r, [] => itemsSyn r []
  | .hole n _ :: r, v :: vs =>
    let (o, vs') := itemsSyn r vs
    ((match v with
      | none => []
      | some x => [⟨[' '], n, some ([], [], .dq x)⟩]) ++ o, vs')

/-- Mirrors `StartTag.render`. -/
def StartTag.toSyn (t : StartTag) (vs : List (Option (List Char))) :
    TagSyn × List (Option (List Char)) :=
  match t.source, t.items.all TagItem.isAttr with
  | some src, true => (src.toSyn, vs)
  | _, _ =>
    let (o, vs') := itemsSyn t.items vs
    (⟨t.name, o, if t.selfClosing ∧ t.spaceBeforeSlash then [' '] else [], t.selfClosing⟩, vs')

/-- The attribute-hole values a rebuilt tag consumes satisfy `htmlSafe` (every
attribute escaper's postcondition, docs/context-model.md section 6.1; for
`scriptAttr` the `htmlSafe` half of `jsonAttr`). -/
def ItemValsOK : List TagItem → List (Option (List Char)) → Prop
  | [], _ => True
  | .attr _ :: r, vs => ItemValsOK r vs
  | .hole _ _ :: r, [] => ItemValsOK r []
  | .hole _ _ :: r, v :: vs => (∀ x, v = some x → htmlSafe x) ∧ ItemValsOK r vs

/-- Mirrors `StartTag.render`: only a rebuilt tag consumes values. -/
def StartTag.ValsOK (t : StartTag) (vs : List (Option (List Char))) : Prop :=
  match t.source, t.items.all TagItem.isAttr with
  | some _, true => True
  | _, _ => ItemValsOK t.items vs

/-! ### Bytes: the translations render the same output -/

theorem SrcAttr.toSyn_render (w : List Char) (a : SrcAttr) : (a.toSyn w).render = w ++ a.render := by
  rcases a with ⟨n, _ | ⟨w1, w2, v⟩⟩
  · simp [SrcAttr.toSyn, AttrSyn.render, SrcAttr.render]
  · cases v <;> simp [SrcAttr.toSyn, AttrSyn.render, SrcAttr.render, SrcValue.toAVal,
      AVal.render, SrcValue.render]

theorem srcItems_render : ∀ items : List (List Char × SrcAttr),
    ((items.map fun x => SrcAttr.toSyn x.1 x.2).map AttrSyn.render).flatten =
      items.flatMap (fun x => x.1 ++ x.2.render)
  | [] => by simp
  | (w, a) :: r => by
    simp only [List.map_cons, List.flatten_cons, List.flatMap_cons]
    rw [srcItems_render r, SrcAttr.toSyn_render]

theorem SrcTag.toSyn_render (t : SrcTag) : t.toSyn.render = t.render := by
  simp only [SrcTag.toSyn, TagSyn.render, SrcTag.render]
  rw [srcItems_render]
  rfl

theorem StaticAttr.toSyn_render (a : StaticAttr) : a.toSyn.render = [' '] ++ a.render := by
  cases a <;> simp [StaticAttr.toSyn, AttrSyn.render, StaticAttr.render, AVal.render]

theorem itemsSyn_render : ∀ (items : List TagItem) (vs : List (Option (List Char))),
    ((itemsSyn items vs).1.map AttrSyn.render).flatten = (renderItems items vs).1 ∧
    (itemsSyn items vs).2 = (renderItems items vs).2
  | [], vs => by simp [itemsSyn, renderItems]
  | .attr a :: r, vs => by
    obtain ⟨h1, h2⟩ := itemsSyn_render r vs
    simp [itemsSyn, renderItems, StaticAttr.toSyn_render, h1, h2]
  | .hole n c :: r, [] => by
    simpa [itemsSyn, renderItems] using itemsSyn_render r []
  | .hole n c :: r, v :: vs => by
    obtain ⟨h1, h2⟩ := itemsSyn_render r vs
    cases v <;> simp [itemsSyn, renderItems, AttrSyn.render, AVal.render, h1, h2]

/-! ### Side conditions carry over -/

theorem isWs_eq (c : Char) : isWs c = isAsciiWs c := by
  unfold isWs isAsciiWs
  cases h1 : (c == '\t') <;> cases h2 : (c == '\n') <;> cases h3 : (c == '\x0C') <;>
    cases h4 : (c == '\r') <;> cases h5 : (c == ' ') <;> simp_all

theorem isWs_cases {c : Char} (h : isWs c = true) :
    c = '\t' ∨ c = '\n' ∨ c = '\x0C' ∨ c = '\r' ∨ c = ' ' := by
  simpa [isWs, or_assoc] using h

theorem nameChar_of_isNameChar {c : Char} (h : isNameChar c = true) : NameChar c := by
  refine ⟨?_, ?_, ?_, ?_, ?_⟩
  · cases hw : isWs c
    · rfl
    · rcases isWs_cases hw with rfl | rfl | rfl | rfl | rfl <;> revert h <;> decide
  all_goals (rintro rfl; revert h; decide)

theorem allWs_of_all {w : List Char} (h : w.all isAsciiWs = true) : AllWs w := by
  intro c hc
  rw [isWs_eq]
  exact List.all_eq_true.mp h c hc

theorem validElementName_syn {n : List Char} (h : validElementName n = true) :
    (∃ c r, n = c :: r ∧ isAlpha c = true) ∧ ∀ c ∈ n, NameChar c := by
  match n, h with
  | c :: r, h =>
    simp only [validElementName, Bool.and_eq_true, List.all_eq_true] at h
    refine ⟨⟨c, r, rfl, h.1⟩, ?_⟩
    intro d hd
    rcases List.mem_cons.mp hd with rfl | hd
    · exact nameChar_of_isNameChar (by simp [isNameChar, h.1])
    · exact nameChar_of_isNameChar (h.2 d hd)

theorem validHoleName_syn {n : List Char} (h : validHoleName n = true) :
    n ≠ [] ∧ ∀ c ∈ n, NameChar c := by
  match n, h with
  | c :: r, h =>
    simp only [validHoleName, Bool.and_eq_true, List.all_eq_true] at h
    refine ⟨by simp, ?_⟩
    intro d hd
    rcases List.mem_cons.mp hd with rfl | hd
    · apply nameChar_of_isNameChar
      rcases Bool.or_eq_true_iff.mp h.1 with h1 | h1
      · simp [isNameChar, h1]
      · simp [isNameChar] at h1 ⊢; simp [h1]
    · exact nameChar_of_isNameChar (h.2 d hd)

theorem validSrcAttrName_syn {n : List Char} (h : validSrcAttrName n = true) :
    n ≠ [] ∧ ∀ c ∈ n, NameChar c := by
  simp only [validSrcAttrName, Bool.and_eq_true, Bool.not_eq_true', List.isEmpty_eq_false_iff,
    List.all_eq_true] at h
  obtain ⟨⟨hne, _⟩, hall⟩ := h
  refine ⟨hne, fun c hc => ?_⟩
  have := hall c hc
  simp only [bne_iff_ne, ne_eq] at this
  obtain ⟨⟨⟨⟨⟨⟨⟨hw, h1⟩, h2⟩, h3⟩, _⟩, _⟩, _⟩, h4⟩ := this
  exact ⟨by rw [isWs_eq]; exact hw, h1, h2, h3, h4⟩

theorem notMem_of_all_ne {v : List Char} {q : Char} (h : v.all (· != q) = true) : q ∉ v := by
  intro hq
  have := List.all_eq_true.mp h q hq
  simp at this

theorem srcValue_ok {v : SrcValue} (h : v.ok = true) (hn : '\x00' ∉ v.render) : v.toAVal.OK := by
  cases v with
  | dquote x =>
    refine ⟨notMem_of_all_ne h, fun hx => hn ?_⟩
    simp [SrcValue.render, hx]
  | squote x =>
    refine ⟨notMem_of_all_ne h, fun hx => hn ?_⟩
    simp [SrcValue.render, hx]
  | unquoted x =>
    simp only [SrcValue.ok, Bool.and_eq_true, Bool.not_eq_true', List.isEmpty_eq_false_iff,
      List.all_eq_true] at h
    obtain ⟨hne, hall⟩ := h
    have hc : ∀ c ∈ x, isWs c = false ∧ c ≠ '>' ∧ c ≠ '"' ∧ c ≠ '\'' := by
      intro c hc
      have := hall c hc
      simp only [bne_iff_ne, ne_eq] at this
      obtain ⟨⟨⟨hw, h1⟩, h2⟩, h3⟩ := this
      exact ⟨by rw [isWs_eq]; exact hw, h1, h2, h3⟩
    refine ⟨hne, fun c hcx => ⟨(hc c hcx).1, (hc c hcx).2.1, fun h0 => hn ?_⟩, ?_, ?_⟩
    · subst h0; simpa [SrcValue.render] using hcx
    · cases x with
      | nil => exact absurd rfl hne
      | cons d r => simpa using (hc d (by simp)).2.2.1
    · cases x with
      | nil => exact absurd rfl hne
      | cons d r => simpa using (hc d (by simp)).2.2.2

theorem StaticAttr.toSyn_ok {a : StaticAttr} (h : a.ok = true) : a.toSyn.OK ∧ a.toSyn.ws ≠ [] := by
  cases a with
  | bare n =>
    have := validSrcAttrName_syn (by simpa [StaticAttr.ok] using h)
    exact ⟨⟨by simp [StaticAttr.toSyn, AllWs, isWs], this.1, this.2, trivial⟩, by simp [StaticAttr.toSyn]⟩
  | dquote n v =>
    simp only [StaticAttr.ok, Bool.and_eq_true] at h
    obtain ⟨⟨hn, hv⟩, h0⟩ := h
    have := validSrcAttrName_syn hn
    refine ⟨⟨by simp [StaticAttr.toSyn, AllWs, isWs], this.1, this.2, by simp [AllWs],
      by simp [AllWs], ?_⟩, by simp [StaticAttr.toSyn]⟩
    exact srcValue_ok (v := .dquote v) hv (by simp [SrcValue.render]; exact notMem_of_all_ne h0)
  | squote n v =>
    simp only [StaticAttr.ok, Bool.and_eq_true] at h
    obtain ⟨⟨hn, hv⟩, h0⟩ := h
    have := validSrcAttrName_syn hn
    refine ⟨⟨by simp [StaticAttr.toSyn, AllWs, isWs], this.1, this.2, by simp [AllWs],
      by simp [AllWs], ?_⟩, by simp [StaticAttr.toSyn]⟩
    exact srcValue_ok (v := .squote v) hv (by simp [SrcValue.render]; exact notMem_of_all_ne h0)
  | unquoted n v =>
    simp only [StaticAttr.ok, Bool.and_eq_true] at h
    obtain ⟨⟨hn, hv⟩, h0⟩ := h
    have := validSrcAttrName_syn hn
    refine ⟨⟨by simp [StaticAttr.toSyn, AllWs, isWs], this.1, this.2, by simp [AllWs],
      by simp [AllWs], ?_⟩, by simp [StaticAttr.toSyn]⟩
    exact srcValue_ok (v := .unquoted v) hv (by simp [SrcValue.render]; exact notMem_of_all_ne h0)

theorem holeAttr_ok {n x : List Char} (hn : validHoleName n = true) (hx : htmlSafe x) :
    (⟨[' '], n, some ([], [], .dq x)⟩ : AttrSyn).OK := by
  have := validHoleName_syn hn
  refine ⟨by simp [AllWs, isWs], this.1, this.2, by simp [AllWs], by simp [AllWs], ?_, ?_⟩
  · intro hq; exact (hx.1 '"' hq).2.2.1 rfl
  · intro hq; exact (hx.1 '\x00' hq).2.2.2.2 rfl

theorem itemsSyn_ok : ∀ (items : List TagItem) (vs : List (Option (List Char))),
    (∀ it ∈ items, match it with
      | .attr a => a.ok = true
      | .hole n _ => validHoleName n = true) →
    ItemValsOK items vs → ∀ a ∈ (itemsSyn items vs).1, a.OK ∧ a.ws ≠ []
  | [], _, _, _ => by simp [itemsSyn]
  | .attr s :: r, vs, hi, hv => by
    intro a ha
    simp only [itemsSyn, List.mem_cons] at ha
    rcases ha with rfl | ha
    · exact StaticAttr.toSyn_ok (by simpa using hi (.attr s) (by simp))
    · exact itemsSyn_ok r vs (fun it h => hi it (by simp [h])) (by simpa [ItemValsOK] using hv) a ha
  | .hole n c :: r, [], hi, hv => by
    simpa [itemsSyn] using itemsSyn_ok r [] (fun it h => hi it (by simp [h]))
      (by simpa [ItemValsOK] using hv)
  | .hole n c :: r, v :: vs, hi, hv => by
    intro a ha
    simp only [ItemValsOK] at hv
    cases v with
    | none =>
      simp only [itemsSyn, List.nil_append] at ha
      exact itemsSyn_ok r vs (fun it h => hi it (by simp [h])) hv.2 a ha
    | some x =>
      simp only [itemsSyn, List.singleton_append, List.mem_cons] at ha
      rcases ha with rfl | ha
      · exact ⟨holeAttr_ok (by simpa using hi (.hole n c) (by simp)) (hv.1 x rfl), by simp⟩
      · exact itemsSyn_ok r vs (fun it h => hi it (by simp [h])) hv.2 a ha

theorem chain_of_all : ∀ (q : Bool) (l : List AttrSyn), (∀ a ∈ l, a.OK ∧ a.ws ≠ []) → AttrsChain q l
  | _, [], _ => trivial
  | _, a :: r, h => ⟨(h a (by simp)).1, Or.inl (h a (by simp)).2,
      chain_of_all _ r (fun b hb => h b (by simp [hb]))⟩

theorem itemsSyn_noUnq : ∀ (items : List TagItem) (vs : List (Option (List Char))),
    (∀ n v, TagItem.attr (.unquoted n v) ∉ items) → ∀ a ∈ (itemsSyn items vs).1, a.isUnq = false
  | [], _, _ => by simp [itemsSyn]
  | .attr s :: r, vs, h => by
    intro a ha
    simp only [itemsSyn, List.mem_cons] at ha
    rcases ha with rfl | ha
    · cases s with
      | unquoted n v => exact absurd (by simp) (h n v)
      | _ => rfl
    · exact itemsSyn_noUnq r vs (fun n v hm => h n v (by simp [hm])) a ha
  | .hole n c :: r, [], h => by
    simpa [itemsSyn] using itemsSyn_noUnq r [] (fun n v hm => h n v (by simp [hm]))
  | .hole n c :: r, v :: vs, h => by
    intro a ha
    cases v with
    | none =>
      simp only [itemsSyn, List.nil_append] at ha
      exact itemsSyn_noUnq r vs (fun n v hm => h n v (by simp [hm])) a ha
    | some x =>
      simp only [itemsSyn, List.singleton_append, List.mem_cons] at ha
      rcases ha with rfl | ha
      · rfl
      · exact itemsSyn_noUnq r vs (fun n v hm => h n v (by simp [hm])) a ha

theorem SrcAttr.toSyn_endsQuoted (w : List Char) (a : SrcAttr) :
    (a.toSyn w).endsQuoted = a.endsQuoted := by
  rcases a with ⟨n, _ | ⟨w1, w2, v⟩⟩
  · rfl
  · cases v <;> rfl

theorem SrcAttr.toSyn_ok {w : List Char} {a : SrcAttr} (hw : w.all isAsciiWs = true)
    (ha : a.ok = true) (hn : '\x00' ∉ a.render) : (a.toSyn w).OK := by
  rcases a with ⟨n, _ | ⟨w1, w2, v⟩⟩
  · simp only [SrcAttr.ok, Bool.and_eq_true] at ha
    have := validSrcAttrName_syn ha.1
    exact ⟨allWs_of_all hw, this.1, this.2, trivial⟩
  · simp only [SrcAttr.ok, Bool.and_eq_true] at ha
    obtain ⟨hnm, ⟨h1, h2⟩, hv⟩ := ha
    have := validSrcAttrName_syn hnm
    refine ⟨allWs_of_all hw, this.1, this.2, allWs_of_all h1, allWs_of_all h2, ?_⟩
    exact srcValue_ok hv (fun h0 => hn (by simp [SrcAttr.render, h0]))

theorem srcChain_attrs : ∀ (q : Bool) (items : List (List Char × SrcAttr)),
    SrcChain q items → AttrsChain q (items.map fun x => SrcAttr.toSyn x.1 x.2)
  | _, [], _ => trivial
  | q, (w, a) :: r, ⟨hw, ha, hn, hq, hr⟩ => by
    refine ⟨SrcAttr.toSyn_ok hw ha hn, ?_, ?_⟩
    · rcases hq with hq | hq
      · exact Or.inl hq
      · exact Or.inr hq
    · rw [SrcAttr.toSyn_endsQuoted]; exact srcChain_attrs _ r hr

theorem SrcTag.toSyn_ok {t : SrcTag} (h : SrcTagOK t) : t.toSyn.OK := by
  obtain ⟨hn, ht, hc, hsc⟩ := h
  obtain ⟨h1, h2⟩ := validElementName_syn hn
  refine ⟨h1, h2, srcChain_attrs false t.items hc, allWs_of_all ht, ?_⟩
  intro hs htr a hl
  simp only [SrcTag.toSyn, List.getLast?_map, Option.map_eq_some_iff] at hl
  obtain ⟨⟨w, b⟩, hb, rfl⟩ := hl
  rcases hsc hs htr w b hb with hq | hv
  · rcases b with ⟨n, _ | ⟨w1, w2, v⟩⟩
    · rfl
    · cases v <;> simp_all [SrcAttr.endsQuoted, SrcValue.quoted, SrcAttr.toSyn, AttrSyn.isUnq,
        SrcValue.toAVal]
  · rcases b with ⟨n, _ | ⟨w1, w2, v⟩⟩
    · rfl
    · simp at hv

/-! ### The four bridge statements -/

theorem StartTag.toSyn_render (t : StartTag) (vs : List (Option (List Char))) :
    (t.toSyn vs).1.render = (t.render vs).1 ∧ (t.toSyn vs).2 = (t.render vs).2 := by
  obtain ⟨h1, h2⟩ := itemsSyn_render t.items vs
  rcases hs : t.source with _ | src <;> cases ha : t.items.all TagItem.isAttr <;>
    simp only [StartTag.toSyn, StartTag.render, hs, ha] <;>
    first
    | exact ⟨SrcTag.toSyn_render src, trivial⟩
    | (simp only [TagSyn.render, h1, h2, and_true]
       cases t.selfClosing <;> cases t.spaceBeforeSlash <;> simp)

theorem StartTag.toSyn_name (t : StartTag) (vs : List (Option (List Char))) (h : StartTag2OK t) :
    (t.toSyn vs).1.name = t.name := by
  rcases hs : t.source with _ | src <;> cases ha : t.items.all TagItem.isAttr <;>
    simp only [StartTag.toSyn, hs, ha]
  exact (h.2.2.2.2 src hs).2.2.1

theorem StartTag.toSyn_ok (t : StartTag) (vs : List (Option (List Char))) (h : StartTag2OK t)
    (hv : t.ValsOK vs) : (t.toSyn vs).1.OK := by
  obtain ⟨hname, hitems, _, hslash, hsrc⟩ := h
  rcases hs : t.source with _ | src <;> cases ha : t.items.all TagItem.isAttr <;>
    simp only [StartTag.toSyn, hs, ha] <;>
    first
    | exact SrcTag.toSyn_ok (hsrc src hs).1
    | (have hreb : t.rebuilt = true := by simp [StartTag.rebuilt, hs, ha]
       have hvals : ItemValsOK t.items vs := by
         simpa [StartTag.ValsOK, hs, ha] using hv
       obtain ⟨hn1, hn2⟩ := validElementName_syn hname.1
       refine ⟨hn1, hn2, chain_of_all false _ (itemsSyn_ok t.items vs hitems hvals), ?_, ?_⟩
       · split <;> simp [AllWs, isWs]
       · intro hsc htr a hl
         dsimp only at hsc htr hl
         have hsp : t.spaceBeforeSlash = false := by
           cases hb : t.spaceBeforeSlash
           · rfl
           · simp [hsc, hb] at htr
         exact itemsSyn_noUnq t.items vs (hslash hreb hsc hsp) a (List.mem_of_getLast? hl))


/-- The browser's lowercasing is the compiler's ASCII lowercasing. -/
theorem lowerStr_eq_asciiLower (s : List Char) : lowerStr s = asciiLower s := rfl


/-! ### Duplicate attribute names carry over (S3) -/

theorem itemsSyn_names_sublist : ∀ (items : List TagItem) (vs : List (Option (List Char))),
    List.Sublist (((itemsSyn items vs).1.map AttrSyn.token).map Prod.fst)
      (items.map (fun it => asciiLower it.name))
  | [], vs => by simp [itemsSyn]
  | .attr a :: r, vs => by
    have ih := itemsSyn_names_sublist r vs
    simp only [itemsSyn, List.map_cons]
    have : (a.toSyn.token).1 = asciiLower (TagItem.attr a).name := by
      simp only [AttrSyn.token, lowerStr_eq_asciiLower]; cases a <;> rfl
    rw [this]
    exact ih.cons_cons _
  | .hole n c :: r, [] => by
    have ih := itemsSyn_names_sublist r []
    simpa [itemsSyn] using ih.trans (by simp)
  | .hole n c :: r, v :: vs => by
    have ih := itemsSyn_names_sublist r vs
    cases v with
    | none =>
      simp only [itemsSyn, List.nil_append, List.map_cons]
      exact ih.trans (List.sublist_cons_self _ _)
    | some x =>
      simp only [itemsSyn, List.singleton_append, List.map_cons]
      have : ((⟨[' '], n, some ([], [], AVal.dq x)⟩ : AttrSyn).token).1
          = asciiLower (TagItem.hole n c).name := by
        simp [AttrSyn.token, lowerStr_eq_asciiLower, TagItem.name]
      rw [this]
      exact ih.cons_cons _

theorem StartTag.toSyn_nodup (t : StartTag) (vs : List (Option (List Char))) (h : TagAttrsOK t) :
    (((t.toSyn vs).1.attrs.map AttrSyn.token).map Prod.fst).Nodup := by
  obtain ⟨_, hnodup, _, hsrc⟩ := h
  rcases hs : t.source with _ | src <;> cases ha : t.items.all TagItem.isAttr
  case none.false | none.true | some.false =>
    all_goals
      simp only [StartTag.toSyn, hs, ha]
      exact (itemsSyn_names_sublist t.items vs).nodup hnodup
  case some.true =>
    simp only [StartTag.toSyn, hs, ha, SrcTag.toSyn]
    have he : (((src.items.map fun x => SrcAttr.toSyn x.1 x.2).map AttrSyn.token).map Prod.fst)
        = src.items.map (fun x => asciiLower x.2.name) := by
      simp only [List.map_map]
      exact List.map_congr_left fun x _ => by
        simp [AttrSyn.token, SrcAttr.toSyn, lowerStr_eq_asciiLower]
    rw [he]; exact (hsrc src hs).2.1



/-! ### The script tag (slice 3) -/

theorem asciiLowerChar_alpha {c : Char} (h : isAlpha (asciiLowerChar c) = true) : isAlpha c = true := by
  unfold asciiLowerChar Char.toLower at h
  split at h
  · rename_i hup
    have h1 : ('A' : Char) ≤ c := Char.le_def.mpr hup.1
    have h2 : c ≤ ('Z' : Char) := Char.le_def.mpr hup.2
    show (('a' ≤ c && c ≤ 'z') || ('A' ≤ c && c ≤ 'Z')) = true
    simp [h1, h2]
  · exact h

theorem mem_alpha_script {c : Char} (h : c ∈ "script".toList) : isAlpha c = true := by
  have : "script".toList = ['s', 'c', 'r', 'i', 'p', 't'] := rfl
  rw [this, List.mem_cons, List.mem_cons, List.mem_cons, List.mem_cons, List.mem_cons,
    List.mem_singleton] at h
  rcases h with rfl | rfl | rfl | rfl | rfl | rfl <;> decide

theorem script_name_alpha {n : List Char} (h : asciiLower n = "script".toList) :
    ∀ c ∈ n, isAlpha c = true := by
  intro c hc
  exact asciiLowerChar_alpha (mem_alpha_script (h ▸ List.mem_map_of_mem hc))


theorem nameChar_of_alpha {c : Char} (h : isAlpha c = true) : NameChar c := by
  simp only [isAlpha, Bool.or_eq_true, Bool.and_eq_true, decide_eq_true_eq] at h
  refine ⟨?_, ?_, ?_, ?_, ?_⟩
  · cases hw : isWs c with
    | false => rfl
    | true =>
      exfalso
      rcases (by simpa [isWs, or_assoc] using hw :
          c = '\t' ∨ c = '\n' ∨ c = '\x0C' ∨ c = '\r' ∨ c = ' ')
        with rfl | rfl | rfl | rfl | rfl <;> rcases h with ⟨a,_⟩|⟨a,_⟩ <;> exact absurd a (by decide)
  all_goals (rintro rfl; rcases h with ⟨a,_⟩|⟨a,_⟩ <;> exact absurd a (by decide))

theorem scriptName_facts {n : List Char} (h : asciiLower n = "script".toList) :
    (∃ c r, n = c :: r ∧ isAlpha c = true) ∧ ∀ c ∈ n, NameChar c := by
  have hne : n ≠ [] := by
    rintro rfl; simp [asciiLower] at h
  have halpha := script_name_alpha h
  refine ⟨?_, fun c hc => nameChar_of_alpha (halpha c hc)⟩
  match n, hne with
  | c :: r, _ => exact ⟨c, r, rfl, halpha c (by simp)⟩

theorem StartTag.toSyn_name_script (e : ScriptEl) (vs : List (Option (List Char)))
    (h : ScriptOK e) : (e.tag.toSyn vs).1.name = e.tag.name := by
  obtain ⟨_, _, hattrs, _⟩ := h
  rcases hs : e.tag.source with _ | src <;> cases ha : e.tag.items.all TagItem.isAttr <;>
    simp only [StartTag.toSyn, hs, ha]
  exact (hattrs.2.2.2 src hs).2.2.1

theorem StartTag.toSyn_ok_script (e : ScriptEl) (vs : List (Option (List Char)))
    (h : ScriptOK e) (hv : e.tag.ValsOK vs) : (e.tag.toSyn vs).1.OK := by
  obtain ⟨hsn, _, hattrs, _⟩ := h
  obtain ⟨hitems, _, hslash, hsrc⟩ := hattrs
  obtain ⟨hnfacts, hncc⟩ := scriptName_facts hsn
  generalize ht : e.tag = t at hitems hslash hsrc hv hnfacts hncc ⊢
  rcases hs : t.source with _ | src <;> cases ha : t.items.all TagItem.isAttr <;>
    simp only [StartTag.toSyn, hs, ha] <;>
    first
    | exact SrcTag.toSyn_ok (hsrc src hs).1
    | (have hreb : t.rebuilt = true := by simp [StartTag.rebuilt, hs, ha]
       have hvals : ItemValsOK t.items vs := by simpa [StartTag.ValsOK, hs, ha] using hv
       refine ⟨hnfacts, hncc, chain_of_all false _ (itemsSyn_ok t.items vs hitems hvals), ?_, ?_⟩
       · split <;> simp [AllWs, isWs]
       · intro hsc htr a hl
         dsimp only at hsc htr hl
         have hsp : t.spaceBeforeSlash = false := by
           cases hb : t.spaceBeforeSlash
           · rfl
           · simp [hsc, hb] at htr
         exact itemsSyn_noUnq t.items vs (hslash hreb hsc hsp) a (List.mem_of_getLast? hl))


end TesseraT1
