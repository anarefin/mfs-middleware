# D14: No shared code: contracts only, cross-cutting code copied from a template

Status: Accepted, 2026-11-03 (spec revision 2). Amended 2026-11-13: modules are subprojects of one
multi-module root build (D13); still no shared build logic, versions inline per module.

## Context

Shared libraries couple release cycles and leave a module impossible to move to its own repo.

## Decision

No library, common, starter or test-kit module. Services share only contract files (JSON Schema, OpenAPI) in the root `contracts/` folder, which holds no code; each module reads it only in tests and build-time checks through the `mwContractsDir` property. Cross-cutting code (correlation, error envelope, masking, logging, outbox, inbox, Kafka retry) is copied from `templates/mw-service-template`, never depended on.

Build logic is not shared either. Originally each module also had its own wrapper, settings and
version catalog. Since 2026-11-13 (D13, BitsPay pattern, one daemon and the build cache, user
decision) the modules are subprojects of one root build, which only lists them and runs the repo
checks: nothing is applied to a module from outside, and each module writes its dependency versions
inline in its own `build.gradle.kts`. The one shared constraint is Gradle's: a plugin has one
version in the whole build, so all modules use the same plugin versions.

## Consequences

- Duplicated chassis code is intended; a template fix is rolled into each service as its own change.
- Each contract file has one owner (`contracts/OWNERS.yml`); only the owner changes it.
- Each module declares what it produces and consumes in `contracts.yml`, checked by Docker-free
  tests. Originally the `verify-changed` gate used it to test only a change's contract neighbours;
  since 2026-11-13 the build cache does that job: `./gradlew clean build` re-runs only the modules
  whose inputs (sources, `contracts.yml`, the contract files they read) changed.

## Where in the repo

- `contracts/README.md`, `contracts/OWNERS.yml`
- `templates/mw-service-template`
- `services/mw-notify/contracts.yml` (a manifest)
- spec section 4.3
