# Handoff: how we work on this repo

Rules for all agents are in [AGENTS.md](../AGENTS.md): the seven design principles, keep-it-simple,
and the PR workflow. This doc covers how the work gets orchestrated.

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

- **The verifier is the boundary for markup contexts.** The pinned spec HTML parser checks every
  template at build time (#367). It accepts only error-free parses outside three coarse
  exclusions (#406, security-architecture.md §11.4). A new parser-differential or composition
  report (family A/B) becomes a verifier test case: a seed the verifier must reject. If the
  verifier accepted it, fix the verifier or its rule table. Don't patch `HtmlParser`,
  `MarkupTokenizer` or `Contexts` with a special case for it. Early, friendly errors there are
  welcome only when the verifier already rejects the template and the message is better.
- **Reject error recovery; don't model it.** If the parser and a supported browser build
  different trees (the nightly detector, #399, or a report), add a coarse exclusion and a test.
  Never model the browser's algorithm in the verifier, and never patch the parser. Don't widen
  the error allow-list without a corpus measurement.
- **Engine bumps are their own PR.** A PR that moves the pinned validator release runs the seeds,
  the corpus and the nightly detector, and lists every change in verdict.

- **Two specs.** [security-model.md](security-model.md) is the threat model and trust
  boundaries. [context-model.md](context-model.md) is the HTML profile as tables, with each
  row's status, check and proof. Every change to `HtmlParser`, `MarkupTokenizer`, `Contexts`,
  `TextInlining`, `Escapers` or the verifier's rule table (`verify/`) updates context-model.md in
  the same PR.
- **The oracle gate** (#324) tests the verifier (skeleton fidelity, the inertness lemma) and
  keeps value checks for scripts and URLs; its context check holds a name outside HTML to the
  framework-neutral shape and names no framework (#608; the htmx request list left in
  `HtmlInvariant` and the Node check is #611). A new context
  or parse mode lands with oracle rows. A PR that widens the accepted language says so in its
  description.
- **Framework-neutral, not framework-modelled (#583).** Never add a rule that names a client
  framework. Outside standard HTML attributes a computed value is framework-neutral (a number, a
  boolean or a same-origin path), and computed text never contains `{{`; the hostile sweep checks
  that shape (`HostileSinks`). What still names one: the constant `v-pre` on a computed `<title>`
  or `<textarea>`, and the build error for a computed htmx 1.x `hx-ws` or `hx-sse` (#562). A
  report about what a framework does with other output is outside the guarantee.
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

- Remotes: `origin` is `wolfi-dev/tessera`, `fork` is `dlorenc/tessera`. Push to the
  fork and open PRs against origin.
- CI is Chainguard Checks (`.chainguard/ci/maven.yaml`), not GitHub Actions.
  - Wait on PRs with `gh pr checks <n> --repo wolfi-dev/tessera --watch`; the check to watch
    is "Chainguard Checks (presubmit)".
  - Read logs with `chainctl checks logs wolfi-dev/tessera#<n> --check verify --errors`.
- Merge on green CI. Review is not required. Commits are unsigned; ignore the signing check.
- Labels used so far: `wave-1` through `wave-4`, `refactor-wave-1` through `refactor-wave-4`,
  `migration-bench`, `documentation`, `security`, `security-phase-0` through `security-phase-5`,
  `security-verifier`.

## Known limits relevant to migration work

- Maven or Gradle ([examples/gradle](../examples/gradle/build.gradle), built in CI). The processor
  needs `-Atessera.templates=<absolute dir>`.
- javac doesn't recompile when only a template changes. With Maven, `tessera:check`
  (`tessera-maven-plugin`) deletes the stale output so it does (#425).
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
