# Handoff: how we work on this repo

Rules for all agents are in [AGENTS.md](../AGENTS.md): the seven design principles, keep-it-simple,
and the PR workflow. This doc covers how the work gets orchestrated.

## State (2026-10-22)

The Tessera MVP is done: the compiler, the runtime, the Spring starters (`jakarta` and `javax`),
`tessera-migrate` (page record/compare and the Thymeleaf converter), the petclinic port, a
security suite, JMH benchmarks, [guide.md](guide.md) (the porting guide written for agents) and
the Claude Code migration skill in `plugins/tessera`. A code-quality pass followed (#85).

Since then:

- **Java 8 and Spring 4.3 floors.** Application code built against Tessera compiles with JDK 8+
  javac and runs on Java 8+ (#154, #166); `tessera-migrate` runs on Java 8+ (#167). The starters
  support Spring Framework 4.3+ / Boot 1.5+ (#156, #178), with a `javax.servlet` variant and
  `TesseraConfiguration` for Spring MVC without Boot (#156).
- **No third-party compile dependencies** in `tessera-runtime` or the starters. `tessera-compiler`
  has its own `MarkupTokenizer` (#170); from #365 on it also shades the pinned validator.nu
  htmlparser for the build-time verifier (#364), and nothing else. `tessera-migrate`, a developer
  tool, bundles attoparser and jsoup.
- **Migration benchmark** in [bench/migration](../bench/migration/README.md) (#115, #151): an
  agent migrates real apps from the guide alone, scored with `tessera-migrate compare`. The gaps
  it found are open issues labelled `migration-bench` (#118 to #137), and #151 tracks the
  benchmark itself.

`mvn clean verify` is green.

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

## Security process

From [security-architecture.md](security-architecture.md) §7 (accepted Oct 24, 2026) and §11
(the build-time verifier, decided Oct 25, 2026):

- **The verifier is the boundary for markup contexts.** Since #364, two spec HTML parsers check
  every template at build time. A new parser-differential or composition report (family A/B)
  becomes a verifier test case: a seed the verifier must reject. If the verifier accepted it,
  fix the verifier or its rule table. Don't patch `HtmlParser`, `MarkupTokenizer` or
  `Contexts` with a special case for it. Early, friendly errors there are welcome only when the
  verifier already rejects the template and the message is better.
- **Engine bumps are their own PR.** A PR that moves the pinned upstream htmlparser commit runs
  the seeds and the corpus and lists every change in verdict.

- **Two specs.** [security-model.md](security-model.md) is the threat model and trust
  boundaries. [context-model.md](context-model.md) is the HTML profile as tables, with each
  row's status, check and proof. Every change to `HtmlParser`, `MarkupTokenizer`, `Contexts`,
  `TextInlining`, `Escapers`, the verifier's rule table (`verify/`) or the framework table
  updates context-model.md in the same PR.
- **The oracle gate** (#324) tests the verifier (skeleton fidelity, the inertness lemma) and
  keeps value checks for scripts, frameworks and URLs. A new context, parse mode or framework
  entry lands with oracle or harness rows. A PR that widens the accepted language says so in
  its description.
- **Deny-by-default for client frameworks.** No computed value in an unknown attribute namespace.
  Framework support is a table entry with a version range and harness rows.
- **One owner per security area.** Claim an issue before starting. For parser, context or escaper
  changes, post a design comment and get agreement before opening a PR. Don't open a second PR
  for a claimed issue; offer tests to the first one.
- **Reject over rewrite, and fail closed when unsure.** If a check has to guess (regex or
  division, which namespace), the guess leads to a build error, never to accepting.
- **Severity is about the compiler, not the author.** A template the compiler misclassifies is a
  bug even if the markup is contrived. For markup contexts, fix it in the verifier (above);
  elsewhere, by shrinking the profile.
- **Release audit on the oracle's terms.** The adversarial audit runs against the profile and
  reports profile gaps, not single bypasses.

## Mechanics

- Remotes: `origin` is `chainguard-dev/harder` (private), `fork` is `dlorenc/harder`. Push to the
  fork and open PRs against origin.
- CI is Chainguard Checks (`.chainguard/ci/maven.yaml`), not GitHub Actions.
  - Wait on PRs with `gh pr checks <n> --repo chainguard-dev/harder --watch`; the check to watch
    is "Chainguard Checks (presubmit)".
  - Read logs with `chainctl checks logs chainguard-dev/harder#<n> --check verify --errors`.
- Merge on green CI. Review is not required. Commits are unsigned; ignore the signing check.
- Labels used so far: `wave-1` through `wave-4`, `refactor-wave-1` through `refactor-wave-4`,
  `migration-bench`, `documentation`, `security`, `security-phase-0` through `security-phase-5`,
  `security-verifier`.

## Known limits relevant to migration work

- Maven only: nothing tests Gradle, and there is no Maven or Gradle plugin. The processor needs
  `-Atessera.templates=<absolute dir>`.
- Maven doesn't recompile when only a template changes. Use `mvn clean`.
- The processor also runs in test compilation. It finds `@TemplateFunctions` classes from an
  earlier compile through the index it writes to `META-INF/tessera/functions/` (#118).
- Artifacts are not published. A target app consumes Tessera through `mvn install` of this
  repo, as `0.1.0-SNAPSHOT` in `~/.m2`.
- Floors: the target app builds with JDK 8+ and runs on Java 8+, on Spring 4.3+ / Boot 1.5+.
  This repo builds with JDK 21+.
- There is no runtime template loading and no `__${}__` preprocessing, by design.
- [guide.md section 7](guide.md#7-known-limitations) lists the current gaps.
  [examples/petclinic/MIGRATION-NOTES.md](../examples/petclinic/MIGRATION-NOTES.md) is the
  friction log of the first port, and [bench/migration/RESULTS.md](../bench/migration/RESULTS.md)
  has the failure analysis of the benchmark runs.
