/-
The script data states (13.2.5.4, 15-19) read the body of a `<script>` element,
static JavaScript interleaved with JSON hole values, as Character tokens up to
Tessera's end tag.

Two transitions leave the fragment Tessera allows: `<!--` (script data escape
start dash on `-`, into the escaped states) and an appropriate end tag before
Tessera's. Static script text never contains either (S6b, S8:
`NoDangerS`); across a static/value boundary the JSON value's first
characters rule them out (`JsonFirst`); and static text before a value does not
end in `<!-` (S12).
-/
import TesseraT1.Whatwg

namespace TesseraT1

open Whatwg

/-- The last start tag's name inside a script element. -/
abbrev scriptName : List Char := ['s', 'c', 'r', 'i', 'p', 't']

theorem script_toList : "script".toList = scriptName := rfl

/-- Characters the tokenizer holds back in a script data state (emitted later or
flushed as text). -/
def pend : St → List Char
  | .rawLt _ => ['<']
  | .rawEndTagOpen _ => ['<', '/']
  | .rawEndTagName _ buf => ['<', '/'] ++ buf
  | _ => []

/-- An end tag delimiter after a tag name. -/
def isDelim (c : Char) : Prop := isWs c = true ∨ c = '/' ∨ c = '>'

/-- `Q` ends where the tokenizer would leave the fragment Tessera allows: `<!--`,
or `</` + six letters ASCII-lowercasing to `script` + a delimiter. -/
def DangerEnd (Q : List Char) : Prop :=
  (∃ P, Q = P ++ ['<', '!', '-', '-']) ∨
  (∃ P L d, Q = P ++ ['<', '/'] ++ L ++ [d] ∧ lowerStr L = scriptName ∧ isDelim d)

/-- No prefix of `s` ends in danger: `s` contains no `<!--` and no `</script`
(ASCII case-insensitive) followed by whitespace, `/` or `>`. -/
def NoDangerS (s : List Char) : Prop := ∀ i, ¬ DangerEnd (s.take i)

/-- The first characters of a JSON value `Escapers.json` writes (J1-J4), with J0's
consequences that matter here (no `<`, no NUL). -/
def JsonFirst (v : List Char) : Prop :=
  '<' ∉ v ∧ '\x00' ∉ v ∧
  ∃ c w, v = c :: w ∧
    (c = '"' ∨ c = '-' ∨ c.isDigit = true ∨ c = 't' ∨ c = 'f' ∨ c = 'n' ∨ c = '[' ∨ c = '{') ∧
    (c = '-' → ∃ d w', w = d :: w' ∧ d.isDigit = true) ∧
    (c = 't' → ∃ w', w = 'r' :: w')

/-- The script data states with the static text `P` read since the last value.
An end tag name buffer either lies inside `P` (`etnIn`) or contains value letters
and so can never grow into `script` (`etnOut`). -/
inductive Good : St → List Char → Prop where
  | raw (P : List Char) : Good (.raw .script) P
  | lt (P : List Char) : Good (.rawLt .script) (P ++ ['<'])
  | eto (P : List Char) : Good (.rawEndTagOpen .script) (P ++ ['<', '/'])
  | etnIn (P buf : List Char) : (∀ c ∈ buf, isAlpha c = true) →
      Good (.rawEndTagName .script buf) (P ++ ['<', '/'] ++ buf)
  | etnOut (P buf : List Char) : (∀ c ∈ buf, isAlpha c = true) →
      ¬ (lowerStr buf <+: scriptName) → Good (.rawEndTagName .script buf) P
  | esc (P : List Char) : Good .scriptEscapeStart (P ++ ['<', '!'])
  | escDash (P : List Char) : Good .scriptEscapeStartDash (P ++ ['<', '!', '-'])

/-- One step's outcome: the next state is `Good` and the characters emitted plus
those now held back are those held back before plus `c`. -/
def StepOK (r : R) (ls : List Char) (st : St) (c : Char) (Q : List Char) : Prop :=
  ∃ st' out, r = (⟨st', ls⟩, out.map .char) ∧ Good st' Q ∧ pend st ++ [c] = out ++ pend st'

