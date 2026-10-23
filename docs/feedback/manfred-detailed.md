# Tessera — detailed feedback

*From Manfred Moser. 2026-10-23 to 2026-10-26.*

*Lenses: a Java developer with a lot of Maven, Spring, and Thymeleaf mileage
reading the repo as a potential adopter and migrator; and an open-source
maintainer and advocate reading it as something a team would have to bet a
long-lived dependency on. I built it, ran a full end-to-end migration of a fresh
app, and probed the security and build behavior. I did not run the whole
multi-app migration benchmark.*

Based on the repo at `main`, a local build on JDK 27 with Maven 3.10, and
`docs/strategy.md`, `docs/tessera-vision.md`, `docs/mvp-design.md`,
`docs/guide.md`, `docs/research.md`, `examples/petclinic/PORTING.md`,
`examples/petclinic/MIGRATION-NOTES.md`, `tessera-benchmarks/RESULTS.md`, and
`bench/migration/RESULTS.md`.

What I did not verify firsthand, and take from the repo: the Spring Boot 1.5 and
`javax` compatibility floor, since I exercised only Boot 4.1; the no-reflection
and GraalVM-native claims; and the benchmark throughput numbers. I flag these
where they matter.

## 1. TL;DR

I built it and tried to break out. I could not, which is the point.

The structural argument holds, and it holds in practice, not just on paper.
Thymeleaf's recurring bug class is SSTI, every historical fix has been a
denylist patch over a Turing-complete reflective expression language, and
Tessera removes the class by compiling templates to typed Java with a closed
grammar. I threw two classic payloads at a real template and both failed the
build with a precise location and a suggested fix, and a full end-to-end
migration of a fresh spring-petclinic came out green with less friction than the
project's own notes led me to expect. It is also genuinely close to Thymeleaf,
and the diagnostics are better than I expected.

So the security model is not where I would push. Where I would push is adoption.
This is a developer tool, and the things that decide whether developers adopt a
template engine are mostly deferred or deprioritized: the day-to-day experience,
and the confidence that the project will last and have a community.

## 2. What I ran

**Build.** `mvn install -DskipTests` is green in about 54 seconds on JDK 27 with
Maven 3.10. The full build with tests fails, but only in `LombokTest`, and the
cause is a test-scope Lombok that cannot initialize on JDK 27:
`NoSuchFieldException: com.sun.tools.javac.code.TypeTag :: UNKNOWN`, Lombok
reflecting into javac internals that changed. CI uses JDK 21, where the build is
green. This is not a Tessera defect, but a contributor on the current JDK hits a
red test build out of the box, and the README only says JDK 21+.

**Tried to break out.** I injected two payloads into `owners/ownerDetails.html`
and compiled petclinic:

* `t:text="T(java.lang.Runtime).getRuntime().exec('id')"` fails with
  `owners/ownerDetails.html:21:19: error: type references (T(...)) are not
  supported: call a @TemplateFunction (e.g. numbers.sequence(1, n)) or pass the
  value as a parameter`.
* `t:text="owner.getClass().getName()"` fails with
  `owners/ownerDetails.html:21:19: error: 'getClass' is a java.lang.Object
  method and can never be called from a template`.

The first is a cheap lexical reject; the second exercises the real view-safety
allowlist. Both held, both named the file, line, and column, and both suggested
what to do instead. That is the core claim working.

**Full migration.** I migrated a fresh upstream spring-petclinic, on Spring Boot
4.1 and Java 17, to Tessera end-to-end from the guide alone: all 12 templates
ported, Thymeleaf and attoparser gone from the dependency tree, and the test
suite green at 79 tests, matching baseline. It took about 35 minutes, and the
manual work was almost entirely mechanical typing. I ran the app live and
injected `<script>alert(1)</script>` as an owner name; the detail page rendered
it fully escaped. The friction was noticeably lower than the project's own
`MIGRATION-NOTES.md` led me to expect, because the converter and codegen bugs
the original port hit are fixed.

