# harder

Prototypes for Chainguard's secure-by-design library portfolio ([strategy](docs/strategy.md)).

## Tessera

A compiled, safe-by-design successor to Thymeleaf. Templates are natural HTML with `t:`
attributes and a typed `t:params` header. They compile at build time (annotation processor) to
plain Java. There is no runtime template parser, no reflection, a closed expression language
over an allow-list of view-safe types, and contextual autoescaping. Server-side template
injection is impossible by construction.

| Module | What it is |
|---|---|
| `tessera-runtime` | Zero-dependency runtime: escapers, safe types, links, messages |
| `tessera-compiler` | Template compiler and annotation processor |
| `tessera-spring-boot-starter` | Spring MVC view resolution, forms, CSRF, i18n, `auth` functions |
| `tessera-migrate` | Thymeleaf to Tessera converter with an unconvertible-construct report |
| `tessera-benchmarks` | JMH benchmarks against Thymeleaf ([results](tessera-benchmarks/RESULTS.md)) |
| `examples/petclinic` | Spring PetClinic ported to Tessera ([notes](examples/petclinic/MIGRATION-NOTES.md)) |

- Using and migrating to Tessera: [docs/guide.md](docs/guide.md)
- MVP design: [docs/mvp-design.md](docs/mvp-design.md); long-term vision: [docs/tessera-vision.md](docs/tessera-vision.md)
- Contributing (humans and agents): [AGENTS.md](AGENTS.md)

Build: `mvn -B -ntp verify` (JDK 21+). CI runs on Chainguard Checks (`.chainguard/ci/`).
