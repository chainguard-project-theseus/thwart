/-
The RCDATA and RAWTEXT states (13.2.5.2-3, 9-14) read the body of a `title`,
`textarea`, `style` or `iframe` element (and, with scripting enabled, `noscript`)
as Character tokens up to Tessera's end tag.

The only transition that leaves what Tessera allows is an appropriate end tag
before Tessera's. Static text never contains one (S8, `NoRawDangerS`); static
text before a hole leaves the plain state (S12, `goodR_plain`), and a value never
supplies a `<` that could start one: RCDATA values are `htmlSafe` (no `<`), TEXT
values in `noscript` are `htmlTextSafe`, whose only `<` begin `<!---->`.
-/
import TesseraT1.Script
import TesseraT1.TextChunks

namespace TesseraT1

open Whatwg

/-- The two non-script raw-text content models. -/
def NotScript (k : Raw) : Prop := k = .rcdata ∨ k = .rawtext

theorem notScript_ne {k : Raw} (hk : NotScript k) : k ≠ .script := by
  rcases hk with rfl | rfl <;> simp

/-- The RCDATA/RAWTEXT states with the static text `P` read since the last value. -/
inductive GoodR (k : Raw) : St → List Char → Prop where
  | raw (P : List Char) : GoodR k (.raw k) P
  | lt (P : List Char) : GoodR k (.rawLt k) (P ++ ['<'])
  | eto (P : List Char) : GoodR k (.rawEndTagOpen k) (P ++ ['<', '/'])
  | etn (P buf : List Char) : (∀ c ∈ buf, isAlpha c = true) →
      GoodR k (.rawEndTagName k buf) (P ++ ['<', '/'] ++ buf)

/-- `Q` ends in an appropriate end tag for `n` followed by a delimiter. -/
def DangerR (n Q : List Char) : Prop :=
  ∃ P L d, Q = P ++ ['<', '/'] ++ L ++ [d] ∧ lowerStr L = n ∧ isDelim d

/-- No prefix of `s` ends in an appropriate end tag for `n`. -/
def NoRawDangerS (n s : List Char) : Prop := ∀ i, ¬ DangerR n (s.take i)

theorem appropriate_iff {n buf : List Char} : appropriate n buf = true ↔ lowerStr buf = n := by
  simp [appropriate]

theorem stepRaw_r {k : Raw} (ls P : List Char) {c : Char} (hn : c ≠ '\x00') :
    ∃ st' out, stepRaw k ls c = (⟨st', ls⟩, out.map .char) ∧ GoodR k st' (P ++ [c]) ∧
      pend (.raw k) ++ [c] = out ++ pend st' := by
  by_cases h : c = '<'
  · subst h; exact ⟨_, [], by simp [stepRaw], .lt P, by simp [pend]⟩
  · exact ⟨_, [c], by simp [stepRaw, h, hn], .raw _, by simp [pend]⟩

/-- **Static RCDATA/RAWTEXT text**, one character. -/
theorem goodR_step (tr : Tree) {k : Raw} (hk : NotScript k) {n : List Char} {st : St}
    {P : List Char} (h : GoodR k st P) {c : Char} (hn : c ≠ '\x00') (hd : ¬ DangerR n (P ++ [c])) :
    ∃ st' out, step tr ⟨st, n⟩ c = (⟨st', n⟩, out.map .char) ∧ GoodR k st' (P ++ [c]) ∧
      pend st ++ [c] = out ++ pend st' := by
  have hks : k ≠ .script := notScript_ne hk
  cases h with
  | raw P => simpa [step] using stepRaw_r n P (k := k) hn
  | lt P =>
    by_cases h1 : c = '/'
    · subst h1
      exact ⟨.rawEndTagOpen k, [], by simp [step], by simpa using GoodR.eto P, by simp [pend]⟩
    obtain ⟨st', out, e, g, hp⟩ := stepRaw_r n (P ++ ['<']) (k := k) hn
    refine ⟨st', '<' :: out, ?_, g, ?_⟩
    · simp only [step, h1, ite_false, hks, false_and]; rw [e]; simp
    · simp [pend] at hp ⊢; exact hp
  | eto P =>
    by_cases h1 : isAlpha c = true
    · exact ⟨.rawEndTagName k [c], [], by simp [step, h1],
        by simpa using GoodR.etn P [c] (by simp [h1]), by simp [pend]⟩
    obtain ⟨st', out, e, g, hp⟩ := stepRaw_r n (P ++ ['<', '/']) (k := k) hn
    refine ⟨st', '<' :: '/' :: out, ?_, g, ?_⟩
    · simp only [step, h1, ite_false]; rw [e]; simp
    · simp [pend] at hp ⊢; exact hp
  | etn P buf ha =>
    have hdl : ¬ (isDelim c ∧ lowerStr buf = n) := by
      rintro ⟨hc, hb⟩
      exact hd ⟨P, buf, c, by simp, hb, hc⟩
    by_cases h1 : isAlpha c = true
    · obtain ⟨hw, h2, h3⟩ := alpha_not_ws h1
      have hall : ∀ d ∈ buf ++ [c], isAlpha d = true := fun d hd => by
        rcases List.mem_append.mp hd with h | h
        · exact ha d h
        · simp at h; subst h; exact h1
      refine ⟨.rawEndTagName k (buf ++ [c]), [], ?_, by simpa using GoodR.etn P (buf ++ [c]) hall,
        by simp [pend]⟩
      simp [step, stepRawEndTagName, hw, h2, h3, h1]
    · have n1 : ¬ (isWs c = true ∧ appropriate n buf = true) := fun ⟨a, b⟩ =>
        hdl ⟨.inl a, appropriate_iff.mp b⟩
      have n2 : ¬ (c = '/' ∧ appropriate n buf = true) := fun ⟨a, b⟩ =>
        hdl ⟨.inr (.inl a), appropriate_iff.mp b⟩
      have n3 : ¬ (c = '>' ∧ appropriate n buf = true) := fun ⟨a, b⟩ =>
        hdl ⟨.inr (.inr a), appropriate_iff.mp b⟩
      obtain ⟨st', out, e, g, hp⟩ := stepRaw_r n (P ++ ['<', '/'] ++ buf) (k := k) hn
      refine ⟨st', ['<', '/'] ++ buf ++ out, ?_, g, ?_⟩
      · simp only [step, stepRawEndTagName, ite_eq_right_of_eq_false _ _ (eq_false n1),
          ite_eq_right_of_eq_false _ _ (eq_false n2), ite_eq_right_of_eq_false _ _ (eq_false n3),
          ite_eq_right_of_eq_false _ _ (eq_false h1)]
        rw [e]; simp
      · simp [pend] at hp ⊢; exact hp