**Migration converter.** `java -jar tessera-migrate ...` converts petclinic's 12
Thymeleaf templates and writes a 36-item report, each item carrying a file and
line and a specific instruction. The jar runs directly now, so the old
no-`Main-Class` friction from `MIGRATION-NOTES.md` is fixed, and a fresh run
produced fewer items than the 53 the original port logged, with several
documented converter mis-translations now corrected. What remains is mostly
typing `Object` stubs and reworking `__...__` dynamic constructs by hand.

**Incremental build.** I edited a template and ran `mvn compile` without
`clean`. The edit was not picked up: the generated source was byte-for-byte
unchanged and the build stayed green. An injected unsafe expression was caught
only by `mvn clean compile`. So the build-time guarantee is enforced on clean
builds, not incremental ones. See section 4.2.

**Gradle.** There are no Gradle files in the repo. The Gradle gap in section 4.1
is confirmed firsthand, not just read from the "Out" list.

## 3. What is genuinely strong

**The guarantee works in practice.** Covered in section 2. Worth repeating that
the deep case, `getClass()`, is the one that matters: it shows the view-safety
allowlist does the work, not only a lexical screen for `T(` and `new`.

**Replacement, because a real fix is not Chainguard's to ship.** Fixing
Thymeleaf in place would be the better outcome if it were available: no
migration, and the whole ecosystem kept. It is not available, for two
compounding reasons. First, the bug class is the architecture. Thymeleaf's
SSTI comes from a runtime, reflective, Turing-complete expression language,
and the only way to remove it is to replace that core with a compile-time,
closed grammar, which is a different engine wearing Thymeleaf's name, not a
patch. The in-place alternatives, a dialect, a restricted mode, or config,
are the plugin-style defense, and the CVE chain in `research.md`,
CVE-2023-38286 through CVE-2026-41901, is a run of bypasses of exactly that.
You cannot make a runtime interpreter safe by construction with a plugin,
because the interpreter is the bug class. Second, Chainguard does not own
Thymeleaf, so even that deep rewrite is not something it could land upstream;
the fallback for an outsider is forking and patching every framework and
every version still in the field, maintained forever, the extended-support
model at a scale that never closes. Removing the bug class once in a new
engine, with a migration path, is the only structural fix available from
outside, and the cost that moves to migration is the cost the agent tooling is
built to make cheap.

**Architecture.** Using an annotation processor over javac's `javax.lang.model`,
the JStachio and Qute approach documented in `research.md` section 5, is the
right core. Type resolution rides on the compiler the app already runs, so
Maven, incremental builds, and eventually IDEs get support largely for free, and
there is no second type system to keep in sync. One Java class per template with
pre-encoded static chunks and direct accessor calls is a clean, fast design.

**Contextual escaping.** Computing the escaper at compile time from output
context, the Go `html/template` and Soy lineage, is the correct call. The
context table in `mvp-design.md` and guide section 4.4 is more thorough than I
expected: URL versus resource URL versus script versus refresh versus style
versus event handler, plus client-framework directives such as Vue, Alpine,
`_hyperscript`, AngularJS, and Knockout treated as build errors because the
browser runs them as JavaScript after escaping. That is real study of the modern
front-end attack surface.

**Supply chain.** `tessera-runtime` has zero third-party dependencies, enforced
by `RuntimeArchitectureTest`. `tessera-compiler` shades one relocated
`nu.validator` htmlparser for the build-time spec-parser verifier, dropped from
the published pom so consumers never inherit it and it never runs at render
time. Since the runtime is what ships into an app, the "a whole bug class
leaves your SBOM" story holds for the artifact that actually lands in
production.

**Compatibility floor, as an on-ramp, not a resting place.** Java 8, Spring
Framework 4.3+, Boot 1.5+, and Servlet 3.0+, with a shaded `javax` variant and
a no-Boot `TesseraConfiguration`. This is unglamorous, and it is exactly the
work that determines whether the apps that carry the most Thymeleaf CVE risk
can adopt at all. It is a strength only read as a starting line. An app on
Boot 1.5 and Java 8 carries a large and growing exposure in the stack itself,
so the floor should come with a strong push to move forward, not an
endorsement of staying put with a template-engine band-aid on an end-of-life
framework. The migration tooling is what makes that push credible, and section
5.1 argues it should be aimed at the whole stack, not only at Thymeleaf.

