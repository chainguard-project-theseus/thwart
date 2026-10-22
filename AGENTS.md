# AGENTS.md — read this before touching code

This repo prototypes **Tessera**, a compiled, safe-by-design successor to Thymeleaf, the
pilot of Chainguard's secure-by-design library portfolio.

- Strategy: [docs/strategy.md](docs/strategy.md)
- Vision (long-term design): [docs/tessera-vision.md](docs/tessera-vision.md)
- **MVP design (what we are building now): [docs/mvp-design.md](docs/mvp-design.md)**

## Design principles (non-negotiable)

Every change must uphold all six. If a task seems to require breaking one, stop and say so
in the PR/issue instead of working around it.

1. **Templates are code.** Compiled at build time into Java classes. The runtime has no
   template parser and no API that accepts template source as a `String`.
2. **No ambient authority.** A template sees only its declared, typed `t:params`. No class
   names, no `new`, no static state.
3. **Allow-list everything callable.** Property access resolves at compile time against the
   view-safe type allow-list. Functions exist only if registered with `@TemplateFunction`.
   The never-view-safe list (`Object` methods, `Class`, `ClassLoader`, `java.lang.reflect`,
   `java.lang.invoke`, `Thread`, `Runtime`, `ProcessBuilder`, servlet/Spring context
   objects) can never be reached, even if registered.
4. **No reflection at runtime.** All access is generated Java. No `java.lang.reflect`,
   `MethodHandle`, SpEL, OGNL, or `ScriptEngine` in runtime or generated code.
5. **Escaping is contextual and cannot be forgotten.** The compiler picks the escaper from
   the output context. Raw output needs a safe type (`SafeHtml`, `TrustedUrl`), never a flag
   on a string.
6. **Familiar before novel.** Track Thymeleaf syntax and behaviour wherever that is safe so
   migration is mechanical.

## Keep it simple

This is an MVP built tonight. Prefer the smallest thing that is correct and secure.

- Do exactly the issue's scope. No speculative abstractions, plugin systems, config knobs,
  or "future-proofing". No new modules or dependencies unless the issue says so.
- Plain Java. `tessera-runtime`, `tessera-compiler` (the annotation processor runs inside the
  application's javac, which may be JDK 8), `tessera-migrate`, `tessera-spring-boot-starter`
  (main and test sources) and the code the generator emits are **Java 8** (no records, sealed
  types, pattern matching, switch expressions, `var` or post-8 APIs such as `List.of`; the
  compiler has `Backports` and migrate `Compat` for the latter); javac `--release 8` and the
  release-8 and JDK 8 test runs enforce it. The starter must also keep working on Spring 5.0 /
  Boot 2.0 (`tessera-spring-tests`, also `--release 8`). Benchmarks, examples and the tests of
  the runtime, compiler and migrate are Java 21. The runtime module has **zero third-party
  dependencies**.
- Error messages from the compiler must name the template file, line and column.
- Security-relevant behaviour gets a test, including a negative test (the bad thing fails).
- If something is out of scope, leave a `// TODO(#issue)` or open an issue; don't build it.

## Repo layout and ownership

See the module table in [docs/mvp-design.md](docs/mvp-design.md).

Shared contracts are the template model (`tessera-compiler/.../model/*`), the `Expr` classes in
`.../expr`, `Scope`/`Variable`/`CompiledExpr` in `.../types`,
`CodegenContext`/`ElementPlan`/`AttributeProcessor`/`AttributeProcessors` in `.../codegen`, the
runtime API, and the `dev.tessera.compiler.testing.Compilation` harness. If you must change one,
keep the change additive and minimal and call it out in the PR description.

## Build and test

- `mvn -B -ntp verify` from the repo root. JDK 21+ (`--release 21`; runtime, compiler, migrate and
  starter `--release 8`). `Jdk8ProcessorTest` builds a small app with a real JDK 8 javac: point
  `JDK8_HOME` at a JDK 8 (without it the test is skipped locally and fails in CI).
- Tests use JUnit Jupiter 6 (the root pom pins `junit-bom` 6.1.3). Compiler tests use the in-memory harness
  `dev.tessera.compiler.testing.Compilation` (in `tessera-compiler/src/test/java`), which runs
  javac with the real annotation processor and lets you render or assert on diagnostics.
  `Builder.release(8)` compiles at the Java 8 floor; the compiler module's `release-8` surefire
  run repeats most template tests that way.
- Maven does not recompile when only a template changes; use `mvn clean verify` when in doubt.

## Workflow for agents

- Work in your own git worktree on a branch named `issue-<n>-<slug>`. Never commit to `main`
  directly.
- One issue per PR. PR title `<summary> (#<n>)`, body says `Fixes #<n>`.
- Remotes: `origin` = `chainguard-dev/harder`, `fork` = `dlorenc/harder`. Push branches to
  `fork` and open PRs against origin:
  `gh pr create --repo chainguard-dev/harder --head dlorenc:<branch> --base main`.
- Rebase on `origin/main` before pushing; resolve conflicts yourself.
- CI is **Chainguard checks** (config in `.chainguard/`), not GitHub Actions. Wait for the
  checks on your PR with `gh pr checks <n> --watch`.
- When all checks are green, squash-merge (`gh pr merge <n> --squash --delete-branch`).
  Review is not required. If CI fails, fix it; don't merge red.
- Commits are unsigned (`git -c commit.gpgsign=false commit`). Ignore any signing check.