theorem stepRaw_script (ls : List Char) (P : List Char) {c : Char} (hn : c ≠ '\x00') :
    StepOK (stepRaw .script ls c) ls (.raw .script) c (P ++ [c]) := by
  by_cases h : c = '<'
  · subst h; exact ⟨_, [], by simp [stepRaw], .lt P, by simp [pend]⟩
  · exact ⟨_, [c], by simp [stepRaw, h, hn], .raw _, by simp [pend]⟩

theorem lowerStr_append (a b : List Char) : lowerStr (a ++ b) = lowerStr a ++ lowerStr b := by
  simp [lowerStr]

theorem appropriate_script {buf : List Char} :
    appropriate scriptName buf = true ↔ lowerStr buf = scriptName := by
  simp [appropriate]

/-- **Static script text**, one character. -/
theorem good_step (tr : Tree) {st : St} {P : List Char} (h : Good st P) {c : Char}
    (hn : c ≠ '\x00') (hd : ¬ DangerEnd (P ++ [c])) :
    StepOK (step tr ⟨st, scriptName⟩ c) scriptName st c (P ++ [c]) := by
  cases h with
  | raw P =>
    simpa [step] using stepRaw_script scriptName P hn
  | lt P =>
    by_cases h1 : c = '/'
    · subst h1
      exact ⟨.rawEndTagOpen .script, [], by simp [step], by simpa using Good.eto P, by simp [pend]⟩
    by_cases h2 : c = '!'
    · subst h2
      exact ⟨.scriptEscapeStart, ['<', '!'], by simp [step], by simpa using Good.esc P, by simp [pend]⟩
    obtain ⟨st', out, e, g, hp⟩ := stepRaw_script scriptName (P ++ ['<']) hn
    refine ⟨st', '<' :: out, ?_, g, ?_⟩
    · simp only [step, h1, h2, false_and, ite_false]; rw [e]; simp
    · simp [pend] at hp ⊢; exact hp
  | eto P =>
    by_cases h1 : isAlpha c = true
    · exact ⟨.rawEndTagName .script [c], [], by simp [step, h1], by simpa using Good.etnIn P [c] (by simp [h1]),
        by simp [pend]⟩
    obtain ⟨st', out, e, g, hp⟩ := stepRaw_script scriptName (P ++ ['<', '/']) hn
    refine ⟨st', '<' :: '/' :: out, ?_, g, ?_⟩
    · simp only [step, h1, ite_false]; rw [e]; simp
    · simp [pend] at hp ⊢; exact hp
  | etnIn P buf ha =>
    have hdl : ¬ (isDelim c ∧ lowerStr buf = scriptName) := by
      rintro ⟨hc, hb⟩
      exact hd (.inr ⟨P, buf, c, by simp, hb, hc⟩)
    by_cases h1 : isAlpha c = true
    · have hw : isWs c = false := by
        revert h1; simp only [isAlpha, isWs]; intro h1
        rcases Bool.or_eq_true _ _ |>.mp h1 with h | h <;>
          simp only [Bool.and_eq_true, decide_eq_true_eq] at h <;>
          · simp only [Bool.or_eq_false_iff, beq_eq_false_iff_ne]
            refine ⟨⟨⟨⟨?_, ?_⟩, ?_⟩, ?_⟩, ?_⟩ <;> rintro rfl <;> simp_all
      have h2 : c ≠ '/' := by rintro rfl; simp [isAlpha] at h1
      have h3 : c ≠ '>' := by rintro rfl; simp [isAlpha] at h1
      have hall : ∀ d ∈ buf ++ [c], isAlpha d = true := fun d hd => by
        rcases List.mem_append.mp hd with h | h
        · exact ha d h
        · simp at h; subst h; exact h1
      refine ⟨.rawEndTagName .script (buf ++ [c]), [], ?_, by simpa using Good.etnIn P (buf ++ [c]) hall,
        by simp [pend]⟩
      simp [step, stepRawEndTagName, hw, h2, h3, h1]
    · have e0 : stepRawEndTagName tr .script scriptName buf c =
          let r := stepRaw .script scriptName c
          (r.1, [.char '<', .char '/'] ++ buf.map .char ++ r.2) := by
        have n1 : ¬ (isWs c = true ∧ appropriate scriptName buf = true) := fun ⟨a, b⟩ =>
          hdl ⟨.inl a, appropriate_script.mp b⟩
        have n2 : ¬ (c = '/' ∧ appropriate scriptName buf = true) := fun ⟨a, b⟩ =>
          hdl ⟨.inr (.inl a), appropriate_script.mp b⟩
        have n3 : ¬ (c = '>' ∧ appropriate scriptName buf = true) := fun ⟨a, b⟩ =>
          hdl ⟨.inr (.inr a), appropriate_script.mp b⟩
        simp only [stepRawEndTagName, if_neg n1, if_neg n2, if_neg n3, if_neg h1]
      obtain ⟨st', out, e, g, hp⟩ := stepRaw_script scriptName (P ++ ['<', '/'] ++ buf) hn
      refine ⟨st', ['<', '/'] ++ buf ++ out, ?_, g, ?_⟩
      · simp only [step]; rw [e0]; simp only; rw [e]; simp
      · simp [pend] at hp ⊢; exact hp
  | etnOut P buf ha hnp =>
    have hdl : ¬ (isDelim c ∧ lowerStr buf = scriptName) := by
      rintro ⟨_, hb⟩; exact hnp (hb ▸ List.prefix_refl _)
    by_cases h1 : isAlpha c = true
    · have hw : isWs c = false := by
        revert h1; simp only [isAlpha, isWs]; intro h1
        rcases Bool.or_eq_true _ _ |>.mp h1 with h | h <;>
          simp only [Bool.and_eq_true, decide_eq_true_eq] at h <;>
          · simp only [Bool.or_eq_false_iff, beq_eq_false_iff_ne]
            refine ⟨⟨⟨⟨?_, ?_⟩, ?_⟩, ?_⟩, ?_⟩ <;> rintro rfl <;> simp_all
      have h2 : c ≠ '/' := by rintro rfl; simp [isAlpha] at h1
      have h3 : c ≠ '>' := by rintro rfl; simp [isAlpha] at h1
      have hall : ∀ d ∈ buf ++ [c], isAlpha d = true := fun d hd => by
        rcases List.mem_append.mp hd with h | h
        · exact ha d h
        · simp at h; subst h; exact h1
      refine ⟨.rawEndTagName .script (buf ++ [c]), [], ?_, Good.etnOut _ (buf ++ [c]) hall ?_,
        by simp [pend]⟩
      · simp [step, stepRawEndTagName, hw, h2, h3, h1]
      · intro hp; apply hnp
        rw [lowerStr_append] at hp
        exact (List.prefix_append _ _).trans hp
    · have e0 : stepRawEndTagName tr .script scriptName buf c =
          let r := stepRaw .script scriptName c
          (r.1, [.char '<', .char '/'] ++ buf.map .char ++ r.2) := by
        have n1 : ¬ (isWs c = true ∧ appropriate scriptName buf = true) := fun ⟨a, b⟩ =>
          hdl ⟨.inl a, appropriate_script.mp b⟩
        have n2 : ¬ (c = '/' ∧ appropriate scriptName buf = true) := fun ⟨a, b⟩ =>
          hdl ⟨.inr (.inl a), appropriate_script.mp b⟩
        have n3 : ¬ (c = '>' ∧ appropriate scriptName buf = true) := fun ⟨a, b⟩ =>
          hdl ⟨.inr (.inr a), appropriate_script.mp b⟩
        simp only [stepRawEndTagName, if_neg n1, if_neg n2, if_neg n3, if_neg h1]
      obtain ⟨st', out, e, g, hp⟩ := stepRaw_script scriptName P hn
      refine ⟨st', ['<', '/'] ++ buf ++ out, ?_, g, ?_⟩
      · simp only [step]; rw [e0]; simp only; rw [e]; simp
      · simp [pend] at hp ⊢; exact hp
  | esc P =>
    by_cases h1 : c = '-'
    · subst h1
      exact ⟨.scriptEscapeStartDash, ['-'], by simp [step], by simpa using Good.escDash P, by simp [pend]⟩
    obtain ⟨st', out, e, g, hp⟩ := stepRaw_script scriptName (P ++ ['<', '!']) hn
    exact ⟨st', out, by simp only [step, h1, ite_false]; exact e, g, by simpa [pend] using hp⟩
  | escDash P =>
    by_cases h1 : c = '-'
    · subst h1; exact absurd (.inl ⟨P, by simp⟩) hd
    obtain ⟨st', out, e, g, hp⟩ := stepRaw_script scriptName (P ++ ['<', '!', '-']) hn
    exact ⟨st', out, by simp only [step, h1, ite_false]; exact e, g, by simpa [pend] using hp⟩