**Honesty in the evidence.** The diagnostics catalogue is CI-tested, the guide
examples are compiled, the petclinic port ships a friction log, and the
migration benchmark runs dozens of independent agent migrations and openly
reports where they fail, with hard and held-out apps passing 0 to 1 of 3 on the
stricter page-parity compare. One result stands out: across all 57 runs to date,
every migrated app still builds and passes its own tests, and none weakened
security with an `unchecked` escape or by disabling CSRF. That candor is rare,
and the no-weakened-security result across 57 runs is itself a strong data
point. My one ask: make sure the marketing layer does not sand it off into "AI
migrates your app automatically."

## 4. Risks and gaps, the developer-experience lens

Roughly in priority order.

### 4.1 Gradle is missing, and eventually important

Maven only today, confirmed firsthand: no Gradle files, no build plugin, wiring
is raw `annotationProcessorPaths` plus an absolute `-Atessera.templates` path,
and the Boot 1.5 parent's old compiler-plugin pin has to be overridden by hand.

A large share of Spring Boot apps, especially newer ones, are built with
Gradle. To be clear about my own bias: I am a Maven committer and no fan of
Gradle, so this is not advocacy for the tool. The Spring world's build-tool
history is a mess in its own right, Spring Framework moved its own build from
Maven to Gradle years ago in an early and contentious migration, and both
have stayed first-class for users since, so the ecosystem never really settled
on one. None of that changes the conclusion: Gradle is necessary regardless of
taste, and leaving it out leaves those apps out.

The annotation-processor architecture should make a Gradle path tractable,
since it is the same javac processor, so this is a resourcing decision, not a
design wall. It does not have to be at launch, but first-class Gradle is
eventually important, and I would put it on the near-term roadmap. The layout
dialect, also out of scope in the MVP, is the same kind of cut: a popular
feature that real apps use, so eventually worth supporting even if not now.

### 4.2 Incremental build is a false green, and hot reload is absent

Two problems in the same inner loop.

First, I confirmed that `mvn compile` does not pick up a template-only edit.
After a clean build, I changed a template and recompiled without `clean`; the
generated source was unchanged and the build stayed green. An injected unsafe
expression was caught only by `mvn clean compile`. For a plain developer this is
a confusing inner loop. For a security-positioned tool it is worse: the
guarantee you are selling is enforced on a clean build but silently skipped on
an incremental one, which stays green on stale generated code. CI does clean
builds and the stale class is what runs, so this is not an RCE, but "green
build, guarantee not actually checked" is the kind of surprise a security tool
should not ship with. At a minimum, document it loudly; better, make a
template change trigger reprocessing. This looks tractable, a solvable
build-integration problem rather than a design limit: a Maven plugin or a
staleness check that treats the template files as build inputs would force
the processor to re-run when one changes.

Second, there is no hot reload in the MVP, since devtools is explicitly out.
Thymeleaf's single most-loved property for day-to-day work is edit a template,
refresh the browser. A compiled engine that is slower to iterate on than the
interpreted one it replaces will lose the developer's goodwill in the first
hour, no matter how good the security story is for their boss. This cannot be
phase 2.

*Evidence: the firsthand test in section 2; guide section 2, "Maven doesn't
recompile when only a template changes"; the `mvp-design.md` "Out" list for
devtools and hot reload.*

### 4.3 "Migrate your templates" is not "migrate your app"

The no-ambient-authority principle is the right security call, and it is also
the migration cliff. The guide is honest: anything the old template read from
request, session, application scope, cookies, headers, path variables, or an
implicit fallback must be lifted into the model and declared in `t:params`, and
"nothing warns you when you miss one." You find out when a page differs in
comparison or throws at runtime.