/-- **Static RCDATA/RAWTEXT text.** -/
theorem goodR_run (tr : Tree) {k : Raw} (hk : NotScript k) (n : List Char) :
    ∀ (s : List Char) {st : St} {P : List Char}, GoodR k st P → '\x00' ∉ s →
      (∀ Q, Q <+: s → Q ≠ [] → ¬ DangerR n (P ++ Q)) →
      ∃ st' out, run tr ⟨st, n⟩ s = (⟨st', n⟩, out.map .char) ∧ GoodR k st' (P ++ s) ∧
        pend st ++ s = out ++ pend st'
  | [], st, P, h, _, _ => ⟨st, [], rfl, by simpa using h, by simp⟩
  | c :: s, st, P, h, hn, hd => by
    have hc : c ≠ '\x00' := fun e => hn (e ▸ List.mem_cons_self ..)
    obtain ⟨s1, o1, e1, g1, p1⟩ := goodR_step tr hk h hc (hd [c] (by simp) (by simp))
    obtain ⟨s2, o2, e2, g2, p2⟩ := goodR_run tr hk n s g1 (fun m => hn (List.mem_cons_of_mem _ m))
      (fun Q hq hne => by
        have := hd (c :: Q) (List.cons_prefix_cons.mpr ⟨rfl, hq⟩) (by simp)
        simpa using this)
    refine ⟨s2, o1 ++ o2, by simp [run, e1, e2], by simpa using g2, ?_⟩
    calc pend st ++ c :: s = (pend st ++ [c]) ++ s := by simp
      _ = o1 ++ (pend s1 ++ s) := by rw [p1]; simp
      _ = (o1 ++ o2) ++ pend s2 := by rw [p2]; simp

theorem noRawDanger_prefix {n s : List Char} (h : NoRawDangerS n s) :
    ∀ Q, Q <+: s → Q ≠ [] → ¬ DangerR n ([] ++ Q) := by
  intro Q hq _
  obtain ⟨t, rfl⟩ := hq
  simpa using h Q.length

