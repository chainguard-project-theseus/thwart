/-
The escaper postconditions T1 assumes (docs/context-model.md, section 6.1, the Proof column).

OpenJML proves these for `dev.tessera.runtime.Escapers` (OpenJML proofs kept on a fork,
github.com/tcnghia/harder, branch proofs). Here they are definitions only: T1 takes a hole's
value to satisfy the one for the hole's escaper as a hypothesis.

Each definition mirrors the JML model method of the same name in Escapers.java,
over a whole value `t` (the JML `t[from..to)` with `from = 0`, `to = t.length`).
Lean's `Char` is a Unicode scalar value, so `htmlPairedSurrogates` holds trivially
and is left out.
-/
namespace TesseraT1

/-- The empty comment `Escapers.BRACE_BREAK`. -/
def braceBreak : List Char := "<!---->".toList

/-- JML `htmlCommentAt(t, k, to)`: `BRACE_BREAK` starts at `t[k]`. -/
def htmlCommentAt (t : List Char) (k : Nat) : Prop :=
  (t.drop k).take 7 = braceBreak

/-- JML `htmlEntityAt(t, k, to)`: one of `&amp; &lt; &gt; &quot; &#39;` starts at `t[k]`. -/
def htmlEntityAt (t : List Char) (k : Nat) : Prop :=
  let u := t.drop k
  u.take 5 = "&amp;".toList ∨ u.take 4 = "&lt;".toList ∨ u.take 4 = "&gt;".toList ∨
    u.take 6 = "&quot;".toList ∨ u.take 5 = "&#39;".toList

/-- JML `htmlNoDelimiters`: no raw `< > " '` or NUL. -/
def htmlNoDelimiters (t : List Char) : Prop :=
  ∀ c ∈ t, c ≠ '<' ∧ c ≠ '>' ∧ c ≠ '"' ∧ c ≠ '\'' ∧ c ≠ '\x00'

/-- JML `htmlEntitiesOnly`: every `&` starts one of the five entities. -/
def htmlEntitiesOnly (t : List Char) : Prop :=
  ∀ k, t[k]? = some '&' → htmlEntityAt t k

/-- JML `htmlNoQuotes`: no raw `" '` or NUL. -/
def htmlNoQuotes (t : List Char) : Prop :=
  ∀ c ∈ t, c ≠ '"' ∧ c ≠ '\'' ∧ c ≠ '\x00'

/-- JML `htmlCommentsOnly`: every `<` and `>` belongs to a complete `<!---->`. -/
def htmlCommentsOnly (t : List Char) : Prop :=
  ∀ k, (t[k]? = some '<' → htmlCommentAt t k) ∧
       (t[k]? = some '>' → 6 ≤ k ∧ htmlCommentAt t (k - 6))

/-- JML `htmlNoDoubleBrace`: no two adjacent `{`. -/
def htmlNoDoubleBrace (t : List Char) : Prop :=
  ∀ k, ¬ (t[k]? = some '{' ∧ t[k + 1]? = some '{')

/-- JML `htmlSafe`: the property of `attr`, `rcdata`, `url`, `resourceUrl`, `refresh`. -/
def htmlSafe (t : List Char) : Prop :=
  htmlNoDelimiters t ∧ htmlEntitiesOnly t

/-- JML `htmlTextSafe`: the property of `Escapers.text` (OutputContext.TEXT). -/
def htmlTextSafe (t : List Char) : Prop :=
  htmlNoQuotes t ∧ htmlCommentsOnly t ∧ htmlEntitiesOnly t ∧ htmlNoDoubleBrace t ∧
    t.head? ≠ some '{' ∧ t.getLast? ≠ some '{'

/--
JML `jsonScriptSafe`: the property of `Escapers.json` / `jsonAttr` for a `<script>`.
None of `< > & ' ` + "`" + ` $`, U+2028, U+2029, a C0 control or DEL appears, so the
output cannot close `</script>`, open `<!--`, or escape a JS string, regex, template
literal or comment. (The precise statement comes from the escaper proof; this is the
character set it excludes.)
-/
def jsonScriptSafe (t : List Char) : Prop :=
  ∀ c ∈ t, c ≠ '<' ∧ c ≠ '>' ∧ c ≠ '&' ∧ c ≠ '\'' ∧ c ≠ '`' ∧ c ≠ '$' ∧
    c ≠ ' ' ∧ c ≠ ' ' ∧ c ≠ '\x7F' ∧ c.toNat ≥ 0x20

end TesseraT1