I saw both sides of this firsthand. A full end-to-end migration of a fresh
petclinic went better than the docs suggest: about 35 minutes, almost entirely
mechanical, all 79 tests green, and the converter and codegen bugs the original
port hit are fixed. The template port itself is smooth now. The cliff is not the
templates; it is the controller and model rework on apps that lean on ambient
scope. For CAS, Broadleaf, RuoYi, or any app with a `@ControllerAdvice` web of
ambient values, that is real work, not a mechanical rewrite, and the migration
benchmark confirms it: "difficulty scales with Spring integration, not template
syntax," and the hard and held-out apps pass between 0 and 1 of 3, with the
newest engines, JSP with Tiles, Velocity, and Pebble, among the hardest. The
agent-driven migration promise is strong, but the honest framing is this: the
agent converts templates and finds the gaps, and closing the controller and
model gaps is effort proportional to how much the app leaned on ambient scope.

*Evidence: the full migration in section 2; guide sections 1 and 3;
`bench/migration/RESULTS.md`, the #115 observations and the later held-out and
new-engine rounds.*

### 4.4 A null property becomes a production 500

Thymeleaf renders empty for a null property chain. Tessera throws at render time
on a null intermediate unless the author wrote `?.`, and a null link path
variable fails the render too. So a page that used to show a harmless blank can
become a 500 after migration. I hit this in the fresh petclinic migration: two
controller tests failed because a list fixture held an owner with a null id, and
the link `@{/owners/{ownerId}(ownerId=owner.id)}` throws on the null path
variable where Thymeleaf rendered an empty segment. Parity comparison catches
many of these, but it is a runtime foot-gun that deserves a prominent callout
and maybe a compiler-assisted nudge, for example flagging property chains on
nullable types that lack `?.`.

*Evidence: guide section 1 on null model attributes and section 4.5 on null path
variables; the `bench/migration` jobsearch and fredbet null-related failures,
#119 and #120.*

### 4.5 `@TemplateView` on JPA entities nudges an anti-pattern

The low-friction on-ramp in the docs, and in the petclinic port, is annotating
domain entities such as `Owner`, `Pet`, and `Visit` with `@TemplateView`. That
exposes persistence entities straight to the view, which invites lazy-loading
surprises, N+1, and over-exposure of fields. The cleaner path, building a view
record in the controller, exists and is mentioned, but it is more work, so the
docs lean on annotating entities.

I would flip the emphasis: lead with view models and records as the recommended
shape, and present entity annotation as the pragmatic escape hatch with its
caveats stated. This also strengthens the "templates read data, they don't run
code" principle, since view models carry exactly what the page needs.

*Evidence: guide section 4.2 and petclinic `PORTING.md` step 2, which annotates
six entities.*

### 4.6 The residual attack surface should ship with its guardrails

The honest threat model in `tessera-vision.md` lists the residuals:
`SafeHtml.unchecked`, `TrustedUrl.unchecked`, and registered
`@TemplateFunction`s. "SSTI impossible by construction" is true for the engine.
It is not automatically true for an app that sprinkles `unchecked` or registers
a function that takes a `String` and does something reflective. The stated
mitigation is greppable and lint-bannable, but there is no lint or
function-registry audit shipping yet.

For the security-team buyer this is the crux. The thing they are paying for is
the guarantee, and the guarantee has exactly these holes. I would ship two
things. First, a lint or ArchUnit-style check that bans `*.unchecked` and flags
risky function signatures. Second, a generated function-registry audit report
per build; that is literally the review surface the vision doc says it is, so
hand it to them. It also feeds the verification-evidence column in the
strategy's offer table.

*Evidence: the `tessera-vision.md` threat-model table, its residual rows.*

### 4.7 Fixed sanitizer and CSS policies will push people toward `unchecked`

`SafeHtml.sanitize` and the `css.*` helpers have hard-coded, non-configurable
allowlists. The defaults are sensible. But real content breaks them: stored rich
text that legitimately contains a `<table>`, or a design system that needs
`rgb()` or `var()` in an inline style. When the safe path cannot express a
legitimate need, the tempting exit is `SafeHtml.unchecked` or
`TrustedUrl.unchecked`. That is, the policy pushes users toward the one
dangerous door. There should be a sanctioned, still-safe extension mechanism, a
reviewed custom policy or additional validated `css.*` forms, so the escape
valve is not all-or-nothing.

