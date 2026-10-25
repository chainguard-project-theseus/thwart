/-
Theorem T1, slice 4: slice 3 plus the raw-text elements of the profile
(docs/security-architecture.md §5.3): `title` and `textarea` (RCDATA, with
`Escapers.rcdata` holes), `style` (RAWTEXT, no holes) and `iframe` (RAWTEXT, empty).
-/
import TesseraT1.T1Slice3
import TesseraT1.RawText
import TesseraT1.RawBridge

namespace TesseraT1

open Whatwg

/-! ### A raw-text body: static text and holes -/

/-- The body conditions in the shape the proof walks, with the values the holes
consume. `n` is the element's lowercased name. A static part has no NUL and no
browser end tag (S8); it is followed by a hole or nothing; before a hole it leaves
the plain state (S12). A hole's value, if present, has no `<` and no NUL. -/
def BodyOKR (n : List Char) : List ScriptPart → List (Option (List Char)) → Prop
  | [], _ => True
  | .hole :: r, [] => BodyOKR n r []
  | .hole :: r, v :: vs => (∀ x, v = some x → '<' ∉ x ∧ '\x00' ∉ x) ∧ BodyOKR n r vs
  | .static s :: r, vs => '\x00' ∉ s ∧ NoRawDangerS n s ∧
      (match r with
       | [] => True
       | .static _ :: _ => False
       | .hole :: _ => (∀ P', s ≠ P' ++ ['<']) ∧
           (∀ P' buf, (∀ c ∈ buf, isAlpha c = true) → s ≠ P' ++ ['<', '/'] ++ buf)) ∧
      BodyOKR n r vs

