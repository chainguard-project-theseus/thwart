/-
Theorem T1, slice 5: slice 4 plus `<noscript>` elements, read as a browser with
scripting enabled reads them (docs/security-architecture.md §5.3): RAWTEXT up to
`</noscript` + delimiter. Tessera parses the content as markup; the profile keeps that
reading safe by allowing inside `noscript` only static text without `<`, TEXT holes,
start and end tags, and comments, none of which can form `</noscript` (`NoscriptBodyOK`).
With scripting disabled the content is markup, which is Tessera's reading; that side is
the slice-4 theorem on the body and is not restated here.
-/
import TesseraT1.T1Slice4

namespace TesseraT1

open Whatwg

abbrev noscriptName : List Char := ['n', 'o', 's', 'c', 'r', 'i', 'p', 't']

theorem noscript_toList : "noscript".toList = noscriptName := rfl

/-! ### Characters of a rendered tag -/

theorem nameChar_ne {c : Char} (h : isNameChar c = true) : c ≠ '<' ∧ c ≠ '\x00' := by
  refine ⟨?_, ?_⟩ <;> rintro rfl <;> revert h <;> decide

theorem validElementName_chars {n : List Char} (h : validElementName n = true) :
    ∀ c ∈ n, c ≠ '<' ∧ c ≠ '\x00' := by
  match n, h with
  | c :: r, h =>
    simp only [validElementName, Bool.and_eq_true, List.all_eq_true] at h
    intro d hd
    rcases List.mem_cons.mp hd with rfl | hd
    · exact nameChar_ne (by simp [isNameChar, h.1])
    · exact nameChar_ne (h.2 d hd)

theorem validHoleName_chars {n : List Char} (h : validHoleName n = true) :
    ∀ c ∈ n, c ≠ '<' ∧ c ≠ '\x00' := by
  match n, h with
  | c :: r, h =>
    simp only [validHoleName, Bool.and_eq_true, List.all_eq_true, Bool.or_eq_true,
      decide_eq_true_eq] at h
    intro d hd
    rcases List.mem_cons.mp hd with rfl | hd
    · rcases h.1 with h1 | h1
      · exact nameChar_ne (by simp [isNameChar, h1])
      · subst h1; decide
    · exact nameChar_ne (h.2 d hd)

theorem validSrcAttrName_chars {n : List Char} (h : validSrcAttrName n = true) :
    ∀ c ∈ n, c ≠ '<' ∧ c ≠ '\x00' := by
  intro c hc
  refine ⟨fun e => ?_, fun e => ?_⟩ <;> subst e <;> simp [validSrcAttrName] at h <;>
    have := h.2 _ hc <;> simp at this

theorem asciiWs_ne {c : Char} (h : isAsciiWs c = true) : c ≠ '<' ∧ c ≠ '\x00' := by
  refine ⟨?_, ?_⟩ <;> rintro rfl <;> revert h <;> decide

theorem allWs_ne {w : List Char} (h : w.all isAsciiWs = true) : ∀ c ∈ w, c ≠ '<' ∧ c ≠ '\x00' :=
  fun c hc => asciiWs_ne (List.all_eq_true.mp h c hc)

/-- The characters of a rendered tag after its `<`: no `<`, no NUL. -/
def NoLtNul (s : List Char) : Prop := ∀ c ∈ s, c ≠ '<' ∧ c ≠ '\x00'

theorem noLtNul_append {a b : List Char} (ha : NoLtNul a) (hb : NoLtNul b) : NoLtNul (a ++ b) := by
  intro c hc
  rcases List.mem_append.mp hc with h | h
  · exact ha c h
  · exact hb c h

theorem noLtNul_of_notMem {s : List Char} (h1 : '<' ∉ s) (h2 : '\x00' ∉ s) : NoLtNul s :=
  fun c hc => ⟨fun e => h1 (e ▸ hc), fun e => h2 (e ▸ hc)⟩

theorem noLtNul_dec {l : List Char} (h : l.all (fun c => c != '<' && c != '\x00') = true) :
    NoLtNul l := by
  intro c hc
  have := List.all_eq_true.mp h c hc
  simp only [Bool.and_eq_true, bne_iff_ne, ne_eq] at this
  exact this

theorem staticAttr_render_noLtNul {a : StaticAttr} (hok : a.ok = true) (hl : a.noLt) :
    NoLtNul a.render := by
  cases a with
  | dquote n v =>
    simp only [StaticAttr.ok, Bool.and_eq_true, SrcValue.ok, List.all_eq_true, bne_iff_ne] at hok
    have hv : NoLtNul v := noLtNul_of_notMem hl (fun m => hok.2 _ m rfl)
    exact noLtNul_append (noLtNul_append (noLtNul_append (validSrcAttrName_chars hok.1.1)
      (noLtNul_dec (by decide))) hv) (noLtNul_dec (by decide))
  | squote n v =>
    simp only [StaticAttr.ok, Bool.and_eq_true, SrcValue.ok, List.all_eq_true, bne_iff_ne] at hok
    have hv : NoLtNul v := noLtNul_of_notMem hl (fun m => hok.2 _ m rfl)
    exact noLtNul_append (noLtNul_append (noLtNul_append (validSrcAttrName_chars hok.1.1)
      (noLtNul_dec (by decide))) hv) (noLtNul_dec (by decide))
  | unquoted n v =>
    simp only [StaticAttr.ok, Bool.and_eq_true, SrcValue.ok, List.all_eq_true, bne_iff_ne] at hok
    have hv : NoLtNul v := noLtNul_of_notMem hl (fun m => hok.2 _ m rfl)
    exact noLtNul_append (noLtNul_append (validSrcAttrName_chars hok.1.1)
      (noLtNul_dec (by decide))) hv
  | bare n =>
    simp only [StaticAttr.ok, Bool.and_true] at hok
    exact validSrcAttrName_chars hok

