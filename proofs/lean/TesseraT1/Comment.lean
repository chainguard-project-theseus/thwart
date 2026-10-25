/-
The comment states of the tokenizer model (13.2.5.43-52) read a static comment
`<!--` b `-->` as exactly one Comment token with data b, provided no `>` in b
lands where a comment state would emit (`CommentBodyOK`).
-/
import TesseraT1.Whatwg

namespace TesseraT1

open Whatwg

/-- The comment states with the input read since `<!--`: `L` is the comment data
plus the characters a state holds back (`-`, `--`, `--!`). -/
inductive CInv : St → List Char → Prop where
  | start : CInv (.commentStart []) []
  | startDash : CInv (.commentStartDash []) ['-']
  | comment (d : List Char) : CInv (.comment d) d
  | lt (d : List Char) : CInv (.commentLt d) d
  | ltBang (d : List Char) : CInv (.commentLtBang d) d
  | ltBangDash (d : List Char) : CInv (.commentLtBangDash d) (d ++ ['-'])
  | ltBangDashDash (d : List Char) : CInv (.commentLtBangDashDash d) (d ++ ['-', '-'])
  | endDash (d : List Char) : CInv (.commentEndDash d) (d ++ ['-'])
  | end_ (d : List Char) : CInv (.commentEnd d) (d ++ ['-', '-'])
  | endBang (d : List Char) : CInv (.commentEndBang d) (d ++ ['-', '-', '!'])

/-- After reading `L`, a `>` would end the comment: `<!-->`, `<!--->`, `-->`, `--!>`. -/
def EmitsAt (L : List Char) : Prop :=
  L = [] ∨ L = ['-'] ∨ (∃ p, L = p ++ ['-', '-']) ∨ (∃ p, L = p ++ ['-', '-', '!'])

/-- A comment body the browser reads to Tessera's end: no NUL (it would become
U+FFFD), and no `>` where `EmitsAt` holds. This is S6a (`HtmlParser.earlyCommentEnd`:
no `<!-->`, `<!--->`, or `--!>`) plus Tessera ending the comment at its first `-->`. -/
def CommentBodyOK (b : List Char) : Prop :=
  '\x00' ∉ b ∧ ∀ i, b[i]? = some '>' → ¬ EmitsAt (b.take i)

theorem stepComment_inv (ls d : List Char) {c : Char} (hc : c ≠ '\x00') :
    ∃ st', stepComment ls d c = (⟨st', ls⟩, []) ∧ CInv st' (d ++ [c]) := by
  unfold stepComment
  by_cases h1 : c = '<'
  · subst h1; exact ⟨.commentLt (d ++ ['<']), by simp, .lt _⟩
  by_cases h2 : c = '-'
  · subst h2; exact ⟨.commentEndDash d, by simp, .endDash _⟩
  · exact ⟨.comment (d ++ [c]), by simp [h1, h2, hc], .comment _⟩