/-- Where a run ends: next state `Good`, the characters emitted, and the balance
of held-back characters. -/
def RunOK (r : R) (ls : List Char) (st : St) (s Q : List Char) : Prop :=
  ∃ st' out, r = (⟨st', ls⟩, out.map .char) ∧ Good st' Q ∧ pend st ++ s = out ++ pend st'

/-- **Static script text.** -/
theorem good_run (tr : Tree) :
    ∀ (s : List Char) {st : St} {P : List Char}, Good st P → '\x00' ∉ s →
      (∀ Q, Q <+: s → Q ≠ [] → ¬ DangerEnd (P ++ Q)) →
      RunOK (run tr ⟨st, scriptName⟩ s) scriptName st s (P ++ s)
  | [], st, P, h, _, _ => ⟨st, [], rfl, by simpa using h, by simp⟩
  | c :: s, st, P, h, hn, hd => by
    have hc : c ≠ '\x00' := fun e => hn (e ▸ List.mem_cons_self ..)
    obtain ⟨s1, o1, e1, g1, p1⟩ := good_step tr h hc (hd [c] (by simp) (by simp))
    obtain ⟨s2, o2, e2, g2, p2⟩ := good_run tr s g1 (fun m => hn (List.mem_cons_of_mem _ m))
      (fun Q hq hne => by
        have := hd (c :: Q) (List.cons_prefix_cons.mpr ⟨rfl, hq⟩) (by simp)
        simpa using this)
    refine ⟨s2, o1 ++ o2, ?_, by simpa using g2, ?_⟩
    · simp [run, e1, e2]
    · calc pend st ++ c :: s = (pend st ++ [c]) ++ s := by simp
        _ = o1 ++ (pend s1 ++ s) := by rw [p1]; simp
        _ = (o1 ++ o2) ++ pend s2 := by rw [p2]; simp

