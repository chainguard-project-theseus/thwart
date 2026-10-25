/-
Rule contracts (the OpenJML proofs kept on a fork, github.com/tcnghia/harder, branch proofs),
stated over the static pieces Lean's `Template` is built from, and the bridge from
them to the slice predicates in Subset.lean.

Each `def` here transcribes a contract's plain-math statement; nothing in this file
assumes the compiler satisfies it. A theorem that needs a contract takes it as a
hypothesis.
-/
import TesseraT1.Subset

namespace TesseraT1

/--
`bogusAt(t, i)` (contract of `HtmlParser.bogusComment`, S6d): `t[i] = '<'` and
`t[i+1]` is `!` or `?`, or is `/` not followed by an ASCII letter or `>`
("`</`" at the very end counts as followed by something else).
-/
def bogusAt (t : List Char) (i : Nat) : Prop :=
  t[i]? = some '<' ∧
    (t[i + 1]? = some '!' ∨ t[i + 1]? = some '?' ∨
      (t[i + 1]? = some '/' ∧
        ¬ (∃ c, t[i + 2]? = some c ∧ (isAsciiAlpha c = true ∨ c = '>'))))

/-- `bogusComment(t) = -1`: `Builder.flushText` reports no error for the text run. -/
def NoBogus (t : List Char) : Prop := ∀ i, ¬ bogusAt t i

/--
The text-run half of the tokenizer contract (`MarkupTokenizer.kind` with S1): a `<`
followed by an ASCII letter starts a start tag, and `</` + ASCII letter or `</>` an
end tag, so neither stays in a text run of an accepted template.
Not yet stated as a JML contract.
-/
def NoTagOpenInText (t : List Char) : Prop :=
  ∀ i, t[i]? = some '<' →
    (∀ c, t[i + 1]? = some c → isAsciiAlpha c = false) ∧
    (t[i + 1]? = some '/' → ∀ c, t[i + 2]? = some c → isAsciiAlpha c = false ∧ c ≠ '>')

/-- The two contracts give slice 1's lexical condition. A static piece is a
substring of its text run; the inheritance argument for the piece's last two
characters (a `</` cut by a hole) is the bottom-up pair's, and markupBefore covers
the cut. -/
theorem staticText_of_contracts {t : List Char} (hb : NoBogus t) (hk : NoTagOpenInText t) :
    StaticText t := by
  intro i hi
  unfold ltIsText
  cases h1 : t[i + 1]? with
  | none => trivial
  | some c =>
    have ha := (hk i hi).1 c h1
    refine ⟨ha, ?_, ?_, ?_⟩
    · rintro rfl; exact hb i ⟨hi, Or.inl h1⟩
    · rintro rfl
      apply hb i ⟨hi, Or.inr (Or.inr ⟨h1, ?_⟩)⟩
      rintro ⟨d, hd, hd'⟩
      have := (hk i hi).2 h1 d hd
      rcases hd' with hd' | hd' <;> simp_all
    · rintro rfl; exact hb i ⟨hi, Or.inr (Or.inl h1)⟩

/-- `letters(t, f)`: every `t[k]`, `f ≤ k < |t|`, is an ASCII letter. -/
def letters (t : List Char) (f : Nat) : Prop := ∀ k, f ≤ k → ∀ c, t[k]? = some c → isAsciiAlpha c = true

/--
`endingAt(t, j)` (contract of `TextInlining.markupBefore`, S12, proved in OpenJML
on the fork): `t[j] = '<'` and the rest of `t` is
empty, `!` or `?`, `!-`, or `/` + ASCII letters.
-/
def endingAt (t : List Char) (j : Nat) : Prop :=
  j < t.length ∧ t[j]? = some '<' ∧
    (j + 1 = t.length ∨
      (j + 2 = t.length ∧ (t[j + 1]? = some '!' ∨ t[j + 1]? = some '?')) ∨
      (j + 3 = t.length ∧ t[j + 1]? = some '!' ∧ t[j + 2]? = some '-') ∨
      (t[j + 1]? = some '/' ∧ letters t (j + 2)))

/-- `htmlMarkupStart(t) = -1`: `markupBefore` accepts a TEXT or RCDATA hole after `t`. -/
def NoMarkupEnd (t : List Char) : Prop := ∀ j, ¬ endingAt t j

/-- The `markupBefore` contract gives slice 1's `NoLtBeforeHole`. -/
theorem noLtBeforeHole_of_contract {t : List Char} (h : NoMarkupEnd t) : NoLtBeforeHole t := by
  intro hl
  have hne : t ≠ [] := by rintro rfl; simp at hl
  apply h (t.length - 1)
  have hlen : 0 < t.length := List.length_pos_iff.mpr hne
  refine ⟨by omega, ?_, .inl (by omega)⟩
  rw [List.getLast?_eq_getElem?] at hl
  exact hl

end TesseraT1