theorem renderItems_noLtNul : ∀ (items : List TagItem) (vs : List (Option (List Char))),
    (∀ it ∈ items, match it with | .attr a => a.ok = true | .hole n _ => validHoleName n = true) →
    (∀ it ∈ items, match it with | .attr a => a.noLt | .hole _ _ => True) →
    ItemValsOK items vs → NoLtNul (renderItems items vs).1
  | [], _, _, _, _ => by simp [renderItems, NoLtNul]
  | .attr a :: r, vs, hok, hl, hv => by
    have ih := renderItems_noLtNul r vs (fun it h => hok it (List.mem_cons_of_mem _ h))
      (fun it h => hl it (List.mem_cons_of_mem _ h)) hv
    have hak : a.ok = true := hok (.attr a) (by simp)
    have hal : a.noLt := hl (.attr a) (by simp)
    simp only [renderItems]
    exact noLtNul_append (noLtNul_append (noLtNul_dec (by decide))
      (staticAttr_render_noLtNul hak hal)) ih
  | .hole n c :: r, [], hok, hl, hv => by
    simp only [renderItems]
    exact renderItems_noLtNul r [] (fun it h => hok it (List.mem_cons_of_mem _ h))
      (fun it h => hl it (List.mem_cons_of_mem _ h)) hv
  | .hole n c :: r, none :: vs, hok, hl, ⟨_, hv⟩ => by
    simp only [renderItems]
    exact renderItems_noLtNul r vs (fun it h => hok it (List.mem_cons_of_mem _ h))
      (fun it h => hl it (List.mem_cons_of_mem _ h)) hv
  | .hole n c :: r, some x :: vs, hok, hl, ⟨hx, hv⟩ => by
    have ih := renderItems_noLtNul r vs (fun it h => hok it (List.mem_cons_of_mem _ h))
      (fun it h => hl it (List.mem_cons_of_mem _ h)) hv
    have hxs := hx x rfl
    have hnn : validHoleName n = true := hok (.hole n c) (by simp)
    have hxl : NoLtNul x := fun d hd => ⟨(hxs.1 d hd).1, (hxs.1 d hd).2.2.2.2⟩
    simp only [renderItems]
    exact noLtNul_append (noLtNul_append (noLtNul_append (noLtNul_append (noLtNul_append
      (noLtNul_dec (by decide)) (validHoleName_chars hnn)) (noLtNul_dec (by decide))) hxl)
      (noLtNul_dec (by decide))) ih

theorem srcChain_items : ∀ (q : Bool) (items : List (List Char × SrcAttr)), SrcChain q items →
    ∀ x ∈ items, x.1.all isAsciiWs = true ∧ x.2.ok = true ∧ '\x00' ∉ x.2.render
  | _, [], _, _, h => by simp at h
  | q, (w, a) :: r, ⟨hw, ha, hn, _, hr⟩, x, hx => by
    rcases List.mem_cons.mp hx with rfl | hx
    · exact ⟨hw, ha, hn⟩
    · exact srcChain_items _ r hr x hx

theorem srcAttr_render_noLtNul {a : SrcAttr} (hok : a.ok = true) (hn : '\x00' ∉ a.render) (hl : a.noLt) :
    NoLtNul a.render := by
  rcases a with ⟨n, _ | ⟨w1, w2, v⟩⟩
  · simp only [SrcAttr.ok, Bool.and_true] at hok
    intro c hc
    simp only [SrcAttr.render, List.append_nil] at hc
    exact validSrcAttrName_chars hok c hc
  · simp only [SrcAttr.ok, Bool.and_eq_true] at hok
    simp only [SrcAttr.noLt] at hl
    intro c hc
    refine ⟨?_, fun e => hn (e ▸ hc)⟩
    simp only [SrcAttr.render, List.mem_append, List.mem_singleton] at hc
    rcases hc with hc | ((hc | rfl) | hc) | hc
    · exact (validSrcAttrName_chars hok.1 c hc).1
    · exact (allWs_ne hok.2.1.1 c hc).1
    · decide
    · exact (allWs_ne hok.2.1.2 c hc).1
    · exact fun e => hl (e ▸ hc)

/-- A start tag inside `noscript` renders as `<` + a letter + characters without `<` or NUL. -/
theorem noscriptTag_render {t : StartTag} (h : NoscriptTagOK t) {vs : List (Option (List Char))}
    (hv : t.ValsOK vs) :
    ∃ c rest, (t.render vs).1 = '<' :: c :: rest ∧ isAlpha c = true ∧ NoLtNul rest := by
  obtain ⟨hname, ⟨hitems, _, _, hsrc⟩, hl, hls⟩ := h
  rcases hs : t.source with _ | src <;> cases ha : t.items.all TagItem.isAttr <;>
    simp only [StartTag.render, hs, ha]
  case some.true =>
    obtain ⟨⟨hvn, htrail, hchain, _⟩, _, _, _⟩ := hsrc src hs
    obtain ⟨c, r, hn, hc⟩ := (validElementName_syn hvn).1
    have hnm := validElementName_chars hvn
    refine ⟨c, r ++ (src.items.flatMap fun (w, a) => w ++ a.render) ++ src.trailWs ++
      (if src.selfClosing then ['/', '>'] else ['>']), ?_, hc, ?_⟩
    · simp [SrcTag.render, hn]
    · refine noLtNul_append (noLtNul_append (noLtNul_append
        (fun d hd => hnm d (by simp [hn, hd])) ?_) (allWs_ne htrail)) ?_
      · intro d hd
        rw [List.mem_flatMap] at hd
        obtain ⟨⟨w, a⟩, hx, hd⟩ := hd
        obtain ⟨hw, hak, hnl⟩ := srcChain_items false src.items hchain _ hx
        rcases List.mem_append.mp hd with hd | hd
        · exact allWs_ne hw d hd
        · exact srcAttr_render_noLtNul hak hnl (hls src hs _ hx) d hd
      · split <;> exact noLtNul_dec (by decide)
  all_goals
    obtain ⟨c, r, hn, hc⟩ := (validElementName_syn hname).1
    have hnm := validElementName_chars hname
    have hvals : ItemValsOK t.items vs := by simpa [StartTag.ValsOK, hs, ha] using hv
    refine ⟨c, r ++ (renderItems t.items vs).1 ++
      (if t.selfClosing then (if t.spaceBeforeSlash then " />".toList else "/>".toList) else ['>']),
      ?_, hc, ?_⟩
    · simp [hn]
    · refine noLtNul_append (noLtNul_append (fun d hd => hnm d (by simp [hn, hd]))
        (renderItems_noLtNul t.items vs hitems hl hvals)) ?_
      split <;> first | exact noLtNul_dec (by decide) | (split <;> exact noLtNul_dec (by decide))

