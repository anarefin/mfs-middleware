# 01: Repo skeleton and local quality gate

**What to build:** A developer or coding agent can clone the repo and run the local quality gate. The root is a Gradle (Kotlin DSL) composite build used only for convenience: one `includeBuild` per module, no shared configuration, no `buildSrc` and no convention plugins (D13, D14). The Makefile offers `up`, `verify`, `e2e`, `perf`, `security`, `new-service`, `check-isolation`, `contracts-lint` and `versions-check`. Each target works for one module (`M=<module>`) and for all modules. The repo root `CLAUDE.md` records the conventions: layered packages (4.4), error model (5.3), masking (18.3), log fields (21.1), test commands and the "never" rules. A PR template has a test-report section. Spec WP01.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] `make verify` runs (and passes) against an empty module set and against a single dummy module
- [ ] `make check-isolation` fails on a planted cross-module Gradle dependency, an `includeBuild` inside a module, a convention plugin, or a relative path outside the module. The only path it allows is the root `contracts/` folder
- [ ] `make versions-check` reports version drift between two modules' version catalogs
- [ ] Root `CLAUDE.md` lists package layout, error model, masking, log fields, test commands and the never-rules (no secrets in code, no `System.out`, no field injection, no blocking on Kafka threads)
- [ ] PR template exists with a test-report section and a checklist for the reviewer re-running `make verify`