theorem noScriptDanger_prefix {s : List Char} (h : NoDangerS s) :
    ∀ Q, Q <+: s → Q ≠ [] → ¬ DangerEnd ([] ++ Q) := by
  intro Q hq _
  obtain ⟨t, rfl⟩ := hq
  have := h Q.length
  simpa using this

/-- **Tessera's end tag.** From any script state, `</` + a name that lowercases to
`script` + `>` emits the held-back characters and the end tag. -/
theorem good_close (tr : Tree) {st : St} {P : List Char} (h : Good st P) {E : List Char}
    (hE : lowerStr E = scriptName) (hEa : ∀ c ∈ E, isAlpha c = true) :
    run tr ⟨st, scriptName⟩ (['<', '/'] ++ E ++ ['>']) =
      (⟨.data, scriptName⟩, (pend st).map .char ++ [.endTag scriptName]) := by
  -- `<` from any script state reaches the less-than sign state.
  have e1 : step tr ⟨st, scriptName⟩ '<' =
      (⟨.rawLt .script, scriptName⟩, (pend st).map .char) := by
    cases h with
    | raw => simp [step, stepRaw, pend]
    | lt => simp [step, stepRaw, pend, isAlpha]
    | eto => simp [step, stepRaw, pend, isAlpha]
    | etnIn _ buf _ | etnOut _ buf _ _ =>
      simp [step, stepRawEndTagName, stepRaw, pend, isAlpha, isWs]
    | esc => simp [step, stepRaw, pend]
    | escDash => simp [step, stepRaw, pend]
  obtain ⟨c, r, rfl⟩ : ∃ c r, E = c :: r := by
    cases E with
    | nil => simp [lowerStr] at hE
    | cons c r => exact ⟨c, r, rfl⟩
  have hc : isAlpha c = true := hEa c (by simp)
  -- The name, letter by letter, in the end tag name state.
  have hname : ∀ (q b : List Char), (∀ d ∈ q, isAlpha d = true) →
      run tr ⟨.rawEndTagName .script b, scriptName⟩ q =
        (⟨.rawEndTagName .script (b ++ q), scriptName⟩, []) := by
    intro q
    induction q with
    | nil => intro b _; simp [run]
    | cons d q ih =>
      intro b hq
      have hd : isAlpha d = true := hq d (by simp)
      have hw : isWs d = false := by
        revert hd; simp only [isAlpha, isWs]; intro hd
        rcases Bool.or_eq_true _ _ |>.mp hd with h | h <;>
          simp only [Bool.and_eq_true, decide_eq_true_eq] at h <;>
          · simp only [Bool.or_eq_false_iff, beq_eq_false_iff_ne]
            refine ⟨⟨⟨⟨?_, ?_⟩, ?_⟩, ?_⟩, ?_⟩ <;> rintro rfl <;> simp_all
      have h2 : d ≠ '/' := by rintro rfl; simp [isAlpha] at hd
      have h3 : d ≠ '>' := by rintro rfl; simp [isAlpha] at hd
      simp only [run]
      rw [show step tr ⟨.rawEndTagName .script b, scriptName⟩ d =
          (⟨.rawEndTagName .script (b ++ [d]), scriptName⟩, []) by
        simp [step, stepRawEndTagName, hw, h2, h3, hd]]
      simp only
      rw [ih (b ++ [d]) (fun x hx => hq x (by simp [hx]))]
      simp
  simp only [List.cons_append, List.nil_append, run]
  rw [e1]
  simp only
  rw [show step tr ⟨.rawLt .script, scriptName⟩ '/' = (⟨.rawEndTagOpen .script, scriptName⟩, [])
    by simp [step]]
  simp only
  rw [show step tr ⟨.rawEndTagOpen .script, scriptName⟩ c =
      (⟨.rawEndTagName .script [c], scriptName⟩, []) by simp [step, hc]]
  simp only
  rw [run_append, hname r [c] (fun d hd => hEa d (by simp [hd]))]
  have hap : appropriate scriptName (c :: r) = true := appropriate_script.mpr hE
  simp [run, step, stepRawEndTagName, isWs, hap, emitTag, endTagOf, hE]

