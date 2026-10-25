/-
T1 for the whole profile (docs/security-architecture.md §5.1-5.5), on the template tree,
with the stateful tree builder of `Modes.lean`: HTML elements, raw-text and script
elements, `noscript` (scripting enabled), SVG and MathML with their integration points,
and `select`.

The statement (`t1_profile_tree`): for a template in the profile and hole values that
satisfy the escaper postconditions of the contexts the compiler chose, the tokenizer
driven by the profile's tree builder reads the rendered page as exactly the tokens the
template assigns (`denoteF`), ends in the data state with an empty stack, and so never
leaves the modeled fragment.
-/
import TesseraT1.Profile
import TesseraT1.T1Slice5
import TesseraT1.Modes

namespace TesseraT1
open Whatwg

/-! ## Rendering, values and Tessera's reading of a template tree -/

mutual
/-- The generator's output for a node, and the hole values left. -/
def renderN : Node → List (Option (List Char)) → List Char × List (Option (List Char))
  | .text s, vs => (s, vs)
  | .hole _, [] => ([], [])
  | .hole _, v :: vs => (v.getD [], vs)
  | .comment b, vs => ("<!--".toList ++ b ++ "-->".toList, vs)
  | .doctype d, vs => (d.render, vs)
  | .void t, vs => t.render vs
  | .element t ch, vs =>
    let r1 := t.render vs
    let r2 := renderF ch r1.2
    (r1.1 ++ r2.1 ++ ['<', '/'] ++ t.name ++ ['>'], r2.2)
def renderF : Forest → List (Option (List Char)) → List Char × List (Option (List Char))
  | .nil, vs => ([], vs)
  | .cons n r, vs =>
    let r1 := renderN n vs
    let r2 := renderF r r1.2
    (r1.1 ++ r2.1, r2.2)
end

mutual
/-- Each hole's value satisfies the postcondition of the escaper of its context. -/
def ValsN : Node → List (Option (List Char)) → Prop
  | .text _, _ => True
  | .hole .text, [] => True
  | .hole .text, v :: _ => ∀ x, v = some x → htmlTextSafe x
  | .hole .rcdata, [] => True
  | .hole .rcdata, v :: _ => ∀ x, v = some x → htmlSafe x
  | .hole .script, [] => False
  | .hole .script, v :: _ => ∃ x, v = some x ∧ jsonScriptSafe x ∧ JsonFirst x
  | .comment _, _ => True
  | .doctype _, _ => True
  | .void t, vs => t.ValsOK vs
  | .element t ch, vs => t.ValsOK vs ∧ ValsF ch (t.render vs).2
def ValsF : Forest → List (Option (List Char)) → Prop
  | .nil, _ => True
  | .cons n r, vs => ValsN n vs ∧ ValsF r (renderN n vs).2
end

/-- SVG HTML integration points (13.2.6, lowercased) and MathML text integration points. -/
def svgIntegration : List (List Char) := ["foreignobject", "desc", "title"].map String.toList
def mathTextIP : List (List Char) := ["mi", "mo", "mn", "ms", "mtext"].map String.toList

/-- HTML elements whose content a browser reads as raw text (scripting enabled). -/
def rawReadNames : List (List Char) := "script".toList :: "noscript".toList :: rawNames

/-- The parse mode of an element's content (§5.3), from the parent's mode and the
element's lowercased name. -/
def childMode : Mode → List Char → Mode
  | .html, n =>
    if n = "svg".toList then .svg else if n = "math".toList then .math
    else if n = "select".toList then .select else .html
  | .svg, n => if n ∈ svgIntegration then .html else .svg
  | .math, n => if n ∈ mathTextIP then .html else .math
  | .select, n => if n = "option".toList then .html else .select
  | .noscript, _ => .noscript

mutual
/-- Tessera's reading of a node as tokens, in a parse mode: an element is its StartTag,
its content (raw text as characters, markup recursively) and its EndTag. -/
def denoteN : Mode → Node → List (Option (List Char)) → List Token
  | _, .text s, _ => s.map .char
  | _, .hole _, [] => []
  | _, .hole .text, v :: _ => textTokens (v.getD [])
  | _, .hole _, v :: _ => (v.getD []).map .char
  | _, .comment b, _ => [.comment b]
  | _, .doctype _, _ => [.doctype (some "html".toList) false]
  | _, .void t, vs => [(t.toSyn vs).1.tessera]
  | m, .element t ch, vs =>
    (t.toSyn vs).1.tessera ::
      ((if m = .html ∧ asciiLower t.name ∈ rawReadNames then
          (renderF ch (t.render vs).2).1.map .char
        else denoteF (childMode m (asciiLower t.name)) ch (t.render vs).2) ++
        [.endTag (lowerStr t.name)])
def denoteF : Mode → Forest → List (Option (List Char)) → List Token
  | _, .nil, _ => []
  | m, .cons n r, vs => denoteN m n vs ++ denoteF m r (renderN n vs).2
end

/-! ## The profile's tree builder -/

inductive Ns where
  | html | svg | math
  deriving DecidableEq, Repr

/-- The open elements that matter to the tokenizer: namespace and lowercased name. -/
abbrev Stack := List (Ns × List Char)

/-- Start tags are processed by the rules for foreign content (13.2.6, the tree
construction dispatcher) when the current node is an SVG element other than an HTML
integration point, or a MathML element other than a text integration point. CHOICE:
`annotation-xml` counts as foreign (the profile gives it no HTML encoding). -/
def foreignDispatch : Stack → Bool
  | [] => false
  | (.html, _) :: _ => false
  | (.svg, n) :: _ => !svgIntegration.contains n
  | (.math, n) :: _ => !mathTextIP.contains n

/-- The tokenizer state after a start tag processed by the HTML rules: `treeStub`'s
table, except that `svg`, `math` and `select` stay in the data state (the stack now
tracks them) and a custom element (a name with `-`) is "any other start tag". -/
def htmlSwitch : List (List Char × St) :=
  [("script", .raw .script), ("title", .raw .rcdata), ("textarea", .raw .rcdata),
   ("style", .raw .rawtext), ("xmp", .raw .rawtext), ("iframe", .raw .rawtext),
   ("noembed", .raw .rawtext), ("noframes", .raw .rawtext), ("noscript", .raw .rawtext),
   ("plaintext", .out), ("frameset", .out), ("template", .out)].map fun p => (p.1.toList, p.2)

def htmlNext (n : List Char) : St :=
  if '-' ∈ n then .data else (List.lookup n htmlSwitch).getD .data

/-- The namespace of a new element: the current node's in foreign content; otherwise SVG
for `svg`, MathML for `math`, HTML for everything else. -/
def nsOf (σ : Stack) (n : List Char) : Ns :=
  if foreignDispatch σ then (σ.head?.map Prod.fst).getD .html
  else if n = "svg".toList then .svg else if n = "math".toList then .math else .html

/-- After a start tag: a void HTML element or a self-closing foreign element is popped at
once (13.2.6.4.7, 13.2.6.5); every other element is pushed. -/
def pushP (σ : Stack) (n : List Char) (sc : Bool) : Stack :=
  let ns := nsOf σ n
  if ns = .html then (if n ∈ voidNames.map String.toList then σ else (ns, n) :: σ)
  else (if sc then σ else (ns, n) :: σ)

/-- After an end tag: pop the current node when its name matches. The profile accepts
only balanced templates, so the browser's other cases are not modeled. -/
def popP (σ : Stack) (n : List Char) : Stack :=
  match σ with
  | (_, m) :: r => if m = n then r else σ
  | [] => []

def profileTB : TreeSM Stack :=
  ⟨fun σ n => if foreignDispatch σ then .data else htmlNext n, pushP, popP⟩

/-- The stack dispatches start tags as the mode requires. The `noscript` mode never
appears: `noscript` content is read as raw text (`run_noscript`). -/
def Dispatch (σ : Stack) : Mode → Prop
  | .html => foreignDispatch σ = false
  | .select => foreignDispatch σ = false
  | .svg => ∃ n r, σ = (.svg, n) :: r ∧ n ∉ svgIntegration
  | .math => ∃ n r, σ = (.math, n) :: r ∧ n ∉ mathTextIP
  | .noscript => False

