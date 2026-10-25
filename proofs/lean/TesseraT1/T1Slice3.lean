/-
Theorem T1, slice 3: slice 2 plus JavaScript `<script>` elements whose content is
static script text and JSON holes (`Escapers.json`).
-/
import TesseraT1.T1Slice2
import TesseraT1.Script
import TesseraT1.ScriptBridge

namespace TesseraT1

open Whatwg

/-! ### The script body -/

/-- The script-body conditions in the shape the proof walks, with the values each
hole consumes: every hole has a value (the generator writes JSON `null` for a null
value) satisfying `JsonFirst`; a static part has no danger and no NUL, is followed by
a hole or nothing, and does not end in `<!-` before a hole. -/
def BodyOK : List ScriptPart → List (Option (List Char)) → Prop
  | [], _ => True
  | .hole :: _, [] => False
  | .hole :: r, v :: vs => (∃ x, v = some x ∧ JsonFirst x) ∧ BodyOK r vs
  | .static s :: r, vs => NoDangerS s ∧ '\x00' ∉ s ∧
      (match r with
       | [] => True
       | .static _ :: _ => False
       | .hole :: _ => ∀ P', s ≠ P' ++ ['<', '!', '-']) ∧
      BodyOK r vs

theorem body_run (tr : Tree) :
    ∀ (body : List ScriptPart) (vs : List (Option (List Char))) {st : St}, Calm st →
      BodyOK body vs →
      ∃ st' P out, run tr ⟨st, scriptName⟩ (renderScript body vs).1 =
        (⟨st', scriptName⟩, out.map .char) ∧ Good st' P ∧
        pend st ++ (renderScript body vs).1 = out ++ pend st'
  | [], _, st, h, _ => ⟨st, [], [], by simp [renderScript, run], calm_good h [], by simp [renderScript]⟩
  | .hole :: _, [], _, _, hb => hb.elim
  | .hole :: r, v :: vs, st, h, ⟨⟨x, hvx, hx⟩, hr⟩ => by
    subst hvx
    obtain ⟨s1, o1, e1, c1, p1⟩ := good_value tr (calm_good h []) (fun P' e => by simpa using congrArg List.length e) hx
    obtain ⟨s2, P, o2, e2, g2, p2⟩ := body_run tr r vs c1 hr
    refine ⟨s2, P, o1 ++ o2, ?_, g2, ?_⟩
    · simp only [renderScript, Option.getD_some]
      rw [run_append, e1]; simp only; rw [e2]; simp
    · simp only [renderScript, Option.getD_some]
      rw [← List.append_assoc, p1, List.append_assoc, p2]; simp
  | .static s :: r, vs, st, h, ⟨hd, hn, hnext, hr⟩ => by
    obtain ⟨s1, o1, e1, g1, p1⟩ := good_run tr s (calm_good h []) hn (noScriptDanger_prefix hd)
    simp only [List.nil_append] at g1
    match r, hnext, hr with
    | [], _, _ =>
      refine ⟨s1, s, o1, ?_, g1, ?_⟩
      · simpa [renderScript] using e1
      · simpa [renderScript] using p1
    | .hole :: r', hend, hr =>
      match vs, hr with
      | [], hr => exact hr.elim
      | v :: vs', ⟨⟨x, hvx, hx⟩, hr'⟩ =>
        subst hvx
        obtain ⟨s2, o2, e2, c2, p2⟩ := good_value tr g1 hend hx
        obtain ⟨s3, P, o3, e3, g3, p3⟩ := body_run tr r' vs' c2 hr'
        refine ⟨s3, P, o1 ++ o2 ++ o3, ?_, g3, ?_⟩
        · simp only [renderScript, Option.getD_some]
          rw [run_append, e1]; simp only; rw [run_append, e2]; simp only; rw [e3]; simp
        · simp only [renderScript, Option.getD_some]
          calc pend st ++ (s ++ (x ++ (renderScript r' vs').1))
              = (pend st ++ s) ++ x ++ (renderScript r' vs').1 := by simp
            _ = o1 ++ (pend s1 ++ x) ++ (renderScript r' vs').1 := by rw [p1]; simp
            _ = o1 ++ o2 ++ (pend s2 ++ (renderScript r' vs').1) := by rw [p2]; simp
            _ = _ := by rw [p3]; simp

/-- **A script element's content and end tag.** From the script data state just
after `<script ...>`, the body and Tessera's end tag are the body's characters and
one EndTag. -/
theorem run_scriptBody (tr : Tree) {body : List ScriptPart} {vs : List (Option (List Char))}
    (hb : BodyOK body vs) {E : List Char} (hE : lowerStr E = scriptName)
    (hEa : ∀ c ∈ E, isAlpha c = true) :
    run tr ⟨.raw .script, scriptName⟩ ((renderScript body vs).1 ++ ['<', '/'] ++ E ++ ['>']) =
      (⟨.data, scriptName⟩, (renderScript body vs).1.map .char ++ [.endTag scriptName]) := by
  obtain ⟨st, P, out, e, g, p⟩ := body_run tr body vs (.inl rfl) hb
  rw [show pend (St.raw Raw.script) = [] from rfl, List.nil_append] at p
  rw [List.append_assoc, List.append_assoc, run_append, e]
  simp only
  rw [← List.append_assoc, good_close tr g hE hEa, p]
  simp

/-! ### Slice-3 templates -/

/-- The values a script body's holes consume: each one present and a JSON value
(`jsonScriptSafe`, the escaper proof's postcondition, and `JsonFirst`, J1-J4). -/
def ScriptValsOK : List ScriptPart → List (Option (List Char)) → Prop
  | [], _ => True
  | .static _ :: r, vs => ScriptValsOK r vs
  | .hole :: _, [] => False
  | .hole :: r, v :: vs => (∃ x, v = some x ∧ jsonScriptSafe x ∧ JsonFirst x) ∧ ScriptValsOK r vs

theorem bodyOK_of :
    ∀ (body : List ScriptPart) (vs : List (Option (List Char))),
      (∀ p ∈ body, match p with | .static s => NoScriptDanger s | .hole => True) →
      NormalScript body →
      (∀ i s, body[i]? = some (.static s) → body[i+1]? = some .hole → NoScriptLtBeforeHole s) →
      ScriptValsOK body vs → BodyOK body vs
  | [], _, _, _, _, _ => trivial
  | .hole :: _, [], _, _, _, h => h.elim
  | .hole :: r, v :: vs, hd, hn, hl, ⟨⟨x, hx, _, hj⟩, hr⟩ =>
    ⟨⟨x, hx, hj⟩, bodyOK_of r vs (fun p hp => hd p (List.mem_cons_of_mem _ hp))
      (by cases r with | nil => trivial | cons q r => cases q <;> exact hn)
      (fun i s h1 h2 => hl (i + 1) s (by simpa using h1) (by simpa using h2)) hr⟩
  | .static s :: r, vs, hd, hn, hl, hv => by
    have hs : NoScriptDanger s := hd (.static s) (by simp)
    obtain ⟨hds, hns⟩ := noDangerS_of_noScriptDanger hs
    refine ⟨hds, hns, ?_, bodyOK_of r vs (fun p hp => hd p (List.mem_cons_of_mem _ hp)) ?_
      (fun i s h1 h2 => hl (i + 1) s (by simpa using h1) (by simpa using h2)) hv⟩
    · match r, hn with
      | [], _ => trivial
      | .static _ :: _, hn => exact hn.elim
      | .hole :: _, _ => exact notEnd_of_noScriptLtBeforeHole (hl 0 s rfl rfl)
    · match r, hn with
      | [], _ => trivial
      | .static _ :: _, hn => exact hn.elim
      | .hole :: _, hn => exact hn

/-- A slice-3 template piece: the slice-2 pieces and `<script>` elements. -/
inductive Piece3 where
  | static (s : List Char)
  | holeText
  | startTag (t : StartTag)
  | endTag (name : List Char)
  | comment (b : List Char)
  | doctype (d : Doctype)
  | script (e : ScriptEl)

def render3 : List Piece3 → List (Option (List Char)) → List Char
  | [], _ => []
  | .static s :: r, vs => s ++ render3 r vs
  | .holeText :: r, [] => render3 r []
  | .holeText :: r, v :: vs => v.getD [] ++ render3 r vs
  | .startTag t :: r, vs => (t.render vs).1 ++ render3 r (t.render vs).2
  | .endTag n :: r, vs => ['<', '/'] ++ n ++ ['>'] ++ render3 r vs
  | .comment b :: r, vs => "<!--".toList ++ b ++ "-->".toList ++ render3 r vs
  | .doctype d :: r, vs => d.render ++ render3 r vs
  | .script e :: r, vs => (e.render vs).1 ++ render3 r (e.render vs).2

def ValsOK3 : List Piece3 → List (Option (List Char)) → Prop
  | [], _ => True
  | .static _ :: r, vs => ValsOK3 r vs
  | .holeText :: r, [] => ValsOK3 r []
  | .holeText :: r, v :: vs => (∀ x, v = some x → htmlTextSafe x) ∧ ValsOK3 r vs
  | .startTag t :: r, vs => t.ValsOK vs ∧ ValsOK3 r (t.render vs).2
  | .endTag _ :: r, vs => ValsOK3 r vs
  | .comment _ :: r, vs => ValsOK3 r vs
  | .doctype _ :: r, vs => ValsOK3 r vs
  | .script e :: r, vs => e.tag.ValsOK vs ∧ ScriptValsOK e.body (e.tag.render vs).2 ∧
      ValsOK3 r (e.render vs).2

/-- Tessera's reading of a slice-3 page. A script element is its start tag, its
content (static script and JSON values) as characters, and its end tag. -/
def denote3 : List Piece3 → List (Option (List Char)) → List Token
  | [], _ => []
  | .static s :: r, vs => s.map .char ++ denote3 r vs
  | .holeText :: r, [] => denote3 r []
  | .holeText :: r, v :: vs => textTokens (v.getD []) ++ denote3 r vs
  | .startTag t :: r, vs => (t.toSyn vs).1.tessera :: denote3 r (t.render vs).2
  | .endTag n :: r, vs => .endTag (lowerStr n) :: denote3 r vs
  | .comment b :: r, vs => .comment b :: denote3 r vs
  | .doctype _ :: r, vs => .doctype (some "html".toList) false :: denote3 r vs
  | .script e :: r, vs =>
    (e.tag.toSyn vs).1.tessera ::
      ((renderScript e.body (e.tag.render vs).2).1.map .char ++ [.endTag scriptName] ++
        denote3 r (e.render vs).2)

def Piece3.ok : Piece3 → Prop
  | .static s => StaticText s
  | .holeText => True
  | .startTag t => StartTag2OK t
  | .endTag n => Slice2Name n
  | .comment b => CommentTessera b
  | .doctype d => DoctypeOK d
  | .script e => ScriptOK e

def Normal3 : List Piece3 → Prop
  | [] => True
  | [_] => True
  | .static _ :: .static _ :: _ => False
  | _ :: p :: r => Normal3 (p :: r)

/-- Slice-3 membership: as `InSubset2`, with `<script>` elements (`ScriptOK`). -/
def InSubset3 (T : List Piece3) : Prop :=
  Normal3 T ∧ (∀ p ∈ T, p.ok) ∧
  (∀ i s, T[i]? = some (.static s) → T[i + 1]? = some .holeText → NoLtBeforeHole s)

def StartsMarkup3 : List Piece3 → Prop
  | .startTag _ :: _ | .endTag _ :: _ | .comment _ :: _ | .doctype _ :: _ | .script _ :: _ => True
  | _ => False

def Ok3 : List Piece3 → Prop
  | [] => True
  | .static s :: r => StaticText s ∧ (s.getLast? = some '<' → r = [] ∨ StartsMarkup3 r) ∧ Ok3 r
  | .holeText :: r => Ok3 r
  | .startTag t :: r => StartTag2OK t ∧ Ok3 r
  | .endTag n :: r => Slice2Name n ∧ Ok3 r
  | .comment b :: r => CommentTessera b ∧ Ok3 r
  | .doctype d :: r => DoctypeOK d ∧ Ok3 r
  | .script e :: r => ScriptOK e ∧ Ok3 r

theorem render3_markup {r : List Piece3} (h : StartsMarkup3 r) (vs : List (Option (List Char))) :
    ∃ rest, render3 r vs = '<' :: rest := by
  match r, h with
  | .startTag t :: r, _ =>
    simp only [render3]
    unfold StartTag.render
    split <;> simp [SrcTag.render]
  | .script e :: r, _ =>
    simp only [render3, ScriptEl.render]
    unfold StartTag.render
    split <;> simp [SrcTag.render]
  | .endTag _ :: _, _ => simp [render3]
  | .comment _ :: _, _ => simp [render3]
  | .doctype _ :: _, _ => simp [render3, Doctype.render]

theorem script_lower {e : ScriptEl} (h : ScriptOK e) : lowerStr e.tag.name = scriptName := by
  rw [lowerStr_eq_asciiLower, h.1]; rfl

/-- A script element from the data state. -/
theorem run_script (tr : Tree) (hstub : tr scriptName = .raw .script) (ls : List Char) {e : ScriptEl} {vs : List (Option (List Char))}
    (he : ScriptOK e) (hvt : e.tag.ValsOK vs) (hvb : ScriptValsOK e.body (e.tag.render vs).2) :
    run tr ⟨.data, ls⟩ (e.render vs).1 =
      (⟨.data, scriptName⟩, (e.tag.toSyn vs).1.tessera ::
        ((renderScript e.body (e.tag.render vs).2).1.map .char ++ [.endTag scriptName])) := by
  obtain ⟨hr1, hr2⟩ := StartTag.toSyn_render e.tag vs
  have hl := script_lower he
  simp only [ScriptEl.render]
  rw [← hr1, List.append_assoc, List.append_assoc, List.append_assoc, run_append,
    run_startTag tr ls (StartTag.toSyn_ok_script e vs he hvt),
    token_eq_tessera (StartTag.toSyn_nodup e.tag vs he.2.2.1), StartTag.toSyn_name_script e vs he, hl,
    hstub]
  simp only [List.singleton_append]
  have hb := bodyOK_of e.body _ he.2.2.2.1 he.2.2.2.2.1 he.2.2.2.2.2 hvb
  rw [← List.append_assoc, ← List.append_assoc,
    run_scriptBody tr hb hl (script_name_alpha he.1)]
theorem t1_ok3 :
    ∀ (T : List Piece3) (vs : List (Option (List Char))) (ls : List Char), Ok3 T → ValsOK3 T vs →
      tokensFrom treeStub ⟨.data, ls⟩ (render3 T vs) = denote3 T vs
  | [], _, _, _, _ => by simp [render3, denote3, tokensFrom, run, eofTokens]
  | .holeText :: r, [], ls, h, hv => by
    simpa [render3, denote3] using t1_ok3 r [] ls h hv
  | .holeText :: r, v :: vs, ls, h, hv => by
    simp only [render3, denote3]
    rw [tokensFrom_append]
    cases v with
    | none => simpa [run, textTokens] using t1_ok3 r vs ls h hv.2
    | some x =>
      rw [Option.getD_some, run_textChunks treeStub ls (htmlTextSafe_chunks (hv.1 x rfl))]
      rw [t1_ok3 r vs ls h hv.2]
  | .startTag t :: r, vs, ls, ⟨ht, h⟩, ⟨hvt, hv⟩ => by
    simp only [render3, denote3]
    obtain ⟨hr1, hr2⟩ := StartTag.toSyn_render t vs
    rw [← hr1, ← hr2, tokensFrom_append, run_startTag treeStub ls (StartTag.toSyn_ok t vs ht hvt),
      token_eq_tessera (StartTag.toSyn_nodup t vs ht.2),
      StartTag.toSyn_name t vs ht, treeStub_slice2 ht.1]
    rw [hr2] at *
    simp [t1_ok3 r _ (lowerStr t.name) h hv]
  | .endTag n :: r, vs, ls, ⟨hn, h⟩, hv => by
    simp only [render3, denote3]
    obtain ⟨h1, h2⟩ := slice2Name_ok hn
    rw [tokensFrom_append, run_endTag treeStub ls h1 h2]
    simp [t1_ok3 r vs ls h hv]
  | .comment b :: r, vs, ls, ⟨hb, h⟩, hv => by
    simp only [render3, denote3]
    have e : "<!--".toList ++ b ++ "-->".toList = ['<', '!', '-', '-'] ++ b ++ ['-', '-', '>'] := rfl
    rw [e, tokensFrom_append, run_comment treeStub ls (commentBodyOK_of_tessera hb)]
    simp [t1_ok3 r vs ls h hv]
  | .doctype d :: r, vs, ls, ⟨hd, h⟩, hv => by
    simp only [render3, denote3]
    rw [tokensFrom_append, run_doctype treeStub ls hd]
    simp [t1_ok3 r vs ls h hv]
  | .script e :: r, vs, ls, ⟨he, h⟩, ⟨hvt, hvb, hv⟩ => by
    simp only [render3, denote3]
    rw [tokensFrom_append, run_script treeStub (by decide) ls he hvt hvb]
    simp [t1_ok3 r _ scriptName h hv]
  | .static s :: r, vs, ls, ⟨hs, hlast, h⟩, hv => by
    simp only [render3, denote3]
    rw [tokensFrom_append]
    rcases run_static treeStub ls s hs with ⟨_, hrun⟩ | ⟨hl, hrun⟩
    · rw [hrun, t1_ok3 r vs ls h hv]
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
      · cases vs <;> simp [render3, denote3, tokensFrom, run, eofTokens]
      · obtain ⟨rest, hrest⟩ := render3_markup hm vs
        rw [hrest, tokensFrom_tagOpen_lt, ← hrest, t1_ok3 r vs ls h hv]
        simp

/-- Where a slice-3 run ends: the data state, or tag open after a final `<`. -/
def EndsOK3 (m : M) : Prop := m.st = .data ∨ m.st = .tagOpen

theorem run_ok3 :
    ∀ (T : List Piece3) (vs : List (Option (List Char))) (ls : List Char), Ok3 T → ValsOK3 T vs →
      EndsOK3 (run treeStub ⟨.data, ls⟩ (render3 T vs)).1
  | [], _, _, _, _ => by simp [render3, run, EndsOK3]
  | .holeText :: r, [], ls, h, hv => by
    simpa [render3] using run_ok3 r [] ls h hv
  | .holeText :: r, v :: vs, ls, h, hv => by
    simp only [render3]
    rw [run_append]
    cases v with
    | none => simpa [run] using run_ok3 r vs ls h hv.2
    | some x =>
      rw [Option.getD_some, run_textChunks treeStub ls (htmlTextSafe_chunks (hv.1 x rfl))]
      exact run_ok3 r vs ls h hv.2
  | .startTag t :: r, vs, ls, ⟨ht, h⟩, ⟨hvt, hv⟩ => by
    simp only [render3]
    obtain ⟨hr1, hr2⟩ := StartTag.toSyn_render t vs
    rw [← hr1, ← hr2, run_append, run_startTag treeStub ls (StartTag.toSyn_ok t vs ht hvt),
      StartTag.toSyn_name t vs ht, treeStub_slice2 ht.1]
    rw [hr2] at *
    exact run_ok3 r _ (lowerStr t.name) h hv
  | .endTag n :: r, vs, ls, ⟨hn, h⟩, hv => by
    simp only [render3]
    obtain ⟨h1, h2⟩ := slice2Name_ok hn
    rw [run_append, run_endTag treeStub ls h1 h2]
    exact run_ok3 r vs ls h hv
  | .comment b :: r, vs, ls, ⟨hb, h⟩, hv => by
    simp only [render3]
    have e : "<!--".toList ++ b ++ "-->".toList = ['<', '!', '-', '-'] ++ b ++ ['-', '-', '>'] := rfl
    rw [e, run_append, run_comment treeStub ls (commentBodyOK_of_tessera hb)]
    exact run_ok3 r vs ls h hv
  | .doctype d :: r, vs, ls, ⟨hd, h⟩, hv => by
    simp only [render3]
    rw [run_append, run_doctype treeStub ls hd]
    exact run_ok3 r vs ls h hv
  | .script e :: r, vs, ls, ⟨he, h⟩, ⟨hvt, hvb, hv⟩ => by
    simp only [render3]
    rw [run_append, run_script treeStub (by decide) ls he hvt hvb]
    exact run_ok3 r _ scriptName h hv
  | .static s :: r, vs, ls, ⟨hs, hlast, h⟩, hv => by
    simp only [render3]
    rw [run_append]
    rcases run_static treeStub ls s hs with ⟨_, hrun⟩ | ⟨hl, hrun⟩
    · rw [hrun]; exact run_ok3 r vs ls h hv
    · rw [hrun]
      rcases hlast hl with rfl | hm
      · cases vs <;> simp [render3, run, EndsOK3]
      · obtain ⟨rest, hrest⟩ := render3_markup hm vs
        have e : run treeStub ⟨.tagOpen, ls⟩ (render3 r vs) =
            ((run treeStub ⟨.data, ls⟩ (render3 r vs)).1,
              .char '<' :: (run treeStub ⟨.data, ls⟩ (render3 r vs)).2) := by
          rw [hrest]; simp [run, step, stepData, isAlpha]
        rw [e]; exact run_ok3 r vs ls h hv

theorem normal3_tail {p : Piece3} {r : List Piece3} (h : Normal3 (p :: r)) : Normal3 r := by
  match p, r, h with
  | _, [], _ => trivial
  | .static _, .static _ :: _, h => exact h.elim
  | .static _, .holeText :: _, h => exact h
  | .static _, .startTag _ :: _, h => exact h
  | .static _, .endTag _ :: _, h => exact h
  | .static _, .comment _ :: _, h => exact h
  | .static _, .doctype _ :: _, h => exact h
  | .static _, .script _ :: _, h => exact h
  | .holeText, q :: _, h => cases q <;> exact h
  | .startTag _, q :: _, h => cases q <;> exact h
  | .endTag _, q :: _, h => cases q <;> exact h
  | .comment _, q :: _, h => cases q <;> exact h
  | .doctype _, q :: _, h => cases q <;> exact h
  | .script _, q :: _, h => cases q <;> exact h

theorem inSubset3_tail {p : Piece3} {r : List Piece3} (h : InSubset3 (p :: r)) : InSubset3 r :=
  ⟨normal3_tail h.1, fun q hq => h.2.1 q (List.mem_cons_of_mem _ hq),
    fun i s h1 h2 => h.2.2 (i + 1) s (by simpa using h1) (by simpa using h2)⟩

theorem inSubset3_ok : ∀ T : List Piece3, InSubset3 T → Ok3 T
  | [], _ => trivial
  | .holeText :: r, h => inSubset3_ok r (inSubset3_tail h)
  | .startTag t :: r, h => ⟨h.2.1 (.startTag t) (by simp), inSubset3_ok r (inSubset3_tail h)⟩
  | .endTag n :: r, h => ⟨h.2.1 (.endTag n) (by simp), inSubset3_ok r (inSubset3_tail h)⟩
  | .comment b :: r, h => ⟨h.2.1 (.comment b) (by simp), inSubset3_ok r (inSubset3_tail h)⟩
  | .doctype d :: r, h => ⟨h.2.1 (.doctype d) (by simp), inSubset3_ok r (inSubset3_tail h)⟩
  | .script e :: r, h => ⟨h.2.1 (.script e) (by simp), inSubset3_ok r (inSubset3_tail h)⟩
  | .static s :: r, h => by
    refine ⟨h.2.1 (.static s) (by simp), fun hl => ?_, inSubset3_ok r (inSubset3_tail h)⟩
    match r, h.1 with
    | [], _ => exact .inl rfl
    | .static _ :: _, hn => exact hn.elim
    | .holeText :: _, _ => exact absurd hl (h.2.2 0 s rfl rfl)
    | .startTag _ :: _, _ => exact .inr trivial
    | .endTag _ :: _, _ => exact .inr trivial
    | .comment _ :: _, _ => exact .inr trivial
    | .doctype _ :: _, _ => exact .inr trivial
    | .script _ :: _, _ => exact .inr trivial

/--
**T1, slice 3.** For every slice-3 template in the safe subset and every list of
hole values in which each TEXT value satisfies `htmlTextSafe`, each attribute
value `htmlSafe`, and each script value `jsonScriptSafe` and `JsonFirst`, the WHATWG
tokenizer, with the tree builder's tokenizer switches (`treeStub`), reads the
rendered page as exactly the tokens Tessera assigned: as in slice 2, plus each
`<script>` element as its StartTag, its content (static script text and JSON
values) as Character tokens, and one EndTag `script`.
-/
theorem t1_slice3 (T : List Piece3) (vs : List (Option (List Char)))
    (hT : InSubset3 T) (hv : ValsOK3 T vs) :
    tokenize treeStub (render3 T vs) = denote3 T vs :=
  t1_ok3 T vs [] (inSubset3_ok T hT) hv

/-- **T1, slice 3, coverage.** The run never reaches the `out` sink. -/
theorem t1_slice3_inModel (T : List Piece3) (vs : List (Option (List Char)))
    (hT : InSubset3 T) (hv : ValsOK3 T vs) :
    leavesModel treeStub init (render3 T vs) = false := by
  rcases run_ok3 T vs [] (inSubset3_ok T hT) hv with h | h <;> simp [leavesModel, init, h]

end TesseraT1