/-! ### JSON values -/

/-- After a value: the script data state, or an end tag name holding value letters. -/
def Calm (st : St) : Prop :=
  st = .raw .script ∨
    ∃ buf, st = .rawEndTagName .script buf ∧ (∀ c ∈ buf, isAlpha c = true) ∧
      ¬ (lowerStr buf <+: scriptName)

theorem calm_good {st : St} (h : Calm st) (P : List Char) : Good st P := by
  rcases h with rfl | ⟨buf, rfl, ha, hn⟩
  · exact .raw P
  · exact .etnOut P buf ha hn

theorem alpha_not_ws {c : Char} (h : isAlpha c = true) : isWs c = false ∧ c ≠ '/' ∧ c ≠ '>' := by
  refine ⟨?_, by rintro rfl; simp [isAlpha] at h, by rintro rfl; simp [isAlpha] at h⟩
  revert h; simp only [isAlpha, isWs]; intro h
  rcases Bool.or_eq_true _ _ |>.mp h with h | h <;>
    simp only [Bool.and_eq_true, decide_eq_true_eq] at h <;>
    · simp only [Bool.or_eq_false_iff, beq_eq_false_iff_ne]
      refine ⟨⟨⟨⟨?_, ?_⟩, ?_⟩, ?_⟩, ?_⟩ <;> rintro rfl <;> simp_all

