/-
Differential test of the tokenizer model (`TesseraT1.Whatwg`) against the html5lib
tokenizer tests vendored in test/html5lib (html5lib-tests c777c408, MIT).

For each test and each initial state the model covers (Data, RCDATA, RAWTEXT,
Script data), run `Whatwg.run noTree` over the preprocessed input and compare the
tokens with the expected output (Character tokens coalesced, attributes compared
as sets, parse errors ignored). A test is skipped, and counted, when
* its input contains `&` (the model treats character references as transparent),
* it is `doubleEscaped` (lone surrogates are not Lean `Char`s), or
* the run reaches the `out` sink (DOCTYPE identifiers, `<?`, PLAINTEXT, script-data escaped).
Exits 1 if any compared test disagrees.
-/
import Lean.Data.Json
import TesseraT1.Whatwg

open Lean TesseraT1.Whatwg

/-- Token in html5lib's output shape. -/
inductive Out where
  | chars (s : String)
  | start (name : String) (attrs : List (String × String)) (sc : Bool)
  | stop (name : String)
  | comment (s : String)
  | doctype (name : Option String) (correct : Bool)
  deriving BEq, Repr

def sortAttrs (l : List (String × String)) : List (String × String) :=
  l.toArray.qsort (fun a b => a.1 < b.1) |>.toList

def coalesce : List Token → List Out
  | [] => []
  | t :: r =>
    let rest := coalesce r
    match t, rest with
    | .char c, .chars s :: rest' => .chars (String.singleton c ++ s) :: rest'
    | .char c, _ => .chars (String.singleton c) :: rest
    | .startTag n a sc, _ =>
      .start (String.ofList n) (sortAttrs (a.map fun (k, v) => (String.ofList k, String.ofList v))) sc :: rest
    | .endTag n, _ => .stop (String.ofList n) :: rest
    | .comment d, _ => .comment (String.ofList d) :: rest
    | .doctype n q, _ => .doctype (n.map String.ofList) (!q) :: rest

def parseOut (j : Json) : Except String (Option Out) := do
  let arr ← j.getArr?
  let kind ← arr[0]!.getStr?
  match kind with
  | "Character" => return some (.chars (← arr[1]!.getStr?))
  | "Comment" => return some (.comment (← arr[1]!.getStr?))
  | "EndTag" => return some (.stop (← arr[1]!.getStr?))
  | "StartTag" =>
    let name ← arr[1]!.getStr?
    let obj ← arr[2]!.getObj?
    let attrs ← obj.toArray.toList.mapM fun ⟨k, v⟩ => do return (k, ← v.getStr?)
    let sc := arr.size > 3 && arr[3]! == Json.bool true
    return some (.start name (sortAttrs attrs) sc)
  | "DOCTYPE" =>
    -- Public and system identifiers lead the model to `out`, so only the name is compared;
    -- a non-null identifier makes the expected output unmatchable, which is correct.
    let name := (arr[1]!.getStr?).toOption
    if arr[2]! != Json.null || arr[3]! != Json.null then
      return some (.comment "<doctype with identifiers>")
    return some (.doctype name (arr[4]! == Json.bool true))
  | _ => return none

/-- html5lib's expected output, re-coalesced (consecutive Character entries). -/
partial def recoalesce : List Out → List Out
  | .chars a :: .chars b :: r => recoalesce (.chars (a ++ b) :: r)
  | x :: r => x :: recoalesce r
  | [] => []

/-- 13.2.3.5 newline normalization. -/
def preprocess : List Char → List Char
  | '\r' :: '\n' :: r => '\n' :: preprocess r
  | '\r' :: r => '\n' :: preprocess r
  | c :: r => c :: preprocess r
  | [] => []

def initialSt : String → Option St
  | "Data state" => some .data
  | "RCDATA state" => some (.raw .rcdata)
  | "RAWTEXT state" => some (.raw .rawtext)
  | "Script data state" => some (.raw .script)
  | _ => none

structure Stats where
  pass : Nat := 0
  fail : Nat := 0
  skipAmp : Nat := 0
  skipEsc : Nat := 0
  skipOut : Nat := 0
  skipState : Nat := 0

def runTest (file : String) (t : Json) (st : Stats) : IO Stats := do
  let desc := (t.getObjValAs? String "description").toOption.getD "?"
  let input := (t.getObjValAs? String "input").toOption.getD ""
  if (t.getObjValAs? Bool "doubleEscaped").toOption.getD false then
    return { st with skipEsc := st.skipEsc + 1 }
  if input.toList.contains '&' then
    return { st with skipAmp := st.skipAmp + 1 }
  let states := (t.getObjValAs? (List String) "initialStates").toOption.getD ["Data state"]
  let last := (t.getObjValAs? String "lastStartTag").toOption.getD ""
  let some outArr := (t.getObjVal? "output").toOption.bind (·.getArr?.toOption)
    | return { st with fail := st.fail + 1 }
  let expected ← match outArr.toList.mapM parseOut with
    | .ok l => pure (recoalesce (l.filterMap id))
    | .error e => throw (IO.userError s!"{file}: {desc}: {e}")
  let mut st := st
  for s in states do
    match initialSt s with
    | none => st := { st with skipState := st.skipState + 1 }
    | some s0 =>
      let m0 : M := ⟨s0, last.toList⟩
      let inp := preprocess input.toList
      if leavesModel noTree m0 inp then
        st := { st with skipOut := st.skipOut + 1 }
      else
        let got := coalesce (tokensFrom noTree m0 inp)
        if got == expected then st := { st with pass := st.pass + 1 }
        else
          st := { st with fail := st.fail + 1 }
          IO.println s!"FAIL {file}: {desc} [{s}]\n  input:    {repr input}\n  expected: {repr expected}\n  got:      {repr got}"
  return st

def main (args : List String) : IO UInt32 := do
  let mut st : Stats := {}
  for file in args do
    let j ← IO.ofExcept (Json.parse (← IO.FS.readFile file))
    let tests := (j.getObjVal? "tests").toOption.bind (·.getArr?.toOption) |>.getD #[]
    for t in tests do
      st ← runTest file t st
  IO.println s!"pass {st.pass}, fail {st.fail}; skipped: & in input {st.skipAmp}, doubleEscaped {st.skipEsc}, reaches unmodeled state {st.skipOut}, unmodeled initial state {st.skipState}"
  return if st.fail = 0 then 0 else 1
