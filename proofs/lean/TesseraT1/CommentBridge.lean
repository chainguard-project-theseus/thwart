/-
The bridge from the compiler's comment checks to the tokenizer lemma's side
condition: `CommentTessera b` (Subset.lean: what S6a and Tessera's comment
delimiting let through) implies `CommentBodyOK b` (Comment.lean: what makes the
browser read `<!--` + b + `-->` as one comment token with data b).
-/
import TesseraT1.Subset
import TesseraT1.Comment

namespace TesseraT1

theorem split_at_gt {b : List Char} {i : Nat} (h : b[i]? = some '>') :
    b = b.take i ++ '>' :: b.drop (i + 1) := by
  have hi : i < b.length := by
    rcases Nat.lt_or_ge i b.length with hi | hi
    · exact hi
    · simp [List.getElem?_eq_none hi] at h
  have hget : b[i] = '>' := by
    rw [List.getElem?_eq_getElem hi] at h; exact Option.some.inj h
  conv => lhs; rw [← List.take_append_drop i b]
  rw [List.drop_eq_getElem_cons hi, hget]

theorem getElem?_append_len (A B : List Char) (k : Nat) : (A ++ B)[A.length + k]? = B[k]? := by
  simp [List.getElem?_append_right]

theorem commentBodyOK_of_tessera {b : List Char} (h : CommentTessera b) : CommentBodyOK b := by
  obtain ⟨h1, h2, h3, h4, h5⟩ := h
  refine ⟨h5, fun i hi he => ?_⟩
  have hb := split_at_gt hi
  generalize b.take i = L at hb he
  generalize b.drop (i + 1) = rest at hb
  subst hb
  rcases he with hL | hL | ⟨p, hL⟩ | ⟨p, hL⟩ <;> subst hL
  · exact h1 (by simp)
  · exact h2 (by simp)
  · have hf : "<!--".toList ++ (p ++ ['-', '-'] ++ '>' :: rest) ++ "-->".toList
        = (['<', '!', '-', '-'] ++ p) ++ ('-' :: '-' :: '>' :: (rest ++ ['-', '-', '>'])) := by
      simp
    rw [hf] at h4
    have hlen : (['<', '!', '-', '-'] ++ p).length = 4 + p.length := by simp; omega
    have := h4 ((['<', '!', '-', '-'] ++ p).length + 2) (by omega) (by simp; omega)
      (by rw [getElem?_append_len]; rfl)
    apply this
    constructor
    · show (['<', '!', '-', '-'] ++ p ++ _)[(['<', '!', '-', '-'] ++ p).length + 1]? = _
      rw [getElem?_append_len]; rfl
    · show (['<', '!', '-', '-'] ++ p ++ _)[(['<', '!', '-', '-'] ++ p).length + 0]? = _
      rw [getElem?_append_len]; rfl
  · have hf : "<!--".toList ++ (p ++ ['-', '-', '!'] ++ '>' :: rest) ++ "-->".toList
        = (['<', '!', '-', '-'] ++ p) ++ ('-' :: '-' :: '!' :: '>' :: (rest ++ ['-', '-', '>'])) := by
      simp
    rw [hf] at h3
    apply h3 (['<', '!', '-', '-'] ++ p).length (by simp)
    rw [List.drop_left]; rfl

end TesseraT1