/-- A character other than `<` and NUL keeps a run calm. -/
theorem calm_step (tr : Tree) {st : St} (h : Calm st) {c : Char} (hl : c ≠ '<') (hn : c ≠ '\x00') :
    ∃ st' out, step tr ⟨st, scriptName⟩ c = (⟨st', scriptName⟩, out.map .char) ∧ Calm st' ∧
      pend st ++ [c] = out ++ pend st' := by
  rcases h with rfl | ⟨buf, rfl, ha, hnp⟩
  · exact ⟨.raw .script, [c], by simp [step, stepRaw, hl, hn], .inl rfl, by simp [pend]⟩
  · have hnap : appropriate scriptName buf = false := by
      cases e : appropriate scriptName buf
      · rfl
      · exact absurd ((appropriate_script.mp e) ▸ List.prefix_refl _) hnp
    by_cases h1 : isAlpha c = true
    · obtain ⟨hw, h2, h3⟩ := alpha_not_ws h1
      refine ⟨.rawEndTagName .script (buf ++ [c]), [], ?_, .inr ⟨buf ++ [c], rfl, ?_, ?_⟩,
        by simp [pend]⟩
      · simp [step, stepRawEndTagName, hw, h2, h3, h1]
      · intro d hd
        rcases List.mem_append.mp hd with h | h
        · exact ha d h
        · simp at h; subst h; exact h1
      · intro hp; apply hnp
        rw [lowerStr_append] at hp
        exact (List.prefix_append _ _).trans hp
    · refine ⟨.raw .script, ['<', '/'] ++ buf ++ [c], ?_, .inl rfl, by simp [pend]⟩
      simp [step, stepRawEndTagName, hnap, h1, stepRaw, hl, hn]

theorem calm_run (tr : Tree) :
    ∀ (w : List Char) {st : St}, Calm st → '<' ∉ w → '\x00' ∉ w →
      ∃ st' out, run tr ⟨st, scriptName⟩ w = (⟨st', scriptName⟩, out.map .char) ∧ Calm st' ∧
        pend st ++ w = out ++ pend st'
  | [], st, h, _, _ => ⟨st, [], rfl, h, by simp⟩
  | c :: w, st, h, hl, hn => by
    obtain ⟨s1, o1, e1, c1, p1⟩ := calm_step tr h (fun e => hl (e ▸ List.mem_cons_self ..))
      (fun e => hn (e ▸ List.mem_cons_self ..))
    obtain ⟨s2, o2, e2, c2, p2⟩ := calm_run tr w c1 (fun m => hl (List.mem_cons_of_mem _ m))
      (fun m => hn (List.mem_cons_of_mem _ m))
    refine ⟨s2, o1 ++ o2, by simp [run, e1, e2], c2, ?_⟩
    calc pend st ++ c :: w = (pend st ++ [c]) ++ w := by simp
      _ = o1 ++ (pend s1 ++ w) := by rw [p1]; simp
      _ = _ := by rw [p2]; simp

/-- The prefixes of `script`. -/
theorem script_prefix {L : List Char} (h : L <+: scriptName) :
    L = [] ∨ L = ['s'] ∨ L = ['s', 'c'] ∨ L = ['s', 'c', 'r'] ∨ L = ['s', 'c', 'r', 'i'] ∨
      L = ['s', 'c', 'r', 'i', 'p'] ∨ L = scriptName := by
  obtain ⟨t, h⟩ := h
  rcases L with _ | ⟨a, _ | ⟨b, _ | ⟨c, _ | ⟨d, _ | ⟨e, _ | ⟨f, _ | ⟨g, r⟩⟩⟩⟩⟩⟩⟩ <;>
    simp_all [scriptName]

/-- An end tag name buffer that a JSON keyword letter extends stays out of reach
of `script` after at most one more letter. -/
theorem keyword_letter {buf : List Char} {c : Char} (hc : c = 't' ∨ c = 'f' ∨ c = 'n')
    (hp : lowerStr (buf ++ [c]) <+: scriptName) : c = 't' ∧ lowerStr (buf ++ [c]) = scriptName := by
  have hl : lower c = c := by rcases hc with rfl | rfl | rfl <;> rfl
  rw [lowerStr_append] at hp
  have hcl : lowerStr [c] = [c] := by simp [lowerStr, hl]
  rw [hcl] at hp
  rw [lowerStr_append, hcl]
  rcases script_prefix hp with h | h | h | h | h | h | h <;>
    have hlast := congrArg List.getLast? h <;> simp at hlast
  all_goals first
    | exact ⟨hlast, h⟩
    | (subst hlast; simp at hc)