*Evidence: guide section 4.4, "The policy is fixed," with `rgb(...)` and
`var(...)` not accepted.*

### 4.8 Why Tessera over simply adopting JTE?

The vision doc raises this itself. JTE is compiled and typed, has
content-type-aware escaping, supports both Maven and Gradle, has hot reload, has
a Spring Boot starter, and is Apache-2.0 open source with no commercial strings.
It is now a template option on start.spring.io, which raises the bar for an
incumbent-compatible replacement, and it eliminates SSTI too, because it is
compiled Java with compile-time contextual escaping. Tessera's real
differentiators are Thymeleaf syntax compatibility, the migration tooling, and
Spring form binding through `t:object`, `t:field`, and `t:errors`. Those are
genuine, but a narrower moat than the security pitch implies, because the
security win is not unique to Tessera.

Developers will ask this directly. The crisp answer is probably this: you keep
your Thymeleaf-shaped templates and Spring forms and migrate mechanically,
instead of rewriting into a different template language, plus the backports,
provenance, and migration service wrapper. Worth stating explicitly and early,
including where you would tell someone JTE is the better fit.

There is also a way to stop fighting JTE. Because JTE is itself a safe,
compiled, SSTI-free engine, it could be a migration target for the same
tooling, not only a rival. The valuable asset is the agent that moves a
Thymeleaf app onto a safe engine; where it lands is a choice. Pointed at JTE it
delivers a free, open engine with Gradle and hot reload; pointed at Tessera it
keeps the Thymeleaf-shaped templates and Spring form binding. That puts the
business on the migration, provenance, and backports rather than on beating
JTE, and it fits leaning the engine commercial per section 5.5: you sell the
migration and the maintained supply chain whichever destination a customer
picks.

*Evidence: `tessera-vision.md`, the trade-offs section; `research.md` section 3,
the prior-art table; and jte.gg, with the JTE features here confirmed against
the current docs in October 2026.*

### 4.9 IDE support is absent

Typed `t:params` make a language server tractable, as the vision says, but it is
out of the MVP. Without editor completion and inline errors, the "better in the
IDE than Thymeleaf" promise is unmet, and the compile-cycle friction from
section 4.2 bites harder because errors surface only at build. For a tool whose
whole pitch is that errors move to build time, giving the developer those errors
in the editor is the natural payoff.

*Evidence: the `mvp-design.md` "Out" list for the language server.*

## 5. Beyond the prototype: adoption and strategy

This section goes past what you asked. You shared the prototype and the MVP
design; this is about `strategy.md` and the business around it. It is just my
two cents, offered as input, not part of the review you asked for.

### 5.1 The security win is narrow, and the positioning fights rolling forward

Removing SSTI closes one window, but an app's largest exposure is usually the
stack it sits on, not its template engine. The guide tells you not to upgrade
Spring during the migration and to keep Boot 1.5, which leaves the app on an
end-of-life, unsupported framework with its own stream of CVEs. This is the part
I want to push hardest on, because it runs against the rolling-forward model I
set out in the [rolling-everything whitepaper][wp] and the [leadership vision
memo][vision]: pinning to an old version does not freeze risk, it accumulates
it, and LTS-style stasis is the problem, not a safe harbor. A migration that
lands an app on Tessera but leaves it on Boot 1.5 has made the pinning more
comfortable, which is the opposite of the goal.

The Java 8 and Boot 1.5 floor is excellent for adoption and double-edged for
security: it lets a security-conscious buyer feel safer while staying on the
runtime that is the bigger risk. The portfolio strategy answers this for
libraries such as YAML, XML, and serialization, but not for the large
frameworks, Spring and Hibernate, that carry most of the surface.

