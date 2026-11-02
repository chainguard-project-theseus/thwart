# Handoff: how we work on this repo

Rules for all agents are in [AGENTS.md](../AGENTS.md): the north star, the seven design
principles, keep-it-simple, and the PR workflow. This doc covers how the work gets orchestrated.
For security work, follow [security-architecture.md](security-architecture.md) §12 and
[security-model.md](security-model.md) §6.

## Orchestration pattern

1. **Main session stays small.** It plans, files issues, launches workflows, and reads summaries.
   It does not write code.
2. **Waves with disjoint ownership.** Each issue lists the files it *owns*. Issues in the same
   wave never share files, and shared contracts only change additively, so parallel merges
   don't collide.
3. **Two agents per issue**, each in its own worktree or develop session: the *implementer*
   branches off `origin/main` and opens a PR from the fork; a fresh *reviewer/lander* checks it
   against the principles and for overcomplication, fixes real problems (not nits), rebases,
   waits for CI and merges as AGENTS.md says.
4. **Follow-ups become issues** or comments on later-wave issues. The main session routes them.

## Fast loop

Work in a `chainctl develop` session (the default size: 8 CPUs, 32 GB). It has JDK 21 and JDK 8
under `/usr/lib/jvm` and Maven; `apk add jq procps nodejs-22 npm` once for `hack/corpus.sh`
(busybox `ps` kills old surefire forks on JDK 8; ewp-registry builds with npm). No Docker is
needed. In a fresh session install everything once with
`mvn -B -q install -DskipTests -Dinvoker.skip`; on a clean checkout `tier0` installs nothing. Then
stay offline:

- `hack/quick.sh tier0`: after a change, install the modules changed against `origin/main` (and
  their dependencies), no tests. Seconds.
- `hack/quick.sh tier1`: the security core (`SeedVerifierTest`, `ExitStateTest`, `HostileSweepTest`, the escaper,
  SSTI, ASCII and text choke point tests). About a minute; run it before every PR.
- `hack/quick.sh tier2`: tier0, then the tests of the changed modules.
- `hack/corpus.sh [app ...]`: for every bench app with a committed `diff.patch`, clone it at
  `app.json`'s commit, apply the latest run's patch and build and test it on the app's JDK
  against the installed 0.1.0-SNAPSHOT. Prints app, run, compile, tests. `JOBS=3` runs three apps
  at once (all 14 in about 5 minutes); logs are under `/tmp/tessera-corpus/out/<app>/`.

`quick.sh` runs Maven with `-o`; set `ONLINE=1` when an artifact is missing (the first test run
of a module in a fresh session). CI still runs the full `mvn verify`.

## Mechanics

- Remotes: `origin` is `wolfi-dev/tessera`, `fork` is `dlorenc/tessera`. Push to the
  fork and open PRs against origin.
- CI is Chainguard Checks (`.chainguard/ci/maven.yaml`), not GitHub Actions. Wait with
  `gh pr checks <n> --repo wolfi-dev/tessera --watch` (the check is "Chainguard Checks
  (presubmit)"); read logs with `chainctl checks logs wolfi-dev/tessera#<n> --check verify --errors`.
- Merge when the checks are green and the PR is approved by an owner in `.chainguard/owners.yaml`
  (AGENTS.md). Commits are unsigned; ignore the signing check.

## Known limits relevant to migration work

- Artifacts are not published. A target app consumes Tessera through `mvn install` of this
  repo, as `0.1.0-SNAPSHOT` in `~/.m2`.
- [guide.md section 5](guide.md#5-known-limitations) lists the current gaps, and
  [bench/migration/RESULTS.md](../bench/migration/RESULTS.md) has the failure analysis of the
  benchmark runs.
