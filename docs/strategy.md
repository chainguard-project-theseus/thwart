# Secure-by-Design Library Portfolio: Strategy

Oct 7, 2026 · Dan Lorenc

## Summary

Chainguard becomes the upstream for safe-by-design replacements of the libraries that generate the most CVEs, and sells the result to enterprises inside the existing subscription. Each library removes a whole bug class by design rather than patching instances of it.

The libraries are open source and published as source. Builds, backports, provenance and agent-driven migration come through the Chainguard registry. The buyer is the enterprise security team, not the individual developer, so broad free adoption is a secondary goal.

The premise: frontier models make vulnerability discovery cheap. CVE volume in legacy libraries will outrun any team's ability to patch, and eliminating bug classes becomes the only defense that scales. Tessera, a compiled replacement for Thymeleaf, is the pilot.

## Why now

When finding vulnerabilities gets cheap, patching stops scaling and design becomes the only lever that does.

- **Discovery cost is collapsing.** Frontier models can audit large codebases for known bug patterns far faster than human researchers. Libraries whose design invites a bug class (reflection-heavy template engines, polymorphic deserializers, permissive parsers) will produce CVEs faster than maintainers and customers can triage them.
- **The patch treadmill gets worse, not better.** Each new finding means a scanner alert, a triage meeting, an upgrade, and often a compatibility break.
- **Enterprises will buy outcomes.** The attractive promise to a security leader is not faster patches but a bug class that disappears from their SBOM.
- **Building and migrating got cheaper too.** AI-assisted development makes a large portfolio of new libraries feasible, and coding agents can port customer code onto them.

## The offer

Bundled into the existing Chainguard subscription; not a new SKU.

| Component | What the customer gets |
|---|---|
| Builds | Release builds from the Chainguard registry, with signed provenance and SBOMs |
| Bug-class elimination | A documented statement of which vulnerability classes each library rules out by design, and how |
| Backports | Fixes for the rare CVE backported to older supported versions |
| Verification evidence | Published results of the pre-release audit for each version |
| Agent-driven migration | Automated porting from the incumbent library, with a report of what changed and what needs review |
| Compliance mapping | A mapping from each library's guarantees to OWASP and common audit frameworks |

The sales message is "this class of bug leaves your SBOM," measured against the incumbent's CVE history.

## Distribution and licensing

- **License.** Each library uses a standard OSI-approved license.
- **Source-only publication.** Chainguard publishes source, not builds, to public channels. Release builds live in the Chainguard registry.
- **Trademarks.** Chainguard owns each library's name. One trademark policy covers the portfolio and explicitly allows "built from Tessera source."

| Ecosystem | How people consume libraries | Where subscription value comes from |
|---|---|---|
| Java, .NET | Binary artifacts from a repository | Builds, provenance, backports, migration |
| Go | Source modules | Provenance, curated versions, backports, migration |
| Rust | Source crates | Same as Go |
| Python, JavaScript | Mostly source; some native builds | Provenance and backports; native builds |

Launch in Java and .NET; source-distributed ecosystems are a second phase.

## Choosing libraries

Each candidate is scored 1-5 on CVE burden, design leverage, replaceability, and customer pull.

| Category | Recurring bug class | Incumbent examples | Design that removes it |
|---|---|---|---|
| Server-side templates | Template injection leading to code execution | Thymeleaf, FreeMarker, Velocity | Compile-time templates, no reflection (Tessera) |
| YAML parsing | Unsafe deserialization into arbitrary types | SnakeYAML | Parse to plain values only; typed binding via allow-listed schema |
| Object serialization | Gadget-chain deserialization | XStream, polymorphic JSON modes | No type names in data; explicit, generated binders |
| XML parsing | External entity expansion, entity bombs | JDK default parsers | No DTD or entity support by default; bounded input |
| Archive extraction | Zip slip, decompression bombs | Hand-rolled extraction | Extraction API that only writes inside a target root, with size limits |
| Expression languages | Injection into embedded expression evaluation | SpEL, OGNL, MVEL | Closed grammar over values; no class access |
| Logging | Lookups or formatting that evaluate input | Log4Shell-style lookups | Structured logging; parameters never interpreted |

## Verification standard

- **Adversarial model audit** before each release; findings block the release.
- **Continuous fuzzing** of parsers, escapers and interpreters.
- **Formal verification where it pays** (escapers, URL scheme checks, path normalization).
- **Shared safety core**: safe types, contextual escapers, sandbox runtime and parser tooling built once and reused.
- **Published evidence** per release.

The external claim is "no engine-level code execution by design, verified each release," not "no CVEs."

## Agent-driven migration

An agent ports a customer's code from the incumbent to the portfolio library (templates, call sites, configuration, tests), opens a reviewable change, and reports converted code, unconvertible constructs, and behaviour changes needing review. The customer's test suite runs before and after; rendered template output is compared on sample data.

## First steps

1. Pull customer data on template-engine findings.
2. Build the shared safety core first (safe types, contextual escapers).
3. Ship Tessera with the full offer.
4. Measure migration time, findings removed, developer satisfaction.
5. Pick the next three categories.