The reconciliation is the migration capability, and it is a strong one. The same
agent that moves an app from Thymeleaf to Tessera is an instance of exactly the
tooling that makes rolling forward tractable: dependency bumps, API migrations,
and caller refactors at AI speed. Aim it at the whole stack, Boot 1.5 to 3.x,
`javax` to `jakarta`, end-of-life Java to current, and Tessera becomes the
on-ramp to rolling rather than a way to stay still. That fits where Chainguard
already runs the rolling model, in containers and OS packages. I would give the
migration tooling top billing, treat the safe libraries as part of what it
carries an app toward, and be explicit that template SSTI is one bug class among
many.

I would make that ambition explicit and larger than template plus framework.
Once the agent can bump a dependency and fix the fallout, the target is every
library the app pulls in, carried to its latest version and kept there, not
moved once and left. Two Chainguard capabilities slot straight in. Default the
rebuilt dependencies to Chainguard Libraries, so a migrated app gains
provenance and a maintained supply chain, not only newer version numbers. Where
a jump to latest is not yet safe, lean on the CVE backports Chainguard ships in
those libraries, so the app is covered on its current version while the upgrade
is staged. Then treat the tooling itself as the durable asset: each release
should migrate more libraries, close more of the gap to latest on its own, and
run on a schedule rather than as a one-time port. That turns Tessera from a
template migration into the on-ramp for keeping a whole application current,
my rolling-everything thesis made operational.

Step back and the priority inverts. The Thymeleaf replacement is the smaller,
less important part of this. Building a bespoke, safe-by-construction
replacement is expensive: a new compiler, a new runtime, and a migration path,
per library. That is justified for Thymeleaf, where the bug class is severe and
the incumbent is everywhere, and a handful of other high-value targets might
earn the same treatment. But the approach does not scale. Far more libraries
carry CVE risk than anyone can replace one engine at a time, and trying would
recreate the unbounded, per-project maintenance cost that replacement was
supposed to escape. For the few cases where it is worth doing, keeping the
replacement commercial or private is defensible, because each one is a
high-touch, bespoke build, not a community framework.

The effort that scales is the upgrade tooling. The general capability to roll
an application's whole dependency set forward, adopt rebuilt libraries with
provenance, and fall back to CVE backports where a jump is not yet safe,
applies to every dependency, not one at a time. So I would limit the
replacement play to a few projects and put most of the investment there. This
also lines up with the direction Tyler is building toward in Guardener, which I
proposed when he onboarded and have since talked through with him and others as
part of the rolling-forward idea.

### 5.2 No community is a first-class risk for a framework

A template engine is one of the stickiest dependencies a team takes on.
Migrating off one is expensive, so teams adopt conservatively and weight
longevity, community, and governance as heavily as features. Thymeleaf's real
moat is not its syntax; it is the ecosystem: years of answers and tutorials,
IDE support, third-party dialects, and the knowledge that the next developer
you hire has already used it. Tessera has none of that yet. "Familiar
before novel" lowers the learning curve but does not substitute for a community.
For most enterprises, being the first external adopter of a framework with a bus
factor of one organization is a stop on its own.

### 5.3 Source-only publication is not really open source to a Java dev

The strategy publishes source to public channels but keeps release builds in the
Chainguard registry. For a Java library that is a meaningful gap. The ecosystem
norm is versioned artifacts on Maven Central, consumed with a one-line
coordinate. If I can read the code but cannot pull a released binary without a
subscription, that reads as source-available, not open source, regardless of the
OSI license. Today the repo ships `0.1.0-SNAPSHOT` through a local `mvn
install`, which is fine for a pilot but underlines that there is no frictionless
open-source consumption path. If broad adoption ever matters, release binaries
need to be on Central.

### 5.4 Single-vendor control and commercial intent trigger the rug-pull reflex

The strategy is explicit. Chainguard owns the trademark, is the sole upstream,
and the value is bundled into a paid subscription with broad free adoption a
secondary goal. After the last few years of high-profile relicensings across
Terraform, Redis, Elastic, and HashiCorp, enterprises and open-source
communities are primed to ask what stops this from being relicensed or paywalled
once they depend on it. The honest, upfront answer needs to be part of the
pitch: a stable OSI license commitment, a clear line on what stays free versus
paid, and ideally governance that is not just trust us. The reassuring part is
that Chainguard's own direction, selling the service of providing open source
kept current rather than a patched fork, is the natural answer to this worry,
but it only reassures if it is said out loud in Tessera's positioning.

