/-
Theorem T1 (docs/security-architecture.md §5.1, docs/context-model.md section 1),
slice 1: element text in the HTML namespace with TEXT holes, no tags.

T1 here is token-stream equality: the WHATWG tokenizer model (`Whatwg`), run on
the rendered page to its end, emits exactly the tokens `denote` reads off the
template: each static character as a Character token, and each hole's value as
Character tokens except that each `<!---->` in it is an empty Comment. Equality
is up to character-reference decoding (see the header of Whatwg.lean). It holds
for every tree-builder stub, because a slice-1 page emits no start tags.
-/
import TesseraT1.Subset
import TesseraT1.Check
import TesseraT1.TextChunks
import TesseraT1.Whatwg

namespace TesseraT1

open Whatwg

/-- How a TEXT value reads: characters, with each `<!---->` an empty comment. -/
def textTokens : List Char → List Token
  | '<' :: '!' :: '-' :: '-' :: '-' :: '-' :: '>' :: r => .comment [] :: textTokens r
  | c :: r => .char c :: textTokens r
  | [] => []

/-- Tessera's reading of a slice-1 page, as tokens. -/
def denote : Template → List (List Char) → List Token
  | [], _ => []
  | .static s :: r, vs => s.map .char ++ denote r vs
  | .holeText :: r, [] => denote r []
  | .holeText :: r, v :: vs => textTokens v ++ denote r vs

theorem textTokens_cons {c : Char} (hc : c ≠ '<') (r : List Char) :
    textTokens (c :: r) = .char c :: textTokens r := by
  conv => lhs; rw [textTokens.eq_def]
  split
  · simp_all
  · rename_i h; obtain ⟨rfl, rfl⟩ := h; rfl
  · simp_all

theorem textTokens_plain_append {p : List Char} (hp : ∀ c ∈ p, c ≠ '<') (r : List Char) :
    textTokens (p ++ r) = p.map .char ++ textTokens r := by
  induction p with
  | nil => rfl
  | cons c p ih =>
    simp only [List.mem_cons, forall_eq_or_imp] at hp
    simp [textTokens_cons hp.1, ih hp.2]

theorem step_data_plain (tr : Tree) (ls : List Char) {c : Char} (hc : c ≠ '<') :
    step tr ⟨.data, ls⟩ c = (⟨.data, ls⟩, [.char c]) := by
  simp [step, stepData, hc]

/-- Characters other than `<` keep the data state and are emitted as is. -/
theorem run_data_plain (tr : Tree) (ls : List Char) {p : List Char} (hp : ∀ c ∈ p, c ≠ '<') :
    run tr ⟨.data, ls⟩ p = (⟨.data, ls⟩, p.map .char) := by
  induction p with
  | nil => rfl
  | cons c p ih =>
    simp only [List.mem_cons, forall_eq_or_imp] at hp
    simp [run, step_data_plain tr ls hp.1, ih hp.2]

theorem run_braceBreak (tr : Tree) (ls : List Char) :
    run tr ⟨.data, ls⟩ braceBreak = (⟨.data, ls⟩, [.comment []]) := by
  simp [braceBreak, run, step, stepData, stepMarkupDecl, stepCommentEnd]

/-- A TEXT hole's value, read from the data state, returns to the data state. -/
theorem run_textChunks (tr : Tree) (ls : List Char) {v : List Char} (h : TextChunks v) :
    run tr ⟨.data, ls⟩ v = (⟨.data, ls⟩, textTokens v) := by
  induction h with
  | nil => rfl
  | plain c r hc _ _ ih =>
    simp [run, step_data_plain tr ls hc, ih, textTokens_cons hc]
  | entity e r he _ ih =>
    have hp : ∀ c ∈ e, c ≠ '<' := by
      simp only [entities, List.mem_cons, List.not_mem_nil, or_false] at he
      rcases he with rfl | rfl | rfl | rfl | rfl <;> decide
    rw [run_append, run_data_plain tr ls hp, ih, textTokens_plain_append hp]
  | comment r _ ih =>
    rw [run_append, run_braceBreak, ih]
    simp [braceBreak, textTokens]

/-- The tag-open state on a character that cannot start a tag (`ltIsText`):
emit `<` and reconsume in the data state. -/
theorem step_tagOpen_text (tr : Tree) (ls : List Char) {d : Char}
    (hd : isAsciiAlpha d = false ∧ d ≠ '!' ∧ d ≠ '/' ∧ d ≠ '?') :
    step tr ⟨.tagOpen, ls⟩ d = ((stepData ls d).1, .char '<' :: (stepData ls d).2) := by
  have ha : isAlpha d = false := hd.1
  simp [step, ha, hd.2.1, hd.2.2.1, hd.2.2.2]