theorem comment_step (tr : Tree) (ls : List Char) {st : St} {L : List Char} (h : CInv st L)
    {c : Char} (hc : c ≠ '\x00') (hgt : c = '>' → ¬ EmitsAt L) :
    ∃ st', step tr ⟨st, ls⟩ c = (⟨st', ls⟩, []) ∧ CInv st' (L ++ [c]) := by
  cases h with
  | start =>
    by_cases h1 : c = '-'
    · subst h1; exact ⟨.commentStartDash [], by simp [step], .startDash⟩
    by_cases h2 : c = '>'
    · exact absurd (Or.inl rfl) (hgt h2)
    obtain ⟨st', e, hi⟩ := stepComment_inv ls [] hc
    exact ⟨st', by simp [step, h1, h2, e], by simpa using hi⟩
  | startDash =>
    by_cases h1 : c = '-'
    · subst h1; exact ⟨.commentEnd [], by simp [step], by simpa using CInv.end_ []⟩
    by_cases h2 : c = '>'
    · exact absurd (Or.inr (Or.inl rfl)) (hgt h2)
    obtain ⟨st', e, hi⟩ := stepComment_inv ls ['-'] hc
    exact ⟨st', by simp [step, h1, h2, e], hi⟩
  | comment d =>
    obtain ⟨st', e, hi⟩ := stepComment_inv ls L hc
    exact ⟨st', by simp [step, e], hi⟩
  | lt d =>
    by_cases h1 : c = '!'
    · subst h1; exact ⟨.commentLtBang (L ++ ['!']), by simp [step], .ltBang _⟩
    by_cases h2 : c = '<'
    · subst h2; exact ⟨.commentLt (L ++ ['<']), by simp [step], .lt _⟩
    obtain ⟨st', e, hi⟩ := stepComment_inv ls L hc
    exact ⟨st', by simp [step, h1, h2, e], hi⟩
  | ltBang d =>
    by_cases h1 : c = '-'
    · subst h1; exact ⟨.commentLtBangDash L, by simp [step], .ltBangDash _⟩
    obtain ⟨st', e, hi⟩ := stepComment_inv ls L hc
    exact ⟨st', by simp [step, h1, e], hi⟩
  | ltBangDash d =>
    by_cases h1 : c = '-'
    · subst h1; exact ⟨.commentLtBangDashDash d, by simp [step], by simpa using CInv.ltBangDashDash d⟩
    obtain ⟨st', e, hi⟩ := stepComment_inv ls (d ++ ['-']) hc
    exact ⟨st', by simp [step, stepCommentEndDash, h1, e], hi⟩
  | ltBangDashDash d | end_ d =>
    by_cases h1 : c = '>'
    · exact absurd (Or.inr (Or.inr (Or.inl ⟨d, rfl⟩))) (hgt h1)
    by_cases h2 : c = '!'
    · subst h2; exact ⟨.commentEndBang d, by simp [step, stepCommentEnd], by simpa using CInv.endBang d⟩
    by_cases h3 : c = '-'
    · subst h3; exact ⟨.commentEnd (d ++ ['-']), by simp [step, stepCommentEnd], by simpa using CInv.end_ (d ++ ['-'])⟩
    obtain ⟨st', e, hi⟩ := stepComment_inv ls (d ++ ['-', '-']) hc
    exact ⟨st', by simp [step, stepCommentEnd, h1, h2, h3, e], by simpa using hi⟩
  | endDash d =>
    by_cases h1 : c = '-'
    · subst h1; exact ⟨.commentEnd d, by simp [step, stepCommentEndDash], by simpa using CInv.end_ d⟩
    obtain ⟨st', e, hi⟩ := stepComment_inv ls (d ++ ['-']) hc
    exact ⟨st', by simp [step, stepCommentEndDash, h1, e], hi⟩
  | endBang d =>
    by_cases h1 : c = '-'
    · subst h1; exact ⟨.commentEndDash (d ++ ['-', '-', '!']), by simp [step], by simpa using CInv.endDash (d ++ ['-', '-', '!'])⟩
    by_cases h2 : c = '>'
    · exact absurd (Or.inr (Or.inr (Or.inr ⟨d, rfl⟩))) (hgt h2)
    obtain ⟨st', e, hi⟩ := stepComment_inv ls (d ++ ['-', '-', '!']) hc
    exact ⟨st', by simp [step, h1, h2, e], by simpa using hi⟩

theorem comment_run (tr : Tree) (ls : List Char) :
    ∀ (r : List Char) {st : St} {L : List Char}, CInv st L → '\x00' ∉ r →
      (∀ i, r[i]? = some '>' → ¬ EmitsAt (L ++ r.take i)) →
      ∃ st', run tr ⟨st, ls⟩ r = (⟨st', ls⟩, []) ∧ CInv st' (L ++ r)
  | [], st, L, h, _, _ => ⟨st, rfl, by simpa using h⟩
  | c :: r, st, L, h, hn, hgt => by
    have hc : c ≠ '\x00' := fun e => hn (e ▸ List.mem_cons_self ..)
    obtain ⟨s1, e1, h1⟩ := comment_step tr ls h hc (fun e => by simpa using hgt 0 (by simp [e]))
    obtain ⟨s2, e2, h2⟩ := comment_run tr ls r h1 (fun m => hn (List.mem_cons_of_mem _ m))
      (fun i hi => by simpa using hgt (i + 1) (by simpa using hi))
    exact ⟨s2, by simp [run, e1, e2], by simpa using h2⟩

/-- Reading `-->` from any comment state emits the data read so far. -/
theorem comment_close (tr : Tree) (ls : List Char) {st : St} {L : List Char} (h : CInv st L) :
    run tr ⟨st, ls⟩ ['-', '-', '>'] = (⟨.data, ls⟩, [.comment L]) := by
  cases h <;>
    simp [run, step, stepComment, stepCommentEnd, stepCommentEndDash, List.append_assoc]

/-- **Comments.** From the data state, `<!--` b `-->` is one Comment token with data b. -/
theorem run_comment (tr : Tree) (ls : List Char) {b : List Char} (hb : CommentBodyOK b) :
    run tr ⟨.data, ls⟩ (['<', '!', '-', '-'] ++ b ++ ['-', '-', '>']) =
      (⟨.data, ls⟩, [.comment b]) := by
  have e0 : run tr ⟨.data, ls⟩ ['<', '!', '-', '-'] = (⟨.commentStart [], ls⟩, []) := by
    simp [run, step, stepData, stepMarkupDecl]
  obtain ⟨st, e1, h1⟩ := comment_run tr ls b CInv.start hb.1 (by simpa using hb.2)
  rw [run_append, run_append, e0, e1, comment_close tr ls (by simpa using h1)]
  simp

end TesseraT1
