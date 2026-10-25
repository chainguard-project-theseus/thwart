/-
A tree builder with state, on top of the tokenizer model.

`Whatwg.run` takes the tree builder as a function of the start tag's name alone. The
tree construction stage (13.2.6) decides the tokenizer's next state from the stack of
open elements too: inside foreign content a `title` or `script` start tag does not switch
the tokenizer, and inside a `template` some insertion modes ignore it. `TreeSM` is a tree
builder with a state `σ` that every tag token updates; `runS` runs the tokenizer with it.

The bridge to `Whatwg.run`: the tokenizer consults the tree builder only when it emits a
start tag (`emitTag`), so a run whose tokens have at most one tag before any start tag
(`OneStart`) reads the same with the state fixed at its start (`runS_run`). That lets
every lemma proved for `run tr` apply to one element at a time.
-/
import TesseraT1.Whatwg

namespace TesseraT1.Whatwg

def Token.isStart : Token → Bool
  | .startTag .. => true
  | _ => false

def Token.isTag : Token → Bool
  | .startTag .. => true
  | .endTag _ => true
  | _ => false

/-- A tree builder with state: the tokenizer state after a start tag, and the state
updates for start tags (name, self-closing flag) and end tags. Names are as the
tokenizer emits them (ASCII-lowercased). -/
structure TreeSM (σ : Type) where
  next : σ → List Char → St
  push : σ → List Char → Bool → σ
  pop : σ → List Char → σ

variable {σ : Type}

/-- The name-only tree builder the tokenizer sees in state `s`. -/
def TreeSM.tree (T : TreeSM σ) (s : σ) : Tree := T.next s

def TreeSM.apply (T : TreeSM σ) (s : σ) : Token → σ
  | .startTag n _ sc => T.push s n sc
  | .endTag n => T.pop s n
  | _ => s

def TreeSM.applyAll (T : TreeSM σ) (s : σ) (ts : List Token) : σ := ts.foldl T.apply s

theorem TreeSM.applyAll_append (T : TreeSM σ) (s : σ) (a b : List Token) :
    T.applyAll s (a ++ b) = T.applyAll (T.applyAll s a) b := List.foldl_append ..

/-- One step with the tree builder's state. -/
def stepS (T : TreeSM σ) (s : σ) (m : M) (c : Char) : (M × σ) × List Token :=
  let r := step (T.tree s) m c
  ((r.1, T.applyAll s r.2), r.2)

/-- Run the tokenizer with the tree builder's state. -/
def runS (T : TreeSM σ) : σ → M → List Char → (M × σ) × List Token
  | s, m, [] => ((m, s), [])
  | s, m, c :: cs =>
    let r1 := stepS T s m c
    let r2 := runS T r1.1.2 r1.1.1 cs
    (r2.1, r1.2 ++ r2.2)

theorem runS_append (T : TreeSM σ) (s : σ) (m : M) (a b : List Char) :
    runS T s m (a ++ b) =
      ((runS T (runS T s m a).1.2 (runS T s m a).1.1 b).1,
        (runS T s m a).2 ++ (runS T (runS T s m a).1.2 (runS T s m a).1.1 b).2) := by
  induction a generalizing s m with
  | nil => simp [runS]
  | cons c a ih => simp [runS, ih, List.append_assoc]

/-! ### The tokenizer consults the tree builder only at a start tag -/

def NoStart (ts : List Token) : Prop := ∀ t ∈ ts, t.isStart = false
def NoTag (ts : List Token) : Prop := ∀ t ∈ ts, t.isTag = false

/-- After the first tag token no start tag follows: every start tag is emitted in the
tree-builder state the run started in. -/
def OneStart : List Token → Prop
  | [] => True
  | t :: r => if t.isTag then NoStart r else OneStart r

