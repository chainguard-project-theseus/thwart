# Contributing to Thwart

We don't accept pull requests from external contributors. If you and your
organization would seriously like to get involved, email
dlorenc@chainguard.dev.

## For maintainers

[AGENTS.md](AGENTS.md) holds the rules for how work on Thwart is done, by people and coding agents
alike. In short:

- **Uphold the seven principles in AGENTS.md;** if a change seems to need breaking one, stop and
  say so.
- **Keep it small.** Do exactly the issue's scope. Fix parser-differential and composition bugs
  in the build-time verifier or its test cases, not with special cases in the parser. Add no new
  modules or dependencies unless the issue asks for them.
- **Java 8 where it ships.** The runtime, compiler, migrate tool, starters and generated code
  compile at `--release 8`. `thwart-runtime` has no third-party dependencies.
- **Security-relevant behaviour gets a test**, including a negative one. Changes to the parser,
  contexts, escapers or verifier update
  [docs/context-model.md](docs/context-model.md) in the same PR. [SECURITY.md](SECURITY.md) and
  [docs/security-model.md](docs/security-model.md) describe the threat model.
- **Build and test** with `mvn -B -ntp verify` (JDK 21+, Maven 3.9+; set `JDK8_HOME` to run
  `Jdk8ProcessorTest`). CI is Chainguard Checks, configured in `.chainguard/`.
- **One issue per PR.** Name the branch `issue-<n>-<slug>`, title the PR `<summary> (#<n>)`, put
  `Fixes #<n>` in the body, and rebase on `main` before pushing.

Report security issues privately, as [SECURITY.md](SECURITY.md) describes. Don't use a public issue.