### 5.5 Low, commercial-only adoption is probably the right call

For most of the portfolio, a YAML parser or an archive extractor, the motion
"the security team buys it, few developers care" is fine, because those are
drop-in library swaps. A template engine is different: it is a developer-facing
framework, and the usual argument is that it lives or dies on a community
producing examples, answers, plugins, and talent. That argument is real, and
§5.2 is the cost of going without it. My own view, though, is that low,
commercial-only adoption is probably the right call here anyway. Wide adoption
is a long shot for a single-vendor framework entering a field that already has
Thymeleaf and JTE, and the sustained effort to earn it, docs, talks, plugins,
and community management, is large and easy to underestimate. Doing a few
migrations extremely well and selling the result is a better bet than spending
that effort to win a crowd that may never come. The community path is genuine,
and §5.6 is honest about what it would take; I just would not take it.

[EmeritOSS][emeritoss] is the clearest in-house proof that the low-adoption
motion can be exactly right: those forks are single-vendor, source-available,
commercially distributed, with contribution closed and no community, and that
is correct because the goal is to keep a wound-down project safe while users
migrate off it. The same model could be a valid choice for Tessera too, and
might even be the better one. The point is not that Tessera must chase
adoption, but that running the EmeritOSS motion here, or not, is a strategic
choice to make deliberately and agree on, not a default inherited from the
rest of the portfolio.

### 5.6 What would move the needle

If adoption is the goal after all, this is the floor. I lean the other way, per
section 5.5, but the case deserves its strongest version, so here it is.

* Publish versioned release binaries to Maven Central, and keep provenance,
  backports, and migration as the paid layer.
* A public, written longevity and licensing commitment: what stays free, license
  stability, and support horizon.
* A real docs site and a handful of runnable sample apps beyond petclinic.
* Seed the knowledge base through conference talks, blog posts, and an IDE or
  language-server plugin.
* A visible contribution path and public roadmap, so it reads as a project, not
  a product drop.
* Consider an open-source-friendly tier, free builds for open-source projects
  and individuals, to seed the flywheel the security-team motion will not.

**Fair counterweight.** Chainguard has real credibility in the security and
supply-chain space, the source is genuinely open under an OSI license so a
community can form, the migration tooling lowers the switching cost that
normally freezes template-engine decisions, and the honest benchmarking builds
the kind of trust early adopters need. The raw material for a community is
there; the strategy just does not currently prioritize creating one.

## 6. Smaller notes

* **JDK 27 build.** As in section 2, the full test build fails only in
  `LombokTest` because test-scope Lombok cannot initialize on JDK 27. Worth a
  README line that names the tested JDK, and worth watching, since Lombok
  lagging new JDKs will keep biting contributors on the latest release.
* **Benchmarks of 7 to 21 times are a nice "safe and faster" hook**, but keep
  the framing honest: steady-state render with Thymeleaf caching on, synthetic
  pages, and a single-machine run. Do not let a deck turn "21 times on a
  fragment-heavy micro page" into "21 times faster app."
* **`tessera-migrate` ergonomics.** `java -jar` works now, which is good. The
  petclinic log records earlier converter misses, such as literal tokens and
  `#strings.listJoin`. First-run polish matters, since this is the demo surface.
* **Naming.** "Tessera" is flagged as a placeholder, which is fine, but pick
  early. The trademark and "built from X source" policy depends on a settled
  name.
* **Error-page `timestamp` is a `Date`, not view-safe.** Small, but it is the
  kind of papercut, in guide section 2 on error pages, that every Boot app hits
  immediately. A view-safe wrapper or a documented recipe would help.
* **A few Thymeleaf idioms break in the mechanical port and surface as diffs.**
  A null attribute is omitted where Thymeleaf emits `value=""`, and `*{firstName
  + ' ' + lastName}` has to become `*{firstName} + ' ' + *{lastName}` because
  `*{...}` takes a single path. Minor, but a diff-watcher or a first-time
  migrator trips on them.