/-- A static text span, read from the data state. If it does not end in `<` the
run is back in the data state; if it does, it waits in tag open, which emits the
`<` at end of input. -/
theorem run_static (tr : Tree) (ls : List Char) :
    ∀ s : List Char, StaticText s →
      (s.getLast? ≠ some '<' ∧ run tr ⟨.data, ls⟩ s = (⟨.data, ls⟩, s.map .char)) ∨
      (s.getLast? = some '<' ∧ run tr ⟨.data, ls⟩ s = (⟨.tagOpen, ls⟩, s.dropLast.map .char))
  | [], _ => Or.inl ⟨by simp, rfl⟩
  | c :: r, h => by
    rw [staticText_cons] at h
    by_cases hc : c = '<'
    · subst hc
      have hok := h.1 rfl
      match r, hok, h.2 with
      | [], _, _ => exact Or.inr ⟨rfl, rfl⟩
      | d :: r', hok, hr =>
        have hd : isAsciiAlpha d = false ∧ d ≠ '!' ∧ d ≠ '/' ∧ d ≠ '?' := by
          simpa [ltOkB, Bool.and_eq_true, bne_iff_ne, and_assoc] using hok
        have e1 : run tr ⟨.data, ls⟩ ('<' :: d :: r') =
            ((run tr ⟨.data, ls⟩ (d :: r')).1, .char '<' :: (run tr ⟨.data, ls⟩ (d :: r')).2) := by
          simp only [run]
          rw [show step tr ⟨.data, ls⟩ '<' = (⟨.tagOpen, ls⟩, []) by simp [step, stepData]]
          rw [step_tagOpen_text tr ls hd]
          simp [step]
        rcases run_static tr ls (d :: r') hr with ⟨hl, hrun⟩ | ⟨hl, hrun⟩
        · left
          refine ⟨by simpa [List.getLast?_cons_cons] using hl, ?_⟩
          rw [e1, hrun]; simp
        · right
          refine ⟨by simpa [List.getLast?_cons_cons] using hl, ?_⟩
          rw [e1, hrun]
          simp [List.dropLast_cons_of_ne_nil]
    · have e1 : run tr ⟨.data, ls⟩ (c :: r) =
          ((run tr ⟨.data, ls⟩ r).1, .char c :: (run tr ⟨.data, ls⟩ r).2) := by
        simp [run, step_data_plain tr ls hc]
      rcases run_static tr ls r h.2 with ⟨hl, hrun⟩ | ⟨hl, hrun⟩
      · left
        refine ⟨?_, by rw [e1, hrun]; simp⟩
        cases r with
        | nil => simpa using hc
        | cons d r => simpa [List.getLast?_cons_cons] using hl
      · right
        cases r with
        | nil => simp at hl
        | cons d r =>
          refine ⟨by simpa [List.getLast?_cons_cons] using hl, ?_⟩
          rw [e1, hrun]; simp

/-- The slice-1 conditions in the shape the proof walks: each static span is
`StaticText`, and a span followed by more output does not end in `<`. -/
def Ok : Template → Prop
  | [] => True
  | .static s :: r => StaticText s ∧ (r ≠ [] → s.getLast? ≠ some '<') ∧ Ok r
  | .holeText :: r => Ok r

theorem normal_tail {p : Piece} {r : Template} (h : Normal (p :: r)) : Normal r := by
  match p, r, h with
  | _, [], _ => trivial
  | .holeText, .holeText :: _, h => exact h
  | .holeText, .static _ :: _, h => exact h
  | .static _, .holeText :: _, h => exact h

theorem inSubset_tail {p : Piece} {r : Template} (h : InSubset (p :: r)) : InSubset r :=
  ⟨normal_tail h.1, fun s hs => h.2.1 s (List.mem_cons_of_mem _ hs),
    fun i s h1 h2 => h.2.2 (i + 1) s (by simpa using h1) (by simpa using h2)⟩

theorem inSubset_ok : ∀ T : Template, InSubset T → Ok T
  | [], _ => trivial
  | .holeText :: r, h => inSubset_ok r (inSubset_tail h)
  | .static s :: r, h => by
    refine ⟨h.2.1 s (by simp), fun hr => ?_, inSubset_ok r (inSubset_tail h)⟩
    match r, hr, h.1 with
    | .holeText :: _, _, _ => exact h.2.2 0 s rfl rfl

theorem t1_ok (tr : Tree) (ls : List Char) :
    ∀ (T : Template) (vs : List (List Char)), Ok T → (∀ v ∈ vs, htmlTextSafe v) →
      tokensFrom tr ⟨.data, ls⟩ (render T vs) = denote T vs
  | [], _, _, _ => rfl
  | .holeText :: r, [], h, hv => by
    simpa [render, denote] using t1_ok tr ls r [] h hv
  | .holeText :: r, v :: vs, h, hv => by
    simp only [render, denote]
    rw [tokensFrom_append, run_textChunks tr ls (htmlTextSafe_chunks (hv v (by simp)))]
    rw [t1_ok tr ls r vs h (fun w hw => hv w (List.mem_cons_of_mem _ hw))]
  | .static s :: r, vs, ⟨hs, hlast, hr⟩, hv => by
    simp only [render, denote]
    rw [tokensFrom_append]
    rcases run_static tr ls s hs with ⟨_, hrun⟩ | ⟨hl, hrun⟩
    · rw [hrun, t1_ok tr ls r vs hr hv]
    · cases r with
      | cons p r => exact absurd hl (hlast (by simp))
      | nil =>
        rw [hrun]
        cases vs <;>
        · simp only [render, denote, tokensFrom, run, eofTokens, List.append_nil]
          obtain ⟨s', rfl⟩ : ∃ s', s = s' ++ ['<'] := by
            refine ⟨s.dropLast, ?_⟩
            have hne : s ≠ [] := by rintro rfl; simp at hl
            have hg := List.dropLast_concat_getLast hne
            rw [List.getLast?_eq_some_getLast hne] at hl
            simp only [Option.some.injEq] at hl
            rw [hl] at hg
            exact hg.symm
          simp

/--
**T1, slice 1.** For every slice-1 template in the safe subset and every list of
TEXT-escaper outputs, the WHATWG tokenizer reads the rendered page as exactly the
tokens Tessera assigned: static characters as Character tokens, each value as
Character tokens plus an empty Comment per `<!---->`. Holds for any tree-builder
stub (no start tag is emitted), in particular `treeStub`.
-/
theorem t1_slice1 (tr : Tree) (T : Template) (vs : List (List Char))
    (hT : InSubset T) (hv : ∀ v ∈ vs, htmlTextSafe v) :
    tokenize tr (render T vs) = denote T vs :=
  t1_ok tr [] T vs (inSubset_ok T hT) hv

theorem textTokens_kinds (v : List Char) :
    ∀ t ∈ textTokens v, (∃ c, t = .char c) ∨ t = .comment [] := by
  fun_induction textTokens v <;> simp_all

theorem denote_kinds (T : Template) (vs : List (List Char)) :
    ∀ t ∈ denote T vs, (∃ c, t = .char c) ∨ t = .comment [] := by
  fun_induction denote T vs with
  | case1 => simp
  | case2 s r vs ih => intro t ht; simp at ht; rcases ht with ⟨c, _, rfl⟩ | ht; exact Or.inl ⟨c, rfl⟩; exact ih t ht
  | case3 r ih => exact ih
  | case4 r v vs ih => intro t ht; simp at ht; rcases ht with ht | ht; exact textTokens_kinds v t ht; exact ih t ht

theorem run_ok (tr : Tree) (ls : List Char) :
    ∀ (T : Template) (vs : List (List Char)), Ok T → (∀ v ∈ vs, htmlTextSafe v) →
      (run tr ⟨.data, ls⟩ (render T vs)).1 = ⟨.data, ls⟩ ∨
        (run tr ⟨.data, ls⟩ (render T vs)).1 = ⟨.tagOpen, ls⟩
  | [], _, _, _ => Or.inl rfl
  | .holeText :: r, [], h, hv => by simpa [render] using run_ok tr ls r [] h hv
  | .holeText :: r, v :: vs, h, hv => by
    simp only [render]
    rw [run_append, run_textChunks tr ls (htmlTextSafe_chunks (hv v (by simp)))]
    exact run_ok tr ls r vs h (fun w hw => hv w (List.mem_cons_of_mem _ hw))
  | .static s :: r, vs, ⟨hs, hlast, hr⟩, hv => by
    simp only [render]
    rw [run_append]
    rcases run_static tr ls s hs with ⟨_, hrun⟩ | ⟨hl, hrun⟩
    · rw [hrun]; exact run_ok tr ls r vs hr hv
    · cases r with
      | cons p r => exact absurd hl (hlast (by simp))
      | nil => rw [hrun]; cases vs <;> exact Or.inr rfl

/-- **T1, slice 1, coverage.** The run never reaches the `out` sink: a slice-1
page stays inside the modeled tokenizer fragment. -/
theorem t1_slice1_inModel (tr : Tree) (T : Template) (vs : List (List Char))
    (hT : InSubset T) (hv : ∀ v ∈ vs, htmlTextSafe v) :
    leavesModel tr init (render T vs) = false := by
  rcases run_ok tr [] T vs (inSubset_ok T hT) hv with h | h <;>
    simp [leavesModel, init, h]

/-- Corollary: every token of a slice-1 page is a Character or an empty Comment. -/
theorem t1_slice1_tokens (T : Template) (vs : List (List Char))
    (hT : InSubset T) (hv : ∀ v ∈ vs, htmlTextSafe v) :
    ∀ t ∈ tokenize treeStub (render T vs), (∃ c, t = .char c) ∨ t = .comment [] := by
  rw [t1_slice1 treeStub T vs hT hv]
  exact denote_kinds T vs

end TesseraT1