theorem dispatch_html {σ : Stack} (h : Dispatch σ .html) : foreignDispatch σ = false := h
theorem dispatch_select {σ : Stack} (h : Dispatch σ .select) : foreignDispatch σ = false := h

/-! ## Facts about the tree builder -/

theorem tree_html {σ : Stack} (h : foreignDispatch σ = false) (n : List Char) :
    profileTB.tree σ n = htmlNext n := by
  simp [profileTB, TreeSM.tree, h]

theorem tree_foreign {σ : Stack} (h : foreignDispatch σ = true) (n : List Char) :
    profileTB.tree σ n = .data := by
  simp [profileTB, TreeSM.tree, h]

theorem dispatch_svg_foreign {σ : Stack} (h : Dispatch σ .svg) : foreignDispatch σ = true := by
  obtain ⟨n, r, rfl, hn⟩ := h
  simp [foreignDispatch, List.contains_eq_mem, hn]

theorem dispatch_math_foreign {σ : Stack} (h : Dispatch σ .math) : foreignDispatch σ = true := by
  obtain ⟨n, r, rfl, hn⟩ := h
  simp [foreignDispatch, List.contains_eq_mem, hn]

theorem html_switch_scope : (htmlNames.map String.toList).all (fun n =>
    rawReadNames.contains n || htmlNext n == .data) = true := by decide

theorem void_next : (voidNames.map String.toList).all (fun n => htmlNext n == .data) = true := by
  decide

theorem void_not_foreign : (voidNames.map String.toList).all
    (fun n => n != "svg".toList && n != "math".toList) = true := by decide

theorem htmlNext_data_of {n : List Char} (h : HtmlName n) (hr : asciiLower n ∉ rawReadNames) :
    htmlNext (asciiLower n) = .data := by
  rcases h.2 with hm | hd
  · have := List.all_eq_true.mp html_switch_scope _ hm
    simp only [Bool.or_eq_true, List.contains_eq_mem, decide_eq_true_eq, beq_iff_eq] at this
    rcases this with h | h
    · exact absurd h hr
    · exact h
  · simp [htmlNext, dash_lower hd]

theorem htmlNext_void {n : List Char} (h : IsVoid n) : htmlNext (asciiLower n) = .data := by
  have := List.all_eq_true.mp void_next _ h
  simpa using this

theorem htmlNext_raw {n : List Char} {body : List ScriptPart} (h : RawKindOK n body) :
    htmlNext n = .raw (rawKind n) := by
  rcases h with h | ⟨h, _⟩ | ⟨h, _⟩
  · simp only [rcdataNames, List.map_cons, List.map_nil, List.mem_cons, List.not_mem_nil,
      or_false] at h
    rcases h with rfl | rfl <;> decide
  · subst h; decide
  · subst h; decide

theorem nsOf_html {σ : Stack} (h : foreignDispatch σ = false) {n : List Char}
    (h1 : n ≠ "svg".toList) (h2 : n ≠ "math".toList) : nsOf σ n = .html := by
  simp only [nsOf, h, Bool.false_eq_true, ite_false, h1, h2]

/-- A non-void HTML element is pushed. -/
theorem pushP_html {σ : Stack} (h : foreignDispatch σ = false) {n : List Char}
    (h1 : n ≠ "svg".toList) (h2 : n ≠ "math".toList) (hv : n ∉ voidNames.map String.toList)
    (sc : Bool) : pushP σ n sc = (.html, n) :: σ := by
  simp only [pushP, nsOf_html h h1 h2, hv, eq_self_iff_true, ite_true, ite_false]

/-- A void HTML element is not pushed. -/
theorem pushP_void {σ : Stack} (h : foreignDispatch σ = false) {n : List Char}
    (hv : n ∈ voidNames.map String.toList) (sc : Bool) : pushP σ n sc = σ := by
  have h1 : n ≠ "svg".toList := by
    have := List.all_eq_true.mp void_not_foreign _ hv
    simp only [Bool.and_eq_true, bne_iff_ne, ne_eq] at this
    exact this.1
  have h2 : n ≠ "math".toList := by
    have := List.all_eq_true.mp void_not_foreign _ hv
    simp only [Bool.and_eq_true, bne_iff_ne, ne_eq] at this
    exact this.2
  simp only [pushP, nsOf_html h h1 h2, hv, eq_self_iff_true, ite_true]

/-- `svg` and `math` open their namespaces from HTML. -/
theorem pushP_svg {σ : Stack} (h : foreignDispatch σ = false) :
    pushP σ "svg".toList false = (.svg, "svg".toList) :: σ := by
  simp [pushP, nsOf, h]

theorem pushP_math {σ : Stack} (h : foreignDispatch σ = false) :
    pushP σ "math".toList false = (.math, "math".toList) :: σ := by
  simp [pushP, nsOf, h]

/-- In foreign content a start tag takes the current node's namespace. -/
theorem pushP_foreign {ns : Ns} (hns : ns ≠ .html) {m : List Char} {r : Stack}
    (h : foreignDispatch ((ns, m) :: r) = true) (n : List Char) :
    pushP ((ns, m) :: r) n false = (ns, n) :: (ns, m) :: r := by
  simp [pushP, nsOf, h, hns]

theorem pushP_foreign_sc {ns : Ns} (hns : ns ≠ .html) {m : List Char} {r : Stack}
    (h : foreignDispatch ((ns, m) :: r) = true) (n : List Char) :
    pushP ((ns, m) :: r) n true = (ns, m) :: r := by
  simp [pushP, nsOf, h, hns]

theorem popP_cons (ns : Ns) (n : List Char) (σ : Stack) : popP ((ns, n) :: σ) n = σ := by
  simp [popP]

theorem StartTag.toSyn_selfClosing (t : StartTag) (vs : List (Option (List Char))) (h : TagAttrsOK t) :
    (t.toSyn vs).1.selfClosing = t.selfClosing := by
  rcases hs : t.source with _ | src <;> cases ha : t.items.all TagItem.isAttr <;>
    simp only [StartTag.toSyn, SrcTag.toSyn, hs, ha]
  exact (h.2.2.2 src hs).2.2.2

/-- The stack after a start tag token Tessera assigns. -/
theorem apply_tessera (σ : Stack) (t : StartTag) (vs : List (Option (List Char))) (h : TagAttrsOK t) :
    profileTB.apply σ (t.toSyn vs).1.tessera = pushP σ (lowerStr t.name) t.selfClosing := by
  simp [TagSyn.tessera, TreeSM.apply, profileTB, StartTag.toSyn_name_of t vs h,
    StartTag.toSyn_selfClosing t vs h]

theorem applyAll_chars (σ : Stack) (cs : List Char) : profileTB.applyAll σ (cs.map .char) = σ :=
  applyAll_noTag _ _ _ (fun t ht => by
    obtain ⟨c, _, rfl⟩ := List.mem_map.mp ht
    rfl)

/-! ## Running one piece under the stateful tree builder -/