* **The guide's build snippet hardcodes `<release>21</release>`.** Under a
  Spring Boot parent that manages the release, it should be left out; the
  migration run worked by leaving release to the parent and adding only the
  processor wiring. Worth correcting in the guide.

## 7. Open threads to still work through

An iteration backlog. Flag which matter most.

* [ ] Run a hard or held-out app end-to-end. Petclinic is done and came out
  green; the easy case does not exercise the controller-rework cliff. I looked
  for Thymeleaf apps the current benchmark set does not already cover, and
  there is genuinely new coverage to add, with caveats. Candidates with clean
  licenses:
  * `thymeleafexamples-stsm` (Apache-2.0): dense dynamic form binding, indexed
    `th:field` with preprocessing, `#ids`, and the `${{...}}` conversion
    service, none of which petclinic exercises.
  * `thymeleafexamples-springmail` (Apache-2.0): TEXT-mode templates and the
    `data-th-*` attribute syntax, template modes an HTML-only app never hits.
  * `thymeleaf-tests` (Apache-2.0): 3000+ isolated fixtures including an
    `accessrestrictions` negative-security set that lines up with Tessera's
    allowlist and no-ambient-authority rules.
  * `AnanthaRajuC/Spring-Boot-Application-Template` and
    `mdbytes/SpringMVCWebApp` (MIT), and `gussttaav/g-commerce-thymeleaf` (MIT,
    Thymeleaf plus HTMX): heavy form binding, security dialect, and i18n in
    real apps.
  Caveats: several of the richest apps lean on the layout dialect, which
  `mvp-design.md` lists as out of scope today, though it is a popular feature
  worth supporting eventually, so they would test that `tessera-migrate`
  reports cleanly what it cannot convert, not a successful migration;
  `thymeleaf-tests` encodes Thymeleaf's runtime semantics and error strings, so
  it is an input-coverage and oracle source, not a drop-in
  byte-exact golden; and I dropped a few otherwise-good apps that ship no
  license. I am happy to open a follow-up PR wiring the licensed ones in as
  `bench/migration` apps and golden fixtures if that is useful.
* [ ] Migration-as-modernization, and more ambitious: frame the agent as the
  on-ramp to rolling the app forward, not only off Thymeleaf and Boot 1.5.
  Carry every library to latest where safe, default the rebuilt dependencies to
  Chainguard Libraries for provenance, lean on their CVE backports where a jump
  is not yet safe, and invest in the tooling so each release upgrades more and
  runs on a schedule. This is the thread that connects Tessera to my
  rolling-everything thesis.
* [ ] Roadmap sequencing: which of Gradle, hot reload, the incremental-build
  fix, and the language server are launch gates versus fast-follows?
* [ ] Go-to-market: does a developer tool sold through a security-team motion
  need a developer-adoption motion in parallel?
* [ ] Open-source consumption model: are release binaries going to Maven
  Central, or is source-only final?
* [ ] A public licensing and longevity commitment to defuse the relicensing
  reflex. Is Chainguard willing to make one, and in what form?
* [ ] Positioning versus JTE, Rocker, JStachio, and Qute in one honest
  comparison page. JTE especially, now that it is on start.spring.io, including
  whether JTE is a migration target, not only a rival, per section 4.8.
* [ ] Size the Thymeleaf install base and its trend, via Maven Central
  downloads, GitHub dependents, and start.spring.io selection telemetry, to
  ground the adoption argument rather than assert it.
* [ ] Anything I have gotten wrong or been unfair about.

[wp]: https://docs.google.com/document/d/10fsNKCEuVvDw_EaQkxfmkW_bPwj-yGSJ5MaHjFn0pI8/edit
[vision]: https://docs.google.com/document/d/12oFbPplrnHr4nKRHT0biZibS_IoZeWTYSGnxv2_0LSw/edit
[emeritoss]: https://github.com/chainguard-forks