theorem json_first_char {c : Char}
    (hc : c = '"' ∨ c = '-' ∨ c.isDigit = true ∨ c = 't' ∨ c = 'f' ∨ c = 'n' ∨ c = '[' ∨ c = '{') :
    c ≠ '/' ∧ c ≠ '!' ∧ c ≠ '<' ∧ c ≠ '\x00' ∧ ¬ isDelim c ∧
      (isAlpha c = true → c = 't' ∨ c = 'f' ∨ c = 'n') := by
  rcases hc with rfl | rfl | h1 | rfl | rfl | rfl | rfl | rfl
  all_goals try (simp [isDelim, isWs, isAlpha]; done)
  have n : ∀ e : Char, e.isDigit = false → c ≠ e := fun e he hce => by
    subst hce; rw [h1] at he; cases he
  refine ⟨n _ (by decide), n _ (by decide), n _ (by decide), n _ (by decide), ?_, ?_⟩
  · rintro (hw | rfl | rfl)
    · simp only [isWs, Bool.or_eq_true, beq_iff_eq] at hw
      rcases hw with ((((rfl | rfl) | rfl) | rfl) | rfl) <;> simp at h1
    all_goals simp at h1
  · intro ha
    simp [isAlpha, Char.isDigit, Char.le_def, UInt32.le_iff_toNat_le] at h1 ha
    try omega

