# Handoff: how we work on this repo

Rules for all agents are in [AGENTS.md](../AGENTS.md): the six design principles, keep-it-simple,
and the PR workflow. This doc covers how the work gets orchestrated.

## State (2026-10-22)

The Tessera MVP is done. It has the compiler, the runtime, the Spring starter, `tessera-migrate`,
the petclinic port, a security suite, JMH benchmarks, and [guide.md](guide.md) (the porting guide
written for agents). A code-quality pass followed, tracked in #85. All issues are closed.
`mvn clean verify` runs about 1.3k tests, all green.

## Orchestration pattern

1. **Main session stays small.** It plans, files issues, launches workflows, and reads summaries.
   It does not write code.
2. **Design first, then fan out.** One architect agent writes the design and a skeleton PR with
   the shared contracts and `TODO(#n)` stubs, then files the issues.
3. **Waves with disjoint ownership.** Each issue lists the files it *owns*. Issues in the same
   wave never share files, and shared contracts only change additively. This is why parallel
   merges don't collide.
4. **Two agents per issue.** Both run in their own git worktree:
   - The *implementer* branches off `origin/main` and opens a PR from the fork.
   - A fresh *reviewer/lander* checks the PR against the principles and for overcomplication,
     fixes real problems (not nits), rebases, waits for CI, and squash-merges when it is green.
5. **Follow-ups become issues.** Reviewers' notes become comments on later-wave issues or new
   issues. The main session routes them.
6. **Between waves:** confirm the postsubmit run on `main` is green, run `mvn clean verify`
   locally, and remove finished worktrees under `.claude/worktrees/`.

## Mechanics

- Remotes: `origin` is `chainguard-dev/harder` (private), `fork` is `dlorenc/harder`. Push to the
  fork and open PRs against origin.
- CI is Chainguard Checks (`.chainguard/ci/maven.yaml`), not GitHub Actions.
  - Wait on PRs with `gh pr checks <n> --repo chainguard-dev/harder --watch`; the check to watch
    is "Chainguard Checks (presubmit)".
  - Read logs with `chainctl checks logs chainguard-dev/harder#<n> --check verify --errors`.
- Merge on green CI. Review is not required. Commits are unsigned; ignore the signing check.
- Labels used so far: `wave-1` through `wave-4`, `refactor-wave-1` through `refactor-wave-4`.

## Known limits relevant to migration work

- Maven only, no Gradle plugin. The processor needs `-Atessera.templates=<absolute dir>`.
- Maven doesn't recompile when only a template changes. Use `mvn clean`.
- Artifacts are not published. A target app consumes Tessera through `mvn install` of this
  repo, as `0.1.0-SNAPSHOT` in `~/.m2`.
- There is no runtime template loading and no `__${}__` preprocessing, by design.
- [examples/petclinic/MIGRATION-NOTES.md](../examples/petclinic/MIGRATION-NOTES.md) lists the
  friction from the one real port so far.