/-- Static text that does not end in `<` or `</` + letters leaves the plain state. -/
theorem goodR_plain {k : Raw} {st : St} {P : List Char} (h : GoodR k st P)
    (hl : ∀ P', P ≠ P' ++ ['<']) (hs : ∀ P' buf, (∀ c ∈ buf, isAlpha c = true) →
      P ≠ P' ++ ['<', '/'] ++ buf) : st = .raw k := by
  cases h with
  | raw => rfl
  | lt P => exact absurd rfl (hl P)
  | eto P => exact absurd (by simp) (hs P [] (by simp))
  | etn P buf ha => exact absurd rfl (hs P buf ha)

/-- An RCDATA value (`htmlSafe`: no `<`, no NUL) keeps the plain state. -/
theorem raw_plain_run (tr : Tree) (k : Raw) (n : List Char) :
    ∀ (v : List Char), '<' ∉ v → '\x00' ∉ v →
      run tr ⟨.raw k, n⟩ v = (⟨.raw k, n⟩, v.map .char)
  | [], _, _ => rfl
  | c :: v, hl, hn => by
    have h1 : c ≠ '<' := fun e => hl (e ▸ List.mem_cons_self ..)
    have h2 : c ≠ '\x00' := fun e => hn (e ▸ List.mem_cons_self ..)
    simp [run, step, stepRaw, h1, h2,
      raw_plain_run tr k n v (fun m => hl (List.mem_cons_of_mem _ m))
        (fun m => hn (List.mem_cons_of_mem _ m))]

/-- `<!---->` in RCDATA or RAWTEXT is seven characters: `<!` does not open the
escaped states there (only script data has them). -/
theorem raw_braceBreak (tr : Tree) {k : Raw} (hk : NotScript k) (n : List Char) :
    run tr ⟨.raw k, n⟩ braceBreak = (⟨.raw k, n⟩, braceBreak.map .char) := by
  have hks : k ≠ .script := notScript_ne hk
  simp [braceBreak, run, step, stepRaw, hks]

/-- A TEXT value (`htmlTextSafe`, no NUL) read as RCDATA or RAWTEXT keeps the plain
state and is its own characters: its `<` begin `<!---->`. -/
theorem raw_chunks_run (tr : Tree) {k : Raw} (hk : NotScript k) (n : List Char) {v : List Char}
    (h : TextChunks v) (hn : '\x00' ∉ v) :
    run tr ⟨.raw k, n⟩ v = (⟨.raw k, n⟩, v.map .char) := by
  induction h with
  | nil => rfl
  | plain c r hc _ _ ih =>
    have h2 : c ≠ '\x00' := fun e => hn (e ▸ List.mem_cons_self ..)
    simp [run, step, stepRaw, hc, h2, ih (fun m => hn (List.mem_cons_of_mem _ m))]
  | entity e r he _ ih =>
    have hp : '<' ∉ e ∧ '\x00' ∉ e := by
      simp only [entities, List.mem_cons, List.not_mem_nil, or_false] at he
      rcases he with rfl | rfl | rfl | rfl | rfl <;> decide
    rw [run_append, raw_plain_run tr k n e hp.1 hp.2,
      ih (fun m => hn (List.mem_append.mpr (.inr m)))]
    simp
  | comment r _ ih =>
    rw [run_append, raw_braceBreak tr hk n, ih (fun m => hn (List.mem_append.mpr (.inr m)))]
    simp

/-- **Tessera's end tag.** From any RCDATA/RAWTEXT state, `</` + a non-empty name that
lowercases to the element's name + `>` emits the held-back characters and the end tag. -/
theorem goodR_close (tr : Tree) {k : Raw} (hk : NotScript k) {n : List Char} {st : St}
    {P : List Char} (h : GoodR k st P) {E : List Char} (hE : lowerStr E = n) (hE0 : E ≠ [])
    (hEa : ∀ c ∈ E, isAlpha c = true) :
    run tr ⟨st, n⟩ (['<', '/'] ++ E ++ ['>']) =
      (⟨.data, n⟩, (pend st).map .char ++ [.endTag n]) := by
  have hks : k ≠ .script := notScript_ne hk
  -- `<` from any raw state reaches the less-than sign state.
  have e1 : step tr ⟨st, n⟩ '<' = (⟨.rawLt k, n⟩, (pend st).map .char) := by
    cases h with
    | raw => simp [step, stepRaw, pend]
    | lt => simp [step, stepRaw, pend, hks]
    | eto => simp [step, stepRaw, pend, isAlpha]
    | etn _ buf _ => simp [step, stepRawEndTagName, stepRaw, pend, isAlpha, isWs]
  obtain ⟨c, r, rfl⟩ : ∃ c r, E = c :: r := by
    cases E with
    | nil => exact absurd rfl hE0
    | cons c r => exact ⟨c, r, rfl⟩
  have hc : isAlpha c = true := hEa c (by simp)
  -- The name, letter by letter, in the end tag name state.
  have hname : ∀ (q b : List Char), (∀ d ∈ q, isAlpha d = true) →
      run tr ⟨.rawEndTagName k b, n⟩ q = (⟨.rawEndTagName k (b ++ q), n⟩, []) := by
    intro q
    induction q with
    | nil => intro b _; simp [run]
    | cons d q ih =>
      intro b hq
      have hd : isAlpha d = true := hq d (by simp)
      obtain ⟨hw, h2, h3⟩ := alpha_not_ws hd
      simp only [run]
      rw [show step tr ⟨.rawEndTagName k b, n⟩ d = (⟨.rawEndTagName k (b ++ [d]), n⟩, []) by
        simp [step, stepRawEndTagName, hw, h2, h3, hd]]
      simp only
      rw [ih (b ++ [d]) (fun x hx => hq x (by simp [hx]))]
      simp
  simp only [List.cons_append, List.nil_append, run]
  rw [e1]
  simp only
  rw [show step tr ⟨.rawLt k, n⟩ '/' = (⟨.rawEndTagOpen k, n⟩, []) by simp [step]]
  simp only
  rw [show step tr ⟨.rawEndTagOpen k, n⟩ c = (⟨.rawEndTagName k [c], n⟩, []) by simp [step, hc]]
  simp only
  rw [run_append, hname r [c] (fun d hd => hEa d (by simp [hd]))]
  have hap : appropriate n (c :: r) = true := appropriate_iff.mpr hE
  simp [run, step, stepRawEndTagName, isWs, hap, emitTag, endTagOf, hE]

end TesseraT1