theorem runS_noTag (σ : Stack) {m : M} {s : List Char} {m' : M} {ts : List Token}
    (h : run (profileTB.tree σ) m s = (m', ts)) (hn : NoTag ts) :
    runS profileTB σ m s = ((m', σ), ts) := by
  have := runS_run_noStart profileTB s σ (profileTB.tree σ) m (by rw [h]; exact noTag_noStart hn)
  rw [h] at this
  simp only at this
  rw [this, applyAll_noTag _ _ _ hn]

theorem runS_oneTag (σ : Stack) {m : M} {s : List Char} {m' : M} {ts : List Token}
    (h : run (profileTB.tree σ) m s = (m', ts)) (ho : OneStart ts) :
    runS profileTB σ m s = ((m', profileTB.applyAll σ ts), ts) := by
  have := runS_run profileTB s σ m (by rw [h]; exact ho)
  rw [h] at this
  simpa using this

theorem noTag_chars (cs : List Char) : NoTag (cs.map .char) := fun t ht => by
  obtain ⟨c, _, rfl⟩ := List.mem_map.mp ht
  rfl

theorem noTag_textTokens (v : List Char) : NoTag (textTokens v) := fun t ht => by
  rcases textTokens_kinds v t ht with ⟨c, rfl⟩ | rfl <;> rfl

theorem oneStart_single (t : Token) : OneStart [t] := by
  simp [OneStart, NoStart]

theorem oneStart_elem (tok : Token) (cs : List Char) (n : List Char) :
    OneStart (tok :: (cs.map .char ++ [.endTag n])) := by
  simp only [OneStart]
  split
  · intro t ht
    simp only [List.mem_append, List.mem_singleton] at ht
    rcases ht with ht | rfl
    · obtain ⟨c, _, rfl⟩ := List.mem_map.mp ht
      rfl
    · rfl
  · -- `tok` is a tag in every use; this branch is unreachable but harmless to prove.
    induction cs with
    | nil => simp [OneStart, NoStart]
    | cons c cs ih => simpa [OneStart, Token.isTag] using ih

/-- A pushed element: its start tag, its content (returning to the stack it was read
with), and its end tag. -/
theorem runS_element (σ : Stack) (ls : List Char) {t : StartTag} {vs : List (Option (List Char))}
    (ns : Ns) (hname : (∃ c r, t.name = c :: r ∧ isAlpha c = true) ∧ ∀ c ∈ t.name, NameChar c)
    (hattrs : TagAttrsOK t) (hv : t.ValsOK vs)
    (hnext : profileTB.tree σ (lowerStr t.name) = .data)
    (hpush : pushP σ (lowerStr t.name) t.selfClosing = (ns, lowerStr t.name) :: σ)
    {body : List Char} {toks : List Token}
    (hbody : ∀ ls0, ∃ ls1, runS profileTB ((ns, lowerStr t.name) :: σ) ⟨.data, ls0⟩ body =
      ((⟨.data, ls1⟩, (ns, lowerStr t.name) :: σ), toks)) :
    ∃ ls', runS profileTB σ ⟨.data, ls⟩ ((t.render vs).1 ++ body ++ ['<', '/'] ++ t.name ++ ['>']) =
      ((⟨.data, ls'⟩, σ), (t.toSyn vs).1.tessera :: (toks ++ [.endTag (lowerStr t.name)])) := by
  obtain ⟨hr1, _⟩ := StartTag.toSyn_render t vs
  have e1 := run_startTag (profileTB.tree σ) ls (StartTag.toSyn_ok_of t vs hname hattrs hv)
  rw [StartTag.toSyn_name_of t vs hattrs, hnext, hr1,
    token_eq_tessera (StartTag.toSyn_nodup t vs hattrs)] at e1
  have s1 := runS_oneTag σ e1 (oneStart_single _)
  have happ : profileTB.applyAll σ [(t.toSyn vs).1.tessera] = (ns, lowerStr t.name) :: σ := by
    simp only [TreeSM.applyAll, List.foldl_cons, List.foldl_nil]
    rw [apply_tessera σ t vs hattrs, hpush]
  rw [happ] at s1
  obtain ⟨ls1, e2⟩ := hbody (lowerStr t.name)
  have e3 := run_endTag (profileTB.tree ((ns, lowerStr t.name) :: σ)) ls1 hname.1 hname.2
  have s3 := runS_oneTag _ e3 (oneStart_single _)
  have hpop : profileTB.applyAll ((ns, lowerStr t.name) :: σ) [.endTag (lowerStr t.name)] = σ := by
    simp [TreeSM.applyAll, TreeSM.apply, profileTB, popP]
  rw [hpop] at s3
  refine ⟨ls1, ?_⟩
  rw [show (t.render vs).1 ++ body ++ ['<', '/'] ++ t.name ++ ['>'] =
      (t.render vs).1 ++ (body ++ (['<', '/'] ++ t.name ++ ['>'])) by simp]
  rw [runS_append, s1]
  simp only
  rw [runS_append, e2]
  simp only
  rw [s3]
  simp

/-! ## Bridges for the element lemmas of slices 3-5 -/

theorem renderF_scriptBody : ∀ (F : Forest) (body : List ScriptPart) (vs : List (Option (List Char))),
    scriptBody F = some body → renderF F vs = renderScript body vs
  | .nil, body, vs, h => by
    simp only [scriptBody, Option.some.injEq] at h
    subst h; simp [renderF, renderScript]
  | .cons (.text s) r, body, vs, h => by
    simp only [scriptBody, Option.map_eq_some_iff] at h
    obtain ⟨b, hb, rfl⟩ := h
    simp only [renderF, renderN, renderScript, renderF_scriptBody r b vs hb]
  | .cons (.hole .script) r, body, [], h => by
    simp only [scriptBody, Option.map_eq_some_iff] at h
    obtain ⟨b, hb, rfl⟩ := h
    simp only [renderF, renderN, renderScript, renderF_scriptBody r b [] hb, List.nil_append, Prod.eta]
  | .cons (.hole .script) r, body, v :: vs, h => by
    simp only [scriptBody, Option.map_eq_some_iff] at h
    obtain ⟨b, hb, rfl⟩ := h
    simp only [renderF, renderN, renderScript, renderF_scriptBody r b vs hb]

  | .cons (.hole .text) _, _, _, h => by cases h
  | .cons (.hole .rcdata) _, _, _, h => by cases h
  | .cons (.comment _) _, _, _, h => by cases h
  | .cons (.doctype _) _, _, _, h => by cases h
  | .cons (.void _) _, _, _, h => by cases h
  | .cons (.element _ _) _, _, _, h => by cases h
theorem renderF_rawBody : ∀ (F : Forest) (body : List ScriptPart) (vs : List (Option (List Char))),
    rawBody F = some body → renderF F vs = renderScript body vs
  | .nil, body, vs, h => by
    simp only [rawBody, Option.some.injEq] at h
    subst h; simp [renderF, renderScript]
  | .cons (.text s) r, body, vs, h => by
    simp only [rawBody, Option.map_eq_some_iff] at h
    obtain ⟨b, hb, rfl⟩ := h
    simp only [renderF, renderN, renderScript, renderF_rawBody r b vs hb]
  | .cons (.hole .rcdata) r, body, [], h => by
    simp only [rawBody, Option.map_eq_some_iff] at h
    obtain ⟨b, hb, rfl⟩ := h
    simp only [renderF, renderN, renderScript, renderF_rawBody r b [] hb, List.nil_append, Prod.eta]
  | .cons (.hole .rcdata) r, body, v :: vs, h => by
    simp only [rawBody, Option.map_eq_some_iff] at h
    obtain ⟨b, hb, rfl⟩ := h
    simp only [renderF, renderN, renderScript, renderF_rawBody r b vs hb]

  | .cons (.hole .text) _, _, _, h => by cases h
  | .cons (.hole .script) _, _, _, h => by cases h
  | .cons (.comment _) _, _, _, h => by cases h
  | .cons (.doctype _) _, _, _, h => by cases h
  | .cons (.void _) _, _, _, h => by cases h
  | .cons (.element _ _) _, _, _, h => by cases h
theorem scriptVals_of : ∀ (F : Forest) (body : List ScriptPart) (vs : List (Option (List Char))),
    scriptBody F = some body → ValsF F vs → ScriptValsOK body vs
  | .nil, body, vs, h, _ => by
    simp only [scriptBody, Option.some.injEq] at h
    subst h; trivial
  | .cons (.text s) r, body, vs, h, ⟨_, hv⟩ => by
    simp only [scriptBody, Option.map_eq_some_iff] at h
    obtain ⟨b, hb, rfl⟩ := h
    exact scriptVals_of r b vs hb (by simpa [renderN] using hv)
  | .cons (.hole .script) r, body, [], h, ⟨hx, _⟩ => hx.elim
  | .cons (.hole .script) r, body, v :: vs, h, ⟨hx, hv⟩ => by
    simp only [scriptBody, Option.map_eq_some_iff] at h
    obtain ⟨b, hb, rfl⟩ := h
    exact ⟨hx, scriptVals_of r b vs hb (by simpa [renderN] using hv)⟩
  | .cons (.hole .text) _, _, _, h, _ => by cases h
  | .cons (.hole .rcdata) _, _, _, h, _ => by cases h
  | .cons (.comment _) _, _, _, h, _ => by cases h
  | .cons (.doctype _) _, _, _, h, _ => by cases h
  | .cons (.void _) _, _, _, h, _ => by cases h
  | .cons (.element _ _) _, _, _, h, _ => by cases h

theorem rawVals_of : ∀ (F : Forest) (body : List ScriptPart) (vs : List (Option (List Char))),
    rawBody F = some body → ValsF F vs → RawValsOK body vs
  | .nil, body, vs, h, _ => by
    simp only [rawBody, Option.some.injEq] at h
    subst h; trivial
  | .cons (.text s) r, body, vs, h, ⟨_, hv⟩ => by
    simp only [rawBody, Option.map_eq_some_iff] at h
    obtain ⟨b, hb, rfl⟩ := h
    exact rawVals_of r b vs hb (by simpa [renderN] using hv)
  | .cons (.hole .rcdata) r, body, [], h, ⟨_, hv⟩ => by
    simp only [rawBody, Option.map_eq_some_iff] at h
    obtain ⟨b, hb, rfl⟩ := h
    exact rawVals_of r b [] hb (by simpa [renderN] using hv)
  | .cons (.hole .rcdata) r, body, v :: vs, h, ⟨hx, hv⟩ => by
    simp only [rawBody, Option.map_eq_some_iff] at h
    obtain ⟨b, hb, rfl⟩ := h
    exact ⟨hx, rawVals_of r b vs hb (by simpa [renderN] using hv)⟩
  | .cons (.hole .text) _, _, _, h, _ => by cases h
  | .cons (.hole .script) _, _, _, h, _ => by cases h
  | .cons (.comment _) _, _, _, h, _ => by cases h
  | .cons (.doctype _) _, _, _, h, _ => by cases h
  | .cons (.void _) _, _, _, h, _ => by cases h
  | .cons (.element _ _) _, _, _, h, _ => by cases h

/-! ### `noscript` content as slice-4 pieces -/

theorem render4_append : ∀ (a b : List Piece4) (vs : List (Option (List Char))),
    render4 (a ++ b) vs = render4 a vs ++ render4 b (rest4 a vs) ∧
    rest4 (a ++ b) vs = rest4 b (rest4 a vs)
  | [], b, vs => by simp [render4, rest4]
  | .static s :: a, b, vs => by
    simp only [List.cons_append, render4, rest4, render4_append a b vs, List.append_assoc, and_self]
  | .holeText :: a, b, [] => by
    simp only [List.cons_append, render4, rest4]; exact render4_append a b []
  | .holeText :: a, b, v :: vs => by
    simp only [List.cons_append, render4, rest4, render4_append a b vs, List.append_assoc, and_self]
  | .startTag t :: a, b, vs => by
    simp only [List.cons_append, render4, rest4, render4_append a b _, List.append_assoc, and_self]
  | .endTag n :: a, b, vs => by
    simp only [List.cons_append, render4, rest4, render4_append a b vs, List.append_assoc, and_self]
  | .comment c :: a, b, vs => by
    simp only [List.cons_append, render4, rest4, render4_append a b vs, List.append_assoc, and_self]
  | .doctype d :: a, b, vs => by
    simp only [List.cons_append, render4, rest4, render4_append a b vs, List.append_assoc, and_self]
  | .script e :: a, b, vs => by
    simp only [List.cons_append, render4, rest4, render4_append a b _, List.append_assoc, and_self]
  | .raw e :: a, b, vs => by
    simp only [List.cons_append, render4, rest4, render4_append a b _, List.append_assoc, and_self]

theorem valsOK4_append : ∀ (a b : List Piece4) (vs : List (Option (List Char))),
    ValsOK4 a vs → ValsOK4 b (rest4 a vs) → ValsOK4 (a ++ b) vs
  | [], b, vs, _, hb => hb
  | .static s :: a, b, vs, ha, hb => valsOK4_append a b vs ha hb
  | .holeText :: a, b, [], ha, hb => valsOK4_append a b [] ha hb
  | .holeText :: a, b, v :: vs, ⟨hx, ha⟩, hb => ⟨hx, valsOK4_append a b vs ha hb⟩
  | .startTag t :: a, b, vs, ⟨hx, ha⟩, hb => ⟨hx, valsOK4_append a b _ ha hb⟩
  | .endTag n :: a, b, vs, ha, hb => valsOK4_append a b vs ha hb
  | .comment c :: a, b, vs, ha, hb => valsOK4_append a b vs ha hb
  | .doctype d :: a, b, vs, ha, hb => valsOK4_append a b vs ha hb
  | .script e :: a, b, vs, ⟨h1, h2, ha⟩, hb => ⟨h1, h2, valsOK4_append a b _ ha hb⟩
  | .raw e :: a, b, vs, ⟨h1, h2, ha⟩, hb => ⟨h1, h2, valsOK4_append a b _ ha hb⟩

theorem noscriptBodyOK_append : ∀ (a b : List Piece4), NoscriptBodyOK a → NoscriptBodyOK b →
    NoscriptBodyOK (a ++ b)
  | [], _, _, hb => hb
  | .static s :: a, b, ⟨h1, h2, ha⟩, hb => ⟨h1, h2, noscriptBodyOK_append a b ha hb⟩
  | .holeText :: a, b, ha, hb => noscriptBodyOK_append a b ha hb
  | .startTag t :: a, b, ⟨h1, ha⟩, hb => ⟨h1, noscriptBodyOK_append a b ha hb⟩
  | .endTag n :: a, b, ⟨h1, h2, ha⟩, hb => ⟨h1, h2, noscriptBodyOK_append a b ha hb⟩
  | .comment c :: a, b, ⟨h1, h2, ha⟩, hb => ⟨h1, h2, noscriptBodyOK_append a b ha hb⟩
  | .doctype d :: a, b, ha, _ => ha.elim
  | .script e :: a, b, ha, _ => ha.elim
  | .raw e :: a, b, ⟨h1, h2, h3, ha⟩, hb => by
    exact ⟨h1, h2, h3, noscriptBodyOK_append a b ha hb⟩

theorem commentTessera_nul {b : List Char} (h : CommentTessera b) : '\x00' ∉ b := h.2.2.2.2

/-- In `noscript` mode an element is neither raw text nor script, so `flatten` writes its
start tag, its content and its end tag. -/
theorem flatten_noscript_element {t : StartTag} {ch : Forest}
    (h : asciiLower t.name ∉ "script".toList :: "noscript".toList :: rawNames) :
    flatten (.element t ch) = .startTag t :: flattenF ch ++ [.endTag t.name] := by
  have h1 : asciiLower t.name ≠ "script".toList := fun e => h (by simp [e])
  have h2 : asciiLower t.name ∉ rawNames := fun e => h (by simp [e])
  simp only [flatten, h1, h2, ite_false]

mutual
theorem noscript_flatten : ∀ (n : Node) (vs : List (Option (List Char))), NodeOK .noscript n → ValsN n vs →
    renderN n vs = (render4 (flatten n) vs, rest4 (flatten n) vs) ∧
    NoscriptBodyOK (flatten n) ∧ ValsOK4 (flatten n) vs
  | .text s, vs, h, _ => ⟨by simp [renderN, flatten, render4, rest4], ⟨h.1, h.2, trivial⟩, trivial⟩
  | .hole ctx, [], h, _ => by
    simp only [NodeOK] at h; subst h
    exact ⟨by simp [renderN, flatten, render4, rest4], trivial, trivial⟩
  | .hole ctx, v :: vs, h, hv => by
    simp only [NodeOK] at h; subst h
    exact ⟨by simp [renderN, flatten, render4, rest4], trivial, ⟨hv, trivial⟩⟩
  | .comment b, vs, ⟨hb, hl⟩, _ => by
    refine ⟨by simp [renderN, flatten, render4, rest4], ⟨hl, commentTessera_nul hb, trivial⟩, trivial⟩
  | .doctype d, vs, h, _ => h.elim
  | .void t, vs, ⟨ht, _, _⟩, hv => ⟨by simp [renderN, flatten, render4, rest4], ⟨ht, trivial⟩, ⟨hv, trivial⟩⟩
  | .element t ch, vs, ⟨ht, hsc, hn, _, hrest⟩, ⟨hvt, hvc⟩ => by
    rcases hrest with ⟨hx, hc⟩ | ⟨hi, rfl⟩
    · obtain ⟨e, hb, hv⟩ := noscriptF_flatten ch (t.render vs).2 hc hvc
      rw [flatten_noscript_element hx]
      refine ⟨?_, ⟨ht, noscriptBodyOK_append _ _ hb ⟨hn.1, fun e => hx (by rw [e, ← noscript_toList]; simp), trivial⟩⟩,
        ⟨hvt, valsOK4_append _ _ _ hv (by simp [ValsOK4])⟩⟩
      simp only [renderN, render4, rest4, (render4_append _ _ _).1, (render4_append _ _ _).2, e]
      simp [render4, rest4]
    · have hs : asciiLower t.name ≠ "script".toList := by rw [hi]; decide
      have hr : asciiLower t.name ∈ rawNames := by rw [hi]; decide
      simp only [flatten, hs, hr, ite_false, ite_true, rawBody, Option.getD_some]
      refine ⟨?_, ⟨ht, hi, rfl, trivial⟩, ⟨hvt, trivial, trivial⟩⟩
      simp [renderN, renderF, render4, rest4, RawEl.render, renderScript]
theorem noscriptF_flatten : ∀ (F : Forest) (vs : List (Option (List Char))), ForestOK .noscript F → ValsF F vs →
    renderF F vs = (render4 (flattenF F) vs, rest4 (flattenF F) vs) ∧
    NoscriptBodyOK (flattenF F) ∧ ValsOK4 (flattenF F) vs
  | .nil, vs, _, _ => ⟨by simp [renderF, flattenF, render4, rest4], trivial, trivial⟩
  | .cons n r, vs, ⟨hn, _, hr⟩, ⟨hvn, hvr⟩ => by
    obtain ⟨e1, b1, v1⟩ := noscript_flatten n vs hn hvn
    obtain ⟨e2, b2, v2⟩ := noscriptF_flatten r _ hr hvr
    refine ⟨?_, noscriptBodyOK_append _ _ b1 b2, valsOK4_append _ _ _ v1 (by rw [e1] at v2; simpa using v2)⟩
    simp only [renderF, flattenF, (render4_append _ _ _).1, (render4_append _ _ _).2, e1]
    rw [e1] at e2
    simp only at e2
    rw [e2]
end

/-! ## Text-only content is HTML-mode content -/

theorem textOnly_forestOK : ∀ (F : Forest), TextOnly F → ForestOK .html F
  | .nil, _ => trivial
  | .cons n r, ⟨hn, h2, hr⟩ => by
    refine ⟨?_, h2, textOnly_forestOK r hr⟩
    cases n with
    | text s => exact hn
    | hole ctx => cases ctx with
      | text => rfl
      | rcdata => exact hn.elim
      | script => exact hn.elim
    | comment _ => exact hn.elim
    | doctype _ => exact hn.elim
    | void _ => exact hn.elim
    | element _ _ => exact hn.elim

theorem staticNoAmp_forestOK : ∀ (F : Forest) (m : Mode), StaticNoAmp F → ForestOK m F
  | .nil, _, _ => trivial
  | .cons (.text s) r, m, ⟨h1, h2, hr⟩ => ⟨by cases m <;> exact h1.1, h2, staticNoAmp_forestOK r m hr⟩
  | .cons (.hole _) _, _, ⟨h, _, _⟩ => h.elim
  | .cons (.comment _) _, _, ⟨h, _, _⟩ => h.elim
  | .cons (.doctype _) _, _, ⟨h, _, _⟩ => h.elim
  | .cons (.void _) _, _, ⟨h, _, _⟩ => h.elim
  | .cons (.element _ _) _, _, ⟨h, _, _⟩ => h.elim

/-! ## T1 on the tree -/

theorem nodeOK_text {m : Mode} {s : List Char} (h : NodeOK m (.text s)) : TextOK s := by
  cases m <;> exact h

theorem nodeOK_hole {m : Mode} {ctx : HoleCtx} (h : NodeOK m (.hole ctx)) : ctx = .text := by
  cases m <;> exact h

theorem nodeOK_comment {m : Mode} {b : List Char} (h : NodeOK m (.comment b)) : CommentOK b := by
  cases m <;> first | exact h | exact h.1

theorem nodeOK_doctype {m : Mode} {d : Doctype} (h : NodeOK m (.doctype d)) : False := by
  cases m <;> exact h

theorem denoteN_text (m : Mode) (s : List Char) (vs : List (Option (List Char))) :
    denoteN m (.text s) vs = s.map .char := by
  cases m <;> rfl

theorem denoteN_hole_nil (m : Mode) (ctx : HoleCtx) : denoteN m (.hole ctx) [] = [] := by
  cases m <;> rfl

theorem denoteN_hole_text (m : Mode) (v : Option (List Char)) (vs : List (Option (List Char))) :
    denoteN m (.hole .text) (v :: vs) = textTokens (v.getD []) := by
  cases m <;> rfl

theorem denoteN_comment (m : Mode) (b : List Char) (vs : List (Option (List Char))) :
    denoteN m (.comment b) vs = [.comment b] := by
  cases m <;> rfl

theorem denoteN_void (m : Mode) (t : StartTag) (vs : List (Option (List Char))) :
    denoteN m (.void t) vs = [(t.toSyn vs).1.tessera] := by
  cases m <;> rfl

theorem denoteN_element (m : Mode) (t : StartTag) (ch : Forest) (vs : List (Option (List Char))) :
    denoteN m (.element t ch) vs =
      (t.toSyn vs).1.tessera ::
        ((if m = .html ∧ asciiLower t.name ∈ rawReadNames then
            (renderF ch (t.render vs).2).1.map .char
          else denoteF (childMode m (asciiLower t.name)) ch (t.render vs).2) ++
          [.endTag (lowerStr t.name)]) := by
  cases m <;> rfl

theorem renderN_hole_nil (c : HoleCtx) : renderN (.hole c) [] = ([], []) := rfl

theorem applyAll_elem (σ : Stack) (tok : Token) (cs : List Char) (n : List Char) :
    profileTB.applyAll σ (tok :: (cs.map .char ++ [.endTag n])) = popP (profileTB.apply σ tok) n := by
  simp only [TreeSM.applyAll, List.foldl_cons, List.foldl_append]
  rw [show List.foldl profileTB.apply (profileTB.apply σ tok) (cs.map .char) = profileTB.apply σ tok from
    applyAll_chars _ _]
  rfl

/-- The name facts `TagSyn.OK` needs, from a valid element name. -/
theorem nameFacts {n : List Char} (h : validElementName n = true) :
    (∃ c r, n = c :: r ∧ isAlpha c = true) ∧ ∀ c ∈ n, NameChar c :=
  validElementName_syn h

theorem raw_names_html : rawNames.all (fun n => n != "svg".toList && n != "math".toList &&
    !(voidNames.map String.toList).contains n) = true := by decide

/-- A void element's start tag, read in place. -/
theorem runS_voidTag (σ : Stack) (ls : List Char) {t : StartTag} {vs : List (Option (List Char))}
    (hname : validElementName t.name = true) (hattrs : TagAttrsOK t) (hv : t.ValsOK vs)
    (hnext : profileTB.tree σ (lowerStr t.name) = .data)
    (hpush : pushP σ (lowerStr t.name) t.selfClosing = σ) :
    runS profileTB σ ⟨.data, ls⟩ (t.render vs).1 =
      ((⟨.data, lowerStr t.name⟩, σ), [(t.toSyn vs).1.tessera]) := by
  obtain ⟨hr1, _⟩ := StartTag.toSyn_render t vs
  have e1 := run_startTag (profileTB.tree σ) ls (StartTag.toSyn_ok_of t vs (nameFacts hname) hattrs hv)
  rw [StartTag.toSyn_name_of t vs hattrs, hnext, hr1,
    token_eq_tessera (StartTag.toSyn_nodup t vs hattrs)] at e1
  have s1 := runS_oneTag σ e1 (oneStart_single _)
  rw [show profileTB.applyAll σ [(t.toSyn vs).1.tessera] = σ by
    simp only [TreeSM.applyAll, List.foldl_cons, List.foldl_nil]
    rw [apply_tessera σ t vs hattrs, hpush]] at s1
  exact s1

/-- A raw-text, script or `noscript` element: start tag, characters, end tag. -/
theorem runS_rawLike (σ : Stack) {n : List Char} (hpush : pushP σ n false = (.html, n) :: σ)
    {s : List Char} {ls : List Char} {tok : Token} {cs : List Char}
    (hrun : run (profileTB.tree σ) ⟨.data, ls⟩ s = (⟨.data, n⟩, tok :: (cs.map .char ++ [.endTag n])))
    (htok : profileTB.apply σ tok = pushP σ n false) :
    runS profileTB σ ⟨.data, ls⟩ s = ((⟨.data, n⟩, σ), tok :: (cs.map .char ++ [.endTag n])) := by
  have := runS_oneTag σ hrun (oneStart_elem tok cs n)
  rw [applyAll_elem, htok, hpush, popP_cons] at this
  exact this

theorem foreignDispatch_cons_html (n : List Char) (σ : Stack) :
    foreignDispatch ((.html, n) :: σ) = false := rfl

theorem foreignDispatch_svg (n : List Char) (σ : Stack) (h : n ∈ svgIntegration) :
    foreignDispatch ((.svg, n) :: σ) = false := by
  simp [foreignDispatch, List.contains_eq_mem, h]

theorem foreignDispatch_math (n : List Char) (σ : Stack) (h : n ∈ mathTextIP) :
    foreignDispatch ((.math, n) :: σ) = false := by
  simp [foreignDispatch, List.contains_eq_mem, h]

mutual
theorem t1N : ∀ (n : Node) (m : Mode) (σ : Stack) (vs : List (Option (List Char))) (ls : List Char),
    NodeOK m n → ValsN n vs → Dispatch σ m →
    ∃ ls', runS profileTB σ ⟨.data, ls⟩ (renderN n vs).1 = ((⟨.data, ls'⟩, σ), denoteN m n vs)
  | .text s, m, σ, vs, ls, h, _, _ => by
    have ht := nodeOK_text h
    refine ⟨ls, ?_⟩
    rw [denoteN_text]
    exact runS_noTag σ (run_data_plain (profileTB.tree σ) ls (fun c hc e => ht.1 (e ▸ hc))) (noTag_chars s)
  | .hole ctx, m, σ, [], ls, h, _, _ => by
    have hc := nodeOK_hole h
    subst hc
    refine ⟨ls, ?_⟩
    rw [denoteN_hole_nil, renderN_hole_nil]
    rfl
  | .hole ctx, m, σ, v :: vs, ls, h, hv, _ => by
    have hc := nodeOK_hole h
    subst hc
    refine ⟨ls, ?_⟩
    rw [denoteN_hole_text]
    cases v with
    | none => rfl
    | some x =>
      have hx : htmlTextSafe x := hv x rfl
      simp only [renderN, Option.getD_some]
      exact runS_noTag σ (run_textChunks (profileTB.tree σ) ls (htmlTextSafe_chunks hx)) (noTag_textTokens x)
  | .comment b, m, σ, vs, ls, h, _, _ => by
    have hb := nodeOK_comment h
    refine ⟨ls, ?_⟩
    rw [denoteN_comment]
    have e : "<!--".toList ++ b ++ "-->".toList = ['<', '!', '-', '-'] ++ b ++ ['-', '-', '>'] := rfl
    simp only [renderN, e]
    exact runS_noTag σ (run_comment (profileTB.tree σ) ls (commentBodyOK_of_tessera hb))
      (fun t ht => by simp at ht; subst ht; rfl)
  | .doctype d, m, σ, vs, ls, h, _, _ => (nodeOK_doctype h).elim
  | .void t, .html, σ, vs, ls, ⟨ht, hvd, hn⟩, hv, hd => by
    refine ⟨lowerStr t.name, ?_⟩
    rw [denoteN_void]
    simp only [renderN]
    exact runS_voidTag σ ls hn.1 ht hv (by rw [tree_html (dispatch_html hd), lowerStr_eq_asciiLower]; exact htmlNext_void hvd)
      (by rw [lowerStr_eq_asciiLower]; exact pushP_void (dispatch_html hd) hvd _)
  | .void t, .select, σ, vs, ls, ⟨ht, hn, hhr⟩, hv, hd => by
    have hvd : IsVoid t.name := by unfold IsVoid; rw [hhr]; decide
    refine ⟨lowerStr t.name, ?_⟩
    rw [denoteN_void]
    simp only [renderN]
    exact runS_voidTag σ ls hn.1 ht hv (by rw [tree_html (dispatch_select hd), lowerStr_eq_asciiLower]; exact htmlNext_void hvd)
      (by rw [lowerStr_eq_asciiLower]; exact pushP_void (dispatch_select hd) hvd _)
  | .void t, .svg, σ, vs, ls, ⟨ht, hsc, hn⟩, hv, hd => by
    obtain ⟨n0, r0, rfl, hn0⟩ := hd
    have hf : foreignDispatch ((.svg, n0) :: r0) = true := dispatch_svg_foreign ⟨n0, r0, rfl, hn0⟩
    refine ⟨lowerStr t.name, ?_⟩
    rw [denoteN_void]
    simp only [renderN]
    exact runS_voidTag _ ls hn.1 ht hv (tree_foreign hf _) (by rw [hsc]; exact pushP_foreign_sc (by decide) hf _)
  | .void t, .math, σ, vs, ls, ⟨ht, hsc, hn⟩, hv, hd => by
    obtain ⟨n0, r0, rfl, hn0⟩ := hd
    have hf : foreignDispatch ((.math, n0) :: r0) = true := dispatch_math_foreign ⟨n0, r0, rfl, hn0⟩
    refine ⟨lowerStr t.name, ?_⟩
    rw [denoteN_void]
    simp only [renderN]
    exact runS_voidTag _ ls hn.1 ht hv (tree_foreign hf _) (by rw [hsc]; exact pushP_foreign_sc (by decide) hf _)
  | .void t, .noscript, σ, vs, ls, _, _, hd => hd.elim
  | .element t ch, .noscript, σ, vs, ls, _, _, hd => hd.elim
  | .element t ch, .html, σ, vs, ls, ⟨ht, hsc, hn, hnv, hc⟩, ⟨hvt, hvc⟩, hd => by
    have hl : lowerStr t.name = asciiLower t.name := lowerStr_eq_asciiLower _
    have hd' := dispatch_html hd
    rw [denoteN_element]
    by_cases hs : asciiLower t.name = "script".toList
    · -- `<script>`: slice 3
      rw [if_pos hs] at hc
      obtain ⟨body, hb, hok⟩ := hc
      have hmem : asciiLower t.name ∈ rawReadNames := by rw [hs]; decide
      rw [if_pos ⟨rfl, hmem⟩]
      have er : renderN (.element t ch) vs = ScriptEl.render ⟨t, body⟩ vs := by
        simp only [renderN, ScriptEl.render, renderF_scriptBody ch body _ hb]
      have hsn : lowerStr t.name = scriptName := by rw [hl, hs]; try rfl
      refine ⟨scriptName, ?_⟩
      rw [er, renderF_scriptBody ch body _ hb, hsn]
      refine runS_rawLike σ (n := scriptName) (pushP_html hd' (by decide) (by decide) (by decide) false) ?_ ?_
      · exact run_script (profileTB.tree σ) (by rw [tree_html hd']; decide) ls hok hvt
          (scriptVals_of ch body _ hb hvc)
      · rw [apply_tessera σ t vs ht, hsn, hsc]
    by_cases hr : asciiLower t.name ∈ rawNames
    · -- `title`, `textarea`, `style`, `iframe`: slice 4
      rw [if_neg hs, if_pos hr] at hc
      obtain ⟨body, hb, hok⟩ := hc
      have hmem : asciiLower t.name ∈ rawReadNames := by
        simp only [rawReadNames, List.mem_cons]; exact Or.inr (Or.inr hr)
      rw [if_pos ⟨rfl, hmem⟩]
      have er : renderN (.element t ch) vs = RawEl.render ⟨t, body⟩ vs := by
        simp only [renderN, RawEl.render, renderF_rawBody ch body _ hb]
      have hfacts := List.all_eq_true.mp raw_names_html _ hr
      simp only [Bool.and_eq_true, bne_iff_ne, ne_eq, Bool.not_eq_true', List.contains_eq_mem,
        decide_eq_false_iff_not] at hfacts
      refine ⟨lowerStr t.name, ?_⟩
      rw [er, renderF_rawBody ch body _ hb]
      refine runS_rawLike σ (n := lowerStr t.name)
        (hl ▸ pushP_html hd' hfacts.1.1 hfacts.1.2 hfacts.2 false) ?_ ?_
      · exact run_rawEl (profileTB.tree σ) ls hok
          (by rw [tree_html hd', hl]; exact htmlNext_raw hok.1) hvt (rawVals_of ch body _ hb hvc)
      · rw [apply_tessera σ t vs ht, hsc]
    rw [if_neg hs, if_neg hr] at hc
    by_cases hns : asciiLower t.name = "noscript".toList
    · -- `<noscript>`: slice 5, read as raw text with scripting enabled
      have h1 : asciiLower t.name ≠ "svg".toList := by rw [hns]; decide
      have h2 : asciiLower t.name ≠ "math".toList := by rw [hns]; decide
      have h3 : asciiLower t.name ≠ "select".toList := by rw [hns]; decide
      rw [if_neg h1, if_neg h2, if_neg h3, if_pos hns] at hc
      have hmem : asciiLower t.name ∈ rawReadNames := by rw [hns]; decide
      rw [if_pos ⟨rfl, hmem⟩]
      obtain ⟨e, hb, hv⟩ := noscriptF_flatten ch (t.render vs).2 hc hvc
      have hsn : lowerStr t.name = noscriptName := by rw [hl, hns]; try rfl
      have er : renderN (.element t ch) vs = NoscriptEl.render ⟨t, flattenF ch⟩ vs := by
        simp only [renderN, NoscriptEl.render, e]
      refine ⟨noscriptName, ?_⟩
      rw [er, hsn]
      have e1 : (renderF ch (t.render vs).2).1 = render4 (flattenF ch) (t.render vs).2 := by rw [e]
      rw [e1]
      refine runS_rawLike σ (n := noscriptName)
        (pushP_html hd' (by decide) (by decide) (by decide) false) ?_ ?_
      · exact run_noscript (profileTB.tree σ) (by rw [tree_html hd']; decide) ls ⟨hns, hsc, ht, hb⟩ hvt hv
      · rw [apply_tessera σ t vs ht, hsn, hsc]
    -- an ordinary HTML element, or `svg`, `math`, `select`: children in their mode
    have hnr : asciiLower t.name ∉ rawReadNames := by
      simp only [rawReadNames, List.mem_cons, not_or]
      exact ⟨hs, hns, hr⟩
    rw [if_neg (fun h => hnr h.2)]
    have hnext : profileTB.tree σ (lowerStr t.name) = .data := by
      rw [tree_html hd', hl]; exact htmlNext_data_of hn hnr
    simp only [renderN]
    by_cases hsvg : asciiLower t.name = "svg".toList
    · rw [if_pos hsvg] at hc
      have hcm : childMode .html (asciiLower t.name) = .svg := by rw [hsvg]; decide
      rw [hcm]
      exact runS_element σ ls .svg (nameFacts hn.1) ht hvt hnext
        (by simp only [hl, hsvg, hsc]; exact pushP_svg hd')
        (fun ls0 => t1F ch .svg ((.svg, lowerStr t.name) :: σ) (t.render vs).2 ls0 hc hvc
          ⟨lowerStr t.name, σ, rfl, by rw [hl, hsvg]; decide⟩)
    by_cases hmath : asciiLower t.name = "math".toList
    · rw [if_neg hsvg, if_pos hmath] at hc
      have hcm : childMode .html (asciiLower t.name) = .math := by rw [hmath]; decide
      rw [hcm]
      exact runS_element σ ls .math (nameFacts hn.1) ht hvt hnext
        (by simp only [hl, hmath, hsc]; exact pushP_math hd')
        (fun ls0 => t1F ch .math ((.math, lowerStr t.name) :: σ) (t.render vs).2 ls0 hc hvc
          ⟨lowerStr t.name, σ, rfl, by rw [hl, hmath]; decide⟩)
    have hvl : asciiLower t.name ∉ voidNames.map String.toList := hnv
    by_cases hsel : asciiLower t.name = "select".toList
    · rw [if_neg hsvg, if_neg hmath, if_pos hsel] at hc
      have hcm : childMode .html (asciiLower t.name) = .select := by rw [hsel]; decide
      rw [hcm]
      exact runS_element σ ls .html (nameFacts hn.1) ht hvt hnext
        (by simp only [hl, hsc]; exact pushP_html hd' hsvg hmath hvl false)
        (fun ls0 => t1F ch .select ((.html, lowerStr t.name) :: σ) (t.render vs).2 ls0 hc hvc rfl)
    · rw [if_neg hsvg, if_neg hmath, if_neg hsel, if_neg hns] at hc
      have hcm : childMode .html (asciiLower t.name) = .html := by
        simp only [childMode, hsvg, hmath, hsel, ite_false]
      rw [hcm]
      exact runS_element σ ls .html (nameFacts hn.1) ht hvt hnext
        (by simp only [hl, hsc]; exact pushP_html hd' hsvg hmath hvl false)
        (fun ls0 => t1F ch .html ((.html, lowerStr t.name) :: σ) (t.render vs).2 ls0 hc hvc rfl)
  | .element t ch, .svg, σ, vs, ls, ⟨ht, hsc, hn, hc⟩, ⟨hvt, hvc⟩, hd => by
    obtain ⟨n0, r0, rfl, hn0⟩ := hd
    have hf : foreignDispatch ((.svg, n0) :: r0) = true := dispatch_svg_foreign ⟨n0, r0, rfl, hn0⟩
    have hl : lowerStr t.name = asciiLower t.name := lowerStr_eq_asciiLower _
    rw [denoteN_element, if_neg (fun h => nomatch h.1)]
    simp only [renderN]
    have hpush : pushP ((.svg, n0) :: r0) (lowerStr t.name) t.selfClosing =
        (.svg, lowerStr t.name) :: (.svg, n0) :: r0 := by
      rw [hsc]; exact pushP_foreign (by decide) hf _
    have hnext := tree_foreign hf (lowerStr t.name)
    by_cases hip : asciiLower t.name ∈ svgIntegration
    · have hcm : childMode .svg (asciiLower t.name) = .html := by
        simp only [childMode, hip, ite_true]
      rw [hcm]
      have hdisp : Dispatch ((.svg, lowerStr t.name) :: (.svg, n0) :: r0) .html := by
        show foreignDispatch _ = false
        rw [hl]; exact foreignDispatch_svg _ _ hip
      have hch : ForestOK .html ch := by
        simp only [svgIntegration, List.map_cons, List.map_nil, List.mem_cons, List.not_mem_nil,
          or_false] at hip
        rcases hip with h | h | h
        · have c1 : ¬ (asciiLower t.name = "title".toList ∨ asciiLower t.name = "desc".toList) := by
            rw [h]; decide
          have c2 : ¬ (asciiLower t.name = "script".toList ∨ asciiLower t.name = "style".toList) := by
            rw [h]; decide
          rw [if_neg c1, if_neg c2, if_pos h] at hc
          exact hc
        · rw [if_pos (Or.inr h)] at hc
          exact textOnly_forestOK ch hc
        · rw [if_pos (Or.inl h)] at hc
          exact textOnly_forestOK ch hc
      exact runS_element _ ls .svg (nameFacts hn.1) ht hvt hnext hpush
        (fun ls0 => t1F ch .html _ (t.render vs).2 ls0 hch hvc hdisp)
    · have hcm : childMode .svg (asciiLower t.name) = .svg := by
        simp only [childMode, hip, ite_false]
      rw [hcm]
      have hdisp : Dispatch ((.svg, lowerStr t.name) :: (.svg, n0) :: r0) .svg :=
        ⟨lowerStr t.name, _, rfl, by rw [hl]; exact hip⟩
      have hch : ForestOK .svg ch := by
        have h1 : ¬ (asciiLower t.name = "title".toList ∨ asciiLower t.name = "desc".toList) := by
          rintro (h | h) <;> exact hip (by simp [svgIntegration, h])
        have h3 : asciiLower t.name ≠ "foreignobject".toList := fun h => hip (by simp [svgIntegration, h])
        rw [if_neg h1] at hc
        by_cases h2 : asciiLower t.name = "script".toList ∨ asciiLower t.name = "style".toList
        · rw [if_pos h2] at hc
          exact staticNoAmp_forestOK ch .svg hc
        · rw [if_neg h2, if_neg h3] at hc
          exact hc
      exact runS_element _ ls .svg (nameFacts hn.1) ht hvt hnext hpush
        (fun ls0 => t1F ch .svg _ (t.render vs).2 ls0 hch hvc hdisp)
  | .element t ch, .math, σ, vs, ls, ⟨ht, hsc, hn, hc⟩, ⟨hvt, hvc⟩, hd => by
    obtain ⟨n0, r0, rfl, hn0⟩ := hd
    have hf : foreignDispatch ((.math, n0) :: r0) = true := dispatch_math_foreign ⟨n0, r0, rfl, hn0⟩
    have hl : lowerStr t.name = asciiLower t.name := lowerStr_eq_asciiLower _
    rw [denoteN_element, if_neg (fun h => nomatch h.1)]
    simp only [renderN]
    have hpush : pushP ((.math, n0) :: r0) (lowerStr t.name) t.selfClosing =
        (.math, lowerStr t.name) :: (.math, n0) :: r0 := by
      rw [hsc]; exact pushP_foreign (by decide) hf _
    have hnext := tree_foreign hf (lowerStr t.name)
    by_cases hip : asciiLower t.name ∈ mathTextIP
    · have hcm : childMode .math (asciiLower t.name) = .html := by
        simp only [childMode, hip, ite_true]
      rw [hcm]
      have hdisp : Dispatch ((.math, lowerStr t.name) :: (.math, n0) :: r0) .html := by
        show foreignDispatch _ = false
        rw [hl]; exact foreignDispatch_math _ _ hip
      have hch : ForestOK .html ch := by
        have hip' : asciiLower t.name ∈ ["mi", "mo", "mn", "ms", "mtext"].map String.toList := hip
        rw [if_pos hip'] at hc
        exact textOnly_forestOK ch hc
      exact runS_element _ ls .math (nameFacts hn.1) ht hvt hnext hpush
        (fun ls0 => t1F ch .html _ (t.render vs).2 ls0 hch hvc hdisp)
    · have hcm : childMode .math (asciiLower t.name) = .math := by
        simp only [childMode, hip, ite_false]
      rw [hcm]
      have hdisp : Dispatch ((.math, lowerStr t.name) :: (.math, n0) :: r0) .math :=
        ⟨lowerStr t.name, _, rfl, by rw [hl]; exact hip⟩
      have hch : ForestOK .math ch := by
        have hip' : asciiLower t.name ∉ ["mi", "mo", "mn", "ms", "mtext"].map String.toList := hip
        rw [if_neg hip'] at hc
        exact hc
      exact runS_element _ ls .math (nameFacts hn.1) ht hvt hnext hpush
        (fun ls0 => t1F ch .math _ (t.render vs).2 ls0 hch hvc hdisp)
  | .element t ch, .select, σ, vs, ls, ⟨ht, hsc, hn, hc⟩, ⟨hvt, hvc⟩, hd => by
    have hl : lowerStr t.name = asciiLower t.name := lowerStr_eq_asciiLower _
    have hd' := dispatch_select hd
    rw [denoteN_element, if_neg (fun h => nomatch h.1)]
    simp only [renderN]
    rcases hc with ⟨hopt, hch⟩ | ⟨hgrp, hch, _⟩
    · have hcm : childMode .select (asciiLower t.name) = .html := by rw [hopt]; decide
      rw [hcm]
      have hnext : profileTB.tree σ (lowerStr t.name) = .data := by
        rw [tree_html hd', hl, hopt]; decide
      exact runS_element σ ls .html (nameFacts hn.1) ht hvt hnext
        (by simp only [hl, hopt, hsc]; exact pushP_html hd' (by decide) (by decide) (by decide) false)
        (fun ls0 => t1F ch .html ((.html, lowerStr t.name) :: σ) (t.render vs).2 ls0
          (textOnly_forestOK ch hch) hvc rfl)
    · have hcm : childMode .select (asciiLower t.name) = .select := by rw [hgrp]; decide
      rw [hcm]
      have hnext : profileTB.tree σ (lowerStr t.name) = .data := by
        rw [tree_html hd', hl, hgrp]; decide
      exact runS_element σ ls .html (nameFacts hn.1) ht hvt hnext
        (by simp only [hl, hgrp, hsc]; exact pushP_html hd' (by decide) (by decide) (by decide) false)
        (fun ls0 => t1F ch .select ((.html, lowerStr t.name) :: σ) (t.render vs).2 ls0 hch hvc rfl)
theorem t1F : ∀ (F : Forest) (m : Mode) (σ : Stack) (vs : List (Option (List Char))) (ls : List Char),
    ForestOK m F → ValsF F vs → Dispatch σ m →
    ∃ ls', runS profileTB σ ⟨.data, ls⟩ (renderF F vs).1 = ((⟨.data, ls'⟩, σ), denoteF m F vs)
  | .nil, m, σ, vs, ls, _, _, _ => ⟨ls, by simp [renderF, runS, denoteF]⟩
  | .cons n r, m, σ, vs, ls, ⟨hn, _, hr⟩, ⟨hvn, hvr⟩, hd => by
    obtain ⟨ls1, e1⟩ := t1N n m σ vs ls hn hvn hd
    obtain ⟨ls2, e2⟩ := t1F r m σ (renderN n vs).2 ls1 hr hvr hd
    refine ⟨ls2, ?_⟩
    simp only [renderF, denoteF]
    rw [runS_append, e1]
    simp only
    rw [e2]
end

/-- Values for a whole template: the DOCTYPE consumes none. -/
def ValsT : Forest → List (Option (List Char)) → Prop
  | .cons (.doctype _) r, vs => ValsF r vs
  | T, vs => ValsF T vs

/--
**T1 for the profile.** For every template in the Tessera HTML profile (`InProfile`:
§5.2 token rules, §5.3 parse modes and allow-lists, §5.4 script bodies as `ScriptOK`,
§5.5 hole contexts as `HoleCtx`) and every list of hole values satisfying the escaper
postconditions of the contexts the compiler chose (`ValsT`), the WHATWG tokenizer driven
by the profile's tree builder (`profileTB`: namespaces, integration points, raw-text
switches) reads the rendered page as exactly the tokens the template assigns
(`denoteF .html`), and ends in the data state with every element closed.

So every hole's value is Character tokens, or one attribute value of one StartTag, of
the element Tessera put it in, read in the tokenizer state of that element's content
model, in the namespace Tessera assigned; and no value or static text opens or closes an
element the template does not have. This is the token-level reading of §5.1; which node
of the DOM a token attaches to is not modeled (README).
-/
theorem t1_profile_tree : ∀ (T : Forest) (vs : List (Option (List Char))), InProfile T → ValsT T vs →
    ∃ ls', runS profileTB [] init (renderF T vs).1 = ((⟨.data, ls'⟩, []), denoteF .html T vs)
  | .cons (.doctype d) r, vs, ⟨hd, hr⟩, hv => by
    obtain ⟨ls1, e1⟩ := t1F r .html [] vs [] hr hv rfl
    refine ⟨ls1, ?_⟩
    simp only [renderF, renderN, denoteF, init]
    rw [runS_append, runS_noTag [] (run_doctype (profileTB.tree []) [] hd)
      (fun t ht => by simp at ht; subst ht; rfl)]
    simp only
    rw [show denoteN .html (.doctype d) vs = [.doctype (some "html".toList) false] from rfl, e1]
  | .nil, vs, h, hv => t1F _ .html [] vs [] h hv rfl
  | .cons (.text s) r, vs, h, hv => t1F _ .html [] vs [] h hv rfl
  | .cons (.hole c) r, vs, h, hv => t1F _ .html [] vs [] h hv rfl
  | .cons (.comment b) r, vs, h, hv => t1F _ .html [] vs [] h hv rfl
  | .cons (.void t) r, vs, h, hv => t1F _ .html [] vs [] h hv rfl
  | .cons (.element t ch) r, vs, h, hv => t1F _ .html [] vs [] h hv rfl

/-- The run never reaches the `out` sink: it ends in the data state, and `out` is sticky. -/
theorem t1_profile_tree_inModel (T : Forest) (vs : List (Option (List Char))) (hT : InProfile T)
    (hv : ValsT T vs) : (runS profileTB [] init (renderF T vs).1).1.1.st ≠ .out := by
  obtain ⟨ls', e⟩ := t1_profile_tree T vs hT hv
  rw [e]; simp

end TesseraT1
