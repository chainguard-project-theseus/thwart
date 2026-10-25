/-
Bridge from the compiler's `<script>` content checks (Subset.lean) to the script-data
tokenizer lemmas' side conditions (Script.lean).
-/
import TesseraT1.Subset
import TesseraT1.Script
import TesseraT1.TagBridge

namespace TesseraT1
open Whatwg

theorem noDangerS_of_noScriptDanger {s : List Char} (h : NoScriptDanger s) :
    NoDangerS s ∧ '\x00' ∉ s := by
  obtain ⟨hnul, hsafe⟩ := h
  refine ⟨fun i hd => hsafe i ?_, hnul⟩
  rcases hd with ⟨P, hP⟩ | ⟨P, L, d, hP, hL, hd⟩
  · exact Or.inl ⟨P, hP⟩
  · exact Or.inr ⟨P, L, d, hP, by rw [← lowerStr_eq_asciiLower]; exact hL,
by rcases hd with hd|hd|hd <;> simp [scriptEndDelim, ← isWs_eq, hd]⟩

theorem notEnd_of_noScriptLtBeforeHole {s : List Char} (h : NoScriptLtBeforeHole s) :
    ∀ P', s ≠ P' ++ ['<', '!', '-'] := by
  intro P' he
  apply h
  subst he
  refine ⟨?_, ?_, ?_⟩
  · simp [List.getLast?_append]
  · simp
  · simp

theorem json_of_safe {v : List Char} (h : jsonScriptSafe v) : '<' ∉ v ∧ '\x00' ∉ v := by
  refine ⟨fun hv => (h '<' hv).1 rfl, fun hv => ?_⟩
  have := (h '\x00' hv).2.2.2.2.2.2.2.2.2
  simp at this

end TesseraT1
