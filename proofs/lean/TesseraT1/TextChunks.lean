/-
A TEXT hole's value as a sequence of chunks (`Escapers.text`; docs/security-architecture.md §5.2):
characters other than `<` and `&`, the five entities `Escapers` writes, and the
empty comment `<!---->`. In the WHATWG data state a `>` is an ordinary character,
so only `<` and `&` constrain the value there.
-/
import TesseraT1.Escapers

namespace TesseraT1

/-- The entities `Escapers` writes. -/
def entities : List (List Char) :=
  ["&amp;".toList, "&lt;".toList, "&gt;".toList, "&quot;".toList, "&#39;".toList]

inductive TextChunks : List Char → Prop where
  | nil : TextChunks []
  | plain (c : Char) (r : List Char) : c ≠ '<' → c ≠ '&' → TextChunks r → TextChunks (c :: r)
  | entity (e r : List Char) : e ∈ entities → TextChunks r → TextChunks (e ++ r)
  | comment (r : List Char) : TextChunks r → TextChunks (braceBreak ++ r)

/-- Every `<` starts `<!---->` (the `<` half of `htmlCommentsOnly`). -/
def LtOnlyComments (t : List Char) : Prop := ∀ k, t[k]? = some '<' → htmlCommentAt t k

theorem htmlCommentsOnly_lt {t : List Char} (h : htmlCommentsOnly t) : LtOnlyComments t :=
  fun k hk => (h k).1 hk

theorem ltOnly_drop {t : List Char} (n : Nat) (h : LtOnlyComments t) : LtOnlyComments (t.drop n) := by
  intro k hk
  have := h (n + k) (by simpa [List.getElem?_drop] using hk)
  simpa [htmlCommentAt, List.drop_drop] using this

theorem entOnly_drop {t : List Char} (n : Nat) (h : htmlEntitiesOnly t) : htmlEntitiesOnly (t.drop n) := by
  intro k hk
  have := h (n + k) (by simpa [List.getElem?_drop] using hk)
  simpa [htmlEntityAt, List.drop_drop] using this

/-- If `t` starts with the `m`-char prefix `p`, it is `p` followed by the rest. -/
theorem split_prefix {t p : List Char} (m : Nat) (hp : t.take m = p) : t = p ++ t.drop m := by
  rw [← hp, List.take_append_drop]

/-- `t` starts with `p` (of length `m ≥ 1`), so dropping `m` chars shortens it. -/
theorem drop_lt {t p : List Char} {m : Nat} (hp : t.take m = p) (hpl : p.length = m) (hm : 1 ≤ m) :
    (t.drop m).length + 1 ≤ t.length := by
  have := congrArg List.length hp
  simp [List.length_take, List.length_drop] at this ⊢; omega

theorem chunks_aux : ∀ (n : Nat) (t : List Char), t.length ≤ n →
    LtOnlyComments t → htmlEntitiesOnly t → TextChunks t := by
  intro n
  induction n with
  | zero =>
    intro t hn _ _
    have : t = [] := List.eq_nil_of_length_eq_zero (by omega)
    subst this; exact .nil
  | succ n ih =>
    intro t hn hl he
    match t with
    | [] => exact .nil
    | c :: r =>
      by_cases hc : c = '<'
      · have hca : (c :: r).take 7 = braceBreak := by
          simpa [htmlCommentAt] using hl 0 (by simp [hc])
        have hlen := drop_lt hca rfl (by decide)
        rw [split_prefix 7 hca]
        exact .comment _ (ih _ (by omega) (ltOnly_drop 7 hl) (entOnly_drop 7 he))
      · by_cases ha : c = '&'
        · have hea : htmlEntityAt (c :: r) 0 := he 0 (by simp [ha])
          simp only [htmlEntityAt, List.drop_zero] at hea
          rcases hea with h | h | h | h | h <;>
          · have hlen := drop_lt h rfl (by decide)
            rw [split_prefix _ h]
            exact .entity _ _ (by simp [entities])
              (ih _ (by omega) (ltOnly_drop _ hl) (entOnly_drop _ he))
        · have hd := ih r (by simp at hn; omega)
            (by simpa using ltOnly_drop 1 hl) (by simpa using entOnly_drop 1 he)
          exact .plain c r hc ha hd

theorem chunks_of {t : List Char} (hl : LtOnlyComments t) (he : htmlEntitiesOnly t) : TextChunks t :=
  chunks_aux t.length t (Nat.le_refl _) hl he

/-- The decomposition T1's slice-1 proof uses. -/
theorem htmlTextSafe_chunks {t : List Char} (h : htmlTextSafe t) : TextChunks t :=
  chunks_of (htmlCommentsOnly_lt h.2.1) h.2.2.1

end TesseraT1