/-! ### RAWTEXT reading of markup -/

/-- `<` + a character that is not `/` + characters without `<`: all text in RCDATA/RAWTEXT. -/
theorem raw_lt_run (tr : Tree) {k : Raw} (hk : NotScript k) (n : List Char) {c : Char} {w : List Char}
    (hc1 : c ≠ '/') (hc2 : c ≠ '<') (hc3 : c ≠ '\x00') (hw : NoLtNul w) :
    run tr ⟨.raw k, n⟩ ('<' :: c :: w) = (⟨.raw k, n⟩, ('<' :: c :: w).map .char) := by
  have hks : k ≠ .script := notScript_ne hk
  have hw1 : '<' ∉ w := fun m => (hw _ m).1 rfl
  have hw2 : '\x00' ∉ w := fun m => (hw _ m).2 rfl
  simp only [run]
  rw [show step tr ⟨.raw k, n⟩ '<' = (⟨.rawLt k, n⟩, []) by simp [step, stepRaw]]
  simp only
  rw [show step tr ⟨.rawLt k, n⟩ c = (⟨.raw k, n⟩, [.char '<', .char c]) by
    simp [step, stepRaw, hc1, hc2, hc3, hks]]
  simp only
  rw [raw_plain_run tr k n w hw1 hw2]
  simp

theorem isNameChar_facts {c : Char} (h : isNameChar c = true) :
    isWs c = false ∧ c ≠ '/' ∧ c ≠ '>' := by
  refine ⟨?_, ?_, ?_⟩
  · cases hw : isWs c
    · rfl
    · rcases isWs_cases hw with rfl | rfl | rfl | rfl | rfl <;> revert h <;> decide
  all_goals (rintro rfl; revert h; decide)

