# Reviewer focus: tessera

Tessera is a template engine whose job is to make XSS and template injection
impossible: a template that compiles must not let a value run as code in the
browser. Review at full depth. Hold the change to the design principles and
rules in `AGENTS.md`; a break of a principle is Blocking unless the PR says so
and explains why.

## What to look hardest at

- Escaping and contexts. For a change to an output context, an escaper,
  `Contexts`, `ParsingContexts`, `Rules`, `ContextVerifier` or the tokenizer:
  which contexts it affects, what a hostile value renders as in each, and
  whether the verifier and the compiler still agree. A check that becomes
  looser is Blocking unless a test shows the looser case is safe.
- Parsers and names. Browsers fold tag and attribute names A-Z only; a
  comparison using `equalsIgnoreCase` or `toLowerCase` where the browser would
  see a different name is a finding. For any byte, escape, URL or name parser,
  enumerate alternate encodings (case variants, non-ASCII look-alikes,
  character references, percent and double encoding, in-band terminators)
  and confirm each is handled or refused.
- Fail closed. An unknown element, attribute, context or value type must be
  a build error or an escape, never raw output.
- Security behavior needs a test and a negative test; name both by path and
  line, or say they are missing (Blocking).

## Findings and verdict

Start each finding with `**Blocking:**` (severity high for a security or
correctness defect, medium otherwise; state the failure scenario as
`<template or value> -> <wrong output>`, or downgrade to a Question) or
`**Question:**` (severity low, something the author must answer).

`approve` only with zero Blocking and zero Questions, a named test for every
behavior the change adds or alters (a regression test for any fix), and the
title and body re-derived from the code. When in doubt, `comment`.

Don't call `list_checks`; the checks gate the merge on their own.
