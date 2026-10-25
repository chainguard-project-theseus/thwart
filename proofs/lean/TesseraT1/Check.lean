import TesseraT1.Subset
namespace TesseraT1

/-- `ltIsText` on the char after a `<`, as a Bool. -/
def ltOkB : Option Char → Bool
  | none => true
  | some c => !isAsciiAlpha c && c != '!' && c != '/' && c != '?'

/-- Decidable form of `StaticText`. -/
def staticTextB : List Char → Bool
  | [] => true
  | c :: r => (c != '<' || ltOkB r.head?) && staticTextB r

theorem ltIsText_zero (c : Char) (r : List Char) :
    ltIsText (c :: r) 0 ↔ ltOkB r.head? = true := by
  cases r with
  | nil => simp [ltIsText, ltOkB]
  | cons d r => simp [ltIsText, ltOkB, and_assoc]

theorem ltIsText_succ (c : Char) (r : List Char) (i : Nat) :
    ltIsText (c :: r) (i + 1) ↔ ltIsText r i := by
  simp [ltIsText]

theorem staticText_cons (c : Char) (r : List Char) :
    StaticText (c :: r) ↔ (c = '<' → ltOkB r.head? = true) ∧ StaticText r := by
  constructor
  · intro h
    refine ⟨fun hc => ?_, fun i hi => ?_⟩
    · exact (ltIsText_zero c r).mp (h 0 (by simp [hc]))
    · exact (ltIsText_succ c r i).mp (h (i + 1) (by simpa using hi))
  · rintro ⟨h0, hr⟩ i hi
    cases i with
    | zero => exact (ltIsText_zero c r).mpr (h0 (by simpa using hi))
    | succ i => exact (ltIsText_succ c r i).mpr (hr i (by simpa using hi))

theorem staticTextB_iff (s : List Char) : staticTextB s = true ↔ StaticText s := by
  induction s with
  | nil => simp [staticTextB, StaticText]
  | cons c r ih =>
    rw [staticText_cons, ← ih]
    simp only [staticTextB, Bool.and_eq_true, Bool.or_eq_true, bne_iff_ne, ne_eq]
    constructor
    · rintro ⟨h1, h2⟩
      exact ⟨fun hc => by rcases h1 with h1 | h1 <;> simp_all, h2⟩
    · rintro ⟨h1, h2⟩
      refine ⟨?_, h2⟩
      by_cases hc : c = '<'
      · exact Or.inr (h1 hc)
      · exact Or.inl hc

instance (s : List Char) : Decidable (StaticText s) :=
  decidable_of_iff _ (staticTextB_iff s)

example : StaticText "a < b".toList := by decide
example : ¬ StaticText "a <b".toList := by decide
example : ¬ StaticText "a <!x".toList := by decide

end TesseraT1