/-- Two step results that differ at most in the state after a start tag. -/
def Indep (r r' : R) : Prop :=
  r.2 = r'.2 ∧ r.1.lastStart = r'.1.lastStart ∧ (NoStart r.2 → r.1.st = r'.1.st)

theorem indep_refl (r : R) : Indep r r := ⟨rfl, rfl, fun _ => rfl⟩

theorem emitTag_indep (tr tr' : Tree) (ls : List Char) (t : Tag) :
    Indep (emitTag tr ls t) (emitTag tr' ls t) := by
  unfold emitTag
  split
  · exact indep_refl _
  · refine ⟨rfl, rfl, fun h => ?_⟩
    have := h _ (List.mem_singleton_self _)
    simp [Token.isStart] at this

theorem stepTagName_indep (tr tr' : Tree) (ls : List Char) (t : Tag) (c : Char) :
    Indep (stepTagName tr ls t c) (stepTagName tr' ls t c) := by
  unfold stepTagName
  repeat' split
  all_goals first | exact indep_refl _ | exact emitTag_indep tr tr' ls t

theorem stepAttrName_indep (tr tr' : Tree) (ls : List Char) (t : Tag) (c : Char) :
    Indep (stepAttrName tr ls t c) (stepAttrName tr' ls t c) := by
  unfold stepAttrName
  repeat' split
  all_goals first | exact indep_refl _ | exact emitTag_indep tr tr' ls t

theorem stepBeforeAttrName_indep (tr tr' : Tree) (ls : List Char) (t : Tag) (c : Char) :
    Indep (stepBeforeAttrName tr ls t c) (stepBeforeAttrName tr' ls t c) := by
  unfold stepBeforeAttrName
  repeat' split
  all_goals first
    | exact indep_refl _
    | exact emitTag_indep tr tr' ls t
    | exact stepAttrName_indep tr tr' ls _ c

theorem stepAttrValueUnq_indep (tr tr' : Tree) (ls : List Char) (t : Tag) (c : Char) :
    Indep (stepAttrValueUnq tr ls t c) (stepAttrValueUnq tr' ls t c) := by
  unfold stepAttrValueUnq
  repeat' split
  all_goals first | exact indep_refl _ | exact emitTag_indep tr tr' ls t

theorem stepRawEndTagName_indep (tr tr' : Tree) (k : Raw) (ls buf : List Char) (c : Char) :
    Indep (stepRawEndTagName tr k ls buf c) (stepRawEndTagName tr' k ls buf c) := by
  unfold stepRawEndTagName
  repeat' split
  all_goals first | exact indep_refl _ | exact emitTag_indep tr tr' ls _

theorem step_indep (tr tr' : Tree) (m : M) (c : Char) : Indep (step tr m c) (step tr' m c) := by
  obtain ⟨st, ls⟩ := m
  cases st <;> simp only [step] <;> repeat' split
  all_goals first
    | exact indep_refl _
    | exact emitTag_indep tr tr' ls _
    | exact stepTagName_indep tr tr' ls _ c
    | exact stepAttrName_indep tr tr' ls _ c
    | exact stepBeforeAttrName_indep tr tr' ls _ c
    | exact stepAttrValueUnq_indep tr tr' ls _ c
    | exact stepRawEndTagName_indep tr tr' _ ls _ c

theorem indep_eq {r r' : R} (h : Indep r r') (hn : NoStart r.2) : r = r' := by
  obtain ⟨⟨st, ls⟩, ts⟩ := r
  obtain ⟨⟨st', ls'⟩, ts'⟩ := r'
  obtain ⟨e1, e2, e3⟩ := h
  simp only at e1 e2 e3 hn
  subst e1 e2
  rw [e3 hn]

theorem noTag_noStart {ts : List Token} (h : NoTag ts) : NoStart ts := by
  intro t ht
  have := h t ht
  cases t <;> simp [Token.isTag, Token.isStart] at this ⊢

theorem applyAll_noTag (T : TreeSM σ) (s : σ) : ∀ ts, NoTag ts → T.applyAll s ts = s
  | [], _ => rfl
  | t :: r, h => by
    have ht := h t (by simp)
    have e : T.apply s t = s := by
      cases t <;> first | rfl | (simp [Token.isTag] at ht)
    simp only [TreeSM.applyAll, List.foldl_cons, e]
    exact applyAll_noTag T s r (fun u hu => h u (by simp [hu]))

theorem oneStart_append_noTag : ∀ (a b : List Token), NoTag a → OneStart (a ++ b) → OneStart b
  | [], _, _, h => h
  | t :: a, b, hn, h => by
    have ht := hn t (by simp)
    simp only [List.cons_append, OneStart, ht, Bool.false_eq_true, ite_false] at h
    exact oneStart_append_noTag a b (fun u hu => hn u (by simp [hu])) h

theorem oneStart_append_tag : ∀ (a b : List Token), ¬ NoTag a → OneStart (a ++ b) → NoStart b
  | [], _, hn, _ => absurd (fun t ht => by simp at ht) hn
  | t :: a, b, hn, h => by
    cases ht : t.isTag
    · simp only [List.cons_append, OneStart, ht, Bool.false_eq_true, ite_false] at h
      exact oneStart_append_tag a b (fun ha => hn (fun u hu => by
        rcases List.mem_cons.mp hu with rfl | hu
        · exact ht
        · exact ha u hu)) h
    · simp only [List.cons_append, OneStart, ht, ite_true] at h
      exact fun u hu => h u (by simp [hu])

/-- **A run without start tags reads the same under any tree builder**, and the state
update is the fold over its tokens. -/
theorem runS_run_noStart (T : TreeSM σ) : ∀ (s : List Char) (sc : σ) (tr : Tree) (m : M),
    NoStart (run tr m s).2 →
    runS T sc m s = (((run tr m s).1, T.applyAll sc (run tr m s).2), (run tr m s).2)
  | [], _, _, _, _ => by simp [runS, run, TreeSM.applyAll]
  | c :: cs, sc, tr, m, h => by
    have h1 : NoStart (step tr m c).2 := fun t ht => h t (by simp [run, ht])
    have h2 : NoStart (run tr (step tr m c).1 cs).2 := fun t ht => h t (by simp [run, ht])
    have hst : step (T.tree sc) m c = step tr m c :=
      indep_eq (step_indep _ _ m c) (by rw [(step_indep (T.tree sc) tr m c).1]; exact h1)
    have ih := runS_run_noStart T cs (T.applyAll sc (step tr m c).2) tr (step tr m c).1 h2
    simp only [runS, stepS]
    rw [hst, ih]
    simp [run, TreeSM.applyAll, List.foldl_append]

/-- **One element at a time.** A run whose tokens have at most one tag before any start
tag reads under the stateful tree builder as under the name-only tree builder of its
starting state, and the state after it is the fold over its tokens. -/
theorem runS_run (T : TreeSM σ) : ∀ (s : List Char) (s0 : σ) (m : M),
    OneStart (run (T.tree s0) m s).2 →
    runS T s0 m s =
      (((run (T.tree s0) m s).1, T.applyAll s0 (run (T.tree s0) m s).2), (run (T.tree s0) m s).2)
  | [], _, _, _ => by simp [runS, run, TreeSM.applyAll]
  | c :: cs, s0, m, h => by
    have h' : OneStart ((step (T.tree s0) m c).2 ++ (run (T.tree s0) (step (T.tree s0) m c).1 cs).2) := by
      simpa [run] using h
    by_cases ht : NoTag (step (T.tree s0) m c).2
    · have hs : T.applyAll s0 (step (T.tree s0) m c).2 = s0 := applyAll_noTag T s0 _ ht
      have ih := runS_run T cs s0 (step (T.tree s0) m c).1 (oneStart_append_noTag _ _ ht h')
      simp only [runS, stepS]
      rw [hs, ih]
      simp only [run, TreeSM.applyAll_append, hs]
    · have ih := runS_run_noStart T cs (T.applyAll s0 (step (T.tree s0) m c).2) (T.tree s0)
        (step (T.tree s0) m c).1 (oneStart_append_tag _ _ ht h')
      simp only [runS, stepS]
      rw [ih]
      simp [run, TreeSM.applyAll, List.foldl_append]

end TesseraT1.Whatwg
