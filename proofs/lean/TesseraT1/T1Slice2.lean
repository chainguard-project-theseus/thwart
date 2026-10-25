/-
Theorem T1, slice 2: element text with TEXT holes, start tags (copied or rebuilt)
with static attributes and attribute holes, end tags, comments and
`<!DOCTYPE html>`, in the HTML namespace, with no element that switches the
tokenizer (`slice3Names`).
-/
import TesseraT1.T1
import TesseraT1.Comment
import TesseraT1.CommentBridge
import TesseraT1.TagBridge

namespace TesseraT1

open Whatwg

/-! ### DOCTYPE -/

theorem lower_eq_ne {c d : Char} (h : lower c = d) {e : Char} (he : lower e = e) (hne : e ≠ d) :
    c ≠ e := by
  rintro rfl; exact hne (he.symm.trans h)

/-- `DOCTYPE` in any ASCII case, from the markup declaration open state. -/
theorem mdo_doctype (tr : Tree) (ls : List Char) {kw : List Char}
    (h : lowerStr kw = "doctype".toList) :
    run tr ⟨.markupDecl [], ls⟩ kw = (⟨.doctype, ls⟩, []) := by
  have e : "doctype".toList = ['d', 'o', 'c', 't', 'y', 'p', 'e'] := rfl
  rw [e] at h
  rcases kw with _ | ⟨k1, _ | ⟨k2, _ | ⟨k3, _ | ⟨k4, _ | ⟨k5, _ | ⟨k6, _ | ⟨k7, _ | ⟨k8, r⟩⟩⟩⟩⟩⟩⟩⟩ <;>
    simp [lowerStr] at h
  obtain ⟨h1, h2, h3, h4, h5, h6, h7⟩ := h
  have n1 : k1 ≠ '-' := lower_eq_ne h1 (by decide) (by decide)
  have n1' : k1 ≠ '[' := lower_eq_ne h1 (by decide) (by decide)
  have e2 : "[CDATA[".toList = ['[', 'C', 'D', 'A', 'T', 'A', '['] := rfl
  simp [run, step, stepMarkupDecl, lowerStr, e, e2, h1, h2, h3, h4, h5, h6, h7, n1, n1']

theorem lower_facts {c d : Char} (h : lower c = d) (hd : ('a' ≤ d ∧ d ≤ 'z') = True) :
    isWs c = false ∧ c ≠ '>' ∧ c ≠ '\x00' ∧ c ≠ '-' ∧ c ≠ '[' := by
  have k : ∀ e, lower e = e → ('a' ≤ e ∧ e ≤ 'z') = False → c ≠ e := by
    rintro e he hz rfl
    rw [he] at h; subst h; rw [hz] at hd; exact False.elim (hd ▸ trivial)
  refine ⟨?_, k _ (by decide) (by decide), k _ (by decide) (by decide), k _ (by decide) (by decide),
    k _ (by decide) (by decide)⟩
  have := k '\t' (by decide) (by decide)
  have := k '\n' (by decide) (by decide)
  have := k '\x0C' (by decide) (by decide)
  have := k '\r' (by decide) (by decide)
  have := k ' ' (by decide) (by decide)
  simp_all [isWs]

theorem run_doctype_ws (tr : Tree) (ls : List Char) (n : List Char) :
    ∀ w, AllWs w → run tr ⟨.afterDoctypeName n, ls⟩ w = (⟨.afterDoctypeName n, ls⟩, [])
  | [], _ => rfl
  | c :: w, h => by
    have hc : isWs c = true := h c (by simp)
    simp [run, step, hc, run_doctype_ws tr ls n w (fun d hd => h d (by simp [hd]))]

theorem run_beforeDoctypeName_ws (tr : Tree) (ls : List Char) :
    ∀ w, AllWs w → run tr ⟨.beforeDoctypeName, ls⟩ w = (⟨.beforeDoctypeName, ls⟩, [])
  | [], _ => rfl
  | c :: w, h => by
    have hc : isWs c = true := h c (by simp)
    simp [run, step, stepBeforeDoctypeName, hc,
      run_beforeDoctypeName_ws tr ls w (fun d hd => h d (by simp [hd]))]

theorem allWs_of_ascii {w : List Char} (h : w.all isAsciiWs = true) : AllWs w := by
  intro c hc
  have := List.all_eq_true.mp h c hc
  simp only [isAsciiWs, Bool.or_eq_true, decide_eq_true_eq] at this
  rcases this with ((((h | h) | h) | h) | h) <;> simp [isWs, h]

/-- **DOCTYPE.** `<!DOCTYPE html>` as `DoctypeOK` admits it is one DOCTYPE token. -/
theorem run_doctype (tr : Tree) (ls : List Char) {d : Doctype} (h : DoctypeOK d) :
    run tr ⟨.data, ls⟩ d.render = (⟨.data, ls⟩, [.doctype (some "html".toList) false]) := by
  obtain ⟨hkw, hw1, hw1a, hn, hw2⟩ := h
  rw [← lowerStr_eq_asciiLower] at hkw hn
  have hw1' := allWs_of_ascii hw1a
  have hw2' := allWs_of_ascii hw2
  obtain ⟨kw, ws1, name, ws2⟩ := d
  simp only at hkw hw1 hn hw1' hw2'
  obtain ⟨x, w1, rfl⟩ : ∃ x w1, ws1 = x :: w1 := by
    cases ws1 with
    | nil => exact absurd rfl hw1
    | cons x w1 => exact ⟨x, w1, rfl⟩
  have hx : isWs x = true := hw1' x (by simp)
  have e : "html".toList = ['h', 't', 'm', 'l'] := rfl
  rw [e] at hn
  rcases name with _ | ⟨n1, _ | ⟨n2, _ | ⟨n3, _ | ⟨n4, _ | ⟨n5, r⟩⟩⟩⟩⟩ <;> simp [lowerStr] at hn
  obtain ⟨h1, h2, h3, h4⟩ := hn
  obtain ⟨a1, b1, c1, -, -⟩ := lower_facts h1 (by decide)
  obtain ⟨a2, b2, c2, -, -⟩ := lower_facts h2 (by decide)
  obtain ⟨a3, b3, c3, -, -⟩ := lower_facts h3 (by decide)
  obtain ⟨a4, b4, c4, -, -⟩ := lower_facts h4 (by decide)
  have e0 : run tr ⟨.data, ls⟩ (['<', '!'] ++ kw) = (⟨.doctype, ls⟩, []) := by
    exact run_append_of (m1 := ⟨.markupDecl [], ls⟩) (t1 := []) (by simp [run, step, stepData])
      (mdo_doctype tr ls hkw)
  have e1 : run tr ⟨.doctype, ls⟩ (x :: w1) = (⟨.beforeDoctypeName, ls⟩, []) := by
    simp only [run]
    rw [show step tr ⟨.doctype, ls⟩ x = (⟨.beforeDoctypeName, ls⟩, []) by simp [step, hx]]
    simp [run_beforeDoctypeName_ws tr ls w1 (fun d hd => hw1' d (by simp [hd]))]
  have e2 : run tr ⟨.beforeDoctypeName, ls⟩ [n1, n2, n3, n4] =
      (⟨.doctypeName ['h', 't', 'm', 'l'], ls⟩, []) := by
    simp [run, step, stepBeforeDoctypeName, a1, b1, c1, a2, b2, c2, a3, b3, c3, a4, b4, c4,
      h1, h2, h3, h4]
  have e3 : run tr ⟨.doctypeName ['h', 't', 'm', 'l'], ls⟩ (ws2 ++ ['>']) =
      (⟨.data, ls⟩, [.doctype (some ['h', 't', 'm', 'l']) false]) := by
    match ws2, hw2' with
    | [], _ => simp [run, step, isWs]
    | y :: w2, hw =>
      have hy : isWs y = true := hw y (by simp)
      simp only [List.cons_append, run]
      rw [show step tr ⟨.doctypeName ['h', 't', 'm', 'l'], ls⟩ y =
        (⟨.afterDoctypeName ['h', 't', 'm', 'l'], ls⟩, []) by simp [step, hy]]
      simp only
      rw [run_append, run_doctype_ws tr ls _ w2 (fun d hd => hw d (by simp [hd]))]
      simp [run, step, isWs]
  have er : Doctype.render ⟨kw, x :: w1, [n1, n2, n3, n4], ws2⟩ =
      (['<', '!'] ++ kw) ++ ((x :: w1) ++ ([n1, n2, n3, n4] ++ (ws2 ++ ['>']))) := by
    simp [Doctype.render]
  rw [er, run_append_of e0 (run_append_of e1 (run_append_of e2 e3))]
  simp [e]

/-! ### Pieces -/

/-- A `<` left pending in tag open, followed by markup: emit it, then read the markup. -/
theorem tokensFrom_tagOpen_lt (tr : Tree) (ls : List Char) (r : List Char) :
    tokensFrom tr ⟨.tagOpen, ls⟩ ('<' :: r) = .char '<' :: tokensFrom tr ⟨.data, ls⟩ ('<' :: r) := by
  simp [tokensFrom, run, step, stepData, isAlpha]

/-- Slice-2 tag names leave the tokenizer in the data state. -/
theorem treeStub_slice2 {n : List Char} (h : Slice2Name n) : treeStub (lowerStr n) = .data := by
  have hn := h.2
  rw [← lowerStr_eq_asciiLower] at hn
  simp [slice3Names] at hn
  simp [treeStub, hn]

theorem slice2Name_ok {n : List Char} (h : Slice2Name n) :
    (∃ c r, n = c :: r ∧ isAlpha c = true) ∧ ∀ c ∈ n, NameChar c := by
  obtain ⟨hv, -⟩ := h
  match n, hv with
  | c :: r, hv =>
    simp only [validElementName, Bool.and_eq_true, List.all_eq_true] at hv
    have hc : isAlpha c = true := hv.1
    refine ⟨⟨c, r, rfl, hc⟩, ?_⟩
    have nc : ∀ d, isNameChar d = true → NameChar d := by
      intro d hd
      have k : ∀ e, isNameChar e = false → d ≠ e := fun e he h => by subst h; simp_all
      have := k '\t' (by decide)
      have := k '\n' (by decide)
      have := k '\x0C' (by decide)
      have := k '\r' (by decide)
      have := k ' ' (by decide)
      exact ⟨by simp_all [isWs], k _ (by decide), k _ (by decide), k _ (by decide), k _ (by decide)⟩
    intro d hd
    rcases List.mem_cons.mp hd with rfl | hd
    · exact nc d (by simp [isNameChar, hv.1])
    · exact nc d (hv.2 d hd)

/-! ### The slice-2 theorem -/

/-- Hole values, paired with their holes the way `render2` consumes them: a TEXT
value satisfies `htmlTextSafe`, an attribute value `htmlSafe`. -/
def ValsOK2 : List Piece2 → List (Option (List Char)) → Prop
  | [], _ => True
  | .static _ :: r, vs => ValsOK2 r vs
  | .holeText :: r, [] => ValsOK2 r []
  | .holeText :: r, v :: vs => (∀ x, v = some x → htmlTextSafe x) ∧ ValsOK2 r vs
  | .startTag t :: r, vs => t.ValsOK vs ∧ ValsOK2 r (t.render vs).2
  | .endTag _ :: r, vs => ValsOK2 r vs
  | .comment _ :: r, vs => ValsOK2 r vs
  | .doctype _ :: r, vs => ValsOK2 r vs

/-- Tessera's reading of a slice-2 page, as tokens. -/
def denote2 : List Piece2 → List (Option (List Char)) → List Token
  | [], _ => []
  | .static s :: r, vs => s.map .char ++ denote2 r vs
  | .holeText :: r, [] => denote2 r []
  | .holeText :: r, v :: vs => textTokens (v.getD []) ++ denote2 r vs
  | .startTag t :: r, vs => (t.toSyn vs).1.tessera :: denote2 r (t.render vs).2
  | .endTag n :: r, vs => .endTag (lowerStr n) :: denote2 r vs
  | .comment b :: r, vs => .comment b :: denote2 r vs
  | .doctype _ :: r, vs => .doctype (some "html".toList) false :: denote2 r vs

def StartsMarkup : List Piece2 → Prop
  | .startTag _ :: _ | .endTag _ :: _ | .comment _ :: _ | .doctype _ :: _ => True
  | _ => False

/-- The slice-2 conditions in the shape the proof walks. -/
def Ok2 : List Piece2 → Prop
  | [] => True
  | .static s :: r => StaticText s ∧ (s.getLast? = some '<' → r = [] ∨ StartsMarkup r) ∧ Ok2 r
  | .holeText :: r => Ok2 r
  | .startTag t :: r => StartTag2OK t ∧ Ok2 r
  | .endTag n :: r => Slice2Name n ∧ Ok2 r
  | .comment b :: r => CommentTessera b ∧ Ok2 r
  | .doctype d :: r => DoctypeOK d ∧ Ok2 r

theorem render2_markup {r : List Piece2} (h : StartsMarkup r) (vs : List (Option (List Char))) :
    ∃ rest, render2 r vs = '<' :: rest := by
  match r, h with
  | .startTag t :: r, _ =>
    simp only [render2]
    unfold StartTag.render
    split <;> simp [SrcTag.render]
  | .endTag _ :: _, _ => simp [render2]
  | .comment _ :: _, _ => simp [render2]
  | .doctype _ :: _, _ => simp [render2, Doctype.render]

theorem t1_ok2 :
    ∀ (T : List Piece2) (vs : List (Option (List Char))) (ls : List Char), Ok2 T → ValsOK2 T vs →
      tokensFrom treeStub ⟨.data, ls⟩ (render2 T vs) = denote2 T vs
  | [], _, _, _, _ => by simp [render2, denote2, tokensFrom, run, eofTokens]
  | .holeText :: r, [], ls, h, hv => by
    simpa [render2, denote2] using t1_ok2 r [] ls h hv
  | .holeText :: r, v :: vs, ls, h, hv => by
    simp only [render2, denote2]
    rw [tokensFrom_append]
    cases v with
    | none => simpa [run, textTokens] using t1_ok2 r vs ls h hv.2
    | some x =>
      rw [Option.getD_some, run_textChunks treeStub ls (htmlTextSafe_chunks (hv.1 x rfl))]
      rw [t1_ok2 r vs ls h hv.2]
  | .startTag t :: r, vs, ls, ⟨ht, h⟩, ⟨hvt, hv⟩ => by
    simp only [render2, denote2]
    obtain ⟨hr1, hr2⟩ := StartTag.toSyn_render t vs
    rw [← hr1, ← hr2, tokensFrom_append, run_startTag treeStub ls (StartTag.toSyn_ok t vs ht hvt),
      token_eq_tessera (StartTag.toSyn_nodup t vs ht.2),
      StartTag.toSyn_name t vs ht, treeStub_slice2 ht.1]
    rw [hr2] at *
    simp [t1_ok2 r _ (lowerStr t.name) h hv]
  | .endTag n :: r, vs, ls, ⟨hn, h⟩, hv => by
    simp only [render2, denote2]
    obtain ⟨h1, h2⟩ := slice2Name_ok hn
    rw [tokensFrom_append, run_endTag treeStub ls h1 h2]
    simp [t1_ok2 r vs ls h hv]
  | .comment b :: r, vs, ls, ⟨hb, h⟩, hv => by
    simp only [render2, denote2]
    have e : "<!--".toList ++ b ++ "-->".toList = ['<', '!', '-', '-'] ++ b ++ ['-', '-', '>'] := rfl
    rw [e, tokensFrom_append, run_comment treeStub ls (commentBodyOK_of_tessera hb)]
    simp [t1_ok2 r vs ls h hv]
  | .doctype d :: r, vs, ls, ⟨hd, h⟩, hv => by
    simp only [render2, denote2]
    rw [tokensFrom_append, run_doctype treeStub ls hd]
    simp [t1_ok2 r vs ls h hv]
  | .static s :: r, vs, ls, ⟨hs, hlast, h⟩, hv => by
    simp only [render2, denote2]
    rw [tokensFrom_append]
    rcases run_static treeStub ls s hs with ⟨_, hrun⟩ | ⟨hl, hrun⟩
    · rw [hrun, t1_ok2 r vs ls h hv]
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
      · cases vs <;> simp [render2, denote2, tokensFrom, run, eofTokens]
      · obtain ⟨rest, hrest⟩ := render2_markup hm vs
        rw [hrest, tokensFrom_tagOpen_lt, ← hrest, t1_ok2 r vs ls h hv]
        simp

/-- Where a slice-2 run ends: the data state, or tag open after a final `<`. -/
def EndsOK (m : M) : Prop := m.st = .data ∨ m.st = .tagOpen

theorem run_ok2 :
    ∀ (T : List Piece2) (vs : List (Option (List Char))) (ls : List Char), Ok2 T → ValsOK2 T vs →
      EndsOK (run treeStub ⟨.data, ls⟩ (render2 T vs)).1
  | [], _, _, _, _ => by simp [render2, run, EndsOK]
  | .holeText :: r, [], ls, h, hv => by
    simpa [render2] using run_ok2 r [] ls h hv
  | .holeText :: r, v :: vs, ls, h, hv => by
    simp only [render2]
    rw [run_append]
    cases v with
    | none => simpa [run] using run_ok2 r vs ls h hv.2
    | some x =>
      rw [Option.getD_some, run_textChunks treeStub ls (htmlTextSafe_chunks (hv.1 x rfl))]
      exact run_ok2 r vs ls h hv.2
  | .startTag t :: r, vs, ls, ⟨ht, h⟩, ⟨hvt, hv⟩ => by
    simp only [render2]
    obtain ⟨hr1, hr2⟩ := StartTag.toSyn_render t vs
    rw [← hr1, ← hr2, run_append, run_startTag treeStub ls (StartTag.toSyn_ok t vs ht hvt),
      StartTag.toSyn_name t vs ht, treeStub_slice2 ht.1]
    rw [hr2] at *
    exact run_ok2 r _ (lowerStr t.name) h hv
  | .endTag n :: r, vs, ls, ⟨hn, h⟩, hv => by
    simp only [render2]
    obtain ⟨h1, h2⟩ := slice2Name_ok hn
    rw [run_append, run_endTag treeStub ls h1 h2]
    exact run_ok2 r vs ls h hv
  | .comment b :: r, vs, ls, ⟨hb, h⟩, hv => by
    simp only [render2]
    have e : "<!--".toList ++ b ++ "-->".toList = ['<', '!', '-', '-'] ++ b ++ ['-', '-', '>'] := rfl
    rw [e, run_append, run_comment treeStub ls (commentBodyOK_of_tessera hb)]
    exact run_ok2 r vs ls h hv
  | .doctype d :: r, vs, ls, ⟨hd, h⟩, hv => by
    simp only [render2]
    rw [run_append, run_doctype treeStub ls hd]
    exact run_ok2 r vs ls h hv
  | .static s :: r, vs, ls, ⟨hs, hlast, h⟩, hv => by
    simp only [render2]
    rw [run_append]
    rcases run_static treeStub ls s hs with ⟨_, hrun⟩ | ⟨hl, hrun⟩
    · rw [hrun]; exact run_ok2 r vs ls h hv
    · rw [hrun]
      rcases hlast hl with rfl | hm
      · cases vs <;> simp [render2, run, EndsOK]
      · obtain ⟨rest, hrest⟩ := render2_markup hm vs
        have e : run treeStub ⟨.tagOpen, ls⟩ (render2 r vs) =
            ((run treeStub ⟨.data, ls⟩ (render2 r vs)).1,
              .char '<' :: (run treeStub ⟨.data, ls⟩ (render2 r vs)).2) := by
          rw [hrest]; simp [run, step, stepData, isAlpha]
        rw [e]; exact run_ok2 r vs ls h hv

theorem normal2_tail {p : Piece2} {r : List Piece2} (h : Normal2 (p :: r)) : Normal2 r := by
  match p, r, h with
  | _, [], _ => trivial
  | .static _, .static _ :: _, h => exact h.elim
  | .static _, .holeText :: _, h => exact h
  | .static _, .startTag _ :: _, h => exact h
  | .static _, .endTag _ :: _, h => exact h
  | .static _, .comment _ :: _, h => exact h
  | .static _, .doctype _ :: _, h => exact h
  | .holeText, q :: _, h => cases q <;> exact h
  | .startTag _, q :: _, h => cases q <;> exact h
  | .endTag _, q :: _, h => cases q <;> exact h
  | .comment _, q :: _, h => cases q <;> exact h
  | .doctype _, q :: _, h => cases q <;> exact h

theorem inSubset2_tail {p : Piece2} {r : List Piece2} (h : InSubset2 (p :: r)) : InSubset2 r :=
  ⟨normal2_tail h.1, fun q hq => h.2.1 q (List.mem_cons_of_mem _ hq),
    fun i s h1 h2 => h.2.2 (i + 1) s (by simpa using h1) (by simpa using h2)⟩

theorem inSubset2_ok : ∀ T : List Piece2, InSubset2 T → Ok2 T
  | [], _ => trivial
  | .holeText :: r, h => inSubset2_ok r (inSubset2_tail h)
  | .startTag t :: r, h => ⟨h.2.1 (.startTag t) (by simp), inSubset2_ok r (inSubset2_tail h)⟩
  | .endTag n :: r, h => ⟨h.2.1 (.endTag n) (by simp), inSubset2_ok r (inSubset2_tail h)⟩
  | .comment b :: r, h => ⟨h.2.1 (.comment b) (by simp), inSubset2_ok r (inSubset2_tail h)⟩
  | .doctype d :: r, h => ⟨h.2.1 (.doctype d) (by simp), inSubset2_ok r (inSubset2_tail h)⟩
  | .static s :: r, h => by
    refine ⟨h.2.1 (.static s) (by simp), fun hl => ?_, inSubset2_ok r (inSubset2_tail h)⟩
    match r, h.1 with
    | [], _ => exact .inl rfl
    | .static _ :: _, hn => exact hn.elim
    | .holeText :: _, _ => exact absurd hl (h.2.2 0 s rfl rfl)
    | .startTag _ :: _, _ => exact .inr trivial
    | .endTag _ :: _, _ => exact .inr trivial
    | .comment _ :: _, _ => exact .inr trivial
    | .doctype _ :: _, _ => exact .inr trivial

/--
**T1, slice 2.** For every slice-2 template in the safe subset and every list of
hole values in which each TEXT value satisfies `htmlTextSafe` and each attribute
value `htmlSafe`, the WHATWG tokenizer, with the tree builder's tokenizer switches
(`treeStub`), reads the rendered page as exactly the tokens Tessera assigned:
static text as Character tokens, each TEXT value as Character tokens plus an empty
Comment per `<!---->`, each start tag as one StartTag token whose attributes are
the static attributes and the attribute-hole values as written, each end tag,
comment and `<!DOCTYPE html>` as its one token.
-/
theorem t1_slice2 (T : List Piece2) (vs : List (Option (List Char)))
    (hT : InSubset2 T) (hv : ValsOK2 T vs) :
    tokenize treeStub (render2 T vs) = denote2 T vs :=
  t1_ok2 T vs [] (inSubset2_ok T hT) hv

/-- **T1, slice 2, coverage.** The run never reaches the `out` sink. -/
theorem t1_slice2_inModel (T : List Piece2) (vs : List (Option (List Char)))
    (hT : InSubset2 T) (hv : ValsOK2 T vs) :
    leavesModel treeStub init (render2 T vs) = false := by
  rcases run_ok2 T vs [] (inSubset2_ok T hT) hv with h | h <;> simp [leavesModel, init, h]

end TesseraT1
