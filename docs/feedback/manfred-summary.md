# Tessera — feedback summary

*From Manfred Moser. 2026-10-23 to 2026-10-26.*

*Lenses: Java developer, and open-source maintainer and advocate.*

## TL;DR

I built it, and tried to break out, and failed, which is the point. The
claim holds: the RCE class is gone by construction, not by a denylist. Classic
payloads like `T(java.lang.Runtime).getRuntime().exec('id')` and a reflection
reach through `owner.getClass()` are both hard build errors with a precise file,
line, and column and a suggested fix. It is also genuinely close to Thymeleaf,
and the compiler diagnostics are better than I expected. A full migration of a
fresh petclinic came out green, with less friction than the repo's own notes
suggest.

So the security model is not the risk. The risk is adoption. This is a
developer-facing framework, and the things that decide whether developers adopt
a template engine are mostly deferred: the day-to-day experience, and the
confidence that the project will last and have a community.

## What I ran

* **Build.** `mvn install -DskipTests` is green in under a minute on JDK 27. The
  full build with tests fails only in `LombokTest`, because the test-scope
  Lombok cannot initialize on JDK 27. CI uses JDK 21, where it is green.
* **Tried to break out.** Two injected payloads, `T(...)` and `getClass()`, were
  both rejected at build time with clear file, line, and column diagnostics.
* **Full migration.** Ported a fresh upstream spring-petclinic to Tessera
  end-to-end from the guide: 12 of 12 templates, Thymeleaf removed, all 79 tests
  green, about 35 minutes and mostly mechanical. Injected `<script>` in a name
  and it rendered fully escaped.
* **Migration converter.** 36 accurate `file:line` report items, and several
  converter bugs the original port logged are now fixed. The jar runs directly.
* **Incremental build.** `mvn compile` ignores a template-only edit and stays
  green on stale generated code. Only `mvn clean compile` re-runs the processor
  and catches an unsafe expression.

## Top strengths

* **The core guarantee works, firsthand.** Both a lexical `T(...)` and a deep
  allowlist reach through `getClass()` fail the build.
* **Replace, don't patch, because a real patch isn't on the table.** Fixing
  Thymeleaf in place would be preferable if it were possible, but the bug class
  is the runtime expression interpreter at its core: removing it is a new
  engine, not a patch, and Chainguard is not the Thymeleaf maintainer who could
  land that upstream anyway. The only in-place option is the
  denylist/restricted-mode treadmill the CVE chain keeps bypassing. So
  replacement is less "obviously right" than "the only structural fix available
  from outside."
* **Compiler diagnostics** name the file, line, and column and suggest a fix.
* **Right architecture.** An annotation processor over javac's
  `javax.lang.model`, so build tools and eventually IDEs mostly just work.
* **Zero third-party dependencies in the runtime.** `tessera-runtime` is
  dependency-free, enforced by a test; the compiler shades one relocated
  spec-parser that consumers never inherit and that never runs at render time.
  Since the runtime is what ships into an app, the "shrinks your SBOM" claim
  holds for what lands in production.
* **Java 8, Spring 4.3, and Boot 1.5 floor, as an on-ramp, not a resting
  place.** The unglamorous compatibility work lets the legacy apps that carry
  the most Thymeleaf CVE risk adopt at all, which only counts as a strength if
  it is the first step of a move to current Spring and Java, not a reason to
  stay on an end-of-life stack. See section 5.1.
* **Intellectual honesty, and a strong security result.** CI-tested diagnostics,
  compiled guide examples, a real petclinic port with a friction log, and a
  migration benchmark across 57 agent runs that openly reports its failures.
  Across all 57, every migrated app still builds and passes its tests, and none
  weakened security.

## Top risks

* **Gradle is missing, and eventually important.** Maven only today, no build
  plugin, confirmed firsthand with no Gradle files in the repo. A large share
  of Spring Boot apps are Gradle, so first-class support will matter, as a
  fast-follow rather than a launch blocker.
* **Incremental build is a false green.** Template-only edits are not
  recompiled, so the build-time guarantee only holds on a clean build. For a
  security tool that is worth fixing, not just flagging loudly, and it looks
  tractable rather than a design wall.
* **No hot reload in the MVP.** Edit-and-refresh is Thymeleaf's killer feature,
  and losing it is the first thing a developer notices.
* **"Migrate your templates" is not "migrate your app."** No ambient authority
  forces request, session, and path values into the model, and nothing warns
  when one is missed. The petclinic port itself was smooth, about 35 minutes and
  mostly mechanical; the cliff is the controller and model rework on apps that
  lean on ambient scope, where the benchmark's hard apps still fail.
* **The residual surface needs its guardrails.** `SafeHtml.unchecked`,
  `TrustedUrl.unchecked`, and registered `@TemplateFunction`s are the whole
  remaining attack surface, but the promised lint and audit tooling is not
  shipped yet.

## Beyond what you asked: adoption and strategy

This part goes past the prototype. Just my 2 cents.

The security win is narrow on its own, and the positioning cuts against my
[rolling-forward thesis][wp]. Removing SSTI while the guide says to keep Boot
1.5 leaves the app pinned to an end-of-life framework that carries most of its
surface. The reconciliation is the migration agent: the same tooling that moves
an app off Thymeleaf is what could move it off Boot 1.5 and `javax`. Aimed at
the whole stack, Tessera is the on-ramp to rolling, not a way to stay still, and
that reads as the bigger opportunity and the one that fits where Chainguard
already heads. The ambitious version goes further than the stack: carry every
dependency to latest where safe, default the rebuilt ones to Chainguard
Libraries for provenance, lean on their CVE backports where a jump is not yet
safe, and keep the app current on a schedule rather than porting it once. And
the priority inverts: the Thymeleaf replacement is the smaller part, a bespoke
safe-by-construction engine that is expensive per library and will not scale
past a few high-value targets, so the investment that scales is the upgrade
tooling, not more replacements.

A template engine is a sticky dependency, so teams weight longevity and
community as heavily as features. Tessera has zero external users today, no
Maven Central binaries, single-vendor trademark control, and explicit commercial
intent, which together trigger the "will this get paywalled or abandoned?"
reflex. For a YAML parser, "the security team buys it, few developers care" is a
fine motion; for a developer-facing framework the same motion carries a real
cost, because no adoption means no community, which makes the next team's
evaluation worse.

The prior question is whether broad adoption is even the goal, and that is
worth deciding on purpose rather than by default. Chainguard's
[EmeritOSS][emeritoss] program shows the house can run a deliberately
low-adoption model well, single-vendor and source-available with commercial
distribution and no community motion, built to keep mature projects safe while
users migrate off them. That same low-adoption, commercial-only model could be
valid for Tessera too, and my own view is that it is probably the better goal:
wide adoption is a long shot for a single-vendor framework entering a crowded
field, and the effort to earn it is large. What matters either way is that the
choice is deliberate and everyone is on the same page. If adoption is the goal,
the minimum is free Maven Central artifacts, a written longevity and licensing
commitment, and a real adoption motion across docs, samples, talks, and an IDE
plugin. If it is not, the commercial-only path is a legitimate default, as long
as it is chosen rather than drifted into.

*Detail and evidence for every point live in the [detailed
feedback](manfred-detailed.md).*

[wp]: https://docs.google.com/document/d/10fsNKCEuVvDw_EaQkxfmkW_bPwj-yGSJ5MaHjFn0pI8/edit
[emeritoss]: https://github.com/chainguard-forks