/-- **A JSON hole value**, read after static script text that does not end in `<!-`. -/
theorem good_value (tr : Tree) {st : St} {P : List Char} (h : Good st P)
    (hd : ∀ P', P ≠ P' ++ ['<', '!', '-']) {v : List Char} (hv : JsonFirst v) :
    ∃ st' out, run tr ⟨st, scriptName⟩ v = (⟨st', scriptName⟩, out.map .char) ∧ Calm st' ∧
      pend st ++ v = out ++ pend st' := by
  obtain ⟨hl, hn, c, w, rfl, hc, hminus, ht⟩ := hv
  obtain ⟨c1, c2, c3, c4, c5, c6⟩ := json_first_char hc
  have hlw : '<' ∉ w := fun m => hl (List.mem_cons_of_mem _ m)
  have hnw : '\x00' ∉ w := fun m => hn (List.mem_cons_of_mem _ m)
  -- After the first character the run is calm, or (for `-`/`t` after `<!`/`</scrip`)
  -- one more character makes it calm; `finish` reads the rest.
  have finish : ∀ {s1 : St} {o1 : List Char} (w' : List Char), w' <:+ w → Calm s1 →
      run tr ⟨st, scriptName⟩ (c :: w) =
        ((run tr ⟨s1, scriptName⟩ w').1, o1.map .char ++ (run tr ⟨s1, scriptName⟩ w').2) →
      pend st ++ c :: w = o1 ++ pend s1 ++ w' →
      ∃ st' out, run tr ⟨st, scriptName⟩ (c :: w) = (⟨st', scriptName⟩, out.map .char) ∧ Calm st' ∧
        pend st ++ c :: w = out ++ pend st' := by
    intro s1 o1 w' hsuf hc1 e p
    have hl' : '<' ∉ w' := fun m => hlw (hsuf.subset m)
    have hn' : '\x00' ∉ w' := fun m => hnw (hsuf.subset m)
    obtain ⟨s2, o2, e2, c2', p2⟩ := calm_run tr w' hc1 hl' hn'
    refine ⟨s2, o1 ++ o2, by rw [e, e2]; simp, c2', ?_⟩
    rw [p, List.append_assoc, p2]; simp
  have hsuf : w <:+ w := List.suffix_refl w
  cases h with
  | raw P =>
    exact calm_run tr (c :: w) (.inl rfl) hl hn
  | lt P =>
    exact finish (s1 := .raw .script) (o1 := ['<', c]) w hsuf (.inl rfl)
      (by simp [run, step, c1, c2, stepRaw, c3, c4]) (by simp [pend])
  | eto P =>
    by_cases ha : isAlpha c = true
    · have hk := c6 ha
      refine finish (s1 := .rawEndTagName .script [c]) (o1 := []) w hsuf
        (.inr ⟨[c], rfl, by simp [ha], ?_⟩) (by simp [run, step, ha]) (by simp [pend])
      rcases hk with rfl | rfl | rfl <;> simp [lowerStr, lower, scriptName]
    · exact finish (s1 := .raw .script) (o1 := ['<', '/', c]) w hsuf (.inl rfl)
        (by simp [run, step, ha, stepRaw, c3, c4]) (by simp [pend])
  | etnOut P buf hb hnp =>
    exact calm_run tr (c :: w) (.inr ⟨buf, rfl, hb, hnp⟩) hl hn
  | etnIn P buf hb =>
    have hall : ∀ x, isAlpha x = true → ∀ d ∈ buf ++ [x], isAlpha d = true := fun x hx d hd => by
      rcases List.mem_append.mp hd with h | h
      · exact hb d h
      · simp at h; subst h; exact hx
    by_cases ha : isAlpha c = true
    · obtain ⟨hw, h2, h3⟩ := alpha_not_ws ha
      have e1 : step tr ⟨.rawEndTagName .script buf, scriptName⟩ c =
          (⟨.rawEndTagName .script (buf ++ [c]), scriptName⟩, []) := by
        simp [step, stepRawEndTagName, hw, h2, h3, ha]
      by_cases hp : lowerStr (buf ++ [c]) <+: scriptName
      · obtain ⟨rfl, hs⟩ := keyword_letter (c6 ha) hp
        obtain ⟨w', rfl⟩ := ht rfl
        have har : isAlpha 'r' = true := by decide
        have e2 : step tr ⟨.rawEndTagName .script (buf ++ ['t']), scriptName⟩ 'r' =
            (⟨.rawEndTagName .script (buf ++ ['t'] ++ ['r']), scriptName⟩, []) := by
          simp [step, stepRawEndTagName, isWs, isAlpha]
        have hall2 : ∀ d ∈ buf ++ ['t'] ++ ['r'], isAlpha d = true := by
          intro d hd
          rcases List.mem_append.mp hd with h | h
          · exact hall _ ha d h
          · simp at h; subst h; exact har
        refine finish (s1 := .rawEndTagName .script (buf ++ ['t'] ++ ['r'])) (o1 := []) w'
          (List.suffix_cons _ _) (.inr ⟨_, rfl, hall2, ?_⟩) ?_ (by simp [pend])
        · intro hq
          rw [lowerStr_append, hs] at hq
          have := hq.length_le
          simp [lowerStr, scriptName] at this
        · simp [run, e1, e2]
      · exact finish (s1 := .rawEndTagName .script (buf ++ [c])) (o1 := []) w hsuf
          (.inr ⟨_, rfl, hall _ ha, hp⟩) (by simp [run, e1]) (by simp [pend])
    · have n1 : ¬ (isWs c = true ∧ appropriate scriptName buf = true) := fun ⟨a, _⟩ => c5 (.inl a)
      have n2 : ¬ (c = '/' ∧ appropriate scriptName buf = true) := fun ⟨a, _⟩ => c5 (.inr (.inl a))
      have n3 : ¬ (c = '>' ∧ appropriate scriptName buf = true) := fun ⟨a, _⟩ => c5 (.inr (.inr a))
      refine finish (s1 := .raw .script) (o1 := ['<', '/'] ++ buf ++ [c]) w hsuf (.inl rfl) ?_
        (by simp [pend])
      simp only [run]
      rw [show step tr ⟨.rawEndTagName .script buf, scriptName⟩ c =
          (⟨.raw .script, scriptName⟩, (['<', '/'] ++ buf ++ [c]).map .char) by
        simp only [step, stepRawEndTagName, ite_cond_eq_false _ _ (eq_false n1), ite_cond_eq_false _ _ (eq_false n2),
          ite_cond_eq_false _ _ (eq_false n3), ite_cond_eq_false _ _ (eq_false ha)]
        simp [stepRaw, c3, c4]]
  | esc P =>
    by_cases hm : c = '-'
    · subst hm
      obtain ⟨d, w', rfl, hd1⟩ := hminus rfl
      have hdl : d ≠ '<' := fun e => hlw (e ▸ List.mem_cons_self ..)
      have hdn : d ≠ '\x00' := fun e => hnw (e ▸ List.mem_cons_self ..)
      have hdm : d ≠ '-' := by rintro rfl; simp at hd1
      exact finish (s1 := .raw .script) (o1 := ['-', d]) w' (List.suffix_cons _ _) (.inl rfl)
        (by simp [run, step, stepRaw, hdl, hdn, hdm]) (by simp [pend])
    · exact finish (s1 := .raw .script) (o1 := [c]) w hsuf (.inl rfl)
        (by simp [run, step, hm, stepRaw, c3, c4]) (by simp [pend])
  | escDash P => exact absurd rfl (hd P)

end TesseraT1
