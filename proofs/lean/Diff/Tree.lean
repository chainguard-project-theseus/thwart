/-
Check of the tree-builder stub `Whatwg.treeStub` against the WPT tree-construction
tests (html/syntax/parsing/resources/*.dat, vendored in test/wpt-tree; the commit is
in test/wpt-tree/SOURCE).

`treeStub` claims: after a start tag named script / title / textarea / style / xmp /
iframe / noembed / noframes / noscript, the tokenizer reads raw text up to the
appropriate end tag, in every insertion mode reachable without `template`, `select`,
`svg`, `math`, `frameset` and `plaintext` (which it sends to `out`), with scripting
enabled. For each document test the stub covers, this program tokenizes the input
with `treeStub` and collects, for every such start tag, the characters up to the next
end tag (dropping a leading LF for `textarea`, as the "in body" rule does). It compares
that multiset of (name, text) pairs with the (name, text content) of the HTML elements
of those names in the expected tree. A test is skipped when it is in a `scripted` file (those run `document.write`), is a fragment test, has
`#script-off`, has `&` in its input, contains one of the excluded tag names, or the run
reaches `out`. Exits 1 on any mismatch.
-/
import TesseraT1.Whatwg

open TesseraT1.Whatwg

def rawNames : List String :=
  ["script", "title", "textarea", "style", "xmp", "iframe", "noembed", "noframes", "noscript"]

def excluded : List String := ["<template", "<select", "<svg", "<math", "<frameset", "<plaintext"]

structure Test where
  data : String := ""
  doc : List String := []
  fragment : Bool := false
  scriptOff : Bool := false

def headers : List String :=
  ["#data", "#errors", "#new-errors", "#document", "#document-fragment", "#script-off", "#script-on"]

/-- Split a .dat file into tests. -/
def parseDat (s : String) : List Test := Id.run do
  let lines := s.splitOn "\n"
  let mut tests : Array Test := #[]
  let mut cur : Option Test := none
  let mut sec := ""
  let mut dataLines : Array String := #[]
  for l in lines do
    if headers.contains l then
      if l == "#data" then
        if let some t := cur then
          tests := tests.push { t with data := "\n".intercalate dataLines.toList }
        cur := some {}
        dataLines := #[]
      else if l == "#document-fragment" then
        cur := cur.map ({ · with fragment := true })
      else if l == "#script-off" then
        cur := cur.map ({ · with scriptOff := true })
      sec := l
    else if sec == "#data" then
      dataLines := dataLines.push l
    else if sec == "#document" then
      cur := cur.map fun t => { t with doc := t.doc ++ [l] }
  if let some t := cur then
    tests := tests.push { t with data := "\n".intercalate dataLines.toList }
  return tests.toList

/-- Nodes of the expected tree: (depth, kind, value); kind is "el" or "text". -/
def parseDoc (ls : List String) : List (Nat × String × String) := Id.run do
  let mut out : Array (Nat × String × String) := #[]
  let mut pending : Option (Nat × String) := none   -- an open multi-line text node
  for l in ls do
    if let some (d, acc) := pending then
      if l.endsWith "\"" then
        out := out.push (d, "text", acc ++ "\n" ++ (l.dropEnd 1).toString)
        pending := none
      else
        pending := some (d, acc ++ "\n" ++ l)
      continue
    if !l.startsWith "| " then continue
    let body := (l.drop 2).toString
    let indent := (body.takeWhile (· == ' ')).toString.length
    let d := indent / 2
    let v := (body.drop indent).toString
    if v.startsWith "\"" then
      let inner := (v.drop 1).toString
      if inner.endsWith "\"" && inner.length ≥ 1 then
        out := out.push (d, "text", (inner.dropEnd 1).toString)
      else
        pending := some (d, inner)
    else if v.startsWith "<" && !v.startsWith "<!" && !v.startsWith "<?" then
      out := out.push (d, "el", ((v.drop 1).dropEnd 1).toString)
    else
      out := out.push (d, "other", v)
  return out.toList

/-- (name, text content) of each HTML raw-text element in the expected tree. -/
def expectedRaw (nodes : List (Nat × String × String)) : List (String × String) := Id.run do
  let arr := nodes.toArray
  let mut res : Array (String × String) := #[]
  for i in [0:arr.size] do
    let (d, k, v) := arr[i]!
    if k == "el" && rawNames.contains v then
      let mut txt := ""
      let mut j := i + 1
      while j < arr.size && arr[j]!.1 > d do
        let (d', k', v') := arr[j]!
        if d' == d + 1 && k' == "text" then txt := txt ++ v'
        j := j + 1
      res := res.push (v, txt)
  return res.toList

/-- (name, text) for each raw-text start tag in the model's token stream. -/
def modelRaw (toks : List Token) : List (String × String) :=
  let rec go : List Token → Option (String × String) → List (String × String)
    | [], none => []
    | [], some p => [p]
    | .startTag n _ _ :: r, none =>
      let n := String.ofList n
      if rawNames.contains n then go r (some (n, "")) else go r none
    | .char c :: r, some (n, t) => go r (some (n, t.push c))
    | _ :: r, some p => p :: go r none
    | _ :: r, none => go r none
  (go toks none).map fun (n, t) =>
    if n == "textarea" && t.startsWith "\n" then (n, (t.drop 1).toString) else (n, t)

def sortPairs (l : List (String × String)) : List (String × String) :=
  l.toArray.qsort (fun a b => a.1 < b.1 || (a.1 == b.1 && a.2 < b.2)) |>.toList

def preprocess : List Char → List Char
  | '\r' :: '\n' :: r => '\n' :: preprocess r
  | '\r' :: r => '\n' :: preprocess r
  | c :: r => c :: preprocess r
  | [] => []

def main (args : List String) : IO UInt32 := do
  let mut pass := 0
  let mut fail := 0
  let mut skip := 0
  let mut withRaw := 0
  for file in args do
    for t in parseDat (← IO.FS.readFile file) do
      let low := String.ofList (t.data.toList.map Char.toLower)
      if (file.splitOn "scripted").length > 1 || t.fragment || t.scriptOff || t.data.toList.contains '&' ||
          excluded.any (fun e => (low.splitOn e).length > 1) then
        skip := skip + 1
        continue
      let inp := preprocess t.data.toList
      if leavesModel treeStub init inp then
        skip := skip + 1
        continue
      let got := sortPairs (modelRaw (tokenize treeStub inp))
      let want := sortPairs (expectedRaw (parseDoc t.doc))
      if !want.isEmpty then withRaw := withRaw + 1
      if got == want then pass := pass + 1
      else
        fail := fail + 1
        IO.println s!"FAIL {file}\n  input: {repr t.data}\n  expected: {repr want}\n  model:    {repr got}"
  IO.println s!"pass {pass} ({withRaw} with raw-text elements), fail {fail}, skipped {skip}"
  return if fail = 0 then 0 else 1