theorem bodyR_run (tr : Tree) {k : Raw} (hk : NotScript k) (n : List Char) :
    ∀ (body : List ScriptPart) (vs : List (Option (List Char))), BodyOKR n body vs →
      ∃ st' P out, run tr ⟨.raw k, n⟩ (renderScript body vs).1 = (⟨st', n⟩, out.map .char) ∧
        GoodR k st' P ∧ (renderScript body vs).1 = out ++ pend st'
  | [], _, _ => ⟨.raw k, [], [], by simp [renderScript, run], .raw [], by simp [renderScript, pend]⟩
  | .hole :: r, [], hb => by
    simpa [renderScript] using bodyR_run tr hk n r [] hb
  | .hole :: r, none :: vs, ⟨_, hr⟩ => by
    simpa [renderScript] using bodyR_run tr hk n r vs hr
  | .hole :: r, some x :: vs, ⟨hv, hr⟩ => by
    obtain ⟨hl, hn⟩ := hv x rfl
    obtain ⟨s2, P, o2, e2, g2, p2⟩ := bodyR_run tr hk n r vs hr
    refine ⟨s2, P, x ++ o2, ?_, g2, ?_⟩
    · simp only [renderScript, Option.getD_some]
      rw [run_append, raw_plain_run tr k n x hl hn]; simp only; rw [e2]; simp
    · simp only [renderScript, Option.getD_some]
      rw [p2]; simp
  | .static s :: r, vs, ⟨hn, hd, hnext, hr⟩ => by
    obtain ⟨s1, o1, e1, g1, p1⟩ := goodR_run tr hk n s (GoodR.raw []) hn (noRawDanger_prefix hd)
    simp only [List.nil_append] at g1
    rw [show pend (St.raw k) = [] from rfl, List.nil_append] at p1
    match r, hnext, hr with
    | [], _, _ =>
      refine ⟨s1, s, o1, ?_, g1, ?_⟩
      · simpa [renderScript] using e1
      · simpa [renderScript] using p1
    | .hole :: r', ⟨hl, hs⟩, hr =>
      have hst : s1 = .raw k := goodR_plain g1 hl hs
      subst hst
      rw [show pend (St.raw k) = [] from rfl, List.append_nil] at p1
      -- The hole's value (if any) keeps the plain state; then the rest of the body.
      have rest : ∀ (x : List Char) (vs' : List (Option (List Char))), '<' ∉ x → '\x00' ∉ x →
          BodyOKR n r' vs' →
          ∃ st' P out, run tr ⟨.raw k, n⟩ (s ++ (x ++ (renderScript r' vs').1)) =
            (⟨st', n⟩, out.map .char) ∧ GoodR k st' P ∧
            s ++ (x ++ (renderScript r' vs').1) = out ++ pend st' := by
        intro x vs' hl hn' hr'
        obtain ⟨s2, P, o2, e2, g2, p2⟩ := bodyR_run tr hk n r' vs' hr'
        refine ⟨s2, P, o1 ++ x ++ o2, ?_, g2, ?_⟩
        · rw [run_append, e1]; simp only
          rw [run_append, raw_plain_run tr k n x hl hn']; simp only
          rw [e2]; simp
        · rw [← p1, p2]; simp
      match vs, hr with
      | [], hr => simpa [renderScript] using rest [] [] (by simp) (by simp) hr
      | none :: vs', ⟨_, hr⟩ => simpa [renderScript] using rest [] vs' (by simp) (by simp) hr
      | some x :: vs', ⟨hv, hr⟩ =>
        obtain ⟨hl', hn'⟩ := hv x rfl
        simpa [renderScript] using rest x vs' hl' hn' hr

/-- **A raw-text element's content and end tag.** From the RCDATA/RAWTEXT state just
after the start tag, the body and Tessera's end tag are the body's characters and one
EndTag. -/
theorem run_rawBody (tr : Tree) {k : Raw} (hk : NotScript k) {n : List Char}
    {body : List ScriptPart} {vs : List (Option (List Char))} (hb : BodyOKR n body vs)
    {E : List Char} (hE : lowerStr E = n) (hE0 : E ≠ []) (hEa : ∀ c ∈ E, isAlpha c = true) :
    run tr ⟨.raw k, n⟩ ((renderScript body vs).1 ++ ['<', '/'] ++ E ++ ['>']) =
      (⟨.data, n⟩, (renderScript body vs).1.map .char ++ [.endTag n]) := by
  obtain ⟨st, P, out, e, g, p⟩ := bodyR_run tr hk n body vs hb
  rw [List.append_assoc, List.append_assoc, run_append, e]
  simp only
  rw [← List.append_assoc, goodR_close tr hk g hE hE0 hEa, p]
  simp

/-- The compiler-side raw body conditions give the shape `bodyR_run` walks. -/
theorem bodyOKR_of (n : List Char) :
    ∀ (body : List ScriptPart) (vs : List (Option (List Char))),
      (∀ p ∈ body, match p with | .static s => NoRawDanger n s | .hole => True) →
      NormalScript body →
      (∀ i s, body[i]? = some (.static s) → body[i+1]? = some .hole → NoRawLtBeforeHole s) →
      RawValsOK body vs → BodyOKR n body vs
  | [], _, _, _, _, _ => trivial
  | .hole :: r, [], hd, hn, hl, hv =>
    bodyOKR_of n r [] (fun p hp => hd p (List.mem_cons_of_mem _ hp))
      (by cases r with | nil => trivial | cons q r => cases q <;> exact hn)
      (fun i s h1 h2 => hl (i + 1) s (by simpa using h1) (by simpa using h2)) hv
  | .hole :: r, v :: vs, hd, hn, hl, ⟨hx, hr⟩ =>
    ⟨fun x hvx => htmlSafe_lt_nul (hx x hvx),
      bodyOKR_of n r vs (fun p hp => hd p (List.mem_cons_of_mem _ hp))
        (by cases r with | nil => trivial | cons q r => cases q <;> exact hn)
        (fun i s h1 h2 => hl (i + 1) s (by simpa using h1) (by simpa using h2)) hr⟩
  | .static s :: r, vs, hd, hn, hl, hv => by
    have hs : NoRawDanger n s := hd (.static s) (by simp)
    obtain ⟨hns, hds⟩ := noRawDangerS_of hs
    refine ⟨hns, hds, ?_, bodyOKR_of n r vs (fun p hp => hd p (List.mem_cons_of_mem _ hp)) ?_
      (fun i s h1 h2 => hl (i + 1) s (by simpa using h1) (by simpa using h2)) hv⟩
    · match r, hn with
      | [], _ => trivial
      | .static _ :: _, hn => exact hn.elim
      | .hole :: _, _ => exact hl 0 s rfl rfl
    · match r, hn with
      | [], _ => trivial
      | .static _ :: _, hn => exact hn.elim
      | .hole :: _, hn => exact hn

/-- A raw-text element from the data state: its StartTag, its content as characters,
and one EndTag with its lowercased name. -/
theorem run_rawEl (tr : Tree) (ls : List Char) {e : RawEl} {vs : List (Option (List Char))}
    (he : RawOK e) (hstub : tr (lowerStr e.tag.name) = .raw (rawKind (asciiLower e.tag.name)))
    (hvt : e.tag.ValsOK vs) (hvb : RawValsOK e.body (e.tag.render vs).2) :
    run tr ⟨.data, ls⟩ (e.render vs).1 =
      (⟨.data, lowerStr e.tag.name⟩, (e.tag.toSyn vs).1.tessera ::
        ((renderScript e.body (e.tag.render vs).2).1.map .char ++
          [.endTag (lowerStr e.tag.name)])) := by
  obtain ⟨hr1, hr2⟩ := StartTag.toSyn_render e.tag vs
  obtain ⟨hkind, hsc, hattrs, hparts, hnorm, hlt⟩ := he
  have hfacts := rawName_facts hkind
  simp only [RawEl.render]
  rw [← hr1, List.append_assoc, List.append_assoc, List.append_assoc, run_append,
    run_startTag tr ls (StartTag.toSyn_ok_of e.tag vs hfacts hattrs hvt),
    token_eq_tessera (StartTag.toSyn_nodup e.tag vs hattrs), StartTag.toSyn_name_of e.tag vs hattrs,
    hstub]
  simp only [List.singleton_append]
  have hb := bodyOKR_of (lowerStr e.tag.name) e.body _ hparts hnorm hlt hvb
  rw [← List.append_assoc, ← List.append_assoc,
    run_rawBody tr (rawKind_notScript _) hb rfl (rawName_ne hkind) (rawName_alpha hkind)]

/-! ### Slice-4 templates -/

/-- A slice-4 template piece: the slice-3 pieces and raw-text elements. -/
inductive Piece4 where
  | static (s : List Char)
  | holeText
  | startTag (t : StartTag)
  | endTag (name : List Char)
  | comment (b : List Char)
  | doctype (d : Doctype)
  | script (e : ScriptEl)
  | raw (e : RawEl)

def render4 : List Piece4 → List (Option (List Char)) → List Char
  | [], _ => []
  | .static s :: r, vs => s ++ render4 r vs
  | .holeText :: r, [] => render4 r []
  | .holeText :: r, v :: vs => v.getD [] ++ render4 r vs
  | .startTag t :: r, vs => (t.render vs).1 ++ render4 r (t.render vs).2
  | .endTag n :: r, vs => ['<', '/'] ++ n ++ ['>'] ++ render4 r vs
  | .comment b :: r, vs => "<!--".toList ++ b ++ "-->".toList ++ render4 r vs
  | .doctype d :: r, vs => d.render ++ render4 r vs
  | .script e :: r, vs => (e.render vs).1 ++ render4 r (e.render vs).2
  | .raw e :: r, vs => (e.render vs).1 ++ render4 r (e.render vs).2

def ValsOK4 : List Piece4 → List (Option (List Char)) → Prop
  | [], _ => True
  | .static _ :: r, vs => ValsOK4 r vs
  | .holeText :: r, [] => ValsOK4 r []
  | .holeText :: r, v :: vs => (∀ x, v = some x → htmlTextSafe x) ∧ ValsOK4 r vs
  | .startTag t :: r, vs => t.ValsOK vs ∧ ValsOK4 r (t.render vs).2
  | .endTag _ :: r, vs => ValsOK4 r vs
  | .comment _ :: r, vs => ValsOK4 r vs
  | .doctype _ :: r, vs => ValsOK4 r vs
  | .script e :: r, vs => e.tag.ValsOK vs ∧ ScriptValsOK e.body (e.tag.render vs).2 ∧
      ValsOK4 r (e.render vs).2
  | .raw e :: r, vs => e.tag.ValsOK vs ∧ RawValsOK e.body (e.tag.render vs).2 ∧
      ValsOK4 r (e.render vs).2

/-- Tessera's reading of a slice-4 page. A raw-text element is its start tag, its
content (static text and RCDATA values) as characters, and its end tag. -/
def denote4 : List Piece4 → List (Option (List Char)) → List Token
  | [], _ => []
  | .static s :: r, vs => s.map .char ++ denote4 r vs
  | .holeText :: r, [] => denote4 r []
  | .holeText :: r, v :: vs => textTokens (v.getD []) ++ denote4 r vs
  | .startTag t :: r, vs => (t.toSyn vs).1.tessera :: denote4 r (t.render vs).2
  | .endTag n :: r, vs => .endTag (lowerStr n) :: denote4 r vs
  | .comment b :: r, vs => .comment b :: denote4 r vs
  | .doctype _ :: r, vs => .doctype (some "html".toList) false :: denote4 r vs
  | .script e :: r, vs =>
    (e.tag.toSyn vs).1.tessera ::
      ((renderScript e.body (e.tag.render vs).2).1.map .char ++ [.endTag scriptName] ++
        denote4 r (e.render vs).2)
  | .raw e :: r, vs =>
    (e.tag.toSyn vs).1.tessera ::
      ((renderScript e.body (e.tag.render vs).2).1.map .char ++ [.endTag (lowerStr e.tag.name)] ++
        denote4 r (e.render vs).2)

def Piece4.ok : Piece4 → Prop
  | .static s => StaticText s
  | .holeText => True
  | .startTag t => StartTag2OK t
  | .endTag n => Slice2Name n
  | .comment b => CommentTessera b
  | .doctype d => DoctypeOK d
  | .script e => ScriptOK e
  | .raw e => RawOK e

def Normal4 : List Piece4 → Prop
  | [] => True
  | [_] => True
  | .static _ :: .static _ :: _ => False
  | _ :: p :: r => Normal4 (p :: r)

/-- Slice-4 membership: as `InSubset3`, with raw-text elements (`RawOK`). -/
def InSubset4 (T : List Piece4) : Prop :=
  Normal4 T ∧ (∀ p ∈ T, p.ok) ∧
  (∀ i s, T[i]? = some (.static s) → T[i + 1]? = some .holeText → NoLtBeforeHole s)

def StartsMarkup4 : List Piece4 → Prop
  | .startTag _ :: _ | .endTag _ :: _ | .comment _ :: _ | .doctype _ :: _ | .script _ :: _
  | .raw _ :: _ => True
  | _ => False

def Ok4 : List Piece4 → Prop
  | [] => True
  | .static s :: r => StaticText s ∧ (s.getLast? = some '<' → r = [] ∨ StartsMarkup4 r) ∧ Ok4 r
  | .holeText :: r => Ok4 r
  | .startTag t :: r => StartTag2OK t ∧ Ok4 r
  | .endTag n :: r => Slice2Name n ∧ Ok4 r
  | .comment b :: r => CommentTessera b ∧ Ok4 r
  | .doctype d :: r => DoctypeOK d ∧ Ok4 r
  | .script e :: r => ScriptOK e ∧ Ok4 r
  | .raw e :: r => RawOK e ∧ Ok4 r

theorem render4_markup {r : List Piece4} (h : StartsMarkup4 r) (vs : List (Option (List Char))) :
    ∃ rest, render4 r vs = '<' :: rest := by
  match r, h with
  | .startTag t :: r, _ =>
    simp only [render4]
    unfold StartTag.render
    split <;> simp [SrcTag.render]
  | .script e :: r, _ =>
    simp only [render4, ScriptEl.render]
    unfold StartTag.render
    split <;> simp [SrcTag.render]
  | .raw e :: r, _ =>
    simp only [render4, RawEl.render]
    unfold StartTag.render
    split <;> simp [SrcTag.render]
  | .endTag _ :: _, _ => simp [render4]
  | .comment _ :: _, _ => simp [render4]
  | .doctype _ :: _, _ => simp [render4, Doctype.render]

theorem t1_ok4 :
    ∀ (T : List Piece4) (vs : List (Option (List Char))) (ls : List Char), Ok4 T → ValsOK4 T vs →
      tokensFrom treeStub ⟨.data, ls⟩ (render4 T vs) = denote4 T vs
  | [], _, _, _, _ => by simp [render4, denote4, tokensFrom, run, eofTokens]
  | .holeText :: r, [], ls, h, hv => by
    simpa [render4, denote4] using t1_ok4 r [] ls h hv
  | .holeText :: r, v :: vs, ls, h, hv => by
    simp only [render4, denote4]
    rw [tokensFrom_append]
    cases v with
    | none => simpa [run, textTokens] using t1_ok4 r vs ls h hv.2
    | some x =>
      rw [Option.getD_some, run_textChunks treeStub ls (htmlTextSafe_chunks (hv.1 x rfl))]
      rw [t1_ok4 r vs ls h hv.2]
  | .startTag t :: r, vs, ls, ⟨ht, h⟩, ⟨hvt, hv⟩ => by
    simp only [render4, denote4]
    obtain ⟨hr1, hr2⟩ := StartTag.toSyn_render t vs
    rw [← hr1, ← hr2, tokensFrom_append, run_startTag treeStub ls (StartTag.toSyn_ok t vs ht hvt),
      token_eq_tessera (StartTag.toSyn_nodup t vs ht.2),
      StartTag.toSyn_name t vs ht, treeStub_slice2 ht.1]
    rw [hr2] at *
    simp [t1_ok4 r _ (lowerStr t.name) h hv]
  | .endTag n :: r, vs, ls, ⟨hn, h⟩, hv => by
    simp only [render4, denote4]
    obtain ⟨h1, h2⟩ := slice2Name_ok hn
    rw [tokensFrom_append, run_endTag treeStub ls h1 h2]
    simp [t1_ok4 r vs ls h hv]
  | .comment b :: r, vs, ls, ⟨hb, h⟩, hv => by
    simp only [render4, denote4]
    have e : "<!--".toList ++ b ++ "-->".toList = ['<', '!', '-', '-'] ++ b ++ ['-', '-', '>'] := rfl
    rw [e, tokensFrom_append, run_comment treeStub ls (commentBodyOK_of_tessera hb)]
    simp [t1_ok4 r vs ls h hv]
  | .doctype d :: r, vs, ls, ⟨hd, h⟩, hv => by
    simp only [render4, denote4]
    rw [tokensFrom_append, run_doctype treeStub ls hd]
    simp [t1_ok4 r vs ls h hv]
  | .script e :: r, vs, ls, ⟨he, h⟩, ⟨hvt, hvb, hv⟩ => by
    simp only [render4, denote4]
    rw [tokensFrom_append, run_script treeStub (by decide) ls he hvt hvb]
    simp [t1_ok4 r _ scriptName h hv]
  | .raw e :: r, vs, ls, ⟨he, h⟩, ⟨hvt, hvb, hv⟩ => by
    simp only [render4, denote4]
    rw [tokensFrom_append, run_rawEl treeStub ls he (by rw [lowerStr_eq_asciiLower]; exact treeStub_raw he.1) hvt hvb]
    simp [t1_ok4 r _ (lowerStr e.tag.name) h hv]
  | .static s :: r, vs, ls, ⟨hs, hlast, h⟩, hv => by
    simp only [render4, denote4]
    rw [tokensFrom_append]
    rcases run_static treeStub ls s hs with ⟨_, hrun⟩ | ⟨hl, hrun⟩
    · rw [hrun, t1_ok4 r vs ls h hv]
    · rw [hrun]
      have hs' : s = s.dropLast ++ ['<'] := by
        have hne : s ≠ [] := by rintro rfl; simp at hl
        have hg := List.dropLast_concat_getLast hne
        rw [List.getLast?_eq_some_getLast hne] at hl
        simp only [Option.some.injEq] at hl
        rw [hl] at hg
        exact hg.symm
      conv => rhs; rw [hs']
      rcases hlast hl with rfl | hm
      · cases vs <;> simp [render4, denote4, tokensFrom, run, eofTokens]
      · obtain ⟨rest, hrest⟩ := render4_markup hm vs
        rw [hrest, tokensFrom_tagOpen_lt, ← hrest, t1_ok4 r vs ls h hv]
        simp

/-- Where a slice-4 run ends: the data state, or tag open after a final `<`. -/
def EndsOK4 (m : M) : Prop := m.st = .data ∨ m.st = .tagOpen

theorem run_ok4 :
    ∀ (T : List Piece4) (vs : List (Option (List Char))) (ls : List Char), Ok4 T → ValsOK4 T vs →
      EndsOK4 (run treeStub ⟨.data, ls⟩ (render4 T vs)).1
  | [], _, _, _, _ => by simp [render4, run, EndsOK4]
  | .holeText :: r, [], ls, h, hv => by
    simpa [render4] using run_ok4 r [] ls h hv
  | .holeText :: r, v :: vs, ls, h, hv => by
    simp only [render4]
    rw [run_append]
    cases v with
    | none => simpa [run] using run_ok4 r vs ls h hv.2
    | some x =>
      rw [Option.getD_some, run_textChunks treeStub ls (htmlTextSafe_chunks (hv.1 x rfl))]
      exact run_ok4 r vs ls h hv.2
  | .startTag t :: r, vs, ls, ⟨ht, h⟩, ⟨hvt, hv⟩ => by
    simp only [render4]
    obtain ⟨hr1, hr2⟩ := StartTag.toSyn_render t vs
    rw [← hr1, ← hr2, run_append, run_startTag treeStub ls (StartTag.toSyn_ok t vs ht hvt),
      StartTag.toSyn_name t vs ht, treeStub_slice2 ht.1]
    rw [hr2] at *
    exact run_ok4 r _ (lowerStr t.name) h hv
  | .endTag n :: r, vs, ls, ⟨hn, h⟩, hv => by
    simp only [render4]
    obtain ⟨h1, h2⟩ := slice2Name_ok hn
    rw [run_append, run_endTag treeStub ls h1 h2]
    exact run_ok4 r vs ls h hv
  | .comment b :: r, vs, ls, ⟨hb, h⟩, hv => by
    simp only [render4]
    have e : "<!--".toList ++ b ++ "-->".toList = ['<', '!', '-', '-'] ++ b ++ ['-', '-', '>'] := rfl
    rw [e, run_append, run_comment treeStub ls (commentBodyOK_of_tessera hb)]
    exact run_ok4 r vs ls h hv
  | .doctype d :: r, vs, ls, ⟨hd, h⟩, hv => by
    simp only [render4]
    rw [run_append, run_doctype treeStub ls hd]
    exact run_ok4 r vs ls h hv
  | .script e :: r, vs, ls, ⟨he, h⟩, ⟨hvt, hvb, hv⟩ => by
    simp only [render4]
    rw [run_append, run_script treeStub (by decide) ls he hvt hvb]
    exact run_ok4 r _ scriptName h hv
  | .raw e :: r, vs, ls, ⟨he, h⟩, ⟨hvt, hvb, hv⟩ => by
    simp only [render4]
    rw [run_append, run_rawEl treeStub ls he (by rw [lowerStr_eq_asciiLower]; exact treeStub_raw he.1) hvt hvb]
    exact run_ok4 r _ (lowerStr e.tag.name) h hv
  | .static s :: r, vs, ls, ⟨hs, hlast, h⟩, hv => by
    simp only [render4]
    rw [run_append]
    rcases run_static treeStub ls s hs with ⟨_, hrun⟩ | ⟨hl, hrun⟩
    · rw [hrun]; exact run_ok4 r vs ls h hv
    · rw [hrun]
      rcases hlast hl with rfl | hm
      · cases vs <;> simp [render4, run, EndsOK4]
      · obtain ⟨rest, hrest⟩ := render4_markup hm vs
        have e : run treeStub ⟨.tagOpen, ls⟩ (render4 r vs) =
            ((run treeStub ⟨.data, ls⟩ (render4 r vs)).1,
              .char '<' :: (run treeStub ⟨.data, ls⟩ (render4 r vs)).2) := by
          rw [hrest]; simp [run, step, stepData, isAlpha]
        rw [e]; exact run_ok4 r vs ls h hv

theorem normal4_tail {p : Piece4} {r : List Piece4} (h : Normal4 (p :: r)) : Normal4 r := by
  match p, r, h with
  | _, [], _ => trivial
  | .static _, .static _ :: _, h => exact h.elim
  | .static _, .holeText :: _, h => exact h
  | .static _, .startTag _ :: _, h => exact h
  | .static _, .endTag _ :: _, h => exact h
  | .static _, .comment _ :: _, h => exact h
  | .static _, .doctype _ :: _, h => exact h
  | .static _, .script _ :: _, h => exact h
  | .static _, .raw _ :: _, h => exact h
  | .holeText, q :: _, h => cases q <;> exact h
  | .startTag _, q :: _, h => cases q <;> exact h
  | .endTag _, q :: _, h => cases q <;> exact h
  | .comment _, q :: _, h => cases q <;> exact h
  | .doctype _, q :: _, h => cases q <;> exact h
  | .script _, q :: _, h => cases q <;> exact h
  | .raw _, q :: _, h => cases q <;> exact h

theorem inSubset4_tail {p : Piece4} {r : List Piece4} (h : InSubset4 (p :: r)) : InSubset4 r :=
  ⟨normal4_tail h.1, fun q hq => h.2.1 q (List.mem_cons_of_mem _ hq),
    fun i s h1 h2 => h.2.2 (i + 1) s (by simpa using h1) (by simpa using h2)⟩

theorem inSubset4_ok : ∀ T : List Piece4, InSubset4 T → Ok4 T
  | [], _ => trivial
  | .holeText :: r, h => inSubset4_ok r (inSubset4_tail h)
  | .startTag t :: r, h => ⟨h.2.1 (.startTag t) (by simp), inSubset4_ok r (inSubset4_tail h)⟩
  | .endTag n :: r, h => ⟨h.2.1 (.endTag n) (by simp), inSubset4_ok r (inSubset4_tail h)⟩
  | .comment b :: r, h => ⟨h.2.1 (.comment b) (by simp), inSubset4_ok r (inSubset4_tail h)⟩
  | .doctype d :: r, h => ⟨h.2.1 (.doctype d) (by simp), inSubset4_ok r (inSubset4_tail h)⟩
  | .script e :: r, h => ⟨h.2.1 (.script e) (by simp), inSubset4_ok r (inSubset4_tail h)⟩
  | .raw e :: r, h => ⟨h.2.1 (.raw e) (by simp), inSubset4_ok r (inSubset4_tail h)⟩
  | .static s :: r, h => by
    refine ⟨h.2.1 (.static s) (by simp), fun hl => ?_, inSubset4_ok r (inSubset4_tail h)⟩
    match r, h.1 with
    | [], _ => exact .inl rfl
    | .static _ :: _, hn => exact hn.elim
    | .holeText :: _, _ => exact absurd hl (h.2.2 0 s rfl rfl)
    | .startTag _ :: _, _ => exact .inr trivial
    | .endTag _ :: _, _ => exact .inr trivial
    | .comment _ :: _, _ => exact .inr trivial
    | .doctype _ :: _, _ => exact .inr trivial
    | .script _ :: _, _ => exact .inr trivial
    | .raw _ :: _, _ => exact .inr trivial

/--
**T1, slice 4.** For every slice-4 template in the profile and every list of hole
values in which each TEXT value satisfies `htmlTextSafe`, each attribute and RCDATA
value `htmlSafe`, and each script value `jsonScriptSafe` and `JsonFirst`, the WHATWG
tokenizer, with the tree builder's tokenizer switches (`treeStub`), reads the rendered
page as exactly the tokens Tessera assigned: as in slice 3, plus each `title`,
`textarea`, `style` or `iframe` element as its StartTag, its content (static text and
RCDATA values) as Character tokens, and one EndTag with its name. So an RCDATA value
is character data of the element Tessera put it in, and no static raw-text content
ends the element early.
-/
theorem t1_slice4 (T : List Piece4) (vs : List (Option (List Char)))
    (hT : InSubset4 T) (hv : ValsOK4 T vs) :
    tokenize treeStub (render4 T vs) = denote4 T vs :=
  t1_ok4 T vs [] (inSubset4_ok T hT) hv

/-- **T1, slice 4, coverage.** The run never reaches the `out` sink. -/
theorem t1_slice4_inModel (T : List Piece4) (vs : List (Option (List Char)))
    (hT : InSubset4 T) (hv : ValsOK4 T vs) :
    leavesModel treeStub init (render4 T vs) = false := by
  rcases run_ok4 T vs [] (inSubset4_ok T hT) hv with h | h <;> simp [leavesModel, init, h]

end TesseraT1