/-- An end tag name's characters in an RCDATA/RAWTEXT end tag name state: letters extend
the buffer; the first other name character flushes it as text. -/
theorem rawEndTagName_name (tr : Tree) (k : Raw) (ls : List Char) :
    ∀ (q b : List Char), (∀ d ∈ q, isNameChar d = true) →
      ∃ st' out, run tr ⟨.rawEndTagName k b, ls⟩ q = (⟨st', ls⟩, out.map .char) ∧
        ((st' = .raw k ∧ out = ['<', '/'] ++ b ++ q) ∨
         (st' = .rawEndTagName k (b ++ q) ∧ out = [] ∧ ∀ d ∈ q, isAlpha d = true))
  | [], b, _ => ⟨.rawEndTagName k b, [], by simp [run], Or.inr ⟨by simp, rfl, by simp⟩⟩
  | d :: q, b, hq => by
    have hd := hq d (by simp)
    obtain ⟨hw, h2, h3⟩ := isNameChar_facts hd
    have hq' : ∀ e ∈ q, isNameChar e = true := fun e he => hq e (by simp [he])
    by_cases ha : isAlpha d = true
    · obtain ⟨st', out, e, hcase⟩ := rawEndTagName_name tr k ls q (b ++ [d]) hq'
      refine ⟨st', out, ?_, ?_⟩
      · simp only [run]
        rw [show step tr ⟨.rawEndTagName k b, ls⟩ d = (⟨.rawEndTagName k (b ++ [d]), ls⟩, []) by
          simp [step, stepRawEndTagName, hw, h2, h3, ha]]
        simp only
        rw [e]; simp
      · rcases hcase with ⟨h1, h2'⟩ | ⟨h1, h2', h3'⟩
        · exact Or.inl ⟨h1, by rw [h2']; simp⟩
        · refine Or.inr ⟨by rw [h1]; simp, h2', ?_⟩
          intro e he
          rcases List.mem_cons.mp he with rfl | he
          · exact ha
          · exact h3' e he
    · have hne := nameChar_ne hd
      have hq2 : NoLtNul q := fun e he => nameChar_ne (hq' e he)
      refine ⟨.raw k, ['<', '/'] ++ b ++ d :: q, ?_, Or.inl ⟨rfl, rfl⟩⟩
      simp only [run]
      rw [show step tr ⟨.rawEndTagName k b, ls⟩ d =
          (⟨.raw k, ls⟩, (['<', '/'] ++ b ++ [d]).map .char) by
        have n1 : ¬ (isWs d = true ∧ appropriate ls b = true) := fun ⟨a, _⟩ => by simp [hw] at a
        have n2 : ¬ (d = '/' ∧ appropriate ls b = true) := fun ⟨a, _⟩ => h2 a
        have n3 : ¬ (d = '>' ∧ appropriate ls b = true) := fun ⟨a, _⟩ => h3 a
        simp only [step, stepRawEndTagName, ite_eq_right_of_eq_false _ _ (eq_false n1),
          ite_eq_right_of_eq_false _ _ (eq_false n2), ite_eq_right_of_eq_false _ _ (eq_false n3),
          ite_eq_right_of_eq_false _ _ (eq_false ha)]
        simp [stepRaw, hne.1, hne.2]]
      simp only
      rw [raw_plain_run tr k ls q (fun m => (hq2 _ m).1 rfl) (fun m => (hq2 _ m).2 rfl)]
      simp

/-- An end tag for another element inside RCDATA/RAWTEXT is text. -/
theorem raw_endTag_run (tr : Tree) {k : Raw} (hk : NotScript k) (ls : List Char) {m : List Char}
    (hm : validElementName m = true) (hne : lowerStr m ≠ ls) :
    run tr ⟨.raw k, ls⟩ (['<', '/'] ++ m ++ ['>']) =
      (⟨.raw k, ls⟩, (['<', '/'] ++ m ++ ['>']).map .char) := by
  have hks : k ≠ .script := notScript_ne hk
  obtain ⟨c, r, rfl, hc⟩ := (validElementName_syn hm).1
  have hr : ∀ d ∈ r, isNameChar d = true := by
    simp only [validElementName, Bool.and_eq_true, List.all_eq_true] at hm
    exact hm.2
  simp only [List.cons_append, List.nil_append, run]
  rw [show step tr ⟨.raw k, ls⟩ '<' = (⟨.rawLt k, ls⟩, []) by simp [step, stepRaw]]
  simp only
  rw [show step tr ⟨.rawLt k, ls⟩ '/' = (⟨.rawEndTagOpen k, ls⟩, []) by simp [step]]
  simp only
  rw [show step tr ⟨.rawEndTagOpen k, ls⟩ c = (⟨.rawEndTagName k [c], ls⟩, []) by simp [step, hc]]
  simp only
  rw [run_append]
  obtain ⟨st', out, e, hcase⟩ := rawEndTagName_name tr k ls r [c] hr
  rw [e]
  simp only
  rcases hcase with ⟨rfl, rfl⟩ | ⟨rfl, rfl, _⟩
  · simp [run, step, stepRaw]
  · have hap : appropriate ls (c :: r) = false := by
      cases h : appropriate ls (c :: r)
      · rfl
      · exact absurd (appropriate_iff.mp h) hne
    simp [run, step, stepRawEndTagName, hap, isWs, isAlpha, stepRaw, List.singleton_append]

/-! ### The `noscript` body -/

/-- What the profile allows inside `noscript` (as slice-4 pieces, Tessera's markup
reading): static text and comments without `<`, TEXT holes, start tags with no `<` in
attribute values, and end tags of other elements. -/
def NoscriptBodyOK : List Piece4 → Prop
  | [] => True
  | .static s :: r => '<' ∉ s ∧ '\x00' ∉ s ∧ NoscriptBodyOK r
  | .holeText :: r => NoscriptBodyOK r
  | .startTag t :: r => NoscriptTagOK t ∧ NoscriptBodyOK r
  | .endTag n :: r => validElementName n = true ∧ asciiLower n ≠ noscriptName ∧ NoscriptBodyOK r
  | .comment b :: r => '<' ∉ b ∧ '\x00' ∉ b ∧ NoscriptBodyOK r
  | .doctype _ :: _ => False
  | .script _ :: _ => False
  -- An empty `iframe` (GTM's `<noscript><iframe ...></iframe>`): a tag and its end tag.
  | .raw e :: r => NoscriptTagOK e.tag ∧ asciiLower e.tag.name = "iframe".toList ∧ e.body = [] ∧
      NoscriptBodyOK r

theorem htmlTextSafe_nul {v : List Char} (h : htmlTextSafe v) : '\x00' ∉ v :=
  fun m => (h.1 _ m).2.2 rfl

/-- **The `noscript` body, with scripting enabled.** Everything Tessera reads as markup
is RAWTEXT characters, and the tokenizer stays in the RAWTEXT state. -/
theorem run_noscriptBody (tr : Tree) :
    ∀ (body : List Piece4) (vs : List (Option (List Char))), NoscriptBodyOK body → ValsOK4 body vs →
      run tr ⟨.raw .rawtext, noscriptName⟩ (render4 body vs) =
        (⟨.raw .rawtext, noscriptName⟩, (render4 body vs).map .char)
  | [], _, _, _ => by simp [render4, run]
  | .static s :: r, vs, ⟨h1, h2, hr⟩, hv => by
    simp only [render4]
    rw [run_append, raw_plain_run tr _ _ s h1 h2]
    simp only
    rw [run_noscriptBody tr r vs hr hv]; simp
  | .holeText :: r, [], hr, hv => by
    simp only [render4]
    exact run_noscriptBody tr r [] hr hv
  | .holeText :: r, v :: vs, hr, ⟨hx, hv⟩ => by
    simp only [render4]
    cases v with
    | none =>
      rw [Option.getD_none, List.nil_append]
      exact run_noscriptBody tr r vs hr hv
    | some x =>
      rw [run_append]
      have hxs := hx x rfl
      rw [Option.getD_some, raw_chunks_run tr (.inr rfl) _ (htmlTextSafe_chunks hxs) (htmlTextSafe_nul hxs)]
      simp only
      rw [run_noscriptBody tr r vs hr hv]; simp
  | .startTag t :: r, vs, ⟨ht, hr⟩, ⟨hvt, hv⟩ => by
    simp only [render4]
    obtain ⟨c, rest, e, hc, hrest⟩ := noscriptTag_render ht hvt
    rw [run_append, e, raw_lt_run tr (.inr rfl) _ (alpha_not_ws hc).2.1
      (by rintro rfl; simp [isAlpha] at hc) (by rintro rfl; simp [isAlpha] at hc) hrest]
    simp only
    rw [run_noscriptBody tr r _ hr hv]; simp
  | .endTag n :: r, vs, ⟨hn, hne, hr⟩, hv => by
    simp only [render4]
    rw [run_append, raw_endTag_run tr (.inr rfl) _ hn (by rw [lowerStr_eq_asciiLower]; exact hne)]
    simp only
    rw [run_noscriptBody tr r vs hr hv]; simp
  | .comment b :: r, vs, ⟨h1, h2, hr⟩, hv => by
    simp only [render4]
    have e : "<!--".toList ++ b ++ "-->".toList = '<' :: '!' :: (['-', '-'] ++ b ++ ['-', '-', '>']) := rfl
    have hw : NoLtNul (['-', '-'] ++ b ++ ['-', '-', '>']) :=
      noLtNul_append (noLtNul_append (noLtNul_dec (by decide)) (noLtNul_of_notMem h1 h2))
        (noLtNul_dec (by decide))
    rw [e, run_append, raw_lt_run tr (.inr rfl) noscriptName (c := '!') (by decide) (by decide) (by decide) hw]
    simp only
    rw [run_noscriptBody tr r vs hr hv]; simp
  | .raw e :: r, vs, ⟨ht, hn, hb, hr⟩, ⟨hvt, _, hv⟩ => by
    obtain ⟨c, rest, e1, hc, hrest⟩ := noscriptTag_render ht hvt
    have hne : lowerStr e.tag.name ≠ noscriptName := by
      rw [lowerStr_eq_asciiLower, hn]; decide
    have hv' : ValsOK4 r (e.tag.render vs).2 := by
      simpa [RawEl.render, hb, renderScript] using hv
    have hsplit : render4 (.raw e :: r) vs =
        (e.tag.render vs).1 ++ ((['<', '/'] ++ e.tag.name ++ ['>']) ++ render4 r (e.tag.render vs).2) := by
      simp [render4, RawEl.render, hb, renderScript]
    rw [hsplit, run_append, e1, raw_lt_run tr (.inr rfl) _ (alpha_not_ws hc).2.1
      (by rintro rfl; simp [isAlpha] at hc) (by rintro rfl; simp [isAlpha] at hc) hrest]
    simp only
    rw [run_append, raw_endTag_run tr (.inr rfl) _ ht.1 hne]
    simp only
    rw [run_noscriptBody tr r _ hr hv']; simp

/-! ### The `noscript` element -/

/-- A `noscript` element: its start tag and its content as slice-4 pieces. -/
structure NoscriptEl where
  tag : StartTag
  body : List Piece4

/-- The values left after rendering slice-4 pieces (mirrors `render4`'s consumption). -/
def rest4 : List Piece4 → List (Option (List Char)) → List (Option (List Char))
  | [], vs => vs
  | .static _ :: r, vs => rest4 r vs
  | .holeText :: r, [] => rest4 r []
  | .holeText :: r, _ :: vs => rest4 r vs
  | .startTag t :: r, vs => rest4 r (t.render vs).2
  | .endTag _ :: r, vs => rest4 r vs
  | .comment _ :: r, vs => rest4 r vs
  | .doctype _ :: r, vs => rest4 r vs
  | .script e :: r, vs => rest4 r (e.render vs).2
  | .raw e :: r, vs => rest4 r (e.render vs).2

def NoscriptEl.render (e : NoscriptEl) (vs : List (Option (List Char))) :
    List Char × List (Option (List Char)) :=
  let (tagOut, vs1) := e.tag.render vs
  (tagOut ++ render4 e.body vs1 ++ ['<', '/'] ++ e.tag.name ++ ['>'], rest4 e.body vs1)

/-- A `noscript` element is in the profile: its name, not self-closing, the slice-2
attribute rules, and `NoscriptBodyOK` content. -/
def NoscriptOK (e : NoscriptEl) : Prop :=
  asciiLower e.tag.name = noscriptName ∧ e.tag.selfClosing = false ∧ TagAttrsOK e.tag ∧
  NoscriptBodyOK e.body

theorem noscript_facts {n : List Char} (h : asciiLower n = noscriptName) :
    (∃ c r, n = c :: r ∧ isAlpha c = true) ∧ (∀ c ∈ n, NameChar c) ∧ n ≠ [] ∧
      ∀ c ∈ n, isAlpha c = true := by
  have halpha : ∀ c ∈ n, isAlpha c = true := alpha_of_asciiLower h (by decide)
  have hne : n ≠ [] := by rintro rfl; exact absurd h (by decide)
  refine ⟨?_, fun c hc => nameChar_of_alpha (halpha c hc), hne, halpha⟩
  match n, hne with
  | c :: r, _ => exact ⟨c, r, rfl, halpha c (by simp)⟩

/-- A `noscript` element from the data state, scripting enabled: its StartTag, its whole
content as characters, and one EndTag. -/
theorem run_noscript (tr : Tree) (hstub : tr noscriptName = .raw .rawtext) (ls : List Char) {e : NoscriptEl} {vs : List (Option (List Char))}
    (he : NoscriptOK e) (hvt : e.tag.ValsOK vs) (hvb : ValsOK4 e.body (e.tag.render vs).2) :
    run tr ⟨.data, ls⟩ (e.render vs).1 =
      (⟨.data, noscriptName⟩, (e.tag.toSyn vs).1.tessera ::
        ((render4 e.body (e.tag.render vs).2).map .char ++ [.endTag noscriptName])) := by
  obtain ⟨hr1, hr2⟩ := StartTag.toSyn_render e.tag vs
  obtain ⟨hname, hsc, hattrs, hbody⟩ := he
  obtain ⟨hf1, hf2, hne, halpha⟩ := noscript_facts hname
  have hl : lowerStr e.tag.name = noscriptName := by rw [lowerStr_eq_asciiLower, hname]
  simp only [NoscriptEl.render]
  rw [← hr1, List.append_assoc, List.append_assoc, List.append_assoc, run_append,
    run_startTag tr ls (StartTag.toSyn_ok_of e.tag vs ⟨hf1, hf2⟩ hattrs hvt),
    token_eq_tessera (StartTag.toSyn_nodup e.tag vs hattrs), StartTag.toSyn_name_of e.tag vs hattrs,
    hl, hstub]
  simp only [List.singleton_append]
  rw [run_append, run_noscriptBody tr e.body _ hbody hvb]
  simp only
  rw [← List.append_assoc, goodR_close tr (.inr rfl) (GoodR.raw []) hl hne halpha]
  simp [pend]

/-! ### Slice-5 templates -/

/-- A slice-5 template piece: the slice-4 pieces and `noscript` elements. -/
inductive Piece5 where
  | static (s : List Char)
  | holeText
  | startTag (t : StartTag)
  | endTag (name : List Char)
  | comment (b : List Char)
  | doctype (d : Doctype)
  | script (e : ScriptEl)
  | raw (e : RawEl)
  | noscript (e : NoscriptEl)

def render5 : List Piece5 → List (Option (List Char)) → List Char
  | [], _ => []
  | .static s :: r, vs => s ++ render5 r vs
  | .holeText :: r, [] => render5 r []
  | .holeText :: r, v :: vs => v.getD [] ++ render5 r vs
  | .startTag t :: r, vs => (t.render vs).1 ++ render5 r (t.render vs).2
  | .endTag n :: r, vs => ['<', '/'] ++ n ++ ['>'] ++ render5 r vs
  | .comment b :: r, vs => "<!--".toList ++ b ++ "-->".toList ++ render5 r vs
  | .doctype d :: r, vs => d.render ++ render5 r vs
  | .script e :: r, vs => (e.render vs).1 ++ render5 r (e.render vs).2
  | .raw e :: r, vs => (e.render vs).1 ++ render5 r (e.render vs).2
  | .noscript e :: r, vs => (e.render vs).1 ++ render5 r (e.render vs).2

def ValsOK5 : List Piece5 → List (Option (List Char)) → Prop
  | [], _ => True
  | .static _ :: r, vs => ValsOK5 r vs
  | .holeText :: r, [] => ValsOK5 r []
  | .holeText :: r, v :: vs => (∀ x, v = some x → htmlTextSafe x) ∧ ValsOK5 r vs
  | .startTag t :: r, vs => t.ValsOK vs ∧ ValsOK5 r (t.render vs).2
  | .endTag _ :: r, vs => ValsOK5 r vs
  | .comment _ :: r, vs => ValsOK5 r vs
  | .doctype _ :: r, vs => ValsOK5 r vs
  | .script e :: r, vs => e.tag.ValsOK vs ∧ ScriptValsOK e.body (e.tag.render vs).2 ∧
      ValsOK5 r (e.render vs).2
  | .raw e :: r, vs => e.tag.ValsOK vs ∧ RawValsOK e.body (e.tag.render vs).2 ∧
      ValsOK5 r (e.render vs).2
  | .noscript e :: r, vs => e.tag.ValsOK vs ∧ ValsOK4 e.body (e.tag.render vs).2 ∧
      ValsOK5 r (e.render vs).2

/-- Tessera's reading of a slice-5 page with scripting enabled: a `noscript` element is
its start tag, its whole content as characters, and its end tag. -/
def denote5 : List Piece5 → List (Option (List Char)) → List Token
  | [], _ => []
  | .static s :: r, vs => s.map .char ++ denote5 r vs
  | .holeText :: r, [] => denote5 r []
  | .holeText :: r, v :: vs => textTokens (v.getD []) ++ denote5 r vs
  | .startTag t :: r, vs => (t.toSyn vs).1.tessera :: denote5 r (t.render vs).2
  | .endTag n :: r, vs => .endTag (lowerStr n) :: denote5 r vs
  | .comment b :: r, vs => .comment b :: denote5 r vs
  | .doctype _ :: r, vs => .doctype (some "html".toList) false :: denote5 r vs
  | .script e :: r, vs =>
    (e.tag.toSyn vs).1.tessera ::
      ((renderScript e.body (e.tag.render vs).2).1.map .char ++ [.endTag scriptName] ++
        denote5 r (e.render vs).2)
  | .raw e :: r, vs =>
    (e.tag.toSyn vs).1.tessera ::
      ((renderScript e.body (e.tag.render vs).2).1.map .char ++ [.endTag (lowerStr e.tag.name)] ++
        denote5 r (e.render vs).2)
  | .noscript e :: r, vs =>
    (e.tag.toSyn vs).1.tessera ::
      ((render4 e.body (e.tag.render vs).2).map .char ++ [.endTag noscriptName] ++
        denote5 r (e.render vs).2)

def Piece5.ok : Piece5 → Prop
  | .static s => StaticText s
  | .holeText => True
  | .startTag t => StartTag2OK t
  | .endTag n => Slice2Name n
  | .comment b => CommentTessera b
  | .doctype d => DoctypeOK d
  | .script e => ScriptOK e
  | .raw e => RawOK e
  | .noscript e => NoscriptOK e

def Normal5 : List Piece5 → Prop
  | [] => True
  | [_] => True
  | .static _ :: .static _ :: _ => False
  | _ :: p :: r => Normal5 (p :: r)

def InSubset5 (T : List Piece5) : Prop :=
  Normal5 T ∧ (∀ p ∈ T, p.ok) ∧
  (∀ i s, T[i]? = some (.static s) → T[i + 1]? = some .holeText → NoLtBeforeHole s)

def StartsMarkup5 : List Piece5 → Prop
  | .startTag _ :: _ | .endTag _ :: _ | .comment _ :: _ | .doctype _ :: _ | .script _ :: _
  | .raw _ :: _ | .noscript _ :: _ => True
  | _ => False

def Ok5 : List Piece5 → Prop
  | [] => True
  | .static s :: r => StaticText s ∧ (s.getLast? = some '<' → r = [] ∨ StartsMarkup5 r) ∧ Ok5 r
  | .holeText :: r => Ok5 r
  | .startTag t :: r => StartTag2OK t ∧ Ok5 r
  | .endTag n :: r => Slice2Name n ∧ Ok5 r
  | .comment b :: r => CommentTessera b ∧ Ok5 r
  | .doctype d :: r => DoctypeOK d ∧ Ok5 r
  | .script e :: r => ScriptOK e ∧ Ok5 r
  | .raw e :: r => RawOK e ∧ Ok5 r
  | .noscript e :: r => NoscriptOK e ∧ Ok5 r

theorem render5_markup {r : List Piece5} (h : StartsMarkup5 r) (vs : List (Option (List Char))) :
    ∃ rest, render5 r vs = '<' :: rest := by
  match r, h with
  | .startTag t :: r, _ =>
    simp only [render5]
    unfold StartTag.render
    split <;> simp [SrcTag.render]
  | .script e :: r, _ =>
    simp only [render5, ScriptEl.render]
    unfold StartTag.render
    split <;> simp [SrcTag.render]
  | .raw e :: r, _ =>
    simp only [render5, RawEl.render]
    unfold StartTag.render
    split <;> simp [SrcTag.render]
  | .noscript e :: r, _ =>
    simp only [render5, NoscriptEl.render]
    unfold StartTag.render
    split <;> simp [SrcTag.render]
  | .endTag _ :: _, _ => simp [render5]
  | .comment _ :: _, _ => simp [render5]
  | .doctype _ :: _, _ => simp [render5, Doctype.render]

theorem t1_ok5 :
    ∀ (T : List Piece5) (vs : List (Option (List Char))) (ls : List Char), Ok5 T → ValsOK5 T vs →
      tokensFrom treeStub ⟨.data, ls⟩ (render5 T vs) = denote5 T vs
  | [], _, _, _, _ => by simp [render5, denote5, tokensFrom, run, eofTokens]
  | .holeText :: r, [], ls, h, hv => by
    simpa [render5, denote5] using t1_ok5 r [] ls h hv
  | .holeText :: r, v :: vs, ls, h, hv => by
    simp only [render5, denote5]
    rw [tokensFrom_append]
    cases v with
    | none => simpa [run, textTokens] using t1_ok5 r vs ls h hv.2
    | some x =>
      rw [Option.getD_some, run_textChunks treeStub ls (htmlTextSafe_chunks (hv.1 x rfl))]
      rw [t1_ok5 r vs ls h hv.2]
  | .startTag t :: r, vs, ls, ⟨ht, h⟩, ⟨hvt, hv⟩ => by
    simp only [render5, denote5]
    obtain ⟨hr1, hr2⟩ := StartTag.toSyn_render t vs
    rw [← hr1, ← hr2, tokensFrom_append, run_startTag treeStub ls (StartTag.toSyn_ok t vs ht hvt),
      token_eq_tessera (StartTag.toSyn_nodup t vs ht.2),
      StartTag.toSyn_name t vs ht, treeStub_slice2 ht.1]
    rw [hr2] at *
    simp [t1_ok5 r _ (lowerStr t.name) h hv]
  | .endTag n :: r, vs, ls, ⟨hn, h⟩, hv => by
    simp only [render5, denote5]
    obtain ⟨h1, h2⟩ := slice2Name_ok hn
    rw [tokensFrom_append, run_endTag treeStub ls h1 h2]
    simp [t1_ok5 r vs ls h hv]
  | .comment b :: r, vs, ls, ⟨hb, h⟩, hv => by
    simp only [render5, denote5]
    have e : "<!--".toList ++ b ++ "-->".toList = ['<', '!', '-', '-'] ++ b ++ ['-', '-', '>'] := rfl
    rw [e, tokensFrom_append, run_comment treeStub ls (commentBodyOK_of_tessera hb)]
    simp [t1_ok5 r vs ls h hv]
  | .doctype d :: r, vs, ls, ⟨hd, h⟩, hv => by
    simp only [render5, denote5]
    rw [tokensFrom_append, run_doctype treeStub ls hd]
    simp [t1_ok5 r vs ls h hv]
  | .script e :: r, vs, ls, ⟨he, h⟩, ⟨hvt, hvb, hv⟩ => by
    simp only [render5, denote5]
    rw [tokensFrom_append, run_script treeStub (by decide) ls he hvt hvb]
    simp [t1_ok5 r _ scriptName h hv]
  | .raw e :: r, vs, ls, ⟨he, h⟩, ⟨hvt, hvb, hv⟩ => by
    simp only [render5, denote5]
    rw [tokensFrom_append, run_rawEl treeStub ls he (by rw [lowerStr_eq_asciiLower]; exact treeStub_raw he.1) hvt hvb]
    simp [t1_ok5 r _ (lowerStr e.tag.name) h hv]
  | .noscript e :: r, vs, ls, ⟨he, h⟩, ⟨hvt, hvb, hv⟩ => by
    simp only [render5, denote5]
    rw [tokensFrom_append, run_noscript treeStub (by decide) ls he hvt hvb]
    simp [t1_ok5 r _ noscriptName h hv]
  | .static s :: r, vs, ls, ⟨hs, hlast, h⟩, hv => by
    simp only [render5, denote5]
    rw [tokensFrom_append]
    rcases run_static treeStub ls s hs with ⟨_, hrun⟩ | ⟨hl, hrun⟩
    · rw [hrun, t1_ok5 r vs ls h hv]
    · rw [hrun]
      have hs' : s = s.dropLast ++ ['<'] := by
        have hne : s ≠ [] := by rintro rfl; simp at hl
        have hg := List.dropLast_concat_getLast hne
        rw [List.getLast?_eq_some_getLast hne] at hl
        simp only [Option.some.injEq] at hl
        rw [hl] at hg
        exact hg.symm
      conv => rhs; rw [hs']
      rcases hlast hl with rfl | hm
      · cases vs <;> simp [render5, denote5, tokensFrom, run, eofTokens]
      · obtain ⟨rest, hrest⟩ := render5_markup hm vs
        rw [hrest, tokensFrom_tagOpen_lt, ← hrest, t1_ok5 r vs ls h hv]
        simp

def EndsOK5 (m : M) : Prop := m.st = .data ∨ m.st = .tagOpen

theorem run_ok5 :
    ∀ (T : List Piece5) (vs : List (Option (List Char))) (ls : List Char), Ok5 T → ValsOK5 T vs →
      EndsOK5 (run treeStub ⟨.data, ls⟩ (render5 T vs)).1
  | [], _, _, _, _ => by simp [render5, run, EndsOK5]
  | .holeText :: r, [], ls, h, hv => by
    simpa [render5] using run_ok5 r [] ls h hv
  | .holeText :: r, v :: vs, ls, h, hv => by
    simp only [render5]
    rw [run_append]
    cases v with
    | none => simpa [run] using run_ok5 r vs ls h hv.2
    | some x =>
      rw [Option.getD_some, run_textChunks treeStub ls (htmlTextSafe_chunks (hv.1 x rfl))]
      exact run_ok5 r vs ls h hv.2
  | .startTag t :: r, vs, ls, ⟨ht, h⟩, ⟨hvt, hv⟩ => by
    simp only [render5]
    obtain ⟨hr1, hr2⟩ := StartTag.toSyn_render t vs
    rw [← hr1, ← hr2, run_append, run_startTag treeStub ls (StartTag.toSyn_ok t vs ht hvt),
      StartTag.toSyn_name t vs ht, treeStub_slice2 ht.1]
    rw [hr2] at *
    exact run_ok5 r _ (lowerStr t.name) h hv
  | .endTag n :: r, vs, ls, ⟨hn, h⟩, hv => by
    simp only [render5]
    obtain ⟨h1, h2⟩ := slice2Name_ok hn
    rw [run_append, run_endTag treeStub ls h1 h2]
    exact run_ok5 r vs ls h hv
  | .comment b :: r, vs, ls, ⟨hb, h⟩, hv => by
    simp only [render5]
    have e : "<!--".toList ++ b ++ "-->".toList = ['<', '!', '-', '-'] ++ b ++ ['-', '-', '>'] := rfl
    rw [e, run_append, run_comment treeStub ls (commentBodyOK_of_tessera hb)]
    exact run_ok5 r vs ls h hv
  | .doctype d :: r, vs, ls, ⟨hd, h⟩, hv => by
    simp only [render5]
    rw [run_append, run_doctype treeStub ls hd]
    exact run_ok5 r vs ls h hv
  | .script e :: r, vs, ls, ⟨he, h⟩, ⟨hvt, hvb, hv⟩ => by
    simp only [render5]
    rw [run_append, run_script treeStub (by decide) ls he hvt hvb]
    exact run_ok5 r _ scriptName h hv
  | .raw e :: r, vs, ls, ⟨he, h⟩, ⟨hvt, hvb, hv⟩ => by
    simp only [render5]
    rw [run_append, run_rawEl treeStub ls he (by rw [lowerStr_eq_asciiLower]; exact treeStub_raw he.1) hvt hvb]
    exact run_ok5 r _ (lowerStr e.tag.name) h hv
  | .noscript e :: r, vs, ls, ⟨he, h⟩, ⟨hvt, hvb, hv⟩ => by
    simp only [render5]
    rw [run_append, run_noscript treeStub (by decide) ls he hvt hvb]
    exact run_ok5 r _ noscriptName h hv
  | .static s :: r, vs, ls, ⟨hs, hlast, h⟩, hv => by
    simp only [render5]
    rw [run_append]
    rcases run_static treeStub ls s hs with ⟨_, hrun⟩ | ⟨hl, hrun⟩
    · rw [hrun]; exact run_ok5 r vs ls h hv
    · rw [hrun]
      rcases hlast hl with rfl | hm
      · cases vs <;> simp [render5, run, EndsOK5]
      · obtain ⟨rest, hrest⟩ := render5_markup hm vs
        have e : run treeStub ⟨.tagOpen, ls⟩ (render5 r vs) =
            ((run treeStub ⟨.data, ls⟩ (render5 r vs)).1,
              .char '<' :: (run treeStub ⟨.data, ls⟩ (render5 r vs)).2) := by
          rw [hrest]; simp [run, step, stepData, isAlpha]
        rw [e]; exact run_ok5 r vs ls h hv

theorem normal5_tail {p : Piece5} {r : List Piece5} (h : Normal5 (p :: r)) : Normal5 r := by
  match p, r, h with
  | _, [], _ => trivial
  | .static _, .static _ :: _, h => exact h.elim
  | .static _, .holeText :: _, h => exact h
  | .static _, .startTag _ :: _, h => exact h
  | .static _, .endTag _ :: _, h => exact h
  | .static _, .comment _ :: _, h => exact h
  | .static _, .doctype _ :: _, h => exact h
  | .static _, .script _ :: _, h => exact h
  | .static _, .raw _ :: _, h => exact h
  | .static _, .noscript _ :: _, h => exact h
  | .holeText, q :: _, h => cases q <;> exact h
  | .startTag _, q :: _, h => cases q <;> exact h
  | .endTag _, q :: _, h => cases q <;> exact h
  | .comment _, q :: _, h => cases q <;> exact h
  | .doctype _, q :: _, h => cases q <;> exact h
  | .script _, q :: _, h => cases q <;> exact h
  | .raw _, q :: _, h => cases q <;> exact h
  | .noscript _, q :: _, h => cases q <;> exact h

theorem inSubset5_tail {p : Piece5} {r : List Piece5} (h : InSubset5 (p :: r)) : InSubset5 r :=
  ⟨normal5_tail h.1, fun q hq => h.2.1 q (List.mem_cons_of_mem _ hq),
    fun i s h1 h2 => h.2.2 (i + 1) s (by simpa using h1) (by simpa using h2)⟩

theorem inSubset5_ok : ∀ T : List Piece5, InSubset5 T → Ok5 T
  | [], _ => trivial
  | .holeText :: r, h => inSubset5_ok r (inSubset5_tail h)
  | .startTag t :: r, h => ⟨h.2.1 (.startTag t) (by simp), inSubset5_ok r (inSubset5_tail h)⟩
  | .endTag n :: r, h => ⟨h.2.1 (.endTag n) (by simp), inSubset5_ok r (inSubset5_tail h)⟩
  | .comment b :: r, h => ⟨h.2.1 (.comment b) (by simp), inSubset5_ok r (inSubset5_tail h)⟩
  | .doctype d :: r, h => ⟨h.2.1 (.doctype d) (by simp), inSubset5_ok r (inSubset5_tail h)⟩
  | .script e :: r, h => ⟨h.2.1 (.script e) (by simp), inSubset5_ok r (inSubset5_tail h)⟩
  | .raw e :: r, h => ⟨h.2.1 (.raw e) (by simp), inSubset5_ok r (inSubset5_tail h)⟩
  | .noscript e :: r, h => ⟨h.2.1 (.noscript e) (by simp), inSubset5_ok r (inSubset5_tail h)⟩
  | .static s :: r, h => by
    refine ⟨h.2.1 (.static s) (by simp), fun hl => ?_, inSubset5_ok r (inSubset5_tail h)⟩
    match r, h.1 with
    | [], _ => exact .inl rfl
    | .static _ :: _, hn => exact hn.elim
    | .holeText :: _, _ => exact absurd hl (h.2.2 0 s rfl rfl)
    | .startTag _ :: _, _ => exact .inr trivial
    | .endTag _ :: _, _ => exact .inr trivial
    | .comment _ :: _, _ => exact .inr trivial
    | .doctype _ :: _, _ => exact .inr trivial
    | .script _ :: _, _ => exact .inr trivial
    | .raw _ :: _, _ => exact .inr trivial
    | .noscript _ :: _, _ => exact .inr trivial

/--
**T1, slice 5.** As slice 4, plus `noscript` elements read with scripting enabled: each
is its StartTag, its whole content (Tessera's markup, rendered) as Character tokens, and
one EndTag `noscript`. So with scripting enabled nothing inside `noscript` is parsed, no
value or static content ends the element early, and the page after `</noscript>` is read
as Tessera reads it.
-/
theorem t1_slice5 (T : List Piece5) (vs : List (Option (List Char)))
    (hT : InSubset5 T) (hv : ValsOK5 T vs) :
    tokenize treeStub (render5 T vs) = denote5 T vs :=
  t1_ok5 T vs [] (inSubset5_ok T hT) hv

theorem t1_slice5_inModel (T : List Piece5) (vs : List (Option (List Char)))
    (hT : InSubset5 T) (hv : ValsOK5 T vs) :
    leavesModel treeStub init (render5 T vs) = false := by
  rcases run_ok5 T vs [] (inSubset5_ok T hT) hv with h | h <;> simp [leavesModel, init, h]

end TesseraT1
