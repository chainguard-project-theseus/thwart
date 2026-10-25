/-
Bridge from the compiler's raw-text element checks (Subset.lean, slice 4) to the
RCDATA/RAWTEXT tokenizer lemmas' side conditions (RawText.lean), and the tag facts the
start-tag lemma needs for the four element names.
-/
import TesseraT1.Subset
import TesseraT1.RawText
import TesseraT1.TagBridge

namespace TesseraT1
open Whatwg

/-- The tokenizer state the tree builder picks for a lowercased raw-text element name. -/
def rawKind (n : List Char) : Raw := if n ∈ rcdataNames then .rcdata else .rawtext

theorem rawKind_notScript (n : List Char) : NotScript (rawKind n) := by
  unfold rawKind; split <;> simp [NotScript]

/-- `treeStub` switches to RCDATA for `title`/`textarea` and RAWTEXT for `style`/`iframe`
("in head" and "in body" rules, 13.2.6.4.4 and 13.2.6.4.7). -/
theorem treeStub_raw {n : List Char} {body : List ScriptPart} (h : RawKindOK n body) :
    treeStub n = .raw (rawKind n) := by
  rcases h with h | ⟨h, _⟩ | ⟨h, _⟩
  · simp only [rcdataNames, List.map_cons, List.map_nil, List.mem_cons, List.not_mem_nil,
      or_false] at h
    rcases h with rfl | rfl <;> decide
  · subst h; decide
  · subst h; decide

theorem noRawDangerS_of {n s : List Char} (h : NoRawDanger n s) :
    '\x00' ∉ s ∧ NoRawDangerS n s := by
  obtain ⟨hnul, hsafe⟩ := h
  refine ⟨hnul, fun i hd => hsafe i ?_⟩
  obtain ⟨P, L, d, hP, hL, hd⟩ := hd
  exact ⟨P, L, d, hP, by rw [← lowerStr_eq_asciiLower]; exact hL,
    by rcases hd with hd | hd | hd <;> simp [scriptEndDelim, ← isWs_eq, hd]⟩

theorem htmlSafe_lt_nul {x : List Char} (h : htmlSafe x) : '<' ∉ x ∧ '\x00' ∉ x :=
  ⟨fun m => (h.1 '<' m).1 rfl, fun m => (h.1 '\x00' m).2.2.2.2 rfl⟩

/-- The values a raw body's holes consume: each present value satisfies `htmlSafe`
(`Escapers.rcdata`); a missing value renders as nothing. -/
def RawValsOK : List ScriptPart → List (Option (List Char)) → Prop
  | [], _ => True
  | .static _ :: r, vs => RawValsOK r vs
  | .hole :: r, [] => RawValsOK r []
  | .hole :: r, v :: vs => (∀ x, v = some x → htmlSafe x) ∧ RawValsOK r vs

/-! ### The tag of a raw-text element -/

theorem alpha_of_asciiLower {n L : List Char} (h : asciiLower n = L) (hL : L.all isAlpha = true) :
    ∀ c ∈ n, isAlpha c = true := fun c hc =>
  asciiLowerChar_alpha (List.all_eq_true.mp hL _ (h ▸ List.mem_map_of_mem hc))

theorem rawName_alpha {n : List Char} {body : List ScriptPart} (h : RawKindOK (asciiLower n) body) :
    ∀ c ∈ n, isAlpha c = true := by
  rcases h with h | ⟨h, _⟩ | ⟨h, _⟩
  · simp only [rcdataNames, List.map_cons, List.map_nil, List.mem_cons, List.not_mem_nil,
      or_false] at h
    rcases h with h | h <;> exact alpha_of_asciiLower h (by decide)
  · exact alpha_of_asciiLower h (by decide)
  · exact alpha_of_asciiLower h (by decide)

theorem rawName_ne {n : List Char} {body : List ScriptPart} (h : RawKindOK (asciiLower n) body) :
    n ≠ [] := by
  rintro rfl
  rw [show asciiLower [] = [] from rfl] at h
  rcases h with h | ⟨h, _⟩ | ⟨h, _⟩
  · exact absurd h (by decide)
  · exact absurd h (by decide)
  · exact absurd h (by decide)

/-- The name facts `TagSyn.OK` needs: a leading ASCII letter and name characters only. -/
theorem rawName_facts {n : List Char} {body : List ScriptPart} (h : RawKindOK (asciiLower n) body) :
    (∃ c r, n = c :: r ∧ isAlpha c = true) ∧ ∀ c ∈ n, NameChar c := by
  have halpha := rawName_alpha h
  refine ⟨?_, fun c hc => nameChar_of_alpha (halpha c hc)⟩
  match n, rawName_ne h with
  | c :: r, _ => exact ⟨c, r, rfl, halpha c (by simp)⟩

/-- `StartTag.toSyn_name` for any tag with the shared attribute conditions (no name scope). -/
theorem StartTag.toSyn_name_of (t : StartTag) (vs : List (Option (List Char))) (h : TagAttrsOK t) :
    (t.toSyn vs).1.name = t.name := by
  rcases hs : t.source with _ | src <;> cases ha : t.items.all TagItem.isAttr <;>
    simp only [StartTag.toSyn, hs, ha]
  exact (h.2.2.2 src hs).2.2.1

/-- `StartTag.toSyn_ok` for any tag whose name has the `TagSyn.OK` name facts. -/
theorem StartTag.toSyn_ok_of (t : StartTag) (vs : List (Option (List Char)))
    (hfacts : (∃ c r, t.name = c :: r ∧ isAlpha c = true) ∧ ∀ c ∈ t.name, NameChar c)
    (hattrs : TagAttrsOK t) (hv : t.ValsOK vs) : (t.toSyn vs).1.OK := by
  obtain ⟨hitems, _, hslash, hsrc⟩ := hattrs
  obtain ⟨hnfacts, hncc⟩ := hfacts
  rcases hs : t.source with _ | src <;> cases ha : t.items.all TagItem.isAttr <;>
    simp only [StartTag.toSyn, hs, ha] <;>
    first
    | exact SrcTag.toSyn_ok (hsrc src hs).1
    | (have hreb : t.rebuilt = true := by simp [StartTag.rebuilt, hs, ha]
       have hvals : ItemValsOK t.items vs := by simpa [StartTag.ValsOK, hs, ha] using hv
       refine ⟨hnfacts, hncc, chain_of_all false _ (itemsSyn_ok t.items vs hitems hvals), ?_, ?_⟩
       · split <;> simp [AllWs, isWs]
       · intro hsc htr a hl
         dsimp only at hsc htr hl
         have hsp : t.spaceBeforeSlash = false := by
           cases hb : t.spaceBeforeSlash
           · rfl
           · simp [hsc, hb] at htr
         exact itemsSyn_noUnq t.items vs (hslash hreb hsc hsp) a (List.mem_of_getLast? hl))

end TesseraT1
